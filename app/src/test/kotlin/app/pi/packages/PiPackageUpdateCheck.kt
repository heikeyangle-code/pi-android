package app.pi.packages

// Pure-logic checks for `pi update`, runnable on a bare JVM with no Gradle.
//
// Same shape as PackagesPureLogicCheck.kt in this directory: a plain `fun main()`
// with a hand-rolled `check()` counter and no test framework, because this module's
// Gradle build has no `testImplementation(libs.junit)` and adding one means editing
// `app/build.gradle.kts`, which this package does not own.
//
//   tools/run-app-pure-checks.sh
//
// **Not yet registered** in that script: this change does not own it. The group to
// add is a copy of the `packages` group's shape with `PiPackageUpdateCheckKt` as the
// main class and this file's compile closure —
//
//   app/src/test/kotlin/app/pi/packages/PiPackageUpdateCheck.kt
//   app/src/main/kotlin/app/pi/packages/PiPackageUpdate.kt
//   app/src/main/kotlin/app/pi/packages/PiPackageSource.kt
//   app/src/main/kotlin/app/pi/packages/PackageStrings.kt
//   app/src/main/kotlin/app/pi/packages/PiResourceDiscovery.kt
//   app/src/main/kotlin/app/pi/packages/PiPackageModel.kt
//
// — the last two because `PackageStrings`' signatures name `PiResourceDiscovery.Kind`
// and `PiBuiltinExtension.Presence`. Android-, fs- and Compose-free by construction;
// if it ever stops being so, the harness stops compiling it, loudly, on purpose.
//
// What it pins, and why each is worth pinning:
//
//  - the two argv shapes we emit, and that the **positional source** is the only
//    word handed to the shell quoter (a source with a space or an `&` must not
//    split into two arguments, and a flag must not be quoted into one word);
//  - that `self` / `pi` are refused rather than passed on — those two are pi's
//    self-update targets (`package-manager-cli.ts:534-536`), which would replace the
//    pinned engine payload, and the refusal must be **case-sensitive** the way pi's
//    comparison is, so `SELF` stays a (never-matching) package source;
//  - that pinned npm and local paths are not offered as updatable, since pi's own
//    collector skips exactly those (`core/package-manager.ts:1103-1110`);
//  - that pi's own result line is read by **verb**, so the `Updating <source>...`
//    progress lines (`package-manager-cli.ts:946-950`) cannot be mistaken for the
//    result — the one classification mistake here that would put a false "updated"
//    on screen.

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
    // ------------------------------------------------------------------- plan
    check("null 目标 = 全部（--extensions）", PiPackageUpdate.plan(null), PiPackageUpdate.Plan.Run(null, PiPackageUpdate.ALL_WHAT))
    check("具体来源原样保留", PiPackageUpdate.plan("npm:foo@1.0.0"), PiPackageUpdate.Plan.Run("npm:foo@1.0.0", "npm:foo@1.0.0"))
    check("来源两端空白被去掉", PiPackageUpdate.plan("  git:github.com/u/r  "), PiPackageUpdate.Plan.Run("git:github.com/u/r", "git:github.com/u/r"))
    check("空来源被拒", PiPackageUpdate.plan("") is PiPackageUpdate.Plan.Refused, true)
    check("全空白来源被拒", PiPackageUpdate.plan("   ") is PiPackageUpdate.Plan.Refused, true)
    check("self 被拒（那是 pi 自身更新）", PiPackageUpdate.plan("self") is PiPackageUpdate.Plan.Refused, true)
    check("pi 被拒（同上）", PiPackageUpdate.plan("pi") is PiPackageUpdate.Plan.Refused, true)
    check(
        "拒绝语说明理由与去处",
        (PiPackageUpdate.plan("self") as PiPackageUpdate.Plan.Refused).message,
        PackageStrings.UPDATE_REFUSED_SELF,
    )
    // pi compares with `source === "self" || source === "pi"` — case-sensitive. A
    // case-insensitive refusal here would block a package that pi itself would accept.
    check("SELF 大写不是 pi 的自身更新目标，按来源放行", PiPackageUpdate.plan("SELF") is PiPackageUpdate.Plan.Run, true)

    // ------------------------------------------------------------------ words
    val quote: (String) -> String = { "<$it>" }
    check(
        "全部更新的 argv：update --extensions",
        PiPackageUpdate.words(PiPackageUpdate.Plan.Run(null, PiPackageUpdate.ALL_WHAT), quote),
        listOf("update", "--extensions"),
    )
    check(
        "单个来源的 argv：update <来源>",
        PiPackageUpdate.words(PiPackageUpdate.Plan.Run("npm:foo", "npm:foo"), quote),
        listOf("update", "<npm:foo>"),
    )
    check(
        "只有来源那一个词经过 quoting，命令与 flag 不经过",
        PiPackageUpdate.words(PiPackageUpdate.Plan.Run("a b&c", "a b&c"), quote),
        listOf("update", "<a b&c>"),
    )

    // ------------------------------------------------------------- canUpdate
    val pinned = PiPackageSource.parse("npm:foo@1.0.0")
    val ranged = PiPackageSource.parse("npm:foo@^1.0.0")
    val untagged = PiPackageSource.parse("npm:foo")
    val git = PiPackageSource.parse("git:github.com/u/r")
    val local = PiPackageSource.parse("/tmp/some-package")
    check("精确版本 npm：不提供更新（pi 自己会跳过）", PiPackageUpdate.canUpdate(pinned), false)
    check("范围版本 npm：可更新", PiPackageUpdate.canUpdate(ranged), true)
    check("无版本标签的 npm：可更新", PiPackageUpdate.canUpdate(untagged), true)
    check("git 来源：可更新", PiPackageUpdate.canUpdate(git), true)
    check("本地路径：不提供更新（没有可拉取的内容）", PiPackageUpdate.canUpdate(local), false)
    check("精确版本的理由句", PiPackageUpdate.skipReason(pinned), PackageStrings.UPDATE_SKIP_PINNED)
    check("本地路径的理由句", PiPackageUpdate.skipReason(local), PackageStrings.UPDATE_SKIP_LOCAL)
    check("可更新的来源没有理由句", PiPackageUpdate.skipReason(git), null)

    // ------------------------------------------------------------ resultLine
    check(
        "只认 pi 的结果行，不认 Updating 进度行",
        PiPackageUpdate.resultLine("Updating npm:foo...\nUpdating user npm packages...\nUpdated packages\n"),
        "Updated packages",
    )
    check("单个来源的结果行", PiPackageUpdate.resultLine("Updated npm:foo@2.0.0\n"), "Updated npm:foo@2.0.0")
    check("install 的结果行仍然认", PiPackageUpdate.resultLine("Installed npm:foo\n"), "Installed npm:foo")
    check("remove 的结果行仍然认", PiPackageUpdate.resultLine("Removed npm:foo\n"), "Removed npm:foo")
    check("只有进度行时没有结果行", PiPackageUpdate.resultLine("Updating npm:foo...\n"), null)
    check("空输出没有结果行", PiPackageUpdate.resultLine(""), null)
    check("前导空白不影响识别", PiPackageUpdate.resultLine("   Updated packages  \n"), "Updated packages")
    check(
        "没有结果行时的回退句说明 pi 没打印，而不是宣称已更新",
        PiPackageUpdate.fallbackSummary("全部资源包").contains("没有打印"),
        true,
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
