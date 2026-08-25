package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.regenerateVectors
import app.keemobile.kotpass.models.Group
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import takagi.ru.monica.data.KeePassCipherAlgorithm
import takagi.ru.monica.data.KeePassDatabaseCreationOptions
import takagi.ru.monica.data.KeePassFormatVersion
import takagi.ru.monica.data.KeePassKdfAlgorithm
import takagi.ru.monica.utils.KeePassCodecSupport

internal enum class KeePassDatabaseCompression {
    NONE,
    GZIP
}

internal data class KeePassDatabaseGroupOption(
    val uuid: UUID,
    val name: String,
    val path: String,
    val isRoot: Boolean,
    val isRecycleBin: Boolean
)

internal data class KeePassDatabaseSettingsSnapshot(
    val databaseId: Long,
    val formatVersion: KeePassFormatVersion,
    val name: String,
    val description: String,
    val defaultUsername: String,
    val color: String?,
    val maintenanceHistoryDays: Int,
    val historyMaxItems: Int,
    val historyMaxSizeBytes: Int,
    val masterKeyChangedAt: Instant?,
    val masterKeyChangeRecommendationDays: Int,
    val masterKeyChangeForceDays: Int,
    val recycleBinEnabled: Boolean,
    val recycleBinGroupUuid: UUID?,
    val templateGroupUuid: UUID?,
    val compression: KeePassDatabaseCompression,
    val cipherAlgorithm: KeePassCipherAlgorithm,
    val kdfAlgorithm: KeePassKdfAlgorithm,
    val transformRounds: Long,
    val memoryBytes: Long,
    val parallelism: Int,
    val readOnly: Boolean,
    val groups: List<KeePassDatabaseGroupOption>
) {
    fun toUpdate(): KeePassDatabaseSettingsUpdate = KeePassDatabaseSettingsUpdate(
        name = name,
        description = description,
        defaultUsername = defaultUsername,
        color = color,
        maintenanceHistoryDays = maintenanceHistoryDays,
        historyMaxItems = historyMaxItems,
        historyMaxSizeBytes = historyMaxSizeBytes,
        masterKeyChangeRecommendationDays = masterKeyChangeRecommendationDays,
        masterKeyChangeForceDays = masterKeyChangeForceDays,
        recycleBinEnabled = recycleBinEnabled,
        recycleBinGroupUuid = recycleBinGroupUuid,
        templateGroupUuid = templateGroupUuid,
        compression = compression,
        cipherAlgorithm = cipherAlgorithm,
        kdfAlgorithm = kdfAlgorithm,
        transformRounds = transformRounds,
        memoryBytes = memoryBytes,
        parallelism = parallelism
    )
}

internal data class KeePassDatabaseSettingsUpdate(
    val name: String,
    val description: String,
    val defaultUsername: String,
    val color: String?,
    val maintenanceHistoryDays: Int,
    val historyMaxItems: Int,
    val historyMaxSizeBytes: Int,
    val masterKeyChangeRecommendationDays: Int,
    val masterKeyChangeForceDays: Int,
    val recycleBinEnabled: Boolean,
    val recycleBinGroupUuid: UUID?,
    val templateGroupUuid: UUID?,
    val compression: KeePassDatabaseCompression,
    val cipherAlgorithm: KeePassCipherAlgorithm,
    val kdfAlgorithm: KeePassKdfAlgorithm,
    val transformRounds: Long,
    val memoryBytes: Long,
    val parallelism: Int
)

