package takagi.ru.monica.ui.cardwallet

/** Crop in the rotated source's pixel coordinates; shared by preview and JPEG rendering. */
data class CardCropGeometry(
    val left: Float, val top: Float, val width: Float, val height: Float,
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false, val flipVertical: Boolean = false,
) {
    fun sourceSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> =
        if (quarterTurns.mod(2) == 0) sourceWidth to sourceHeight else sourceHeight to sourceWidth

    fun transform(sourceWidth: Int, sourceHeight: Int, zoom: Float, panX: Float, panY: Float): CardCropGeometry {
        val (rotatedWidth, rotatedHeight) = sourceSize(sourceWidth, sourceHeight)
        val maxWidth = minOf(rotatedWidth.toFloat(), rotatedHeight * CardFaceImageProcessor.CARD_ASPECT_RATIO)
        val newWidth = (width / zoom).coerceIn(maxWidth / 8f, maxWidth)
        val newHeight = newWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
        return copy(
            left = (left + (width - newWidth) / 2 - panX).coerceIn(0f, (rotatedWidth - newWidth).coerceAtLeast(0f)),
            top = (top + (height - newHeight) / 2 - panY).coerceIn(0f, (rotatedHeight - newHeight).coerceAtLeast(0f)),
            width = newWidth, height = newHeight,
        )
    }

    /** Keep the selected subject and relative zoom, clamping only when the frame meets an edge. */
    fun rotated(sourceWidth: Int, sourceHeight: Int, clockwise: Boolean): CardCropGeometry {
        val (oldWidth, oldHeight) = sourceSize(sourceWidth, sourceHeight)
        val centerX = left + width / 2
        val centerY = top + height / 2
        val newCenterX = if (clockwise) oldHeight - centerY else centerY
        val newCenterY = if (clockwise) centerX else oldWidth - centerX
        val turns = (quarterTurns + if (clockwise) 1 else -1).mod(4)
        val centered = centered(sourceWidth, sourceHeight, turns)
        val oldMaxWidth = minOf(oldWidth.toFloat(), oldHeight * CardFaceImageProcessor.CARD_ASPECT_RATIO)
        val newWidth = centered.width * (width / oldMaxWidth).coerceIn(1f / 8f, 1f)
        val newHeight = newWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
        return centered.copy(
            left = (newCenterX - newWidth / 2).coerceIn(0f, (oldHeight - newWidth).coerceAtLeast(0f)),
            top = (newCenterY - newHeight / 2).coerceIn(0f, (oldWidth - newHeight).coerceAtLeast(0f)),
            width = newWidth, height = newHeight,
            // A quarter turn swaps the current screen axes of both mirror operations.
            flipHorizontal = flipVertical, flipVertical = flipHorizontal,
        )
    }

    fun flipped(sourceWidth: Int, sourceHeight: Int, horizontal: Boolean): CardCropGeometry {
        val (w, h) = sourceSize(sourceWidth, sourceHeight)
        return if (horizontal) copy(left = (w - left - width).coerceAtLeast(0f), flipHorizontal = !flipHorizontal)
            else copy(top = (h - top - height).coerceAtLeast(0f), flipVertical = !flipVertical)
    }

    companion object {
        fun centered(width: Int, height: Int, quarterTurns: Int = 0): CardCropGeometry {
            val turns = quarterTurns.mod(4)
            val w = if (turns % 2 == 0) width else height
            val h = if (turns % 2 == 0) height else width
            val cropWidth = minOf(w.toFloat(), h * CardFaceImageProcessor.CARD_ASPECT_RATIO)
            val cropHeight = cropWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
            return CardCropGeometry((w - cropWidth) / 2, (h - cropHeight) / 2, cropWidth, cropHeight, turns)
        }
    }
}
