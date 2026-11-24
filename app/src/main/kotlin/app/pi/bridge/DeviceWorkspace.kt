package app.pi.bridge

import android.content.Context
import app.pi.runtime.GuestWorkspacePath
import app.pi.runtime.PiPaths
import app.pi.runtime.PtyLauncher
import java.io.File

/**
 * Tracks the active project directory on both the Android host and inside the Linux guest,
 * published by `/app/health` and diagnostics.
 */
object DeviceWorkspace {

    @Volatile
    private var aliases: List<String> = emptyList()

    @Volatile
    private var hostPath: String? = null

    @Volatile
    private var engineGuestPath: String = GuestWorkspacePath.DEFAULT_GUEST

    /** Re-read the active project directory from [WorkspaceStore]. */
    fun refresh(context: Context) {
        val paths = PiPaths(
            filesDir = context.filesDir,
            nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        )
        val host = runCatching { PtyLauncher.workspaceHost(context).absolutePath }
            .getOrElse { File(paths.projectsRoot, GuestWorkspacePath.DEFAULT_NAME).absolutePath }
        val canonical = canonicalize(host)
        val set = LinkedHashSet<String>()
        set.add(canonical)
        set.add(canonical.replace("/data/data/", "/data/user/0/"))
        set.add(canonical.replace("/data/user/0/", "/data/data/"))
        for (external in listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary")) {
            if (canonical == external || canonical.startsWith("$external/")) {
                for (other in listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary")) {
                    set.add(other + canonical.removePrefix(external))
                }
            }
        }
        aliases = set.filter { it.isNotEmpty() }.toList()
        hostPath = canonical
        engineGuestPath = GuestWorkspacePath.hostToGuest(
            canonicalize(paths.rootfs.absolutePath),
            canonical,
        )
    }

    fun shellPath(): String? = hostPath

    fun isKnown(): Boolean = aliases.isNotEmpty()

    /** The active project's canonical guest path (identical for engine and terminal). */
    fun guestPath(): String = engineGuestPath

    fun summary(): String = hostPath
        ?.let { "当前项目：$it（guest 内：$engineGuestPath）" }
        ?: "当前项目：尚未确定（运行时还没启动）"

    fun aliasSummary(): String = if (aliases.isEmpty()) "（未确定）" else aliases.joinToString("、")

    private fun canonicalize(path: String): String {
        val clean = path.trim().trim('"', '\'')
        if (clean.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (segment in clean.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
