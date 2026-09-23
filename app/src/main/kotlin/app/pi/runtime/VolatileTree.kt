package app.pi.runtime

import java.io.File

/**
 * 「这个路径在易失树里吗」——一条纯函数回答的那个问题。
 *
 * ## 为什么它必须存在，而不是一句注释
 *
 * `<files>/pi/runtime` 是**易失**的（`RuntimeProvisioner` 会重建它），
 * `<files>/pi/workspaces`、`<files>/pi/.pi/agent` 和 `<files>/pi/persist` 是**耐久**的。
 * 用户报过「升级完软件，工作区根目录的东西全没了」，所以这条边界不能只是一个约定：
 * 只要有**任何一处**把耐久目录算进了易失树，升级就会删用户的东西，而这件事在编译期
 * 不会响、在设备上也是静默的。
 *
 * 于是边界被写成一个能被断言、能被 bare-JVM harness 钉住的函数：
 *
 *  - [PiPaths] 在构造时用它检查三个耐久目录都不在 [PiPaths.runtime] 之下，违反就抛；
 *  - `RuntimeProvisioner` 的每一次递归删除都先问它，不在易失树里就拒绝删；
 *  - `tools/run-app-pure-checks.sh` 的 `runtime-payload-state` harness 钉住这两种行为，
 *    并演示「如果工作区真的落在 runtime 下就必须报错」的反例。
 *
 * ## 判定是**词法**的
 *
 * 用 `toPath().normalize()` 消掉 `..` 和重复分隔符之后比较路径前缀，**不解析符号链接**。
 * 这是刻意的：这些目录由本 App 自己拼出来，符号链接解析会引入 IO、可能抛异常，而且
 * 一个把工作区做进易失树的符号链接同样应该被拒绝，而不是被解析掉。前缀比较带分隔符，
 * 所以 `<runtime>-old` 不会被误判成 `<runtime>` 的子路径。
 *
 * 不 import 任何 Android 类：本文件由 bare-JVM harness 编译。
 */
object VolatileTree {

    /**
     * [target] 相对 [runtime] 的位置；不在 [runtime] 之下时返回 null。
     *
     * [target] 正好等于 [runtime] 时返回空串——「它就是易失树本身」是一个真实答案，
     * 而不是「在外面」。
     */
    fun relativePath(runtime: File, target: File): String? {
        val root = normalized(runtime)
        val candidate = normalized(target)
        if (candidate == root) return ""
        val prefix = if (root.endsWith(File.separator)) root else root + File.separator
        return if (candidate.startsWith(prefix)) candidate.substring(prefix.length) else null
    }

    /** [target] 是否就是 [runtime] 或它下面的节点。 */
    fun contains(runtime: File, target: File): Boolean = relativePath(runtime, target) != null

    /**
     * [targets] 里落在 [runtime] **之外**的那些。
     *
     * 删除前用它：「除易失树之外一个节点都不许删」被表达成
     * 「[offending] 必须为空」，而不是一句承诺。空列表是唯一允许继续的信号。
     */
    fun offending(runtime: File, targets: List<File>): List<File> =
        targets.filter { !contains(runtime, it) }

    private fun normalized(file: File): String =
        file.absoluteFile.toPath().normalize().toString()
}

/**
 * 耐久区相对 `<files>/pi` 的三个子目录，**一处拼写**。
 *
 * 三个目录都不在易失树里，且都必须在升级、重建运行时之后仍然存在：
 *
 *  - [WORKSPACES_RELATIVE]：工作区根。`GuestWorkspacePath.ROOT_RELATIVE`（`pi/workspaces`，
 *    相对 files 目录）是它的另一侧拼法；harness 直接比较两者，所以两边不可能漂移。
 *  - [AGENT_RELATIVE]：`<files>/pi/.pi/agent`，pi 自己的 home（会话、settings、auth、
 *    扩展、技能、主题、提示词）。它会被 bind 到 guest 的 `/root/.pi/agent`。
 *  - [PERSIST_RELATIVE]：本 App 自己的耐久目录，放启动审计这类「必须活过升级」的文件。
 *    用 `persist` 而不是直接塞进 `<files>/pi/`：`<files>/pi` 下已经有 pi 自己的布局
 *    （`.pi/`）和易失的 `runtime/`、`engines/`，再混进 App 私有文件会让「哪些是 pi 的、
 *    哪些是我们的」看不出来；而且用户手机上装的那份构建里已经有一个 `files/pi/persist/`
 *    （`{cache,npm}`），沿用同一个名字，两边的语义一致。
 */
object DurableLayout {

    /** `<files>/pi/workspaces`，相对 `PiPaths.home`。 */
    const val WORKSPACES_RELATIVE: String = "workspaces"

    /** `<files>/pi/.pi/agent`，相对 `PiPaths.home`。 */
    const val AGENT_RELATIVE: String = ".pi/agent"

    /** `<files>/pi/persist`，相对 `PiPaths.home`。 */
    const val PERSIST_RELATIVE: String = "persist"

    /** 三个耐久目录，顺序固定，便于报告逐行打印。 */
    fun durableDirs(home: File): List<File> = listOf(
        File(home, WORKSPACES_RELATIVE),
        File(home, AGENT_RELATIVE),
        File(home, PERSIST_RELATIVE),
    )

    /**
     * 三个耐久目录里**落在 [rootfs] 之内**的那些 —— 也就是 `wipe()` 必须先搬走的那些
     * （[DurablePreserve]）。
     *
     * **今天是空列表**：三个耐久目录都在 `<files>/pi/runtime` 之外，而且 `PiPaths` 的构造
     * 检查（[violations]）会拒绝把它们放进去。这个函数存在，是为了让「把工作区和 agent
     * 目录搬进 rootfs」那一天**不需要再新写一套保护机制** —— `wipe()` 读的就是它，而
     * harness 的 G 段钉住「今天为空」这个事实（也就是那次改动对现在的行为零影响）。
     *
     * 用 [VolatileTree.contains] 而不是自己拼字符串前缀，是为了让「什么算在树下」只有一处定义。
     */
    fun durableInsideRootfs(durable: List<File>, rootfs: File): List<File> =
        durable.filter { VolatileTree.contains(rootfs, it) }

    /**
     * 落在 [runtime] 之内的耐久目录，每个一条**可读的中文句子**；空列表表示结构正确。
     *
     * 返回句子而不是布尔值，是因为这个结果的两条出口都要把它呈现给人：`PiPaths` 抛出时
     * 进异常消息，`DiagnosticsReport` 打印时进报告正文。让「哪个目录、在哪棵树下」这句话
     * 只写一次，就不会出现「断言说 A、报告说 B」。
     */
    fun violations(home: File, runtime: File): List<String> = durableDirs(home).mapNotNull { dir ->
        VolatileTree.relativePath(runtime, dir)?.let { relative ->
            "耐久目录 ${dir.path} 落在易失树 ${runtime.path} 之内（相对路径 \"$relative\"）：" +
                "升级或重建运行时会把它删掉。"
        }
    }
}
