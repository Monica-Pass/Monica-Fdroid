package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.MonicaExpandableContent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.bitwarden.BitwardenVault
import takagi.ru.monica.data.LocalKeePassDatabase
import takagi.ru.monica.data.model.StorageTarget
import takagi.ru.monica.data.model.WifiData
import takagi.ru.monica.data.model.WifiIp
import takagi.ru.monica.data.model.WifiProxy
import takagi.ru.monica.data.model.WifiSecurity
import takagi.ru.monica.data.model.normalizedStorageTargets
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.data.model.withoutStorageTarget
import takagi.ru.monica.ui.components.EntryTypeChip
import takagi.ru.monica.ui.components.LocalTemplateTargets
import takagi.ru.monica.ui.components.LocalTemplateNavigation
import takagi.ru.monica.ui.components.templateDefaultTarget
import takagi.ru.monica.ui.components.EntryTypeChipOption
import takagi.ru.monica.ui.components.MultiStorageTargetPickerBottomSheet
import takagi.ru.monica.ui.components.MultiStorageTargetSelectorCard
import takagi.ru.monica.ui.components.OutlinedTextField
import takagi.ru.monica.ui.components.buildMultiStorageTarget
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.utils.WifiQrParser
import takagi.ru.monica.viewmodel.CategoryFilter
import takagi.ru.monica.viewmodel.LocalKeePassViewModel
import takagi.ru.monica.viewmodel.PasswordViewModel

/**
 * WIFI 添加/编辑页面。
 *
 * 结构参考 [AddEditPasswordScreen]：顶部选择存储位置（多目标），下方按
 * 分组（基础/安全/隐私/代理/IP 设置）展开可折叠卡片。
 *
 * 额外能力：从 [pendingQrResult] 解析 ZXing 约定的 `WIFI:T:..;S:..;P:..` 字串
 * 自动回填表单；顶部 "扫码" 按钮跳到二维码扫描页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditWifiScreen(
    viewModel: PasswordViewModel,
    localKeePassViewModel: LocalKeePassViewModel? = null,
    passwordId: Long?,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null,
    pendingQrResult: String? = null,
    onConsumePendingQrResult: () -> Unit = {},
    onScanQrCode: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
    onNavigateToApiToken: () -> Unit = {},
    onNavigateToPassword: () -> Unit,
    onNavigateToBarcode: () -> Unit = onNavigateToPassword,
    onNavigateToSshKey: (() -> Unit)? = null,
    onSaveCompleted: ((Long?) -> Unit)? = null,
) {
    AddEditPasswordScreen(viewModel = viewModel, localKeePassViewModel = localKeePassViewModel,
        passwordId = passwordId, initialLoginType = "WIFI", initialCategoryId = initialCategoryId,
        initialKeePassDatabaseId = initialKeePassDatabaseId, initialKeePassGroupPath = initialKeePassGroupPath,
        initialBitwardenVaultId = initialBitwardenVaultId, initialBitwardenFolderId = initialBitwardenFolderId,
        onNavigateBack = onNavigateBack, onSaveCompleted = onSaveCompleted,
        onSwitchToApiToken = { onNavigateToApiToken() },
        pendingQrResult = pendingQrResult, onConsumePendingQrResult = onConsumePendingQrResult,
        onScanAuthenticatorQrCode = onScanQrCode, onSwitchToWifi = {},
        onSwitchToSshKey = { onNavigateToSshKey?.invoke() })
}
