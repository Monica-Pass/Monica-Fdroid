package takagi.ru.monica.ui.cardwallet

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val WALLET_STACK_DRAG_RESISTANCE = 0.62f

internal data class WalletStackPose(
    val x: Float,
    val y: Float,
    val scale: Float,
    val alpha: Float = 1f,
)

/** Keep the scroll coordinate small, including after repeated backwards laps. */
internal fun walletStackLoopPosition(position: Float, count: Int): Float {
    if (count <= 1) return 0f
    val remainder = position % count
    return if (remainder < 0f) (remainder + count).let { if (it >= count) 0f else it } else remainder
}

internal fun walletStackFocusIndex(position: Float, count: Int, looping: Boolean): Int {
    if (count <= 1) return 0
    return if (looping) Math.floorMod(position.roundToInt(), count)
    else position.roundToInt().coerceIn(0, count - 1)
}

/** A bounded window of virtual slots lets the last card lead directly into the first. */
internal fun walletStackVisibleRange(position: Float, count: Int, looping: Boolean): IntRange {
    if (count <= 0) return IntRange.EMPTY
    val center = floor(position).toInt()
    return if (looping && count > 1) center - 3..center + 4
    else (center - 3).coerceAtLeast(0)..(center + 4).coerceAtMost(count - 1)
}

/** Choose one accessible copy per real card, nearest to the focused virtual slot. */
internal fun walletStackNearestSlot(index: Int, referenceSlot: Int, count: Int, looping: Boolean): Int {
    if (!looping || count <= 1) return index
    val distance = Math.floorMod(index - referenceSlot, count)
    return referenceSlot + if (distance > count / 2) distance - count else distance
}

/**
 * A departing card lifts clear of its neighbour before slipping into the upper back slot.
 * At the half-card layer change the faces do not intersect, in either scroll direction.
 */
internal fun walletStackPose(
    index: Int, position: Float, cardWidth: Float, cardHeight: Float, width: Float, centerY: Float,
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
        scale = scale,
        // Recycle only at the hidden viewport edges, identically in both modes.
        alpha = smoothStackProgress((4f - depth) / 0.7f),
    )
}

internal fun walletStackReleaseVelocity(velocityPx: Float, stepPx: Float): Float =
    (-velocityPx / stepPx.coerceAtLeast(1f) * WALLET_STACK_DRAG_RESISTANCE).coerceIn(-3.4f, 3.4f)

/** Short, bounded momentum instead of a list fling that runs through many cards. */
internal fun walletStackSettleTarget(
    position: Float, velocity: Float, lastIndex: Int, looping: Boolean = false,
): Float {
    if (lastIndex <= 0) return 0f
    val target = (position + velocity.coerceIn(-3.4f, 3.4f) * 0.14f).roundToInt()
    // Animate through the seam before normalizing; clamping here would jump or stop.
    return (if (looping) target else target.coerceIn(0, lastIndex)).toFloat()
}

internal fun smoothStackProgress(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
