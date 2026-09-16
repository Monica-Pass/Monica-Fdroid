package takagi.ru.monica.ui.screens

import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.NativeApiTokenSummary
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.viewmodel.MdbxViewModel

/** Uses the ordinary destination picker, restricted to stores that retain native objects. */
@Composable
internal fun NativeTokenBatchMoveSheet(
    visible: Boolean,
    viewModel: MdbxViewModel,
    entries: List<NativeApiTokenSummary>,
    onDismiss: () -> Unit,
    onCompleted: () -> Unit,
    onTransferOther: suspend (UnifiedMoveCategoryTarget, UnifiedMoveAction) -> Pair<Int, Int> = { _, _ -> 0 to 0 },
) {
    val databases by viewModel.allDatabases.collectAsStateWithLifecycle()
    val folders = remember { mutableStateMapOf<Long, List<takagi.ru.monica.repository.MdbxStoredFolderEntry>>() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transferring by remember { mutableStateOf(false) }
    val sourceId = entries.firstOrNull()?.databaseId
    UnifiedMoveToCategoryBottomSheet(
        visible = visible && !transferring,
        onDismiss = onDismiss,
        initialSource = sourceId?.let { UnifiedMoveInitialSource.MdbxDatabase(it) } ?: UnifiedMoveInitialSource.MonicaLocal,
        categories = emptyList(), keepassDatabases = emptyList(), bitwardenVaults = emptyList(),
        mdbxDatabases = databases.filter { it.engineTypeEnum == MdbxEngineType.RUST_MDBX2 },
        getBitwardenFolders = { flowOf(emptyList()) }, getKeePassGroups = { flowOf(emptyList()) },
        getMdbxFolders = { id -> flowOf(folders[id].orEmpty()) },
        refreshMdbxFolders = { id -> scope.launch {
            try { folders[id] = viewModel.nativeApiTokenFolders(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, R.string.api_token_folder_error, Toast.LENGTH_LONG).show() }
        } },
        allowLocalTarget = false, allowMove = true, allowCopy = true,
        onTargetSelected = { target, action ->
            val destination = when (target) {
                is UnifiedMoveCategoryTarget.MdbxDatabaseTarget -> target.databaseId to null
                is UnifiedMoveCategoryTarget.MdbxFolderTarget -> target.databaseId to target.folderId
                else -> null
            }
            if (destination != null) {
                val snapshot = entries.toList()
                transferring = true
                scope.launch {
                    var succeeded = 0
                    var failed = 0
                    try {
                        for (entry in snapshot) {
                            try {
                                viewModel.transferNativeApiToken(entry, destination.first, destination.second, action == UnifiedMoveAction.COPY)
                                succeeded++
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { failed++ }
                        }
                        val other = onTransferOther(target, action)
                        succeeded += other.first
                        failed += other.second
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed++ }
                    finally { transferring = false }
                    Toast.makeText(context, context.getString(R.string.password_batch_transfer_partial_result, succeeded, failed), Toast.LENGTH_LONG).show()
                    if (failed == 0) onCompleted()
                    onDismiss()
                }
            }
        },
    )
    if (transferring) androidx.compose.ui.window.Dialog(onDismissRequest = {}) { CircularProgressIndicator() }
}
