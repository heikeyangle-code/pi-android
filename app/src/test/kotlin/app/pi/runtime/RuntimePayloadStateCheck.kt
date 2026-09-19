package app.pi.runtime

// A bare-JVM harness for the two decisions that keep an update from deleting the user's
// files (`runtime/PayloadPrune.kt`, `runtime/VolatileTree.kt`, `runtime/PayloadPrune.kt`'s
// durable layout), compiled and run by `tools/run-app-pure-checks.sh`.
//
// Why a harness and not a device: both defects already shipped and both are silent.
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

    root.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
