package app.pi.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pi.runtime.PtyLauncher
import app.pi.terminal.TerminalController
import app.pi.terminal.TerminalKeys
import app.pi.terminal.TerminalPalette
import kotlinx.coroutines.delay

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
 */
@Composable
fun TerminalPane(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isDark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val palette = remember(isDark) { if (isDark) TerminalPalette.dark() else TerminalPalette.light() }
    val store = remember(context, palette) { TerminalStore(context, palette) }

    var fontSize by remember { mutableStateOf(13f) }
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    var ctrlArmed by remember { mutableStateOf(false) }
    var showNewTabMenu by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val androidClipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    val active = store.active()
    // The guest's output is a `StateFlow` of revisions rather than a Compose
    // state; collecting it is what makes a repaint happen at all.
    val revision = active?.controller?.revision?.collectAsStateWithLifecycle()?.value ?: 0L

    // OSC 52: a program — or pi itself — asked for the clipboard. The emulator
    // cannot own the Android clipboard, so the pane drains those requests here.
    LaunchedEffect(active, revision) {
        val controller = active?.controller ?: return@LaunchedEffect
        while (true) {
            val text = controller.tryClipboardRequest() ?: break
            androidClipboard.setPrimaryClip(ClipData.newPlainText("pi terminal", text))
            statusText = "已复制到剪贴板（OSC 52）"
        }
    }

    LaunchedEffect(statusText) {
        if (statusText != null) {
            delay(2500)
            statusText = null
        }
    }

    DisposableEffect(store) {
        onDispose { store.closeAll() }
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

        val controller = active?.controller
        if (controller == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在启动终端…", color = palette.foreground)
            }
        } else {
            TerminalInputHost(
                controller = controller,
                ctrlArmed = ctrlArmed,
                onCtrlConsumed = { ctrlArmed = false },
                modifier = Modifier.weight(1f),
            ) { focus ->
                TerminalSurface(
                    controller = controller,
                    revision = revision,
                    fontSize = fontSize,
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onCopy = { text ->
                        if (text.isNotBlank()) {
                            androidClipboard.setPrimaryClip(ClipData.newPlainText("pi terminal", text))
                            statusText = "已选择复制"
                        }
                    },
                    onOpenLink = { url -> openLink(context, url) },
                    onTap = { focus() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        TerminalKeyBar(
            palette = palette,
            ctrlArmed = ctrlArmed,
            onCtrlToggle = { ctrlArmed = !ctrlArmed },
            onKey = { bytes ->
                active?.controller?.send(bytes)
                // Any key press disarms the sticky modifier, like a real keyboard.
                ctrlArmed = false
            },
            onFontSize = { fontSize = it },
            fontSize = fontSize,
            statusText = statusText,
        )
    }
}

/**
 * The tabs and their controllers.
 *
 * Held outside the composable so that a recomposition — including the one every
 * frame of guest output causes — cannot restart a process. Disposal closes every
 * session: the terminal's processes are deliberately tied to the screen, because a
 * hidden PTY would be a process the user cannot see or stop.
 */
class TerminalStore(
    private val context: Context,
    private val palette: TerminalPalette,
) {

    /** One open terminal. The controller is null until the tab is first shown. */
    data class Tab(
        val kind: PtyLauncher.Kind,
        val title: String,
        val command: String = "",
        val controller: TerminalController? = null,
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
        closing.controller?.close()
        tabs.removeAt(index)
        if (tabs.isEmpty()) tabs += Tab(PtyLauncher.Kind.Shell, "Shell")
        activeIndex = activeIndex.coerceIn(0, tabs.size - 1)
        ensureStarted(activeIndex)
    }

    fun restart() {
        val current = active() ?: return
        current.controller?.close()
        tabs[activeIndex] = current.copy(controller = null)
        ensureStarted(activeIndex)
    }

    fun closeAll() {
        tabs.forEach { it.controller?.close() }
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
        if (tab.controller != null) return
        val controller = TerminalController.open(
            context = context,
            kind = tab.kind,
            columns = DEFAULT_COLUMNS,
            rows = DEFAULT_ROWS,
            palette = palette,
            command = tab.command,
        )
        tabs[index] = tab.copy(controller = controller)
    }
}

/**
 * The hidden input field.
 *
 * A soft keyboard can only be summoned by a focused text field, and an IME can only
 * deliver composed text (CJK, emoji, autocorrect) to one. So there is an
 * almost-invisible field whose value is always reset to empty: every keystroke
 * produces an `onValueChange` with the newly committed text, which goes straight to
 * the pty. Nothing is ever rendered from the field — the terminal grid is the
 * display, and the pty echoes what the user typed.
 */
@Composable
private fun TerminalInputHost(
    controller: TerminalController,
    ctrlArmed: Boolean,
    onCtrlConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (focus: () -> Unit) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun focus() {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    Box(modifier) {
        content { focus() }
        BasicTextField(
            value = TextFieldValue(""),
            onValueChange = { value ->
                val text = value.text
                if (text.isEmpty()) return@BasicTextField
                controller.send(if (ctrlArmed) TerminalKeys.applyControl(text) else text)
                if (ctrlArmed) onCtrlConsumed()
            },
            modifier = Modifier
                .width(1.dp)
                .height(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event -> handleHardwareKey(event, controller) },
            textStyle = TextStyle(
                color = Color.Transparent,
                fontSize = 1.sp,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}

/**
 * Hardware (and IME) key events.
 *
 * Returns true only for keys this turned into terminal bytes, so the field still
 * receives printable text and the system still receives shortcuts we do not claim.
 * Ctrl/Alt combinations and non-printing keys are consumed here; a bare letter is
 * left to the IME path above, which is where composed text comes from.
 */
private fun handleHardwareKey(event: KeyEvent, controller: TerminalController): Boolean {
    if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) return false
    val native = event.nativeKeyEvent
    val modified = native.isCtrlPressed || native.isAltPressed
    val special = native.unicodeChar == 0 || native.unicodeChar < 0x20
    if (!modified && !special) return false
    val encoded = TerminalKeys.encode(native) ?: return false
    controller.send(encoded)
    return true
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
                        if (selected) palette.selection else Color.Transparent,
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

private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 26

private fun Color.luminanceIsDark(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f

/**
 * Follow an OSC 8 hyperlink.
 *
 * Only schemes a user can meaningfully act on are handed to the system. An
 * `intent:` or `file:` URL printed by anything running in the guest would
 * otherwise be a way to drive the phone's app launcher from the terminal.
 */
private fun openLink(context: Context, url: String) {
    val scheme = url.substringBefore(':').lowercase()
    if (scheme !in setOf("http", "https", "mailto", "tel")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
