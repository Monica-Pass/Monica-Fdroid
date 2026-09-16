package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.theme.MonicaTheme
import takagi.ru.monica.viewmodel.LocalKeePassViewModel

/** Production source pages with synthetic names, verification states and sync warnings. */
@RunWith(AndroidJUnit4::class)
class DatabaseTileSizeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var mdbx by mutableStateOf(false)
    private var remote = false
    private var scale = 1f
    private var width = 400.dp
    private var locale = Locale.SIMPLIFIED_CHINESE
    private val opened = mutableListOf<Long>()
    private val conflicts = mutableListOf<Long>()
    private val names = listOf("个人", "keepass1.keepass", "工作", "家庭共享数据库备份")

    private fun show() {
        compose.setContent {
            val configuration = Configuration(compose.activity.resources.configuration).apply {
                setLocale(this@DatabaseTileSizeTest.locale)
                fontScale = scale
                screenWidthDp = width.value.toInt()
            }
            val context = remember {
                ContextThemeWrapper(compose.activity, 0).apply { applyOverrideConfiguration(configuration) }
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(LocalDensity.current.density, scale),
            ) {
                MonicaTheme(darkTheme = true) {
                    Surface(Modifier.width(width).fillMaxHeight(), color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface) {
                        if (mdbx) {
                            val databases = names.mapIndexed { index, name ->
                                LocalMdbxDatabase(id = index + 1L, name = name, filePath = "/synthetic/$index.mdbx",
                                    sourceType = if (remote) MdbxSourceType.REMOTE_WEBDAV.name else MdbxSourceType.LOCAL_INTERNAL.name,
                                    lastSyncStatus = if (index == 2) MdbxSyncStatus.FAILED.name else MdbxSyncStatus.IN_SYNC.name,
                                    isDefault = index == 0)
                            }
                            MdbxSourceManagementPage(
                                if (remote) MdbxManagerSource.WEBDAV else MdbxManagerSource.LOCAL,
                                databases, mapOf(4L to 1234), emptyMap(), {}, {}, { opened += it.id },
                            )
                        } else {
                            val databases = names.mapIndexed { index, name ->
                                LocalKeePassDatabase(id = index + 1L, name = name, filePath = "/synthetic/$index.kdbx",
                                    sourceType = if (remote) KeePassDatabaseSourceType.REMOTE_WEBDAV else KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
                                    lastSyncStatus = if (remote && index == 0) KeePassSyncStatus.CONFLICT else KeePassSyncStatus.IN_SYNC,
                                    isDefault = index == 0)
                            }
                            val verification = mapOf(
                                1L to if (remote) LocalKeePassViewModel.VerificationState.Failed("Synthetic failure") else LocalKeePassViewModel.VerificationState.Unknown,
                                2L to LocalKeePassViewModel.VerificationState.Verified(10, 20),
                            )
                            KeePassSourceManagementPage(
                                if (remote) KeePassManagementSource.WEBDAV else KeePassManagementSource.LOCAL,
                                databases, verification, {}, {}, { opened += it.id }, { conflicts += it.id },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertUniformTiles(prefix: String, expected: Pair<Dp, Dp>? = null): Pair<Dp, Dp> {
        compose.onNodeWithTag("${prefix}_database_grid").performScrollToIndex(1)
        val first = compose.onNodeWithTag("${prefix}_database_1").getUnclippedBoundsInRoot()
        val size = expected ?: ((first.right - first.left) to (first.bottom - first.top))
        for (id in 1..4) {
            compose.onNodeWithTag("${prefix}_database_grid").performScrollToIndex(id)
            compose.onNodeWithTag("${prefix}_database_$id")
                .assertWidthIsEqualTo(size.first).assertHeightIsEqualTo(size.second)
        }
        compose.onNodeWithTag("${prefix}_database_grid").performScrollToIndex(0)
        return size
    }

    @Test fun shortAndWrappedNamesHaveTheSameSizeAcrossProviders() {
        show()
        val size = assertUniformTiles("keepass")
        capture("keepass-uniform")
        compose.onNodeWithTag("keepass_database_2").performClick()
        compose.runOnIdle { mdbx = true }
        assertUniformTiles("mdbx", size)
        capture("mdbx-uniform")
        compose.onNodeWithTag("mdbx_database_2").performClick()
        assertEquals(listOf(2L, 2L), opened)
    }

    @Test fun largeTextAndConflictActionsKeepUniformSizes() {
        remote = true
        width = 320.dp
        scale = 1.8f
        locale = Locale.GERMAN
        show()
        assertUniformTiles("keepass")
        val label = instrumentation.targetContext.createConfigurationContext(
            Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(this@DatabaseTileSizeTest.locale) }
        ).getString(R.string.keepass_conflict_review)
        compose.onNodeWithText(label).performClick()
        assertEquals(listOf(1L), conflicts)
        capture("keepass-large-text-conflict")
        compose.runOnIdle { mdbx = true }
        assertUniformTiles("mdbx")
        capture("mdbx-large-text")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "database-tile-size").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
