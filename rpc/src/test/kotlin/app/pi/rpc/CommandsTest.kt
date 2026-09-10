package app.pi.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the wire contract. These field names are the compatibility surface with
 * pi (docs/pi-android-app-design.md §4.2) — renaming one silently breaks the
 * client against a real engine, so each builder is asserted literally.
 */
class CommandsTest {

    private fun JsonObject.s(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `prompt carries message and id`() {
        val c = PiCommands.prompt("r1", "hello")
        assertEquals("r1", c.s("id"))
        assertEquals("prompt", c.s("type"))
        assertEquals("hello", c.s("message"))
        assertNull(c["images"])
        assertNull(c["streamingBehavior"])
    }

    @Test
    fun `prompt encodes images in pi's ImageContent shape`() {
        val c = PiCommands.prompt(
            "r1",
            "look",
            images = listOf(PiImage(base64 = "AAAA", mimeType = "image/png")),
        )
        val img = c["images"]!!.jsonArray.single().jsonObject
        assertEquals("image", img.s("type"))
        assertEquals("AAAA", img.s("data"))
        assertEquals("image/png", img.s("mimeType"))
    }

    @Test
    fun `streaming behaviour is only present when asked for`() {
        assertEquals("steer", PiCommands.prompt("r", "m", streamingBehavior = StreamingBehavior.Steer).s("streamingBehavior"))
        assertEquals("followUp", PiCommands.followUp("r", "m", streamingBehavior = StreamingBehavior.FollowUp).s("streamingBehavior"))
        assertNull(PiCommands.prompt("r", "m").s("streamingBehavior"))
    }

    @Test
    fun `queue modes use pi's hyphenated wire values`() {
        assertEquals(
            "one-at-a-time",
            PiCommands.setSteeringMode("r", QueueMode.OneAtATime).s("mode"),
        )
        assertEquals("all", PiCommands.setFollowUpMode("r", QueueMode.All).s("mode"))
    }

    @Test
    fun `model and thinking setters use pi's field names`() {
        val m = PiCommands.setModel("r", "anthropic", "claude-sonnet-4.5")
        assertEquals("set_model", m.s("type"))
        assertEquals("anthropic", m.s("provider"))
        assertEquals("claude-sonnet-4.5", m.s("modelId"))

        val t = PiCommands.setThinkingLevel("r", "medium")
        assertEquals("set_thinking_level", t.s("type"))
        assertEquals("medium", t.s("level"))
    }

    @Test
    fun `session commands match pi's parameter names`() {
        assertEquals("sess.jsonl", PiCommands.switchSession("r", "sess.jsonl").s("sessionPath"))
        assertEquals("e-1", PiCommands.fork("r", "e-1").s("entryId"))
        assertEquals("name", PiCommands.setSessionName("r", "name").s("name"))
        assertEquals("e-9", PiCommands.getEntries("r", since = "e-9").s("since"))
        assertNull(PiCommands.getEntries("r").s("since"))
    }

    @Test
    fun `compact omits empty instructions rather than sending null`() {
        val bare = PiCommands.compact("r")
        assertEquals("compact", bare.s("type"))
        assertFalse(bare.containsKey("customInstructions"))
        assertEquals("focus", PiCommands.compact("r", "focus").s("customInstructions"))
    }

    @Test
    fun `extension ui responses use three distinct shapes`() {
        val v = PiCommands.extensionUiValue("u1", "Allow")
        assertEquals("extension_ui_response", v.s("type"))
        assertEquals("Allow", v.s("value"))

        val c = PiCommands.extensionUiConfirmed("u2", true)
        assertEquals("true", c.s("confirmed"))

        val x = PiCommands.extensionUiCancelled("u3")
        assertEquals("true", x.s("cancelled"))
        assertFalse(x.containsKey("value"))
    }

    @Test
    fun `every command encodes as exactly one LF-terminated line`() {
        val all = listOf(
            PiCommands.prompt("r", "m"), PiCommands.steer("r", "m"), PiCommands.followUp("r", "m"),
            PiCommands.abort("r"), PiCommands.clearQueue("r"), PiCommands.newSession("r"),
            PiCommands.getState("r"), PiCommands.getMessages("r"),
            PiCommands.setModel("r", "p", "m"), PiCommands.cycleModel("r"),
            PiCommands.getAvailableModels("r"),
            PiCommands.setThinkingLevel("r", "low"), PiCommands.cycleThinkingLevel("r"),
            PiCommands.getAvailableThinkingLevels("r"),
            PiCommands.setSteeringMode("r", QueueMode.All), PiCommands.setFollowUpMode("r", QueueMode.All),
            PiCommands.compact("r"), PiCommands.setAutoCompaction("r", true),
            PiCommands.setAutoRetry("r", true), PiCommands.abortRetry("r"),
            PiCommands.bash("r", "ls"), PiCommands.abortBash("r"),
            PiCommands.getSessionStats("r"), PiCommands.exportHtml("r"),
            PiCommands.switchSession("r", "s"), PiCommands.fork("r", "e"), PiCommands.clone("r"),
            PiCommands.getForkMessages("r"), PiCommands.getEntries("r"), PiCommands.getTree("r"),
            PiCommands.getLastAssistantText("r"), PiCommands.setSessionName("r", "n"),
            PiCommands.getCommands("r"),
        )
        assertEquals(33, all.size)
        for (cmd in all) {
            val line = PiCommands.encode(cmd)
            assertTrue("must end with a single LF", line.endsWith("\n"))
            assertEquals("must contain no interior LF", 1, line.count { it == '\n' })
            assertNotNull(cmd.s("type"))
            assertNotNull("every command carries an id", cmd.s("id"))
        }
    }
}
