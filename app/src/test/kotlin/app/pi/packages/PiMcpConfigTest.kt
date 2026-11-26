package app.pi.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiMcpConfigTest {

    @Test
    fun authProviderRoundTripsAndRespectsSdkSecurityRules() {
        val globalDraft = PiMcpConfig.Draft(
            name = "github-mcp",
            http = true,
            url = "https://mcp.example.com/mcp",
            authProvider = "github-copilot",
        )
        assertNull(PiMcpConfig.check(globalDraft, emptyList(), isProject = false))

        // Project scope MUST reject auth.provider (Pi SDK 1.6.1 security rule)
        assertNotNull(PiMcpConfig.check(globalDraft, emptyList(), isProject = true))

        // Plain HTTP non-loopback MUST be rejected for auth.provider
        val insecureHttp = globalDraft.copy(url = "http://remote.example.com/mcp")
        assertNotNull(PiMcpConfig.check(insecureHttp, emptyList(), isProject = false))

        // Loopback HTTP MUST be allowed for auth.provider
        val loopbackHttp = globalDraft.copy(url = "http://127.0.0.1:8080/mcp")
        assertNull(PiMcpConfig.check(loopbackHttp, emptyList(), isProject = false))

        // Mutual exclusion with bearer / Authorization header and OAuth
        assertNotNull(PiMcpConfig.check(globalDraft.copy(bearer = "secret"), emptyList(), isProject = false))
        assertNotNull(PiMcpConfig.check(globalDraft.copy(headers = "Authorization: Bearer abc"), emptyList(), isProject = false))
        assertNotNull(PiMcpConfig.check(globalDraft.copy(oauthClientId = "cid"), emptyList(), isProject = false))

        // Serialize & parse back
        val cfg = PiMcpConfig.toConfig(globalDraft, base = null)
        assertEquals("github-copilot", cfg["auth"]?.jsonObject?.get("provider")?.jsonPrimitive?.content)

        val server = PiMcpConfig.Server("github-mcp", cfg)
        assertEquals("github-copilot", server.authProvider)
        val roundTripped = PiMcpConfig.toDraft(server)
        assertEquals("github-copilot", roundTripped.authProvider)

        // Switching HTTP -> STDIO must clear auth
        val stdioCfg = PiMcpConfig.toConfig(
            roundTripped.copy(http = false, command = "npx", args = "-y\nserver"),
            base = cfg,
        )
        assertNull(stdioCfg["auth"])
        assertNull(stdioCfg["url"])
        assertEquals("npx", stdioCfg["command"]?.jsonPrimitive?.content)
    }

    @Test
    fun projectExposureOverrideWritesAndCleansUpWhenMatchingGlobal() {
        val emptyRoot = JsonObject(emptyMap())
        val overridden = PiMcpConfig.setProjectExposureOverride(
            root = emptyRoot,
            name = "docs",
            exposure = "direct",
            globalExposure = "codemode",
        )
        val servers1 = overridden["mcpServers"]!!.jsonObject
        assertEquals("direct", servers1["docs"]!!.jsonObject["exposure"]!!.jsonPrimitive.content)

        // Adding enabled override alongside exposure preserves both keys
        val both = PiMcpConfig.setProjectOverride(
            root = overridden,
            name = "docs",
            enabled = false,
            globalEnabled = true,
        )
        val docsObj = both["mcpServers"]!!.jsonObject["docs"]!!.jsonObject
        assertEquals("direct", docsObj["exposure"]!!.jsonPrimitive.content)
        assertEquals("false", docsObj["enabled"]!!.jsonPrimitive.content)

        // Resetting exposure back to global removes only exposure, keeps enabled=false
        val exposureCleared = PiMcpConfig.setProjectExposureOverride(
            root = both,
            name = "docs",
            exposure = "codemode",
            globalExposure = "codemode",
        )
        val docsAfterExposureClear = exposureCleared["mcpServers"]!!.jsonObject["docs"]!!.jsonObject
        assertNull(docsAfterExposureClear["exposure"])
        assertEquals("false", docsAfterExposureClear["enabled"]!!.jsonPrimitive.content)

        // Resetting enabled back to global cleans up empty override entry completely
        val allCleared = PiMcpConfig.setProjectOverride(
            root = exposureCleared,
            name = "docs",
            enabled = true,
            globalEnabled = true,
        )
        assertTrue(allCleared["mcpServers"]!!.jsonObject.isEmpty())
    }

    @Test
    fun clearingAuthProviderPreservesUnknownAuthKeys() {
        val base = Json.parseToJsonElement(
            """{"url":"https://mcp.example.com/mcp","auth":{"provider":"openai","customFutureField":"keep"}}""",
        ).jsonObject
        val draft = PiMcpConfig.toDraft(PiMcpConfig.Server("test", base)).copy(authProvider = "")
        val updated = PiMcpConfig.toConfig(draft, base)
        val auth = updated["auth"]!!.jsonObject
        assertNull(auth["provider"])
        assertEquals("keep", auth["customFutureField"]!!.jsonPrimitive.content)
    }
}
