package app.pi.ui.extension

import androidx.compose.ui.Alignment
import app.pi.host.ExtUiReducer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionSurfaceRulesTest {

    @Test
    fun `reducer parses supportsMouse, hasTextCursor, and overlayOptions on custom surface`() {
        val reducer = ExtUiReducer()
        val payload = Json.parseToJsonElement(
            """
            {
              "surfaceId": "s-chess",
              "mode": "custom",
              "rev": 1,
              "lines": ["Chess", "Your turn"],
              "overlay": true,
              "interactive": true,
              "supportsMouse": true,
              "hasTextCursor": true,
              "overlayOptions": {
                "width": "80%",
                "minWidth": 56,
                "maxHeight": 24,
                "anchor": "center",
                "nonCapturing": false
              }
            }
            """.trimIndent(),
        ).jsonObject

        val next = reducer.apply("ui.surface", payload)
        assertNotNull(next)
        val custom = next!!.custom
        assertNotNull(custom)
        assertTrue(custom!!.supportsMouse)
        assertTrue(custom.hasTextCursor)
        assertEquals("80%", custom.overlayOptions?.widthSpec)
        assertEquals(56, custom.overlayOptions?.minWidth)
        assertEquals("24", custom.overlayOptions?.maxHeightSpec)
        assertEquals("center", custom.overlayOptions?.anchor)
        assertFalse(custom.nonCapturing)
    }

    @Test
    fun `reducer distinguishes explicit empty working frames from null default`() {
        val reducer = ExtUiReducer()
        assertNull(reducer.state.working.frames)

        val emptyFrames = Json.parseToJsonElement("""{"frames": []}""").jsonObject
        val updated = reducer.apply("ui.workingIndicator", emptyFrames)
        assertNotNull(updated)
        assertEquals(emptyList<String>(), updated!!.working.frames)
    }

    @Test
    fun `extractSurfaceShortcuts extracts pipe legend keys and skips gamepad navigation keys`() {
        val rows = listOf(
            "  ♞ Chess - You: White vs Agent: Black",
            "  a b c d e f g h",
            " ↑↓←→ move  |  ENTER select  |  F flip  |  N new  |  G games  |  Q/ESC quit ",
        )
        val shortcuts = extractSurfaceShortcuts(rows)
        assertEquals(listOf("f", "n", "g", "q"), shortcuts.map { it.sendData })
        assertEquals(listOf("F", "N", "G", "Q"), shortcuts.map { it.keyLabel })
        assertEquals(listOf("flip", "new", "games", "quit"), shortcuts.map { it.description })
    }

    @Test
    fun `extractSurfaceShortcuts parses bracket shortcuts and respects maxCount`() {
        val rows = listOf(
            " [r] restart   [h] hint   [u] undo   [s] save   [?] help ",
        )
        val shortcuts = extractSurfaceShortcuts(rows, maxCount = 3)
        assertEquals(listOf("r", "h", "u"), shortcuts.map { it.sendData })
    }

    @Test
    fun `terminalCharWidth matches terminal cell geometry for ASCII, chess, CJK, and combining marks`() {
        assertEquals(1, terminalCharWidth('A'.code))
        assertEquals(1, terminalCharWidth(0x2500)) // box drawing horizontal
        assertEquals(1, terminalCharWidth(0x2654)) // white chess king
        assertEquals(2, terminalCharWidth('中'.code))
        assertEquals(2, terminalCharWidth(0x1F600)) // grinning face emoji
        assertEquals(0, terminalCharWidth(0x0301)) // combining acute accent
        assertEquals(2, terminalCharWidth(0x23F3)) // ⏳ hourglass emoji presentation
        assertEquals(2, terminalCharWidth(0x2705)) // ✅ check mark emoji
        assertEquals(2, terminalCharWidth(0x26A1)) // ⚡ high voltage emoji
        assertEquals(2, visibleColumns("⚠️")) // U+26A0 + FE0F promoted to 2 cols
    }

    @Test
    fun `overlayAnchorAlignment maps official Pi OverlayAnchor values cleanly`() {
        assertEquals(Alignment.TopStart, overlayAnchorAlignment("top-left"))
        assertEquals(Alignment.TopCenter, overlayAnchorAlignment("top-center"))
        assertEquals(Alignment.TopEnd, overlayAnchorAlignment("top-right"))
        assertEquals(Alignment.Center, overlayAnchorAlignment("center"))
        assertEquals(Alignment.BottomStart, overlayAnchorAlignment("bottom-left"))
        assertEquals(Alignment.BottomEnd, overlayAnchorAlignment("bottom-right"))
        assertEquals(Alignment.BottomEnd, overlayAnchorAlignment(null))
    }

    @Test
    fun `isGeometricTerminalGlyph covers box drawing and block elements only`() {
        assertTrue(isGeometricTerminalGlyph('─'.code))
        assertTrue(isGeometricTerminalGlyph('│'.code))
        assertTrue(isGeometricTerminalGlyph('╭'.code))
        assertTrue(isGeometricTerminalGlyph('═'.code))
        assertTrue(isGeometricTerminalGlyph('█'.code))
        assertTrue(isGeometricTerminalGlyph('░'.code))
        assertTrue(isGeometricTerminalGlyph('▒'.code))
        assertTrue(isGeometricTerminalGlyph('▀'.code))
        assertFalse(isGeometricTerminalGlyph('A'.code))
        assertFalse(isGeometricTerminalGlyph('.'.code))
        assertFalse(isGeometricTerminalGlyph('♔'.code))
    }

    @Test
    fun `inferSurfaceTitle skips dot-grid board cells and picks clean title`() {
        val tetrisLines = listOf(
            "        ┌──────────────────────┐",
            "        │ . . . . . . . . . .  │    TETRIS",
            "        │ . . . . . . . . . .  │    Score 7",
        )
        assertEquals("TETRIS", inferSurfaceTitle(tetrisLines))
        assertEquals("Chess", inferSurfaceTitle(listOf("╭──── Chess ────╮")))
        assertNull(inferSurfaceTitle(listOf("┌──────────┐", "│ . . . .  │")))
        assertEquals(
            "BTW · local · hidden thread preserved",
            inferSurfaceTitle(listOf("┌────────────────────────────────────────┐", "│ BTW · local · hidden thread preserved │", "│ 1 exchange · streaming · ↑462 ↓0      │")),
        )
    }

    @Test
    fun `extractSurfaceShortcuts extracts vertical Controls legend without false positives`() {
        val tetrisRows = listOf(
            "        │ . . . . . . . . . .  │    Controls",
            "        │ . . . . . . . . . .  │      ← →   Move",
            "        │ . . . . . . . . . .  │      ↑     Rotate",
            "        │ . . . . . . . . . .  │      Spc   Hard drop",
            "        │ . . . . . . . . . .  │      ESC   Pause",
            "        │ . . . . . . . . . .  │      Q     Quit",
            "        │ . . . . . . . . . .  │      R     Restart",
        )
        val shortcuts = extractSurfaceShortcuts(tetrisRows)
        assertEquals(listOf("q", "r"), shortcuts.map { it.sendData })
        assertEquals(listOf("Quit", "Restart"), shortcuts.map { it.description })
    }
}
