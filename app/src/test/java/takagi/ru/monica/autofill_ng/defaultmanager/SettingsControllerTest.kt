// Adapted from cr-zhichen/password-manager-switch (MIT), copyright (c) 2026 cr-zhichen.
// Upstream: 1f409c6d3e53d257a4ba2520ea5633f99e208a7a. See assets/licenses/password-manager-switch.txt.
package takagi.ru.monica.autofill_ng.defaultmanager

import org.junit.Assert.*
import org.junit.Test

class SettingsControllerTest {
    private val old = "old.manager/old.manager.Credentials"
    private val selected = "new.manager/new.manager.Credentials"
    private val fill = "new.manager/new.manager.Fill"

    private fun snapshot() = SettingsSnapshot(10, mapOf(
        SettingKey.CREDENTIALS to old,
        SettingKey.PRIMARY to old,
        SettingKey.AUTOFILL to null,
    ))

    private class FakeStore(initial: SettingsSnapshot) : SettingsStore {
        val userId = initial.userId
        val data = initial.values.toMutableMap()
        val writes = mutableListOf<Pair<SettingKey, String?>>()
        var onWrite: ((SettingKey, String?) -> Boolean)? = null
        var onRead: ((SettingKey) -> Unit)? = null
        override fun read(userId: Int, key: SettingKey): String? {
            check(userId == this.userId)
            onRead?.invoke(key)
            return data[key]
        }
        override fun write(userId: Int, key: SettingKey, value: String?) {
            check(userId == this.userId)
            writes += key to value
            if (onWrite?.invoke(key, value) != false) data[key] = value
        }
    }

    @Test fun secondWriteFailureRestoresFirstWriteAndPreservesUnrelatedAutofill() {
        val initial = snapshot()
        val store = FakeStore(initial)
        store.onWrite = { key, value ->
            if (key == SettingKey.PRIMARY && value == selected) throw IllegalStateException("denied")
            true
        }
        val failure = assertThrows(SettingsFailure::class.java) {
            SettingsController(store).apply(initial, selected, fill, false)
        }
        assertEquals(initial.values, store.data)
        assertTrue(failure.message!!.contains("已核对并恢复"))
        assertFalse(store.writes.any { it.first == SettingKey.AUTOFILL })
    }

    @Test fun zeroExitButIgnoredWriteIsNotReportedAsSuccess() {
        val initial = snapshot()
        val store = FakeStore(initial)
        store.onWrite = { key, _ -> key != SettingKey.PRIMARY }
        val failure = assertThrows(SettingsFailure::class.java) {
            SettingsController(store).apply(initial, selected, null, false)
        }
        assertEquals(initial.values, store.data)
        assertTrue(failure.detail.contains("读回的配置不一致"))
    }

    @Test fun commandMayChangeValueBeforeThrowingAndMustStillBeRolledBack() {
        val initial = snapshot()
        val store = FakeStore(initial)
        store.onWrite = { key, value ->
            if (key == SettingKey.PRIMARY && value == selected) {
                store.data[key] = value
                throw IllegalStateException("pipe interrupted")
            }
            true
        }
        assertThrows(SettingsFailure::class.java) { SettingsController(store).apply(initial, selected, null, false) }
        assertEquals(initial.values, store.data)
    }

    @Test fun stalePreviewMustNotOverwriteANewerSystemSelection() {
        val store = FakeStore(snapshot())
        store.data[SettingKey.AUTOFILL] = "another.manager/another.manager.Fill"
        assertThrows(SettingsFailure::class.java) { SettingsController(store).apply(snapshot(), selected, fill, false) }
        assertTrue(store.writes.isEmpty())
    }

    @Test fun rollbackDoesNotOverwriteConcurrentThirdPartyChange() {
        val store = FakeStore(snapshot())
        store.onWrite = { key, _ ->
            if (key == SettingKey.PRIMARY) {
                store.data[SettingKey.CREDENTIALS] = "another.manager/another.manager.Credentials"
                throw IllegalStateException("concurrent edit")
            }
            true
        }
        val failure = assertThrows(SettingsFailure::class.java) {
            SettingsController(store).apply(snapshot(), selected, null, false)
        }
        assertEquals("another.manager/another.manager.Credentials", store.data[SettingKey.CREDENTIALS])
        assertTrue(failure.message!!.contains("无法确认全部恢复"))
    }

