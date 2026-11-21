package app.pi.session.tree

import app.pi.protocol.EntryMeta
import app.pi.protocol.SessionEntry
import app.pi.protocol.SessionTreeNode
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeModelCycleTest {

    @Test
    fun selfParentAndMutualCycleNeverHangTreeModelView() {
        val selfNode = SessionTreeNode(
            entry = SessionEntry.Unknown("message", EntryMeta(id = "self", parentId = "self", timestamp = null), JsonObject(emptyMap())),
            children = emptyList(),
            label = null,
            labelTimestamp = null,
        )
        val a = SessionTreeNode(
            entry = SessionEntry.Unknown("message", EntryMeta(id = "a", parentId = "b", timestamp = null), JsonObject(emptyMap())),
            children = emptyList(),
            label = null,
            labelTimestamp = null,
        )
        val b = SessionTreeNode(
            entry = SessionEntry.Unknown("message", EntryMeta(id = "b", parentId = "a", timestamp = null), JsonObject(emptyMap())),
            children = emptyList(),
            label = null,
            labelTimestamp = null,
        )

        val view = TreeModel.view(
            roots = listOf(selfNode, a, b),
            leafId = "a",
            filter = TreeFilter.All,
            query = "",
            folded = emptySet(),
        )

        // Must terminate cleanly without infinite DFS loop or OOM.
        assertTrue(view.rows.size <= 3)
    }
}
