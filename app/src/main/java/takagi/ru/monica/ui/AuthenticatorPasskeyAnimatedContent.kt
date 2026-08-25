package takagi.ru.monica.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import takagi.ru.monica.ui.main.navigation.BottomNavItem
import takagi.ru.monica.ui.navigation.parallaxEnterFromLeft
import takagi.ru.monica.ui.navigation.parallaxExitToLeft
import takagi.ru.monica.ui.navigation.slideInFromRight
import takagi.ru.monica.ui.navigation.slideOutToRight

@Composable
internal fun AuthenticatorPasskeyAnimatedContent(
    currentTab: BottomNavItem,
    modifier: Modifier = Modifier,
    content: @Composable (BottomNavItem) -> Unit
) {
    val saveableStateHolder = rememberSaveableStateHolder()

    if (currentTab.isAuthenticatorPasskeyTab()) {
        AnimatedContent(
            targetState = currentTab,
            modifier = modifier,
            transitionSpec = {
                when {
                    initialState == BottomNavItem.Authenticator && targetState == BottomNavItem.Passkey ->
                        (slideInFromRight() togetherWith parallaxExitToLeft()).using(
                            SizeTransform(clip = false)
                        )

                    initialState == BottomNavItem.Passkey && targetState == BottomNavItem.Authenticator ->
                        (parallaxEnterFromLeft() togetherWith slideOutToRight()).using(
                            SizeTransform(clip = false)
                        )

                    else -> EnterTransition.None togetherWith ExitTransition.None
                }
            },
            contentKey = BottomNavItem::key,
            label = "authenticator_passkey_switch",
            content = { targetTab ->
                saveableStateHolder.SaveableStateProvider(targetTab.key) {
                    content(targetTab)
                }
            }
        )
    } else {
        Box(modifier = modifier) {
            key(currentTab.key) {
                saveableStateHolder.SaveableStateProvider(currentTab.key) {
                    content(currentTab)
                }
            }
        }
    }
}

private fun BottomNavItem.isAuthenticatorPasskeyTab(): Boolean =
    this == BottomNavItem.Authenticator || this == BottomNavItem.Passkey
