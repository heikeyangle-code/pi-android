package app.pi.bridge

import java.io.File

// A bare-JVM check for the **two implementations of one device-shell policy**.
//
// `DeviceShellGuard.hardBlocks` (Kotlin) is the enforcement: it runs inside the app
// process on every `/app/shell` call and cannot be bypassed from the guest.
// `FORBIDDEN_SHELL_PATTERNS` (TypeScript, `assets/pi-extensions/pi-android-bridge/
// danger.ts`) is its mirror: the permission gate uses it to refuse a command that
// can never be allowed *before* bothering the user with a confirmation dialog. Both
// files say the same thing about who wins, and both carry the honest note that
// eleven of the twelve TypeScript patterns match case-insensitively while the Kotlin
// regexes are case-sensitive.
//
// Two copies of a rule is the shape this repository treats as a defect generator
// (`docs/pi-sourced-lists.md`), and the copies *had* already diverged once: the
// block-device pattern was written `/\/dev\/block\b/` in TypeScript, where there is
// no word boundary between a space and a slash, so it could never match. That was
// found by hand. Nothing in the build could find it, which is what this file changes.
//
// It reads both files as **source text** (the Kotlin one imports `android.os.Process`,
// so it cannot be compiled on a bare JVM — same reason `settings-audit` reads the
// settings registry instead of importing it) and compares:
//
//   1. the two hard-block lists have the same length;
//   2. each entry reduces to the same sequence of literal tokens, in the same order —
//      after the character following every backslash is dropped, so `\bmount\b` and
//      `(^|[\s;&|()])mount(\s|$)` both reduce to `mount`, and the TypeScript `i` flag
//      does not matter;
//   3. neither list contains a `\b` immediately before a `/`, which is the exact
//      spelling that made the block-device rule dead on one side;
//   4. the set of `android_*` tools the device extension registers equals the key set
//      of `DANGER_LEVELS` — a tool with no declared level is treated as dangerous
//      (fail closed, so it is safe), but it is then undocumented, and a level with no
//      tool is dead weight the gate's install notice counts.
//
// What it does NOT check, so that a green run is not read as more than it is: that
// the two implementations agree on any particular command. That needs both regex
// engines, and the TypeScript one only exists inside pi's process. This compares the
// *rules*, not their behaviour.
//
// Registered in `tools/run-app-pure-checks.sh` as `shell-policy-mirror`.

private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")

private const val GUARD =
    "app/src/main/kotlin/app/pi/bridge/DeviceShell.kt"
private const val DANGER =
    "app/src/main/assets/pi-extensions/pi-android-bridge/danger.ts"
private const val DEVICE_EXTENSION =
    "app/src/main/assets/pi-extensions/pi-android-bridge/index.ts"

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?, consequence: String) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual\n  → $consequence")
    }
}

/** One hard block, from either side, as the token sequence its pattern reduces to. */
private data class Block(val tokens: List<String>, val source: String)

/**
 * The literal tokens a regex source names, in order.
 *
 * Two kinds of escape are treated differently, and that difference is what makes the
 * comparison meaningful:
 *
 *  - an **operator** escape (`\b`, `\s`, `\d`, …) names no character; it is a
 *    boundary, so the current token ends there;
 *  - any other escape is the **literal** character it protects (`\/` is a slash,
 *    `\.` a dot), so it goes through the ordinary "part of a token or not" test.
 *
 * Both files mean the same thing by the block-device pattern, and this reduction is
 * what shows it: Kotlin's `/dev/block` and TypeScript's `\/dev\/block` both reduce to
 * `dev` `block`. A *different* rewrite on one side fails the comparison, which is the
 * intent — the two lists are kept textually parallel, and a deliberate rewrite has to
 * be made on both sides.
 */
private const val REGEX_OPERATOR_ESCAPES = "bBsSdDwWnNrRtTaAzZ"

private fun tokens(pattern: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            out.add(current.toString().lowercase())
            current.setLength(0)
        }
    }
    var index = 0
    while (index < pattern.length) {
        val character = pattern[index]
        if (character == '\\' && index + 1 < pattern.length) {
            val escaped = pattern[index + 1]
            index += 2
            if (escaped in REGEX_OPERATOR_ESCAPES) {
                flush()
            } else if (escaped.isLetterOrDigit() || escaped == '_') {
                current.append(escaped)
            } else {
                flush()
            }
            continue
        }
        if (character.isLetterOrDigit() || character == '_') current.append(character) else flush()
        index++
    }
    flush()
    return out
}

