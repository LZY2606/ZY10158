package app

import java.sql.ResultSet
import java.sql.Statement

data class ImportResult(val captureId: Long, val alreadyExisted: Boolean)

class Repository(internal val db: Database) {
    internal val conn get() = db.conn

    /* ---------------- 导入（按捕获文件哈希幂等） ---------------- */

    fun importCapture(filename: String, text: String): ImportResult {
        val parsed = CaptureParser.parse(text)
        synchronized(db) {
            findCaptureByHash(parsed.sha256)?.let {
                return ImportResult(it, true)
            }
            try {
                val captureId = insertCapture(filename, parsed, text)
                insertRawFrames(captureId, parsed.frames)
                insertResets(captureId, parsed.resets)
                conn.commit()
                return ImportResult(captureId, false)
            } catch (e: Exception) {
                conn.rollback(); throw e
            }
        }
    }

    private fun findCaptureByHash(sha: String): Long? =
        conn.prepareStatement("SELECT id FROM capture WHERE sha256=?").use { ps ->
            ps.setString(1, sha)
            ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }

    private fun insertCapture(filename: String, parsed: ParsedCapture, text: String): Long {
        val ps = conn.prepareStatement(
            """INSERT INTO capture(sha256, filename, imported_at_ms, frame_count, reset_count, published)
               VALUES(?,?,?,?,?,0)""", Statement.RETURN_GENERATED_KEYS
        )
        ps.setString(1, parsed.sha256); ps.setString(2, filename)
        ps.setLong(3, System.currentTimeMillis())
        ps.setInt(4, parsed.frames.size); ps.setInt(5, parsed.resets.size)
        ps.executeUpdate()
        return ps.generatedKeys.use { it.next(); it.getLong(1) }
    }

