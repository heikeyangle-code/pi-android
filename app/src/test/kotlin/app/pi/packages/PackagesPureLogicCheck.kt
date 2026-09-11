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
// difference between "no packages" and "unparseable"), and every restart
// transition including the turn-running rule.

import app.pi.packages.ExtensionLifecycle
import app.pi.packages.PiListOutput
import app.pi.packages.PiPackageScope
import app.pi.packages.PiPackageSource
import app.pi.packages.ProjectTrust
import app.pi.packages.TrustFile
import app.pi.packages.TrustStore

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
    check("rpc explanation mentions the silent skip", resolve().explanation?.contains("静默跳过"), true)
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

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
