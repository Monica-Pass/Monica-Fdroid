package takagi.ru.monica.ui.cardwallet

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val WALLET_STACK_DRAG_RESISTANCE = 0.62f

internal data class WalletStackPose(val x: Float, val y: Float, val scale: Float)

/**
 * A departing card lifts clear of its neighbour before slipping into the upper back slot.
 * At the half-card layer change the faces do not intersect, in either scroll direction.
 */
internal fun walletStackPose(
    index: Int, position: Float, cardWidth: Float, cardHeight: Float, width: Float, centerY: Float
): WalletStackPose {
    val distance = index - position
    val depth = abs(distance)
    val scale = (1f - 0.1f * depth * depth / (0.4f + depth)).coerceAtLeast(0.72f)
    val offset = if (distance < 0f) {
        if (depth <= 1f) {
            val lift = sin(PI.toFloat() * depth)
            // The slot curve and lift have matching tangents at both ends.
            -(0.72f * depth - 0.45f * depth * depth + 0.13f * depth * depth * depth + 0.54f * lift * lift)
        }
        else -0.4f - 0.21f * (depth - 1f) / (1f + (depth - 1f) * 0.18f)
    } else {
        if (depth <= 1f) 0.72f * depth
        else 0.72f + 0.72f * (depth - 1f) / (1f + (depth - 1f) * 0.45f)
    }
    return WalletStackPose(
        x = (width - cardWidth * scale) / 2f,
        y = centerY + offset * cardHeight - cardHeight * scale / 2f,
        scale = scale
    )
}

internal fun walletStackReleaseVelocity(velocityPx: Float, stepPx: Float): Float =
    (-velocityPx / stepPx.coerceAtLeast(1f) * WALLET_STACK_DRAG_RESISTANCE).coerceIn(-3.4f, 3.4f)

/** Short, bounded momentum instead of a list fling that runs through many cards. */
internal fun walletStackSettleTarget(position: Float, velocity: Float, lastIndex: Int): Float =
    (position + velocity.coerceIn(-3.4f, 3.4f) * 0.14f)
        .roundToInt().coerceIn(0, lastIndex).toFloat()

internal fun smoothStackProgress(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
