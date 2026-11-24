package app.pi.runtime

import java.io.File

/**
 * Single source of truth for mapping project paths between Android host and Linux guest.
 *
 * ## Clean-slate directory contract
 *
 *  - Guest `$HOME` is `/root`.
 *  - Sandbox projects live inside the rootfs at `<rootfs>/root/projects/<name>`, which the
 *    guest sees natively as `/root/projects/<name>` (literally `~/projects/<name>`) with **zero**
 *    bind mounts.
 *  - The default project is `default` (`/root/projects/default` == `~/projects/default`).
 *  - Primary device shared storage (`/storage/emulated/0`, `/sdcard`) is bind-mounted at its
 *    real path by `GuestRecipe.binds`, so device projects keep their exact path on both sides.
 *  - Removable SD cards or other external volumes outside `rootfs` and primary shared storage
 *    are bind-mounted under [EXTERNAL_MOUNT_ROOT] (`/mnt/external/<trimmed>`).
 *  - The engine, terminal, `@` completion, package commands, and file manager all share this
 *    exact single spelling. There is no dual `/workspace` alias.
 */
object GuestWorkspacePath {

    /** Sandbox projects root inside rootfs (`<rootfs>/root/projects`). */
    const val PROJECTS_ROOT_RELATIVE: String = DurableLayout.PROJECTS_IN_ROOTFS

    /** Guest path of the sandbox projects root (`/root/projects`, i.e. `~/projects`). */
    const val PROJECTS_ROOT_GUEST: String = "/root/projects"

    /** Default sandbox project directory name. */
    const val DEFAULT_NAME: String = "default"

    /** Guest path of the default sandbox project (`/root/projects/default`). */
    const val DEFAULT_GUEST: String = "$PROJECTS_ROOT_GUEST/$DEFAULT_NAME"

    /** Guest mount prefix for removable external volumes that need an explicit bind. */
    const val EXTERNAL_MOUNT_ROOT: String = "/mnt/external"

    @Volatile
    private var adoptedGuestPath: String = DEFAULT_GUEST

    /** Currently active guest project cwd published by [WorkspaceStore]. */
    val CURRENT_GUEST: String get() = adoptedGuestPath

    /** Publish the active guest cwd for process-wide readers. Call only from [WorkspaceStore]. */
    fun adoptGuestPath(guestPath: String) {
        val clean = guestPath.trim().trimEnd('/')
        if (clean.startsWith("/")) {
            adoptedGuestPath = clean.ifEmpty { "/" }
        }
    }

    /** Host directory for an owned sandbox project [name] under [projectsRoot]. */
    fun projectDir(projectsRoot: File, name: String): File = File(projectsRoot, name)

    /** Ensures the owned sandbox project directory exists on disk and returns it. */
    fun ensureProjectDir(projectsRoot: File, name: String): File =
        projectDir(projectsRoot, name).also { it.mkdirs() }

    /**
     * Maps a host directory path to its canonical guest path given the rootfs directory path.
     *
     * Pure string function (testable on bare JVM without Android or filesystem I/O):
     *  1. Inside [rootfsPath] -> direct guest path (`/` or `/...`).
     *  2. Primary shared storage (`/storage/emulated/0`, `/sdcard`) -> unchanged.
     *  3. Anything else (e.g. removable SD card `/storage/1234-5678/...`) -> `/mnt/external/...`.
     */
    fun hostToGuest(rootfsPath: String, hostPath: String): String {
        val cleanRootfs = rootfsPath.trimEnd('/')
        val cleanHost = hostPath.trimEnd('/').ifEmpty { "/" }
        if (cleanRootfs.isNotEmpty()) {
            if (cleanHost == cleanRootfs) return "/"
            if (cleanHost.startsWith("$cleanRootfs/")) {
                return "/" + cleanHost.removePrefix("$cleanRootfs/").trimStart('/')
            }
        }
        if (isPrimarySharedStorage(cleanHost)) {
            return cleanHost
        }
        val trimmed = cleanHost.trimStart('/')
        return if (trimmed.isEmpty()) EXTERNAL_MOUNT_ROOT else "$EXTERNAL_MOUNT_ROOT/$trimmed"
    }

    /**
     * Canonicalizing overload of [hostToGuest] so `/data/user/0/...` and `/data/data/...`
     * resolve identically against [rootfs].
     */
    fun hostToGuest(rootfs: File, hostDir: File): String {
        val rootPath = runCatching { rootfs.canonicalPath }.getOrDefault(rootfs.absolutePath)
        val dirPath = runCatching { hostDir.canonicalPath }.getOrDefault(hostDir.absolutePath)
        return hostToGuest(rootPath, dirPath)
    }

    /**
     * True when [hostWorkspace] requires an explicit container `-b host:guest` bind mount to be
     * reachable at [guestWorkspace].
     *
     * Paths inside [rootfs] are reachable natively via the rootfs prefix; primary shared storage
     * (`/storage/emulated/0`, `/sdcard`) is already mounted by `GuestRecipe.binds` on every boot.
     * Only removable volumes outside those trees need an extra bind mount.
     */
    fun needsBind(rootfs: File, hostWorkspace: File, guestWorkspace: String): Boolean {
        val root = runCatching { rootfs.canonicalPath }.getOrDefault(rootfs.absolutePath).trimEnd('/')
        val host = runCatching { hostWorkspace.canonicalPath }.getOrDefault(hostWorkspace.absolutePath).trimEnd('/')
        if (host == root || host.startsWith("$root/")) return false
        return !isPrimarySharedStorage(guestWorkspace.trimEnd('/'))
    }

    /**
     * Converts a guest path to a path relative to [guestWorkspace], purely by prefix stripping.
     */
    fun relativeToWorkspace(path: String, guestWorkspace: String): String {
        val trimmed = path.trimStart('/')
        val engine = guestWorkspace.trimStart('/').trimEnd('/')
        if (engine.isNotEmpty()) {
            if (trimmed == engine) return ""
            if (trimmed.startsWith("$engine/")) return trimmed.removePrefix("$engine/")
        }
        return trimmed
    }

    /**
     * Formats a guest path for UI display following Pi SDK's `shortenHomePath` semantics
     * (`/root` -> `~`, `/root/projects/default` -> `~/projects/default`).
     */
    fun shortenForDisplay(guestPath: String): String {
        val clean = guestPath.trim().trimEnd('/').ifEmpty { "/" }
        return when {
            clean == "/root" -> "~"
            clean.startsWith("/root/") -> "~" + clean.removePrefix("/root")
            else -> clean
        }
    }

    private fun isPrimarySharedStorage(cleanPath: String): Boolean =
        cleanPath == "/storage/emulated/0" ||
            cleanPath.startsWith("/storage/emulated/0/") ||
            cleanPath == "/sdcard" ||
            cleanPath.startsWith("/sdcard/")
}
