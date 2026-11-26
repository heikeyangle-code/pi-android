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
    fun defaultThemeFollowsSystemDarkWithStudioPalettes() {
        val dir = createTempDirectory("pi-theme-default").toFile()
        try {
            assertEquals("light/dark", PiThemeLoader.defaultSetting(systemDark = true))
            assertEquals("light/dark", PiThemeLoader.defaultSetting(systemDark = false))

            val darkResolved = PiThemeLoader.load(dir, dir, emptyList(), setting = null, systemDark = true)
            assertEquals("dark", darkResolved.name)
            assertTrue(darkResolved.dark)
            assertEquals(PiPalette.Dark, darkResolved.palette)
            assertNull(darkResolved.error)

            val lightResolved = PiThemeLoader.load(dir, dir, emptyList(), setting = "", systemDark = false)
            assertEquals("light", lightResolved.name)
            assertFalse(lightResolved.dark)
            assertEquals(PiPalette.Light, lightResolved.palette)
            assertNull(lightResolved.error)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun systemThemeFollowsSystemDarkAndHasNoVerboseNotes() {
        val dir = createTempDirectory("pi-theme-sys").toFile()
        try {
            val darkSys = PiThemeLoader.load(dir, dir, emptyList(), setting = "system", systemDark = true)
            assertEquals("system", darkSys.name)
            assertTrue("system theme should be dark when systemDark=true", darkSys.dark)
            assertTrue("system theme should have no verbose notes", darkSys.notes.isEmpty())
            assertNull(darkSys.error)

            val lightSys = PiThemeLoader.load(dir, dir, emptyList(), setting = "system", systemDark = false)
            assertEquals("system", lightSys.name)
            assertFalse("system theme should be light when systemDark=false", lightSys.dark)
            assertTrue("system theme should have no verbose notes", lightSys.notes.isEmpty())
            assertNull(lightSys.error)
            assertTrue(
                "light system pageBg should be brighter than dark system pageBg",
                lightSys.palette.pageBg.red > darkSys.palette.pageBg.red,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun parsesBomCommunityThemeWithCssAndOkColorsSilently() {
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
                    "yellow": "hsl(41, 86%, 83%)",
                    "oklchWhite": "oklch(100% 0.3 150)",
                    "okhslAccent": "okhsl(220 80% 65%)"
                  },
                  "colors": {
                    "accent": "brand",
                    "borderAccent": "okhslAccent",
                    "mdHeading": "oklchWhite",
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
            assertEquals(0xFF89B4FA.toInt(), resolved.palette.accent.let { c ->
                val r = Math.round(c.red * 255f)
                val g = Math.round(c.green * 255f)
                val b = Math.round(c.blue * 255f)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            })
            assertEquals(255, Math.round(resolved.palette.mdHeading.red * 255f))
            assertEquals(255, Math.round(resolved.palette.mdHeading.green * 255f))
            assertEquals(255, Math.round(resolved.palette.mdHeading.blue * 255f))
            assertEquals(166, Math.round(resolved.palette.success.red * 255f))
            assertEquals(227, Math.round(resolved.palette.success.green * 255f))
            assertEquals(161, Math.round(resolved.palette.success.blue * 255f))
            // Unified PiOkhsl.okhslToRgb round-trip matches resolved borderAccent
            val expectedBorder = okhslToRgb(220.0, 0.80, 0.65)
            assertEquals(expectedBorder.r, Math.round(resolved.palette.borderAccent.red * 255f))
            assertEquals(expectedBorder.g, Math.round(resolved.palette.borderAccent.green * 255f))
            assertEquals(expectedBorder.b, Math.round(resolved.palette.borderAccent.blue * 255f))
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
            assertEquals(listOf("dark", "light", "system"), discovered.take(3).map { it.name })
            val customEntry = discovered.firstOrNull { it.name == "Ocean - Breeze" }
            assertNotNull(customEntry)
            assertEquals(PiThemeScope.AgentDir, customEntry!!.scope)
            assertTrue(PiThemeLoader.deleteCustomTheme(customEntry))
            assertFalse(imported.file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun terminalPaletteDerivesAnsiFromActiveTheme() {
        val base = app.pi.ui.terminal.TerminalPalette.of(PiPalette.Dark)
        val custom = app.pi.ui.terminal.TerminalPalette.of(
            PiPalette.Dark.copy(
                error = androidx.compose.ui.graphics.Color(0xFFFF5555),
                success = androidx.compose.ui.graphics.Color(0xFF50FA7B),
                mdLink = androidx.compose.ui.graphics.Color(0xFF8BE9FD),
            ),
        )
        assertEquals(16, custom.ansi16.size)
        assertFalse(
            "custom theme ANSI red should differ from default dark red",
            base.ansi16[1] == custom.ansi16[1],
        )
        assertFalse(
            "custom theme ANSI green should differ from default dark green",
            base.ansi16[2] == custom.ansi16[2],
        )
        assertFalse(
            "custom theme ANSI blue should differ from default dark blue",
            base.ansi16[4] == custom.ansi16[4],
        )
    }

    @Test
    fun themeStudioSynthesizesWcagCompliantThemesAndSavesOfficialJson() {
        val dir = createTempDirectory("pi-theme-studio").toFile()
        try {
            fun contrastOf(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
                fun toRgb(c: androidx.compose.ui.graphics.Color) = Rgb(
                    r = Math.round(c.red * 255f),
                    g = Math.round(c.green * 255f),
                    b = Math.round(c.blue * 255f),
                )
                return wcagContrast(toRgb(a), toRgb(b))
            }

            // Sweep every curated canvas × curated accent × mood + full 0..360° hue wheel
            for (canvas in PiThemeStudio.allCanvases) {
                for (mood in PiThemeMood.entries) {
                    for (accent in PiThemeStudio.accents) {
                        val p = PiThemeStudio.synthesize(canvas, accent.hue, accent.monochrome, mood)
                        assertTrue("text on pageBg >= 10:1", contrastOf(p.text, p.pageBg) >= 10.0)
                        assertTrue("accent on pageBg >= 4.5:1", contrastOf(p.accent, p.pageBg) >= 4.5)
                        assertTrue("accent on cardBg >= 4.5:1", contrastOf(p.accent, p.cardBg) >= 4.5)
                        assertTrue("toolDiffAdded on toolPendingBg >= 4.5:1", contrastOf(p.toolDiffAdded, p.toolPendingBg) >= 4.5)
                        assertTrue("toolDiffRemoved on toolPendingBg >= 4.5:1", contrastOf(p.toolDiffRemoved, p.toolPendingBg) >= 4.5)
                        assertTrue("searchMatchText on searchMatchBg >= 4.5:1", contrastOf(p.searchMatchText, p.searchMatchBg) >= 4.5)
                        assertTrue("mdCodeBlock on cardBg >= 9:1", contrastOf(p.mdCodeBlock, p.cardBg) >= 9.0)
                    }
                    for (deg in 0 until 360 step 45) {
                        val p = PiThemeStudio.synthesize(canvas, deg.toDouble(), false, mood)
                        assertTrue("hue $deg accent on pageBg >= 4.5:1", contrastOf(p.accent, p.pageBg) >= 4.5)
                    }
                }
            }

            // Verify all 6 dual-mode iconic master presets (Claude, Flexoki, Rosé Pine, Catppuccin, Tokyo Night, Pure Mono)
            for (preset in PiThemeStudio.masterPresets) {
                for (dark in listOf(true, false)) {
                    val c = preset.canvasFor(dark)
                    val p = PiThemeStudio.synthesize(c, preset.accentHue, preset.monochrome, preset.mood)
                    assertTrue("${preset.nameFor(dark)} text >= 10:1", contrastOf(p.text, p.pageBg) >= 10.0)
                    assertTrue("${preset.nameFor(dark)} accent >= 4.5:1", contrastOf(p.accent, p.pageBg) >= 4.5)
                    assertTrue("${preset.nameFor(dark)} diffAdded >= 4.5:1", contrastOf(p.toolDiffAdded, p.toolPendingBg) >= 4.5)
                    assertTrue("${preset.nameFor(dark)} mdCodeBlock >= 9:1", contrastOf(p.mdCodeBlock, p.cardBg) >= 9.0)
                }
            }

            // Persist Claude Light preset and verify strict official Pi 1.6.0 schema round-trip
            val claude = PiThemeStudio.masterPresets.first { it.id == "claude" }
            val palette = PiThemeStudio.synthesize(claude.lightCanvas, claude.accentHue, claude.monochrome, claude.mood)
            val saved = PiThemeLoader.saveCustomTheme(claude.lightName, dark = false, palette = palette, agentDir = dir).getOrThrow()
            assertTrue(saved.file.isFile)
            val parsed = Json.parseToJsonElement(saved.file.readText()) as JsonObject
            assertEquals("light", (parsed["appearance"] as JsonPrimitive).content)
            assertEquals(56, (parsed["colors"] as JsonObject).size)
            assertEquals(setOf("pageBg", "cardBg", "infoBg"), (parsed["export"] as JsonObject).keys)

            val reloaded = PiThemeLoader.load(dir, dir, emptyList(), setting = claude.lightName, systemDark = true)
            assertNull(reloaded.error)
            assertEquals(claude.lightName, reloaded.name)
            assertFalse("Claude Light studio theme should reload as light", reloaded.dark)
            assertEquals(palette.accent, reloaded.palette.accent)
            assertEquals(palette.pageBg, reloaded.palette.pageBg)
        } finally {
            dir.deleteRecursively()
        }
    }
}
