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
}
