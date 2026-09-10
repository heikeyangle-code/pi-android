package app.pi.rpc

/**
 * A `/skill:name` expansion, exactly as pi rewrites it into the user message.
 *
 * pi does not persist a `skill` entry. `AgentSession._expandSkillCommand`
 * replaces the typed `/skill:name args` with
 *
 * ```
 * <skill name="name" location="/abs/SKILL.md">
 * References are relative to /abs.
 *
 * <body>
 * </skill>
 * ```
 *
 * followed by the trailing arguments when there are any, and sends that as an
 * ordinary user message (`core/agent-session.ts`). Its own TUI splits the text
 * back apart with `parseSkillBlock` and renders a skill card plus the trailing
 * user message (`modes/interactive/interactive-mode.ts`). A client that does not
 * do the same shows the literal XML and the whole skill body inside the user's
 * own bubble.
 */
data class PiSkillBlock(
    val name: String,
    val location: String,
    /** The SKILL.md body, frontmatter already stripped by pi. */
    val content: String,
    /** Text the user typed after `/skill:name`, if any. */
    val userMessage: String?,
)

/**
 * pi's own pattern, verbatim from `parseSkillBlock` in
 * `packages/coding-agent/src/core/agent-session.ts`.
 *
 * Deliberately anchored at both ends: the regex only matches a message that is
 * *entirely* the skill block (plus optional trailing arguments), so a user who
 * pastes a literal `<skill …>` document into the composer keeps their text
 * instead of having it silently reinterpreted as an expansion.
 */
private val SKILL_BLOCK = Regex(
    """^<skill name="([^"]+)" location="([^"]+)">\n([\s\S]*?)\n</skill>(?:\n\n([\s\S]+))?${'$'}""",
)

/**
 * Parse pi's skill block out of a user message.
 *
 * Returns null when [text] is not a skill block, which is the common case.
 * Total: never throws on arbitrary input.
 */
fun parsePiSkillBlock(text: String): PiSkillBlock? {
    val match = SKILL_BLOCK.matchEntire(text) ?: return null
    val name = match.groupValues[1]
    val location = match.groupValues[2]
    val content = match.groupValues[3]
    // Group 4 is the optional trailing user text; pi trims it and treats an
    // empty result as absent (`match[4]?.trim() || undefined`).
    val userMessage = match.groupValues[4].trim().takeIf { it.isNotEmpty() }
    return PiSkillBlock(
        name = name,
        location = location,
        content = content,
        userMessage = userMessage,
    )
}
