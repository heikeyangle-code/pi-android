package app.pi.ui.render

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiRichRenderTest {

    @Test
    fun `extracts inline dollar and paren math while preserving markdown and CJK adjacency`() {
        val md = "当\$x_1 + x_2 = \\frac{a}{b}\$时，**加粗**和\\(\\alpha + \\beta\\)都正常。"
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasInlineMath)
        assertEquals(2, extracted.entries.size)
        assertEquals("x_1 + x_2 = \\frac{a}{b}", extracted.entries[0].tex)
        assertEquals("\\alpha + \\beta", extracted.entries[1].tex)
        // Markdown 下划线被纯 ASCII 哨兵保护，不暴露给 GFM 解析器
        assertFalse(extracted.markdown.contains("x_1"))
        assertTrue(extracted.markdown.contains("**加粗**"))

        // 任何非段落表面调用 resolveToUnicode 绝不泄漏哨兵
        val resolved = PiInlineMath.resolveToUnicode(extracted.markdown, extracted)
        assertFalse(resolved.contains(extracted.markerPrefix))
        assertTrue(resolved.contains("α + β"))
    }

    @Test
    fun `real GFM MarkdownParser keeps every inline math sentinel intact inside a single leaf AST token`() {
        val md = """
            当${'$'}x_1 + x_2 \in \mathbb{R}${'$'}时，**${'$'}E = mc^2${'$'}**、*${'$'}\alpha + \beta${'$'}*与[${'$'}f(x)${'$'}](https://example.com)都正常。

            - 列表项里的行内公式：${'$'}\mathcal{L}(\theta) = \mathbb{E}[X]${'$'}
            1. 有序列表里的行内公式：\(\frac{1}{2}\)

            > 引用块里的行内公式：${'$'}\mathfrak{g}${'$'}

            ## 标题里的公式 ${'$'}a^2 + b^2 = c^2${'$'}

            | 符号 | 含义 |
            | --- | --- |
            | ${'$'}\mathbb{N}${'$'} | 自然数 |
        """.trimIndent()

        val extracted = PiInlineMath.extract(md)
        assertEquals(9, extracted.entries.size)

        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(extracted.markdown)

        // 逐个叶节点检查：每一个叶节点经 resolveToUnicode 后必须零哨兵泄漏（证明 GFMLexer 没有把哨兵切碎！）
        val matchedIndices = mutableSetOf<Int>()
        fun walkLeaves(node: ASTNode) {
            if (node.children.isEmpty()) {
                val slice = extracted.markdown.substring(node.startOffset, node.endOffset)
                for (seg in PiInlineMath.splitSegments(slice, extracted.markerPrefix, extracted.markerSuffix)) {
                    if (seg is PiInlineMath.Segment.MathRef) {
                        matchedIndices.add(seg.index)
                    }
                }
                val resolved = PiInlineMath.resolveToUnicode(slice, extracted)
                assertFalse(
                    "Leaf token leaked sentinel: '$slice' -> '$resolved'",
                    resolved.contains(extracted.markerPrefix),
                )
            } else {
                for (child in node.children) walkLeaves(child)
            }
        }
        walkLeaves(tree)

        // 全部 9 条行内公式都在真实 GFM AST 的单个叶节点内完整命中！
        assertEquals((0 until 9).toSet(), matchedIndices)
    }

    @Test
    fun `avoids sentinel collision when source markdown literally contains default marker prefix`() {
        val md = "Literal ${PiInlineMath.DEFAULT_MARKER_PREFIX}0${PiInlineMath.DEFAULT_MARKER_SUFFIX} and \$x^2\$."
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasInlineMath)
        assertFalse(extracted.markerPrefix == PiInlineMath.DEFAULT_MARKER_PREFIX)
        val resolved = PiInlineMath.resolveToUnicode(extracted.markdown, extracted)
        assertTrue(resolved.contains("${PiInlineMath.DEFAULT_MARKER_PREFIX}0${PiInlineMath.DEFAULT_MARKER_SUFFIX}"))
        assertTrue(resolved.contains("x²"))
        assertFalse(resolved.contains(extracted.markerPrefix))
    }

    @Test
    fun `normalizes standalone bracket display math and collapses blank lines inside block math`() {
        val md = """
            先看公式：
            \[
            \int_0^1 x^2 \, dx = \frac{1}{3}

            \forall x \in \mathbb{R}
            \]
            行内代码 `${'$'}a + b${'$'}` 不该被提取。
            ```kotlin
            val price = "${'$'}100 and ${'$'}200"
            ```
        """.trimIndent()
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasBlockMath)
        assertEquals(0, extracted.entries.size)
        assertTrue(extracted.markdown.contains("`${'$'}a + b${'$'}`"))

        // 真实 GFM 解析器下，规范化后的块级公式必须落在单个 PARAGRAPH 里且首尾是 $$
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(extracted.markdown)
        val paragraphs = tree.children.filter { it.type == MarkdownElementTypes.PARAGRAPH }
        val mathParagraph = paragraphs.firstOrNull { p ->
            val t = extracted.markdown.substring(p.startOffset, p.endOffset).trim()
            t.startsWith("$$") && t.endsWith("$$")
        }
        assertTrue("Block math must stay a single PARAGRAPH in GFM AST", mathParagraph != null)
    }

    @Test
    fun `isolates adjacent block math with blank lines so markdown parser never merges it into prose`() {
        val md = "We have:\n$$\nE = mc^2\n$$\nwhere \$m\$ is mass."
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasBlockMath)
        assertTrue(extracted.hasInlineMath)
        assertTrue(extracted.markdown.contains("We have:\n\n$$\nE = mc^2\n$$\n\nwhere "))
        assertEquals(1, extracted.entries.size)
        assertEquals("m", extracted.entries[0].tex)
    }

    @Test
    fun `never misclassifies currency or escaped dollar signs as inline math`() {
        val md = "Total is \$5 and \$10, or \$50 per item and \$100 max, plus escaped \\\$x\\\$."
        val extracted = PiInlineMath.extract(md)

        assertFalse(extracted.hasInlineMath)
        assertEquals(md, extracted.markdown)
    }

    @Test
    fun `proportional table column widths avoid bloating narrow columns`() {
        // | # | 状态 | 详细说明 |
        val chars = listOf(
            estimateTableCellDisplayChars("#"),
            estimateTableCellDisplayChars("正常"),
            estimateTableCellDisplayChars("这是一段较长的字段详细说明内容"),
        )
        val widths = computePiTableColumnWidthsDp(chars, availableDp = 360f)

        assertEquals(3, widths.size)
        // 窄列远小于说明列，且总宽正好铺满屏幕不触发横向滚动
        assertTrue(widths[0] < widths[2] * 0.6f)
        assertEquals(360f, widths.sum(), 1.5f)
    }

    @Test
    fun `wide table exceeds viewport and stays bounded per column`() {
        val chars = listOf(12, 24, 30, 18, 40)
        val widths = computePiTableColumnWidthsDp(chars, availableDp = 360f)

        assertEquals(5, widths.size)
        assertTrue(widths.sum() > 360f)
        assertTrue(widths.all { it in 56f..240f })
    }

    @Test
    fun `native syntax highlighter tokenizes keywords, types, functions, strings, interpolations, operators and punctuation`() {
        val kotlinSample = """
            /** Computes total for @param items */
            @Composable
            fun computeTotal(items: List<Int>): Int {
                val msg = "count=${'$'}{items.size}"
                return items.sum() + 0x10
            }
        """.trimIndent()
        val result = PiFastCodeHighlighter.highlight(kotlinSample, "kotlin")
        assertTrue(result.languageKnown)
        val tokens = result.spans.map { it.token }.toSet()
        assertTrue("Expected Comment", PiSyntaxToken.Comment in tokens)
        assertTrue("Expected Keyword", PiSyntaxToken.Keyword in tokens)
        assertTrue("Expected Type", PiSyntaxToken.Type in tokens)
        assertTrue("Expected Function", PiSyntaxToken.Function in tokens)
        assertTrue("Expected StringLiteral", PiSyntaxToken.StringLiteral in tokens)
        assertTrue("Expected Variable (interpolation/member)", PiSyntaxToken.Variable in tokens)
        assertTrue("Expected Number", PiSyntaxToken.Number in tokens)
        assertTrue("Expected Operator", PiSyntaxToken.Operator in tokens)
        assertTrue("Expected Punctuation", PiSyntaxToken.Punctuation in tokens)
        assertTrue("Expected Muted (@Composable)", PiSyntaxToken.Muted in tokens)

        // Python f-string + Dockerfile + Rust macro + TOML
        val py = PiFastCodeHighlighter.highlight("x = f\"hello {user.name}\" # note", "python")
        assertTrue(py.languageKnown)
        assertTrue(py.spans.any { it.token == PiSyntaxToken.StringLiteral })
        assertTrue(py.spans.any { it.token == PiSyntaxToken.Variable })

        val rust = PiFastCodeHighlighter.highlight("#[derive(Debug)]\nfn main() { println!(\"hi\"); }", "rust")
        assertTrue(rust.languageKnown)
        assertTrue(rust.spans.any { it.token == PiSyntaxToken.Muted })
        assertTrue(rust.spans.any { it.token == PiSyntaxToken.Function })

        val docker = PiFastCodeHighlighter.highlight("FROM ubuntu:24.04\nRUN apt-get update", "dockerfile")
        assertTrue(docker.languageKnown)
        assertTrue(docker.spans.any { it.token == PiSyntaxToken.Keyword })
    }

    @Test
    fun `reference link extractor resolves case-insensitive normalized labels`() {
        val md = """
            See [Pi Docs][pi  docs] for details.

            [PI DOCS]: https://example.com/pi
        """.trimIndent()
        val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(md)
        val refs = extractPiReferenceLinks(md, tree)
        assertEquals("https://example.com/pi", refs["pi docs"])
    }
}
