package app.pi.packages

/**
 * `stripJsonComments` — pi's `core/model-config.ts:267` calls it on `models.json`
 * before `JSON.parse`, which is why a user's annotated `models.json` must not be
 * rejected by this app's readers.
 *
 * ## Why this is its own object
 *
 * It is the only part of reading `models.json` that pi does *not* do with a plain
 * `JSON.parse`, and more than one reader needs it now: `PiConfigFiles` (the
 * read/modify/write) and `PiModelInventory` (the read-only list behind 设置 → 模型).
 * Keeping one copy is the point — a second stripper would be free to disagree with
 * the first, and disagreement here means one screen shows models the other cannot
 * see. It also lives outside `PiConfigFiles` so the bare-JVM harness can compile it
 * (`PiConfigFiles` imports the app's settings layer, which the harness cannot).
 *
 * Deliberately narrow: `//` line comments and block comments **outside strings**,
 * nothing else (no trailing commas, no single quotes). It is used for reading only —
 * never to re-serialise a user's file, which stays byte-for-byte theirs.
 */
object PiJsonComments {

    fun strip(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (inString) {
                out.append(ch)
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                index++
                continue
            }
            when {
                ch == '"' -> {
                    inString = true
                    out.append(ch)
                    index++
                }
                ch == '/' && index + 1 < text.length && text[index + 1] == '/' -> {
                    while (index < text.length && text[index] != '\n') index++
                }
                ch == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < text.length && !(text[index] == '*' && text[index + 1] == '/')) index++
                    index += 2
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }
}
