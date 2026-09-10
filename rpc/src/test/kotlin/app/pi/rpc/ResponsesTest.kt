package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The response readers are the contract behind the command palette, the status
 * row and the model picker. Each sample below is the shape pi declares in
 * `src/modes/rpc/rpc-types.ts` / `src/core/agent-session.ts`, so a mismatch here
 * is a mismatch with the engine.
 */
class ResponsesTest {

    private fun response(command: String, data: String): PiEvent.Response {
        val event = PiEvents.parse("""{"id":"1","type":"response","command":"$command","success":true,"data":$data}""")
        return event as PiEvent.Response
    }

    @Test
    fun `slash commands carry name, description and source`() {
        val commands = PiResponses.slashCommands(
            response(
                "get_commands",
                """{"commands":[
                    {"name":"pix-reload","description":"Reload config","source":"extension",
                     "sourceInfo":{"path":"/x.ts","source":"local","scope":"global","origin":"top-level"}},
                    {"name":"review","description":"Review code","source":"skill","sourceInfo":{}},
                    {"name":"triage","source":"prompt","sourceInfo":{}}
                ]}""".trimIndent().replace("\n", ""),
            ),
        )
        assertEquals(3, commands.size)
        assertEquals("pix-reload", commands[0].name)
        assertEquals(PiResponses.CommandSource.Extension, commands[0].source)
        assertEquals(PiResponses.CommandSource.Skill, commands[1].source)
        assertEquals(PiResponses.CommandSource.Prompt, commands[2].source)
        assertNull(commands[2].description)
    }

    @Test
    fun `session state reads every field pi declares`() {
        val state = PiResponses.sessionState(
            response(
                "get_state",
                """{"thinkingLevel":"medium","isStreaming":true,"isCompacting":false,
                    "steeringMode":"one-at-a-time","followUpMode":"all",
                    "sessionFile":"/s/x.jsonl","sessionId":"abc","sessionName":"work",
                    "autoCompactionEnabled":true,"messageCount":12,"pendingMessageCount":2}"""
                    .trimIndent().replace("\n", ""),
            ),
        )!!
        assertEquals("medium", state.thinkingLevel)
        assertTrue(state.isStreaming)
        assertEquals("all", state.followUpMode)
        assertEquals("/s/x.jsonl", state.sessionFile)
        assertEquals(12, state.messageCount)
        assertEquals(2, state.pendingMessageCount)
    }

    @Test
    fun `session stats read the nested token totals and context usage`() {
        val stats = PiResponses.sessionStats(
            response(
                "get_session_stats",
                """{"sessionFile":"/s/x.jsonl","sessionId":"abc","userMessages":3,
                    "assistantMessages":4,"toolCalls":9,"toolResults":9,"totalMessages":16,
                    "tokens":{"input":1200,"output":340,"cacheRead":9000,"cacheWrite":40,"total":10580},
                    "cost":0.0123,
                    "contextUsage":{"tokens":24000,"contextWindow":200000,"percent":12}}"""
                    .trimIndent().replace("\n", ""),
            ),
        )!!
        assertEquals(3, stats.userMessages)
        assertEquals(9, stats.toolCalls)
        assertEquals(10580L, stats.tokens!!.total)
        assertEquals(9000L, stats.tokens.cacheRead)
        assertEquals(0.0123, stats.cost!!, 1e-9)
        assertEquals(12.0, stats.contextUsage!!.percent!!, 1e-9)
    }

    @Test
    fun `context usage tolerates pi's documented nulls`() {
        // pi explicitly allows tokens/percent to be null right after compaction,
        // before the next response. That must not read as zero.
        val stats = PiResponses.sessionStats(
            response(
                "get_session_stats",
                """{"sessionId":"abc","userMessages":0,"assistantMessages":0,"toolCalls":0,
                    "toolResults":0,"totalMessages":0,
                    "contextUsage":{"tokens":null,"contextWindow":200000,"percent":null}}"""
                    .trimIndent().replace("\n", ""),
            ),
        )!!
        assertNull(stats.contextUsage!!.tokens)
        assertNull(stats.contextUsage.percent)
        assertEquals(200000L, stats.contextUsage.contextWindow)
    }

    @Test
    fun `available models expose vision support and pricing`() {
        val models = PiResponses.availableModels(
            response(
                "get_available_models",
                """{"models":[
                    {"id":"claude-sonnet-4.5","name":"Sonnet 4.5","api":"anthropic-messages",
                     "provider":"anthropic","baseUrl":"https://api.anthropic.com","reasoning":true,
                     "input":["text","image"],
                     "cost":{"input":3,"output":15,"cacheRead":0.3,"cacheWrite":3.75},
                     "contextWindow":200000,"maxTokens":64000},
                    {"id":"gpt-4o-mini","name":"4o mini","api":"openai-responses","provider":"openai",
                     "baseUrl":"https://api.openai.com","reasoning":false,"input":["text"],
                     "cost":{"input":0.15,"output":0.6,"cacheRead":0.075,"cacheWrite":0},
                     "contextWindow":128000,"maxTokens":16384}
                ]}""".trimIndent().replace("\n", ""),
            ),
        )
        assertEquals(2, models.size)
        assertTrue(models[0].reasoning)
        assertTrue(models[0].acceptsImages)
        assertEquals(200000L, models[0].contextWindow)
        assertEquals(3.0, models[0].inputCost!!, 1e-9)
        assertEquals(false, models[1].acceptsImages)
        assertEquals(0.15, models[1].inputCost!!, 1e-9)
    }

    @Test
    fun `thinking levels come back as wire strings`() {
        val levels = PiResponses.thinkingLevels(
            response(
                "get_available_thinking_levels",
                """{"levels":["off","minimal","low","medium","high","xhigh","max"]}""",
            ),
        )
        assertEquals(listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"), levels)
    }

    @Test
    fun `small payload readers match pi's field names`() {
        assertEquals("hello", PiResponses.lastAssistantText(response("get_last_assistant_text", """{"text":"hello"}""")))
        assertEquals("/out/x.html", PiResponses.exportPath(response("export_html", """{"path":"/out/x.html"}""")))
        assertTrue(PiResponses.cancelled(response("switch_session", """{"cancelled":true}""")))
    }

    @Test
    fun `a missing or misshapen payload degrades instead of throwing`() {
        val bare = PiEvent.Response(id = "1", command = "get_session_stats", success = true, error = null, data = null)
        assertNull(PiResponses.sessionStats(bare))
        assertNull(PiResponses.sessionState(bare))
        assertEquals(emptyList<PiResponses.SlashCommand>(), PiResponses.slashCommands(bare))
        assertEquals(emptyList<PiResponses.ModelInfo>(), PiResponses.availableModels(bare))
        assertEquals(emptyList<String>(), PiResponses.thinkingLevels(bare))
        assertEquals(emptyList<kotlinx.serialization.json.JsonObject>(), PiResponses.entries(bare))
    }

    @Test
    fun `unexpected types inside a payload are skipped, not fatal`() {
        val commands = PiResponses.slashCommands(
            response("get_commands", """{"commands":["a string",{"noName":true},{"name":"ok","source":"weird"}]}"""),
        )
        assertEquals(1, commands.size)
        assertEquals("ok", commands[0].name)
        assertEquals(PiResponses.CommandSource.Unknown, commands[0].source)
    }
}
