package app.pi.ui.terminal

import android.content.Context
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import app.pi.settings.PiSettingsFileStore
import app.pi.ui.settings.PiSettingsCatalog
import app.pi.ui.settings.PiSettingsStore
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `app.terminal.*` settings, read from the same documents the Settings screen
 * writes.
 *
 * `audit: docs/gap-disposition.md` row #55 recorded these as registered-but-dead:
 * each key occurred exactly once in the tree — in `PiSettingsRegistry.kt` — so the
 * terminal always used the hard-coded values. This file is the consumer, and it
 * has deliberately shrunk: the engine swap moved three of the four rows' worth of
 * behaviour into `org.connectbot:termlib` and the libvterm library underneath it,
 * and pretending otherwise would be worse than saying so.
 *
 * **Still consumed here:**
 *  - `app.terminal.fontSize` — passed as the terminal component's
 *    `initialFontSize`, and it is also the font size `TerminalPane`'s
 *    `terminalGrid` measures the *initial* grid with. It is therefore only the
 *    starting point twice over: the component is given that grid as `forcedSize`
 *    (see [TerminalBridge.pinnedSize]) and computes the font that fits it, and the
 *    runtime control on top is the component's own pinch-zoom.
 *  - `app.terminal.keyBar` — the chip list, resolved against
 *    [TerminalBarKey.catalog]. Names the old bar used (`pipe`, `tilde`) still
 *    resolve, so a stored list keeps working.
 *
 * **Now owned by the engine, and therefore not read any more:**
 *  - `app.terminal.scrollbackLines` — libvterm's transcript is the engine's, and
 *    the component keeps a fixed 1000 lines.
 *  - `app.terminal.cursorStyle` — cursor shape is whatever the guest asks for
 *    through `DECSCUSR`, which libvterm honours.
 *
 * Those two rows are no longer in `PiSettingsRegistry.kt`: the registry audit in
 * this pass removed them, so there is nothing left here to keep in sync. The two
 * keys **still read below** lost their rows too (用户裁定：`终端与 Shell` 整组删除，
 * 终端页退役) — a hand-written `settings.json` value keeps working, but `设置` no
 * longer has a row for them, so this file owns their default values now (see
 * [TerminalPreferences]).
 *
 * **Same store, not a second one.** The paths are built from the same two
 * canonical resolvers the rest of the app uses — `PiPaths` for the agent dir (as
 * `PiSessionViewModel.settingsStore` does) and `PtyLauncher.workspaceHost` for the
 * workspace, whose own contract is "a terminal tab and a chat session look at one
 * directory". Re-deriving `workspace-1` here would have been a second truth about
 * which file the user just edited.
 *
 * Since the workspace is user-selectable, "the workspace" in that sentence means
 * the **current** one: `PtyLauncher.workspaceHost` resolves it from
 * `WorkspaceStore`, so the project document this store reads
 * (`<workspace>/.pi/settings.json`, via `PiSettingsFileStore.forWorkspace`) is the
 * one belonging to the workspace the engine is in. A terminal tab opened after a
 * switch sees the new workspace's project settings; the tab is re-entered rather
 * than hot-swapped, which is also why no rebuild hook is needed here.
 *
 * Reads happen once per composition of the Workbench screen; the tab is re-entered
 * rather than hot-swapped, so a changed value lands when the terminal page is next
 * opened.
 */
internal fun terminalSettingsStore(context: Context): PiSettingsStore {
    val app = context.applicationContext ?: context
    val paths = PiPaths(
        filesDir = app.filesDir,
        nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
    )
    return PiSettingsFileStore.forWorkspace(
        agentDir = paths.agentDir,
        workspace = PtyLauncher.workspaceHost(app),
    )
}

/**
 * Resolved values for one Workbench composition.
 *
 * Defaults come from the registry row when there is one (`PiSettingsCatalog.byKey`),
 * and from the constants in this file when there is not: `app.terminal.fontSize` /
 * `app.terminal.keyBar` lost their rows with the 终端与 Shell group, and a missing
 * row used to mean `fallback = 0.0` — a terminal that measured its grid with a 0sp
 * font. The numbers below are that row's declared default (`num(13)`) and the bar's
 * own [TerminalBarKey.defaultRows], so a stored value still wins over either.
 */
internal data class TerminalPreferences(
    val fontSize: Float,
    val keyBar: List<List<TerminalBarKey>>,
) {
    companion object {
        /** 已删除的 `app.terminal.fontSize` 行的默认值（当年注册表里写的就是这个数）。 */
        private const val DEFAULT_FONT_SIZE = 13.0

        fun read(store: PiSettingsStore): TerminalPreferences = TerminalPreferences(
            fontSize = number(store, "app.terminal.fontSize", DEFAULT_FONT_SIZE).toFloat(),
            keyBar = keyBarOf(store),
        )

        /**
         * The stored value, else the registry row's declared default, else [fallback],
         * clamped to the row's declared range when the row exists.
         */
        private fun number(store: PiSettingsStore, key: String, fallback: Double): Number {
            val setting = PiSettingsCatalog.byKey[key]
            val stored = (store.read(key) as? JsonPrimitive)?.content?.toDoubleOrNull()
            val declared = (setting?.defaultValue as? JsonPrimitive)?.content?.toDoubleOrNull()
            val raw = stored ?: declared ?: fallback
            return raw
                .coerceAtLeast(setting?.min?.toDouble() ?: raw)
                .coerceAtMost(setting?.max?.toDouble() ?: raw)
        }

        /**
         * Map the setting's names onto the chips the bar can draw.
         *
         * The registry's presets use portable names while the bar labels the keys
         * with glyphs, so both spellings resolve. Unknown names are dropped rather
         * than turned into a broken chip, and an empty result falls back to the
         * default bar — an empty key bar on a phone would leave no way to type
         * `esc` or reach the arrow keys.
         */
        private fun keyBarOf(store: PiSettingsStore): List<List<TerminalBarKey>> {
            val names = (store.read("app.terminal.keyBar") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: return TerminalBarKey.defaultRows
            if (names.isEmpty()) return TerminalBarKey.defaultRows
            val resolved = names.mapNotNull { name ->
                TerminalBarKey.byId(ALIASES[name.lowercase()] ?: name)
            }
            if (resolved.isEmpty()) return TerminalBarKey.defaultRows
            // A stored list is flat, but the bar is a grid: chunk it back into
            // the default layout's row length so a custom bar keeps its shape.
            return resolved.chunked(KEY_BAR_ROW_SIZE)
        }

        private val ALIASES: Map<String, String> = mapOf(
            "escape" to "esc",
            "pageup" to "pgup",
            "pagedown" to "pgdn",
            "slash" to "slash",
            "dash" to "dash",
        )

        /** Termux's default layout is two rows of seven; see [TerminalBarKey.defaultRows]. */
        private const val KEY_BAR_ROW_SIZE = 7
    }
}
