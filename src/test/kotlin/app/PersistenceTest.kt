package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PersistenceTest {

    @TempDir
    @JvmField
    var tmp: Path? = null

    private fun db(): Triple<Database, Repository, ViewService> {
        val d = Database(tmp!!.resolve("t.sqlite").toString())
        val r = Repository(d)
        return Triple(d, r, ViewService(r))
    }

    @Test
    fun `import is idempotent by capture hash`() {
        val (_, repo, service) = db()
        val a = repo.importCapture("a.log", "0.0 7E0 REQ 02 10 03\n")
        service.recompute(a.captureId)
        val b = repo.importCapture("different-name.log", "0.0 7E0 REQ 02 10 03\n")
        assertEquals(a.captureId, b.captureId)
        assertTrue(b.alreadyExisted)
        assertEquals(1, repo.listCaptures().size)
    }

    @Test
    fun `machine run is only visible after atomic publish`() {
        val (_, repo, service) = db()
        val imp = repo.importCapture("a.log", "0.0 7E0 REQ 02 10 03\n")
        val runId = service.recompute(imp.captureId)
        val stored = repo.loadMachineRun(imp.captureId, runId)
        assertNotNull(stored)
        // 诊断与状态同事务可见
        assertNotNull(stored!!.assemblies.firstOrNull { it.status == AssemblyStatus.COMPLETE })
    }

    @Test
    fun `human adjudication survives parser rerun`() {
        val (_, repo, service) = db()
        val imp = repo.importCapture("a.log", """
            0.0 7E0 REQ 02 10 03
            0.002 7E8 RESP 02 50 03 00 00 00 00 00
        """.trimIndent() + "\n")
        service.recompute(imp.captureId)
        repo.submitFrameAdjudication(imp.captureId, FrameAdjInput(1, true, "污染帧", null), null)
        service.recompute(imp.captureId) // 新解析器重跑
        val view = service.effectiveView(imp.captureId)!!
        assertTrue(view.frames[1].rejected)
        assertEquals("污染帧", view.frames[1].rejectReason)
        // 响应不再参与成功配对
        assertTrue(view.assemblies.none { it.assembly.direction == Direction.RESP && !it.rejected && it.assembly.complete })
    }

    @Test
    fun `stale adjudication version conflicts and is persisted, never silently lost`() {
        val (_, repo, _) = db()
        val imp = repo.importCapture("a.log", "0.0 7E0 REQ 02 10 03\n")
        val v1 = repo.submitFrameAdjudication(imp.captureId, FrameAdjInput(0, true, "x", null), null)
        assertEquals(1, v1)
        // 另一路基于 v1 之后又有写入，再拿旧版本号提交
        repo.submitFrameAdjudication(imp.captureId, FrameAdjInput(0, false, null, null), 1)
        val ex = assertThrows(ConflictException::class.java) {
            repo.submitFrameAdjudication(imp.captureId, FrameAdjInput(0, true, "late", null), 1)
        }
        assertTrue(ex.message!!.contains("版本冲突"))
        val conflicts = repo.listConflicts(imp.captureId)
        assertTrue(conflicts.isNotEmpty())
        assertEquals("FRAME", conflicts.first()["kind"])
    }

    @Test
    fun `role override changes direction and generation isolation stays intact`() {
        val (_, repo, service) = db()
        val imp = repo.importCapture("a.log", """
            0.0 7E0 REQ 02 10 03
            0.002 7E8 RESP 02 50 03 00 00 00 00 00
        """.trimIndent() + "\n")
        service.recompute(imp.captureId)
        repo.submitFrameAdjudication(
            imp.captureId,
            FrameAdjInput(1, false, null, Direction.REQ), null
        )
        service.recompute(imp.captureId)
        val view = service.effectiveView(imp.captureId)!!
        assertEquals(Direction.REQ, view.frames[1].effectiveDirection)
    }

    @Test
    fun `concurrent link adjudications never silently disappear`() {
        val (_, repo, service) = db()
        val text = """
            0.0 7E0 REQ 02 10 03
            0.002 7E8 RESP 02 50 03 00 00 00 00 00
        """.trimIndent() + "\n"
        val imp = repo.importCapture("a.log", text)
        service.recompute(imp.captureId)
        val linkId = service.effectiveView(imp.captureId)!!.links.first().link.id

        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val ok = AtomicInteger(0)
        val conflicted = AtomicInteger(0)
        repeat(n) { idx ->
            pool.submit {
                start.await()
                try {
                    repo.submitLinkAdjudication(
                        imp.captureId,
                        LinkAdjInput(linkId, null, idx % 2 == 0, "note-$idx"),
                        null
                    )
                    ok.incrementAndGet()
                } catch (e: ConflictException) {
                    conflicted.incrementAndGet()
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        // 无条件版本提交：全部成功（null expectedVersion），最终版本为 n
        assertEquals(n, ok.get())
        val adj = repo.loadLinkAdjudications(imp.captureId).single()
        assertEquals(n, adj.version)
    }
}

class FixtureEndToEndTest {
    @Test
    fun `all fixtures classify expected edge cases`() {
        val base = java.io.File("fixtures")
        val cases = listOf(
            "01_restart_and_replay.log" to listOf("MATCHED", "NEGATIVE"),
            "02_timeout_boundaries.log" to listOf("TIMEOUT_FC", "TIMEOUT_CF"),
            "03_duplicates_conflicts_incomplete.log" to listOf("SN_CONFLICT")
        )
        for ((name, _) in cases) {
            val parsed = CaptureParser.parse(java.io.File(base, name).readText())
            val result = Reassembler().run(parsed.frames, parsed.resets)
            val links = PairingEngine.pair(result.assemblies)
            when (name) {
                "01_restart_and_replay.log" -> {
                    assertTrue(links.any { it.status == LinkStatus.MATCHED })
                    assertTrue(links.any { it.status == LinkStatus.NEGATIVE })
                    assertTrue(result.generations.any { it.canId == 0x7E8 && it.generation >= 1 })
                }
                "02_timeout_boundaries.log" -> {
                    assertTrue(result.assemblies.any { it.status == AssemblyStatus.TIMEOUT_FC })
                    assertTrue(result.assemblies.any { it.status == AssemblyStatus.TIMEOUT_CF })
                    assertTrue(result.diagnostics.any { it.code == "FLOW_CONTROL_ABORT" })
                }
                "03_duplicates_conflicts_incomplete.log" -> {
                    assertTrue(result.diagnostics.any { it.code == "SN_CONFLICT" })
                    assertTrue(result.diagnostics.any { it.code == "CF_RETRANSMIT" })
                    assertTrue(result.diagnostics.any { it.code == "INVALID_PCI" })
                    assertTrue(result.assemblies.any { !it.complete && it.missing.isNotEmpty() })
                }
            }
        }
    }
}
