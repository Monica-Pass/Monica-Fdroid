package takagi.ru.monica.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MdbxUnsupportedModePage(databaseName: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        MdbxTopAppBar(title = { Text(databaseName) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).testTag("mdbx_mode_unsupported"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.mdbx_client_mode_unsupported_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.mdbx_client_mode_unsupported))
        }
    }
}
