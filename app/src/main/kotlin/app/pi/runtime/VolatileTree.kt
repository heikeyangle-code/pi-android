package app.pi.runtime

import java.io.File

/**
 * 「这个路径在易失树里吗」——一条纯函数回答的那个问题。
 *
 * ## 为什么它必须存在，而不是一句注释
 *
 * `<files>/pi/runtime` 是**易失**的（`RuntimeProvisioner` 会重建它），
 * `<files>/pi/workspaces` 和 `<files>/pi/persist` 是**耐久**的，`<files>/pi/runtime` 是
 * **易失**的；pi 的 agent 目录自 2026-09-23 起住在易失树**之内**的 rootfs 里
 * （`<rootfs>/root/.pi/agent`，见 [DurableLayout]），由 `wipe()` 的搬走/搬回保护，而不是
 * 由位置保护。
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
 * 耐久目录的**一处拼写**，以及「它们在不在易失树里」这一条判定的唯一实现。
 *
 * ## 2026-09-23：pi 的 agent 目录搬进了 rootfs
 *
 * pi 的 agent 目录（会话、settings、auth、扩展、技能、主题、提示词）现在住在
 * **rootfs 里**：[AGENT_IN_ROOTFS] = `<rootfs>/root/.pi/agent`，就是 guest 的
 * `/root/.pi/agent`。
 *
 * 这么做是因为 proroot 的 `scandir(3)`/`mkstemp(3)`/`mkdtemp(3)` 在「宿主源是应用私有
 * 目录」的绑定子树里返回 `ENOENT`（`docs/proroot-scandir-defect.md`）—— 而 agent 目录
 * 正是那条绑定：不搬走它，`npm install` 往 agent 目录里的写入、以及任何碰它的
 * `mkstemp` 系工具，在 proroot 下都要绕。搬进 rootfs 之后它不再是一条绑定，走的是一条
 * 普通的 rootfs 路径。
 *
 * **工作区根也搬了**：[WORKSPACES_IN_ROOTFS] = `<rootfs>/workspace/pi/workspaces`。它的
 * guest 拼写由 [GuestWorkspacePath] 给出，那条规则的前提是「某个基底镜像在 `/workspace`」——
 * 所以搬它的同时把那个基底从 `<files>` 换成了 [WORKSPACE_BASE_IN_ROOTFS]
 * （`<rootfs>/workspace`），**guest 拼写一个字没变**（`/workspace/pi/workspaces/<名>`，
 * 也就是 pi 写进 `trust.json` 的那个 cwd）。这正是 `git` 能被修好的原因：`.git/tXXXXXX`
 * 现在建在一条普通的 rootfs 路径上，而不是绑定子树的里面。
 *
 * ## 代价是「agent 目录现在在易失树里」，而这一条是被保护的，不是被默许的
 *
 * 它落在 rootfs 里是**合法**的：`wipe()`（唯一会整棵删 `<files>/pi/runtime` 的地方，
 * 只有用户显式点「修复」能到）会先用 [DurablePreserve] 把它**搬出易失树**，删完再搬回；
 * 搬不动就拒绝删除。所以 [violations] 现在的判据是
 *
 * > 耐久目录可以在 rootfs 之内；**不允许**的是落在易失树里、却在 rootfs **之外**
 *
 * 因为搬运清单覆盖的正是 rootfs 之内那一层：rootfs 外的易失节点没有任何东西保护它。
 *
 * [ROOTFS_RELATIVE] 是 `PiPaths.rootfs` 相对 `PiPaths.runtime` 的拼写，搬进 rootfs 的
 * 耐久目录都按它定位 —— 一处拼写，`PiPaths` 也从这里取。
 */
object DurableLayout {

    /** `<files>/pi/runtime/rootfs`，相对 [PiPaths.runtime]。`PiPaths.rootfs` 用它。 */
    const val ROOTFS_RELATIVE: String = "rootfs"

    /** `<rootfs>/root/.pi/agent`，相对 rootfs。就是 guest 的 `/root/.pi/agent`。 */
    const val AGENT_IN_ROOTFS: String = "root/.pi/agent"

    /**
     * `<rootfs>/workspace`，相对 rootfs —— `GuestWorkspacePath` 当作 guest `/workspace`
     * 的那一层。工作区根在它下面，所以 guest 的 `/workspace/pi/workspaces/<名>`
     * 就是一条普通的 rootfs 路径。
     */
    const val WORKSPACE_BASE_IN_ROOTFS: String = "workspace"

    /** `<rootfs>/workspace/pi/workspaces`，相对 rootfs。guest 侧是 `/workspace/pi/workspaces`。 */
    const val WORKSPACES_IN_ROOTFS: String = "workspace/pi/workspaces"

    /** `<files>/pi/persist`，相对 `PiPaths.home`。本 App 自己的耐久目录。 */
    const val PERSIST_RELATIVE: String = "persist"

    /** [ROOTFS_RELATIVE] 在 [runtime] 下解析出的那个目录。 */
    fun rootfsOf(runtime: File): File = File(runtime, ROOTFS_RELATIVE)

    /** `GuestWorkspacePath` 的基底：`<rootfs>/workspace`。 */
    fun workspaceBaseOf(runtime: File): File = File(rootfsOf(runtime), WORKSPACE_BASE_IN_ROOTFS)

    /** 三个耐久目录，顺序固定，便于报告逐行打印。 */
    fun durableDirs(home: File, runtime: File): List<File> {
        val rootfs = rootfsOf(runtime)
        return listOf(
            File(rootfs, WORKSPACES_IN_ROOTFS),
            File(rootfs, AGENT_IN_ROOTFS),
            File(home, PERSIST_RELATIVE),
        )
    }

    /**
     * 三个耐久目录里**落在 [rootfs] 之内**的那些 —— 也就是 `wipe()` 必须先搬走的那些
     * （[DurablePreserve]）。
     *
     * 用 [VolatileTree.contains] 而不是自己拼字符串前缀，是为了让「什么算在树下」只有一处定义。
     */
    fun durableInsideRootfs(durable: List<File>, rootfs: File): List<File> =
        durable.filter { VolatileTree.contains(rootfs, it) }

    /**
     * 结构违规，每个一条**可读的中文句子**；空列表表示结构正确。
     *
     * 判据只有一条：**耐久目录可以落在 rootfs 之内（[DurablePreserve] 保护它），但不允许
     * 落在易失树里、rootfs 之外** —— 那里没有任何东西保护它。
     *
     * 返回句子而不是布尔值，是因为这个结果的两条出口都要把它呈现给人：`PiPaths` 抛出时
     * 进异常消息，`DiagnosticsReport` 打印时进报告正文。让「哪个目录、在哪棵树下」这句话
     * 只写一次，就不会出现「断言说 A、报告说 B」。
     */
    fun violations(home: File, runtime: File): List<String> {
        val rootfs = rootfsOf(runtime)
        return durableDirs(home, runtime).mapNotNull { dir ->
            // rootfs 之内的耐久目录由 `wipe()` 的搬运清单保护 —— 这是设计，不是违规。
            if (VolatileTree.contains(rootfs, dir)) return@mapNotNull null
            VolatileTree.relativePath(runtime, dir)?.let { relative ->
                "耐久目录 ${dir.path} 落在易失树 ${runtime.path} 之内、却在 rootfs " +
                    "${rootfs.path} 之外（相对路径 \"$relative\"）：`wipe()` 的搬运清单只覆盖 " +
                    "rootfs 之内的耐久目录，所以升级或重建运行时会把它删掉。"
            }
        }
    }
}
