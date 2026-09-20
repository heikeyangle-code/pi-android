package app.pi.packages

import app.pi.rpc.Ansi

/**
 * pi 的「扩展加载失败」长什么样，以及怎么让它变成一条能看见的读数。
 *
 * ## 为什么需要它（`android_*` 消失那类问题的唯一出口）
 *
 * pi 对**每一种**资源目录的发现都是「`existsSync` 通过 → `readdirSync` → 出错就静默吞掉」：
 *
 *  - `core/extensions/loader.ts:712-744`（`discoverExtensionsInDir`）：`readdirSync` 抛错 →
 *    `catch { return [] }`，**整个目录静默变成 0 个扩展**，`LoadExtensionsResult.errors` 里
 *    一个字节都不留；
 *  - `core/package-manager.ts:312-359`（`collectFiles`，prompts/themes/包内资源）与
 *    `:365-395`（`collectSkillEntries`，skills）：同一个形状，`catch { // Ignore errors }`。
 *
 * 而 `existsSync`/`statSync` 与 `readdirSync` 在两套运行时的**别名拼写绑定**目录里行为不同。
 * 本机实测（同一台 proroot guest，launcher argv 里有
 * `-b /data/user/0/com.dsh.client/cache/shm:/dev/shm`，Node v24.19.0）：
 *
 * ```
 * fs.existsSync('/dev/shm')   -> true           ← 存在性没问题
 * fs.readdirSync('/dev/shm')  -> ENOENT         ← 目录内容读不出来
 * fs.opendirSync('/dev/shm')  -> 0 项，且不报错  ← 甚至静默为空
 * ```
 *
 * 所以「文件在不在」与「pi 能不能发现它」是两个问题，而后者失败时**没有任何输出**。
 *
 * ## 这里能看见的那一半
 *
 * pi 在 RPC 模式（非交互）下把 startup diagnostics 打到 **stderr**（`main.ts:98-104` 的
 * `reportDiagnostics`，调用点 `:900-906`），其中每条扩展加载失败是
 *
 * ```
 * Error: Failed to load extension "<path>": <error>
 * ```
 *
 * （message 在 `main.ts:786-789` 拼出），并且只要有一条 `error` 级 diagnostic，pi 会紧接着
 * **以退出码 1 结束**（`main.ts:905-910`）。`EngineDiagnostics.stderr` 抓的就是这一段，所以
 * 这里把它解析成「几条 + 第一条原文」。
 *
 * 目录遍历静默失败的那一半**不会**留下 stderr（`catch` 把它吃了）；判别它只有一条路：在
 * guest 里对 pi 会扫的目录真的做一次 `readdir`（`ui/settings/DiagnosticsReport.kt` 的那一节
 * 把这句命令写给了用户）。
 *
 * Android-free（只依赖 `:rpc` 的 [Ansi] 与 stdlib），所以 bare-JVM harness 能逐条覆盖它。
 */
object PiExtensionLoadErrors {

    /** pi 那行里的字面标记；避免把整句抄两遍。 */
    const val MARKER: String = "Failed to load extension \""

    /**
     * 报告里最多列几条原文。诊断报告是给人读的，而这一类失败通常是**同一个原因**重复多次
     * （目录读不到 ⇒ 目录里每一项各报一次），所以条数永远报全、原文只留前几条。
     */
    const val MAX_LISTED: Int = 5

    /** 一条解析出来的失败。 */
    data class Failure(
        /** pi 报的扩展路径，原样保留（它可能就是模型/用户需要去修的那一个）。 */
        val path: String,
        /** 冒号之后的原文；pi 没给详情时为空串。 */
        val message: String,
    )

    /** 一次解析的结果。空列表是"没发现"，不是"读了但读不到"。 */
    data class Report(val failures: List<Failure>) {
        val count: Int get() = failures.size
        val first: Failure? get() = failures.firstOrNull()

        /**
         * 报告里的若干行。
         *
         * 没有失败时**一行就说完**，不写"0 条"了事：这句话的用途是区分"引擎没报"与
         * "引擎没跑过/没退出"，而那件事由调用方在紧邻的一行里说（见 `DiagnosticsReport`）。
         */
        fun describe(): List<String> = if (failures.isEmpty()) {
            listOf("  引擎 stderr 里没有扩展加载失败的行")
        } else {
            buildList {
                add("  扩展加载失败 ${count} 条；第一条：${first?.path.orEmpty()}")
                if (first?.message?.isNotBlank() == true) add("    ${first!!.message}")
                if (count > 1) {
                    add("  其余 ${count - 1} 条（最多再列 ${minOf(count - 1, MAX_LISTED - 1)} 条）：")
                    failures.drop(1).take(MAX_LISTED - 1).forEach { add("    · ${it.path}：${it.message}") }
                }
            }
        }
    }

    /**
     * 从引擎捕获的 stderr 里解析。
     *
     * 先过 [Ansi.strip]：pi 用 chalk 着色（`reportDiagnostics` 的 `chalk.red`），而颜色在
     * 管道下通常已关闭——但"通常"不是契约，去掉转义字符后匹配才是。
     */
    fun parse(stderr: String?): Report {
        if (stderr.isNullOrBlank()) return Report(emptyList())
        val failures = ArrayList<Failure>()
        Ansi.strip(stderr).lineSequence().forEach { raw ->
            val line = raw.trim()
            val markerAt = line.indexOf(MARKER)
            if (markerAt < 0) return@forEach
            val afterMarker = line.substring(markerAt + MARKER.length)
            val closingQuote = afterMarker.indexOf('"')
            if (closingQuote < 0) return@forEach
            val path = afterMarker.substring(0, closingQuote)
            val message = afterMarker.substring(closingQuote + 1).trim().removePrefix(":").trim()
            failures += Failure(path = path, message = message)
        }
        return Report(failures)
    }
}
