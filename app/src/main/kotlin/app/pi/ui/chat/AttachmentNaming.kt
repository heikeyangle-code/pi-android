package app.pi.ui.chat

/**
 * Naming rules for a picked file that is copied into the workspace.
 *
 * ## Why this is a separate, pure file
 *
 * The name comes from the **provider**, which means it comes from outside the app: a
 * documents provider may call a file anything, including `../../../databases/x.db`, a
 * name with a NUL or a newline in it, or a 300-character one. It is about to become a
 * path component under `<workspace>/attachments/`, so getting it wrong is a directory
 * traversal, and the argument for the rules has to be stateable without a device —
 * which is why they are here rather than inline in the picker callback
 * (`ChatScreen.kt`'s `copyIntoWorkspace`, whose caller cannot be compiled by the
 * bare-JVM harnesses because it is Compose).
 *
 * The caller adds a second, independent guard: the canonical path of the file it is
 * about to write must be exactly `<attachments>/<this name>`. Two checks, because a
 * sanitiser's own correctness is the thing being trusted here.
 *
 * ## The rules, and each one's reason
 *
 *  - **A path is not a name.** Everything up to the last `/` or `\` is dropped, so a
 *    provider that answers with a path yields its file name.
 *  - **Separators and the platform's illegal characters become `_`**: `/ \ : * ? " < > |`
 *    plus every C0 control character and DEL. On a filesystem these are all either
 *    illegal or meaningful; the app must not create a file whose name a later shell or
 *    URL would read differently.
 *  - **Leading dots go.** `.` and `..` are directory entries, not names, and a leading
 *    dot also hides the file from the workspace listing the user is about to look at.
 *  - **An empty result becomes `attachment`.** A provider that says nothing still
 *    leaves the user with a file they can find.
 *  - **The name is truncated, keeping its extension** ([MAX_ATTACHMENT_NAME_CHARS]).
 *    The extension is the part that tells the agent (and the user) what the file *is*,
 *    and a provider-supplied name of several hundred characters would otherwise meet
 *    the filesystem's own 255-byte limit at write time and fail there instead.
 */
internal fun sanitizeAttachmentName(raw: String?): String {
    val base = (raw ?: "").substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = buildString(base.length) {
        for (character in base) {
            val illegal = character in ILLEGAL_NAME_CHARS || character.code < 0x20 || character.code == 0x7F
            append(if (illegal) '_' else character)
        }
    }.trim().trimStart('.').trim()

    return truncateKeepingExtension(cleaned.ifEmpty { "attachment" })
}

/**
 * A name that does not collide with an existing one: `report.pdf`, `report-2.pdf`,
 * `report-3.pdf`, …
 *
 * [exists] is the caller's own check rather than a directory listing so the rule stays
 * pure and testable; the picker passes `File(directory, candidate).exists()`.
 *
 * `-2` and not `copy`, `(1)` or a timestamp: the suffix has to survive being typed
 * back into the composer, and the two files are otherwise identical — the number is
 * the only thing that can distinguish them for the user who now has both.
 */
internal fun uniqueAttachmentName(desired: String, exists: (String) -> Boolean): String {
    if (!exists(desired)) return desired
    val dot = desired.lastIndexOf('.')
    // `dot > 0` and not `dot >= 0`: a leading dot was already stripped, so a name that
    // starts with one is impossible here, and `dot == 0` would split an empty stem.
    val stem = if (dot > 0) desired.substring(0, dot) else desired
    val extension = if (dot > 0) desired.substring(dot) else ""
    for (suffix in 2..MAX_ATTACHMENT_NAME_ATTEMPTS) {
        val candidate = "$stem-$suffix$extension"
        if (!exists(candidate)) return candidate
    }
    return "$stem-${MAX_ATTACHMENT_NAME_ATTEMPTS}$extension"
}

/**
 * Cut [name] to [MAX_ATTACHMENT_NAME_CHARS], keeping the final extension and the
 * characters before it.
 *
 * The budget counts the extension, so the result is a legal name of the stated length
 * rather than "too long once the extension is added back".
 */
private fun truncateKeepingExtension(name: String): String {
    if (name.length <= MAX_ATTACHMENT_NAME_CHARS) return name
    val dot = name.lastIndexOf('.')
    // Only a *short* extension is worth keeping; a 200-character "extension" is a stem
    // with a dot in it, and keeping it would spend the whole budget on nothing.
    val extension = if (dot > 0 && name.length - dot <= MAX_ATTACHMENT_EXTENSION_CHARS) {
        name.substring(dot)
    } else {
        ""
    }
    val stemBudget = (MAX_ATTACHMENT_NAME_CHARS - extension.length).coerceAtLeast(1)
    return name.substring(0, stemBudget) + extension
}

/** Everything a filesystem or a shell would read as something other than a character. */
private const val ILLEGAL_NAME_CHARS = "/\\:*?\"<>|"

/** The longest name this app will create, extension included. */
private const val MAX_ATTACHMENT_NAME_CHARS = 60

/** An extension longer than this is treated as part of the stem, not as an extension. */
private const val MAX_ATTACHMENT_EXTENSION_CHARS = 10

/** How many `-2`, `-3`, … candidates are tried before the last one is reused. */
private const val MAX_ATTACHMENT_NAME_ATTEMPTS = 999
