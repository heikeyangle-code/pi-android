package app.pi.ui.chat.activity

import app.pi.protocol.AssistantText
import app.pi.protocol.PiImage
import app.pi.protocol.ThinkingBlock
import app.pi.protocol.ToolCall
import app.pi.protocol.ToolDiff
import app.pi.protocol.ToolStatus
import app.pi.protocol.UserMessage
import app.pi.ui.blocks.shortenToolPathForPhone
import app.pi.ui.render.TranscriptGap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityGroupProjectorTest {

    @Test
    fun `pairEditDiffs pairs matching edit ToolDiff and leaves standalone diffs untouched`() {
        val editCall = ToolCall(
            key = "tc_edit",
            ts = 100L,
            toolCallId = "tc_edit",
            toolName = "edit",
            status = ToolStatus.Success,
        )
        val editDiff = ToolDiff(
            key = "tc_edit-diff",
            ts = 120L,
            path = "src/Foo.kt",
            diffText = "@@ -1 +1 @@\n-a\n+b",
            added = 1,
            removed = 1,
            toolCallId = "tc_edit",
            toolName = "edit",
        )
        val otherDiff = ToolDiff(
            key = "tc_custom-diff",
            ts = 130L,
            path = "src/Bar.kt",
            diffText = "@@ -1 +1 @@\n-x\n+y",
            added = 1,
            removed = 1,
            toolCallId = "tc_custom",
            toolName = "patch",
        )

        val paired = pairEditDiffs(listOf(editCall, editDiff, otherDiff))
        assertEquals(1, paired.size)
        assertEquals(editDiff, paired["tc_edit"])
        assertNull(paired["tc_custom"])
    }

    @Test
    fun `shortenToolPathForPhone preserves tail segments on long paths and keeps short paths verbatim`() {
        assertEquals("src/Main.kt", shortenToolPathForPhone("src/Main.kt"))
        assertEquals(
            "…/blocks/EditBlock.kt",
            shortenToolPathForPhone("app/src/main/kotlin/app/pi/ui/blocks/EditBlock.kt"),
        )
        assertEquals(
            "…/VeryLongComponentFilenameThatExceedsLimit.kt",
            shortenToolPathForPhone("app/src/main/kotlin/a/b/VeryLongComponentFilenameThatExceedsLimit.kt"),
        )
    }

    @Test
    fun `lone thinking block stays Single while thinking plus tools collapses with stable key`() {
        val user = UserMessage(key = "u1", ts = 1L, text = "Check EditBlock")
        val thinking = ThinkingBlock(key = "th1", ts = 2L, text = "Let me check", streaming = false, elapsedMs = 1_500L)
        val answer = AssistantText(key = "a1", ts = 3L, text = "Done!", streaming = false)

        val loneRows = ActivityGroupProjector.project(listOf(user, thinking, answer))
        assertEquals(3, loneRows.size)
        assertTrue(loneRows[1] is ChatRow.Single)

        val readCall = ToolCall(
            key = "tc1",
            ts = 10L,
            toolCallId = "tc1",
            toolName = "read",
            args = buildJsonObject { put("path", "app/src/EditBlock.kt") },
            status = ToolStatus.Success,
            durationMs = 80L,
        )
        val editCall = ToolCall(
            key = "tc2",
            ts = 20L,
            toolCallId = "tc2",
            toolName = "edit",
            args = buildJsonObject { put("file_path", "app/src/EditBlock.kt") },
            status = ToolStatus.Success,
            durationMs = 120L,
        )
        val editDiff = ToolDiff(
            key = "tc2-diff",
            ts = 21L,
            path = "app/src/EditBlock.kt",
            diffText = "@@ -1 +1 @@\n-old\n+new",
            added = 12,
            removed = 4,
            toolCallId = "tc2",
            toolName = "edit",
        )
        val paired = pairEditDiffs(listOf(readCall, editCall, editDiff))

        val grouped = ActivityGroupProjector.project(
            items = listOf(user, thinking, readCall, editCall, answer),
            pairedDiffByCallId = paired,
        )
        assertEquals(3, grouped.size)
        assertTrue(grouped[0] is ChatRow.Single)
        val group = grouped[1] as ChatRow.ActivityGroup
        assertEquals("activity-group:th1", group.key)
        assertEquals(3, group.items.size)
        assertEquals(1, group.summary.thinkingCount)
        assertEquals(1, group.summary.readCount)
        assertEquals(1, group.summary.editCount)
        assertEquals(12, group.summary.totalAdded)
        assertEquals(4, group.summary.totalRemoved)
        assertEquals(1_700L, group.summary.totalElapsedMs)
        assertFalse(group.summary.isRunning)
        assertEquals(TranscriptGap.AssistantMessage, chatRowGap(grouped, 1))
        assertEquals(TranscriptGap.SameMessage, chatRowGap(grouped, 2))
    }

    @Test
    fun `running step, error snippet, hidden thinking, and images project accurately`() {
        val img = PiImage(base64 = "AAAA", mimeType = "image/png")
        val thinking = ThinkingBlock(key = "th1", ts = 1L, text = "plan", streaming = false)
        val failed = ToolCall(
            key = "tc1",
            ts = 2L,
            toolCallId = "tc1",
            toolName = "bash",
            args = buildJsonObject { put("command", "./gradlew test") },
            status = ToolStatus.Error,
            output = "Compilation error at line 42\nMore details",
            isError = true,
            images = listOf(img),
        )
        val pending = ToolCall(
            key = "tc2",
            ts = 3L,
            toolCallId = "tc2",
            toolName = "read",
            args = buildJsonObject { put("path", "src/ui/EditBlock.kt") },
            status = ToolStatus.Pending,
        )

        val rows = ActivityGroupProjector.project(
            items = listOf(thinking, failed, pending),
            hideThinking = true,
        )
        assertEquals(1, rows.size)
        val group = rows[0] as ChatRow.ActivityGroup
        assertEquals("activity-group:tc1", group.key)
        assertEquals(0, group.summary.thinkingCount)
        assertEquals(1, group.summary.failedCount)
        assertEquals("Compilation error at line 42", group.summary.firstErrorSnippet)
        assertTrue(group.summary.isRunning)
        assertTrue(group.summary.hasPendingTool)
        assertEquals(ActiveStepKind.Read, group.summary.activeStepKind)
        assertEquals("EditBlock.kt", group.summary.activeTarget)
        assertEquals(1, group.summary.images.size)
    }

    @Test
    fun `ActivityDisplayMode normalizes unknown values to cards`() {
        assertEquals(ActivityDisplayMode.CARDS, ActivityDisplayMode.normalize(null))
        assertEquals(ActivityDisplayMode.CARDS, ActivityDisplayMode.normalize("unknown"))
        assertEquals(ActivityDisplayMode.COMPACT, ActivityDisplayMode.normalize("compact"))
        assertFalse(ActivityDisplayMode.isCompact(null))
        assertFalse(ActivityDisplayMode.isCompact("cards"))
        assertTrue(ActivityDisplayMode.isCompact("compact"))
    }
}
