package takagi.ru.monica.ui.common.pull

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullSearchGestureStateTest {
    private class Fixture {
        var opens = 0
        var thresholdFeedback = 0
        var expanded = false
        val gesture = PullSearchGestureState(
            canTriggerSearch = { !expanded },
            onSearchThresholdReached = { thresholdFeedback++ },
            onSearchTriggered = { opens++ },
        )
    }

    @Test
    fun reachingTheThresholdGivesFeedbackAndReleaseOpensImmediatelyOnce() {
        val fixture = Fixture()
        val gesture = fixture.gesture
        gesture.beginGesture()
        gesture.updatePull(inSearchRange = true)
        gesture.updatePull(inSearchRange = true)
        assertEquals(1, fixture.thresholdFeedback)
        assertEquals(0, fixture.opens)

        gesture.releaseSearch()
        assertEquals(1, fixture.opens)
        gesture.endTouch()
        gesture.releaseSearch()
        assertEquals(1, fixture.opens)
        assertFalse(gesture.canRelease)
    }

    @Test
    fun pointerUpKeepsReadinessForTheSubsequentNestedScrollRelease() {
        val fixture = Fixture()
        val gesture = fixture.gesture
        gesture.beginGesture()
        gesture.updatePull(inSearchRange = true)
        gesture.endTouch()
        assertFalse(gesture.canPull)
        assertTrue(gesture.canRelease)

        gesture.releaseSearch()
        gesture.releaseSearch()
        assertEquals(1, fixture.opens)
    }

    @Test
    fun aShortPullDoesNotOpenSearch() {
        val fixture = Fixture()
        fixture.gesture.beginGesture()
        fixture.gesture.updatePull(inSearchRange = false)
        fixture.gesture.endTouch()
        fixture.gesture.releaseSearch()
        assertEquals(0, fixture.opens)
        assertEquals(0, fixture.thresholdFeedback)
    }

    @Test
    fun retreatingBelowThresholdDisarmsTheSearch() {
        val fixture = Fixture()
        fixture.gesture.beginGesture()
        fixture.gesture.updatePull(inSearchRange = true)
        fixture.gesture.updatePull(inSearchRange = false)
        fixture.gesture.endTouch()
        fixture.gesture.releaseSearch()
        assertEquals(0, fixture.opens)
    }

    @Test
    fun aCancelledTouchCannotOpenSearchOnALateScrollRelease() {
        val fixture = Fixture()
        fixture.gesture.beginGesture()
        fixture.gesture.updatePull(inSearchRange = true)
        fixture.gesture.cancelGesture()
        fixture.gesture.onScroll()
        fixture.gesture.updatePull(inSearchRange = true)
        fixture.gesture.releaseSearch()
        assertFalse(fixture.gesture.canPull)
        assertEquals(0, fixture.opens)
    }

    @Test
    fun scrollingBackToTheTopRequiresAFreshPullToSearch() {
        val fixture = Fixture()
        val gesture = fixture.gesture
        gesture.beginGesture()
        gesture.onScroll(contentConsumed = true)
        gesture.onScroll(contentConsumed = false)
        gesture.updatePull(inSearchRange = true)
        assertFalse(gesture.canPull)
        gesture.endTouch()
        gesture.releaseSearch()
        assertEquals(0, fixture.opens)

        gesture.beginGesture()
        gesture.updatePull(inSearchRange = true)
        gesture.endTouch()
        gesture.releaseSearch()
        assertEquals(1, fixture.opens)
    }

    @Test
    fun scrollingContentAfterReachingTheThresholdDisarmsSearch() {
        val fixture = Fixture()
        fixture.gesture.beginGesture()
        fixture.gesture.updatePull(inSearchRange = true)
        fixture.gesture.onScroll(contentConsumed = true)
        fixture.gesture.endTouch()
        fixture.gesture.releaseSearch()
        assertEquals(0, fixture.opens)
    }

    @Test
    fun aNewTouchDoesNotInheritThePreviousPullReadiness() {
        val fixture = Fixture()
        fixture.gesture.beginGesture()
        fixture.gesture.updatePull(inSearchRange = true)
        fixture.gesture.endTouch()
        fixture.gesture.beginGesture()
        fixture.gesture.endTouch()
        fixture.gesture.releaseSearch()
        assertEquals(0, fixture.opens)
    }

    @Test
    fun openingSearchElsewhereBeforeReleasePreventsADuplicateOpen() {
        val fixture = Fixture()
        fixture.gesture.beginGesture()
        fixture.gesture.updatePull(inSearchRange = true)
        fixture.expanded = true
        fixture.gesture.endTouch()
        fixture.gesture.releaseSearch()
        assertEquals(0, fixture.opens)
    }

    @Test
    fun syncCanConsumeAReleasedPullWithoutOpeningSearchAsWell() {
        val fixture = Fixture()
        fixture.gesture.beginGesture()
        fixture.gesture.updatePull(inSearchRange = true)
        fixture.gesture.endTouch()
        assertTrue(fixture.gesture.canRelease)
        fixture.gesture.cancelGesture()
        fixture.gesture.releaseSearch()
        assertFalse(fixture.gesture.canRelease)
        assertEquals(0, fixture.opens)
    }
}
