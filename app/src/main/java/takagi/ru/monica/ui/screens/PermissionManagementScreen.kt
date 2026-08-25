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
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun PermissionManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: PermissionViewModel = viewModel()
) {
    val context = LocalContext.current
    val permissionsByCategory by viewModel.permissionsByCategory.collectAsState()
    val permissionStats by viewModel.permissionStats.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showHelpDialog by remember { mutableStateOf(false) }
    var showInfoCard by remember { mutableStateOf(true) }
    var pendingRuntimePermission by remember { mutableStateOf<PermissionInfo?>(null) }
    var deniedRuntimePermission by remember { mutableStateOf<PermissionInfo?>(null) }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
        Toast.makeText(
            context,
            context.getString(R.string.permission_status_refreshed),
            Toast.LENGTH_SHORT
        ).show()
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

    // 准备共享元素 Modifier
    val sharedTransitionScope = takagi.ru.monica.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = takagi.ru.monica.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "permission_settings_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }

    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshPermissions() },
                        enabled = !isLoading
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(
                            Icons.Default.Help,
                            contentDescription = stringResource(R.string.help)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // 信息卡片
                if (showInfoCard) {
                    PermissionInfoCard(
                        onDismiss = { showInfoCard = false }
                    )
                }

                // 权限统计
                permissionStats?.let { stats ->
                    PermissionStatsCard(stats = stats)
                }

                // 按分类显示权限
                permissionsByCategory.forEach { (category, permissions) ->
                    PermissionCategorySection(
                        category = category,
                        permissions = permissions,
                        onPermissionClick = ::handlePermissionClick
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }

            // 加载指示器
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(16.dp)
                        .size(48.dp)
                )
            }
        }
    }

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


