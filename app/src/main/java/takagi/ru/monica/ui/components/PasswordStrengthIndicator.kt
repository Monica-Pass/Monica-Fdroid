package takagi.ru.monica.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import takagi.ru.monica.utils.PasswordStrengthAnalyzer
import takagi.ru.monica.ui.theme.LocalPowerSavePolicy
import kotlin.math.sin
import kotlin.math.PI

/**
 * 密码强度指示器组件
 * 
 * 可视化显示密码强度，包括进度条、分数、等级和颜色指示。
 * 
 * ## 显示内容
 * - 📊 进度条 (0-100%)
 * - 🎯 强度分数 (0-100分)
 * - 🏷️ 强度等级 (非常弱/弱/一般/强/非常强)
 * - 🎨 颜色指示 (红→橙→黄→浅绿→绿)
 * 
 * ## 使用示例
 * ```kotlin
 * var password by remember { mutableStateOf("") }
 * val strength = PasswordStrengthAnalyzer.calculateStrength(password)
 * 
 * PasswordStrengthIndicator(
 *     strength = strength,
 *     showScore = true,
 *     style = ProgressBarStyle.LINEAR,
 *     modifier = Modifier.fillMaxWidth()
 * )
 * ```
 * 
 * @param strength 密码强度分数 (0-100)
 * @param showScore 是否显示数字分数（默认 true）
 * @param style 进度条样式（默认 LINEAR）
 * @param modifier 修饰符
 */
@Composable
fun PasswordStrengthIndicator(
    strength: Int,
    showScore: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    
    // 强度等级和颜色
    val level = PasswordStrengthAnalyzer.getStrengthLevel(strength)
    val levelText = PasswordStrengthAnalyzer.getStrengthLevelText(level, context)
    val color = getStrengthColor(level, colorScheme)
    
    // 进度动画
    val animatedProgress by animateFloatAsState(
        targetValue = strength / 100f,
        animationSpec = tween(durationMillis = 300),
        label = "strength_progress"
    )
    
    Column(
        modifier = modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 密码强度指示器始终使用线性样式
        LinearProgressIndicator(
            progress = { animatedProgress },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
        
        // 强度信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 等级文本
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = context.getString(takagi.ru.monica.R.string.password_strength),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = levelText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = color
                )
            }
            
            // 分数
            if (showScore) {
                Text(
                    text = "$strength/100",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = color
                )
            }
        }
    }
}

/**
 * 获取强度对应的颜色
 * 
 * @param level 强度等级
 * @return 对应的颜色
 */
private fun getStrengthColor(
    level: PasswordStrengthAnalyzer.StrengthLevel,
    colorScheme: ColorScheme
): Color {
    return when (level) {
        PasswordStrengthAnalyzer.StrengthLevel.VERY_WEAK -> colorScheme.error
        PasswordStrengthAnalyzer.StrengthLevel.WEAK -> colorScheme.error.copy(alpha = 0.8f)
        PasswordStrengthAnalyzer.StrengthLevel.FAIR -> colorScheme.tertiary
        PasswordStrengthAnalyzer.StrengthLevel.STRONG -> colorScheme.secondary
        PasswordStrengthAnalyzer.StrengthLevel.VERY_STRONG -> colorScheme.primary
    }
}

/**
 * 波浪形进度条组件
 * 
 * 使用 Canvas 绘制波浪动画效果的进度指示器。
 * 
 * @param progress 进度值 (0.0-1.0)
 * @param color 进度条颜色
 * @param trackColor 轨道颜色
 * @param modifier 修饰符
 */
@Composable
fun WaveProgressIndicator(
    progress: Float,
    color: Color,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    // 波浪是纯装饰效果，省电模式下保持静态以避免持续重绘。
    val waveOffset: Float
    if (LocalPowerSavePolicy.current.allowContinuousDecorativeMotion) {
        val infiniteTransition = rememberInfiniteTransition(label = "wave")
        val animatedOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave_offset"
        )
        waveOffset = animatedOffset
    } else {
        waveOffset = 0f
    }
    
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val progressWidth = width * progress
        
        // 绘制背景轨道（圆角）
        drawRoundRect(
            color = trackColor,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2f, height / 2f)
        )
        
        // 绘制波浪形进度
        if (progress > 0f) {
            val wavePath = Path().apply {
                val amplitude = height * 0.3f  // 波浪振幅
                val wavelength = width * 0.25f  // 波长（更长的波浪）
                val centerY = height / 2f
                
                // 起始点
                moveTo(0f, centerY)
                
                // 绘制平滑的波浪曲线
                var x = 0f
                while (x <= progressWidth) {
                    val phase = ((x / wavelength) * 2 * PI.toFloat()) + waveOffset
                    val y = centerY + amplitude * sin(phase)
                    lineTo(x, y)
                    x += 2f  // 更密集的采样点
                }
                
                // 闭合路径
                lineTo(progressWidth, height)
                lineTo(0f, height)
                close()
            }
            
            // 使用 clipRect 创建圆角效果
            clipRect(
                left = 0f,
                top = 0f,
                right = progressWidth,
                bottom = height
            ) {
                drawPath(
                    path = wavePath,
                    color = color
                )
            }
        }
    }
}

/**
 * 密码强度建议列表组件
 * 
 * 显示密码改进建议列表。
 * 
 * @param suggestions 建议列表
 * @param modifier 修饰符
 */
@Composable
fun PasswordSuggestionsList(
    suggestions: List<String>,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return
    
    val context = androidx.compose.ui.platform.LocalContext.current
    
    Column(
        modifier = modifier.padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = context.getString(takagi.ru.monica.R.string.improvement_suggestions),
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        suggestions.forEach { suggestion ->
            val isWarning = suggestion.startsWith("⚠️")
            val color = if (isWarning) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = color
                )
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 密码强度卡片组件（紧凑版）
 * 
 * 包含强度指示器和建议的完整卡片。
 * 
 * @param password 待分析的密码
 * @param modifier 修饰符
 */
@Composable
fun PasswordStrengthCard(
    password: String,
    modifier: Modifier = Modifier
) {
    if (password.isEmpty()) return
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val strength = PasswordStrengthAnalyzer.calculateStrength(password)
    val suggestions = PasswordStrengthAnalyzer.getSuggestions(password, context)
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 强度指示器
            PasswordStrengthIndicator(
                strength = strength,
                showScore = true
            )
            
            // 建议列表
            if (suggestions.isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                PasswordSuggestionsList(suggestions = suggestions)
            }
        }
    }
}
