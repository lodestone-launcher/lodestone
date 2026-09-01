package com.github.lodestone.domain.model.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default arrangement, checked for the thing that is invisible in the numbers.
 *
 * A layout is ten centre points and ten sizes, and whether any two of them collide is not something
 * anyone can see by reading the list — it depends on the window's aspect, because the positions are
 * fractions and the sizes are not. Two buttons a tenth of the window apart are comfortably separate
 * across a 2670px panel and overlapping down a 1200px one, which is exactly how the right-hand
 * cluster came to sit on top of itself.
 */
class ControlLayoutTest {

    /**
     * Windows to check against, in density-independent pixels.
     *
     * The first is the panel the defaults were measured from — 2670x1200 at density 2.6 — so it is
     * the one that has to be exactly right. The others are a small phone and a tablet, which have
     * different aspects and so different collisions.
     */
    private val windows = listOf(
        "measured 2670x1200 @2.6" to (1027f to 462f),
        "small phone" to (640f to 360f),
        "tablet" to (1280f to 800f),
    )

    private data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun overlaps(other: Rect): Boolean =
            left < other.right && other.left < right && top < other.bottom && other.top < bottom
    }

    private fun ControlPlacement.rect(width: Float, height: Float): Rect {
        val half = size / 2f
        return Rect(x * width - half, y * height - half, x * width + half, y * height + half)
    }

    /** The row along the top edge, which is packed to Bedrock's own spacing. */
    private val topRow = setOf(ControlId.CHAT, ControlId.INVENTORY, ControlId.PAUSE, ControlId.DEBUG)

    private fun assertNoOverlaps(
        name: String,
        width: Float,
        height: Float,
        among: List<ControlPlacement>,
    ) {
        for (i in among.indices) {
            for (j in i + 1 until among.size) {
                val a = among[i]
                val b = among[j]
                assertTrue(
                    "$name: ${a.id} and ${b.id} overlap",
                    !a.rect(width, height).overlaps(b.rect(width, height)),
                )
            }
        }
    }

    @Test
    fun `no two controls overlap on the window the defaults were measured from`() {
        // Every control, not only the visible ones: hiding a button is a default, and turning one
        // on in the editor must not drop it on top of its neighbour.
        val (name, size) = windows.first()
        assertNoOverlaps(name, size.first, size.second, ControlLayout.Default.placements)
    }

    @Test
    fun `the controls a thumb reaches never overlap, on any window`() {
        // The top row is excluded on purpose, and this is the trade-off it records. Bedrock spaces
        // those three 29dp apart at 25dp across, a gap of four — and since positions are fractions
        // of the window while sizes are not, that gap closes on a narrower one. Matching Bedrock
        // exactly was the ask, so the spacing stays and the constraint is written down here rather
        // than discovered on a small phone.
        //
        // Nothing a thumb actually flies between is allowed that latitude.
        for ((name, size) in windows) {
            assertNoOverlaps(
                name,
                size.first,
                size.second,
                ControlLayout.Default.placements.filterNot { it.id in topRow },
            )
        }
    }

    @Test
    fun `the top row keeps Bedrock's spacing`() {
        // Read off Bedrock at 2670x1200: centres 76px apart on 64px buttons, which at that panel's
        // 2.6 density is 29.2dp apart on 24.6dp buttons. If either number drifts, the row stops
        // looking like Bedrock's, and this is what says so.
        val (width, _) = windows.first().second
        val row = listOf(ControlId.CHAT, ControlId.INVENTORY, ControlId.PAUSE)
            .map { ControlLayout.Default[it]!! }
        val spacing = (row[1].x - row[0].x) * width
        assertEquals(29.2f, spacing, 1.5f)
        assertEquals(spacing, (row[2].x - row[1].x) * width, 0.5f)
        row.forEach { assertEquals(25f, it.size, 1f) }
    }

    @Test
    fun `every control is fully on screen on the window the defaults were measured from`() {
        val (width, height) = windows.first().second
        for (placement in ControlLayout.Default.placements) {
            val rect = placement.rect(width, height)
            assertTrue(
                "${placement.id} runs off the left or top: $rect",
                rect.left >= 0f && rect.top >= 0f,
            )
            assertTrue(
                "${placement.id} runs off the right or bottom: $rect",
                rect.right <= width && rect.bottom <= height,
            )
        }
    }

    @Test
    fun `a layout written before a control existed gains it`() {
        val old = ControlLayout(ControlLayout.DEFAULT.filterNot { it.id == ControlId.INTERACT })
        val completed = old.completed()

        assertEquals(ControlLayout.DEFAULT.size, completed.placements.size)
        assertTrue(completed[ControlId.INTERACT] != null)
    }

    @Test
    fun `moving a control past an edge is clamped rather than lost`() {
        val stick = ControlLayout.Default[ControlId.STICK]!!
        val moved = ControlLayout.Default.with(stick.copy(x = -3f, y = 9f, size = 5_000f))
        val result = moved[ControlId.STICK]!!

        assertEquals(0f, result.x, 0f)
        assertEquals(1f, result.y, 0f)
        assertEquals(ControlPlacement.MAXIMUM_SIZE, result.size, 0f)
    }
}
