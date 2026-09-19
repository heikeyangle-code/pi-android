package app.pi.runtime

// The bare-JVM harness for the **opt-in proroot runtime's pure logic**, registered in
// `tools/run-app-pure-checks.sh` as `proroot`.
//
// Why this harness exists, in the shape the other runtime harnesses use: everything it
// pins is a decision that is invisible until it is wrong on a device, and there is no
// device in the build. Concretely —
//
//  1. **`ProrootCommand`'s argv/env is the §2.3.1 mapping table** from
//     `docs/proroot-app-design.md` §2.3.1 / `docs/proroot-research.md` §4.3. Four of the
//     proot flags are *rejected* by proroot (`-L`, `--kill-on-exit`, `--rootfs=`,
//     `--cwd=` print `unknown option` and exit), so a copy-paste of the proot recipe
//     does not degrade — it fails every launch. The check pins both halves: what must
//     be there, and what must not.
//  2. **`RuntimeChoice.decide` decides whether a closed-source binary runs.** The guard
//     order (switch → files → gate → failure streak) is the safety property, and the
//     boundary at exactly three failures is DSH App's contract (§7.1).
//  3. **`ProrootConfigSweep` deletes files.** It must delete a dead owner's table and
//     never a live one's, because the engine, the terminal and a package install can
//     run at once (§1.3), and the fix for a leak must not become a new bug.
//  4. **`ProrootRawProbe.parse` decides whether a leak vetoes the runtime.** The
//     verdicts are strings emitted by a Perl script inside the guest; a parser that
//     mis-reads "leaked" as "untranslated" would let the one disqualifying outcome
//     through.
//  5. **`ProrootLaunchHandle` is the only way to learn a launcher's pid** on this
//     platform (`Process.pid()` does not resolve here), so its arm/resolve/delete
//     sequence is driven against a real temporary directory.
//  6. **`GuestProcessTree` computes what gets signalled.** Killing the wrong pid means
//     killing a guest that was doing something else; the child-first order is what
//     keeps a stop from orphaning the tree it is stopping.
//  7. **The `-b` value has to be `host:guest`** (added 2026-09-19). proroot v1.2.8 refuses
//     the whole invocation on a bare host path, so the shared bind table is respelled by
//     the proroot builder and both halves are pinned: proot keeps its spelling, proroot's
//     argv contains no value without a colon. This was the defect that made every proroot
//     launch die before forking.
//  8. **The gate is档-aware, and the shipping档 is the strict one** (added 2026-09-19).
//     `RuntimeChoice.probeGate` is executed for all four inputs in both seccomp档 — a leak
//     vetoes in both, broken tools veto in both, an untranslated raw syscall vetoes only
//     where translation was promised — the cache key carries the档 so the two can never
//     share a verdict, and the settings sentence's disclosure is pinned as two different
//     sentences.
//  9. **A launcher that never reached the guest is not a translation verdict** (added
//     2026-09-19). `ProrootRawProbe.parse` keeps the run's own words and classifies an
//     argument-parsing death as its own phase, so the row and the report quote proroot
//     instead of claiming the raw syscall was untranslated.
//
// Android-free by construction: every file listed in this harness's closure imports
// `java.io` and the Kotlin stdlib and nothing else. If one of them ever grows an
// Android import, this harness fails to compile — loudly, on purpose.

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val FILES = "/data/user/0/app.pi/files"
private const val NATIVE = "/data/app/app.pi/lib/arm64"

private fun paths() = PiPaths(
    filesDir = java.io.File(FILES),
    nativeLibDir = java.io.File(NATIVE),
)

/** Every `-b` bind pair in an argv, in order, as `host:guest`. */
private fun binds(argv: List<String>): List<String> =
    argv.zipWithNext().filter { (flag, _) -> flag == "-b" }.map { (_, value) -> value }

