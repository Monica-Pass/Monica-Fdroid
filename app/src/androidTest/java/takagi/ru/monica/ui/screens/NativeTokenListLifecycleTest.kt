package takagi.ru.monica.ui.screens

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.security.SecurityManager
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.MdbxViewModel

/** The overview suspends native-token collection by passing a null model. */
@RunWith(AndroidJUnit4::class)
class NativeTokenListLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: PasswordDatabase
    private lateinit var model: MdbxViewModel
    private var enabled by mutableStateOf(false)
    private lateinit var ui: NativeTokenListUi

    @Before fun prepareEmptySource(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        model = MdbxViewModel(context.applicationContext as Application,
            database.localMdbxDatabaseDao(), database.mdbxRemoteSourceDao(), database.passwordEntryDao(),
            database.secureItemDao(), database.passkeyDao(), database.attachmentDao(), database.customFieldDao(),
            SecurityManager(context))
        // Keep a fully loaded, empty source fixed while only the UI activation changes.
        model.viewModelScope.launch { model.allDatabases.collect {} }
        withTimeout(10_000) { model.allDatabasesLoaded.first { it } }
    }

    @After fun closeSource() {
        model.viewModelScope.cancel()
        database.close()
    }

    @Test fun activatingTheCollectorFromTheOverviewKeepsAnEmptyListUsable() {
        verifyTransitions(initiallyEnabled = false)
    }

    @Test fun returningToTheOverviewCanDeactivateAndReactivateTheCollector() {
        verifyTransitions(initiallyEnabled = true)
    }

    private fun verifyTransitions(initiallyEnabled: Boolean) {
        enabled = initiallyEnabled
        compose.setContent {
            val tokens = rememberNativeTokenList(
                viewModel = if (enabled) model else null,
                filter = CategoryFilter.All,
                query = "",
                onlyTokens = false,
                onToggle = {},
                onOpen = { _, _ -> },
            )
            SideEffect { ui = tokens }
        }
        repeat(6) {
            compose.runOnIdle {
                assertTrue(ui.entries.isEmpty())
                assertFalse(ui.visible)
                assertFalse(ui.loading)
                assertFalse(ui.failed)
                enabled = !enabled
            }
            compose.waitForIdle()
        }
    }
}
