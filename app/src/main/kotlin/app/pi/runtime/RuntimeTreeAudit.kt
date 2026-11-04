package app.pi.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 「解包说成功了，树里却少东西」——这条判定的唯一实现，以及 usrmerge 链接的修复。
 *
 * ## 为什么需要它（真机报告）
 *
 * 一台刚安装的设备上，解包流程正常走完，随后启动自检失败，proot 的原话是：
 *
 *     proot error: '/bin/bash' not found (root = <files>/pi/runtime/rootfs, cwd = /, $PATH=(null))
 *
 * `not found` 是 proot 的 `which()` 在内核翻译后的路径上 `stat` 为空才会说的话：它既不是
 * 权限被拒（那会是被 SELinux / W^X 拒掉的另一句），也不是解释器缺失（另一句）。也就是说
 * **树里真的没有 `/bin/bash`**，而不是"有但跑不起来"。
 *
 * 而 `.stamp` 已经写了。于是之后每一次启动都走「戳记相符 → 什么都不做」的快路径，重试与
 * 重建之外无路可走；同一份 APK 在别的设备上又完全正常。这种情况只有三种来源：
 *
 *  1. 树被判成"已解包"，实际上只写进去了一部分（中途被杀、备份恢复只回了小文件、
 *     某次覆盖解压中途出错但没有失败到最后）；
 *  2. `bin -> usr/bin` 这条 **符号链接**没建成（某些存储/加固策略会拒绝 symlink），而
 *     `usr/bin/bash` 本身是在的 —— `bin` 于是不存在，`/bin/bash` 自然 `not found`；
 *  3. 载荷真的缺文件（构建期问题，重建也救不了）。
 *
 * 前两种都能在设备上判出来并至少修掉第二种，所以：解包**之后**查一遍关键路径（[missing]），
 * 快路径**也要**查（戳记不是树完整性的证明），缺了就当作"没解完整"重新覆盖解压一遍；解完
 * 还缺就把缺哪个、`/bin` 到底是什么、根目录里有什么，一起写进失败页。
 *
 * ## 为什么关键路径是这四个
 *
 * 只挑"缺了 guest 一定起不来、而且一句 `stat` 就能问完"的：
 *
 *  - `bin/bash` —— 所有 guest 命令的壳，也是那台设备失败的地方（`bin` 是链接，所以这一条
 *    同时覆盖"链接没建"和"`usr/bin/bash` 没写"两种原因）；
 *  - `bin/sh` —— 脚本 shebang 的另一个常见目标；
 *  - `usr/bin/env` —— 解释器兜底；它缺了，任何 `#!/usr/bin/env …` 都会静默 ENOENT；
 *  - `opt/node/bin/node` —— 引擎本体。
 *
 * 目录、整个世界、几千个文件都不在这里查：那是 `payload` 清单与 digest 的事，逐文件遍历
 * 会把每次启动变成一次全树扫描。四条 `stat`（`isFile`）是这条判定愿意付的全部代价。
 *
 * ## usrmerge 修复的边界
 *
 * [repairUsrMergeLinks] 只做一件事：**目标目录存在、链接本身不在或悬空**时，把
 * `bin`/`sbin`/`lib`/`lib64` 这四条标准链接补回去（先删悬空的，再建相对的）。
 *
 * 它**永不**删除一个真实的目录或文件：如果 `bin` 已经是个装着内容的真目录（例如上一版把
 * 链接解引用成了副本），那里面可能就是用户自己的东西，删掉是不可接受的；这种形状原样保留，
 * 只在 [Repair] 里如实记下来。
 *
 * Android-free（`java.io` + `java.nio.file`），由 `tools/run-app-pure-checks.sh` 的
 * `runtime-tree-audit` 在裸 JVM 上真跑。
 */
object RuntimeTreeAudit {

