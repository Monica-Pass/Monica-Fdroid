package takagi.ru.monica.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import takagi.ru.monica.R
import takagi.ru.monica.data.MdbxTigaMode
import takagi.ru.monica.ui.LocalHapticFeedbackEnabled
import takagi.ru.monica.ui.LocalReduceAnimations
import takagi.ru.monica.ui.haptic.rememberHapticFeedback

/** Presentation only: enum values and KDF profiles are never inferred from colors or positions. */
@Composable
internal fun MdbxTigaModeSelector(
    selectedMode: MdbxTigaMode,
    onModeChange: (MdbxTigaMode) -> Unit,
    onDetent: (() -> Unit)? = null,
) {
    val modes = remember { listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER) }
    val haptics = rememberHapticFeedback()
    val feedbackEnabled = LocalHapticFeedbackEnabled.current
    val reducedMotion = LocalReduceAnimations.current
    var lastMode by remember(selectedMode) { mutableStateOf(selectedMode) }
    fun select(mode: MdbxTigaMode) {
        if (mode == lastMode) return
        lastMode = mode
        if (feedbackEnabled) (onDetent ?: haptics::performLightClick).invoke()
        onModeChange(mode)
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val targetColor = when (selectedMode) {
        MdbxTigaMode.POWER -> if (dark) Color(0xFFFF8690) else Color(0xFFAD2343)
        MdbxTigaMode.MULTI -> if (dark) Color(0xFFBE9AFF) else Color(0xFF7140C5)
        MdbxTigaMode.SKY -> if (dark) Color(0xFFBE9AFF) else Color(0xFF7140C5)
    }
    val accent by animateColorAsState(targetColor, if (reducedMotion) snap() else spring(), label = "tigaColor")
    val endColor by animateColorAsState(when (selectedMode) {
        MdbxTigaMode.MULTI -> Color(0xFFF25372)
        MdbxTigaMode.POWER -> Color(0xFFFF687D)
        MdbxTigaMode.SKY -> if (dark) Color(0xFFBE9AFF) else Color(0xFF7140C5)
    }, if (reducedMotion) snap() else spring(), label = "tigaGradientEnd")
    // Subtle full-card color remains visible even when the thumb covers the Sky fill.
    // Derive it from the animated accents so track and background transition together.
    val tintBase = if (dark) Color(0xFF141218) else Color(0xFFFFFBFF)
    val tintAmount = if (dark) 0.10f else 0.06f
    val background = Brush.horizontalGradient(listOf(
        lerp(tintBase, accent, tintAmount), lerp(tintBase, endColor, tintAmount)))
    val sparklePhase = if (reducedMotion) 0.5f else {
        val transition = rememberInfiniteTransition(label = "tigaSparkles")
        val phase by transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "twinkle")
        phase
    }
    val currentSelect by rememberUpdatedState<(MdbxTigaMode) -> Unit>({ select(it) })
    val fraction by animateFloatAsState(modes.indexOf(selectedMode) / 2f,
        if (reducedMotion) snap() else spring(dampingRatio = 0.85f, stiffness = 500f), label = "tigaDetent")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val title = stringResource(R.string.mdbx_tiga_section)
    MdbxCard(modifier = Modifier.fillMaxWidth().testTag("tiga_selector"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().background(background).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selectedMode.label, style = MaterialTheme.typography.headlineSmall, color = accent,
                modifier = Modifier.padding(top = 8.dp).testTag("tiga_selected_mode"))
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius = 20.dp.toPx()
                    val travel = (size.width - radius * 2).coerceAtLeast(0f)
                    val centerY = size.height / 2
                    val thumbX = radius + travel * if (rtl) 1 - fraction else fraction
                    val top = centerY - radius
                    drawRoundRect(trackColor, Offset(0f, top), Size(size.width, radius * 2), CornerRadius(radius))
                    val fillWidth = radius * 2 + travel * fraction
                    val fillStart = if (rtl) size.width - fillWidth else 0f
                    drawRoundRect(Brush.horizontalGradient(
                        if (rtl) listOf(endColor, accent) else listOf(accent, endColor),
                        startX = fillStart, endX = fillStart + fillWidth),
                        Offset(fillStart, top), Size(fillWidth, radius * 2), CornerRadius(radius))
                    // Fixed positions avoid particle jumps during recomposition. Outside the
                    // active fill the same stars are quiet, so Sky still has visible stars.
                    repeat(22) { i ->
                        val x = radius + travel * ((i * 37 % 101) / 100f)
                        val y = centerY + ((i * 23 % 31) - 15) / 20f * radius
                        val active = if (rtl) x >= fillStart else x <= fillWidth
                        val twinkle = 0.35f + 0.65f * ((sin((sparklePhase + i * 0.19f) * 6.283f) + 1f) / 2f)
                        val color = (if (active) Color.White else accent).copy(alpha = twinkle * if (active) 0.8f else 0.3f)
                        val point = Offset(x, y)
                        drawCircle(color, (if (i % 5 == 0) 1.2f else 0.7f).dp.toPx(), point)
                        if (i % 5 == 0) {
                            val r = 2.4.dp.toPx()
                            drawLine(color, point - Offset(r, 0f), point + Offset(r, 0f), 0.7.dp.toPx())
                            drawLine(color, point - Offset(0f, r), point + Offset(0f, r), 0.7.dp.toPx())
                        }
                    }
                    repeat(3) { i ->
                        drawCircle(Color.White.copy(alpha = 0.45f), 3.dp.toPx(), Offset(radius + travel * i / 2f, centerY))
                    }
                    drawCircle(Color.Black.copy(alpha = 0.12f), radius, Offset(thumbX, centerY + 1.dp.toPx()))
                    drawCircle(Color.White, radius - 1.dp.toPx(), Offset(thumbX, centerY))
                }
                // Absolute pointer positions keep detents stable even when a parent updates
                // selectedMode during a gesture. Vertical drags remain available to the form.
                Box(Modifier.fillMaxSize().testTag("tiga_slider")
                    .semantics {
                        contentDescription = title
                        stateDescription = selectedMode.label
                        progressBarRangeInfo = ProgressBarRangeInfo(modes.indexOf(selectedMode).toFloat(), 0f..2f, 1)
                        setProgress { value ->
                            currentSelect(modes[value.roundToInt().coerceIn(0, 2)])
                            true
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else {
                            val delta = when (event.key) {
                                Key.DirectionRight -> if (rtl) -1 else 1
                                Key.DirectionLeft -> if (rtl) 1 else -1
                                else -> 0
                            }
                            if (delta == 0) false else {
                                currentSelect(modes[(modes.indexOf(selectedMode) + delta).coerceIn(0, 2)])
                                true
                            }
                        }
                    }.focusable()
                    .pointerInput(rtl) {
                        fun pick(x: Float) {
                            val radius = 20.dp.toPx()
                            val position = ((x - radius) / (size.width - radius * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            currentSelect(modes[((if (rtl) 1 - position else position) * 2).roundToInt()])
                        }
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            pick(change.position.x)
                        }
                    }
                    .pointerInput(rtl) {
                        detectTapGestures { point ->
                            val radius = 20.dp.toPx()
                            val position = ((point.x - radius) / (size.width - radius * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
                            currentSelect(modes[((if (rtl) 1 - position else position) * 2).roundToInt()])
                        }
                    })
            }
            Row(Modifier.fillMaxWidth()) {
                modes.forEach { mode ->
                    TextButton(onClick = { select(mode) }, modifier = Modifier.weight(1f).testTag("tiga_mode_${mode.name}"),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                        Text(mode.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                            color = if (mode == selectedMode) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(stringResource(when (selectedMode) {
                MdbxTigaMode.POWER -> R.string.mdbx_tiga_power_desc
                MdbxTigaMode.MULTI -> R.string.mdbx_tiga_multi_desc
                MdbxTigaMode.SKY -> R.string.mdbx_tiga_sky_desc
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth())
        }
    }
}
