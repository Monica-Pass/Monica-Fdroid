package takagi.ru.monica.repository

internal data class Mdbx2CollectionPathNode(
    val collectionId: String,
    val parentCollectionId: String?,
    val title: String
)

internal fun buildMdbx2CollectionDisplayPaths(
    rootCollectionId: String,
    rootDisplayName: String,
    nodes: List<Mdbx2CollectionPathNode>
): Map<String, String> {
    val byId = nodes.associateBy(Mdbx2CollectionPathNode::collectionId)
    val paths = mutableMapOf(rootCollectionId to rootDisplayName)

    fun resolve(collectionId: String, visiting: MutableSet<String> = linkedSetOf()): String? {
        paths[collectionId]?.let { return it }
        if (!visiting.add(collectionId)) return null
        val node = byId[collectionId] ?: return null
        val parentPath = node.parentCollectionId
            ?.takeIf { it.isNotBlank() && it != collectionId }
            ?.let { parentId -> resolve(parentId, visiting) }
            ?: rootDisplayName
        visiting.remove(collectionId)
        return listOf(parentPath, node.title.trim())
            .filter { it.isNotBlank() }
            .joinToString("/")
            .also { paths[collectionId] = it }
    }

    nodes.forEach { node -> resolve(node.collectionId) }
    return paths
}
