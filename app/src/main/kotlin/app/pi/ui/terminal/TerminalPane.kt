package app.pi.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pi.runtime.PtyLauncher
import kotlinx.coroutines.delay
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

/**
 * The Workbench terminal: real tabs, a real PTY, and the original pi TUI.
 *
 * This is the app's only intentional terminal surface, and the reason it exists is
 * narrow but load-bearing. A handful of pi extension APIs draw terminal cells
 * (`ctx.ui.custom()`, overlays, custom footers, `registerMessageRenderer`,
 * `renderCall`/`renderResult`) and are inert anywhere but pi's own TUI. Running the
 * unmodified TUI here is what keeps the "100% compatible" claim honest
 * (docs/pi-android-app-design.md §20.4, docs/pi-android-ui-spec.md §5.3).
 *
 * The three tab types are therefore not decoration — they are the three ways to
 * reach that: a guest shell, the original pi TUI, and an arbitrary command.
 *
 * ## What changed, and what did not
 *
 * What is drawn between the tab strip and the key bar is now
 * `org.connectbot:termlib` — libvterm behind JNI, presented as a Compose
 * component — instead of this app's own VT parser and canvas renderer. The
 * library also brings what the old surface had to hand-roll: text selection with
 * a magnifier, pinch-zoom, scrolling, and the soft-keyboard IME path.
 *
 * The shell around it is unchanged on purpose: the tab strip, the new-tab menu,
 * the key bar's position, and the `app.terminal.*` settings all stay where the
 * ui-spec puts them. [TerminalBridge] is the only new seam; it wires the library
 * to `PtyLauncher`, which still owns the PTY.
 */
