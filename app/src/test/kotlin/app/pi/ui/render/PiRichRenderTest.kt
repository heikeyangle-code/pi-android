package app.pi.ui.render

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
        // Markdown 下划线被哨兵保护，不暴露给 GFM 解析器
        assertFalse(extracted.markdown.contains("x_1"))
        assertTrue(extracted.markdown.contains("**加粗**"))

        // 任何非段落表面调用 resolveToUnicode 绝不泄漏私有区哨兵
        val resolved = PiInlineMath.resolveToUnicode(extracted.markdown, extracted.entries)
        assertFalse(resolved.contains(PiInlineMath.SENTINEL_START))
        assertFalse(resolved.contains(PiInlineMath.SENTINEL_END))
        assertTrue(resolved.contains("α + β"))
    }

    @Test
    fun `normalizes standalone bracket display math and preserves code fences and inline code`() {
        val md = """
            先看公式：
            \[
            \int_0^1 x^2 \, dx = \frac{1}{3}
            \]
            行内代码 `${'$'}a + b${'$'}` 不该被提取。
            ```kotlin
            val price = "${'$'}100 and ${'$'}200"
            ```
        """.trimIndent()
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasBlockMath)
        assertEquals(0, extracted.entries.size)
        assertTrue(extracted.markdown.contains("$$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n$$"))
        assertTrue(extracted.markdown.contains("`${'$'}a + b${'$'}`"))
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
}
