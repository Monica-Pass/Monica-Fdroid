package takagi.ru.monica.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import takagi.ru.monica.data.model.PermissionCategory
import takagi.ru.monica.data.model.PermissionStats
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import takagi.ru.monica.R
import takagi.ru.monica.data.model.PermissionInfo
import takagi.ru.monica.ui.components.*
import takagi.ru.monica.viewmodel.PermissionViewModel

/**
 * 权限管理主界面
 * Permission management main screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: PermissionViewModel = viewModel()
) {
    val context = LocalContext.current
    val permissionsByCategory by viewModel.permissionsByCategory.collectAsState()
    val permissionStats by viewModel.permissionStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showHelpDialog by rememberSaveable { mutableStateOf(false) }
    var pendingRuntimePermission by remember { mutableStateOf<PermissionInfo?>(null) }
    var deniedRuntimePermission by remember { mutableStateOf<PermissionInfo?>(null) }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }

    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val permission = pendingRuntimePermission
        pendingRuntimePermission = null
        viewModel.refreshPermissions()
        if (permission != null) {
            val name = context.getString(permission.nameResId)
            if (granted) {
                Toast.makeText(
                    context,
                    context.getString(R.string.permission_request_granted, name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                deniedRuntimePermission = permission
            }
        }
    }

    fun launchPermissionSettings(permission: PermissionInfo) {
        try {
            settingsLauncher.launch(createPermissionSettingsIntent(context, permission.id))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                context,
                context.getString(R.string.cannot_open_settings),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun handlePermissionClick(permission: PermissionInfo) {
        when (resolvePermissionClickAction(permission.id, permission.status)) {
            PermissionClickAction.REQUEST_RUNTIME_PERMISSION -> {
                pendingRuntimePermission = permission
                runtimePermissionLauncher.launch(permission.androidPermission)
            }
            PermissionClickAction.OPEN_AUTOFILL_SETTINGS,
            PermissionClickAction.OPEN_ACCESSIBILITY_SETTINGS,
            PermissionClickAction.OPEN_BIOMETRIC_SETTINGS,
            PermissionClickAction.OPEN_APP_SETTINGS -> launchPermissionSettings(permission)
            PermissionClickAction.SHOW_GRANTED -> Toast.makeText(
                context,
                context.getString(
                    R.string.permission_already_granted,
                    context.getString(permission.nameResId)
                ),
                Toast.LENGTH_SHORT
            ).show()
            PermissionClickAction.IGNORE -> Unit
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    PermissionManagementContent(
        permissionsByCategory = permissionsByCategory,
        permissionStats = permissionStats,
        isLoading = isLoading,
        onNavigateBack = onNavigateBack,
        onRefresh = viewModel::refreshPermissions,
        onHelp = { showHelpDialog = true },
        onPermissionClick = ::handlePermissionClick,
    )

    // 帮助对话框
    if (showHelpDialog) {
        PermissionHelpDialog(
            onDismiss = { showHelpDialog = false }
        )
    }

    deniedRuntimePermission?.let { permission ->
        val permissionName = stringResource(permission.nameResId)
        AlertDialog(
            onDismissRequest = { deniedRuntimePermission = null },
            title = { Text(stringResource(R.string.permission_request_denied_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.permission_request_denied_message,
                        permissionName
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deniedRuntimePermission = null
                        launchPermissionSettings(permission)
                    }
                ) {
                    Text(stringResource(R.string.permission_open_system_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { deniedRuntimePermission = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
internal fun PermissionManagementContent(
    permissionsByCategory: Map<PermissionCategory, List<PermissionInfo>>,
    permissionStats: PermissionStats?,
    isLoading: Boolean,
    onNavigateBack: () -> Unit,
    onRefresh: () -> Unit,
    onHelp: () -> Unit,
    onPermissionClick: (PermissionInfo) -> Unit,
) {
    Scaffold(
        topBar = {
            SettingsSubpageTopBar(stringResource(R.string.permission_management_title), onNavigateBack) {
                IconButton(onClick = onHelp) {
                    Icon(Icons.Default.HelpOutline, stringResource(R.string.help))
                }
            }
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 24.dp)) {
                PermissionStatsCard(permissionStats, onRefresh, isLoading)
                permissionsByCategory.forEach { (category, permissions) ->
                    if (permissions.isNotEmpty()) {
                        PermissionCategorySection(category, permissions, onPermissionClick)
                    }
                }
            }
            if (isLoading && permissionsByCategory.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

private fun createPermissionSettingsIntent(context: Context, permissionId: String): Intent {
    return when (permissionId) {
        "AUTOFILL" -> Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        "ACCESSIBILITY" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "BIOMETRIC" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
}


