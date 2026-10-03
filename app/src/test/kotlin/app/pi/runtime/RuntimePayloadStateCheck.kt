package app.pi.runtime

// A bare-JVM harness for the three decisions that keep an update from deleting the user's
// files (`runtime/PayloadPrune.kt`, `runtime/VolatileTree.kt`, `runtime/RuntimeProvisioner.kt`'s
// decision shape), compiled and run by `tools/run-app-pure-checks.sh`.
//
// Why a harness and not a device: all three defects already shipped and all three are silent.
//
//  - The old provisioner compared one global digest and, on any difference,
//    `deleteRecursively()` the whole runtime tree — so a pi bump, a Node bump or a new
//    library in the git closure destroyed everything the user had installed inside the
//    guest. The replacement deletes exactly `oldList ∩ present − newList`, and the one
//    property that makes it safe (a file in neither list can never be in that set) is
//    arithmetic, not something a device can show you before it has already gone wrong.
//  - "The workspace root is not inside the volatile tree" used to be a comment. It is now
//    asserted at `PiPaths` construction and checked before every recursive delete; the
//    counterexamples below are the ones that must fail loudly.
//  - Whether any of that runs at all is a property of `RuntimeProvisioner`'s *shape*, which
//    a device cannot show either: the stamp fast path has to stay "one comparison and
//    nothing else", `wipe()` has to stay reachable only from the explicit repair flag, and
//    the no-per-payload-state transition has to extract over the tree without pruning. That
//    file cannot be compiled here (it imports `AssetManager`/`Dispatchers`), so its
//    structure is read as source text — the same technique `ProrootCheck.kt` uses for
//    `RuntimeSelection.kt`.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

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
    // ------------------------------------------------------------ the prune decision
    // A file the user created is in neither list. It must never appear in the delete set,
    // no matter what the two lists say.
    val old = listOf("rootfs/etc/a", "rootfs/usr/lib/old.so", "rootfs/opt/node/bin/node")
    val new = listOf("rootfs/etc/a", "rootfs/opt/node/bin/node", "rootfs/usr/lib/new.so")
    val present = setOf(
        "rootfs/etc/a",
        "rootfs/usr/lib/old.so",
        "rootfs/opt/node/bin/node",
        "rootfs/usr/lib/new.so",
        // Neither list: the user's own file, and a workspace-looking path.
        "rootfs/root/work/notes.md",
        "rootfs/opt/node/lib/node_modules/mine/index.js",
    )
    val victims = PayloadPrune.victims(old, new, present)
    check("A1 an old-only path is deleted", "rootfs/usr/lib/old.so" in victims, true)
    check("A2 a path in both lists is kept", "rootfs/etc/a" in victims, false)
    check("A3 a new-only path is not deleted", "rootfs/usr/lib/new.so" in victims, false)
    check(
        "A4 a file outside both lists is never deleted",
        victims.none { it == "rootfs/root/work/notes.md" || it == "rootfs/opt/node/lib/node_modules/mine/index.js" },
        true,
    )
    check("A5 the delete set is exactly the old-only present path", victims, listOf("rootfs/usr/lib/old.so"))

    // A path that only the old list names but that is no longer on disk is not reported:
    // there is nothing to delete, and a phantom entry would overstate what was pruned.
    check(
        "B1 an absent old-only path is not reported",
        PayloadPrune.victims(listOf("rootfs/gone"), emptyList(), emptySet()),
        emptyList<String>(),
    )

    // The lists are generated text. If one is corrupt, the decision must refuse to follow
    // it rather than resolve `..` or an absolute path against the runtime tree.
    val unsafe = listOf("../outside", "/etc/passwd", "./relative", "a/../../b", "", "a//b")
    check(
        "B2 unsafe list entries are never deleted",
        PayloadPrune.victims(unsafe, emptyList(), unsafe.toSet()),
        emptyList<String>(),
    )
    check("B3 an absolute path is unsafe", PayloadPrune.isSafePath("/etc/passwd"), false)
    check("B4 a parent component is unsafe", PayloadPrune.isSafePath("a/../b"), false)
    check("B5 a leading ./ is unsafe", PayloadPrune.isSafePath("./a"), false)
    check("B6 a normal relative path is safe", PayloadPrune.isSafePath("rootfs/usr/bin/git"), true)

    // Deepest first: a file's parent directory is only empty once the file is gone.
    check(
        "C1 deletion order is deepest first",
        PayloadPrune.victims(
            listOf("rootfs/opt/old/bin/tool", "rootfs/opt/old", "rootfs/opt/old/bin"),
            emptyList(),
            setOf("rootfs/opt/old/bin/tool", "rootfs/opt/old", "rootfs/opt/old/bin"),
        ),
        listOf("rootfs/opt/old/bin/tool", "rootfs/opt/old/bin", "rootfs/opt/old"),
    )
    check(
        "C2 duplicates collapse",
        PayloadPrune.victims(listOf("rootfs/a", "rootfs/a"), emptyList(), setOf("rootfs/a")),
        listOf("rootfs/a"),
    )

    // The list codec. Sorted and deduplicated, so two runs of the build produce the same
    // file and two devices' state files compare meaningfully; empty means empty.
    check("D1 encoding sorts and terminates with a newline", PayloadPrune.encodeList(listOf("b", "a")), "a\nb\n")
    check("D2 encoding deduplicates", PayloadPrune.encodeList(listOf("a", "a")), "a\n")
    check("D3 an empty list encodes to an empty string", PayloadPrune.encodeList(emptyList()), "")
    check("D4 encoding drops unsafe entries", PayloadPrune.encodeList(listOf("../x", "a")), "a\n")
    check("D5 parsing is the inverse of encoding", PayloadPrune.parseList("a\nb\n"), listOf("a", "b"))
    check("D6 parsing skips blank lines", PayloadPrune.parseList("a\n\n b \n"), listOf("a", "b"))

    // The second gate: a legal relative entry can still point outside the tree *now*,
    // because a directory on the way to it may have been replaced with a symlink after the
    // payload was extracted. Deletion resolves the canonical path first and asks this.
    check("H1 a path below the root is within", PayloadPrune.within("/a/b", "/a/b/c"), true)
    check("H2 the root itself is within", PayloadPrune.within("/a/b", "/a/b"), true)
    check("H3 a trailing separator on the root is fine", PayloadPrune.within("/a/b/", "/a/b/c"), true)
    check("H4 a sibling that merely shares the prefix is not within", PayloadPrune.within("/a/b", "/a/bc"), false)
    check("H5 a path above the root is not within", PayloadPrune.within("/a/b/c", "/a/b"), false)
    check("H6 an unrelated absolute path is not within", PayloadPrune.within("/a/b", "/etc/passwd"), false)

    // ------------------------------------------------- durable vs volatile, and wipe()
    // The layout the app actually builds: `<files>/pi/{workspaces,.pi/agent,persist}` are
    // durable, `<files>/pi/runtime` is volatile. `wipe()`'s delete set is inside
    // `paths.runtime` by construction, so these assertions are the proof that it cannot
    // contain a durable directory.
    val root = File(System.getProperty("java.io.tmpdir"), "pi-durable-check-${System.nanoTime()}")
    val filesDir = File(root, "files")
    val nativeLib = File(root, "lib")
    filesDir.mkdirs()
    nativeLib.mkdirs()
    val paths = PiPaths(filesDir = filesDir, nativeLibDir = nativeLib)

    // Both of the directories the app owns moved **inside** the rootfs on 2026-09-23
    // (`DurableLayout.AGENT_IN_ROOTFS` / `WORKSPACES_IN_ROOTFS`). That is the point of the
    // change: a rootfs path is an ordinary path, so proroot's bind-subtree failures cannot
    // reach pi's home or a workspace, which is what makes `git` work again. What keeps them
    // safe is `wipe()`'s move-out, not their location (W 段).
    check("E1 the workspace root is inside the rootfs", VolatileTree.contains(paths.rootfs, paths.workspaces), true)
    check("E2 the agent dir is inside the rootfs", VolatileTree.contains(paths.rootfs, paths.agentDir), true)
    check("E3 persist is outside the volatile tree", VolatileTree.contains(paths.runtime, paths.persist), false)
    check("E4 the layout reports no violation", DurableLayout.violations(paths.home, paths.runtime), emptyList<String>())
    // What `wipe()` is allowed to delete: the volatile root itself. Nothing else is ever
    // passed to a recursive delete in the provisioner.
    check("E5 the volatile root is deletable", VolatileTree.offending(paths.runtime, listOf(paths.runtime)), emptyList<File>())
    // A recursive delete pointed at a durable directory is refused for the ones that live
    // **outside** the volatile tree — `deleteTreeInsideVolatile` turns each offender into a
    // thrown ProvisioningException. Only `persist` is out there now; the two directories
    // inside the rootfs are protected by `DurablePreserve` instead.
    check(
        "E6 the one out-of-tree durable directory is refused as a delete target",
        VolatileTree.offending(paths.runtime, DurableLayout.durableDirs(paths.home, paths.runtime)),
        listOf(paths.persist),
    )
    check(
        "E7 neither rootfs directory is an offender — the rootfs is where they belong",
        VolatileTree.offending(paths.runtime, listOf(paths.agentDir, paths.workspaces)),
        emptyList<File>(),
    )
    check(
        "E8 the runtime's own children are legal targets",
        VolatileTree.offending(paths.runtime, listOf(File(paths.runtime, "node-stage"), File(paths.runtime, "lib"))),
        emptyList<File>(),
    )
    // A sibling with the runtime's prefix is not "inside" it.
    check(
        "E9 a path that merely shares the prefix is outside",
        VolatileTree.contains(paths.runtime, File(filesDir, "pi/runtime-old")),
        false,
    )
    check("E10 the volatile root contains itself", VolatileTree.relativePath(paths.runtime, paths.runtime), "")

    // The counterexample, and the asymmetry that *is* the rule: with `home` inside the
    // volatile tree, the one directory located relative to `home` becomes a violation —
    // and the two that are located relative to the rootfs do not. That is what lets durable
    // directories live in the rootfs without weakening this check.
    val movedHome = paths.runtime
    val violations = DurableLayout.violations(movedHome, paths.runtime)
    check("F1 a durable dir spelled under the volatile tree is a violation", violations.size, 1)
    check("F2 the violation names the directory", violations.all { it.contains("落在易失树") }, true)
    check("F3 the violation names the tree", violations.all { it.contains(paths.runtime.path) }, true)
    check(
        "F4 neither rootfs-resident directory is among them",
        violations.none { it.contains(paths.agentDir.path) || it.contains(paths.workspaces.path) },
        true,
    )

    // ------------------------------------------------- 显式「修复」那一刀的保护机制
    // `wipe()` 是唯一会整棵删 `<files>/pi/runtime` 的地方，只有 `rebuild = true` 能到。
    // 从 2026-09-23 起 pi 的 agent 目录**和工作区根都在** rootfs 里
    // （`DurableLayout.AGENT_IN_ROOTFS` / `WORKSPACES_IN_ROOTFS`），所以这一刀不再是空跑：
    // 每次「修复」都要真的把这两个目录搬出去再搬回来。只有 persist 在易失树之外。
    check(
        "W1 the rootfs-resident durable directories are the move list",
        DurableLayout.durableInsideRootfs(DurableLayout.durableDirs(paths.home, paths.runtime), paths.rootfs),
        listOf(paths.workspaces, paths.agentDir),
    )
    check(
        "W1b and the one out-of-tree durable directory is not in it",
        DurableLayout.durableInsideRootfs(listOf(paths.persist), paths.rootfs),
        emptyList<File>(),
    )
    val inRootfs = File(paths.rootfs, "somewhere-else")
    check(
        "W2 a durable directory inside the rootfs is picked up, one outside is not",
        DurableLayout.durableInsideRootfs(listOf(inRootfs, paths.persist), paths.rootfs),
        listOf(inRootfs),
    )
    check(
        "W3 the volatile tree itself is never one of them",
        DurableLayout.durableInsideRootfs(listOf(paths.runtime), paths.rootfs),
        emptyList<File>(),
    )

    // 搬走 → 删 → 搬回，在**真实目录**上跑一遍：设备上真正会发生的就是这三步。
    val preserveRoot = File(System.getProperty("java.io.tmpdir"), "pi-preserve-check-${System.nanoTime()}")
    val pRuntime = File(preserveRoot, "pi/runtime")
    val pRootfs = File(pRuntime, "rootfs")
    val pPreserve = File(preserveRoot, "pi/${DurablePreserve.PRESERVE_DIR}")
    val userWs = File(pRootfs, "workspace")
    userWs.mkdirs()
    File(userWs, "main.py").writeText("keep me")
    File(pRootfs, "usr").mkdirs()
    File(pRootfs, "usr/node").writeText("payload")

    val stash = DurablePreserve.moveOut(listOf(userWs), pPreserve)
    check("W4 the durable directory left the tree before the delete", userWs.exists(), false)
    check("W5 it is parked under the preserve directory", stash.entries.single().first.parentFile, pPreserve)
    check("W6 the preserve directory is outside the volatile tree", VolatileTree.contains(pRuntime, pPreserve), false)
    check("W7 its content is intact while parked", File(stash.entries.single().first, "main.py").readText(), "keep me")

    // 这一行就是 `wipe()` 的那一刀。
    pRuntime.deleteRecursively()
    check("W8 the payload really is gone", File(pRootfs, "usr/node").exists(), false)
    check("W9 the user's file survived the delete", File(stash.entries.single().first, "main.py").readText(), "keep me")

    check("W10 everything came back", DurablePreserve.restore(stash), emptyList<File>())
    check("W11 readable again at its original path", File(userWs, "main.py").readText(), "keep me")

    // 搬不动就**不许删**：落脚位置被一个普通文件占住时 `moveOut` 必须抛，而不是返回一个空
    // 清单、让调用方以为数据已经安全了就去删树。这条分支就是「宁可让重建失败」那句话。
    val blocked = File(preserveRoot, "blocked")
    blocked.writeText("not a directory")
    val refused = runCatching { DurablePreserve.moveOut(listOf(userWs), blocked) }.isFailure
    check("W12 a move-out that cannot happen refuses instead of reporting success", refused, true)
    check("W13 and the durable directory is untouched by the refusal", File(userWs, "main.py").readText(), "keep me")
    preserveRoot.deleteRecursively()

    // One spelling of the workspace root, on both sides of the boundary: the guest relative
    // path is what remains of `WORKSPACES_IN_ROOTFS` after the guest's `/workspace` base,
    // and `PiPaths.workspaces` is built from that same pair.
    check(
        "G1 the workspace's two spellings agree",
        GuestWorkspacePath.ROOT_RELATIVE,
        DurableLayout.WORKSPACES_IN_ROOTFS.removePrefix(DurableLayout.WORKSPACE_BASE_IN_ROOTFS + "/"),
    )
    check(
        "G2 workspaces sits on the guest's /workspace base",
        paths.workspaces,
        File(paths.workspaceBase, GuestWorkspacePath.ROOT_RELATIVE),
    )

    // ------------------------------------------- the transition, as arithmetic
    // "Every existing install extracts over the tree and deletes nothing" is not a promise
    // here, it is what the set difference answers when there is no old list: with nothing
    // recorded, there is nothing to compare against, so the delete set is empty whatever
    // the tree holds. The other direction — an *empty new* list — is the dangerous one
    // (`owned` empty ⇒ every old path looks old-only), which is why `prunePayload` refuses
    // it; both halves are pinned so a future "simplification" of that guard fails here.
    check(
        "H7 with no old list the delete set is empty (the transition)",
        PayloadPrune.victims(emptyList(), new, present),
        emptyList<String>(),
    )
    check(
        "H8 with an empty new list everything old-and-present would look deletable",
        PayloadPrune.victims(old, emptyList(), present).isNotEmpty(),
        true,
    )

    // --------------------- what a boot actually does, read off the provisioner's shape
    //
    // The prune's arithmetic is pinned above, and the boundary is pinned below. What neither
    // can show — and what a bare JVM cannot execute either, because `RuntimeProvisioner`
    // imports `AssetManager`, `Dispatchers.IO` and the coroutines plugin's suspend/withContext
    // machinery — is the *shape of the decision* that decides whether any of it runs. Every
    // claim in the class KDoc is a claim about that shape, and every way it can break is
    // silent on a device:
    //
    //   ① the fast path: with the packaged revision equal to `.stamp`, nothing beyond the
    //      16-character comparison is read — no digest, no list, no payload-state directory,
    //      and no `mkdirs` the old early return did not already perform (it still does its
    //      two idempotent repairs, exactly as before);
    //   ② only `rebuild = true` reaches `wipe()`, and the whole file contains exactly one
    //      recursive delete;
    //   ③ the transition (a device with no per-payload state) is planned as "every payload",
    //      extracts over the tree, and prunes nothing because the old list is missing.
    //
    // So the file is read as source text with whitespace normalised, exactly the way
    // `ProrootCheck.kt` reads `RuntimeSelection.kt` and `image-size` reads the composable
    // call sites: `pi.repo.root` is handed to every harness for this. That pins the
    // *structure*, not the formatting, and it is the strongest statement available without
    // an Android unit-test runtime.
    val provisionerSource = File(
        File(System.getProperty("pi.repo.root") ?: "."),
        "app/src/main/kotlin/app/pi/runtime/RuntimeProvisioner.kt",
    ).readText().replace(Regex("\\s+"), " ")

    // `wipe()` 的**顺序**是行为，不是排版：搬走必须在删之前，搬回必须在删之后，搬不动必须
    // 是抛异常而不是继续删。上面的 W4–W13 直接驱动 `DurablePreserve`，但没有任何东西能证明
    // `wipe()` 真的按这个顺序调它 —— 所以这里把 `wipe()` 的函数体当源码文本读。
    val wipeBody = provisionerSource
        .substringAfter("private fun wipe() {")
        .substringBefore("private fun deleteTreeInsideVolatile(")
    val moveOutAt = wipeBody.indexOf("DurablePreserve.moveOut(")
    val deleteAt = wipeBody.indexOf("deleteTreeInsideVolatile(")
    check("W14 wipe moves the durable directories out before it deletes", moveOutAt >= 0 && moveOutAt < deleteAt, true)
    check("W15 and restores them afterwards", wipeBody.contains("DurablePreserve.restore(stash)"), true)
    check("W16 a refusal becomes a ProvisioningException, not a delete", wipeBody.contains("throw ProvisioningException("), true)
    check(
        "W17 the skeleton is recreated only after a successful delete",
        wipeBody.contains("if (deleted) prepareVolatileDirs()"),
        true,
    )
    check(
        "W18 a stranded directory keeps the preserve copy",
        wipeBody.contains("if (stranded.isEmpty()) runCatching { preserve.delete() }"),
        true,
    )
    // 清理落脚目录**不能**是第二次递归删除：这是 P7 那条「全文件只有一处递归删除」守卫
    // 的另一半，写在这里是因为它和 W14–W18 是同一段代码。
    check(
        "W19 the preserve cleanup is a plain delete, not a recursive one",
        wipeBody.contains("preserve.deleteRecursively()"),
        false,
    )

    // ① The fast path is one comparison and one early return.
    val fastPath = "if (!rebuild && paths.rootfs.isDirectory && isStampCurrent(revision)) " +
        "{ return finishCurrent(revision, migration = false, onStep) }"
    check("P1 the fast path is one guarded early return", provisionerSource.split(fastPath).size - 1, 1)
    check(
        "P2 the comparison is the stamp file against the packaged revision",
        provisionerSource.contains("return stamp.isFile && stamp.readText().trim() == revision"),
        true,
    )
    val slowPathReads = listOf(
        "val migration = !paths.payloadStateDir().exists()",
        "val stateDigests = PAYLOADS.associateWith { readStateDigest(it) }",
        "val packagedDigests = PAYLOADS.associateWith { packagedDigest(it) }",
    )
    check(
        "P3 the early return precedes every per-payload read",
        slowPathReads.all { provisionerSource.indexOf(fastPath) < provisionerSource.indexOf(it) },
        true,
    )
    // …and the work the fast path *does* do reads none of it either: the two idempotent
    // repairs in `finishCurrent` (`ensureToolsVisible`/`ensureAndroidGroups`) and the stamp
    // write. This is the "no digest, no list, no traversal" sentence, as a slice.
    val finishCurrentBody = provisionerSource
        .substringAfter("private fun finishCurrent(revision: String, migration: Boolean, onStep: (Step) -> Unit): ProvisionOutcome {")
        .substringBefore("private fun prepareVolatileDirs() {")
    check(
        "P4 the fast path's own work reads no payload state",
        listOf("readStateDigest", "readStateList", "packagedDigest", "payloadStateDir").none { it in finishCurrentBody },
        true,
    )
    // P4b: …and it reports a step **only when it has a warning to show**. A step *is* a
    // full-screen page — `ChatScreen` draws `BootScreen` for `Boot.Working` and for nothing
    // else — so an unconditional one here put that page over a chat page that was already
    // usable, for as long as these probes take, on **every** launch that changed nothing:
    // the 「一秒的启动动画，每次进来一闪而过」 the user reported. Both halves are pinned,
    // because only the pair rules out the old shape.
    check(
        "P4b a launch with nothing to unpack reports no step",
        finishCurrentBody.contains("firstOrNull()?.let { onStep(") &&
            !finishCurrentBody.contains("onStep(Step(steps"),
        true,
    )

    // ② The repair path is the only deleter, and it is entered only explicitly.
    check("P5 wipe() is declared exactly once", provisionerSource.split("private fun wipe() {").size - 1, 1)
    check(
        "P6 the only call to wipe() is the explicit-rebuild branch",
        provisionerSource.split("if (rebuild) { next(); wipe(); index++ }").size - 1,
        1,
    )
    check(
        "P7 the file contains exactly one recursive delete",
        provisionerSource.split(".deleteRecursively()").size - 1,
        1,
    )
    check(
        "P8 and that one is the volatile-root guard's own call",
        provisionerSource.contains("target.deleteRecursively()"),
        true,
    )
    // `rebuild` is a parameter a caller has to pass, never a value derived here from a
    // digest comparison — which is the sentence "the revision changing never wipes".
    check(
        "P9 rebuild is an explicit parameter defaulting to false",
        provisionerSource.split("rebuild: Boolean = false").size - 1,
        1,
    )
    check(
        "P10 nothing in the file constructs a rebuild decision from the stamp",
        provisionerSource.split("if (!rebuild &&").size - 1,
        1,
    )

    // ③ The transition: no state ⇒ every payload planned, nothing pruned.
    check(
        "P11 the migration condition is 'no per-payload state at all'",
        provisionerSource.split("val migration = !paths.payloadStateDir().exists()").size - 1,
        1,
    )
    check("P12 a migration plans every payload", provisionerSource.split("migration -> PAYLOADS").size - 1, 1)
    check("P13 so does a missing rootfs", provisionerSource.split("rootfsMissing -> PAYLOADS").size - 1, 1)
    check(
        "P14 a prune needs both lists, and returns without them",
        provisionerSource.contains("val old = readStateList(payload) ?: return") &&
            provisionerSource.contains("val new = newList ?: return"),
        true,
    )
    check(
        "P15 an empty packaged list is 'cannot tell', not 'prune everything'",
        provisionerSource.split("if (new.isEmpty()) return").size - 1,
        1,
    )
    check(
        "P16 every delete goes through the pinned set difference",
        provisionerSource.split("PayloadPrune.victims(").size - 1,
        1,
    )
    check(
        "P17 extract-over precedes the prune, which precedes the state write",
        provisionerSource.indexOf("extractPayloadOver(payload)") <
            provisionerSource.indexOf("prunePayload(payload, newList)") &&
            provisionerSource.indexOf("prunePayload(payload, newList)") <
            provisionerSource.indexOf("recordPayloadState(payload, packagedDigests[payload], newList)"),
        true,
    )
    check(
        "P18 the transition's own step says it deletes nothing",
        provisionerSource.contains("迁移到按载荷更新（只覆盖，不删除）"),
        true,
    )

    // The build half of the same property: a `.list` holds files and symlinks only, because
    // a directory entry in a list is what would make a prune reach a subtree the user has put
    // files into. `payloadPaths` drops `tar`'s directory rows (a trailing `/`) and sorts, and
    // `PayloadPrune.encodeList`/`isSafePath` reject what the build must never emit.
    val fetchSource = File(
        File(System.getProperty("pi.repo.root") ?: "."),
        "tools/fetch-runtime.mjs",
    ).readText().replace(Regex("\\s+"), " ")
    check(
        "P19 the build drops directory entries from every list",
        fetchSource.split("if (name.length === 0 || name.endsWith(\"/\")) continue;").size - 1,
        1,
    )
    check("P20 the build emits the list in sorted order", fetchSource.contains("return [...out].sort();"), true)

    // -------------------------------------------- 事后收尾的失败：不许静默、不许记账
    //
    // 这一批修的是同一个形状：**某一步失败了，而下一次启动走的是"不用再做"的那条路**，
    // 所以失败不报出来就永远没人重试。四条：
    //
    //  - P0 客体必要配置没写成，载荷却全部记账 ⇒ 下次 `plan` 为空 ⇒ `finishCurrent`
    //    （以前从不写这些文件）⇒ 写 stamp 宣告成功、页面消失，`/etc/resolv.conf` 永久缺失；
    //  - P1a `deleteRecursively` 没删净却继续 ⇒ 「重建运行时」报成功、下一次仍撞在同一个节点；
    //  - P1b 搬回失败/被强杀 ⇒ 用户的目录留在 `.preserve`，全仓没有第二个地方看它；
    //  - 合并 node 时写失败被吞 ⇒ 照样记账 ⇒ 半份树被当成最新，且没有任何页面。
    //
    // P0 的两条性质：**一个写入口**（因此没有第二份"忘了加判断"的逻辑），而且它判的是
    // 存在性、不是内容（"文件都在时写入次数为 0"的等价可观察量）。
    val ensureConfigBody = provisionerSource
        .substringAfter("private fun ensureGuestConfig(): String? {")
        .substringBefore("private fun configureGuestFiles() {")
    check(
        "P21 the guest config has exactly one guarded writer",
        ensureConfigBody.contains("if (paths.missingGuestConfig().isNotEmpty()) configureGuestFiles()"),
        true,
    )
    check(
        "P22 the guard is an existence check, not a read or a write",
        ensureConfigBody.contains("missingGuestConfig()") && !ensureConfigBody.contains("readText"),
        true,
    )
    check(
        "P23 and the writer has no other caller",
        provisionerSource.split("configureGuestFiles()").size - 1,
        2, // 定义处 + ensureGuestConfig 里那一次调用
    )
    check(
        "P24 the fast path repairs the guest config too",
        finishCurrentBody.contains("ensureGuestConfig()"),
        true,
    )
    val provisionBody = provisionerSource
        .substringAfter("private fun provision(revision: String, rebuild: Boolean, onStep: (Step) -> Unit): ProvisionOutcome {")
        .substringBefore("private fun finishCurrent(")
    check(
        "P25 so does the extraction path, through the same function",
        provisionBody.contains("ensureGuestConfig()"),
        true,
    )

    // P1a：删完必须**验证真的没了**，没删净就是失败。文案要点出还剩哪些路径。
    val deleteBody = provisionerSource
        .substringAfter("private fun deleteTreeInsideVolatile(target: File, why: String) {")
        .substringBefore("private fun extractPayloadOver(")
    check(
        "P26 a recursive delete that did not finish throws instead of reporting success",
        deleteBody.contains("target.deleteRecursively()") &&
            deleteBody.contains("if (target.exists()) {") &&
            deleteBody.contains("没能删净"),
        true,
    )
    check(
        "P26b and it names what is left",
        deleteBody.contains("walkTopDown()"),
        true,
    )

    // P1b：搁浅的耐久目录在**两条路**上都会被捡回来；搬不回就失败（所以 stamp 不写、下次还来）。
    check(
        "P27 a stranded durable directory is recovered through DurablePreserve.recover",
        provisionerSource.contains("DurablePreserve.recover("),
        true,
    )
    check(
        "P27b from both provisioning paths",
        finishCurrentBody.contains("recoverStrandedDurableDirs()") &&
            provisionBody.contains("recoverStrandedDurableDirs()"),
        true,
    )
    check(
        "P27c and a rebuild that cannot put them back fails instead of reporting success",
        wipeBody.contains("重建运行时没能把耐久目录搬回原位"),
        true,
    )

    // 合并 node 时的写失败：收集 → 抛 → 因此 digest 不会被写（失败 ⇒ 不许记账）。
    val mergeBody = provisionerSource
        .substringAfter("private fun mergeTreeOver(from: File, to: File): List<String> {")
        .substringBefore("private fun installTool(")
    check(
        "P28 mergeTreeOver names every path it could not merge",
        mergeBody.contains("failed += child.path") && mergeBody.contains("failed += source.path"),
        true,
    )
    check(
        "P29 and extractNode refuses to finish while any of them is outstanding",
        provisionerSource
            .substringAfter("private fun extractNode() {")
            .substringBefore("private fun mergeTreeOver(")
            .contains("if (notMerged.isNotEmpty())"),
        true,
    )

    // prune 与记账的失败也被报出来（这两条以前是纯 `runCatching`/忽略返回值）。
    check(
        "P30 a prune that could not delete is reported",
        provisionerSource.contains("payloadWarnings += \"\$relative（删除失败）\""),
        true,
    )
    check(
        "P31 a payload state that was not written is reported",
        provisionerSource.contains("payloadWarnings += recordWarning"),
        true,
    )
    check(
        "P32 and every warning reaches the one step label",
        provisionBody.contains("recoveryWarning,") &&
            provisionBody.contains("payloadWarning(),") &&
            provisionBody.contains(".joinToString(\"\\n\")"),
        true,
    )

    // ------------------------------------------------ extraction over a changed tree
    //
    // An update does **not** start from an empty tree. The changed payload is unpacked onto
    // the tree the previous one left, and the old-only files are pruned only afterwards
    // (`RuntimeProvisioner`'s `extract → prune → record`). So the extractor itself has to
    // survive a path whose *kind* changed, or the unpack dies with a bare `ENOENT` against a
    // file whose parent "should" be there.
    //
    // That is the device report this section exists for — every overwrite install ended on:
    //
    //     failed to unpack pi-engine.tgz: <…>/dist/bundle/cli.js: open failed: ENOENT
    //
    // where the real story was that `dist/bundle` could not be created because something that
    // is not a directory already sat there. Retrying could not help (the prune that would have
    // removed it runs *after* a successful extraction), so the only way out was「重建运行时」
    // — wiping the guest and everything the user had installed in it. Replaying the same two
    // payloads onto a *clean* tree writes all 14941 files without one failure, so the archive
    // was never at fault.
    //
    // All five states below are ones a real tree can be in, and all five are silent without
    // the repair: `mkdirs()` returns `false` and the write answers `ENOENT`/`EISDIR`.
    class TarEntry(val name: String, val type: Char, val content: ByteArray = ByteArray(0))

    fun tarHeader(name: String, type: Char, size: Long): ByteArray {
        val header = ByteArray(512)
        fun put(offset: Int, text: String, length: Int) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            bytes.copyInto(header, offset, 0, minOf(bytes.size, length))
        }
        fun octal(offset: Int, value: Long, length: Int) =
            put(offset, value.toString(8).padStart(length - 1, '0'), length - 1)
        put(0, name, 100)
        octal(100, 0b110_100_100L, 8) // 0644
        octal(108, 0L, 8)
        octal(116, 0L, 8)
        octal(124, size, 12)
        octal(136, 0L, 12)
        put(148, "        ", 8) // checksum: `TarExtractor` does not verify it, by design
        header[156] = type.code.toByte()
        return header
    }

    fun tarGz(vararg entries: TarEntry): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            for (entry in entries) {
                gz.write(tarHeader(entry.name, entry.type, entry.content.size.toLong()))
                gz.write(entry.content)
                gz.write(ByteArray((512 - entry.content.size % 512) % 512))
            }
            gz.write(ByteArray(1024)) // end-of-archive marker: two zero blocks
        }
        return out.toByteArray()
    }

    fun extractOver(dest: File, vararg entries: TarEntry): Throwable? = runCatching {
        TarExtractor(dest).extractGzip(ByteArrayInputStream(tarGz(*entries)))
    }.exceptionOrNull()

    val extractRoot = File(root, "extract").also { it.mkdirs() }

    // E1: a regular file where the new payload wants a directory. This is the device's shape:
    // `mkdirs()` answers false, the write answers ENOENT, and every retry repeats it.
    val e1 = File(extractRoot, "e1").also { it.mkdirs() }
    File(e1, "a").writeText("stale file")
    val e1Threw = extractOver(e1, TarEntry("a/", '5'), TarEntry("a/f.txt", '0', "hello".toByteArray()))
    check("E1 a stale file where a directory belongs: no failure", e1Threw, null)
    check("E1 …the file inside it landed", File(e1, "a/f.txt").readText(), "hello")

    // E2: an empty directory where the new payload wants a file (`EISDIR` without the repair).
    val e2 = File(extractRoot, "e2").also { it.mkdirs() }
    File(e2, "b").mkdirs()
    val e2Threw = extractOver(e2, TarEntry("b", '0', "now a file".toByteArray()))
    check("E2 an empty directory where a file belongs: no failure", e2Threw, null)
    check("E2 …and it is a file now", File(e2, "b").readText(), "now a file")

    // E3: a **dangling symlink** where the new payload wants a file. `outputStream()` follows
    // it, so this is the one that produces the device's exact `ENOENT` wording.
    val e3 = File(extractRoot, "e3").also { it.mkdirs() }
    Files.createSymbolicLink(File(e3, "c").toPath(), File("gone.txt").toPath())
    val e3Threw = extractOver(e3, TarEntry("c", '0', "real".toByteArray()))
    check("E3 a dangling symlink where a file belongs: no failure", e3Threw, null)
    check("E3 …and the link was replaced, not followed", Files.isSymbolicLink(File(e3, "c").toPath()), false)
    check("E3 …with the payload's bytes", File(e3, "c").readText(), "real")

    // E4: a broken **ancestor**. Clearing only the immediate parent is not enough — every
    // component between the destination and the file has to be walked.
    val e4 = File(extractRoot, "e4").also { it.mkdirs() }
    File(e4, "deep").writeText("stale file")
    val e4Threw = extractOver(e4, TarEntry("deep/er/f.txt", '0', "deep".toByteArray()))
    check("E4 a broken ancestor: no failure", e4Threw, null)
    check("E4 …the whole chain was rebuilt", File(e4, "deep/er/f.txt").readText(), "deep")

    // E5: a **non-empty** directory in the way is refused, never deleted. The payload lists own
    // no directories (`PayloadPrune`), so whatever is inside one is the user's — and the
    // failure has to name the node, because "a path plus ENOENT" cannot be told apart from
    // "the archive lacks this file", which is what cost the device round trip.
    val e5 = File(extractRoot, "e5").also { it.mkdirs() }
    File(e5, "d").mkdirs()
    File(e5, "d/user.txt").writeText("mine")
    val e5Message = extractOver(e5, TarEntry("d", '0', "payload".toByteArray()))?.message ?: ""
    check("E5 a non-empty directory is not deleted", File(e5, "d/user.txt").readText(), "mine")
    check("E5 …and the failure names it as a directory", e5Message.contains("目录"), true)

    // ------------------------------------------------- 客体必要配置："缺了什么"
    //
    // P0 修的是"载荷全部记账了、配置文件却没写成功"那条路：下一次启动 `plan` 为空，走
    // `finishCurrent`，而它以前从不写这些文件 —— 于是 stamp 写成成功、失败页消失，
    // `/etc/resolv.conf` 永久缺失（guest 里所有域名解析失败，App 看起来是健康的）。
    //
    // 判据必须幂等且便宜：都在时**一个字节都不写**，只做四次存在性判断。这一节用真实目录跑
    // 它的每一种"缺"：整棵树是新的 / 一个文件是零字节 / 该是目录的位置是文件 / 只少一个。
    val cfgRoot = File(System.getProperty("java.io.tmpdir"), "pi-guest-config-check-${System.nanoTime()}")
    val cfgPaths = PiPaths(filesDir = File(cfgRoot, "files"), nativeLibDir = File(cfgRoot, "native"))
    check(
        "M1 a tree with no guest config names all four targets",
        cfgPaths.missingGuestConfig().map { it.relative },
        cfgPaths.guestConfigTargets().map { it.relative },
    )
    check("M1b and there are four of them", cfgPaths.guestConfigTargets().size, 4)

    cfgPaths.rootfs.mkdirs()
    cfgPaths.guestConfigTargets().forEach { target ->
        val file = File(cfgPaths.rootfs, target.relative)
        if (target.directory) file.mkdirs() else file.also { it.parentFile?.mkdirs() }.writeText("x\n")
    }
    check("M2 everything in place asks for nothing", cfgPaths.missingGuestConfig(), emptyList<PiPaths.GuestConfigTarget>())

    // 零字节 == 没写成功：截断的 stamp 读作"没解包"，截断的 resolv.conf 读作"没有 DNS"。
    File(cfgPaths.rootfs, "etc/hosts").writeText("")
    check(
        "M3 an empty file counts as missing",
        cfgPaths.missingGuestConfig().map { it.relative },
        listOf("etc/hosts"),
    )

    // 该是目录的位置被一个普通文件占住，也算缺 —— 只判 `exists()` 会漏掉这一种。
    File(cfgPaths.rootfs, "etc/hosts").writeText("x\n")
    cfgPaths.agentDir.deleteRecursively()
    cfgPaths.agentDir.parentFile?.mkdirs()
    cfgPaths.agentDir.writeText("not a directory")
    check(
        "M4 a file where a directory belongs counts as missing",
        cfgPaths.missingGuestConfig().map { it.relative },
        listOf(DurableLayout.AGENT_IN_ROOTFS),
    )
    cfgRoot.deleteRecursively()

    // ------------------------------------------------- 「搁浅的耐久目录」的启动恢复
    //
    // `wipe()` 搬出 → 删树 → 搬回，中间被强杀（或搬回失败）就把用户的会话/工作区留在
    // `.preserve` 里，而在这之前**全仓没有第二个地方看那个目录**。`recover` 就是那一眼：
    // 原位置不在 → 搬回；原位置是空目录 → 搬回；两边都有内容 → 一个字节都不动并报出来。
    val recRoot = File(System.getProperty("java.io.tmpdir"), "pi-recover-check-${System.nanoTime()}")
    val recHome = File(recRoot, "pi")
    val recRuntime = File(recHome, "runtime")
    val recPreserve = File(recHome, DurablePreserve.PRESERVE_DIR)
    val recDurable = DurableLayout.durableDirs(recHome, recRuntime)
    val recWs = recDurable[0]
    val recAgent = recDurable[1]

    check("R1 no stash means nothing to recover", DurablePreserve.recover(recDurable, recPreserve).anything, false)
    check("R1b and it does not create the preserve directory", recPreserve.exists(), false)

    // R2：原位置不存在 → 搬回，内容完整，壳被清掉。
    recPreserve.mkdirs()
    recWs.mkdirs()
    File(recWs, "notes.md").writeText("keep me")
    val stashWs = File(recPreserve, "workspaces-${System.nanoTime()}")
    check("R2 the stash is where an interrupted wipe would leave it", recWs.renameTo(stashWs), true)
    val r2 = DurablePreserve.recover(recDurable, recPreserve)
    check("R2b a missing target is restored", r2.restored, listOf(recWs))
    check("R2c with its content", File(recWs, "notes.md").readText(), "keep me")
    check("R2d and nothing is stranded", r2.stranded, emptyList<Pair<File, File>>())
    check("R2e the emptied shell is cleaned up", recPreserve.exists(), false)
    check("R3 a second call is a no-op", DurablePreserve.recover(recDurable, recPreserve).anything, false)

    // R4：新树已经建了一个**空**目录 —— 不能因为它存在就把数据丢在 .preserve 里。
    recPreserve.mkdirs()
    recAgent.mkdirs()
    File(recAgent, "auth.json").writeText("sk-secret")
    val stashAgent = File(recPreserve, "agent-${System.nanoTime()}")
    check("R4 the credential is parked", recAgent.renameTo(stashAgent), true)
    recAgent.mkdirs()
    check("R4b the fresh target really is empty", recAgent.listFiles()?.isEmpty(), true)
    val r4 = DurablePreserve.recover(recDurable, recPreserve)
    check("R4c an empty target does not block the restore", r4.restored, listOf(recAgent))
    check("R4d the parked credential is back", File(recAgent, "auth.json").readText(), "sk-secret")

    // R5：两边都有内容 → 保守。既不恢复、也不删除、也不隐藏。
    // R4 成功之后 `.preserve` 被清掉了（那是它该有的样子），所以这里重新造一个壳。
    recPreserve.mkdirs()
    val stashAgent2 = File(recPreserve, "agent-${System.nanoTime()}")
    check("R5 the parked copy moves aside", recAgent.renameTo(stashAgent2), true)
    recAgent.mkdirs()
    File(recAgent, "settings.json").writeText("new")
    val r5 = DurablePreserve.recover(recDurable, recPreserve)
    check("R5b nothing is restored when both sides have content", r5.restored, emptyList<File>())
    check("R5c and it is reported instead of hidden", r5.stranded.map { it.second }, listOf(recAgent))
    check("R5d the new content is untouched", File(recAgent, "settings.json").readText(), "new")
    check("R5e the parked copy is untouched too", File(stashAgent2, "auth.json").readText(), "sk-secret")
    check("R5f and the shell is kept because something is still parked", recPreserve.isDirectory, true)

    // R6：不认识的条目（不是我们放的目录名）既不被认领、也不被删掉 —— 清壳只清空目录。
    val junk = File(recPreserve, "junk-1").also { it.mkdirs() }
    check(
        "R6 an unrecognised entry is not claimed",
        DurablePreserve.recoveryTargets(recPreserve, recDurable).none { it.first == junk },
        true,
    )
    val prefixProbe = File(recRoot, "prefix-probe").also { it.mkdirs() }
    File(prefixProbe, "workspacesX-1").mkdirs()
    File(prefixProbe, "workspaces-1").mkdirs()
    File(prefixProbe, "junk-1").mkdirs()
    check(
        "R6b the name prefix must include the separator",
        DurablePreserve.recoveryTargets(prefixProbe, recDurable).map { it.first.name },
        listOf("workspaces-1"),
    )
    recRoot.deleteRecursively()

    // ------------------------------------------------- 一个目录的两个 guest 拼法
    //
    // `GuestWorkspacePath` 说明同一个 host 目录在 guest 里有两个规范名字：引擎的 cwd 是
    // `/workspace/pi/workspaces/<name>`，终端把它挂在一层之上、就叫 `/workspace`。转录里的
    // 路径两种都会出现，只认其中一种就是设备上那条「写完没被删，点开说被删了」——文件确实
    // 在 `<工作区>/pyjhora/…`，界面去找 `<工作区>/workspace/pyjhora/…`，然后说它不在。
    val engineGuest = "/workspace/pi/workspaces/workspace-1"
    check(
        "W20 引擎拼法折成工作区相对路径",
        GuestWorkspacePath.relativeToWorkspace("$engineGuest/pyjhora/x.md", engineGuest),
        "pyjhora/x.md",
    )
    check(
        "W21 终端拼法折成同一个答案（这条就是设备上那个 bug）",
        GuestWorkspacePath.relativeToWorkspace("/workspace/pyjhora/x.md", engineGuest),
        "pyjhora/x.md",
    )
    check(
        "W22 已经是相对路径时原样返回",
        GuestWorkspacePath.relativeToWorkspace("pyjhora/x.md", engineGuest),
        "pyjhora/x.md",
    )
    check(
        "W23 两种拼法都不像时只去前导斜杠（调用方还要再试 guest 根那一支）",
        GuestWorkspacePath.relativeToWorkspace("/root/.pi/agent/auth.json", engineGuest),
        "root/.pi/agent/auth.json",
    )
    check(
        "W24 引擎拼法与 under() 互为逆运算",
        GuestWorkspacePath.relativeToWorkspace(
            GuestWorkspacePath.under("/base", "/base/pi/workspaces/workspace-1"),
            GuestWorkspacePath.under("/base", "/base/pi/workspaces/workspace-1"),
        ),
        "",
    )

    root.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
