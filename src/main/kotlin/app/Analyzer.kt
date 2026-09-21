package app

data class AnalyzerInputs(
    val frames: List<RawFrame>,
    val directives: List<RestartDirective>,
    val marks: List<RestartMark>,
    val frameReviews: Map<Long, FrameReview>,
    val attributionReviews: Map<String, AttributionReview>,
)

/**
 * Full pipeline for one capture:
 *   generations -> role overrides / rejection -> ISO-TP reassembly -> request/response pairing.
 */
class Analyzer(
    private val captureId: Long,
    private val p2TimeoutMs: Long = P2_TIMEOUT_MS,
    private val nBs: Long = N_BS_TIMEOUT_MS,
    private val nCr: Long = N_CR_TIMEOUT_MS,
) {
    fun analyze(inputs: AnalyzerInputs, virtualNowMs: Long): EngineResult {
        val boundaries = collectBoundaries(inputs.directives, inputs.marks)
        val effective = assignGenerations(inputs.frames, inputs.frameReviews, boundaries)
        val result = ReassemblyEngine(captureId, nBs, nCr).run(effective, virtualNowMs)
        return Pairing(captureId, p2TimeoutMs, inputs.attributionReviews).pair(result, virtualNowMs)
    }

    data class Boundary(val offsetMs: Long, val scopeCanId: Int?)

    fun collectBoundariesPublic(
        directives: List<RestartDirective>, marks: List<RestartMark>
    ): List<Boundary> = collectBoundaries(directives, marks)

    private fun collectBoundaries(
        directives: List<RestartDirective>, marks: List<RestartMark>
    ): List<Boundary> {
        // A file directive and a human mark at the same (offset, scope) describe the same ECU
        // event and must not bump the generation twice.
        val unique = LinkedHashSet<Pair<Long, Int?>>()
        directives.forEach { unique += it.offsetMs to it.scopeCanId }
        marks.forEach { unique += it.offsetMs to it.scopeCanId }
        return unique.map { Boundary(it.first, it.second) }
            .sortedWith(compareBy({ it.offsetMs }, { it.scopeCanId == null }))
    }

    /**
     * Assigns ECU generations. A boundary with a CAN-ID scope bumps that exact ID; an unscoped
     * boundary bumps every ECU-related diagnostic ID seen on the bus. Generation counts are
     * per CAN ID, and the frame exactly at a boundary offset starts the new generation
     * (half-open [boundary, +inf)).
     */
    fun assignGenerationsPublic(
        frames: List<RawFrame>,
        frameReviews: Map<Long, FrameReview>,
        boundaries: List<Boundary>,
    ): List<EffectiveFrame> = assignGenerations(frames, frameReviews, boundaries)

    internal fun assignGenerations(
        frames: List<RawFrame>,
        frameReviews: Map<Long, FrameReview>,
        boundaries: List<Boundary>,
    ): List<EffectiveFrame> {
        // Pre-compute, per CAN ID, the distinct applicable boundary offsets. A frame belongs to
        // generation n when exactly n applicable boundaries satisfy boundaryOffset <= frameOffset
        // (half-open [boundary, +inf)); the frame exactly at a restart offset starts the new life.
        val genCountById = HashMap<Int, List<Long>>()
        fun applicableFor(canId: Int): List<Long> {
            genCountById[canId]?.let { return it }
            val offsets = boundaries
                .filter { it.scopeCanId == null || it.scopeCanId == canId }
                .map { it.offsetMs }.distinct().sorted()
            genCountById[canId] = offsets
            return offsets
        }
        return frames.map { f ->
            val review = frameReviews[f.id]
            val dir = review?.roleOverride ?: f.direction
            val gen = applicableFor(f.canId).count { f.offsetMs >= it }
            EffectiveFrame(f, dir, review?.rejected == true, gen, false)
        }
    }
}

private data class CandidateReq(val message: Message, val endMs: Long)

/**
 * Request/response pairing with replay and ambiguity handling.
 *
 * Only successfully reassembled messages on standard UDS physical IDs participate.
 * A response may pair with a complete request on the peer CAN ID, same ECU generation,
 * whose transfer finished before the response started and within P2 (half-open [0, P2]).
 * When more than one such request exists (duplicate/replayed requests), the two most
 * recent candidates are retained for human attribution instead of guessing silently.
 */
