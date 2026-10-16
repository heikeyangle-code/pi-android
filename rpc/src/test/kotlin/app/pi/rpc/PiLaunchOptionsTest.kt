package app.pi.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins pi's pre-spawn spellings (`docs/gap-disposition.md` rows #11, #12, #64,
 * #65, #66 — the `I11` family): `PI_OFFLINE`, `PI_CACHE_RETENTION=long`, and the
 * session flags.
 *
 * **`--system-prompt` / `--append-system-prompt` are the rows this file used to
 * pin, and they are gone here on purpose.** pi reads those two values from
 * `<agentDir>/SYSTEM.md` / `APPEND_SYSTEM.md`, and a flag on the command line
 * short-circuits that discovery (`dist/core/resource-loader.js:475`), which is how a
 * file the user wrote went dead while the old setting was non-empty. The settings
 * rows edit the files now (`app.pi.ui.PromptFileSettingsStore`), so the assertion
 * that used to check the flags were *correctly emitted* is now the opposite one:
 * **neither flag may ever appear**, and the fields that produced them must not come
 * back (a re-added field would silently re-shadow the file again).
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
    fun `no prompt flag is emitted, and no field can make one appear`() {
        // The structural half: `systemPrompt` / `appendSystemPrompt` were fields of this
        // data class. Their absence is what makes "the file wins" true, so it is asserted
        // on the class itself rather than only on one built suffix.
        val fields = PiLaunchOptions::class.java.declaredFields.map { it.name }
        assertFalse("PiLaunchOptions must not carry a prompt field again: $fields", fields.contains("systemPrompt"))
        assertFalse(
            "PiLaunchOptions must not carry a prompt field again: $fields",
            fields.contains("appendSystemPrompt"),
        )

        // The behavioural half: with every other knob on, neither flag is on the command
        // line. `--append-system-prompt` does not contain `--system-prompt`, so both checks
        // are needed.
        val everything = PiLaunchOptions(
            offline = true,
            longCacheRetention = true,
            noContextFiles = true,
            noExtensions = true,
            noSkills = true,
            noPromptTemplates = true,
            noThemes = true,
            extensionFlags = ExtensionFlagArgs.parse("--plan").flags,
            continueSessionId = "01932f4a-0000-7000-8000-abcdefabcdef",
        ).commandLineSuffix()
        assertFalse(everything, everything.contains("--system-prompt"))
        assertFalse(everything, everything.contains("--append-system-prompt"))
    }

    @Test
    fun `the normaliser emits no prompt flag either`() {
        // `fromSettingValues` is the one place the settings document becomes process
        // inputs. It no longer takes the two prompt values at all; the call below passes
        // everything it still accepts.
        val options = PiLaunchOptions.fromSettingValues(
            offline = null,
            cacheRetention = null,
            noContextFiles = null,
            extensionArgs = "--plan",
            noExtensions = true,
            noSkills = true,
            noPromptTemplates = true,
            noThemes = true,
        )
        assertEquals(
            " --no-extensions --no-skills --no-prompt-templates --no-themes --plan",
            options.commandLineSuffix(),
        )
    }

    @Test
    fun `the suffix is still shell quoted where a value can contain a space`() {
        // The property the prompt flags' quoting test used to guard, kept on the flag that
        // still carries a caller-supplied string: `--session <path>` goes out as two tokens,
        // so a path with a space must stay one argument for bash. (The extension flags'
        // values are quoted the same way and pinned by the `extension-flags` harness.)
        assertEquals(
            " --session '/tmp/my sessions/a.jsonl'",
            PiLaunchOptions(resumeSessionPath = "/tmp/my sessions/a.jsonl").commandLineSuffix(),
        )
    }

    @Test
    fun `every option composes into one suffix and one env`() {
        val options = PiLaunchOptions(
            offline = true,
            longCacheRetention = true,
            noExtensions = true,
            extensionFlags = ExtensionFlagArgs.parse("--plan").flags,
        )
        assertEquals(
            mapOf("PI_OFFLINE" to "1", "PI_CACHE_RETENTION" to "long"),
            options.environment(),
        )
        assertTrue(options.commandLineSuffix().startsWith(" --no-extensions"))
        assertTrue(options.commandLineSuffix().endsWith(" --plan"))
    }
}
