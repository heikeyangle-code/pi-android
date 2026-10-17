package app.pi.ui

import app.pi.runtime.PiProjectConfig
import app.pi.ui.settings.DiscoverySource
import app.pi.ui.settings.PiSettingsStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * `app.prompts.system` / `app.prompts.append` — the **two prompt files**
 * pi reads, edited from the settings screen.
 *
 * ## Why these two rows are files and not a settings key
 *
 * pi's `systemPrompt` / `appendSystemPrompt` are not settings: `core/settings-schema.js`,
 * `settings-defaults.js` and `settings-manager.js` carry no such key, so a `systemPrompt`
 * left in `settings.json` would be a value pi never reads. They exist in exactly two
 * official shapes — the CLI flags `--system-prompt` / `--append-system-prompt`
 * (`dist/cli/args.js:297-298`) and these files — and the file shape has a priority chain
 * of its own (`dist/core/resource-loader.js:944-964`):
 *
 * ```
 * <workspace>/.pi/SYSTEM.md   (only when the file exists and the project is trusted)
 *   > <agentDir>/SYSTEM.md
 *   > pi's own built-in system prompt
 * ```
 *
 * `APPEND_SYSTEM.md` is the same shape (`discoverAppendSystemPromptFile()`), and it is
 * **one** file, not a merge of two.
 *
 * The app used to build the CLI flags out of these two rows instead. That added a layer on
 * top of pi's own mechanism and, because `resourceLoader` short-circuits its discovery as
 * soon as a flag is present (`this.systemPromptSource ?? this.discoverSystemPromptFile()`,
 * `dist/core/resource-loader.js:475`), it silently killed the files: a `SYSTEM.md` the user
 * wrote in 「Pi 文件」 was dead the whole time the row was non-empty. The row now **is** the
 * file, so there is one source of truth instead of two, and the priority chain above runs
 * as pi designed it.
 *
 * ## Where the value is written
 *
 * `app.prompts.system` → `<agentDir>/SYSTEM.md`, `app.prompts.append` →
 * `<agentDir>/APPEND_SYSTEM.md` ([PromptFileSlot]). The project-scoped copies under the
 * workspace stay reachable through the 「Pi 文件」 screen and through the workspace tree —
 * the settings rows own the **global** file only, which is what the rows say.
 *
 * ## What "unset" means, and why a blank value deletes the file
 *
 * pi tests `existsSync` on these files, and an **empty file that exists** is not "unset" —
 * it is an empty system prompt, which is strictly worse than pi's own. So a blank or empty
 * value removes the file rather than writing `""`: [PromptFileStore.write] deletes on
 * `isBlank`, and the text editor's "clear" gesture already routes to `remove()` as well
 * (`PiSettingEditorHost`). The invariant the two paths share is *"no file" == "pi's own
 * prompt"*; there is deliberately no third state.
 *
 * ## Read: BOM, and the missing failure channel
 *
 * pi reads these files through `stripBom` (`dist/core/resource-loader.js:100-114`), so the
 * editor text is stripped the same way — otherwise the user gets an invisible `U+FEFF` at
 * the head of the document and writes it back on every save.
 *
 * `PiSettingsStore.read` has **no failure channel**: its contract is "the value, or null
 * when not set", and the file store answers a corrupt `settings.json` with an empty
 * document rather than an error (`PiSettingsFileStore.readDocumentOrNull`). So a file that
 * exists but cannot be read (or is not a regular file — a directory, a dangling link)
 * reads as `null`, i.e. "not set". The alternatives are worse: throwing would take the
 * whole settings screen down over one unreadable file, and a sentinel string would be
 * loaded into the editor and written back as content. The honest report of that state lives
 * one row below, in the read-only 「系统提示词来源」 row ([PromptFileFacts]), which says the
 * file exists and cannot be read instead of pretending it is absent.
 *
 * ## No compatibility with the old values
 *
 * A value an older build stored for these two keys is **ignored**: the decorator answers
 * these keys itself and never consults [base], so neither the `app-prefs.json` copy (these
 * are `app.*` keys, so that is where they were written) nor a legacy copy in
 * `settings.json` has any effect. That is deliberate — no migration, no one-time copy, no
 * "fall back to the old key when the file is missing". A stale value that silently wins
 * over the file is the exact defect this change removes.
 */
