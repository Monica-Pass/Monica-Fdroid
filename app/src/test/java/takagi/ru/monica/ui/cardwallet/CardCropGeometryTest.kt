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

    @Test fun fourTurnsAndInverseTurnRestoreCenteredCrop() {
        for ((w, h) in listOf(1600 to 1000, 400 to 2400, 10000 to 10)) {
            val initial = CardCropGeometry.centered(w, h)
            var current = initial
            repeat(4) { current = current.rotated(w, h, clockwise = true) }
            assertEquals(initial, current)
            assertEquals(initial, initial.rotated(w, h, false).rotated(w, h, true))
        }
    }

    @Test fun rotationKeepsSubjectAndZoomWhenAwayFromEdges() {
        val initial = CardCropGeometry.centered(1600, 1000).transform(1600, 1000, 3f, 50f, -30f)
        val rotated = initial.rotated(1600, 1000, true)
        assertEquals(1, rotated.quarterTurns)
        assertEquals(1000 - initial.top - initial.height / 2, rotated.left + rotated.width / 2, .001f)
        assertEquals(initial.left + initial.width / 2, rotated.top + rotated.height / 2, .001f)
        assertEquals(CardCropGeometry.centered(1600, 1000, 1).width / 3, rotated.width, .001f)
    }

    @Test fun zoomAndPanStayInsideRotatedExtremeImages() {
        for ((w, h) in listOf(10000 to 10, 10 to 10000, 1200 to 800)) {
            var region = CardCropGeometry.centered(w, h)
            repeat(8) {
                region = region.rotated(w, h, it % 3 != 0).transform(w, h, if (it % 2 == 0) 8f else .01f, 10000f, -10000f)
                val (rw, rh) = region.sourceSize(w, h)
                assertTrue(region.left >= 0 && region.top >= 0)
                assertTrue(region.left + region.width <= rw + .001f)
                assertTrue(region.top + region.height <= rh + .001f)
                assertEquals(CardFaceImageProcessor.CARD_ASPECT_RATIO, region.width / region.height, .0001f)
            }
        }
    }

    @Test fun doubleMirrorRestoresSubjectAndQuarterTurnSwapsMirrorAxes() {
        val crop = CardCropGeometry.centered(1200, 800).transform(1200, 800, 2f, 32f, -24f)
        for (horizontal in listOf(false, true)) {
            val twice = crop.flipped(1200, 800, horizontal).flipped(1200, 800, horizontal)
            assertEquals(crop.left, twice.left, .001f)
            assertEquals(crop.top, twice.top, .001f)
            assertFalse(twice.flipHorizontal || twice.flipVertical)
        }
        val before = crop.flipped(1200, 800, true).rotated(1200, 800, true)
        val after = crop.rotated(1200, 800, true).flipped(1200, 800, false)
        assertTrue(before.flipVertical)
        assertFalse(before.flipHorizontal)
        assertEquals(after.left, before.left, .001f)
        assertEquals(after.top, before.top, .001f)
        assertEquals(after.width, before.width, .001f)
    }
}
