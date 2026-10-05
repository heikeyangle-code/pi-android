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
    fun `a user message is drawn when pi delivers it, not when it is sent`() {
        val r = reducer()
        // Sending is not an event. pi creates the row on `message_start(role:
        // "user")` (`interactive-mode.ts:3452-3459`, 1.0.1), so a message that is
        // merely queued has no row — the app's queue row is where a queued one shows
        // up, and nothing in the transcript may claim it was sent.
        r.onEvent(PiEvents.parse("""{"type":"queue_update","steering":["steer me"],"followUp":[]}"""))
        assertEquals(0, r.transcript.size)
        r.onEvent(userEnd("steer me"))
        assertEquals(1, r.transcript.size)
        assertTrue(r.transcript[0] is UserMessage)
        assertEquals("steer me", (r.transcript[0] as UserMessage).text)
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
        // Apart by more than the throttle window, so both chunks reach the row through a
        // publication. A suppressed chunk is deliberately *not* in the row until one of
        // the three flush points — see the throttle test below.
        clock += 1_000
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
    fun `nested tool calls never become top-level cards`() {
        val r = reducer()
        // 父调用：codemode 脚本本身。
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"codemode","args":{}}"""))
        // 脚本里的一次 `read`：pi 把 id 改写成 `<父 id>/<n>`，并照旧发三个生命周期事件，
        // 三个都带 `parentToolCallId`（`core/nested-tool-calls.ts:186-190,245-251`）。
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1/1","toolName":"read","args":{"path":"a.txt"},"parentToolCallId":"tc1"}"""))
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_update","toolCallId":"tc1/1","parentToolCallId":"tc1","partialResult":{"content":[{"type":"text","text":"nested"}]}}"""))
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"tc1/1","toolName":"read","isError":false,"result":{"content":[{"type":"text","text":"nested"}]},"parentToolCallId":"tc1"}"""))
        // 只有父调用是 transcript 内容（`docs/extensions.md:148`），也只有它会随会话落盘。
        assertEquals(1, r.transcript.size)
        val card = r.transcript[0] as ToolCall
        assertEquals("codemode", card.toolName)
        assertEquals(ToolStatus.Pending, card.status)
    }

    @Test
    fun `a nested tool end cannot append a card on its own`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"codemode","args":{}}"""))
        // 关键的一条：`finalizeTool` 对查不到 id 的调用会 append 一张新卡片，所以只挡
        // `start` 挡不住嵌套调用，`_end` 必须自己挡（否则顶层卡片晚一个事件出现）。
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"tc1/1","toolName":"read","isError":false,"result":{"content":[{"type":"text","text":"x"}]},"parentToolCallId":"tc1"}"""))
        assertEquals(1, r.transcript.size)
        assertEquals("codemode", (r.transcript[0] as ToolCall).toolName)
    }

    @Test
    fun `the nested-call guard keys on the id shape, not on any slash`() {
        val r = reducer()
        // 一个恰好含 `/` 但不是 `<父 id>/<n>` 形状的 provider id：照常建卡。
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"call_a/b","toolName":"bash","args":{}}"""))
        assertEquals(1, r.transcript.size)
        // 深度 2：`tc1/1/2` 的直接父 id 从未建过卡片，仍要沿前缀上溯认出 tc1。
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"codemode","args":{}}"""))
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"tc1/1/2","toolName":"read","isError":false,"result":{"content":[{"type":"text","text":"deep"}]},"parentToolCallId":"tc1/1"}"""))
        assertEquals(2, r.transcript.size)
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
        r.onEvent(userEnd("hi"))
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
        // Only the *publication* is coalesced. What the reduction keeps is: the row holds
        // the text as of the last publication, the reducer's accumulator holds the rest
        // (`TranscriptReducer.pendingToolOutput`), and the next publication that goes
        // through carries all of it — which is the half a consumer can see.
        assertEquals("a", (r.transcript.single() as ToolCall).output)

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
    // pi draws a user row from its own event (`interactive-mode.ts:3452-3459`,
    // 1.0.1), and this app now draws nothing of its own — the event is the only
    // source, for the app's own prompt and for an extension's
    // `pi.sendUserMessage()` alike (`core/agent-session.ts:2234-2260`).

    private fun userEnd(text: String) =
        PiEvents.parse(
            """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"$text"}]}}""",
        )

    @Test
    fun `a delivered message is exactly one row, however many times it is answered`() {
        val r = reducer()
        r.onEvent(userEnd("hello"))
        r.onEvent(PiEvents.parse("""{"type":"response","command":"prompt","success":true}"""))
        assertEquals(1, r.transcript.size)
        assertEquals("hello", (r.transcript[0] as UserMessage).text)
    }

    @Test
    fun `two identical sends produce two rows, not one`() {
        val r = reducer()
        r.onEvent(userEnd("again"))
        r.onEvent(userEnd("again"))
        assertEquals(2, r.transcript.size)
    }

    @Test
    fun `a refused send leaves no row and does not swallow the next message`() {
        val r = reducer()
        // A refusal is a `success: false` with no `message_end` behind it. Nothing
        // was drawn for the send (see the delivery test above), so there is nothing
        // to undo — and the next message pi does deliver is its own row.
        r.onEvent(PiEvents.parse("""{"type":"response","command":"prompt","success":false,"error":"no model"}"""))
        assertEquals(0, r.transcript.size)
        r.onEvent(userEnd("from the extension"))
        assertEquals(1, r.transcript.size)
        assertEquals("from the extension", (r.transcript[0] as UserMessage).text)
    }

    @Test
    fun `a skill message pi expanded draws the card of the text pi queued`() {
        val r = reducer()
        // The user typed `/skill:demo`; pi expanded it on the way in and that is the
        // message it delivers (`_expandSkillCommand`, `agent-session.ts:2140-2147`),
        // so the row is the card — the app has no raw invocation of its own to show.
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"<skill name=\"demo\" location=\"/skills/demo/SKILL.md\">\nReferences are relative to /skills/demo.\n\nthe body\n</skill>"}]}}""",
            ),
        )
        assertEquals(1, r.transcript.size)
        assertTrue(r.transcript[0] is SkillInvocation)
        assertEquals("demo", (r.transcript[0] as SkillInvocation).skillName)
    }

    @Test
    fun `a message delivered behind tool rows is appended in delivery order`() {
        val r = reducer()
        // A queued steer lands mid-turn, after the tool batch it waited for — so its
        // row is appended *after* the tool card, which is where pi's TUI adds it
        // too (`message_start` -> `addMessageToChat`).
        r.onEvent(toolStart("t1"))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"user","content":[{"type":"text","text":"<skill name=\"demo\" location=\"/s/SKILL.md\">\nReferences are relative to /s.\n\nbody\n</skill>"}]}}""",
            ),
        )
        assertEquals(2, r.transcript.size)
        assertTrue(r.transcript[0] is ToolCall)
        assertTrue(r.transcript[1] is SkillInvocation)
    }

    @Test
    fun `a rebuilt stream renders the replayed row and a later delivery is its own row`() {
        val r = reducer()
        r.reset()
        r.onEntry(obj("""{"type":"message","id":"e1","message":{"role":"user","content":[{"type":"text","text":"replayed"}]}}"""))
        // The persisted entry is the row; a live delivery of the same text after a
        // replay is a second one, exactly as it is in pi (its TUI renders the
        // replayed entries and then every `message_start` it sees).
        r.onEvent(userEnd("replayed"))
        assertEquals(2, r.transcript.size)
        assertEquals("replayed", (r.transcript[1] as UserMessage).text)
    }
}
