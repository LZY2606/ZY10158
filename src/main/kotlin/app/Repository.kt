package app

import java.sql.Connection
import java.sql.PreparedStatement

/** Raised when a versioned review write loses an optimistic-concurrency race. */
class ConflictException(message: String) : RuntimeException(message)

class Repository(val db: Database) {
    private val conn: Connection get() = db.conn

    // ---------- import (idempotent on capture file SHA-256) ----------

    fun findCaptureByHash(sha: String): Capture? {
        conn.prepareStatement("SELECT * FROM captures WHERE file_sha256 = ?").use { ps ->
            ps.setString(1, sha)
            ps.executeQuery().use { rs ->
                if (rs.next()) return captureRow(rs)
            }
        }
        return null
    }

    fun listCaptures(): List<Capture> {
        val out = ArrayList<Capture>()
        conn.createStatement().executeQuery("SELECT * FROM captures ORDER BY id").use { rs ->
            while (rs.next()) out.add(captureRow(rs))
        }
        return out
    }

    fun getCapture(id: Long): Capture? {
        conn.prepareStatement("SELECT * FROM captures WHERE id = ?").use { ps ->
            ps.setLong(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) return captureRow(rs) }
        }
        return null
    }

    /** Imports the parsed capture; returns the existing capture row when the hash was seen before. */
    fun importCapture(parsed: ParsedCapture, nowMs: Long): Pair<Capture, Boolean> {
        findCaptureByHash(parsed.fileSha256)?.let { return it to false }
        return try {
            conn.prepareStatement(
                """INSERT INTO captures(name, file_sha256, frame_count, imported_at_ms, review_version)
                   VALUES(?,?,?,?,1)""",
                PreparedStatement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, parsed.name)
                ps.setString(2, parsed.fileSha256)
                ps.setInt(3, parsed.frames.size)
                ps.setLong(4, nowMs)
                ps.executeUpdate()
                val captureId = ps.generatedKeys.use { if (it.next()) it.getLong(1) else error("no capture id") }
                conn.prepareStatement(
                    """INSERT INTO raw_frames(capture_id, seq, offset_ms, can_id, direction, data_hex)
                       VALUES(?,?,?,?,?,?)"""
                ).use { fps ->
                    for (f in parsed.frames) {
                        fps.setLong(1, captureId); fps.setInt(2, f.seq); fps.setLong(3, f.offsetMs)
                        fps.setInt(4, f.canId); fps.setString(5, f.direction.name)
                        fps.setString(6, Hex.encode(f.data)); fps.addBatch()
                    }
                    fps.executeBatch()
                }
                conn.prepareStatement(
                    "INSERT INTO restart_directives(capture_id, offset_ms, scope_can_id, note) VALUES(?,?,?,?)"
                ).use { dps ->
                    for (d in parsed.directives) {
                        dps.setLong(1, captureId); dps.setLong(2, d.offsetMs)
                        if (d.scopeCanId == null) dps.setNull(3, java.sql.Types.INTEGER) else dps.setInt(3, d.scopeCanId)
                        dps.setString(4, d.note); dps.addBatch()
                    }
                    dps.executeBatch()
                }
                conn.commit()
                (getCapture(captureId)!!) to true
            }
        } catch (e: Exception) {
            conn.rollback()
            findCaptureByHash(parsed.fileSha256)?.let { return it to false }
            throw e
        }
    }

    fun loadFrames(captureId: Long): List<RawFrame> {
        val out = ArrayList<RawFrame>()
        conn.prepareStatement(
            "SELECT * FROM raw_frames WHERE capture_id = ? ORDER BY seq"
        ).use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        RawFrame(
                            id = rs.getLong("id"), captureId = captureId, seq = rs.getInt("seq"),
                            offsetMs = rs.getLong("offset_ms"), canId = rs.getInt("can_id"),
                            direction = Direction.valueOf(rs.getString("direction")),
                            data = Hex.decode(rs.getString("data_hex"))
                        )
                    )
                }
            }
        }
        return out
    }

    fun loadDirectives(captureId: Long): List<RestartDirective> {
        val out = ArrayList<RestartDirective>()
        conn.prepareStatement(
            "SELECT * FROM restart_directives WHERE capture_id = ? ORDER BY offset_ms, id"
        ).use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        RestartDirective(
                            id = rs.getLong("id"), captureId = captureId, offsetMs = rs.getLong("offset_ms"),
                            scopeCanId = rs.int("scope_can_id"), note = rs.getString("note")
                        )
                    )
                }
            }
        }
        return out
    }

    fun bumpReviewVersion(captureId: Long): Long {
        conn.prepareStatement("UPDATE captures SET review_version = review_version + 1 WHERE id = ?").use {
            it.setLong(1, captureId); it.executeUpdate()
        }
        conn.prepareStatement("SELECT review_version FROM captures WHERE id = ?").use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    // ---------- L2 review: restart marks ----------

    fun listRestartMarks(captureId: Long): List<RestartMark> {
        val out = ArrayList<RestartMark>()
        conn.prepareStatement(
            "SELECT * FROM restart_marks WHERE capture_id = ? ORDER BY offset_ms, id"
        ).use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(
                    RestartMark(
                        id = rs.getLong("id"), captureId = captureId, offsetMs = rs.getLong("offset_ms"),
                        scopeCanId = rs.int("scope_can_id"), note = rs.getString("note"),
                        createdAtMs = rs.getLong("created_at_ms")
                    )
                )
            }
        }
        return out
    }

    fun addRestartMark(captureId: Long, offsetMs: Long, scopeCanId: Int?, note: String, nowMs: Long): RestartMark {
        require(offsetMs >= 0) { "offset must be >= 0" }
        val mark = conn.prepareStatement(
            """INSERT INTO restart_marks(capture_id, offset_ms, scope_can_id, note, created_at_ms)
               VALUES(?,?,?,?,?)""",
            PreparedStatement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setLong(1, captureId); ps.setLong(2, offsetMs)
            if (scopeCanId == null) ps.setNull(3, java.sql.Types.INTEGER) else ps.setInt(3, scopeCanId)
            ps.setString(4, note); ps.setLong(5, nowMs)
            ps.executeUpdate()
            val id = ps.generatedKeys.use { if (it.next()) it.getLong(1) else error("no mark id") }
            bumpReviewVersion(captureId)
            RestartMark(id, captureId, offsetMs, scopeCanId, note, nowMs)
        }
        conn.commit()
        return mark
    }

    fun deleteRestartMark(captureId: Long, markId: Long) {
        conn.prepareStatement("DELETE FROM restart_marks WHERE id = ? AND capture_id = ?").use {
            it.setLong(1, markId); it.setLong(2, captureId)
            val n = it.executeUpdate()
            if (n == 0) throw IllegalArgumentException("restart mark $markId not found")
            bumpReviewVersion(captureId)
        }
        conn.commit()
    }

    // ---------- L2 review: frame judgments (versioned) ----------

    fun getFrameReview(frameId: Long): FrameReview? {
        conn.prepareStatement("SELECT * FROM frame_reviews WHERE frame_id = ?").use { ps ->
            ps.setLong(1, frameId)
            ps.executeQuery().use { rs ->
                if (rs.next()) return FrameReview(
                    frameId = frameId,
                    rejected = rs.getInt("rejected") == 1,
                    roleOverride = rs.getString("role_override")?.let { Direction.valueOf(it) },
                    note = rs.getString("note"), version = rs.getLong("version"),
                    updatedAtMs = rs.getLong("updated_at_ms")
                )
            }
        }
        return null
    }

    /**
     * Upsert a frame judgment with optimistic concurrency.
     * expectedVersion null means "must be a new row"; otherwise it must match the stored version.
     * The stored version increments on every accepted write, so a racing concurrent commit
     * fails with ConflictException instead of silently clobbering the other judgment.
     */
    fun upsertFrameReview(
        frameId: Long, captureId: Long, rejected: Boolean,
        roleOverride: Direction?, note: String, expectedVersion: Long?, nowMs: Long
    ): FrameReview {
        requireCaptureFrame(frameId, captureId)
        val current = getFrameReview(frameId)
        if (expectedVersion == null) {
            if (current != null) throw ConflictException("frame $frameId already reviewed at version ${current.version}")
        } else {
            if (current == null) throw ConflictException("frame $frameId has no review row (expected v$expectedVersion)")
            if (current.version != expectedVersion)
                throw ConflictException("frame $frameId review is v${current.version}, expected v$expectedVersion")
        }
        val newVersion = (current?.version ?: 0) + 1
        conn.prepareStatement(
            """INSERT INTO frame_reviews(frame_id, rejected, role_override, note, version, updated_at_ms)
               VALUES(?,?,?,?,?,?)
               ON CONFLICT(frame_id) DO UPDATE SET
                 rejected=excluded.rejected, role_override=excluded.role_override,
                 note=excluded.note, version=excluded.version, updated_at_ms=excluded.updated_at_ms"""
        ).use { ps ->
            ps.setLong(1, frameId); ps.setInt(2, if (rejected) 1 else 0)
            if (roleOverride == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, roleOverride.name)
            ps.setString(4, note); ps.setLong(5, newVersion); ps.setLong(6, nowMs)
            ps.executeUpdate()
        }
        bumpReviewVersion(captureId)
        conn.commit()
        return FrameReview(frameId, rejected, roleOverride, note, newVersion, nowMs)
    }

    private fun requireCaptureFrame(frameId: Long, captureId: Long) {
        conn.prepareStatement("SELECT 1 FROM raw_frames WHERE id = ? AND capture_id = ?").use { ps ->
            ps.setLong(1, frameId); ps.setLong(2, captureId)
            ps.executeQuery().use { if (!it.next()) throw IllegalArgumentException("frame $frameId not in capture $captureId") }
        }
    }

    // ---------- L2 review: attribution resolution (versioned) ----------

    fun getAttributionReview(captureId: Long, localKey: String): AttributionReview? {
        conn.prepareStatement(
            "SELECT * FROM attribution_reviews WHERE capture_id = ? AND message_local_key = ?"
        ).use { ps ->
            ps.setLong(1, captureId); ps.setString(2, localKey)
            ps.executeQuery().use { rs ->
                if (rs.next()) return AttributionReview(
                    messageLocalKey = localKey,
                    choiceLocalKey = rs.getString("choice_local_key"),
                    keepBoth = rs.getInt("keep_both") == 1, note = rs.getString("note"),
                    version = rs.getLong("version"), updatedAtMs = rs.getLong("updated_at_ms")
                )
            }
        }
        return null
    }

    fun upsertAttributionReview(
        captureId: Long, localKey: String, choiceLocalKey: String?,
        keepBoth: Boolean, note: String, expectedVersion: Long?, nowMs: Long
    ): AttributionReview {
        require(!keepBoth || choiceLocalKey == null) { "keepBoth and an explicit choice are mutually exclusive" }
        val current = getAttributionReview(captureId, localKey)
        if (expectedVersion == null) {
            if (current != null) throw ConflictException("$localKey already resolved at v${current.version}")
        } else {
            if (current == null) throw ConflictException("$localKey has no resolution row")
            if (current.version != expectedVersion)
                throw ConflictException("$localKey resolution is v${current.version}, expected v$expectedVersion")
        }
        val newVersion = (current?.version ?: 0) + 1
        conn.prepareStatement(
            """INSERT INTO attribution_reviews(message_local_key, capture_id, choice_local_key, keep_both, note, version, updated_at_ms)
               VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(message_local_key) DO UPDATE SET
                 choice_local_key=excluded.choice_local_key, keep_both=excluded.keep_both,
                 note=excluded.note, version=excluded.version, updated_at_ms=excluded.updated_at_ms"""
        ).use { ps ->
            ps.setString(1, localKey); ps.setLong(2, captureId)
            if (choiceLocalKey == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, choiceLocalKey)
            ps.setInt(4, if (keepBoth) 1 else 0); ps.setString(5, note)
            ps.setLong(6, newVersion); ps.setLong(7, nowMs)
            ps.executeUpdate()
        }
        bumpReviewVersion(captureId)
        conn.commit()
        return AttributionReview(localKey, choiceLocalKey, keepBoth, note, newVersion, nowMs)
    }

    // ---------- L1 parse runs: transactional publish ----------

    /**
     * Persists a whole parser run atomically: parser run row + every message + every diagnostic
     * become visible together. The run is inserted with published=0 and only flipped to 1 in the
     * same transaction after all rows landed; readers never observe a half-published run.
     */
    fun publishRun(
        captureId: Long, reviewVersion: Long, messages: List<Message>,
        diagnostics: List<Diagnostic>, nowMs: Long
    ): ParserRun {
        try {
            val runNumber = nextRunNumber(captureId)
            val runId = conn.prepareStatement(
                """INSERT INTO parser_runs(capture_id, run_number, started_at_ms, finished_at_ms,
                   review_version_at_run, published) VALUES(?,?,?,?,?,0)""",
                PreparedStatement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setLong(1, captureId); ps.setInt(2, runNumber)
                ps.setLong(3, nowMs); ps.setLong(4, nowMs); ps.setLong(5, reviewVersion)
                ps.executeUpdate()
                ps.generatedKeys.use { if (it.next()) it.getLong(1) else error("no run id") }
            }

            conn.prepareStatement(
                """INSERT INTO messages(run_id, capture_id, local_key, can_id, direction, gen,
                   first_frame_seq, last_frame_seq, start_ms, end_ms, multi_frame, expected_length,
                   payload_hex, missing_indices, received_frame_seqs, status, status_detail,
                   paired_message_local_key, sid, candidate_local_keys)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
            ).use { ms ->
                for (m in messages) {
                    ms.setLong(1, runId); ms.setLong(2, captureId); ms.setString(3, m.localKey)
                    ms.setInt(4, m.canId); ms.setString(5, m.direction.name); ms.setInt(6, m.gen)
                    ms.setInt(7, m.firstFrameSeq); ms.setInt(8, m.lastFrameSeq)
                    ms.setLong(9, m.startMs); ms.setLong(10, m.endMs)
                    ms.setInt(11, if (m.multiFrame) 1 else 0)
                    if (m.expectedLength == null) ms.setNull(12, java.sql.Types.INTEGER)
                    else ms.setInt(12, m.expectedLength)
                    ms.setString(13, Hex.encode(m.payload))
                    ms.setString(14, m.missingIndices.joinToString(","))
                    ms.setString(15, m.receivedFrameSeqs.joinToString(","))
                    ms.setString(16, m.status.name); ms.setString(17, m.statusDetail)
                    if (m.pairedMessageId == null) {
                        ms.setNull(18, java.sql.Types.VARCHAR)
                    } else {
                        val partner = messages.firstOrNull { it.id == m.pairedMessageId }
                            ?: error("paired message id ${m.pairedMessageId} missing from run")
                        ms.setString(18, partner.localKey)
                    }
                    if (m.sid == null) ms.setNull(19, java.sql.Types.INTEGER) else ms.setInt(19, m.sid)
                    ms.setString(20, m.candidateMessageIds.joinToString(",") { idx ->
                        messages.first { it.id == idx.toLong() }.localKey
                    })
                    ms.addBatch()
                }
                ms.executeBatch()
            }

            conn.prepareStatement(
                """INSERT INTO diagnostics(run_id, capture_id, code, severity, offset_ms, can_id,
                   direction, gen, frame_seq, message_local_key, detail)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)"""
            ).use { ds ->
                for (d in diagnostics) {
                    ds.setLong(1, runId); ds.setLong(2, captureId)
                    ds.setString(3, d.code.name); ds.setString(4, d.severity.name)
                    if (d.offsetMs == null) ds.setNull(5, java.sql.Types.BIGINT) else ds.setLong(5, d.offsetMs)
                    if (d.canId == null) ds.setNull(6, java.sql.Types.INTEGER) else ds.setInt(6, d.canId)
                    if (d.direction == null) ds.setNull(7, java.sql.Types.VARCHAR) else ds.setString(7, d.direction.name)
                    if (d.gen == null) ds.setNull(8, java.sql.Types.INTEGER) else ds.setInt(8, d.gen)
                    if (d.frameSeq == null) ds.setNull(9, java.sql.Types.INTEGER) else ds.setInt(9, d.frameSeq)
                    if (d.messageLocalKey == null) ds.setNull(10, java.sql.Types.VARCHAR)
                    else ds.setString(10, d.messageLocalKey)
                    ds.setString(11, d.detail); ds.addBatch()
                }
                ds.executeBatch()
            }

            conn.prepareStatement("UPDATE parser_runs SET published = 1 WHERE id = ?").use {
                it.setLong(1, runId); it.executeUpdate()
            }
            conn.commit()
            return getRun(captureId, runNumber)!!
        } catch (e: Exception) {
            conn.rollback()
            throw e
        }
    }

    private fun nextRunNumber(captureId: Long): Int {
        conn.prepareStatement("SELECT COALESCE(MAX(run_number), 0) + 1 AS n FROM parser_runs WHERE capture_id = ?")
            .use { ps ->
                ps.setLong(1, captureId)
                ps.executeQuery().use { rs -> rs.next(); return rs.getInt("n") }
            }
    }

    fun getRun(captureId: Long, runNumber: Int): ParserRun? {
        conn.prepareStatement("SELECT * FROM parser_runs WHERE capture_id = ? AND run_number = ?").use { ps ->
            ps.setLong(1, captureId); ps.setInt(2, runNumber)
            ps.executeQuery().use { rs -> if (rs.next()) return runRow(rs) }
        }
        return null
    }

    fun latestPublishedRun(captureId: Long): ParserRun? {
        conn.prepareStatement(
            "SELECT * FROM parser_runs WHERE capture_id = ? AND published = 1 ORDER BY run_number DESC LIMIT 1"
        ).use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs -> if (rs.next()) return runRow(rs) }
        }
        return null
    }

    fun listRuns(captureId: Long): List<ParserRun> {
        val out = ArrayList<ParserRun>()
        conn.prepareStatement("SELECT * FROM parser_runs WHERE capture_id = ? ORDER BY run_number").use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs -> while (rs.next()) out.add(runRow(rs)) }
        }
        return out
    }

    fun loadMessages(runId: Long): List<Message> {
        val keyToId = HashMap<String, Long>()
        val out = ArrayList<Message>()
        conn.prepareStatement("SELECT * FROM messages WHERE run_id = ? ORDER BY id").use { ps ->
            ps.setLong(1, runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val key = rs.getString("local_key")
                    keyToId[key] = rs.getLong("id")
                }
            }
        }
        conn.prepareStatement("SELECT * FROM messages WHERE run_id = ? ORDER BY id").use { ps ->
            ps.setLong(1, runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val partnerKey = rs.getString("paired_message_local_key")
                    out.add(
                        Message(
                            id = rs.getLong("id"), runId = runId, captureId = rs.getLong("capture_id"),
                            localKey = rs.getString("local_key"), canId = rs.getInt("can_id"),
                            direction = Direction.valueOf(rs.getString("direction")), gen = rs.getInt("gen"),
                            firstFrameSeq = rs.getInt("first_frame_seq"),
                            lastFrameSeq = rs.getInt("last_frame_seq"),
                            startMs = rs.getLong("start_ms"), endMs = rs.getLong("end_ms"),
                            multiFrame = rs.getInt("multi_frame") == 1,
                            expectedLength = rs.int("expected_length"),
                            payload = Hex.decode(rs.getString("payload_hex")),
                            missingIndices = parseIntList(rs.getString("missing_indices")),
                            receivedFrameSeqs = parseIntList(rs.getString("received_frame_seqs")),
                            status = MessageStatus.valueOf(rs.getString("status")),
                            statusDetail = rs.getString("status_detail"),
                            pairedMessageId = partnerKey?.let { keyToId[it] },
                            sid = rs.int("sid"),
                            candidateMessageIds = rs.getString("candidate_local_keys")
                                .split(",").filter { it.isNotEmpty() }.map { keyToId[it]!!.toInt() }
                        )
                    )
                }
            }
        }
        return out
    }

    fun loadDiagnostics(runId: Long): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        conn.prepareStatement("SELECT * FROM diagnostics WHERE run_id = ? ORDER BY id").use { ps ->
            ps.setLong(1, runId)
            ps.executeQuery().use { rs ->
                while (rs.next()) out.add(
                    Diagnostic(
                        id = rs.getLong("id"), runId = runId, captureId = rs.getLong("capture_id"),
                        code = DiagnosticCode.valueOf(rs.getString("code")),
                        severity = Severity.valueOf(rs.getString("severity")),
                        offsetMs = rs.long("offset_ms"), canId = rs.int("can_id"),
                        direction = rs.getString("direction")?.let { Direction.valueOf(it) },
                        gen = rs.int("gen"), frameSeq = rs.int("frame_seq"),
                        messageLocalKey = rs.getString("message_local_key"),
                        detail = rs.getString("detail")
                    )
                )
            }
        }
        return out
    }

    private fun parseIntList(s: String): List<Int> =
        if (s.isEmpty()) emptyList() else s.split(",").map { it.toInt() }

    private fun captureRow(rs: java.sql.ResultSet) = Capture(
        id = rs.getLong("id"), name = rs.getString("name"),
        fileSha256 = rs.getString("file_sha256"), frameCount = rs.getInt("frame_count"),
        importedAtMs = rs.getLong("imported_at_ms"), reviewVersion = rs.getLong("review_version")
    )

    private fun runRow(rs: java.sql.ResultSet) = ParserRun(
        id = rs.getLong("id"), captureId = rs.getLong("capture_id"),
        runNumber = rs.getInt("run_number"), startedAtMs = rs.getLong("started_at_ms"),
        finishedAtMs = rs.getLong("finished_at_ms"),
        reviewVersionAtRun = rs.getLong("review_version_at_run"),
        published = rs.getInt("published") == 1
    )
}
