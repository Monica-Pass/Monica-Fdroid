package takagi.ru.monica.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import takagi.ru.monica.R
import androidx.compose.ui.res.stringResource

internal val SecurityAnalysisPullTriggerDistance = 72.dp
internal val SecurityAnalysisPullMaxDistance = 112.dp

internal fun calculateSecurityAnalysisPullProgress(
    currentOffset: Float,
    triggerDistance: Float
): Float {
    if (triggerDistance <= 0f) return 0f
    return (currentOffset / triggerDistance).coerceIn(0f, 1f)
}

@Composable
fun SecurityAnalysisPullCard(
    pullOffset: Float,
    triggerDistance: Float,
    isArmed: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = calculateSecurityAnalysisPullProgress(
        currentOffset = pullOffset,
        triggerDistance = triggerDistance
    )
    val compactContentAlpha = (1f - progress * 1.8f).coerceIn(0f, 1f)
    val expandedContentAlpha = ((progress - 0.18f) / 0.82f).coerceIn(0f, 1f)
    val compactHeight = 88.dp
    val expandedHeight = 236.dp
    val cardHeight = compactHeight + (expandedHeight - compactHeight) * progress
    val cornerRadius = 28.dp + 16.dp * progress

    val containerColor = lerp(
        MaterialTheme.colorScheme.surfaceContainerHigh,
        MaterialTheme.colorScheme.primaryContainer,
        progress
    )
    val foregroundColor = lerp(
        MaterialTheme.colorScheme.onSurface,
        MaterialTheme.colorScheme.onPrimaryContainer,
        progress
    )
    val securityTitle = stringResource(R.string.security_analysis)
    val securityDescription = stringResource(R.string.security_analysis_description)
    val hintText = stringResource(
        if (isArmed) {
            R.string.security_analysis_pull_release
        } else {
            R.string.security_analysis_pull_hint
        }
    )

    Card(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clearAndSetSemantics {
                contentDescription = "$securityTitle. $securityDescription. $hintText"
                role = Role.Button
                onClick(label = securityTitle) {
                    onOpen()
                    true
                }
            },
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 2.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = compactContentAlpha
                        translationY = progress * -8.dp.toPx()
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = securityTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = foregroundColor
                    )
                    Text(
                        text = securityDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            rotationZ = progress * 180f
                        }
                )
            }

            Column(
                modifier = Modifier.graphicsLayer {
                    alpha = expandedContentAlpha
                    val contentScale = 0.9f + 0.1f * progress
                    scaleX = contentScale
                    scaleY = contentScale
                    translationY = (1f - progress) * 18.dp.toPx()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = securityTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = foregroundColor,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        letterSpacing = 0.1.sp
                    ),
                    color = foregroundColor.copy(alpha = 0.78f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
