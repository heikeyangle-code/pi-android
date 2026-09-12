package app.pi.packages

// Package-internal pure-logic checks, runnable on a bare JVM with no Gradle.
//
// Why this file has no test framework: this module's build has no
// `testImplementation(libs.junit)` and adding one means editing
// `app/build.gradle.kts`, which this package does not own. It is a plain `main()`
// instead, so nothing about the Gradle build has to change for it to exist.
//
// **RUN BY CI since 2026-09-11**, by `tools/run-app-pure-checks.sh` from the `pure-checks`
// job. The first CI run reported ClassNotFoundException for this class, and that was a
// symptom rather than the cause: the compiler had thrown before it compiled anything (its
// own classpath was missing kotlinx-coroutines), and the runner of that day had no guard
// for "produced nothing", so the missing class was the only thing it could report. The
// `package` line was never missing - this file has always declared it, further down.
// `tools/typecheck.sh` compiles `app/src/main/kotlin` only, so it never saw this file.
//
//   cd /root/pi-android
//   KOTLINC_CP="$(find build/typecheck/kotlinc -name '*.jar' | tr '\n' ':')"
//   G="$HOME/.gradle/caches/modules-2/files-2.1"
//   LIBS="$(find "$G/org.jetbrains.kotlin/kotlin-stdlib/2.2.21" -name 'kotlin-stdlib-2.2.21.jar'):\
//   $(find "$G/org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.9.0" -name '*.jar'):\
//   $(find "$G/org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.9.0" -name '*.jar'):\
//   $(find "$G/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0" -name '*.jar')"
//   java -cp "$KOTLINC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 \
//     -classpath "$LIBS" -d /tmp/pkgcheck/out \
//     app/src/test/kotlin/app/pi/packages/PackagesPureLogicCheck.kt \
//     app/src/main/kotlin/app/pi/packages/{TrustFile,PiPackageSource,ProjectTrust,PiPackageModel,PiListOutput,ExtensionLifecycle}.kt \
//     rpc/src/main/kotlin/app/pi/rpc/Ansi.kt
//   java -cp "/tmp/pkgcheck/out:$LIBS" PackagesPureLogicCheckKt
//
// It checks the parts that can silently be wrong: pi's trust.json byte format,
// nearest-ancestor trust lookup, the five prompt options, the `--approve` /
// `-l` semantics, pi's package-source classification (including the surprising
// "a plain https URL is a local path" rule), `pi list` parsing (including the
// difference between "no packages" and "unparseable"), every restart
// transition including the turn-running rule, and the built-in-vs-installed
// classification the package screen labels its rows from (E7: pi itself has no
// such concept, so the shipped set is the app's own transcription).

import app.pi.packages.ExtensionLifecycle
import app.pi.packages.PiAgentDirContract
import app.pi.packages.PiBuiltinExtension
import app.pi.packages.PiListOutput
import app.pi.packages.PiPackageScope
import app.pi.packages.PiPackageSource
import app.pi.packages.ProjectTrust
import app.pi.packages.TrustFile
import app.pi.packages.TrustStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

