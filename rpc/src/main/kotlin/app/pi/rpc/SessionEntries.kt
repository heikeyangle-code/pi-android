package app.pi.rpc

import kotlinx.serialization.json.JsonObject

/**
 * The `id` / `parentId` / `timestamp` triple every session entry carries
 * (`docs/session-format.md` §"Entry Base").
 *
 * Kept as one value so each [SessionEntry] variant can expose the identity
 * fields through the interface without repeating three constructor parameters
 * nine times. All three are nullable because the reader must tolerate a
 * malformed payload rather than drop the entry — see the policy on
 * [PiResponses]. pi itself documents them as required.
 */
data class EntryMeta(
    val id: String?,
    val parentId: String?,
    val timestamp: String?,
)

/**
 * One entry of a session's append-only tree, as returned by `get_entries` and
 * embedded in `get_tree` nodes.
 *
 * Union transcribed from `src/core/session-manager.ts` (`SessionEntry`) and
 * `docs/session-format.md` §"Entry Types". The session header (`type:
 * "session"`) is deliberately not part of this union: pi excludes it from both
 * responses.
 */
sealed interface SessionEntry {

    /** pi's `type` discriminator, verbatim. */
    val type: String

    val meta: EntryMeta

    val id: String? get() = meta.id
    val parentId: String? get() = meta.parentId
    val timestamp: String? get() = meta.timestamp

    /** `SessionMessageEntry` — the `message` field holds a full [PiMessage]. */
    data class Message(
        override val meta: EntryMeta,
        val message: PiMessage,
    ) : SessionEntry {
        override val type = "message"
    }

    data class ModelChange(
        override val meta: EntryMeta,
        val provider: String?,
        val modelId: String?,
    ) : SessionEntry {
        override val type = "model_change"
    }

    data class ThinkingLevelChange(
        override val meta: EntryMeta,
        val thinkingLevel: String?,
    ) : SessionEntry {
        override val type = "thinking_level_change"
    }

    /**
     * `CompactionEntry`. Note this is *not* the `compact` RPC response: the
     * response additionally carries `estimatedTokensAfter`, which a stored
     * compaction entry does not have (`session-manager.ts` vs
     * `core/compaction/compaction.ts#CompactionResult`).
     */
    data class Compaction(
        override val meta: EntryMeta,
        val summary: String?,
        val firstKeptEntryId: String?,
        val tokensBefore: Long?,
        val usage: TokenUsage?,
        val details: JsonObject?,
        /** True when an extension generated this summary. */
        val fromHook: Boolean?,
    ) : SessionEntry {
        override val type = "compaction"
    }

    /** `BranchSummaryEntry` — summary of the branch that was abandoned. */
    data class BranchSummary(
        override val meta: EntryMeta,
        val fromId: String?,
        val summary: String?,
        val usage: TokenUsage?,
        val details: JsonObject?,
        val fromHook: Boolean?,
    ) : SessionEntry {
        override val type = "branch_summary"
    }

    /** Extension state. Does not participate in LLM context. */
    data class Custom(
        override val meta: EntryMeta,
        val customType: String?,
        val data: JsonObject?,
    ) : SessionEntry {
        override val type = "custom"
    }

    /** Extension-injected message. Does participate in LLM context. */
    data class CustomMessage(
        override val meta: EntryMeta,
        val customType: String?,
        val content: List<PiContentBlock>,
        val display: Boolean,
        val details: JsonObject?,
    ) : SessionEntry {
        override val type = "custom_message"
    }

    /** Bookmark on another entry; `label == null` means the label was cleared. */
    data class Label(
        override val meta: EntryMeta,
        val targetId: String?,
        val label: String?,
    ) : SessionEntry {
        override val type = "label"
    }

    /** Session metadata, currently only the display name. */
    data class SessionInfo(
        override val meta: EntryMeta,
        val name: String?,
    ) : SessionEntry {
        override val type = "session_info"
    }

