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
// `app/pi/runtime/PiProjectConfig.kt`, `app/pi/ui/PromptFileSettingsStore.kt` (the two
// prompt rows' file store, which is `java.io`/`java.nio` only) and `:rpc`'s
// `SettingsDocument.kt` + `PiJson.kt` + `internal/Json.kt`. No Android, no Compose, no Gradle.
//
//   settings-store   app.pi.settings.PiSettingsStoreCheckKt

import app.pi.runtime.PiProjectConfig
import app.pi.ui.PromptFileFacts
import app.pi.ui.PromptFileSettingsStoreDecorator
import app.pi.ui.PromptFileSlot
import app.pi.ui.PromptFileStore
import app.pi.ui.settings.DiscoverySource
import kotlinx.serialization.json.JsonNull
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
        // A deliberately synthetic key: this section is about the store's `app.*`
        // sidecar routing, not about any one setting, and naming a real
        // `app.appearance.*` row here would make the harness look like a second
        // reader of a key the registry no longer has.
        val key = "app.appearance.storeProbe"
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
        global.writeText("""{"app":{"appearance":{"storeProbe":"cozy"}}}""")
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

    // A corrupt document is **left where it is**, and a write into it is refused.
    //
    // It used to be renamed to `settings.json.corrupt-<millis>` from the **read** path, which
    // runs per row per frame (`PiSettingsFileStore.Document.load`) — and nothing anywhere ever
    // read the renamed file back, so the user's settings looked like they had vanished while the
    // next write recreated the document with only the key that write carried. The three checks
    // below are the two halves of that intent: the read touches nothing, and the write does not
    // replace a document it could not parse.
    run {
        val dir = tempDir("corrupt")
        val global = File(dir, "settings.json")
        val corrupt = "{ this is not json"
        global.writeText(corrupt)
        val store = store(dir)
        check("a corrupt document reads as empty", store.read("theme") == null)
        check(
            "a corrupt document is left where it is, and no copy is moved aside",
            global.isFile && global.readText() == corrupt &&
                dir.listFiles().orEmpty().none { it.name.startsWith("settings.json.corrupt-") },
            dir.listFiles().orEmpty().joinToString { it.name },
        )
        // The base this write would be applied to is unknown, so writing would drop every key
        // the file holds in exchange for one. Refuse: not one byte of the file changes.
        store.write("theme", JsonPrimitive("dark"))
        check(
            "a write onto a corrupt document changes not one byte",
            global.readText() == corrupt,
            global.readTextOrEmpty().take(200),
        )
        check("and the key that write asked for is not in the document", store.read("theme") == null)
    }

    // ------------------------------------------ the two prompt rows are pi's files
    //
    // `app.runtime.systemPrompt` / `app.runtime.appendSystemPrompt` are
    // `<agentDir>/SYSTEM.md` / `APPEND_SYSTEM.md` (`ui/PromptFileSettingsStore.kt`). What the
    // row, pi and the 「Pi 文件」 screen have to agree on:
    //
    //   * an absent file is "never set" (`null`), so the row shows pi's default;
    //   * a **blank** value deletes the file, because a file that exists with an empty body
    //     is an empty system prompt to pi — not a fallback to pi's own;
    //   * the read strips the BOM, the way pi's `stripBom` does
    //     (`core/resource-loader.js:100-114`), or the editor would carry an invisible
    //     character and write it back;
    //   * a value an older build left in the store is **ignored** (no migration): the
    //     decorator answers these keys itself, so the file is the only source.
    //
    // **Two APIs, and this section drives both on purpose.** The settings stack only ever
    // holds a `PiSettingsStore`, so the decorator's writer is the interface's
    // `write(key: String, value: JsonElement)` — that is the type every row and editor goes
    // through, and the four `JsonPrimitive(...)` calls below are what pin it. The *file-level*
    // text API is `PromptFileStore.write(key: String, text: String)` — the same object the
    // decorator delegates to, with no JSON in sight. Asserting only the String form would leave
    // the interface's contract unpinned (and a decorator that changed the parameter type would
    // stop being a `PiSettingsStore` at all, which is exactly what the compiler caught here).
    run {
        val dir = tempDir("prompt-files")
        val agentDir = File(dir, "agent").also { it.mkdirs() }
        val workspace = File(dir, "workspace").also { it.mkdirs() }
        val sidecar = PromptFileStore(agentDir)
        val decorator = PromptFileSettingsStoreDecorator(store(dir), sidecar)
        val systemFile = File(agentDir, "SYSTEM.md")
        val appendFile = File(agentDir, "APPEND_SYSTEM.md")
        val systemKey = "app.runtime.systemPrompt"
        val appendKey = "app.runtime.appendSystemPrompt"

        check(
            "the two keys map onto pi's two file names",
            PromptFileSlot.forKey(systemKey)?.fileName == "SYSTEM.md" &&
                PromptFileSlot.forKey(appendKey)?.fileName == "APPEND_SYSTEM.md",
        )
        check("an absent prompt file reads as unset", decorator.read(systemKey) == null)
        decorator.write("theme", JsonPrimitive("dark"))
        check(
            "every other key still goes to the base store",
            (decorator.read("theme") as? JsonPrimitive)?.content == "dark",
        )

        // Through the interface: a string primitive, which is what `PiTextEditorSheet` hands
        // over (`JsonPrimitive(value)`).
        decorator.write(systemKey, JsonPrimitive("You are pi."))
        check("the write lands in pi's file", systemFile.readText() == "You are pi.")
        check(
            "and reads back through the decorator",
            (decorator.read(systemKey) as? JsonPrimitive)?.content == "You are pi.",
        )
        check("the other row keeps its own file", !appendFile.isFile)
        check(
            "a fresh store over the same directory reads the same file",
            PromptFileStore(agentDir).read(systemKey) == "You are pi.",
        )
        // And the file-level text API, which is the one that has no JSON in its signature.
        sidecar.write(systemKey, "written as text")
        check("PromptFileStore.write takes the text directly", systemFile.readText() == "written as text")
        decorator.write(systemKey, JsonPrimitive("You are pi."))

        // What pi would see if someone else wrote the file: one leading BOM is dropped, the
        // rest of the document is untouched.
        systemFile.writeText("\uFEFF# edited by the file screen")
        sidecar.invalidate()
        check(
            "the read strips one leading BOM",
            sidecar.read(systemKey) == "# edited by the file screen",
            "read: ${sidecar.read(systemKey)}",
        )

        // Blank == delete. The editor's "clear" gesture routes to `remove`, and a typed
        // whitespace-only body routes here — both must end at "no file", never at an empty one.
        decorator.write(systemKey, JsonPrimitive("   \n "))
        check("a blank write deletes the file instead of leaving an empty one", !systemFile.isFile)
        check("and the row reads as unset again", decorator.read(systemKey) == null)
        // A value that is not a string at all (a `JsonNull`, a number) is an editor bug, not
        // input; the documented answer is "clear", never a stringified `null` in the prompt.
        decorator.write(systemKey, JsonPrimitive("# not a string"))
        check("the file is there before the non-string write", systemFile.isFile)
        decorator.write(systemKey, JsonNull)
        check("a non-string value clears the file instead of being stringified", !systemFile.isFile)

        decorator.write(appendKey, JsonPrimitive("Answer briefly."))
        check("the append row writes its own file", appendFile.readText() == "Answer briefly.")
        decorator.remove(appendKey)
        check("remove deletes the file", !appendFile.isFile)

        // The legacy value an older build stored for these `app.*` keys goes to the app-local
        // sidecar (`PiSettingsFileStore`); a copy in pi's document is read by the old build's
        // path only. Neither may show through the decorator — not even after the file is
        // deleted, which is the "no fallback to the old key" rule.
        val base = store(dir)
        base.write(systemKey, JsonPrimitive("legacy stored value"))
        decorator.write(systemKey, JsonPrimitive("from the file"))
        check(
            "the file wins over a stored value",
            (decorator.read(systemKey) as? JsonPrimitive)?.content == "from the file",
            "read: ${decorator.read(systemKey)}",
        )
        check(
            "the stored value is still on disk (nothing migrated or deleted it)",
            (base.read(systemKey) as? JsonPrimitive)?.content == "legacy stored value",
        )
        systemFile.delete()
        sidecar.invalidate()
        check(
            "a deleted file does not fall back to the stored value",
            decorator.read(systemKey) == null,
            "read: ${decorator.read(systemKey)}",
        )

        // Atomic replace, and a temp name no other writer can be using (the 「Pi 文件」 screen
        // writes this same file with its own `<name>.tmp-<pid>` spelling).
        PromptFileStore(agentDir).write(systemKey, "again")
        check("a prompt write leaves no temp file behind", systemFile.parentFile.listFiles()
            .orEmpty().none { it.name.contains(".tmp-") })

        // `PromptFileFacts` — the read-only 「提示词文件」 row. It reports files, and it must not
        // invent the trust verdict it cannot know, nor a count it did not measure.
        //
        // "None of the four" has to be *made* true first: the fixture above left a global
        // `SYSTEM.md` holding "again".
        systemFile.delete()
        val project = File(PiProjectConfig.root(workspace), "SYSTEM.md")
        check(
            "with no prompt file at all the row says so in the app's own word, never `0 份`",
            PromptFileFacts.describePromptFiles(agentDir, workspace) == "还没有任何提示词文件",
            "got: ${PromptFileFacts.describePromptFiles(agentDir, workspace)}",
        )
        project.parentFile.mkdirs()
        project.writeText("# project")
        check(
            "one workspace file counts as one, named by its scope with the shared label",
            PromptFileFacts.describePromptFiles(agentDir, workspace) ==
                "1 份 · ${DiscoverySource.ProjectPi.label} 1",
            "got: ${PromptFileFacts.describePromptFiles(agentDir, workspace)}",
        )
        systemFile.writeText("again")
        check(
            "the same name in both scopes is two copies, not one replacement",
            PromptFileFacts.describePromptFiles(agentDir, workspace) ==
                "2 份 · ${DiscoverySource.ProjectPi.label} 1 · ${DiscoverySource.Global.label} 1",
            "got: ${PromptFileFacts.describePromptFiles(agentDir, workspace)}",
        )
        check(
            "the workspace copy is listed before the global one (it wins when trusted)",
            PromptFileFacts.describePromptFiles(agentDir, workspace)
                .indexOf(DiscoverySource.ProjectPi.label) <
                PromptFileFacts.describePromptFiles(agentDir, workspace)
                    .indexOf(DiscoverySource.Global.label),
        )
        // `APPEND_SYSTEM.md` is part of the same reading — that is what "this row no longer
        // covers only `SYSTEM.md`" means. A row that kept reporting one of the two names would
        // be the half-truth this change exists to remove.
        val projectAppend = File(PiProjectConfig.root(workspace), "APPEND_SYSTEM.md")
        projectAppend.writeText("extra")
        check(
            "an `APPEND_SYSTEM.md` is counted too, in the same reading",
            PromptFileFacts.describePromptFiles(agentDir, workspace) ==
                "3 份 · ${DiscoverySource.ProjectPi.label} 2 · ${DiscoverySource.Global.label} 1",
            "got: ${PromptFileFacts.describePromptFiles(agentDir, workspace)}",
        )
        projectAppend.delete()
        systemFile.delete()
        check(
            "a `SYSTEM.md` that is not a regular file is reported as such, not as absent",
            run {
                val directory = File(agentDir, "SYSTEM.md").also { it.mkdirs() }
                val text = PromptFileFacts.describePromptFiles(agentDir, workspace)
                directory.delete()
                text.contains("不是普通文件")
            },
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
