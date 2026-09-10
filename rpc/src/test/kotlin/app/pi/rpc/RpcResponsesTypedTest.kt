package app.pi.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the typed readers for every response `docs/rpc.md` documents.
 *
 * Every payload below is copied from the doc (or, where the doc and the
 * implementation disagree, from `rpc-mode.ts` — the disagreement is called out
 * in the test), so a mismatch here is a mismatch with a real engine. The
 * unknown-field tests are the forward-compatibility half of the same contract:
 * pi adds fields in minor versions, and a reader that throws on a key it has
 * never seen would turn an engine upgrade into a client crash.
 */
class RpcResponsesTypedTest {

    private fun response(command: String, data: String): PiEvent.Response =
        PiEvents.parse("""{"id":"1","type":"response","command":"$command","success":true,"data":$data}""")
            as PiEvent.Response

    /** pi's `success()` omits `data` entirely when the handler passed `undefined`. */
    private fun responseWithoutData(command: String): PiEvent.Response =
        PiEvents.parse("""{"id":"1","type":"response","command":"$command","success":true}""")
            as PiEvent.Response

    /** pi serialises an explicit `data: null` (e.g. `cycle_model`, docs/rpc.md). */
    private fun responseWithNullData(command: String): PiEvent.Response =
        PiEvents.parse("""{"id":"1","type":"response","command":"$command","success":true,"data":null}""")
            as PiEvent.Response

    private val fullModel = """
        {"id":"claude-sonnet-4-20250514","name":"Claude Sonnet 4","api":"anthropic-messages",
         "provider":"anthropic","baseUrl":"https://api.anthropic.com","reasoning":true,
         "input":["text","image"],"contextWindow":200000,"maxTokens":16384,
         "cost":{"input":3.0,"output":15.0,"cacheRead":0.3,"cacheWrite":3.75}}
    """.trimIndent().replace("\n", "")

    // ------------------------------------------------------------------ get_state

    @Test
    fun `get_state parses the model and every documented field`() {
        // docs/rpc.md §get_state response.
        val state = PiResponses.sessionState(
            response(
                "get_state",
                """{"model":$fullModel,"thinkingLevel":"medium","isStreaming":false,
                    "isCompacting":false,"steeringMode":"all","followUpMode":"one-at-a-time",
                    "sessionFile":"/path/to/session.jsonl","sessionId":"abc123",
                    "sessionName":"my-feature-work","autoCompactionEnabled":true,
                    "messageCount":5,"pendingMessageCount":0}"""
                    .trimIndent().replace("\n", ""),
            ),
        )!!

        assertEquals("claude-sonnet-4-20250514", state.model!!.id)
        assertTrue(state.model.acceptsImages)
        assertEquals(200000L, state.model.contextWindow)
        assertEquals("medium", state.thinkingLevel)
        assertFalse(state.isStreaming)
        assertEquals("all", state.steeringMode)
        assertEquals("/path/to/session.jsonl", state.sessionFile)
        assertEquals("abc123", state.sessionId)
        assertEquals("my-feature-work", state.sessionName)
        assertTrue(state.autoCompactionEnabled)
        assertEquals(5, state.messageCount)
    }

    @Test
    fun `get_state models a null model and an absent sessionName`() {
        val state = PiResponses.sessionState(
            response(
                "get_state",
                """{"model":null,"thinkingLevel":"off","isStreaming":false,"isCompacting":false,
                    "steeringMode":"one-at-a-time","followUpMode":"one-at-a-time",
                    "sessionId":"abc123","autoCompactionEnabled":false,
                    "messageCount":0,"pendingMessageCount":0}""".trimIndent().replace("\n", ""),
            ),
        )!!
        assertNull(state.model)
        assertNull(state.sessionName)
        assertNull(state.sessionFile)
    }

    // --------------------------------------------------------------- get_messages

