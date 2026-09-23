package app.pi.packages

import java.io.File

/**
 * The value types shared by the package service, the list parser and the UI:
 * [PiPackageScope], [PiPackageEntry], [PiBuiltinExtension] (what the APK ships
 * itself, which pi has no concept of — see that type's own note), plus
 * [PiAgentDirContract], the engine/guest-command agreement about which directory is
 * pi's agent dir.
 *
 * They live in their own file so that [PiListOutput] — which is pure string
 * handling over `pi list`'s output — does not have to depend on the Android-facing
 * [PiPackageService] and its [GuestCommand]. Keeping the parser free of Android is
 * what makes it checkable off-device, and this parser is exactly the kind of code
 * that silently lies when it is wrong: a mis-parsed `pi list` looks identical to
 * "no packages installed".
 */

/** `install`/`remove` write to one of pi's two settings documents. */
enum class PiPackageScope(
    /** The CLI flag pi uses for this scope: `""` or `-l`. */
    val flag: String,
    /** App-side label; pi's own names are "user settings" / "project settings". */
    val label: String,
) {
    /** `~/.pi/agent/settings.json` — pi's default (`docs/packages.md:43`). */
    User("", "全局（用户）"),

    /** `.pi/settings.json` in the project — `pi install -l`. */
    Project("-l", "项目"),
}

/**
 * One extension the APK ships in its own assets — and where the app learned that.
 *
 * ## pi does not have this concept, so the app must not pretend it reported it
 *
 * Nothing in pi marks an extension as "built in". Everything under
 * `<agentDir>/extensions/` is auto-discovered and labelled `source: "auto"`,
 * `scope: "user"` (`core/package-manager.ts:2352-2362`, collected at `:2470-2475`),
 * which is the same label a file the user dropped in there by hand receives; and
 * `pi list` reports only the `packages` array of `settings.json`
 * (`package-manager-cli.ts:970-1002`, reading `package-manager.ts:977-1003`), so a
 * shipped extension never appears in its output at all.
 *
 * The one authority for "this one came with the app" is therefore the app's own
 * asset tree, `app/src/main/assets/pi-extensions/`, which
 * `bridge/DeviceBridgeController.kt:264-306` copies into `<agentDir>/extensions/`.
 * [SHIPPED] is a transcription of that tree, and the UI has to say the label comes
 * from here rather than from pi.
 *
 * ## The entry file, not the copied directory
 *
 * [entryUnderExtensions] is what pi's discovery actually loads, and that is not
 * always the thing that was copied. `resolveExtensionEntries`
 * (`core/package-manager.ts:557-585`) accepts a subdirectory only when it contains
 * `index.ts` or `index.js`, so the two asset directories are loaded as their
 * `index.ts` while the single-file asset is loaded as itself. The `index.ts`
 * siblings (`client.ts`, `danger.ts`, `hljs.ts`, …) are never separate extensions,
 * and `collectAutoExtensionEntries` (`:587-639`) skips the installer's
 * `.pi-android-assets` stamp because names starting with a dot are ignored at
 * `:604`.
 */
data class PiBuiltinExtension(
    /** The asset directory or file name, exactly as it appears in the APK. */
    val name: String,
    /** Path below `<agentDir>/extensions/` that pi loads. */
    val entryUnderExtensions: String,
) {

    /**
     * The guest spelling of the file pi loads, for a given guest agent dir
     * (`AgentLayout.guestAgentDir` is `/root/.pi/agent`, pinned by
     * `PiEngineHost.kt:302`).
     */
    fun guestEntryPath(guestAgentDir: String): String =
        "$guestAgentDir/extensions/$entryUnderExtensions"

    /**
     * Where the shipped file was actually found.
     *
     * Three states rather than two, because the app has two agent directories and
     * they are not interchangeable: `PiEngineHost` binds the durable one over guest
     * `/root/.pi/agent` for the engine it launches (`PiEngineHost.kt:285-294`), while
     * [GuestCommand] builds its proot argv with the workspace as the *only* extra
     * bind (`GuestCommand.kt:98-106`), so a package command reads the rootfs copy.
     * A file present in one and not the other is a real, printable difference — and
     * the exact difference that makes "it is installed" and "pi loads it" two
     * different sentences.
     */
    enum class Presence {
        /** In the directory the running engine reads: pi will load it. */
        EngineAgentDir,

        /** Only in the rootfs copy, which the engine's bind shadows: pi will not. */
        RootfsCopyOnly,

        /** Neither copy has it. */
        Missing,
    }

    fun presenceIn(engineAgentDirHasEntry: Boolean, rootfsHasEntry: Boolean): Presence = when {
        engineAgentDirHasEntry -> Presence.EngineAgentDir
        rootfsHasEntry -> Presence.RootfsCopyOnly
        else -> Presence.Missing
    }

    companion object {
        /**
         * Transcription of `app/src/main/assets/pi-extensions/` (8 files, 3 entries),
         * with the entry each one is discovered as. Keep this list in step with the
         * asset tree: it is the only record that these are the app's, and it is what
         * the UI labels a row from.
         */
        val SHIPPED: List<PiBuiltinExtension> = listOf(
            PiBuiltinExtension("pi-android-bridge", "pi-android-bridge/index.ts"),
            PiBuiltinExtension("pi-android-permission-gate", "pi-android-permission-gate.ts"),
            PiBuiltinExtension("pi-highlight", "pi-highlight/index.ts"),
        )
    }
}

