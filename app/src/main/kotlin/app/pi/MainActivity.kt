package app.pi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pi.ui.PiRoot
import app.pi.ui.PiSessionViewModel
import app.pi.ui.theme.PiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // isSystemInDarkTheme() is @Composable, so it has to be read in the
            // composition. It is only an input to the theme resolution: pi's
            // `theme` setting may name one theme ("dark", "my-theme") or the
            // automatic pair "lightTheme/darkTheme", and the pair is resolved
            // against this value — the same job `resolveThemeSetting` does for
            // pi's own terminal background detection (`theme.ts:597-608`).
            val systemDark = isSystemInDarkTheme()
            val session: PiSessionViewModel = viewModel()
            val theme by session.theme.collectAsState()
            val uiState by session.state.collectAsState()

            LaunchedEffect(systemDark) { session.startUiPreferences(systemDark) }

            // The palette is the resolved pi theme, not two hand-written
            // constants: a theme file the user tuned on their desktop changes
            // this app as well, which is the stated intent of `PiPalette.kt`.
            PiTheme(
                dark = theme.dark,
                palette = theme.palette,
                textScaleDelta = uiState.prefs.fontScaleDelta,
            ) {
                PiRoot(isDark = theme.dark)
            }
        }
    }
}
