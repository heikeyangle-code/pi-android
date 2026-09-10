package app.pi.settings

import app.pi.rpc.PiJson
import app.pi.rpc.SettingsDocument
import app.pi.ui.settings.PiSettingsStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The real settings store: pi's own files, read and written in pi's own format.
 *
 * Layout and semantics are pi's (`core/settings-manager.ts`), verified against the
 * source rather than assumed:
 *
 *  - **Two documents.** Global is `<agentDir>/settings.json`; project is
 *    `<cwd>/.pi/settings.json`. pi merges global as the *base* and project as the
 *    *overrides*, recursively, so a project file only has to state what it
 *    changes.
 *  - **Arrays replace, objects merge.** pi's `isMergeableObject` explicitly
 *    excludes arrays, so `defaultTools` in a project file replaces the global
 *    list outright rather than appending to it.
 *  - **Sparse on purpose.** Omitting a key is how a user asks for pi's built-in
 *    default, so a missing key reads as null and the UI shows the default.
 *  - **Unknown keys are preserved.** pi does a plain `JSON.parse` with no schema
 *    validation, which is what lets app-only preferences live here harmlessly.
 *
 * Two deliberate improvements over pi, both about not destroying user data:
 *
 *  1. **Atomic writes.** pi calls `writeFileSync` directly, so a kill mid-write
 *     leaves a truncated settings file — and pi then fails to start. Here the
 *     document goes to a temp file and is renamed into place.
 *  2. **A corrupt file is never clobbered.** If the existing document cannot be
 *     parsed, it is moved aside with a timestamped suffix instead of being
 *     overwritten with the new value, so a mangled file stays recoverable.
 */
class PiSettingsFileStore(
    private val globalFile: File,
    private val projectFile: File? = null,
    /**
     * Keys that are **not** pi settings paths — a UI convenience like
     * `packages[].autoload` describes a field *inside* package entries, so
     * writing it as a top-level key would corrupt pi's document. Such keys are
     * kept in this separate file instead of being silently dropped.
     */
    private val appLocalFile: File? = null,
) : PiSettingsStore {

    private var globalCache: JsonObject? = null
    private var projectCache: JsonObject? = null
    private var appLocalCache: JsonObject? = null

    // ------------------------------------------------------------------ reading

    override fun read(key: String): JsonElement? {
        if (isAppLocalKey(key)) return lookup(appLocal(), key)
        return lookup(merged(), key)
    }

    /** The document pi would see: global as base, project merged over it. */
    fun merged(): JsonObject = deepMerge(global(), project())

    fun global(): JsonObject = globalCache ?: readDocument(globalFile).also { globalCache = it }

    fun project(): JsonObject = if (projectFile == null) {
        JsonObject(emptyMap())
    } else {
        projectCache ?: readDocument(projectFile).also { projectCache = it }
    }

    fun appLocal(): JsonObject = appLocalFile?.let {
        appLocalCache ?: readDocument(it).also { cached -> appLocalCache = cached }
    } ?: JsonObject(emptyMap())

    // ------------------------------------------------------------------ writing

    override fun write(key: String, value: JsonElement) {
        if (isAppLocalKey(key)) {
            val file = appLocalFile ?: return
            val next = setPath(appLocal(), key, value)
            writeDocument(file, next)
            appLocalCache = next
            return
        }
        // Writes go to the global document unless a project document is present
        // and already carries the key — a project override should be updated
        // where it lives, not shadowed by a new global value.
        val targetIsProject = projectFile != null && lookup(project(), key) != null
        val base = if (targetIsProject) project() else global()
        val next = setPath(base, key, value)
        if (targetIsProject) {
            writeDocument(projectFile!!, next)
            projectCache = next
        } else {
            writeDocument(globalFile, next)
            globalCache = next
        }
    }

    /** Forget cached documents so the next read hits disk. */
    fun invalidate() {
        globalCache = null; projectCache = null; appLocalCache = null
    }

    // ------------------------------------------------------------------ JSON I/O

    private fun readDocument(file: File): JsonObject {
        if (!file.isFile) return JsonObject(emptyMap())
        val text = runCatching { file.readText() }.getOrNull() ?: return JsonObject(emptyMap())
        if (text.isBlank()) return JsonObject(emptyMap())
        return PiJson.parseObjectOrNull(text) ?: run {
            // Unparsable. Keep it — a user's mangled file is still their file.
            val aside = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
            runCatching { Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            JsonObject(emptyMap())
        }
    }

    private fun writeDocument(file: File, document: JsonObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp-${ProcessHandle.current().pid()}")
        temp.writeText(document.toString())
        runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure {
                // Cross-device rename can fail; fall back to a copy.
                file.writeText(document.toString())
                temp.delete()
            }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Keys that address *inside* an array element (`packages[].autoload`) are not
     * JSON paths and must not be written as top-level keys.
     */
    private fun isAppLocalKey(key: String): Boolean = key.contains("[]")

    // Delegated to `:rpc`'s SettingsDocument, which is unit-tested on a bare JDK.
    // Keeping one implementation matters: a second copy of merge/lookup/set here
    // would be free to drift, and drift in this code corrupts user config.
    private fun lookup(document: JsonObject, key: String): JsonElement? =
        SettingsDocument.lookup(document, key)

    private fun setPath(document: JsonObject, key: String, value: JsonElement): JsonObject =
        SettingsDocument.setPath(document, key, value)

    private fun deepMerge(base: JsonObject, overrides: JsonObject): JsonObject =
        SettingsDocument.merge(base, overrides)

    companion object {
        /**
         * Build the store the app actually uses: global settings under pi's home
         * inside the guest, the project document under the current workspace, and
         * an app-local sidecar for keys that are not pi settings paths.
         */
        fun forWorkspace(agentDir: File, workspace: File): PiSettingsFileStore =
            PiSettingsFileStore(
                globalFile = File(agentDir, "settings.json"),
                projectFile = File(workspace, ".pi/settings.json"),
                appLocalFile = File(agentDir.parentFile ?: agentDir, "app-prefs.json"),
            )
    }
}

/** Convenience for zones that only need a primitive out of the store. */
fun PiSettingsStore.readString(key: String): String? = (read(key) as? JsonPrimitive)?.content

fun PiSettingsStore.readBoolean(key: String): Boolean? =
    (read(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
