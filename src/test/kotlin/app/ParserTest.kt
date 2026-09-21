package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ParserTest {
    @Test
    fun `parses frames and restart directives`() {
        val parsed = CaptureParser.parse("demo", """
            # restart at=500 id=7E8 power cycle
            100 7E0 TX 02 10 03 00 00 00 00 00
            110 7E8 RX 02 50 03 00 00 00 00 00
        """.trimIndent())

        assertEquals(2, parsed.frames.size)
        assertEquals(1, parsed.directives.size)
        with(parsed.directives[0]) {
            assertEquals(500L, offsetMs)
            assertEquals(0x7E8, scopeCanId)
        }
        assertEquals(Direction.TX, parsed.frames[0].direction)
        assertEquals(0x02, parsed.frames[0].data[0].toInt() and 0xFF)
        assertTrue(parsed.fileSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `rejects decreasing timestamps and bad dlc`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureParser.parse("x", "100 7E0 TX 02 10\n90 7E0 TX 02 10")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureParser.parse("x", "100 7E0 TX")
        }
    }

    @Test
    fun `isotp classification and helpers`() {
        assertEquals(FrameType.SF, IsoTp.classify(Hex.decode("023E000000000000")))
        assertEquals(FrameType.FF, IsoTp.classify(Hex.decode("101462F190415544")))
        assertEquals(FrameType.CF, IsoTp.classify(Hex.decode("2149563132333435")))
        assertEquals(FrameType.FC, IsoTp.classify(Hex.decode("3000000000000000")))
        assertEquals(20, IsoTp.ffLength(Hex.decode("1014000000000000")))
        assertEquals(1, IsoTp.cfIndex(Hex.decode("2100")))
        assertEquals(0x7E8, IsoTp.peerCanId(0x7E0))
        assertEquals(0x7E0, IsoTp.peerCanId(0x7E8))
    }

    @Test
    fun `hex roundtrip`() {
        val bytes = byteArrayOf(0, 15, 16, -1, 0x7F)
        assertEquals("000f10ff7f", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("00 0f 10 ff 7f"))
    }
}
