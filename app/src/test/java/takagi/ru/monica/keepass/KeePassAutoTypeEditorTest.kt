package takagi.ru.monica.keepass

import app.keemobile.kotpass.constants.AutoTypeObfuscation
import app.keemobile.kotpass.models.AutoTypeData
import app.keemobile.kotpass.models.AutoTypeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeePassAutoTypeEditorTest {
    @Test
    fun fromAndPatchPreserveEntryAutoTypeMetadata() {
        val source = AutoTypeData(
            enabled = true,
            obfuscation = AutoTypeObfuscation.UseClipboard,
            defaultSequence = "{USERNAME}{TAB}{PASSWORD}",
            items = listOf(AutoTypeItem("Example", "{USERNAME}{ENTER}")),
        )

        val draft = KeePassAutoTypeEditor.from(source)
        val patch = draft.toPatch()

        assertEquals(true, patch.enabled)
        assertEquals(AutoTypeObfuscation.UseClipboard.name, patch.obfuscation)
        assertEquals("Example", patch.items.single().window)
        assertEquals("{USERNAME}{ENTER}", patch.items.single().keystrokeSequence)
        assertNull(KeePassAutoTypeEditor.validate(draft))
    }

    @Test
    fun validationRejectsBlankAndDuplicateWindows() {
        val blank = KeePassAutoTypeDraft(
            enabled = true,
            obfuscation = AutoTypeObfuscation.None,
            defaultSequence = "",
            rules = listOf(KeePassAutoTypeRuleDraft(1, " ", "{TAB}")),
        )
        assertEquals(KeePassAutoTypeDraftError.WINDOW_REQUIRED, KeePassAutoTypeEditor.validate(blank))

        val duplicate = blank.copy(
            rules = listOf(
                KeePassAutoTypeRuleDraft(1, "Example", "{TAB}"),
                KeePassAutoTypeRuleDraft(2, " example ", "{ENTER}"),
            ),
        )
        assertEquals(KeePassAutoTypeDraftError.DUPLICATE_WINDOW, KeePassAutoTypeEditor.validate(duplicate))
    }
}
