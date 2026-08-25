package takagi.ru.monica.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.Category
import takagi.ru.monica.ui.components.UnifiedMoveAction
import takagi.ru.monica.ui.components.UnifiedMoveCategoryTarget
import takagi.ru.monica.viewmodel.CategoryFilter

class PasswordCategoryFolderTransferSupportTest {

    @Test
    fun editShortcutKeepsItsCategoryWhenTheObservedCategoryListIsTemporarilyEmpty() {
        val category = Category(id = 12, name = "Accounts/Email")
        val shortcut = PasswordQuickFolderShortcut(
            key = "folder-12",
            title = "Email",
            subtitle = "Monica",
            isBack = false,
            targetFilter = CategoryFilter.Custom(category.id),
            passwordCount = 2,
            editableCategory = category,
        )

        assertSame(category, shortcut.resolveEditableCategory(emptyList()))
    }

    @Test
    fun transferPlanIncludesTheRootAndEveryDescendantInParentFirstOrder() {
        val source = Category(id = 1, name = "Accounts/Work")
        val nodes = buildPasswordCategoryFolderTransferNodes(
            categories = listOf(
                Category(id = 4, name = "Personal"),
                Category(id = 3, name = "Accounts/Work/Email/Team"),
                source,
                Category(id = 2, name = "Accounts/Work/Email"),
                Category(id = 5, name = "Accounts/Workshop")
            ),
            sourceCategory = source,
        )

        assertEquals(listOf(1L, 2L, 3L), nodes.map { it.category.id })
        assertEquals(
            listOf(
                listOf("Work"),
                listOf("Work", "Email"),
                listOf("Work", "Email", "Team")
            ),
            nodes.map { it.relativeSegments }
        )
    }

    @Test
    fun localFolderCannotBeCopiedIntoItsOwnDescendant() {
        val source = Category(id = 1, name = "Accounts/Work")
        val child = Category(id = 2, name = "Accounts/Work/Email")

        val error = runCatching {
            validatePasswordCategoryFolderLocalDestination(
                categories = listOf(source, child),
                sourceCategory = source,
                target = UnifiedMoveCategoryTarget.MonicaCategory(child.id),
                action = UnifiedMoveAction.COPY,
            )
        }.exceptionOrNull()

        assertTrue(error is PasswordCategoryFolderTransferBlockedException)
    }

    @Test
    fun nestedFolderCanBeCopiedToTheRoot() {
        val source = Category(id = 2, name = "Accounts/Work")

        validatePasswordCategoryFolderLocalDestination(
            categories = listOf(Category(id = 1, name = "Accounts"), source),
            sourceCategory = source,
            target = UnifiedMoveCategoryTarget.Uncategorized,
            action = UnifiedMoveAction.COPY,
        )
    }
}
