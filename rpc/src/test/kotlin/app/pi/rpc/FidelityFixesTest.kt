package app.pi.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fidelity fixes found by the adversarial review
 * (`docs/fidelity-review.md`), each against the pi source that justifies it.
 *
 * These are all cases where the wire carries something the projection used to
 * throw away, so the tests assert the *projected row*, not just the parse.
 */
class FidelityFixesTest {

    private var clock = 1_000L
    private fun reducer() = TranscriptReducer { clock }

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** A persisted `message` entry, built as data so no text needs escaping. */
    private fun messageEntry(role: String, content: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("id", "m1")
        put("parentId", null as String?)
        put("timestamp", "2024-12-03T14:00:01.000Z")
        put(
            "message",
            buildJsonObject {
                put("role", role)
                put("content", content)
                put("timestamp", 1000)
            },
        )
    }

    // ------------------------------------------------------------------- skills

    /**
     * pi's expansion, reproduced exactly as `_expandSkillCommand` writes it
     * (`core/agent-session.ts:1363-1376`): the wrapper, then "References are
     * relative to <baseDir>.", then the body, then the trailing arguments.
     */
    private val skillMessage = "<skill name=\"pdf\" location=\"/home/u/.pi/agent/skills/pdf/SKILL.md\">\n" +
        "References are relative to /home/u/.pi/agent/skills/pdf.\n" +
        "\n" +
        "# PDF skill\n" +
        "step one\n" +
        "step two\n" +
        "</skill>"

    @Test
    fun `parsePiSkillBlock matches pi's own regex`() {
        val parsed = parsePiSkillBlock(skillMessage)!!
        assertEquals("pdf", parsed.name)
        assertEquals("/home/u/.pi/agent/skills/pdf/SKILL.md", parsed.location)
        assertTrue(parsed.content.startsWith("References are relative to"))
        assertTrue(parsed.content.endsWith("step two"))
        assertNull(parsed.userMessage)

        val withArgs = parsePiSkillBlock("$skillMessage\n\nsummarise this pdf")
        assertEquals("summarise this pdf", withArgs!!.userMessage)
        // pi trims the trailing text (`match[4]?.trim()`).
        assertEquals(
            "summarise this pdf",
            parsePiSkillBlock("$skillMessage\n\n  summarise this pdf  ")!!.userMessage,
        )
    }

    @Test
    fun `parsePiSkillBlock rejects anything that is not a whole skill block`() {
        assertNull(parsePiSkillBlock("hello"))
        assertNull(parsePiSkillBlock(""))
        // pi's regex is anchored, so a user who pastes the wrapper into a larger
        // message keeps their text instead of getting it reinterpreted.
        assertNull(parsePiSkillBlock("look at this: $skillMessage"))
        assertNull(parsePiSkillBlock("$skillMessage and more"))
    }

    @Test
    fun `a skill user message projects a skill card plus the trailing message`() {
        // History replay is where this matters: pi persists the expanded text as
        // an ordinary user message and never writes a `skill` entry.
        val r = reducer()
        r.onEntry(messageEntry("user", "$skillMessage\n\nsummarise this pdf"))
        assertEquals(2, r.transcript.size)
        val skill = r.transcript[0] as SkillInvocation
        assertEquals("pdf", skill.skillName)
        assertTrue(skill.body.contains("step two"))
        assertEquals("summarise this pdf", (r.transcript[1] as UserMessage).text)
        // Distinct keys, so the list can animate each row separately.
        assertFalse(skill.key == r.transcript[1].key)
    }

    @Test
    fun `a skill message without trailing text projects only the card`() {
        val r = reducer()
        r.onEntry(messageEntry("user", skillMessage))
        assertEquals(1, r.transcript.size)
        assertEquals("pdf", (r.transcript.single() as SkillInvocation).skillName)
    }

    @Test
    fun `a plain user message is still one bubble and never a skill card`() {
        val r = reducer()
        r.onEntry(messageEntry("user", "hello"))
        assertEquals("hello", (r.transcript.single() as UserMessage).text)
        assertFalse(r.transcript.any { it is SkillInvocation })
    }

    // -------------------------------------------------------------- compaction

