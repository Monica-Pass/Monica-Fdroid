package takagi.ru.monica.utils

import takagi.ru.monica.R

import takagi.ru.monica.data.Category

data class LocalCategoryMovePlan(
    val updatedCategories: List<Category>,
    val destinationPath: String
)

data class LocalCategoryPathOption(
    val path: String,
    val displayName: String,
    val parentPath: String?,
    val parentPathLabel: String?,
    val depth: Int,
    val category: Category?
)

internal fun planLocalCategoryMove(
    categories: List<Category>,
    sourceCategory: Category,
    targetParentCategory: Category?,
    strings: StringResolver
): LocalCategoryMovePlan {
    val sourcePath = normalizeLocalCategoryPath(sourceCategory.name)
    if (sourcePath.isBlank()) {
        throw IllegalArgumentException(strings.get(R.string.entry_message_category_path_invalid))
    }

    val destinationParentPath = targetParentCategory
        ?.name
        ?.let(::normalizeLocalCategoryPath)
        ?.ifBlank { null }
    val leafName = getLocalCategoryLeafName(sourcePath)
    val destinationPath = buildLocalCategoryPath(destinationParentPath, leafName)
    val sourceParentPath = getLocalCategoryParentPath(sourcePath)

    if (destinationPath.equals(sourcePath, ignoreCase = true) &&
        destinationParentPath.equals(sourceParentPath, ignoreCase = true)
    ) {
        return LocalCategoryMovePlan(updatedCategories = emptyList(), destinationPath = destinationPath)
    }

    if (!destinationParentPath.isNullOrBlank() && isLocalCategoryDescendantPath(
            parentPath = sourcePath,
            candidatePath = destinationParentPath
        )
    ) {
        throw IllegalArgumentException(strings.get(R.string.entry_message_category_move_into_self))
    }

    val movingCategories = categories.filter { category ->
        val path = normalizeLocalCategoryPath(category.name)
        isLocalCategoryDescendantPath(sourcePath, path)
    }
    if (movingCategories.isEmpty()) {
        throw IllegalArgumentException(strings.get(R.string.entry_message_category_missing))
    }

    val remappedPaths = movingCategories.associate { category ->
        val oldPath = normalizeLocalCategoryPath(category.name)
        val suffix = oldPath.removePrefix(sourcePath)
        category.id to (destinationPath + suffix)
    }

    val movingIds = movingCategories.map { it.id }.toSet()
    val conflicts = categories
        .asSequence()
        .filter { it.id !in movingIds }
        .map { normalizeLocalCategoryPath(it.name).lowercase() }
        .toSet()
    val duplicatedTargets = remappedPaths.values
        .groupingBy { it.lowercase() }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    if (destinationPath.lowercase() in conflicts || duplicatedTargets.isNotEmpty()) {
        throw IllegalArgumentException(strings.get(R.string.entry_message_category_exists))
    }
    if (remappedPaths.values.any { it.lowercase() in conflicts }) {
        throw IllegalArgumentException(strings.get(R.string.entry_message_category_move_conflict))
    }

    val updatedCategories = categories.mapNotNull { category ->
        val newPath = remappedPaths[category.id] ?: return@mapNotNull null
        if (newPath.equals(category.name, ignoreCase = false)) {
            null
        } else {
            category.copy(name = newPath)
        }
    }

    return LocalCategoryMovePlan(
        updatedCategories = updatedCategories,
        destinationPath = destinationPath
    )
}

fun normalizeLocalCategoryPath(path: String): String {
    return path
        .split("/")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")
}

fun buildLocalCategoryPath(parentPath: String?, name: String): String {
    val child = normalizeLocalCategoryPath(name)
    if (child.isBlank()) return ""
    val parent = parentPath?.let(::normalizeLocalCategoryPath).orEmpty()
    return if (parent.isBlank()) child else "$parent/$child"
}

fun getLocalCategoryLeafName(path: String): String {
    val normalized = normalizeLocalCategoryPath(path)
    if (normalized.isBlank()) return ""
    return normalized.substringAfterLast('/')
}

fun getLocalCategoryParentPath(path: String): String? {
    val normalized = normalizeLocalCategoryPath(path)
    if (!normalized.contains('/')) return null
    return normalized.substringBeforeLast('/').ifBlank { null }
}

fun isLocalCategoryDescendantPath(parentPath: String, candidatePath: String): Boolean {
    val normalizedParent = normalizeLocalCategoryPath(parentPath)
    val normalizedCandidate = normalizeLocalCategoryPath(candidatePath)
    return normalizedCandidate.equals(normalizedParent, ignoreCase = true) ||
        normalizedCandidate.startsWith("$normalizedParent/", ignoreCase = true)
}