internal object KeePassDatabaseSettingsEditor {
    fun snapshot(
        databaseId: Long,
        database: KeePassDatabase,
        readOnly: Boolean
    ): KeePassDatabaseSettingsSnapshot {
        val meta = database.content.meta
        val formatVersion = when (database) {
            is KeePassDatabase.Ver3x -> KeePassFormatVersion.KDBX3
            is KeePassDatabase.Ver4x -> KeePassFormatVersion.KDBX4
        }
        val kdf = when (database) {
            is KeePassDatabase.Ver3x -> KdfSnapshot(
                algorithm = KeePassKdfAlgorithm.AES_KDF,
                rounds = database.header.transformRounds.toLong(),
                memoryBytes = KeePassDatabaseCreationOptions.DEFAULT_ARGON_MEMORY_BYTES,
                parallelism = 1
            )
            is KeePassDatabase.Ver4x -> when (val parameters = database.header.kdfParameters) {
                is KdfParameters.Aes -> KdfSnapshot(
                    algorithm = KeePassKdfAlgorithm.AES_KDF,
                    rounds = parameters.rounds.toLong(),
                    memoryBytes = KeePassDatabaseCreationOptions.DEFAULT_ARGON_MEMORY_BYTES,
                    parallelism = 1
                )
                is KdfParameters.Argon2 -> KdfSnapshot(
                    algorithm = if (parameters.variant == KdfParameters.Argon2.Variant.Argon2id) {
                        KeePassKdfAlgorithm.ARGON2ID
                    } else {
                        KeePassKdfAlgorithm.ARGON2D
                    },
                    rounds = parameters.iterations.toLong(),
                    memoryBytes = parameters.memory.toLong(),
                    parallelism = parameters.parallelism.toInt()
                )
            }
        }

        return KeePassDatabaseSettingsSnapshot(
            databaseId = databaseId,
            formatVersion = formatVersion,
            name = meta.name,
            description = meta.description,
            defaultUsername = meta.defaultUser,
            color = meta.color,
            maintenanceHistoryDays = meta.maintenanceHistoryDays,
            historyMaxItems = meta.historyMaxItems,
            historyMaxSizeBytes = meta.historyMaxSize,
            masterKeyChangedAt = meta.masterKeyChanged,
            masterKeyChangeRecommendationDays = meta.masterKeyChangeRec,
            masterKeyChangeForceDays = meta.masterKeyChangeForce,
            recycleBinEnabled = meta.recycleBinEnabled,
            recycleBinGroupUuid = meta.recycleBinUuid,
            templateGroupUuid = meta.entryTemplatesGroup,
            compression = database.header.compression.toPublicCompression(),
            cipherAlgorithm = database.header.cipherId.toCipherAlgorithm(),
            kdfAlgorithm = kdf.algorithm,
            transformRounds = kdf.rounds,
            memoryBytes = kdf.memoryBytes,
            parallelism = kdf.parallelism,
            readOnly = readOnly,
            groups = buildGroupOptions(database.content.group, meta.recycleBinUuid)
        )
    }