/**
 * The one guest path the app and the engine must agree on, and the check that they do.
 *
 * `PiEngineHost` launches pi with the durable agent dir bound over the guest's
 * `/root/.pi/agent` (`PiEngineHost.kt:285-294`) and pins the same path in the
 * environment (`:302-303`). Every other process the app starts *for* pi has to do the
 * same, or the two disagree about which `settings.json`, `npm/` tree, `extensions/`
 * and `trust.json` are pi's — and that failure is silent in the worst way: `pi install`
 * prints `Installed npm:foo`, exits 0, and the running engine reads a directory that
 * never saw it. [GuestCommand] had exactly this hole until its bind was added.
 *
 * This object exists so the agreement is a value that can be checked rather than a
 * literal repeated in two files: [GuestCommand] asserts [misleadingAgentDirBinds] over the bind
 * list it hands to proot, and `PackagesPureLogicCheck` covers the predicate.
 */
object PiAgentDirContract {

    /** `PiEngineHost.guestAgentDir` — pi's agent dir inside the guest. */
    const val GUEST_PATH: String = "/root/.pi/agent"

    /** The variable pi resolves its agent dir from (`config.ts:528-532`). */
    const val ENV_VAR: String = "PI_CODING_AGENT_DIR"

    /** Also set by the engine (`PiEngineHost.kt:303`); pi's default is this too. */
    const val SESSION_ENV_VAR: String = "PI_CODING_AGENT_SESSION_DIR"

    fun sessionDir(guestAgentDir: String): String = "$guestAgentDir/sessions"

    /**
     * The binds in [binds] that would put a **different** host directory on the guest's
     * [GUEST_PATH], or on something under it. Empty is the healthy answer.
     *
     * The agreement used to be "every launch path binds the *same* host directory to this
     * guest path", and the predicate that asserted it was `bindsAgentDir(binds, host)`.
     * Since 2026-09-23 the agreement is **structural** instead: [GUEST_PATH] is the rootfs
     * directory `PiPaths.agentDir`, so a guest command reaches the very directory the
     * engine does **with no bind at all** — and a bind on that path is now the only way to
     * break it, which is what this reports.
     */
    fun misleadingAgentDirBinds(binds: List<Pair<String, String>>): List<Pair<String, String>> =
        binds.filter { (_, guest) -> guest == GUEST_PATH || guest.startsWith("$GUEST_PATH/") }
}

/** One list row, as `pi list` describes it (`package-manager-cli.ts:970-1004`). */
data class PiPackageEntry(
    val source: PiPackageSource,
    val scope: PiPackageScope,
    /** True for the object form in settings (`filtered` in pi's output). */
    val filtered: Boolean,
    /** `pkg.installedPath`, when pi resolved one. */
    val installedPath: String?,
    /**
     * The four glob arrays of the object form, keyed by resource type
     * (`extensions` / `skills` / `prompts` / `themes`), exactly as
     * `settings.json` holds them. Empty for the bare-string form.
     *
     * `pi list` reports nothing but the `(filtered)` suffix, so these come from
     * [PiPackageFilters] reading the settings documents — without that, a package
     * whose filters were set in pi's own TUI looked like a plain package here.
     */
    val filters: Map<String, List<String>> = emptyMap(),
)

