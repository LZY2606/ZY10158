package app

import java.util.concurrent.atomic.AtomicLong

/** Injectable monotonic clock so every time-dependent behavior is deterministic in tests/demo. */
interface VirtualClock {
    fun nowMs(): Long
}

class SystemVirtualClock(private val baseMs: Long = System.currentTimeMillis()) : VirtualClock {
    private val start = System.nanoTime()
    override fun nowMs(): Long = baseMs + (System.nanoTime() - start) / 1_000_000L
}

class ManualClock(initial: Long = 0L) : VirtualClock {
    private val t = AtomicLong(initial)
    override fun nowMs(): Long = t.get()
    fun advance(deltaMs: Long) {
        require(deltaMs >= 0) { "virtual clock cannot move backwards" }
        t.addAndGet(deltaMs)
    }
    fun set(value: Long) {
        require(value >= t.get()) { "virtual clock cannot move backwards" }
        t.set(value)
    }
}

class ConflictView(val message: String)

class AnalysisService(
    private val repo: Repository,
    @Volatile var clock: VirtualClock,
) {
    /** Switches to the deterministic manual clock (optionally seeding it) and returns it. */
    fun useManualClock(seedMs: Long? = null): ManualClock {
        val current = clock as? ManualClock
        if (current != null) {
            if (seedMs != null) current.set(seedMs)
            return current
        }
        return ManualClock(seedMs ?: 0L).also { clock = it }
    }

    fun useSystemClock(): SystemVirtualClock = SystemVirtualClock().also { clock = it }
    /** Re-runs reassembly from L0 + current L2 review inputs and atomically publishes a new run. */
    fun rerun(captureId: Long): ParserRun {
        val capture = repo.getCapture(captureId) ?: throw IllegalArgumentException("capture $captureId not found")
        val frames = repo.loadFrames(captureId)
        val directives = repo.loadDirectives(captureId)
        val marks = repo.listRestartMarks(captureId)
        val reviews = frames.mapNotNull { repo.getFrameReview(it.id) }.associateBy { it.frameId }
        val attributions = frames.let {
            // attribution reviews are keyed by message local key; load all for the capture
            loadAllAttributionReviews(captureId)
        }

        // End of the capture timeline is the last frame's timestamp on the virtual clock:
        // transfer timeouts are always judged against captured timestamps, never wall-clock.
        val timelineEnd = frames.maxOfOrNull { it.offsetMs } ?: clock.nowMs()
        val result = Analyzer(captureId).analyze(
            AnalyzerInputs(frames, directives, marks, reviews, attributions),
            virtualNowMs = timelineEnd
        )
        return repo.publishRun(
            captureId = captureId, reviewVersion = capture.reviewVersion,
            messages = result.messages, diagnostics = result.diagnostics, nowMs = clock.nowMs()
        )
    }

    fun loadAttributionReviewsPublic(captureId: Long): Map<String, AttributionReview> =
        loadAllAttributionReviews(captureId)

    private fun loadAllAttributionReviews(captureId: Long): Map<String, AttributionReview> {
        val out = HashMap<String, AttributionReview>()
        repo.db.conn.prepareStatement(
            "SELECT * FROM attribution_reviews WHERE capture_id = ?"
        ).use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val key = rs.getString("message_local_key")
                    out[key] = AttributionReview(
                        messageLocalKey = key,
                        choiceLocalKey = rs.getString("choice_local_key"),
                        keepBoth = rs.getInt("keep_both") == 1,
                        note = rs.getString("note"),
                        version = rs.getLong("version"),
                        updatedAtMs = rs.getLong("updated_at_ms"),
                    )
                }
            }
        }
        return out
    }

    /** Imports a capture text and immediately publishes run #1, both in one service step. */
    fun import(name: String, content: String): Pair<Capture, Boolean> {
        val parsed = CaptureParser.parse(name, content)
        val (capture, created) = repo.importCapture(parsed, clock.nowMs())
        if (created) rerun(capture.id)
        return capture to created
    }
}
