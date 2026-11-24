package app.pi.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTextSelectTest {

    @Test
    fun `splits mixed markdown into structured selectable blocks without losing content`() {
        val md = """
            ## Summary

            Updated `TailFollow` with **zero** jitter.

            ```kotlin
            fun keepFollowing(): Boolean {
                // blank line inside code must stay in the same block

                return true
            }
            ```

            > Quoted insight from [Pi docs](https://example.com/pi)

            - First bullet
            - Second bullet
        """.trimIndent()

        val blocks = splitSelectableBlocks(md)
        assertEquals(5, blocks.size)

        assertEquals(SelectableBlockKind.Heading, blocks[0].kind)
        assertEquals("Summary", blocks[0].plainText)

        assertEquals(SelectableBlockKind.Paragraph, blocks[1].kind)
        assertEquals("Updated TailFollow with zero jitter.", blocks[1].plainText)

        assertEquals(SelectableBlockKind.Code, blocks[2].kind)
        assertEquals("kotlin", blocks[2].label)
        assertTrue(blocks[2].plainText.contains("return true"))
        assertFalse(blocks[2].plainText.contains("```"))

        assertEquals(SelectableBlockKind.Quote, blocks[3].kind)
        assertEquals("Quoted insight from Pi docs (https://example.com/pi)", blocks[3].plainText)

        assertEquals(SelectableBlockKind.List, blocks[4].kind)
        assertTrue(blocks[4].rawText.contains("- First bullet"))
        assertTrue(blocks[4].rawText.contains("- Second bullet"))
    }

    @Test
    fun `detects markdown formatting cleanly and keeps plain text untouched`() {
        assertFalse(hasMarkdownFormatting("Plain sentence with no formatting."))
        assertTrue(hasMarkdownFormatting("Here is **bold** and `code`."))
        assertEquals("Plain sentence with no formatting.", stripMarkdownForPlainCopy("Plain sentence with no formatting."))
        assertEquals("Here is bold and code.", stripMarkdownForPlainCopy("Here is **bold** and `code`."))
    }

    @Test
    fun `preserves code block syntax intact during plain copy`() {
        val md = "Intro with **emphasis**\n\n```bash\necho \"**keep** `literal`\"\n```"
        val plain = stripMarkdownForPlainCopy(md)
        assertEquals("Intro with emphasis\n\necho \"**keep** `literal`\"", plain)
    }
}
