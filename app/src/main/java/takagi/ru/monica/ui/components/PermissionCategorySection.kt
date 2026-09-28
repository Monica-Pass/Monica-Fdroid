package takagi.ru.monica.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import takagi.ru.monica.data.model.PermissionCategory
import takagi.ru.monica.data.model.PermissionInfo
import takagi.ru.monica.ui.screens.settingsSectionItemShape

@Composable
fun PermissionCategorySection(
    category: PermissionCategory,
    permissions: List<PermissionInfo>,
    onPermissionClick: (PermissionInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SettingsSubpageHeading(stringResource(category.titleResId))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            permissions.forEachIndexed { index, permission ->
                PermissionCard(permission, { onPermissionClick(permission) },
                    shape = settingsSectionItemShape(index, permissions.size))
            }
        }
    }
}
