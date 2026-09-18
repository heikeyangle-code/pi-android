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
//  6. Two rows may not carry the same title. The registry had two rows called
//     「主题」(`theme` and `themes`), which makes a search hit and a group entry
//     ambiguous; that is a user-visible defect that no test could see.
//  7. No key may contain `[]`. `SettingsDocument.setPath` splits on `.` and knows
//     nothing about array syntax, so `packages[].autoload` (removed in
//     `docs/settings-review.md` §1.2) was written as a literal top-level key named
//     `packages[]` — data pi does not read and the app does not read back.
//  8. Every property declared on `PiSetting` must have a real reader: an occurrence
//     of `setting.<property>` outside the declaration itself. Three fields
//     (`depth`, `globAware`, `emptyListLabel`) were written on 12/6/all rows and
//     read by nothing — `docs/settings-audit-impl.md` §B9. A property that is only
//     ever *set* is a promise the code does not keep, which is the same defect as a
//     key that is only ever persisted.
//
// Rules 1 and 5 read the tree as text, so they strip comments first: a `key = "..."`
// that only appears inside a comment (or a chip that only matches one) must not
// satisfy anything. That hole was real — `thinkingBudgets` only appeared in a search
// chip's string, and a comment mentioning a removed key kept a chip green.
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

/**
 * Remove comments from Kotlin source, keeping everything else byte-for-byte —
 * **including string literals**, because two checks here are about the values those
 * literals hold (a registered key's name, a row's title, a search chip).
 *
 * Strings, raw strings and char literals are tracked so a `//` inside a literal is not
 * mistaken for a comment, and block comments nest (Kotlin allows it —
 * `tools/check-nested-comments.py` exists because this repository has got that wrong).
 */
private fun stripComments(source: String): String {
    val out = StringBuilder(source.length)
    var index = 0
    var blockDepth = 0
    var inLineComment = false
    var inString = false
    var inRawString = false
    var inChar = false
    while (index < source.length) {
        val c = source[index]
        when {
            inLineComment -> {
                if (c == '\n') {
                    inLineComment = false
                    out.append(c)
                }
                index++
            }

            blockDepth > 0 -> when {
                source.startsWith("/*", index) -> {
                    blockDepth++
                    index += 2
                }

                source.startsWith("*/", index) -> {
                    blockDepth--
                    index += 2
                }

                else -> {
                    if (c == '\n') out.append(c)
                    index++
                }
            }

            inRawString -> {
                if (source.startsWith("\"\"\"", index)) {
                    inRawString = false
                    out.append("\"\"\"")
                    index += 3
                } else {
                    out.append(c)
                    index++
                }
            }

            inString -> when (c) {
                '\\' -> {
                    out.append(c)
                    if (index + 1 < source.length) out.append(source[index + 1])
                    index += 2
                }

                '"' -> {
                    inString = false
                    out.append(c)
                    index++
                }

                else -> {
                    out.append(c)
                    index++
                }
            }

            inChar -> when (c) {
                '\\' -> {
                    out.append(c)
                    if (index + 1 < source.length) out.append(source[index + 1])
                    index += 2
                }

                '\'' -> {
                    inChar = false
                    out.append(c)
                    index++
                }

                else -> {
                    out.append(c)
                    index++
                }
            }

            source.startsWith("//", index) -> {
                inLineComment = true
                index += 2
            }

            source.startsWith("/*", index) -> {
                blockDepth = 1
                index += 2
            }

            source.startsWith("\"\"\"", index) -> {
                inRawString = true
                out.append("\"\"\"")
                index += 3
            }

            c == '"' -> {
                inString = true
                out.append(c)
                index++
            }

            c == '\'' -> {
                inChar = true
                out.append(c)
                index++
            }

            else -> {
                out.append(c)
                index++
            }
        }
    }
    return out.toString()
}

/**
 * One row's shape, as the information-architecture rules need it.
 *
 * `group`/`section` come from the row's own arguments, `kind` from `PiRowKind`, and
 * `readOnly` from the flag — so the rules below are checked against the same text the UI
 * renders from, not against a second copy.
 */
private class RowShape(
    val key: String,
    val group: String,
    val section: String,
    val kind: String,
    val readOnly: Boolean,
) {
    /**
     * A row the user cannot edit in place: an Action (it does something) or a `readOnly`
     * row (it reports a fact). Those are the kinds that may legitimately sit alone in a
     * section, because the section header is how the screen says "these are not settings".
     */
    val nonEditable: Boolean get() = kind == "Action" || readOnly
}

