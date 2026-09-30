package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.animateMonicaContentSize
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import takagi.ru.monica.R
import takagi.ru.monica.data.CustomFieldDraft
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.model.LOGIN_TYPE_SSH_KEY
import takagi.ru.monica.data.model.SshKeyData
import takagi.ru.monica.data.model.SshKeyDataCodec
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.ui.components.CustomFieldEditCard
import takagi.ru.monica.ui.components.CustomFieldSectionHeader
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.LocalTemplateTargets
import takagi.ru.monica.ui.components.LocalTemplateNavigation
import takagi.ru.monica.ui.components.templateDefaultTarget
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.SshKeyGenerationProgressIndicator
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.SshKeyGenerator
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

/**
 * SSH 密钥添加 / 编辑页面。
 *
 * 与 [AddEditWifiScreen] 结构一致：顶部选择多存储目标，下方渲染密钥字段卡片、
 * 自定义字段。支持在编辑前「生成」一个新密钥覆盖当前表单。
 *
 * 不需要接入 `pendingQrResult` 等能力（SSH 密钥不通过扫码导入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditSshKeyScreen(
    viewModel: PasswordViewModel,
    localKeePassViewModel: LocalKeePassViewModel? = null,
    passwordId: Long?,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    onNavigateBack: () -> Unit,
    onNavigateToApiToken: () -> Unit = {},
    onNavigateToPassword: () -> Unit,
    onNavigateToBarcode: () -> Unit = onNavigateToPassword,
    onNavigateToWifi: () -> Unit,
    onSaveCompleted: ((Long?) -> Unit)? = null,
) {
    AddEditPasswordScreen(viewModel = viewModel, localKeePassViewModel = localKeePassViewModel,
        passwordId = passwordId, initialLoginType = "SSH_KEY", initialCategoryId = initialCategoryId,
        initialKeePassDatabaseId = initialKeePassDatabaseId, initialKeePassGroupPath = initialKeePassGroupPath,
        initialBitwardenVaultId = initialBitwardenVaultId, initialBitwardenFolderId = initialBitwardenFolderId,
        onNavigateBack = onNavigateBack, onSaveCompleted = onSaveCompleted,
        onSwitchToApiToken = { onNavigateToApiToken() },
        onSwitchToWifi = { onNavigateToWifi() }, onSwitchToSshKey = {})
}
