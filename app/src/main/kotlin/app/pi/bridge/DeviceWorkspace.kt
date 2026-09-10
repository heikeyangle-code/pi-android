package app.pi.bridge

import android.content.Context
import app.pi.runtime.PtyLauncher
import java.io.File

/**
 * The workspace, as the device shell has to spell it — the concrete
 * [ShellWriteBoundary] the guard asks about.
 *
 * ### Where the path comes from
 *
 * [PtyLauncher.workspaceHost] is the runtime layer's single source of truth for
 * "the directory the user selected as the workspace" (today it is
 * `<filesDir>/pi/workspaces/workspace-1`, bind-mounted into the guest as
 * `/workspace`; `PiRuntime.baseBinds` adds the shared-storage binds). This object
 * deliberately *reads* that function instead of repeating the constant, so if the
 * workspace ever becomes user-selectable the boundary follows it without a second
 * edit — a gate that decided "inside" from a stale hardcoded copy of the path
 * would be worse than no gate, because it would look like it worked.
 *
 * ### Why the guest path `/workspace` is not an alias
 *
 * The device shell runs in the *host* namespace, where `/workspace` does not
 * exist. Accepting it would let the policy say "allowed" to a command that then
 * fails with ENOENT. It is left out on purpose, and the refusal names the host
 * path instead, which is the honest answer to "write inside the workspace".
 *
 * ### What the boundary is not
 *
 * It does not bound the *guest*. `PiRuntime.baseBinds` binds the device's shared
 * storage (and the workspace) into the proot guest, so pi's own built-in `bash`
 * sees `/sdcard` as the user's storage. That is the guest's workbench and it is
 * outside this gate on purpose (the gate's jurisdiction is the device tools), and
 * in any case the guest process runs as this app's uid under `untrusted_app`, so
 * its file access is exactly the app's — the platform's scoped-storage rules are
 * what bound it, not this class.
 */
object DeviceWorkspace : ShellWriteBoundary {

    /** Only used if the runtime layer's accessor itself fails. */
    private const val FALLBACK_RELATIVE = "pi/workspaces/workspace-1"

    @Volatile
    private var aliases: List<String> = emptyList()

    @Volatile
    private var hostPath: String? = null

    /** Re-read the workspace. Cheap (no I/O beyond a `File` construction). */
    fun refresh(context: Context) {
        val host = runCatching { PtyLauncher.workspaceHost(context).absolutePath }
            .getOrElse { File(context.filesDir, FALLBACK_RELATIVE).absolutePath }
        val canonical = canonicalize(host)
        val set = LinkedHashSet<String>()
        set.add(canonical)
        // The same directory spelled with either app-data prefix.
        set.add(canonical.replace("/data/data/", "/data/user/0/"))
        set.add(canonical.replace("/data/user/0/", "/data/data/"))
        // If the user's workspace lives on shared storage, all three spellings of
        // that storage are the same directory, and the device shell reaches it by
        // whichever one the caller wrote.
        for (external in listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary")) {
            if (canonical == external || canonical.startsWith("$external/")) {
                for (other in listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary")) {
                    set.add(other + canonical.removePrefix(external))
                }
            }
        }
        aliases = set.filter { it.isNotEmpty() }.toList()
        hostPath = canonical
    }

    override fun contains(path: String): Boolean {
        val canonical = canonicalize(path)
        if (canonical.isEmpty()) return false
        return aliases.any { alias -> canonical == alias || canonical.startsWith("$alias/") }
    }

    override fun shellPath(): String? = hostPath

    override fun isKnown(): Boolean = aliases.isNotEmpty()

    /** One line for the authorization page and for the model. */
    fun summary(): String = hostPath
        ?.let { "写入边界 = 工作区：$it（guest 内是 /workspace）" }
        ?: "写入边界 = 工作区：尚未确定（运行时还没启动）"

    /** All spellings, for the diagnostics card. */
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
