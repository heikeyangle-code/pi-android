package app.pi.ui.settings

// A bare-JVM harness for `PiResourceFacts.kt`, the pure half of the 扩展与资源 screen's
// "what did pi actually discover?" rows.
//
// Why it exists: that screen used to list five empty path arrays and say nothing about the
// resources pi had actually found, which is why the user read it as useless. The rows that
// replace that silence are only honest if three cases stay distinct — **N found**, **found
// nothing**, and **could not read** — because collapsing the last two into `0` is the
// fake-reading shape this repository keeps paying for (see `docs/settings-audit-impl.md`
// §B10's sibling rule for `RuntimeFacts`).
//
// The other thing pinned here is deduplication: the scanner reports one entry per copy of a
// resource (it has to, to draw conflicts), while "how many skills do I have" is a question
// about *names*. The winner is the highest-priority source, which is pi's own resolution
// order.
//
// Android-free on purpose: it compiles `PiResourceFacts.kt` (kotlin stdlib only) together
// with this file.
//
//   settings-resources   app.pi.ui.settings.PiResourceFactsCheckKt

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

private fun resource(kind: DiscoveredKind, name: String, source: DiscoverySource) =
    DiscoveredResource(kind, name, source)

fun main() {
    val skillsKey = DiscoveredKind.Skills.key

    // ---------------------------------------------------------------- N found
    run {
        val scan = ResourceScan.Found(
            listOf(
                resource(DiscoveredKind.Skills, "alpha", DiscoverySource.Global),
                resource(DiscoveredKind.Skills, "beta", DiscoverySource.ProjectPi),
                resource(DiscoveredKind.Skills, "gamma", DiscoverySource.Global),
                resource(DiscoveredKind.Themes, "nord", DiscoverySource.Global),
                resource(DiscoveredKind.Prompts, "review", DiscoverySource.Package),
                resource(DiscoveredKind.Extensions, "pi-android-bridge", DiscoverySource.Global),
            ),
        )
        val texts = resourceFactOverrides(scan)
        check(
            "a found skill count is reported with its sources",
            texts[skillsKey] == "3 个 · 项目 .pi 1 · ~/.pi/agent 2",
            "got: ${texts[skillsKey]}",
        )
        check(
            "each kind counts only its own resources",
            texts[DiscoveredKind.Themes.key] == "1 个 · ~/.pi/agent 1" &&
                texts[DiscoveredKind.Prompts.key] == "1 个 · 已装包 1" &&
                texts[DiscoveredKind.Extensions.key] == "1 个 · ~/.pi/agent 1",
            texts.toString(),
        )
        check(
            "a source with no resources is not printed",
            texts[DiscoveredKind.Themes.key]?.contains("项目") == false,
            texts[DiscoveredKind.Themes.key].orEmpty(),
        )
    }

    // ------------------------------------------------- same name, two copies
    run {
        // The scanner returns both copies so the listing screen can draw the conflict; the
        // count must still be 1, attributed to the source pi would load.
        val scan = ResourceScan.Found(
            listOf(
                resource(DiscoveredKind.Skills, "dup", DiscoverySource.Global),
                resource(DiscoveredKind.Skills, "dup", DiscoverySource.ProjectPi),
            ),
        )
        val tests = countDiscovered((scan as ResourceScan.Found).resources, DiscoveredKind.Skills)
        check("a name present twice counts once", tests.total == 1, "${tests.total}")
        check(
            "the winning source is the higher-priority one (project .pi)",
            tests.bySource == listOf(DiscoverySource.ProjectPi to 1),
            tests.bySource.toString(),
        )
    }

    // A package copy loses to a project copy; an extension-contributed skill keeps its own
    // label when nothing else claims the name.
    run {
        val scan = ResourceScan.Found(
            listOf(
                resource(DiscoveredKind.Skills, "shared", DiscoverySource.Package),
                resource(DiscoveredKind.Skills, "shared", DiscoverySource.ProjectPi),
                resource(DiscoveredKind.Skills, "pi-android-device", DiscoverySource.Extension),
            ),
        )
        val counts = countDiscovered((scan as ResourceScan.Found).resources, DiscoveredKind.Skills)
        check("two distinct names", counts.total == 2, "${counts.total}")
        check(
            "sources are ordered by pi's precedence and keep their labels",
            counts.bySource == listOf(DiscoverySource.ProjectPi to 1, DiscoverySource.Extension to 1),
            counts.bySource.toString(),
        )
        check(
            "the extension-contributed source is named, not folded into the global one",
            resourceFactOverrides(scan)[DiscoveredKind.Skills.key]
                ?.contains(DiscoverySource.Extension.label) == true,
            resourceFactOverrides(scan)[DiscoveredKind.Skills.key].orEmpty(),
        )
    }

    // ------------------------------------------------------------ found nothing
    run {
        val texts = resourceFactOverrides(ResourceScan.Found(emptyList()))
        DiscoveredKind.entries.forEach { kind ->
            check(
                "${kind.label}: nothing found says so, and never prints a fake 0",
                texts[kind.key] == "还没有发现任何资源" && !texts[kind.key]!!.contains("0 个"),
                "got: ${texts[kind.key]}",
            )
        }
        check(
            "the summary says nothing-found rather than a number",
            discoveredSummaryLine(ResourceScan.Found(emptyList())) == "还没有发现任何资源",
            discoveredSummaryLine(ResourceScan.Found(emptyList())).orEmpty(),
        )
    }

    // --------------------------------------------------------------- unreadable
    run {
        val texts = resourceFactOverrides(ResourceScan.Unreadable("运行时尚未解包"))
        DiscoveredKind.entries.forEach { kind ->
            check(
                "${kind.label}: an unreadable scan reports the reason",
                texts[kind.key] == "读不到：运行时尚未解包",
                "got: ${texts[kind.key]}",
            )
        }
        check(
            "an unreadable scan never prints a number",
            texts.values.none { it.contains("个") },
            texts.toString(),
        )
    }

    // ------------------------------------------------------- not-read-yet placeholder
    // "Not read yet" is a third state, and it must not look like either of the other two.
    run {
        val placeholders = resourceFactPlaceholders()
        check(
            "the placeholder covers every fact row",
            placeholders.keys == DiscoveredKind.entries.map { it.key }.toSet(),
            placeholders.keys.toString(),
        )
        check(
            "the placeholder says 未读取 and never a number",
            placeholders.values.all { it == "未读取" } &&
                placeholders.values.none { value -> value.any { it.isDigit() } },
            placeholders.toString(),
        )
        check(
            "the placeholder is not the empty-scan text",
            placeholders.values.none { it == "还没有发现任何资源" },
            placeholders.toString(),
        )
    }

    // ---------------------------------------------------------------- summary line
    run {
        check("no scan yet → no summary clause (never a fake 0)", discoveredSummaryLine(null) == null)
        check(
            "an unreadable scan → no summary clause",
            discoveredSummaryLine(ResourceScan.Unreadable("io")) == null,
            discoveredSummaryLine(ResourceScan.Unreadable("io")).orEmpty(),
        )
        val line = discoveredSummaryLine(
            ResourceScan.Found(
                listOf(
                    resource(DiscoveredKind.Skills, "a", DiscoverySource.Global),
                    resource(DiscoveredKind.Skills, "b", DiscoverySource.Global),
                    resource(DiscoveredKind.Themes, "nord", DiscoverySource.Global),
                ),
            ),
        )
        check(
            "the summary names each non-empty kind with its count",
            line == "已发现 2 个技能 · 1 个主题",
            line.orEmpty(),
        )
        check(
            "the summary omits empty kinds instead of printing zeros",
            line?.contains("提示模板") == false && line?.contains("0 ") == false,
            line.orEmpty(),
        )
    }

    // ------------------------------------------------------------------- the cache
    run {
        check("the cache starts empty in a fresh process", PiResourceFactsCache.lastScan() == null)
        PiResourceFactsCache.put("/tmp/ws-a", ResourceScan.Found(emptyList()))
        check(
            "the cache hands back the last scan",
            PiResourceFactsCache.lastScan() is ResourceScan.Found,
        )
        PiResourceFactsCache.put("/tmp/ws-b", ResourceScan.Unreadable("gone"))
        check(
            "the cache keeps the newest workspace's scan",
            PiResourceFactsCache.lastScan() == ResourceScan.Unreadable("gone"),
            PiResourceFactsCache.lastScan().toString(),
        )
    }

    check(
        "the four fact keys are distinct and namespaced under app.resources",
        DiscoveredKind.entries.map { it.key }.toSet().size == 4 &&
            DiscoveredKind.entries.all { it.key.startsWith("app.resources.discovered.") },
        DiscoveredKind.entries.joinToString { it.key },
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
