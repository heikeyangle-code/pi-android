package app.pi.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `a codemode update with no text still lands its call snapshot`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"codemode","args":{"code":"1"}}"""))
        // pi 的 codemode **一个字的正文都不送**：每个嵌套调用的进度都走
        // `{content: [], details: {calls: […]}}`（`dist/extensions/codemode/execute.js:395`），
        // 而 `contentText` 对空数组答 `null`（`Events.kt:767-785`）。所以"没有文本就丢掉这次
        // 更新"会让运行中的 codemode 卡永远看不到调用清单 —— 这条钉的就是那一句。
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_update","toolCallId":"tc1","partialResult":{"content":[],"details":{"calls":[{"name":"read","args":"{}","status":"running"}]}}}""",
            ),
        )
        val card = r.transcript.single() as ToolCall
        assertTrue(card.liveDetails != null)
        assertTrue(card.liveDetails.toString().contains("read"))
        assertTrue(card.liveDetails.toString().contains("running"))
        // 这次更新没带文本，所以 `output` 保持原样（这里本来就是空）。
        assertEquals("", card.output)
    }

    @Test
    fun `the settled result releases the live snapshot`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"codemode","args":{"code":"1"}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_update","toolCallId":"tc1","partialResult":{"content":[],"details":{"calls":[{"name":"read","status":"running"}]}}}""",
            ),
        )
        assertTrue((r.transcript.single() as ToolCall).liveDetails != null)
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_end","toolCallId":"tc1","toolName":"codemode","isError":false,"result":{"content":[{"type":"text","text":"done"}],"details":{"calls":[{"name":"read","status":"ok"}]}}}""",
            ),
        )
        val done = r.transcript.single() as ToolCall
        // 结果自己的 `details` 一到，快照立刻放掉：卡片从此读 `details`，再留着就是纯占内存。
        assertNull(done.liveDetails)
        assertTrue(done.details != null)
    }

    @Test
    fun `an update carrying neither text nor details is dropped`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"bash","args":{"command":"x"}}"""))
        // bash 的第一次空更新就是这个形状（`core/tools/bash.js:216`）：既没有正文也没有
        // details，不该被当成一次发布。
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_update","toolCallId":"tc1","partialResult":{"content":[],"details":null}}"""))
        val card = r.transcript.single() as ToolCall
        assertNull(card.liveDetails)
        assertEquals("", card.output)
    }

    @Test
    fun `a details-only update leaves the streamed output alone`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"tc1","toolName":"bash","args":{"command":"x"}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_update","toolCallId":"tc1","partialResult":{"content":[{"type":"text","text":"half"}],"details":{"truncation":{"truncated":true}}}}""",
            ),
        )
        assertEquals("half", (r.transcript.single() as ToolCall).output)
        clock += 1_000
        // 一次只带 details 的更新：不能把已经流出来的正文清掉（累加器只为带文本的更新建）。
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_update","toolCallId":"tc1","partialResult":{"content":[],"details":{"fullOutputPath":"/tmp/x"}}}"""))
        val card = r.transcript.single() as ToolCall
        assertEquals("half", card.output)
        assertTrue(card.liveDetails.toString().contains("/tmp/x"))
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
        // anchored on the real form (`protocol/.../SkillBlock.kt`).
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

    // ---------------------------------------------------------------- 工具耗时（pi 1.1.0）

    @Test
    fun `pi 1_1_0's recorded duration wins over the wall clock`() {
        // pi 的 TUI 也是这个口径（`core/tools/renderers/bash.ts:99-110`：记录值优先，
        // "it is monotonic and survives reloads"）。墙钟差被拉到一个数量级，
        // 否则"用了记录值"和"用了墙钟"看着一样。
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"sleep 60"}}"""))
        clock += 60_000
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","isError":false,"result":{"content":[{"type":"text","text":"ok"}]},"durationMs":1200}"""))
        val card = r.transcript.single() as ToolCall
        assertEquals(1200L, card.durationMs)
        assertEquals(1200L, card.elapsedMs)
    }

    @Test
    fun `a result without a recorded duration keeps the wall-clock fallback`() {
        // pi < 1.1.0 写的会话、或根本没跑的工具：行为与 1.0.3 时代逐字一致（墙钟）。
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{}}"""))
        clock += 60_000
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","isError":false,"result":{"content":[{"type":"text","text":"ok"}]}}"""))
        val card = r.transcript.single() as ToolCall
        assertNull(card.durationMs)
        assertEquals(60_000L, card.elapsedMs)
    }

    @Test
    fun `replay keeps the duration pi wrote into the session file`() {
        // `toolResult` 消息自己带 `durationMs`（pi-agent-core 的 `createToolResultMessage`），
        // 所以重开会话后读数不变 —— 上游修 "losing Took after reloading a session" 用的就是它。
        val r = reducer()
        r.onEntry(obj("""{"type":"message","id":"e1","message":{"role":"toolResult","toolCallId":"c1","toolName":"bash","content":[{"type":"text","text":"ok"}],"timestamp":1000,"durationMs":1200}}"""))
        val card = r.transcript.single() as ToolCall
        assertEquals(1200L, card.elapsedMs)
    }

    // --------------------------------------------------------- thinking streaming

    @Test
    fun `empty thinking_end content preserves streamed thinking deltas`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"agent_start"}"""))
        r.onEvent(thinking("step 1. "))
        r.onEvent(thinking("step 2."))
        // Some providers emit thinking_end with an empty string `""` instead of omitting content.
        // The reducer must keep the accumulated deltas and only close the streaming flag.
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":""}}"""))
        assertEquals(1, r.transcript.size)
        val block = r.transcript[0] as ThinkingBlock
        assertEquals("step 1. step 2.", block.text)
        assertFalse(block.streaming)
    }

    @Test
    fun `sequential thinking blocks with the same contentIndex do not overwrite each other`() {
        val r = reducer()
        r.onEvent(thinking("first thought"))
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"first thought"}}"""))
        r.onEvent(thinking("second thought"))
        assertEquals(2, r.transcript.size)
        val first = r.transcript[0] as ThinkingBlock
        val second = r.transcript[1] as ThinkingBlock
        assertEquals("first thought", first.text)
        assertFalse(first.streaming)
        assertEquals("second thought", second.text)
        assertTrue(second.streaming)
    }

    @Test
    fun `leading empty thinking_delta does not emit a blank thinking block`() {
        val r = reducer()
        r.onEvent(thinking(""))
        assertEquals(0, r.transcript.size)
        r.onEvent(thinking("actual thought"))
        assertEquals(1, r.transcript.size)
        assertEquals("actual thought", (r.transcript[0] as ThinkingBlock).text)
    }

    @Test
    fun `message delta throttle publishes first token immediately and coalesces within window`() {
        val r = TranscriptReducer(messageDeltaThrottleMs = MESSAGE_DELTA_THROTTLE_MS) { clock }
        r.onEvent(PiEvents.parse("""{"type":"agent_start"}"""))

        val first = r.onEvent(text("He"))
        assertEquals(TranscriptChange.Appended(0), first)
        assertEquals("He", (r.transcript[0] as AssistantText).text)
        assertFalse(r.hasPendingMessageDeltas())

        clock += 10L
        assertEquals(TranscriptChange.None, r.onEvent(text("llo")))
        clock += 10L
        assertEquals(TranscriptChange.None, r.onEvent(text(", ")))
        // Still holding the last published snapshot until window elapses or flush runs.
        assertEquals("He", (r.transcript[0] as AssistantText).text)
        assertTrue(r.hasPendingMessageDeltas())

        // Crossing 33 ms materialises all coalesced deltas in one publish.
        clock += 20L
        assertEquals(TranscriptChange.Updated(0), r.onEvent(text("world")))
        assertEquals("Hello, world", (r.transcript[0] as AssistantText).text)
        assertFalse(r.hasPendingMessageDeltas())
    }

    @Test
    fun `flushPendingMessageDeltas materialises throttled tail without losing characters`() {
        val r = TranscriptReducer(messageDeltaThrottleMs = MESSAGE_DELTA_THROTTLE_MS) { clock }
        r.onEvent(text("A"))
        clock += 5L
        assertEquals(TranscriptChange.None, r.onEvent(text("B")))
        clock += 5L
        assertEquals(TranscriptChange.None, r.onEvent(text("C")))
        assertTrue(r.hasPendingMessageDeltas())

        clock += 30L
        assertEquals(TranscriptChange.Updated(0), r.flushPendingMessageDeltas())
        assertEquals("ABC", (r.transcript[0] as AssistantText).text)
        assertFalse(r.hasPendingMessageDeltas())
        assertEquals(TranscriptChange.None, r.flushPendingMessageDeltas())
    }

    @Test
    fun `throttled thinking deltas survive empty thinking_end and flush cleanly`() {
        val r = TranscriptReducer(messageDeltaThrottleMs = MESSAGE_DELTA_THROTTLE_MS) { clock }
        r.onEvent(thinking("step 1"))
        clock += 5L
        assertEquals(TranscriptChange.None, r.onEvent(thinking(" -> step 2")))
        assertTrue(r.hasPendingMessageDeltas())

        clock += 5L
        val endChange = r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":""}}"""),
        )
        assertEquals(TranscriptChange.Updated(0), endChange)
        val block = r.transcript[0] as ThinkingBlock
        assertEquals("step 1 -> step 2", block.text)
        assertFalse(block.streaming)
        assertFalse(r.hasPendingMessageDeltas())
    }

    @Test
    fun `throttled text deltas flush before tool call start and on message_end`() {
        val r = TranscriptReducer(messageDeltaThrottleMs = MESSAGE_DELTA_THROTTLE_MS) { clock }
        r.onEvent(text("Let me check"))
        clock += 5L
        assertEquals(TranscriptChange.None, r.onEvent(text(" that file.")))
        assertTrue(r.hasPendingMessageDeltas())

        // Tool call start must flush the preceding assistant text immediately.
        clock += 5L
        r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"call_1","name":"read"}}"""),
        )
        assertEquals("Let me check that file.", (r.transcript[0] as AssistantText).text)
        assertFalse(r.hasPendingMessageDeltas())

        // Subsequent block ending directly via message_end (without text_end) also flushes.
        clock += 100L
        r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":2,"delta":"Done"}}"""),
        )
        clock += 5L
        assertEquals(
            TranscriptChange.None,
            r.onEvent(
                PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":2,"delta":"!"}}"""),
            ),
        )
        assertTrue(r.hasPendingMessageDeltas())

        clock += 5L
        r.onEvent(PiEvents.parse("""{"type":"message_end","message":{"role":"assistant"}}"""))
        val second = r.transcript[2] as AssistantText
        assertEquals("Done!", second.text)
        assertFalse(second.streaming)
        assertFalse(r.hasPendingMessageDeltas())
    }

    @Test
    fun `thinking settles when text_start arrives before late openai-completions thinking_end`() {
        val r = TranscriptReducer(messageDeltaThrottleMs = MESSAGE_DELTA_THROTTLE_MS) { clock }
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_start","contentIndex":0}}"""))
        clock += 1_500L
        r.onEvent(thinking("Evaluating options"))
        clock += 2_500L
        r.onEvent(thinking(" -> plan ready"))

        // openai-completions.js opens text without emitting thinking_end until EOF.
        clock += 200L
        val settleChange = r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":1}}"""),
        )
        assertEquals(TranscriptChange.Updated(0), settleChange)
        val settled = r.transcript[0] as ThinkingBlock
        assertFalse(settled.streaming)
        assertEquals("Evaluating options -> plan ready", settled.text)
        assertEquals(4_200L, settled.elapsedMs)

        // Answer streams for 20 seconds; thinking block must stay settled at 4.2s.
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Final answer."}}"""))
        clock += 20_000L

        // Late thinking_end flushed at EOF must NOT duplicate the row or overwrite elapsedMs.
        val lateEnd = r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"Evaluating options -> plan ready"}}"""),
        )
        assertEquals(TranscriptChange.None, lateEnd)
        assertEquals(2, r.transcript.size)
        val finalThinking = r.transcript[0] as ThinkingBlock
        assertFalse(finalThinking.streaming)
        assertEquals(4_200L, finalThinking.elapsedMs)
    }

    @Test
    fun `thinking settles immediately on text_delta or toolcall_start even without text_start`() {
        val r = TranscriptReducer { clock }
        r.onEvent(thinking("checking files"))
        clock += 3_100L
        r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"tc_1","toolName":"read"}}"""),
        )
        val settled = r.transcript[0] as ThinkingBlock
        assertFalse(settled.streaming)
        assertEquals(3_100L, settled.elapsedMs)

        // Delayed thinking_end reconciles authoritative text in place without overwriting duration.
        clock += 5_000L
        val lateEnd = r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"checking files!"}}"""),
        )
        assertEquals(TranscriptChange.Updated(0), lateEnd)
        assertEquals(2, r.transcript.size)
        val updated = r.transcript[0] as ThinkingBlock
        assertEquals("checking files!", updated.text)
        assertEquals(3_100L, updated.elapsedMs)
    }

    @Test
    fun `delta-less thinking_start to thinking_end records real elapsed time but omits 0ms instant blocks`() {
        val r = TranscriptReducer { clock }
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_start","contentIndex":0}}"""))
        clock += 6_400L
        r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"Summary reasoning"}}"""),
        )
        val measured = r.transcript[0] as ThinkingBlock
        assertFalse(measured.streaming)
        assertEquals(6_400L, measured.elapsedMs)

        // Instant redacted thinking (0ms between start and end) keeps elapsedMs null.
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_start","contentIndex":1}}"""))
        r.onEvent(
            PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":1,"content":"[Reasoning redacted]"}}"""),
        )
        val redacted = r.transcript[1] as ThinkingBlock
        assertFalse(redacted.streaming)
        assertEquals(null, redacted.elapsedMs)
    }

    @Test
    fun `interleaved thinking_delta after implicit settlement reopens same row cleanly`() {
        val r = TranscriptReducer { clock }
        r.onEvent(thinking("part 1"))
        clock += 1_000L
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Hi"}}"""))
        assertFalse((r.transcript[0] as ThinkingBlock).streaming)

        clock += 500L
        r.onEvent(thinking(" + part 2"))
        val reopened = r.transcript[0] as ThinkingBlock
        assertTrue(reopened.streaming)
        assertEquals("part 1 + part 2", reopened.text)
        assertEquals(2, r.transcript.size)

        clock += 500L
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"part 1 + part 2"}}"""))
        val done = r.transcript[0] as ThinkingBlock
        assertFalse(done.streaming)
        assertEquals(2_000L, done.elapsedMs)
    }

    @Test
    fun `appendExtensionNotice follows official Pi consecutive info replace and warning error append`() {
        val r = TranscriptReducer { clock }
        val c1 = r.appendExtensionNotice("Loading 1/2", Notice.Tone.Info)
        assertTrue(c1 is TranscriptChange.Appended)
        assertEquals(1, r.transcript.size)
        val firstKey = r.transcript[0].key

        // Consecutive trailing Info replaces in-place (official showStatus anti-spam)
        clock += 500L
        val c2 = r.appendExtensionNotice("Loading 2/2", Notice.Tone.Info)
        assertTrue(c2 is TranscriptChange.Updated)
        assertEquals(1, r.transcript.size)
        val updated = r.transcript[0] as Notice
        assertEquals(firstKey, updated.key)
        assertEquals("Loading 2/2", updated.text)

        // Warning always appends a new row and breaks the trailing Info slot
        clock += 500L
        val c3 = r.appendExtensionNotice("Careful", Notice.Tone.Warning)
        assertTrue(c3 is TranscriptChange.Appended)
        assertEquals(2, r.transcript.size)
        assertEquals(Notice.Tone.Warning, (r.transcript[1] as Notice).tone)

        // Error always appends
        clock += 500L
        val c4 = r.appendExtensionNotice("Failed", Notice.Tone.Error)
        assertTrue(c4 is TranscriptChange.Appended)
        assertEquals(3, r.transcript.size)

        // Next Info after non-Info appends a fresh row
        clock += 500L
        val c5 = r.appendExtensionNotice("Done", Notice.Tone.Info)
        assertTrue(c5 is TranscriptChange.Appended)
        assertEquals(4, r.transcript.size)

        // Blank notice is ignored
        assertEquals(TranscriptChange.None, r.appendExtensionNotice("   ", Notice.Tone.Info))
        assertEquals(4, r.transcript.size)
    }

    @Test
    fun `parseUsage and turnUsage preserve Pi 1_7_0 imageOutput token count`() {
        val r = TranscriptReducer { clock }
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"img 1"}],"usage":{"input":100,"output":20,"imageOutput":512,"totalTokens":632,"cost":0.02}}}""",
            ),
        )
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"img 2"}],"usage":{"input":50,"output":10,"imageOutput":256,"totalTokens":316,"cost":0.01}}}""",
            ),
        )
        assertEquals(256L, r.lastUsage?.imageOutput)
        assertEquals(768L, r.turnUsage?.imageOutput)
    }
}
