package takagi.ru.monica.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import takagi.ru.monica.ui.animation.AnimationUtils

/**
 * Local expansion follows the app's reduced-motion preference independently of the shared-element
 * transition safe mode. Compose also applies Android's system animation duration scale.
 */
val LocalExpansionAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * Reveals a section below a fixed header, keeping the outgoing content until collapse finishes.
 * Put the body's padding/spacing inside [content] so it contracts with the body. The containing
 * card follows this height automatically; do not also animate the card's size.
 */
@Composable
fun MonicaExpandableContent(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val animate = LocalExpansionAnimationsEnabled.current
    // A new state starts at the requested endpoint when the preference changes mid-transition.
    // Updating only enter/exit specs would leave AnimatedVisibility's active transition running.
    val visibility = remember(animate) { MutableTransitionState(expanded) }
    visibility.targetState = expanded
    AnimatedVisibility(
        visibleState = visibility,
        modifier = modifier,
        enter = if (animate) AnimationUtils.expandVerticallyAnimation() else EnterTransition.None,
        exit = if (animate) AnimationUtils.shrinkVerticallyAnimation() else ExitTransition.None,
        label = "monicaExpansion"
    ) {
        content()
    }
}

/**
 * Smooths changes to an existing body, such as text wrapping or deleting rows from a history list.
 * Apply once to content INSIDE a Card/Surface, before content padding or size constraints. This lets
 * the surface draw rounded corners at every intermediate height. Use [MonicaExpandableContent]
 * instead when the body is inserted/removed; combining both would animate the same size twice.
 */
@Composable
fun Modifier.animateMonicaContentSize(): Modifier = if (LocalExpansionAnimationsEnabled.current) {
    animateContentSize(
        animationSpec = AnimationUtils.expansionSizeSpec,
        alignment = Alignment.TopStart
    )
} else {
    this
}

/** The same chevron rotates with the section instead of swapping two icons on the first frame. */
@Composable
fun MonicaExpansionChevron(
    expanded: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    val targetRotation = if (expanded) 180f else 0f
    val rotation by if (LocalExpansionAnimationsEnabled.current) {
        animateFloatAsState(
            targetValue = targetRotation,
            animationSpec = AnimationUtils.expansionRotationSpec,
            label = "monicaExpansionChevron"
        )
    } else {
        rememberUpdatedState(targetRotation)
    }
    Icon(
        imageVector = Icons.Default.ExpandMore,
        contentDescription = contentDescription,
        modifier = modifier.graphicsLayer { rotationZ = rotation },
        tint = tint
    )
}
