package app.pi.runtime

/**
 * POSIX single-quote escaping for a string that is about to be handed to a shell.
 *
 * It lives in its own file because two very different callers need the same rule
 * and neither may import the other: `PtyLauncher` builds the terminal's guest script
 * (and imports Android), while [ProrootRawProbe] builds the probe script and is
 * compiled into a bare-JVM harness, which is only possible if every file in that
 * closure is Android-free.
 *
 * The rule: wrap in single quotes, and close/reopen around every embedded quote
 * (`'` → `'\''`). Inside single quotes a POSIX shell treats **everything** as
 * literal, so there is nothing else to escape — no `$`, no backtick, no backslash.
 */
internal object ShellQuote {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
