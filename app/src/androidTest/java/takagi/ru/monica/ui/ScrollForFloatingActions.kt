package takagi.ru.monica.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction

/** Scroll the target above floating actions before injecting a real touch. */
internal fun ComposeContentTestRule.bringAboveFloatingActions(
    target: SemanticsNodeInteraction,
    scrollContainer: SemanticsNodeInteraction,
) {
    target.performScrollTo()
    val viewport = scrollContainer.fetchSemanticsNode().boundsInRoot
    val bounds = target.fetchSemanticsNode().boundsInRoot
    scrollContainer.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
        scroll(0f, bounds.center.y - viewport.center.y)
    }
    waitForIdle()
}
