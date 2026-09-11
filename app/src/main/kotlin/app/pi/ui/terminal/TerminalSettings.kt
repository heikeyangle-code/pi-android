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
 *    `initialFontSize`. Note it is now only the *starting* size: the component is
 *    given a forced grid (see [TerminalBridge.fixedSize]) and computes the font
 *    that fits it, so the runtime control is the component's own pinch-zoom.
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
 * Those two rows still exist in `PiSettingsRegistry.kt` (out of this change's
 * scope) and should either gain an engine-side knob or be removed there; they are
 * dead until then, and this comment is the pointer for whoever does it.
 *
 * **Same store, not a second one.** The paths are built from the same two
 * canonical resolvers the rest of the app uses — `PiPaths` for the agent dir (as
 * `PiSessionViewModel.settingsStore` does) and `PtyLauncher.workspaceHost` for the
 * workspace, whose own contract is "a terminal tab and a chat session look at one
 * directory". Re-deriving `workspace-1` here would have been a second truth about
 * which file the user just edited.
 *
 * Reads happen once per composition of the Workbench screen, and `app.terminal.*`
 * commands are documented as needing a reload; the tab is re-entered rather than
 * hot-swapped, which matches `EffectiveKind.Reload` on the key-bar row.
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
 * Defaults are read from the registry (`PiSettingsCatalog.byKey`) rather than
 * repeated here, so the Settings screen and the terminal cannot drift apart about
 * what "unset" means.
 */
internal data class TerminalPreferences(
    val fontSize: Float,
    val keyBar: List<List<TerminalBarKey>>,
) {
    companion object {
        fun read(store: PiSettingsStore): TerminalPreferences = TerminalPreferences(
            fontSize = number(store, "app.terminal.fontSize").toFloat(),
            keyBar = keyBarOf(store),
        )

        /** The registry's value if set, else its declared default, clamped to its declared range. */
        private fun number(store: PiSettingsStore, key: String): Number {
            val setting = PiSettingsCatalog.byKey[key]
            val stored = (store.read(key) as? JsonPrimitive)?.content?.toDoubleOrNull()
            val fallback = (setting?.defaultValue as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
            val raw = stored ?: fallback
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