@Composable
fun TerminalPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val palette = remember(isDark) { if (isDark) TerminalPalette.dark() else TerminalPalette.light() }
    // One store for the `app.terminal.*` settings, built from the same agent dir
    // and workspace the Settings screen writes through. Reads happen once per
    // Workbench composition; the keys the user edits are `EffectiveKind.Reload`
    // rows, so re-entering the tab is the documented way to apply them.
    val settingsStore = remember(context) { terminalSettingsStore(context) }
    val preferences = remember(settingsStore) { TerminalPreferences.read(settingsStore) }

    val androidClipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    var statusText by remember { mutableStateOf<String?>(null) }

    // The one place an OSC 52 request from the guest becomes an Android
    // clipboard write. The library decodes the sequence and calls this on the
    // main looper (it posts its OSC handling there), so touching the clipboard
    // here is safe.
    val store = remember(context, palette) {
        TerminalStore(context, palette) { text ->
            androidClipboard.setPrimaryClip(ClipData.newPlainText("pi terminal", text))
            statusText = "已复制到剪贴板（OSC 52）"
        }
    }

    var ctrlArmed by remember { mutableStateOf(false) }
    var altArmed by remember { mutableStateOf(false) }
    var showNewTabMenu by remember { mutableStateOf(false) }

    val bridge = store.active()?.bridge

    LaunchedEffect(statusText) {
        if (statusText != null) {
            delay(2500)
            statusText = null
        }
    }

    DisposableEffect(store) {
        onDispose { store.closeAll() }
    }

    // The sticky `ctrl`/`alt` chips, handed to the library so that the *soft*
    // keyboard respects them too: the library's key handler combines these with
    // the hardware modifiers and calls clearTransients() after each key, which is
    // exactly one-shot behaviour.
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

    Column(modifier.fillMaxSize().background(palette.background)) {
        TerminalTabBar(
            tabs = store.tabs,
            activeIndex = store.activeIndex,
            onSelect = { store.select(it) },
            onClose = { store.close(it) },
            onAdd = { showNewTabMenu = true },
            onRestart = { store.restart() },
            palette = palette,
        ) {
            DropdownMenu(expanded = showNewTabMenu, onDismissRequest = { showNewTabMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Shell（guest bash）") },
                    onClick = {
                        showNewTabMenu = false
                        store.open(PtyLauncher.Kind.Shell, "Shell")
                    },
                )
                DropdownMenuItem(
                    text = { Text("pi TUI（原版）") },
                    onClick = {
                        showNewTabMenu = false
                        store.open(PtyLauncher.Kind.PiTui, "pi TUI")
                    },
                )
                listOf("apt update", "git status", "node -v").forEach { command ->
                    DropdownMenuItem(
                        text = { Text("命令：$command") },
                        onClick = {
                            showNewTabMenu = false
                            store.open(PtyLauncher.Kind.Custom, command, command)
                        },
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            if (bridge == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("正在启动终端…", color = palette.foreground)
                }
            } else {
                TerminalSurface(
                    bridge = bridge,
                    preferences = preferences,
                    palette = palette,
                    modifierManager = modifierManager,
                )
            }
        }

        TerminalKeyBar(
            palette = palette,
            ctrlArmed = ctrlArmed,
            altArmed = altArmed,
            statusText = statusText,
            rows = preferences.keyBar,
            onKey = { key ->
                val sticky = key.sticky
                if (sticky != null) {
                    when (sticky) {
                        StickyModifier.Ctrl -> ctrlArmed = !ctrlArmed
                        StickyModifier.Alt -> altArmed = !altArmed
                    }
                } else {
                    val emulator = bridge?.emulator
                    if (emulator != null) {
                        val mods = (if (ctrlArmed) MOD_CTRL else 0) or (if (altArmed) MOD_ALT else 0)
                        val character = key.character
                        if (character != null) {
                            emulator.dispatchCharacter(mods, character)
                        } else if (key.vtermKey != VTermKey.NONE) {
                            emulator.dispatchKey(mods, key.vtermKey)
                        }
                    }
                    // A sticky modifier applies to one key, exactly as it does on
                    // the library's own keyboard path.
                    ctrlArmed = false
                    altArmed = false
                }
            },
            onPaste = { bridge?.paste(readClipboard(context, androidClipboard)) },
        )
    }
}

/**
 * The library's Compose component, plus the one correction it needs.
 *
 * `forcedSize` keeps the emulator's grid at the size `PtyLauncher` pinned, which
 * is the whole reason the guest's full-screen TUI lines up: `script(1)` cannot
 * resize the pty it created, so the guest's grid is frozen at spawn time and an
 * emulator that reflowed to the view would paint a grid pi never laid out.
 *
 * The correction is [onSizeChanged]. With `forcedSize` set, the library computes
 * the font size to fit that frozen grid — but it *also* resizes the emulator from
 * the view size on every layout change, and its own re-assert is inside a
 * `LaunchedEffect` keyed on the forced dimensions, which do not change. So after
 * a rotation nothing would put the grid back. Re-asserting it here is idempotent
 * and runs after the library's own resize, because a parent's layout callback
 * follows its children's.
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
    val (rows, columns) = bridge.fixedSize

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { emulator.resize(rows, columns) },
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
            forcedSize = bridge.fixedSize,
            modifierManager = modifierManager,
        )
    }
}

/**
 * The tabs and their bridges.
 *
 * Held outside the composable so that a recomposition cannot restart a process.
 * Disposal closes every session: the terminal's processes are deliberately tied to
 * the screen, because a hidden PTY would be a process the user cannot see or stop.
 */
class TerminalStore(
    private val context: Context,
    private val palette: TerminalPalette,
    /** Where a guest OSC 52 request goes; see [TerminalBridge]. */
    private val onClipboardCopy: (String) -> Unit,
) {

    /** One open terminal. The bridge is null until the tab is first shown. */
    data class Tab(
        val kind: PtyLauncher.Kind,
        val title: String,
        val command: String = "",
        val bridge: TerminalBridge? = null,
    )

    val tabs: SnapshotStateList<Tab> = mutableStateListOf(Tab(PtyLauncher.Kind.Shell, "Shell"))

    var activeIndex by mutableStateOf(0)
        private set

    fun active(): Tab? = tabs.getOrNull(activeIndex)

    fun select(index: Int) {
        activeIndex = index.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
        ensureStarted(activeIndex)
    }

    fun open(kind: PtyLauncher.Kind, title: String, command: String = "") {
        tabs += Tab(kind, title, command)
        activeIndex = tabs.size - 1
        ensureStarted(activeIndex)
    }

    fun close(index: Int) {
        val closing = tabs.getOrNull(index) ?: return
        closing.bridge?.close()
        tabs.removeAt(index)
        if (tabs.isEmpty()) tabs += Tab(PtyLauncher.Kind.Shell, "Shell")
        activeIndex = activeIndex.coerceIn(0, tabs.size - 1)
        ensureStarted(activeIndex)
    }

    fun restart() {
        val current = active() ?: return
        current.bridge?.close()
        tabs[activeIndex] = current.copy(bridge = null)
        ensureStarted(activeIndex)
    }

    fun closeAll() {
        tabs.forEach { it.bridge?.close() }
        tabs.clear()
    }

    /**
     * Start the tab's process the first time it is shown.
     *
     * Lazy on purpose: opening five tabs must not spawn five proot trees, and a tab
     * the user never looks at should cost nothing.
     */
    private fun ensureStarted(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (tab.bridge != null) return
        val bridge = TerminalBridge.open(
            context = context,
            kind = tab.kind,
            columns = DEFAULT_COLUMNS,
            rows = DEFAULT_ROWS,
            palette = palette,
            onClipboardCopy = onClipboardCopy,
            command = tab.command,
        )
        tabs[index] = tab.copy(bridge = bridge)
    }
}

/** The tab strip: closable tabs, a restart action, and the new-tab menu. */
@Composable
private fun TerminalTabBar(
    tabs: List<TerminalStore.Tab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: () -> Unit,
    onRestart: () -> Unit,
    palette: TerminalPalette,
    menu: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == activeIndex
            Row(
                Modifier
                    .padding(vertical = 4.dp, horizontal = 2.dp)
                    .background(
                        if (selected) palette.chipArmed else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tab.title,
                    color = palette.foreground,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .clickable(interactionSource = null, indication = null) { onSelect(index) },
                )
                IconButton(onClick = { onClose(index) }, modifier = Modifier.width(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭标签", tint = palette.foreground)
                }
            }
        }
        IconButton(onClick = onRestart) {
            Icon(Icons.Filled.Refresh, contentDescription = "重启标签", tint = palette.foreground)
        }
        Box {
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "新建标签", tint = palette.foreground)
            }
            menu()
        }
    }
}

/**
 * The clipboard, or an empty string when the platform refuses to hand it over.
 *
 * The context is passed through rather than nulled: `coerceToText` needs it to
 * resolve an `Intent` or a `content:` URI, which is exactly the case a copied
 * link or rich text arrives as.
 */
private fun readClipboard(context: Context, clipboard: ClipboardManager): String =
    runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() }
        .getOrDefault("")

private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 26

/** libvterm's modifier bits, as `dispatchKey`/`dispatchCharacter` take them. */
private const val MOD_ALT = 2
private const val MOD_CTRL = 4

private fun Color.luminanceIsDark(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f
