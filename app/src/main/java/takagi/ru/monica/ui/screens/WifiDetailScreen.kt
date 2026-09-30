package takagi.ru.monica.ui.screens

import takagi.ru.monica.ui.components.animateMonicaContentSize
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.R
import takagi.ru.monica.data.PasswordEntry
import takagi.ru.monica.data.model.WifiData
import takagi.ru.monica.data.model.WifiSecurity
import takagi.ru.monica.data.model.toStorageTarget
import takagi.ru.monica.ui.components.CustomFieldDetailCard
import takagi.ru.monica.ui.components.PasswordFieldActionMenuHost
import takagi.ru.monica.ui.components.TextQrCodeDialog
import takagi.ru.monica.ui.components.rememberPasswordFieldActionMenuState
import takagi.ru.monica.ui.icons.MonicaIcons
import takagi.ru.monica.utils.ClipboardUtils
import takagi.ru.monica.utils.WifiConnectLauncher
import takagi.ru.monica.utils.WifiQrPayload
import takagi.ru.monica.viewmodel.PasswordViewModel

/**
 * WIFI 详情页（精简版）：SSID、安全性、密码（默认隐藏）、隐藏网络、存储位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDetailScreen(
    viewModel: PasswordViewModel,
    passwordId: Long,
    onNavigateBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onCreateSend: ((title: String, text: String) -> Unit)? = null
) {

    PasswordDetailScreen(viewModel = viewModel, passwordId = passwordId, biometricEnabled = false,
        onNavigateBack = onNavigateBack, onEditPassword = onEdit, onCreateSend = onCreateSend)
}
