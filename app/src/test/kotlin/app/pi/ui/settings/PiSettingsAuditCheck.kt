package app.pi.ui.settings

// A bare-JVM audit of the settings registry, registered in
// `tools/run-app-pure-checks.sh` as `settings-audit`.
//
// Why this check exists: the settings page has repeatedly shipped rows that are
// persisted and never consulted — the same defect in three shapes, recorded in
// `docs/known-gaps.md` §I7/§I10/§I11 and re-cut in `docs/settings-review.md`.
// `PiSettingsRegistry.kt` is 1000+ lines of data, and nothing in the build ever
// compared it against the rest of the tree, so a dead row could only be found by
// hand. This is that comparison, made mechanical:
//
//  1. Every registered key must either appear as a string literal somewhere in
//     the app's own sources (a reader — the automatable approximation of "has a
//     consumer"), or be declared in `PiSettingsCatalog.piOwnedKeys` with the pi
//     location that reads it. A key that satisfies neither is a switch with no
//     effect and fails the build, printed by name.
//  2. An `app.*` key can never be "pi-owned": the app is its only possible
//     reader, so whitelisting one would be the same dead switch with paperwork.
//  3. `piOwnedKeys` must not go stale: every entry has to be a registered key.
//  4. No key may be registered twice — `byKey` would silently keep the last one.
//  5. One layer up from a dead row: every hint chip on the search screen must still
//     resolve to something in the registry's rows. `/tree` and `sessionDir` both
//     stopped resolving when their rows were removed, and nothing would have
//     noticed without this.
//
// The registry itself cannot be compiled here: it imports Compose and Material
// icons, which is exactly what this harness must not need. So the registry is
// read as *source text* (`key = "..."` / `"key" to "reason"`), which is also the
// granularity the check is about — the key name is the thing that must appear.

import java.io.File
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

private val keyPattern = Regex("""^\s*key = "([^"]+)",\s*$""")
private val piOwnedPattern = Regex("""^\s*"([^"]+)" to "([^"]*)",\s*$""")

/** Every `key = "..."` of the registry, in file order. */
private fun registeredKeys(registry: String): List<String> =
    registry.lineSequence().mapNotNull { keyPattern.find(it)?.groupValues?.get(1) }.toList()

/**
 * The `piOwnedKeys` map: key → the reason it is not read by the app.
 *
 * Bounded by the declaration and the closing paren of the map literal, so the
 * `key = "..."` entries elsewhere in the file cannot leak into it.
 */
private fun piOwnedKeys(registry: String): Map<String, String> {
    val lines = registry.lines()
    val start = lines.indexOfFirst { it.contains("val piOwnedKeys") }
    if (start < 0) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (index in start + 1 until lines.size) {
        val line = lines[index]
        if (line.trim() == ")") break
        val match = piOwnedPattern.find(line) ?: continue
        result[match.groupValues[1]] = match.groupValues[2]
    }
    return result
}

/** Kotlin sources under [root] that the audit treats as "the app's own code". */
private fun appSources(root: File): List<File> {
    val sourceRoot = File(root, "app/src/main/kotlin")
    if (!sourceRoot.isDirectory) return emptyList()
    return sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
}

fun main() {
    val root = System.getProperty("pi.repo.root")?.let(::File)
        ?: File(System.getProperty("user.dir") ?: ".")
    val registryFile = File(root, "app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt")

    check("the registry source is readable at $registryFile", registryFile.isFile)
    if (!registryFile.isFile) {
        println("\nharness: FAILED (no registry to audit)")
        exitProcess(1)
    }

    val registry = registryFile.readText()
    val keys = registeredKeys(registry)
    val owned = piOwnedKeys(registry)
    val sources = appSources(root)

    check("the app's own sources were found", sources.isNotEmpty(), "looked under $root/app/src/main/kotlin")
    check("the registry declares keys", keys.isNotEmpty())

    // Rule 4: duplicates. `associateBy` keeps the last, so the earlier row would
    // be unreachable data that still shows up in the group it was written for.
    val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    check("no key is registered twice", duplicates.isEmpty(), "duplicated: ${duplicates.joinToString()}")

    // Rule 3: a whitelist entry that is no longer a row is a stale exemption.
    val stale = owned.keys - keys.toSet()
    check("every pi-owned declaration is a registered key", stale.isEmpty(), "stale: ${stale.joinToString()}")

    // Rule 2: the app is the only possible reader of its own keys, so an `app.*`
    // entry in the whitelist is a dead switch that has been papered over.
    val badOwned = owned.keys.filter { it.startsWith("app.") }
    check("no app.* key is declared pi-owned", badOwned.isEmpty(), "declared: ${badOwned.joinToString()}")

    // Rule 1: the audit itself. This is the assertion that fails the build.
    val allText = sources
        .filter { it.absolutePath != registryFile.absolutePath }
        .joinToString("\n") { it.readText() }
    val unread = keys.filter { key -> !allText.contains("\"$key\"") && key !in owned }
    check(
        "every registered key is either read by the app or declared pi-owned",
        unread.isEmpty(),
        unread.joinToString("\n  ") { "no reader and no piOwnedKeys entry: $it" },
    )

    // The whitelist carries the reason, so an entry without evidence is rejected
    // too — otherwise "declared" would be an empty word to hide behind.
    val undocumented = owned.filterValues { it.isBlank() }.keys
    check("every pi-owned declaration carries its reason", undocumented.isEmpty(), undocumented.joinToString())

    // One layer up from a dead row: a search hint whose chip selects a query that
    // matches nothing. Only the `examples` list is read — taking every standalone
    // string in the file would let an unrelated literal fail the build later — and a
    // chip is checked against the *rows region* of the registry (from the settings
    // list up to the lookup table), not the whole file, so a chip that only matched
    // a KDoc comment cannot pass.
    val searchScreen = File(root, "app/src/main/kotlin/app/pi/ui/settings/SettingsSearchScreen.kt")
    val chip = Regex("""^\s*"([^"]+)",\s*$""")
    val chipBlock = searchScreen.takeIf { it.isFile }
        ?.readText()
        ?.substringAfter("val examples = listOf(", "")
        ?.substringBefore("\n    )", "")
        ?: ""
    val chips = chipBlock.lines()
        .mapNotNull { chip.find(it)?.groupValues?.get(1) }
        .filter { it.isNotBlank() && !it.contains("$") }
    check("the search screen's hint chips were found", chips.isNotEmpty())
    val rows = registry
        .substringAfter("val settings: List<PiSetting> = listOf(")
        .substringBefore("/** Registry lookup by exact dotted key. */")
    val dangling = chips.filter { !rows.contains(it.removePrefix("/")) }
    check(
        "every search hint chip resolves to a registered row",
        dangling.isEmpty(),
        dangling.joinToString { "\"$it\" matches no key, alias, title or description" },
    )

    val appKeys = keys.count { it.startsWith("app.") }
    val piKeys = keys.size - appKeys
    println(
        "\naudit: ${keys.size} registered keys ($piKeys pi, $appKeys app): " +
            "${keys.size - owned.size} read by this app, ${owned.size} declared pi-owned, " +
            "${chips.size} search hints",
    )
    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
