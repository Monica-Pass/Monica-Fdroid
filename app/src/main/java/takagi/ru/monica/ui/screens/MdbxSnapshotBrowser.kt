package takagi.ru.monica.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.repository.*

@Composable
internal fun SnapshotStructurePreviewPage(preview: MdbxStructurePreview?, compareMode: Boolean, modifier: Modifier = Modifier) {
    val saved = rememberSaveableStateHolder()
    var selected by remember(preview?.snapshotId) { mutableStateOf<MdbxStructureNode?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (preview == null) {
        Box(modifier.fillMaxSize()) { Text(stringResource(R.string.mdbx_ui_snapshot_structure_loading), Modifier.padding(16.dp)) }
        return
    }
    // The keys keep each version's path, sort and list position when changing layout.
    @Composable fun pane(current: Boolean, modifier: Modifier) {
        saved.SaveableStateProvider("${preview.snapshotId}:${if (current) "current" else "snapshot"}") {
            MdbxFolderBrowser(nodes = if (current) preview.currentNodes else preview.snapshotNodes,
                rootName = stringResource(R.string.root_directory), modifier = modifier,
                paneId = if (current) "current" else "snapshot",
                heading = stringResource(if (current) R.string.mdbx_ui_current_version else R.string.mdbx_ui_snapshot_version),
                onEntry = { selected = it })
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if ((compareMode || landscape) && maxWidth >= 540.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pane(true, Modifier.weight(1f))
                pane(false, Modifier.weight(1f))
            }
        } else pane(false, Modifier.fillMaxSize())
    }
    selected?.let { node ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(node.name) }, text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(mdbxBrowserTypeName(node.metadata))
                    Text(node.path)
                    if (node.status != MdbxStructureNodeStatus.UNCHANGED) Text(structureStatusLabel(node.status), color = structureStatusColor(node.status))
                    Text(stringResource(R.string.mdbx_native_snapshot_readonly), style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.close)) } })
    }
}