    fun apply(
        database: KeePassDatabase,
        update: KeePassDatabaseSettingsUpdate,
        nowProvider: () -> Instant = Instant::now,
        random: SecureRandom = SecureRandom(),
        cipherProviders: List<CipherProvider> = KeePassCodecSupport.cipherProviders
    ): KeePassDatabase {
        validate(database, update)
        val now = nowProvider()
        val originalMeta = database.content.meta
        val selectedRecycleUuid = update.recycleBinGroupUuid
        val selectedTemplateUuid = update.templateGroupUuid

        selectedRecycleUuid?.let { uuid ->
            require(findGroup(database.content.group, uuid) != null) {
                "Selected recycle-bin group does not exist"
            }
        }
        selectedTemplateUuid?.let { uuid ->
            require(findGroup(database.content.group, uuid) != null) {
                "Selected template group does not exist"
            }
        }

        val normalizedColor = update.color?.trim()?.takeIf { it.isNotEmpty() }
        val metaChanged = originalMeta.name != update.name.trim() ||
            originalMeta.description != update.description ||
            originalMeta.defaultUser != update.defaultUsername ||
            originalMeta.color != normalizedColor ||
            originalMeta.maintenanceHistoryDays != update.maintenanceHistoryDays ||
            originalMeta.historyMaxItems != update.historyMaxItems ||
            originalMeta.historyMaxSize != update.historyMaxSizeBytes ||
            originalMeta.masterKeyChangeRec != update.masterKeyChangeRecommendationDays ||
            originalMeta.masterKeyChangeForce != update.masterKeyChangeForceDays ||
            originalMeta.recycleBinEnabled != update.recycleBinEnabled ||
            originalMeta.recycleBinUuid != selectedRecycleUuid ||
            originalMeta.entryTemplatesGroup != selectedTemplateUuid

        val updatedMeta = originalMeta.copy(
            settingsChanged = if (metaChanged) now else originalMeta.settingsChanged,
            name = update.name.trim(),
            nameChanged = if (originalMeta.name != update.name.trim()) now else originalMeta.nameChanged,
            description = update.description,
            descriptionChanged = if (originalMeta.description != update.description) now else originalMeta.descriptionChanged,
            defaultUser = update.defaultUsername,
            defaultUserChanged = if (originalMeta.defaultUser != update.defaultUsername) now else originalMeta.defaultUserChanged,
            maintenanceHistoryDays = update.maintenanceHistoryDays,
            color = normalizedColor,
            masterKeyChangeRec = update.masterKeyChangeRecommendationDays,
            masterKeyChangeForce = update.masterKeyChangeForceDays,
            recycleBinEnabled = update.recycleBinEnabled,
            recycleBinUuid = selectedRecycleUuid,
            recycleBinChanged = if (
                originalMeta.recycleBinEnabled != update.recycleBinEnabled ||
                originalMeta.recycleBinUuid != selectedRecycleUuid
            ) now else originalMeta.recycleBinChanged,
            entryTemplatesGroup = selectedTemplateUuid,
            entryTemplatesGroupChanged = if (originalMeta.entryTemplatesGroup != selectedTemplateUuid) {
                now
            } else {
                originalMeta.entryTemplatesGroupChanged
            },
            historyMaxItems = update.historyMaxItems,
            historyMaxSize = update.historyMaxSizeBytes
        )

        var updated = database.withMeta(updatedMeta)
        if (update.recycleBinEnabled && selectedRecycleUuid == null) {
            updated = KeePassRecycleBinPolicy(nowProvider = { now }).ensure(updated).database
        }

        val requestedCompression = update.compression.toNativeCompression()
        val requestedCipher = KeePassCodecSupport.resolveCipherUuid(update.cipherAlgorithm)
        val headerSecurityChanged = database.header.cipherId != requestedCipher ||
            !kdfMatches(database, update)

        updated = when (updated) {
            is KeePassDatabase.Ver3x -> updated.copy(
                header = updated.header.copy(
                    cipherId = requestedCipher,
                    compression = requestedCompression,
                    transformRounds = update.transformRounds.toULong()
                )
            )
            is KeePassDatabase.Ver4x -> {
                val existingKdf = updated.header.kdfParameters
                val seedOrSalt = when (existingKdf) {
                    is KdfParameters.Aes -> existingKdf.seed
                    is KdfParameters.Argon2 -> existingKdf.salt
                }
                updated.copy(
                    header = updated.header.copy(
                        cipherId = requestedCipher,
                        compression = requestedCompression,
                        kdfParameters = when (update.kdfAlgorithm) {
                            KeePassKdfAlgorithm.AES_KDF -> KdfParameters.Aes(
                                rounds = update.transformRounds.toULong(),
                                seed = seedOrSalt
                            )
                            KeePassKdfAlgorithm.ARGON2D,
                            KeePassKdfAlgorithm.ARGON2ID -> KdfParameters.Argon2(
                                variant = if (update.kdfAlgorithm == KeePassKdfAlgorithm.ARGON2ID) {
                                    KdfParameters.Argon2.Variant.Argon2id
                                } else {
                                    KdfParameters.Argon2.Variant.Argon2d
                                },
                                salt = seedOrSalt,
                                parallelism = update.parallelism.toUInt(),
                                memory = update.memoryBytes.toULong(),
                                iterations = update.transformRounds.toULong(),
                                version = 0x13U,
                                secretKey = null,
                                associatedData = null
                            )
                        }
                    )
                )
            }
        }

        return if (headerSecurityChanged) {
            updated.regenerateVectors(random = random, cipherProviders = cipherProviders)
        } else {
            updated
        }
    }

    private fun validate(database: KeePassDatabase, update: KeePassDatabaseSettingsUpdate) {
        require(update.name.trim().isNotEmpty()) { "Database name cannot be empty" }
        require(update.maintenanceHistoryDays >= -1) { "History maintenance days must be -1 or greater" }
        require(update.historyMaxItems >= -1) { "History item limit must be -1 or greater" }
        require(update.historyMaxSizeBytes >= -1) { "History size limit must be -1 or greater" }
        require(update.masterKeyChangeRecommendationDays >= -1) { "Master-key recommendation must be -1 or greater" }
        require(update.masterKeyChangeForceDays >= -1) { "Master-key force interval must be -1 or greater" }
        require(update.transformRounds >= 1L) { "KDF rounds must be at least 1" }

        if (database is KeePassDatabase.Ver3x) {
            require(update.kdfAlgorithm == KeePassKdfAlgorithm.AES_KDF) {
                "KDBX3 supports AES-KDF only"
            }
            require(update.cipherAlgorithm != KeePassCipherAlgorithm.CHACHA20) {
                "KDBX3 does not support ChaCha20"
            }
        } else if (update.kdfAlgorithm != KeePassKdfAlgorithm.AES_KDF) {
            require(update.memoryBytes in KeePassDatabaseCreationOptions.MIN_MEMORY_BYTES..KeePassDatabaseCreationOptions.MAX_MEMORY_BYTES) {
                "Argon2 memory is outside the supported range"
            }
            require(update.parallelism in 1..32) { "Argon2 parallelism must be between 1 and 32" }
        }
    }

