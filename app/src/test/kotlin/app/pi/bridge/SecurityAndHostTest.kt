package app.pi.bridge

import app.pi.host.ExtUiReducer
import app.pi.packages.PiConfigFiles
import app.pi.session.SessionFileReader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

class SecurityAndHostTest {

    @Test
    fun `GuestImageBytes rejects loopback, private LAN, link-local, CGNAT, and ULA addresses`() {
        val blockedIps = listOf(
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "::1",
            "fc00::1",
            "fd12:3456:789a::1",
        )
        for (ip in blockedIps) {
            assertTrue("Expected $ip to be blocked", GuestImageBytes.isDisallowedAddress(InetAddress.getByName(ip)))
        }
        assertFalse(GuestImageBytes.isDisallowedAddress(InetAddress.getByName("93.184.216.34")))
        assertNotNull(GuestImageBytes.rejectUnsafeRemoteHost("localhost"))
        assertNotNull(GuestImageBytes.rejectUnsafeRemoteHost("127.0.0.1"))
    }

    @Test
    fun `ExtUiReducer does not resurrect closed custom surfaces on late ui surface frames`() {
        val reducer = ExtUiReducer()
        val openFrame = JsonObject(
            mapOf(
                "surfaceId" to JsonPrimitive("s1"),
                "mode" to JsonPrimitive("custom"),
                "rev" to JsonPrimitive(1),
                "lines" to JsonArray(listOf(JsonPrimitive("hello"))),
            ),
        )
        assertNotNull(reducer.apply("ui.surface", openFrame))
        assertEquals(1, reducer.state.customStack.size)

        val closeEvent = JsonObject(
            mapOf(
                "surfaceId" to JsonPrimitive("s1"),
                "mode" to JsonPrimitive("custom"),
            ),
        )
        assertNotNull(reducer.apply("ui.close", closeEvent))
        assertTrue(reducer.state.customStack.isEmpty())

        // Late higher-rev frame for closed surface must be ignored.
        val lateFrame = JsonObject(
            mapOf(
                "surfaceId" to JsonPrimitive("s1"),
                "mode" to JsonPrimitive("custom"),
                "rev" to JsonPrimitive(2),
                "lines" to JsonArray(listOf(JsonPrimitive("zombie"))),
            ),
        )
        assertNull(reducer.apply("ui.surface", lateFrame))
        assertTrue(reducer.state.customStack.isEmpty())
    }

    @Test
    fun `PiConfigFiles writes atomically and cleans up temp files`() {
        val dir = Files.createTempDirectory("pi-config-test").toFile()
        try {
            val target = File(dir, "auth.json")
            val ok = PiConfigFiles.withLock(target) {
                PiConfigFiles.write(target, "{\"key\":\"secret\"}\n", mode600 = true)
            }
            assertTrue(ok)
            assertEquals("{\"key\":\"secret\"}\n", target.readText())
            val leftovers = dir.listFiles().orEmpty().filter { it.name.contains(".tmp-") || it.name.endsWith(".lock") }
            assertTrue("Unexpected leftover files: $leftovers", leftovers.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `SessionFileReader readEntries streams lines without retaining raw file lines`() {
        val dir = Files.createTempDirectory("pi-session-reader-test").toFile()
        try {
            val file = File(dir, "session.jsonl")
            file.writeText(
                """
                {"type":"session","version":3,"id":"s1","timestamp":"2026-01-01T00:00:00Z","cwd":"/workspace"}
                {"type":"message","id":"m1","parentId":null,"timestamp":"2026-01-01T00:00:01Z","message":{"role":"user","content":"hi"}}
                {"type":"message","id":"m2","parentId":"m1","timestamp":"2026-01-01T00:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"hello"}]}}
                """.trimIndent() + "\n",
            )
            val entries = mutableListOf<JsonObject>()
            val complete = SessionFileReader.readEntries(file) { entries += it }
            assertTrue(complete)
            assertEquals(2, entries.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
