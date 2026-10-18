package app.pi.packages

// A bare-JVM harness for **where** `PiOfficialCatalog` looks for pi's shipped model
// data (`<rootfs>/opt/pi/…/pi-ai/dist/providers/data`).
// Registered in `tools/run-app-pure-checks.sh` as `official-catalog-path`.
//
// Why this must be pinned: the directory's depth is decided by npm's dependency tree,
// and that tree has already moved once. The engine payload at `9fb65b7` nested
// `@earendil-works/pi-ai` under `pi-coding-agent/node_modules`; pi 1.3.0's
// `tools/pi-engine.lock.json` hoists every `@earendil-works/*` to the top level
// (`node_modules/@earendil-works/pi-ai`, `pi-coding-agent` nests 0). A hard-coded path
// therefore fails **silently in the one direction nobody re-checks by hand**: the
// catalog reads as empty, the import sheet's `providerChoices` collapses to the few
// app-side presets, and the user reports 「已有厂商，再点导入模型，列表里只有三个，搜也搜不出来」.
// So this harness reads the lock file — the one truthful statement about where pi-ai
// actually lands — and fails the build when `PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES`
// stops covering it, i.e. the next engine bump cannot move the tree unnoticed.
//
// The rest pins the resolution itself: the hoisted layout wins when both are unpacked,
// the legacy nested layout still works on its own, and "nothing found" reports **every
// path it tried** instead of one anonymous "读不到" (that sentence is what made the
// empty-catalog failure indistinguishable from "pi has no providers").
//
// Android-free: java.io / java.nio plus kotlinx.serialization and the stdlib — the same
// closure as the `official-catalog` harness, which compiles `PiOfficialCatalog.kt`.

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** One provider file plus a valid manifest, in [relative] under [root]. */
private fun plant(root: File, relative: String, providerId: String) {
    val dir = File(root, relative).apply { mkdirs() }
    val body = """{"test-api":{"chat:$providerId-1":{"id":"$providerId-1","type":"chat"}}}"""
    File(dir, "$providerId.json").writeText(body)
    val manifest = """{"schemaVersion":${PiOfficialCatalog.MODEL_DATA_SCHEMA_VERSION},""" +
        """"files":{"$providerId.json":"${PiOfficialCatalog.sha256Hex(body)}"}}"""
    File(dir, PiOfficialCatalog.MANIFEST_NAME).writeText(manifest)
}

/** The candidate list read as a `File` list — the order `readFromRootfs` walks. */
private fun candidates(root: File): List<File> =
    PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES.map { File(root, it) }

fun main() {
    val repoRoot = File(System.getProperty("pi.repo.root") ?: ".").absoluteFile
    val lock = File(repoRoot, "tools/pi-engine.lock.json")
    check("the engine lock file is readable", lock.isFile, true)
    val packages = runCatching {
        (Json.parseToJsonElement(lock.readText()) as? JsonObject)?.get("packages") as? JsonObject
    }.getOrNull()
    check("the lock file carries a packages table", packages != null, true)

    // 1) The anti-drift check: every location the lock installs pi-ai into must be a
    //    candidate directory. This is the check that fails on the next tree move.
    val piAiKeys = packages?.keys.orEmpty().filter { it.endsWith("@earendil-works/pi-ai") }
    check("the lock names where pi-ai is installed", piAiKeys.isNotEmpty(), true)
    for (key in piAiKeys) {
        val expected = "opt/pi/$key/dist/providers/data"
        check(
            "ROOTFS_DATA_DIRECTORIES covers the lock's $key",
            PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES.contains(expected),
            true,
        )
    }
    check(
        "the candidate list has no duplicates",
        PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES.distinct().size,
        PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES.size,
    )

    val hoisted = PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES.first()
    val nested = PiOfficialCatalog.ROOTFS_DATA_DIRECTORIES.last()

    // 2) Both layouts unpacked (the state a user upgrading from an older payload can be
    //    in): the hoisted one — the layout the current lock describes — must win.
    val both = java.nio.file.Files.createTempDirectory("pi-catalog-both").toFile()
    try {
        plant(both, hoisted, "hoisted")
        plant(both, nested, "nested")
        check(
            "with both layouts unpacked the hoisted one is used",
            PiOfficialCatalog.readFromRootfs(both).providers.map { it.id },
            listOf("hoisted"),
        )
    } finally {
        both.deleteRecursively()
    }

    // 3) Only the legacy nested layout (an old payload already on disk) must still read.
    val legacy = java.nio.file.Files.createTempDirectory("pi-catalog-legacy").toFile()
    try {
        plant(legacy, nested, "legacy")
        check(
            "the nested layout still reads on its own",
            PiOfficialCatalog.readFromRootfs(legacy).providers.map { it.id },
            listOf("legacy"),
        )
    } finally {
        legacy.deleteRecursively()
    }

    // 4) Nothing unpacked: no providers **and** every attempted path in problems. The
    //    latter is the whole point — one anonymous "读不到" cannot be told apart from
    //    "the payload moved".
    val empty = java.nio.file.Files.createTempDirectory("pi-catalog-empty").toFile()
    try {
        val catalog = PiOfficialCatalog.readFromRootfs(empty)
        check("nothing unpacked yields no providers", catalog.providers, emptyList<PiOfficialCatalog.Provider>())
        val tried = candidates(empty).map { it.path }
        check(
            "nothing unpacked names every attempted path",
            tried.isNotEmpty() && tried.all { path -> catalog.problems.any { it.contains(path) } },
            true,
        )
    } finally {
        empty.deleteRecursively()
    }

    // 5) A manifest that is present but yields nothing (its one file is missing): the
    //    path is still reported, so a broken payload reads differently from a moved one.
    val broken = java.nio.file.Files.createTempDirectory("pi-catalog-broken").toFile()
    try {
        val dir = File(broken, hoisted).apply { mkdirs() }
        File(dir, PiOfficialCatalog.MANIFEST_NAME).writeText(
            """{"schemaVersion":${PiOfficialCatalog.MODEL_DATA_SCHEMA_VERSION},"files":{"gone.json":"x"}}""",
        )
        val catalog = PiOfficialCatalog.readFromRootfs(broken)
        check("a manifest whose files are gone yields no providers", catalog.providers, emptyList<PiOfficialCatalog.Provider>())
        check(
            "…and still names the directory it looked in",
            catalog.problems.any { it.contains(File(broken, hoisted).path) },
            true,
        )
    } finally {
        broken.deleteRecursively()
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
