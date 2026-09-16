package takagi.ru.monica.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import takagi.ru.monica.R
import takagi.ru.monica.data.Language

@Composable
fun LanguageSelectionDialog(
    currentLanguage: Language,
    onLanguageSelected: (Language) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxHeight = minOf(560.dp, configuration.screenHeightDp.dp * 0.82f)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (Language.entries.indexOf(currentLanguage) - 2).coerceAtLeast(0)
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Dialog creates a window with its own Android composition locals.
        // Keep the page's locale and interface scale inside that window.
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalDensity provides density,
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .testTag("language_selection_dialog"),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.language),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        FilledTonalIconButton(
                            onClick = onDismiss,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .testTag("language_options"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(Language.entries, key = { _, language -> language.name }) { index, language ->
                            LanguageOption(
                                language = language,
                                selected = language == currentLanguage,
                                first = index == 1,
                                last = index == Language.entries.lastIndex,
                                onClick = { onLanguageSelected(language) },
                                modifier = if (language == Language.SYSTEM) Modifier.padding(bottom = 8.dp) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOption(
    language: Language,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val standalone = selected || language == Language.SYSTEM
    val topCorner by animateDpAsState(
        targetValue = if (pressed) 12.dp else if (standalone || first) 20.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "languageOptionTopCorner",
    )
    val bottomCorner by animateDpAsState(
        targetValue = if (pressed) 12.dp else if (standalone || last) 20.dp else 4.dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "languageOptionBottomCorner",
    )
    val shape = RoundedCornerShape(
        topStart = topCorner,
        topEnd = topCorner,
        bottomStart = bottomCorner,
        bottomEnd = bottomCorner,
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        label = "languageOptionColor",
    )
    val contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                onClick = onClick,
            )
            .testTag("language_option_${language.name}")
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (language == Language.SYSTEM) {
            Icon(
                imageVector = Icons.Rounded.SettingsSuggest,
                contentDescription = null,
                tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (language == Language.CLASSICAL_CHINESE) {
                    stringResource(R.string.language_classical_chinese_native)
                } else {
                    getLanguageDisplayName(language, context)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
            )
            if (language == Language.CLASSICAL_CHINESE) {
                Text(
                    text = stringResource(R.string.language_classical_chinese_english),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
            }
        }
        if (selected) {
            Box(
                modifier = Modifier.size(24.dp).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}

internal fun getLanguageDisplayName(language: Language, context: Context): String = context.getString(
    when (language) {
        Language.SYSTEM -> R.string.language_system
        Language.ENGLISH -> R.string.language_english
        Language.CHINESE -> R.string.language_chinese
        Language.CLASSICAL_CHINESE -> R.string.language_classical_chinese
        Language.VIETNAMESE -> R.string.language_vietnamese
        Language.JAPANESE -> R.string.language_japanese
        Language.RUSSIAN -> R.string.language_russian
        Language.KOREAN -> R.string.language_korean
        Language.GERMAN -> R.string.language_german
        Language.SPANISH -> R.string.language_spanish
        Language.FRENCH -> R.string.language_french
        Language.POLISH -> R.string.language_polish
        Language.NYA -> R.string.language_nya
    }
)
