package takagi.ru.monica.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Mdbx2HistorySupportTest {

    @Test
    fun buildsReadableNestedCollectionPathsWithoutLeakingIds() {
        val paths = buildMdbx2CollectionDisplayPaths(
            rootCollectionId = "root-id",
            rootDisplayName = "根目录",
            nodes = listOf(
                Mdbx2CollectionPathNode("work-id", "root-id", "工作"),
                Mdbx2CollectionPathNode("mail-id", "work-id", "邮箱")
            )
        )

        assertEquals("根目录", paths["root-id"])
        assertEquals("根目录/工作", paths["work-id"])
        assertEquals("根目录/工作/邮箱", paths["mail-id"])
        assertFalse(paths.values.any { it.contains("-id") })
    }

    @Test
    fun missingOrCyclicParentsFallBackWithoutRenderingRawIdentifiers() {
        val paths = buildMdbx2CollectionDisplayPaths(
            rootCollectionId = "root",
            rootDisplayName = "根目录",
            nodes = listOf(
                Mdbx2CollectionPathNode("orphan", "missing", "独立"),
                Mdbx2CollectionPathNode("cycle-a", "cycle-b", "A"),
                Mdbx2CollectionPathNode("cycle-b", "cycle-a", "B")
            )
        )

        assertEquals("根目录/独立", paths["orphan"])
        assertFalse(paths.values.any { it.contains("missing") || it.contains("cycle-") })
    }
}
