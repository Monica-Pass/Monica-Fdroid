package takagi.ru.monica.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.regenerateVectors
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyContent
import app.keemobile.kotpass.database.modifiers.modifyCustomIcons
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.CustomIcon
import app.keemobile.kotpass.models.DeletedObject
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import app.keemobile.kotpass.models.TimeData
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassConflictCenterTest {
    @Test
    fun `snapshot reports entry group metadata tombstone binary and icon changes`() {
        val groupUuid = UUID.randomUUID()
        val entryUuid = UUID.randomUUID()
        val iconUuid = UUID.randomUUID()
        val binary = BinaryData.Uncompressed(false, "base attachment".toByteArray())
        val base = database("Base")
            .modifyParentGroup {
                copy(groups = listOf(Group(uuid = groupUuid, name = "Accounts", entries = listOf(entry(entryUuid, "Base")))))
            }
        val local = base
            .modifyParentGroup { copy(groups = groups.map { it.copy(name = "Local accounts", entries = listOf(entry(entryUuid, "Local"))) }) }
            .modifyContent {
                copy(
                    meta = meta.copy(description = "Local description"),
                    deletedObjects = listOf(DeletedObject(UUID.randomUUID(), Instant.parse("2026-08-18T00:00:00Z")))
                )
            }
            .modifyBinaries { mapOf(binary.hash to binary) }
            .modifyCustomIcons {
                mapOf(iconUuid to CustomIcon(byteArrayOf(1, 2, 3), "Local icon", null))
            }
        val remoteBinary = BinaryData.Uncompressed(false, "remote attachment".toByteArray())
        val remote = base
            .modifyParentGroup { copy(groups = groups.map { it.copy(entries = listOf(entry(entryUuid, "Remote"))) }) }
            .modifyBinaries { mapOf(remoteBinary.hash to remoteBinary) }

        val snapshot = KeePassConflictCenter.inspect(base, local, remote)

        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.ENTRY && it.ambiguous })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.GROUP })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.DATABASE_METADATA })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.DELETED_OBJECT })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.BINARY })
        assertTrue(snapshot.items.any { it.objectType == KeePassConflictObjectType.CUSTOM_ICON })
        assertFalse(snapshot.mergeRecommended)
    }

    @Test
    fun `whole database decisions preserve the selected side and cancellation writes nothing`() {
        val base = database("Base")
        val local = base.modifyContent { copy(meta = meta.copy(name = "Local")) }
        val remote = base.modifyContent { copy(meta = meta.copy(name = "Remote")) }

        val keepLocal = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.KEEP_LOCAL)
        val useRemote = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.USE_REMOTE)
        val cancel = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.CANCEL)

        assertEquals(local, keepLocal.database)
        assertEquals(remote, useRemote.database)
        assertNull(cancel.database)
        assertTrue(cancel.cancelled)
    }

    @Test
    fun `merge keeps independent changes and creates a conflict copy for divergent entry edits`() {
        val sharedUuid = UUID.randomUUID()
        val localOnlyUuid = UUID.randomUUID()
        val remoteOnlyUuid = UUID.randomUUID()
        val base = database("Base").modifyParentGroup {
            copy(entries = listOf(entry(sharedUuid, "Shared")))
        }
        val local = base.modifyParentGroup {
            copy(entries = listOf(entry(sharedUuid, "Local shared"), entry(localOnlyUuid, "Local only")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(entry(sharedUuid, "Remote shared"), entry(remoteOnlyUuid, "Remote only")))
        }

        val result = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.MERGE)

        assertNotNull(result.database)
        val merged = result.database!!
        assertNotNull(findEntry(merged.content.group, sharedUuid))
        assertNotNull(findEntry(merged.content.group, localOnlyUuid))
        assertNotNull(findEntry(merged.content.group, remoteOnlyUuid))
        assertEquals("Local shared", title(findEntry(merged.content.group, sharedUuid)!!))
        assertTrue(allEntries(merged.content.group).any { title(it).contains("conflict", ignoreCase = true) })
        assertEquals(1, result.conflictCopyCount)
    }

    @Test
    fun `selected merge combines independent field edits without a conflict copy`() {
        val entryUuid = UUID.randomUUID()
        val baseEntry = credentialEntry(entryUuid, "Account", "base-user", "base-password")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(baseEntry)) }
        val local = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "local-user", "base-password")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "base-user", "remote-password")))
        }

        val snapshot = KeePassConflictCenter.inspect(base, local, remote)
        val result = KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap())

        assertEquals(0, snapshot.ambiguousCount)
        assertEquals(0, result.conflictCopyCount)
        val mergedEntry = findEntry(result.database!!.content.group, entryUuid)!!
        assertEquals("local-user", field(mergedEntry, "UserName"))
        assertEquals("remote-password", field(mergedEntry, "Password"))
    }

    @Test
    fun `selected merge requires and applies a choice for a divergent field`() {
        val entryUuid = UUID.randomUUID()
        val base = database("Base").modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "base-user", "password")))
        }
        val local = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "local-user", "password")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "remote-user", "password")))
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.FIELD && it.label == "UserName" }
        val missingChoice = runCatching {
            KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap())
        }.exceptionOrNull()
        assertTrue(missingChoice is IllegalArgumentException)

        val localResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.LOCAL)
        )
        val remoteResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.REMOTE)
        )

        assertEquals("local-user", field(findEntry(localResult.database!!.content.group, entryUuid)!!, "UserName"))
        assertEquals("remote-user", field(findEntry(remoteResult.database!!.content.group, entryUuid)!!, "UserName"))
    }

    @Test
    fun `protected field conflict hides values while preserving selected protection`() {
        val entryUuid = UUID.randomUUID()
        val base = database("Base").modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "user", "base-secret")))
        }
        val local = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "user", "local-secret")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "user", "remote-secret")))
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.FIELD && it.label == "Password" }

        assertTrue(detail.protectedValue)
        assertFalse(detail.localSummary.orEmpty().contains("local-secret"))
        assertFalse(detail.remoteSummary.orEmpty().contains("remote-secret"))

        val result = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.REMOTE)
        )
        val password = findEntry(result.database!!.content.group, entryUuid)!!.fields.getValue("Password")
        assertTrue(password is EntryValue.Encrypted)
        assertEquals("remote-secret", password.content)
    }

    @Test
    fun `selected merge resolves deletion against an edited entry`() {
        val entryUuid = UUID.randomUUID()
        val baseEntry = credentialEntry(entryUuid, "Account", "base-user", "password")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(baseEntry)) }
        val local = base.modifyParentGroup { copy(entries = emptyList()) }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(credentialEntry(entryUuid, "Account", "remote-user", "password")))
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.EXISTENCE }
        val deleteResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.LOCAL)
        )
        val keepResult = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.REMOTE)
        )

        assertNull(findEntry(deleteResult.database!!.content.group, entryUuid))
        assertEquals("remote-user", field(findEntry(keepResult.database!!.content.group, entryUuid)!!, "UserName"))
    }

    @Test
    fun `selected merge resolves divergent entry locations`() {
        val entryUuid = UUID.randomUUID()
        val sourceUuid = UUID.randomUUID()
        val localTargetUuid = UUID.randomUUID()
        val remoteTargetUuid = UUID.randomUUID()
        val sharedEntry = credentialEntry(entryUuid, "Account", "user", "password")
        val base = database("Base").modifyParentGroup {
            copy(
                groups = listOf(
                    Group(uuid = sourceUuid, name = "Source", entries = listOf(sharedEntry)),
                    Group(uuid = localTargetUuid, name = "Local target"),
                    Group(uuid = remoteTargetUuid, name = "Remote target")
                )
            )
        }
        val local = base.modifyParentGroup {
            copy(
                groups = groups.map { group ->
                    when (group.uuid) {
                        sourceUuid -> group.copy(entries = emptyList())
                        localTargetUuid -> group.copy(entries = listOf(sharedEntry))
                        else -> group
                    }
                }
            )
        }
        val remote = base.modifyParentGroup {
            copy(
                groups = groups.map { group ->
                    when (group.uuid) {
                        sourceUuid -> group.copy(entries = emptyList())
                        remoteTargetUuid -> group.copy(entries = listOf(sharedEntry))
                        else -> group
                    }
                }
            )
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote)
            .items.single { it.objectType == KeePassConflictObjectType.ENTRY }
            .details.single { it.kind == KeePassConflictDetailKind.LOCATION }
        val result = KeePassConflictCenter.resolveSelected(
            base,
            local,
            remote,
            mapOf(detail.id to KeePassConflictResolutionSide.LOCAL)
        )

        assertEquals(localTargetUuid, findEntryParent(result.database!!.content.group, entryUuid))
    }

    @Test
    fun `automatic merge combines fields and retains protected extensions history and attachments`() {
        val uuid = UUID.randomUUID()
        val passkey = "{\"credentialId\":\"synthetic-passkey\",\"signCount\":0,\"provider\":\"Monica\"}"
        val attachment = BinaryData.Uncompressed(false, "synthetic attachment".toByteArray())
        val original = credentialEntry(uuid, "Account", "user", "password").copy(
            fields = EntryFields.of(
                "Title" to EntryValue.Plain("Account"),
                "UserName" to EntryValue.Plain("user"),
                "Password" to EntryValue.Encrypted(EncryptedValue.fromString("password")),
                "MonicaPasskey" to EntryValue.Encrypted(EncryptedValue.fromString(passkey)),
                "plugin-unknown" to EntryValue.Plain("unchanged opaque data")
            ),
            binaries = listOf(BinaryReference(hash = attachment.hash, name = "fixture.bin"))
        )
        val localHistory = original.copy(history = emptyList())
        val remoteHistory = entry(uuid, "Earlier revision")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(original)) }
            .modifyBinaries { mapOf(attachment.hash to attachment) }
        val local = base.modifyParentGroup {
            copy(entries = listOf(withField(original, "UserName", EntryValue.Plain("local-user")).copy(history = listOf(localHistory))))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(withField(original, "Password", EntryValue.Encrypted(EncryptedValue.fromString("remote-password"))).copy(history = listOf(remoteHistory))))
        }

        val result = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.MERGE)
        val merged = findEntry(result.database!!.content.group, uuid)!!
        assertEquals(0, result.conflictCopyCount)
        assertEquals(1, allEntries(result.database!!.content.group).size)
        assertEquals("local-user", field(merged, "UserName"))
        assertEquals("remote-password", field(merged, "Password"))
        assertTrue(merged.fields["Password"] is EntryValue.Encrypted)
        assertTrue(merged.fields["MonicaPasskey"] is EntryValue.Encrypted)
        assertEquals(passkey, field(merged, "MonicaPasskey"))
        assertEquals("unchanged opaque data", field(merged, "plugin-unknown"))
        assertEquals(original.binaries, merged.binaries)
        assertEquals(setOf("Account", "Earlier revision"), merged.history.map(::title).toSet())
    }

    @Test
    fun `automatic preservation of one conflict does not duplicate unrelated field merges`() {
        val independent = credentialEntry(UUID.randomUUID(), "Independent", "user", "password")
        val disputed = entry(UUID.randomUUID(), "Base title")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(independent, disputed)) }
        val local = base.modifyParentGroup {
            copy(entries = listOf(withField(independent, "UserName", EntryValue.Plain("local-user")), entry(disputed.uuid, "Local title")))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(withField(independent, "Password", EntryValue.Encrypted(EncryptedValue.fromString("remote-password"))), entry(disputed.uuid, "Remote title")))
        }

        val result = KeePassConflictCenter.resolve(base, local, remote, KeePassConflictDecision.MERGE)
        val all = allEntries(result.database!!.content.group)
        assertEquals(1, result.conflictCopyCount)
        assertEquals(3, all.size)
        val merged = all.single { it.uuid == independent.uuid }
        assertEquals("local-user", field(merged, "UserName"))
        assertEquals("remote-password", field(merged, "Password"))
        assertTrue(all.any { title(it).startsWith("Remote title") })
    }

    @Test
    fun `independent expiry change survives a newer remote field edit`() {
        val expiresAt = Instant.parse("2030-01-01T00:00:00Z")
        val createdAt = Instant.parse("2026-09-01T00:00:00Z")
        val original = credentialEntry(UUID.randomUUID(), "Account", "user", "password").copy(
            times = TimeData(
                creationTime = createdAt, lastAccessTime = createdAt, locationChanged = createdAt,
                expiryTime = null, expires = false, lastModificationTime = createdAt
            )
        )
        val base = database("Base").modifyParentGroup { copy(entries = listOf(original)) }
        val local = base.modifyParentGroup {
            copy(entries = listOf(original.copy(times = original.times!!.copy(
                expires = true, expiryTime = expiresAt, lastModificationTime = Instant.parse("2026-09-02T00:00:00Z")
            ))))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(withField(original, "UserName", EntryValue.Plain("remote-user")).copy(
                times = original.times!!.copy(lastModificationTime = Instant.parse("2026-09-03T00:00:00Z"))
            )))
        }

        val result = KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap())
        val merged = findEntry(result.database!!.content.group, original.uuid)!!
        assertEquals("remote-user", field(merged, "UserName"))
        assertTrue(merged.times!!.expires)
        assertEquals(expiresAt, merged.times!!.expiryTime)
    }

    @Test
    fun `independent KDBX saves do not create database settings conflicts`() {
        for (version in listOf(3, 4)) {
            val original = credentialEntry(UUID.randomUUID(), "Account", "user", "password")
            val base = roundTrip(fastDatabase(version).modifyParentGroup { copy(entries = listOf(original)) })
            val savedEntry = base.content.group.entries.single()
            val local = roundTrip(base.modifyParentGroup {
                copy(entries = listOf(withField(savedEntry, "UserName", EntryValue.Plain("local-user"))))
            })
            val remote = roundTrip(base.modifyParentGroup {
                copy(entries = listOf(withField(savedEntry, "Notes", EntryValue.Plain("remote-note"))))
            })

            val snapshot = KeePassConflictCenter.inspect(base, local, remote)
            assertEquals("KDBX $version", 0, snapshot.ambiguousCount)
            assertFalse(snapshot.items.any { it.objectType == KeePassConflictObjectType.DATABASE_METADATA })
            val merged = roundTrip(KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap()).database!!)
            val result = merged.content.group.entries.single()
            assertEquals("local-user", field(result, "UserName"))
            assertEquals("remote-note", field(result, "Notes"))
            assertEquals("password", field(result, "Password"))
            assertTrue(merged.header.masterSeed.size > 0)
            assertTrue(merged.header.encryptionIV.size > 0)
        }
    }

    @Test
    fun `remote KDF settings survive an independent local save`() {
        val original = credentialEntry(UUID.randomUUID(), "Account", "user", "password")
        val base = roundTrip(fastDatabase(4).modifyParentGroup { copy(entries = listOf(original)) }) as KeePassDatabase.Ver4x
        val local = roundTrip(base.modifyParentGroup {
            copy(entries = listOf(withField(entries.single(), "UserName", EntryValue.Plain("local-user"))))
        })
        val kdf = base.header.kdfParameters as KdfParameters.Aes
        val remote = roundTrip(base.copy(header = base.header.copy(kdfParameters = kdf.copy(rounds = 32U))))

        val setting = KeePassConflictCenter.inspect(base, local, remote).items.single {
            it.objectType == KeePassConflictObjectType.DATABASE_METADATA
        }
        assertNull(setting.localChange)
        assertEquals(KeePassConflictChangeType.MODIFIED, setting.remoteChange)
        assertFalse(setting.ambiguous)
        val merged = roundTrip(KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap()).database!!) as KeePassDatabase.Ver4x
        assertEquals(32UL, (merged.header.kdfParameters as KdfParameters.Aes).rounds)
        assertEquals("local-user", field(merged.content.group.entries.single(), "UserName"))
    }

    @Test
    fun `Argon2 salt regeneration is ignored while its cost changes remain settings`() {
        val base = database("Base") as KeePassDatabase.Ver4x
        val local = base.regenerateVectors(cipherProviders = BaseCiphers.entries)
        val remote = base.regenerateVectors(cipherProviders = BaseCiphers.entries) as KeePassDatabase.Ver4x
        assertTrue(KeePassConflictCenter.inspect(base, local, remote).items.isEmpty())

        val kdf = remote.header.kdfParameters as KdfParameters.Argon2
        val changedRemote = remote.copy(header = remote.header.copy(kdfParameters = kdf.copy(iterations = kdf.iterations + 1U)))
        val merged = KeePassConflictCenter.resolveSelected(base, local, changedRemote, emptyMap()).database!! as KeePassDatabase.Ver4x
        assertEquals(kdf.iterations + 1U, (merged.header.kdfParameters as KdfParameters.Argon2).iterations)
    }

    @Test
    fun `an attachment-only local edit merges with a remote field edit`() {
        val original = credentialEntry(UUID.randomUUID(), "Account", "user", "password")
        val attachment = BinaryData.Uncompressed(true, "synthetic local attachment".toByteArray())
        val reference = BinaryReference(hash = attachment.hash, name = "local.txt")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(original)) }
        val local = base.modifyParentGroup { copy(entries = listOf(original.copy(binaries = listOf(reference)))) }
            .modifyBinaries { mapOf(attachment.hash to attachment) }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(withField(original, "UserName", EntryValue.Plain("remote-user"))))
        }

        val result = KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap())
        val merged = result.database!!
        assertEquals(0, result.conflictCopyCount)
        assertEquals("remote-user", field(merged.content.group.entries.single(), "UserName"))
        assertEquals(listOf(reference), merged.content.group.entries.single().binaries)
        assertEquals(attachment, merged.binaries[attachment.hash])
    }

    @Test
    fun `history-only local edits survive and distinct properties do not collapse`() {
        val original = credentialEntry(UUID.randomUUID(), "Account", "user", "password")
        val localHistory = original.copy(tags = listOf("local-history"))
        val remoteHistory = original.copy(tags = listOf("remote-history"))
        val base = database("Base").modifyParentGroup { copy(entries = listOf(original)) }
        val local = base.modifyParentGroup { copy(entries = listOf(original.copy(history = listOf(localHistory)))) }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(withField(original, "UserName", EntryValue.Plain("remote-user")).copy(history = listOf(remoteHistory))))
        }

        val result = KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap())
        val merged = result.database!!.content.group.entries.single()
        assertEquals("remote-user", field(merged, "UserName"))
        assertEquals(setOf("local-history", "remote-history"), merged.history.flatMap { it.tags }.toSet())
        assertEquals(2, merged.history.size)
    }

    @Test
    fun `protection-only changes are compared against concurrent field edits`() {
        val original = withField(entry(UUID.randomUUID(), "Account"), "custom", EntryValue.Plain("value"))
        val base = database("Base").modifyParentGroup { copy(entries = listOf(original)) }
        val local = base.modifyParentGroup {
            copy(entries = listOf(withField(original, "custom", EntryValue.Encrypted(EncryptedValue.fromString("value")))))
        }
        val remote = base.modifyParentGroup {
            copy(entries = listOf(withField(original, "custom", EntryValue.Plain("remote-value"))))
        }

        val detail = KeePassConflictCenter.inspect(base, local, remote).items.single().details.single()
        assertTrue(detail.protectedValue)
        assertTrue(runCatching { KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap()) }.isFailure)
        val merged = KeePassConflictCenter.resolveSelected(base, local, remote,
            mapOf(detail.id to KeePassConflictResolutionSide.LOCAL)).database!!.content.group.entries.single()
        assertEquals("value", field(merged, "custom"))
        assertTrue(merged.fields["custom"] is EntryValue.Encrypted)
    }

    @Test
    fun `usage-only timestamps and counts do not appear as conflicting edits`() {
        val original = credentialEntry(UUID.randomUUID(), "Account", "user", "password")
        val base = database("Base").modifyParentGroup { copy(entries = listOf(original)) }
        val local = base.modifyParentGroup {
            copy(entries = listOf(original.copy(times = original.times!!.copy(
                lastAccessTime = Instant.parse("2030-01-01T00:00:00Z"), usageCount = 10
            ))))
        }
        assertTrue(KeePassConflictCenter.inspect(base, local, base).items.isEmpty())
    }

    @Test
    fun `deleting a folder cannot erase concurrent new entries or their reused attachments`() {
        val folderId = UUID.randomUUID()
        val nestedId = UUID.randomUUID()
        val attachment = BinaryData.Uncompressed(false, "reused attachment".toByteArray())
        val reference = BinaryReference(hash = attachment.hash, name = "note.txt")
        val original = entry(UUID.randomUUID(), "Old account").copy(binaries = listOf(reference))
        val added = entry(UUID.randomUUID(), "New account").copy(binaries = listOf(reference))
        val base = database("Base").modifyParentGroup {
            copy(groups = listOf(Group(folderId, "Accounts", entries = listOf(original))))
        }.modifyBinaries { mapOf(attachment.hash to attachment) }
        val deleted = base.modifyParentGroup { copy(groups = emptyList()) }.modifyBinaries { emptyMap() }
        val edited = base.modifyParentGroup {
            copy(groups = groups.map { it.copy(groups = listOf(Group(nestedId, "New folder", entries = listOf(added)))) })
        }

        // Check both directions; a folder deletion only removes unchanged old content.
        for ((local, remote) in listOf(deleted to edited, edited to deleted)) {
            val result = KeePassConflictCenter.resolveSelected(base, local, remote, emptyMap()).database!!
            assertNull(findEntry(result.content.group, original.uuid))
            assertNotNull(findEntry(result.content.group, added.uuid))
            assertEquals(nestedId, findEntryParent(result.content.group, added.uuid))
            assertEquals(attachment, result.binaries[attachment.hash])
        }
    }

    private fun fastDatabase(version: Int): KeePassDatabase = if (version == 3) {
        val created = KeePassDatabase.Ver3x.create("Root", Meta(name = "Base"), Credentials.from(EncryptedValue.fromString("password")))
        created.copy(header = created.header.copy(transformRounds = 16U))
    } else {
        val created = database("Base") as KeePassDatabase.Ver4x
        created.copy(header = created.header.copy(kdfParameters = KdfParameters.Aes(16U, created.header.masterSeed)))
    }

    private fun roundTrip(database: KeePassDatabase): KeePassDatabase = ByteArrayOutputStream().use { output ->
        database.encode(output)
        output.toByteArray().inputStream().use { KeePassDatabase.decode(it, database.credentials) }
    }

    private fun withField(entry: Entry, name: String, value: EntryValue): Entry = entry.copy(
        fields = EntryFields.of(*(entry.fields.toMap() + (name to value)).toList().toTypedArray())
    )

    private fun database(name: String): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(generator = "Monica conflict test", name = name),
        credentials = Credentials.from(EncryptedValue.fromString("password"))
    )

    private fun entry(uuid: UUID, title: String): Entry = Entry(
        uuid = uuid,
        fields = EntryFields.of("Title" to EntryValue.Plain(title))
    )

    private fun credentialEntry(
        uuid: UUID,
        title: String,
        username: String,
        password: String
    ): Entry = Entry(
        uuid = uuid,
        fields = EntryFields.of(
            "Title" to EntryValue.Plain(title),
            "UserName" to EntryValue.Plain(username),
            "Password" to EntryValue.Encrypted(EncryptedValue.fromString(password))
        )
    )

    private fun field(entry: Entry, name: String): String = entry.fields.getValue(name).content

    private fun title(entry: Entry): String = entry.fields.getValue("Title").content

    private fun findEntry(group: Group, uuid: UUID): Entry? {
        group.entries.firstOrNull { it.uuid == uuid }?.let { return it }
        group.groups.forEach { child -> findEntry(child, uuid)?.let { return it } }
        return null
    }

    private fun allEntries(group: Group): List<Entry> = group.entries + group.groups.flatMap(::allEntries)

    private fun findEntryParent(group: Group, uuid: UUID): UUID? {
        if (group.entries.any { it.uuid == uuid }) return group.uuid
        group.groups.forEach { child -> findEntryParent(child, uuid)?.let { return it } }
        return null
    }
}
