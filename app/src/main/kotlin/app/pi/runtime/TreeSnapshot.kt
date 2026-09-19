package app.pi.runtime

import java.io.File

/**
 * 「工作区根目录的东西还在不在」——用**有界**的两次观察回答。
 *
 * ## 为什么是「直接子项」而不是全树摘要
 *
 * 用户报的是「工作区根目录的东西，每次升级完软件都会被删掉」。要证明或否证这件事，只需
 * 看工作区根自己的存在性、`lastModified`，以及它的**直接子项**：每个子项的
 * `name` / 是否为目录 / `size` / `lastModified`。这是 O(顶层条目数) 的成本——几十项、
 * 微秒级——而递归进子树在设备上不是可接受的代价：实测经 proot 的纯 `stat` 遍历，
 * 13 529 个文件 / 101 MiB 要 **3.5 s**，19 526 个文件 / 459 MiB 要 **30 s**。用户会把
 * 那 30 秒当成卡死，而这正是要修的毛病。所以这里**不算内容哈希**，也**不递归**。
 *
 * ## 「有多大」是另一个问题，用预算回答
 *
 * [walk] 提供量级信息，但**先到先停**：最多 [WALK_BUDGET_ENTRIES] 个节点或
 * [WALK_BUDGET_MS] 毫秒，谁先到谁停。停在哪个上限上会被写进 [Walk.capped] 的句子里，
 * 报告里照原样打印——「可见的截断必须说得出」是项目规则，而一个被截断的计数如果不说，
 * 就会冒充成完整计数。
 *
 * ## 读不到 ≠ 0 个 ≠ 不存在
 *
 * [Root.exists]、[Root.unreadable]、[Root.childCount] 是三个不同的答案：目录不存在、
 * 目录在但 `listFiles()` 返回 null（权限/IO）、以及目录里确实 0 项。三者不能互相冒充，
 * 所以它们各占一个字段，报告里各写各的。
 *
 * 不 import 任何 Android 类（`java.io.File` 与 `System.nanoTime()` 而已）：本文件由
 * bare-JVM harness 编译，并用真实临时目录驱动。
 */
object TreeSnapshot {

    /** 记进审计行的直接子项上限；超出的部分只记个数，见 [Root.omitted]。 */
    const val MAX_CHILDREN: Int = 32

    /** [walk] 的节点数预算。 */
    const val WALK_BUDGET_ENTRIES: Int = 2000

    /** [walk] 的时间预算，毫秒。 */
    const val WALK_BUDGET_MS: Long = 200L

    /** 一个直接子项，四个字段就是「它还在不在、还是不是原来那个」的全部证据。 */
    data class Child(val name: String, val directory: Boolean, val bytes: Long, val modifiedMs: Long)

    /**
     * 一个目录根的两个事实：它自己，和它的直接子项。
     *
     * @param exists 路径存在。false 时后面各项全部无意义（但仍如实写成「不存在」）。
     * @param unreadable 目录在、但读不了（`listFiles()` 返回 null）。与 [exists]=false 不同。
     * @param modifiedMs 根自己的 `lastModified()`；读不到时为 null，不伪装成 0。
     * @param childCount 实际直接子项数（[children] 被 [MAX_CHILDREN] 截断时仍然完整）。
     * @param omitted 因为超过 [MAX_CHILDREN] 而**没有记进** [children] 的子项数。
     */
    data class Root(
        val path: String,
        val exists: Boolean,
        val unreadable: Boolean,
        val modifiedMs: Long?,
        val childCount: Int,
        val children: List<Child>,
        val omitted: Int,
    )

    /**
     * 一次有预算的递归观察。
     *
     * @param visited 访问过的节点数（含目录）。
     * @param files 其中的非目录节点数（含符号链接，链接记 0 字节、不进链接目标）。
     * @param bytes 常规文件大小之和。
     * @param directoriesRead 成功列出的目录数。
     * @param directoriesUnreadable 列不出来的目录数（读不到，不是空）。
     * @param capped null 表示计完了；否则是一句说明停在哪条预算上。
     */
    data class Walk(
        val visited: Long,
        val files: Long,
        val bytes: Long,
        val directoriesRead: Long,
        val directoriesUnreadable: Long,
        val capped: String?,
    )

