package takagi.ru.monica.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.Category

class LocalCategoryMoveSupportTest {

    @Test
    fun buildLocalCategoryPathOptions_includesVirtualParentDirectories() {
        val options = buildLocalCategoryPathOptions(
            listOf(
                Category(id = 1, name = "应用分类/1.社交聊天"),
                Category(id = 2, name = "应用分类/2.生活服务")
            )
        )

        assertEquals(
            listOf("应用分类", "应用分类/1.社交聊天", "应用分类/2.生活服务"),
            options.map { it.path }
        )
        assertNull(options.first { it.path == "应用分类" }.category)
    }

    @Test
    fun buildLocalCategoryPathOptions_canReturnOnlyRealCategoryTargets() {
        val options = buildLocalCategoryPathOptions(
            categories = listOf(
                Category(id = 1, name = "应用分类/1.社交聊天"),
                Category(id = 2, name = "应用分类/2.生活服务")
            ),
            includeVirtualParents = false
        )

        assertEquals(
            listOf("应用分类/1.社交聊天", "应用分类/2.生活服务"),
            options.map { it.path }
        )
        assertEquals(listOf(1L, 2L), options.map { it.category?.id })
    }

    @Test
    fun localCategoryHierarchyLabel_usesLeafNameWithDepthIndent() {
        assertEquals("应用分类", localCategoryHierarchyLabel("应用分类"))
        assertEquals("|- 1.社交聊天", localCategoryHierarchyLabel("应用分类/1.社交聊天"))
        assertEquals("  |- 子目录", localCategoryHierarchyLabel("应用分类/1.社交聊天/子目录"))
    }

    @Test
    fun resolveLocalCategoryIdsInScope_returnsOnlySelectedCategoryWhenDescendantsDisabled() {
        val categories = listOf(
            Category(id = 1, name = "Work"),
            Category(id = 2, name = "Work/Email"),
            Category(id = 3, name = "Work/Email/Company")
        )

        assertEquals(
            setOf(1L),
            resolveLocalCategoryIdsInScope(
                categories = categories,
                selectedCategoryId = 1L,
                includeDescendants = false
            )
        )
    }

    @Test
    fun resolveLocalCategoryIdsInScope_includesAllNestedCategoriesCaseInsensitively() {
        val categories = listOf(
            Category(id = 1, name = " Work "),
            Category(id = 2, name = "work / Email"),
            Category(id = 3, name = "WORK/Email/Company"),
            Category(id = 4, name = "Workshop"),
            Category(id = 5, name = "Personal/Work")
        )

        assertEquals(
            setOf(1L, 2L, 3L),
            resolveLocalCategoryIdsInScope(
                categories = categories,
                selectedCategoryId = 1L,
                includeDescendants = true
            )
        )
    }

    @Test
    fun resolveLocalCategoryIdsInScope_keepsSelectedIdWhenCategoryListIsTemporarilyEmpty() {
        assertEquals(
            setOf(42L),
            resolveLocalCategoryIdsInScope(
                categories = emptyList(),
                selectedCategoryId = 42L,
                includeDescendants = true
            )
        )
    }

    @Test
    fun planLocalCategoryRename_renamesTheWholeSubtreeButKeepsItsParent() {
        val source = Category(id = 2, name = "Accounts/Work")
        val categories = listOf(
            Category(id = 1, name = "Accounts"),
            source,
            Category(id = 3, name = "Accounts/Work/Email"),
            Category(id = 4, name = "Personal")
        )

        val plan = planLocalCategoryRename(categories, source, "Office")

        assertEquals("Accounts/Office", plan.destinationPath)
        assertEquals(
            mapOf(
                2L to "Accounts/Office",
                3L to "Accounts/Office/Email"
            ),
            plan.updatedCategories.associate { it.id to it.name }
        )
    }

    @Test
    fun planLocalCategoryRename_rejectsANameThatWouldCollideWithAnotherTree() {
        val source = Category(id = 2, name = "Accounts/Work")
        val error = runCatching {
            planLocalCategoryRename(
                categories = listOf(
                    Category(id = 1, name = "Accounts"),
                    source,
                    Category(id = 3, name = "Accounts/Personal")
                ),
                sourceCategory = source,
                newLeafName = "Personal"
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