    @Test fun preservingOtherProvidersDeduplicatesShortAndLongComponents() {
        val initial = snapshot().copy(values = snapshot().values + (SettingKey.CREDENTIALS to "$old:new.manager/.Credentials"))
        val store = FakeStore(initial)
        val result = SettingsController(store).apply(initial, selected, null, true)
        assertEquals(setOf(old, selected), components(result[SettingKey.CREDENTIALS]))
        assertEquals(selected, result[SettingKey.PRIMARY])
        assertNull(result[SettingKey.AUTOFILL])
    }

    @Test fun restoringAbsentValuesUsesDeleteAndExplicitUserInsteadOfLiteralNull() {
        val commands = mutableListOf<List<String>>()
        val store = ShellSettingsStore { args -> commands += args; CommandResult(0, "Deleted 1 rows") }
        store.write(10, SettingKey.AUTOFILL, null)
        assertEquals(listOf("/system/bin/settings", "--user", "10", "delete", "secure", "autofill_service"), commands.single())
    }

    @Test fun emptySettingAndAbsentSettingAreReadWithoutInventingAService() {
        assertNull(ShellSettingsStore { CommandResult(0, "null\n") }.read(0, SettingKey.AUTOFILL))
        assertEquals("", ShellSettingsStore { CommandResult(0, "\n") }.read(0, SettingKey.AUTOFILL))
    }

    @Test fun permissionExceptionWithZeroExitIsStillAnError() {
        val store = ShellSettingsStore { CommandResult(0, "java.lang.SecurityException: Permission denial") }
        assertThrows(SettingsFailure::class.java) { store.read(0, SettingKey.PRIMARY) }
    }

    @Test fun malformedSystemValueStopsMutationBeforeAnyWrite() {
        val store = FakeStore(snapshot())
        store.data[SettingKey.CREDENTIALS] = "settings: access denied"
        assertThrows(SettingsFailure::class.java) { SettingsController(store).apply(snapshot(), selected, fill, false) }
        assertTrue(store.writes.isEmpty())
    }

    @Test fun componentInputCannotContainShellSyntaxOrAdditionalComponents() {
        for (value in listOf("pkg/.Service;reboot", "pkg/.A:pkg/.B", "pkg/.Service\n", "$(id)/.Service")) {
            assertThrows(SettingsFailure::class.java) { canonicalComponent(value) }
        }
    }

    @Test fun rollbackWriteFailureIsReportedAndDoesNotClaimRestoration() {
        val store = FakeStore(snapshot())
        store.onWrite = { key, value ->
            if (key == SettingKey.PRIMARY || value == old) throw IllegalStateException("denied")
            true
        }
        val failure = assertThrows(SettingsFailure::class.java) {
            SettingsController(store).apply(snapshot(), selected, null, false)
        }
        assertEquals(selected, store.data[SettingKey.CREDENTIALS])
        assertTrue(failure.message!!.contains("无法确认全部恢复"))
    }

    @Test fun restoresAllThreeSettingsIncludingOriginallyUnsetValue() {
        val changed = SettingsSnapshot(10, mapOf(
            SettingKey.CREDENTIALS to selected, SettingKey.PRIMARY to selected, SettingKey.AUTOFILL to fill,
        ))
        val store = FakeStore(changed)
        val restored = SettingsController(store).replace(changed, snapshot())
        assertEquals(snapshot(), restored)
    }

    @Test fun selectingTheCurrentServiceWithShortNameDoesNotRewriteAnySetting() {
        val initial = snapshot()
        val store = FakeStore(initial)
        val result = SettingsController(store).apply(initial, "old.manager/.Credentials", null, false)
        assertTrue(initial.equivalentTo(result))
        assertTrue(store.writes.isEmpty())
    }
    @Test fun concurrentChangeBetweenWritesIsNotOverwritten() {
        val store = FakeStore(snapshot())
        val concurrent = "other.manager/other.manager.Credentials"
        store.onWrite = { key, _ ->
            if (key == SettingKey.CREDENTIALS) store.data[SettingKey.PRIMARY] = concurrent
            true
        }
        assertThrows(SettingsFailure::class.java) {
            SettingsController(store).apply(snapshot(), selected, fill, false)
        }
        assertEquals(concurrent, store.data[SettingKey.PRIMARY])
        assertFalse(store.writes.any { it.first == SettingKey.PRIMARY || it.first == SettingKey.AUTOFILL })
    }
}
