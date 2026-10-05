// Adapted from cr-zhichen/password-manager-switch (MIT), copyright (c) 2026 cr-zhichen.
// Upstream: 1f409c6d3e53d257a4ba2520ea5633f99e208a7a. See assets/licenses/password-manager-switch.txt.
package takagi.ru.monica.autofill_ng.defaultmanager

enum class SettingKey(val wireName: String) {
    CREDENTIALS("credential_service"),
    PRIMARY("credential_service_primary"),
    AUTOFILL("autofill_service"),
}

data class SettingsSnapshot(val userId: Int, val values: Map<SettingKey, String?>) {
    init { require(values.keys == SettingKey.entries.toSet()) }
    operator fun get(key: SettingKey): String? = values.getValue(key)
    fun equivalentTo(other: SettingsSnapshot): Boolean = userId == other.userId &&
        SettingKey.entries.all { components(this[it]) == components(other[it]) }

    fun withSelection(credential: String?, autofill: String?, keepOthers: Boolean): SettingsSnapshot {
        require(credential != null || autofill != null) { "请先选择一个服务。" }
        val desired = values.toMutableMap()
        credential?.let {
            val selected = canonicalComponent(it)
            val enabled = if (keepOthers) components(this[SettingKey.CREDENTIALS]) + selected else setOf(selected)
            desired[SettingKey.CREDENTIALS] = enabled.joinToString(":")
            desired[SettingKey.PRIMARY] = selected
        }
        autofill?.let { desired[SettingKey.AUTOFILL] = canonicalComponent(it) }
        return SettingsSnapshot(userId, desired)
    }
}

class SettingsFailure(message: String, val detail: String = "") : Exception(message)

interface SettingsStore {
    fun read(userId: Int, key: SettingKey): String?
    fun write(userId: Int, key: SettingKey, value: String?)
}

/** Android accepts both package/.Service and package/package.Service. */
fun canonicalComponent(value: String): String {
    val match = Regex("([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)/(\\.?[A-Za-z_$][A-Za-z0-9_.$]*)")
        .matchEntire(value) ?: throw SettingsFailure("系统返回了无法识别的服务名称。", value.take(500))
    val (pkg, name) = match.destructured
    return "$pkg/${if (name.startsWith('.')) pkg + name else name}"
}

fun components(value: String?): Set<String> = if (value.isNullOrEmpty()) emptySet() else
    value.split(':').map(::canonicalComponent).toSet()

/** Fixed allowlist and explicit Android user; no arbitrary shell text crosses Binder. */
class SettingsController(private val store: SettingsStore) {
    fun read(userId: Int): SettingsSnapshot {
        require(userId in 0..21474) { "无效的 Android 用户。" }
        return SettingsSnapshot(userId, SettingKey.entries.associateWith { key ->
            store.read(userId, key).also { validateValue(key, it) }
        })
    }

    fun apply(
        expected: SettingsSnapshot,
        credential: String?,
        autofill: String?,
        keepOthers: Boolean,
    ): SettingsSnapshot {
        return replace(expected, expected.withSelection(credential, autofill, keepOthers))
    }

    fun replace(expected: SettingsSnapshot, desired: SettingsSnapshot): SettingsSnapshot {
        require(expected.userId == desired.userId) { "不能恢复其他 Android 用户的配置。" }
        desired.values.forEach { (key, value) -> validateValue(key, value) }
        val before = read(expected.userId)
        if (!before.equivalentTo(expected)) {
            throw SettingsFailure("系统配置已发生变化，请刷新后重新选择。未写入任何设置。")
        }
        val changes = SettingKey.entries.filter { components(before[it]) != components(desired[it]) }
        if (changes.isEmpty()) return before
        val attempted = mutableListOf<SettingKey>()
        try {
            changes.forEach { key ->
                // Recheck remaining keys between writes; do not overwrite a newer selection.
                val progress = SettingsSnapshot(before.userId, before.values + attempted.associateWith { desired[it] })
                if (!read(before.userId).equivalentTo(progress)) {
                    throw SettingsFailure("系统配置在操作期间发生变化。")
                }
                // Record before calling: a failed command might already have changed the value.
                attempted += key
                store.write(before.userId, key, desired[key])
            }
            val after = read(before.userId)
            if (!after.equivalentTo(desired)) {
                throw SettingsFailure("写入后读回的配置不一致，系统可能拒绝或改回了设置。")
            }
            return after
        } catch (failure: Exception) {
            val rollbackErrors = mutableListOf<String>()
            attempted.asReversed().forEach { key ->
                try {
                    val current = components(store.read(before.userId, key))
                    when (current) {
                        components(before[key]) -> Unit
                        components(desired[key]) -> store.write(before.userId, key, before[key])
                        else -> throw SettingsFailure("配置被其他进程修改，未覆盖 ${key.wireName}。")
                    }
                } catch (rollback: Exception) {
                    rollbackErrors += "${key.wireName}: ${rollback.message}"
                }
            }
            val restored = runCatching { read(before.userId).equivalentTo(before) }.getOrDefault(false)
            val message = if (restored) "设置失败，已核对并恢复操作前的配置。" else
                "设置未完成，无法确认全部恢复。请刷新检查，必要时使用「恢复上次配置」。"
            throw SettingsFailure(message, buildString {
                appendLine(failure.message)
                if (failure is SettingsFailure && failure.detail.isNotEmpty()) appendLine(failure.detail)
                rollbackErrors.forEach { appendLine(it) }
            }.trim())
        }
    }

    private fun validateValue(key: SettingKey, value: String?) {
        if ((value?.length ?: 0) > 32768) throw SettingsFailure("系统配置过长。")
        val entries = components(value)
        if (key == SettingKey.AUTOFILL && entries.size > 1) {
            throw SettingsFailure("自动填充只能指定一个服务。")
        }
    }
}

data class CommandResult(val code: Int, val output: String)
fun interface CommandRunner { fun run(arguments: List<String>): CommandResult }

class ShellSettingsStore(private val runner: CommandRunner) : SettingsStore {
    override fun read(userId: Int, key: SettingKey): String? {
        val output = execute(userId, "get", key).trim()
        return output.takeUnless { it == "null" }
    }

    override fun write(userId: Int, key: SettingKey, value: String?) {
        if (value == null) execute(userId, "delete", key)
        else execute(userId, "put", key, value)
    }

    private fun execute(userId: Int, action: String, key: SettingKey, value: String? = null): String {
        val args = listOf("/system/bin/settings", "--user", userId.toString(), action, "secure", key.wireName) +
            listOfNotNull(value)
        val result = runner.run(args)
        val error = Regex("(?im)^\\s*(?:error|exception|.*SecurityException|permission denial|permission denied|bad arguments|unknown command)")
            .containsMatchIn(result.output)
        if (result.code != 0 || error) throw SettingsFailure(
            "系统拒绝读写设置，请检查 Shizuku 授权及系统限制。", result.output.take(4000),
        )
        return result.output
    }
}