    @Test
    fun `get_messages parses every documented AgentMessage role`() {
        // Roles from docs/session-format.md §"AgentMessage Union".
        val messages = PiResponses.messages(
            response(
                "get_messages",
                """{"messages":[
                    {"role":"user","content":"Hello!","timestamp":1733234567890},
                    {"role":"assistant","api":"anthropic-messages","provider":"anthropic",
                     "model":"claude-sonnet-4-20250514","stopReason":"toolUse",
                     "content":[{"type":"text","text":"Hi! How can I help?"},
                                {"type":"thinking","thinking":"User is greeting me...","redacted":false},
                                {"type":"toolCall","id":"call_123","name":"bash","arguments":{"command":"ls"}}],
                     "usage":{"input":100,"output":50,"cacheRead":0,"cacheWrite":0,
                              "totalTokens":150,"cost":{"input":0.0003,"output":0.00075,"total":0.00105}},
                     "timestamp":1733234567891},
                    {"role":"toolResult","toolCallId":"call_123","toolName":"bash",
                     "content":[{"type":"text","text":"total 48"}],"isError":false,
                     "usage":{"input":100,"output":50,"totalTokens":150,"cost":{"total":0.00105}},
                     "timestamp":1733234567892},
                    {"role":"bashExecution","command":"ls -la","output":"total 48","exitCode":0,
                     "cancelled":false,"truncated":true,"fullOutputPath":"/tmp/pi-bash-abc.log",
                     "excludeFromContext":true,"timestamp":1733234567893},
                    {"role":"custom","customType":"my-extension","content":"Injected context...",
                     "display":true,"timestamp":1733234567894},
                    {"role":"branchSummary","summary":"Branch explored approach A...",
                     "fromId":"f6g7h8i9","timestamp":1733234567895},
                    {"role":"compactionSummary","summary":"Earlier work...","tokensBefore":50000,
                     "timestamp":1733234567896}
                ]}""".trimIndent().replace("\n", ""),
            ),
        )

        assertEquals(7, messages.size)

        val user = messages[0] as PiMessage.User
        assertEquals("user", user.role)
        assertEquals("Hello!", user.text)

        val assistant = messages[1] as PiMessage.Assistant
        assertEquals("Hi! How can I help?", assistant.text)
        assertEquals("toolUse", assistant.stopReason)
        assertEquals(3, assistant.content.size)
        assertEquals("User is greeting me...", (assistant.content[1] as PiContentBlock.Thinking).thinking)
        assertFalse((assistant.content[1] as PiContentBlock.Thinking).redacted)
        val call = assistant.content[2] as PiContentBlock.ToolCall
        assertEquals("call_123", call.id)
        assertEquals("ls", call.arguments!!["command"].toString().trim('"'))
        assertEquals(150L, assistant.usage!!.totalTokens)

        val toolResult = messages[2] as PiMessage.ToolResult
        assertEquals("call_123", toolResult.toolCallId)
        assertFalse(toolResult.isError)
        assertEquals("total 48", toolResult.text)

        val bash = messages[3] as PiMessage.BashExecution
        assertEquals("ls -la", bash.command)
        assertEquals(0, bash.exitCode)
        assertTrue(bash.truncated)
        assertEquals("/tmp/pi-bash-abc.log", bash.fullOutputPath)
        assertTrue(bash.excludeFromContext == true)

        assertEquals("my-extension", (messages[4] as PiMessage.Custom).customType)
        assertTrue((messages[4] as PiMessage.Custom).display)
        assertEquals("Branch explored approach A...", (messages[5] as PiMessage.BranchSummary).text)
        assertEquals("f6g7h8i9", (messages[5] as PiMessage.BranchSummary).fromId)
        assertEquals(50000L, (messages[6] as PiMessage.CompactionSummary).tokensBefore)
    }

    @Test
    fun `an unknown message role is preserved rather than dropped`() {
        val messages = PiResponses.messages(
            response("get_messages", """{"messages":[{"role":"futureRole","payload":42}]}"""),
        )
        assertEquals(1, messages.size)
        val unknown = messages[0] as PiMessage.Unknown
        assertEquals("futureRole", unknown.role)
        assertEquals("42", unknown.raw["payload"].toString())
    }

