@file:OptIn(ExperimentalStdlibApi::class)
package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PairingAndParserTest {

    private fun assemble(text: String): ReassemblyResult {
        val parsed = CaptureParser.parse(text)
        return Reassembler().run(parsed.frames, parsed.resets)
    }

    @Test
    fun `parser supports extended and candump formats`() {
        val a = CaptureParser.parse("0.002000 7E0 REQ 02 10 03")
        assertEquals(1, a.frames.size)
        assertEquals(0x7E0, a.frames[0].canId)
        assertEquals(Direction.REQ, a.frames[0].declaredDirection)
        assertEquals(2000L, a.frames[0].tsUs)

        val b = CaptureParser.parse("(1.500000) can0 7E8#0250030000000000")
        assertEquals(Direction.RESP, b.frames[0].declaredDirection)
        assertEquals(1_500_000L, b.frames[0].tsUs)
        assertEquals("0250030000000000", b.frames[0].data.toHexCompact())

        val r = CaptureParser.parse("#RESET 0.25 id=0x7E0\n0.3 7E0 REQ 02 10 03")
        assertEquals(1, r.resets.size)
        assertEquals(0x7E0, r.resets[0].canId)
    }

    @Test
    fun `parser is idempotent by content hash`() {
        val p1 = CaptureParser.parse("0.0 7E0 REQ 02 10 03")
        val p2 = CaptureParser.parse("0.0 7E0 REQ 02 10 03")
        assertEquals(p1.sha256, p2.sha256)
        assertNotEquals(p1.sha256, CaptureParser.parse("0.1 7E0 REQ 02 10 03").sha256)
    }

    @Test
    fun `positive response is MATCHED`() {
        val text = """
            0.0 7E0 REQ 02 10 03
            0.002 7E8 RESP 02 50 03 00 00 00 00 00
        """.trimIndent()
        val r = assemble(text)
        val links = PairingEngine.pair(r.assemblies)
        assertEquals(1, links.size)
        assertEquals(LinkStatus.MATCHED, links[0].status)
        assertEquals(0x10, links[0].serviceId)
    }

    @Test
    fun `negative response is NEGATIVE with NRC`() {
        val text = """
            0.0 7E0 REQ 02 11 01
            0.002 7E8 RESP 03 7F 11 22 00 00 00 00
        """.trimIndent()
        val links = PairingEngine.pair(assemble(text).assemblies)
        assertEquals(LinkStatus.NEGATIVE, links[0].status)
        assertEquals(0x22, links[0].nrc)
    }

    @Test
    fun `orphan request and orphan response are reported`() {
        val text = """
            0.0 7E0 REQ 02 10 03
            0.002 7D8 RESP 03 7F 19 13 00 00 00 00
        """.trimIndent()
        val links = PairingEngine.pair(assemble(text).assemblies)
        assertTrue(links.any { it.status == LinkStatus.ORPHAN_REQUEST })
        assertTrue(links.any { it.status == LinkStatus.ORPHAN_RESPONSE })
    }

    @Test
    fun `replayed old response after reset is not paired as fresh`() {
        val text = """
            0.0 7E0 REQ 02 10 03
            0.002 7E8 RESP 02 50 03 00 00 00 00 00
            #RESET 0.05
            0.06 7E8 RESP 02 50 03 00 00 00 00 00
        """.trimIndent()
        val r = assemble(text)
        val links = PairingEngine.pair(r.assemblies)
        // 第 1 代响应没有第 1 代请求：REPLAY_RESPONSE 或 ORPHAN_RESPONSE，绝不与第 0 代请求配对
        val stale = links.filter { it.responseAssemblyId != null }
        assertFalse(stale.any {
            it.status == LinkStatus.MATCHED &&
                r.assemblies.first { a -> a.id == it.requestAssemblyId }.generation == 0 &&
                r.assemblies.first { a -> a.id == it.responseAssemblyId }.generation == 1
        })
    }

    @Test
    fun `two close responses produce AMBIGUOUS with two candidates`() {
        val text = """
            0.0 7E0 REQ 02 10 03
            0.002 7E8 RESP 02 50 03 00 00 00 00 00
            0.003 7E8 RESP 02 50 03 00 00 00 00 00
        """.trimIndent()
        val links = PairingEngine.pair(assemble(text).assemblies)
        val reqLink = links.first { it.requestAssemblyId != null }
        assertEquals(LinkStatus.AMBIGUOUS, reqLink.status)
        assertEquals(2, reqLink.candidates.size)
    }

    @Test
    fun `incomplete response never reports success`() {
        val text = """
            0.0 7E0 REQ 03 22 F1 93 00 00 00 00
            0.001 7E8 RESP 10 14 62 F1 93 11 22 33
            0.002 7E0 RESP 30 00 00
            0.003 7E8 RESP 21 55 66 77 88 99 AA BB
        """.trimIndent()
        val r = assemble(text)
        val resp = r.assemblies.first { it.direction == Direction.RESP }
        assertFalse(resp.complete)
        assertNotEquals(AssemblyStatus.COMPLETE, resp.status)
        assertTrue(resp.missing.isNotEmpty())
    }
}