fun main() {
    val p = paths()

    // ================================================================ 1. §2.3.1 映射
    val command = "echo hi"
    val prootArgv = ProotCommand.build(p, command, "/root", null)
    val prorootArgv = ProrootCommand.build(p, command, "/root", null)

    check("proroot runs the launcher from nativeLibraryDir", prorootArgv.first(), "$NATIVE/libproroot.so")
    check("proroot keeps --link2symlink", prorootArgv.contains("--link2symlink"), true)
    check("proroot keeps -0", prorootArgv.contains("-0"), true)
    check("proroot respells --rootfs= as -r", prorootArgv.contains("--rootfs=${p.rootfs.path}"), false)
    check("proroot passes -r <rootfs>", prorootArgv.windowed(2).any { it == listOf("-r", p.rootfs.path) }, true)
    check("proroot respells --cwd= as -w", prorootArgv.contains("--cwd=/root"), false)
    check("proroot passes -w <cwd>", prorootArgv.windowed(2).any { it == listOf("-w", "/root") }, true)

    // The four proot-only spellings, each of which proroot rejects with `unknown option`
    // (§4.2). `-L` would be `prorootArgv.contains("-L")`, but `-L` must also not appear
    // as part of another word — it is a standalone flag, so an exact match is right.
    check("proroot does not pass -L", prorootArgv.contains("-L"), false)
    check("proroot does not pass --kill-on-exit", prorootArgv.contains("--kill-on-exit"), false)
    check("proroot does not pass --rootfs=", prorootArgv.any { it.startsWith("--rootfs=") }, false)
    check("proroot does not pass --cwd=", prorootArgv.any { it.startsWith("--cwd=") }, false)
    // The l2s bind is proot's, because proot resolves the intermediates through it.
    // proroot anchors its own link handling and is not told about the directory (§4.3);
    // the directory itself is still created by `PiPaths.l2s` so switching back works.
    val l2sBind = "${p.l2s.path}:${p.l2s.path}"
    check("proot binds the l2s store", binds(prootArgv).contains(l2sBind), true)
    check("proroot does not bind the l2s store", binds(prorootArgv).contains(l2sBind), false)

    // The bind table is shared, not copied: every bind proot passes is also passed by
    // proroot (minus the l2s one), and the added ones are the same set the shared recipe
    // produces. A second copy of this list in the proroot builder would show up here as
    // a missing entry the day someone edits only one of them.
    val storage = java.io.File("/storage/emulated/0")
    val sharedBinds = GuestRecipe.binds(p, storage).map { it[1] }
    val prootBinds = binds(ProotCommand.build(p, command, "/root", storage))
    val prorootBinds = binds(ProrootCommand.build(p, command, "/root", storage))
    check("proot's binds are the shared table plus l2s and tmp", prootBinds.toSet(), (sharedBinds + l2sBind + "${p.tmp.path}:/tmp").toSet())
    check("both runtimes bind /dev, /proc, /sys, /system, /apex and /dev/fd", sharedBinds.any { it == "/dev" } && sharedBinds.any { it == "/proc" } && sharedBinds.any { it == "/sys" } && sharedBinds.any { it == "/system" } && sharedBinds.any { it == "/apex" } && sharedBinds.any { it == "/proc/self/fd:/dev/fd" }, true)
    check("both runtimes bind external storage twice", sharedBinds.count { it.startsWith(storage.path) }, 2)

    // ---- the `-b` spelling, which is where proroot actually died ---------------
    // proroot v1.2.8 parses a `-b` value with `strchr(value, ':')` and refuses the whole
    // invocation when there is no colon (`[proroot] bad bind format (expected
    // host:guest)`), while proot accepts the bare form as host == guest. The shared
    // table therefore has to be *respelled* by the proroot builder — and the failure it
    // prevents is a launcher that exits before forking, which is indistinguishable from
    // "translation did not happen" everywhere else. Both halves are pinned: the shared
    // table keeps proot's spelling, and proroot's argv contains no value without a colon.
    check("the shared table keeps proot's bare spelling", sharedBinds.contains("/dev"), true)
    check("proot still emits the bare form", prootBinds.contains("/dev"), true)
    check("the normalizer qualifies a bare host path", ProrootCommand.bindArgument("/dev"), "/dev:/dev")
    check("the normalizer leaves a pair alone", ProrootCommand.bindArgument("/dev/urandom:/dev/random"), "/dev/urandom:/dev/random")
    check("the separator is the one proroot parses", ProrootCommand.BIND_SEPARATOR, ":")
    check("proroot qualifies the shared table", prorootBinds.toSet(), (sharedBinds.map { ProrootCommand.bindArgument(it) } + "${p.tmp.path}:/tmp").toSet())
    check("proroot emits no unqualified bind value", prorootBinds.all { it.contains(ProrootCommand.BIND_SEPARATOR) }, true)
    check("proroot binds /dev to /dev", prorootBinds.contains("/dev:/dev"), true)
    check("proot's bind count is unchanged by the respelling", prootBinds.size, sharedBinds.size + 2)

    // The tail is the shared shell invocation, identical in both.
    check("proroot ends with the shared shell tail", prorootArgv.takeLast(3), listOf("/bin/bash", "-c", command))
    check("proot ends with the same tail", prootArgv.takeLast(3), prorootArgv.takeLast(3))

    // ---- environment -------------------------------------------------------------
    val prootEnv = ProotCommand.environment(p)
    val prorootEnv = ProrootCommand.environment(p)
    check("proroot points PROROOT_TMP_DIR at a host path", prorootEnv["PROROOT_TMP_DIR"], "${p.runtime.path}/proroot-tmp")
    check("PROROOT_TMP_DIR is not a guest path", prorootEnv["PROROOT_TMP_DIR"]?.startsWith("/root/") ?: false, false)
    check("proroot names its linker", prorootEnv["PROROOT_LINKER_PATH"], "$NATIVE/libproroot-linker.so")
    check("proroot names its runtime hook", prorootEnv["PROROOT_LIB_PATH"], "$NATIVE/libproroot-runtime.so")
    check("proroot names its stub loader", prorootEnv["PROROOT_STUB_LOADER"], "$NATIVE/libproroot-stub-loader.so")
    // The proot-only variables. `PROOT_LOADER` is the load-bearing one for proot
    // (Android 10+ W^X) and meaningless to proroot; `PROOT_L2S_DIR` is not recognised;
    // `LD_LIBRARY_PATH` exists only for proot's libtalloc alias and would inject host
    // paths into a guest that has no use for them (§4.3).
    check("proroot sets no PROOT_* variable", prorootEnv.keys.none { it.startsWith("PROOT_") }, true)
    check("proroot does not set LD_LIBRARY_PATH", prorootEnv.containsKey("LD_LIBRARY_PATH"), false)
    check("proot still sets PROOT_LOADER", prootEnv["PROOT_LOADER"], "$NATIVE/libprootloader.so")
    // The shared half must be *identical*, not merely present: the guest is supposed to
    // be the same guest on both runtimes.
    check(
        "the runtime-independent environment is identical",
        listOf("HOME", "TMPDIR", "TERM", "LANG", "PATH", "NARB_DISABLE_NATIVE_CACHE", "GIT_SSL_CAINFO", "SSL_CERT_FILE")
            .associateWith { prootEnv[it] },
        listOf("HOME", "TMPDIR", "TERM", "LANG", "PATH", "NARB_DISABLE_NATIVE_CACHE", "GIT_SSL_CAINFO", "SSL_CERT_FILE")
            .associateWith { prorootEnv[it] },
    )
    check("NARB_DISABLE_NATIVE_CACHE is on under both runtimes", listOf(prootEnv["NARB_DISABLE_NATIVE_CACHE"], prorootEnv["NARB_DISABLE_NATIVE_CACHE"]), listOf("1", "1"))
    check("both runtimes name the same CA bundle", listOf(prootEnv["SSL_CERT_FILE"], prorootEnv["SSL_CERT_FILE"]), listOf(GuestRecipe.GUEST_CA_BUNDLE, GuestRecipe.GUEST_CA_BUNDLE))
    check("the forwarded CA constant is the shared one", ProotCommand.GUEST_CA_BUNDLE, GuestRecipe.GUEST_CA_BUNDLE)
    check("extra environment is merged, not dropped", ProrootCommand.environment(p, mapOf("X" to "1"))["X"], "1")
    // The launcher's environment is exactly the four variables the reference
    // implementation exports (measured from its live `/proc/<pid>/environ` on the device:
    // `PROROOT_TMP_DIR`, `PROROOT_LIB_PATH`, `PROROOT_LINKER_PATH`, `PROROOT_STUB_LOADER`
    // and nothing else proroot-shaped). `PROROOT_TRAMPOLINE_PATH` is discovered by the
    // launcher itself, and `PROROOT_NO_SECCOMP` is *deliberately absent* — v1.2.8 has no
    // reader for it (it writes `1` into its own children and nothing consumes it), so
    // exporting it would change our environment and nothing else.
    check(
        "proroot exports the four launcher variables",
        prorootEnv.keys.filter { it.startsWith("PROROOT_") }.sorted(),
        listOf("PROROOT_LIB_PATH", "PROROOT_LINKER_PATH", "PROROOT_STUB_LOADER", "PROROOT_TMP_DIR"),
    )
    check("the no-seccomp variable is not exported", prorootEnv.containsKey(ProrootCommand.NO_SECCOMP_ENV), false)
    check("the trampoline path is left to the launcher", prorootEnv.containsKey("PROROOT_TRAMPOLINE_PATH"), false)
    check("the no-seccomp name is the binary's", ProrootCommand.NO_SECCOMP_ENV, "PROROOT_NO_SECCOMP")

    // ---- the dispatcher -----------------------------------------------------------
    check("GuestCommandLine dispatches to the proot builder", GuestCommandLine.build(p, GuestEngine.Proot, command, "/root", null), prootArgv)
    check("GuestCommandLine dispatches to the proroot builder", GuestCommandLine.build(p, GuestEngine.Proroot, command, "/root", null), prorootArgv)
    check("GuestCommandLine dispatches the environment", GuestCommandLine.environment(p, GuestEngine.Proroot)["PROROOT_TMP_DIR"], prorootEnv["PROROOT_TMP_DIR"])
    check("extra binds reach both builders", GuestCommandLine.build(p, GuestEngine.Proroot, command, "/root", null, listOf("/a" to "/b")).windowed(2).any { it == listOf("-b", "/a:/b") }, true)

    // ================================================================ 2. 选择逻辑
    // The guard order, one condition at a time, then the combination.
    check("the switch off means proot, whatever else is true", RuntimeChoice.decide(enabled = false, filesPresent = true, probePassed = true, consecutiveFailures = 0), EngineDecision(GuestEngine.Proot, EngineFallback.SwitchOff))
    check("a missing runtime file means proot", RuntimeChoice.decide(enabled = true, filesPresent = false, probePassed = true, consecutiveFailures = 0), EngineDecision(GuestEngine.Proot, EngineFallback.RuntimeFilesMissing))
    check("a failed gate means proot even with every file present", RuntimeChoice.decide(enabled = true, filesPresent = true, probePassed = false, consecutiveFailures = 0), EngineDecision(GuestEngine.Proot, EngineFallback.ProbeNotPassed))
    check("two failures still allow proroot", RuntimeChoice.decide(enabled = true, filesPresent = true, probePassed = true, consecutiveFailures = 2), EngineDecision(GuestEngine.Proroot, EngineFallback.None))
    check("the third consecutive failure forces proot", RuntimeChoice.decide(enabled = true, filesPresent = true, probePassed = true, consecutiveFailures = 3), EngineDecision(GuestEngine.Proot, EngineFallback.FailureStreak))
    check("the streak outranks a passing gate", RuntimeChoice.decide(enabled = true, filesPresent = true, probePassed = true, consecutiveFailures = 99), EngineDecision(GuestEngine.Proot, EngineFallback.FailureStreak))
    check("all four conditions satisfied means proroot", RuntimeChoice.decide(enabled = true, filesPresent = true, probePassed = true, consecutiveFailures = 0), EngineDecision(GuestEngine.Proroot, EngineFallback.None))
    check("the switch is checked before the files", RuntimeChoice.decide(enabled = false, filesPresent = false, probePassed = false, consecutiveFailures = 5), EngineDecision(GuestEngine.Proot, EngineFallback.SwitchOff))
    check("every failure reason has a sentence", EngineFallback.entries.all { RuntimeChoice.describe(it).isNotBlank() }, true)
    // 尚未运行 is the sentence users got stuck on: the first launch after the switch is
    // turned on is the one that runs the probe **and still uses proot**, and only a later
    // launch can end up on proroot. The old text ("首次使用时会自动跑一次") described the
    // probe but not what the user sees in between. Pinned here because this sentence is
    // in `RuntimeChoice` — a symbol this harness already owns — and the settings row and
    // the report both render it verbatim.
    val notRun = RuntimeChoice.describe(EngineFallback.ProbeNotRun)
    check("尚未运行 says the next launch runs the probe", notRun.contains("下一次启动 guest 会跑一次"), true)
    check("尚未运行 says that launch still uses proot", notRun.contains("那一次仍用 proot"), true)
    check("尚未运行 says proroot can only take over after that", notRun.contains("再下一次"), true)
    check("尚未运行 is still one line", notRun.none { it == '\n' || it == '\r' }, true)
    check("the failure budget is three", RuntimeChoice.MAX_CONSECUTIVE_FAILURES, 3)
    check("a failure advances the counter", RuntimeChoice.afterFailure(0), 1)
    check("a failure at the boundary is exhausted", RuntimeChoice.exhausted(RuntimeChoice.afterFailure(2)), true)
    check("two failures are not exhausted", RuntimeChoice.exhausted(2), false)
    check("a success clears the streak", RuntimeChoice.afterSuccess(), 0)
    check("the required file list is the five shipped names", RuntimeChoice.REQUIRED_FILES, listOf("libproroot.so", "libproroot-runtime.so", "libproroot-linker.so", "libproroot-bridge.so", "libproroot-stub-loader.so"))
    check("the component getters line up with the required names", p.prorootComponents().map { it.name }, RuntimeChoice.REQUIRED_FILES)
    check("missing components are reported by name (none exist in this fixture)", p.missingProrootComponents(), listOf("libproroot.so", "libproroot-runtime.so", "libproroot-linker.so", "libproroot-bridge.so", "libproroot-stub-loader.so"))

    // ================================================================ 3. 配置表清理
    check("a config name parses to its pid", ProrootConfigSweep.pidOf(".proroot-config-22918"), 22918)
    check("the name for a pid is the launcher's", ProrootConfigSweep.name(22918), ".proroot-config-22918")
    check("a foreign name is not ours", ProrootConfigSweep.pidOf("libproroot.so"), null)
    check("a name without a pid is not ours", ProrootConfigSweep.pidOf(".proroot-config-"), null)
    check("a name with a non-numeric pid is not ours", ProrootConfigSweep.pidOf(".proroot-config-12x"), null)
    check("the prefix is the measured one", ProrootConfigSweep.PREFIX, ".proroot-config-")
    check("the cap is bounded", ProrootConfigSweep.DEFAULT_LIMIT, 32)

    val dead = ProrootConfigSweep.Entry(".proroot-config-11", 11, 100)
    val live = ProrootConfigSweep.Entry(".proroot-config-22", 22, 200)
    val liveOlder = ProrootConfigSweep.Entry(".proroot-config-33", 33, 50)
    val unparseable = ProrootConfigSweep.Entry(".proroot-config-oops", null, 10)

    val sweepDeadOnly = ProrootConfigSweep.plan(listOf(dead, live), alivePids = setOf(22))
    check("a dead owner's table is deleted", sweepDeadOnly.delete, listOf(".proroot-config-11"))
    check("the dead count is reported", sweepDeadOnly.dead, 1)
    check("nothing is cut when under the cap", sweepDeadOnly.overCap, 0)
    val sweepLiveOnly = ProrootConfigSweep.plan(listOf(live), alivePids = setOf(22))
    check("a live owner's table is never deleted", sweepLiveOnly.delete, emptyList<String>())
    // The liveness pass cannot judge an unparseable name, and guessing "dead" would
    // delete a file another launch may be using.
    val sweepUnparseable = ProrootConfigSweep.plan(listOf(unparseable), alivePids = emptySet())
    check("an unparseable name survives the liveness pass", sweepUnparseable.delete, emptyList<String>())
    // Cap: with 3 survivors and a limit of 2, the oldest by mtime goes — not the dead
    // one (already gone) and not the one being kept.
    val sweepCap = ProrootConfigSweep.plan(
        entries = listOf(dead, live, liveOlder, ProrootConfigSweep.Entry(".proroot-config-44", 44, 300)),
        alivePids = setOf(22, 33, 44),
        limit = 2,
    )
    check("the dead table is deleted first", ".proroot-config-11" in sweepCap.delete, true)
    check("the cap removes the oldest survivor", ".proroot-config-33" in sweepCap.delete, true)
    check(
        "the cap keeps the newest two",
        sweepCap.delete.toSet(),
        setOf(".proroot-config-11", ".proroot-config-33"),
    )
    check("the over-cap count is reported separately", sweepCap.overCap, 1)
    // `keepNames` protects a name from the cap; the cap still has to reach the limit,
    // so it cuts the *other* survivor. (The first version of this check asserted an
    // empty delete list, which was simply wrong about its own rule — the harness caught
    // it, which is the reason it exists.)
    val sweepKeep = ProrootConfigSweep.plan(
        entries = listOf(live, liveOlder),
        alivePids = setOf(22, 33),
        keepNames = setOf(".proroot-config-33"),
        limit = 1,
    )
    check("a keep entry is never cut by the cap", sweepKeep.delete.contains(".proroot-config-33"), false)
    check("the cap cuts the other survivor to reach the limit", sweepKeep.delete, listOf(".proroot-config-22"))
    check("the cap note names the count", ProrootConfigSweep.capNote(sweepCap)?.contains("1") ?: false, true)
    check("no cap note when nothing was cut", ProrootConfigSweep.capNote(sweepDeadOnly), null)
    check("an empty plan is empty", ProrootConfigSweep.plan(emptyList(), emptySet()).isEmpty, true)

    // ================================================================ 4. 进程树回收
    //        1
    //        └─ 2
    //           ├─ 4
    //           └─ 5
    //        3
    val tree = mapOf(1 to 0, 2 to 1, 3 to 1, 4 to 2, 5 to 2)
    check("descendants are the reachable set", GuestProcessTree.descendants(1, tree).toSet(), setOf(2, 3, 4, 5))
    check("the root is not its own descendant", GuestProcessTree.descendants(1, tree).contains(1), false)
    check("the deepest children come first", GuestProcessTree.descendants(1, tree).take(2).toSet(), setOf(4, 5))
    check("the root is signalled last", GuestProcessTree.plan(1, tree).last(), 1)
    check("a sibling subtree does not include its sibling", GuestProcessTree.descendants(2, tree).toSet(), setOf(4, 5))
    check("an unrelated pid is not selected", GuestProcessTree.descendants(1, tree).contains(99), false)
    // A recycled pid can look like a cycle; the walk must terminate and stay finite.
    check("a cycle cannot loop forever", GuestProcessTree.descendants(1, mapOf(1 to 2, 2 to 1)).toSet(), setOf(2))
    check("an empty tree has no descendants", GuestProcessTree.descendants(1, emptyMap()), emptyList<String>())
    // `/proc/<pid>/stat`'s `comm` may contain spaces and parentheses, which is why the
    // parse anchors on the LAST ')'.
    check("ppid parses from a plain stat line", GuestProcessTree.parseStatPpid("22921 (node) S 22918 22918 0 0 -1 4194560"), 22918)
    check("ppid parses when comm contains spaces", GuestProcessTree.parseStatPpid("7 (weird name here) R 3 7 7 0"), 3)
    check("ppid parses when comm contains parentheses", GuestProcessTree.parseStatPpid("7 (a)b) S 42 7 7 0"), 42)
    check("a garbage stat line yields null", GuestProcessTree.parseStatPpid("not a stat line"), null)
    // `123456L`, not `123456`: the function returns `Long?`, and `check` compares as
    // `Any?`, where a boxed Int is not equal to a boxed Long. (That mismatch printed
    // "expected 123456 / actual 123456", which is exactly what this harness is for.)
    check("start time parses at the documented offset", GuestProcessTree.parseStatStartTime("7 (x) S 3 7 7 0 -1 4194560 1 0 0 0 1 1 0 0 20 0 1 0 " + "123456" + " 0"), 123456L)
    check("a short stat line yields no start time", GuestProcessTree.parseStatStartTime("7 (x) S 3"), null)
    // The predicate that decides whether signals are sent at all. A wrong `true` kills a
    // recycled pid; a wrong `false` leaks a live tree; and an unreadable /proc entry
    // (observed == null) must never be treated as a match.
    check("the same start time is the same process", GuestProcessTree.isSameProcess(42L, 42L), true)
    check("a different start time is a recycled pid", GuestProcessTree.isSameProcess(43L, 42L), false)
    check("no observation is never a match", GuestProcessTree.isSameProcess(null, 42L), false)
    check("no recorded identity accepts any live process", GuestProcessTree.isSameProcess(42L, null), true)
    check("no observation and no identity is still not a match", GuestProcessTree.isSameProcess(null, null), false)

    // ================================================================ 5. 探针缓存
    // The key is `version + 档 + revision + digest`, and the 档 is in it because the two
    // configurations answer different questions: a verdict earned under one is not an
    // answer about the other (`ProrootProbeCache.key`).
    val key = ProrootProbeCache.key("2026-06-17.3", "abc123", ProrootSeccomp.Seccomp.tag)
    check("the key carries the cache version", key.startsWith("v2"), true)
    check("the key carries the revision", key.contains("2026-06-17.3"), true)
    check("the key carries the digest", key.contains("abc123"), true)
    val rendered = ProrootProbeCache.render(key, passed = true, detail = listOf("guestpath=translated", "passwd=translated"))
    val parsed = ProrootProbeCache.parse(rendered, key)
    check("a pass round-trips", parsed?.passed, true)
    check("the evidence is kept", parsed?.detail, listOf("guestpath=translated", "passwd=translated"))
    check("a failure round-trips as a failure", ProrootProbeCache.parse(ProrootProbeCache.render(key, false, emptyList<String>()), key)?.passed, false)
    // The point of the key: a verdict is only an answer to the question it was asked.
    check("a verdict for another revision is not reused", ProrootProbeCache.parse(rendered, ProrootProbeCache.key("2026-06-18.1", "abc123", ProrootSeccomp.Seccomp.tag)), null)
    check("a verdict for other binaries is not reused", ProrootProbeCache.parse(rendered, ProrootProbeCache.key("2026-06-17.3", "def456", ProrootSeccomp.Seccomp.tag)), null)
    // ... and the third question: which seccomp configuration was measured. A key that
    // forgot the 档 would let the two share a verdict, which is the same defect as a row
    // that says "proroot" while every launch falls back.
    val keyNoSeccomp = ProrootProbeCache.key("2026-06-17.3", "abc123", ProrootSeccomp.NoSeccomp.tag)
    check("the key carries the mode tag", key.contains(ProrootSeccomp.Seccomp.tag), true)
    check("the two modes get different keys", key == keyNoSeccomp, false)
    check("a verdict for another mode is not reused", ProrootProbeCache.parse(rendered, keyNoSeccomp), null)
    check("the other mode's verdict round-trips under its own key", ProrootProbeCache.parse(ProrootProbeCache.render(keyNoSeccomp, true, emptyList<String>()), keyNoSeccomp)?.passed, true)
    // `v1` files cannot be read: their verdict could not tell the two 档 apart, so it is not
    // an answer to a `v2` question. (A `v1` *failure* would otherwise keep proroot disabled
    // on a device whose real defect has since been fixed.)
    val legacy = "v1\t2026-06-17.3\tabc123\nFAIL\n  guestpath=untranslated（errno=2）"
    check("a v1 verdict is not reused", ProrootProbeCache.parse(legacy, key), null)
    check("an empty file is no verdict", ProrootProbeCache.parse("", key), null)
    check("a missing file is no verdict", ProrootProbeCache.parse(null, key), null)
    check("a truncated file is no verdict", ProrootProbeCache.parse("v2\t${ProrootSeccomp.Seccomp.tag}\t2026-06-17.3\tabc123\n", key), null)
    check("an unknown verdict word is no verdict", ProrootProbeCache.parse("$key\nMAYBE\n", key), null)
    check("CRLF is tolerated", ProrootProbeCache.parse("$key\r\nPASS\r\n", key)?.passed, true)

    // ================================================================ 6. raw syscall 探针
    // The three verdict shapes the Perl probe can emit, and what each means. The veto
    // is the leak: a raw read that returns *different* content than libc for the same
    // path is a silent host-file read (§5.P0-2).
    fun rawOutput(vararg lines: String) = lines.joinToString("\n")

    val translated = ProrootRawProbe.parse(
        rawOutput(
            "${ProrootRawProbe.MARKER}\tguestpath\ttranslated\tlen=36",
            "${ProrootRawProbe.MARKER}\thostpath\treachable\tlen=36",
            "${ProrootRawProbe.MARKER}\tpasswd\ttranslated\tlen=1234",
        ),
    )
    check("a translated raw syscall passes", translated.passed, true)
    check("translation is observed", translated.translated, true)
    check("a translated run has no leak", translated.leaked, false)
    check("a passing probe has no failure sentence", translated.failure, null)

    val untranslated = ProrootRawProbe.parse(
        rawOutput(
            "${ProrootRawProbe.MARKER}\tguestpath\tuntranslated\terrno=2",
            "${ProrootRawProbe.MARKER}\thostpath\tunreachable\terrno=2",
            "${ProrootRawProbe.MARKER}\tpasswd\tunreadable\terrno=13",
        ),
    )
    check("an untranslated raw syscall does not pass (REQUIRE_TRANSLATION)", untranslated.passed, ProrootRawProbe.REQUIRE_TRANSLATION.not())
    check("an untranslated run is not a leak", untranslated.leaked, false)
    check("the untranslated failure names the gap", untranslated.failure?.contains("没有") ?: false, true)

    val leaked = ProrootRawProbe.parse(
        rawOutput(
            "${ProrootRawProbe.MARKER}\tguestpath\ttranslated\tlen=36",
            "${ProrootRawProbe.MARKER}\tpasswd\tleaked\traw=115 libc=1234",
        ),
    )
    check("a leak is detected", leaked.leaked, true)
    check("a leak never passes", leaked.passed, false)
    check("the leak failure sentence says so", leaked.failure?.contains("宿主文件") ?: false, true)

    val mismatch = ProrootRawProbe.parse(
        rawOutput("${ProrootRawProbe.MARKER}\tguestpath\tmismatch\traw=10 libc=36"),
    )
    check("a planted-file mismatch counts as a leak", mismatch.leaked, true)
    check("a mismatch never passes", mismatch.passed, false)

    val noPerl = ProrootRawProbe.parse("${ProrootRawProbe.MARKER}\tinterpreter\tmissing\tperl")
    check("a missing interpreter does not pass", noPerl.passed, false)
    check("a missing interpreter is reported as such", noPerl.failure?.contains("perl") ?: false, true)

    val empty = ProrootRawProbe.parse("")
    check("empty output does not pass", empty.passed, false)
    check("empty output is reported", empty.describe().isNotEmpty(), true)
    check(
        "shell noise around the markers is ignored",
        ProrootRawProbe.parse("warning: something\ntranslated\n${ProrootRawProbe.MARKER}\tpasswd\ttranslated\tlen=1\n").passed,
        true,
    )
    check("a marker line too short to be a result is ignored", ProrootRawProbe.parse("${ProrootRawProbe.MARKER}\tpasswd").passed, false)
    check("describe() reports the pass", translated.describe().first().startsWith("✓"), true)
    check("describe() reports the leak", leaked.describe().first().startsWith("✗"), true)

    // The script carries the host spelling of the planted file, which is the only way it
    // can measure whether the raw layer is addressing host paths.
    val script = ProrootRawProbe.guestCommand(hostTmpPath = "/data/x/tmp", token = "TOKEN-1")
    check("the script plants the file at the guest spelling", script.contains(ProrootRawProbe.PLANTED_GUEST_PATH), true)
    check("the script knows the host spelling of /tmp", script.contains("/data/x/tmp/${ProrootRawProbe.PLANTED_NAME}"), true)
    check("the script uses the shared quoting rule", script.contains(ShellQuote.quote("TOKEN-1")), true)
    check("the arm64 openat number is the one §6.5 ② used", script.contains("syscall(56,"), true)
    check("the script checks for perl instead of assuming it", script.contains("command -v perl"), true)

    // ---- the launcher's own words are evidence, not noise ------------------------
    // The defect: proroot's launcher exits during argument parsing, writes
    // `[proroot] bad bind format (expected host:guest): /dev` to stderr and nothing to
    // stdout. `parse` used to keep only marker lines, so that run became an *empty*
    // report whose `failure` is the untranslated sentence — proroot was refused for a
    // measurement that never happened, and the reason was invisible in the row and the
    // report alike. The classification below is what makes the two failure modes
    // tellable apart, and it is the sentence the user can act on.
    val launcherDied = ProrootRawProbe.parse(
        "[proroot] bad bind format (expected host:guest): /dev\n" +
            "Usage: libproroot.so [-r rootfs] [-0] [--link2symlink] [-b host:guest] command\n",
    )
    check("a launcher killed before the guest is a launch failure", launcherDied.launcherFailed, true)
    check("the launcher's sentence is quoted verbatim", launcherDied.launcherFailure, "[proroot] bad bind format (expected host:guest): /dev")
    check("a launch failure never passes", launcherDied.passed, false)
    check("a launch failure is not reported as a translation verdict", launcherDied.failure?.contains("启动器") ?: false, true)
    check("a launch failure is not reported as untranslated", launcherDied.failure?.contains("没有被翻译") ?: false, false)
    check("the launch failure has its own header", launcherDied.describe().first().startsWith(ProrootRawProbe.LAUNCH_FAILURE_HEADER), true)
    check("the launch failure renders as its own phase", launcherDied.describe().any { it.trim().startsWith("${ProrootRawProbe.LAUNCHER_PHASE}=") }, true)
    check("the phase carries the launcher's sentence", launcherDied.describe().any { it.contains("[proroot] bad bind format") }, true)
    check("the second launcher line is kept as evidence", launcherDied.launchLines.any { it.startsWith("Usage:") }, true)
    check("what the launcher said is bounded", launcherDied.launchLines.size <= ProrootRawProbe.MAX_LAUNCH_LINES, true)
    check("a bounded launcher line is marked", ProrootRawProbe.parse("[proroot] ${"x".repeat(400)}").launchLines.first().length, ProrootRawProbe.MAX_LAUNCH_CHARS)
    // A run that *did* measure something is never reclassified: the launcher may print a
    // warning and still work, and then the phases decide.
    check(
        "a warning does not hide a real measurement",
        ProrootRawProbe.parse(
            "[proroot] something odd\n${ProrootRawProbe.MARKER}\tguestpath\tuntranslated\terrno=2\n",
        ).launcherFailed,
        false,
    )
    check("a healthy run has no launch lines", translated.launchLines, emptyList<String>())
    check("a missing interpreter is not a launch failure", noPerl.launcherFailed, false)
    check("an empty run is not a launch failure", empty.launcherFailed, false)

    // ================================================ 6b. 门禁按档位区分
    // The gate rule itself, executed rather than copied (`RuntimeChoice.probeGate` is what
    // `ProrootProbe.run` calls). Four inputs and the measurement's own verdicts: the leak is
    // a veto in **every** 档, the real `rg`/`fd` invocation is required in **every** 档, and
    // only the promise of raw translation is档-dependent.
    check("a leak vetoes proroot in the shipping mode", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = true, rawTranslated = true, toolsOk = true), false)
    check("a leak vetoes proroot in the no-seccomp mode too", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = true, rawTranslated = true, toolsOk = true), false)
    check("a leak vetoes even when nothing else is wrong", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = true, rawTranslated = false, toolsOk = true), false)
    check("broken tools veto proroot in the shipping mode", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = false, rawTranslated = true, toolsOk = false), false)
    check("broken tools veto proroot in the no-seccomp mode", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = false, rawTranslated = true, toolsOk = false), false)
    check("tools that never ran are not a pass in either mode", listOf(ProrootSeccomp.Seccomp, ProrootSeccomp.NoSeccomp).map { RuntimeChoice.probeGate(it, false, true, false) }, listOf(false, false))
    check("untranslated raw vetoes proroot where translation was promised", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = false, rawTranslated = false, toolsOk = true), false)
    check("untranslated raw is only information in the no-seccomp mode", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = false, rawTranslated = false, toolsOk = true), true)
    check("a good run passes in both modes", listOf(ProrootSeccomp.Seccomp, ProrootSeccomp.NoSeccomp).map { RuntimeChoice.probeGate(it, false, true, true) }, listOf(true, true))
    // The configuration this app actually launches under. It is the **strict** one, and
    // that is a decision with a reason (`ProrootSeccomp`'s KDoc): `PROROOT_NO_SECCOMP` has
    // no reader in v1.2.8, and the device measurement shows raw translation working under
    // the launcher's default — so weakening the gate would buy nothing and cost the one
    // reading that catches a silent host-file read.
    check("the shipping mode is the strict one", RuntimeChoice.PROROOT_SECCOMP, ProrootSeccomp.Seccomp)
    check("the shipping mode requires raw translation", RuntimeChoice.PROROOT_SECCOMP.requiresRawTranslation, true)
    check("the shipping mode needs no variable", RuntimeChoice.PROROOT_SECCOMP.envValue, null)
    check("only the no-seccomp mode carries a value", listOf(ProrootSeccomp.Seccomp.envValue, ProrootSeccomp.NoSeccomp.envValue), listOf(null, "1"))
    check("the two modes are named differently", listOf(ProrootSeccomp.Seccomp.shortLabel, ProrootSeccomp.NoSeccomp.shortLabel).toSet().size, 2)
    check("the two tags differ", listOf(ProrootSeccomp.Seccomp.tag, ProrootSeccomp.NoSeccomp.tag).toSet().size, 2)
    // The settings row and the report read this sentence, so the two 档 must not be made to
    // sound equivalent: the shipping one promises both layers, the other admits the gap.
    check("the shipping disclosure names both layers", listOf("libc", "svc").all { RuntimeChoice.PROROOT_SECCOMP.disclosure.contains(it) }, true)
    check("the no-seccomp disclosure admits the raw gap", ProrootSeccomp.NoSeccomp.disclosure.contains("raw syscall"), true)
    check("the disclosures are not the same sentence", ProrootSeccomp.Seccomp.disclosure == ProrootSeccomp.NoSeccomp.disclosure, false)
    check("the no-seccomp disclosure says it is not selectable", ProrootSeccomp.NoSeccomp.disclosure.contains("不可选"), true)
    check("every mode has a disclosure", ProrootSeccomp.entries.all { it.disclosure.isNotBlank() }, true)
    check("the in-use sentence names the shipping mode", RuntimeChoice.describe(EngineFallback.None).contains(RuntimeChoice.PROROOT_SECCOMP.disclosure), true)

    // ================================================================ 7. 启动 pid 句柄
    // Driven against a real directory: `arm` records what was there, `resolveLauncherPid`
    // reads the pid out of the one new name, and `deleteConfig` removes exactly that file.
    val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-handle-check")
    tmp.deleteRecursively()
    tmp.mkdirs()
    java.io.File(tmp, ProrootConfigSweep.name(111)).writeText("old")
    val handle = ProrootLaunchHandle.arm(tmp)
    check("a pre-existing table is not mistaken for this launch", handle.resolveLauncherPid(timeoutMs = 30), null)
    java.io.File(tmp, ProrootConfigSweep.name(222)).writeText("new")
    check("the new table's pid is resolved", handle.resolveLauncherPid(timeoutMs = 100), 222)
    check("the handle points at that file", handle.configFile()?.name, ProrootConfigSweep.name(222))
    check("the pre-existing table is untouched", java.io.File(tmp, ProrootConfigSweep.name(111)).exists(), true)
    handle.deleteConfig()
    check("deleteConfig removes this launch's table", java.io.File(tmp, ProrootConfigSweep.name(222)).exists(), false)
    check("deleteConfig leaves the other one", java.io.File(tmp, ProrootConfigSweep.name(111)).exists(), true)
    check("the resolve timeout is bounded", ProrootLaunchHandle.DEFAULT_RESOLVE_TIMEOUT_MS, 1_500L)
    // The token match is what stops two concurrent launches (engine + terminal, or a
    // mention scan during a package install) from taking each other's pid — which would
    // mean a terminal close reaping the running engine's tree.
    val environ = "PATH=/usr/bin\u0000${ProrootLaunchHandle.TOKEN_ENV}=abc\u0000HOME=/root\u0000"
    check("the token is found in a NUL-separated environ", ProrootLaunchHandle.environHasToken(environ, "abc"), true)
    check("another token is not a match", ProrootLaunchHandle.environHasToken(environ, "abd"), false)
    check("a prefix of a token is not a match", ProrootLaunchHandle.environHasToken(environ, "ab"), false)
    check("a longer token is not a match", ProrootLaunchHandle.environHasToken(environ, "abcd"), false)
    check("an empty environ is not a match", ProrootLaunchHandle.environHasToken("", "abc"), false)
    check(
        "the token inside another variable's value is not a match",
        ProrootLaunchHandle.environHasToken("X=${ProrootLaunchHandle.TOKEN_ENV}=abc\u0000", "abc"),
        false,
    )
    check("the token variable name is namespaced", ProrootLaunchHandle.TOKEN_ENV, "PI_LAUNCH_TOKEN")
    // A handle armed with a token must not accept a table that does not carry it. The
    // process in this test is the harness itself, whose environ has no such token, so
    // resolve must come back empty rather than guessing — the exact behaviour that
    // makes a concurrent launch safe.
    val tokenDir = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-handle-token")
    tokenDir.deleteRecursively()
    tokenDir.mkdirs()
    val strict = ProrootLaunchHandle.arm(tokenDir, launchToken = "not-in-any-environ")
    java.io.File(tokenDir, ProrootConfigSweep.name(333)).writeText("x")
    check("a table whose owner lacks this launch's token is not accepted", strict.resolveLauncherPid(timeoutMs = 30), null)
    check("nothing was deleted by a failed resolve", java.io.File(tokenDir, ProrootConfigSweep.name(333)).exists(), true)
    tokenDir.deleteRecursively()
    tmp.deleteRecursively()

    // ================================= 7b. 重新打开开关 = 重跑门禁（缺陷回归）
    // The defect: the gate's verdict is cached under `revision + digest`, and a *failed*
    // verdict therefore outlives its cause — a probe that failed once for a transient
    // reason would disable proroot for good, while the switch's own promise ("turning it
    // back on tries again") appeared to do nothing, because the key cannot change.
    //
    // The fix is [ProrootRetry], which production reaches through
    // `RuntimeSelection.setProrootEnabled`. Rather than assert a copy of its rule, this
    // section **executes it** with the two effects injected: a counter in a variable and
    // the real unlink of a real cache file under a temporary `PiPaths`. If production
    // ever stops calling it, or calls it with the wrong condition, this is what fails.
    val retryRoot = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-retry-check")
    retryRoot.deleteRecursively()
    val retryPaths = PiPaths(
        filesDir = java.io.File(retryRoot, "files"),
        nativeLibDir = java.io.File(retryRoot, "lib"),
    )
    check(
        "the cache the gate reads and writes is the one the retry deletes",
        retryPaths.prorootProbeCache().path,
        "${retryRoot.path}/files/pi/runtime/.proroot-probe",
    )

    // (1) The rule, in both directions.
    check("re-enabling resets the failure counter", ProrootRetry.plan(nowEnabled = true).failureCounterReset, true)
    check("re-enabling invalidates the cached verdict", ProrootRetry.plan(nowEnabled = true).probeCacheInvalidated, true)
    check("disabling does not invalidate the cached verdict", ProrootRetry.plan(nowEnabled = false).probeCacheInvalidated, false)
    check("the toggle rule is the one the decision point exposes", RuntimeChoice.invalidatesProbeCache(true), ProrootRetry.plan(true).probeCacheInvalidated)

    // (2) The key really is insensitive to the switch — which is *why* deleting the file
    // is load-bearing rather than decorative. A cached failure stays valid for its key
    // across any number of toggles; only the unlink changes the answer.
    val failKey = ProrootProbeCache.key("2026-06-17.3", "abc123", ProrootSeccomp.Seccomp.tag)
    val failText = ProrootProbeCache.render(failKey, passed = false, detail = listOf("✗ 探针未通过"))
    check("a cached failure is valid for its key", ProrootProbeCache.parse(failText, failKey)?.passed, false)
    check("and the key does not mention the switch", failKey.contains("enabled") || failKey.contains("proroot"), false)

    // (3) The production transition, executed for real: cache file first, then off→on.
    retryPaths.prorootProbeCache().parentFile?.mkdirs()
    retryPaths.prorootProbeCache().writeText(failText)
    check("the stale failure is on disk", retryPaths.prorootProbeCache().isFile, true)
    check("invalidating a cache that does not exist is not an error", retryPaths.clearProrootProbeCache(), true)
    check("the stale verdict is gone", retryPaths.prorootProbeCache().exists(), false)

    // Named `retryFailures`, **not** `failures`: a local reusing the harness counter's name
    // shadows it for the rest of `main`, and the final verdict then reads the local — which
    // the transition under test sets to 0 — instead of the number of failed checks. That is
    // how this harness printed `OK` over a failing assertion before 2026-09-19.
    var retryFailures = 3
    retryPaths.prorootProbeCache().writeText(failText)
    val applied = ProrootRetry.apply(
        nowEnabled = true,
        resetFailures = { retryFailures = 0 },
        invalidateProbeCache = { retryPaths.clearProrootProbeCache() },
    )
    check("the executed transition reports both effects", applied, ProrootRetry.Plan(failureCounterReset = true, probeCacheInvalidated = true))
    check("the executed transition cleared the counter", retryFailures, 0)
    check("the executed transition removed the cached failure", retryPaths.prorootProbeCache().exists(), false)
    // What `RuntimeSelection.status()` does with no cache under the current key: the row
    // says "the next launch will probe", not "probe failed" — the user-visible half of
    // the fix, and the device criterion in `docs/device-verification.md` §J8.
    check("no cache means 'not yet run' rather than 'failed'", ProrootProbeCache.parse(null, failKey), null)

    // (4) Turning it **off** keeps the verdict: the cache describes the runtime tree, not
    // the switch, and there is nothing to re-earn on the way out.
    retryFailures = 1
    retryPaths.prorootProbeCache().writeText(failText)
    val off = ProrootRetry.apply(
        nowEnabled = false,
        resetFailures = { retryFailures = 0 },
        invalidateProbeCache = { retryPaths.clearProrootProbeCache() },
    )
    check("switching off still resets the counter", retryFailures, 0)
    check("switching off does not touch the cache", retryPaths.prorootProbeCache().isFile, true)
    check("and says so in its plan", off.probeCacheInvalidated, false)
    check("the kept verdict is still readable", ProrootProbeCache.parse(retryPaths.prorootProbeCache().readText(), failKey)?.passed, false)
    retryRoot.deleteRecursively()

    // ================================================================ 8. 共享配方常量
    check("the shell tail is bash -c", GuestRecipe.shellArgs("x"), listOf("/bin/bash", "-c", "x"))
    check("the tmp bind is the app's own directory", GuestRecipe.tmpBind(p), listOf("-b", "${p.tmp.path}:/tmp"))
    check("the proroot scratch is inside the volatile runtime tree", p.prorootTmp.path, "$FILES/pi/runtime/proroot-tmp")
    check("the probe cache is inside the volatile runtime tree", p.prorootProbeCache().path, "$FILES/pi/runtime/.proroot-probe")
    check("the CA bundle is the payload's path", GuestRecipe.GUEST_CA_BUNDLE, "/etc/ssl/certs/ca-certificates.crt")
    check("quoting survives an embedded single quote", ShellQuote.quote("a'b"), "'a'\\''b'")

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
