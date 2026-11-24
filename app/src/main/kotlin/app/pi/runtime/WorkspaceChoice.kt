package app.pi.runtime

import app.pi.i18n.tr

/**
 * Pure, Android-free rules for project naming, selection, validation, and session grouping.
 */
object WorkspaceChoice {

    /** Maximum characters for a custom project display label. */
    const val MAX_LABEL_CHARS: Int = 40

    /** Maximum characters for a single sandbox project directory name. */
    const val MAX_NAME_CHARS: Int = 64

    /**
     * Validates a sandbox project directory name under `/root/projects/`.
     * Returns a user-facing error message, or `null` when valid.
     */
    fun nameProblem(raw: String): String? = when {
        raw.isEmpty() -> tr("名字不能为空。", "Name cannot be empty.")
        raw.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } ->
            tr("名字里不能有 / \\ 或控制字符。", "Name cannot contain /, \\, or control characters.")
        raw == "." || raw == ".." ->
            tr("「$raw」不能当目录名。", "\"$raw\" cannot be used as a directory name.")
        raw.startsWith('.') ->
            tr("不能以点开头（. 开头的是隐藏目录，pi 的 .pi 就在里面）。", "Name cannot start with a dot (hidden directories).")
        raw != raw.trim() || raw.endsWith('.') ->
            tr("名字首尾不能是空格，结尾也不能是点。", "Name cannot start/end with spaces or end with a dot.")
        raw.length > MAX_NAME_CHARS ->
            tr("名字最多 $MAX_NAME_CHARS 个字符。", "Name must be at most $MAX_NAME_CHARS characters.")
        else -> null
    }

    /**
     * True when [name] is a valid single-segment project directory directly inside `/root/projects/`.
     * Registered external/custom paths always start with `/` and therefore return `false`.
     */
    fun isOwned(name: String): Boolean = nameProblem(name) == null

    /**
     * Generates a clean default folder name (`project`, `project-2`, `project-3`, ...) that does not
     * collide with [existing]. Used only if a caller asks to create a sandbox project without specifying a name.
     */
    fun nextDefaultProjectName(existing: Collection<String>): String {
        val used = existing.map { it.lowercase() }.toHashSet()
        if ("project" !in used) return "project"
        var index = 2
        while ("project-$index" in used) index++
        return "project-$index"
    }

    /** Sanitizes a custom display label; returns `null` when empty. */
    fun label(raw: String): String? =
        raw.filterNot { it.isISOControl() }.trim().take(MAX_LABEL_CHARS).ifEmpty { null }

    /**
     * Single source of truth for displaying a project's human-readable name.
     */
    fun displayName(name: String, label: String?): String {
        val custom = label?.trim()?.takeIf { it.isNotEmpty() && it != name }
        if (custom != null) return custom
        if (name == GuestWorkspacePath.DEFAULT_NAME) return tr("默认项目", "Default Project")
        if (name.startsWith("/")) {
            val leaf = name.trimEnd('/').substringAfterLast('/')
            if (leaf.isNotEmpty()) return leaf
        }
        return name
    }

    /**
     * Formats the group heading for a Pi session's recorded guest `cwd`.
     */
    fun sessionGroupLabel(
        cwd: String,
        projectsRootGuest: String = GuestWorkspacePath.PROJECTS_ROOT_GUEST,
        labels: Map<String, String>,
    ): String {
        val raw = cwd.trim()
        if (raw.isEmpty()) return tr("工作目录未记录", "Working directory not recorded")
        val clean = raw.trimEnd('/')
        if (clean.isEmpty()) return "/"
        val root = projectsRootGuest.trimEnd('/')
        val prefix = "$root/"
        if (clean.startsWith(prefix)) {
            val leaf = clean.removePrefix(prefix)
            if (leaf.isNotEmpty() && !leaf.contains('/') && isOwned(leaf)) {
                return displayName(leaf, labels[leaf])
            }
        }
        labels[clean]?.takeIf { it.isNotBlank() }?.let { return it }
        return clean.substringAfterLast('/').ifEmpty { clean }
    }

    /** Deduplicated, ordered device storage volume roots (primary shared storage first). */
    fun volumeRoots(primary: String?, removable: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        (listOfNotNull(primary) + removable).forEach { raw ->
            val clean = raw.trim().trimEnd('/')
            if (clean.isNotEmpty()) out += clean
        }
        return out.toList()
    }

    /**
     * Explains why a host storage directory cannot be picked as an external project, or `null` if allowed.
     */
    fun pickRefusal(path: String, volumes: List<String>, readable: Boolean): String? {
        val clean = path.trim().trimEnd('/').ifEmpty { "/" }
        if (!clean.startsWith("/")) return tr("只能选设备上的绝对路径。", "Only absolute device paths are allowed.")
        val forbidden = setOf(
            "/", "/system", "/vendor", "/apex", "/proc", "/sys", "/dev", "/etc", "/data", "/root",
            "/storage", "/mnt", "/sbin", "/bin", "/lib", "/usr", "/boot", "/init",
        )
        if (clean in forbidden) {
            return tr("「$clean」是系统目录，不能设为项目目录。", "\"$clean\" is a system directory and cannot be used as a project.")
        }
        val inVolume = volumes.any { volume -> clean == volume || clean.startsWith("$volume/") }
        if (!inVolume) {
            val where = volumes.joinToString(tr("、", ", "))
                .ifEmpty { tr("（这台设备没报告任何存储卷）", "(no storage volumes reported)") }
            return tr("只能选存储卡或共享存储里的目录（当前可用：$where）。", "Only directories under shared storage or SD card are allowed ($where).")
        }
        if (!readable) {
            return tr("这个目录现在读不到（可能是存储卡被拔出、正在卸载，或缺少权限），请换一个。", "This directory is not readable right now (storage unmounted or missing permission).")
        }
        return null
    }

    /**
     * Maps a host path to its canonical guest path via [GuestWorkspacePath.hostToGuest].
     */
    fun guestPathOf(hostPath: String, rootfsPath: String): String =
        GuestWorkspacePath.hostToGuest(rootfsPath, hostPath)

    /**
     * Maps a guest `cwd` recorded in a Pi session header back to a project identity:
     *  - Direct child of `/root/projects/<name>` -> `<name>` (owned project).
     *  - Under `/storage/...` or `/sdcard/...` -> same host path.
     *  - Under `/mnt/external/<host>` -> `/<host>`.
     *  - Any other rootfs directory -> `null` (caller resolves via `hostDirForGuestCwd`).
     */
    fun nameForGuestCwd(
        guestCwd: String,
        projectsRootGuest: String = GuestWorkspacePath.PROJECTS_ROOT_GUEST,
    ): String? {
        val clean = guestCwd.trim().trimEnd('/')
        if (clean.isEmpty() || clean == "/") return null
        val rootPrefix = projectsRootGuest.trimEnd('/') + "/"
        if (clean.startsWith(rootPrefix)) {
            val leaf = clean.removePrefix(rootPrefix)
            return leaf.takeIf { it.isNotEmpty() && !it.contains('/') && isOwned(it) }
        }
        val externalPrefix = GuestWorkspacePath.EXTERNAL_MOUNT_ROOT.trimEnd('/') + "/"
        if (clean.startsWith(externalPrefix)) {
            val hostPath = "/" + clean.removePrefix(externalPrefix)
            return hostPath.takeIf { it.length > 1 }
        }
        return clean.takeIf {
            it.length > 1 && (it.startsWith("/storage/") || it.startsWith("/sdcard/"))
        }
    }

    /**
     * Only owned sandbox projects under `/root/projects/` delete files from disk when removed.
     * Registered external / custom directories are only unregistered; their files are never touched.
     */
    fun deleteRemovesFiles(name: String): Boolean = isOwned(name)

    data class Look(val isDirectory: Boolean, val isSymlink: Boolean)

    sealed interface Current {
        val name: String
        val requested: String?
        val note: String?
    }

    data class Chosen(
        override val name: String,
        override val requested: String?,
    ) : Current {
        override val note: String? get() = null
    }

    data class Fallback(
        override val name: String,
        override val requested: String?,
        override val note: String,
    ) : Current

    fun decide(
        requested: String?,
        defaultName: String,
        externals: Collection<String> = emptySet(),
        look: (String) -> Look,
    ): Current {
        val name = requested?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Chosen(defaultName, null)
        if (!isOwned(name) && name !in externals) {
            return Fallback(
                name = defaultName,
                requested = name,
                note = tr("设置里的当前项目「$name」不可用，已回到默认项目。", "Saved project \"$name\" is unavailable; switched back to Default Project."),
            )
        }
        val probe = look(name)
        if (probe.isSymlink) {
            return Fallback(
                name = defaultName,
                requested = name,
                note = if (name == defaultName) {
                    tr("默认项目目录是一个符号链接，请把它换成真实目录。", "Default project directory is a symlink; please replace it with a real directory.")
                } else {
                    tr("项目「$name」是一个符号链接，已回到默认项目。", "Project \"$name\" is a symlink; switched back to Default Project.")
                },
            )
        }
        if (!probe.isDirectory) {
            if (name == defaultName) return Chosen(defaultName, name)
            return Fallback(
                name = defaultName,
                requested = name,
                note = tr("项目「$name」的目录不存在，已回到默认项目。", "Project directory \"$name\" no longer exists; switched back to Default Project."),
            )
        }
        return Chosen(name, name)
    }

    sealed interface Pick {
        object Unavailable : Pick
        object AlreadyHere : Pick
        object ConfirmInterrupt : Pick
        object Switch : Pick
    }

    fun decidePick(isCurrent: Boolean, turnRunning: Boolean, available: Boolean = true): Pick = when {
        !available -> Pick.Unavailable
        isCurrent -> Pick.AlreadyHere
        turnRunning -> Pick.ConfirmInterrupt
        else -> Pick.Switch
    }
}
