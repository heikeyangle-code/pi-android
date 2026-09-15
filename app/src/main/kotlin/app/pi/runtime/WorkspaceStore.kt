package app.pi.runtime

import android.content.Context
import app.pi.settings.PiSettingsFileStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The workspaces the user can run pi in, and which one is current.
 *
 * ## What a workspace is
 *
 * One directory under `<files>/pi/workspaces/`, named `workspace-N`, bind-mounted
 * into the guest as the engine's cwd ([GuestWorkspacePath] owns both spellings).
 * The directory **path is the workspace's identity**: pi records it in
 * `trust.json`, and every session header records it as the session's `cwd`
 * (`core/session-manager.ts:37/178`), which is what the app filters the session
 * list by. Nothing here moves a directory once it exists — see [rename].
 *
 * ## Which one is current, and what happens when that is wrong
 *
 * The choice is persisted in the app's own settings key [SETTING_KEY]
 * (`app.workspace.current`, a relative name like `workspace-1`). It lives in
 * pi's global `settings.json` document, the same file every other `app.*`
 * preference uses — **not** in one of pi's own keys, and deliberately not read
 * through the *merged* global-plus-project view: the project document lives
 * inside a workspace, so asking it which workspace is current would let the
 * answer depend on itself.
 *
 * [resolve] never throws and never invents a directory. When the persisted name
 * is missing, malformed, or names a directory that no longer exists, it answers
 * the default workspace and a [Resolution.note] that says so in words;
 * [reconcile] additionally writes the correction back, because the one state the
 * app must never be left in is "the setting says A while the process runs B".
 *
 * ## One writer, and why it is this object
 *
 * Reads have to work from a bare `Context` — `PtyLauncher.workspaceHost`,
 * `DeviceWorkspace.refresh` and the terminal all resolve the workspace before any
 * ViewModel exists — so persistence cannot be wired in from the UI layer. Writes
 * therefore use [PiSettingsFileStore], the app's only implementation of "read
 * pi's settings documents, write them atomically, never clobber a corrupt one",
 * configured with **no project file**: this object only ever addresses the global
 * document, which is where its two keys live. `app/src/main/kotlin/app/pi/runtime/`
 * importing `app.pi.settings` is a new package edge (settings already imports
 * `PiProjectConfig` from here); the alternative was a second implementation of
 * the atomic write, and this repository has already paid for that kind of copy
 * enough times to stop making it.
 *
 * `PiSessionViewModel.settingsStore` keeps its own cache of the same file, so any
 * caller that persists through either object must invalidate the other's view —
 * the ViewModel does that after every workspace write (`invalidateSettingsCache`).
 */
object WorkspaceStore {

    /**
     * The app's own key for the current workspace, in pi's global document.
     *
     * A relative name (`workspace-1`), not a path: the files directory is not
     * stable across installs, and a path would also make a future move impossible.
     */
    const val SETTING_KEY: String = "app.workspace.current"

    /**
     * The user-facing names of the workspaces, as a JSON object from relative name
     * to label. App-owned, for exactly the same reason as [SETTING_KEY].
     */
    const val NAMES_KEY: String = "app.workspace.names"

    /** `<files>/pi/workspaces`, from the one object that owns the layout. */
    const val ROOT_RELATIVE: String = GuestWorkspacePath.ROOT_RELATIVE

    /** The workspace an install that has never chosen one uses. */
    val DEFAULT_NAME: String = GuestWorkspacePath.DEFAULT_NAME

    /**
     * The directory names this app creates and is therefore allowed to delete.
     *
     * Deliberately a pattern and not "any child directory": `pi/workspaces` sits in
     * app-private storage but a directory there can also be something a user pushed
     * in over adb or that a future feature wrote. Deleting it because it happened to
     * sit in our folder is not a risk this object takes. The digit run is bounded so
     * the number survives `toInt()`.
     */
    private val OWNED: Regex = Regex("^workspace-([1-9][0-9]{0,5})$")

    /** Labels are shown in a list row; long ones are a rendering problem, not data. */
    private const val MAX_LABEL_CHARS = 40

    // ---------------------------------------------------------------- model

    /**
     * One workspace, as a screen needs it: name, path, and whether it is current.
     *
     * [name] is the directory name — the identity. [label] is the app-owned
     * display name and equals [name] until the user renames it; [displayName] is
     * the single thing a row should print.
     */
    data class Entry(
        val name: String,
        val label: String,
        val host: File,
        val relative: String,
        val guestPath: String,
        val isCurrent: Boolean,
    ) {
        /** What a list row shows. */
        val displayName: String get() = label.trim().ifEmpty { name }
    }

