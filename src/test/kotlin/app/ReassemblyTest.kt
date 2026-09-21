package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReassemblyTest {

    private fun frame(seq: Int, t: Long, id: Int, dir: Direction, hex: String, gen: Int = 0) =
        EffectiveFrame(RawFrame(seq.toLong(), 1, seq, t, id, dir, Hex.decode(hex)), dir, false, gen, false)

    private fun completeMultiframe(): List<EffectiveFrame> = listOf(
        frame(0, 0, 0x7E8, Direction.RX, "101462F190415544"),
        frame(1, 5, 0x7E0, Direction.TX, "3000000000000000"),
        frame(2, 10, 0x7E8, Direction.RX, "2149563132333435"),
        frame(3, 20, 0x7E8, Direction.RX, "2236373839304142"),
        frame(4, 30, 0x7E8, Direction.RX, "2300000000000000"),
    )

    @Test
    fun `reassembles complete multi-frame message`() {
        val result = ReassemblyEngine(1).run(completeMultiframe(), 1000)
        val msg = result.messages.single()
        assertEquals(MessageStatus.COMPLETE, msg.status)
        assertEquals(20, msg.expectedLength)
        assertEquals(20, msg.payload.size)
        assertEquals("62f1904155444956313233343536373839304142", Hex.encode(msg.payload))
        assertTrue(msg.missingIndices.isEmpty())
    }

    @Test
    fun `flow control exactly at N_Bs endpoint is on time (half-open)`() {
        val frames = listOf(
            frame(0, 0, 0x7E8, Direction.RX, "100A010203040506"),
            frame(1, 100, 0x7E0, Direction.TX, "3000000000000000"), // exactly at t=100
            frame(2, 150, 0x7E8, Direction.RX, "210708090A000000"),
        )
        val result = ReassemblyEngine(1).run(frames, 1000)
        val msg = result.messages.single()
        assertEquals(MessageStatus.COMPLETE, msg.status, result.diagnostics.toString())
    }

    @Test
    fun `flow control one ms past N_Bs endpoint times out`() {
        val frames = listOf(
            frame(0, 1000, 0x7E8, Direction.RX, "100AB0B1B2B3B4B5"),
            frame(1, 1101, 0x7E0, Direction.TX, "3000000000000000"),
            frame(2, 1120, 0x7E8, Direction.RX, "2100000000000000"),
        )
        val result = ReassemblyEngine(1).run(frames, 5000)
        assertEquals(MessageStatus.TIMEOUT, result.messages.first().status)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.ISO_TIMEOUT_N_BS })
    }

    @Test
    fun `consecutive frame exactly at N_Cr endpoint is on time`() {
        // FC at t=10 (<=N_Bs), CF1 at t=110: exactly 100ms after FC, so still on time.
        // Capture virtual end at t=210 is also exactly N_Cr after CF1: no timeout fires.
        val frames = listOf(
            frame(0, 0, 0x7E8, Direction.RX, "1012C0C1C2C3C4C5"),
            frame(1, 10, 0x7E0, Direction.TX, "3000000000000000"),
            frame(2, 110, 0x7E8, Direction.RX, "21D0D1D2D3D4D5D6"),
        )
        val result = ReassemblyEngine(1).run(frames, 210)
        assertEquals(MessageStatus.INCOMPLETE, result.messages.first().status)
        assertFalse(result.diagnostics.any { it.code == DiagnosticCode.ISO_TIMEOUT_N_CR })
    }

    @Test
    fun `identical CF retransmit keeps evidence without duplicating bytes`() {
        // 20-byte transfer: FF(6) + CF1(7) + retransmitted CF1(identical) + CF2(7)
        val frames = listOf(
            frame(0, 0, 0x7E8, Direction.RX, "1014B0B1B2B3B4B5"),
            frame(1, 5, 0x7E0, Direction.TX, "3000000000000000"),
            frame(2, 10, 0x7E8, Direction.RX, "2101020304050607"),
            frame(3, 12, 0x7E8, Direction.RX, "2101020304050607"), // identical retransmit
            frame(4, 20, 0x7E8, Direction.RX, "2208090A0B0C0D0E"),
        )
        val result = ReassemblyEngine(1).run(frames, 1000)
        val msg = result.messages.single()
        assertEquals(MessageStatus.COMPLETE, msg.status)
        assertEquals(20, msg.payload.size)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.ISO_DUPLICATE_RETRANSMIT })
        // retransmitted 7 bytes were not appended a second time
        assertFalse(Hex.encode(msg.payload).contains("0102030405060701020304050607"))
    }

    @Test
    fun `same CF index with different payload conflicts and never overwrites`() {
        val frames = listOf(
            frame(0, 0, 0x7E8, Direction.RX, "1014B0B1B2B3B4B5"),
            frame(1, 5, 0x7E0, Direction.TX, "3000000000000000"),
            frame(2, 10, 0x7E8, Direction.RX, "2101020304050607"),
            frame(3, 20, 0x7E8, Direction.RX, "21FFEEDDCCBBAA99"), // conflicting index 1
        )
        val result = ReassemblyEngine(1).run(frames, 1000)
        val msg = result.messages.single()
        assertEquals(MessageStatus.CONFLICT, msg.status)
        // first payload preserved, conflicting later bytes never overwrite
        assertEquals("b0b1b2b3b4b501020304050607", Hex.encode(msg.payload))
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.ISO_SEQUENCE_CONFLICT })
    }

    @Test
    fun `missing sequence index is retained, message stays incomplete`() {
        // 27-byte DTC response: 6 FF bytes + 3 CFs (7,7,7). Slot for CF index 2 is dropped.
        val frames = listOf(
            frame(0, 0, 0x7E8, Direction.RX, "101B590208010203"),
            frame(1, 5, 0x7E0, Direction.TX, "3000000000000000"),
            frame(2, 10, 0x7E8, Direction.RX, "210405060708090A"),
            // CF index 2 dropped on the bus
            frame(3, 20, 0x7E8, Direction.RX, "2312131415161718"),
        )
        val result = ReassemblyEngine(1).run(frames, 30)
        val msg = result.messages.first { it.multiFrame }
        assertEquals(MessageStatus.INCOMPLETE, msg.status)
        assertTrue(2 in msg.missingIndices)
        // bytes from the out-of-order CF3 are retained as evidence
        assertEquals("12131415161718", Hex.encode(msg.payload).takeLast(14))
    }

    @Test
    fun `frames across ECU restart are never concatenated`() {
        val frames = listOf(
            frame(0, 300, 0x7E8, Direction.RX, "100FB0B1B2B3B4B5", gen = 0),
            frame(1, 310, 0x7E0, Direction.TX, "3000000000000000", gen = 0),
            frame(2, 320, 0x7E8, Direction.RX, "2101020304050607", gen = 0),
            frame(3, 500, 0x7E8, Direction.RX, "2208090A0B0C0D0E", gen = 1),
        )
        val result = ReassemblyEngine(1).run(frames, 5000)
        val gens = result.messages.groupBy { it.gen }
        val g0 = gens.getValue(0).single()
        assertEquals(MessageStatus.INCOMPLETE, g0.status)
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.ISO_CROSS_RESTART_TRUNCATION })
        // gen-1 lone CF is unexpected, not appended to gen-0 payload
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.ISO_UNEXPECTED_FRAME })
        assertFalse(Hex.encode(g0.payload).contains("08090a0b0c0d0e"))
    }

    @Test
    fun `rejected frames are excluded but recorded`() {
        val ef = frame(0, 0, 0x7E0, Direction.TX, "023E000000000000").copy(rejected = true)
        val result = ReassemblyEngine(1).run(listOf(ef), 1000)
        assertTrue(result.messages.isEmpty())
        assertTrue(result.diagnostics.any { it.code == DiagnosticCode.FRAME_REJECTED })
    }
}
