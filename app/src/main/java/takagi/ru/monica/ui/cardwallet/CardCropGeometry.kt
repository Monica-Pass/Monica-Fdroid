package takagi.ru.monica.ui.cardwallet

/** Source-pixel crop rectangle, shared by the gesture preview and JPEG renderer. */
data class CardCropGeometry(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun transform(sourceWidth: Int, sourceHeight: Int, zoom: Float, panX: Float, panY: Float): CardCropGeometry {
        val maxWidth = minOf(sourceWidth.toFloat(), sourceHeight * CardFaceImageProcessor.CARD_ASPECT_RATIO)
        val newWidth = (width / zoom).coerceIn(maxWidth / 8f, maxWidth)
        val newHeight = newWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
        return CardCropGeometry(
            (left + (width - newWidth) / 2 - panX).coerceIn(0f, sourceWidth - newWidth),
            (top + (height - newHeight) / 2 - panY).coerceIn(0f, sourceHeight - newHeight),
            newWidth, newHeight
        )
    }

    companion object {
        fun centered(width: Int, height: Int): CardCropGeometry {
            val cropWidth = minOf(width.toFloat(), height * CardFaceImageProcessor.CARD_ASPECT_RATIO)
            val cropHeight = cropWidth / CardFaceImageProcessor.CARD_ASPECT_RATIO
            return CardCropGeometry((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight)
        }
    }
}
