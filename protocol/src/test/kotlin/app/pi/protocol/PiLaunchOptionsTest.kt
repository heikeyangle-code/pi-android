package app.pi.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins structured [PiLaunchOptions] environment & field normalization for SDK Host launch.
 */
class PiLaunchOptionsTest {

    @Test
    fun `defaults add nothing at all`() {
        val options = PiLaunchOptions()
        assertEquals(emptyMap<String, String>(), options.environment())
        assertFalse(options.offline)
        assertFalse(options.longCacheRetention)
        assertFalse(options.noContextFiles)
        assertTrue(options.extensionFlags.isEmpty())
    }

    @Test
    fun `offline sets PI_OFFLINE to 1`() {
        assertEquals(mapOf("PI_OFFLINE" to "1"), PiLaunchOptions(offline = true).environment())
    }

    @Test
    fun `offline false omits the variable rather than setting it to zero`() {
        val env = PiLaunchOptions(offline = false, longCacheRetention = false).environment()
        assertFalse(env.containsKey("PI_OFFLINE"))
        assertFalse(env.containsKey("PI_CACHE_RETENTION"))
    }

    @Test
    fun `long cache retention uses pi's long value`() {
        assertEquals(
            mapOf("PI_CACHE_RETENTION" to "long"),
            PiLaunchOptions(longCacheRetention = true).environment(),
        )
    }

    @Test
    fun `no prompt field exists on PiLaunchOptions`() {
        val fields = PiLaunchOptions::class.java.declaredFields.map { it.name }
        assertFalse("PiLaunchOptions must not carry a prompt field: $fields", fields.contains("systemPrompt"))
        assertFalse("PiLaunchOptions must not carry a prompt field: $fields", fields.contains("appendSystemPrompt"))
    }

    @Test
    fun `fromSettingValues normalizes structured SDK Host open args and extension flags`() {
        val options = PiLaunchOptions.fromSettingValues(
            offline = true,
            cacheRetention = "long",
            noContextFiles = true,
            extensionArgs = "--plan --mode=fast",
            noExtensions = true,
            noSkills = true,
            noPromptTemplates = true,
            noThemes = true,
            resumeSessionPath = "/tmp/my sessions/a.jsonl",
        )
        assertEquals(
            mapOf("PI_OFFLINE" to "1", "PI_CACHE_RETENTION" to "long"),
            options.environment(),
        )
        assertTrue(options.noContextFiles)
        assertTrue(options.noExtensions)
        assertTrue(options.noSkills)
        assertTrue(options.noPromptTemplates)
        assertTrue(options.noThemes)
        assertEquals("/tmp/my sessions/a.jsonl", options.resumeSessionPath)
        assertEquals(
            listOf(ExtensionFlagArg("plan", null), ExtensionFlagArg("mode", "fast")),
            options.extensionFlags,
        )
    }
}