/** Every row's (group, section, kind, readOnly), in registry order. */
private fun rowShapes(rowsRegion: String): List<RowShape> =
    rowsRegion.split("\n        PiSetting(").mapNotNull { block ->
        val key = rowField(block, "key")
        val group = Regex("""\bgroup = (G_\w+)""").find(block)?.groupValues?.get(1)
        val section = rowField(block, "section")
        val kind = Regex("""\bkind = PiRowKind\.(\w+)""").find(block)?.groupValues?.get(1)
        if (key == null || group == null || section == null || kind == null) {
            null
        } else {
            RowShape(key, group, section, kind, block.contains("readOnly = true"))
        }
    }

/** One `name = "value"` argument of a row block, or null. */
private fun rowField(block: String, name: String): String? =
    Regex("\\b" + name + " = \"([^\"]*)\"").find(block)?.groupValues?.get(1)

/** The declared groups: id → title, from `PiSettingsCatalog.groups`. */
private fun declaredGroups(registry: String): Map<String, String> {
    val start = registry.indexOf("val groups: List<PiSettingsGroup> = listOf(")
    if (start < 0) return emptyMap()
    val region = registry.substring(start)
    return Regex("PiSettingsGroup\\((G_\\w+), \"([^\"]*)\"")
        .findAll(region)
        .associate { it.groupValues[1] to it.groupValues[2] }
}

/** The `data class PiSetting(...)` declaration, with its balanced closing paren. */
private fun piSettingDeclaration(registry: String): String {
    val start = registry.indexOf("data class PiSetting(")
    if (start < 0) return ""
    var depth = 0
    var index = start
    while (index < registry.length) {
        when (registry[index]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return registry.substring(start, index + 1)
            }
        }
        index++
    }
    return registry.substring(start)
}

/**
 * Properties a `PiSetting` may be declared with, from its own declaration.
 *
 * Taking them from the declaration (rather than from every `x = ` in the file) is what
 * keeps this check about the data class instead of about every local variable in the
 * registry.
 */
private fun piSettingFields(registry: String): List<String> =
    Regex("""val (\w+):""").findAll(piSettingDeclaration(registry)).map { it.groupValues[1] }.toList()

/**
 * Fields read through a receiver other than a variable literally named `setting`.
 *
 * The reason is required and must be non-empty, exactly like the `piOwnedKeys` entries:
 * "declared" must never be an empty word to hide behind. An entry that is no longer a
 * declared field fails the check below, so this cannot accumulate stale exemptions.
 */
