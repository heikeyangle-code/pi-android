package app.pi.ui.chat.activity

/**
 * Isolated setting definition for transcript tool & process presentation mode.
 *
 * Kept inside `ui/chat/activity/` so the entire Compact Trace feature stays strictly decoupled
 * from `TranscriptReducer` and classic tool cards and can be removed cleanly if ever desired.
 */
object ActivityDisplayMode {
    const val SETTING_KEY: String = "app.appearance.toolDisplayMode"

    /** Default: every tool renders as its own official-parity card. */
    const val CARDS: String = "cards"

    /** Compact Trace: consecutive thinking + tool steps fold into one calm summary row so prose leads. */
    const val COMPACT: String = "compact"

    fun normalize(raw: String?): String = when (raw) {
        COMPACT -> COMPACT
        else -> CARDS
    }

    fun isCompact(raw: String?): Boolean = normalize(raw) == COMPACT
}
