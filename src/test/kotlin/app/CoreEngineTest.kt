package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreEngineTest {

    private fun runText(text: String) = CaptureParser.parse(text).let {
        Reassembler().run(it.frames, it.resets)
    }

    @Test
    fun `single frame completes`() {
        val result = runText("0.0 7E0 REQ 02 10 03")
        val a = result.assemblies.single()
        assertEquals(AssemblyStatus.COMPLETE, a.status)
        assertEquals("1003", a.payload.toHexCompact())
        assertTrue(a.complete)
    }

    @Test
    fun `happy path multi-frame with flow control`() {
        val text = """
0.0 7E0 REQ 03 22 F1 90 00 00 00 00
0.001 7E8 RESP 10 0C 62 F1 90 11 22 33
0.002 7E0 RESP 30 00 00
0.003 7E8 RESP 21 44 55 66 77 88 99 AA
0.004 7E8 RESP 22 CC DD EE FF 00 11 22
        """.trimIndent()
        val completed = runText(text).assemblies.filter { it.direction == Direction.RESP && it.complete }
        assertEquals(1, completed.size)
        assertEquals(12, completed[0].receivedLen)
        assertEquals("62F190112233445566778899AABBCCDDEEFF0011".take(24), completed[0].payload.toHexCompact())
    }

    @Test
    fun `frame exactly at FC deadline is a timeout (half-open window)`() {
        val text = """
0.0 7E0 REQ 10 0A 2E F1 90 01 02 03
0.1 7E8 REQ 30 00 00
        """.trimIndent()
        val parsed = CaptureParser.parse(text)
        val result = Reassembler(ReassemblyConfig(fcTimeoutUs = 100_000)).run(parsed.frames, parsed.resets)
        val a = result.assemblies.first { it.direction == Direction.REQ }
        assertEquals(AssemblyStatus.TIMEOUT_FC, a.status)
        assertEquals("FLOW_CONTROL_TIMEOUT", a.errorCode)
        assertTrue(result.diagnostics.any { it.code == "FLOW_CONTROL_TIMEOUT" })
    }

    @Test
    fun `frame one microsecond before FC deadline succeeds`() {
        val text = """
0.0 7E8 RESP 10 0A 62 F1 90 01 02 03
0.099999 7E0 RESP 30 00 00
0.100000 7E8 RESP 21 04 05 06 07 08 09
        """.trimIndent()
        val parsed = CaptureParser.parse(text)
        val result = Reassembler(ReassemblyConfig(fcTimeoutUs = 100_000)).run(parsed.frames, parsed.resets)
        val a = result.assemblies.first { it.direction == Direction.RESP }
        assertEquals(AssemblyStatus.COMPLETE, a.status)
    }

    @Test
    fun `CF exactly at CR deadline times out`() {
        val text = """
0.0 7E8 RESP 10 0E 62 F1 90 02 11 22
0.001 7E0 RESP 30 00 00
0.002 7E8 RESP 21 44 55 66 77 88 99 AA
0.253 7E8 RESP 22 CC DD EE FF 00 00 00
        """.trimIndent()
        val parsed = CaptureParser.parse(text)
        val result = Reassembler(ReassemblyConfig(crTimeoutUs = 250_000)).run(parsed.frames, parsed.resets)
        val a = result.assemblies.first { it.direction == Direction.RESP }
        assertEquals(AssemblyStatus.TIMEOUT_CF, a.status)
        assertTrue(a.missing.isNotEmpty(), "不完整重组必须保留缺失序号")
        assertTrue(a.receivedLen > 0 && a.receivedLen < a.declaredLen!!)
        assertFalse(a.complete)
    }

    @Test
    fun `identical duplicate CF is retransmit evidence and completes`() {
        val text = """
0.0 7E0 REQ 03 22 F1 90 00 00 00 00
0.001 7E8 RESP 10 14 62 F1 90 11 22 33
0.002 7E0 RESP 30 00 00
0.003 7E8 RESP 21 44 55 66 77 88 99 AA
0.004 7E8 RESP 22 CC DD EE FF 00 11 22
0.005 7E8 RESP 22 CC DD EE FF 00 11 22
        """.trimIndent()
        val a = runText(text).assemblies.first { it.direction == Direction.RESP }
        assertTrue(a.evidence.any { it.kind == "retransmit" }, "重传证据必须保留")
        assertEquals(20, a.declaredLen)
    }

    @Test
    fun `same SN with different payload is a conflict and never overwritten`() {
        val text = """
0.0 7E0 REQ 03 22 F1 91 00 00 00 00
0.001 7E8 RESP 10 14 62 F1 91 11 22 33
0.002 7E0 RESP 30 00 00
0.003 7E8 RESP 21 44 55 66 77 88 99 AA
0.004 7E8 RESP 22 CC DD EE FF 00 11 22
0.005 7E8 RESP 22 DE AD BE EF 00 11 22
        """.trimIndent()
        val a = runText(text).assemblies.first { it.direction == Direction.RESP }
        assertEquals(AssemblyStatus.SN_CONFLICT, a.status)
        assertTrue(a.payload.toHexCompact().contains("CCDDEEFF001122"))
        assertFalse(a.payload.toHexCompact().contains("DEADBEEF"))
    }

    @Test
    fun `out of order CFs buffered and completed`() {
        val text = """
0.0 7E0 REQ 03 22 F1 92 00 00 00 00
0.001 7E8 RESP 10 0C 62 F1 92 11 22 33
0.002 7E0 RESP 30 00 00
0.003 7E8 RESP 22 CC DD EE FF 00 11 22
0.004 7E8 RESP 21 44 55 66 77 88 99 AA
        """.trimIndent()
        val a = runText(text).assemblies.first { it.direction == Direction.RESP }
        assertEquals(AssemblyStatus.COMPLETE, a.status)
        assertEquals(12, a.receivedLen)
    }

    @Test
    fun `frames across ECU reset are never stitched together`() {
        val text = """
0.0 7E8 RESP 10 0C 62 F1 94 11 22 33
0.001 7E0 RESP 30 00 00
#RESET 0.002
0.003 7E8 RESP 21 44 55 66 77 88 99 AA
0.004 7E8 RESP 22 CC DD EE FF 00 11 22
        """.trimIndent()
        val parsed = CaptureParser.parse(text)
        val result = Reassembler().run(parsed.frames, parsed.resets)
        val respAsms = result.assemblies.filter { it.direction == Direction.RESP }
        assertTrue(respAsms.any { it.status == AssemblyStatus.ABORTED })
        assertTrue(respAsms.none { it.complete && it.generation == 0 && it.receivedLen == 12 })
        assertTrue(result.generations.any { it.canId == 0x7E8 && it.generation == 1 })
    }

    @Test
    fun `invalid PCI frame is diagnostic and ignored`() {
        val result = runText("0.0 7D8 RESP 07 12 34 00 00 00 00 00")
        assertTrue(result.diagnostics.any { it.code == "INVALID_PCI" })
        assertTrue(result.assemblies.none { it.complete })
    }
}
