package app.pi.packages

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
 * literal repeated in two files: [GuestCommand] asserts [bindsAgentDir] over the bind
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
     * True when [binds] carries the agent dir: the guest target is exactly
     * [GUEST_PATH], and its host source is [hostAgentDir].
     *
     * A bind to a different guest path is invisible to pi; a bind *from* a different
     * host directory is a different `settings.json` — the second one is the actual
     * bug this predicate exists to catch.
     */
    fun bindsAgentDir(binds: List<Pair<String, String>>, hostAgentDir: String): Boolean =
        binds.contains(hostAgentDir to GUEST_PATH)
}

/** One list row, as `pi list` describes it (`package-manager-cli.ts:970-1004`). */
data class PiPackageEntry(
    val source: PiPackageSource,
    val scope: PiPackageScope,
    /** True for the object form in settings (`filtered` in pi's output). */
    val filtered: Boolean,
    /** `pkg.installedPath`, when pi resolved one. */
    val installedPath: String?,
)