    /**
     * The answer to "which workspace is this process in", with the correction that
     * was needed to get there.
     *
     * [requested] is what the settings file said (null when it said nothing), and
     * [note] is non-null exactly when [name] is **not** what was asked for — the
     * caller shows it rather than letting the switch look like it worked.
     */
    data class Resolution(
        val name: String,
        val requested: String?,
        val note: String?,
    )

    sealed interface Create {
        data class Ok(val entry: Entry) : Create

        /** Nothing was created. The message is written for the user. */
        data class Failed(val message: String) : Create
    }

    sealed interface Rename {
        data class Ok(val entry: Entry) : Rename

        data class Failed(val message: String) : Rename
    }

    /** What a delete would remove, measured before anything is removed. */
    data class DeleteTarget(
        val name: String,
        val label: String,
        val host: File,
        val files: Int,
        val dirs: Int,
        val bytes: Long,
    )

    /**
     * Proof that a scan happened and the user agreed to *that* scan.
     *
     * Deliberately not constructible outside this object: the only way to obtain
     * one is [previewDelete], which is the "report how many files are inside it
     * first" step the design requires. [delete] takes the token rather than a
     * name, so a caller cannot reach the delete without the preview — this is the
     * API shape of "二次确认", not a convention someone has to remember.
     */
    class DeleteConfirmation internal constructor(
        val target: DeleteTarget,
    )

    sealed interface Preview {
        data class Ok(val target: DeleteTarget, val confirmation: DeleteConfirmation) : Preview

        /** Refused before any counting, with the reason to show. */
        data class Refused(val message: String) : Preview
    }

    sealed interface Delete {
        data class Ok(val name: String, val files: Int, val bytes: Long) : Delete

        data class Refused(val message: String) : Delete

        data class Failed(val message: String) : Delete
    }

    // ------------------------------------------------------------- resolving

    /** `<files>/pi/workspaces`. Not created by this accessor. */
    fun root(context: Context): File = File(context.filesDir, ROOT_RELATIVE)

    /** `pi/workspaces/<name>` — the spelling `GuestWorkspacePath` needs. */
    fun relativeOf(name: String): String = "$ROOT_RELATIVE/$name"

    /** True for the directory names this app creates (see [OWNED]). */
    fun isOwned(name: String): Boolean = OWNED.matches(name)

    /**
     * Which workspace the process should be in, and the correction needed to get
     * there. **Read-only**: it adopts nothing and writes nothing.
     *
     * See the class KDoc for the fallback rule. A missing *default* workspace is
     * not a fallback — it is the first launch, and every caller already creates
     * the directory it is about to use (`GuestWorkspacePath.ensureHost`).
     */
    fun resolve(context: Context): Resolution {
        val requested = runCatching { store(context).read(SETTING_KEY) }
            .getOrNull()
            ?.let { (it as? JsonPrimitive)?.content }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return Resolution(DEFAULT_NAME, null, null)

        if (!isOwned(requested)) {
            return Resolution(
                name = DEFAULT_NAME,
                requested = requested,
                note = "设置里的当前工作区「$requested」不是本应用创建的工作区，已回到默认工作区「$DEFAULT_NAME」。",
            )
        }
        val dir = File(root(context), requested)
        // A symlink is refused for the same reason it is refused a delete: the
        // workspace is the device shell's write boundary and the engine's cwd, and
        // both of those should describe a real directory this app owns rather than
        // wherever a link happens to point. Only a link is refused — the default
        // workspace is recreated on demand and never a link.
        if (Files.isSymbolicLink(dir.toPath())) {
            return Resolution(
                name = DEFAULT_NAME,
                requested = requested,
                note = if (requested == DEFAULT_NAME) {
                    "默认工作区「$DEFAULT_NAME」是一个符号链接，不是一个真实目录；请把它换成真实目录。"
                } else {
                    "工作区「$requested」是一个符号链接，不是一个真实目录，已回到默认工作区「$DEFAULT_NAME」。"
                },
            )
        }
        if (!dir.isDirectory) {
            if (requested == DEFAULT_NAME) {
                // The default may simply not have been created yet; every launch
                // path makes it on demand, so this is not a fallback.
                return Resolution(DEFAULT_NAME, requested, null)
            }
            return Resolution(
                name = DEFAULT_NAME,
                requested = requested,
                note = "工作区「$requested」的目录不存在（可能被外部删除了），已回到默认工作区「$DEFAULT_NAME」。",
            )
        }
        return Resolution(requested, requested, null)
    }

