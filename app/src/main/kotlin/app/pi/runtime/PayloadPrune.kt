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
 * ## 目录也在清单里（否则永远删不掉）
 *
 * 清单里的条目**既有文件/符号链接，也有目录**（`tools/fetch-runtime.mjs` 的 `payloadPaths`
 * 剥掉尾部斜杠后同样收下目录行）。这一条是必需的，不是顺手：目录如果没有"所有权"，就会
 * 出现一个谁也认领不了的残留 ——
 *
 *  1. 旧载荷在那条路径上有文件，新载荷没有 ⇒ 文件进了 [victims]，被 `File.delete()` 删掉；
 *  2. 目录本身**不在**旧清单里 ⇒ 它从来没有被当作"旧载荷拥有的东西"，也就永远进不了删除
 *     集合；
 *  3. 下一份载荷也不拥有它（它的成员早就不在新清单里）⇒ 那个空目录**永远留着**。
 *
 * 空的 `node_modules` 目录不是无害的几十字节：Node 的解析撞上 `.../node_modules/<pkg>/`
 * 就**就地失败、不再往上找**，真包明明在上层也会被判成"找不到包"。
 *
 * 目录进了 [victims] 之后，为什么**不需要**任何新机制：
 *
 *  - [victims] 已经按 `compareByDescending { depth(it) }` 排序 ⇒ **文件先于它的父目录**；
 *  - 删除动作是**单节点 `File.delete()`**（调用方那一处）⇒ 对**空**目录成功，对**非空**
 *    目录**必然失败**；
 *  - 于是"旧文件删掉 → 同一个父目录紧接着出现 → 此时它已经空了 → 删掉"是顺序的自然结果；
 *    而"目录里还有用户后来加的 `npm -g`/apt/手建文件"→ 非空 → 删除失败 → **保留**。
 *
 * 那条安全规则因此**不需要额外判断**：它是 `File.delete()` 的语义本身。也**不许**换成
 * `deleteRecursively()`：递归删一个有内容的目录就是删用户的东西。
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
     * 本次覆盖解压后要删的相对路径，**由深到浅**排序。
     *
     * 排序是删除正确性的一半：**文件先于它的父目录**。删除是单节点的
     * `File.delete()`，对空目录成功、对非空目录必然失败，所以同一个父目录紧跟在它的文件
     * 后面被删时，它已经空了 —— 于是"旧载荷的目录不会留下空壳"是这条顺序的自然结果；而
     * 里面还有清单之外的东西（用户后来装的、手建的）时，删除失败、目录保留，用户的东西
     * 一个字节没动。
     *
     * @param old 旧清单：上一次成功安装该载荷时它拥有、且当时确实写进树的路径。**含目录**
     *        （构建期把目录行也写进 `<name>.list`，尾部斜杠已剥掉），这就是旧载荷在那条路径
     *        上的所有权能覆盖到目录的原因。
     * @param new 新清单：这一份载荷现在拥有、并且刚刚被覆盖写入的路径，同样含目录。两个清单
     *        都有的路径不是受害者（那份载荷仍然拥有它）。
     * @param present 当前树上确实存在、且**属于旧清单**的那些路径：文件、符号链接与目录
     *        （调用方用**不跟随符号链接**的探测得到，所以悬空链接也算"在"）。目录必须包含在
     *        内 —— 否则 `it in present` 会把它们全部滤掉，目录所有权就又成了空话。
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
