package takagi.ru.monica.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    requireMdbxTigaCreationMode(selectedMode)
    val modes = remember { mdbxTigaModesForCreation() }
    val haptics = rememberUpdatedState(rememberHapticFeedback())
    val feedbackEnabled = rememberUpdatedState(LocalHapticFeedbackEnabled.current)
    val detentCallback = rememberUpdatedState(onDetent)
    val modeCallback = rememberUpdatedState(onModeChange)
    val reducedMotion = LocalReduceAnimations.current
    var lastMode by remember(modes) { mutableStateOf(selectedMode) }
    SideEffect { lastMode = selectedMode }
    // Keep the callback identity stable, but read current settings and the current owner.
    // A callable reference to a local function can otherwise retain old captured settings.
    val select: (MdbxTigaMode) -> Unit = remember(modes) {
        { mode ->
            require(mode in modes)
            if (mode != lastMode) {
                lastMode = mode
                if (feedbackEnabled.value) (detentCallback.value ?: haptics.value::performClick).invoke()
                modeCallback.value(mode)
            }
        }
    }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val targetColor = when (selectedMode) {
        MdbxTigaMode.GLITTER -> error("Unsupported Android mode")
        MdbxTigaMode.POWER -> if (dark) Color(0xFFFF8690) else Color(0xFFAD2343)
        MdbxTigaMode.MULTI -> if (dark) Color(0xFFBE9AFF) else Color(0xFF7140C5)
        MdbxTigaMode.SKY -> if (dark) Color(0xFFBE9AFF) else Color(0xFF7140C5)
    }
    val accent by animateColorAsState(targetColor, if (reducedMotion) snap() else spring(), label = "tigaColor")
    val endColor by animateColorAsState(when (selectedMode) {
        MdbxTigaMode.GLITTER -> error("Unsupported Android mode")
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
    val title = stringResource(R.string.mdbx_tiga_section)
    MdbxCard(modifier = Modifier.fillMaxWidth().testTag("tiga_selector"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().background(background).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(selectedMode.label, style = MaterialTheme.typography.headlineSmall, color = accent,
                modifier = Modifier.padding(top = 8.dp).testTag("tiga_selected_mode"))
            MdbxTigaSlider(
                modes = modes, selectedMode = selectedMode, onModeChange = select,
                title = title, accent = accent, endColor = endColor, reducedMotion = reducedMotion,
            )
            val modeRows = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) modes.chunked(2) else listOf(modes)
            modeRows.forEach { row ->
              Row(Modifier.fillMaxWidth()) {
                row.forEach { mode ->
                    TextButton(onClick = { select(mode) }, modifier = Modifier.weight(1f).testTag("tiga_mode_${mode.name}"),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                        Text(mode.label, style = MaterialTheme.typography.labelMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (mode == selectedMode) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
              }
            }
            Text(stringResource(when (selectedMode) {
                MdbxTigaMode.GLITTER -> R.string.mdbx_client_mode_unsupported
                MdbxTigaMode.POWER -> R.string.mdbx_tiga_power_desc
                MdbxTigaMode.MULTI -> R.string.mdbx_tiga_multi_desc
                MdbxTigaMode.SKY -> R.string.mdbx_tiga_sky_desc
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Pointer position is continuous; the parent only stores the four named security modes. */
@Composable
private fun MdbxTigaSlider(
    modes: List<MdbxTigaMode>, selectedMode: MdbxTigaMode, onModeChange: (MdbxTigaMode) -> Unit,
    title: String, accent: Color, endColor: Color, reducedMotion: Boolean,
) {
    val selectedIndex = modes.indexOf(selectedMode)
    val currentSelect by rememberUpdatedState(onModeChange)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val position = remember(modes) { Animatable(selectedIndex.toFloat() / modes.lastIndex) }
    var dragging by remember(modes) { mutableStateOf(false) }
    var dragFraction by remember(modes) { mutableFloatStateOf(position.value) }
    var releasedFrom by remember(modes) { mutableStateOf<Float?>(null) }
    LaunchedEffect(selectedIndex, dragging, reducedMotion, modes) {
        if (dragging) {
            position.stop()
        } else {
            // Seed from the last pointer event even if release happened before another frame.
            releasedFrom?.let { position.snapTo(it) }
            releasedFrom = null
            val target = selectedIndex.toFloat() / modes.lastIndex
            if (reducedMotion) position.snapTo(target)
            else position.animateTo(target, tween(280, easing = FastOutSlowInEasing))
        }
    }
    val pressed by animateFloatAsState(if (dragging) 1f else 0f,
        if (reducedMotion) snap() else tween(120), label = "tigaThumbPress")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val stillPhase = remember { mutableFloatStateOf(0.5f) }
    val sparklePhase: State<Float> = if (reducedMotion || !foreground) stillPhase else {
        rememberInfiniteTransition(label = "tigaSparkles").animateFloat(
            0f, 6.283185f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "twinkle")
    }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Canvas(Modifier.fillMaxSize().testTag("tiga_track")) {
            // Read animation states during drawing; star frames do not recompose the form.
            val fraction = (if (dragging) dragFraction else position.value).coerceIn(0f, 1f)
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
            val trackClip = Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + radius * 2, CornerRadius(radius))) }
            clipPath(trackClip) {
                // No dim stars in the unfilled area, nor sparkles leaking past rounded edges.
                clipRect(left = if (rtl) thumbX else 0f, right = if (rtl) size.width else thumbX) {
                    repeat(28) { i ->
                        val x = 6.dp.toPx() + (size.width - 12.dp.toPx()) * ((i * 37 % 101) / 100f)
                        val y = centerY + ((i * 23 % 31) - 15) / 20f * radius
                        val pulse = (sin(sparklePhase.value + i * 2.399f) + 1f) / 2f
                        val color = Color.White.copy(alpha = 0.15f + 0.8f * pulse)
                        val point = Offset(x, y)
                        drawCircle(color, (0.6f + pulse * 0.5f).dp.toPx(), point)
                        if (i % 4 == 0) {
                            val r = (1.7f + pulse * 1.1f).dp.toPx()
                            drawLine(color, point - Offset(r, 0f), point + Offset(r, 0f), 0.7.dp.toPx())
                            drawLine(color, point - Offset(0f, r), point + Offset(0f, r), 0.7.dp.toPx())
                        }
                    }
                }
            }
            repeat(modes.size) { i ->
                drawCircle(Color.White.copy(alpha = 0.45f), 3.dp.toPx(), Offset(radius + travel * i / modes.lastIndex, centerY))
            }
            val thumbRadius = radius - 1.dp.toPx() + pressed * 1.5.dp.toPx()
            drawCircle(accent.copy(alpha = pressed * 0.18f), thumbRadius + 5.dp.toPx(), Offset(thumbX, centerY))
            drawCircle(Color.Black.copy(alpha = 0.12f), thumbRadius + 1.dp.toPx(), Offset(thumbX, centerY + 1.dp.toPx()))
            drawCircle(Color.White, thumbRadius, Offset(thumbX, centerY))
        }
        Box(Modifier.fillMaxSize().testTag("tiga_slider")
            .semantics {
                contentDescription = title
                stateDescription = selectedMode.label
                progressBarRangeInfo = ProgressBarRangeInfo(selectedIndex.toFloat(), 0f..modes.lastIndex.toFloat(), modes.size - 2)
                setProgress { value ->
                    if (!value.isFinite()) false else {
                        currentSelect(modes[value.roundToInt().coerceIn(0, modes.lastIndex)])
                        true
                    }
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
                        currentSelect(modes[(selectedIndex + delta).coerceIn(0, modes.lastIndex)])
                        true
                    }
                }
            }.focusable()
            .pointerInput(rtl, modes) {
                fun fractionAt(x: Float): Float {
                    val radius = 20.dp.toPx()
                    val fraction = ((x - radius) / (size.width - radius * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    return if (rtl) 1 - fraction else fraction
                }
                fun dragTo(x: Float) {
                    dragFraction = fractionAt(x)
                    currentSelect(modes[(dragFraction * modes.lastIndex).roundToInt()])
                }
                fun settle() {
                    releasedFrom = dragFraction
                    dragging = false
                }
                detectHorizontalDragGestures(
                    onDragStart = { point -> dragging = true; dragTo(point.x) },
                    onDragEnd = ::settle,
                    onDragCancel = ::settle,
                ) { change, _ ->
                    change.consume()
                    dragTo(change.position.x)
                }
            }
            .pointerInput(rtl, modes) {
                detectTapGestures { point ->
                    val radius = 20.dp.toPx()
                    val fraction = ((point.x - radius) / (size.width - radius * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    currentSelect(modes[((if (rtl) 1 - fraction else fraction) * modes.lastIndex).roundToInt()])
                }
            })
    }
}