    /**
     * [resolve], plus the two process-wide consequences of the answer: the current
     * workspace is published to [GuestWorkspacePath] (so every reader of the rule
     * moves with it) and a fallback is written back to the settings file.
     *
     * The write-back is what keeps a deleted workspace from producing the same
     * correction on every launch — and, more importantly, from leaving the stored
     * choice pointing at a directory nothing runs in. It is a correction of a
     * broken value, not a silent change of the user's choice: [Resolution.note]
     * carries the sentence the caller must show.
     */
    fun refresh(context: Context): Resolution {
        val resolved = resolve(context)
        GuestWorkspacePath.adoptRelative(relativeOf(resolved.name))
        if (resolved.note != null && resolved.requested != null) {
            writeCurrent(context, resolved.name)
        }
        return resolved
    }

    /** [refresh] for a caller that also wants to know whether the fix was stored. */
    fun reconcile(context: Context): Resolution = refresh(context)

    /** The current workspace's directory name, without publishing or writing. */
    fun currentName(context: Context): String = resolve(context).name

    /** The current workspace's host directory, created if missing. */
    fun currentHost(context: Context): File =
        GuestWorkspacePath.ensureHost(context.filesDir, relativeOf(refresh(context).name))

    /** The entry for [name], or null when it is not one of ours or its directory is gone. */
    fun existing(context: Context, name: String): Entry? {
        if (!isOwned(name)) return null
        val dir = File(root(context), name)
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return null
        return entry(context, name, dir)
    }