    @Test
    fun `message content may be a bare string or an array of blocks`() {
        val stringContent = parsePiMessage(
            JsonObject(
                mapOf(
                    "role" to JsonPrimitive("user"),
                    "content" to JsonPrimitive("plain"),
                ),
            ),
        )
        assertEquals("plain", stringContent.text)

        val blocks = parsePiMessage(
            JsonObject(
                mapOf(
                    "role" to JsonPrimitive("user"),
                    "content" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("text"),
                                    "text" to JsonPrimitive("look:"),
                                ),
                            ),
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("image"),
                                    "data" to JsonPrimitive("AAAA"),
                                    "mimeType" to JsonPrimitive("image/png"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals("look:[image]", blocks.text)
        val user = blocks as PiMessage.User
        assertEquals("AAAA", (user.content[1] as PiContentBlock.Image).data)
        assertEquals("image/png", (user.content[1] as PiContentBlock.Image).mimeType)
    }

    // ---------------------------------------------------------------- get_entries

    @Test
    fun `get_entries parses every documented entry type`() {
        // Entry shapes from docs/session-format.md §"Entry Types".
        val page = PiResponses.sessionEntries(
            response(
                "get_entries",
                """{"leafId":"i9j0k1l2","entries":[
                    {"type":"message","id":"a1b2c3d4","parentId":null,"timestamp":"2024-12-03T14:00:01.000Z",
                     "message":{"role":"user","content":"Hello","timestamp":1733234401000}},
                    {"type":"model_change","id":"d4e5f6g7","parentId":"c3d4e5f6","timestamp":"2024-12-03T14:05:00.000Z",
                     "provider":"openai","modelId":"gpt-4o"},
                    {"type":"thinking_level_change","id":"e5f6g7h8","parentId":"d4e5f6g7","timestamp":"2024-12-03T14:06:00.000Z",
                     "thinkingLevel":"high"},
                    {"type":"compaction","id":"f6g7h8i9","parentId":"e5f6g7h8","timestamp":"2024-12-03T14:10:00.000Z",
                     "summary":"User discussed X","firstKeptEntryId":"c3d4e5f6","tokensBefore":50000,
                     "usage":{"input":32000,"output":1200,"cacheRead":0,"cacheWrite":0,"totalTokens":33200,
                              "cost":{"input":0.01,"output":0.02,"total":0.03}},
                     "details":{"readFiles":["a.ts"]},"fromHook":true},
                    {"type":"branch_summary","id":"g7h8i9j0","parentId":"a1b2c3d4","timestamp":"2024-12-03T14:15:00.000Z",
                     "fromId":"f6g7h8i9","summary":"Branch explored approach A"},
                    {"type":"custom","id":"h8i9j0k1","parentId":"g7h8i9j0","timestamp":"2024-12-03T14:20:00.000Z",
                     "customType":"my-extension","data":{"count":42}},
                    {"type":"custom_message","id":"i9j0k1l2","parentId":"h8i9j0k1","timestamp":"2024-12-03T14:25:00.000Z",
                     "customType":"my-extension","content":"Injected context...","display":true},
                    {"type":"label","id":"j0k1l2m3","parentId":"i9j0k1l2","timestamp":"2024-12-03T14:30:00.000Z",
                     "targetId":"a1b2c3d4","label":"checkpoint-1"},
                    {"type":"session_info","id":"k1l2m3n4","parentId":"j0k1l2m3","timestamp":"2024-12-03T14:35:00.000Z",
                     "name":"Refactor auth module"}
                ]}""".trimIndent().replace("\n", ""),
            ),
        )!!

        assertEquals("i9j0k1l2", page.leafId)
        assertEquals(9, page.entries.size)

        val message = page.entries[0] as SessionEntry.Message
        assertEquals("a1b2c3d4", message.id)
        assertNull(message.parentId)
        assertEquals("Hello", message.message.text)

        assertEquals("gpt-4o", (page.entries[1] as SessionEntry.ModelChange).modelId)
        assertEquals("high", (page.entries[2] as SessionEntry.ThinkingLevelChange).thinkingLevel)
        val compaction = page.entries[3] as SessionEntry.Compaction
        assertEquals("User discussed X", compaction.summary)
        assertEquals(50000L, compaction.tokensBefore)
        assertTrue(compaction.fromHook == true)
        assertTrue(compaction.details!!["readFiles"].toString().contains("a.ts"))
        assertNotNull(compaction.usage)
        assertEquals(33200L, compaction.usage!!.totalTokens)
        assertEquals("f6g7h8i9", (page.entries[4] as SessionEntry.BranchSummary).fromId)
        assertEquals("my-extension", (page.entries[5] as SessionEntry.Custom).customType)
        assertEquals("my-extension", (page.entries[6] as SessionEntry.CustomMessage).customType)
        assertTrue((page.entries[6] as SessionEntry.CustomMessage).display)
        assertEquals("checkpoint-1", (page.entries[7] as SessionEntry.Label).label)
        assertEquals("Refactor auth module", (page.entries[8] as SessionEntry.SessionInfo).name)
    }

    @Test
    fun `an unknown entry type is kept with its identity fields`() {
        val page = PiResponses.sessionEntries(
            response(
                "get_entries",
                """{"leafId":"x","entries":[{"type":"future_entry","id":"x","parentId":"y","timestamp":"t"}]}""",
            ),
        )!!
        val unknown = page.entries.single() as SessionEntry.Unknown
        assertEquals("future_entry", unknown.type)
        assertEquals("x", unknown.id)
        assertEquals("y", unknown.parentId)
    }

    @Test
    fun `get_entries with a stale cursor is an error, not an empty page`() {
        // rpc-mode.ts answers success:false when `since` matches no entry, so the
        // reader must not be reached at all in that case.
        val event = PiEvents.parse(
            """{"id":"1","type":"response","command":"get_entries","success":false,"error":"Entry not found: gone"}""",
        ) as PiEvent.Response
        assertFalse(event.success)
        assertEquals("Entry not found: gone", event.error)
    }

    @Test
    fun `get_tree recurses through children and carries labels`() {
        val page = PiResponses.tree(
            response(
                "get_tree",
                """{"leafId":"def456","tree":[
                    {"entry":{"type":"message","id":"abc123","parentId":null,"timestamp":"t1",
                              "message":{"role":"user","content":"root"}},
                     "children":[
                        {"entry":{"type":"message","id":"def456","parentId":"abc123","timestamp":"t2",
                                  "message":{"role":"assistant","content":[{"type":"text","text":"leaf"}]}},
                         "children":[],
                         "label":"checkpoint","labelTimestamp":"t3"}
                     ]}
                ]}""".trimIndent().replace("\n", ""),
            ),
        )!!
        assertEquals("def456", page.leafId)
        val root = page.tree.single()
        assertNull(root.label)
        val child = root.children.single()
        assertEquals("def456", child.entry.id)
        assertEquals("checkpoint", child.label)
        assertEquals("t3", child.labelTimestamp)
        assertEquals("leaf", (child.entry as SessionEntry.Message).message.text)
    }

    // ----------------------------------------------------------- fork / clone etc

    @Test
    fun `get_fork_messages yields entryId and text and drops rows without an id`() {
        val messages = PiResponses.forkMessages(
            response(
                "get_fork_messages",
                """{"messages":[{"entryId":"abc123","text":"First prompt..."},
                                {"entryId":"def456","text":"Second prompt..."},
                                {"text":"no id"}]}""".trimIndent().replace("\n", ""),
            ),
        )
        assertEquals(2, messages.size)
        assertEquals("abc123", messages[0].entryId)
        assertEquals("Second prompt...", messages[1].text)
    }

    @Test
    fun `fork carries the forked text and tolerates its documented absence`() {
        val withText = PiResponses.forkResult(
            response("fork", """{"text":"The original prompt text...","cancelled":false}"""),
        )!!
        assertEquals("The original prompt text...", withText.text)
        assertFalse(withText.cancelled)

        // agent-session-runtime.ts types `selectedText` as optional, and
        // rpc-mode.ts forwards it verbatim, so the field can be missing.
        val withoutText = PiResponses.forkResult(response("fork", """{"cancelled":true}"""))!!
        assertNull(withoutText.text)
        assertTrue(withoutText.cancelled)
    }

    @Test
    fun `new_session switch_session and clone report cancellation`() {
        for (command in listOf("new_session", "switch_session", "clone")) {
            assertFalse(PiResponses.cancelledResult(response(command, """{"cancelled":false}"""))!!.cancelled)
            assertTrue(PiResponses.cancelledResult(response(command, """{"cancelled":true}"""))!!.cancelled)
        }
    }

    @Test
    fun `get_last_assistant_text separates null from absent`() {
        assertEquals("The assistant's response...", PiResponses.lastAssistantText(response("get_last_assistant_text", """{"text":"The assistant's response..."}""")))
        // docs/rpc.md: "Returns {"text": null} if no assistant messages exist."
        assertNull(PiResponses.lastAssistantText(response("get_last_assistant_text", """{"text":null}""")))
        assertNull(PiResponses.lastAssistantText(responseWithoutData("get_last_assistant_text")))
    }

    @Test
    fun `export_html returns the written path`() {
        assertEquals("/tmp/session.html", PiResponses.exportResult(response("export_html", """{"path":"/tmp/session.html"}"""))!!.path)
        assertNull(PiResponses.exportResult(response("export_html", """{}"""))!!.path)
    }

    // ------------------------------------------------------------------- commands

    @Test
    fun `get_commands reads the sourceInfo the implementation emits`() {
        // rpc-mode.ts pushes { name, description, source, sourceInfo }; docs/rpc.md
        // instead shows flat path/location fields, which the handler never sends.
        val commands = PiResponses.slashCommands(
            response(
                "get_commands",
                """{"commands":[
                    {"name":"session-name","description":"Set or clear session name","source":"extension",
                     "sourceInfo":{"path":"/home/user/.pi/agent/extensions/session.ts","source":"local",
                                   "scope":"global","origin":"top-level"}},
                    {"name":"fix-tests","description":"Fix failing tests","source":"prompt",
                     "location":"project","path":"/home/user/myproject/.pi/agent/prompts/fix-tests.md"},
                    {"name":"skill:brave-search","description":"Web search via Brave API","source":"skill",
                     "sourceInfo":{"path":"/home/user/.pi/agent/skills/brave-search/SKILL.md"}}
                ]}""".trimIndent().replace("\n", ""),
            ),
        )
        assertEquals(3, commands.size)
        assertEquals(PiResponses.CommandSource.Extension, commands[0].source)
        assertEquals("/home/user/.pi/agent/extensions/session.ts", commands[0].sourceInfo!!.path)
        assertEquals("global", commands[0].sourceInfo.scope)
        assertNull(commands[0].location)
        // Documented flat fields still parse, so a pi that follows the doc works.
        assertEquals("project", commands[1].location)
        assertEquals("/home/user/myproject/.pi/agent/prompts/fix-tests.md", commands[1].path)
        assertEquals(PiResponses.CommandSource.Skill, commands[2].source)
    }

    // ---------------------------------------------------------------------- model

    @Test
    fun `set_model parses the bare Model payload`() {
        // rpc-mode.ts: success(id, "set_model", model) — not wrapped in { model }.
        val model = PiResponses.setModel(response("set_model", fullModel))!!
        assertEquals("claude-sonnet-4-20250514", model.id)
        assertEquals("Claude Sonnet 4", model.name)
        assertEquals("anthropic", model.provider)
        assertEquals("anthropic-messages", model.api)
        assertEquals(3.0, model.inputCost!!, 1e-9)
        assertEquals(3.75, model.cacheWriteCost!!, 1e-9)
        assertTrue(model.reasoning)
    }

    @Test
    fun `cycle_model parses the wrapper and reports the documented null data`() {
        val result = PiResponses.cycleModel(
            response(
                "cycle_model",
                """{"model":$fullModel,"thinkingLevel":"medium","isScoped":true}"""
                    .trimIndent().replace("\n", ""),
            ),
        )!!
        assertEquals("claude-sonnet-4-20250514", result.model!!.id)
        assertEquals("medium", result.thinkingLevel)
        assertTrue(result.isScoped)

        // docs/rpc.md: "Returns null data if only one model available."
        assertNull(PiResponses.cycleModel(responseWithNullData("cycle_model")))
        assertNull(PiResponses.cycleModel(responseWithoutData("cycle_model")))
    }

    @Test
    fun `cycle_thinking_level parses the level and reports the documented null data`() {
        assertEquals("high", PiResponses.cycleThinkingLevel(response("cycle_thinking_level", """{"level":"high"}"""))!!.level)
        // docs/rpc.md: "Returns null data if model doesn't support thinking."
        assertNull(PiResponses.cycleThinkingLevel(responseWithNullData("cycle_thinking_level")))
    }

    @Test
    fun `a model without an id is dropped rather than keyed by an invention`() {
        val models = PiResponses.availableModels(
            response("get_available_models", """{"models":[{"name":"no id"},{"id":"ok"}]}"""),
        )
        assertEquals(1, models.size)
        assertEquals("ok", models[0].id)
        // pi's own UI falls back to the id for the display name.
        assertEquals("ok", models[0].name)
    }

    // ------------------------------------------------------------- compact / bash

    @Test
    fun `compact parses the documented result`() {
        val result = PiResponses.compactionResult(
            response(
                "compact",
                """{"summary":"Summary of conversation...","firstKeptEntryId":"abc123",
                    "tokensBefore":150000,"estimatedTokensAfter":32000,
                    "usage":{"input":32000,"output":1200,"cacheRead":0,"cacheWrite":0,
                             "totalTokens":33200,
                             "cost":{"input":0.01,"output":0.02,"cacheRead":0,"cacheWrite":0,"total":0.03}},
                    "details":{}}""".trimIndent().replace("\n", ""),
            ),
        )!!
        assertEquals("Summary of conversation...", result.summary)
        assertEquals("abc123", result.firstKeptEntryId)
        assertEquals(150000L, result.tokensBefore)
        assertEquals(32000L, result.estimatedTokensAfter)
        assertEquals(33200L, result.usage!!.totalTokens)
        assertEquals(0.03, result.usage.cost!!, 1e-9)
        assertTrue(result.hasDetails)
    }

    @Test
    fun `compact tolerates the handler that omits usage and details`() {
        // docs/rpc.md: "`usage` ... may be omitted by custom compaction handlers."
        val result = PiResponses.compactionResult(
            response(
                "compact",
                """{"summary":"s","firstKeptEntryId":"e","tokensBefore":10}""",
            ),
        )!!
        assertNull(result.usage)
        assertNull(result.estimatedTokensAfter)
        assertFalse(result.hasDetails)
    }

    @Test
    fun `bash parses output, exit code and truncation`() {
        val plain = PiResponses.bashResult(
            response("bash", """{"output":"total 48\n","exitCode":0,"cancelled":false,"truncated":false}"""),
        )!!
        assertEquals("total 48\n", plain.output)
        assertEquals(0, plain.exitCode)
        assertNull(plain.fullOutputPath)

        val truncated = PiResponses.bashResult(
            response(
                "bash",
                """{"output":"truncated output...","exitCode":0,"cancelled":false,"truncated":true,
                    "fullOutputPath":"/tmp/pi-bash-abc123.log"}""".trimIndent().replace("\n", ""),
            ),
        )!!
        assertTrue(truncated.truncated)
        assertEquals("/tmp/pi-bash-abc123.log", truncated.fullOutputPath)

        // core/bash-executor.ts: exitCode is undefined when killed/cancelled.
        val cancelled = PiResponses.bashResult(
            response("bash", """{"output":"","cancelled":true,"truncated":false}"""),
        )!!
        assertNull(cancelled.exitCode)
        assertTrue(cancelled.cancelled)
    }

    @Test
    fun `clear_queue returns both queues`() {
        val result = PiResponses.clearQueueResult(
            response(
                "clear_queue",
                """{"steering":["Change direction"],"followUp":["Summarize when finished"]}""",
            ),
        )!!
        assertEquals(listOf("Change direction"), result.steering)
        assertEquals(listOf("Summarize when finished"), result.followUp)
    }

    // --------------------------------------------------------- forward compatibility

    @Test
    fun `unknown extra fields never break a reader`() {
        // A minor pi version adding a field must degrade nothing. Every payload
        // here carries fields no reader names, at every nesting level.
        val state = PiResponses.sessionState(
            response(
                "get_state",
                """{"model":{"id":"m","name":"M","reasoning":false,"input":["text"],
                             "contextWindow":1,"maxTokens":1,"cost":{"input":1,"output":1},
                             "futureModelField":{"nested":true}},
                    "thinkingLevel":"low","isStreaming":false,"isCompacting":false,
                    "steeringMode":"all","followUpMode":"all","sessionId":"s",
                    "autoCompactionEnabled":false,"messageCount":0,"pendingMessageCount":0,
                    "futureStateField":[1,2,3]}""".trimIndent().replace("\n", ""),
            ),
        )!!
        assertEquals("low", state.thinkingLevel)

        val messages = PiResponses.messages(
            response(
                "get_messages",
                """{"messages":[{"role":"assistant","content":[{"type":"text","text":"hi",
                        "futureBlockField":true}],"api":"a","provider":"p","model":"m",
                        "usage":{"input":1,"output":1,"totalTokens":2,"cost":{"total":0},
                                 "futureUsageField":"x"},
                        "stopReason":"stop","timestamp":1,"futureMessageField":null}],
                    "futureMessagesWrapperField":{}}""".trimIndent().replace("\n", ""),
            ),
        )
        assertEquals("hi", messages.single().text)

        val page = PiResponses.sessionEntries(
            response(
                "get_entries",
                """{"leafId":"a","futurePageField":1,
                    "entries":[{"type":"message","id":"a","parentId":null,"timestamp":"t",
                                "futureEntryField":true,
                                "message":{"role":"user","content":"x","futureMessageField":1}}]}""",
            ),
        )!!
        assertEquals("a", page.leafId)
        assertEquals("x", (page.entries.single() as SessionEntry.Message).message.text)

        val tree = PiResponses.tree(
            response(
                "get_tree",
                """{"leafId":null,"futureTreeWrapper":0,
                    "tree":[{"entry":{"type":"model_change","id":"a","parentId":null,"timestamp":"t"},
                             "children":[],"futureNodeField":"ignored"}]}""".trimIndent().replace("\n", ""),
            ),
        )!!
        assertNull(tree.leafId)

        val stats = PiResponses.sessionStats(
            response(
                "get_session_stats",
                """{"sessionId":"s","userMessages":1,"assistantMessages":1,"toolCalls":0,
                    "toolResults":0,"totalMessages":2,
                    "tokens":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"total":2},
                    "cost":0.0,"futureStatsField":"x"}""".trimIndent().replace("\n", ""),
            ),
        )!!
        assertEquals(2, stats.totalMessages)

        val commands = PiResponses.slashCommands(
            response("get_commands", """{"commands":[{"name":"n","source":"prompt","futureCommandField":1}],"futureWrapper":true}"""),
        )
        assertEquals("n", commands.single().name)

        val models = PiResponses.availableModels(
            response("get_available_models", """{"models":[{"id":"m","futureModelField":1}],"futureWrapper":true}"""),
        )
        assertEquals("m", models.single().id)

        val bash = PiResponses.bashResult(
            response("bash", """{"output":"o","cancelled":false,"truncated":false,"futureBashField":1}"""),
        )!!
        assertEquals("o", bash.output)

        val compact = PiResponses.compactionResult(
            response("compact", """{"summary":"s","firstKeptEntryId":"e","tokensBefore":1,"futureCompactionField":true}"""),
        )!!
        assertEquals("s", compact.summary)

        val fork = PiResponses.forkResult(
            response("fork", """{"text":"t","cancelled":false,"futureForkField":1}"""),
        )!!
        assertEquals("t", fork.text)
    }

    @Test
    fun `absent required fields degrade instead of throwing`() {
        // No payload may produce an exception: a shape change degrades the UI.
        assertNull(PiResponses.sessionState(responseWithoutData("get_state")))
        assertNull(PiResponses.sessionStats(responseWithoutData("get_session_stats")))
        assertNull(PiResponses.cycleModel(responseWithoutData("cycle_model")))
        assertNull(PiResponses.compactionResult(responseWithoutData("compact")))
        assertNull(PiResponses.bashResult(responseWithoutData("bash")))
        assertNull(PiResponses.forkResult(responseWithoutData("fork")))
        assertNull(PiResponses.cancelledResult(responseWithoutData("new_session")))
        assertNull(PiResponses.sessionEntries(responseWithoutData("get_entries")))
        assertNull(PiResponses.tree(responseWithoutData("get_tree")))
        assertNull(PiResponses.clearQueueResult(responseWithoutData("clear_queue")))

        // `data` is present but not an object: still null, never a throw.
        assertNull(PiResponses.sessionState(response("get_state", """["not","an","object"]""")))
        assertNull(PiResponses.compactionResult(response("compact", """42""")))

        // A bare model payload with no id cannot be presented, so it is null.
        assertNull(PiResponses.setModel(response("set_model", """{"name":"no id"}""")))

        // Collections degrade to empty rather than null-or-throw.
        assertEquals(emptyList<PiMessage>(), PiResponses.messages(response("get_messages", """{"messages":[1,"x",null]}""")))
        assertEquals(emptyList<PiResponses.ForkMessage>(), PiResponses.forkMessages(response("get_fork_messages", """{"messages":[]}""")))
        assertEquals(emptyList<String>(), PiResponses.thinkingLevels(response("get_available_thinking_levels", """{"levels":"not-an-array"}""")))
        assertEquals(emptyList<PiResponses.SlashCommand>(), PiResponses.slashCommands(responseWithoutData("get_commands")))
    }

    @Test
    fun `dataIsNull separates an explicit null payload from an absent one`() {
        assertTrue(PiResponses.dataIsNull(responseWithoutData("cycle_model")))
        assertTrue(PiResponses.dataIsNull(responseWithNullData("cycle_model")))
        assertFalse(PiResponses.dataIsNull(response("cycle_model", """{"isScoped":false}""")))
    }
}
