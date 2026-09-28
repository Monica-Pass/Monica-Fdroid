package takagi.ru.monica.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

internal data class SettingsSearchNavigation(
    val focusTitleRes: Int = 0,
    val open: (SettingsSearchEntry) -> Unit,
)

internal val LocalSettingsSearchNavigation = compositionLocalOf<SettingsSearchNavigation?> { null }
private const val SEARCH_FOCUS_KEY = "settings_search_focus"
internal val SettingsSearchTarget = SemanticsPropertyKey<Boolean>("SettingsSearchTarget")

@Composable
internal fun rememberSettingsSearchNavigation(navController: NavHostController): SettingsSearchNavigation {
    val entry by navController.currentBackStackEntryAsState()
    val focusFlow = remember(entry) {
        entry?.savedStateHandle?.getStateFlow(SEARCH_FOCUS_KEY, 0) ?: MutableStateFlow(0)
    }
    val focus by focusFlow.collectAsState()
    return SettingsSearchNavigation(focus) { result ->
        // A new back-stack entry keeps the original query and its scroll position intact.
        // No preference, permission or destructive action is executed by a search result.
        navController.navigate(result.route)
        navController.currentBackStackEntry?.savedStateHandle?.set(SEARCH_FOCUS_KEY, result.focusTitleRes)
    }
}

internal fun Modifier.settingsSearchAnchor(title: String): Modifier = composed {
    val focus = LocalSettingsSearchNavigation.current?.focusTitleRes ?: 0
    val context = LocalContext.current
    val matches = focus != 0 && title == context.getString(focus)
    if (!matches) return@composed this
    val requester = remember { BringIntoViewRequester() }
    var placed by remember(focus) { mutableStateOf(false) }
    var highlight by remember(focus) { mutableStateOf(true) }
    LaunchedEffect(focus, placed) {
        if (!placed) return@LaunchedEffect
        withFrameNanos { }
        requester.bringIntoView()
        delay(2_000)
        highlight = false
    }
    this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { placed = true }
        .semantics { this[SettingsSearchTarget] = true }
        .then(if (highlight) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
}