    /**
     * Every workspace this app owns, oldest number first, with the current one
     * flagged. Creates the root and the default workspace if they are missing —
     * the same `mkdirs()` the engine and the terminal already do before binding
     * them, so a first launch lists the default instead of an empty list.
     */
    fun list(context: Context): List<Entry> {
        val root = root(context)
        root.mkdirs()
        val names = labels(context)
        val current = currentName(context)
        val entries = root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && isOwned(it.name) }
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .map { entry(context, it.name, it, names[it.name], current) }
            .sortedBy { OWNED.find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
            .toList()
        if (entries.none { it.name == DEFAULT_NAME }) {
            val dir = File(root, DEFAULT_NAME)
            dir.mkdirs()
            return (entries + entry(context, DEFAULT_NAME, dir, names[DEFAULT_NAME], current))
                .sortedBy { OWNED.find(it.name)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
        }
        return entries
    }

    // ------------------------------------------------------------- mutating

    /**
     * Create the next free `workspace-N` and return it. Never selects it: creating
     * a directory and moving the engine into it are two different decisions, and
     * only the user makes the second one.
     */
    fun create(context: Context): Create {
        val root = root(context)
        if (!root.isDirectory && !root.mkdirs()) {
            return Create.Failed("无法创建工作区目录：${root.absolutePath}（存储空间或权限问题）。")
        }
        val name = nextFreeName(root)
            ?: return Create.Failed("无法分配新的工作区编号：${root.absolutePath} 下的 workspace-1 到 workspace-999999 都已被占用。")
        val dir = File(root, name)
        if (!dir.mkdirs() || !dir.isDirectory) {
            return Create.Failed("无法创建工作区目录：${dir.absolutePath}（存储空间或权限问题）。")
        }
        return Create.Ok(entry(context, name, dir))
    }

    /**
     * Give [name] a user-facing label. **The directory is not moved, renamed or
     * touched** — the label is stored in [NAMES_KEY].
     *
     * That is the decision this method exists to record. A workspace's identity is
     * its directory path: pi keys the project's trust entry by cwd, and every
     * session header records the cwd it ran in, which is how the app decides which
     * sessions belong to the workspace on screen. Renaming the directory would
     * therefore quietly do two things the user did not ask for — drop the project's
     * trust back to the default, and make every session in that workspace
     * disappear from its list (they are still on disk under the old cwd). A label
     * changes nothing the engine or pi can see, which is exactly what "rename"
     * should mean for a thing whose path is its identity.
     *
     * An empty label, or one that equals the directory name, clears the label.
     */
    fun rename(context: Context, name: String, label: String): Rename {
        val dir = File(root(context), name)
        if (!isOwned(name) || !dir.isDirectory) {
            return Rename.Failed("工作区「$name」不存在或不是本应用创建的，无法重命名。")
        }
        val cleaned = sanitizeLabel(label)
            ?: return Rename.Failed("名称不能为空（也可以清空它，恢复为「$name」）。")
        val next = labels(context).toMutableMap()
        if (cleaned == name) next.remove(name) else next[name] = cleaned
        if (!writeLabels(context, next)) {
            return Rename.Failed("名称没有保存成功（无法写入设置文件）。")
        }
        return Rename.Ok(entry(context, name, dir))
    }

    /**
     * Count what a delete of [name] would remove, and hand back the only token
     * [delete] accepts.
     *
     * The count is *reported*, not acted on: it is the number the confirmation has
     * to show. Counting and deleting both walk with `Files.walkFileTree` without
     * following links, so a symlink inside the workspace is counted and removed as
     * the link it is rather than followed out of the tree.
     */
    fun previewDelete(context: Context, name: String): Preview {
        if (!isOwned(name)) {
            return Preview.Refused("只能删除本应用创建的工作区（workspace-N），「$name」不是。")
        }
        val dir = File(root(context), name)
        if (!dir.isDirectory) {
            return Preview.Refused("工作区「$name」的目录不存在，没有可删除的内容。")
        }
        if (Files.isSymbolicLink(dir.toPath())) {
            return Preview.Refused("工作区「$name」是一个符号链接，不是本应用创建的目录，已拒绝删除。")
        }
        if (name == currentName(context)) {
            return Preview.Refused("「$name」是当前工作区，引擎正运行在里面。请先切换到别的工作区，再删除它。")
        }
        val tally = tally(dir) ?: return Preview.Refused("无法读取工作区「$name」的内容，已拒绝删除。")
        val target = DeleteTarget(
            name = name,
            label = labels(context)[name] ?: name,
            host = dir,
            files = tally.files,
            dirs = tally.dirs,
            bytes = tally.bytes,
        )
        return Preview.Ok(target, DeleteConfirmation(target))
    }

    /**
     * Delete the workspace a [DeleteConfirmation] describes. **The directory is
     * removed recursively**; there is no "empty it but keep it" mode, on purpose —
     * a workspace whose contents were cleared still looks like a workspace to
     * every list and to pi, and the user asked for removal.
     *
     * Re-counts before deleting, so a tree that changed between the preview and
     * the confirmation is reported with the number that was actually removed
     * rather than the stale one the user agreed to.
     */
    fun delete(context: Context, confirmation: DeleteConfirmation): Delete {
        val name = confirmation.target.name
        if (!isOwned(name)) return Delete.Refused("只能删除本应用创建的工作区（workspace-N）。")
        val dir = File(root(context), name)
        if (!dir.isDirectory) return Delete.Refused("工作区「$name」已经不存在了。")
        if (Files.isSymbolicLink(dir.toPath())) {
            return Delete.Refused("工作区「$name」是一个符号链接，已拒绝删除。")
        }
        if (name == currentName(context)) {
            return Delete.Refused("「$name」是当前工作区，引擎正运行在里面。请先切换到别的工作区，再删除它。")
        }
        // Belt and braces: the name is already a single path segment, but the
        // deletion is recursive, so the target is re-checked to be a direct child
        // of our root by canonical path before anything is removed.
        val root = root(context).canonicalFile
        val canonical = runCatching { dir.canonicalFile }.getOrNull()
            ?: return Delete.Failed("无法解析工作区「$name」的路径，已拒绝删除。")
        if (canonical.parentFile != root) {
            return Delete.Refused("工作区「$name」不在 ${root.absolutePath} 下，已拒绝删除。")
        }
        val before = tally(dir) ?: return Delete.Failed("无法读取工作区「$name」的内容，已拒绝删除。")
        // Read the labels *before* the directory goes away: [labels] filters out
        // entries whose directory no longer exists, so asking it afterwards would
        // report "no label for this name" and the stale `app.workspace.names`
        // entry would survive to be inherited by the next `workspace-N`.
        val labelled = labels(context)
        if (!deleteTree(dir)) {
            return Delete.Failed("删除工作区「$name」失败，可能有文件正被占用。")
        }
        val next = labelled.toMutableMap()
        if (next.remove(name) != null) writeLabels(context, next)
        return Delete.Ok(name, before.files, before.bytes)
    }

    /**
     * Persist [name] as the current workspace and publish it to
     * [GuestWorkspacePath]. Returns false — and changes nothing — when [name] is
     * not one of ours or its directory is gone.
     *
     * This is the *second* half of a switch, not the first: the engine is moved
     * with an explicit directory (so a crash mid-switch cannot leave the setting
     * pointing somewhere the engine never ran), and this records the result.
     */
    fun setCurrent(context: Context, name: String): Boolean {
        if (existing(context, name) == null) return false
        if (!writeCurrent(context, name)) return false
        GuestWorkspacePath.adoptRelative(relativeOf(name))
        return true
    }

    /** The labels currently stored, without the ones for workspaces that are gone. */
    fun labels(context: Context): Map<String, String> {
        val raw = runCatching { store(context).read(NAMES_KEY) }.getOrNull() as? JsonObject ?: return emptyMap()
        val root = root(context)
        return raw.mapNotNull { (key, value) ->
            val label = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!isOwned(key) || !File(root, key).isDirectory) return@mapNotNull null
            key to label
        }.toMap()
    }

