package takagi.ru.monica.ui

import androidx.test.espresso.Espresso
import androidx.test.espresso.Root
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher

/** Bottom sheets can sit above another dialog. Address the focused window, not its host. */
internal fun closeFocusedKeyboard() {
    focusedRoot().perform(ViewActions.closeSoftKeyboard())
}

internal fun pressFocusedBack() {
    focusedRoot().perform(ViewActions.pressBack())
}

private fun focusedRoot() = Espresso.onView(ViewMatchers.isRoot())
        .inRoot(object : TypeSafeMatcher<Root>() {
            override fun matchesSafely(root: Root) = root.decorView.hasWindowFocus()
            override fun describeTo(description: Description) {
                description.appendText("window currently holding input focus")
            }
        })
