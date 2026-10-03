package takagi.ru.monica.repository

import org.junit.Assert.*
import org.junit.Test

class MdbxBrowserIndexTest {
    private fun folder(id: String, parent: String? = null) = MdbxStructureNode(id, parent, id,
        MdbxStructureNodeType.FOLDER, id, MdbxStructureNodeStatus.UNCHANGED, 0, "")
    private fun entry(id: String, parent: String?) = folder(id, parent).copy(type = MdbxStructureNodeType.ENTRY)
    @Test fun childrenAndBreadcrumbsKeepNestedEntriesSeparate() {
        val index = MdbxBrowserIndex(listOf(folder("a"), folder("b", "a"), entry("x", "b"), entry("y", "a")))
        assertEquals(listOf("a"), index.children(null).map { it.id })
        assertEquals(setOf("b", "y"), index.children("a").map { it.id }.toSet())
        assertEquals(listOf("a", "b"), index.ancestors("b").map { it.id })
    }
    @Test fun moveCannotTargetSelfOrDescendants() {
        val index = MdbxBrowserIndex(listOf(folder("a"), folder("b", "a"), folder("c")))
        assertFalse(index.canMoveFolder("a", "a"))
        assertFalse(index.canMoveFolder("a", "b"))
        assertFalse(index.canMoveFolder("a", "missing"))
        assertTrue(index.canMoveFolder("b", null))
        assertTrue(index.canMoveFolder("a", "c"))
    }
    @Test fun orphansAndCyclesRemainReachableWithoutChangingOriginalNodes() {
        val nodes = listOf(folder("a", "b"), folder("b", "a"), entry("x", "missing"), folder("orphan", "missing"))
        val index = MdbxBrowserIndex(nodes)
        assertEquals(4, index.children(null).size)
        assertEquals("b", nodes[0].parentId)
        assertEquals(listOf("a"), index.ancestors("a").map { it.id })
    }
    @Test fun sharedIdsAcrossTypesDoNotDropEntries() {
        val index = MdbxBrowserIndex(listOf(folder("a"), entry("a", null)))
        assertEquals(2, index.children(null).size)
    }
}
