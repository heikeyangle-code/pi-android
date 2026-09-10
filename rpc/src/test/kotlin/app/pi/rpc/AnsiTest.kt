package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANSI handling is not cosmetic here: pi's theme helpers and most CLI tools emit
 * SGR, and tool output is shown verbatim in the transcript. A parser that drops
 * a sequence it does not know is fine; one that mangles it puts escape bytes on
 * screen.
 */
class AnsiTest {

    private val esc = "\u001b"

    @Test
    fun `plain text passes through as one default span`() {
        val spans = Ansi.parse("hello")
        assertEquals(1, spans.size)
        assertEquals("hello", spans[0].text)
        assertNull(spans[0].foreground)
        assertFalse(spans[0].bold)
    }

    @Test
    fun `empty input yields no spans`() {
        assertEquals(emptyList<Ansi.Span>(), Ansi.parse(""))
    }

    @Test
    fun `basic and bright foreground colours map to the standard palette`() {
        val spans = Ansi.parse("${esc}[31mred${esc}[0m ${esc}[92mbright green")
        assertEquals("red", spans[0].text)
        assertEquals(0x800000, spans[0].foreground)
        assertEquals(" ", spans[1].text)
        assertNull(spans[1].foreground)
        assertEquals("bright green", spans[2].text)
        assertEquals(0x00FF00, spans[2].foreground)
    }

    @Test
    fun `background colours are tracked separately from foreground`() {
        val spans = Ansi.parse("${esc}[44;37mx")
        assertEquals("x", spans[0].text)
        assertEquals(0x000080, spans[0].background)
        assertEquals(0xC0C0C0, spans[0].foreground)
    }

    @Test
    fun `text attributes are carried`() {
        val spans = Ansi.parse("${esc}[1;2;3;4mstyled")
        assertTrue(spans[0].bold)
        assertTrue(spans[0].dim)
        assertTrue(spans[0].italic)
        assertTrue(spans[0].underline)
    }

    @Test
    fun `bold can be turned back off without clearing colour`() {
        val spans = Ansi.parse("${esc}[1;31mbold${esc}[22mplain")
        assertTrue(spans[0].bold)
        assertFalse(spans[1].bold)
        assertEquals(0x800000, spans[1].foreground)
    }

    @Test
    fun `256-colour indices decode to the xterm cube`() {
        // 196 is pure red in the 6x6x6 cube: index-16 = 180 -> r=5,g=0,b=0.
        val red = Ansi.parse("${esc}[38;5;196mx")
        assertEquals(0xFF0000, red[0].foreground)

        // 21 is the blue corner: index-16 = 5 -> r=0,g=0,b=5.
        val blue = Ansi.parse("${esc}[38;5;21mx")
        assertEquals(0x0000FF, blue[0].foreground)

        // 232..255 are the grey ramp, 8 + 10*n.
        val grey = Ansi.parse("${esc}[38;5;240mx")
        assertEquals(0x585858, grey[0].foreground)
    }

    @Test
    fun `true colour is honoured`() {
        val spans = Ansi.parse("${esc}[38;2;18;52;86mdeep")
        assertEquals(0x123456, spans[0].foreground)
        val bg = Ansi.parse("${esc}[48;2;255;128;0mx")
        assertEquals(0xFF8000, bg[0].background)
    }

    @Test
    fun `out of range channels are clamped rather than wrapped`() {
        val spans = Ansi.parse("${esc}[38;2;300;-5;999mx")
        assertEquals(0xFF00FF, spans[0].foreground)
    }

    @Test
    fun `reset clears everything`() {
        val spans = Ansi.parse("${esc}[1;4;31;44ma${esc}[0mb")
        assertEquals("a", spans[0].text)
        assertTrue(spans[0].bold)
        assertEquals("b", spans[1].text)
        assertFalse(spans[1].bold)
        assertNull(spans[1].foreground)
        assertNull(spans[1].background)
    }

    @Test
    fun `non-SGR control sequences are dropped, not printed`() {
        // Cursor movement and clear-screen have no meaning in a captured stream.
        val spans = Ansi.parse("${esc}[2Ja${esc}[10;20Hb")
        assertEquals("ab", spans.joinToString("") { it.text })
    }

    @Test
    fun `an unterminated sequence at the end of a truncated stream is discarded`() {
        val spans = Ansi.parse("ok${esc}[38;5;")
        assertEquals("ok", spans.joinToString("") { it.text })
    }

    @Test
    fun `strip removes escapes and leaves the text`() {
        assertEquals("hello world", Ansi.strip("${esc}[31mhello${esc}[0m world"))
        assertEquals("plain", Ansi.strip("plain"))
        assertEquals("ab", Ansi.strip("${esc}[2Ja${esc}[1mb"))
    }

    @Test
    fun `containsEscapes is a cheap pre-check`() {
        assertFalse(Ansi.containsEscapes("nothing here"))
        assertTrue(Ansi.containsEscapes("${esc}[31mx"))
    }

    @Test
    fun `colour survives across multiple spans of one line`() {
        // A realistic npm/pytest-style line.
        val line = "${esc}[32mPASS${esc}[0m test/a ${esc}[31mFAIL${esc}[0m test/b"
        val spans = Ansi.parse(line)
        assertEquals("PASS", spans[0].text)
        assertEquals(0x008000, spans[0].foreground)
        assertTrue(spans.any { it.text == "FAIL" && it.foreground == 0x800000 })
    }
}