    @Test
    fun `compaction_end result fills the live marker`() {
        // pi emits `{ summary, firstKeptEntryId, tokensBefore,
        // estimatedTokensAfter, usage, details }` on `compaction_end`
        // (agent-session.ts:2082-2090, core/compaction/compaction.ts:88-97); the
        // `compaction` entry that also carries it is never announced live.
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_start","reason":"threshold"}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"compaction_end","reason":"threshold","aborted":false,"willRetry":false,
                    "result":{"summary":"older work summarized","firstKeptEntryId":"k1",
                              "tokensBefore":150000,"estimatedTokensAfter":32000,
                              "usage":{"input":32000,"output":1200,"totalTokens":33200},
                              "details":{}}}""".trimIndent().replace("\n", ""),
            ),
        )
        val marker = r.transcript.single() as CompactionMarker
        assertEquals(CompactionMarker.Status.Done, marker.status)
        assertEquals("older work summarized", marker.summary)
        assertEquals(150000L, marker.tokensFreed ?: 0L)
        assertEquals("k1", marker.firstKeptEntryId)
    }

    @Test
    fun `an aborted compaction keeps result null and stays aborted`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_start","reason":"manual"}"""))
        r.onEvent(PiEvents.parse("""{"type":"compaction_end","aborted":true,"result":null,"willRetry":false}"""))
        val marker = r.transcript.single() as CompactionMarker
        assertEquals(CompactionMarker.Status.Aborted, marker.status)
        assertEquals("", marker.summary)
    }

    @Test
    fun `the event parser exposes the compaction result`() {
        val event = PiEvents.parse(
            """{"type":"compaction_end","aborted":false,"willRetry":false,"result":{"summary":"s","tokensBefore":9}}""",
        ) as PiEvent.CompactionEnd
        assertEquals("s", event.result!!.summary)
        assertEquals(9L, event.result.tokensBefore)
    }

    // ------------------------------------------------------- summarization retry

    @Test
    fun `summarization retries surface as one notice and are closed`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"summarization_retry_scheduled","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"terminated"}""",
            ),
        )
        val notice = r.transcript.single() as Notice
        assertEquals(Notice.Tone.Warning, notice.tone)
        assertTrue(notice.text.contains("1/3"))
        assertTrue(notice.text.contains("2s"))
        assertTrue(notice.text.contains("terminated"))

        r.onEvent(
            PiEvents.parse(
                """{"type":"summarization_retry_attempt_start","source":"compaction","reason":"threshold"}""",
            ),
        )
        assertEquals(1, r.transcript.size)
        assertTrue((r.transcript.single() as Notice).text.contains("压缩摘要"))

        clock += 1_000
        val change = r.onEvent(PiEvents.parse("""{"type":"summarization_retry_finished"}"""))
        assertTrue(change is TranscriptChange.Updated)
        assertEquals(1, r.transcript.size)
        val closed = r.transcript.single() as Notice
        assertEquals(Notice.Tone.Info, closed.tone)
        assertTrue(closed.text.endsWith("重试结束"))
    }

    @Test
    fun `a second attempt updates the notice instead of stacking a warning`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"summarization_retry_scheduled","attempt":1,"maxAttempts":3,"delayMs":2000}"""))
        r.onEvent(PiEvents.parse("""{"type":"summarization_retry_scheduled","attempt":2,"maxAttempts":3,"delayMs":4000}"""))
        assertEquals(1, r.transcript.size)
        assertTrue((r.transcript.single() as Notice).text.contains("2/3"))
        assertTrue((r.transcript.single() as Notice).text.contains("4s"))
    }

    @Test
    fun `a branch summary retry names its source`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"summarization_retry_attempt_start","source":"branchSummary"}"""))
        assertEquals(1, r.transcript.size)
        assertTrue((r.transcript.single() as Notice).text.contains("分支摘要"))
    }

    // ------------------------------------------------------------ entry_appended

    @Test
    fun `entry_appended projects the entry payload from the wire`() {
        // agent-session.ts emits `{ type: "entry_appended"; entry: SessionEntry }`
        // and json-event.ts passes it through, so the payload is available live.
        val r = reducer()
        val change = r.onEvent(
            PiEvents.parse(
                """{"type":"entry_appended","entry":{"type":"custom_message","id":"e1","parentId":null,
                    "timestamp":"2024-12-03T14:25:00.000Z","customType":"my-ext",
                    "content":"Injected context...","display":true}}""".trimIndent().replace("\n", ""),
            ),
        )
        assertTrue(change is TranscriptChange.Appended)
        val hook = r.transcript.single() as HookMessage
        assertEquals("my-ext", hook.customType)
        assertEquals("Injected context...", hook.markdown)
    }

    @Test
    fun `entry_appended carries the entry object and degrades without one`() {
        val event = PiEvents.parse("""{"type":"entry_appended","entry":{"id":"e1","type":"message"}}""")
            as PiEvent.EntryAppended
        assertEquals("e1", event.entryId)
        assertEquals("message", event.entryType)
        assertEquals("e1", event.entry!!["id"].toString().trim('"'))

        // A malformed record must not throw; the reducer simply has nothing to do.
        val bare = PiEvents.parse("""{"type":"entry_appended"}""") as PiEvent.EntryAppended
        assertNull(bare.entry)
        assertEquals(TranscriptChange.None, reducer().onEvent(bare))
    }

    @Test
    fun `an entry_appended model change becomes a model change row`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"entry_appended","entry":{"type":"model_change","id":"m1","parentId":null,
                    "timestamp":"2024-12-03T14:05:00.000Z","provider":"openai","modelId":"gpt-4o"}}"""
                    .trimIndent().replace("\n", ""),
            ),
        )
        val change = r.transcript.single() as ModelChange
        assertEquals("openai", change.provider)
        assertEquals("gpt-4o", change.modelId)
    }

    // ------------------------------------------------------------------ deltas

    @Test
    fun `a done delta reads pi's reason field`() {
        // packages/ai/src/types.ts: `{ type: "done"; reason; message }`; the wire
        // never uses `stopReason` for this delta.
        val event = PiEvents.parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"done","reason":"toolUse"}}""",
        ) as PiEvent.MessageUpdate
        assertEquals("toolUse", (event.delta as AssistantDelta.Done).reason)

        val wrongKey = PiEvents.parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"done","stopReason":"toolUse"}}""",
        ) as PiEvent.MessageUpdate
        assertNull((wrongKey.delta as AssistantDelta.Done).reason)
    }

    @Test
    fun `an unknown assistant delta kind is surfaced instead of swallowed`() {
        // Events.kt documents Unknown as "rendered as a generic notice"; before
        // this it fell through `else -> None` and a newer pi's delta vanished.
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","assistantMessageEvent":{"type":"future_delta","contentIndex":0,"delta":"x"}}""",
            ),
        )
        val notice = r.transcript.single() as Notice
        assertTrue(notice.text.contains("future_delta"))

        // Deduplicated by kind, so a repeated delta cannot flood the stream.
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","assistantMessageEvent":{"type":"future_delta","contentIndex":0,"delta":"y"}}""",
            ),
        )
        assertEquals(1, r.transcript.size)
    }

    // ------------------------------------------------- live custom messages

    @Test
    fun `a live custom message becomes a hook message`() {
        // History replay already rendered `custom_message` entries; the live
        // chain needed `customType`/`display` on MessageEnd to do the same, so
        // injected context used to appear only after a reload.
        val r = reducer()
        val change = r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"custom","customType":"todo",
                    "content":"- [ ] ship it","display":true,"timestamp":1000}}""".trimIndent().replace("\n", ""),
            ),
        )
        assertTrue(change is TranscriptChange.Appended)
        val hook = r.transcript.single() as HookMessage
        assertEquals("todo", hook.customType)
        assertEquals("- [ ] ship it", hook.markdown)
    }

    @Test
    fun `a hidden or empty live custom message is not rendered`() {
        val r = reducer()
        // display:false means pi keeps it in context but hides it.
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"custom","customType":"state","content":"x","display":false}}""",
            ),
        )
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"custom","customType":"state","content":"","display":true}}""",
            ),
        )
        assertTrue(r.transcript.isEmpty())
    }

    @Test
    fun `an assistant message end still only closes streaming`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"hi"}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"hi"}],"stopReason":"stop"}}""",
            ),
        )
        assertEquals(1, r.transcript.size)
        assertFalse((r.transcript.single() as AssistantText).streaming)
    }

    // ------------------------------------------------------- extension errors

    // ------------------------------------------------- content-block ordering

    @Test
    fun `an interleaved text block keeps pi's block order`() {
        // Captured from pi's real anthropic-messages adapter (fed a provider
        // stream of text(0) -> tool_use(1) -> text(2)): the adapter emits
        // text_start@0, toolcall_start@1, text_start@2. Keying streaming rows by
        // "the last streaming text" would append block 2 into block 0's row,
        // which sits before the tool card.
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_start","message":{"role":"assistant"}}"""))
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":0}}"""))
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"First part. "}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":1,"id":"toolu_1","toolName":"bash"}}""",
            ),
        )
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":2}}"""))
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":2,"delta":"Second part."}}"""))

        assertEquals(3, r.transcript.size)
        assertEquals("First part. ", (r.transcript[0] as AssistantText).text)
        assertEquals("bash", (r.transcript[1] as ToolCall).toolName)
        assertEquals("Second part.", (r.transcript[2] as AssistantText).text)
    }

    @Test
    fun `a new assistant message restarts content block numbering`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_start","message":{"role":"assistant"}}"""))
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"one"}}"""))
        r.onEvent(PiEvents.parse("""{"type":"message_end","message":{"role":"assistant"}}"""))
        r.onEvent(PiEvents.parse("""{"type":"message_start","message":{"role":"assistant"}}"""))
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"two"}}"""))
        assertEquals(2, r.transcript.size)
        assertEquals("one", (r.transcript[0] as AssistantText).text)
        assertEquals("two", (r.transcript[1] as AssistantText).text)
    }

    @Test
    fun `turn events carry no index on the RPC wire`() {
        // AgentEvent declares no turnIndex and RPC forwards the agent event
        // verbatim; only the extension TurnStartEvent/TurnEndEvent carry one, and
        // those never reach stdout. An extra field is ignored, never surfaced.
        assertEquals(PiEvent.TurnStart, PiEvents.parse("""{"type":"turn_start","turnIndex":7}"""))
        val end = PiEvents.parse("""{"type":"turn_end","turnIndex":7,"toolResults":[{},{}]}""") as PiEvent.TurnEnd
        assertEquals(2, end.toolResultCount)
    }

    @Test
    fun `extension_error names the failing extension and hook`() {
        // docs/rpc.md §extension_error: { extensionPath, event, error };
        // rpc-mode.ts emits `error` text where `message` would be.
        val event = PiEvents.parse(
            """{"type":"extension_error","extensionPath":"/home/u/.pi/agent/extensions/x.ts","event":"tool_call","error":"boom"}""",
        ) as PiEvent.ExtensionError
        assertEquals("/home/u/.pi/agent/extensions/x.ts", event.extensionPath)
        assertEquals("tool_call", event.event)
        assertEquals("boom", event.message)
    }

    // ------------------------------------------- P5 / F3 + F2: abnormal turns

    @Test
    fun `a length stop reports the truncation instead of looking complete`() {
        // assistant-message.ts:182-185 prints a red line for `length`
        // unconditionally — without it an answer cut off by the output-token
        // limit reads as finished.
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"half"}],"stopReason":"length"}}""",
            ),
        )
        val error = r.transcript.single() as ErrorText
        assertEquals("回复被令牌上限截断", error.message)
    }

    @Test
    fun `a length stop closes no tool card`() {
        // interactive-mode.ts:3295 closes pending tools only for `aborted`/`error`;
        // `length` takes the else branch at :3306 and leaves them alone. pi's own
        // comment upstream says a length stop "can happen before a tool call is
        // complete", which is why the card must not be declared failed.
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":0,"id":"c1","toolName":"bash"}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"sleep 99"}}],"stopReason":"length"}}""",
            ),
        )
        assertEquals(ToolStatus.Pending, r.transcript.filterIsInstance<ToolCall>().single().status)
        assertEquals("回复被令牌上限截断", r.transcript.filterIsInstance<ErrorText>().single().message)
    }

    @Test
    fun `an aborted turn with a tool call closes its card and prints no second line`() {
        // The same assistant message that opened the card is the one that ends
        // (`message_end` carries the final `message`), so `hasToolCalls` is true,
        // assistant-message.ts:187 is false and pi prints no row — the closed card
        // carries the error instead (`interactive-mode.ts:3298-3304`).
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":0,"id":"c1","toolName":"bash"}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[""" +
                    """{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"sleep 99"}}],""" +
                    """"stopReason":"aborted","errorMessage":"Stopped by user"}}""",
            ),
        )
        assertTrue("no ErrorText row when the message carried a tool call", r.transcript.none { it is ErrorText })
        val call = r.transcript.filterIsInstance<ToolCall>().single()
        assertEquals(ToolStatus.Error, call.status)
        assertTrue(call.isError)
        assertTrue(call.endedAt != null)
        assertEquals("Stopped by user", call.output)
    }

    @Test
    fun `an aborted turn with no tool call prints the abort line`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"aborted","errorMessage":"Stopped by user"}}""",
            ),
        )
        val error = r.transcript.single() as ErrorText
        assertEquals("Stopped by user", error.message)
    }

    @Test
    fun `pi's fixed "Request was aborted" literal is not echoed`() {
        // assistant-message.ts:190-191: `message.errorMessage !== "Request was
        // aborted"` — pi refuses that one string and prints its own fixed line.
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"aborted","errorMessage":"Request was aborted"}}""",
            ),
        )
        val error = r.transcript.single() as ErrorText
        assertEquals("回合已中止", error.message)
    }

    @Test
    fun `an error stop with no tool call keeps pi's own message as the sentence`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"error","errorMessage":"529 overloaded"}}""",
            ),
        )
        val error = r.transcript.single() as ErrorText
        assertEquals("529 overloaded", error.message)
    }

    @Test
    fun `an error stop with a tool call closes the card and prints no row`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":0,"id":"c1","toolName":"bash"}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"toolCall","id":"c1","name":"bash","arguments":{}}],"stopReason":"error","errorMessage":"529 overloaded"}}""",
            ),
        )
        assertTrue(r.transcript.none { it is ErrorText })
        assertEquals("529 overloaded", r.transcript.filterIsInstance<ToolCall>().single().output)
    }

    @Test
    fun `a normal stop adds no error row`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"done"}}""",
            ),
        )
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"done"}],"stopReason":"stop"}}""",
            ),
        )
        assertTrue(r.transcript.none { it is ErrorText })
        assertEquals(1, r.transcript.size)
    }

    // ---------------------------------- F2/F3 on the replay path (persisted)

    @Test
    fun `history replay reports a length truncation exactly like the live stream`() {
        // The persisted assistant message keeps `stopReason`/`errorMessage`
        // verbatim (`packages/ai/src/types.ts:440,443`), so `get_entries` replay
        // must print the same row the live `message_end` did — until now the
        // replay path ignored the field and a truncated answer looked finished.
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"a1","timestamp":1000,"message":{"role":"assistant",""" +
                        """"content":[{"type":"text","text":"half"}],"stopReason":"length"}}""",
                ),
            ),
        )
        val error = r.transcript.filterIsInstance<ErrorText>().single()
        assertEquals("回复被令牌上限截断", error.message)
        assertEquals("a1-turn-failed", error.key)
        assertEquals("half", r.transcript.filterIsInstance<AssistantText>().single().text)
    }

    @Test
    fun `history replay closes the tool cards an aborted turn left pending`() {
        // interactive-mode.ts:3735-3746 — on replay pi hands every tool component
        // the aborted assistant entry opened `updateResult({isError:true})`,
        // because the `toolResult` message for it never arrives. Because that
        // entry also carries a tool call, assistant-message.ts:187 is false and
        // no separate row is printed.
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"a1","timestamp":1000,"message":{"role":"assistant",""" +
                        """"content":[{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"sleep 99"}}],""" +
                        """"stopReason":"aborted","errorMessage":"Operation aborted"}}""",
                ),
            ),
        )
        assertTrue("no ErrorText row when the entry carried a tool call", r.transcript.none { it is ErrorText })
        val call = r.transcript.filterIsInstance<ToolCall>().single()
        assertEquals(ToolStatus.Error, call.status)
        assertTrue(call.isError)
        assertEquals("Operation aborted", call.output)
        assertTrue(call.endedAt != null)
    }

    @Test
    fun `history replay leaves a length-truncated tool card pending`() {
        // The `length` half of ruling 1, on the replay path: interactive-mode.ts:3295
        // /:3735 never include `length` in the close condition.
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"a1","timestamp":1000,"message":{"role":"assistant",""" +
                        """"content":[{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"sleep 99"}}],""" +
                        """"stopReason":"length"}}""",
                ),
            ),
        )
        assertEquals(ToolStatus.Pending, r.transcript.filterIsInstance<ToolCall>().single().status)
        assertEquals("回复被令牌上限截断", r.transcript.filterIsInstance<ErrorText>().single().message)
    }

    @Test
    fun `history replay keeps pi's own errorMessage for an error stop`() {
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"a1","timestamp":1000,"message":{"role":"assistant",""" +
                        """"content":[],"stopReason":"error","errorMessage":"529 overloaded"}}""",
                ),
            ),
        )
        val error = r.transcript.filterIsInstance<ErrorText>().single()
        assertEquals("529 overloaded", error.message)
    }

    @Test
    fun `a normal history stop adds no error row and does not close a pending card`() {
        // `toolUse`/`stop` are the two reasons that still lead to a result
        // (`packages/ai/src/types.ts:406`); the toolResult entry follows and
        // finalises the card, so replay must leave it pending at this point.
        val r = reducer()
        r.seedFromHistory(
            listOf(
                obj(
                    """{"type":"message","id":"a1","timestamp":1000,"message":{"role":"assistant",""" +
                        """"content":[{"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"ls"}}],""" +
                        """"stopReason":"toolUse"}}""",
                ),
            ),
        )
        assertTrue(r.transcript.none { it is ErrorText })
        assertEquals(ToolStatus.Pending, r.transcript.filterIsInstance<ToolCall>().single().status)
    }

    @Test
    fun `a re-seed of a failed turn keeps the key stable`() {
        val entries = listOf(
            obj(
                """{"type":"message","id":"a1","timestamp":1000,"message":{"role":"assistant",""" +
                    """"content":[{"type":"text","text":"half"}],"stopReason":"length"}}""",
            ),
        )
        val r = reducer()
        r.seedFromHistory(entries)
        val first = r.transcript.filterIsInstance<ErrorText>().single().key
        r.seedFromHistory(entries)
        assertEquals(1, r.transcript.filterIsInstance<ErrorText>().size)
        assertEquals(first, r.transcript.filterIsInstance<ErrorText>().single().key)
    }

    // -------------------------------------- F24/F23/F16/F18: dropped payloads

    @Test
    fun `text_end content is authoritative over accumulated deltas`() {
        // A lost delta must not leave the row wrong: `text_end.content` is pi's
        // own text for the block.
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"hel"}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","assistantMessageEvent":{"type":"text_end","contentIndex":0,"content":"hello"}}""",
            ),
        )
        assertEquals("hello", (r.transcript.single() as AssistantText).text)
    }

    @Test
    fun `toolcall_end fills the card's arguments`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":0,"id":"c1","toolName":""}}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","contentIndex":0,"toolCall":{"id":"c1","name":"bash","arguments":{"command":"ls"}}}}""",
            ),
        )
        val call = r.transcript.single() as ToolCall
        assertEquals("bash", call.toolName)
        assertEquals("ls", call.args!!["command"].toString().trim('"'))
    }

    @Test
    fun `a truncated tool result is flagged and its images are kept`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_end","toolCallId":"c1","toolName":"read","isError":false,
                    "result":{"content":[{"type":"text","text":"head…"},{"type":"image","data":"AAAA","mimeType":"image/png"}],
                                "details":{"truncation":{"truncated":true},"fullOutputPath":"/tmp/x"}}}"""
                    .trimIndent().replace("\n", ""),
            ),
        )
        val call = r.transcript.filterIsInstance<ToolCall>().single()
        assertTrue(call.outputTruncated)
        assertEquals(1, call.images.size)
        assertEquals("AAAA", call.images.single().base64)
        assertEquals("image/png", call.images.single().mimeType)
    }

    @Test
    fun `a null truncation detail is not reported as truncated`() {
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","isError":false,
                    "result":{"content":[{"type":"text","text":"ok"}],"details":{"truncation":null}}}"""
                    .trimIndent().replace("\n", ""),
            ),
        )
        assertFalse(r.transcript.filterIsInstance<ToolCall>().single().outputTruncated)
    }

    @Test
    fun `both usage payloads reach the transcript`() {
        // F10: usage was parsed in three places and read by nobody.
        val r = reducer()
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_update","usage":{"input":100,"output":20,"totalTokens":120,"cost":{"total":0.001}}}""",
            ),
        )
        assertEquals(120L, r.lastUsage!!.totalTokens)
        r.onEvent(
            PiEvents.parse(
                """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"stop",
                    "usage":{"input":150,"output":30,"totalTokens":180,"cost":{"total":0.002}}}}"""
                    .trimIndent().replace("\n", ""),
            ),
        )
        assertEquals(180L, r.lastUsage!!.totalTokens)
        assertEquals(0.002, r.lastUsage!!.cost!!, 1e-9)
    }

    @Test
    fun `compaction usage is carried onto the marker`() {
        val r = reducer()
        r.onEvent(PiEvents.parse("""{"type":"compaction_start","reason":"threshold"}"""))
        r.onEvent(
            PiEvents.parse(
                """{"type":"compaction_end","aborted":false,"willRetry":false,
                    "result":{"summary":"s","firstKeptEntryId":"k","tokensBefore":100,
                              "usage":{"input":10,"output":5,"totalTokens":15,"cost":{"total":0.03}}}}"""
                    .trimIndent().replace("\n", ""),
            ),
        )
        assertEquals(0.03, (r.transcript.single() as CompactionMarker).usage!!.cost!!, 1e-9)
    }
}
