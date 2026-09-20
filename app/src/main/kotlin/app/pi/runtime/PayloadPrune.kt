package app.pi.runtime

/**
 * 「覆盖解压之后，哪些旧路径该删」——这条判定被抽成纯函数的地方。
 *
 * ## 它替换掉的是什么
 *
 * 旧行为是：一个全局摘要（`runtime-revision.txt` 的 SHA-256）与设备上的 `.stamp` 不一致，
 * 就 `deleteRecursively()` 整棵 `<files>/pi/runtime`，再把全部载荷重新解一遍。任何一份载荷
 * 变了——pi 升版、Node 升版、git 闭包多一个库、proroot 的一个 `.so` 变了——用户自己装进
 * 客体的东西全没了（apt/pip、`npm -g`、`/usr/local/bin`、`/root` 除 bind 的 `.pi/agent`
 * 之外的一切）。用户的说法是「我为什么每次升级完软件？很多东西，很多文件都会没有」。
 *
 * 新行为是**逐载荷记账**：每个载荷自带 digest 与「它装了哪些相对路径」的清单，
 * 摘要没变就一个字节都不动；变了就**覆盖解压**到原树上，然后只删「旧清单拥有、新清单不再
 * 拥有、而且现在确实还在」的那些路径。
 *
 * ## 安全性质（由 harness 钉住，不是由注释保证）
 *
 *  - 删除集合是 `旧清单 ∩ 树上存在 − 新清单` 的子集，所以**两份清单之外的任何路径永远
 *    进不了删除集合**——用户自己创建的文件不在任何清单里，因此不可能被删。
 *  - 两份清单都有的路径保留（新载荷刚刚覆盖写入了它）。
 *  - 只出现在旧清单里的路径删除（上游删掉的文件不会永远留在树里）。
 *  - 旧清单里但树上已经不在的路径不进删除集合：没有东西可删，报告也不该多算。
 *  - 绝对路径、含 `..`、空路径、带 `./` 前缀的条目一律当作不合法并拒绝。清单是**构建期**
 *    生成的文本，但如果它坏了（截断、被改写），判定必须拒绝而不是跟着跑。
 *
 * ## 目录不在清单里，这是刻意的
 *
 * 构建期生成清单时只列**文件与符号链接**，不列目录。于是删除只可能是对单个文件/链接的
 * `File.delete()`，永远不会出现 `deleteRecursively()`：一个「旧清单里有、新清单里没有」的
 * 目录，里面可能已经住了用户自己的文件，递归删它就是删用户的东西。副作用是空的旧目录会
 * 留下（几十字节），这是为「清单之外的节点一个都不删」付的、明码标价的代价。
 *
 * 不 import 任何 Android 类，也不碰文件系统：本文件由 bare-JVM harness 编译并直接驱动。
 */
object PayloadPrune {

    /**
     * 解析 `<name>.list` 的正文：一行一个相对路径，去掉 `\r` 与首尾空白，跳过空行。
     *
     * 保序返回（清单本身是排好序的，这里的顺序只用于可读性）。
     */
    fun parseList(text: String): List<String> = text.lineSequence()
        .map { it.trim().removeSuffix("\r") }
        .filter { it.isNotEmpty() }
        .toList()

    /**
     * 写回清单正文：去重、按字典序排序、一行一条、结尾一个换行；空集合写成空串。
     *
     * 排序是判定的一部分而不是排版：清单要能被逐字比较，而 `Files.walk` 的顺序不是
     * 规定顺序（`tools/fetch-runtime.mjs` 生成时同样排序）。
     */
    fun encodeList(paths: Collection<String>): String {
        val sorted = paths.filter { isSafePath(it) }.distinct().sorted()
        if (sorted.isEmpty()) return ""
        return sorted.joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * 这条清单条目是否可以安全地解析成易失树里的一个相对路径。
     *
     * 拒绝空串、绝对路径、任何 `..` 分量、以及以 `./` 开头的写法（清单格式规定不带这个
     * 前缀，出现它就说明生成端有另一套写法，宁可拒绝）。
     */
    fun isSafePath(relative: String): Boolean {
        if (relative.isBlank()) return false
        if (relative.startsWith("/")) return false
        if (relative.startsWith("./")) return false
        if (relative.contains('\\')) return false
        return relative.split('/').none { it.isEmpty() || it == ".." || it == "." }
    }

    /**
     * 本次覆盖解压后要删的相对路径，**由深到浅**排序（先删文件，父目录才有机会变空）。
     *
     * @param old 旧清单：上一次成功安装该载荷时它拥有、且当时确实写进树的路径。
     * @param new 新清单：这一份载荷现在拥有、并且刚刚被覆盖写入的路径。
     * @param present 当前树上确实存在的**非目录**节点（调用方用不跟随链接的探测得到）。
     */
    fun victims(old: List<String>, new: List<String>, present: Set<String>): List<String> {
        val owned = new.toHashSet()
        return old.asSequence()
            .filter { isSafePath(it) }
            .filter { it !in owned }
            .filter { it in present }
            .distinct()
            .sortedWith(compareByDescending<String> { depth(it) }.thenByDescending { it })
            .toList()
    }

    /**
     * [candidate] 是不是 [root] 或它下面的节点——**词法的**前缀判断，两者都必须是已经
     * 解析过的绝对路径。
     *
     * 这是 [victims] 之外的第二道闸，防的是「清单条目本身合法，但它现在指到树外」：清单是
     * 构建期按载荷内容生成的，条目不多不少就是那些相对路径；可是**解压之后**，树里任何一层
     * 目录都可能被换成符号链接（客体内以 App 的 uid 运行，`ln -s` 是允许的）。只按词法拼
     * `runtime/<条目>` 再 `delete()`，一条 `rootfs/a/b` 就可能顺着被换掉的 `rootfs/a` 删到
     * `<files>/pi/.pi/agent/b` —— 正是这次改动要保证永远不会发生的事。
     *
     * 所以调用方先取**规范路径**（解析符号链接），再用这个函数判断它还在不在易失树里；
     * 不在就拒绝删除。判定放在这里而不是调用方，是因为它必须能被 bare-JVM harness 钉住
     * （符号链接的构造与解析是文件系统的事，前缀判定才是那条边界）。
     */
    fun within(root: String, candidate: String): Boolean {
        val base = root.trimEnd('/')
        if (base.isEmpty()) return false
        return candidate == base || candidate.startsWith("$base/")
    }

    private fun depth(relative: String): Int = relative.count { it == '/' }
}