    /**
     * 一个载荷落地后必须存在的东西。
     *
     * [executable] 的判据是"真的能执行"，不是"存在"：`chmod` 丢位、链接指到一个目录、
     * 只剩 0 字节，三种都在 `exists()` 里看起来很正常，而在设备上全都表现为同一个
     * `not found` / `Permission denied`。所以每个载荷解完之后查的是它自己的这一组
     * （[requirementsFor]），**在写 digest 之前**——写早了，下次启动就会因为"digest 相符"
     * 而跳过补解，残树从此永久化。
     */
    data class Requirement(val path: String, val executable: Boolean = false)

    /** 缺了它 guest 一定起不来的相对路径，按"先查最可能的"排序。 */
    val CRITICAL_REQUIREMENTS: List<Requirement> = listOf(
        Requirement("bin/bash", executable = true),
        Requirement("bin/sh", executable = true),
        Requirement("usr/bin/env", executable = true),
        Requirement("opt/node/bin/node", executable = true),
    )

    val CRITICAL: List<String> = CRITICAL_REQUIREMENTS.map { it.path }

    /**
     * 每个载荷自己那一组关键文件，键是 `RuntimeProvisioner` 里的载荷名（去掉 `.tgz`）。
     *
     * 为什么按载荷分开而不是只查一次整树：`RuntimeProvisioner` 是**逐载荷**记账的
     * （extract → prune → record digest）。如果只在最后查一次，一个坏掉的 ubuntu-base 会在
     * 它自己的 digest 被写下去之后才被发现，而下次启动"digest 相符"就不会再解它了——残树
     * 被永久保留下来。放在载荷边界上，坏的那一份永远不会被记录成"已完成"。
     *
     * 引擎载荷（`pi-engine`）检查的是 `extractEngine` 自己写的启动器，不是归档里的某个
     * 路径：归档内部结构由引擎自己决定，启动器才是 app 真正会执行的那个文件。
     */
    fun requirementsFor(payloadName: String): List<Requirement> = when (payloadName) {
        "ubuntu-base" -> listOf(
            Requirement("bin/bash", executable = true),
            Requirement("bin/sh", executable = true),
            Requirement("usr/bin/bash", executable = true),
            Requirement("usr/bin/dash", executable = true),
            Requirement("usr/bin/env", executable = true),
        )
        "node" -> listOf(
            Requirement("opt/node/bin/node", executable = true),
            Requirement("usr/local/bin/node"),
        )
        "ripgrep" -> listOf(Requirement("usr/local/bin/rg", executable = true))
        "fd" -> listOf(Requirement("usr/local/bin/fd", executable = true))
        "git" -> listOf(Requirement("usr/bin/git", executable = true))
        "pi-engine" -> listOf(Requirement("opt/pi/bin/pi", executable = true))
        else -> emptyList()
    }

    /** 一条 usrmerge 标准链接：rootfs 里的名字，和它该指向的相对目标。 */
    data class MergeLink(val path: String, val target: String)

    /**
     * Ubuntu 24.04（merged-usr）的四条顶层链接。
     *
     * 顺序就是创建顺序，没有别的原因；四条都是**相对**目标，与官方 tar 里逐字节相同
     * （`bin -> usr/bin`、`lib -> usr/lib`、`sbin -> usr/sbin`、`lib64 -> usr/lib64`）。
     */
    val USRMERGE_LINKS: List<MergeLink> = listOf(
        MergeLink("bin", "usr/bin"),
        MergeLink("sbin", "usr/sbin"),
        MergeLink("lib", "usr/lib"),
        MergeLink("lib64", "usr/lib64"),
    )

    /**
     * [repairUsrMergeLinks] 的一次结果。
     *
     * [repaired] 是这次真的新建/重建的链接，[leftAlone] 是因为"那里有真东西"而没动的路径
     * （**不是失败**：它有可能是用户自己建的），[failed] 是尝试了但没成功的路径——只有它
     * 才值得出现在失败页上。
     */
    data class Repair(
        val repaired: List<String> = emptyList(),
        val leftAlone: List<String> = emptyList(),
        val failed: List<String> = emptyList(),
    ) {
        val changed: Boolean get() = repaired.isNotEmpty()
    }

