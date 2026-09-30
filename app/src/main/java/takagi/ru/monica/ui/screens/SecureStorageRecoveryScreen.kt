package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.ui.components.SettingsSubpageTopBar

/** No repositories, vault reads, recovery writes or credentials are used by this screen. */
@Composable
internal fun SecureStorageRecoveryScreen(onRetry: () -> Unit, onCopyDiagnostic: () -> Unit, onExit: () -> Unit) {
    Scaffold(topBar = { SettingsSubpageTopBar(stringResource(R.string.secure_startup_title), onExit) },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("secure_startup_retry")) {
                    Text(stringResource(R.string.secure_startup_retry))
                }
                FilledTonalButton(onClick = onCopyDiagnostic, modifier = Modifier.fillMaxWidth().testTag("secure_startup_diagnostic")) {
                    Text(stringResource(R.string.secure_startup_diagnostic))
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(bottom = 16.dp).testTag("secure_startup_blocked"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = settingsSectionItemShape(0, 1), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.secure_startup_preserved), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.secure_startup_description), modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                listOf(R.string.secure_startup_restart, R.string.secure_startup_environment, R.string.secure_startup_no_reset)
                    .forEachIndexed { index, text ->
                        Surface(shape = settingsSectionItemShape(index, 3), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Text(stringResource(text), modifier = Modifier.fillMaxWidth().padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
            }
        }
    }
}
