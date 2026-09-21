package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClockTest {
    @Test
    fun `manual clock is deterministic and cannot go backwards`() {
        val clock = ManualClock(100)
        assertEquals(100, clock.nowMs())
        clock.advance(50)
        assertEquals(150, clock.nowMs())
        assertThrows(IllegalArgumentException::class.java) { clock.set(149) }
        clock.set(150)
        assertEquals(150, clock.nowMs())
    }

    @Test
    fun `service can switch clocks and reassembly uses captured timestamps only`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val db = Database(java.io.File(dir.toFile(), "c.sqlite").absolutePath)
        val repo = Repository(db)
        val service = AnalysisService(repo, ManualClock(0L))
        val (capture, created) = service.import("x.cand",
            "100 7E0 TX 02 3E 00 00 00 00 00 00\n700 7E0 TX 02 3E 01 00 00 00 00 00\n")
        assertTrue(created)
        // Advancing the injected virtual clock to 5000ms must not move diagnostics: the
        // unanswered diagnostic is anchored to captured offsets (100 + P2 = 600).
        (service.clock as ManualClock).set(5000)
        val run = service.rerun(capture.id)
        val diags = repo.loadDiagnostics(run.id)
        val unanswered = diags.first {
            it.code == DiagnosticCode.PAIR_UNANSWERED_REQUEST && it.messageLocalKey!!.endsWith("f0")
        }
        assertEquals(600L, unanswered.offsetMs)
        db.close()
    }
}
