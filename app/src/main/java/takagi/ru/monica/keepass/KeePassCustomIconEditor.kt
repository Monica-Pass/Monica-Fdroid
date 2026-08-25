package takagi.ru.monica.keepass

import app.keemobile.kotpass.models.CustomIcon
import java.time.Instant
import java.util.UUID

/**
 * UI-independent model for editing the KDBX custom icon pool.
 *
 * The KDBX format stores icons in a database-wide pool while entries and
 * groups only keep a UUID reference.  Keeping the reference count here makes
 * it impossible for the editor to delete an icon that is still in use.
 */
internal data class KeePassCustomIconItem(
    val uuid: UUID,
    val icon: CustomIcon,
    val referenceCount: Int,
) {
    val isReferenced: Boolean get() = referenceCount > 0
}

internal object KeePassCustomIconEditor {
    const val MAX_ICON_BYTES: Int = 2 * 1024 * 1024

    fun list(
        pool: Map<UUID, CustomIcon>,
        referencedUuids: Map<UUID, Int> = emptyMap(),
    ): List<KeePassCustomIconItem> = pool
        .map { (uuid, icon) ->
            KeePassCustomIconItem(
                uuid = uuid,
                icon = icon,
                referenceCount = referencedUuids[uuid] ?: 0,
            )
        }
        .sortedWith(
            compareBy<KeePassCustomIconItem> { it.icon.name.orEmpty().lowercase() }
                .thenBy { it.uuid.toString() },
        )

    fun countReferences(
        referencedUuids: Iterable<UUID>,
    ): Map<UUID, Int> = referencedUuids.groupingBy { it }.eachCount()

    fun newIcon(
        bytes: ByteArray,
        name: String,
        uuid: UUID = UUID.randomUUID(),
        now: Instant = Instant.now(),
    ): Pair<UUID, CustomIcon> {
        validateImageBytes(bytes).getOrThrow()
        val normalizedName = name.trim().takeIf { it.isNotEmpty() } ?: "Monica icon"
        return uuid to CustomIcon(
            data = bytes.copyOf(),
            name = normalizedName,
            lastModified = now,
        )
    }

    fun canDelete(item: KeePassCustomIconItem): Boolean = !item.isReferenced

    /** Validates the small set of image formats accepted by KeePass icon pools. */
    fun validateImageBytes(bytes: ByteArray): Result<Unit> = runCatching {
        require(bytes.isNotEmpty()) { "Custom icon cannot be empty" }
        require(bytes.size <= MAX_ICON_BYTES) {
            "Custom icon is too large (maximum ${MAX_ICON_BYTES / 1024 / 1024} MB)"
        }
        require(isSupportedImageHeader(bytes)) {
            "Unsupported custom icon format"
        }
    }

    private fun isSupportedImageHeader(bytes: ByteArray): Boolean {
        fun startsWith(vararg expected: Int): Boolean =
            bytes.size >= expected.size && expected.indices.all { bytes[it].toInt() and 0xff == expected[it] }

        val png = startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val jpeg = startsWith(0xff, 0xd8, 0xff)
        val gif = startsWith(0x47, 0x49, 0x46, 0x38)
        val webp = startsWith(0x52, 0x49, 0x46, 0x46) &&
            bytes.size >= 12 && bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x45, 0x42, 0x50))
        val ico = startsWith(0x00, 0x00, 0x01, 0x00)
        return png || jpeg || gif || webp || ico
    }
}