/** Kotlin: `HardBlock(Regex("<source>"), "what", "why")` inside `hardBlocks`. */
private fun kotlinBlocks(text: String): List<Block> {
    val listStart = text.indexOf("private val hardBlocks: List<HardBlock> = listOf(")
    require(listStart >= 0) {
        "DeviceShell.kt no longer declares `private val hardBlocks: List<HardBlock> = listOf(` — " +
            "this check reads that declaration by name; re-read bridge/DeviceShell.kt."
    }
    val body = text.substring(listStart)
    val pattern = Regex("""HardBlock\(\s*Regex\("((?:[^"\\]|\\.)*)"\)""")
    return pattern.findAll(body).map { match ->
        // Kotlin escapes a backslash in a string literal as `\\`; the regex source the
        // engine sees has one. `tokens` skips the character after a backslash, so a
        // doubled backslash would shift everything by one — hence the unescape.
        val source = match.groupValues[1].replace("\\\\", "\\")
        Block(tokens(source), source)
    }.toList()
}

/** TypeScript: `{ pattern: /<source>/<flags>, label: "…" }` inside the array. */
private fun tsBlocks(text: String): List<Block> {
    val listStart = text.indexOf("export const FORBIDDEN_SHELL_PATTERNS")
    require(listStart >= 0) {
        "danger.ts no longer declares `FORBIDDEN_SHELL_PATTERNS` — this check reads that " +
            "declaration by name; re-read assets/pi-extensions/pi-android-bridge/danger.ts."
    }
    // Stop at the next exported list: `RELAXED_ONLY_PATTERNS` has the same object shape
    // and is a *different* rule (the 放宽模式 switch, single-sourced through /app/health),
    // so counting its entries here would report a length mismatch that is not one.
    val end = text.indexOf("export const RELAXED_ONLY_PATTERNS", listStart)
    val body = if (end < 0) text.substring(listStart) else text.substring(listStart, end)
    val pattern = Regex("""pattern:\s*/((?:[^/\\]|\\.)*)/([a-z]*)\s*,""")
    return pattern.findAll(body).map { match ->
        Block(tokens(match.groupValues[1]), match.groupValues[1])
    }.toList()
}

fun main() {
    val guardText = File(ROOT, GUARD).readText()
    val dangerText = File(ROOT, DANGER).readText()
    val extensionText = File(ROOT, DEVICE_EXTENSION).readText()

    // ------------------------------------------------ 1. same number of hard blocks
    val kotlinBlocks = kotlinBlocks(guardText)
    val tsBlocks = tsBlocks(dangerText)
    check(
        "the two hard-block lists have the same length",
        tsBlocks.size,
        kotlinBlocks.size,
        "DeviceShellGuard.hardBlocks is the enforcement and danger.ts is its mirror. Add or remove " +
            "a rule in BOTH, or the dialog will offer commands the guard refuses (or refuse without asking).",
    )

    // ------------------------------------------- 2. same tokens, in the same order
    val pairs = kotlinBlocks.zip(tsBlocks)
    for ((index, pair) in pairs.withIndex()) {
        val (kotlin, ts) = pair
        check(
            "hard block #${index + 1} names the same tokens on both sides",
            ts.tokens,
            kotlin.tokens,
            "Kotlin: $GUARD `$kotlin.source`; TypeScript: $DANGER `$ts.source`. " +
                "The TypeScript list is only a pre-filter, so this cannot make the device unsafe — " +
                "it makes the gate ask about something the guard then refuses.",
        )
    }

    // ------------------------------- 3. no `\b` immediately before a `/` anywhere
    // This is the shape that made the TypeScript block-device rule dead: a word
    // boundary between a space and a slash can never match.
    for ((label, blocks) in listOf("Kotlin" to kotlinBlocks, "TypeScript" to tsBlocks)) {
        val broken = blocks.filter { it.source.contains("\\b/") }
        check(
            "$label has no `\\b` immediately before a `/`",
            broken.map { it.source },
            emptyList<String>(),
            "`\\b` cannot match between a space and `/`, so this pattern is dead. " +
                "Drop the `\\b` (see the note above DeviceShellGuard.hardBlocks, which records the same bug).",
        )
    }

    // ------------------- 4. every registered device tool has a declared danger level
    val registered = Regex("name:\\s*\"(android_[a-z_]+)\"")
        .findAll(extensionText)
        .map { it.groupValues[1] }
        .toSet()
    val declared = Regex("\\n\\t(android_[a-z_]+):\\s*\"(read|control|dangerous)\",")
        .findAll(dangerText)
        .map { it.groupValues[1] }
        .toSet()
    check(
        "every tool the device extension registers has a danger level",
        registered - declared,
        emptySet<String>(),
        "dangerLevelOf() treats an android_* name with no level as dangerous, which is fail-closed " +
            "but undocumented: the gate asks for every call and the install notice undercounts. " +
            "Add the name to DANGER_LEVELS in $DANGER.",
    )
    check(
        "every declared danger level names a registered tool",
        declared - registered,
        emptySet<String>(),
        "a level with no tool is dead weight in $DANGER — it inflates DANGEROUS_TOOLS.length, " +
            "which the permission gate prints to the user on every session start.",
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
