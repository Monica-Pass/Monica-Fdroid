package takagi.ru.monica.autofill_ng.protection

import android.content.Context
import android.os.Binder
import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import takagi.ru.monica.BuildConfig

/** A short-lived, shell-only helper. It has no vault, file, network, or general shell API. */
@Keep
class ShizukuAccessibilityUserService(context: Context) : IMonicaAccessibilityRecovery.Stub() {
    private val callerUid = context.applicationInfo.uid
    private val userId = callerUid / 100_000
    private val transactionLock = Any()
    private var used = false

    init {
        check(context.packageName == BuildConfig.APPLICATION_ID)
        check(Process.myUid() == SHELL_UID) { "Only the ADB backend is supported" }
        // Do not leave a privileged helper running if its client disappears during binding.
        Thread({
            Thread.sleep(30_000)
            synchronized(transactionLock) { exitProcess(0) }
        }, "MonicaRecoveryExpiry").apply { isDaemon = true }.start()
    }

    override fun restartMonicaAccessibility(): Int = synchronized(transactionLock) {
        if (Binder.getCallingUid() != callerUid || Process.myUid() != SHELL_UID || used) {
            return@synchronized AccessibilityRecoveryResult.NOT_AUTHORIZED.code
        }
        used = true
        AccessibilityServiceRestart(
            settings = object : AccessibilityServiceSettings {
                override fun readEnabledServices(): String {
                    val value = settingsCommand("get").trimEnd('\n', '\r')
                    check(value.length <= MAX_SETTING_LENGTH && '\n' !in value && '\r' !in value)
                    return if (value == "null") "" else value
                }

                override fun writeEnabledServices(value: String) {
                    check(value.length <= MAX_SETTING_LENGTH && '\n' !in value && '\r' !in value)
                    settingsCommand("put", value)
                }
            },
            awaitDisconnect = { Thread.sleep(350) },
        ).restart().code
    }

    override fun destroy() {
        val uid = Binder.getCallingUid()
        if (uid != callerUid && uid != SHELL_UID && uid != 0) return
        // Let an in-flight restore finish before the Shizuku server disposes of this process.
        synchronized(transactionLock) { exitProcess(0) }
    }

    private fun settingsCommand(operation: String, value: String? = null): String {
        check(operation == "get" || operation == "put")
        val arguments = mutableListOf(
            "/system/bin/settings", "--user", userId.toString(), operation,
            "secure", "enabled_accessibility_services",
        )
        if (value != null) arguments.add(value)
        // Each value is a separate argv entry. No `su`, `sh -c`, interpolation, or arbitrary input.
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        try {
            check(process.waitFor(3, TimeUnit.SECONDS)) { "Settings operation timed out" }
            check(process.exitValue() == 0) { "Settings operation rejected" }
            val output = ByteArray(MAX_SETTING_LENGTH + 1)
            var length = 0
            process.inputStream.use { input ->
                while (length < output.size) {
                    val count = input.read(output, length, output.size - length)
                    if (count < 0) break
                    length += count
                }
            }
            check(length <= MAX_SETTING_LENGTH)
            return String(output, 0, length, Charsets.UTF_8)
        } finally {
            process.destroyForcibly()
        }
    }

    companion object {
        private const val SHELL_UID = 2000
        private const val MAX_SETTING_LENGTH = 65_536
    }
}