    /**
     * [dir] 自己与它的**直接**子项。
     *
     * 子项按名字排序，所以两次观察的文本可以直接比对（`listFiles()` 的顺序不是规定顺序）。
     * `isDirectory` 跟随符号链接（一个指向目录的链接会被记成目录），这是刻意的：用户看到
     * 的「根目录里还有没有那一项」跟随链接才与他看到的一致。
     */
    fun root(dir: File, maxChildren: Int = MAX_CHILDREN): Root {
        val path = dir.path
        if (!dir.isDirectory) {
            return Root(path, exists = false, unreadable = false, modifiedMs = null, childCount = 0, children = emptyList(), omitted = 0)
        }
        val modified = dir.lastModified().takeIf { it > 0L }
        val listed = dir.listFiles()
            ?: return Root(path, exists = true, unreadable = true, modifiedMs = modified, childCount = 0, children = emptyList(), omitted = 0)
        val sorted = listed.sortedBy { it.name }
        val children = sorted.take(maxChildren).map { child ->
            Child(
                name = child.name,
                directory = child.isDirectory,
                bytes = if (child.isDirectory) 0L else child.length(),
                modifiedMs = child.lastModified(),
            )
        }
        return Root(
            path = path,
            exists = true,
            unreadable = false,
            modifiedMs = modified,
            childCount = sorted.size,
            children = children,
            omitted = sorted.size - children.size,
        )
    }

    /**
     * 有预算地数一数 [dir] 下面的量级。
     *
     * 不跟随符号链接（链接按一个节点计、0 字节），所以链接环不会让它转不完；两个预算
     * （[budgetEntries] / [budgetMs]）是**硬停**，不是目标：每条子项之前都检查一次，
     * 所以 `visited` 绝不会超过 [budgetEntries]，一个装了几万项的目录也不会一次性把预算
     * 冲掉。停在哪个上限上会写进 [Walk.capped] 的句子里。
     *
     * [Walk.directoriesRead] 是「列出成功的目录数」；在被截断的那一层，它包含一个只列到
     * 上限处的目录——那句话说的是「列出来了」，不是「走完了」。
     */
    fun walk(
        dir: File,
        budgetEntries: Int = WALK_BUDGET_ENTRIES,
        budgetMs: Long = WALK_BUDGET_MS,
    ): Walk {
        var visited = 0L
        var files = 0L
        var bytes = 0L
        var directoriesRead = 0L
        var directoriesUnreadable = 0L
        var capped: String? = null
        val startedNs = System.nanoTime()

        if (!dir.isDirectory) {
            return Walk(0, 0, 0, 0, 0, "目录不存在")
        }
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        budget@ while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles()
            if (children == null) {
                directoriesUnreadable++
                continue
            }
            directoriesRead++
            for (child in children) {
                // The budget is checked before *every* entry, not once per directory: a
                // directory with 100k children would otherwise blow past it in one pass.
                if (visited >= budgetEntries) {
                    capped = "达到 $budgetEntries 个节点上限，未计完"
                    break@budget
                }
                val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
                if (elapsedMs >= budgetMs) {
                    capped = "达到 $budgetMs ms 上限（已用 $elapsedMs ms），未计完"
                    break@budget
                }
                visited++
                val path = child.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    files++
                    continue
                }
                if (child.isDirectory) {
                    stack.addLast(child)
                } else {
                    files++
                    bytes += child.length()
                }
            }
        }
        return Walk(visited, files, bytes, directoriesRead, directoriesUnreadable, capped)
    }

    /**
     * 一个目录根的事实，渲染成审计行里的一个片段。
     *
     * 三个状态各写各的：`不存在` / `存在但读不到` / `children=N`。`omitted` 大于 0 时
     * 必须写出来——那是「记不下而不是没有」。
     */
    fun rootSummary(root: Root): String {
        if (!root.exists) return "path=${root.path} 不存在"
        val mtime = root.modifiedMs?.toString() ?: "读不到"
        if (root.unreadable) return "path=${root.path} 存在但读不到 mtime=$mtime"
        val items = root.children.joinToString(",") { child ->
            val kind = if (child.directory) "dir" else "file"
            "${child.name}:$kind:${child.bytes}:${child.modifiedMs}"
        }
        val body = if (items.isEmpty()) "-" else items
        val omitted = if (root.omitted > 0) " omitted=${root.omitted}" else ""
        return "path=${root.path} mtime=$mtime children=${root.childCount}$omitted items=[$body]"
    }

    /**
     * 一次有预算遍历的**计数摘要行内片段**——上限句照原样带着，没截断时写 `未截断`。
     *
     * 与 [rootSummary] 分开：那一句说的是「还在不在」，这一句说的是「有多大」，
     * 两者在审计行里相邻但互不冒充。
     */
    fun walkSummary(walk: Walk): String {
        val base = "visited=${walk.visited} files=${walk.files} bytes=${walk.bytes} " +
            "dirsRead=${walk.directoriesRead} dirsUnreadable=${walk.directoriesUnreadable}"
        return if (walk.capped == null) "$base 未截断" else "$base 截断：${walk.capped}"
    }
}