    private fun kdfMatches(database: KeePassDatabase, update: KeePassDatabaseSettingsUpdate): Boolean {
        return when (database) {
            is KeePassDatabase.Ver3x -> update.kdfAlgorithm == KeePassKdfAlgorithm.AES_KDF &&
                database.header.transformRounds.toLong() == update.transformRounds
            is KeePassDatabase.Ver4x -> when (val existing = database.header.kdfParameters) {
                is KdfParameters.Aes -> update.kdfAlgorithm == KeePassKdfAlgorithm.AES_KDF &&
                    existing.rounds.toLong() == update.transformRounds
                is KdfParameters.Argon2 -> {
                    val requestedVariant = if (update.kdfAlgorithm == KeePassKdfAlgorithm.ARGON2ID) {
                        KdfParameters.Argon2.Variant.Argon2id
                    } else {
                        KdfParameters.Argon2.Variant.Argon2d
                    }
                    update.kdfAlgorithm != KeePassKdfAlgorithm.AES_KDF &&
                        existing.variant == requestedVariant &&
                        existing.iterations.toLong() == update.transformRounds &&
                        existing.memory.toLong() == update.memoryBytes &&
                        existing.parallelism.toInt() == update.parallelism
                }
            }
        }
    }

    private fun KeePassDatabase.withMeta(meta: app.keemobile.kotpass.models.Meta): KeePassDatabase = when (this) {
        is KeePassDatabase.Ver3x -> copy(content = content.copy(meta = meta))
        is KeePassDatabase.Ver4x -> copy(content = content.copy(meta = meta))
    }

    private fun buildGroupOptions(root: Group, recycleBinUuid: UUID?): List<KeePassDatabaseGroupOption> {
        val result = mutableListOf<KeePassDatabaseGroupOption>()
        fun visit(group: Group, parentPath: String?, rootNode: Boolean) {
            val path = if (parentPath.isNullOrBlank()) group.name else "$parentPath / ${group.name}"
            result += KeePassDatabaseGroupOption(
                uuid = group.uuid,
                name = group.name,
                path = path,
                isRoot = rootNode,
                isRecycleBin = group.uuid == recycleBinUuid
            )
            group.groups.forEach { child -> visit(child, path, false) }
        }
        visit(root, null, true)
        return result
    }

    private fun findGroup(group: Group, uuid: UUID): Group? {
        if (group.uuid == uuid) return group
        group.groups.forEach { child -> findGroup(child, uuid)?.let { return it } }
        return null
    }

    private fun DatabaseHeader.Compression.toPublicCompression(): KeePassDatabaseCompression = when (this) {
        DatabaseHeader.Compression.None -> KeePassDatabaseCompression.NONE
        DatabaseHeader.Compression.GZip -> KeePassDatabaseCompression.GZIP
    }

    private fun KeePassDatabaseCompression.toNativeCompression(): DatabaseHeader.Compression = when (this) {
        KeePassDatabaseCompression.NONE -> DatabaseHeader.Compression.None
        KeePassDatabaseCompression.GZIP -> DatabaseHeader.Compression.GZip
    }

    private fun UUID.toCipherAlgorithm(): KeePassCipherAlgorithm = when (this) {
        BaseCiphers.Aes.uuid -> KeePassCipherAlgorithm.AES
        BaseCiphers.ChaCha20.uuid -> KeePassCipherAlgorithm.CHACHA20
        TwofishCipher.uuid -> KeePassCipherAlgorithm.TWOFISH
        else -> throw IllegalArgumentException("Unsupported KeePass cipher: $this")
    }

    private data class KdfSnapshot(
        val algorithm: KeePassKdfAlgorithm,
        val rounds: Long,
        val memoryBytes: Long,
        val parallelism: Int
    )
}
