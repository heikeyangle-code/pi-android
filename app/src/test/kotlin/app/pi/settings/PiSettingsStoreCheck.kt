package app.pi.settings

// A bare-JVM harness for `PiSettingsFileStore`, the app's writer of pi's `settings.json`
// documents.
//
// Why it exists: this file decides whether a settings edit survives, and every case below is
// a way it did not (`docs/settings-audit-impl.md` §B1/§B2/§B3):
//
//  - **§B1 "restore the default" wrote `JsonNull`.** pi's `parseTimeoutSetting` treats `null`
//    as present-and-unparseable and throws (`core/settings-manager.ts:186-196`), so a
//    `httpIdleTimeoutMs: null` in the file stopped `pi --mode rpc` from starting
//    (`main.ts:850-851`). A removal must leave the key **absent**, and the scope it is removed
//    from is pi's rule: the document that explicitly carries it (`persistScopedSettings`,
//    `:632-661`).
//  - **§B2 two stores over one file disagreed.** `WorkspaceStore` built its own instance for
//    the global document, cached it forever, and wrote it back — erasing whatever the settings
//    screen had changed in the meantime. Documents and locks are now per canonical file path
//    (`PiSettingsFileStore.Document`), so the cases below assert exactly that: a second
//    instance sees the first one's write, and neither write erases the other's key.
//  - **§B3 the write was not a read-modify-write inside pi's lock.** Two writers in one process
//    also shared `<name>.tmp-<pid>`, so they could interleave into one temp file before either
//    renamed it. The concurrency case below hammers that, and the temp-name case asserts the
//    name is unique per call.
//
// Android-free on purpose: it compiles `app/pi/settings/PiSettingsStore.kt` (the interface,
// which lives in the Compose-free file), `PiSettingsFileStore.kt`, `PiSettingsLock.kt`,
// `app/pi/runtime/PiProjectConfig.kt` and `:rpc`'s `SettingsDocument.kt` + `PiJson.kt` +
// `internal/Json.kt`. No Android, no Compose, no Gradle.
//
//   settings-store   app.pi.settings.PiSettingsStoreCheckKt

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
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

private fun tempDir(label: String): File =
    Files.createTempDirectory("pi-settings-$label").toFile()

private fun File.readJsonOrNull(): JsonObject? =
    if (!isFile) null else app.pi.rpc.PiJson.parseObjectOrNull(readText())

/** The same dotted-path lookup the store uses, so assertions read documents pi's way. */
private fun lookup(document: JsonObject?, key: String): kotlinx.serialization.json.JsonElement? =
    document?.let { app.pi.rpc.SettingsDocument.lookup(it, key) }

/** For failure detail strings: an absent file must not throw while reporting a failure. */
private fun File.readTextOrEmpty(): String = if (isFile) readText() else "<absent>"

private fun store(dir: File, projectFile: File? = null): PiSettingsFileStore =
    PiSettingsFileStore(
        globalFile = File(dir, "settings.json"),
        projectFile = projectFile,
        appLocalFile = File(dir, "app-prefs.json"),
    )

