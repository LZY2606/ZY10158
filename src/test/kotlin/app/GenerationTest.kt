package app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GenerationTest {

    private fun analyzer() = Analyzer(1)

    private fun frames(vararg pairs: Pair<Long, Int>): List<RawFrame> =
        pairs.mapIndexed { i, (t, id) ->
            RawFrame(i.toLong(), 1, i, t, id, if (id < 0x7E8) Direction.TX else Direction.RX,
                Hex.decode("023E000000000000"))
        }

    @Test
    fun `unscoped boundary at offset starts new gen for every id, frame exactly on boundary is new gen`() {
        val a = analyzer()
        val boundaries = listOf(Analyzer.Boundary(500L, null))
        val eff = a.assignGenerationsPublic(frames(100L to 0x7E0, 500L to 0x7E0, 600L to 0x7E8), emptyMap(), boundaries)
        assertEquals(listOf(0, 1, 1), eff.map { it.gen })
    }

    @Test
    fun `scoped boundary only bumps that CAN id`() {
        val a = analyzer()
        val boundaries = listOf(Analyzer.Boundary(500L, 0x7E8))
        val eff = a.assignGenerationsPublic(
            frames(100L to 0x7E0, 500L to 0x7E8, 510L to 0x7E0), emptyMap(), boundaries)
        assertEquals(listOf(0, 1, 0), eff.map { it.gen })
    }

    @Test
    fun `role override changes effective direction and rejection excludes`() {
        val a = analyzer()
        val fs = frames(100L to 0x7E0)
        val reviews = mapOf(0L to FrameReview(0L, rejected = true, roleOverride = Direction.RX, note = "x", version = 1, updatedAtMs = 1))
        val eff = a.assignGenerationsPublic(fs, reviews, emptyList())
        assertEquals(Direction.RX, eff.single().effectiveDirection)
        assertTrue(eff.single().rejected)
    }
}
