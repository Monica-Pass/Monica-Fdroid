package takagi.ru.monica.ui.screens

import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

class DefaultManagerTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (intent.getBooleanExtra("dark", false)) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, if (intent.getBooleanExtra("dark", false)) 1.5f else 1f)) {
                    DefaultPasswordManagerSheet { finish() }
                }
            }
        }
    }
}
