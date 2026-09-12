package takagi.ru.monica.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.GeneratorPreferencesManager

@RunWith(AndroidJUnit4::class)
class GeneratorViewModelInitializationTest {
    @Test
    fun cachedPreferencesCannotRaceStateInitialization() = runBlocking {
        val manager = GeneratorPreferencesManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = manager.load()
        val saved = original.copy(selectedGenerator = "PIN", pinLength = 9, symbolLength = 29, sshKeyRsaSize = 4096)
        val stores = mutableListOf<ViewModelStore>()
        val models = mutableListOf<GeneratorViewModel>()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GeneratorViewModel(manager) as T
        }
        try {
            manager.save(saved)
            // Warm DataStore so preference restoration can run immediately on IO
            // while the constructor is still allocating its state holders on Main.
            assertEquals(saved, manager.load())
            withContext(Dispatchers.Main) {
                repeat(200) {
                    val store = ViewModelStore().also(stores::add)
                    models += ViewModelProvider(store, factory)[GeneratorViewModel::class.java]
                }
            }
            withTimeout(10_000) {
                while (models.any { it.sshKeyRsaSize.value != saved.sshKeyRsaSize }) delay(10)
            }
            models.forEach { model ->
                assertEquals(GeneratorType.PIN, model.selectedGenerator.value)
                assertEquals(saved.pinLength, model.pinLength.value)
                assertEquals(saved.symbolLength, model.symbolLength.value)
                assertEquals(saved.sshKeyRsaSize, model.sshKeyRsaSize.value)
            }
        } finally {
            withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) }
            manager.save(original)
        }
    }
}
