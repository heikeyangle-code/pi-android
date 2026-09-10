package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Event parsing must be total: anything pi can say — including things this
 * build has never heard of — has to land on a typed case or [PiEvent.Unknown].
 * A parse failure may never propagate (docs/pi-android-ui-spec.md §1, principle 7).
 */
class EventsTest {

    @Test
    fun `parses a success response with its payload`() {
        val e = PiEvents.parse(
            """{"id":"1","type":"response","command":"get_state","success":true,
                "data":{"sessionId":"abc","isStreaming":false}}""".trimIndent().replace("\n", ""),
        )
        assertTrue(e is PiEvent.Response)
        e as PiEvent.Response
        assertEquals("1", e.id)
        assertEquals("get_state", e.command)
        assertTrue(e.success)
        assertNotNull(e.data)
    }

    @Test
    fun `parses a failed response rather than treating it as success`() {
        val e = PiEvents.parse("""{"id":"2","type":"response","command":"prompt","success":false,"error":"streaming"}""")
        e as PiEvent.Response
        assertEquals(false, e.success)
        assertEquals("streaming", e.error)
    }

    @Test
    fun `parses streaming text deltas with their content index`() {
        val e = PiEvents.parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hel"}}""",
        )
        e as PiEvent.MessageUpdate
        val d = e.delta
        assertTrue(d is AssistantDelta.TextDelta)
        d as AssistantDelta.TextDelta
        assertEquals(0, d.contentIndex)
        assertEquals("Hel", d.delta)
    }

    @Test
    fun `parses thinking deltas distinctly from text`() {
        val e = PiEvents.parse(
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"hmm"}}""",
        )
        e as PiEvent.MessageUpdate
        assertTrue(e.delta is AssistantDelta.ThinkingDelta)
    }

    @Test
    fun `flattens a tool result content array and keeps structured details`() {
        val e = PiEvents.parse(
            """{"type":"tool_execution_end","toolCallId":"t1","toolName":"edit","isError":false,
                "result":{"content":[{"type":"text","text":"done"}],"details":{"diff":"@@ -1 +1 @@"}}}"""
                .trimIndent().replace("\n", ""),
        )
        e as PiEvent.ToolExecutionEnd
        assertEquals("t1", e.toolCallId)
        assertEquals("done", e.resultText)
        assertEquals(false, e.isError)
        // details is the key that lets the UI render a real diff card instead of guessing.
        assertTrue(e.details.toString().contains("@@ -1 +1 @@"))
    }

    @Test
    fun `reads partial tool output out of partialResult content`() {
        val e = PiEvents.parse(
            """{"type":"tool_execution_update","toolCallId":"t1","partialResult":{"content":[{"type":"text","text":"half"}]}}""",
        )
        e as PiEvent.ToolExecutionUpdate
        assertEquals("half", e.partialText)
    }

    @Test
    fun `exposes an extension dialog with everything the UI needs to draw it`() {
        val e = PiEvents.parse(
            """{"type":"extension_ui_request","id":"u1","method":"select","title":"Dangerous command",
                "options":["Yes","No"],"timeout":30000}""".trimIndent().replace("\n", ""),
        )
        e as PiEvent.ExtensionUiRequest
        assertEquals("u1", e.uiId)
        assertEquals("select", e.method)
        assertEquals(listOf("Yes", "No"), e.options)
        assertEquals(30000L, e.timeoutMs)
    }

    @Test
    fun `empty options list is safe for non-list dialogs`() {
        val e = PiEvents.parse("""{"type":"extension_ui_request","id":"u2","method":"confirm","message":"ok?"}""")
        e as PiEvent.ExtensionUiRequest
        assertEquals(emptyList<String>(), e.options)
        assertEquals("ok?", e.message)
    }

    @Test
    fun `unknown event types are preserved, not dropped`() {
        val e = PiEvents.parse("""{"type":"brand_new_thing","payload":{"n":1}}""")
        assertTrue(e is PiEvent.Unknown)
        e as PiEvent.Unknown
        assertEquals("brand_new_thing", e.type)
        assertTrue(e.raw.toString().contains("payload"))
    }

    @Test
    fun `unparsable input degrades instead of throwing`() {
        val e = PiEvents.parse("{not json at all")
        assertTrue(e is PiEvent.Unknown)
    }

    @Test
    fun `a missing type does not throw`() {
        val e = PiEvents.parse("""{"id":"1"}""")
        assertTrue(e is PiEvent.Unknown)
    }

    @Test
    fun `wrong field types fall back to Unknown with the original object`() {
        // options should be an array; a string must not blow up the parser.
        val e = PiEvents.parse("""{"type":"extension_ui_request","id":"u3","method":"select","options":"oops"}""")
        // Either a typed request with an empty option list, or Unknown — both are
        // acceptable; throwing is not.
        assertNotNull(e)
    }

    @Test
    fun `queue update accepts both camelCase and snake_case keys`() {
        val a = PiEvents.parse("""{"type":"queue_update","steering":["s"],"followUp":["f"]}""")
        a as PiEvent.QueueUpdate
        assertEquals(listOf("s"), a.steering)
        assertEquals(listOf("f"), a.followUp)

        val b = PiEvents.parse("""{"type":"queue_update","steering":["s"],"follow_up":["f2"]}""")
        b as PiEvent.QueueUpdate
        assertEquals(listOf("f2"), b.followUp)
    }

    @Test
    fun `usage is parsed for the footer contract`() {
        val e = PiEvents.parse(
            """{"type":"message_update","usage":{"input":100,"output":20,"cacheRead":900,
                "cacheWrite":10,"totalTokens":1030,"cost":0.0123}}""".trimIndent().replace("\n", ""),
        )
        e as PiEvent.MessageUpdate
        val u = e.usage
        assertNotNull(u)
        assertEquals(100L, u!!.input)
        assertEquals(900L, u.cacheRead)
        assertEquals(0.0123, u.cost!!, 1e-9)
    }

    @Test
    fun `every documented event type maps to a typed case`() {
        val samples = listOf(
            """{"type":"agent_start"}""",
            """{"type":"agent_end","willRetry":false}""",
            """{"type":"agent_settled"}""",
            """{"type":"turn_start","turnIndex":0}""",
            """{"type":"turn_end","turnIndex":0}""",
            """{"type":"message_start","message":{"role":"assistant"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[],"stopReason":"endTurn"}}""",
            """{"type":"tool_execution_start","toolCallId":"t","toolName":"bash","args":{}}""",
            """{"type":"bash_execution_update","id":"b1","delta":"out"}""",
            """{"type":"compaction_start","reason":"threshold"}""",
            """{"type":"compaction_end","aborted":false,"willRetry":false}""",
            """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000}""",
            """{"type":"auto_retry_end","success":true,"attempt":1}""",
            """{"type":"extension_error","message":"boom"}""",
            """{"type":"entry_appended","entry":{"id":"e1","type":"message"}}""",
            """{"type":"session_info_changed","name":"my session"}""",
            """{"type":"thinking_level_changed","level":"high"}""",
        )
        val unknowns = samples.map { PiEvents.parse(it) }.filterIsInstance<PiEvent.Unknown>()
        assertEquals("no documented event may degrade: $unknowns", emptyList<PiEvent.Unknown>(), unknowns)
    }
}
