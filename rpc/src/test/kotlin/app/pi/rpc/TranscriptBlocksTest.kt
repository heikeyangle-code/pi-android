package app.pi.rpc

import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the block kinds the transcript gained beyond the original five:
 * diff, compaction, branch summary, hook message, model change, skill, system
 * prompt, error and date separator — plus the history projection that rebuilds
 * the same stream when a session is reopened
 * (docs/pi-android-ui-spec.md §4.2 / §7.4).
 */
class TranscriptBlocksTest {

    private var clock = 1_000L
    private fun reducer() = TranscriptReducer { clock }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun text(s: String) =
        PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"$s"}}""")

    private fun thinking(s: String) =
        PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"$s"}}""")

    private val editDiff = listOf(
        "diff --git a/src/auth.ts b/src/auth.ts",
        "--- a/src/auth.ts",
        "+++ b/src/auth.ts",
        "@@ -1,2 +1,2 @@",
        "-const secret = \"hardcoded\"",
        "+const secret = env.AUTH_SECRET",
        " export function auth() {}",
    ).joinToString("\n")

    // ------------------------------------------------------------- model change

    @Test
    fun `a model change entry becomes a model change block`() {
        val r = reducer()
        r.onEntry(
            obj(
                """{"type":"model_change","id":"m1","timestamp":1000,""" +
                    """"provider":"deepseek","modelId":"deepseek-chat"}""",
            ),
        )
        val item = r.transcript.single() as ModelChange
        assertEquals("m1", item.key)
        assertEquals("deepseek", item.provider)
        assertEquals("deepseek-chat", item.modelId)
    }

    @Test
    fun `model_select is extension-only, so a record with that type is inert`() {
        // `_emitModelSelect` calls `_extensionRunner.emit(...)`, never
        // `_emit`/`subscribe` (agent-session.ts), so it is not on RPC stdout;
        // and the persisted entry union has no `model_select`
        // (session-manager.ts). The live signal is the `get_state` poll after
        // `agent_settled` in PiSessionViewModel.
        //
        // This is also why the name is in the reducer's `NON_WIRE_EVENT_TYPES`
        // set: an unknown *event* is now surfaced as a notice (F25), and for this
        // name that notice would be false — no app upgrade can make pi emit it.
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"model_select","provider":"anthropic","modelId":"claude-sonnet-4.5"}"""))
        r.onEvent(PiEvents.parse("""{"type":"model_select","model":{"provider":"openai","id":"gpt-5"}}"""))
        assertTrue(r.transcript.isEmpty())
    }

    @Test
    fun `a model change with no model at all is ignored`() {
        val r = reducer()
        r.onEntry(obj("""{"type":"model_change","id":"m1","timestamp":1000}"""))
        assertTrue(r.transcript.isEmpty())
    }

    // ------------------------------------------------------------ hook messages

    @Test
    fun `a custom message becomes a hook message with its type`() {
        val r = reducer()
        r.onEntry(
            obj(
                """{"type":"custom_message","id":"h1","timestamp":1000,""" +
                    """"customType":"todo","content":"- [ ] ship it"}""",
            ),
        )
        val item = r.transcript.single() as HookMessage
        assertEquals("todo", item.customType)
        assertEquals("- [ ] ship it", item.markdown)
    }

    @Test
    fun `a hidden custom message never reaches the transcript`() {
        val r = reducer()
        r.onEntry(
            obj(
                """{"type":"custom_message","id":"h1","timestamp":1000,""" +
                    """"customType":"state","content":"x","display":false}""",
            ),
        )
        assertTrue(r.transcript.isEmpty())
    }

    // ---------------------------------------------------- extension state (F6)

    /**
     * pi renders a `custom` entry through the extension's registered renderer
     * (`interactive-mode.ts:3202-3207` live, `:3703-3707` on replay). That
     * renderer cannot cross RPC, so the reducer must at least surface the type
     * and the payload as a row — it used to emit nothing (F6).
     */
    @Test
    fun `an extension state entry becomes a muted row carrying its type and data`() {
        val r = reducer()
        r.onEntry(
            obj("""{"type":"custom","id":"c1","timestamp":1000,""" +
                """"customType":"todo","data":{"n":1}}"""),
        )
        val item = r.transcript.single() as Notice
        assertEquals(Notice.Tone.Info, item.tone)
        assertTrue(item.text.contains("todo"))
        assertTrue(item.text.contains("""{"n":1}"""))
    }

    @Test
    fun `an extension state row keeps pi's entry id as its key and survives a re-seed`() {
        val prompt = obj(
            """{"type":"message","id":"u1","timestamp":1000,""" +
                """"message":{"role":"user","content":"hi"}}""",
        )
        val entry = obj("""{"type":"custom","id":"c1","timestamp":1000,"customType":"state"}""")
        val live = reducer()
        live.onEntry(entry)
        val replayed = reducer().also { it.seedFromHistory(listOf(prompt, entry)) }
        assertEquals("c1", live.transcript.single().key)
        assertEquals("c1", replayed.transcript.filterIsInstance<Notice>().single().key)
    }

    /** A `custom` entry is not context, so it must not become the hook card. */
    @Test
    fun `an extension state entry is not rendered as a hook message`() {
        val r = reducer()
        r.onEntry(
            obj("""{"type":"custom","id":"c1","timestamp":1000,"customType":"todo","data":"ship it"}"""),
        )
        assertTrue(r.transcript.none { it is HookMessage })
        assertTrue(r.transcript.single() is Notice)
    }

    @Test
    fun `a malformed extension state entry still produces a labelled row`() {
        val r = reducer()
        r.onEntry(obj("""{"type":"custom","id":"c1","timestamp":1000}"""))
        assertTrue((r.transcript.single() as Notice).text.contains("extension"))
    }

    @Test
    fun `a long extension payload is truncated to one bounded line`() {
        val r = reducer()
        r.onEntry(
            obj("""{"type":"custom","id":"c1","timestamp":1000,"customType":"big","data":"${"x".repeat(500)}"}"""),
        )
        val text = (r.transcript.single() as Notice).text
        assertTrue(text.length < 260)
        assertTrue(text.endsWith("…"))
    }

    /** `CustomEntry` has no `display` in pi; a future one is honoured, not ignored. */
    @Test
    fun `an extension state entry marked hidden stays out of the transcript`() {
        val r = reducer()
        r.onEntry(
            obj("""{"type":"custom","id":"c1","timestamp":1000,"customType":"s","display":false}"""),
        )
        assertTrue(r.transcript.isEmpty())
    }

    // ----------------------------------------------------------- branch summary

    @Test
    fun `a branch summary entry becomes a branch summary block`() {
        val r = reducer()
        r.onEntry(
            obj(
                """{"type":"branch_summary","id":"b1","timestamp":1000,""" +
                    """"summary":"tried the other approach","fromId":"e9"}""",
            ),
        )
        val item = r.transcript.single() as BranchSummary
        assertEquals("tried the other approach", item.summary)
        assertEquals("e9", item.branchId)
    }

    // -------------------------------------------------------------- compaction

    @Test
    fun `a compaction entry carries the summary and the freed tokens`() {
        val r = reducer()
        r.onEntry(
            obj(
                """{"type":"compaction","id":"c1","timestamp":1000,""" +
                    """"summary":"older work summarized","tokensFreed":42000,""" +
                    """"firstKeptEntryId":"k1"}""",
            ),
        )
        val item = r.transcript.single() as CompactionMarker
        assertEquals(CompactionMarker.Status.Done, item.status)
        assertEquals("older work summarized", item.summary)
        assertEquals(42000L, item.tokensFreed ?: 0L)
        assertEquals("k1", item.firstKeptEntryId)
    }

    @Test
    fun `live compaction start and end update a single marker`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_start","reason":"threshold"}"""))
        val running = r.transcript.single() as CompactionMarker
        assertEquals(CompactionMarker.Status.Running, running.status)
        assertEquals("threshold", running.reason)

        clock += 5_000
        val change = r.onEvent(PiEvents.parse("""{"type":"compaction_end","aborted":false,"willRetry":false}"""))
        assertEquals(1, r.transcript.size)
        val done = r.transcript.single() as CompactionMarker
        assertEquals(CompactionMarker.Status.Done, done.status)
        assertEquals(1_000L, done.ts)
        assertTrue(change is TranscriptChange.Updated)
    }

    @Test
    fun `a compaction end without a start still produces a marker`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_end","aborted":false,"willRetry":false}"""))
        assertEquals(CompactionMarker.Status.Done, (r.transcript.single() as CompactionMarker).status)
    }

    @Test
    fun `a failed compaction keeps pi's error message`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_start","reason":"overflow"}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"compaction_end","aborted":false,"willRetry":false,""" +
                    """"errorMessage":"context too long"}""",
            ),
        )
        val item = r.transcript.single() as CompactionMarker
        assertEquals(CompactionMarker.Status.Failed, item.status)
        assertEquals("context too long", item.errorMessage)
    }

    @Test
    fun `an aborted compaction is not reported as success`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_start","reason":"manual"}"""))
        r.onEvent(PiEvents.parse("""{"type":"compaction_end","aborted":true,"willRetry":false}"""))
        assertEquals(CompactionMarker.Status.Aborted, (r.transcript.single() as CompactionMarker).status)
    }

    // ------------------------------------------------- system prompt and skills

    @Test
    fun `a system prompt row comes from the app helper, not from an entry type`() {
        // pi's persisted union has no `system_prompt` entry (session-manager.ts);
        // the text is only reachable through `getSystemPrompt()`. An entry-shaped
        // record is therefore inert, and onSystemPrompt is the app-only API that
        // produces the row (F22: it has no producer — see that method's KDoc).
        val r = reducer()
        r.onEntry(obj("""{"type":"system_prompt","id":"sp","timestamp":1000,"text":"You are pi."}"""))
        assertTrue(r.transcript.isEmpty())

        r.onSystemPrompt("You are pi.")
        assertEquals("You are pi.", (r.transcript.single() as SystemPrompt).fullText)
    }

    /**
     * F26: pi has no `skill` or `skill_invocation` entry type — `_expandSkillCommand`
     * rewrites the user message (`core/agent-session.ts`) — so a record with that
     * name must stay inert rather than pretend to be wire data. The real skill
     * card comes from the `<skill …>` split of a user message; see
     * `FidelityFixesTest`'s `a skill user message projects a skill card plus the
     * trailing message`.
     */
    @Test
    fun `skill-shaped entry records are inert, since pi has no such entry type`() {
        val r = reducer()
        r.onEntry(
            obj("""{"type":"skill","id":"s1","timestamp":1000,"name":"pdf","body":"step one\nstep two"}"""),
        )
        r.onEntry(
            obj("""{"type":"skill_invocation","id":"s2","timestamp":1000,"name":"pdf","body":"body"}"""),
        )
        assertTrue(r.transcript.isEmpty())
    }

    // -------------------------------------------------------------------- error

    @Test
    fun `an error-shaped entry record is inert, since pi has no such entry type`() {
        // pi has no `error` entry type: a failure is a `stopReason`/delta event
        // (session-manager.ts), and the row comes from `failTurn` / a provider
        // error delta (F26 removed the unused app-side helper).
        val r = reducer()
        r.onEntry(
            obj(
                """{"type":"error","id":"e1","timestamp":1000,"message":"provider exploded",""" +
                    """"content":[{"type":"text","text":"stack trace"}]}""",
            ),
        )
        assertTrue(r.transcript.isEmpty())
    }

    @Test
    fun `a provider error delta becomes an error block, not a notice`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","assistantMessageEvent":{"type":"error","reason":"429"}}""",
            ),
        )
        val item = r.transcript.single() as ErrorText
        assertEquals("模型调用失败", item.message)
        assertEquals("429", item.detail)
    }

    @Test
    fun `unknown entry types are inert`() {
        val r = reducer()
        r.onEntry(obj("""{"type":"label","id":"l1","timestamp":1000,"label":"wip"}"""))
        r.onEntry(obj("""{"type":"totally_new","id":"z1","timestamp":1000}"""))
        assertTrue(r.transcript.isEmpty())
        assertFalse(r.streaming)
    }

    // ----------------------------------------------------------------- thinking

    @Test
    fun `a thinking level entry stamps later thinking blocks`() {
        val r = reducer()
        r.onEntry(obj("""{"type":"thinking_level_change","id":"t1","timestamp":1000,"thinkingLevel":"high"}"""))
        assertEquals("high", r.thinkingLevel)
        r.onEvent(thinking("hmm"))
        assertEquals("high", (r.transcript.single() as ThinkingBlock).level)
    }

    @Test
    fun `message end records how long thinking took`() {
        val r = reducer()
        r.onEvent(thinking("hmm"))
        clock = 13_000L
        r.onEvent(PiEvents.parse("""{"type":"message_end","message":{"role":"assistant"}}"""))
        val item = r.transcript.single() as ThinkingBlock
        assertFalse(item.streaming)
        assertEquals(12_000L, item.elapsedMs ?: 0L)
    }

    // ---------------------------------------------------------------- tool diff

    @Test
    fun `a tool end carrying a diff appends a parsed diff block`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_start","toolCallId":"tc1","toolName":"edit",""" +
                    """"args":{"path":"src/auth.ts"}}""",
            ),
        )
        val change = r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_end","toolCallId":"tc1","toolName":"edit","isError":false,""" +
                    """"result":{"content":[{"type":"text","text":"applied"}],""" +
                    """"details":{"diff":${jsonString(editDiff)},"path":"src/auth.ts"}}}""",
            ),
        )
        assertEquals(2, r.transcript.size)
        val card = r.transcript[0] as ToolCall
        assertEquals(ToolStatus.Success, card.status)
        assertEquals("applied", card.output)
        assertTrue(card.diffText != null)

        val diff = r.transcript[1] as ToolDiff
        assertEquals("tc1-diff", diff.key)
        assertEquals("src/auth.ts", diff.path)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals("tc1", diff.toolCallId)
        // One file-header hunk plus the @@ body.
        assertEquals(2, diff.hunks.size)
        assertTrue(change is TranscriptChange.Appended)
        assertEquals(1, (change as TranscriptChange.Appended).index)
    }

    @Test
    fun `a repeated tool end updates the diff instead of duplicating it`() {
        val r = reducer()
        val end = """{"type":"tool_execution_end","toolCallId":"tc1","toolName":"edit","isError":false,""" +
            """"result":{"content":[{"type":"text","text":"applied"}],""" +
            """"details":{"diff":${jsonString(editDiff)},"path":"src/auth.ts"}}}"""
        r.onEvent(PiEvents.parse(end))
        r.onEvent(PiEvents.parse(end))
        assertEquals(2, r.transcript.size)
        assertEquals(1, r.transcript.filterIsInstance<ToolDiff>().size)
    }

    @Test
    fun `a tool without a diff stays a single card`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_end","toolCallId":"t9","toolName":"read","isError":false,""" +
                    """"result":{"content":[{"type":"text","text":"body"}]}}""",
            ),
        )
        assertEquals(1, r.transcript.size)
        assertTrue(r.transcript[0] is ToolCall)
    }

    @Test
    fun `the args summary prefers the meaningful argument`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash",""" +
                    """"args":{"command":"npm test","timeout":30}}""",
            ),
        )
        assertEquals("npm test", (r.transcript.single() as ToolCall).argsSummary)
    }

    @Test
    fun `a tool call with no arguments summarises to an empty string`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"tool_execution_start","toolCallId":"t1","toolName":"ls"}"""))
        assertEquals("", (r.transcript.single() as ToolCall).argsSummary)
    }

    // ------------------------------------------------------------ date boundary

    @Test
    fun `a day separator appears only when the day actually changes`() {
        val r = reducer()
        r.onUserPrompt("first")
        assertEquals(1, r.transcript.size)
        clock += 2 * 24 * 60 * 60 * 1000L
        r.onUserPrompt("second")
        assertEquals(3, r.transcript.size)
        assertTrue(r.transcript[1] is DateSeparator)
        assertEquals("今天", (r.transcript[1] as DateSeparator).label)
    }

    @Test
    fun `turn end across midnight inserts a date separator`() {
        val r = reducer()
        r.onUserPrompt("first")
        clock += 2 * 24 * 60 * 60 * 1000L
        val change = r.onEvent(PiEvents.parse("""{"type":"turn_end","turnIndex":0}"""))
        assertEquals(2, r.transcript.size)
        assertTrue(r.transcript[1] is DateSeparator)
        assertTrue(change is TranscriptChange.Appended)
    }

    @Test
    fun `day labels use pi's own vocabulary`() {
        val now = OffsetDateTime.parse("2025-06-15T12:00:00Z").toInstant().toEpochMilli()
        assertEquals("今天", dayLabel(now, now))
        assertEquals("昨天", dayLabel(now - 24L * 60 * 60 * 1000, now))
        assertTrue(dayLabel(now - 40L * 24 * 60 * 60 * 1000, now).contains("月"))
        assertTrue(dayLabel(now - 400L * 24 * 60 * 60 * 1000, now).contains("2024"))
    }

    // -------------------------------------------------------- history seeding

    @Test
    fun `seedFromHistory projects user, assistant and tool result entries`() {
        val r = reducer()
        val change = r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"u1","timestamp":"2025-06-15T12:00:00Z",""" +
                        """"message":{"role":"user","content":"重构 auth 中间件"}}""",
                ),
                obj(
                    """{"type":"message","id":"a1","timestamp":"2025-06-15T12:00:01Z",""" +
                        """"message":{"role":"assistant","content":[""" +
                        """{"type":"thinking","thinking":"先看看现有实现"},{"type":"text","text":"我先看一下。"},""" +
                        """{"type":"toolCall","id":"c1","name":"read","arguments":{"path":"src/auth.ts"}}]}}""",
                ),
                obj(
                    """{"type":"message","id":"r1","timestamp":"2025-06-15T12:00:02Z",""" +
                        """"message":{"role":"toolResult","toolCallId":"c1",""" +
                        """"content":[{"type":"text","text":"export function auth() {}"}]}}""",
                ),
            ),
        )
        assertEquals(5, r.transcript.size)
        assertTrue(r.transcript[0] is DateSeparator)
        assertEquals("重构 auth 中间件", (r.transcript[1] as UserMessage).text)
        assertEquals("先看看现有实现", (r.transcript[2] as ThinkingBlock).text)
        assertEquals("我先看一下。", (r.transcript[3] as AssistantText).text)
        val card = r.transcript[4] as ToolCall
        assertEquals("read", card.toolName)
        assertEquals(ToolStatus.Success, card.status)
        assertEquals("export function auth() {}", card.output)
        assertFalse(r.streaming)
        assertTrue(change is TranscriptChange.Appended)
    }

    @Test
    fun `history keys come from pi's entry ids so a re-seed is stable`() {
        val entries = listOf(
            obj(
                """{"type":"message","id":"u1","timestamp":"2025-06-15T12:00:00Z",""" +
                    """"message":{"role":"user","content":"hi"}}""",
            ),
            obj(
                """{"type":"message","id":"a1","timestamp":"2025-06-15T12:00:01Z",""" +
                    """"message":{"role":"assistant","content":[{"type":"text","text":"one"},""" +
                    """{"type":"text","text":"two"}]}}""",
            ),
        )
        val r = reducer()
        r.seedFromHistory(entries)
        val keys = r.transcript.filter { it !is DateSeparator }.map { it.key }
        assertEquals(listOf("u1", "a1", "a1-1"), keys)
        val before = r.transcript.filter { it !is DateSeparator }.map { it.key }
        r.seedFromHistory(entries)
        assertEquals(before, r.transcript.filter { it !is DateSeparator }.map { it.key })
    }

    @Test
    fun `seedFromHistory inserts separators for each day it spans`() {
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"u1","timestamp":"2025-06-15T12:00:00Z",""" +
                        """"message":{"role":"user","content":"day one"}}""",
                ),
                obj(
                    """{"type":"message","id":"u2","timestamp":"2025-06-17T12:00:00Z",""" +
                        """"message":{"role":"user","content":"day three"}}""",
                ),
            ),
        )
        val separators = r.transcript.filterIsInstance<DateSeparator>()
        assertEquals(2, separators.size)
        assertEquals(4, r.transcript.size)
    }

    @Test
    fun `seeding an empty history clears the stream`() {
        val r = reducer()
        r.onUserPrompt("stale")
        assertTrue(r.seedFromHistory(emptyList()) == TranscriptChange.None)
        assertTrue(r.transcript.isEmpty())
    }

    @Test
    fun `seeding history carries tool diffs through details`() {
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"a1","timestamp":1000,"message":{"role":"assistant","content":[""" +
                        """{"type":"toolCall","id":"c1","name":"edit","arguments":{"path":"a.kt"}}]}}""",
                ),
                obj(
                    """{"type":"message","id":"r1","timestamp":1001,"message":{"role":"toolResult",""" +
                        """"toolCallId":"c1","content":[{"type":"text","text":"ok"}],""" +
                        """"details":{"diff":${jsonString(editDiff)},"path":"a.kt"}}}""",
                ),
            ),
        )
        val diff = r.transcript.filterIsInstance<ToolDiff>().single()
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
    }

    @Test
    fun `a user message with image blocks keeps its images`() {
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"u1","timestamp":1000,"message":{"role":"user","content":[""" +
                        """{"type":"text","text":"看看这个"},{"type":"image","data":"AAAA","mimeType":"image/png"}]}}""",
                ),
            ),
        )
        val item = r.transcript.filterIsInstance<UserMessage>().single()
        assertEquals("看看这个", item.text)
        assertEquals(1, item.images.size)
        assertEquals("image/png", item.images.single().mimeType)
    }

    @Test
    fun `a tool result with no preceding call still produces a card`() {
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"r1","timestamp":1000,"message":{"role":"toolResult",""" +
                        """"toolCallId":"orphan","content":[{"type":"text","text":"x"}]}}""",
                ),
            ),
        )
        val card = r.transcript.filterIsInstance<ToolCall>().single()
        assertEquals("orphan", card.toolCallId)
    }

    @Test
    fun `history seeding clears state left over from the previous session`() {
        val r = reducer()
        r.onUserPrompt("session one")
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"u1","timestamp":1000,"message":{"role":"user","content":"session two"}}""",
                ),
            ),
        )
        assertEquals(2, r.transcript.size)
        assertTrue(r.transcript[0] is DateSeparator)
        assertEquals("session two", (r.transcript[1] as UserMessage).text)
    }

    @Test
    fun `an unparsable timestamp falls back to the reducer clock`() {
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"u1","timestamp":"not a date","message":{"role":"user","content":"hi"}}""",
                ),
            ),
        )
        val user = r.transcript.filterIsInstance<UserMessage>().single()
        assertEquals(1_000L, user.ts)
    }

    @Test
    fun `an epoch timestamp is accepted as well as ISO`() {
        val r = reducer()
        r.onEntry(
            obj("""{"type":"message","id":"u1","timestamp":123456789,"message":{"role":"user","content":"hi"}}"""),
        )
        assertEquals(123456789L, (r.transcript.single() as UserMessage).ts)
    }

    @Test
    fun `reset drops the previous session including its separators`() {
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj("""{"type":"message","id":"u1","timestamp":1000,"message":{"role":"user","content":"hi"}}"""),
            ),
        )
        assertEquals(2, r.transcript.size)
        r.reset()
        assertTrue(r.transcript.isEmpty())
        r.onUserPrompt("fresh")
        assertEquals(1, r.transcript.size)
    }

    @Test
    fun `onEntry never throws on a malformed entry`() {
        val r = reducer()
        r.onEntry(obj("""{"type":"message"}"""))
        r.onEntry(obj("""{"type":"message","message":{}}"""))
        r.onEntry(obj("""{"type":"message","message":{"role":"assistant","content":{}}}"""))
        r.onEntry(obj("""{"type":"message","message":{"role":"toolResult"}}"""))
        r.onEntry(obj("""{"type":"compaction","content":5}"""))
        r.onEntry(obj("""{"type":"branch_summary"}"""))
        r.onEntry(obj("""{"type":"skill"}"""))
        r.onEntry(obj("""{"type":"system_prompt"}"""))
        r.onEntry(obj("""{"type":"error"}"""))
        assertTrue(r.transcript.none { it is UserMessage || it is AssistantText || it is ToolCall })
    }

    /** Wrap arbitrary text as a JSON string literal for embedding in a record. */
    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
