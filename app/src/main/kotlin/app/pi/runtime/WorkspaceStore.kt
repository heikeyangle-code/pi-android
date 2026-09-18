package app.pi.runtime

import android.content.Context
import app.pi.settings.PiSettingsFileStore
import kotlinx.serialization.json.JsonArray
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
 * the default workspace and a [WorkspaceChoice.Current.note] that says so in
 * words; [reconcile] additionally writes the correction back, because the one
 * state the app must never be left in is "the setting says A while the process
 * runs B".
 *
 * The **rule** behind that answer is not written here: it is
 * [WorkspaceChoice.decide], Android-free and pinned by
 * `app/src/test/kotlin/app/pi/runtime/WorkspaceChoiceCheck.kt`. This object owns
 * the file IO (which name is in which document, is that a real directory) and the
 * three name/label rules delegate to the same object, so the harness executes the
 * rule that decides where the engine runs rather than a copy of it.
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

    /**
     * The workspaces that are **real device directories** the user picked, as a JSON
     * array of absolute host paths. App-owned, sidecar-only, for the same reason as
     * [SETTING_KEY].
     *
     * A path is the entry's **identity** here, exactly as a `workspace-N` directory
     * name is for an internal one, and for the same reason: pi records the cwd in
     * every session header and keys the project's trust entry by it. The label lives
     * in [NAMES_KEY] under that path, so a rename still cannot move anything.
     *
     * Why an array of paths and not a JSON object of records: the only field a record
     * would carry *besides* the path is the label, and the label already has a home
     * that both kinds of workspace share. Two homes for a label is how the two would
     * come apart.
     */
    const val EXTERNAL_KEY: String = "app.workspace.external"

    /** `<files>/pi/workspaces`, from the one object that owns the layout. */
    const val ROOT_RELATIVE: String = GuestWorkspacePath.ROOT_RELATIVE

    /** The workspace an install that has never chosen one uses. */
    val DEFAULT_NAME: String = GuestWorkspacePath.DEFAULT_NAME

    // ---------------------------------------------------------------- model

    /**
     * One workspace, as a screen needs it: name, path, and whether it is current.
     *
     * [name] is the identity — the directory name for an app-owned workspace
     * (`workspace-N`), the **absolute host path** for an external one ([external]).
     * [label] is the app-owned display name and equals [name] until the user renames
     * it; [displayName] is the single thing a row should print, and it is
     * [WorkspaceChoice.displayName] rather than a second copy of that rule.
     *
     * [external] is not a cosmetic flag: it is what decides whether [delete] may
     * remove the directory ([WorkspaceChoice.deleteRemovesFiles]) and whether
     * [currentHost] is allowed to `mkdirs()` it. An external workspace is the user's
     * own directory on shared storage; this app must never create or delete anything
     * there it was not asked to.
     *
     * [available] is false when the directory cannot be stat'ed right now (an SD card
     * that is out, a permission that was revoked, a directory the user deleted from a
     * file manager). Such an entry is still **listed** — the registration is not lost
     * and the row says so — but it cannot be switched to.
     */
    data class Entry(
        val name: String,
        val label: String,
        val host: File,
        val relative: String,
        val guestPath: String,
        val isCurrent: Boolean,
        val external: Boolean = false,
        val available: Boolean = true,
    ) {
        /** What a list row shows. */
        val displayName: String get() = WorkspaceChoice.displayName(name, label)
    }

    // `Resolution` used to be declared here. It is [WorkspaceChoice.Current] now: the
    // shape ("the name, what the settings asked for, and the sentence explaining a
    // correction") is the *answer to the rule*, so it lives next to the rule instead of
    // being restated by the object that only does the file IO.

    sealed interface Create {
        data class Ok(val entry: Entry) : Create

        /** Nothing was created. The message is written for the user. */
        data class Failed(val message: String) : Create
    }

    sealed interface Rename {
        data class Ok(val entry: Entry) : Rename

        data class Failed(val message: String) : Rename
    }

    /**
     * What a delete would remove, measured before anything is removed.
     *
     * [removesFiles] is the load-bearing field for an external workspace: false means
     * the operation is **unregistering** the directory, and the confirmation must say
     * so in words. When it is false [files]/[dirs]/[bytes] are all zero because
     * nothing was counted — printing "0 个文件" for a directory full of holiday
     * photos would be this app stating a fact it never measured.
     */
    data class DeleteTarget(
        val name: String,
        val label: String,
        val host: File,
        val files: Int,
        val dirs: Int,
        val bytes: Long,
        val removesFiles: Boolean = true,
        val external: Boolean = false,
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

    /** True for the directory names this app creates — [WorkspaceChoice.isOwned], the one rule. */
    fun isOwned(name: String): Boolean = WorkspaceChoice.isOwned(name)

    /**
     * Which workspace the process should be in, and the correction needed to get
     * there. **Read-only**: it adopts nothing and writes nothing.
     *
     * See the class KDoc for the fallback rule. A missing *default* workspace is
     * not a fallback — it is the first launch, and every caller already creates
     * the directory it is about to use (`GuestWorkspacePath.ensureHost`).
     */
    fun resolve(context: Context): WorkspaceChoice.Current {
        val requested = runCatching { store(context).read(SETTING_KEY) }
            .getOrNull()
            ?.let { (it as? JsonPrimitive)?.content }
        // The rule is [WorkspaceChoice.decide]'s; this object supplies the facts it refuses
        // to guess at — whether that name is one of ours (an app-owned `workspace-N` or a
        // registered external path), and whether the directory behind it is a real
        // directory. `look` is only reached for a name in that set, so `../..` never gets
        // `stat`ed.
        val known = externals(context)
        return WorkspaceChoice.decide(requested, DEFAULT_NAME, externals = known) { name ->
            val dir = if (isOwned(name)) File(root(context), name) else File(name)
            WorkspaceChoice.Look(
                isDirectory = dir.isDirectory,
                // A symlink is refused for the same reason it is refused a delete: the
                // workspace is the device shell's write boundary and the engine's cwd,
                // and both of those should describe a real directory this app owns
                // rather than wherever a link happens to point. Only a link is refused —
                // the default workspace is recreated on demand and never a link.
                isSymlink = Files.isSymbolicLink(dir.toPath()),
            )
        }
    }

    /**
     * [resolve], plus the two process-wide consequences of the answer: the current
     * workspace is published to [GuestWorkspacePath] (so every reader of the rule
     * moves with it) and a fallback is written back to the settings file.
     *
     * The write-back is what keeps a deleted workspace from producing the same
     * correction on every launch — and, more importantly, from leaving the stored
     * choice pointing at a directory nothing runs in. It is a correction of a
     * broken value, not a silent change of the user's choice:
     * [WorkspaceChoice.Current.note] carries the sentence the caller must show.
     */
    fun refresh(context: Context): WorkspaceChoice.Current {
        val resolved = resolve(context)
        GuestWorkspacePath.adoptRelative(if (isOwned(resolved.name)) relativeOf(resolved.name) else resolved.name)
        if (resolved.note != null && resolved.requested != null) {
            writeCurrent(context, resolved.name)
        }
        return resolved
    }

    /** [refresh] for a caller that also wants to know whether the fix was stored. */
    fun reconcile(context: Context): WorkspaceChoice.Current = refresh(context)

    /** The current workspace's directory name, without publishing or writing. */
    fun currentName(context: Context): String = resolve(context).name

    /**
     * The current workspace's host directory, created if missing.
     *
     * **Only an app-owned workspace is created.** `workspace-N` lives in app-private
     * storage and every launch path needs it to exist before a bind (`GuestWorkspacePath`
     * explains why); an external workspace is the user's own directory on shared
     * storage, and a `mkdirs()` there would create a directory on their device because
     * a settings value happened to name it. If the external directory is gone, [refresh]
     * has already fallen back to the default with a sentence — this accessor never
     * invents it.
     */
    fun currentHost(context: Context): File {
        val current = refresh(context).name
        if (!isOwned(current)) return File(current)
        return GuestWorkspacePath.ensureHost(context.filesDir, relativeOf(current))
    }

    /**
     * The entry for [name], or null when it is neither ours nor registered, or its
     * directory is gone.
     *
     * "Gone" includes an external workspace whose directory cannot be stat'ed right
     * now, because every caller of this uses it to decide whether the engine may be
     * moved there — and an engine cannot run in a directory that is not there.
     * [list] is the accessor that still *shows* such an entry (see [Entry.available]).
     */
    fun existing(context: Context, name: String): Entry? {
        if (!isOwned(name) && !externals(context).contains(name)) return null
        val dir = if (isOwned(name)) File(root(context), name) else File(name)
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return null
        return entry(context, name, dir)
    }

    /**
     * Every workspace the user can switch to: the app-owned `workspace-N` directories
     * (oldest number first) followed by the registered external directories (in the
     * order they were added), with the current one flagged.
     *
     * Creates the root and the default workspace if they are missing — the same
     * `mkdirs()` the engine and the terminal already do before binding them, so a
     * first launch lists the default instead of an empty list. **Nothing is ever
     * created for an external entry.**
     *
     * An external entry whose directory is not there right now is still listed, with
     * `available = false`: dropping the row would read as "the registration is gone",
     * and re-adding the card would silently restore it while the row had vanished.
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
            .sortedBy { WorkspaceChoice.number(it.name) ?: Int.MAX_VALUE }
            .toList()
        val owned = if (entries.none { it.name == DEFAULT_NAME }) {
            val dir = File(root, DEFAULT_NAME)
            dir.mkdirs()
            (entries + entry(context, DEFAULT_NAME, dir, names[DEFAULT_NAME], current))
                .sortedBy { WorkspaceChoice.number(it.name) ?: Int.MAX_VALUE }
        } else {
            entries
        }
        return owned + externalEntries(context, names, current)
    }

    /** The registered external workspaces, in registration order. */
    private fun externalEntries(context: Context, names: Map<String, String>, current: String): List<Entry> =
        externals(context).map { path ->
            val dir = File(path)
            entry(
                context = context,
                name = path,
                dir = dir,
                label = names[path],
                current = current,
                external = true,
                available = dir.isDirectory && !Files.isSymbolicLink(dir.toPath()),
            )
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
        val external = !isOwned(name)
        if (external && name !in externals(context)) {
            return Rename.Failed("工作区「$name」不存在或不是本应用登记的工作区，无法重命名。")
        }
        val dir = if (external) File(name) else File(root(context), name)
        if (!external && !dir.isDirectory) {
            return Rename.Failed("工作区「$name」不存在或不是本应用创建的，无法重命名。")
        }
        val cleaned = WorkspaceChoice.label(label)
            ?: return Rename.Failed("名称不能为空（也可以清空它，恢复为「$name」）。")
        // The **raw** map, not [labels]: that one drops entries whose directory is gone
        // (a card that is out, a workspace the user deleted from a file manager), and
        // renaming one workspace must not delete the labels of the others.
        val next = labelsRaw(context).toMutableMap()
        if (cleaned == name || cleaned == dir.name) next.remove(name) else next[name] = cleaned
        if (!writeLabels(context, next)) {
            return Rename.Failed("名称没有保存成功（无法写入设置文件）。")
        }
        return Rename.Ok(entry(context, name, dir, external = external))
    }

    /**
     * Count what a delete of [name] would remove, and hand back the only token
     * [delete] accepts.
     *
     * Two shapes, and the difference is the point:
     *
     *  - an app-owned `workspace-N` is **counted** — the number the confirmation has to
     *    show, because the directory is about to be removed;
     *  - an external workspace is **not counted at all**, because nothing will be
     *    removed. It is unregistered. Counting would cost a full walk of the user's
     *    directory (their photos, their project) to produce a number that decides
     *    nothing, and showing it next to "删除" would be the app implying it is about
     *    to delete them.
     *
     * Both walks use `Files.walkFileTree` without following links, so a symlink inside
     * the workspace is counted and removed as the link it is rather than followed out
     * of the tree.
     */
    fun previewDelete(context: Context, name: String): Preview {
        val external = !isOwned(name)
        if (external && name !in externals(context)) {
            return Preview.Refused("只能删除本应用创建的工作区（workspace-N），「$name」不是。")
        }
        val dir = if (external) File(name) else File(root(context), name)
        if (external) {
            if (name == currentName(context)) {
                return Preview.Refused("「${displayOf(context, name)}」是当前工作区，引擎正运行在里面。请先切换到别的工作区，再删掉它。")
            }
            // Deliberately no existence check: a registered directory that is not there
            // right now (the card is out) is exactly when a user wants to stop having it
            // registered, and unregistering it cannot fail for lack of a directory.
            val target = DeleteTarget(
                name = name,
                label = labels(context)[name] ?: name,
                host = dir,
                files = 0,
                dirs = 0,
                bytes = 0,
                removesFiles = false,
                external = true,
            )
            return Preview.Ok(target, DeleteConfirmation(target))
        }
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
     * Carry out what a [DeleteConfirmation] describes.
     *
     * **The two kinds do different things, and that is not a detail.** An app-owned
     * `workspace-N` is removed recursively — this app created that directory, and
     * there is no "empty it but keep it" mode on purpose: a workspace whose contents
     * were cleared still looks like a workspace to every list and to pi, and the user
     * asked for removal.
     *
     * An **external** workspace is only *unregistered*: its path leaves
     * [EXTERNAL_KEY] and its label leaves [NAMES_KEY], and **not one file or directory
     * on the user's storage is touched**. The user picked that directory; it existed
     * before this app knew about it and it is not this app's to delete
     * ([WorkspaceChoice.deleteRemovesFiles] is the rule, and it is pinned by a harness
     * so a future refactor cannot turn "forget this" into `rm -rf`).
     *
     * The app-owned branch re-counts before deleting, so a tree that changed between
     * the preview and the confirmation is reported with the number that was actually
     * removed rather than the stale one the user agreed to.
     */
    fun delete(context: Context, confirmation: DeleteConfirmation): Delete {
        val name = confirmation.target.name
        if (!isOwned(name)) {
            if (name !in externals(context)) {
                return Delete.Refused("只能删除本应用创建或登记过的工作区，「$name」不是。")
            }
            if (name == currentName(context)) {
                return Delete.Refused("「${displayOf(context, name)}」是当前工作区，请先切换到别的工作区。")
            }
            if (!writeExternals(context, externals(context) - name)) {
                return Delete.Failed("取消登记失败：无法写入设置文件，工作区仍在列表里。")
            }
            val labelled = labelsRaw(context).toMutableMap()
            if (labelled.remove(name) != null) writeLabels(context, labelled)
            return Delete.Ok(name, 0, 0)
        }
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
        val labelled = labelsRaw(context)
        if (!deleteTree(dir)) {
            return Delete.Failed("删除工作区「$name」失败，可能有文件正被占用。")
        }
        val next = labelled.toMutableMap()
        if (next.remove(name) != null) writeLabels(context, next)
        return Delete.Ok(name, before.files, before.bytes)
    }

    // --------------------------------------------------------- external roots

    /**
     * Register a **real device directory** as a workspace, and return its entry.
     *
     * The path is the identity ([Entry.name]) and the only thing that is written is
     * [EXTERNAL_KEY] — one JSON array in the app's sidecar. Nothing on the device is
     * read, moved, created or deleted: the directory may be empty, and if it is not, it
     * becomes the engine's cwd with whatever it already contains.
     *
     * Registering twice is idempotent (the second call returns the same entry) rather
     * than an error, because the picker can be opened again and the user's intent is
     * unambiguous. It does **not** switch to it: registering a directory and moving the
     * engine into it are two decisions — the same rule [create] follows.
     *
     * The caller is responsible for having checked the user's permission and for
     * [WorkspaceChoice.pickRefusal]; this object does not ask for permissions, and it
     * deliberately does not require the directory to exist right now (the registration
     * is a statement of intent, and [Entry.available] reports the rest).
     */
    fun registerExternal(context: Context, path: String): Create {
        val clean = path.trim().trimEnd('/')
        if (clean.isEmpty() || !clean.startsWith("/")) {
            return Create.Failed("只能登记设备上的绝对路径。")
        }
        val dir = File(clean)
        val current = externals(context)
        if (clean !in current) {
            if (!writeExternals(context, current + clean)) {
                return Create.Failed("没有保存成功（无法写入设置文件），这个目录没有被登记。")
            }
        }
        return Create.Ok(entry(context, clean, dir, external = true, available = dir.isDirectory))
    }

    /** The registered external workspace paths, in registration order. */
    fun externals(context: Context): List<String> {
        val raw = runCatching { store(context).read(EXTERNAL_KEY) }.getOrNull()
        val array = raw as? JsonArray ?: return emptyList()
        val seen = LinkedHashSet<String>()
        array.forEach { element ->
            val path = (element as? JsonPrimitive)?.content?.trim()?.trimEnd('/')
            if (!path.isNullOrEmpty() && path.startsWith("/")) seen += path
        }
        return seen.toList()
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
        // `RELATIVE` is the spelling internal readers use for the current workspace, and
        // an external workspace has no such spelling (it is not under the files
        // directory). Publishing its absolute path keeps `GuestWorkspacePath.host()` and
        // `PiProject.workspaceName` from silently describing a directory under `<files>`
        // that does not exist; nothing that *decides* where the engine runs reads this
        // value — `currentHost` and `PiEngineHost` both derive from the store.
        GuestWorkspacePath.adoptRelative(if (isOwned(name)) relativeOf(name) else name)
        return true
    }

    /**
     * The labels currently stored, without the ones for workspaces that are gone.
     *
     * A **view**: it drops entries whose directory cannot be listed right now, so a
     * screen never prints a label for a workspace that is not there. Writes must use
     * [labelsRaw] instead — writing this filtered map back would erase the labels of
     * everything that happened to be unavailable at that moment.
     */
    fun labels(context: Context): Map<String, String> {
        val known = externals(context)
        val root = root(context)
        return labelsRaw(context).filterKeys { key ->
            if (isOwned(key)) File(root, key).isDirectory else key in known && File(key).isDirectory
        }
    }

    /** The stored label map exactly as it is on disk (see [labels]). */
    fun labelsRaw(context: Context): Map<String, String> {
        val raw = runCatching { store(context).read(NAMES_KEY) }.getOrNull() as? JsonObject ?: return emptyMap()
        return raw.mapNotNull { (key, value) ->
            val label = (value as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
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
        external: Boolean = false,
        available: Boolean = true,
    ): Entry = Entry(
        name = name,
        label = label ?: name,
        host = dir,
        // `relative` is the workspace's path relative to the files directory, and that is
        // what `GuestWorkspacePath` needs. An external workspace is not under it, so the
        // honest relative form is the absolute path itself — and `guestPath` below is what
        // anything that wants the guest's spelling must read (they are not the same thing
        // for an external workspace, which is exactly why both fields exist).
        relative = if (external) dir.absolutePath else relativeOf(name),
        guestPath = GuestWorkspacePath.under(context.filesDir.absolutePath, dir.absolutePath),
        isCurrent = name == current,
        external = external,
        available = available,
    )

    private fun nextFreeName(root: File): String? =
        WorkspaceChoice.nextFreeName(root.listFiles().orEmpty().map { it.name })

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

    private fun writeExternals(context: Context, paths: List<String>): Boolean =
        writeSetting(context, EXTERNAL_KEY, JsonArray(paths.map { JsonPrimitive(it) as JsonElement }))

    /**
     * A name for a sentence when the label may not be known: the stored label, else the
     * last path segment (an external path's `name` is a whole path and reading it out
     * loud in a dialog is worse than the folder name).
     */
    private fun displayOf(context: Context, name: String): String {
        val label = labelsRaw(context)[name]
        if (!label.isNullOrBlank()) return label
        if (isOwned(name)) return name
        return File(name).name.ifEmpty { name }
    }

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
