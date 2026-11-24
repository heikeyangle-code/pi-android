package app.pi.runtime

import android.content.Context
import app.pi.i18n.tr
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
 * Manages the user's projects and the active project `cwd`.
 *
 * ## Clean-slate layout
 *
 *  - Owned sandbox projects live directly under `<rootfs>/root/projects/<name>`, which the guest
 *    sees natively as `/root/projects/<name>` (literally `~/projects/<name>`).
 *  - The default project is `default` (`/root/projects/default`).
 *  - Registered custom / device directories (e.g. `/storage/emulated/0/...` or any writable
 *    container directory outside `/root/projects/`) are stored in [EXTERNAL_KEY] by their
 *    absolute host path and are never copied, moved, or deleted when removed from the list.
 *  - Active choice ([SETTING_KEY]), display labels ([NAMES_KEY]), and registered directories
 *    ([EXTERNAL_KEY]) live in Pi's global `settings.json` (`~/.pi/agent/settings.json`).
 */
object WorkspaceStore {

    const val SETTING_KEY: String = "app.workspace.current"
    const val NAMES_KEY: String = "app.workspace.names"
    const val EXTERNAL_KEY: String = "app.workspace.external"

    /** Default owned project directory name (`default`). */
    const val DEFAULT_NAME: String = GuestWorkspacePath.DEFAULT_NAME

    // ---------------------------------------------------------------- model

    /**
     * One project entry:
     *  - [name]: owned directory name under `/root/projects/` (e.g. `"default"`, `"my-app"`),
     *    or absolute host path for registered external/custom directories.
     *  - [guestPath]: canonical POSIX path inside the Linux guest (`GuestWorkspacePath.hostToGuest`).
     *  - [external]: true for directories outside `/root/projects/` (unregistering never deletes files).
     */
    data class Entry(
        val name: String,
        val label: String,
        val host: File,
        val guestPath: String,
        val isCurrent: Boolean,
        val external: Boolean = false,
        val available: Boolean = true,
    ) {
        val displayName: String get() = WorkspaceChoice.displayName(name, label.takeIf { it != name })
    }

    sealed interface Create {
        data class Ok(val entry: Entry) : Create
        data class Failed(val message: String) : Create
    }

    sealed interface Rename {
        data class Ok(val entry: Entry) : Rename
        data class Failed(val message: String) : Rename
    }

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

    class DeleteConfirmation internal constructor(
        val target: DeleteTarget,
    )

    sealed interface Preview {
        data class Ok(val target: DeleteTarget, val confirmation: DeleteConfirmation) : Preview
        data class Refused(val message: String) : Preview
    }

    sealed interface Delete {
        data class Ok(val name: String, val files: Int, val bytes: Long) : Delete
        data class Refused(val message: String) : Delete
        data class Failed(val message: String) : Delete
    }

    // ------------------------------------------------------------- resolving

    /** `<rootfs>/root/projects` (guest `/root/projects`, i.e. `~/projects`). */
    fun root(context: Context): File = pathsOf(context).projectsRoot

    private fun rootfs(context: Context): File = pathsOf(context).rootfs

    private fun pathsOf(context: Context): PiPaths = PiPaths(
        filesDir = context.filesDir,
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
    )

    fun nameForGuestCwd(guestCwd: String): String? =
        WorkspaceChoice.nameForGuestCwd(guestCwd)

    fun isOwned(name: String): Boolean = WorkspaceChoice.isOwned(name)

    fun resolve(context: Context): WorkspaceChoice.Current {
        val requested = runCatching { store(context).read(SETTING_KEY) }
            .getOrNull()
            ?.let { (it as? JsonPrimitive)?.content }
        val known = externals(context)
        return WorkspaceChoice.decide(requested, DEFAULT_NAME, externals = known) { name ->
            val dir = if (isOwned(name)) File(root(context), name) else File(name)
            WorkspaceChoice.Look(
                isDirectory = dir.isDirectory,
                isSymlink = Files.isSymbolicLink(dir.toPath()),
            )
        }
    }

    fun refresh(context: Context): WorkspaceChoice.Current {
        val resolved = resolve(context)
        val dir = if (isOwned(resolved.name)) File(root(context), resolved.name) else File(resolved.name)
        GuestWorkspacePath.adoptGuestPath(GuestWorkspacePath.hostToGuest(rootfs(context), dir))
        if (resolved.note != null && resolved.requested != null) {
            writeCurrent(context, resolved.name)
        }
        return resolved
    }

