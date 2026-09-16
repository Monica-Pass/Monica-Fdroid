package takagi.ru.monica.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import takagi.ru.monica.R
import takagi.ru.monica.data.*
import takagi.ru.monica.ui.components.MonicaExpressiveFilterChip
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.MdbxViewModel
import takagi.ru.monica.viewmodel.nativeApiTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.widget.Toast

internal class NativeTokenListUi(
    val entries: List<NativeApiTokenSummary>,
    val visible: Boolean,
    val onlyTokens: Boolean,
    val loading: Boolean,
    val failed: Boolean,
    val onToggle: () -> Unit,
    val onOpen: (Long?, String?) -> Unit,
    val onRetry: () -> Unit = {},
    val onFavorite: (NativeApiTokenSummary, Boolean) -> Unit = { _, _ -> },
    val byDisplayId: Map<Long, NativeApiTokenSummary> = entries.associateBy { it.displayId },
) {
    fun openDisplayEntry(entry: PasswordEntry): Boolean = byDisplayId[entry.id]?.let {
        onOpen(it.databaseId, it.entryId); true
    } ?: (entry.loginType == "API_TOKEN")
    fun favoriteDisplayEntry(entry: PasswordEntry): Boolean = byDisplayId[entry.id]?.let {
        onFavorite(it, !entry.isFavorite); true
    } ?: (entry.loginType == "API_TOKEN")
}

internal fun filterNativeApiTokens(
    entries: List<NativeApiTokenSummary>, filter: CategoryFilter, query: String,
    favoritesOnly: Boolean = false
): List<NativeApiTokenSummary> = entries.filter { token ->
    val inSource = when (filter) {
        CategoryFilter.All -> true
        CategoryFilter.Starred -> token.isFavorite
        is CategoryFilter.MdbxDatabase -> token.databaseId == filter.databaseId
        is CategoryFilter.MdbxFolderFilter -> token.databaseId == filter.databaseId &&
            (token.collectionId == filter.folderId || filter.folderId in token.ancestorCollectionIds ||
                (token.isRootCollection && filter.folderId == "root"))
        else -> false
    }
    inSource && (!favoritesOnly || token.isFavorite) &&
        (query.isBlank() || listOf(token.title, token.collectionTitle).any { it.contains(query.trim(), true) })
}

@Composable
internal fun rememberNativeTokenList(
    viewModel: MdbxViewModel?, filter: CategoryFilter, query: String,
    onlyTokens: Boolean, onToggle: () -> Unit, onOpen: (Long?, String?) -> Unit,
    includeTokens: Boolean = true, favoritesOnly: Boolean = false
): NativeTokenListUi {
    // The overview passes null to suspend this source. Use an explicit branch so
    // collector remember slots cannot overlap the remaining state on re-entry.
    if (viewModel == null) {
        return NativeTokenListUi(
            entries = emptyList(), visible = false, onlyTokens = false,
            loading = false, failed = false, onToggle = onToggle, onOpen = onOpen,
        )
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val databases by viewModel.allDatabases.collectAsStateWithLifecycle()
    val databasesLoaded by viewModel.allDatabasesLoaded.collectAsStateWithLifecycle()
    val sources = remember(databases, filter) { databases.filter {
        it.engineTypeEnum == MdbxEngineType.RUST_MDBX2 && when (filter) {
            is CategoryFilter.MdbxDatabase -> it.id == filter.databaseId
            is CategoryFilter.MdbxFolderFilter -> it.id == filter.databaseId
            CategoryFilter.All, CategoryFilter.Starred -> true
            else -> false
        }
    }.map { it.nativeApiTokenSource() } }
    val store = viewModel.nativeApiTokenList
    val snapshot by store.state.collectAsStateWithLifecycle()
    val active = includeTokens || onlyTokens
    val latestSources by rememberUpdatedState(if (active) sources else emptyList())
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, store) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) store.request(latestSources, refresh = true)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(store, sources, active) {
        if (active) store.request(sources)
    }
    val entries = remember(snapshot.entries, sources, filter, query, active, favoritesOnly) {
        if (active) filterNativeApiTokens(sources.flatMap(snapshot::rowsFor), filter, query, favoritesOnly)
        else emptyList()
    }
    val loading = active && (!databasesLoaded || sources.any { it in snapshot.loading ||
        (it !in snapshot.entries && it !in snapshot.failed) })
    val failed = active && sources.any { it in snapshot.failed }
    val sourceVisible = filter == CategoryFilter.All || filter == CategoryFilter.Starred ||
        filter is CategoryFilter.MdbxDatabase || filter is CategoryFilter.MdbxFolderFilter
    return NativeTokenListUi(
        entries = entries,
        visible = sources.isNotEmpty() && sourceVisible,
        onlyTokens = onlyTokens && sourceVisible && sources.isNotEmpty(),
        loading = loading, failed = failed,
        byDisplayId = remember(entries) { entries.associateBy { it.displayId } },
        onToggle = onToggle, onOpen = onOpen,
        onRetry = { store.request(sources, refresh = true) },
        onFavorite = { token, favorite -> scope.launch {
            try { viewModel.setNativeApiTokenFavorite(token, favorite) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, R.string.api_token_load_error, Toast.LENGTH_LONG).show() }
        } },
    )
}

@Composable
internal fun NativeTokenFilterChip(state: NativeTokenListUi?) {
    if (state?.visible == true) MonicaExpressiveFilterChip(
        selected = state.onlyTokens, onClick = state.onToggle,
        label = stringResource(R.string.entry_type_api_token), leadingIcon = Icons.Default.Key
    )
}
