package app.pi.engine

import java.io.File
import java.io.IOException

/** APK assets 的最小读取面；真实现包 `AssetManager`，harness 用内存/临时目录实现。 */
interface HostAssetSource {
    /** [dir] 下的直接子项名（文件与目录都列）。目录不存在返回空。 */
    fun list(dir: String): List<String>

    /** 读一个文件的全部字节。 */
    fun read(path: String): ByteArray

    /** [path] 是目录吗（有子项即目录）。 */
    fun isDirectory(path: String): Boolean = list(path).isNotEmpty()
}

/**
 * 把 `assets/guest/sdk-host/` 整棵树复制到 `<rootfs>/opt/pi/sdk-host/`。
 *
 * ## 为什么每次启动都做、而不是「装一次」
 * 目标在**易失树**里：显式修复（`ensureReady(rebuild = true)`）会删掉整个 rootfs，运行时重新
 * 解包也会换掉它。与 `installScandirFix` 同一个理由 —— 每次启动一遍，消除「上次还在、这次没了」
 * 这一整类问题。树很小（约 120 KB / 18 个文件），所以做成「内容相同就不写」：重复启动零写入，
 * 也避免每次都碰闪存。
 *
 * ## 同步语义
 * - 源里有、目标里内容不同或没有 → 写（先写 `.partial` 再改名，半个文件交给 `node` 是启动失败）。
 * - **目标里有、源里没有 → 删**（宿主改名/删文件后，旧文件不能留下被 `import` 到）。
 * - 只管 `sdk-host/` 这一棵树，不碰 `/opt/pi` 里别的东西（引擎、scandir-fix）。
 */
class SdkHostInstaller(
    private val assets: HostAssetSource,
    private val assetRoot: String = ASSET_ROOT,
) {

    data class Result(
        val written: List<String>,
        val removed: List<String>,
        val unchanged: Int,
    ) {
        val total: Int get() = written.size + unchanged
    }

    /**
     * @param target `<rootfs>/opt/pi/sdk-host`
     * @throws IOException 写不进去时 —— 调用方决定是否致命（宿主装不上 = 引擎起不来，应当致命，
     *   并把这句话放进启动失败卡片）。
     */
    fun install(target: File): Result {
        val wanted = LinkedHashMap<String, String>() // 相对路径 → asset 路径
        collect("", wanted)
        if (wanted.isEmpty()) throw IOException("APK 里没有 $assetRoot（assets 没打进去？）")

        val written = mutableListOf<String>()
        var unchanged = 0
        for ((rel, assetPath) in wanted) {
            val dest = File(target, rel)
            val bytes = assets.read(assetPath)
            if (dest.isFile && dest.length() == bytes.size.toLong() && dest.readBytes().contentEquals(bytes)) {
                unchanged++
                continue
            }
            dest.parentFile?.mkdirs()
            val staging = File(dest.parentFile, dest.name + ".partial")
            staging.writeBytes(bytes)
            if (!staging.renameTo(dest)) {
                dest.writeBytes(bytes)
                staging.delete()
            }
            written += rel
        }

        val removed = mutableListOf<String>()
        if (target.isDirectory) {
            val keep = wanted.keys
            target.walkBottomUp().forEach { f ->
                if (f == target) return@forEach
                val rel = f.relativeTo(target).invariantSeparatorsPath
                if (f.isFile && rel !in keep) {
                    if (f.delete()) removed += rel
                } else if (f.isDirectory && f.listFiles().isNullOrEmpty()) {
                    f.delete()
                }
            }
        }
        return Result(written, removed, unchanged)
    }

    private fun collect(rel: String, out: MutableMap<String, String>) {
        val dirPath = if (rel.isEmpty()) assetRoot else "$assetRoot/$rel"
        for (name in assets.list(dirPath)) {
            val childRel = if (rel.isEmpty()) name else "$rel/$name"
            val childPath = "$assetRoot/$childRel"
            if (assets.isDirectory(childPath)) collect(childRel, out) else out[childRel] = childPath
        }
    }

    companion object {
        const val ASSET_ROOT = "guest/sdk-host"
    }
}