private val bareFieldReaders: Map<String, String> = mapOf(
    "emptyListLabel" to "在 `PiSetting.display` 里以接收者属性（裸名）读取：`if (count == 0) emptyListLabel`",
)

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
    // Comments are stripped first: a key named only inside a comment is not a reader,
    // and that hole was real (see the header).
    val allText = sources
        .filter { it.absolutePath != registryFile.absolutePath }
        .joinToString("\n") { stripComments(it.readText()) }
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

    // Rule 6: two rows with one title. The rows region is taken first (the lookup
    // table and the group summaries below it are not rows), then stripped of comments
    // so a title quoted in prose cannot collide with a real one.
    val rowsRaw = registry
        .substringAfter("val settings: List<PiSetting> = listOf(")
        .substringBefore("/** Registry lookup by exact dotted key. */")
    val rows = stripComments(rowsRaw)
    // Per-block pairs, so the failure can name the keys instead of just the title.
    val keysAndTitles = rows.split("PiSetting(").mapNotNull { block ->
        val key = Regex("""\bkey = "([^"]*)"""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = Regex("""\btitle = "([^"]*)"""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
        key to title
    }
    val duplicateTitles = keysAndTitles.groupBy { it.second }.filterValues { it.size > 1 }
    check(
        "no two registered rows share a title",
        duplicateTitles.isEmpty(),
        duplicateTitles.entries.joinToString("\n  ") { (title, rowsWithIt) ->
            "title 「$title」 is used by ${rowsWithIt.joinToString { it.first }}"
        },
    )

    // Rule 7: `[]` in a key. `SettingsDocument.setPath` splits on `.`, so such a key
    // becomes a literal top-level name pi does not read.
    val arraySyntaxKeys = keys.filter { it.contains("[]") }
    check(
        "no key uses array syntax (`[]`)",
        arraySyntaxKeys.isEmpty(),
        arraySyntaxKeys.joinToString { "$it is not a JSON path — SettingsDocument.setPath would write it literally" },
    )

    // Rule 8: every field declared on PiSetting must be read somewhere. A field that
    // is only ever set is a promise the code does not keep (`depth` promised L2/L3
    // navigation; `globAware` promised glob-aware editors; nothing read either).
    val fields = piSettingFields(registry)
    check("the PiSetting declaration was found", fields.isNotEmpty())
    val declaration = piSettingDeclaration(registry)
    val registryWithoutDeclaration = registry.substring(0, registry.indexOf(declaration)) +
        registry.substring(registry.indexOf(declaration) + declaration.length)
    val corpus = sources.joinToString("\n") { source ->
        if (source.absolutePath == registryFile.absolutePath) {
            stripComments(registryWithoutDeclaration)
        } else {
            stripComments(source.readText())
        }
    }
    val unreadFields = fields.filter { field ->
        !corpus.contains("setting.$field") && bareFieldReaders[field] == null
    }
    check(
        "every PiSetting field has a reader",
        unreadFields.isEmpty(),
        unreadFields.joinToString("\n  ") { field ->
            "PiSetting.$field is set by rows but never read: no `setting.$field` outside the declaration"
        },
    )
    val staleFieldExemptions = bareFieldReaders.keys - fields.toSet()
    check(
        "no bare-field exemption is stale",
        staleFieldExemptions.isEmpty(),
        staleFieldExemptions.joinToString { "$it is not a declared PiSetting field" },
    )
    val blankFieldExemptions = bareFieldReaders.filterValues { it.isBlank() }.keys
    check(
        "every bare-field exemption carries its reason",
        blankFieldExemptions.isEmpty(),
        blankFieldExemptions.joinToString(),
    )

    // Rules 9-12: the information architecture. The complaint was that "everything on the
    // settings screen is such a mess", and the mess was four kinds of row — pi keys, app
    // preferences, read-only facts, actions — sharing one shape and one place. What fixes
    // that is group/section membership and order, so those are what gets asserted here.
    val shapes = rowShapes(rows)
    check(
        "every row was parsed for the architecture rules",
        shapes.size == keys.size,
        "parsed ${shapes.size} of ${keys.size}",
    )

    // Rule 9: one section, one kind. An Action or a read-only fact next to editable
    // settings is exactly how "the mess" reads on screen.
    val mixedSections = shapes.groupBy { it.group to it.section }.filterValues { rowsIn ->
        rowsIn.any { it.nonEditable } && rowsIn.any { !it.nonEditable }
    }
    check(
        "no section mixes non-editable rows with settings",
        mixedSections.isEmpty(),
        mixedSections.keys.joinToString("\n  ") { (group, section) ->
            "$group/$section mixes actions or read-only rows with editable settings"
        },
    )

    // Rule 10: no one-row section inside a group that has several sections — a header for a
    // single row is the noise this pass removes — unless that row is non-editable, which is
    // the legitimate case (an action block, or one read-only fact such as 资源包).
    val singleRowNoise = shapes.groupBy { it.group }.flatMap { (group, rowsIn) ->
        val sections = rowsIn.groupBy { it.section }
        if (sections.size < 2) {
            emptyList()
        } else {
            sections.filterValues { it.size == 1 && !it.single().nonEditable }.keys.map { group to it }
        }
    }
    check(
        "no single-row section holds an editable setting inside a multi-section group",
        singleRowNoise.isEmpty(),
        singleRowNoise.joinToString("\n  ") { (group, section) ->
            "$group/$section has one editable row; merge it, or make the section a kind of its own"
        },
    )

    // Rule 11: groups and rows agree. A declared group with no rows renders as an empty
    // screen, and a row pointing at an undeclared group disappears from 全部设置 entirely.
    val groups = declaredGroups(registry)
    check("the group list was found", groups.isNotEmpty())
    val usedGroups = shapes.map { it.group }.toSet()
    val emptyGroups = groups.keys - usedGroups
    val undeclaredGroups = usedGroups - groups.keys
    check("no declared group is empty", emptyGroups.isEmpty(), emptyGroups.joinToString())
    check("every row's group is declared", undeclaredGroups.isEmpty(), undeclaredGroups.joinToString())
    check(
        "no two groups share a title",
        groups.values.toSet().size == groups.size,
        groups.entries.groupBy { it.value }.filterValues { it.size > 1 }.keys.joinToString(),
    )

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
    // `rows` is already comment-stripped above: the registry explains its own removals
    // in comments that live *inside* the settings list, and a chip matching one of
    // those would be a false pass (it happened: a comment mentioning the removed
    // `app.sessions.import` kept the "导入" chip green).
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
            "${chips.size} search hints, ${fields.size} PiSetting fields",
    )
    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
