package takagi.ru.monica.ui.images

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import takagi.ru.monica.R
import takagi.ru.monica.ui.screens.settingsSectionItemShape

private val thumbnailSlots = Semaphore(3)

/** The callback copies an icon or passes a bitmap to the existing card-face cropper. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImageSubscriptionBrowser(
    initialKind: ImageSourceKind = ImageSourceKind.ICON,
    onDismiss: () -> Unit,
    onSelect: (suspend (Bitmap) -> Unit)? = null,
    sourceStore: ImageSubscriptionStore? = null,
    sourceClient: ImageSourceClient? = null
) {
    val context = LocalContext.current
    val store = remember { sourceStore ?: ImageSubscriptionStore(context) }
    val client = remember { sourceClient ?: ImageSourceClient(context) }
    val scope = rememberCoroutineScope()
    var sources by remember { mutableStateOf<List<ImageSubscription>>(emptyList()) }
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ImageSubscription?>(null) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var catalogAvailable by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(loadAttempt) {
        busy = true
        try { sources = store.read(); catalogAvailable = true; error = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { catalogAvailable = false; error = true }
        finally { busy = false }
    }
    fun perform(action: suspend () -> Unit) {
        busy = true; error = false
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { busy = false }
        }
    }
    val active = sources.firstOrNull { it.id == activeId }
    fun back() { if (!busy) { if (activeId != null) { activeId = null; query = "" } else onDismiss() } }
    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { back() }
        Scaffold(modifier = Modifier.testTag("image_sources_browser"),
            topBar = {
                TopAppBar(title = { Text(active?.name ?: stringResource(R.string.image_sources_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = ::back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close)) } },
                    actions = {
                        if (active != null) IconButton(enabled = !busy, onClick = {
                            perform { val updated = client.load(active.url, active.name, active.kind, active.id); store.save(updated); sources = store.read() }
                        }) { Icon(Icons.Default.Refresh, stringResource(R.string.image_sources_refresh)) }
                        else IconButton(enabled = !busy && catalogAvailable, onClick = { adding = true }) {
                            Icon(Icons.Default.Add, stringResource(R.string.image_sources_add))
                        }
                    })
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.image_sources_error), color = MaterialTheme.colorScheme.onErrorContainer)
                        if (!catalogAvailable) TextButton(onClick = { loadAttempt++ }, enabled = !busy) { Text(stringResource(R.string.image_sources_retry)) }
                    }
                }
                if (active == null) {
                    if (onSelect == null) TabRow(selectedTabIndex = kind.ordinal) {
                        ImageSourceKind.entries.forEach { value -> Tab(selected = kind == value, enabled = !busy,
                            onClick = { kind = value }, text = { Text(stringResource(if (value == ImageSourceKind.ICON) R.string.image_sources_icons else R.string.image_sources_cards)) }) }
                    }
                    val visible = sources.filter { it.kind == kind }
                    if (visible.isEmpty() && !busy && catalogAvailable) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.Collections, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.image_sources_empty), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.image_sources_formats), style = MaterialTheme.typography.bodyMedium)
                            FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.image_sources_add)) }
                        }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(visible, key = { _, source -> source.id }) { index, source ->
                            Surface(onClick = { activeId = source.id; query = "" }, enabled = !busy,
                                shape = settingsSectionItemShape(index, visible.size), color = MaterialTheme.colorScheme.surfaceContainer) {
                                ListItem(colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                    headlineContent = { Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(stringResource(R.string.image_sources_count, source.images.size)) },
                                    leadingContent = { Icon(if (kind == ImageSourceKind.ICON) Icons.Default.Image else Icons.Default.CreditCard, null) },
                                    trailingContent = {
                                        var expanded by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, stringResource(R.string.image_sources_options)) }
                                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.image_sources_refresh)) }, onClick = {
                                                    expanded = false
                                                    perform { store.save(client.load(source.url, source.name, source.kind, source.id)); sources = store.read() }
                                                })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.image_sources_remove)) }, onClick = { expanded = false; removing = source })
                                            }
                                        }
                                    })
                            }
                        }
                    }
                } else {
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp), singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text(stringResource(R.string.image_sources_search)) })
                    val images = remember(active.images, query) { active.images.filter { it.name.contains(query, ignoreCase = true) } }
                    if (images.isEmpty()) Text(stringResource(R.string.image_sources_no_results))
                    LazyVerticalGrid(columns = GridCells.Adaptive(if (kind == ImageSourceKind.ICON) 100.dp else 152.dp), modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(images, key = { it.url }) { image ->
                            SourceImageTile(image, client, kind, !busy, active.updatedAt) {
                                perform {
                                    val bitmap = client.image(image.url)
                                    if (onSelect != null) onSelect(bitmap) else bitmap.recycle()
                                }
                            }
                        }
                    }
                    if (onSelect == null) Text(stringResource(R.string.image_sources_use_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        }
    }
    if (adding) AddImageSourceDialog(kind, onCancel = { if (!busy) adding = false }, busy = busy,
        onAdd = { name, url -> perform { store.save(client.load(url, name, kind)); sources = store.read(); adding = false } }, error = error)
    removing?.let { source ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.image_sources_remove)) },
            text = { Text(stringResource(R.string.image_sources_remove_hint)) },
            confirmButton = { TextButton(onClick = { removing = null; perform { store.remove(source.id); sources = store.read() } }) { Text(stringResource(R.string.image_sources_remove)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SourceImageTile(image: SubscribedImage, client: ImageSourceClient, kind: ImageSourceKind, enabled: Boolean, revision: Long, onClick: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val bitmap by produceState<Bitmap?>(null, image.url, revision, retry) {
        try {
            value = thumbnailSlots.withPermit {
                val original = client.image(image.url)
                val scale = minOf(1f, 320f / maxOf(original.width, original.height))
                val small = Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1), (original.height * scale).toInt().coerceAtLeast(1), true)
                if (small !== original) original.recycle()
                small
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
    }
    Surface(onClick = { retry++; onClick() }, enabled = enabled, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(if (kind == ImageSourceKind.ICON) 1f else CardFaceAspect), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), image.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Icon(Icons.Default.Image, stringResource(R.string.image_sources_retry), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(image.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        }
    }
}
private const val CardFaceAspect = 1.586f

@Composable
private fun AddImageSourceDialog(kind: ImageSourceKind, onCancel: () -> Unit, busy: Boolean, error: Boolean, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    val valid = remember(url) { runCatching { ImageFeedParser.url(url.trim()) }.isSuccess }
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.image_sources_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (kind == ImageSourceKind.ICON) R.string.image_sources_icons else R.string.image_sources_cards), color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(name, { name = it.take(160) }, label = { Text(stringResource(R.string.image_sources_name)) }, singleLine = true, enabled = !busy)
                OutlinedTextField(url, { url = it.take(4096) }, label = { Text(stringResource(R.string.image_sources_url)) }, maxLines = 4, enabled = !busy)
                Text(stringResource(R.string.image_sources_formats), style = MaterialTheme.typography.bodySmall)
                if (error) Text(stringResource(R.string.image_sources_error), color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = valid && !busy, onClick = { onAdd(name, url) }) { Text(stringResource(R.string.image_sources_add)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.cancel)) } })
}