/**
 * What pi would load from an `extensions/` directory — the extensions that are
 * **not** packages, and therefore not in `pi list`.
 *
 * ## Why this type exists
 *
 * The packages screen had exactly two sources: the three extensions this app ships
 * (`PiBuiltinExtension.SHIPPED`, found by looking at the filesystem) and the rows
 * `pi list` reports, which come from `settings.json`'s `packages` array
 * (`package-manager-cli.ts:970-1002`). An extension **written straight into
 * `<agentDir>/extensions/`** — by the user, or by the agent itself through `bash` —
 * is neither: pi loads it (`collectAutoExtensionEntries`), the app never listed it,
 * and the screen showed only the app's own three while an extension the model had
 * just written was running invisibly.
 *
 * ## The rules, from pi
 *
 * `collectAutoExtensionEntries` (`core/package-manager.ts:587-639`):
 *
 *  1. if the directory itself carries `package.json`'s `pi.extensions` manifest, those
 *     entries are the whole list (`resolveExtensionEntries`, `:557-572`); otherwise
 *     `index.ts`, then `index.js` (`:574-584`);
 *  2. otherwise scan its entries, skipping names beginning with `.` and
 *     `node_modules` (`:602-603`), following symlinks (`:607-614`), honouring the
 *     directory's `.gitignore` (`:617`, `:619-620`);
 *  3. a **file** counts when it ends in `.ts` or `.js` (`:623`) — not `.mts`/`.cjs`;
 *  4. a **directory** counts only when rule 1 finds an entry inside it (`:625-630`).
 *
 * Rule 2's `.gitignore` handling is the one part not reproduced here: reading git
 * ignore rules is a dependency this screen does not otherwise need, and an
 * `extensions/` directory is not a checkout. The consequence is named where it shows
 * — this reader can list a file pi would skip.
 */
object PiAutoExtensions {

    /** One entry pi would load, named for a person. */
    data class Found(
        /** The directory or file name, without the source suffix for a file. */
        val name: String,
        /** Path relative to the directory scanned, for the row's detail line. */
        val relativePath: String,
    )

    /**
     * Every extension entry under [root], per pi's rules above.
     *
     * [root] itself is checked first: a directory that *is* an extension (its own
     * `index.ts`, or a `package.json` manifest) is a single entry, which is how pi's
     * installer re-exports a whole package.
     */
    fun discover(root: File): List<Found> {
        if (!root.isDirectory) return emptyList()
        val own = resolveEntries(root)
        if (own != null) return own
        return root.listFiles().orEmpty()
            .filter { !it.name.startsWith(".") && it.name != "node_modules" }
            .sortedBy { it.name }
            .flatMap { entry ->
                when {
                    entry.isFile && (entry.name.endsWith(".ts") || entry.name.endsWith(".js")) ->
                        listOf(Found(entry.name.substringBeforeLast('.'), entry.name))
                    entry.isDirectory -> resolveEntries(entry).orEmpty()
                    else -> emptyList()
                }
            }
    }

    /**
     * pi's rule 1 for one directory: its `package.json` `pi.extensions` entries when
     * it has that manifest, else `index.ts`, else `index.js`, else nothing.
     *
     * The manifest branch is modelled because ignoring it would make a package copied
     * into `extensions/` look like an empty directory; the entries it names are not
     * resolved to files (that needs pi's manifest parsing), so the row is labelled
     * with the directory.
     */
    private fun resolveEntries(dir: File): List<Found>? {
        val manifest = File(dir, "package.json")
        if (manifest.isFile) {
            val names = runCatching {
                val root = kotlinx.serialization.json.Json.parseToJsonElement(manifest.readText())
                (((((root as? kotlinx.serialization.json.JsonObject)?.get("pi")) as? kotlinx.serialization.json.JsonObject)
                    ?.get("extensions")) as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    .orEmpty()
            }.getOrDefault(emptyList())
            if (names.isNotEmpty()) {
                return listOf(Found(dir.name, dir.name))
            }
        }
        for (candidate in listOf("index.ts", "index.js")) {
            if (File(dir, candidate).isFile) {
                return listOf(Found(dir.name, File(dir, candidate).relativeTo(dir.parentFile ?: dir).path))
            }
        }
        return null
    }
}