/** One of the two prompt files this store owns. */
enum class PromptFileSlot(val settingKey: String, val fileName: String) {
    /** 整份替换 pi 自带的系统提示 — pi's CLI help: "System prompt (default: coding assistant prompt)". */
    SystemPrompt("app.prompts.system", "SYSTEM.md"),

    /** 追加在系统提示之后 — pi's CLI help: "Append text or file contents to the system prompt". */
    AppendSystemPrompt("app.prompts.append", "APPEND_SYSTEM.md"),
    ;

    companion object {
        fun forKey(key: String): PromptFileSlot? = PromptFileSlot.entries.firstOrNull { it.settingKey == key }
    }
}

/**
 * The two files under `agentDir`, cached the way [app.pi.settings.PiSettingsFileStore]
 * caches its documents.
 *
 * `read(key)` runs per row per frame on the composition thread, so it must not re-read a
 * multi-kilobyte document every time: the text is cached and the file's `(size, mtime)` is
 * compared again at most once per [STAMP_CHECK_INTERVAL_MS] — the same 250 ms discipline
 * and the same constants as the settings store, which is the shape this file's author
 * copied on purpose. `invalidate()` drops the cache outright for the one caller that knows
 * a write landed from outside ([PiSessionViewModel.invalidateSettingsCache]).
 */
class PromptFileStore(private val agentDir: File) {

    private class Entry(val file: File) {
        val lock = Any()

        /** The file's text with its BOM stripped, or null when absent/unreadable. */
        @Volatile
        var cached: String? = null

        /** `(size, mtime)` as of the read that filled [cached]; null when the file is absent. */
        @Volatile
        var stamp: String? = null

        /** When the stamp was last compared, so a per-frame read is not a per-frame `stat`. */
        @Volatile
        var checkedAtMs: Long = 0L

        fun load(): String? {
            val now = System.currentTimeMillis()
            val current = cached
            if (current != null && now - checkedAtMs < STAMP_CHECK_INTERVAL_MS) return current
            synchronized(lock) {
                val inside = cached
                val insideNow = System.currentTimeMillis()
                if (inside != null && insideNow - checkedAtMs < STAMP_CHECK_INTERVAL_MS) return inside
                val fresh = stampOf(file)
                if (inside != null && fresh == stamp) {
                    checkedAtMs = insideNow
                    return inside
                }
                val text = if (file.isFile) {
                    runCatching { file.readText().removePrefix(BOM) }.getOrNull()
                } else {
                    null
                }
                cached = text
                stamp = fresh
                checkedAtMs = insideNow
                return text
            }
        }
    }

    private val entriesByKey: Map<String, Entry> =
        PromptFileSlot.entries.associate { slot -> slot.settingKey to Entry(File(agentDir, slot.fileName)) }

    /** The file text for [key], BOM stripped, or null when the file is absent/unreadable. */
    fun read(key: String): String? = entriesByKey[key]?.load()

