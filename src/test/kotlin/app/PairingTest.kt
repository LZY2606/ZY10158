package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PairingTest {

    private var counter = 0L

    private fun msg(
        t0: Long, canId: Int, dir: Direction, gen: Int, payload: ByteArray,
        status: MessageStatus = MessageStatus.COMPLETE
    ): Message {
        val id = ++counter
        val seq = (id * 10).toInt()
        return Message(
            id = id, runId = 0, captureId = 1,
            localKey = "m%03X-%s-g%d-f%d".format(canId, dir.name, gen, seq),
            canId = canId, direction = dir, gen = gen,
            firstFrameSeq = seq, lastFrameSeq = seq, startMs = t0, endMs = t0,
            multiFrame = false, expectedLength = payload.size, payload = payload,
            missingIndices = emptyList(), receivedFrameSeqs = listOf(seq),
            status = status, statusDetail = ""
        )
    }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun pair(messages: List<Message>, reviews: Map<String, AttributionReview> = emptyMap()) =
        Pairing(1, P2_TIMEOUT_MS, reviews).pair(EngineResult(messages, emptyList()), 10_000)

    @Test
    fun `pairs matching SID within P2 and ignores incomplete messages`() {
        val req = msg(0, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val broken = msg(5, 0x7E0, Direction.TX, 0, bytes(0x22), MessageStatus.INCOMPLETE)
        val resp = msg(20, 0x7E8, Direction.RX, 0, bytes(0x7E, 0x00))
        val result = pair(listOf(req, broken, resp))
        val byId = result.messages.associateBy { it.id }
        assertEquals(resp.id, byId.getValue(req.id).pairedMessageId)
        assertEquals(req.id, byId.getValue(resp.id).pairedMessageId)
        assertNull(byId.getValue(broken.id).pairedMessageId)
    }

    @Test
    fun `response after P2 half-open endpoint is a replay suspect, not paired`() {
        val req = msg(0, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x01))
        val stale = msg(600, 0x7E8, Direction.RX, 0, bytes(0x7E, 0x01)) // gap 600 > 500
        val result = pair(listOf(req, stale))
        val byId = result.messages.associateBy { it.id }
        assertNull(byId.getValue(stale.id).pairedMessageId)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PAIR_REPLAY_SUSPECT })
    }

    @Test
    fun `response exactly at P2 endpoint pairs (half-open)`() {
        val req = msg(0, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val resp = msg(500, 0x7E8, Direction.RX, 0, bytes(0x7E, 0x00))
        val result = pair(listOf(req, resp))
        assertEquals(resp.id, result.messages.first { it.id == req.id }.pairedMessageId)
    }

    @Test
    fun `response for never-requested SID is an orphan`() {
        // Negative SID 0x11 (negative response byte 0x7F marks a rejected service 0x11).
        val resp = msg(0, 0x7E8, Direction.RX, 0, bytes(0x7F, 0x11, 0x12))
        val unrelated = msg(0, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val result = pair(listOf(unrelated, resp))
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PAIR_ORPHAN_RESPONSE })
    }

    @Test
    fun `unanswered request beyond P2 is diagnosed`() {
        val req = msg(0, 0x7E0, Direction.TX, 0, bytes(0x22, 0xF1, 0x90))
        val result = pair(listOf(req))
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PAIR_UNANSWERED_REQUEST })
    }

    @Test
    fun `duplicate requests create two candidates with newest tentatively paired`() {
        val r1 = msg(300, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val r2 = msg(320, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val resp = msg(340, 0x7E8, Direction.RX, 0, bytes(0x7E, 0x00))
        val result = pair(listOf(r1, r2, resp))
        val byId = result.messages.associateBy { it.id }
        val respM = byId.getValue(resp.id)
        assertEquals(r2.id, respM.pairedMessageId)
        assertTrue(respM.candidateMessageIds.containsAll(listOf(r1.id.toInt(), r2.id.toInt())))
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PAIR_AMBIGUOUS })
        // older request must not claim the same response
        assertNull(byId.getValue(r1.id).pairedMessageId)
    }

    @Test
    fun `reviewed keepBoth suppresses automatic pairing`() {
        val r1 = msg(300, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val r2 = msg(320, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val resp = msg(340, 0x7E8, Direction.RX, 0, bytes(0x7E, 0x00))
        val reviews = mapOf(resp.localKey to AttributionReview(resp.localKey, null, true, "", 1, 1))
        val result = pair(listOf(r1, r2, resp), reviews)
        val respM = result.messages.first { it.id == resp.id }
        assertNull(respM.pairedMessageId)
        assertEquals(2, respM.candidateMessageIds.size)
    }

    @Test
    fun `different generations never pair`() {
        val req = msg(0, 0x7E0, Direction.TX, 0, bytes(0x3E, 0x00))
        val resp = msg(20, 0x7E8, Direction.RX, 1, bytes(0x7E, 0x00)) // ECU restarted
        val result = pair(listOf(req, resp))
        assertNull(result.messages.first { it.id == resp.id }.pairedMessageId)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.PAIR_ORPHAN_RESPONSE })
    }
}