    private fun insertRawFrames(captureId: Long, frames: List<RawFrame>) {
        conn.prepareStatement(
            """INSERT INTO raw_frame(capture_id,seq,can_id,ext,data,ts_us,direction,raw_line)
               VALUES(?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            for (f in frames) {
                ps.setLong(1, captureId); ps.setLong(2, f.seq); ps.setInt(3, f.canId)
                ps.setInt(4, if (f.ext) 1 else 0); ps.setBytes(5, f.data)
                ps.setLong(6, f.tsUs); ps.setString(7, f.declaredDirection.name); ps.setString(8, f.rawLine)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    private fun insertResets(captureId: Long, resets: List<ResetBoundary>) {
        conn.prepareStatement(
            """INSERT INTO reset_boundary(id,capture_id,after_seq,ts_us,origin,can_id)
               VALUES(?,?,?,?,?,?)"""
        ).use { ps ->
            for (r in resets) {
                ps.setLong(1, r.id); ps.setLong(2, captureId); ps.setLong(3, r.afterSeq)
                ps.setLong(4, r.tsUs); ps.setString(5, r.origin)
                if (r.canId == null) ps.setNull(6, java.sql.Types.INTEGER) else ps.setInt(6, r.canId)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    /* ---------------- 读取 ---------------- */

    fun listCaptures(): List<Capture> {
        val sql = "SELECT id,sha256,filename,imported_at_ms,frame_count,reset_count,published,latest_run_id FROM capture ORDER BY id"
        return conn.createStatement().executeQuery(sql).use { rs ->
            buildList { while (rs.next()) add(readCapture(rs)) }
        }
    }

    fun getCapture(id: Long): Capture? =
        conn.prepareStatement("SELECT * FROM capture WHERE id=?").use { ps ->
            ps.setLong(1, id); ps.executeQuery().use { if (it.next()) readCapture(it) else null }
        }

    private fun readCapture(rs: ResultSet) = Capture(
        rs.getLong("id"), rs.getString("sha256"), rs.getString("filename"),
        rs.getLong("imported_at_ms"), rs.getInt("frame_count"), rs.getInt("reset_count"),
        rs.getInt("published") == 1, rs.getObject("latest_run_id")?.let { (it as Number).toLong() }
    )

    fun loadFrames(captureId: Long): List<RawFrame> =
        conn.prepareStatement("SELECT * FROM raw_frame WHERE capture_id=? ORDER BY seq").use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        RawFrame(
                            captureId, rs.getLong("seq"), rs.getInt("can_id"),
                            rs.getInt("ext") == 1, rs.getBytes("data"), rs.getLong("ts_us"),
                            Direction.valueOf(rs.getString("direction")), rs.getString("raw_line")
                        )
                    )
                }
            }
        }

    fun loadResets(captureId: Long): List<ResetBoundary> =
        conn.prepareStatement("SELECT * FROM reset_boundary WHERE capture_id=? ORDER BY id").use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        ResetBoundary(
                            rs.getLong("id"), captureId, rs.getLong("after_seq"),
                            rs.getLong("ts_us"), rs.getString("origin"),
                            rs.getObject("can_id")?.let { (it as Number).toInt() }
                        )
                    )
                }
            }
        }

    fun loadFrameAdjudications(captureId: Long): List<FrameAdjudication> =
        conn.prepareStatement("SELECT * FROM frame_adjudication WHERE capture_id=?").use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        FrameAdjudication(
                            0L, captureId, rs.getLong("frame_seq"),
                            rs.getInt("rejected") == 1, rs.getString("reject_reason"),
                            rs.getString("role_override")?.let { Direction.valueOf(it) },
                            rs.getInt("version"), rs.getLong("created_at_ms")
                        )
                    )
                }
            }
        }

    fun loadLinkAdjudications(captureId: Long): List<LinkAdjudication> =
        conn.prepareStatement("SELECT * FROM link_adjudication WHERE capture_id=?").use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        LinkAdjudication(
                            0L, captureId, rs.getLong("link_id"),
                            rs.getObject("chosen_request_assembly_id")?.let { (it as Number).toLong() },
                            rs.getInt("keep_candidates") == 1, rs.getString("note"),
                            rs.getInt("version"), rs.getLong("created_at_ms")
                        )
                    )
                }
            }
        }
}

/* ---------------- 机器层运行（整事务发布） ---------------- */

fun Repository.publishMachineRun(
    captureId: Long,
    cfg: ReassemblyConfig,
    reassembly: ReassemblyResult,
    links: List<ReqRespLink>
): Long = synchronized(db) {
    try {
        val runId = conn.prepareStatement(
            """INSERT INTO machine_run(capture_id,created_at_ms,fc_timeout_us,cr_timeout_us,published)
               VALUES(?,?,?,?,0)""", Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            ps.setLong(1, captureId); ps.setLong(2, System.currentTimeMillis())
            ps.setLong(3, cfg.fcTimeoutUs); ps.setLong(4, cfg.crTimeoutUs)
            ps.executeUpdate()
            ps.generatedKeys.use { it.next(); it.getLong(1) }
        }

        insertAssemblies(runId, captureId, reassembly.assemblies)
        insertFrameAssembly(runId, reassembly.frameAssembly)
        insertDiagnostics(runId, captureId, reassembly.diagnostics)
        insertLinks(runId, captureId, links)

        // 全部状态与诊断落盘后才置 published=1 并更新 capture.latest_run_id（同一事务内原子发布）
        conn.prepareStatement("UPDATE machine_run SET published=1 WHERE id=?").use {
            it.setLong(1, runId); it.executeUpdate()
        }
        conn.prepareStatement("UPDATE capture SET published=1, latest_run_id=? WHERE id=?").use {
            it.setLong(1, runId); it.setLong(2, captureId); it.executeUpdate()
        }
        conn.commit()
        runId
    } catch (e: Exception) {
        conn.rollback(); throw e
    }
}

private fun Repository.insertAssemblies(runId: Long, captureId: Long, list: List<Assembly>) {
    val sql = """INSERT INTO assembly(id,run_id,capture_id,can_id,direction,generation,
        start_frame_seq,end_frame_seq,status,payload,declared_len,received_len,missing_json,
        evidence_json,start_ts_us,end_ts_us,error_code,first_data_frame_seq)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"""
    conn.prepareStatement(sql).use { ps ->
        for (a in list) {
            var c = 1
            ps.setLong(c++, a.id); ps.setLong(c++, runId); ps.setLong(c++, captureId)
            ps.setInt(c++, a.canId); ps.setString(c++, a.direction.name); ps.setInt(c++, a.generation)
            ps.setLong(c++, a.startFrameSeq)
            if (a.endFrameSeq == null) ps.setNull(c++, java.sql.Types.BIGINT) else ps.setLong(c++, a.endFrameSeq)
            ps.setString(c++, a.status.name); ps.setBytes(c++, a.payload)
            if (a.declaredLen == null) ps.setNull(c++, java.sql.Types.INTEGER) else ps.setInt(c++, a.declaredLen)
            ps.setInt(c++, a.receivedLen)
            ps.setString(c++, Json.write(a.missing.map { mapOf("sn" to it.sn, "expectedLen" to it.expectedLen) }))
            ps.setString(c++, Json.write(a.evidence.map {
                mapOf("frameSeq" to it.frameSeq, "kind" to it.kind, "detail" to it.detail)
            }))
            ps.setLong(c++, a.startTsUs)
            if (a.endTsUs == null) ps.setNull(c++, java.sql.Types.BIGINT) else ps.setLong(c++, a.endTsUs)
            ps.setString(c++, a.errorCode)
            ps.setLong(c++, a.firstDataFrameSeq)
            ps.addBatch()
        }
        ps.executeBatch()
    }
}

private fun Repository.insertFrameAssembly(runId: Long, map: Map<Long, Long>) {
    conn.prepareStatement("INSERT INTO frame_assembly(run_id,frame_seq,assembly_id) VALUES(?,?,?)").use { ps ->
        for ((frameSeq, asmId) in map) {
            ps.setLong(1, runId); ps.setLong(2, frameSeq); ps.setLong(3, asmId); ps.addBatch()
        }
        ps.executeBatch()
    }
}

private fun Repository.insertDiagnostics(runId: Long, captureId: Long, list: List<Diagnostic>) {
    val sql = """INSERT INTO diagnostic(id,run_id,capture_id,code,severity,frame_seq,can_id,
        direction,generation,message) VALUES(?,?,?,?,?,?,?,?,?,?)"""
    conn.prepareStatement(sql).use { ps ->
        for (d in list) {
            var c = 1
            ps.setLong(c++, d.id); ps.setLong(c++, runId); ps.setLong(c++, captureId)
            ps.setString(c++, d.code); ps.setString(c++, d.severity.name)
            if (d.frameSeq == null) ps.setNull(c++, java.sql.Types.BIGINT) else ps.setLong(c++, d.frameSeq)
            if (d.canId == null) ps.setNull(c++, java.sql.Types.INTEGER) else ps.setInt(c++, d.canId)
            ps.setString(c++, d.direction?.name)
            if (d.generation == null) ps.setNull(c++, java.sql.Types.INTEGER) else ps.setInt(c++, d.generation)
            ps.setString(c++, d.message)
            ps.addBatch()
        }
        ps.executeBatch()
    }
}

private fun Repository.insertLinks(runId: Long, captureId: Long, list: List<ReqRespLink>) {
    val sql = """INSERT INTO req_resp_link(id,run_id,capture_id,generation,request_assembly_id,
        response_assembly_id,status,candidates_json,service_id,nrc) VALUES(?,?,?,?,?,?,?,?,?,?)"""
    conn.prepareStatement(sql).use { ps ->
        for (l in list) {
            var c = 1
            ps.setLong(c++, l.id); ps.setLong(c++, runId); ps.setLong(c++, captureId)
            ps.setInt(c++, l.generation)
            if (l.requestAssemblyId == null) ps.setNull(c++, java.sql.Types.BIGINT) else ps.setLong(c++, l.requestAssemblyId)
            if (l.responseAssemblyId == null) ps.setNull(c++, java.sql.Types.BIGINT) else ps.setLong(c++, l.responseAssemblyId)
            ps.setString(c++, l.status.name)
            ps.setString(c++, Json.write(l.candidates.map {
                mapOf("requestAssemblyId" to it.requestAssemblyId, "score" to it.score, "reason" to it.reason)
            }))
            if (l.serviceId == null) ps.setNull(c++, java.sql.Types.INTEGER) else ps.setInt(c++, l.serviceId)
            if (l.nrc == null) ps.setNull(c++, java.sql.Types.INTEGER) else ps.setInt(c++, l.nrc)
            ps.addBatch()
        }
        ps.executeBatch()
    }
}

/* ---------------- 读取机器层（最新一次运行） ---------------- */

fun Repository.latestRunId(captureId: Long): Long? =
    getCapture(captureId)?.latestRunId

fun Repository.loadMachineRun(captureId: Long, runId: Long): StoredMachineRun? {
    val published = conn.prepareStatement("SELECT published FROM machine_run WHERE id=? AND capture_id=?").use { ps ->
        ps.setLong(1, runId); ps.setLong(2, captureId)
        ps.executeQuery().use { if (it.next()) it.getInt(1) == 1 else return null }
    }
    if (!published) return null
    return StoredMachineRun(
        runId,
        loadAssemblies(runId),
        loadDiagnostics(runId, captureId),
        loadFrameAssembly(runId),
        loadGenerations(runId, captureId)
    )
}

private fun Repository.loadAssemblies(runId: Long): List<Assembly> =
    conn.prepareStatement("SELECT * FROM assembly WHERE run_id=? ORDER BY id").use { ps ->
        ps.setLong(1, runId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val missingJson = Json.parse("""{"x":${rs.getString("missing_json")}}""").array("x")!!
                    val evidenceJson = Json.parse("""{"x":${rs.getString("evidence_json")}}""").array("x")!!
                    add(
                        Assembly(
                            rs.getLong("id"), rs.getLong("capture_id"), rs.getInt("can_id"),
                            Direction.valueOf(rs.getString("direction")), rs.getInt("generation"),
                            rs.getLong("start_frame_seq"),
                            rs.getObject("end_frame_seq")?.let { (it as Number).toLong() },
                            AssemblyStatus.valueOf(rs.getString("status")),
                            rs.getBytes("payload"),
                            rs.getObject("declared_len")?.let { (it as Number).toInt() },
                            rs.getInt("received_len"),
                            missingJson.map {
                                @Suppress("UNCHECKED_CAST")
                                val m = it as Map<String, Any?>
                                MissingRange((m["sn"] as Number).toInt(), (m["expectedLen"] as Number).toInt())
                            },
                            evidenceJson.map {
                                @Suppress("UNCHECKED_CAST")
                                val m = it as Map<String, Any?>
                                Evidence((m["frameSeq"] as Number).toLong(), m["kind"] as String, m["detail"] as String)
                            },
                            rs.getLong("start_ts_us"),
                            rs.getObject("end_ts_us")?.let { (it as Number).toLong() },
                            rs.getString("error_code"),
                            rs.getLong("first_data_frame_seq")
                        )
                    )
                }
            }
        }
    }

private fun Repository.loadFrameAssembly(runId: Long): Map<Long, Long> =
    conn.prepareStatement("SELECT frame_seq,assembly_id FROM frame_assembly WHERE run_id=?").use { ps ->
        ps.setLong(1, runId)
        ps.executeQuery().use { rs ->
            buildMap {
                while (rs.next()) put(rs.getLong(1), rs.getLong(2))
            }
        }
    }

private fun Repository.loadDiagnostics(runId: Long, captureId: Long): List<Diagnostic> =
    conn.prepareStatement("SELECT * FROM diagnostic WHERE run_id=? ORDER BY id").use { ps ->
        ps.setLong(1, runId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    Diagnostic(
                        rs.getLong("id"), captureId, rs.getString("code"),
                        Severity.valueOf(rs.getString("severity")),
                        rs.getObject("frame_seq")?.let { (it as Number).toLong() },
                        rs.getObject("can_id")?.let { (it as Number).toInt() },
                        rs.getString("direction")?.let { Direction.valueOf(it) },
                        rs.getObject("generation")?.let { (it as Number).toInt() },
                        rs.getString("message")
                    )
                )
            }
        }
    }

private fun Repository.loadGenerations(runId: Long, captureId: Long): List<GenerationInfo> =
    conn.prepareStatement(
        "SELECT * FROM diagnostic WHERE run_id=? AND code='GENERATION_BOUNDARY' ORDER BY frame_seq, can_id"
    ).use { ps ->
        ps.setLong(1, runId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    GenerationInfo(
                        rs.getInt("generation"), rs.getInt("can_id"),
                        rs.getString("direction")?.let { Direction.valueOf(it) },
                        rs.getObject("frame_seq")?.let { (it as Number).toLong() } ?: -1L,
                        0L
                    )
                )
            }
        }
    }

fun Repository.loadLinks(runId: Long, captureId: Long): List<ReqRespLink> =
    conn.prepareStatement("SELECT * FROM req_resp_link WHERE run_id=? ORDER BY id").use { ps ->
        ps.setLong(1, runId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val cands = Json.parse("""{"x":${rs.getString("candidates_json")}}""").array("x")!!
                        .map {
                            @Suppress("UNCHECKED_CAST")
                            val m = it as Map<String, Any?>
                            LinkCandidate(
                                (m["requestAssemblyId"] as Number).toLong(),
                                (m["score"] as Number).toInt(),
                                m["reason"] as String
                            )
                        }
                    add(
                        ReqRespLink(
                            rs.getLong("id"), captureId, rs.getInt("generation"),
                            rs.getObject("request_assembly_id")?.let { (it as Number).toLong() },
                            rs.getObject("response_assembly_id")?.let { (it as Number).toLong() },
                            LinkStatus.valueOf(rs.getString("status")), cands,
                            rs.getObject("service_id")?.let { (it as Number).toInt() },
                            rs.getObject("nrc")?.let { (it as Number).toInt() }
                        )
                    )
                }
            }
        }
    }

/* ---------------- 人工判定（分层版本化） ---------------- */

data class FrameAdjInput(
    val frameSeq: Long,
    val rejected: Boolean,
    val rejectReason: String?,
    val roleOverride: Direction?
)

fun Repository.submitFrameAdjudication(captureId: Long, input: FrameAdjInput, expectedVersion: Int?): Int {
    synchronized(db) {
        val current = conn.prepareStatement(
            "SELECT version FROM frame_adjudication WHERE capture_id=? AND frame_seq=?"
        ).use { ps ->
            ps.setLong(1, captureId); ps.setLong(2, input.frameSeq)
            ps.executeQuery().use { if (it.next()) rsVersion(it) else null }
        }
        if (expectedVersion != null && current != null && expectedVersion != current) {
            recordConflict(captureId, "FRAME", input.frameSeq.toString(), expectedVersion, current,
                mapOf("rejected" to input.rejected, "reason" to input.rejectReason,
                    "roleOverride" to (input.roleOverride?.name)))
            conn.commit()
            throw ConflictException("帧判定版本冲突：期望=$expectedVersion 当前=$current，冲突已写入 adjudication_conflict")
        }
        val next = (current ?: 0) + 1
        conn.prepareStatement(
            """INSERT INTO frame_adjudication(capture_id,frame_seq,rejected,reject_reason,role_override,version,created_at_ms)
               VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(capture_id,frame_seq) DO UPDATE SET
                 rejected=excluded.rejected, reject_reason=excluded.reject_reason,
                 role_override=excluded.role_override, version=excluded.version,
                 created_at_ms=excluded.created_at_ms"""
        ).use { ps ->
            ps.setLong(1, captureId); ps.setLong(2, input.frameSeq)
            ps.setInt(3, if (input.rejected) 1 else 0); ps.setString(4, input.rejectReason)
            ps.setString(5, input.roleOverride?.name); ps.setInt(6, next)
            ps.setLong(7, System.currentTimeMillis())
            ps.executeUpdate()
        }
        audit(captureId, "FRAME", input.frameSeq.toString(), next,
            mapOf("rejected" to input.rejected, "reason" to input.rejectReason,
                "roleOverride" to (input.roleOverride?.name)))
        conn.commit()
        return next
    }
}

data class LinkAdjInput(
    val linkId: Long,
    val chosenRequestAssemblyId: Long?,
    val keepCandidates: Boolean,
    val note: String?
)

fun Repository.submitLinkAdjudication(captureId: Long, input: LinkAdjInput, expectedVersion: Int?): Int {
    synchronized(db) {
        val current = conn.prepareStatement(
            "SELECT version FROM link_adjudication WHERE capture_id=? AND link_id=?"
        ).use { ps ->
            ps.setLong(1, captureId); ps.setLong(2, input.linkId)
            ps.executeQuery().use { if (it.next()) rsVersion(it) else null }
        }
        if (expectedVersion != null && current != null && expectedVersion != current) {
            recordConflict(captureId, "LINK", input.linkId.toString(), expectedVersion, current,
                mapOf("chosen" to input.chosenRequestAssemblyId, "keepCandidates" to input.keepCandidates,
                    "note" to input.note))
            conn.commit()
            throw ConflictException("链接判定版本冲突：期望=$expectedVersion 当前=$current，冲突已写入 adjudication_conflict")
        }
        val next = (current ?: 0) + 1
        conn.prepareStatement(
            """INSERT INTO link_adjudication(capture_id,link_id,chosen_request_assembly_id,keep_candidates,note,version,created_at_ms)
               VALUES(?,?,?,?,?,?,?)
               ON CONFLICT(capture_id,link_id) DO UPDATE SET
                 chosen_request_assembly_id=excluded.chosen_request_assembly_id,
                 keep_candidates=excluded.keep_candidates, note=excluded.note,
                 version=excluded.version, created_at_ms=excluded.created_at_ms"""
        ).use { ps ->
            ps.setLong(1, captureId); ps.setLong(2, input.linkId)
            if (input.chosenRequestAssemblyId == null) ps.setNull(3, java.sql.Types.BIGINT)
            else ps.setLong(3, input.chosenRequestAssemblyId)
            ps.setInt(4, if (input.keepCandidates) 1 else 0); ps.setString(5, input.note)
            ps.setInt(6, next); ps.setLong(7, System.currentTimeMillis())
            ps.executeUpdate()
        }
        audit(captureId, "LINK", input.linkId.toString(), next,
            mapOf("chosen" to input.chosenRequestAssemblyId, "keepCandidates" to input.keepCandidates,
                "note" to input.note))
        conn.commit()
        return next
    }
}

private fun rsVersion(rs: ResultSet): Int = rs.getInt(1)

private fun Repository.recordConflict(
    captureId: Long, kind: String, key: String, expected: Int?, actual: Int?, payload: Map<String, Any?>
) {
    conn.prepareStatement(
        """INSERT INTO adjudication_conflict(capture_id,kind,target_key,expected_version,actual_version,payload_json,created_at_ms)
           VALUES(?,?,?,?,?,?,?)"""
    ).use { ps ->
        ps.setLong(1, captureId); ps.setString(2, kind); ps.setString(3, key)
        if (expected == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setInt(4, expected)
        if (actual == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, actual)
        ps.setString(6, Json.write(payload)); ps.setLong(7, System.currentTimeMillis())
        ps.executeUpdate()
    }
}

private fun Repository.audit(captureId: Long, kind: String, key: String, version: Int, payload: Map<String, Any?>) {
    conn.prepareStatement(
        """INSERT INTO adjudication_audit(capture_id,kind,target_key,version,payload_json,created_at_ms)
           VALUES(?,?,?,?,?,?)"""
    ).use { ps ->
        ps.setLong(1, captureId); ps.setString(2, kind); ps.setString(3, key)
        ps.setInt(4, version); ps.setString(5, Json.write(payload)); ps.setLong(6, System.currentTimeMillis())
        ps.executeUpdate()
    }
}

fun Repository.addHumanReset(captureId: Long, afterSeq: Long, canId: Int?): Long {
    synchronized(db) {
        val frames = loadFrames(captureId)
        require(afterSeq >= -1 && afterSeq < frames.size) { "重启边界位置越界: $afterSeq" }
        val ts = frames.firstOrNull { it.seq > afterSeq }?.tsUs
            ?: (frames.lastOrNull()?.tsUs?.plus(1) ?: 0L)
        val nextId = (conn.prepareStatement(
            "SELECT COALESCE(MAX(id)+1,0) FROM reset_boundary WHERE capture_id=?"
        ).use { ps ->
            ps.setLong(1, captureId)
            ps.executeQuery().use { if (it.next()) it.getLong(1) else 0L }
        })
        conn.prepareStatement(
            "INSERT INTO reset_boundary(id,capture_id,after_seq,ts_us,origin,can_id) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            ps.setLong(1, nextId); ps.setLong(2, captureId); ps.setLong(3, afterSeq)
            ps.setLong(4, ts); ps.setString(5, "human")
            if (canId == null) ps.setNull(6, java.sql.Types.INTEGER) else ps.setInt(6, canId)
            ps.executeUpdate()
        }
        audit(captureId, "RESET", afterSeq.toString(), 1, mapOf("canId" to canId, "tsUs" to ts))
        conn.commit()
        return nextId
    }
}

fun Repository.listConflicts(captureId: Long): List<Map<String, Any?>> =
    conn.prepareStatement(
        "SELECT id,kind,target_key,expected_version,actual_version,payload_json,created_at_ms " +
            "FROM adjudication_conflict WHERE capture_id=? ORDER BY id"
    ).use { ps ->
        ps.setLong(1, captureId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    mapOf(
                        "id" to rs.getLong(1), "kind" to rs.getString(2), "targetKey" to rs.getString(3),
                        "expectedVersion" to (rs.getObject(4)?.let { (it as Number).toInt() }),
                        "actualVersion" to (rs.getObject(5)?.let { (it as Number).toInt() }),
                        "payload" to (Json.parse("""{"x":${rs.getString(6)}}""").obj("x")?.map ?: emptyMap<String, Any?>()),
                        "createdAtMs" to rs.getLong(7)
                    )
                )
            }
        }
    }