// A bare-JVM harness for the pure half of app.pi.packages. Not shipped in :app —
// it is written and run out-of-tree precisely so `tools/typecheck.sh` stays the
// only thing that compiles the module.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // ---------------------------------------------------------------- TrustFile
    // pi writes `JSON.stringify(sorted, null, 2) + "\n"`, keys sorted.
    val store: TrustStore = linkedMapOf(
        "/workspace/b" to false,
        "/workspace" to true,
        "/workspace/a" to null,
    )
    check(
        "serialize matches JSON.stringify(...,null,2)+newline",
        TrustFile.serialize(store),
        "{\n  \"/workspace\": true,\n  \"/workspace/a\": null,\n  \"/workspace/b\": false\n}\n",
    )
    check("serialize empty store is {} plus newline", TrustFile.serialize(emptyMap()), "{}\n")

    // Round trip, including BOM stripping (pi reads through stripBom).
    val parsed = TrustFile.parse("\uFEFF" + TrustFile.serialize(store), "/x/trust.json")
    check("parse round trips", (parsed as? TrustFile.Parse.Ok)?.store, store)

    // pi's three failure modes, verbatim wording.
    check(
        "missing trailing newline still parses",
        (TrustFile.parse("{\"/a\": true}", "/t.json") as? TrustFile.Parse.Ok)?.store,
        mapOf("/a" to true),
    )
    check(
        "array is not a store",
        (TrustFile.parse("[]", "/t.json") as TrustFile.Parse.Invalid).message,
        "Invalid trust store /t.json: expected an object",
    )
    check(
        "string value is rejected with pi's message",
        (TrustFile.parse("{\"/a\": \"yes\"}", "/t.json") as TrustFile.Parse.Invalid).message,
        "Invalid trust store /t.json: value for \"/a\" must be true, false, or null",
    )
    check(
        "malformed json is a read failure",
        (TrustFile.parse("{oops", "/t.json") as TrustFile.Parse.Invalid).message.startsWith(
            "Failed to read trust store /t.json: ",
        ),
        true,
    )

    // Nearest ancestor wins; null is skipped, and that is the security-relevant bit.
    val walk: TrustStore = mapOf(
        "/" to null,
        "/workspace" to true,
        "/workspace/pi/workspaces/workspace-1/.pi" to false,
    )
    check(
        "nearest ancestor wins for a grandchild",
        TrustFile.nearest(walk, "/workspace/pi/workspaces/workspace-1")?.path,
        "/workspace",
    )
    check(
        "a closer false beats a further true",
        TrustFile.nearest(walk, "/workspace/pi/workspaces/workspace-1/.pi/skills")?.decision,
        false,
    )
    check("null entries are skipped", TrustFile.nearest(mapOf("/" to null), "/a/b"), null)
    check("walk terminates at the root", TrustFile.nearest(emptyMap(), "/a/b/c"), null)

    // Node dirname semantics, since trust keys are guest POSIX paths.
    check("dirname /a/b", TrustFile.posixDirname("/a/b"), "/a")
    check("dirname /a", TrustFile.posixDirname("/a"), "/")
    check("dirname /", TrustFile.posixDirname("/"), "/")
    check("dirname /a/ (trailing slash)", TrustFile.posixDirname("/a/"), "/")
    check("parentOf root is null", TrustFile.parentOf("/"), null)
    check("parentOf /a is /", TrustFile.parentOf("/a"), "/")

    // setMany: null deletes, exactly as pi does.
    check(
        "applyUpdates deletes on null",
        TrustFile.applyUpdates(mapOf("/a" to true, "/b" to false), listOf("/a" to null, "/b" to true)),
        mapOf("/b" to true),
    )

    // ------------------------------------------------------------- prompt text
    check(
        "prompt text is pi's formatProjectTrustPrompt",
        ProjectTrust.promptText("/workspace/x"),
        "Trust project folder?\n/workspace/x\n\nThis allows pi to load .pi settings and resources, " +
            "install missing project packages, and execute project extensions.",
    )

    // pi's five options, in pi's order.
    val options = ProjectTrust.options("/workspace/x")
    check(
        "five options in pi's order",
        options.map { it.label },
        listOf(
            "Trust",
            "Trust parent folder (/workspace)",
            "Trust (this session only)",
            "Do not trust",
            "Do not trust (this session only)",
        ),
    )
    check("Trust writes true for the cwd", options[0].updates, listOf("/workspace/x" to true))
    check(
        "Trust parent writes parent true then child null",
        options[1].updates,
        listOf("/workspace" to true, "/workspace/x" to null),
    )
    check("session-only options write nothing", options[2].updates, emptyList<Pair<String, Boolean?>>())
    check("Do not trust writes false", options[3].updates, listOf("/workspace/x" to false))
    check("options at / have no parent entry", ProjectTrust.options("/").size, 4)

    // Resolution order.
    fun resolve(
        override: Boolean? = null,
        trigger: Boolean = true,
        saved: Boolean? = null,
        default: String = "ask",
        hasUI: Boolean = false,
        answer: Boolean? = null,
    ) = ProjectTrust.resolve(
        ProjectTrust.Inputs(
            cwd = "/workspace/x",
            trustOverride = override,
            hasTrustRequiringResources = trigger,
            savedDecision = saved,
            defaultProjectTrust = default,
            hasUI = hasUI,
            userAnswer = answer,
        ),
    )

    check("no trigger is trusted with no prompt", resolve(trigger = false).rationale, ProjectTrust.Rationale.NoTrustRequiringResources)
    check("cli override wins", resolve(override = true, saved = false).rationale, ProjectTrust.Rationale.CliOverride)
    check("saved true is honoured", resolve(saved = true).rationale, ProjectTrust.Rationale.SavedDecision)
    check("saved false is surfaced, not silent", resolve(saved = false).explanation?.isEmpty(), false)
    check("default always", resolve(default = "always").trusted, true)
    check("default never", resolve(default = "never").rationale, ProjectTrust.Rationale.DefaultNever)
    check("rpc (no UI) refuses and explains", resolve().rationale, ProjectTrust.Rationale.NoUiRefused)
    // Pins the *consequence*, not the mechanism. The mechanism is `hasUI == false`,
    // which means nothing to a user, and the word this once matched ("静默跳过") is
    // gone with it — see ProjectTrust.skipNote. What must survive any rewording is
    // that the refusal says the project's own resources are not loaded: that is the
    // half a user cannot infer, and losing it would make this branch silent.
    check("rpc (no UI) refusal names the consequence", resolve().explanation?.contains("不会加载"), true)
    check("user answer beats the no-UI refusal", resolve(hasUI = true, answer = true).rationale, ProjectTrust.Rationale.UserAnswer)

    // ------------------------------------------------------------ source specs
    val npm = PiPackageSource.parse("npm:@scope/name@1.2.3") as PiPackageSource.Npm
    check("npm scoped name", npm.name, "@scope/name")
    check("npm version", npm.version, "1.2.3")
    check("exact version is pinned", npm.pinned, true)
    val npmRange = PiPackageSource.parse("npm:foo@^1.2") as PiPackageSource.Npm
    check("range version", npmRange.version, "^1.2")
    check("range is not pinned", npmRange.pinned, false)
    check("unversioned npm", (PiPackageSource.parse("npm:foo") as PiPackageSource.Npm).version, null)

    // `pinned` is `semver.valid(...) !== null` (`package-manager.ts:59-61`), and pi
    // *skips* pinned specs on update (`:1104`, `:1210`). The row label claims exactly
    // that, so these are the strict-FULL cases, not a numeric-looking heuristic.
    // Every expectation below was checked against node-semver's own `valid()` (7.8.4,
    // the copy bundled with the npm on this machine) before it was written down; the
    // harness itself needs no npm.
    fun pinned(v: String) = PiPackageSource.isExactNpmVersion(v)
    check("a leading v is a valid version", pinned("v1.2.3"), true)
    check("prerelease is a valid version", pinned("1.2.3-rc.1"), true)
    check("build metadata is a valid version", pinned("1.2.3+build"), true)
    check("prerelease and build together", pinned("1.2.3-rc.1+build.7"), true)
    check("surrounding whitespace is trimmed by semver", pinned(" 1.2.3 "), true)
    check("two components is a range, not a version", pinned("1.2"), false)
    check("a caret range is not a version", pinned("^1.2"), false)
    check("a tag is not a version", pinned("latest"), false)
    check("a comparator is not a version", pinned(">=2"), false)
    check("a leading zero is not valid in a component", pinned("01.2.3"), false)
    check("exactly three components are required", pinned("1.2.3.4"), false)
    check("= is only valid in semver's loose shape", pinned("=1.2.3"), false)
    check("an empty prerelease is invalid", pinned("1.2.3-"), false)
    check("an all-digit prerelease identifier may not have a leading zero", pinned("1.2.3-01"), false)
    check("but a single 0 is a valid identifier", pinned("1.2.3-0"), true)
    check("a digit-led non-numeric identifier is valid", pinned("1.2.3-0a"), true)
    check("a hyphen-only identifier is valid", pinned("1.2.3--"), true)
    check("an empty prerelease identifier is not", pinned("1.2.3-a..b"), false)
    check("build identifiers may start with a zero", pinned("1.2.3+01"), true)
    check("underscores are not identifier characters", pinned("1.2.3+a_b"), false)
    check("a numeric component above MAX_SAFE_INTEGER is rejected", pinned("9007199254740992.0.0"), false)
    check("the largest safe component is accepted", pinned("9007199254740991.0.0"), true)
    // The 256-character cap is on the raw input and is checked before trimming, so a
    // version that is otherwise perfectly valid is still not a version at 257.
    check("256 characters is still a version", pinned("1.2.3-" + "a".repeat(250)), true)
    check("257 characters is not, however valid the shape", pinned("1.2.3-" + "a".repeat(251)), false)
    check("non-ascii digits are not digits", pinned("１.2.3"), false)

    val git = PiPackageSource.parse("git:github.com/user/repo@v1") as PiPackageSource.Git
    check("git repo grows an https prefix", git.repo, "https://github.com/user/repo")
    check("git ref", git.ref, "v1")
    check("git host", git.host, "github.com")
    check("git path", git.path, "user/repo")

    val ssh = PiPackageSource.parse("ssh://git@github.com/user/repo") as PiPackageSource.Git
    check("ssh url host", ssh.host, "github.com")

    // A plain https URL is NOT a git source in pi; parseGitUrl needs a git
    // protocol or the git: prefix. It falls through to a local path.
    check(
        "plain https url is local, like pi",
        PiPackageSource.parse("https://example.com/pkg.tar.gz")::class.simpleName,
        "Local",
    )
    // The other half of that rule, and the reason it is not a prefix test: an https
    // URL to a *repository* is a git source, because `parseGitUrl` accepts a protocol
    // URL with at least two path segments (`package-manager.ts:1446-1471`, pi's
    // `parseSource`). Transcribed classification fact — pinned here so the three
    // spellings that reach pi's git installer stay three.
    check(
        "an https url to a repository is a git source",
        PiPackageSource.parse("https://github.com/user/repo")::class.simpleName,
        "Git",
    )
    check(
        "a relative path is local",
        PiPackageSource.parse("./local/pkg")::class.simpleName,
        "Local",
    )
    check("github: prefix is non-local but not git", PiPackageSource.isLocalPath("github:x/y"), false)

    // Pre-flight refuses only what pi cannot accept.
    check("blank source", PiPackageSource.validate("install", "  ")?.message, "Missing install source.")
    check("null source", PiPackageSource.validate("remove", null)?.message, "Missing remove source.")
    check(
        "option-like source",
        PiPackageSource.validate("install", "--force")?.message,
        "Unknown option --force for \"install\".",
    )
    check("a real spec passes", PiPackageSource.validate("install", "npm:foo"), null)
    check("a bogus but pl-parseable spec passes to pi", PiPackageSource.validate("install", "https://x/y"), null)

    // ---------------------------------------------------------- pi list output
    val listing = PiListOutput.parse(
        """
        User packages:
          npm:foo
            /root/.pi/agent/npm/node_modules/foo
          git:github.com/user/repo@v1 (filtered)

        Project packages:
          npm:bar
        """.trimIndent(),
    )
    check("list parses three entries", listing.entries.size, 3)
    check("user scope", listing.entries[0].scope, PiPackageScope.User)
    check("installed path continuation", listing.entries[0].installedPath, "/root/.pi/agent/npm/node_modules/foo")
    check("filtered flag", listing.entries[1].filtered, true)
    check("filtered source is stripped of the suffix", listing.entries[1].source.raw, "git:github.com/user/repo@v1")
    check("project scope", listing.entries[2].scope, PiPackageScope.Project)
    check("no installed path", listing.entries[2].installedPath, null)
    check("nothing unrecognised", listing.unrecognised, false)

    val empty = PiListOutput.parse("No packages installed.\n")
    check("empty install is recognised, not a parse failure", empty.empty, true)
    check("empty install has no entries", empty.entries.size, 0)

    val ansi = PiListOutput.parse("\u001B[1mUser packages:\u001B[22m\n  npm:baz\n")
    check("chalk bold does not defeat the parser", ansi.entries.size, 1)
    check("ansi stripped from the source", ansi.entries[0].source.raw, "npm:baz")

    val garbled = PiListOutput.parse("segmentation fault\n")
    check("garbage is flagged, never 'no packages'", garbled.unrecognised, true)
    check("garbage yields no entries", garbled.entries.size, 0)

    // --------------------------------------------------- restart state machine
    val life = ExtensionLifecycle()
    check("starts idle", life.current, ExtensionLifecycle.State.Idle)
    life.installSucceeded(listOf("Installed npm:foo"), "detail")
    check("a successful install lands in NeedsRestart", life.current::class.simpleName, "NeedsRestart")
    check("pendingRestart is true", life.pendingRestart, true)

    // A confirmed restart with a turn running must NOT restart.
    check("request with a running turn waits", life.requestRestart(turnRunning = true), ExtensionLifecycle.RequestOutcome.WaitingForTurn)
    check("still no restart", life.current::class.simpleName, "AwaitingIdle")
    check("pendingRestart survives", life.pendingRestart, true)
    // Re-asking while still busy keeps waiting.
    check("re-ask while busy keeps waiting", life.requestRestart(turnRunning = true), ExtensionLifecycle.RequestOutcome.WaitingForTurn)
    // Turn ended -> now it asks.
    check("turn ended produces a confirmation", life.requestRestart(turnRunning = false), ExtensionLifecycle.RequestOutcome.NeedsConfirmation)
    check("awaiting confirmation", life.current::class.simpleName, "AwaitingConfirmation")
    // A confirmation is still not a restart.
    check("no restart until confirmed", life.current is ExtensionLifecycle.State.Restarting, false)
    check("confirming starts the restart", life.restartStarted(), true)
    check("restarting", life.current::class.simpleName, "Restarting")
    life.restartSucceeded()
    check("restart lands in ready", life.current::class.simpleName, "Ready")
    check("nothing pending after ready", life.pendingRestart, false)
    check("requesting a restart when ready is a no-op", life.requestRestart(false), ExtensionLifecycle.RequestOutcome.AlreadyReady)

    // Cancel returns to the honest pending state rather than dropping the work.
    val life2 = ExtensionLifecycle()
    life2.installSucceeded(listOf("x"), "d")
    life2.requestRestart(turnRunning = false)
    life2.cancelRestart()
    check("cancel keeps NeedsRestart", life2.current::class.simpleName, "NeedsRestart")
    check("cancel keeps the change list", (life2.current as ExtensionLifecycle.State.NeedsRestart).changes, listOf("x"))

    // A restart never happens during a package command.
    val life3 = ExtensionLifecycle()
    life3.beginInstall("安装", "npm:foo")
    check("request during an install is refused", life3.requestRestart(false), ExtensionLifecycle.RequestOutcome.BusyWithPackageCommand)
    check("still installing", life3.current::class.simpleName, "Installing")
    life3.installFailed("boom")
    check("a failed install returns to idle", life3.current, ExtensionLifecycle.State.Idle)

    // A restart the *engine* refuses after the user confirmed it must go back to
    // waiting. `requestRestart` cannot do that from `Restarting` (it answers
    // BusyWithPackageCommand and changes nothing), so the machine parked there and
    // the screen's Restarting branch has no button: the user could never leave.
    val lifeRefused = ExtensionLifecycle()
    lifeRefused.installSucceeded(listOf("x"), "d")
    check("ask first", lifeRefused.requestRestart(turnRunning = false), ExtensionLifecycle.RequestOutcome.NeedsConfirmation)
    check("confirmed restart starts", lifeRefused.restartStarted(), true)
    check("it is in Restarting", lifeRefused.current::class.simpleName, "Restarting")
    check(
        "the machine is in Restarting before the engine answers",
        lifeRefused.current::class.simpleName,
        "Restarting",
    )
    lifeRefused.restartRefused("引擎：有回合正在运行，未重启")
    check("a refused restart waits instead of parking in Restarting", lifeRefused.current::class.simpleName, "AwaitingIdle")
    check(
        "the engine's own sentence is kept for the screen",
        (lifeRefused.current as ExtensionLifecycle.State.AwaitingIdle).turnNote,
        "引擎：有回合正在运行，未重启",
    )
    check("the change list survives the refusal", lifeRefused.pendingRestart, true)
    check(
        "and it can be re-offered once the turn ends",
        lifeRefused.requestRestart(turnRunning = false),
        ExtensionLifecycle.RequestOutcome.NeedsConfirmation,
    )
    // Outside Restarting it is a no-op: a real restart in progress must not be reset.
    lifeRefused.cancelRestart()
    lifeRefused.restartRefused("不应该生效")
    check("restartRefused is a no-op when no restart was started", lifeRefused.current::class.simpleName, "NeedsRestart")

    // ------------------------------------------- built in vs installed (E7)
    //
    // pi reports no such distinction: `pi list` reads only `settings.json`'s
    // `packages` (`package-manager-cli.ts:970-1002`), and `<agentDir>/extensions/`
    // is auto-discovered as plain user-scope extensions
    // (`package-manager.ts:2352-2362`, `:2470-2475`). So the shipped set is the
    // app's own transcription of `app/src/main/assets/pi-extensions/`, and these
    // checks pin what the UI labels a row from.
    check("three extensions are shipped", PiBuiltinExtension.SHIPPED.size, 3)
    check(
        "shipped names are the asset names",
        PiBuiltinExtension.SHIPPED.map { it.name },
        listOf("pi-android-bridge", "pi-android-permission-gate", "pi-highlight"),
    )

    // pi's discovery loads a subdirectory only through its index.ts
    // (`package-manager.ts:557-585`), so the entry is not the copied directory.
    val bridge = PiBuiltinExtension.SHIPPED.first { it.name == "pi-android-bridge" }
    check("a directory extension is loaded as its index.ts", bridge.entryUnderExtensions, "pi-android-bridge/index.ts")
    check(
        "guest spelling sits under extensions/",
        bridge.guestEntryPath("/root/.pi/agent"),
        "/root/.pi/agent/extensions/pi-android-bridge/index.ts",
    )
    check(
        "the single-file extension is loaded as itself",
        PiBuiltinExtension.SHIPPED.first { it.name == "pi-android-permission-gate" }.entryUnderExtensions,
        "pi-android-permission-gate.ts",
    )

    // The truth table of the two agent directories. The engine's bind
    // (`PiEngineHost.kt:285-294`) and a guest command's single bind
    // (`GuestCommand.kt:98-106`) are what make "found" and "loaded" different.
    check(
        "present in the engine's agent dir",
        bridge.presenceIn(engineAgentDirHasEntry = true, rootfsHasEntry = true),
        PiBuiltinExtension.Presence.EngineAgentDir,
    )
    check(
        "engine copy wins even when both exist",
        bridge.presenceIn(engineAgentDirHasEntry = true, rootfsHasEntry = false),
        PiBuiltinExtension.Presence.EngineAgentDir,
    )
    check(
        "rootfs-only is its own state, not 'installed'",
        bridge.presenceIn(engineAgentDirHasEntry = false, rootfsHasEntry = true),
        PiBuiltinExtension.Presence.RootfsCopyOnly,
    )
    check(
        "absent from both",
        bridge.presenceIn(engineAgentDirHasEntry = false, rootfsHasEntry = false),
        PiBuiltinExtension.Presence.Missing,
    )

    // ------------------------------------- agent dir contract (the bind fix)
    //
    // The engine binds the durable agent dir over guest /root/.pi/agent
    // (`PiEngineHost.kt:285-294`). A guest command that does not pass the same bind
    // writes a different settings.json than the one the running engine reads, exits 0,
    // and changes nothing — so the agreement is a checked value.
    check("the contract guest path is pi's guest agent dir", PiAgentDirContract.GUEST_PATH, "/root/.pi/agent")
    check("session dir is <agentDir>/sessions", PiAgentDirContract.sessionDir("/root/.pi/agent"), "/root/.pi/agent/sessions")
    check(
        "the engine's bind satisfies the contract",
        PiAgentDirContract.bindsAgentDir(
            listOf("/files/pi/workspace-1" to "/workspace/pi/workspaces/workspace-1", "/files/pi/.pi/agent" to "/root/.pi/agent"),
            "/files/pi/.pi/agent",
        ),
        true,
    )
    check(
        "a command that omits the agent bind fails the contract",
        PiAgentDirContract.bindsAgentDir(
            listOf("/files/pi/workspace-1" to "/workspace/pi/workspaces/workspace-1"),
            "/files/pi/.pi/agent",
        ),
        false,
    )
    check(
        "a bind from the rootfs copy fails the contract",
        PiAgentDirContract.bindsAgentDir(
            listOf("/files/pi/runtime/rootfs/root/.pi/agent" to "/root/.pi/agent"),
            "/files/pi/.pi/agent",
        ),
        false,
    )
    check(
        "a bind to another guest path fails the contract",
        PiAgentDirContract.bindsAgentDir(listOf("/files/pi/.pi/agent" to "/root/.pi"), "/files/pi/.pi/agent"),
        false,
    )
    check(
        "bind order does not matter",
        PiAgentDirContract.bindsAgentDir(
            listOf("/files/pi/.pi/agent" to "/root/.pi/agent", "/files/pi/ws" to "/workspace/ws"),
            "/files/pi/.pi/agent",
        ),
        true,
    )

    // ---- `pi config` 的按资源 glob（PiPackageFilters）----------------------------
    //
    // 这一组存在的理由就是那个读取端缺口：App 以前只认 `pi list` 的 `(filtered)`
    // 后缀，四个 glob 数组根本没被解析过。所以第一条检查就是"能不能把 pi 写下的
    // glob 逐条读出来"，后面每一条都对着 pi 自己的规则（`settings-manager.ts:95-104`
    // 的形状、`config-selector.ts:26-38` 的四个键、`:619` 的空数组、`:623-628` 的塌回、
    // `:611-614` 的去重按 body），以及本文件头部写明的**那一处有意偏离**。

    val objectForm = buildJsonObject {
        put("source", JsonPrimitive("npm:foo"))
        put("autoload", JsonPrimitive(false))
        put("extensions", JsonArray(listOf(JsonPrimitive("+extra"), JsonPrimitive("-legacy*"))))
        put("skills", JsonArray(listOf(JsonPrimitive("c*"))))
    }
    val filtered = PiPackageFilters.parse(objectForm)
    check("pi 的 object 形式能解析出 source", filtered?.source, "npm:foo")
    check("能读到 autoload", filtered?.autoload, false)
    check("能逐条读到 extensions 的两条 glob", filtered?.patterns("extensions"), listOf("+extra", "-legacy*"))
    check("能读到 skills 的 glob", filtered?.patterns("skills"), listOf("c*"))
    check("没写的资源键是空的，不是 null 崩", filtered?.patterns("themes"), emptyList<String>())
    check("hasFilters 认得出这个条目有过滤", filtered?.hasFilters, true)

    check("裸字符串是合法的 packages 元素", PiPackageFilters.parse(JsonPrimitive("npm:foo"))?.source, "npm:foo")
    check("空白 source 的对象不会被编成一个包", PiPackageFilters.parse(buildJsonObject { put("source", JsonPrimitive("  ")) }), null)
    check("非对象非字符串不猜成一个包", PiPackageFilters.parse(JsonPrimitive(7)), null)

    val emptyArray = buildJsonObject {
        put("source", JsonPrimitive("npm:bar"))
        put("skills", JsonArray(emptyList()))
    }
    check("空数组等同于没有这个键（pi :619）", PiPackageFilters.parse(emptyArray)?.hasFilters, false)

    // 往返：pi 写下的形状我们能原样写回去，不丢 glob、不丢 autoload。
    check("object 形式往返不丢东西", filtered?.let { PiPackageFilters.toJson(it) }, objectForm)
    check(
        "没有过滤也没有 autoload 时塌回裸字符串（pi :623-628）",
        PiPackageFilters.toJson(PiPackageFilters.Entry(source = "npm:baz")),
        JsonPrimitive("npm:baz"),
    )
    check(
        "**有意偏离**：只剩 autoload 时保留 object 形式，不静默丢掉 autoload",
        PiPackageFilters.toJson(PiPackageFilters.Entry(source = "npm:baz", autoload = false)),
        buildJsonObject { put("source", JsonPrimitive("npm:baz")); put("autoload", JsonPrimitive(false)) },
    )
    check(
        "normalize 丢掉空数组",
        PiPackageFilters.normalize(
            PiPackageFilters.Entry(source = "npm:x", filters = mapOf("skills" to emptyList(), "themes" to listOf("t*"))),
        ).filters,
        mapOf("themes" to listOf("t*")),
    )
    check(
        "**有意偏离**：normalize 后 autoload 仍在",
        PiPackageFilters.normalize(PiPackageFilters.Entry(source = "npm:x", autoload = true)).autoload,
        true,
    )

    // 去重按 body（pi :611-614）：`+c*` 与 `-c*` 不能并存，后者替换前者。
    val added = PiPackageFilters.withPattern(filtered!!, "skills", "-c*")
    check("同 body 的 glob 是替换不是追加", added.patterns("skills"), listOf("-c*"))
    val addedNew = PiPackageFilters.withPattern(filtered, "skills", "other")
    check("新 body 追加在末尾", addedNew.patterns("skills"), listOf("c*", "other"))
    check("不该受影响的资源键不动", addedNew.patterns("extensions"), listOf("+extra", "-legacy*"))
    check("未知名资源键不写入", PiPackageFilters.withPattern(filtered, "nope", "x").patterns("nope"), emptyList<String>())
    check("空白 glob 不写入", PiPackageFilters.withPattern(filtered, "skills", "   ").patterns("skills"), listOf("c*"))
    check(
        "删除是逐字匹配，删空后键消失",
        PiPackageFilters.withoutPattern(filtered, "skills", "c*").patterns("skills"),
        emptyList<String>(),
    )
    check(
        "删除后另一条 glob 还在",
        PiPackageFilters.withoutPattern(filtered, "extensions", "+extra").patterns("extensions"),
        listOf("-legacy*"),
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
