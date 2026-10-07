package takagi.ru.monica.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import takagi.ru.monica.suggestions.CommonInfoTestActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import takagi.ru.monica.ui.components.EntryOptionalFields
import takagi.ru.monica.ui.components.EntrySupplementalSpecs

class EntryOptionalFieldsTest {
    @get:Rule val compose = createAndroidComposeRule<CommonInfoTestActivity>()
    private fun show(content: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { CommonInfoTestActivity.content = content }
        compose.waitForIdle()
    }
    @After fun resetContent() = show { }
    @Test fun newCardDoesNotOfferDuplicateBrandOrNickname() {
        show { MaterialTheme { EntryOptionalFields(EntrySupplementalSpecs.payment, emptyMap()) { _, _ -> } } }
        compose.onNodeWithTag("entry_extra_brand").assertDoesNotExist()
        compose.onNodeWithTag("entry_extra_nickname").assertDoesNotExist()
        compose.onNodeWithTag("entry_extra_add").performClick()
        compose.onNodeWithTag("entry_extra_choose_brand").assertDoesNotExist()
        compose.onNodeWithTag("entry_extra_choose_nickname").assertDoesNotExist()
        compose.onNodeWithTag("entry_extra_choose_pin").performScrollTo().assertIsDisplayed()
    }
    @Test fun existingLegacyValuesRemainEditableAndPinIsProtected() {
        var values by mutableStateOf(mapOf("brand" to "VISA", "nickname" to "Legacy card", "pin" to "8642"))
        show { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            EntryOptionalFields(EntrySupplementalSpecs.payment, values) { spec, value -> values = values + (spec.key to value) }
        } } }
        compose.onNodeWithTag("entry_extra_nickname").performScrollTo().performTextReplacement("Travel")
        compose.onNodeWithTag("entry_extra_pin").performScrollTo().assert(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithTag("entry_extra_reveal_pin").performClick()
        compose.onNodeWithTag("entry_extra_pin").assertTextContains("8642")
        compose.runOnIdle { assertEquals(mapOf("brand" to "VISA", "nickname" to "Travel", "pin" to "8642"), values) }
    }
}
