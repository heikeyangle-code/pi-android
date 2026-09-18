package app.pi.settings

import app.pi.rpc.PiJson
import app.pi.rpc.SettingsDocument
import app.pi.runtime.PiProjectConfig
import app.pi.ui.settings.PiSettingsStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

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
 *    validation, and its own `persistScopedSettings` (`settings-manager.ts:632-661`)
 *    re-reads the file inside the lock and writes back `{...currentFileSettings}`
 *    with only its own modified fields replaced — so it preserves them too.
 *
 * Three deliberate improvements over pi, all about not destroying user data:
 *
 *  1. **Atomic writes.** pi calls `writeFileSync` directly, so a kill mid-write
 *     leaves a truncated settings file — and pi then fails to start. Here the
 *     document goes to a temp file and is renamed into place, and the temp name is
 *     unique per call (a process-local counter), so two writers in one process can
 *     never share it.
 *  2. **A corrupt file is never clobbered.** If the existing document cannot be
 *     parsed, it is moved aside with a timestamped suffix instead of being
 *     overwritten with the new value, so a mangled file stays recoverable.
 *  3. **A write is a read-modify-write inside pi's lock.** [update] re-reads the
 *     document under `<file>.lock` before applying one key, which is what pi itself
 *     does; without it, a whole stale document was written back and a concurrent
 *     writer's key disappeared with no error (`docs/settings-audit-impl.md` §B2/§B3).
 *
 * ## One document per file, process-wide
 *
 * The caches and locks are **per canonical file path**, not per instance
 * ([Document]). Four call sites used to build their own store over the same
 * `settings.json` (`PiSessionViewModel`, `WorkspaceStore`, `PiPackageFilterStore`,
 * `PiEnginePreferences`) and each cached its own copy, so `WorkspaceStore` — which
 * never invalidated anything — wrote back a document from before a settings change
 * and erased it. Sharing the [Document] removes that whole class of bug; [shared] /
 * [forWorkspace] additionally hand out one instance per (global, project, appLocal)
 * triple.
 *
 * ## Where app-only keys go
 *
 * `app.*` (and any key containing `[]`) is **not** a pi setting: it goes to the
 * app-local sidecar beside pi's agent dir (`app-prefs.json`), never into
 * `settings.json`. A value left in the global document by an older build is still
 * read, and dropped from pi's file the next time that key is written, so upgrading
 * does not lose an appearance preference.
 */
