package takagi.ru.monica.keepass

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.models.Group
import java.time.Instant
import java.util.UUID

internal data class KeePassRecycleBinResolution(
    val database: KeePassDatabase,
    val recycleBinUuid: UUID
)

internal class KeePassRecycleBinPolicy(
    private val nowProvider: () -> Instant = Instant::now,
    private val nativeMutation: KeePassNativeMutation = KeePassNativeMutation(nowProvider)
) {
    fun ensure(
        database: KeePassDatabase,
        preferredRecycleBinUuid: UUID? = null
    ): KeePassRecycleBinResolution {
        val root = database.content.group
        val meta = database.content.meta
        val configuredUuid = meta.recycleBinUuid
            ?.takeUnless(::isZeroUuid)
            ?.takeIf { findGroup(root, it) != null }
        val preferredUuid = preferredRecycleBinUuid
            ?.takeUnless(::isZeroUuid)
            ?.takeIf { findGroup(root, it) != null }
        val existingUuid = configuredUuid ?: preferredUuid

        if (existingUuid != null) {
            val repairedDatabase = if (
                meta.recycleBinEnabled &&
                meta.recycleBinUuid == existingUuid
            ) {
                database
            } else {
                database.modifyContent {
                    copy(
                        meta = meta.copy(
                            recycleBinEnabled = true,
                            recycleBinUuid = existingUuid,
                            recycleBinChanged = nowProvider()
                        )
                    )
                }
            }
            return KeePassRecycleBinResolution(repairedDatabase, existingUuid)
        }

        val recycleBin = nativeMutation.initializeGroup(Group.createRecycleBin(DEFAULT_RECYCLE_BIN_NAME))
        val repairedDatabase = database.modifyContent {
            copy(
                meta = meta.copy(
                    recycleBinEnabled = true,
                    recycleBinUuid = recycleBin.uuid,
                    recycleBinChanged = nowProvider()
                ),
                group = root.copy(groups = root.groups + recycleBin)
            )
        }
        return KeePassRecycleBinResolution(repairedDatabase, recycleBin.uuid)
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child ->
            findGroup(child, uuid)?.let { return it }
        }
        return null
    }

    private fun isZeroUuid(uuid: UUID): Boolean {
        return uuid.mostSignificantBits == 0L && uuid.leastSignificantBits == 0L
    }

    private companion object {
        const val DEFAULT_RECYCLE_BIN_NAME = "Recycle Bin"
    }
}