class Pairing(
    private val captureId: Long,
    private val p2TimeoutMs: Long,
    private val attributionReviews: Map<String, AttributionReview>,
) {
    private val extraDiagnostics = ArrayList<Diagnostic>()

    fun pair(result: EngineResult, virtualNowMs: Long): EngineResult {
        val messages = result.messages
        val requests = messages.filter {
            it.status == MessageStatus.COMPLETE &&
                IsoTp.isRequestId(it.canId) &&
                it.direction == Direction.TX &&
                it.payload.isNotEmpty()
        }
        // Only standard UDS physical response IDs participate in pairing at all; ISO-TP
        // traffic on other IDs is reported by the reassembly layer, not as pair orphans.
        val responseIds = 0x7E8..0x7EF
        fun reqService(m: Message) = m.payload[0].toInt() and 0xFF
        val responses = messages.filter {
            it.status == MessageStatus.COMPLETE &&
                it.canId in responseIds &&
                it.direction == Direction.RX &&
                it.payload.isNotEmpty()
        }

        val pairedRequests = HashSet<String>()
        val updated = LinkedHashMap<String, Message>()
        for (m in messages) updated[m.localKey] = m
        fun responseSid(m: Message) = IsoTp.responseServiceId(m.payload)

        // Process responses in arrival order; a request already consumed by an earlier response
        // cannot be claimed again, which neutralizes replayed/stale responses.
        for (resp in responses.sortedBy { it.startMs }) {
            val peerId = IsoTp.peerCanId(resp.canId)!!
            val sid = responseSid(resp)
            val candidates = if (sid == null) emptyList()
            else requests.asSequence()
                .filter { it.canId == peerId && it.gen == resp.gen }
                .filter { it.endMs <= resp.startMs }
                .filter { resp.startMs - it.endMs <= p2TimeoutMs }
                .filter { reqService(it) == sid }
                .filter { it.localKey !in pairedRequests }
                .sortedByDescending { it.endMs }
                .take(2)
                .toList()

            when {
                candidates.isEmpty() -> {
                    // Same SID seen on that ECU at all, but no live request: this is a stale replay.
                    val everSeen = messages.any {
                        it.canId == peerId && it.direction == Direction.TX && it.gen == resp.gen
                            && it.status == MessageStatus.COMPLETE
                            && it.payload.isNotEmpty() && (it.payload[0].toInt() and 0xFF) == sid
                    }
                    val sidHex = sid?.toString(16) ?: "?" 
                    val code = if (everSeen) DiagnosticCode.PAIR_REPLAY_SUSPECT else DiagnosticCode.PAIR_ORPHAN_RESPONSE
                    val detail = if (everSeen)
                        "response SID $sidHex has no open request within P2=${p2TimeoutMs}ms in gen ${resp.gen}; stale/replayed ECU response left unpaired"
                    else
                        "response SID ${sid?.toString(16) ?: "?"} without any matching request in gen ${resp.gen}; left unpaired"
                    extraDiagnostics += Diagnostic(
                        captureId = captureId, code = code,
                        severity = Severity.WARNING,
                        offsetMs = resp.startMs, canId = resp.canId, direction = Direction.RX,
                        gen = resp.gen, frameSeq = resp.firstFrameSeq,
                        messageLocalKey = resp.localKey, detail = detail
                    )
                }

                candidates.size >= 2 -> resolveAmbiguous(resp, candidates, updated, pairedRequests)
                else -> bind(resp, candidates.first(), updated, pairedRequests)
            }
        }

        for (req in requests) {
            if (req.localKey !in pairedRequests) {
                val waited = virtualNowMs - req.endMs
                if (waited > p2TimeoutMs) {
                    extraDiagnostics += Diagnostic(
                        captureId = captureId, code = DiagnosticCode.PAIR_UNANSWERED_REQUEST,
                        severity = Severity.WARNING, offsetMs = req.endMs + p2TimeoutMs,
                        canId = req.canId, direction = Direction.TX, gen = req.gen,
                        frameSeq = req.lastFrameSeq, messageLocalKey = req.localKey,
                        detail = "no response within P2=${p2TimeoutMs}ms (half-open [0,$p2TimeoutMs]); SID ${(req.payload[0].toInt() and 0xFF).toString(16)} unanswered"
                    )
                }
            }
        }

        return result.copy(
            messages = updated.values.sortedWith(compareBy({ it.startMs }, { it.firstFrameSeq })),
            diagnostics = result.diagnostics + extraDiagnostics
        )
    }

    private fun bind(
        resp: Message, req: Message,
        updated: LinkedHashMap<String, Message>,
        pairedRequests: HashSet<String>,
    ) {
        updated[resp.localKey] = resp.copy(pairedMessageId = req.id, sid = resp.payload[0].toInt() and 0xFF)
        updated[req.localKey] = req.copy(pairedMessageId = resp.id, sid = req.payload[0].toInt() and 0xFF)
        pairedRequests += req.localKey
    }

    /**
     * Two plausible requests precede one response. The machine default keeps both candidate IDs
     * on the response and tentatively binds the most recent one; the L2 attribution review may
     * confirm that choice, pick the older candidate, or deliberately keep both sessions.
     */
    private fun resolveAmbiguous(
        resp: Message, candidates: List<Message>,
        updated: LinkedHashMap<String, Message>,
        pairedRequests: HashSet<String>,
    ) {
        val newest = candidates.first()
        val review = attributionReviews[resp.localKey]
        val sid = IsoTp.responseServiceId(resp.payload)

        if (review == null) {
            updated[resp.localKey] = resp.copy(
                pairedMessageId = newest.id, sid = sid ?: (resp.payload[0].toInt() and 0xFF),
                candidateMessageIds = candidates.map { it.id.toInt() }
            )
            updated[newest.localKey] = newest.copy(pairedMessageId = resp.id, sid = newest.payload[0].toInt() and 0xFF)
            pairedRequests += newest.localKey
            extraDiagnostics += Diagnostic(
                captureId = captureId, code = DiagnosticCode.PAIR_AMBIGUOUS,
                severity = Severity.WARNING, offsetMs = resp.startMs, canId = resp.canId,
                direction = Direction.RX, gen = resp.gen, frameSeq = resp.firstFrameSeq,
                messageLocalKey = resp.localKey,
                detail = "response SID ${sid?.toString(16) ?: "?"} matches 2 open requests (frame seq ${candidates.joinToString { it.firstFrameSeq.toString() }}); newest tentatively paired, both candidates retained for review"
            )
            return
        }

        if (review.keepBoth || review.choiceLocalKey == null) {
            updated[resp.localKey] = resp.copy(
                pairedMessageId = null, sid = sid ?: (resp.payload[0].toInt() and 0xFF),
                candidateMessageIds = candidates.map { it.id.toInt() }
            )
            extraDiagnostics += Diagnostic(
                captureId = captureId, code = DiagnosticCode.PAIR_AMBIGUOUS,
                severity = Severity.INFO, offsetMs = resp.startMs, canId = resp.canId,
                direction = Direction.RX, gen = resp.gen, frameSeq = resp.firstFrameSeq,
                messageLocalKey = resp.localKey,
                detail = "human review keeps both session candidates (v${review.version}); no automatic pairing"
            )
            return
        }

        val chosen = candidates.firstOrNull { it.localKey == review.choiceLocalKey }
        if (chosen == null) {
            extraDiagnostics += Diagnostic(
                captureId = captureId, code = DiagnosticCode.PAIR_AMBIGUOUS,
                severity = Severity.WARNING, offsetMs = resp.startMs, canId = resp.canId,
                direction = Direction.RX, gen = resp.gen, frameSeq = resp.firstFrameSeq,
                messageLocalKey = resp.localKey,
                detail = "reviewed choice ${review.choiceLocalKey} (v${review.version}) is no longer a valid candidate after re-parse; needs re-review"
            )
            updated[resp.localKey] = resp.copy(
                pairedMessageId = newest.id, sid = sid ?: (resp.payload[0].toInt() and 0xFF),
                candidateMessageIds = candidates.map { it.id.toInt() }
            )
            pairedRequests += newest.localKey
        } else {
            bind(resp, chosen, updated, pairedRequests)
        }
    }
}
