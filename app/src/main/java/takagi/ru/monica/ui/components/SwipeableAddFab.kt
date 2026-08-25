package takagi.ru.monica.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun SwipeableAddFab(
    modifier: Modifier = Modifier,
    fabContent: @Composable () -> Unit,
    fabBottomOffset: Dp = 0.dp,
    fabContainerColor: Color = Color.Unspecified,
    onClick: () -> Unit,
    onExpandStateChanged: (Boolean) -> Unit = {}
) {
    DisposableEffect(Unit) {
        onExpandStateChanged(false)
        onDispose { onExpandStateChanged(false) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(animationSpec = tween(160)) +
                scaleIn(initialScale = 0.9f, animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(120)) +
                scaleOut(targetScale = 0.9f, animationSpec = tween(140)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = fabBottomOffset + 16.dp)
        ) {
            val fabColor = if (fabContainerColor == Color.Unspecified) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                fabContainerColor
            }
            FloatingActionButton(
                onClick = onClick,
                modifier = Modifier
                    .size(56.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = fabColor,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 8.dp,
                    focusedElevation = 6.dp,
                    hoveredElevation = 8.dp
                )
            ) {
                fabContent()
            }
        }
    }
}
