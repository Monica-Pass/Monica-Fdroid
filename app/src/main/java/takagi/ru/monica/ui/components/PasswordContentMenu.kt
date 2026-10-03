package takagi.ru.monica.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import takagi.ru.monica.data.model.PasswordContentBlocks
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R

/** Presentation choices only. Every section edits the existing password record. */
enum class PasswordContentSection(@StringRes val title: Int, @StringRes val description: Int, val icon: ImageVector) {
    NOTES(R.string.password_content_notes, R.string.password_content_notes_description, Icons.Default.Description),
    AUTHENTICATOR(R.string.section_security_verification, R.string.password_content_otp_description, Icons.Default.Shield),
    DOCUMENT(R.string.password_content_document, R.string.password_content_document_description, Icons.Default.Badge),
    PAYMENT(R.string.payment_info, R.string.password_content_payment_description, Icons.Default.CreditCard),
    CONTACT(R.string.personal_info, R.string.password_content_contact_description, Icons.Default.Person),
    ADDRESS(R.string.billing_address, R.string.password_content_address_description, Icons.Default.Home),
    CUSTOM_FIELDS(R.string.custom_fields, R.string.password_content_fields_description, Icons.Default.List),
    ATTACHMENTS(R.string.attachments, R.string.password_content_attachments_description, Icons.Default.AttachFile),
}

@Composable
fun PasswordContentAddButton(onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag("password_content_add")) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.password_content_add))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordContentMenu(
    sections: List<PasswordContentSection>,
    onAdd: (PasswordContentSection) -> Unit,
    onDismiss: () -> Unit,
    onAddBlock: ((PasswordContentBlocks.Kind) -> Unit)? = null,
    onAddCredential: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.password_content_add), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
            Text(stringResource(R.string.password_content_same_item), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 16.dp))
            if (onAddCredential != null) {
                Surface(onClick = onAddCredential, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("add_project_credential")) {
                    ListItem(headlineContent = { Text(stringResource(R.string.project_credential)) },
                        leadingContent = { Icon(Icons.Default.Person, null) }, trailingContent = { Icon(Icons.Default.Add, null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                }
            }
            if (onAddBlock != null) {
                var more by remember { mutableStateOf(false) }
                val kinds = listOf(PasswordContentBlocks.Kind.API_KEY, PasswordContentBlocks.Kind.API_TOKEN,
                    PasswordContentBlocks.Kind.SSH_KEY, PasswordContentBlocks.Kind.QR_CODE) +
                    if (more) listOf(PasswordContentBlocks.Kind.GPG_KEY) else emptyList()
                kinds.forEachIndexed { index, kind ->
                    Surface(onClick = { onAddBlock(kind) }, shape = entryGroupShape(index, kinds.size),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).testTag("block_add_${kind.name}")) {
                        ListItem(headlineContent = { Text(stringResource(kind.labelRes())) },
                            leadingContent = { Icon(kind.blockIcon(), null) }, trailingContent = { Icon(Icons.Default.Add, null) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                }
                if (!more) TextButton(onClick = { more = true }) { Text(stringResource(R.string.content_block_more)) }
                Spacer(Modifier.height(12.dp))
            }
            sections.forEachIndexed { index, section ->
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) 24.dp else 4.dp,
                    topEnd = if (index == 0) 24.dp else 4.dp,
                    bottomStart = if (index == sections.lastIndex) 24.dp else 4.dp,
                    bottomEnd = if (index == sections.lastIndex) 24.dp else 4.dp,
                )
                ListItem(
                    headlineContent = { Text(stringResource(section.title)) },
                    supportingContent = { Text(stringResource(section.description)) },
                    leadingContent = { Icon(section.icon, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp).clip(shape)
                        .testTag("password_content_choose_${section.name}")
                        .clickable(role = Role.Button) { onAdd(section) },
                )
            }
        }
    }
}
