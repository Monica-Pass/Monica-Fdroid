package takagi.ru.monica.viewmodel

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import takagi.ru.monica.utils.GpgKeyGenerator
import takagi.ru.monica.R

/** Memory-only state: private keys and passphrases must not enter saved instance state. */
class GpgEditorViewModel : ViewModel() {
    var name by mutableStateOf("")
    var email by mutableStateOf("")
    var title by mutableStateOf("")
    var passphrase by mutableStateOf("")
    var bits by mutableStateOf(3072)
    var days by mutableStateOf(365)
    var key by mutableStateOf<GpgKeyGenerator.Key?>(null)
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
    enum class Failure(val message: Int) {
        GENERATE(R.string.gpg_generate_error), IMPORT(R.string.gpg_import_error),
        LOAD(R.string.gpg_load_error), SAVE(R.string.gpg_save_error), EXPORT(R.string.gpg_export_error)
    }
    var failure by mutableStateOf(Failure.GENERATE)
        private set
    var validationAttempted by mutableStateOf(false)
        private set
    val nameInvalid get() = validationAttempted && !GpgKeyGenerator.isValidName(name.trim())
    val emailInvalid get() = validationAttempted && !GpgKeyGenerator.isValidEmail(email.trim())
    fun fail(reason: Failure) { failure = reason; failed = true }
    fun generate() {
        if (busy) return
        validationAttempted = true
        failed = false
        if (nameInvalid || emailInvalid) return
        perform(Failure.GENERATE) {
            val chars = passphrase.toCharArray()
            val requestName = name.trim()
            val requestEmail = email.trim()
            val requestBits = bits
            val requestDays = days
            try {
                withContext(Dispatchers.Default) {
                    GpgKeyGenerator.generate(requestName, requestEmail, requestBits, requestDays, chars)
                }
            } finally { chars.fill('\u0000') }
        }
    }
    fun perform(reason: Failure = Failure.IMPORT, action: suspend () -> GpgKeyGenerator.Key) {
        if (busy) return
        busy = true
        failed = false
        viewModelScope.launch {
            try { key = action(); passphrase = "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(reason) }
            finally { busy = false }
        }
    }
}
