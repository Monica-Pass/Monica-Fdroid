package takagi.ru.monica.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import takagi.ru.monica.R
import takagi.ru.monica.data.SsoProvider

/** Provider identifiers remain stable; only their UI labels follow the app language. */
@Composable
internal fun SsoProvider.localizedName(): String = when (this) {
    SsoProvider.WECHAT -> stringResource(R.string.sso_provider_wechat)
    SsoProvider.WEIBO -> stringResource(R.string.sso_provider_weibo)
    SsoProvider.OTHER -> stringResource(R.string.sso_provider_other)
    else -> displayName
}
