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

import java.io.File

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

    check("E1 the workspace root is outside the volatile tree", VolatileTree.contains(paths.runtime, paths.workspaces), false)
    check("E2 the agent dir is outside the volatile tree", VolatileTree.contains(paths.runtime, paths.agentDir), false)
    check("E3 persist is outside the volatile tree", VolatileTree.contains(paths.runtime, paths.persist), false)
    check("E4 the layout reports no violation", DurableLayout.violations(paths.home, paths.runtime), emptyList<String>())
    // What `wipe()` is allowed to delete: the volatile root itself. Nothing else is ever
    // passed to a recursive delete in the provisioner.
    check("E5 the volatile root is deletable", VolatileTree.offending(paths.runtime, listOf(paths.runtime)), emptyList<File>())
    // And if a future edit pointed a recursive delete at a durable directory, the guard
    // refuses it: all three come back as offenders, which is what `deleteTreeInsideVolatile`
    // turns into a thrown ProvisioningException.
    check(
        "E6 every durable directory is refused as a delete target",
        VolatileTree.offending(paths.runtime, DurableLayout.durableDirs(paths.home)).size,
        3,
    )
    check(
        "E7 the runtime's own children are legal targets",
        VolatileTree.offending(paths.runtime, listOf(File(paths.runtime, "node-stage"), File(paths.runtime, "lib"))),
        emptyList<File>(),
    )
    // A sibling with the runtime's prefix is not "inside" it.
    check(
        "E8 a path that merely shares the prefix is outside",
        VolatileTree.contains(paths.runtime, File(filesDir, "pi/runtime-old")),
        false,
    )
    check("E9 the volatile root contains itself", VolatileTree.relativePath(paths.runtime, paths.runtime), "")

    // The counterexample: if the durable root really were inside the volatile tree, the
    // check that `PiPaths` runs at construction must answer with a loud sentence — this is
    // the same function, so the failure mode the assertion would produce is pinned here.
    val movedHome = paths.runtime
    val violations = DurableLayout.violations(movedHome, paths.runtime)
    check("F1 a durable dir inside the volatile tree is a violation", violations.size, 3)
    check("F2 the violation names the directory", violations.all { it.contains("落在易失树") }, true)
    check("F3 the violation names the tree", violations.all { it.contains(paths.runtime.path) }, true)

    // One spelling of the workspace root, on both sides of the files-directory boundary:
    // `GuestWorkspacePath.ROOT_RELATIVE` is relative to `<files>`, `PiPaths.workspaces` is
    // built from `DurableLayout`, and they must name the same directory.
    check(
        "G1 the two spellings of the workspace root agree",
        GuestWorkspacePath.ROOT_RELATIVE,
        "pi/" + DurableLayout.WORKSPACES_RELATIVE,
    )
    check("G2 workspaces follows the durable layout", paths.workspaces, File(paths.home, DurableLayout.WORKSPACES_RELATIVE))

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

    root.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
