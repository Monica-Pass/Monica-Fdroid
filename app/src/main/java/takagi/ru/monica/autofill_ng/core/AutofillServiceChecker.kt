package takagi.ru.monica.autofill_ng.core

import takagi.ru.monica.R

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import takagi.ru.monica.autofill_ng.AutofillPreferences
import takagi.ru.monica.autofill_ng.MonicaAutofillServiceNg
import takagi.ru.monica.utils.DeviceUtils

/**
 * 自动填充服务状态检查器
 * 
 * 检查服务是否正确配置和启用，并提供诊断信息和修复建议
 * 
 * 功能:
 * - 检查服务是否在 Manifest 中声明
 * - 检查系统是否启用了自动填充服务
 * - 检查应用内是否启用了自动填充
 * - 检查所需权限
 * - 检测设备兼容性问题
 * - 生成修复建议
 * 
 * @author Monica Team
 * @since 2.0
 */
class AutofillServiceChecker(private val context: Context) {
    private val strings = takagi.ru.monica.utils.AppLocaleStringResolver(context)
    
    companion object {
        private const val TAG = "AutofillServiceChecker"
    }

    private fun monicaAutofillComponents(): List<ComponentName> {
        return listOf(
            ComponentName(context, MonicaAutofillServiceNg::class.java),
        )
    }
    
    /**
     * 服务状态数据模型
     */
    data class ServiceStatus(
        val isServiceDeclared: Boolean,
        val isSystemEnabled: Boolean,
        val isAppEnabled: Boolean,
        val hasRequiredPermissions: Boolean,
        val compatibilityIssues: List<String>,
        val recommendations: List<String>
    ) {
        /**
         * 服务是否完全正常
         */
        fun isFullyOperational(): Boolean {
            return isServiceDeclared && 
                   isSystemEnabled && 
                   isAppEnabled && 
                   hasRequiredPermissions &&
                   compatibilityIssues.isEmpty()
        }
        
        /**
         * 获取状态摘要
         */
        fun getSummary(context: Context): String {
            return when {
                isFullyOperational() -> context.getString(takagi.ru.monica.R.string.autofill_status_summary_operational)
                !isServiceDeclared -> context.getString(takagi.ru.monica.R.string.autofill_status_summary_not_declared)
                !isSystemEnabled -> context.getString(takagi.ru.monica.R.string.autofill_status_summary_system_disabled)
                !isAppEnabled -> context.getString(takagi.ru.monica.R.string.autofill_status_summary_app_disabled)
                !hasRequiredPermissions -> context.getString(takagi.ru.monica.R.string.autofill_status_summary_no_permissions)
                compatibilityIssues.isNotEmpty() -> context.getString(takagi.ru.monica.R.string.autofill_status_summary_compatibility_issues)
                else -> context.getString(takagi.ru.monica.R.string.autofill_status_summary_abnormal)
            }
        }
    }
    
    /**
     * 检查服务状态
     * 
     * @return 完整的服务状态信息
     */
    fun checkServiceStatus(): ServiceStatus {
        AutofillLogger.i(TAG, "Starting service status check")
        
        val isServiceDeclared = checkServiceDeclared()
        val isSystemEnabled = checkSystemEnabled()
        val isAppEnabled = checkAppEnabled()
        val hasRequiredPermissions = checkPermissions()
        val compatibilityIssues = detectCompatibilityIssues()
        val recommendations = generateRecommendations(
            isServiceDeclared,
            isSystemEnabled,
            isAppEnabled,
            hasRequiredPermissions,
            compatibilityIssues
        )
        
        val status = ServiceStatus(
            isServiceDeclared = isServiceDeclared,
            isSystemEnabled = isSystemEnabled,
            isAppEnabled = isAppEnabled,
            hasRequiredPermissions = hasRequiredPermissions,
            compatibilityIssues = compatibilityIssues,
            recommendations = recommendations
        )
        
        AutofillLogger.i(TAG, "Service status check completed: ${status.getSummary(context)}")
        
        return status
    }
    
