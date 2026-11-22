package app.pi.ui.terminal

import android.content.Context
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import app.pi.settings.PiSettingsFileStore
import app.pi.ui.settings.PiSettingsStore
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

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

internal data class TerminalPreferences(
    val fontSizeSp: Float,
    val keyBar: List<List<TerminalBarKey>>,
) {
    companion object {
        const val MIN_FONT_SP = 9f
        const val MAX_FONT_SP = 24f
        const val DEFAULT_FONT_SP = 13f

        fun read(store: PiSettingsStore): TerminalPreferences {
            val rawSize = (store.read("app.terminal.fontSize") as? JsonPrimitive)
                ?.content?.toFloatOrNull()
                ?: DEFAULT_FONT_SP
            return TerminalPreferences(
                fontSizeSp = rawSize.coerceIn(MIN_FONT_SP, MAX_FONT_SP),
                keyBar = keyBarOf(store),
            )
        }

        fun saveFontSize(store: PiSettingsStore, sizeSp: Float) {
            runCatching {
                store.write("app.terminal.fontSize", JsonPrimitive(sizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP).toDouble()))
            }
        }

        private fun keyBarOf(store: PiSettingsStore): List<List<TerminalBarKey>> {
            val names = (store.read("app.terminal.keyBar") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: return TerminalBarKey.defaultRows
            if (names.isEmpty()) return TerminalBarKey.defaultRows
            val resolved = names.mapNotNull { name ->
                TerminalBarKey.byId(ALIASES[name.lowercase()] ?: name)
            }
            if (resolved.isEmpty()) return TerminalBarKey.defaultRows
            return resolved.chunked(KEY_BAR_ROW_SIZE)
        }

        private val ALIASES: Map<String, String> = mapOf(
            "escape" to "esc",
            "pageup" to "pgup",
            "pagedown" to "pgdn",
            "slash" to "slash",
            "dash" to "dash",
        )

        private const val KEY_BAR_ROW_SIZE = 8
    }
}
