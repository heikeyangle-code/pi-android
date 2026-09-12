package app.pi.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import app.pi.runtime.PtyLauncher
import kotlinx.coroutines.delay
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

/**
 * The Workbench terminal: one real terminal, running a real shell in the guest.
 *
 * ## What this page is
 *
 * A terminal. Not a launcher, not a tab strip, not a pi surface: it opens an
 * interactive `bash` in the guest and everything else is typed into it. `pi` is on
 * `PATH` (`/usr/local/bin/pi`), so `pi` reaches the original TUI — but this page
 * never starts it on the user's behalf, because a terminal that boots into somebody
 * else's full-screen program is not a terminal.
 *
 * That is the whole reason [PtyLauncher] is used instead of a pipe: `script(1)`
 * allocates a real pty inside the guest, so `process.stdin.isTTY` is true, raw mode
 * works, `Ctrl+C` becomes `SIGINT` through the line discipline, and any full-screen
 * program the user starts behaves as it does on a desktop. See [PtyLauncher]'s KDoc
 * for the mechanism and its one real cost.
 *
 * ## Why the grid is measured here, before the process starts
 *
 * Inside the guest the pty's window size is written once, at spawn, by `stty` plus
 * `COLUMNS`/`LINES` — `script(1)` cannot resize the pty it created. The terminal
 * component is therefore given that same grid as its `forcedSize`, so the emulator
 * and the guest cannot disagree about it.
 *
 * The consequence is that the grid has to be chosen *at spawn time*, and that it has
 * to be a grid which **fits the area it will be drawn in**, because the component
 * picks the font size by fitting the whole grid into the view. Both halves matter:
 *
 *  - A hard-coded `80x26` — what this file used to pass — is a grid with a 3.1
 *    aspect ratio. Fitting 80 columns into a phone's width forces the font down to a
 *    few sp, and 26 rows of it then fill less than half the height: the terminal came
 *    out as a thin band above a large empty area, which is what it looked like on a
 *    device.
 *  - Sizing the emulator from the view instead (no `forcedSize`) fills the screen
 *    but silently desyncs it from the guest: the pty stays at its spawn size while
 *    the emulator reflows on every keyboard transition and rotation, and
 *    full-screen programs then draw into a grid their host does not agree with.
 *
 * [terminalGrid] resolves both at once: it reproduces the component's own cell
 * metrics, so the grid it returns is the grid the component would have chosen for
 * the measured area — and that grid is then *frozen* for the life of the process. A
 * later area change (the soft keyboard, a rotation) leaves a margin rather than
 * reflowing a grid the guest cannot follow.
 *
 * ## Ownership
 *
 * The bridge — and with it the guest process — belongs to this composable: leaving
 * the Workbench closes it. A pty nobody is looking at is a process the user cannot
 * see and cannot stop.
 */