    /**
     * 检查服务是否在 Manifest 中声明
     */
    private fun checkServiceDeclared(): Boolean {
        return try {
            val packageManager = context.packageManager
            val targetComponents = monicaAutofillComponents()
            var declaredWithPermission = 0

            targetComponents.forEach { component ->
                try {
                    val serviceInfo = packageManager.getServiceInfo(
                        component,
                        PackageManager.GET_META_DATA
                    )
                    val hasPermission =
                        serviceInfo.permission == android.Manifest.permission.BIND_AUTOFILL_SERVICE
                    if (hasPermission) {
                        declaredWithPermission++
                    } else {
                        AutofillLogger.w(
                            TAG,
                            "Service declared but missing BIND_AUTOFILL_SERVICE permission: $component"
                        )
                    }
                    AutofillLogger.d(TAG, "Service declared: $component, hasPermission=$hasPermission")
                } catch (e: PackageManager.NameNotFoundException) {
                    AutofillLogger.w(TAG, "Service not declared in manifest: $component")
                }
            }

            val declared = declaredWithPermission > 0
            if (!declared) {
                AutofillLogger.e(TAG, "No Monica autofill services declared with required permission")
            }
            declared
        } catch (e: Exception) {
            AutofillLogger.e(TAG, "Error checking service declaration", e)
            false
        }
    }
    
    /**
     * 检查系统是否启用了自动填充服务
     */
    private fun checkSystemEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val autofillManager = context.getSystemService(AutofillManager::class.java)
                val targetComponents = monicaAutofillComponents().toSet()

                // 主路径：AutofillManager
                val hasEnabledServices = autofillManager?.hasEnabledAutofillServices() == true
                val managerComponent = autofillManager?.autofillServiceComponentName
                val managerMatches = managerComponent != null && targetComponents.contains(managerComponent)

                // 兜底路径：Settings.Secure（部分 ROM 上 manager 返回会延迟/空值）
                val secureAutofillServiceRaw = Settings.Secure.getString(
                    context.contentResolver,
                    "autofill_service"
                )
                val secureComponent = ComponentName.unflattenFromString(secureAutofillServiceRaw)
                val secureMatches = secureComponent != null && targetComponents.contains(secureComponent)

                AutofillLogger.d(
                    TAG,
                    "System autofill state: managerEnabled=$hasEnabledServices, " +
                        "managerComponent=$managerComponent, secureRaw=$secureAutofillServiceRaw, " +
                        "secureComponent=$secureComponent, targets=${targetComponents.joinToString()}"
                )

                if (managerMatches || secureMatches) {
                    if (!hasEnabledServices || !managerMatches) {
                        AutofillLogger.w(
                            TAG,
                            "Detected via secure fallback (possible OEM framework inconsistency)"
                        )
                    }
                    return true
                }

