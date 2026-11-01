package app.pi.ui.render

import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/**
 * 一列的 GFM 对齐方式：`TABLE_SEPARATOR` 那一行里冒号的位置。
 *
 * 解析器**不把对齐存进 AST**：`GFMTokenTypes.TABLE_SEPARATOR` 只是一个 token，
 * `org.jetbrains:markdown 0.7.9` 只在生成 HTML 时读它
 * （`TablesGeneratingProvider.getAlignmentInfo`），Compose 渲染器从不看它
 * （`MarkdownTable.kt` 只用 `TABLE_SEPARATOR` 决定哪一行要画分隔线）。所以对齐只能
 * 由 App 自己从这一行的**文本**里再读一次 —— 就是本文件那个纯函数。
 *
 * 纯逻辑、不碰 Compose、不碰 `android.*`：这样它可以被裸 JVM 的检查直接编译执行
 * （与 `PiMarkdownImageTap` / `PiLatex` 同一套做法）。
 */
internal enum class PiTableAlign {
    /** `---`：这一行没写对齐。渲染时保持库的默认（起始边），**不多设** TextAlign。 */
    Default,

    /** `:---` */
    Left,

    /** `:---:` */
    Center,

    /** `---:` */
    Right,
}

/**
 * 纯函数：把 GFM 表格的对齐行拆成每列的对齐方式。
 *
 * ```text
 * |:---|:---:|---:|---|   →  [Left, Center, Right, Default]
 * ```
 *
 * 规则照抄 GFM 规范，也照抄上游 `TablesGeneratingProvider.getAlignmentInfo`
 * （`org.jetbrains:markdown 0.7.9`）实际认的那几条 —— 两边必须一致，否则同一张表在本 App 与
 * 任何 GFM 渲染器（GitHub、IDE 预览）里会对不齐：
 *
 * - 按 `|` 切分；**首段/末段为空**只是行首/行尾那根竖线，不是一个空列，丢掉。中间的空段
 *   （`|---||:--:|`）**算**一列，按 [PiTableAlign.Default] 处理 —— 上游同一条规则
 *   （`if (!cell.isBlank() || i in 1..cells.lastIndex - 1)`）。
 * - 每段先 `trim()`：`| :--- |` 与 `|:---|` 相同。
 * - 只看冒号：两端都有 = [PiTableAlign.Center]，只有开头 = [PiTableAlign.Left]，只有结尾 =
 *   [PiTableAlign.Right]，都没有 = [PiTableAlign.Default]。
 * - 不给长度设限，也不补齐/截断列数：多出来的列由调用方忽略，缺的列由调用方按
 *   [PiTableAlign.Default] 处理。**这个函数回答的是"这一行写了什么"，不是"这张表有几列"。**
 *
 * 容错：空串、只有一根竖线、没有竖线（一个单元格）都会得到一个合理结果（空表 / 一列默认），
 * 不会抛异常。分隔行本来就不是合法 GFM 时，上游会整张表不识别，走不到这里；走到了也只是
 * 每列默认，等于今天的行为。
 *
 * 已知不做：转义的 `\|`。GFM 的对齐行里它没有意义（对齐单元格只允许冒号、连字符、空白），
 * 上游也是直接 `split("|")`，这里保持一致而不是发明一套比解析器更宽的语法。
 */
internal fun parsePiTableAlignments(separator: String): List<PiTableAlign> {
    val cells = separator.split('|')
    val result = ArrayList<PiTableAlign>(cells.size)
    cells.forEachIndexed { index, cell ->
        val trimmed = cell.trim()
        // 行首/行尾那两根竖线切出来的空段不是列。中间的空段是列（默认对齐）。
        if (trimmed.isEmpty() && (index == 0 || index == cells.lastIndex)) return@forEachIndexed
        result += alignmentOf(trimmed)
    }
    return result
}

private fun alignmentOf(cell: String): PiTableAlign {
    val starts = cell.startsWith(':')
    val ends = cell.endsWith(':')
    return when {
        // 上游把 ":": 也判成 center（`starts && ends`），这里不额外收紧：多一个字符的判断
        // 只会让 App 与 GitHub/IDE 的对齐结果出现分叉。
        starts && ends -> PiTableAlign.Center
        starts -> PiTableAlign.Left
        ends -> PiTableAlign.Right
        else -> PiTableAlign.Default
    }
}

/**
 * 一张表的每列对齐方式；没有分隔行（理论上不会发生）时返回空列表。
 *
 * 取的是**表格节点的直接子节点** `TABLE_SEPARATOR` —— 整条对齐行是一个 token
 * （`|:--|:-:|--:|---|`）。注意表头/数据行里那些 `|` 也是 `TABLE_SEPARATOR`，但它们在
 * `HEADER` / `ROW` 的**里面**，不是表格的直接子节点；`findChildOfType` 只看直接子节点
 * （`ast/ASTUtil.kt` 的 `children.firstOrNull`），正好拿到整行那一个。
 *
 * 偏移量做了 `coerceIn`：这个函数会在**流式**的每一条内容上被调用，而节点是上一次解析的
 * 结果；宁可给空列表（退回库的默认），也不让一次越界的 `substring` 把整条消息打崩。
 */
internal fun piTableAlignments(content: String, table: ASTNode): List<PiTableAlign> {
    val separator = table.findChildOfType(GFMTokenTypes.TABLE_SEPARATOR) ?: return emptyList()
    val start = separator.startOffset.coerceIn(0, content.length)
    val end = separator.endOffset.coerceIn(start, content.length)
    return parsePiTableAlignments(content.substring(start, end))
}