    /**
     * [requirements] 里每一条没满足的原因，一条一句话；全满足时是空列表。
     *
     * 与 [missing] 的分工：`missing` 只回答"在不在"（快路径上每轮都跑，四条 `isFile`），
     * 这里是**闸门**——载荷解完、写 digest 之前、自检之前各跑一次，把"在但不可执行""是目录"
     * "链接悬空""链接逃出 rootfs"分开说清楚。分开说是必须的：这四种在设备上全都表现为
     * proot 的一句 `not found`，而修法完全不同（重新解包 / 修链接 / 查权限 / 拒绝）。
     */
    fun failures(rootfs: File, requirements: List<Requirement>): List<String> =
        requirements.mapNotNull { requirement -> problem(rootfs, requirement) }

    /**
     * [CRITICAL_REQUIREMENTS] 的失效清单，空 = 这棵树能起 guest。
     *
     * 这是"完整"的**唯一**判据，三处共用：戳记快路径（残树不许被当成已就绪）、
     * `treeIncomplete`（决定要不要覆盖重解）、解包末尾的闸门（决定能不能写 stamp）。
     * 用 `failures` 而不是 `missing`：`exists()` 会放过"不可执行 / 0 字节 / 链接指向目录"，
     * 而那三种在设备上全都表现为 proot 的一句 `not found`。
     */
    fun criticalProblems(rootfs: File): List<String> = failures(rootfs, CRITICAL_REQUIREMENTS)

    private fun problem(rootfs: File, requirement: Requirement): String? {
        val file = File(rootfs, requirement.path)
        val label = "/" + requirement.path
        val node = resolveInGuest(rootfs, file)
        val link = if (node === file) null else {
            runCatching { Files.readSymbolicLink(file.toPath()) }.getOrNull()?.toString()
        }
        // Every sentence has to read as one clause, because it is shown verbatim on the boot
        // card and copied into a report: `…/bin/bash：不存在` for a plain file,
        // `…/bin/bash：符号链接 -> usr/bin/bash，不存在` for a link.
        val lead = if (link == null) "$label：" else "$label：符号链接 -> $link，"
        if (!node.exists()) {
            val ancestor = danglingAncestor(rootfs, file)
            return if (ancestor == null) "${lead}不存在" else "${lead}不存在（$ancestor）"
        }
        // A link that climbs out with `..` would run a file outside the tree. Guest-absolute
        // links (`/opt/node/bin/node`) are anchored inside the rootfs by construction, so
        // only the relative spelling can escape — and only that one is checked.
        if (link != null && !link.startsWith("/")) {
            escapesRootfs(rootfs, node)?.let { return "$lead$it" }
        }
        // A non-executable requirement only asks for the node: `usr/local/bin/node` is a
        // link into `/opt/node`, and whether it is executable is the target's answer.
        if (!requirement.executable) return null
        if (node.isDirectory) return "${lead}是目录，不是可执行文件"
        if (!node.canExecute()) return "${lead}没有可执行权限"
        if (node.length() == 0L) return "${lead}是 0 字节的空文件"
        return null
    }

    /**
     * 一个 rootfs 里的路径，按 **guest 的语义**解析到最终的宿主路径。
     *
     * 这一步不能省，而且方向不能反：载荷里有两种链接。
     *
     *  - 相对的（`bin -> usr/bin`、`usr/bin/sh -> dash`）：宿主与 guest 解析结果相同，
     *    `File(rootfs, path)` 自己就能跟；
     *  - **guest 绝对的**（`/usr/local/bin/node -> /opt/node/bin/node`）：宿主上
     *    `/opt/node/...` 是另一台机器的路径，`exists()` 会答 false。真机报告里这条也真的
     *    被误判过——所以这里把它改成"去掉前导 `/` 再从 rootfs 起算"，与 guest 一致。
     *
     * 只跟一跳：本 app 的载荷里没有多跳链（`bin/bash` 是"链接目录 + 真实文件"，由
     * `File` 自己解析），而多跳会让这条判定变成一次任意深度的遍历。
     */
    private fun resolveInGuest(rootfs: File, file: File): File {
        val isLink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        if (!isLink) return file
        val target = runCatching { Files.readSymbolicLink(file.toPath()) }.getOrNull() ?: return file
        val resolved = if (target.isAbsolute) {
            File(rootfs, target.toString().removePrefix("/"))
        } else {
            File(file.parentFile, target.toString())
        }
        return resolved
    }

