package takagi.ru.monica.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import takagi.ru.monica.R

/** Only the current editing context is exposed, alongside Add and Save. */
@Composable
internal fun PasswordCredentialEditorBar(
    commonSelected: Boolean,
    selectedIndex: Int,
    credentialCount: Int,
    canAdd: Boolean,
    canSave: Boolean,
    isSaving: Boolean,
    onOpenPicker: () -> Unit,
    onAdd: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val availableWidth = LocalConfiguration.current.screenWidthDp.dp - 32.dp
    Row(
        modifier = modifier.widthIn(max = minOf(480.dp, availableWidth))
            .testTag("password_credential_editor_bar"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PasswordCredentialSwitcherButton(commonSelected, selectedIndex, credentialCount,
            onOpenPicker, Modifier.weight(1f))
        if (canAdd) {
            FloatingActionButton(onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                Icon(Icons.Rounded.Add, stringResource(R.string.add_credential))
            }
        }
        FloatingActionButton(onClick = onSave,
            containerColor = if (canSave) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (canSave) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant) {
            if (isSaving) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.Check, stringResource(R.string.save))
            }
        }
    }
}

/** Short shared-field labels and an ordinal avoid truncating translated credential names. */
@Composable
private fun PasswordCredentialSwitcherButton(
    commonSelected: Boolean,
    selectedIndex: Int,
    credentialCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val label = if (commonSelected) stringResource(R.string.common_info)
        else stringResource(R.string.credential_number, selectedIndex + 1)
    val position = "${selectedIndex + 1}/$credentialCount"
    val displayLabel = if (commonSelected) stringResource(R.string.credential_editor_shared) else position
    Surface(
        onClick = {
            focusManager.clearFocus()
            keyboard?.hide()
            onClick()
        },
        modifier = modifier.heightIn(min = 56.dp)
            .semantics {
                contentDescription = label
                if (!commonSelected) stateDescription = position
            }
            .testTag("password_credential_switcher"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (commonSelected) Icons.Rounded.Tune else Icons.Rounded.AccountCircle,
                contentDescription = null, modifier = Modifier.size(20.dp))
            Text(displayLabel, style = MaterialTheme.typography.labelLarge,
                maxLines = if (commonSelected) Int.MAX_VALUE else 1,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {})
            Icon(Icons.Rounded.ExpandLess, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** The picker receives display names only; credentials and their drafts stay in the editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PasswordCredentialPickerSheet(
    usernames: List<String>,
    selectedIndex: Int,
    commonSelected: Boolean,
    canAdd: Boolean,
    canRemoveSelected: Boolean,
    onSelect: (Int) -> Unit,
    onSelectCommon: () -> Unit,
    onAdd: () -> Unit,
    onRemoveSelected: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (usernames.isEmpty()) return
    val currentIndex = selectedIndex.coerceIn(usernames.indices)
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex =
        if (commonSelected) 0 else currentIndex)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    val removable = !commonSelected && canRemoveSelected && usernames.size > 1
    val currentLabel = stringResource(R.string.credential_number, currentIndex + 1)
    val currentName = usernames[currentIndex].trim().takeIf(String::isNotEmpty)
        ?.let { "$currentLabel · $it" } ?: currentLabel
    val removalMessage = stringResource(R.string.delete_password_message, currentName)

    fun closeThen(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            try {
                sheetState.hide()
                action()
            } finally {
                closing = false
            }
        }
    }

    MonicaModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // The modal owns a window; retain the editor's locale and interface scale there.
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = configuration.screenHeightDp.dp * 0.82f)
                    .padding(horizontal = 12.dp)
                    .selectableGroup()
                    .testTag("password_credential_sheet"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.edit),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    FilledTonalIconButton(
                        onClick = { closeThen(onDismiss) }, enabled = !closing,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                        modifier = Modifier.testTag("password_credential_close"),
                    ) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.close), Modifier.size(20.dp))
                    }
                }
                PasswordEditorSectionRow(
                    title = stringResource(R.string.common_info), selected = commonSelected,
                    icon = Icons.Rounded.Tune, enabled = !closing,
                    onClick = { closeThen(onSelectCommon) },
                    modifier = Modifier.testTag("password_credential_common"),
                )
                Text(stringResource(R.string.section_credentials),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).semantics { heading() })
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .testTag("password_credential_list"),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(usernames, key = { index, _ -> index }) { index, username ->
                        val selected = !commonSelected && index == currentIndex
                        val label = stringResource(R.string.credential_number, index + 1)
                        val displayName = username.trim()
                        val shape = RoundedCornerShape(
                            topStart = if (index == 0) 24.dp else 4.dp,
                            topEnd = if (index == 0) 24.dp else 4.dp,
                            bottomStart = if (index == usernames.lastIndex) 24.dp else 4.dp,
                            bottomEnd = if (index == usernames.lastIndex) 24.dp else 4.dp,
                        )
                        PasswordEditorSectionRow(
                            title = displayName.ifEmpty { label },
                            supportingText = label.takeIf { displayName.isNotEmpty() },
                            maxTitleLines = if (displayName.isEmpty()) Int.MAX_VALUE else 2,
                            selected = selected, icon = Icons.Rounded.AccountCircle,
                            shape = shape, enabled = !closing,
                            onClick = { closeThen { onSelect(index) } },
                            modifier = Modifier.testTag("password_credential_$index"),
                            onRemove = if (selected && removable) ({ confirmRemoval = true }) else null,
                            removeDescription = removalMessage,
                        )
                    }
                }
                if (canAdd) {
                    Button(
                        onClick = { closeThen(onAdd) }, enabled = !closing,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            .heightIn(min = 56.dp).testTag("password_credential_add"),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.add_credential),
                            modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (confirmRemoval && removable) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            modifier = Modifier.testTag("password_credential_remove_confirmation"),
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(removalMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoval = false
                        closeThen(onRemoveSelected)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("password_credential_confirm_remove"),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false },
                    modifier = Modifier.testTag("password_credential_cancel_remove")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PasswordEditorSectionRow(
    title: String,
    selected: Boolean,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    maxTitleLines: Int = Int.MAX_VALUE,
    shape: Shape = RoundedCornerShape(24.dp),
    onRemove: (() -> Unit)? = null,
    removeDescription: String = "",
) {
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = modifier.weight(1f).heightIn(min = 76.dp)
                    .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                    CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (selected) Icons.Rounded.Check else icon, contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = maxTitleLines, overflow = TextOverflow.Ellipsis)
                    if (!supportingText.isNullOrBlank()) {
                        Text(supportingText, style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, enabled = enabled,
                    modifier = Modifier.padding(end = 4.dp).testTag("password_credential_remove")) {
                    Icon(Icons.Rounded.DeleteOutline, removeDescription, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
