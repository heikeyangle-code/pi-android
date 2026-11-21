package app.pi.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolHardeningTest {

    @Test
    fun `Utf8StreamDecoder handles CJK and 4-byte emoji split across every byte boundary`() {
        val sample = "你好世界🌍🚀测试UTF-8流式解码——保持零乱码"
        val bytes = sample.toByteArray(Charsets.UTF_8)

        for (step in listOf(1, 2, 3, 5, 7)) {
            val decoder = Utf8StreamDecoder()
            val out = StringBuilder()
            var offset = 0
            while (offset < bytes.size) {
                val len = minOf(step, bytes.size - offset)
                val slice = bytes.copyOfRange(offset, offset + len)
                out.append(decoder.decode(slice, len))
                offset += len
            }
            out.append(decoder.flush())
            assertEquals("step=$step", sample, out.toString())
            assertEquals(0, decoder.carriedBytes)
        }
    }

    @Test
    fun `JsonlFramer vectorized feed preserves U+2028 U+2029 and strips CRLF`() {
        val framer = JsonlFramer(maxRecordChars = 64)
        val rec1 = """{"text":"line\u2028sep\u2029ok"}"""
        val rec2 = """{"b":2}"""
        val combined = "$rec1\r\n\n   \r\n$rec2\n"

        val all = mutableListOf<String>()
        // Feed in tiny chunks to exercise both empty-pending fast path and split-pending path.
        all += framer.feed(combined.substring(0, 10))
        all += framer.feed(combined.substring(10))
        all += framer.flush()

        assertEquals(listOf(rec1, rec2), all)
        assertEquals(0L, framer.droppedRecords)
    }

    @Test
    fun `JsonlFramer drops overlong record and continues cleanly`() {
        val framer = JsonlFramer(maxRecordChars = 16)
        val out = framer.feed("0123456789abcdefXYZ\n{\"ok\":1}\n")
        assertEquals(1L, framer.droppedRecords)
        assertEquals(listOf("{\"ok\":1}"), out)
    }

    @Test
    fun `PiEvents parse JsonObject matches parse String`() {
        val raw = """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"hello"}}"""
        val obj = Json.parseToJsonElement(raw) as JsonObject
        assertEquals(PiEvents.parse(raw), PiEvents.parse(obj))
    }

    @Test
    fun `APP_EXPOSED_PRE_SPAWN has no duplicate appKey entries`() {
        val keys = APP_EXPOSED_PRE_SPAWN.map { it.appKey }
        assertEquals("Duplicate keys found: $keys", keys.distinct().size, keys.size)
    }

    @Test
    fun `TranscriptReducer clears pending user echo when session prompt is rejected`() {
        val reducer = TranscriptReducer(now = { 1_000L })
        reducer.onUserPrompt("first message")
        reducer.onEvent(
            PiEvent.Response(
                id = null,
                command = "session.prompt",
                success = false,
                error = "no model selected",
                data = null,
            ),
        )
        // Next message confirmation must not be swallowed by the rejected first echo.
        val change = reducer.onEvent(
            PiEvent.MessageEnd(
                role = "user",
                text = "second message",
                contentBlocks = emptyList(),
            ),
        )
        assertTrue(change is TranscriptChange.Appended)
        val users = reducer.transcript.filterIsInstance<UserMessage>()
        assertEquals(listOf("first message", "second message"), users.map { it.text })
    }
}
