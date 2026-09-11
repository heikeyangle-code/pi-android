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
 * `<filesDir>/pi/workspaces/workspace-1`; `PiRuntime.baseBinds` adds the
 * shared-storage binds). This object deliberately *reads* that function instead of
 * repeating the constant, so if the workspace ever becomes user-selectable the
 * boundary follows it without a second edit — a gate that decided "inside" from a
 * stale hardcoded copy of the path would be worse than no gate, because it would
 * look like it worked.
 *
 * ### The guest spells that one directory two ways
 *
 * The chat engine and the terminal do not agree, and that is a fact about the
 * current code rather than a design:
 *
 *  - `PiEngineHost` computes `guestPathFor(workspace)` by stripping the
 *    `<filesDir>` prefix and mounting the result under `/workspace`, and passes it
 *    as both the bind target and the cwd (`engine/PiEngineHost.kt:256,286,552-556`).
 *    So the pi session's workspace is `/workspace/pi/workspaces/workspace-1`.
 *  - `PtyLauncher` binds the same host directory one level up, at plain
 *    `/workspace` (`runtime/PtyLauncher.kt:146,264`) — and its comment there claims
 *    that is "the path PiEngineHost maps a workspace to", which the line above
 *    shows is not so.
 *
 * [guestPath] publishes the engine's spelling, because the caller reading
 * `/app/health` is the process the engine spawned; [guestPathAliases] carries both
 * instead of one being guessed.
 *
 * ### Why the guest path is not an alias of the write boundary
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

    /** The engine's guest spelling of [hostPath] (see the class comment). */
    @Volatile
    private var engineGuestPath: String = GUEST_WORKSPACE_ROOT

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
        engineGuestPath = guestSpellingOf(canonical, canonicalize(context.filesDir.absolutePath))
    }

    override fun contains(path: String): Boolean {
        val canonical = canonicalize(path)
        if (canonical.isEmpty()) return false
        return aliases.any { alias -> canonical == alias || canonical.startsWith("$alias/") }
    }

    override fun shellPath(): String? = hostPath

    override fun isKnown(): Boolean = aliases.isNotEmpty()

    /**
     * The workspace as the **engine** spells it, i.e. where the process that reads
     * `/app/health` (pi's extension) actually sees it. This mirrors
     * `PiEngineHost.guestPathFor` (`engine/PiEngineHost.kt:552-556`): strip the
     * `<filesDir>` prefix, then mount under `/workspace`; an empty remainder is
     * `/workspace` itself.
     */
    fun guestPath(): String = engineGuestPath

    /**
     * Both spellings, engine first. The second is `PtyLauncher`'s plain
     * `/workspace` (`runtime/PtyLauncher.kt:264`), which is a different mount of the
     * same host directory, so a path valid in one is not valid in the other.
     */
    fun guestPathAliases(): List<String> = listOf(engineGuestPath, GUEST_WORKSPACE_ROOT).distinct()

    /** One line for the authorization page and for the model. */
    fun summary(): String = hostPath
        ?.let { "写入边界 = 工作区：$it（guest 内：$engineGuestPath；终端标签页：$GUEST_WORKSPACE_ROOT）" }
        ?: "写入边界 = 工作区：尚未确定（运行时还没启动）"

    /**
     * All spellings, for the diagnostics card.
     *
     * **当前无调用方**：诊断页读的是 [summary] 与 `/app/health` 的 `workspace`，没有展示
     * 这串别名列表。留着是因为"这个主机路径还有哪些写法"只有这里知道。
     */
    fun aliasSummary(): String = if (aliases.isEmpty()) "（未确定）" else aliases.joinToString("、")

    /**
     * `guestPathFor`, reproduced. Kept as a pure function of the two canonical paths
     * so the rule reads the same as the engine's rather than being a string trick at
     * the call site.
     */
    private fun guestSpellingOf(workspace: String, filesRoot: String): String {
        if (workspace.isEmpty()) return GUEST_WORKSPACE_ROOT
        val relative = when {
            workspace == filesRoot -> ""
            workspace.startsWith("$filesRoot/") -> workspace.removePrefix("$filesRoot/")
            // Outside <filesDir> the engine would mount the absolute path under
            // /workspace, which is what this reproduces rather than guessing a
            // friendlier name. Unreachable with today's workspace.
            else -> workspace.trimStart('/')
        }
        return if (relative.isEmpty()) GUEST_WORKSPACE_ROOT else "$GUEST_WORKSPACE_ROOT/$relative"
    }

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

    // A `const val` directly in the object. It used to sit in a `companion object`, which
    // Kotlin rejects outright here - "Modifier 'companion' is not applicable inside
    // 'standalone object'" - and the compiler then also reported the object itself as
    // inaccessible from DeviceBridgeRouter ("Cannot access 'companion object Companion':
    // it is private in 'DeviceWorkspace'"). One illegal modifier, two errors.
    /** Where a guest sees the workspace's mount point. */
    private const val GUEST_WORKSPACE_ROOT = "/workspace"
}