    /**
     * Stores [text] at [key], or **deletes the file** when it is blank.
     *
     * Blank (including whitespace-only) is not a value here: a file that exists with a blank
     * body makes pi use an empty system prompt, so "leave the row empty" must mean "no file"
     * for pi's built-in prompt to come back.
     */
    fun write(key: String, text: String) {
        val entry = entriesByKey[key] ?: return
        if (text.isBlank()) {
            clear(key)
            return
        }
        val file = entry.file
        file.parentFile?.mkdirs()
        val written = runCatching {
            // Unique per call, never `<name>.tmp-<pid>`: the 「Pi 文件」 screen writes this
            // same file through `PiConfigFiles.write`, whose temp name IS per process, so a
            // shared spelling would let the two writers interleave into one temp file before
            // either renamed it (the §B3 defect `PiSettingsFileStore` already fixed once).
            val temp = File(file.parentFile, "${file.name}.tmp-${TEMP_SEQUENCE.incrementAndGet()}")
            temp.writeText(text)
            runCatching { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                .onFailure {
                    // Cross-device rename can fail; fall back to a copy.
                    file.writeText(text)
                    temp.delete()
                }
            true
        }.getOrDefault(false)
        if (written) {
            synchronized(entry.lock) {
                entry.cached = text.removePrefix(BOM)
                entry.stamp = stampOf(file)
                entry.checkedAtMs = System.currentTimeMillis()
            }
        }
    }

    /** Deletes the file for [key] — pi's "no file", i.e. its own prompt. */
    fun clear(key: String) {
        val entry = entriesByKey[key] ?: return
        runCatching { entry.file.delete() }
        synchronized(entry.lock) {
            entry.cached = null
            entry.stamp = null
            entry.checkedAtMs = System.currentTimeMillis()
        }
    }

    /** Drops the cached text, so the next read hits the disk. */
    fun invalidate() {
        entriesByKey.values.forEach { entry ->
            synchronized(entry.lock) {
                entry.cached = null
                entry.stamp = null
                entry.checkedAtMs = 0L
            }
        }
    }

    private companion object {
        /** pi's `stripBom` (`dist/core/resource-loader.js:100-114`): only a leading one. */
        const val BOM = "\uFEFF"

        /** Unique temp-name suffix within this process; see [write]. */
        val TEMP_SEQUENCE = AtomicLong()

        /** Same bound as `PiSettingsFileStore`: four `stat`s per second per file at most. */
        const val STAMP_CHECK_INTERVAL_MS = 250L

        fun stampOf(file: File): String? =
            if (!file.exists()) null else "${file.length()}:${file.lastModified()}"
    }
}

/**
 * [PiSettingsStore] over [base], with the two prompt keys redirected to the files
 * ([PromptFileStore]).
 *
 * Every other key — reads, writes and removals — goes to [base] unchanged, so the settings
 * stack, its search, the `EffectiveKind` badges and the audit harness keep working through
 * the ordinary interface. This is the same decorator shape `ExtensionArgsStoreDecorator`
 * established for a row whose value must not live in pi's document; the difference is only
 * where the value is kept (a `SharedPreferences` there, two files pi reads here).
 */
class PromptFileSettingsStoreDecorator(
    private val base: PiSettingsStore,
    private val sidecar: PromptFileStore,
) : PiSettingsStore {

    override fun read(key: String): JsonElement? = when {
        PromptFileSlot.forKey(key) != null -> sidecar.read(key)?.let { JsonPrimitive(it) }
        else -> base.read(key)
    }

    override fun write(key: String, value: JsonElement) {
        if (PromptFileSlot.forKey(key) == null) {
            base.write(key, value)
            return
        }
        // The row's editor hands over a string; anything else (a `JsonNull`, a number) would be
        // a bug in the editor rather than user input, and clears the file rather than being
        // stringified into the prompt.
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (text == null) sidecar.clear(key) else sidecar.write(key, text)
    }

    override fun remove(key: String) {
        if (PromptFileSlot.forKey(key) == null) base.remove(key) else sidecar.clear(key)
    }
}

/**
 * The read-only 「提示词文件」 row's value: how many of pi's **four** prompt files exist, and at
 * which scope — as far as the app can honestly say.
 *
 * ## What it can and cannot answer
 *
 * pi's chain is *workspace `.pi/<name>` (when the project is trusted) > global
 * `<agentDir>/<name>` > built-in*, and the app **cannot** determine the middle condition from
 * here. `settingsManager.isProjectTrusted()` is not on pi's RPC surface (`get_state` carries no
 * trust field), and the app's own reproduction of pi's decision (`ProjectTrust.resolve` +
 * `TrustRepository`) is reached only from the packages screen, needs a guest `readlink -f` to
 * canonicalise the cwd the way pi hashes it, and answers a different question even then: it
 * resolves the decision *the app is about to ask the user for*, not what the already-running
 * pi process recorded (`docs/extension-compatibility.md:303` records this as DEGRADED).
 * Printing a trust verdict here would therefore be a guess about a security decision.
 *
 * So this reports the facts it can prove — **which files exist, and at which scope** — and the
 * row's description names the rule instead of a verdict.
 *
 * ## The shape is [app.pi.ui.settings.resourceFactOverrides]', and it reuses its words
 *
 * `<N> 份 · 项目（<工作区>/.pi） 1 · 全局（~/.pi/agent） 1` / `还没有任何提示词文件`, with the
 * source labels taken from [app.pi.ui.settings.DiscoverySource] rather than spelled again — the
 * four 「已发现的 …」 rows in this same screen answer the same question ("what does pi actually
 * load, and from where"), so they must read the same way. The classifier is 份 rather than 个
 * because these are **copies of the same name** in two scopes: which copy wins is the whole
 * question this row exists for.
 *
 * **Not** reported, deliberately: the size of each file. pi's own readout does not report it
 * either, and counting files needs no read at all — so this row no longer touches file
 * contents, only `isFile`/`exists`.
 *
 * ## Where this diverges from pi, and why that is the floor rather than laziness
 *
 * pi prints these as a list of paths in a multi-line `Context` section
 * (`modes/interactive/interactive-mode.js:1382-1392`) and omits the section when the list is
 * empty. The **list** is reachable here — as the row's evidence block ([detail]), which is the
 * multi-line channel this screen already has for read-only facts. What is *not* reachable is
 * "list empty ⇒ no row": the registry has no conditional-row mechanism, so the zero state
 * borrows the app's own word for it (`还没有…`, as in `还没有发现任何资源`) and the row stays
 * while only its evidence block disappears.
 */
object PromptFileFacts {

