package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins pi's pre-spawn spellings (`docs/gap-disposition.md` rows #11, #12, #64,
 * #65, #66 — the `I11` family): `PI_OFFLINE`, `PI_CACHE_RETENTION=long`,
 * `--system-prompt`, `--append-system-prompt`.
 *
 * The quoting test is the load-bearing one: the flags end up inside a
 * `bash -lc` string, so a bare system prompt would be word-split by bash.
 */
class PiLaunchOptionsTest {

    @Test
    fun `defaults add nothing at all`() {
        val options = PiLaunchOptions()
        assertEquals(emptyMap<String, String>(), options.environment())
        assertEquals("", options.commandLineSuffix())
    }

    @Test
    fun `offline sets PI_OFFLINE to 1`() {
        assertEquals(mapOf("PI_OFFLINE" to "1"), PiLaunchOptions(offline = true).environment())
    }

    @Test
    fun `offline false omits the variable rather than setting it to zero`() {
        // core/model-runtime.ts passes `process.env.PI_OFFLINE === undefined` as
        // "model network enabled", so `PI_OFFLINE=0` would still disable it.
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
    fun `system prompt flags match pi's argument parser`() {
        // src/cli/args.ts: `--system-prompt` and `--append-system-prompt`.
        assertEquals(
            " --system-prompt 'You are pi.'",
            PiLaunchOptions(systemPrompt = "You are pi.").commandLineSuffix(),
        )
        assertEquals(
            " --append-system-prompt 'Answer briefly.'",
            PiLaunchOptions(appendSystemPrompt = "Answer briefly.").commandLineSuffix(),
        )
        assertEquals(
            " --system-prompt 'base' --append-system-prompt 'extra'",
            PiLaunchOptions(systemPrompt = "base", appendSystemPrompt = "extra").commandLineSuffix(),
        )
    }

    @Test
    fun `flag values are shell quoted so bash cannot word split them`() {
        assertEquals(
            " --system-prompt 'be terse and kind'",
            PiLaunchOptions(systemPrompt = "be terse and kind").commandLineSuffix(),
        )
        // An embedded single quote is closed, escaped and reopened.
        assertEquals(
            " --system-prompt 'it'\\''s here'",
            PiLaunchOptions(systemPrompt = "it's here").commandLineSuffix(),
        )
        // Shell metacharacters stay inert inside the quotes.
        assertEquals(
            " --append-system-prompt 'a; rm -rf / \$(x)'",
            PiLaunchOptions(appendSystemPrompt = "a; rm -rf / \$(x)").commandLineSuffix(),
        )
    }

    @Test
    fun `blank prompts are ignored instead of sending an empty flag`() {
        val options = PiLaunchOptions(systemPrompt = "   ", appendSystemPrompt = "")
        assertEquals("", options.commandLineSuffix())
    }

    @Test
    fun `every option composes into one suffix and one env`() {
        val options = PiLaunchOptions(
            offline = true,
            longCacheRetention = true,
            systemPrompt = "base",
            appendSystemPrompt = "extra",
        )
        assertEquals(
            mapOf("PI_OFFLINE" to "1", "PI_CACHE_RETENTION" to "long"),
            options.environment(),
        )
        assertTrue(options.commandLineSuffix().startsWith(" --system-prompt 'base'"))
        assertTrue(options.commandLineSuffix().endsWith("--append-system-prompt 'extra'"))
    }
}