    fun reconcile(context: Context): WorkspaceChoice.Current = refresh(context)

    fun currentName(context: Context): String = resolve(context).name

    fun currentHost(context: Context): File {
        val current = refresh(context).name
        if (!isOwned(current)) return File(current)
        return GuestWorkspacePath.ensureProjectDir(root(context), current)
    }

    fun existing(context: Context, name: String): Entry? {
        if (!isOwned(name) && !externals(context).contains(name)) return null
        val dir = if (isOwned(name)) File(root(context), name) else File(name)
        if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return null
        return entry(context, name, dir, external = !isOwned(name))
    }

    fun list(context: Context): List<Entry> {
        val root = root(context)
        root.mkdirs()
        val names = labels(context)
        val current = currentName(context)
        val entries = root.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && isOwned(it.name) }
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .map { entry(context, it.name, it, names[it.name], current, external = false) }
            .sortedWith(ownedOrder)
            .toList()
        val owned = if (entries.none { it.name == DEFAULT_NAME }) {
            val dir = File(root, DEFAULT_NAME).also { it.mkdirs() }
            (entries + entry(context, DEFAULT_NAME, dir, names[DEFAULT_NAME], current, external = false))
                .sortedWith(ownedOrder)
        } else {
            entries
        }
        return owned + externalEntries(context, names, current)
    }

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

    fun create(context: Context, requested: String? = null): Create {
        val root = root(context)
        if (!root.isDirectory && !root.mkdirs()) {
            return Create.Failed(tr("无法创建项目根目录：${root.absolutePath}（存储空间或权限问题）。", "Could not create project root directory: ${root.absolutePath}."))
        }
        val name = requested?.trim()?.takeIf { it.isNotEmpty() }
            ?: WorkspaceChoice.nextDefaultProjectName(root.listFiles().orEmpty().map { it.name })
        WorkspaceChoice.nameProblem(name)?.let { return Create.Failed(it) }
        val dir = File(root, name)
        if (dir.exists()) return Create.Failed(tr("已经有叫「$name」的项目了。", "A project named \"$name\" already exists."))
        if (!dir.mkdirs() || !dir.isDirectory) {
            return Create.Failed(tr("无法创建项目目录：${dir.absolutePath}（存储空间或权限问题）。", "Could not create project directory: ${dir.absolutePath}."))
        }
        return Create.Ok(entry(context, name, dir, external = false))
    }

    fun createInDirectory(context: Context, parentDir: File, folderName: String): Create {
        val cleanName = folderName.trim()
        WorkspaceChoice.nameProblem(cleanName)?.let { return Create.Failed(it) }
        if (!parentDir.isDirectory && !parentDir.mkdirs()) {
            return Create.Failed(tr("无法访问目标目录：${parentDir.absolutePath}", "Cannot access parent directory: ${parentDir.absolutePath}"))
        }
        val target = File(parentDir, cleanName)
        if (target.exists()) {
            return Create.Failed(tr("这里已经有叫「$cleanName」的文件夹了。", "A folder named \"$cleanName\" already exists here."))
        }
        if (!target.mkdirs() || !target.isDirectory) {
            return Create.Failed(tr("无法创建项目目录：${target.absolutePath}（存储空间或权限问题）。", "Could not create project directory: ${target.absolutePath}."))
        }
        return registerDirectory(context, target)
    }

    fun registerDirectory(context: Context, dir: File): Create {
        if (!dir.isDirectory) {
            return Create.Failed(tr("目录不存在：${dir.absolutePath}", "Directory does not exist: ${dir.absolutePath}"))
        }
        val rootCanonical = runCatching { root(context).canonicalFile }.getOrDefault(root(context).absoluteFile)
        val dirCanonical = runCatching { dir.canonicalFile }.getOrDefault(dir.absoluteFile)
        if (dirCanonical.parentFile == rootCanonical && isOwned(dirCanonical.name)) {
            return Create.Ok(entry(context, dirCanonical.name, dirCanonical, external = false))
        }
        return registerExternal(context, dirCanonical.absolutePath)
    }

    fun hostDirForGuestCwd(context: Context, guestCwd: String): File? {
        val clean = guestCwd.trim().trimEnd('/')
        if (clean.isEmpty()) return null
        val mapped = nameForGuestCwd(clean)
        if (mapped != null) {
            val candidate = if (isOwned(mapped)) File(root(context), mapped) else File(mapped)
            if (candidate.isDirectory && !Files.isSymbolicLink(candidate.toPath())) return candidate
        }
        if (clean.startsWith("/storage/") || clean.startsWith("/sdcard/")) {
            val direct = File(clean)
            if (direct.isDirectory && !Files.isSymbolicLink(direct.toPath())) return direct
        }
        if (clean.startsWith("/")) {
            val inRootfs = File(rootfs(context), clean.removePrefix("/"))
            if (inRootfs.isDirectory && !Files.isSymbolicLink(inRootfs.toPath())) return inRootfs
        }
        return null
    }

    fun rename(context: Context, name: String, label: String): Rename {
        val external = !isOwned(name)
        if (external && name !in externals(context)) {
            return Rename.Failed(tr("项目「$name」不存在或未登记，无法重命名。", "Project \"$name\" does not exist or is not registered."))
        }
        val dir = if (external) File(name) else File(root(context), name)
        if (!external && !dir.isDirectory) {
            return Rename.Failed(tr("项目「$name」不存在，无法重命名。", "Project \"$name\" does not exist."))
        }
        val cleaned = WorkspaceChoice.label(label)
            ?: return Rename.Failed(tr("名称不能为空。", "Name cannot be empty."))
        val next = labelsRaw(context).toMutableMap()
        if (cleaned == name || cleaned == dir.name) next.remove(name) else next[name] = cleaned
        if (!writeLabels(context, next)) {
            return Rename.Failed(tr("名称没有保存成功（无法写入设置文件）。", "Could not save name (settings file not writable)."))
        }
        return Rename.Ok(entry(context, name, dir, external = external))
    }

    fun previewDelete(context: Context, name: String): Preview {
        val external = !isOwned(name)
        if (external && name !in externals(context)) {
            return Preview.Refused(tr("只能删除项目根目录下的项目，「$name」是外部登记的目录（可以取消登记，不会删文件）。", "\"$name\" is an external folder and can only be unregistered, not deleted."))
        }
        val dir = if (external) File(name) else File(root(context), name)
        if (external) {
            if (name == currentName(context)) {
                return Preview.Refused(tr("「${displayOf(context, name)}」是当前项目，请先切换到别的项目再移除。", "\"${displayOf(context, name)}\" is the active project. Switch to another project first."))
            }
            val target = DeleteTarget(
                name = name,
                label = labels(context)[name] ?: WorkspaceChoice.displayName(name, null),
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
            return Preview.Refused(tr("项目「${displayOf(context, name)}」的目录不存在。", "Project \"${displayOf(context, name)}\" directory does not exist."))
        }
        if (Files.isSymbolicLink(dir.toPath())) {
            return Preview.Refused(tr("项目「$name」是一个符号链接，已拒绝删除。", "Project \"$name\" is a symlink; deletion refused."))
        }
        if (name == currentName(context)) {
            return Preview.Refused(tr("「${displayOf(context, name)}」是当前项目，请先切换到别的项目再删除。", "\"${displayOf(context, name)}\" is the active project. Switch to another project first."))
        }
        val tally = tally(dir) ?: return Preview.Refused(tr("无法读取项目「${displayOf(context, name)}」的内容，已拒绝删除。", "Could not read project \"${displayOf(context, name)}\"; deletion refused."))
        val target = DeleteTarget(
            name = name,
            label = labels(context)[name] ?: WorkspaceChoice.displayName(name, null),
            host = dir,
            files = tally.files,
            dirs = tally.dirs,
            bytes = tally.bytes,
        )
        return Preview.Ok(target, DeleteConfirmation(target))
    }

    fun delete(context: Context, confirmation: DeleteConfirmation): Delete {
        val name = confirmation.target.name
        if (!isOwned(name)) {
            if (name !in externals(context)) {
                return Delete.Refused(tr("只能移除已登记的项目，「$name」不在列表中。", "\"$name\" is not registered."))
            }
            if (name == currentName(context)) {
                return Delete.Refused(tr("「${displayOf(context, name)}」是当前项目，请先切换到别的项目。", "\"${displayOf(context, name)}\" is the active project. Switch first."))
            }
            if (!writeExternals(context, externals(context) - name)) {
                return Delete.Failed(tr("移除失败：无法写入设置文件。", "Failed to remove project (settings file not writable)."))
            }
            val labelled = labelsRaw(context).toMutableMap()
            if (labelled.remove(name) != null) writeLabels(context, labelled)
            return Delete.Ok(name, 0, 0)
        }
        val dir = File(root(context), name)
        if (!dir.isDirectory) return Delete.Refused(tr("项目「${displayOf(context, name)}」已经不存在了。", "Project \"${displayOf(context, name)}\" no longer exists."))
        if (Files.isSymbolicLink(dir.toPath())) {
            return Delete.Refused(tr("项目「$name」是一个符号链接，已拒绝删除。", "Project \"$name\" is a symlink; deletion refused."))
        }
        if (name == currentName(context)) {
            return Delete.Refused(tr("「${displayOf(context, name)}」是当前项目，请先切换到别的项目再删除。", "\"${displayOf(context, name)}\" is the active project. Switch first."))
        }
        val root = root(context).canonicalFile
        val canonical = runCatching { dir.canonicalFile }.getOrNull()
            ?: return Delete.Failed(tr("无法解析项目「$name」的路径，已拒绝删除。", "Could not resolve path for project \"$name\"; deletion refused."))
        if (canonical.parentFile != root) {
            return Delete.Refused(tr("项目「$name」不在 ${root.absolutePath} 下，已拒绝删除。", "Project \"$name\" is not under ${root.absolutePath}; deletion refused."))
        }
        val before = tally(dir) ?: return Delete.Failed(tr("无法读取项目「$name」的内容，已拒绝删除。", "Could not read project \"$name\"; deletion refused."))
        val labelled = labelsRaw(context)
        if (!deleteTree(dir)) {
            return Delete.Failed(tr("删除项目「${displayOf(context, name)}」失败，可能有文件正被占用。", "Failed to delete project \"${displayOf(context, name)}\"; files may be in use."))
        }
        val next = labelled.toMutableMap()
        if (next.remove(name) != null) writeLabels(context, next)
        return Delete.Ok(name, before.files, before.bytes)
    }

    // --------------------------------------------------------- external roots

    fun registerExternal(context: Context, path: String): Create {
        val clean = path.trim().trimEnd('/')
        if (clean.isEmpty() || !clean.startsWith("/")) {
            return Create.Failed(tr("只能登记设备上的绝对路径。", "Only absolute device paths can be registered."))
        }
        val dir = File(clean)
        val current = externals(context)
        if (clean !in current) {
            if (!writeExternals(context, current + clean)) {
                return Create.Failed(tr("没有保存成功（无法写入设置文件），这个目录没有被登记。", "Failed to register directory (settings file not writable)."))
            }
        }
        return Create.Ok(entry(context, clean, dir, external = true, available = dir.isDirectory))
    }

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

    fun setCurrent(context: Context, name: String): Boolean {
        val target = existing(context, name) ?: return false
        if (!writeCurrent(context, name)) return false
        GuestWorkspacePath.adoptGuestPath(target.guestPath)
        return true
    }

    fun labels(context: Context): Map<String, String> {
        val known = externals(context)
        val root = root(context)
        return labelsRaw(context).filterKeys { key ->
            if (isOwned(key)) File(root, key).isDirectory else key in known && File(key).isDirectory
        }
    }

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
        external: Boolean = !isOwned(name),
        available: Boolean = true,
    ): Entry = Entry(
        name = name,
        label = label ?: name,
        host = dir,
        guestPath = GuestWorkspacePath.hostToGuest(rootfs(context), dir),
        isCurrent = name == current,
        external = external,
        available = available,
    )

    /** `default` first, then alphabetical by directory name. */
    private val ownedOrder: Comparator<Entry> =
        compareBy<Entry>({ if (it.name == DEFAULT_NAME) 0 else 1 }, { it.name.lowercase() })

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

    private fun displayOf(context: Context, name: String): String =
        WorkspaceChoice.displayName(name, labelsRaw(context)[name])

    private fun writeSetting(context: Context, key: String, value: JsonElement): Boolean =
        runCatching { store(context).write(key, value) }.isSuccess

    private class Scope(val rootKey: String, val store: PiSettingsFileStore)

    private fun store(context: Context): PiSettingsFileStore {
        val paths = pathsOf(context)
        val rootKey = paths.rootfs.absolutePath
        val cached = scope
        if (cached != null && cached.rootKey == rootKey) return cached.store
        val created = PiSettingsFileStore(globalFile = File(paths.agentDir, "settings.json"))
        scope = Scope(rootKey, created)
        return created
    }

    @Volatile
    private var scope: Scope? = null
}
