package app.pi.ui.extension

import app.pi.host.HostRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostDialogMappingTest {

    private fun req(id: String, method: String, json: String): HostRequest =
        HostRequest(id = id, method = method, params = Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `maps ui select with options, ANSI title, and timeout`() {
        val dialog = req(
            id = "r1",
            method = "ui.select",
            json = """{"title":"\u001b[33mPick one\u001b[0m","options":["Yes","No",42],"timeout":30000}""",
        ).toExtensionDialog()!!
        assertEquals("r1", dialog.id)
        assertEquals(ExtensionDialogMethod.Select, dialog.method)
        assertEquals("\u001b[33mPick one\u001b[0m", dialog.title)
        assertEquals(listOf("Yes", "No"), dialog.options)
        assertEquals(30000L, dialog.timeoutMs)
        assertEquals(30000L, dialog.remainingMs)
    }

    @Test
    fun `maps ui confirm with message and null timeout`() {
        val dialog = req(
            id = "r2",
            method = "ui.confirm",
            json = """{"title":"Delete?","message":"Cannot be undone","timeout":null}""",
        ).toExtensionDialog()!!
        assertEquals(ExtensionDialogMethod.Confirm, dialog.method)
        assertEquals("Delete?", dialog.title)
        assertEquals("Cannot be undone", dialog.message)
        assertNull(dialog.timeoutMs)
        assertNull(dialog.remainingMs)
    }

    @Test
    fun `maps ui input with placeholder and timeout`() {
        val dialog = req(
            id = "r3",
            method = "ui.input",
            json = """{"title":"Token","placeholder":"paste here","timeout":15000}""",
        ).toExtensionDialog()!!
        assertEquals(ExtensionDialogMethod.Input, dialog.method)
        assertEquals("paste here", dialog.placeholder)
        assertEquals(15000L, dialog.timeoutMs)
    }

    @Test
    fun `maps ui editor with prefill and no timeout`() {
        val dialog = req(
            id = "r4",
            method = "ui.editor",
            json = """{"title":"Edit message","prefill":"line 1\nline 2"}""",
        ).toExtensionDialog()!!
        assertEquals(ExtensionDialogMethod.Editor, dialog.method)
        assertEquals("line 1\nline 2", dialog.prefill)
        assertNull(dialog.timeoutMs)
    }

    @Test
    fun `non-dialog host requests like auth prompt return null`() {
        assertNull(
            req(id = "r5", method = "auth.prompt", json = """{"message":"Enter code"}""").toExtensionDialog(),
        )
    }
}
