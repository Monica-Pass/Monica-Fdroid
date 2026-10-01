package takagi.ru.monica.ui

import androidx.compose.runtime.Composable
import takagi.ru.monica.ui.components.MonicaFilterMenu

@Composable
internal fun PasswordFilterPanel(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) = MonicaFilterMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    contentTag = "password_filter_panel",
    frameTag = "password_filter_menu_frame",
    content = content,
)
