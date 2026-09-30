package takagi.ru.monica.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.model.PasswordContentBlocks
import takagi.ru.monica.data.model.TemplateCredentialDraft
import takagi.ru.monica.ui.components.*

/** Embedded mode of the template form: return a draft; never create a separate database record. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmbeddedTemplateEditorScreen(block: PasswordContentBlocks.Block,
    onSave: (PasswordContentBlocks.Block) -> Unit, onBack: () -> Unit) {
    var title by remember(block.id) { mutableStateOf(block.title) }
    var draft by remember(block.id) { mutableStateOf(TemplateCredentialDraft(block.kind.name,
        PasswordContentBlocks.editableKeys(block.kind).associateWith(block::value))) }
    var error by remember(block.id) { mutableStateOf(false) }
    var showValidationErrors by remember(block.id) { mutableStateOf(false) }
    Scaffold(modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = { Text(stringResource(block.kind.labelRes())) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }, floatingActionButton = {
        FloatingActionButton(onClick = {
            showValidationErrors = true
            if (!draft.valid()) {
                // Missing API key is a field error, not a file/size failure.
                error = draft.type != "API_KEY"
                return@FloatingActionButton
            }
            try { draft.validateKeyMaterial(); onSave(block.edited(title, draft.values)) } catch (_: Exception) { error = true }
        }, modifier = Modifier.testTag("block_save")) { Icon(Icons.Default.Check, stringResource(R.string.save)) }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TemplateFormSection("") {
                OutlinedTextField(title, { title = it }, saveTextState = false, label = { Text(stringResource(R.string.title)) },
                    modifier = Modifier.fillMaxWidth().testTag("block_title"))
            }
            TemplateFormSection("") { TemplateCredentialFields(draft, { draft = it; error = false }, tagPrefix = "block_field_",
                showValidationErrors = showValidationErrors) }
            TemplateFormSection("") {
                OutlinedTextField(draft.value("notes"), { draft = draft.change("notes", it) }, saveTextState = false,
                    label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth().testTag("block_field_notes"), minLines = 3)
            }
            if (error) Text(stringResource(R.string.content_block_save_error), color = MaterialTheme.colorScheme.error)
        }
    }
}
