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
            // Theme preference is owned by the settings store later; until then
            // follow the system. pi's own `theme` value ("dark", "light", or the
            // automatic "lightTheme/darkTheme" pair) maps onto this switch.
            var dark by remember { mutableStateOf(isSystemInDarkTheme()) }
            PiTheme(dark = dark) {
                PiRoot(onToggleTheme = { dark = !dark }, isDark = dark)
            }
        }
    }
}
