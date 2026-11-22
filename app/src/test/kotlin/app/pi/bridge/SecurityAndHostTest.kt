package app.pi.bridge

import app.pi.engine.SdkHostLaunch
import app.pi.host.ExtUiReducer
import app.pi.host.HostHello
import app.pi.host.HostState
import app.pi.runtime.GuestCommandLine
import app.pi.runtime.GuestEngine
import app.pi.runtime.PiPaths
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

    @Test
    fun `SdkHostLaunch argv and direct builders exec node directly without bash`() {
        val dir = Files.createTempDirectory("pi-direct-exec-test").toFile()
        try {
            val nativeLib = File(dir, "lib").apply { mkdirs() }
            val paths = PiPaths(filesDir = dir, nativeLibDir = nativeLib)
            paths.rootfs.mkdirs()
            val guestArgv = SdkHostLaunch.argv(
                cwd = "/workspace/pi/workspaces/default",
                agentDir = "/root/.pi/agent",
                sessionDir = "/root/.pi/agent/sessions",
                piDir = "/opt/pi/node_modules/@earendil-works/pi-coding-agent",
            )
            assertEquals("/opt/node/bin/node", guestArgv.first())
            assertTrue(guestArgv.contains(SdkHostLaunch.GUEST_ENTRY))
            assertTrue(guestArgv.contains("--android-extensions"))
            assertFalse(guestArgv.contains("--mode"))

            for (engine in listOf(GuestEngine.Proot, GuestEngine.Proroot, GuestEngine.Tawcroot)) {
                val built = GuestCommandLine.buildDirect(
                    paths = paths,
                    engine = engine,
                    guestArgv = guestArgv,
                    cwd = "/workspace/pi/workspaces/default",
                    storage = null,
                )
                assertFalse("Direct argv for $engine must not spawn /bin/bash: $built", built.contains("/bin/bash"))
                assertTrue("Direct argv for $engine must exec /opt/node/bin/node: $built", built.contains("/opt/node/bin/node"))
                if (engine == GuestEngine.Tawcroot) {
                    assertTrue("Tawcroot direct argv must keep -s: $built", built.contains("-s"))
                    assertTrue("Tawcroot direct argv must keep -- separator: $built", built.contains("--"))
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `HostHello fromJsonOrNull parses embedded host ready payload and rejects legacy empty ready`() {
        val embedded = JsonObject(
            mapOf(
                "protocol" to JsonPrimitive(1),
                "engine" to JsonPrimitive("coding-agent"),
                "node" to JsonPrimitive("v24.0.0"),
                "commands" to JsonArray(listOf(JsonPrimitive("session.open"), JsonPrimitive("chat.send"))),
                "capabilities" to JsonArray(listOf(JsonPrimitive("stream"))),
            ),
        )
        val parsed = HostHello.fromJsonOrNull(embedded)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.protocol)
        assertEquals(setOf("session.open", "chat.send"), parsed.commands)
        assertEquals(setOf("stream"), parsed.capabilities)

        val legacyReady = JsonObject(mapOf("pid" to JsonPrimitive(1234), "cwd" to JsonPrimitive("/workspace")))
        assertNull(HostHello.fromJsonOrNull(legacyReady))
    }

    @Test
    fun `HostState projects availableThinkingLevels and toSessionState for zero-RTT attach`() {
        val raw = JsonObject(
            mapOf(
                "epoch" to JsonPrimitive(1),
                "seq" to JsonPrimitive(1),
                "sessionId" to JsonPrimitive("sess-1"),
                "sessionFile" to JsonPrimitive("/root/.pi/agent/sessions/s1.jsonl"),
                "thinkingLevel" to JsonPrimitive("medium"),
                "availableThinkingLevels" to JsonArray(listOf(JsonPrimitive("off"), JsonPrimitive("low"), JsonPrimitive("medium"))),
                "supportsThinking" to JsonPrimitive(true),
                "isStreaming" to JsonPrimitive(false),
                "isCompacting" to JsonPrimitive(false),
                "autoCompactionEnabled" to JsonPrimitive(true),
                "pendingMessageCount" to JsonPrimitive(0),
                "messageCounts" to JsonObject(mapOf("total" to JsonPrimitive(4))),
            ),
        )
        val hs = HostState(raw)
        assertEquals(listOf("off", "low", "medium"), hs.availableThinkingLevels)
        assertEquals(true, hs.supportsThinking)
        val ss = hs.toSessionState()
        assertNotNull(ss)
        assertEquals("sess-1", ss!!.sessionId)
        assertEquals("medium", ss.thinkingLevel)
        assertEquals(4, ss.messageCount)
    }

    @Test
    fun `vanilla CLI pi script runs official cli without injected extension patches`() {
        val cli = "/opt/pi/node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js"
        val script = DeviceBridgeController.buildVanillaCliPiScript(cli)
        assertEquals("#!/bin/bash\nexec /opt/node/bin/node $cli \"\$@\"\n", script)
        assertFalse(script.contains("android-extensions"))
    }
}