fun main() {
    // ------------------------------------------------------------------ §B1: remove
    run {
        val dir = tempDir("remove")
        val global = File(dir, "settings.json")
        val store = store(dir)
        store.write("httpIdleTimeoutMs", JsonPrimitive(300_000))
        check("write lands the key", global.readJsonOrNull()?.containsKey("httpIdleTimeoutMs") == true)

        store.remove("httpIdleTimeoutMs")
        val document = global.readJsonOrNull()
        check(
            "remove leaves the key absent (not null)",
            document != null && !document.containsKey("httpIdleTimeoutMs"),
            "document: $document",
        )
        check(
            "remove never writes JSON null",
            !global.readText().contains("null"),
            global.readText(),
        )
    }

    // Removing a nested key prunes the parents it empties (`SettingsDocument.removePath`):
    // `"compaction": {}` is not the same as an absent `compaction` to pi's merge.
    run {
        val dir = tempDir("remove-nested")
        val global = File(dir, "settings.json")
        val store = store(dir)
        store.write("compaction.reserveTokens", JsonPrimitive(16_384))
        store.write("compaction.keepRecentTokens", JsonPrimitive(20_000))
        store.remove("compaction.reserveTokens")
        val document = global.readJsonOrNull()
        check(
            "removing one nested key keeps its sibling",
            document?.get("compaction")?.let { it as? JsonObject }?.containsKey("keepRecentTokens") == true,
            "document: $document",
        )
        store.remove("compaction.keepRecentTokens")
        check(
            "removing the last nested key prunes the empty parent",
            global.readJsonOrNull()?.containsKey("compaction") == false,
            global.readText(),
        )
    }

    // §B1 scope rule: a project override is removed **where it lives**, so the global value
    // shows through again (pi's `persistScopedSettings` semantics).
    run {
        val dir = tempDir("scope")
        val global = File(dir, "settings.json")
        val projectDir = File(dir, "workspace")
        projectDir.mkdirs()
        val project = File(projectDir, "settings.json")
        val store = store(dir, project)

        store.write("theme", JsonPrimitive("dark"))                  // no project file yet -> global
        check("a write goes global when the project has no such key", global.readJsonOrNull()?.get("theme") != null)

        // Make the project the explicit scope for `theme`, as pi would.
        project.writeText("""{"theme":"light"}""")
        store.invalidate()
        store.write("theme", JsonPrimitive("nord"))
        check(
            "an existing project override is updated in the project document",
            project.readJsonOrNull()?.get("theme")?.toString() == "\"nord\"",
            "project: ${project.readTextOrEmpty()} global: ${global.readTextOrEmpty()}",
        )

        store.remove("theme")
        check(
            "removing a project override deletes it from the project document",
            project.readJsonOrNull()?.containsKey("theme") == false,
            "project: ${project.readTextOrEmpty()}",
        )
        check(
            "and leaves the global document alone",
            global.readJsonOrNull()?.get("theme")?.toString() == "\"dark\"",
            "global: ${global.readTextOrEmpty()}",
        )
    }

    // ------------------------------------------------------------------ §B1 app-local keys
    // `app.*` is not a pi setting: it goes to the sidecar, never into pi's document.
    // Dotted paths are **nested objects** in both documents (`SettingsDocument.setPath` walks
    // the dots), which is exactly how the old builds wrote `app.*` into pi's file — so both
    // the "not there" and the "legacy value is still read" assertions go through `lookup`.
    run {
        val dir = tempDir("applocal")
        val global = File(dir, "settings.json")
        val sidecar = File(dir, "app-prefs.json")
        val key = "app.appearance.messageDensity"
        val store = store(dir)
        store.write(key, JsonPrimitive("compact"))
        check(
            "an app.* key is not written into pi's settings.json",
            lookup(global.readJsonOrNull(), key) == null,
            "settings.json: ${global.readTextOrEmpty()}",
        )
        check(
            "an app.* key is written to the sidecar",
            lookup(sidecar.readJsonOrNull(), key)?.toString() == "\"compact\"",
            "app-prefs.json: ${sidecar.readTextOrEmpty()}",
        )
        check("an app.* key reads back", (store.read(key) as? JsonPrimitive)?.content == "compact")

        // The legacy copy an older build left in pi's document still answers, and the next
        // write of that key migrates it out. Written in the nested form, which is what this
        // store itself produced before the sidecar existed.
        global.writeText("""{"app":{"appearance":{"messageDensity":"cozy"}}}""")
        sidecar.writeText("{}")
        store.invalidate()
        check(
            "a legacy app.* value in pi's document is still read",
            (store.read(key) as? JsonPrimitive)?.content == "cozy",
            "read: ${store.read(key)}",
        )
        store.write(key, JsonPrimitive("compact"))
        check(
            "writing it drops the legacy copy from pi's document",
            lookup(global.readJsonOrNull(), key) == null,
            "settings.json: ${global.readTextOrEmpty()}",
        )
    }

    // -------------------------------------------------- §B2 one file, one cache and lock
    run {
        val dir = tempDir("single")
        val global = File(dir, "settings.json")
        val a = store(dir)
        val b = store(dir)   // the `WorkspaceStore` shape: its own instance, never invalidated
        a.write("theme", JsonPrimitive("dark"))
        check(
            "a second instance over the same file sees the first one's write",
            (b.read("theme") as? JsonPrimitive)?.content == "dark",
        )
        // The exact §B2 loss: B writes its own key from the document it had cached.
        b.write("app.workspace.current", JsonPrimitive("workspace-2"))
        check(
            "a second instance's write does not erase the first one's key",
            (a.read("theme") as? JsonPrimitive)?.content == "dark",
            "settings.json: ${global.readTextOrEmpty()}",
        )
        check(
            "shared(...) returns one instance per canonical triple",
            PiSettingsFileStore.shared(File(dir, "settings.json")) ===
                PiSettingsFileStore.shared(File(dir, "settings.json")),
        )
        check(
            "and a different triple is a different instance",
            PiSettingsFileStore.shared(File(dir, "settings.json")) !==
                PiSettingsFileStore.shared(File(dir, "settings.json"), File(dir, "p/settings.json")),
        )
    }

    // §B2 external writer: pi saves the file itself, under its own lock, with no notice to us.
    // The cache must notice the `(size, mtime)` change without anyone calling `invalidate()`.
    run {
        val dir = tempDir("external")
        val global = File(dir, "settings.json")
        val store = store(dir)
        store.write("theme", JsonPrimitive("dark"))
        check("the store cached the value", (store.read("theme") as? JsonPrimitive)?.content == "dark")
        global.writeText("""{"theme":"nord","defaultModel":"claude"}""")
        Thread.sleep(300)   // past STAMP_CHECK_INTERVAL_MS
        check(
            "an external change is picked up without invalidate()",
            (store.read("theme") as? JsonPrimitive)?.content == "nord",
            "read: ${store.read("theme")}",
        )
        check(
            "including a key the store never wrote",
            (store.read("defaultModel") as? JsonPrimitive)?.content == "claude",
        )
    }

    // ------------------------------------------------------------------ §B3 lock and temp names
    run {
        val dir = tempDir("lock")
        val global = File(dir, "settings.json")
        check(
            "the lock directory is pi's `<file>.lock`",
            PiSettingsLock.lockDirectoryFor(global).name == "settings.json.lock",
            PiSettingsLock.lockDirectoryFor(global).path,
        )
        check(
            "the lock directory sits beside the file",
            PiSettingsLock.lockDirectoryFor(global).parentFile == global.parentFile,
        )
        val store = store(dir)
        store.write("theme", JsonPrimitive("dark"))
        val leftovers = dir.listFiles().orEmpty().filter { it.name.contains(".tmp-") }
        check(
            "a write leaves no temp file behind (and its name is unique per call)",
            leftovers.isEmpty(),
            leftovers.joinToString { it.name },
        )
    }

    // §B3 lost update, end to end: two instances, two threads, one document. Before the shared
    // lock and the in-lock re-read, the last writer's snapshot won and every other key was gone.
    run {
        val dir = tempDir("concurrent")
        val global = File(dir, "settings.json")
        val stores = List(4) { store(dir) }
        val keys = (1..40).map { "key$it" }
        val threads = stores.mapIndexed { index, store ->
            Thread {
                keys.filterIndexed { position, _ -> position % stores.size == index }.forEach { key ->
                    store.write(key, JsonPrimitive(key))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val written = keys.filter { key -> (stores[0].read(key) as? JsonPrimitive)?.content == key }
        check(
            "concurrent writers to one document lose nothing",
            written.size == keys.size,
            "missing: ${(keys - written.toSet()).joinToString()}",
        )
        check(
            "the document on disk is parseable after concurrent writes",
            global.readJsonOrNull() != null,
            global.readText().take(200),
        )
    }

    // A corrupt document is moved aside, never overwritten in place (and the store must not
    // then write into the file it just rescued without noticing).
    run {
        val dir = tempDir("corrupt")
        val global = File(dir, "settings.json")
        global.writeText("{ this is not json")
        val store = store(dir)
        check("a corrupt document reads as empty", store.read("theme") == null)
        check(
            "a corrupt document is moved aside",
            dir.listFiles().orEmpty().any { it.name.startsWith("settings.json.corrupt-") },
            dir.listFiles().orEmpty().joinToString { it.name },
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