    /**
     * pi's two file names, in the order it consumes them: it builds the system prompt and then
     * appends (`core/system-prompt.js:78-98`).
     */
    private val PROMPT_FILES = listOf("SYSTEM.md", "APPEND_SYSTEM.md")

    /**
     * One candidate file. [display] is the spelling the rest of this screen already uses
     * (`~/.pi/agent/<name>` in the rows' descriptions), so the evidence block and the prose
     * name the same file the same way. [source] decides both the order and the word.
     *
     * `internal` — and so is everything below it — because [DiscoverySource] is `internal`
     * (it belongs to `PiResourceFacts`) and a **public** declaration may not expose an internal
     * type. The module compile rejects that outright (`'public' function exposes its 'internal'
     * parameter type 'DiscoverySource'`), and the bare-JVM harness closures do **not** reproduce
     * the check — they compile the files as one module, so this class of error only shows up in
     * CI's APK jobs. That is what this annotation is defending against, not style.
     */
    internal data class Candidate(val display: String, val source: DiscoverySource)

    /**
     * What a single pass over the four candidates found.
     *
     * Split out from the two formatters so the caller stats each file **once** and both strings
     * come from the same reading: the row's value and the evidence under it must never describe
     * different states.
     */
    internal data class Reading(val present: List<Candidate>, val notRegular: Int)

    /**
     * Stat the four candidates — **contents are never read** (nothing here reports a size, and
     * pi's own list does not either).
     */
    internal fun read(agentDir: File, workspace: File): Reading {
        val projectRoot = PiProjectConfig.root(workspace)
        // Workspace before agent dir, per name: that is pi's precedence within one name
        // (`docs/configuration.md:39`), and neither the count nor the list may read as if the
        // global copy came first. The order also matches `resourceFactOverrides`
        // (`ProjectPi.rank` < `Global.rank`).
        val candidates = buildList {
            for (name in PROMPT_FILES) {
                add(File(projectRoot, name) to Candidate("<工作区>/.pi/$name", DiscoverySource.ProjectPi))
                add(File(agentDir, name) to Candidate("~/.pi/agent/$name", DiscoverySource.Global))
            }
        }
        val present = candidates.filter { (file, _) -> file.isFile }.map { (_, candidate) -> candidate }
        // `existsSync` is true for these and pi's read then fails — the one state that must not
        // be folded into "absent". That is why this row stats the filesystem rather than
        // trusting the store.
        val notRegular = candidates.count { (file, _) -> file.exists() && !file.isFile }
        return Reading(present = present, notRegular = notRegular)
    }

    /** The row's value. Never a verdict: it does not claim to know whether a workspace copy is in effect. */
    internal fun value(reading: Reading): String {
        val oddity = if (reading.notRegular == 0) "" else "（${reading.notRegular} 项不是普通文件）"
        if (reading.present.isEmpty()) return "还没有任何提示词文件$oddity"
        val bySource = reading.present.groupingBy { it.source }.eachCount().entries
            .sortedBy { it.key.rank }
            .joinToString(" · ") { (source, count) -> "${source.label} $count" }
        return "${reading.present.size} 份 · $bySource$oddity"
    }

    /**
     * The evidence under the row: the files that exist, **one per line, in pi's reading order**
     * — the shape pi itself prints (`interactive-mode.js:1382-1392`). `null` when there is
     * nothing to list, which is also when the block is not drawn at all
     * (`SettingsGroupScreen` skips a blank detail): pi omits its section in that case, and this
     * is the closest this screen can get without a conditional-row mechanism.
     */
    internal fun detail(reading: Reading): String? {
        val lines = buildList {
            addAll(reading.present.map { it.display })
            if (reading.notRegular > 0) {
                add("（${reading.notRegular} 项不是普通文件，pi 会读失败）")
            }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
