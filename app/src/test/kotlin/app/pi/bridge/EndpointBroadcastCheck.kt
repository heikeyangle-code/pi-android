package app.pi.bridge

import java.io.File

// A bare-JVM check that the endpoints `DeviceBridgeRouter` SERVES and the endpoints it
// BROADCASTS are the same set.
//
// The token file (`/root/.pi/device-bridge.json`) carries an `endpoints` array, written
// from `DeviceBridgeRouter.ENDPOINTS`. That array is how anything reading the file — pi's
// extension, the diagnostics page, an agent poking at the bridge from `bash` — discovers
// what this bridge can do. It is a HAND-WRITTEN SECOND COPY of a fact the response
// branches already state, which is the shape this repository treats as a defect
// generator (`docs/pi-sourced-lists.md`).
//
// It had already drifted. The wave-1 reliability endpoints (`/app/ui/elements`,
// `/app/ui/select`, `/app/ui/macro/start`, ... — eleven of them) were added to the router
// without being added here, so the broadcast list under-reported the bridge by eleven
// while all eleven answered normally. Nothing in the build could notice; it was found by
// hand, by diffing the advertised list against a live bridge. This file changes that.
//
// Both facts are read as **source text**, because `DeviceBridgeRouter` cannot be compiled
// on a bare JVM — it pulls in the whole Android app. The comparison is:
//
//   1. every `/app/...` string literal OUTSIDE the ENDPOINTS block — what is served;
//   2. every path INSIDE ENDPOINTS, after dropping its `"METHOD "` prefix — what is
//      broadcast;
//   3. the two sets are equal.
//
// Order is deliberately not compared. ENDPOINTS is a DOCUMENT: grouping its lines by
// subject is the point of it, and requiring the same order would make every reordering a
// build failure for no benefit. Membership is the fact that matters.
//
// Registered in `tools/run-app-pure-checks.sh` as `endpoint-broadcast`.

private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")

private const val ROUTER = "app/src/main/kotlin/app/pi/bridge/DeviceBridgeRouter.kt"

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?, consequence: String) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual\n  → $consequence")
    }
}

/** One `/app/...` path from a double-quoted literal, with an optional `METHOD ` prefix. */
private val PATH = Regex(""""(?:[A-Z]+ +)?(/app/[A-Za-z0-9_\-/]+)""")

/**
 * Copy [text] with comments removed and **string literals kept verbatim**.
 *
 * Keeping the strings is the point: the paths this check compares are extracted from
 * string literals, so a pass that deleted them would find nothing and report a vacuous
 * OK. Comments are dropped because a path mentioned in prose is documentation, not a
 * route — and this file's own header is full of them.
 *
 * Honours Kotlin's lexical rules: line comments, block comments (which nest), raw strings
 * and ordinary strings with backslash escapes.
 */
private fun withoutComments(text: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val char = text[i]
        when {
            char == '/' && text.startsWith("//", i) -> {
                val newline = text.indexOf('\n', i)
                i = if (newline < 0) text.length else newline
            }

            char == '/' && text.startsWith("/*", i) -> {
                var depth = 1
                i += 2
                while (i < text.length && depth > 0) {
                    when {
                        text.startsWith("/*", i) -> {
                            depth++
                            i += 2
                        }

                        text.startsWith("*/", i) -> {
                            depth--
                            i += 2
                        }

                        else -> i++
                    }
                }
            }

            text.startsWith("\"\"\"", i) -> {
                val end = text.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) text.length else end + 3
                out.append(text, i, stop)
                i = stop
            }

            char == '"' -> {
                val start = i
                i++
                while (i < text.length) {
                    when {
                        text[i] == '\\' -> i += 2
                        text[i] == '"' -> {
                            i++
                            break
                        }

                        text[i] == '\n' -> break
                        else -> i++
                    }
                }
                out.append(text, start, i)
            }

            else -> {
                out.append(char)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * The span of the `ENDPOINTS = listOf(...)` declaration, found by matching the
 * parentheses of its `listOf(`. Returned as `start until endExclusive`, so the block's
 * own text can be sliced out and everything else treated as "what is served".
 */
private fun endpointsBlock(text: String): IntRange {
    val marker = "val ENDPOINTS = listOf("
    val start = text.indexOf(marker)
    require(start >= 0) {
        "DeviceBridgeRouter.kt no longer declares `$marker` — this check reads that " +
            "declaration by name; re-read bridge/DeviceBridgeRouter.kt."
    }
    var depth = 0
    var i = start + marker.length - 1
    while (i < text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return start until (i + 1)
            }
        }
        i++
    }
    error("unbalanced parentheses in the ENDPOINTS declaration of $ROUTER")
}

fun main() {
    val raw = File(ROOT, ROUTER).readText()
    val block = endpointsBlock(raw)
    val inside = raw.substring(block.first, block.last + 1)
    val outside = withoutComments(raw.substring(0, block.first) + raw.substring(block.last + 1))

    val broadcast = PATH.findAll(inside).map { it.groupValues[1] }.toSet()
    val served = PATH.findAll(outside).map { it.groupValues[1] }.toSet()

    check(
        "both lists parsed to something",
        minOf(broadcast.size, served.size) > 0,
        true,
        "a pattern that matches nothing makes every comparison below vacuous, which is a " +
            "worse failure than a red check: it would report OK on a file it cannot read.",
    )

    check(
        "every served endpoint is broadcast",
        (served - broadcast).sorted(),
        emptyList<String>(),
        "ENDPOINTS is what the token file advertises. An endpoint missing from it is invisible " +
            "to anything that reads that file instead of probing the bridge — which is exactly " +
            "how eleven wave-1 endpoints went missing.",
    )

    check(
        "nothing is broadcast that is not served",
        (broadcast - served).sorted(),
        emptyList<String>(),
        "an advertised endpoint the router does not answer sends a reader into a 404.",
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