internal fun planLocalCategoryRename(
    categories: List<Category>,
    sourceCategory: Category,
    newLeafName: String,
    strings: StringResolver,
): LocalCategoryMovePlan {
    val sourcePath = normalizeLocalCategoryPath(sourceCategory.name)
    require(sourcePath.isNotBlank()) { strings.get(R.string.entry_message_category_path_invalid) }

    val normalizedLeaf = normalizeLocalCategoryPath(newLeafName)
    require(normalizedLeaf.isNotBlank()) { strings.get(R.string.entry_message_category_name_required) }
    require('/' !in normalizedLeaf) { strings.get(R.string.entry_message_category_name_separator) }

    val sourceParentPath = getLocalCategoryParentPath(sourcePath)
    val destinationPath = buildLocalCategoryPath(sourceParentPath, normalizedLeaf)
    if (destinationPath == sourcePath) {
        return LocalCategoryMovePlan(updatedCategories = emptyList(), destinationPath = destinationPath)
    }

    val movingCategories = categories.filter { category ->
        isLocalCategoryDescendantPath(sourcePath, normalizeLocalCategoryPath(category.name))
    }
    require(movingCategories.isNotEmpty()) { strings.get(R.string.entry_message_category_missing) }

    val remappedPaths = movingCategories.associate { category ->
        val oldPath = normalizeLocalCategoryPath(category.name)
        val suffix = oldPath.removePrefix(sourcePath)
        category.id to (destinationPath + suffix)
    }
    val movingIds = movingCategories.mapTo(mutableSetOf(), Category::id)
    val existingPaths = categories
        .asSequence()
        .filter { it.id !in movingIds }
        .map { normalizeLocalCategoryPath(it.name).lowercase() }
        .toSet()
    require(remappedPaths.values.none { it.lowercase() in existingPaths }) {
        strings.get(R.string.entry_message_category_rename_conflict)
    }

    return LocalCategoryMovePlan(
        updatedCategories = movingCategories.mapNotNull { category ->
            val newPath = remappedPaths.getValue(category.id)
            category.copy(name = newPath).takeUnless { it.name == category.name }
        },
        destinationPath = destinationPath,
    )
}

fun resolveLocalCategoryIdsInScope(
    categories: List<Category>,
    selectedCategoryId: Long,
    includeDescendants: Boolean
): Set<Long> {
    if (!includeDescendants) return setOf(selectedCategoryId)

    val selectedPath = categories
        .firstOrNull { it.id == selectedCategoryId }
        ?.name
        ?.let(::normalizeLocalCategoryPath)
        ?.takeIf { it.isNotBlank() }
        ?: return setOf(selectedCategoryId)

    return buildSet {
        add(selectedCategoryId)
        categories.forEach { category ->
            val candidatePath = normalizeLocalCategoryPath(category.name)
            if (
                candidatePath.isNotBlank() &&
                isLocalCategoryDescendantPath(selectedPath, candidatePath)
            ) {
                add(category.id)
            }
        }
    }
}

fun buildLocalCategoryPathOptions(
    categories: List<Category>,
    includeVirtualParents: Boolean = true
): List<LocalCategoryPathOption> {
    val categoryByPath = categories
        .mapNotNull { category ->
            val path = normalizeLocalCategoryPath(category.name)
            if (path.isBlank()) null else path to category
        }
        .toMap()

    val paths = if (includeVirtualParents) {
        categoryByPath.keys.flatMap { path ->
            val segments = path.split('/')
            segments.indices.map { index ->
                segments.take(index + 1).joinToString("/")
            }
        }
    } else {
        categoryByPath.keys
    }

    return paths
        .distinct()
        .filter { it.isNotBlank() }
        .map { path ->
            val segments = path.split('/')
            LocalCategoryPathOption(
                path = path,
                displayName = segments.lastOrNull().orEmpty(),
                parentPath = getLocalCategoryParentPath(path),
                parentPathLabel = segments.dropLast(1).joinToString("/").ifBlank { null },
                depth = (segments.size - 1).coerceAtLeast(0),
                category = categoryByPath[path]
            )
        }
        .sortedWith(
            compareBy<LocalCategoryPathOption> { option ->
                option.path.split('/').joinToString("\u0000") { segment -> segment.lowercase() }
            }.thenBy { it.path }
        )
}

fun localCategoryHierarchyLabel(path: String): String {
    val normalized = normalizeLocalCategoryPath(path)
    if (normalized.isBlank()) return ""
    val segments = normalized.split('/')
    val leaf = segments.last()
    val depth = (segments.size - 1).coerceAtLeast(0)
    return if (depth == 0) leaf else "${"  ".repeat(depth - 1)}|- $leaf"
}
