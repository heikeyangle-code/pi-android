package app.pi.ui.terminal

import android.content.Context
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import app.pi.settings.PiSettingsFileStore
import app.pi.terminal.TerminalKeys
import app.pi.ui.settings.PiSettingsCatalog
import app.pi.ui.settings.PiSettingsStore
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The four `app.terminal.*` settings, read from the same documents the Settings
 * screen writes.
 *
 * `audit: docs/gap-disposition.md` row #55 recorded these as registered-but-dead:
 * each key occurred exactly once in the tree — in `PiSettingsRegistry.kt` — so the
 * terminal always used the hard-coded values (`fontSize = 13f` at
 * `TerminalPane.kt:84`, the emulator's default `historyLimit = 2000`, one cursor
 * shape, and `TerminalKeys.toolbarKeys` verbatim). This file is the missing
 * consumer.
 *
 * **Same store, not a second one.** The paths are built from the same two
 * canonical resolvers the rest of the app uses — `PiPaths` for the agent dir (as
 * `PiSessionViewModel.settingsStore` does) and `PtyLauncher.workspaceHost` for the
 * workspace, whose own contract is "a terminal tab and a chat session look at one
 * directory" (`PtyLauncher.kt:260-262`). Re-deriving `workspace-1` here would have
 * been a second truth about which file the user just edited.
 *
 * Reads happen once per composition of the Workbench screen, and `app.terminal.*`
 * commands such as key bar changes are documented as needing a reload; the tab is
 * re-entered rather than hot-swapped, which matches `EffectiveKind.Reload` on the
 * key-bar row.
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

/** The cursor drawing the user picked. `DECSCUSR` from the guest is still not honoured; this is the app-side preference only. Public because `TerminalSurface` is a public composable and takes it as a parameter. */
enum class TerminalCursorStyle { Block, Bar, Underline }

/**
 * Resolved values for one Workbench composition.
 *
 * Defaults are read from the registry (`PiSettingsCatalog.byKey`) rather than
 * repeated here, so the Settings screen and the terminal cannot drift apart about
 * what "unset" means.
 */
internal data class TerminalPreferences(
    val fontSize: Float,
    val scrollbackLines: Int,
    val cursorStyle: TerminalCursorStyle,
    val keyBar: List<TerminalKeys.ToolbarKey>,
) {
    companion object {
        fun read(store: PiSettingsStore): TerminalPreferences = TerminalPreferences(
            fontSize = number(store, "app.terminal.fontSize").toFloat(),
            scrollbackLines = number(store, "app.terminal.scrollbackLines").toInt(),
            cursorStyle = cursorStyleOf(string(store, "app.terminal.cursorStyle")),
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

        private fun string(store: PiSettingsStore, key: String): String? =
            (store.read(key) as? JsonPrimitive)?.content

        private fun cursorStyleOf(wire: String?): TerminalCursorStyle = when (wire) {
            "bar" -> TerminalCursorStyle.Bar
            "underline" -> TerminalCursorStyle.Underline
            else -> TerminalCursorStyle.Block
        }

        /**
         * Map the setting's names onto the chips the bar can draw.
         *
         * The registry's presets use portable names (`up`, `pipe`, `tilde`) while
         * the bar labels the same keys with glyphs, so both spellings resolve.
         * Unknown names are dropped rather than turned into a broken chip, and an
         * empty result falls back to the full default bar — an empty key bar on a
         * phone would leave no way to type `esc` or reach the font control.
         */
        private fun keyBarOf(store: PiSettingsStore): List<TerminalKeys.ToolbarKey> {
            val names = (store.read("app.terminal.keyBar") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: return TerminalKeys.toolbarKeys
            if (names.isEmpty()) return TerminalKeys.toolbarKeys
            val byLabel = TerminalKeys.toolbarKeys.associateBy { it.label }
            val resolved = names.mapNotNull { name ->
                byLabel[name] ?: byLabel[ALIASES[name].orEmpty()]
            }
            return resolved.ifEmpty { TerminalKeys.toolbarKeys }
        }

        private val ALIASES: Map<String, String> = mapOf(
            "up" to "↑",
            "down" to "↓",
            "left" to "←",
            "right" to "→",
            "pipe" to "|",
            "tilde" to "~",
            "slash" to "/",
            "dash" to "-",
            "escape" to "esc",
        )
    }
}
