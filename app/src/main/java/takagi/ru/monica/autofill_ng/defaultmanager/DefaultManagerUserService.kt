package takagi.ru.monica.autofill_ng.defaultmanager

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import takagi.ru.monica.BuildConfig

/** Only three secure settings, owned app UID, fixed Monica targets, and the app's Android user. */
@Keep
class DefaultManagerUserService(private val context: Context) : IMonicaDefaultManager.Stub() {
    private val callerUid = context.applicationInfo.uid
    private val userId = callerUid / 100_000
    private val transactionLock = Any()
    private val reader = Executors.newSingleThreadExecutor()
    private val controller = SettingsController(ShellSettingsStore { arguments ->
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = reader.submit<String> {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                val text = StringBuilder()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(text.length + count <= 131072)
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        }
        try {
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Settings command timed out" }
            CommandResult(process.exitValue(), output.get(1, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
            output.cancel(true)
        }
    })

    init {
        check(context.packageName == BuildConfig.APPLICATION_ID)
        check(Process.myUid() == 2000 || Process.myUid() == 0)
        Thread({
            Thread.sleep(90_000)
            synchronized(transactionLock) { reader.shutdownNow(); exitProcess(0) }
        }, "MonicaDefaultManagerExpiry").apply { isDaemon = true }.start()
    }

    override fun read(): Bundle = respond { controller.read(userId) }

    override fun apply(expected: Bundle, includeAutofill: Boolean, keepOthers: Boolean): Bundle = respond {
        val before = expected.toSnapshot().also { require(it.userId == userId) }
        validateService(MonicaDefaultServices.credential, "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE")
        if (includeAutofill) validateService(MonicaDefaultServices.autofill, "android.permission.BIND_AUTOFILL_SERVICE")
        controller.apply(before, MonicaDefaultServices.credential,
            MonicaDefaultServices.autofill.takeIf { includeAutofill }, keepOthers)
    }

    override fun restore(expected: Bundle, previous: Bundle): Bundle = respond {
        val before = expected.toSnapshot().also { require(it.userId == userId) }
        val target = previous.toSnapshot().also { require(it.userId == userId) }
        controller.replace(before, target)
    }

    @Suppress("DEPRECATION")
    private fun validateService(component: String, permission: String) {
        val name = requireNotNull(ComponentName.unflattenFromString(component))
        require(name.packageName == BuildConfig.APPLICATION_ID)
        val identity = Binder.clearCallingIdentity()
        try {
            val pm = context.packageManager
            val info = pm.getServiceInfo(name, 0)
            val setting = pm.getComponentEnabledSetting(name)
            check(info.applicationInfo.enabled && info.permission == permission)
            check(setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled))
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun respond(action: () -> SettingsSnapshot): Bundle = synchronized(transactionLock) {
        // Authorize before any read, including the error path.
        if (Binder.getCallingUid() != callerUid || Build.VERSION.SDK_INT < 34 ||
            (Process.myUid() != 2000 && Process.myUid() != 0)) return@synchronized Bundle().apply { putBoolean("ok", false) }
        try { action().toBundle().apply { putBoolean("ok", true) } }
        catch (_: Exception) { Bundle().apply { putBoolean("ok", false) } }
    }

    override fun destroy() {
        if (Binder.getCallingUid() !in setOf(callerUid, 2000, 0)) return
        synchronized(transactionLock) { reader.shutdownNow(); exitProcess(0) }
    }
}