class PiSettingsFileStore(
    private val globalFile: File,
    private val projectFile: File? = null,
    /**
     * Keys that are **not** pi settings paths — `app.*`, or a UI convenience like
     * `packages[].autoload` that describes a field *inside* package entries. Such keys
     * must not be written as top-level keys of pi's document.
     *
     * Null derives the canonical sidecar from [globalFile] (`<agentDir>/../app-prefs.json`),
     * which is what makes an instance built with only a `globalFile` — `WorkspaceStore`'s —
     * write `app.*` to the same place every other instance reads it from, instead of
     * silently dropping the write.
     */
    appLocalFile: File? = null,
) : PiSettingsStore {

    private val appLocalDocument: File = appLocalFile ?: defaultAppLocalFile(globalFile)

    private val global = documentFor(globalFile)
    private val project = projectFile?.let(::documentFor)
    private val appLocal = documentFor(appLocalDocument)

    // ------------------------------------------------------------------ reading

    override fun read(key: String): JsonElement? {
        if (isAppLocalKey(key)) {
            // The sidecar first. A value left in pi's own document by an older build is still
            // answered — the global copy is cached and pi reads it anyway, and losing a user's
            // appearance setting on upgrade would be worse than one extra lookup.
            return lookup(appLocal.load(), key) ?: lookup(global.load(), key)
        }
        return lookup(merged(), key)
    }

    /** The document pi would see: global as base, project merged over it. */
    fun merged(): JsonObject =
        SettingsDocument.merge(global.load(), project?.load() ?: JsonObject(emptyMap()))

    fun global(): JsonObject = global.load()

    fun project(): JsonObject = project?.load() ?: JsonObject(emptyMap())

    fun appLocal(): JsonObject = appLocal.load()

    // ------------------------------------------------------------------ writing

    override fun write(key: String, value: JsonElement) {
        if (isAppLocalKey(key)) {
            update(appLocal) { SettingsDocument.setPath(it, key, value) }
            dropLegacyCopy(key)
            return
        }
        // Writes go to the global document unless a project document is present
        // and already carries the key — a project override should be updated
        // where it lives, not shadowed by a new global value.
        //
        // "Is present" has to be asked of the **filesystem**, not only of the cache.
        // The project document is `<workspace>/.pi/settings.json`, and the workspace
        // is app-private storage: Android clears it, and a user can delete it. If a
        // missing document were located from a cached copy, `writeDocument` would
        // `mkdirs()` the directory back and write every cached project key plus the
        // one being changed — resurrecting an override the user deleted, which then
        // shadows the global value in pi.
        val inProject = project != null && projectFile?.isFile == true &&
            lookup(project.load(), key) != null
        update(if (inProject) project ?: global else global) { SettingsDocument.setPath(it, key, value) }
    }

    /**
     * Drop [key] from the **scope that sets it explicitly**: the project document when it
     * carries the key, the global one otherwise.
     *
     * This is pi's own deletion semantic. pi's setters write `undefined` for "unset", and
     * `persistScopedSettings` rebuilds the file from `{...currentFileSettings}` plus its
     * modified fields, so the key simply **disappears** from that scope's file and the other
     * scope shows through (`settings-manager.ts:632-661`). Writing `null` instead is not the
     * same thing: pi's `parseTimeoutSetting` treats `null` as "present and unparseable" and
     * throws (`:186-196`), which is how the settings UI used to make `pi --mode rpc` fail to
     * start (`docs/settings-audit-impl.md` §B1). `SettingsDocument.removePath` prunes the
     * parents it empties, so `compaction.reserveTokens` does not leave `"compaction": {}`.
     */
    override fun remove(key: String) {
        if (isAppLocalKey(key)) {
            update(appLocal) { SettingsDocument.removePath(it, key) }
            dropLegacyCopy(key)
            return
        }
        val inProject = project != null && projectFile?.isFile == true && lookup(project.load(), key) != null
        update(if (inProject) project ?: global else global) { SettingsDocument.removePath(it, key) }
    }

    /**
     * One key, one document: take pi's lock, **re-read the file inside it**, apply the change,
     * replace the file atomically, then publish the new cache.
     *
     * The re-read is the part that stops a stale document from being written back (the second
     * half of §B2/§B3): whatever pi or another instance wrote since this cache was filled is
     * the base the change is applied to.
     */
    private fun update(target: Document, transform: (JsonObject) -> JsonObject) {
        PiSettingsLock.withLock(target.file) {
            synchronized(target.lock) {
                val current = if (target.file.isFile) readDocument(target.file) else JsonObject(emptyMap())
                val next = transform(current)
                if (writeDocument(target.file, next)) {
                    target.cached = next
                    target.stamp = stampOf(target.file)
                    target.checkedAtMs = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * Remove an `app.*` key that an older build wrote into pi's own document.
     *
     * Only runs when the key is actually there, so the steady state costs one cached lookup
     * and no write. This is [read]'s other half: the legacy value keeps working until the next
     * write of that key migrates it out of pi's file.
     */
    private fun dropLegacyCopy(key: String) {
        if (!key.startsWith(APP_KEY_PREFIX)) return
        if (lookup(global.load(), key) == null) return
        update(global) { SettingsDocument.removePath(it, key) }
    }

    /** Forget cached documents so the next read hits disk. Clears the shared caches. */
    fun invalidate() {
        listOfNotNull(global, project, appLocal).forEach { document ->
            synchronized(document.lock) {
                document.cached = null
                document.stamp = null
                document.checkedAtMs = 0L
            }
        }
    }

    // ------------------------------------------------------------------ JSON I/O

    private fun readDocument(file: File): JsonObject = readDocumentStatic(file)

    private fun writeDocument(file: File, document: JsonObject): Boolean = runCatching {
        file.parentFile?.mkdirs()
        // Unique per call, not per process: two writers used to share `<name>.tmp-<pid>` and
        // could interleave into the same temp file before either renamed it
        // (`docs/settings-audit-impl.md` §B3). `android.os.Process.myPid()` is gone with it,
        // which is also what lets this file compile in the bare-JVM harness.
        val temp = File(file.parentFile, "${file.name}.tmp-${TEMP_SEQUENCE.incrementAndGet()}")
        temp.writeText(document.toString())
        runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .onFailure {
                // Cross-device rename can fail; fall back to a copy.
                file.writeText(document.toString())
                temp.delete()
            }
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ helpers

    /** Keys that are not pi settings paths: `app.*`, or a field inside an array element. */
    private fun isAppLocalKey(key: String): Boolean =
        key.startsWith(APP_KEY_PREFIX) || key.contains("[]")

    // Delegated to `:rpc`'s SettingsDocument, which is unit-tested on a bare JDK.
    // Keeping one implementation matters: a second copy of merge/lookup/set here
    // would be free to drift, and drift in this code corrupts user config.
    private fun lookup(document: JsonObject, key: String): JsonElement? =
        SettingsDocument.lookup(document, key)

    /**
     * The parsed documents, and the one lock that owns each of them.
     *
     * ## Why a lock, and why it is shared by file
     *
     * A store instance is shared by everything that addresses the same files, and those
     * callers are on **different threads**: the settings screens write from a coroutine,
     * `WorkspaceStore` reads from composition *and* from the engine's IO thread, and the
     * credential service writes from `Dispatchers.IO`. Without a lock, `write` is a
     * read-modify-write — read the document, set one key, replace the cached document and
     * the file — so two writers that read the same base each write a document containing
     * only their own change and the other key is **lost**, silently and permanently.
     *
     * The lock is per **canonical file path**, so two store instances over one file share it.
     * That is the whole point: `WorkspaceStore` builds its own instance for the global
     * document, and before this it could write the document it had cached before a settings
     * change and erase that change (`docs/settings-audit-impl.md` §B2).
     */
    private class Document(val file: File) {
        val lock = Any()

        @Volatile
        var cached: JsonObject? = null

        /** `(size, mtime)` as of the read that filled [cached]. */
        @Volatile
        var stamp: String? = null

        /** When the stamp was last compared, so a per-frame read is not a per-frame `stat`. */
        @Volatile
        var checkedAtMs: Long = 0L

        /**
         * The document, re-read from disk when the file changed underneath us.
         *
         * pi is a **second writer** of `settings.json` in its own process: it saves on
         * `/model`, `/theme`, compaction, trust and every other setter, and it coordinates
         * with `proper-lockfile` rather than with this cache. So a cached document is only
         * trusted while the file's `(size, mtime)` still matches — the same judgement
         * `PiFileStamps` makes for the UI, at the same granularity.
         *
         * The comparison is throttled to [STAMP_CHECK_INTERVAL_MS]: it is a `stat` per
         * document, and `read(key)` runs per row per frame. A change therefore reaches this
         * cache within a quarter second of the next read; the file watcher, which clears the
         * cache outright, makes it immediate while the settings page is on screen anyway.
         */
        fun load(): JsonObject {
            val now = System.currentTimeMillis()
            val current = cached
            if (current != null && now - checkedAtMs < STAMP_CHECK_INTERVAL_MS) return current
            synchronized(lock) {
                val inside = cached
                val insideNow = System.currentTimeMillis()
                if (inside != null && insideNow - checkedAtMs < STAMP_CHECK_INTERVAL_MS) return inside
                val fresh = stampOf(file)
                if (inside != null && fresh == stamp) {
                    checkedAtMs = insideNow
                    return inside
                }
                val loaded = if (file.isFile) readDocumentStatic(file) else JsonObject(emptyMap())
                cached = loaded
                stamp = fresh
                checkedAtMs = insideNow
                return loaded
            }
        }
    }

    companion object {
        private const val APP_KEY_PREFIX = "app."

        /** Unique temp-name suffix within this process; see [writeDocument]. */
        private val TEMP_SEQUENCE = AtomicLong()

        /**
         * How long a cached document is trusted before its `(size, mtime)` is compared again.
         * 250 ms bounds the cost of `read(key)` on the composition thread to four stats per
         * second per document, while a document edited by pi is still picked up by the next
         * frame after that.
         */
        private const val STAMP_CHECK_INTERVAL_MS = 250L

        private val DOCUMENTS = LinkedHashMap<String, Document>()

        private val INSTANCES = LinkedHashMap<String, PiSettingsFileStore>()

        /** The one [Document] for [file]'s canonical path, shared by every instance. */
        private fun documentFor(file: File): Document = synchronized(DOCUMENTS) {
            DOCUMENTS.getOrPut(canonical(file)) { Document(file) }
        }

        private fun stampOf(file: File): String? =
            if (!file.exists()) null else "${file.length()}:${file.lastModified()}"

        private fun readDocumentStatic(file: File): JsonObject {
            val text = runCatching { file.readText() }.getOrNull() ?: return JsonObject(emptyMap())
            if (text.isBlank()) return JsonObject(emptyMap())
            return PiJson.parseObjectOrNull(text) ?: run {
                // Unparsable. Keep it — a user's mangled file is still their file.
                val aside = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
                runCatching { Files.move(file.toPath(), aside.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                JsonObject(emptyMap())
            }
        }

        private fun canonical(file: File): String =
            runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

        /** Where an instance built without an explicit sidecar keeps `app.*`: `<agentDir>/../app-prefs.json`. */
        private fun defaultAppLocalFile(globalFile: File): File =
            File(globalFile.parentFile?.parentFile ?: globalFile.parentFile ?: globalFile, "app-prefs.json")

        /**
         * The store for a (global, project, app-local) triple — one instance per triple, so
         * callers that address the same files share one cache and one lock on top of the
         * shared [Document]s.
         */
        fun shared(globalFile: File, projectFile: File? = null, appLocalFile: File? = null): PiSettingsFileStore {
            val appLocal = appLocalFile ?: defaultAppLocalFile(globalFile)
            val key = listOf(canonical(globalFile), projectFile?.let(::canonical).orEmpty(), canonical(appLocal))
                .joinToString("|")
            return synchronized(INSTANCES) {
                INSTANCES.getOrPut(key) { PiSettingsFileStore(globalFile, projectFile, appLocal) }
            }
        }

        /**
         * Build the store the app actually uses: global settings under pi's home
         * inside the guest, the project document under the current workspace, and an
         * app-local sidecar for keys that are not pi settings paths.
         */
        fun forWorkspace(agentDir: File, workspace: File): PiSettingsFileStore =
            shared(
                globalFile = File(agentDir, "settings.json"),
                // pi's own project path, from the one place the app transcribes it
                // (`core/settings-manager.ts:233` = `<cwd>/<CONFIG_DIR_NAME>/settings.json`).
                projectFile = PiProjectConfig.settingsFile(workspace),
                appLocalFile = File(agentDir.parentFile ?: agentDir, "app-prefs.json"),
            )
    }
}

/** Convenience for zones that only need a primitive out of the store. */
fun PiSettingsStore.readString(key: String): String? = (read(key) as? JsonPrimitive)?.content

fun PiSettingsStore.readBoolean(key: String): Boolean? =
    (read(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
