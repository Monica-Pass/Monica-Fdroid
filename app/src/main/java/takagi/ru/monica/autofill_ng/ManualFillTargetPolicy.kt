package takagi.ru.monica.autofill_ng

internal fun resolveManualFillTargetPackage(
    activePackage: String,
    packageNameToSkip: String,
    expectedTargetPackage: String?,
): String? {
    val active = activePackage.trim()
    if (active.isEmpty() || active.equals(packageNameToSkip.trim(), ignoreCase = true)) {
        return null
    }

    val expected = expectedTargetPackage?.trim()?.takeIf { it.isNotEmpty() }
    if (expected != null && !active.equals(expected, ignoreCase = true)) {
        return null
    }
    return expected ?: active
}
