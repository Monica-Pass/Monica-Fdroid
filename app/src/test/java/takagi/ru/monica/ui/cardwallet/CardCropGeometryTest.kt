package takagi.ru.monica.ui.cardwallet

import org.junit.Assert.*
import org.junit.Test

class CardCropGeometryTest {
    @Test fun centeredCropPreservesCardRatioForExtremeSources() {
        for ((w, h) in listOf(10000 to 10, 10 to 10000, 1200 to 800)) {
            val r = CardCropGeometry.centered(w, h)
            assertEquals(CardFaceImageProcessor.CARD_ASPECT_RATIO, r.width / r.height, .0001f)
            assertEquals(w / 2f, r.left + r.width / 2, .001f)
            assertEquals(h / 2f, r.top + r.height / 2, .001f)
        }
    }

    @Test fun gesturesCannotExposeBlankEdges() {
        for ((w, h) in listOf(10000 to 10, 10 to 10000, 1200 to 800)) {
            var r = CardCropGeometry.centered(w, h)
            for (zoom in listOf(.01f, 2f, 100f, .5f, .01f)) {
                for (pan in listOf(-100000f, 100000f)) {
                    r = r.transform(w, h, zoom, pan, -pan)
                    assertTrue(r.left >= 0 && r.top >= 0)
                    assertTrue(r.left + r.width <= w + .001f)
                    assertTrue(r.top + r.height <= h + .001f)
                }
            }
        }
    }

    @Test fun zoomKeepsCenterAndDragMovesImageInGestureDirection() {
        val initial = CardCropGeometry.centered(1600, 1000)
        val zoomed = initial.transform(1600, 1000, 2f, 0f, 0f)
        assertEquals(initial.width / 2, zoomed.width, .001f)
        assertEquals(800f, zoomed.left + zoomed.width / 2, .001f)
        val dragged = zoomed.transform(1600, 1000, 1f, 20f, -30f)
        assertEquals(zoomed.left - 20, dragged.left, .001f)
        assertEquals(zoomed.top + 30, dragged.top, .001f)
    }
}
