package takagi.ru.monica.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressiveTopBarCursorRegressionTest {

    @Test
    fun restoredSearchQueryPlacesCursorAtEnd() {
        val query = "steam account"

        val value = initialSearchTextFieldValue(query)

        assertEquals(query, value.text)
        assertEquals(TextRange(query.length), value.selection)
    }

    @Test
    fun unchangedQueryPreservesUserSelection() {
        val current = TextFieldValue(
            text = "steam account",
            selection = TextRange(5)
        )

        val value = reconcileSearchTextFieldValue(current, current.text)

        assertEquals(current, value)
    }

    @Test
    fun externallyChangedQueryMovesCursorToNewEnd() {
        val current = TextFieldValue(
            text = "steam",
            selection = TextRange(2)
        )
        val updatedQuery = "steam account"

        val value = reconcileSearchTextFieldValue(current, updatedQuery)

        assertEquals(updatedQuery, value.text)
        assertEquals(TextRange(updatedQuery.length), value.selection)
    }
}
