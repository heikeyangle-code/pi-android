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
// 10. **A bind's host side is spelled the way the guest kernel spells it** (added
//     2026-09-20). `Context.getFilesDir()` gives `/data/user/0/<pkg>/files` while the
//     kernel gives `/data/data/<pkg>/files`, and both runtimes reverse-map by string
//     prefix — so the agent dir and the workspace were unreachable by relative path (and
//     pi's `readdirSync` of the extension dir failed ENOENT, silently losing every
//     bundled tool). The alias fixture below is a symlink the harness makes itself, so the
//     rule is pinned independently of the machine that runs it.
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
    check("proot's binds are the shared table plus l2s and tmp", prootBinds.toSet(), (sharedBinds + l2sBind + GuestRecipe.tmpBind(p)[1]).toSet())
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
    check("proroot qualifies the shared table", prorootBinds.toSet(), (sharedBinds.map { ProrootCommand.bindArgument(it) } + GuestRecipe.tmpBind(p)[1]).toSet())
    check("proroot emits no unqualified bind value", prorootBinds.all { it.contains(ProrootCommand.BIND_SEPARATOR) }, true)
    check("proroot binds /dev to /dev", prorootBinds.contains("/dev:/dev"), true)
    check("proot's bind count is unchanged by the respelling", prootBinds.size, sharedBinds.size + 2)

    // ---- 绑定源的拼写 == 内核会报告的那一种（别名 `/data/user/0` vs `/data/data`）------
    // `Context.getFilesDir()` 给的是**符号链接那一侧**（`/data/user/0/<pkg>/files`），而内核给的
    // 是**物理那一侧**（`/data/data/<pkg>/files`）。两个运行时把宿主路径翻回 guest 路径靠的都是
    // **字符串前缀匹配**，host 侧拼错的那一条绑定就匹配不上内核报出来的路径：guest 的初始 cwd
    // 正是绑定的宿主目录，于是 `getcwd()` 漏出宿主拼写，`.`、`..`、`git status`、子进程全挂。
    //
    // 本机实测（一台**正在跑的 proroot guest**，launcher argv 里有
    // `-b /data/user/0/com.dsh.client/cache/shm:/dev/shm`）：
    //   `cd /dev/shm && /bin/pwd` -> `/data/data/com.dsh.client/cache/shm`（宿主拼写）
    //   `ls .` -> ENOENT          对照 `cd /sdcard && /bin/pwd` -> `/sdcard`
    // 同一个 guest 里，八个绑定根里**唯一**拼写不 canonical 的那一个（`/dev/shm`）是**唯一**
    // 让 Node 的 `readdir` 报 ENOENT 的目录，其余（`/sdcard`、`/system`、`/proc`、`/tmp`、`/root`）
    // 都能读。`GuestRecipe.canonicalHost` 的 KDoc 记了完整证据链。
    //
    // 判据必须与跑它的机器无关，所以别名用**自己造的符号链接**，断言的是规则本身。
    val aliasRoot = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-bind-alias")
    aliasRoot.deleteRecursively()
    val aliasRealDir = java.io.File(aliasRoot, "real")
    java.io.File(aliasRealDir, "pi/workspaces/workspace-1").mkdirs()
    val aliasLink = java.io.File(aliasRoot, "link")
    java.nio.file.Files.createSymbolicLink(aliasLink.toPath(), aliasRealDir.toPath())
    val linkedWorkspace = "${aliasLink.path}/pi/workspaces/workspace-1"
    val realWorkspace = java.io.File(aliasRealDir, "pi/workspaces/workspace-1").canonicalPath

    check("the fixture link really is an alias", linkedWorkspace == realWorkspace, false)
    check("a host path is respelled the kernel's way", GuestRecipe.canonicalHost(linkedWorkspace), realWorkspace)
    check("canonicalising is idempotent", GuestRecipe.canonicalHost(realWorkspace), realWorkspace)
    check("an unresolvable path keeps its canonical prefix", GuestRecipe.canonicalHost("$aliasRoot/nope/x"), "${aliasRoot.canonicalPath}/nope/x")
    check("an empty host is not resolved to the process cwd", GuestRecipe.canonicalHost(""), "")
    // `/proc` 是合成的、**相对进程**的：`/proc/self` 解析成**本进程**，canonicalPath 会把表里那条
    // `/proc/self/fd:/dev/fd` 变成 `/proc/<App 的 pid>/fd`——绑错目录，而且每个进程一个不同的字符串。
    check("the /proc subtree is never respelled", GuestRecipe.canonicalHost("/proc/self/fd"), "/proc/self/fd")
    check("and neither is /proc itself", GuestRecipe.canonicalHost("/proc"), "/proc")
    check("the table's /proc entry survives", prorootBinds.contains("/proc/self/fd:/dev/fd"), true)
    check("the separator is the shared constant", ProrootCommand.BIND_SEPARATOR, GuestRecipe.BIND_SEPARATOR)

    // host 侧会动，guest 侧永不改：这就是 `bindValue` 的全部契约。
    check("the host side of a pair is canonicalised", GuestRecipe.bindValue("$linkedWorkspace:/workspace/x"), "$realWorkspace:/workspace/x")
    check("the guest side of a pair is untouched", GuestRecipe.bindValue("$realWorkspace:/workspace/pi/workspaces/workspace-1"), "$realWorkspace:/workspace/pi/workspaces/workspace-1")
    // 裸值是 proot 的 `host == guest` 简写：没有独立的 host 侧，改它会连 guest 挂载点一起挪。
    check("a bare value is left exactly as written", GuestRecipe.bindValue(linkedWorkspace), linkedWorkspace)
    check("a bare canonical value is left alone too", GuestRecipe.bindValue("/dev"), "/dev")
    // proroot 用不了简写，所以它自己展开——展开**只动 host 侧**，guest 侧逐字不动：简写断言的是
    // "两边指同一个目录"，把 guest 侧也换成解析后的路径就等于悄悄把挂载点挪了。
    check("proroot expands a bare value without moving the guest side", ProrootCommand.bindArgument(linkedWorkspace), "$realWorkspace:$linkedWorkspace")
    check("...so the guest side is byte-identical to what the shorthand named", ProrootCommand.bindArgument(linkedWorkspace).substringAfter(":"), linkedWorkspace)
    check("...and /dev, which is already canonical, is unchanged on both sides", ProrootCommand.bindArgument("/dev"), "/dev:/dev")

    // ---- 唯一裁决点的边界（A1-1 / A1-2 / A1-4）--------------------------------
    // 相对路径：`File("rel").canonicalPath` 会拿 **JVM 的 user.dir** 拼出一个宿主绝对路径 ——
    // 一个调用方从没要过的绑来源。规则是原样返回，让启动器自己报错。
    check("a relative host is never resolved against the JVM cwd", GuestRecipe.canonicalHost("relative/dir"), "relative/dir")
    check("...and the same holds through bindValue", GuestRecipe.bindValue("relative/dir:/guest"), "relative/dir:/guest")
    check("a relative bare value stays bare", ProrootCommand.bindArgument("relative/dir"), "relative/dir:relative/dir")
    // procfs 的别名拼写与 `/proc` 同一类：合成、**相对进程**。`canonicalPath("/dev/stdin")` 会变成
    // `/proc/<App 的 pid>/fd/0`，每个进程一个不同的字符串。
    check("a procfs descriptor alias is never respelled", GuestRecipe.canonicalHost("/dev/stdin"), "/dev/stdin")
    check("...nor /dev/stdout", GuestRecipe.canonicalHost("/dev/stdout"), "/dev/stdout")
    check("...nor /dev/stderr", GuestRecipe.canonicalHost("/dev/stderr"), "/dev/stderr")
    check("...nor /dev/fd", GuestRecipe.canonicalHost("/dev/fd"), "/dev/fd")
    check("...nor anything under /proc", GuestRecipe.canonicalHost("/proc/self/cwd"), "/proc/self/cwd")
    // 多用户：`/data/user/0` 是符号链接、`/data/user/10` 不是，规则交给 canonicalPath，两者都
    // 必须不等于"被 JVM cwd 拼过"的东西（这里用注入的解析器表达，不依赖本机布局）。
    GuestRecipe.installCanonicalResolverForTest { path -> "canonical:$path" }
    check("the resolver is consulted for an ordinary absolute host", GuestRecipe.canonicalHost("/data/user/0/x"), "canonical:/data/user/0/x")
    check("...but not for /proc", GuestRecipe.canonicalHost("/proc/self"), "/proc/self")
    check("...nor for a procfs alias", GuestRecipe.canonicalHost("/dev/stderr"), "/dev/stderr")
    check("...nor for a relative path", GuestRecipe.canonicalHost("x"), "x")
    check("...nor for blank input", GuestRecipe.canonicalHost("  "), "  ")
    // 热路径：同一个 host 在**进程内只解析一次**（第二次起是查表），并且不同 host 各占一格。
    val before = GuestRecipe.canonicalHostCacheSizeForTest()
    check("the second lookup of the same host is a cache hit", GuestRecipe.canonicalHost("/data/user/0/x"), "canonical:/data/user/0/x")
    check("...so the cache did not grow", GuestRecipe.canonicalHostCacheSizeForTest(), before)
    check("a new host is resolved once and remembered", GuestRecipe.canonicalHost("/data/user/0/y"), "canonical:/data/user/0/y")
    check("...and the cache grew by exactly one", GuestRecipe.canonicalHostCacheSizeForTest(), before + 1)
    GuestRecipe.resetCanonicalResolverForTest()

    // ---- `/dev/shm`：共享表必须有它（实测参考实现的 argv 里有 `-b <cache>/shm:/dev/shm`）------
    // 没有它，guest 的 `/dev/shm` 就是宿主 `/dev` 里恰好有什么（Android 没有），POSIX 共享内存
    // 随之失败。两套运行时都要这一条，所以它在共享表里。
    val shmBinds = binds(ProotCommand.build(p, command, "/root", storage)).filter { it.endsWith(":/dev/shm") }
    check("the shared table binds /dev/shm", shmBinds.size, 1)
    check("...from a directory this app owns", shmBinds.single(), "${p.shm.canonicalPath}:/dev/shm")
    check("...and proroot emits it qualified too", binds(ProrootCommand.build(p, command, "/root", storage)).count { it.endsWith(":/dev/shm") }, 1)
    check("...with the guest side unchanged", binds(ProrootCommand.build(p, command, "/root", storage)).single { it.endsWith(":/dev/shm") }.substringAfter(":"), "/dev/shm")
    // `/dev/shm` 必须排在 `-b /dev` **之后**：两套运行时都是后面的绑定覆盖前面的（实测
    // `/dev/random` 因此指向 urandom，major:minor 1:9）。
    val devIndex = sharedBinds.indexOf("/dev")
    val shmIndex = sharedBinds.indexOfFirst { it.endsWith(":/dev/shm") }
    check("...and after the /dev bind, so the later bind wins", devIndex >= 0 && shmIndex > devIndex, true)

    // ---- 引擎启动前的目录自检（`ProrootEngineReadDirProbe`）-------------------------
    // 为什么它在：受控 A/B 表明 `-b` 的 host 侧用内核拼写时 readdir 正常、用 `getFilesDir()`
    // 原样拼写时坏，而 `-r` 的拼写无影响；但设备在按该规则修完之后**仍然**报告"有的目录行、
    // 有的不行"。于是不再赌拼写：引擎启动前用引擎自己的绑定形状真的列一次必须可列的目录，
    // 任何一条列不出来就让本次启动用 proot —— 用户永远不会看到"资源看不见"的状态。
    val gateDirs = listOf("/root/.pi/agent/extensions", "/root/.pi/agent/skills", "/workspace/ws", "/tmp")
    fun gateLine(path: String, count: Int) = "${ProrootEngineReadDirProbe.MARKER}\t$path\t$count"
    fun gateErr(path: String, code: String) =
        "${ProrootEngineReadDirProbe.MARKER}\t$path\t${ProrootEngineReadDirProbe.ERROR}\t$code"
    val gateAllOk = ProrootEngineReadDirProbe.parse(gateDirs.joinToString("\n") { gateLine(it, 3) }, gateDirs)
    check("every required engine directory listed ⇒ the check passes", gateAllOk.ok, true)
    check("...and the line says so with the stage's pass mark", gateAllOk.describe().single().startsWith("✓ 引擎目录可列"), true)
    // 一条坏就整条失败：这正是用户报的"有的目录行、有的不行"。
    val gateOneBad = ProrootEngineReadDirProbe.parse(
        listOf(
            gateLine("/root/.pi/agent/extensions", 3),
            gateErr("/root/.pi/agent/skills", "ENOENT"),
            gateLine("/workspace/ws", 6),
            gateLine("/tmp", 10),
        ).joinToString("\n"),
        gateDirs,
    )
    check("one unreadable directory fails the whole check", gateOneBad.ok, false)
    check("...and the failing directory is named with its errno", gateOneBad.describe().single().contains("/root/.pi/agent/skills=ENOENT"), true)
    // 没输出、噪声、坏计数都不能读成通过（"guest 没回答"不是 pass）。
    check("silence is a failure", ProrootEngineReadDirProbe.parse("", gateDirs).ok, false)
    check(
        "a line the guest never printed is a failure for that directory",
        ProrootEngineReadDirProbe.parse(gateLine("/tmp", 1), gateDirs).dirs.count { !it.ok },
        gateDirs.size - 1,
    )
    check(
        "unrelated shell noise is ignored",
        ProrootEngineReadDirProbe.parse("bash: warning: x\n" + gateDirs.joinToString("\n") { gateLine(it, 0) }, gateDirs).ok,
        true,
    )
    check(
        "an unparseable count is a failure, not a zero",
        ProrootEngineReadDirProbe.parse(gateDirs.joinToString("\n") { gateLine(it, 1) }.replaceFirst("\t1", "\t?"), gateDirs).ok,
        false,
    )
    check(
        "the command runs node (the only reader that shows the defect)",
        ProrootEngineReadDirProbe.guestCommand(gateDirs).contains("node -e") &&
            ProrootEngineReadDirProbe.guestCommand(gateDirs).contains("/root/.pi/agent/skills"),
        true,
    )

    // 两个 builder，在别名上：出去的 host 侧是解析后的路径。
    val aliasExtra = linkedWorkspace to "/workspace/pi/workspaces/workspace-1"
    val prorootAlias = binds(ProrootCommand.build(p, command, "/root", null, listOf(aliasExtra)))
    check("proroot canonicalises an extra bind's host side", prorootAlias.contains("$realWorkspace:/workspace/pi/workspaces/workspace-1"), true)
    check("proroot emits no bind through the alias", prorootAlias.none { it.startsWith("${aliasLink.path}:") }, true)
    val prootAlias = binds(ProotCommand.build(p, command, "/root", null, listOf(aliasExtra)))
    check("proot canonicalises an extra bind's host side too", prootAlias.contains("$realWorkspace:/workspace/pi/workspaces/workspace-1"), true)
    check("proot emits no bind through the alias", prootAlias.none { it.startsWith("${aliasLink.path}:") }, true)

    // 引擎自己的形状：工作区绑定 + `-w <guest 工作区>`，也正是用户看到它的地方。
    // (名字不能叫 `engineCwd`：下面那段 `-w` 的判据在同一个 `main()` 作用域里用它。)
    val engineGuestCwd = "/workspace/pi/workspaces/workspace-1"
    val engineArgv = ProrootCommand.build(p, command, engineGuestCwd, storage, listOf(aliasExtra))
    check("the engine's bind keeps its guest path", binds(engineArgv).contains("$realWorkspace:$engineGuestCwd"), true)
    check("and -w is still the guest path", engineArgv.windowed(2).any { it == listOf("-w", engineGuestCwd) }, true)

    // 一个漏斗：`ensureWorkdir` 判的绑定对与 argv 里的是同一组（"目录存在"与"能被反向映射"
    // 必须是同一个问题）。
    check(
        "the pair list is the argv's bind set",
        ProrootCommand.boundPairs(p, storage, listOf(aliasExtra)).map { (host, guest) -> "$host:$guest" }.toSet(),
        binds(ProrootCommand.build(p, command, "/root", storage, listOf(aliasExtra))).toSet(),
    )

    // **files 目录本身是符号链接**：`/tmp` 也在 App 数据目录下，所以它也必须解析后输出——
    // 否则 guest 里 `cd /tmp` 会遇到同一个问题。
    val aliasPaths = PiPaths(filesDir = aliasLink, nativeLibDir = java.io.File(NATIVE))
    check("the alias tmp path really differs", aliasPaths.tmp.path == aliasPaths.tmp.canonicalPath, false)
    check("proroot's tmp bind is the resolved path", binds(ProrootCommand.build(aliasPaths, command, "/", null)).first { it.endsWith(":/tmp") }, "${aliasPaths.tmp.canonicalPath}:/tmp")
    check("proroot emits nothing through the symlinked files dir", binds(ProrootCommand.build(aliasPaths, command, "/", null)).none { it.startsWith("${aliasLink.path}/") }, true)
    // proot additionally carries the `-b <l2s>:<l2s>` pair, which is **deliberately left
    // untouched** (both sides must stay the same string — `PiRuntime.kt`'s comment on that
    // line), so it is the one value allowed to be spelled through the symlink.
    val aliasProotBinds = binds(ProotCommand.build(aliasPaths, command, "/", null))
        .filterNot { it == "${aliasPaths.l2s.path}:${aliasPaths.l2s.path}" }
    check("proot's tmp bind is the resolved path", aliasProotBinds.first { it.endsWith(":/tmp") }, "${aliasPaths.tmp.canonicalPath}:/tmp")
    check("proot emits nothing else through the symlinked files dir", aliasProotBinds.none { it.startsWith("${aliasLink.path}/") }, true)
    check("and the l2s pair really is the exception", binds(ProotCommand.build(aliasPaths, command, "/", null)).any { it == "${aliasPaths.l2s.path}:${aliasPaths.l2s.path}" }, true)
    aliasRoot.deleteRecursively()

    // ---- `-w` 的目录必须在 rootfs 里存在（2026-09-19 实测；引擎 126 的根因） -------
    // proroot v1.2.8 的 `-w` **只按 rootfs 解析**、不看绑定表：`<rootfs>/<cwd>` 不存在时
    // 启动器打印 `[proroot] chdir workdir failed: …` 并让子进程以 126 退出（原文见
    // `ProrootCommand` 与 `ProrootExecProbe` 的 KDoc）。修法是 `ensureWorkdir`，它的纪律是
    // "只在有绑定覆盖、且那条绑定的 host 目录存在时才补目录"——无条件的 `mkdirs()` 会把
    // "忘了传绑定"从一次响亮的启动失败变成一次安静的空工作区运行。下面把纯判据和真实
    // 文件上的每一种状态都钉住。
    check("a guest path maps to its rootfs path", ProrootCommand.workdirUnder(p.rootfs, "/workspace/pi/workspaces/workspace-1"), java.io.File(p.rootfs, "workspace/pi/workspaces/workspace-1"))
    check("a trailing slash is the same directory", ProrootCommand.workdirUnder(p.rootfs, "/workspace/"), java.io.File(p.rootfs, "workspace"))
    check("`-w /` is the rootfs itself", ProrootCommand.workdirUnder(p.rootfs, "/"), p.rootfs)
    check("a relative cwd is refused", ProrootCommand.workdirUnder(p.rootfs, "workspace"), null)
    check("a `..` segment is refused", ProrootCommand.workdirUnder(p.rootfs, "/workspace/../etc"), null)
    check("an empty segment is refused", ProrootCommand.workdirUnder(p.rootfs, "/workspace//x"), null)

    val engineCwd = "/workspace/pi/workspaces/workspace-1"
    val workspaceBind = "/data/user/0/app.pi/files/pi/workspaces/workspace-1" to engineCwd
    check("the engine's own bind covers its cwd", ProrootCommand.bindingCovering(engineCwd, listOf(workspaceBind)), workspaceBind)
    check("a bind above the cwd covers it", ProrootCommand.bindingCovering("$engineCwd/sub", listOf(workspaceBind)), workspaceBind)
    check("a name that only looks like a prefix does not cover", ProrootCommand.bindingCovering("${engineCwd}x", listOf(workspaceBind)), null)
    check("no bind covers /root", ProrootCommand.bindingCovering("/root", listOf(workspaceBind)), null)
    check("the builder sees the extra binds and the tmp bind", ProrootCommand.boundPairs(p, null, listOf(workspaceBind)).let { it.contains(workspaceBind) && it.any { pair -> pair.second == "/tmp" } }, true)
    check("a bare proot binding reads as host == guest", ProrootCommand.boundPairs(p, null, emptyList()).contains("/dev" to "/dev"), true)

    // 真实文件上的每一种状态。`ensureWorkdir` 只会碰 rootfs 里那个替身目录。
    val wdRoot = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-workdir-check")
    wdRoot.deleteRecursively()
    val wdRootfs = java.io.File(wdRoot, "rootfs")
    val wdHost = java.io.File(wdRoot, "host/workspace-1")
    check("no bind means no directory is created", ProrootCommand.ensureWorkdir(wdRootfs, engineCwd, emptyList()), ProrootCommand.WorkdirState.NO_BIND)
    check("...and nothing was created", wdRootfs.exists(), false)
    wdHost.mkdirs()
    val wdCovering = listOf(wdHost.path to engineCwd)
    check("a rootfs that is not unpacked is left alone", ProrootCommand.ensureWorkdir(wdRootfs, engineCwd, wdCovering), ProrootCommand.WorkdirState.NO_ROOTFS)
    check("...and still nothing was created", wdRootfs.exists(), false)
    wdRootfs.mkdirs()
    check("the first launch creates the guest cwd", ProrootCommand.ensureWorkdir(wdRootfs, engineCwd, wdCovering), ProrootCommand.WorkdirState.CREATED)
    check("...and it is a directory", java.io.File(wdRootfs, "workspace/pi/workspaces/workspace-1").isDirectory, true)
    check("the second launch finds it", ProrootCommand.ensureWorkdir(wdRootfs, engineCwd, wdCovering), ProrootCommand.WorkdirState.PRESENT)
    check("a missing bind host is not papered over", ProrootCommand.ensureWorkdir(wdRootfs, "/workspace/other", listOf(java.io.File(wdRoot, "gone").path to "/workspace/other")), ProrootCommand.WorkdirState.HOST_MISSING)
    check("...and that path was not created either", java.io.File(wdRootfs, "workspace/other").exists(), false)
    val wdBlocked = java.io.File(wdRoot, "blocked/rootfs")
    wdBlocked.mkdirs()
    java.io.File(wdBlocked, "workspace").writeText("a file where a directory has to be")
    check("a mkdirs() that cannot win is reported as such", ProrootCommand.ensureWorkdir(wdBlocked, engineCwd, wdCovering), ProrootCommand.WorkdirState.FAILED)

    // 端到端：`build` 是唯一的漏斗（引擎 / 装包 / 终端 / 三个探针阶段 / 失败取证都走它），
    // 所以引擎形状的 argv 一经产出，rootfs 里就已经有了那个 `-w` 目录。
    val wdPaths = PiPaths(filesDir = java.io.File(wdRoot, "files"), nativeLibDir = java.io.File(wdRoot, "lib"))
    wdPaths.rootfs.mkdirs()
    val wdArgv = ProrootCommand.build(wdPaths, "echo hi", engineCwd, null, listOf(wdHost.path to engineCwd))
    check("build() prepares the engine's -w in the rootfs", java.io.File(wdPaths.rootfs, "workspace/pi/workspaces/workspace-1").isDirectory, true)
    check("...and still passes the cwd it was given", wdArgv.windowed(2).any { it == listOf("-w", engineCwd) }, true)
    wdRoot.deleteRecursively()

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
    // 这一组句子读出来的是「运行时（实际生效）」那一行的值：标题问「在跑哪个」，所以每个值
    // 都必须**先写那个运行时**，原因才跟在括号里。旧的一组是反过来的（关掉时写「未开启（走
    // proot）」），用户直接问了出来：「关掉 Pro Root 为什么要写着未开启」。这条不变量逐值
    // 钉住「谁在最前面」，改文案时最先被它拦住。
    val sentences = EngineFallback.entries.associateWith { RuntimeChoice.describe(it) }
    check(
        "every sentence leads with the runtime that is in effect",
        sentences.values.map { it.substringBefore('（') }.distinct().sorted(),
        listOf("proot", "proroot"),
    )
    check(
        "the sentence for proroot leads with proroot, every proot fallback with proot",
        sentences[EngineFallback.None]?.substringBefore('（'),
        "proroot",
    )
    check(
        "every proot fallback leads with proot",
        EngineFallback.entries.filter { it != EngineFallback.None }
            .associateWith { sentences[it]?.substringBefore('（') },
        EngineFallback.entries.filter { it != EngineFallback.None }.associateWith { "proot" },
    )
    check(
        "turning the switch off reads as the runtime, not as the switch",
        sentences[EngineFallback.SwitchOff],
        "proot",
    )
    // 尚未运行 不再讲「下一次 / 再下一次」：拨开开关现在会**当场**跑探针并重启引擎
    // （`ui/settings/RuntimeSwitchAction`），所以三趟车的说明既不是用户看到的事，也和开关
    // 那一行的文案互相矛盾。这一档只剩一个含义：此刻没有这个 revision 的探针结论。
    val notRun = RuntimeChoice.describe(EngineFallback.ProbeNotRun)
    check("尚未运行 names the missing probe verdict", notRun.contains("探针尚未运行"), true)
    check("尚未运行 no longer promises a next launch", notRun.contains("下一次"), false)
    check("尚未运行 no longer counts launches", notRun.contains("再下一次"), false)
    check("尚未运行 is still one line", notRun.none { it == '\n' || it == '\r' }, true)
    check(
        "the failure streak names the count and the way out",
        listOf(
            sentences[EngineFallback.FailureStreak]?.contains("3 次启动失败") == true,
            sentences[EngineFallback.FailureStreak]?.contains("重新打开开关") == true,
        ),
        listOf(true, true),
    )
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
    check("the key carries the cache version", key.startsWith(ProrootProbeCache.VERSION), true)
    check("the cache version is the dynamic-binary one", ProrootProbeCache.VERSION, "v3")
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
    // ... and `v2` is stale for the same reason, one stage later: it was earned by a probe
    // that never executed the engine's own binary (`ProrootExecProbe`), so a `v2` PASS was
    // cached on the device where every engine launch died with 126. The exact bytes a `v2`
    // build wrote are unreadable now, PASS and FAIL alike.
    val v2Pass = "v2\t${ProrootSeccomp.Seccomp.tag}\t2026-06-17.3\tabc123\nPASS\n  ✓ raw syscall 探针通过"
    val v2Fail = "v2\t${ProrootSeccomp.Seccomp.tag}\t2026-06-17.3\tabc123\nFAIL\n  ✗ raw syscall 探针未通过"
    check("a v2 pass is not reused", ProrootProbeCache.parse(v2Pass, key), null)
    check("a v2 failure is not reused either", ProrootProbeCache.parse(v2Fail, key), null)
    check("an empty file is no verdict", ProrootProbeCache.parse("", key), null)
    check("a missing file is no verdict", ProrootProbeCache.parse(null, key), null)
    check("a truncated file is no verdict", ProrootProbeCache.parse("v3\t${ProrootSeccomp.Seccomp.tag}\t2026-06-17.3\tabc123\n", key), null)
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
    // `ProrootProbe.run` calls). Five inputs and the measurements' own verdicts: the leak is
    // a veto in **every** 档, the real `rg`/`fd` invocation is required in **every** 档, the
    // engine-class binary is required in **every** 档, and only the promise of raw
    // translation is档-dependent.
    check("a leak vetoes proroot in the shipping mode", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = true, rawTranslated = true, toolsOk = true, execOk = true), false)
    check("a leak vetoes proroot in the no-seccomp mode too", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = true, rawTranslated = true, toolsOk = true, execOk = true), false)
    check("a leak vetoes even when nothing else is wrong", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = true, rawTranslated = false, toolsOk = true, execOk = true), false)
    check("broken tools veto proroot in the shipping mode", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = false, rawTranslated = true, toolsOk = false, execOk = true), false)
    check("broken tools veto proroot in the no-seccomp mode", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = false, rawTranslated = true, toolsOk = false, execOk = true), false)
    check("tools that never ran are not a pass in either mode", listOf(ProrootSeccomp.Seccomp, ProrootSeccomp.NoSeccomp).map { RuntimeChoice.probeGate(it, false, true, false, true) }, listOf(false, false))
    check("untranslated raw vetoes proroot where translation was promised", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = false, rawTranslated = false, toolsOk = true, execOk = true), false)
    check("untranslated raw is only information in the no-seccomp mode", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = false, rawTranslated = false, toolsOk = true, execOk = true), true)
    check("a good run passes in both modes", listOf(ProrootSeccomp.Seccomp, ProrootSeccomp.NoSeccomp).map { RuntimeChoice.probeGate(it, false, true, true, true) }, listOf(true, true))

    // ---- the third stage, and the defect that added it (2026-09-19) --------------
    // ① The device: `guestpath=translated` + `✓ rg: ripgrep 15.2.0` + `✓ fd: fd 10.2.0` all
    //    passed **and** every engine launch exited 126. So a failed dynamic-binary stage must
    //    refuse proroot entirely — not "fall back for the engine only", because a runtime on
    //    which the engine cannot start is not a runtime with a hole in it.
    check("① a failed dynamic-binary stage refuses proroot (shipping mode)", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = false, rawTranslated = true, toolsOk = true, execOk = false), false)
    check("① and it refuses in the no-seccomp mode too", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, rawVetoed = false, rawTranslated = true, toolsOk = true, execOk = false), false)
    check("① it outranks a passing raw stage", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = false, rawTranslated = true, toolsOk = true, execOk = false), false)
    check("① a leak still outranks it (nothing is reported as 'just' the exec stage)", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, rawVetoed = true, rawTranslated = true, toolsOk = true, execOk = false), false)
    // ② Everything passing — including the engine's own binary — is the only pass.
    check("② exec + raw + tools all passing is the only pass", listOf(ProrootSeccomp.Seccomp, ProrootSeccomp.NoSeccomp).map { RuntimeChoice.probeGate(it, false, true, true, true) }, listOf(true, true))
    check("② a no-seccomp run that only lacks raw translation still passes", RuntimeChoice.probeGate(ProrootSeccomp.NoSeccomp, false, false, true, true), true)
    check("② and the same run is refused where raw translation was promised", RuntimeChoice.probeGate(ProrootSeccomp.Seccomp, false, false, true, true), false)
    // ③ The cache key covers the new stage: `ProrootProbeCache.VERSION` is bumped, so a `v2`
    //    verdict (earned without the stage) is not readable as a current one. Pinned above in
    //    section 5, and the version is what makes it structural rather than conventional.
    check("③ the verdict's version is the stage-aware one", ProrootProbeCache.key("r", "d", ProrootSeccomp.Seccomp.tag).startsWith("v3\t"), true)
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

    // ================================================ 6c. 动态二进制档（ProrootExecProbe）
    // The stage added after the device where the first two passed and the engine still exited
    // 126. What is pinned here is the part that has to be right *before* a device is
    // available: which binaries it runs, that a non-zero exit or a missing `+x` is a failure
    // rather than a reading, and that the failure-side rendering is bounded and quotes only
    // proroot's own lines.
    check("the stage has a label the row can print", ProrootExecProbe.LABEL, "动态二进制")
    check("the targets are env and the engine's own node", ProrootExecProbe.TARGETS.map { it.exe }, listOf("/usr/bin/env", "/opt/node/bin/node"))
    check("the engine's target is the path the engine execs", ProrootExecProbe.TARGETS.last().commandLine, "/opt/node/bin/node --version")
    val execScript = ProrootExecProbe.guestCommand()
    check("the script runs the engine's own binary", execScript.contains("/opt/node/bin/node --version"), true)
    check("the script also runs a smaller dynamic binary", execScript.contains("/usr/bin/env true"), true)
    check("the script reports existence before running", execScript.contains(ProrootExecProbe.PHASE_EXISTS), true)
    check("the script reports the run's exit code and output", execScript.contains(ProrootExecProbe.PHASE_RUN), true)

    fun execLine(phase: String, exe: String, vararg rest: String): String =
        (listOf(ProrootExecProbe.MARKER, phase, exe) + rest).joinToString(ProrootExecProbe.SEPARATOR)

    val execPass = ProrootExecProbe.parse(
        execLine(ProrootExecProbe.PHASE_EXISTS, "/usr/bin/env", ProrootExecProbe.STATE_EXEC) + "\n" +
            // 真机形状：`env true` 退出 0 且**什么都不打印**（RUN 行没有输出字段）。
            execLine(ProrootExecProbe.PHASE_RUN, "/usr/bin/env", "0") + "\n" +
            execLine(ProrootExecProbe.PHASE_EXISTS, "/opt/node/bin/node", ProrootExecProbe.STATE_EXEC) + "\n" +
            execLine(ProrootExecProbe.PHASE_RUN, "/opt/node/bin/node", "0", "v24.19.0"),
    )
    check("a healthy run passes", execPass.ok, true)
    check("a passing run has one result per target", execPass.results.size, ProrootExecProbe.TARGETS.size)
    check("a pass never carries a reason", execPass.results.map { it.reason }, listOf(null, null))
    check("the pass names the engine's binary and its version", execPass.describe().any { it.contains("/opt/node/bin/node --version") && it.contains("v24.19.0") }, true)
    check("a passing line starts with the pass mark", execPass.describe().first().startsWith(ProrootExecProbe.PASS_MARK), true)

    // **门禁必须是可满足的** —— 这一组断言是用户真机报告换来的，也是这次修复的判据。
    //
    // `/usr/bin/env true` 的设计就是退出 0 且**什么都不打印**；旧规则把「退出码 0 但没有输出」
    // 一律当失败，于是这一档在真机上**永远过不去**。报告原文：
    // `实际生效: proot（探针未通过）(默认档 · 动态二进制: /usr/bin/env true: 退出码 0 但没有任何输出)`
    // —— 同一份报告里 raw syscall、`rg`、`fd` 全过，所以 proroot 永远选不上，表现为「开关没用」。
    //
    // 为什么这条缺陷能活到今天：**夹具本身说了假话**。上面那条 `execPass` 原先给静默的
    // `/usr/bin/env true` 编了一行输出 `true`，于是一个真机上不可能出现的形状一直绿着。
    // 现在 `execPass` 用的就是真机形状（`env true` 静默 + node 打印版本），下面这几条是它的
    // 判据：静默的目标必须通过，而**期望有输出**的那条静默下来必须失败。
    check("真机形状：静默的 /usr/bin/env true + 会打印版本的 node ⇒ 通过", execPass.ok, true)
    check("静默的目标没有 reason", execPass.results.first().reason, null)
    check("静默的成功不画一对空括号", execPass.describe().first().contains("（）"), false)
    check(
        "它说的是「本来就不输出」，不是「没有输出」",
        execPass.describe().first().contains("本来就不输出"),
        true,
    )
    check(
        "两个目标各自声明了「该不该有输出」",
        ProrootExecProbe.TARGETS.map { it.expectsOutput },
        listOf(false, true),
    )
    // 整道门禁必须**可满足**：三档都健康的真机形状（raw 有翻译、工具 ok、动态二进制用上面
    // 那份 `execPass`）⇒ 必须允许 proroot。旧规则下这一格永远是 `false`，那正是「开关拨开也
    // 没用、实际生效永远是 proot」的全部原因；这条断言把「可满足」钉在门禁这一层，而不是
    // 只钉在这一档。
    check(
        "三档都健康的真机形状让门禁可满足（⇒ 允许 proroot）",
        RuntimeChoice.probeGate(
            RuntimeChoice.PROROOT_SECCOMP,
            rawVetoed = false,
            rawTranslated = true,
            toolsOk = true,
            execOk = execPass.ok,
        ),
        true,
    )
    // 反例：**期望有输出**的那一条静默了 ⇒ 必须失败（node 的严格性一点没放松）
    val nodeSilent = ProrootExecProbe.parse(
        execLine(ProrootExecProbe.PHASE_EXISTS, "/usr/bin/env", ProrootExecProbe.STATE_EXEC) + "\n" +
            execLine(ProrootExecProbe.PHASE_RUN, "/usr/bin/env", "0") + "\n" +
            execLine(ProrootExecProbe.PHASE_EXISTS, "/opt/node/bin/node", ProrootExecProbe.STATE_EXEC) + "\n" +
            execLine(ProrootExecProbe.PHASE_RUN, "/opt/node/bin/node", "0"),
    )
    check("node --version 退出 0 却没输出 ⇒ 失败", nodeSilent.ok, false)
    check(
        "失败原因仍是「退出码 0 但没有任何输出」",
        nodeSilent.results.last().reason,
        "退出码 0 但没有任何输出",
    )

    // The device's shape: both binaries exist with +x, and the engine's own one still exits 126.
    val execBroken = ProrootExecProbe.parse(
        execLine(ProrootExecProbe.PHASE_EXISTS, "/usr/bin/env", ProrootExecProbe.STATE_EXEC) + "\n" +
            execLine(ProrootExecProbe.PHASE_RUN, "/usr/bin/env", "0") + "\n" +
            execLine(ProrootExecProbe.PHASE_EXISTS, "/opt/node/bin/node", ProrootExecProbe.STATE_EXEC) + "\n" +
            execLine(ProrootExecProbe.PHASE_RUN, "/opt/node/bin/node", "126", "Permission denied"),
    )
    check("a 126 on the engine's binary fails the stage", execBroken.ok, false)
    check("the failing line names the binary", execBroken.describe().any { it.startsWith(ProrootExecProbe.FAIL_MARK) && it.contains("/opt/node/bin/node") }, true)
    check("the failing line carries the exit code", execBroken.describe().any { it.contains("126") }, true)
    check("the failing line carries the binary's own words", execBroken.describe().any { it.contains("Permission denied") }, true)
    // The other two shapes, which have different fixes and must not look like one another.
    val execMissing = ProrootExecProbe.parse(execLine(ProrootExecProbe.PHASE_EXISTS, "/opt/node/bin/node", ProrootExecProbe.STATE_MISSING))
    check("a missing binary fails with its own reason", execMissing.results.last().reason?.contains("不存在") ?: false, true)
    val execNoX = ProrootExecProbe.parse(execLine(ProrootExecProbe.PHASE_EXISTS, "/opt/node/bin/node", ProrootExecProbe.STATE_NOEXEC))
    check("a binary without +x fails with its own reason", execNoX.results.last().reason?.contains("执行位") ?: false, true)
    // A run that never reached the guest is a failure, and it keeps the launcher's sentence.
    val execLauncherDied = ProrootExecProbe.parse("[proroot] child: stage=execve target=/opt/node/bin/node errno=13")
    check("a launcher death fails every target", execLauncherDied.ok, false)
    check("the launcher's sentence is kept as evidence", execLauncherDied.describe().any { it.contains("[proroot] child: stage=execve") }, true)
    check("no output at all is not a pass", ProrootExecProbe.parse("").ok, false)
    check("shell noise alone is not a pass", ProrootExecProbe.parse("warning: something\nUsage: x").ok, false)
    // A run the caller killed: no target has a result line, and the reason says *timeout*
    // rather than "the launcher never started" — the two have the same missing markers and
    // different fixes.
    val execTimeout = ProrootExecProbe.parse(
        ProrootRawProbe.TIMEOUT_MARKER + ProrootExecProbe.SEPARATOR + "proroot 探针 20 秒没有返回",
    )
    check("a timed-out run fails the stage", execTimeout.ok, false)
    check("a timed-out run is reported as a timeout", execTimeout.results.all { it.reason?.contains("超时") ?: false }, true)
    check("only [proroot] lines are quoted", ProrootExecProbe.launcherLinesFrom("secret=1\n[proroot] x\nPI-EXEC=1"), listOf("[proroot] x"))
    check("a hostile long launcher line is bounded", ProrootExecProbe.launcherLinesFrom("[proroot] " + "x".repeat(4000)).first().length, ProrootExecProbe.MAX_DETAIL_CHARS)

    // The launch-failure predicate: exactly the two codes that mean "the exec never happened".
    check("126 is a launch failure", ProrootExecProbe.isLaunchFailure(126), true)
    check("127 is a launch failure", ProrootExecProbe.isLaunchFailure(127), true)
    check("a real engine exit is not a launch failure", listOf(0, 1, 2, 134).map { ProrootExecProbe.isLaunchFailure(it) }, listOf(false, false, false, false))
    check("no exit code is not a launch failure", ProrootExecProbe.isLaunchFailure(null), false)

    // The autopsy: header + the stage's own lines + the launcher's words, and bounded.
    val autopsy = ProrootExecProbe.autopsyLines(
        exitCode = 126,
        report = execBroken,
        launcherLines = listOf("[proroot] child: stage=execve target=/opt/node/bin/node errno=13"),
    )
    check("the autopsy names the exit code", autopsy.first().contains("126"), true)
    check("the autopsy carries the binary", autopsy.any { it.contains("/opt/node/bin/node") }, true)
    check("the autopsy carries the launcher's line", autopsy.any { it.contains("[proroot] child: stage=execve") }, true)
    val boundedAutopsy = ProrootExecProbe.autopsyLines(126, execBroken, List(40) { "[proroot] line $it" }, limit = 4)
    check("the autopsy is bounded", boundedAutopsy.size, 4)
    check("and says how much it left out", boundedAutopsy.last().contains("还有"), true)
    val failedAutopsy = ProrootExecProbe.autopsyLines(126, null, emptyList(), probeNote = "IOException: x")
    check("an autopsy that could not run says so", failedAutopsy.any { it.contains("IOException") }, true)
    check("an autopsy that could not run still names the exit code", failedAutopsy.first().contains("126"), true)

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
    // The expectation comes from the same rule the builder uses (`canonicalHost`), not from a
    // literal: on a device `<files>` is spelled `/data/user/0/...` while the kernel spells it
    // `/data/data/...`, so a hand-written literal would pin the machine instead of the rule.
    // The alias section above is where the rule itself is pinned.
    check("the tmp bind is the app's own directory", GuestRecipe.tmpBind(p), listOf("-b", "${p.tmp.canonicalPath}:/tmp"))
    check("the proroot scratch is inside the volatile runtime tree", p.prorootTmp.path, "$FILES/pi/runtime/proroot-tmp")
    check("the probe cache is inside the volatile runtime tree", p.prorootProbeCache().path, "$FILES/pi/runtime/.proroot-probe")
    check("the CA bundle is the payload's path", GuestRecipe.GUEST_CA_BUNDLE, "/etc/ssl/certs/ca-certificates.crt")
    check("quoting survives an embedded single quote", ShellQuote.quote("a'b"), "'a'\\''b'")

    // ============================== 9. 开关关着 = 另一条线「一点活都没干」
    //
    // The user's sentence this section answers is not about the *decision*: it is
    // 「就算完全没用，也不影响另一个，一点不影响」. The decision half is pinned above
    // (`decide(enabled = false, …)` is `SwitchOff`), and that alone is not enough —
    // `RuntimeSelection.plan()`/`status()` could still have stat'ed the five proroot
    // components, read the cache and hashed the binaries *before* reaching the decision,
    // and every one of those results would be discarded. That would be work on every guest
    // start for a user who never enabled proroot, and no decision-level check can see it.
    //
    // Four layers are pinned here, and this is exactly how far a bare JVM can go:
    //
    //  1. **The decision cannot depend on any proroot fact while the switch is off.**
    //     The full cross-product of the other three inputs is enumerated: if the answer is
    //     `SwitchOff` for all 16, then nothing those inputs describe can be consulted on
    //     that path in the first place.
    //  2. **The side effects are guarded in the source.** `RuntimeSelection.kt` imports
    //     Android (`Context`, `Log`), so this harness cannot compile it and cannot call
    //     `plan()`/`status()`. What it can do — the way `shell-policy-mirror` reads the
    //     device guard — is read the file as text and require every expression that
    //     touches proroot to sit inside an `enabled` guard: the five stats
    //     (`missingProrootComponents()`), the cache read (`ProrootProbe.cached(`), the
    //     config sweep, the gate call, and the two fields the answers are carried in
    //     (`plan().probe`, `status().probePassed`). A future edit that hoists one of them
    //     out of its guard fails this harness instead of silently costing every user 5
    //     stats, a digest and a directory listing per launch.
    //  3. **The suppressed side effect is real** (part 4): a PASS verdict planted under the
    //     key the gate would compute is surfaced by the *production* reader
    //     (`ProrootProbe.cached`) — so "the switch off does not read the cache" has a
    //     visible consequence (`probePassed == null`, `probe == null`) rather than being an
    //     unfalsifiable sentence about a file nobody would have read anyway.
    //  4. **The component-less mirror** (part 5): with the five components absent the stat
    //     answer is five, the decision refuses with `RuntimeFilesMissing`, and the planted
    //     verdict is not rewritten by anything on that path.
    //
    // What this does **not** prove: that `plan()`/`status()` really return those two nulls
    // at runtime — they live in `RuntimeSelection`, which cannot be compiled here, so the
    // two fields are pinned only through their (single) assignment sites and the guards
    // around them. Proving it needs `RuntimeSelection` compiled with android.jar plus a
    // device; the layer is stated as a gap rather than papered over.
    //
    // (1) The decision ignores the other three facts entirely when the switch is off.
    val offDecisions = buildList {
        for (files in listOf(true, false)) {
            for (probe in listOf(true, false)) {
                for (fail in listOf(0, 1, 3, 99)) {
                    add(RuntimeChoice.decide(enabled = false, filesPresent = files, probePassed = probe, consecutiveFailures = fail))
                }
            }
        }
    }
    check("the switch off answers SwitchOff for every combination", offDecisions.size, 16)
    check(
        "…and none of the other three facts can change that answer",
        offDecisions.all { it == EngineDecision(GuestEngine.Proot, EngineFallback.SwitchOff) },
        true,
    )
    // The reverse half: components missing outranks a passing probe, so an enabled switch
    // on a device without the five files can never run the gate either.
    check(
        "a missing component outranks a passing gate",
        RuntimeChoice.decide(enabled = true, filesPresent = false, probePassed = true, consecutiveFailures = 0),
        EngineDecision(GuestEngine.Proot, EngineFallback.RuntimeFilesMissing),
    )

    // (2) On a tree with no proroot components, nothing proroot-shaped appears just from
    // asking the path questions — and the one accessor that *does* create the directory is
    // the one `plan()`/`sweepProrootConfigs` does not use when the switch is off.
    val offRoot = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-off-${System.nanoTime()}")
    val offPaths = PiPaths(
        filesDir = java.io.File(offRoot, "files").also { it.mkdirs() },
        nativeLibDir = java.io.File(offRoot, "lib").also { it.mkdirs() },
    )
    check("the fixture has no proroot component", offPaths.missingProrootComponents().size, 5)
    check("the non-creating scratch accessor creates no proroot-tmp", offPaths.prorootTmpDir().exists(), false)
    check("asking for the probe cache creates nothing", offPaths.prorootProbeCache().exists(), false)
    check("asking for the autopsy path creates nothing", offPaths.prorootEngineForensics().exists(), false)
    // The pair's contract, stated in `PiPaths.prorootTmp`: the sweep must be able to look
    // without creating, and only a launch creates. Both halves are asserted so a swap of
    // the two accessors cannot pass.
    check("the creating accessor does create it", offPaths.prorootTmp.isDirectory, true)
    check("…and the non-creating one still only looks", offPaths.prorootTmpDir().isDirectory, true)

    // (3) The source-level guards. Whitespace is normalised so this pins the *structure*
    // and not the formatting.
    val selectionSource = java.io.File(
        java.io.File(System.getProperty("pi.repo.root") ?: "."),
        "app/src/main/kotlin/app/pi/runtime/RuntimeSelection.kt",
    ).readText().replace(Regex("\\s+"), " ")
    val statsGuard = "val missing = if (enabled) paths.missingProrootComponents() else emptyList()"
    check("both entry points guard the five stats with `enabled`", selectionSource.split(statsGuard).size - 1, 2)
    check(
        "the five stats are called nowhere else",
        selectionSource.split("missingProrootComponents()").size - 1,
        2,
    )
    check(
        "the gate is called only behind `enabled && filesPresent && not exhausted`",
        selectionSource.contains("val probePassed = if (enabled && filesPresent && !RuntimeChoice.exhausted(failures)) { probe = gate(storage)"),
        true,
    )
    check(
        "the cache is read only behind `enabled && missing.isEmpty()`",
        selectionSource.contains("val cached = if (enabled && missing.isEmpty()) { runCatching { ProrootProbe.cached("),
        true,
    )
    check(
        "the config sweep runs only when the switch is on",
        selectionSource.split("if (enabled) sweepProrootConfigs()").size - 1,
        1,
    )
    check(
        "plan()'s probe field is filled from the gated call only",
        selectionSource.split("probe = probe,").size - 1,
        1,
    )
    check(
        "status()'s probePassed is filled from the guarded cache read only",
        selectionSource.split("probePassed = cached?.passed,").size - 1,
        1,
    )

    // (3b) 开关关着时 plan() 的探针字段与 status() 的 probePassed 各自**只有一个**赋值点，
    // 而 (3) 的守卫说明那个赋值点读的是 `enabled` 之内的值。把两个字段的可达值钉出来：
    // 关着 → `probe == null` / `probePassed == null`，不是 `false`（「没跑过」和「跑了没过」
    // 是两种读数，不能互相冒充，见 `ProrootProbeNarrative.detailLines` 的分支）。
    //
    // 这一段**执行不了**：`status()`/`plan()` 在 `RuntimeSelection` 里，而那个文件 import 了
    // Android（`Context`/`Log`/`Looper`），bare JVM 编译不了。所以这里只钉住赋值点的**唯一性**，
    // 运行期可达性由 (3) 的源码守卫与 (4) 的「种下的结论确实读得到」共同承担。

    // (4) 种下一份「通过」的探针结论，用来证明「没读到」不是一个空命题。
    //
    // A guard that suppresses a side effect is only meaningful while the side effect is
    // real. So this plants a passing verdict **in the file `status()` reads**, under the
    // exact key `gate()` would compute for this tree (revision + `ProrootProbe.digestOf`),
    // and first proves the production reader surfaces it: `ProrootProbe.cached` is the very
    // call `status()` makes when `enabled && missing.isEmpty()`. With the five components
    // present and the planted key matching, it reads back `true` — so an unguarded
    // `status()` would report `probePassed = true`, not `null`, and an unguarded `plan()`
    // would carry a verdict in `plan().probe`. That is what makes "the switch off does not
    // read it" an observable claim rather than a comment.
    val plantedRoot = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-planted-${System.nanoTime()}")
    val plantedPaths = PiPaths(
        filesDir = java.io.File(plantedRoot, "files").also { it.mkdirs() },
        nativeLibDir = java.io.File(plantedRoot, "lib").also { it.mkdirs() },
    )
    RuntimeChoice.REQUIRED_FILES.forEach { name ->
        java.io.File(plantedPaths.nativeLib, name).writeText("so-$name")
    }
    check("the planted fixture has every component", plantedPaths.missingProrootComponents(), emptyList<String>())
    val plantedRevision = "2026-09-19.1"
    val plantedDigest = ProrootProbe.digestOf(plantedPaths)
    check("the digest is a real reading, not the empty string", plantedDigest.length, 64)
    check(
        "the planted verdict lives in the file the retry path deletes",
        plantedPaths.prorootProbeCache().path,
        "${plantedPaths.runtime.path}/.proroot-probe",
    )
    plantedPaths.prorootProbeCache().parentFile?.mkdirs()
    plantedPaths.prorootProbeCache().writeText(
        ProrootProbeCache.render(
            ProrootProbe.key(plantedRevision, plantedDigest),
            passed = true,
            detail = listOf("✓ 种下的结论：全部通过"),
        ),
    )
    check(
        "a planted PASS is readable under the key the gate would ask for",
        ProrootProbe.cached(plantedPaths, plantedRevision, plantedDigest)?.passed,
        true,
    )
    check(
        "…and it is a cached verdict, not a fresh measurement",
        ProrootProbe.cached(plantedPaths, plantedRevision, plantedDigest)?.cached,
        true,
    )
    check(
        "a different revision does not see the planted verdict",
        ProrootProbe.cached(plantedPaths, "2026-01-01.0", plantedDigest),
        null,
    )
    // The read is a read: nothing in this path rewrote the file.
    check(
        "reading the planted verdict leaves it on disk",
        plantedPaths.prorootProbeCache().isFile,
        true,
    )
    plantedRoot.deleteRecursively()

    // (5) 镜像的那一半：开关**开着**、五个组件一个都不在。
    //
    // `RuntimeChoice.decide` 在门禁之前就拒（上面已钉），源码里门禁在
    // `enabled && filesPresent && !exhausted` 之内、缓存读取在 `enabled && missing.isEmpty()`
    // 之内 —— 所以「组件不在」这条路上探针不会跑，连种下的结论都不会被读。可读的那一半在
    // 这里执行：五条 stat 的答案是 5，决策是 `RuntimeFilesMissing`，种下的文件字节不变。
    val absentRoot = java.io.File(System.getProperty("java.io.tmpdir"), "pi-proroot-absent-${System.nanoTime()}")
    val absentPaths = PiPaths(
        filesDir = java.io.File(absentRoot, "files").also { it.mkdirs() },
        nativeLibDir = java.io.File(absentRoot, "lib").also { it.mkdirs() },
    )
    check("the mirror fixture is missing all five", absentPaths.missingProrootComponents().size, 5)
    val absentRevision = "2026-09-19.1"
    val absentDigest = ProrootProbe.digestOf(absentPaths)
    absentPaths.prorootProbeCache().parentFile?.mkdirs()
    absentPaths.prorootProbeCache().writeText(
        ProrootProbeCache.render(
            ProrootProbe.key(absentRevision, absentDigest),
            passed = true,
            detail = listOf("✓ 种下的结论：全部通过"),
        ),
    )
    val absentText = absentPaths.prorootProbeCache().readText()
    check(
        "the planted verdict is readable even with the components absent",
        ProrootProbe.cached(absentPaths, absentRevision, absentDigest)?.passed,
        true,
    )
    check(
        "an enabled switch on a component-less tree refuses before the gate",
        RuntimeChoice.decide(enabled = true, filesPresent = false, probePassed = true, consecutiveFailures = 0),
        EngineDecision(GuestEngine.Proot, EngineFallback.RuntimeFilesMissing),
    )
    check(
        "…and that path never rewrote the planted verdict",
        absentPaths.prorootProbeCache().readText(),
        absentText,
    )
    absentRoot.deleteRecursively()
    offRoot.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
