package app.pi.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reducer is the client's whole notion of "what is on screen". It is tested
 * here as a deterministic state machine: a fake clock and hand-written event
 * records, no Android, no engine.
 */
class TranscriptReducerTest {

    private var clock = 1_000L
    private fun reducer() = TranscriptReducer { clock }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun text(s: String) =
        PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$s"}}""")

    private fun thinking(s: String) =
        PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"$s"}}""")

    @Test
    fun `user prompt appears immediately, before any engine event`() {
        val r = reducer()
        r.onUserPrompt("hello")
        assertEquals(1, r.transcript.size)
        assertTrue(r.transcript[0] is UserMessage)
        assertEquals("hello", (r.transcript[0] as UserMessage).text)
    }

    @Test
    fun `text deltas accumulate into a single block`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"agent_start"}"""))
        r.onEvent(text("Hel"))
        r.onEvent(text("lo "))
        r.onEvent(text("world"))
        assertEquals(1, r.transcript.size)
        val block = r.transcript[0] as AssistantText
        assertEquals("Hello world", block.text)
        assertTrue(block.streaming)
        assertTrue(r.streaming)
    }

    @Test
    fun `a new assistant block starts after the previous one is closed`() {
        val r = reducer()
        r.onEvent(text("first"))
        r.onEvent(PiEvents.parse("""{"type":"message_end","message":{"role":"assistant"}}"""))
        r.onEvent(text("second"))
        assertEquals(2, r.transcript.size)
        assertFalse((r.transcript[0] as AssistantText).streaming)
        assertTrue((r.transcript[1] as AssistantText).streaming)
    }

    @Test
    fun `thinking and text are separate blocks`() {
        val r = reducer()
        r.onEvent(thinking("let me think"))
        r.onEvent(text("answer"))
        assertEquals(2, r.transcript.size)
        assertTrue(r.transcript[0] is ThinkingBlock)
        assertTrue(r.transcript[1] is AssistantText)
    }

    @Test
    fun `tool call start creates a pending card before arguments arrive`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","id":"tc1","toolName":"bash"}}"""))
        val card = r.transcript.single() as ToolCall
        assertEquals("bash", card.toolName)
        assertEquals(ToolStatus.Pending, card.status)
    }

    @Test
    fun `tool lifecycle attaches args, streams output and finalises`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","id":"tc1","toolName":"bash"}}"""))
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"bash","args":{"command":"npm test"}}"""))
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_update","toolCallId":"tc1","partialResult":{"content":[{"type":"text","text":"PASS a"}]}}"""))
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_update","toolCallId":"tc1","partialResult":{"content":[{"type":"text","text":"PASS b"}]}}"""))

        val mid = r.transcript.single() as ToolCall
        assertEquals("npm test", mid.args!!["command"].toString().trim('"'))
        assertTrue(mid.output.contains("PASS a"))
        assertTrue(mid.output.contains("PASS b"))

        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"tc1","toolName":"bash","isError":false,"result":{"content":[{"type":"text","text":"ok"}]}}"""))
        val done = r.transcript.single() as ToolCall
        assertEquals(ToolStatus.Success, done.status)
        assertEquals("ok", done.output)
        assertTrue(done.endedAt != null)
    }

    @Test
    fun `a failed tool becomes an error card carrying structured details`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"t9","toolName":"edit","args":{}}"""))
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"t9","toolName":"edit","isError":true,"result":{"content":[{"type":"text","text":"no match"}],"details":{"diff":"@@ bad"}}}"""))
        // The card plus the diff block derived from `details.diff`.
        val card = r.transcript[0] as ToolCall
        assertEquals(ToolStatus.Error, card.status)
        assertTrue(card.isError)
        assertTrue(card.details.toString().contains("@@ bad"))
        assertEquals(2, r.transcript.size)
        assertTrue(r.transcript[1] is ToolDiff)
    }

    @Test
    fun `a tool end without a preceding start still produces a card`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"orphan","toolName":"read","isError":false,"result":{"content":[{"type":"text","text":"x"}]}}"""))
        assertEquals(1, r.transcript.size)
        assertEquals("read", (r.transcript[0] as ToolCall).toolName)
    }

    @Test
    fun `agent end closes every streaming block`() {
        val r = reducer()
        r.onEvent(thinking("t"))
        r.onEvent(text("a"))
        r.onEvent(PiEvents.parse("""{"type":"agent_end","willRetry":false}"""))
        assertFalse(r.streaming)
        assertTrue(r.transcript.none { it is AssistantText && it.streaming })
        assertTrue(r.transcript.none { it is ThinkingBlock && it.streaming })
    }

    @Test
    fun `compaction becomes a real marker and retries surface as notices`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_start","reason":"threshold"}"""))
        r.onEvent(PiEvents.parse("""{"type":"auto_retry_start","attempt":2,"maxAttempts":3,"delayMs":4000}"""))
        assertEquals(2, r.transcript.size)
        val compaction = r.transcript[0] as CompactionMarker
        assertEquals(CompactionMarker.Status.Running, compaction.status)
        assertEquals("threshold", compaction.reason)
        assertTrue(r.transcript[1] is Notice)
        assertTrue((r.transcript[1] as Notice).text.contains("4s"))
        assertEquals(Notice.Tone.Warning, (r.transcript[1] as Notice).tone)
    }

    @Test
    fun `unknown events surface once and never crash the reducer`() {
        val r = reducer()
        r.onEvent(text("before"))
        // Two *different* unknown kinds — a well-formed record with a new type and
        // an unparsable line, which also arrives as `PiEvent.Unknown`. The notice
        // sentence is shared and deduped, so the stream gains exactly one row, not
        // one per kind (and the unparsable line must not throw).
        r.onEvent(PiEvents.parse("""{"type":"totally_new_event","x":1}"""))
        r.onEvent(PiEvents.parse("} garbage"))
        r.onEvent(text("after"))
        assertEquals(2, r.transcript.size)
        assertEquals("beforeafter", (r.transcript[0] as AssistantText).text)
        val notice = r.transcript[1] as Notice
        assertEquals("收到一条当前版本不认识的消息；升级 App 后可能可见。", notice.text)
    }

    @Test
    fun `reset clears state for a new session`() {
        val r = reducer()
        r.onUserPrompt("hi")
        r.onEvent(text("yo"))
        r.reset()
        assertEquals(0, r.transcript.size)
        assertFalse(r.streaming)
        r.onEvent(text("fresh"))
        assertEquals(1, r.transcript.size)
    }

    @Test
    fun `keys are stable per block so the list can animate the right row`() {
        val r = reducer()
        r.onEvent(text("a"))
        val key = (r.transcript[0] as AssistantText).key
        clock += 5_000
        r.onEvent(text("b"))
        assertEquals(key, (r.transcript[0] as AssistantText).key)
    }

    // ------------------------------------------- tool update throttle (F8)

    private fun toolStart(id: String) =
        PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"$id","toolName":"bash","args":{}}""")

    private fun toolUpdate(id: String, chunk: String) =
        PiEvents.parse(
            """{"type":"tool_execution_update","toolCallId":"$id",""" +
                """"partialResult":{"content":[{"type":"text","text":"$chunk"}]}}""",
        )

    private fun toolEnd(id: String, text: String) =
        PiEvents.parse(
            """{"type":"tool_execution_end","toolCallId":"$id","toolName":"bash","isError":false,""" +
                """"result":{"content":[{"type":"text","text":"$text"}]}}""",
        )

    /**
     * docs/pi-android-ui-spec.md:369 §4.3 asks for `tool_execution_update` to be
     * throttled to 200 ms; pi's own renderer coalesces for the same reason
     * (`packages/tui/src/tui.ts:477`, `:986-1005`).
     */
    @Test
    fun `tool execution updates are throttled to the spec's 200 ms window`() {
        val r = reducer()
        r.onEvent(toolStart("tc1"))

        clock = 1_000
        assertTrue(r.onEvent(toolUpdate("tc1", "a")) is TranscriptChange.Updated)
        clock = 1_100
        assertEquals(TranscriptChange.None, r.onEvent(toolUpdate("tc1", "b")))
        clock = 1_199
        assertEquals(TranscriptChange.None, r.onEvent(toolUpdate("tc1", "c")))
        // The stored row never lags, only the publication.
        assertEquals("abc", (r.transcript.single() as ToolCall).output)

        clock = 1_200
        val change = r.onEvent(toolUpdate("tc1", "d"))
        assertEquals(TranscriptChange.Updated(0), change)
        assertEquals("abcd", (r.transcript.single() as ToolCall).output)
    }

    /** The throttle must never swallow the last chunk of a burst (F8's trap). */
    @Test
    fun `the last update before a turn ends is still delivered`() {
        val r = reducer()
        r.onEvent(toolStart("tc1"))
        clock = 1_000
        r.onEvent(toolUpdate("tc1", "one"))
        clock = 1_010
        assertEquals(TranscriptChange.None, r.onEvent(toolUpdate("tc1", "two")))
        clock = 1_020
        assertEquals(TranscriptChange.None, r.onEvent(toolUpdate("tc1", "three")))

        // A turn boundary with no tool end still flushes the row the throttle
        // was holding: `agent_end` funnels through `finishStreaming`.
        clock = 1_030
        assertEquals(TranscriptChange.Updated(0), r.onEvent(PiEvents.parse("""{"type":"agent_end"}""")))
        val card = r.transcript.single() as ToolCall
        assertEquals("onetwothree", card.output)
    }

    @Test
    fun `a tool end always publishes the final row, throttle or not`() {
        val r = reducer()
        r.onEvent(toolStart("tc1"))
        clock = 1_000
        r.onEvent(toolUpdate("tc1", "partial"))
        clock = 1_010
        assertEquals(TranscriptChange.None, r.onEvent(toolUpdate("tc1", " more")))

        clock = 1_015
        assertTrue(r.onEvent(toolEnd("tc1", "final output")) is TranscriptChange.Updated)
        val card = r.transcript.single() as ToolCall
        assertEquals(ToolStatus.Success, card.status)
        assertEquals("final output", card.output)
    }

    @Test
    fun `an aborted turn still publishes the chunk the throttle was holding`() {
        val r = reducer()
        r.onEvent(toolStart("tc1"))
        clock = 1_000
        r.onEvent(toolUpdate("tc1", "before the abort"))
        clock = 1_010
        assertEquals(TranscriptChange.None, r.onEvent(toolUpdate("tc1", " last")))

        clock = 1_020
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","stopReason":"aborted"}}""",
            ),
        )
        val card = r.transcript[0] as ToolCall
        assertEquals(ToolStatus.Error, card.status)
        assertEquals("before the abort last", card.output)
    }

    /**
     * pi runs the tool calls of one message strictly one at a time
     * (`agent-loop.ts:497-539`), so a stamp per call id is what keeps the first
     * chunk of the *next* call from being swallowed by the previous one's window.
     */
    @Test
    fun `one tool's throttle window does not swallow the next tool's first chunk`() {
        val r = reducer()
        clock = 1_000
        r.onEvent(toolStart("a"))
        assertTrue(r.onEvent(toolUpdate("a", "x")) is TranscriptChange.Updated)
        r.onEvent(toolEnd("a", "x"))

        // Same millisecond: a shared stamp would suppress this.
        assertTrue(r.onEvent(toolStart("b")) is TranscriptChange.Appended)
        assertTrue(r.onEvent(toolUpdate("b", "y")) is TranscriptChange.Updated)
    }

    @Test
    fun `reset drops the throttle so a new session publishes immediately`() {
        val r = reducer()
        clock = 1_000
        r.onEvent(toolStart("a"))
        r.onEvent(toolUpdate("a", "x"))
        r.reset()
        r.onEvent(toolStart("a"))
        clock = 1_000
        assertTrue(r.onEvent(toolUpdate("a", "y")) is TranscriptChange.Updated)
    }

    // ------------------------------------------------------- user message rows
    //
    // pi draws a user row from its own event (`interactive-mode.ts:3222-3225`),
    // and this app also echoes locally for instant feedback, so the two must not
    // both become bubbles. An extension's `pi.sendUserMessage()` reaches the same
    // event (`core/agent-session.ts:1569-1605`).

    private fun userEnd(text: String) =
        PiEvents.parse(
            """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}""",
        )

    @Test
    fun `pi's own message_end confirms the local echo instead of duplicating it`() {
        val r = reducer()
        r.onUserPrompt("hello")
        r.onEvent(userEnd("hello"))
        assertEquals(1, r.transcript.size)
        assertTrue(r.transcript[0] is UserMessage)
        assertEquals("hello", (r.transcript[0] as UserMessage).text)
    }

    @Test
    fun `an extension's user message appears with no local echo`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_start","message":{"role":"user"}}"""))
        r.onEvent(userEnd("from the extension"))
        assertEquals(1, r.transcript.size)
        assertEquals("from the extension", (r.transcript[0] as UserMessage).text)
    }

    @Test
    fun `a user message pi rewrote replaces the echo and still renders one row`() {
        val r = reducer()
        r.onUserPrompt("/skill:demo")
        // Built as a raw JSON record rather than through `userEnd`, and in the
        // exact shape pi's `_expandSkillCommand` writes — `parsePiSkillBlock` is
        // anchored on the real form (`rpc/SkillBlock.kt`).
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"<skill name=\"demo\" location=\"/skills/demo/SKILL.md\">\nReferences are relative to /skills/demo.\n\nthe body\n</skill>"}]}}""",
            ),
        )
        assertEquals(1, r.transcript.size)
        // pi splits a skill block out of the event
        // (`interactive-mode.ts:3631-3650`), so the projection is a card. The
        // invariant that matters: exactly one row, and not the raw echo.
        assertTrue(r.transcript[0] is SkillInvocation)
        assertEquals("demo", (r.transcript[0] as SkillInvocation).skillName)
    }

    @Test
    fun `two identical sends produce two rows, not one`() {
        val r = reducer()
        r.onUserPrompt("again")
        r.onEvent(userEnd("again"))
        r.onUserPrompt("again")
        r.onEvent(userEnd("again"))
        assertEquals(2, r.transcript.size)
    }

    @Test
    fun `a send pi refused does not swallow the next user message`() {
        val r = reducer()
        r.onUserPrompt("rejected")
        r.onEvent(PiEvents.parse("""{"type":"response","command":"prompt","success":false,"error":"no model"}"""))
        r.onEvent(userEnd("from the extension"))
        assertEquals(2, r.transcript.size)
        assertEquals("from the extension", (r.transcript[1] as UserMessage).text)
    }

    @Test
    fun `a rewritten echo behind tool rows is left alone rather than shifted`() {
        val r = reducer()
        r.onUserPrompt("/skill:demo")
        // Tool rows land after the queued echo; removing it now would renumber
        // them and a later tool update would mutate the wrong row.
        r.onEvent(toolStart("t1"))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"<skill name=\"demo\" location=\"/s/SKILL.md\">\nbody\n</skill>"}]}}""",
            ),
        )
        assertEquals(2, r.transcript.size)
        assertEquals("/skill:demo", (r.transcript[0] as UserMessage).text)
        // The tool row is still the tool row, at its own index.
        assertTrue(r.transcript[1] is ToolCall)
    }

    @Test
    fun `a rebuilt stream renders the replayed row and ignores a late confirmation`() {
        val r = reducer()
        r.onUserPrompt("local echo")
        r.reset()
        r.onEntry(obj("""{"type":"message","id":"e1","message":{"role":"user","content":[{"type":"text","text":"replayed"}]}}"""))
        r.onEvent(userEnd("replayed"))
        // The reset dropped the pending echo, so the event is a message the app
        // never showed and must be projected rather than swallowed.
        assertEquals(2, r.transcript.size)
        assertEquals("replayed", (r.transcript[1] as UserMessage).text)
    }
}
