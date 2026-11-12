package app.pi.runtime.os

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * 把 Termux 官方 `bootstrap-<arch>.zip` 解成一个可用的前缀目录（`…/files/usr`）。
 *
 * 这是官方 Termux App 的「第一阶段」安装（`TermuxInstaller`）的逐条对应：
 *  - zip 里的普通条目写到前缀下；
 *  - `SYMLINKS.txt` 每行 `目标←链接路径`，全部条目解完后再建符号链接（链接路径相对前缀，目标原样写入）；
 *  - 可执行位：`bin/`、`libexec/`、`lib/apt/apt-helper`、`lib/apt/methods/` 下的文件设为可执行
 *    （官方就是这四处；zip 本身不带权限位）；
 *  - 先解到 `usr.staging`，成功后整体改名为 `usr`：中途被杀不会留下半个前缀。
 *
 * 「第二阶段」（postinst 脚本）要在系统里**执行**，由 [TermuxRecipe.secondStageCommand] 交给 proot 去跑，不在这里。
 *
 * 纯 JVM、无 Android 依赖：`TermuxBootstrapCheck` 直接对真实 zip 验证。
 */
object TermuxBootstrap {

    data class Result(val files: Int, val dirs: Int, val symlinks: Int)

    private const val SYMLINKS = "SYMLINKS.txt"
    private const val SYMLINK_SEPARATOR = '←'

    /** 官方 `TermuxInstaller` 里被设为可执行的前缀相对路径。 */
    internal fun isExecutable(entry: String): Boolean =
        entry.startsWith("bin/") || entry.startsWith("libexec") ||
            entry.startsWith("lib/apt/apt-helper") || entry.startsWith("lib/apt/methods")

    /**
     * @param prefix 目标前缀目录（例如 `…/data/data/com.termux/files/usr`）；已存在则视为已安装，原样返回 null。
     * @return 解包统计；已存在时 null。
     */
    fun extract(zip: InputStream, prefix: File): Result? {
        if (prefix.exists()) return null
        val parent = prefix.parentFile ?: throw IOException("前缀没有父目录：$prefix")
        parent.mkdirs()
        val staging = File(parent, prefix.name + ".staging")
        if (staging.exists() && !staging.deleteRecursively()) throw IOException("清不掉上次残留的 $staging")
        staging.mkdirs()

        var files = 0
        var dirs = 0
        val links = ArrayList<Pair<String, String>>()
        val root = staging.toPath().toAbsolutePath().normalize()

        ZipInputStream(zip).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                if (name == SYMLINKS) {
                    // 每行：目标←链接路径（链接路径以 ./ 开头，相对前缀）
                    for (line in zin.readBytes().toString(Charsets.UTF_8).lineSequence()) {
                        if (line.isBlank()) continue
                        val at = line.indexOf(SYMLINK_SEPARATOR)
                        if (at <= 0) throw IOException("SYMLINKS.txt 行格式不对：$line")
                        links += line.substring(0, at) to line.substring(at + 1).removePrefix("./")
                    }
                    continue
                }
                val target = root.resolve(name).normalize()
                // zip-slip：任何落到前缀外面的条目都拒绝。
                if (!target.startsWith(root)) throw IOException("条目逃出前缀：$name")
                if (entry.isDirectory) {
                    Files.createDirectories(target); dirs++
                    continue
                }
                Files.createDirectories(target.parent)
                Files.copy(zin, target, StandardCopyOption.REPLACE_EXISTING)
                if (isExecutable(name)) target.toFile().setExecutable(true, false)
                files++
            }
        }
        for ((to, path) in links) {
            val link = root.resolve(path).normalize()
            if (!link.startsWith(root)) throw IOException("符号链接逃出前缀：$path")
            Files.createDirectories(link.parent)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, Paths.get(to))
        }
        if (!staging.renameTo(prefix)) throw IOException("无法把 $staging 改名为 $prefix")
        return Result(files, dirs, links.size)
    }
}