    /**
     * 路径上第一个**悬空**的符号链接，例如 `/bin` 指向已不存在的 `usr/bin`。
     *
     * 这条是给失败页用的：`/bin/bash` 不存在有两种原因——`usr/bin/bash` 没写进去，或者
     * `bin` 链接本身就是断的。前者要重解载荷，后者只要补链接，而用户手里的截图只有一句
     * `not found`，看不出是哪一种。
     */
    private fun danglingAncestor(rootfs: File, file: File): String? {
        var current = file
        while (true) {
            val parent = current.parentFile ?: return null
            if (parent.path == rootfs.path) return null
            val isLink = runCatching { Files.isSymbolicLink(parent.toPath()) }.getOrDefault(false)
            if (isLink && !parent.exists()) {
                val target = runCatching { Files.readSymbolicLink(parent.toPath()) }.getOrNull()
                return "上级 ${parent.path.removePrefix(rootfs.path)} 是悬空链接 -> $target"
            }
            current = parent
        }
    }

    /**
     * 符号链接解析之后是否还在 [rootfs] 里。
     *
     * 载荷是第三方下载物，一条 `bin -> /data/data/other-app/...` 的链接会让 guest 执行
     * rootfs 之外的文件；`canonicalPath` 是内核的答案（真机上 `/data/user/0` 就是
     * `/data/data`），所以这条判据在两个拼写下都成立。读不出来的路径不判失败：那是"没法
     * 证明"而不是"证明了越界"。
     */
    private fun escapesRootfs(rootfs: File, file: File): String? {
        val base = runCatching { rootfs.canonicalPath }.getOrNull() ?: return null
        val resolved = runCatching { file.canonicalPath }.getOrNull() ?: return null
        val inside = resolved == base || resolved.startsWith(base + File.separator)
        return if (inside) null else "解析到 rootfs 之外（$resolved）"
    }

    /**
     * 补回 [USRMERGE_LINKS] 里"目标存在、链接不在或悬空"的那几条。
     *
     * 幂等：四条都正确时不写任何东西（四条 `isSymbolicLink`/`exists` 探针）。目标目录不存在
     * 的链接直接跳过——那是载荷没解出来，不是链接的事，[missing] 会把它报出来。
     */
    fun repairUsrMergeLinks(rootfs: File): Repair {
        val repaired = mutableListOf<String>()
        val leftAlone = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (link in USRMERGE_LINKS) {
            val target = File(rootfs, link.target)
            if (!target.isDirectory) continue
            val path = File(rootfs, link.path)
            val pathP = path.toPath()
            val isLink = runCatching { Files.isSymbolicLink(pathP) }.getOrDefault(false)
            if (isLink && path.exists()) continue // 已经是对的（或至少可解析）
            if (!isLink && path.exists()) {
                // 真实的目录/文件：可能是上一版解引用出来的副本，也可能是用户建的。
                // 删掉它就是在删用户的东西，所以只记录、不动作。
                leftAlone += link.path
                continue
            }
            val done = runCatching {
                if (isLink) path.delete()
                Files.createSymbolicLink(pathP, Path.of(link.target))
                true
            }.getOrDefault(false)
            if (done) repaired += link.path else failed += link.path
        }
        return Repair(repaired = repaired, leftAlone = leftAlone, failed = failed)
    }