    /** An entry type a newer pi introduced. Kept, never dropped. */
    data class Unknown(
        override val type: String,
        override val meta: EntryMeta,
        val raw: JsonObject,
    ) : SessionEntry
}

/**
 * One node of the session tree (`SessionTreeNode` in `session-manager.ts`).
 *
 * `label` / `labelTimestamp` come from the latest `label` entry targeting this
 * node, resolved by pi before it answers `get_tree`.
 */
data class SessionTreeNode(
    val entry: SessionEntry,
    val children: List<SessionTreeNode>,
    val label: String?,
    val labelTimestamp: String?,
)

// --------------------------------------------------------------------- parsing

/** Parse one session entry. Total: unknown types become [SessionEntry.Unknown]. */
fun parseSessionEntry(raw: JsonObject): SessionEntry {
    val meta = EntryMeta(
        id = raw.str("id"),
        parentId = raw.str("parentId"),
        timestamp = raw.str("timestamp"),
    )
    val type = raw.str("type").orEmpty()
    return when (type) {
        "message" -> {
            val message = raw.obj("message")
            if (message == null) {
                // A `message` entry without its message is malformed; keeping it
                // as Unknown preserves the id/parentId wiring for the tree.
                SessionEntry.Unknown(type, meta, raw)
            } else {
                SessionEntry.Message(meta, parsePiMessage(message))
            }
        }

        "model_change" -> SessionEntry.ModelChange(
            meta = meta,
            provider = raw.str("provider"),
            modelId = raw.str("modelId"),
        )

        "thinking_level_change" -> SessionEntry.ThinkingLevelChange(
            meta = meta,
            thinkingLevel = raw.str("thinkingLevel"),
        )

        "compaction" -> SessionEntry.Compaction(
            meta = meta,
            summary = raw.str("summary"),
            firstKeptEntryId = raw.str("firstKeptEntryId"),
            tokensBefore = raw.long("tokensBefore"),
            usage = raw.obj("usage")?.let(PiEvents::parseUsage),
            details = raw.obj("details"),
            fromHook = raw.bool("fromHook") ?: raw.bool("fromExtension"),
        )

        "branch_summary" -> SessionEntry.BranchSummary(
            meta = meta,
            fromId = raw.str("fromId"),
            summary = raw.str("summary"),
            usage = raw.obj("usage")?.let(PiEvents::parseUsage),
            details = raw.obj("details"),
            fromHook = raw.bool("fromHook") ?: raw.bool("fromExtension"),
        )

        "custom" -> SessionEntry.Custom(
            meta = meta,
            customType = raw.str("customType"),
            data = raw.obj("data"),
        )

        "custom_message" -> SessionEntry.CustomMessage(
            meta = meta,
            customType = raw.str("customType"),
            content = parseContentBlocks(raw["content"]),
            display = raw.bool("display") ?: false,
            details = raw.obj("details"),
        )

        "label" -> SessionEntry.Label(
            meta = meta,
            targetId = raw.str("targetId"),
            label = raw.str("label"),
        )

        "session_info" -> SessionEntry.SessionInfo(
            meta = meta,
            name = raw.str("name"),
        )

        else -> SessionEntry.Unknown(type, meta, raw)
    }
}

/**
 * Parse one tree node, recursing into `children`.
 *
 * Depth is bounded by pi's own tree, which is a sequence of entries; a
 * self-referential payload cannot occur because pi builds the tree from a
 * validated parent chain. A child entry that is not an object is skipped rather
 * than aborting the whole tree.
 */
fun parseSessionTreeNode(raw: JsonObject): SessionTreeNode {
    val entry = raw.obj("entry")?.let { parseSessionEntry(it) }
        ?: SessionEntry.Unknown("", EntryMeta(null, null, null), raw)
    val children = (raw["children"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.map { parseSessionTreeNode(it) }
        ?: emptyList()
    return SessionTreeNode(
        entry = entry,
        children = children,
        label = raw.str("label"),
        labelTimestamp = raw.str("labelTimestamp"),
    )
}
