package app.pi.bridge

/**
 * The host-directory strings a guest path can mean, as plain data.
 *
 * Deliberately free of `Context`, `File` and `android.net.Uri`: the mapping is the
 * one part of the image byte channel with a security argument behind it (see
 * [GuestPathMapping]), and keeping it dependency-free is what makes it testable on
 * a bare JVM — `app/src/test/kotlin/app/pi/bridge/GuestPathMappingCheck.kt` pins the
 * order with no device and no Gradle.
 */
internal class GuestPathRoots(
    /** `<filesDir>` — the engine mirrors it at `/workspace`. */
    val filesDir: String,
    /** `<filesDir>/pi/runtime/rootfs`, proot's `--rootfs`. */
    val rootfs: String,
    /** `<filesDir>/pi/runtime/tmp`, bound as guest `/tmp`. */
    val tmp: String,
    /** `<filesDir>/pi/.pi/agent`, bound as guest `/root/.pi/agent`. */
    val agentDir: String,
    /** The workspace directory, bound as the terminal's plain `/workspace`. */
    val workspaceHost: String?,
    /** The device's shared storage, bound as `/sdcard` and `/storage/emulated/0`. */
    val storage: String,
)

/**
 * guest 路径 → host 候选文件的映射，纯字符串运算。
 *
 * Nothing in pi defines an image link, so this is app-side knowledge and therefore
 * has to be *documented* rather than derived: see `GuestImageBytes` for the pi
 * citations and for why each bind exists. What lives here is only the order.
 *
 * **The order is the safety argument.** Two rules:
 *
 *  1. A bind mapping is tried **before** the rootfs. A guest `/tmp/x.png` names the
 *     bound `<files>/pi/runtime/tmp/x.png`; `<rootfs>/tmp/x.png` is a *different*
 *     directory that merely has the same name, so it must never win.
 *  2. For an absolute path with no known bind, the rootfs is tried **before** the
 *     literal host path. `<rootfs>/etc/hosts` is the file the guest means; the
 *     phone's own `/etc/hosts` exists too, and returning it would render content
 *     the model never referred to. The literal probe is kept last so a real host
 *     path (`/data/user/0/<pkg>/...`, `/storage/emulated/0/...`) still resolves.
 */
internal object GuestPathMapping {

    /**
     * @param spellings the same link written more than one way (raw, then
     *   percent-decoded), most literal first. Decoding happens in the caller so this
     *   file needs no Android API.
     * @return host paths in priority order, de-duplicated, as strings.
     */
    fun candidates(spellings: List<String>, roots: GuestPathRoots): List<String> {
        val out = LinkedHashSet<String>()

        fun add(path: String?) {
            if (!path.isNullOrEmpty()) out.add(path)
        }

        for (spelling in spellings) {
            // An empty spelling would otherwise fall into the relative branch and invent
            // a `<workspaceHost>/` candidate. Nothing produces one today (the caller
            // refuses an empty link), and this keeps the pure function total anyway.
            if (spelling.isEmpty()) continue
            when {
                spelling == WORKSPACE || spelling.startsWith("$WORKSPACE/") -> {
                    val relative = spelling.removePrefix(WORKSPACE).trimStart('/')
                    if (relative.isNotEmpty()) {
                        // Engine spelling: guest /workspace mirrors <filesDir>
                        // (PiEngineHost.guestPathFor, engine/PiEngineHost.kt:552-556).
                        add("${roots.filesDir}/$relative")
                        // Terminal spelling: guest /workspace IS the workspace
                        // directory (runtime/PtyLauncher.kt:146,264).
                        roots.workspaceHost?.let { add("$it/$relative") }
                    }
                    add("${roots.rootfs}/${spelling.trimStart('/')}")
                }

                spelling == "/tmp" || spelling.startsWith("/tmp/") -> {
                    val relative = spelling.removePrefix("/tmp").trimStart('/')
                    if (relative.isNotEmpty()) add("${roots.tmp}/$relative")
                    add("${roots.rootfs}/${spelling.trimStart('/')}")
                }

                spelling.startsWith("/root/.pi/agent/") -> {
                    add("${roots.agentDir}/${spelling.removePrefix("/root/.pi/agent/")}")
                    add("${roots.rootfs}/${spelling.trimStart('/')}")
                }

                spelling == "/sdcard" || spelling.startsWith("/sdcard/") -> {
                    val relative = spelling.removePrefix("/sdcard").trimStart('/')
                    if (relative.isNotEmpty()) add("${roots.storage}/$relative")
                    add(spelling)
                }

                spelling.startsWith("/storage/emulated/0/") -> {
                    add("${roots.storage}/${spelling.removePrefix("/storage/emulated/0/")}")
                    add(spelling)
                }

                spelling.startsWith("/storage/self/primary/") -> {
                    add("${roots.storage}/${spelling.removePrefix("/storage/self/primary/")}")
                    add(spelling)
                }

                spelling.startsWith("/") -> {
                    add("${roots.rootfs}/${spelling.trimStart('/')}")
                    add(spelling)
                }

                else -> {
                    // Relative: pi's cwd is the workspace (PiEngineHost.kt:283).
                    roots.workspaceHost?.let { add("$it/$spelling") }
                    add("${roots.filesDir}/$spelling")
                    add(spelling)
                }
            }
        }
        return out.toList()
    }

    /** Where a guest sees the workspace's mount point. */
    const val WORKSPACE = "/workspace"
}