@Composable
fun TerminalPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val palette = remember(isDark) { if (isDark) TerminalPalette.dark() else TerminalPalette.light() }
    // The `app.terminal.*` settings, read from the same documents the Settings
    // screen writes. A read happens once per Workbench composition, and the keys
    // the user edits are `EffectiveKind.Reload` rows, so re-entering the tab is the
    // documented way to apply them.
    val settingsStore = remember(context) { terminalSettingsStore(context) }
    val preferences = remember(settingsStore) { TerminalPreferences.read(settingsStore) }

    val androidClipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var statusText by remember { mutableStateOf<String?>(null) }

    // Restart is a generation counter rather than a method on the bridge: bumping it
    // makes the `remember` below build a fresh bridge, and the `DisposableEffect` on
    // the old one closes its process. That is also the only way to rebuild the
    // emulator, which a wedged guest needs as much as a new process does.
    var generation by remember { mutableStateOf(0) }
    val bridge = remember(context, palette, generation) {
        TerminalBridge.open(
            context = context,
            // Placeholders, replaced by the measured grid in [TerminalSurface],
            // which is what `start` pins the guest's pty to. They only decide the
            // emulator's size for the one frame before that measurement exists.
            rows = PLACEHOLDER_ROWS,
            columns = PLACEHOLDER_COLUMNS,
            palette = palette,
            onClipboardCopy = { text ->
                // The library decodes OSC 52 and posts this to the main looper, so
                // touching the Android clipboard here is safe.
                androidClipboard.setPrimaryClip(ClipData.newPlainText("pi terminal", text))
                statusText = "已复制到剪贴板（OSC 52）"
            },
        )
    }

    DisposableEffect(bridge) {
        onDispose { bridge.close() }
    }

    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }

    LaunchedEffect(statusText) {
        if (statusText != null) {
            delay(2500)
            statusText = null
        }
    }

    // The sticky `ctrl`/`alt` chips, handed to the library so that the *soft*
    // keyboard respects them too: the library's key handler combines these with the
    // hardware modifiers and calls clearTransients() after each key, which is
    // exactly one-shot behaviour. This is what makes `Ctrl+C` reachable from a phone
    // keyboard at all — the chord is the sticky chip plus a letter from the IME.
    val modifierManager = remember(ctrlArmed, altArmed) {
        object : ModifierManager {
            override fun isCtrlActive(): Boolean = ctrlArmed
            override fun isAltActive(): Boolean = altArmed
            override fun isShiftActive(): Boolean = false

            override fun clearTransients() {
                ctrlArmed = false
                altArmed = false
            }
        }
    }

    // The IME is handled one level up, by the `Scaffold` in `PiRoot.kt`: it is the
    // only place that can lift the bottom bar as well, and doing it here *as well*
    // would pad twice (insets are not consumed by a parent's `imePadding()`), which
    // would push the key bar off the top of the shrunken area.
    //
    // What nothing here can do is reflow the guest: the pty's grid was pinned at
    // spawn and cannot be resized (`script(1)` has no way to set it), so the
    // component reacts to the smaller area by fitting the same grid into it. That is
    // the documented cost of the frozen grid — see the KDoc above — and it is
    // visible as a font that shrinks while the keyboard is up rather than one that
    // scrolls.
    Column(modifier.fillMaxSize().background(palette.background)) {
        Box(Modifier.weight(1f)) {
            TerminalSurface(
                bridge = bridge,
                preferences = preferences,
                palette = palette,
                modifierManager = modifierManager,
            )
        }

        TerminalKeyBar(
            palette = palette,
            ctrlArmed = ctrlArmed,
            altArmed = altArmed,
            // The guest's own title (`OSC 0/1/2`) is deliberately not shown: with no
            // tab strip there is nothing for it to label. What this line carries is
            // the two things the user cannot otherwise see — that an OSC 52 copy
            // happened, and that the guest itself failed to start.
            statusText = statusText ?: bridge.lastError?.let { "终端未能启动：$it" },
            rows = preferences.keyBar,
            onKey = { key ->
                val sticky = key.sticky
                if (sticky != null) {
                    when (sticky) {
                        StickyModifier.Ctrl -> ctrlArmed = !ctrlArmed
                        StickyModifier.Alt -> altArmed = !altArmed
                    }
                } else {
                    val emulator = bridge.emulator
                    // Sticky state is what the user armed for *this* key; a chip's
                    // own bits (the `^C`/`^D` chords) are what the chip always is.
                    val mods = (if (ctrlArmed) MOD_CTRL else 0) or
                        (if (altArmed) MOD_ALT else 0) or
                        key.modifiers
                    val character = key.character
                    if (character != null) {
                        // libvterm turns a printable code point plus the Ctrl bit into
                        // the control byte, so this is `0x03` for `^C` — the same way
                        // the library's own keyboard handler encodes it.
                        emulator.dispatchCharacter(mods, character)
                    } else if (key.vtermKey != VTermKey.NONE) {
                        // Never a hard-coded `ESC [ A`: libvterm knows whether the
                        // program asked for application-cursor mode.
                        emulator.dispatchKey(mods, key.vtermKey)
                    }
                    // A sticky modifier applies to one key, exactly as it does on the
                    // library's own keyboard path.
                    ctrlArmed = false
                    altArmed = false
                }
            },
            onPaste = { bridge.paste(readClipboard(context, androidClipboard)) },
            onRestart = { generation++ },
        )
    }
}

/**
 * The library's Compose component, plus the two corrections it needs.
 *
 * `forcedSize` keeps the emulator's grid at the one the guest was pinned to, which
 * is the whole reason the guest's full-screen programs line up.
 *
 * The first correction is [onSizeChanged]. With `forcedSize` set, the component
 * *also* resizes the emulator from the view size on every layout change
 * (`Terminal.kt:581-585`), and its own re-assert to `forcedSize` sits inside a
 * `LaunchedEffect` keyed on the forced dimensions, which do not change
 * (`Terminal.kt:653`) — so after a rotation nothing would put the grid back.
 * Re-asserting it here is idempotent and runs after the component's own resize,
 * because a parent's layout callback follows its children's.
 *
 * The second is the grid itself: see `TerminalPane`'s KDoc for why it is measured
 * and then frozen instead of being derived from the view on every frame.
 */
