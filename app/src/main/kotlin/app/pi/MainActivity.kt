package app.pi

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
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

            // The system bars must follow **pi's** theme, not the platform's.
            //
            // `enableEdgeToEdge()` on its own decides both bars from the *system*
            // night mode, and for the navigation bar it paints AndroidX's own
            // scrim: `SystemBarStyle.auto(DefaultLightScrim, DefaultDarkScrim)`,
            // where the light scrim is `0xe6FFFFFF` — 90 % white. This app's
            // colours do not come from the system, they come from pi's theme
            // setting (`PiPalette.kt`), so a phone in light mode showing a dark
            // pi theme got a white navigation bar under a black app: the bar read
            // as a separate white strip, which is exactly what the user reported
            // ("黑白对撞了都… 现在为什么底部是白条"). The status bar has the same
            // problem in the other direction: its *icons* were chosen from the
            // system mode, so light mode would have drawn dark icons on this
            // app's dark top bar.
            //
            // Re-applying both styles with `detectDarkMode` reading the resolved
            // pi theme keeps everything else AndroidX does (the scrim values, the
            // API-level fallbacks, the contrast-enforcement flags the XML theme
            // already turns off) and corrects only the source of truth. The
            // status bar keeps transparent/transparent because the app draws its
            // own `surfaceDim` edge-to-edge behind it.
            LaunchedEffect(theme.dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) {
                        theme.dark
                    },
                    navigationBarStyle = SystemBarStyle.auto(NAV_LIGHT_SCRIM, NAV_DARK_SCRIM) {
                        theme.dark
                    },
                )
            }

            // The palette is the resolved pi theme, not two hand-written
            // constants: a theme file the user tuned on their desktop changes
            // this app as well, which is the stated intent of `PiPalette.kt`.
            //
            // The window's own ground is re-pointed at that palette's page colour
            // here. `res/values/colors.xml` (day) and `res/values-night/colors.xml`
            // (night) can only carry the *system* polarity's page, because at the
            // moment the starting window is drawn nothing has read pi's `theme`
            // setting yet. Once it has been resolved, a pi theme pinned to the
            // opposite polarity would otherwise leave the window ground on the
            // wrong side of the hand-over for every later frame the system draws
            // (a resize, an activity recreate). The colour is opaque, so this
            // cannot leak a previous frame through.
            LaunchedEffect(theme.palette.pageBg) {
                window.setBackgroundDrawable(ColorDrawable(theme.palette.pageBg.toArgb()))
            }

            PiTheme(
                dark = theme.dark,
                palette = theme.palette,
                textScaleDelta = uiState.prefs.fontScaleDelta,
            ) {
                PiRoot()
            }
        }
    }
}

/**
 * AndroidX's own navigation-bar scrim values (`EdgeToEdge.kt`'s
 * `DefaultLightScrim`/`DefaultDarkScrim`), repeated here because re-applying the
 * styles with our own `detectDarkMode` means supplying both scrims ourselves.
 * The numbers are not ours to choose: they are what `enableEdgeToEdge()` paints
 * when left alone, and keeping them is what makes this a fix of the *source of
 * truth* rather than a restyle of the system bars.
 */
private val NAV_LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NAV_DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
