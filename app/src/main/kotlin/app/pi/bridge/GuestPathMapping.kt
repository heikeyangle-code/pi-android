package app.pi.bridge

import app.pi.runtime.GuestWorkspacePath

/**
 * Pure host-root descriptor for resolving guest image links to host files.
 */
internal class GuestPathRoots(
    /** `<filesDir>/pi/runtime/rootfs` — container rootfs. */
    val rootfs: String,
    /** `PiPaths.agentDir` — `<rootfs>/root/.pi/agent`. */
    val agentDir: String,
    /** Active project host directory (for resolving relative paths). */
    val workspaceHost: String?,
    /** Device shared storage root (`/storage/emulated/0`). */
    val storage: String,
)

/**
 * Maps a guest (or relative) file path string to prioritized host file path candidates.
 */
internal object GuestPathMapping {

    fun candidates(spellings: List<String>, roots: GuestPathRoots): List<String> {
        val out = LinkedHashSet<String>()

        fun add(path: String?) {
            if (!path.isNullOrEmpty()) out.add(path)
        }

        val externalPrefix = GuestWorkspacePath.EXTERNAL_MOUNT_ROOT.trimEnd('/') + "/"

        for (spelling in spellings) {
            if (spelling.isEmpty()) continue
            when {
                spelling.startsWith(externalPrefix) -> {
                    val hostPath = "/" + spelling.removePrefix(externalPrefix).trimStart('/')
                    if (hostPath.length > 1) add(hostPath)
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
                    roots.workspaceHost?.let { add("$it/$spelling") }
                    add(spelling)
                }
            }
        }
        return out.toList()
    }
}
