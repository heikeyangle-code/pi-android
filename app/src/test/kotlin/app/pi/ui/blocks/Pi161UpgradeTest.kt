package app.pi.ui.blocks

import app.pi.host.HostRequest
import app.pi.ui.extension.ExtensionDialogMethod
import app.pi.ui.extension.toExtensionDialog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pi161UpgradeTest {

    @Test
    fun `fullOutputErrorOf parses Pi 1_6_1 error details and ignores blanks`() {
        val details = JsonObject(mapOf("fullOutputError" to JsonPrimitive("  ENOSPC: disk full  ")))
        assertEquals("ENOSPC: disk full", fullOutputErrorOf(details))

        val blank = JsonObject(mapOf("fullOutputError" to JsonPrimitive("   ")))
        assertNull(fullOutputErrorOf(blank))
        assertNull(fullOutputErrorOf(null))
    }

    @Test
    fun `stripFullOutputFooter strips bracket footer matching fullOutputError`() {
        val err = "ENOSPC: disk full"
        val raw = "line1\nline2\n\n[Could not save the full output: $err. Truncated: showing 2 of 500 lines]"
        assertEquals("line1\nline2", stripFullOutputFooter(raw, path = null, error = err))
        // Does not strip if marker is absent from footer
        assertEquals(raw, stripFullOutputFooter(raw, path = null, error = "different error"))
    }

    @Test
    fun `truncationNotice shows fullOutputError when fullOutputPath is null`() {
        val notice = truncationNotice(
            path = null,
            truncation = ToolTruncation(truncatedBy = "lines", outputLines = 10, totalLines = 100, maxBytes = null),
            error = "EACCES: permission denied",
        )!!
        assertTrue(notice.contains("EACCES: permission denied"))
        assertTrue(notice.contains("10"))
        assertTrue(notice.contains("100"))
    }

    @Test
    fun `toExtensionDialog parses Pi 1_6_1 blockedKind`() {
        val req = HostRequest(
            id = "req-1",
            method = "ui.select",
            params = JsonObject(
                mapOf(
                    "title" to JsonPrimitive("Trust this project?"),
                    "options" to JsonArray(listOf(JsonPrimitive("Allow"), JsonPrimitive("Deny"))),
                    "blockedKind" to JsonPrimitive("permission"),
                ),
            ),
        )
        val dialog = req.toExtensionDialog()!!
        assertEquals(ExtensionDialogMethod.Select, dialog.method)
        assertEquals("permission", dialog.blockedKind)
    }
}