                if (!hasEnabledServices && secureAutofillServiceRaw.isNullOrBlank()) {
                    AutofillLogger.d(TAG, "No autofill service enabled in system")
                } else {
                    AutofillLogger.d(TAG, "Autofill is enabled but not Monica service")
                }
                return false
            } catch (e: Exception) {
                AutofillLogger.e(TAG, "Error checking system enabled status", e)
                false
            }
        } else {
            AutofillLogger.w(TAG, "Autofill not supported on Android < 8.0")
            false
        }
    }
    
    /**
     * 检查应用内是否启用了自动填充
     */
    private fun checkAppEnabled(): Boolean {
        return try {
            val isEnabled = runBlocking {
                AutofillPreferences(context).isAutofillEnabled.first()
            }

            AutofillLogger.d(TAG, "App-level autofill enabled: $isEnabled")
            isEnabled
        } catch (e: Exception) {
            AutofillLogger.e(TAG, "Error checking app-level settings", e)
            true // 默认假设启用
        }
    }
    
    /**
     * 检查所需权限
     */
    private fun checkPermissions(): Boolean {
        return try {
            // 自动填充服务不需要运行时权限
            // 只需要在 Manifest 中声明 BIND_AUTOFILL_SERVICE
            // 这个权限由系统自动授予
            
            // 检查是否有其他可选权限
            val hasInternetPermission = context.checkSelfPermission(
                android.Manifest.permission.INTERNET
            ) == PackageManager.PERMISSION_GRANTED
            
            val hasNetworkStatePermission = context.checkSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE
            ) == PackageManager.PERMISSION_GRANTED
            
            AutofillLogger.d(TAG, "Internet permission: $hasInternetPermission")
            AutofillLogger.d(TAG, "Network state permission: $hasNetworkStatePermission")
            
            // 自动填充核心功能不依赖这些权限
            true
        } catch (e: Exception) {
            AutofillLogger.e(TAG, "Error checking permissions", e)
            true
        }
    }
    
    /**
     * 检测设备兼容性问题
     */
    private fun detectCompatibilityIssues(): List<String> {
        val issues = mutableListOf<String>()
        
        try {
            // 1. 检查 Android 版本
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                issues.add(strings.get(R.string.autofill_compat_android_old))
            }
            
            // 2. 检查设备品牌兼容性
            val manufacturer = Build.MANUFACTURER.lowercase()
            val isAndroid12Family =
                Build.VERSION.SDK_INT == Build.VERSION_CODES.S ||
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.S_V2

            when {
                manufacturer.contains("huawei") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    issues.add(strings.get(R.string.autofill_compat_huawei))
                }
                manufacturer.contains("xiaomi") -> {
                    issues.add(strings.get(R.string.autofill_compat_xiaomi))
                }
                manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                    issues.add(strings.get(R.string.autofill_compat_oppo))
                }
                manufacturer.contains("vivo") -> {
                    issues.add(strings.get(R.string.autofill_compat_vivo))
                }
                manufacturer.contains("samsung") -> {
                    // Samsung 通常兼容性较好
                    AutofillLogger.d(TAG, "Samsung device detected, generally good compatibility")
                }
            }

            if (isAndroid12Family && DeviceUtils.isChineseROM()) {
                issues.add(strings.get(R.string.autofill_compat_android12_rom))
            }
            
            // 3. 检查内联建议支持
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val supportsInline = DeviceUtils.supportsInlineSuggestions()
                if (!supportsInline) {
                    issues.add(strings.get(R.string.autofill_compat_inline))
                }
            }
            
            // 4. 检查是否是模拟器
            val isEmulator = Build.FINGERPRINT.contains("generic") ||
                            Build.MODEL.contains("Emulator") ||
                            Build.MODEL.contains("Android SDK")
            
            if (isEmulator) {
                issues.add(strings.get(R.string.autofill_compat_emulator))
            }
            
            // 5. 检查系统自动填充框架
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val autofillManager = context.getSystemService(AutofillManager::class.java)
                val isSupported = autofillManager?.isAutofillSupported == true
                
                if (!isSupported) {
                    issues.add(strings.get(R.string.autofill_compat_unsupported))
                }
            }
            
            AutofillLogger.d(TAG, "Detected ${issues.size} compatibility issues")
            
        } catch (e: Exception) {
            AutofillLogger.e(TAG, "Error detecting compatibility issues", e)
            issues.add(strings.get(R.string.autofill_compat_check_failed))
        }
        
        return issues
    }
    
    /**
     * 生成修复建议
     */
    private fun generateRecommendations(
        isServiceDeclared: Boolean,
        isSystemEnabled: Boolean,
        isAppEnabled: Boolean,
        hasRequiredPermissions: Boolean,
        compatibilityIssues: List<String>
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        try {
            // 1. 服务未声明
            if (!isServiceDeclared) {
                recommendations.add(strings.get(R.string.autofill_check_manifest))
                recommendations.add(strings.get(R.string.autofill_check_permission_manifest))
            }
            
            // 2. 系统未启用
            if (!isSystemEnabled) {
                recommendations.add(strings.get(R.string.autofill_check_enable_system))
                recommendations.add(strings.get(R.string.autofill_check_path_system))
                
                // 针对不同品牌提供具体路径
                val manufacturer = Build.MANUFACTURER.lowercase()
                when {
                    manufacturer.contains("xiaomi") -> {
                        recommendations.add(strings.get(R.string.autofill_check_path_xiaomi))
                        recommendations.add(strings.get(R.string.autofill_check_miui_permission))
                    }
                    manufacturer.contains("huawei") -> {
                        recommendations.add(strings.get(R.string.autofill_check_path_huawei))
                    }
                    manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                        recommendations.add(strings.get(R.string.autofill_check_path_oppo))
                    }
                    manufacturer.contains("vivo") -> {
                        recommendations.add(strings.get(R.string.autofill_check_path_vivo))
                    }
                    manufacturer.contains("samsung") -> {
                        recommendations.add(strings.get(R.string.autofill_check_path_samsung))
                    }
                }
            }
            
            // 3. 应用内未启用
            if (!isAppEnabled) {
                recommendations.add(strings.get(R.string.autofill_check_enable_app))
            }
            
            // 4. 权限问题
            if (!hasRequiredPermissions) {
                recommendations.add(strings.get(R.string.autofill_check_app_permissions))
            }
            
            // 5. 兼容性问题
            if (compatibilityIssues.isNotEmpty()) {
                recommendations.add(strings.get(R.string.autofill_check_issue_count, compatibilityIssues.size))
                
                // 针对特定问题提供建议
                compatibilityIssues.forEach { issue ->
                    when {
                        issue == strings.get(R.string.autofill_compat_xiaomi) -> {
                            recommendations.add(strings.get(R.string.autofill_check_xiaomi_permission))
                        }
                        issue == strings.get(R.string.autofill_compat_huawei) -> {
                            recommendations.add(strings.get(R.string.autofill_check_huawei_autostart))
                        }
                        issue == strings.get(R.string.autofill_compat_oppo) -> {
                            recommendations.add(strings.get(R.string.autofill_check_oppo_permission))
                        }
                        issue == strings.get(R.string.autofill_compat_vivo) -> {
                            recommendations.add(strings.get(R.string.autofill_check_vivo_permission))
                        }
                        issue == strings.get(R.string.autofill_compat_inline) -> {
                            recommendations.add(strings.get(R.string.autofill_compat_inline))
                        }
                        issue == strings.get(R.string.autofill_compat_android12_rom) -> {
                            recommendations.add(strings.get(R.string.autofill_check_background))
                            recommendations.add(strings.get(R.string.autofill_check_trigger_manually))
                        }
                    }
                }
            }
            
            // 6. 通用建议
            if (recommendations.isEmpty()) {
                recommendations.add(strings.get(R.string.autofill_check_configured))
                recommendations.add(strings.get(R.string.autofill_check_view_logs))
            } else {
                recommendations.add(strings.get(R.string.autofill_check_restart))
            }
            
            AutofillLogger.d(TAG, "Generated ${recommendations.size} recommendations")
            
        } catch (e: Exception) {
            AutofillLogger.e(TAG, "Error generating recommendations", e)
            recommendations.add(strings.get(R.string.autofill_check_recommendations_failed))
        }
        
        return recommendations
    }
    
    /**
     * 快速检查服务是否可用
     * 
     * @return true 如果服务已启用且可用
     */
    fun isServiceAvailable(): Boolean {
        return checkSystemEnabled() && checkAppEnabled()
    }
    
    /**
     * 获取自动填充设置的 Intent
     * 用于跳转到系统设置页面
     */
    fun getAutofillSettingsIntent(): android.content.Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                android.content.Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
            } catch (e: Exception) {
                AutofillLogger.e(TAG, "Error creating autofill settings intent", e)
                null
            }
        } else {
            null
        }
    }
}


