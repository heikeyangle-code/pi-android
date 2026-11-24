package app.pi.ui.theme

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiThemeLoaderTest {

    @Test
    fun systemThemeHasNoVerboseNotes() {
        val dir = createTempDirectory("pi-theme-sys").toFile()
        try {
            val resolved = PiThemeLoader.load(
                agentDir = dir,
                workspace = dir,
                configured = emptyList(),
                setting = "system",
                systemDark = true,
            )
            assertEquals("system", resolved.name)
            assertTrue("system theme should have no verbose notes", resolved.notes.isEmpty())
            assertNull(resolved.error)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parsesBomCommunityThemeWithCssColorsAndExtraKeysSilently() {
        val dir = createTempDirectory("pi-theme-community").toFile()
        try {
            val themesDir = File(dir, "themes").apply { mkdirs() }
            val raw = "\uFEFF" + """
                {
                  "name": "catppuccin-test",
                  "author": "community",
                  "description": "extra top-level metadata that should be ignored silently",
                  "vars": {
                    "brand": " #89b4faFF ",
                    "green": "rgb(166, 227, 161)",
                    "yellow": "hsl(41, 86%, 83%)"
                  },
                  "colors": {
                    "accent": "brand",
                    "success": "green",
                    "warning": "yellow",
                    "text": "#cdd6f4",
                    "userMessageBg": "",
                    "selectedBg": "#313244",
                    "toolPendingBg": "#1e1e2e",
                    "error": 196,
                    "muted": "245",
                    "unknownCustomToken": "#ff00ff"
                  },
                  "export": {
                    "pageBg": "#11111b",
                    "extraExportKey": "#000000"
                  }
                }
            """.trimIndent()
            File(themesDir, "catppuccin-test.json").writeText(raw, Charsets.UTF_8)

            val resolved = PiThemeLoader.load(
                agentDir = dir,
                workspace = dir,
                configured = emptyList(),
                setting = "catppuccin-test",
                systemDark = false, // theme is dark; official detectAppearance should detect dark!
            )
            assertNull(resolved.error)
            assertTrue("third-party theme should produce zero verbose notes", resolved.notes.isEmpty())
            assertTrue("official detectAppearance should detect dark theme even when userMessageBg is empty", resolved.dark)
            assertEquals(0xFF89B4FA.toInt(), resolved.palette.accent.hashCode().let {
                val r = Math.round(resolved.palette.accent.red * 255f)
                val g = Math.round(resolved.palette.accent.green * 255f)
                val b = Math.round(resolved.palette.accent.blue * 255f)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            })
            assertEquals(166, Math.round(resolved.palette.success.red * 255f))
            assertEquals(227, Math.round(resolved.palette.success.green * 255f))
            assertEquals(161, Math.round(resolved.palette.success.blue * 255f))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importThemeJsonNormalizesToStrictOfficialSchema() {
        val dir = createTempDirectory("pi-theme-import").toFile()
        try {
            val raw = """
                {
                  "name": "Ocean / Breeze",
                  "extraTop": "ignored",
                  "colors": {
                    "accent": "rgb(56, 189, 248)",
                    "text": "#0f172a",
                    "userMessageBg": "#e2e8f0",
                    "selectedBg": "#cbd5e1",
                    "notAnOfficialToken": "#123456"
                  },
                  "export": {
                    "pageBg": "#f8fafc",
                    "extraExport": "#ffffff"
                  }
                }
            """.trimIndent()
            val result = PiThemeLoader.importThemeJson(raw, dir, overrideName = null, systemDark = true)
            assertTrue(result.isSuccess)
            val imported = result.getOrThrow()
            assertEquals("Ocean - Breeze", imported.name)
            assertFalse("light theme should be detected as light", imported.dark)
            assertTrue(imported.file.isFile)

            val parsed = Json.parseToJsonElement(imported.file.readText()) as JsonObject
            val allowedTop = setOf("\$schema", "name", "appearance", "colors", "export")
            assertEquals(allowedTop, parsed.keys)
            assertEquals("light", (parsed["appearance"] as JsonPrimitive).content)

            val colors = parsed["colors"] as JsonObject
            assertEquals(56, colors.size)
            assertFalse(colors.containsKey("notAnOfficialToken"))
            assertEquals("#38bdf8", (colors["accent"] as JsonPrimitive).content)

            val export = parsed["export"] as JsonObject
            assertEquals(setOf("pageBg", "cardBg", "infoBg"), export.keys)
            assertEquals("#f8fafc", (export["pageBg"] as JsonPrimitive).content)

            val discovered = PiThemeLoader.discover(dir, dir)
            assertEquals(listOf("system", "dark", "light"), discovered.take(3).map { it.name })
            val customEntry = discovered.firstOrNull { it.name == "Ocean - Breeze" }
            assertNotNull(customEntry)
            assertEquals(PiThemeScope.AgentDir, customEntry!!.scope)
            assertTrue(PiThemeLoader.deleteCustomTheme(customEntry))
            assertFalse(imported.file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
