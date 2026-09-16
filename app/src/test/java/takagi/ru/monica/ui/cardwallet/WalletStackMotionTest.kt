package takagi.ru.monica.ui.cardwallet

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStackMotionTest {
    private val width = 360f
    private val height = 225f
    private fun pose(index: Int, position: Float) = walletStackPose(index, position, width, height, 400f, 400f)

    @Test fun `faces are separated when their drawing order changes in either direction`() {
        for (base in 0..12) {
            for (fraction in listOf(0.48f, 0.499f, 0.5f, 0.501f, 0.52f)) {
                val outgoing = pose(base, base + fraction)
                val incoming = pose(base + 1, base + fraction)
                assertTrue("Faces intersect at $base + $fraction",
                    outgoing.y + height * outgoing.scale < incoming.y)
            }
        }
    }

    @Test fun `departing face lifts above its destination before tucking into the back slot`() {
        val front = pose(0, 0f)
        val lifted = pose(0, 0.55f)
        val behind = pose(0, 1f)
        assertTrue(lifted.y < behind.y)
        assertTrue(behind.y < front.y)
        assertTrue(behind.scale < lifted.scale)
        val incomingSteps = (0..100).map { pose(1, it / 100f).y }
        assertTrue(incomingSteps.zipWithNext().all { (first, second) -> second <= first })
    }

    @Test fun `paths and tangents remain continuous across focus and back slot boundaries`() {
        for (boundary in listOf(-1f, 0f, 1f)) {
            val left = pose(0, boundary - 0.001f)
            val center = pose(0, boundary)
            val right = pose(0, boundary + 0.001f)
            assertTrue(abs(right.y - left.y) < 1f)
            assertTrue(abs((center.y - left.y) - (right.y - center.y)) < 0.02f)
            assertTrue(abs(right.scale - left.scale) < 0.001f)
        }
    }

    @Test fun `fast releases have bounded momentum and always settle to a valid card`() {
        val forward = walletStackReleaseVelocity(-100_000f, 250f)
        val backward = walletStackReleaseVelocity(100_000f, 250f)
        assertEquals(5f, walletStackSettleTarget(4.3f, forward, 999), 0f)
        assertEquals(4f, walletStackSettleTarget(4.7f, backward, 999), 0f)
        assertEquals(0f, walletStackSettleTarget(-0.2f, backward, 9), 0f)
        assertEquals(9f, walletStackSettleTarget(9.2f, forward, 9), 0f)
        assertEquals(4f, walletStackSettleTarget(4.3f, 0f, 9), 0f)
    }

    @Test fun `loop coordinates stay bounded across many laps in both directions`() {
        for (count in listOf(2, 3, 8, 9, 1_000)) {
            for (lap in -100..100) {
                val position = walletStackLoopPosition(lap * count + 0.25f, count)
                assertEquals(0.25f, position, 0f)
                assertEquals(0, walletStackFocusIndex(position, count, looping = true))
                assertEquals(count - 1, walletStackFocusIndex(-1f, count, looping = true))
            }
            assertEquals(count - 0.25f, walletStackLoopPosition(-0.25f, count), 0f)
            assertEquals(0, walletStackFocusIndex(count - 0.25f, count, looping = true))
        }
    }

    @Test fun `loop releases cross the seam while ordinary releases remain bounded`() {
        assertEquals(-1f, walletStackSettleTarget(-0.7f, -1f, 4, looping = true), 0f)
        assertEquals(5f, walletStackSettleTarget(4.7f, 1f, 4, looping = true), 0f)
        assertEquals(0f, walletStackSettleTarget(-0.7f, -1f, 4), 0f)
        assertEquals(4f, walletStackSettleTarget(4.7f, 1f, 4), 0f)
    }

    @Test fun `loop viewport stays bounded and gives each real card one primary slot`() {
        assertTrue(walletStackVisibleRange(0f, 0, looping = true).isEmpty())
        for (count in listOf(1, 2, 3, 7, 8, 9, 1_000)) {
            for (position in listOf(-0.1f, 0f, 0.5f, count - 0.1f, count.toFloat())) {
                val normalized = walletStackLoopPosition(position, count)
                val slots = walletStackVisibleRange(normalized, count, looping = true)
                val indices = slots.map { Math.floorMod(it, count) }
                assertEquals(if (count == 1) 1 else 8, indices.size)
                assertTrue(indices.all { it in 0 until count })
                assertTrue(walletStackFocusIndex(position, count, looping = true) in indices)
                val primary = slots.filter { slot ->
                    slot == walletStackNearestSlot(Math.floorMod(slot, count), normalized.roundToInt(), count, looping = true)
                }.map { Math.floorMod(it, count) }
                assertEquals(primary.size, primary.toSet().size)
                assertTrue(walletStackFocusIndex(position, count, looping = true) in primary)
            }
        }
    }

    @Test fun `normalizing a loop leaves the rendered cards and their poses unchanged`() {
        for (count in listOf(2, 3, 8, 9, 1_000)) {
            for (position in listOf(-0.25f, 0f, count + 0.25f)) {
                val normalized = walletStackLoopPosition(position, count)
                fun visible(at: Float) = walletStackVisibleRange(at, count, looping = true).map {
                    Math.floorMod(it, count) to pose(it, at)
                }
                assertEquals(visible(position), visible(normalized))
            }
        }
    }

    @Test fun `small and large stacks move continuously across the first card seam`() {
        for (count in listOf(2, 3, 8, 9, 1_000)) {
            for (offset in -3..3) {
                val left = pose(count + offset, count - 0.001f)
                val right = pose(offset, 0.001f)
                assertTrue("Slot $offset jumped at the seam in $count cards", abs(left.y - right.y) < 3f)
                assertTrue(abs(left.scale - right.scale) < 0.001f)
                assertTrue(abs(left.alpha - right.alpha) < 0.01f)
            }
        }
    }

    @Test fun `last to first uses the ordinary card path and clears the outgoing face`() {
        for (count in listOf(2, 3, 4, 8, 9, 1_000)) {
            for (fraction in listOf(0.48f, 0.5f, 0.52f)) {
                val firstSlot = walletStackNearestSlot(0, count, count, looping = true)
                assertEquals(count, firstSlot)
                val outgoing = pose(count - 1, count - 1 + fraction)
                val incoming = pose(firstSlot, count - 1 + fraction)
                assertEquals(pose(0, fraction).y, outgoing.y, 0.05f)
                assertEquals(pose(1, fraction).y, incoming.y, 0.05f)
                assertTrue("Faces intersect with $count cards at $fraction",
                    outgoing.y + height * outgoing.scale < incoming.y)
            }
        }
    }

    @Test fun `both modes hide faces before their viewport slots are recycled`() {
        for (center in listOf(-100, 0, 1_000)) {
            assertEquals(0f, pose(center - 4, center.toFloat()).alpha, 0f)
            assertEquals(0f, pose(center + 4, center.toFloat()).alpha, 0f)
            assertTrue(pose(center - 4, center - 0.01f).alpha < 0.001f)
            assertTrue(pose(center + 4, center + 0.01f).alpha < 0.001f)
        }
    }

    @Test fun `empty and single card stacks never advance or rotate`() {
        for (count in 0..1) {
            assertEquals(0f, walletStackLoopPosition(20.5f, count), 0f)
            assertEquals(0, walletStackFocusIndex(-20.5f, count, looping = true))
            assertEquals(0f, walletStackSettleTarget(10f, 3f, count - 1, looping = true), 0f)
            assertEquals(0, walletStackNearestSlot(0, 20, count, looping = true))
            assertEquals(count, walletStackVisibleRange(0f, count, looping = true).count())
        }
    }
}
