package takagi.ru.monica.ui.cardwallet

import kotlin.math.abs
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
}