    /**
     * 一个载荷解完之后不完整时，失败页上的那段话。
     *
     * 它必须同时说清三件事，因为这就是"用户第一次安装就卡死"的那类报告需要的信息：
     * **哪个载荷**、**哪几个路径**、以及**下一步会怎样**（digest 没写 → 下次启动会重新解
     * 这个载荷；不用点重建，也不会丢掉 guest 里装的东西）。
     */
    fun payloadFailure(payloadName: String, rootfs: File, problems: List<String>): String = buildString {
        append("载荷 $payloadName 解完之后关键文件不完整：\n")
        problems.forEach { append("  · ").append(it).append('\n') }
        append("\n这个载荷的 digest **没有**写入，所以下次启动会重新解它（不删任何东西）。\n\n")
        append(describe(rootfs))
    }

    /**
     * 一条 [missing] 结果拼成失败页的正文：缺哪几个、`/bin` 到底是什么、补链接补到了哪一步。
     *
     * 只描述**能证明的事实**（`stat` 的答案、链接的指向），不猜原因：这句话会原样出现在用户
     * 发给我们的截图里，它必须是下一次报告可以直接用的证据。
     */
    fun failure(
        rootfs: File,
        problems: List<String>,
        repair: Repair,
        available: Long = rootfs.usableSpace,
    ): String = buildString {
        append("运行时缺关键文件：\n")
        problems.forEach { append("  · ").append(it).append('\n') }
        append("\n")
        append(describe(rootfs, available))
        if (repair.changed) {
            append("\n本次已补回 usrmerge 链接：")
            append(repair.repaired.joinToString("、") { "/$it" })
        }
        if (repair.failed.isNotEmpty()) {
            append("\n没能补回的链接：")
            append(repair.failed.joinToString("、") { "/$it" })
            append("（这台设备的应用数据目录拒绝建立符号链接）")
        }
        if (repair.leftAlone.isNotEmpty()) {
            append("\n原样保留（那里是真实目录/文件，没有删）：")
            append(repair.leftAlone.joinToString("、") { "/$it" })
        }
        append("\n\n这不是权限问题：proot 报的是 rootfs 里找不到文件。")
        append("重建运行时可以重解全部载荷；如果重建后仍然缺同一批文件，是 APK 里的载荷本身不完整。")
    }

    /**
     * 这棵树现在的形状，给失败页与诊断报告用。
     *
     * 输出固定几行，每行一个可以直接对照的事实：`/bin` 是链接/目录/不存在（链接还会写目标
     * 在不在）、四个关键路径各自在不在、rootfs 顶层有什么（前 [MAX_ROOT_ENTRIES] 个）、
     * 可用空间。顶层列表是"树是空的还是只缺一点"的分界证据。
     */
    fun describe(rootfs: File, available: Long = rootfs.usableSpace): String = buildString {
        append("rootfs = ").append(rootfs.path).append('\n')
        append("/bin：").append(kindOf(File(rootfs, "bin"))).append('\n')
        for (relative in CRITICAL) {
            append('/').append(relative).append("：").append(kindOf(File(rootfs, relative))).append('\n')
        }
        val top = runCatching { rootfs.list()?.sorted() }.getOrNull()
        if (top == null) {
            append("根目录：读不出来（目录不存在或不可读）\n")
        } else {
            val shown = top.take(MAX_ROOT_ENTRIES)
            append("根目录（").append(top.size).append(" 项）：")
            append(shown.joinToString("、"))
            if (top.size > shown.size) append(" …（还有 ").append(top.size - shown.size).append(" 项）")
            append('\n')
        }
        append("可用空间：").append(available).append(" 字节")
    }

    /** 一个节点的形状，用 `stat` 能给出的事实说话。 */
    private fun kindOf(file: File): String {
        val isLink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        if (isLink) {
            val target = runCatching { Files.readSymbolicLink(file.toPath()) }.getOrNull()
            val exists = file.exists()
            return "符号链接 -> $target（目标${if (exists) "存在" else "不存在"}）"
        }
        return when {
            file.isDirectory -> "目录"
            file.isFile -> "普通文件（${file.length()} 字节）"
            file.exists() -> "存在但不是普通文件"
            else -> "不存在"
        }
    }

    /** 顶层目录列到多少个为止：够看出"空树/半棵树/完整"，又不至于把失败页撑爆。 */
    const val MAX_ROOT_ENTRIES: Int = 24
}
