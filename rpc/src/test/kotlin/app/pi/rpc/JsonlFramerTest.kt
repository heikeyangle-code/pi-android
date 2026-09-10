package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framer is the one place where a subtle mistake corrupts everything
 * downstream, so these tests pin the exact properties pi's reader relies on.
 */
class JsonlFramerTest {

    @Test
    fun `splits on LF only`() {
        val f = JsonlFramer()
        assertEquals(listOf("a", "b"), f.feed("a\nb\n"))
    }

    @Test
    fun `keeps a partial record buffered across chunks`() {
        val f = JsonlFramer()
        assertEquals(emptyList<String>(), f.feed("{\"a\":"))
        assertEquals(emptyList<String>(), f.feed("1}"))
        assertEquals(listOf("{\"a\":1}"), f.feed("\n"))
    }

    /**
     * The trap pi documents in src/modes/rpc/jsonl.ts: U+2028 and U+2029 are
     * legal unescaped inside JSON strings, and models do emit them. A reader
     * built on readLine/lines() would split this single record into three.
     */
    @Test
    fun `does not split on U+2028 or U+2029`() {
        val f = JsonlFramer()
        val record = "{\"text\":\"a\u2028b\u2029c\"}"
        val out = f.feed(record + "\n")
        assertEquals(1, out.size)
        assertEquals(record, out[0])
    }

    @Test
    fun `strips exactly one trailing CR`() {
        val f = JsonlFramer()
        assertEquals(listOf("x"), f.feed("x\r\n"))
        // Exactly ONE CR is removed. "Accept optional CRLF" is not "trim
        // whitespace": a second CR is payload, and silently eating it would
        // convert a valid JSON string into a different one.
        assertEquals(listOf("y\r"), f.feed("y\r\r\n"))
    }

    @Test
    fun `skips blank and whitespace-only records`() {
        val f = JsonlFramer()
        assertEquals(listOf("a"), f.feed("\n\n   \na\n\n"))
    }

    @Test
    fun `drops over-long records and counts them instead of growing without bound`() {
        val f = JsonlFramer(maxRecordChars = 8)
        val out = f.feed("12345678901234567890\nok\n")
        assertEquals(listOf("ok"), out)
        assertEquals(1L, f.droppedRecords)
    }

    @Test
    fun `flush emits a trailing record when the engine dies mid-write`() {
        val f = JsonlFramer()
        f.feed("{\"partial\":")
        assertEquals(listOf("{\"partial\":"), f.flush())
        assertEquals(emptyList<String>(), f.flush())
    }

    @Test
    fun `flush discards an overflowing tail`() {
        val f = JsonlFramer(maxRecordChars = 4)
        f.feed("abcdefgh")
        assertEquals(emptyList<String>(), f.flush())
        assertEquals(1L, f.droppedRecords)
    }

    @Test
    fun `reset clears both the buffer and the drop counter`() {
        val f = JsonlFramer(maxRecordChars = 4)
        f.feed("abcdefgh\n")
        assertTrue(f.droppedRecords > 0)
        f.reset()
        assertEquals(0L, f.droppedRecords)
        assertEquals(0, f.bufferedChars)
        assertEquals(listOf("z"), f.feed("z\n"))
    }
}
