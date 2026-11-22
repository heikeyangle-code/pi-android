package app.pi.ui.render

/**
 * 一列的 GFM 对齐方式：`TABLE_SEPARATOR` 那一行里冒号的位置。
 *
 * 解析器**不把对齐存进 AST**：`GFMTokenTypes.TABLE_SEPARATOR` 只是一个 token，
 * `org.jetbrains:markdown 0.7.9` 只在生成 HTML 时读它
 * （`TablesGeneratingProvider.getAlignmentInfo`），Compose 渲染器从不看它
 * （`MarkdownTable.kt` 只用 `TABLE_SEPARATOR` 决定哪一行要画分隔线）。所以对齐只能
 * 由 App 自己从这一行的**文本**里再读一次 —— 就是本文件那个纯函数。
 *
 * 纯逻辑、不碰 Compose、不碰 `android.*`、**也不 import 那个解析器**（把 AST 节点换算成
 * 这一行文本的那一步在 `PiMarkdownComponents.kt` 的 `piTableAlignments` 里）：这样
 * `tools/run-app-pure-checks.sh` 里的裸 JVM 检查只凭 kotlin stdlib 就能编译并执行它
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
        // 上游对单个字符 `:` 也走"两端都有冒号"这一支（判成 center）。这里不额外收紧：
        // 多一条"至少一个连字符"的判断，只会让 App 与 GitHub/IDE 的对齐结果出现分叉。
        starts && ends -> PiTableAlign.Center
        starts -> PiTableAlign.Left
        ends -> PiTableAlign.Right
        else -> PiTableAlign.Default
    }
}

/**
 * 估算一格文本的显示宽度（按半角字符计：CJK / 全角记 2，ASCII 记 1）：
 * - 还原行内数学公式哨兵为可读 Unicode 宽度，避免 `PiMathTok0X` 虚增列宽；
 * - 剥离 Markdown 链接 URL（`[文案](https://...)` 只计 `文案`）与行内图片 URL（`![alt](url)` 按缩略图计宽）；
 * - 忽略常见行内 HTML 标签与 Markdown 强调/转义标记。
 */
internal fun estimateTableCellDisplayChars(
    raw: String,
    inlineMath: PiInlineMath.Extracted? = null,
): Int {
    val resolved = if (inlineMath != null && raw.indexOf(inlineMath.markerPrefix) >= 0) {
        PiInlineMath.resolveToUnicode(raw, inlineMath)
    } else {
        raw
    }
    val trimmed = resolved.trim()
    if (trimmed.isEmpty()) return 0
    var units = 0
    var i = 0
    val n = trimmed.length
    var scanned = 0
    while (i < n && scanned < 180) {
        val c = trimmed[i]
        // 行内图片 `![alt](url)` -> 按紧凑缩略图估算宽度
        if (c == '!' && i + 1 < n && trimmed[i + 1] == '[') {
            val closeBracket = trimmed.indexOf("](", i + 2)
            if (closeBracket >= 0) {
                val closeParen = trimmed.indexOf(')', closeBracket + 2)
                if (closeParen >= 0) {
                    units += 8
                    scanned += 8
                    i = closeParen + 1
                    continue
                }
            }
        }
        // Markdown 链接 `[label](url)` -> 仅计 label 宽度，忽略 URL
        if (c == '[') {
            val closeBracket = trimmed.indexOf("](", i + 1)
            if (closeBracket >= 0) {
                val closeParen = trimmed.indexOf(')', closeBracket + 2)
                if (closeParen >= 0) {
                    units += estimatePlainDisplayUnits(trimmed, i + 1, closeBracket)
                    scanned += (closeBracket - i)
                    i = closeParen + 1
                    continue
                }
            }
        }
        // 行内 HTML 标签 `<...>` -> 跳过标签本身
        if (c == '<') {
            val closeAngle = trimmed.indexOf('>', i + 1)
            if (closeAngle in (i + 1)..(i + 64)) {
                i = closeAngle + 1
                continue
            }
        }
        // CommonMark 反斜杠转义 `\*` -> 只计被转义的字符
        if (c == '\\' && i + 1 < n) {
            val next = trimmed[i + 1]
            units += if (next.code >= 0x2E80) 2 else 1
            scanned++
            i += 2
            continue
        }
        if (c == '`' || c == '*' || c == '_' || c == '~') {
            i++
            continue
        }
        units += if (c.code >= 0x2E80) 2 else 1
        scanned++
        i++
    }
    return units
}

private fun estimatePlainDisplayUnits(text: String, start: Int, end: Int): Int {
    var units = 0
    var i = start
    while (i < end) {
        val c = text[i]
        if (c != '`' && c != '*' && c != '_' && c != '~') {
            units += if (c.code >= 0x2E80) 2 else 1
        }
        i++
    }
    return units
}

/**
 * 按列内容估算比例列宽（dp）：
 * - 短列（如 `#` / `状态` / `版本`）不再硬占 120 dp 留出大片空白；
 * - 长说明列获得更宽的空间，减少无谓折行与横向滚动；
 * - 若所有列的自然宽度之和 <= [availableDp]，按比例温和撑满 [availableDp]；
 * - 若超过 [availableDp]，保持自然夹紧宽度，由表格外层横向平滑滚动。
 */
internal fun computePiTableColumnWidthsDp(
    columnDisplayChars: List<Int>,
    availableDp: Float,
    charWidthDp: Float = 8.4f,
    horizontalPaddingDp: Float = 24f,
    minColDp: Float = 56f,
    maxColDp: Float = 240f,
): List<Float> {
    if (columnDisplayChars.isEmpty()) return emptyList()
    val natural = columnDisplayChars.map { chars ->
        val raw = chars.coerceAtLeast(2) * charWidthDp + horizontalPaddingDp
        raw.coerceIn(minColDp, maxColDp)
    }
    val total = natural.sum()
    if (availableDp <= 0f || total <= 0f) return natural
    if (total <= availableDp) {
        // 分配多余空间时偏向较宽的列（避免把 2 字符的 `#` 列拉成 100dp）
        val extra = availableDp - total
        val weights = natural.map { (it - minColDp + 16f).coerceAtLeast(8f) }
        val weightSum = weights.sum()
        return natural.mapIndexed { idx, w -> w + extra * (weights[idx] / weightSum) }
    }
    // 略微超宽（<= 1.18x）且压缩后每列仍 >= minColDp 时温和收进屏幕，避免轻微溢出也要横滑
    if (total <= availableDp * 1.18f) {
        val scale = availableDp / total
        val shrunk = natural.map { (it * scale).coerceAtLeast(minColDp) }
        if (shrunk.sum() <= availableDp + 1f) return shrunk
    }
    return natural
}