    // ------------------------------------------------------------ internals

    private class Tally {
        var files = 0
        var dirs = 0
        var bytes = 0L
    }

    private fun entry(
        context: Context,
        name: String,
        dir: File,
        label: String? = labels(context)[name],
        current: String = currentName(context),
    ): Entry = Entry(
        name = name,
        label = label ?: name,
        host = dir,
        relative = relativeOf(name),
        guestPath = GuestWorkspacePath.under(context.filesDir.absolutePath, dir.absolutePath),
        isCurrent = name == current,
    )

    private fun nextFreeName(root: File): String? {
        val used = root.listFiles().orEmpty()
            .mapNotNull { OWNED.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .toHashSet()
        return (1..999_999).firstOrNull { it !in used }?.let { "workspace-$it" }
    }

    private fun sanitizeLabel(raw: String): String? {
        val cleaned = raw.filterNot { it.isISOControl() }.trim().take(MAX_LABEL_CHARS)
        return cleaned.ifEmpty { null }
    }

    private fun tally(dir: File): Tally? = runCatching {
        val tally = Tally()
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    tally.files++
                    tally.bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(child: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (child != dir.toPath()) tally.dirs++
                    return FileVisitResult.CONTINUE
                }
            },
        )
        tally
    }.getOrNull()

    /**
     * Remove [dir] and everything under it **without following links**.
     *
     * `File.deleteRecursively` is not used: Kotlin's implementation recurses through
     * `listFiles()`, which reports a symlink-to-directory as a directory, so it
     * would delete the *target's* contents. That is the one failure mode of a
     * recursive delete that has to be impossible here.
     */
    private fun deleteTree(dir: File): Boolean = runCatching {
        Files.walkFileTree(
            dir.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(child: Path, failure: IOException?): FileVisitResult {
                    if (failure != null) throw failure
                    Files.deleteIfExists(child)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        !dir.exists()
    }.getOrDefault(false)

    private fun writeCurrent(context: Context, name: String): Boolean =
        writeSetting(context, SETTING_KEY, JsonPrimitive(name))

    private fun writeLabels(context: Context, labels: Map<String, String>): Boolean =
        writeSetting(
            context,
            NAMES_KEY,
            JsonObject(labels.mapValues { (_, label) -> JsonPrimitive(label) as JsonElement }),
        )

    private fun writeSetting(context: Context, key: String, value: JsonElement): Boolean =
        runCatching { store(context).write(key, value) }.isSuccess

    // --- the settings document this object owns -------------------------------

    private class Scope(val filesRoot: String, val store: PiSettingsFileStore)

    /**
     * One [PiSettingsFileStore] per files directory, remembering which one it
     * belongs to.
     *
     * A store caches the parsed document, so re-creating one per call would re-read
     * and re-parse `settings.json` on every workspace resolution — and
     * `PtyLauncher.workspaceHost` is called from composition. The files-root key is
     * what keeps a bare-JVM check that uses a temporary directory from silently
     * reading the previous one's document.
     *
     * `projectFile = null` is the deliberate part: this object addresses the
     * **global** document only, because the project document is
     * `<workspace>/.pi/settings.json` and the workspace is what is being chosen.
     */
    private fun store(context: Context): PiSettingsFileStore {
        val root = context.filesDir.absolutePath
        val cached = scope
        if (cached != null && cached.filesRoot == root) return cached.store
        val paths = PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
        val created = PiSettingsFileStore(globalFile = File(paths.agentDir, "settings.json"))
        scope = Scope(root, created)
        return created
    }

    @Volatile
    private var scope: Scope? = null
}
