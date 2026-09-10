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
                PiRoot(
                    isDark = dark,
                    onThemeChanged = { name ->
                        // pi persists one of: "dark", "light", or the automatic
                        // form "lightTheme/darkTheme". The slash is the tell, and
                        // the string is deliberately never split into two fields —
                        // doing so would make the file unreadable by pi.
                        dark = when {
                            name.contains('/') -> systemDark
                            name.equals("light", ignoreCase = true) -> false
                            name.equals("dark", ignoreCase = true) -> true
                            else -> dark
                        }
                    },
                )
            }
        }
    }
}
