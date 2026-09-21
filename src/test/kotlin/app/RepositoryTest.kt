package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class RepositoryTest {

    private fun newDb(dir: Path): Database {
        Class.forName("org.sqlite.JDBC")
        return Database(File(dir.toFile(), "test.sqlite").absolutePath)
    }

    private val sample = """
        100 7E0 TX 02 10 03 00 00 00 00 00
        110 7E8 RX 02 50 03 00 00 00 00 00
    """.trimIndent()

    @Test
    fun `import is idempotent on capture file hash`(@TempDir dir: Path) {
        val db = newDb(dir)
        val repo = Repository(db)
        val parsed1 = CaptureParser.parse("a.cand", sample)
        val (c1, created1) = repo.importCapture(parsed1, 1000)
        assertTrue(created1)
        val parsed2 = CaptureParser.parse("different-name.cand", sample)
        val (c2, created2) = repo.importCapture(parsed2, 2000)
        assertFalse(created2)
        assertEquals(c1.id, c2.id)
        db.close()
    }

    @Test
    fun `run publishes messages and diagnostics atomically and versions increment`(@TempDir dir: Path) {
        val db = newDb(dir)
        val repo = Repository(db)
        val service = AnalysisService(repo, ManualClock(1000))
        val (capture, created) = service.import("a.cand", sample)
        assertTrue(created)
        val run = repo.latestPublishedRun(capture.id)!!
        assertTrue(run.published)
        val messages = repo.loadMessages(run.id)
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.status == MessageStatus.COMPLETE })
        // pairing: 10 03 request -> 50 03 response
        val req = messages.first { it.direction == Direction.TX }
        val resp = messages.first { it.direction == Direction.RX }
        assertEquals(resp.id, req.pairedMessageId)
        assertEquals(req.id, resp.pairedMessageId)
        db.close()
    }

    @Test
    fun `optimistic concurrency conflict is surfaced not silently overwritten`(@TempDir dir: Path) {
        val db = newDb(dir)
        val repo = Repository(db)
        val (capture, _) = repo.importCapture(CaptureParser.parse("a.cand", sample), 1000)
        val frames = repo.loadFrames(capture.id)
        val v1 = repo.upsertFrameReview(frames[0].id, capture.id, true, null, "polluted", null, 100)
        assertEquals(1, v1.version)
        // create while a row already exists -> conflict
        assertThrows(ConflictException::class.java) {
            repo.upsertFrameReview(frames[0].id, capture.id, false, null, "clobber", null, 200)
        }
        // a second reviewer commits first, advancing the version to 2
        val v2 = repo.upsertFrameReview(frames[0].id, capture.id, true, Direction.RX, "role fix", 1, 250)
        assertEquals(2, v2.version)
        // stale writer still holding expectedVersion=1 must now lose the race instead of overwriting
        assertThrows(ConflictException::class.java) {
            repo.upsertFrameReview(frames[0].id, capture.id, false, null, "stale", 1, 300)
        }
        val still = repo.getFrameReview(frames[0].id)!!
        assertTrue(still.rejected)
        assertEquals("role fix", still.note)
        // correct current version wins
        val v3 = repo.upsertFrameReview(frames[0].id, capture.id, false, null, "cleaned", 2, 400)
        assertEquals(3, v3.version)
        assertFalse(repo.getFrameReview(frames[0].id)!!.rejected)
        db.close()
    }

    @Test
    fun `restart marks bump review version and trigger new run after rerun`(@TempDir dir: Path) {
        val db = newDb(dir)
        val repo = Repository(db)
        val service = AnalysisService(repo, ManualClock(1000))
        val content = """
            100 7E0 TX 02 3E 00 00 00 00 00 00
            110 7E8 RX 02 7E 00 00 00 00 00 00
            520 7E0 TX 02 3E 00 00 00 00 00 00
            530 7E8 RX 02 7E 00 00 00 00 00 00
        """.trimIndent()
        val (capture, _) = service.import("g.cand", content)
        val run1 = repo.latestPublishedRun(capture.id)!!
        repo.addRestartMark(capture.id, 500, null, "manual boundary", 1500)
        service.rerun(capture.id)
        val run2 = repo.latestPublishedRun(capture.id)!!
        assertEquals(run1.runNumber + 1, run2.runNumber)
        assertEquals(2L, run2.reviewVersionAtRun)
        val messages = repo.loadMessages(run2.id)
        val gens = messages.map { it.gen }.toSet()
        assertTrue(gens.containsAll(listOf(0, 1)))
        // request/response pair only inside same generation: 2 pairs total, no cross-gen pairing
        val paired = messages.filter { it.pairedMessageId != null }
        assertEquals(4, paired.size)
        for (m in paired) {
            val partner = messages.first { it.id == m.pairedMessageId }
            assertEquals(m.gen, partner.gen)
        }
        db.close()
    }

    @Test
    fun `attribution review survives a re-parse run`(@TempDir dir: Path) {
        val db = newDb(dir)
        val repo = Repository(db)
        val service = AnalysisService(repo, ManualClock(1000))
        val content = """
            300 7E0 TX 02 3E 00 00 00 00 00 00
            320 7E0 TX 02 3E 00 00 00 00 00 00
            340 7E8 RX 02 7E 00 00 00 00 00 00
        """.trimIndent()
        val (capture, _) = service.import("amb.cand", content)
        val run1 = repo.latestPublishedRun(capture.id)!!
        val resp = repo.loadMessages(run1.id).first { it.direction == Direction.RX }
        repo.upsertAttributionReview(capture.id, resp.localKey, null, true, "keep both sessions", null, 2000)
        service.rerun(capture.id)
        val run2 = repo.latestPublishedRun(capture.id)!!
        val resp2 = repo.loadMessages(run2.id).first { it.direction == Direction.RX }
        assertNull(resp2.pairedMessageId, "keepBoth must suppress automatic pairing after re-parse")
        assertEquals(2, resp2.candidateMessageIds.size)
        db.close()
    }
}
