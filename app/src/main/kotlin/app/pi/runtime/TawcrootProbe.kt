package app.pi.runtime

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 「tawcroot 在这台设备上是否真的能用」的门禁——与 [ProrootProbe] 同一个角色，同一种缓存形状。
 *
 * ## 为什么阶段比 proroot 少
 *
 * proroot 的第一阶段是 raw `svc` 探针：它的翻译靠 seccomp + 运行库 hook，内联 `svc`（musl 静态的
 * `rg`/`fd`）可能绕过翻译，所以要专门测「泄漏」。tawcroot 的路径翻译发生在 **seccomp 陷阱里**
 * （`RET_TRAP` → 进程内 `SIGSYS` handler），内联 `svc` 同样会被陷住，没有「绕过」这一类失败，
 * 所以不需要那一阶段。剩下两件要在真机上证明的事一样要证明：
 *
 *  1. **工具链**：`rg` / `fd` / `git` 等真正跑得起来（[GuestToolProbe]，`engine = Tawcroot`）。
 *  2. **动态二进制**：guest 里的 glibc 动态程序能被 tawcroot 自带的 ELF loader 加载并 exec
 *     （[ProrootExecProbe]，同一条 guest 命令、同一个解析器）。
 *
 * 另外它依赖的内核/策略（seccomp 过滤器能装、`SIGSYS` 不被 zygote 的 filter 吞掉）只有真机上
 * 跑一次才知道——这就是门禁存在的理由：没通过就回 proot，原因写进状态行。
 */
object TawcrootProbe {

    const val EXEC_TIMEOUT_MS: Long = 20_000L

    private const val MAX_OUTPUT_CHARS = 64_000
    private const val CACHE_VERSION = "t3"

    fun key(revision: String, digest: String): String =
        ProrootProbeCache.key(revision, digest, CACHE_VERSION)

    @Volatile
    private var cachedDigest: Triple<String, Long, Long>? = null
    @Volatile
    private var cachedDigestValue: String = ""

    /** 二进制内容的摘要：缓存的结论只对**这一份**二进制成立。进程内按 `(path, size, mtime)` 缓存。 */
    fun digestOf(paths: PiPaths): String {
        val file = paths.tawcrootBinary()
        if (!file.isFile) return ""
        val key = Triple(file.absolutePath, file.length(), file.lastModified())
        if (cachedDigest == key && cachedDigestValue.isNotEmpty()) return cachedDigestValue
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                md.update(buffer, 0, read)
            }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        cachedDigestValue = hex
        cachedDigest = key
        return hex
    }

    fun cached(paths: PiPaths, revision: String, digest: String): ProrootProbe.Verdict? {
        val key = key(revision, digest)
        val text = runCatching { paths.tawcrootProbeCache().readText() }.getOrNull()
        val parsed = ProrootProbeCache.parse(text, key) ?: return null
        return ProrootProbe.Verdict(passed = parsed.passed, key = key, detail = parsed.detail, cached = true)
    }

    /** 跑两个阶段、写缓存（失败也写：下一次启动不必为同一个结论再付一次钱）。阻塞。 */
    fun run(paths: PiPaths, storage: File?, revision: String, digest: String): ProrootProbe.Verdict {
        val key = key(revision, digest)
        val detail = mutableListOf<String>()

        val tools = runCatching { GuestToolProbe.run(paths, storage, GuestEngine.Tawcroot) }.getOrElse { error ->
            GuestToolProbe.Report(
                emptyList(),
                launchError = "tawcroot 工具链探针抛了异常：${error::class.java.simpleName}: ${error.message}",
            )
        }
        detail += tools.describe()

        val exec = runExecStage(paths, storage)
        detail += exec.describe().map { "  $it" }

        val passed = tools.ok && exec.ok
        runCatching {
            paths.runtime.mkdirs()
            paths.tawcrootProbeCache().writeText(ProrootProbeCache.render(key, passed, detail))
        }
        return ProrootProbe.Verdict(
            passed = passed,
            key = key,
            detail = detail,
            cached = false,
            tools = tools,
            exec = exec,
        )
    }

    private fun runExecStage(paths: PiPaths, storage: File?): ProrootExecProbe.Report {
        val output = runCatching {
            val argv = GuestCommandLine.build(
                paths, GuestEngine.Tawcroot, ProrootExecProbe.guestCommand(), cwd = "/", storage = storage,
            )
            val builder = ProcessBuilder(argv)
                .directory(TawcrootCommand.hostCwd(paths))
                .redirectErrorStream(true)
                .also { it.environment().putAll(GuestCommandLine.environment(paths, GuestEngine.Tawcroot)) }
            val process = PersistentProcessSpawner.start(builder)
            val finished = runCatching { process.waitFor(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!finished) {
                val partial = drainProcessAvailable(process.inputStream, MAX_OUTPUT_CHARS, 300L)
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                runCatching { process.outputStream.close() }
                val tail = partial.trim().takeLast(200)
                if (tail.isBlank()) {
                    "tawcroot 探针 ${EXEC_TIMEOUT_MS / 1000} 秒没有返回"
                } else {
                    "tawcroot 探针 ${EXEC_TIMEOUT_MS / 1000} 秒没有返回；已收到的输出：$tail"
                }
            } else {
                val code = runCatching { process.exitValue() }.getOrNull()
                val text = process.inputStream.reader().use { it.readText() }.take(MAX_OUTPUT_CHARS)
                runCatching { process.outputStream.close() }
                // 没有标记行时把**进程退出码**和第一行原始输出补进去：tawcroot 崩溃（139）、
                // bind 解析失败（84）、seccomp 装不上（88）在这里一眼分得开。没有这两个数，
                // 真机上只能看到"没输出"，这正是这次卡住的原因。
                if (ProrootExecProbe.MARKER !in text) {
                    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    buildString {
                        if (text.isNotBlank()) append(text).append('\n')
                        append("tawcroot: 探针退出码 ").append(code ?: "未知")
                        if (first != null) append("；第一行输出：").append(first.take(200))
                        append('\n')
                    }
                } else text
            }
        }.getOrElse { error -> "tawcroot 启动探针失败：${error::class.java.simpleName}: ${error.message}" }
        return ProrootExecProbe.parse(output)
    }
}