@Composable
private fun TerminalSurface(
    bridge: TerminalBridge,
    preferences: TerminalPreferences,
    palette: TerminalPalette,
    modifierManager: ModifierManager,
) {
    val focusRequester = remember { FocusRequester() }
    val emulator = bridge.emulator

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // The component measures its cells from a paint at the font size it will
        // draw with (`Terminal.kt:466-481`), so reproducing that measurement here is
        // what makes the grid below the one it would have chosen itself.
        val fontPx = with(density) { preferences.fontSize.sp.toPx() }
        val measured = terminalGrid(
            widthPx = with(density) { maxWidth.toPx() },
            heightPx = with(density) { maxHeight.toPx() },
            fontSizePx = fontPx,
        )

        // Latched by `remember` with no key: the first measurement wins and is never
        // recomputed. Deliberate — the guest's pty is pinned to this grid at spawn
        // and `script(1)` cannot resize it, so a later change to the area must leave
        // a margin rather than move the emulator away from the guest.
        val pinned = remember { measured }

        // Starting is idempotent, so this re-runs usefully after a restart (a new
        // bridge) and is a no-op for the same bridge.
        LaunchedEffect(bridge, pinned) {
            bridge.start(rows = pinned.first, columns = pinned.second)
        }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { emulator.resize(pinned.first, pinned.second) },
        ) {
            Terminal(
                terminalEmulator = emulator,
                modifier = Modifier.fillMaxSize(),
                typeface = Typeface.MONOSPACE,
                initialFontSize = preferences.fontSize.sp,
                backgroundColor = palette.background,
                foregroundColor = palette.foreground,
                keyboardEnabled = true,
                showSoftKeyboard = true,
                focusRequester = focusRequester,
                forcedSize = pinned,
                modifierManager = modifierManager,
            )
        }
    }
}

/**
 * The cell grid that fits [widthPx] x [heightPx] at [fontSizePx], as `(rows, cols)`.
 *
 * The same arithmetic the terminal component does for itself: `Terminal.kt:472-481`
 * measures `"M"` and the ascent/descent spread, and its `charsPerDimension`
 * (`Terminal.kt:1586`) divides by them. It is reproduced here because the grid has
 * to exist *before* the guest process does, and the component cannot be asked for a
 * size it has not been laid out for yet.
 *
 * Monospace only, which is the only typeface a terminal is drawn in.
 *
 * The clamps are sanity bounds rather than display policy: a degenerate or
 * unbounded constraint must not be able to produce a grid that is then pinned into
 * a guest pty for the life of the session.
 */
private fun terminalGrid(
    widthPx: Float,
    heightPx: Float,
    fontSizePx: Float,
): Pair<Int, Int> {
    if (!widthPx.isFinite() || !heightPx.isFinite() || widthPx <= 0f || heightPx <= 0f) {
        return PLACEHOLDER_ROWS to PLACEHOLDER_COLUMNS
    }
    val paint = Paint().apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSizePx
        isAntiAlias = true
    }
    val cellWidth = paint.measureText("M")
    val metrics = paint.fontMetrics
    val cellHeight = metrics.descent - metrics.ascent
    if (cellWidth <= 0f || cellHeight <= 0f) {
        return PLACEHOLDER_ROWS to PLACEHOLDER_COLUMNS
    }
    val rows = (heightPx / cellHeight).toInt().coerceIn(MIN_ROWS, MAX_ROWS)
    val columns = (widthPx / cellWidth).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    return rows to columns
}

/**
 * The clipboard, or an empty string when the platform refuses to hand it over.
 *
 * The context is passed through rather than nulled: `coerceToText` needs it to
 * resolve an `Intent` or a `content:` URI, which is exactly the case a copied link
 * or rich text arrives as.
 */
private fun readClipboard(context: Context, clipboard: ClipboardManager): String =
    runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() }
        .getOrDefault("")

/** The emulator's size for the one frame before the area has been measured. */
private const val PLACEHOLDER_ROWS = 24
private const val PLACEHOLDER_COLUMNS = 80

private const val MIN_ROWS = 5
private const val MAX_ROWS = 300
private const val MIN_COLUMNS = 20
private const val MAX_COLUMNS = 500

private fun Color.luminanceIsDark(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f
