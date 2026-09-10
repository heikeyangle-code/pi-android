package app.pi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.pi.ui.PiRoot
import app.pi.ui.theme.PiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // isSystemInDarkTheme() is @Composable, so it has to be read in the
            // composition — not inside remember{}'s non-composable lambda. Read
            // it once to seed the mutable state; the user can override after.
            val systemDark = isSystemInDarkTheme()
            var dark by remember { mutableStateOf(systemDark) }
            PiTheme(dark = dark) {
                PiRoot(onToggleTheme = { dark = !dark }, isDark = dark)
            }
        }
    }
}
