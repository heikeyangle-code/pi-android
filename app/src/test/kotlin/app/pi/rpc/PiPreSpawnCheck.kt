package app.pi.rpc

// A bare-JVM check of the **pre-spawn** surface, registered in
// `tools/run-app-pure-checks.sh` as `pre-spawn`.
//
// Why this check exists: `docs/pre-spawn-config.md` claims a table. The part
// that can rot silently is not the prose but the wiring —
//
//   * a row added to the settings screen whose value never reaches
//     `PiLaunchOptions` (the §I11 shape: a process switch with no process),
//   * a knob exposed both as a settings key *and* as a CLI flag, which gives
//     the engine two sources for one value (the §M12 shape: two truths, one of
//     which silently wins),
//   * a `RestartEngine` claim on the row that is not actually restart-only,
//   * a discovery suppression (`--no-extensions` / `--no-skills` /
//     `--no-prompt-templates` / `--no-themes`) that is not carried, or one that is
//     emitted with extra flags. The four are **1:1 with pi**: `--no-extensions` in
//     particular must *not* be paired with `-e` to keep the app's own extensions
//     alive — that would be an app-side exception to a pi flag, and the shipped
//     extensions are ordinary discovered extensions that the switch is meant to
//     turn off too (`docs/pre-spawn-config.md` §2.2).
//   * a pre-spawn row that left the two groups which document the pre-spawn
//     contract (`运行时与诊断 → 进程` for the engine knobs, `提示词` for the two
//     prompt flags — see the `preSpawnGroups` comment below). The second group is
//     new: the prompt rows moved there because that is where a user looks for
//     them, and this check is what keeps that move deliberate rather than drift.
//
// `PiPreSpawnConfig.kt` and `PiLaunchOptions.kt` are pure Kotlin, so they are
// compiled here directly. `PiSettingsRegistry.kt` imports Compose and
// `PiSessionViewModel.kt` imports Android, so those two are read as **source
// text** — the key name and the badge are exactly the granularity these checks
// are about.

import java.io.File
import kotlin.system.exitProcess

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name${if (detail.isEmpty()) "" else "\n  $detail"}")
    }
}

/** One `PiSetting( ... )` row of the registry, keyed by its `key = "..."`. */
private fun settingBlocks(registry: String): List<String> =
    registry.split(Regex("(?m)^\\s*PiSetting\\(\\s*$")).drop(1)

private fun blockFor(registry: String, key: String): String? =
    settingBlocks(registry).firstOrNull { it.contains("key = \"$key\"") }

/** Splits a `--a / --b` / `VAR_A / VAR_B` spelling into its parts. */
private fun spellings(value: String?): List<String> =
    value?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

/** `(^|\s)--flag(\s|$)` — so `--system-prompt` does not match inside `--xsystem-prompt`. */
private fun containsFlag(suffix: String, flag: String): Boolean =
    Regex("(^|\\s)" + Regex.escape(flag) + "(\\s|$)").containsMatchIn(suffix)

/**
 * The text the `everything` fixture writes into `app.extensions.args`.
 *
 * One constant, used by the fixture *and* by the assertion that parses the built
 * command line back: the pass-through row's spelling belongs to the user, so this is
 * the only place the harness can state what should come out, and a second copy could
 * drift from the fixture without either side looking wrong.
 */
private const val PASS_THROUGH_FIXTURE_ARGS = "--plan --ssh user@host:/path"

fun main() {
    val root = System.getProperty("pi.repo.root")?.let(::File)
        ?: File(System.getProperty("user.dir") ?: ".")
    val registryFile = File(root, "app/src/main/kotlin/app/pi/ui/settings/PiSettingsRegistry.kt")
    val viewModelFile = File(root, "app/src/main/kotlin/app/pi/ui/PiSessionViewModel.kt")

    check("the settings registry is readable at $registryFile", registryFile.isFile)
    check("the session view model is readable at $viewModelFile", viewModelFile.isFile)
    if (!registryFile.isFile || !viewModelFile.isFile) {
        println("\nharness: FAILED (missing source to audit)")
        exitProcess(1)
    }
    val registry = registryFile.readText()
    val viewModel = viewModelFile.readText()
    // The app-only sidecar's own file: it is where `app.extensions.args` is spelled
    // out as a key (the row is not a pi key, so the mapping reads it through
    // `ExtensionArgsStore` rather than inline). Read as source text for the same
    // reason the other two are: it imports Android.
    val extensionArgsStoreFile = File(root, "app/src/main/kotlin/app/pi/ui/ExtensionArgsSettingsStore.kt")
    val extensionArgsStore = extensionArgsStoreFile.takeIf { it.isFile }?.readText().orEmpty()

    // ---------------------------------------------------------------- mapping
    // The defaults are what pi gets with no options at all: nothing on either
    // channel. A default that emitted anything would be a silent behaviour change
    // on every launch.
    val defaults = PiLaunchOptions()
    check("the default launch sends no environment", defaults.environment().isEmpty())
    check("the default launch sends no flags", defaults.commandLineSuffix() == "")

    val allNull = PiLaunchOptions.fromSettingValues(
        offline = null,
        cacheRetention = null,
        systemPrompt = null,
        appendSystemPrompt = null,
        noContextFiles = null,
    )
    check("absent settings collapse to the defaults", allNull == defaults)

    val blank = PiLaunchOptions.fromSettingValues(
        offline = false,
        cacheRetention = "short",
        systemPrompt = "   ",
        appendSystemPrompt = "",
        noContextFiles = false,
    )
    check(
        "blank prompts and non-long cache retention emit nothing",
        blank.commandLineSuffix() == "" && blank.environment().isEmpty(),
        "got suffix=${blank.commandLineSuffix()} env=${blank.environment()}",
    )

    // `PI_OFFLINE` is tested for *presence* by core/model-runtime.ts:196, so a
    // false-y string is worse than omitting the key.
    val offlineOff = PiLaunchOptions.fromSettingValues(true, null, null, null, null)
    check("offline=true sets PI_OFFLINE=1", offlineOff.environment()["PI_OFFLINE"] == "1")
    val offlineUnset = PiLaunchOptions.fromSettingValues(false, null, null, null, null)
    check(
        "offline=false omits PI_OFFLINE rather than writing a false-y value",
        !offlineUnset.environment().containsKey("PI_OFFLINE"),
        "core/model-runtime.ts:196 treats any present PI_OFFLINE as 'model network off'.",
    )
    val cacheLong = PiLaunchOptions.fromSettingValues(null, "long", null, null, null)
    check("cacheRetention=long sets PI_CACHE_RETENTION=long", cacheLong.environment()["PI_CACHE_RETENTION"] == "long")
    val cacheShort = PiLaunchOptions.fromSettingValues(null, "short", null, null, null)
    check("cacheRetention=short omits PI_CACHE_RETENTION", !cacheShort.environment().containsKey("PI_CACHE_RETENTION"))

    // ------------------------------------------------- every exposed knob emits
    // The fixture sets **every** knob, including the extension pass-through: a knob
    // listed in the table and not carried by `PiLaunchOptions` is the §I11 defect this
    // whole section exists to catch.
    val everything = PiLaunchOptions.fromSettingValues(
        offline = true,
        cacheRetention = "long",
        systemPrompt = "base prompt",
        appendSystemPrompt = "extra preference",
        noContextFiles = true,
        noExtensions = true,
        noSkills = true,
        noPromptTemplates = true,
        noThemes = true,
        extensionArgs = PASS_THROUGH_FIXTURE_ARGS,
    )
    val suffix = everything.commandLineSuffix()
    val environment = everything.environment()
    for (knob in APP_EXPOSED_PRE_SPAWN) {
        // What must be true follows from `channel`, not from which fields happen to be
        // filled in. `CliFlagOrEnv` means pi accepts *either* spelling, so demanding
        // the flag would fail on a knob the app deliberately carries as its
        // environment variable — `app.runtime.offline` is exactly that: `PiLaunchOptions`
        // sets `PI_OFFLINE=1` (pi reads it, `docs/environment-variables.md:84`) and
        // never passes `--offline`. Asserting the flag there asserted the wrong fact.
        val flagEmitted = knob.flag?.let { containsFlag(suffix, it) } ?: false
        val envEmitted = knob.envVar?.let { environment.containsKey(it) } ?: false
        val why = "PiLaunchOptions must carry this knob to pi's process. Re-read " +
            "rpc/PiLaunchOptions.kt and make the row real, or drop it from " +
            "APP_EXPOSED_PRE_SPAWN (rpc/PiPreSpawnConfig.kt)."
        when (knob.channel) {
            PiPreSpawnChannel.CliFlag -> check(
                "the exposed flag is emitted: ${knob.flag}",
                flagEmitted,
                why,
            )

            PiPreSpawnChannel.EnvVar -> check(
                "the exposed environment variable is emitted: ${knob.envVar}",
                envEmitted,
                why,
            )

            PiPreSpawnChannel.CliFlagOrEnv -> check(
                "the exposed knob is emitted in one of its two spellings: ${knob.appKey}",
                flagEmitted || envEmitted,
                "neither ${knob.flag} nor ${knob.envVar} reached pi. $why",
            )

            // The pass-through knob has no fixed spelling to look for: its flag names
            // are the extensions'. So this is asserted by **parsing the built command
            // line back** — which is the property that matters (the emission
            // round-trips) and not merely that some string appears in it. The app's own
            // flags are filtered out first: the suffix carries all of them on purpose.
            PiPreSpawnChannel.ExtensionFlagsPassThrough -> {
                val expected = ExtensionFlagArgs.parse(PASS_THROUGH_FIXTURE_ARGS).flags
                val actual = ExtensionFlagArgs.parse(suffix).flags
                    .filter { flag -> expected.any { it.name == flag.name } }
                check(
                    "the pass-through knob emits the flags written in its row: ${knob.appKey}",
                    actual == expected,
                    "the row's $PASS_THROUGH_FIXTURE_ARGS came back as $actual out of " +
                        "${suffix.ifBlank { "(empty suffix)" }}. $why",
                )
            }
        }
        // The channel field is documentation, but a wrong one would mislead the doc.
        val consistent = when (knob.channel) {
            PiPreSpawnChannel.CliFlag -> knob.flag != null && knob.envVar == null
            PiPreSpawnChannel.EnvVar -> knob.flag == null && knob.envVar != null
            PiPreSpawnChannel.CliFlagOrEnv -> knob.flag != null && knob.envVar != null
            // null/null is the whole point here: there is no spelling to name.
            PiPreSpawnChannel.ExtensionFlagsPassThrough -> knob.flag == null && knob.envVar == null
        }
        check(
            "the channel describes the spellings: ${knob.appKey}",
            consistent,
            "channel=${knob.channel} flag=${knob.flag} envVar=${knob.envVar} — fix one of them in PiPreSpawnConfig.kt.",
        )
    }

    // Appending is not replacing: both land on the same command line and pi
    // applies them in different places (core/system-prompt.ts:34-41).
    check(
        "replace and append are two distinct flags on one command line",
        containsFlag(suffix, "--system-prompt") &&
            containsFlag(suffix, "--append-system-prompt") &&
            suffix.indexOf("--system-prompt") < suffix.indexOf("--append-system-prompt"),
        "the append row must not shadow the replace row; see docs/pre-spawn-config.md.",
    )
    check(
        "noContextFiles is a flag, not an environment variable",
        containsFlag(suffix, "--no-context-files") && !environment.containsKey("--no-context-files"),
    )
    val noContextOff = PiLaunchOptions.fromSettingValues(null, null, null, null, false)
    check("noContextFiles=false emits nothing", noContextOff.commandLineSuffix() == "")

    // ------------------------------------------------- the four discovery suppressions
    val suppressionSuffix = PiLaunchOptions.fromSettingValues(
        offline = null,
        cacheRetention = null,
        systemPrompt = null,
        appendSystemPrompt = null,
        noContextFiles = null,
        noExtensions = true,
        noSkills = true,
        noPromptTemplates = true,
        noThemes = true,
    ).commandLineSuffix()
    val suppressionFlags = listOf("--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes")
    check(
        "every discovery suppression reaches the command line",
        suppressionFlags.all { containsFlag(suppressionSuffix, it) },
        "got ${suppressionSuffix.ifBlank { "(empty suffix)" }}",
    )
    // Exact, not "contains": with only these four set, the suffix is those four flags
    // and nothing else. This is the assertion that would catch any future companion
    // (an `-e`, a path, an env-derived extra) being smuggled in next to them.
    check(
        "with only the four suppressions on, the suffix is exactly those four flags",
        suppressionSuffix == " --no-extensions --no-skills --no-prompt-templates --no-themes",
        "got ${suppressionSuffix.ifBlank { "(empty suffix)" }}",
    )
    // 1:1 with pi — nothing is added next to a suppression. In particular
    // `--no-extensions` must not come with `-e` companions: the app's shipped
    // extensions are ordinary discovered extensions, so they are supposed to go off
    // with the switch. An `-e` here would be an app-side exception to a pi flag.
    check(
        "no suppression is accompanied by an explicit source flag",
        !Regex("(^|\\s)-e(\\s|$)").containsMatchIn(suppressionSuffix),
        "the command line carries -e next to a --no-* flag: " +
            "${suppressionSuffix.ifBlank { "(empty suffix)" }}. The four are 1:1 with pi " +
            "(docs/pre-spawn-config.md §2.2); keeping the app's extensions alive under " +
            "--no-extensions is a different app-side feature, not this flag.",
    )
    check(
        "the four suppressions are absent when unset",
        suppressionFlags.none { containsFlag(defaults.commandLineSuffix(), it) },
    )

    // Quoting: the suffix is handed to `bash -lc`, so spaces and metacharacters
    // must stay inside one argument.
    val quoted = PiLaunchOptions.fromSettingValues(null, null, "it's here; rm -rf /", "a b", null)
    check(
        "prompt values are shell-quoted",
        quoted.commandLineSuffix().contains("'it'\\''s here; rm -rf /'") &&
            quoted.commandLineSuffix().contains("'a b'"),
        "got ${quoted.commandLineSuffix()}",
    )

    // ------------------------- the conversation this *new process* starts on
    //
    // 用户报的「一个对话在列表里被切成好几段、标题各不相同」的根因是：pi 的 argv 是固定的，
    // 所以每一次进程启动都落在**全新**的会话文件上；而「重启是 App 的实现细节」（proroot
    // 开关当场生效、装包之后重启、崩溃后重试）不该被用户看见成一段新对话。修法是**在 argv 里
    // 钉住**这条对话，而不是起来之后补一条 `switch_session`（那套机制已删）。
    //
    // 这一节把 pi 的解析事实**跑出来**（`PiLaunchOptions` 在这台 JVM 上就是产品代码），
    // 下面再读 `PiSessionViewModel` 的源码把「谁来钉」钉住。
    val sessionId = "01932f4a-0000-7000-8000-abcdefabcdef"
    val pinned = PiLaunchOptions(continueSessionId = sessionId).commandLineSuffix()
    val pinnedTokens = pinned.trim().split(" ").filter { it.isNotEmpty() }
    check(
        "a known session is pinned as two tokens: `--session-id <id>`",
        pinnedTokens == listOf("--session-id", sessionId),
        "got '$pinned'",
    )
    // pi 只把 `--session`/`--session-id` 当成「下一个 token 是我的值」（`cli/args.ts:123-128`）；
    // `--session-id=<id>` 会掉进未知标志分支（`:227-234`）——**静默**开一段新对话，没有任何报错。
    // 所以这个 flag 不走 `renderFlag`（那个函数发 `=` 形式，且现在 `require` 拒绝这两个名字）。
    check(
        "the `=` spelling is never used for `--session-id` (pi would ignore the flag silently)",
        !pinned.contains("--session-id=") && !pinned.contains("session-id="),
        "got '$pinned'",
    )
    // `--session <path>`: 路径存在但不是合法 pi 会话时 pi 直接 `exit(1)`（`main.ts:337-345`），
    // 所以这条路从来不发这个 flag；`--resume` 是交互式选择器，在 RPC 模式下不可用。
    check(
        "`--session <path>` / `--resume` are never emitted",
        !containsFlag(pinned, "--session") && !containsFlag(pinned, "--resume"),
        "got '$pinned'",
    )
    val idBeatsContinue = PiLaunchOptions(
        continueSessionId = sessionId,
        continueMostRecent = true,
    ).commandLineSuffix()
    check(
        "an exact id wins over `-c` (never both)",
        containsFlag(idBeatsContinue, "--session-id") && !containsFlag(idBeatsContinue, "-c"),
        "got '$idBeatsContinue'",
    )
    check(
        "`-c` is emitted when there is no id (冷启动那条路)",
        PiLaunchOptions(continueMostRecent = true).commandLineSuffix().trim() == "-c",
        "got '${PiLaunchOptions(continueMostRecent = true).commandLineSuffix()}'",
    )
    check(
        "the default launch (no id, no `-c`) resumes nothing",
        defaults.commandLineSuffix() == "",
        "got '${defaults.commandLineSuffix()}'",
    )
    val pinnedWithExtensions = PiLaunchOptions(
        continueSessionId = sessionId,
        extensionArgs = PASS_THROUGH_FIXTURE_ARGS,
    ).commandLineSuffix()
    check(
        "`--session-id` sits before the extension pass-through (their tokens cannot eat the id)",
        pinnedWithExtensions.indexOf("--session-id") >= 0 &&
            pinnedWithExtensions.indexOf("--session-id") < pinnedWithExtensions.indexOf("--plan"),
        "got '$pinnedWithExtensions'",
    )
    // 一个扩展如果给自己注册了 `--session-id`，它就能把这条对话挤掉（`=` 形式还会被 pi 当未知
    // 标志交给扩展）—— `renderFlag` 因此 `require` 拒绝这两个名字。执行出来，不靠读源码。
    val clobber = runCatching {
        PiLaunchOptions(
            continueSessionId = sessionId,
            extensionArgs = "--session-id 0000",
        ).commandLineSuffix()
    }
    check(
        "an extension cannot claim `--session-id` and overwrite the pinned conversation",
        clobber.isFailure,
        "expected a refusal, got '${clobber.getOrNull()}'",
    )

    // ------------------------------------------------- where those two values come from
    // 「谁来钉」是**接线**，而 `PiSessionViewModel` import Android、在这里编译不了，所以读源码文本
    // （与下面读注册表同一套分工）。三条事实：
    //
    //  * **只有进程内重启钉 id**：只有那一刻 App 既知道用户在哪条对话上、又保证 cwd 没变。
    //    冷启动（`boot()`，也是崩溃后「重试」按钮走的路）交给 `-c`；切工作区换了 cwd，旧 id
    //    不是 `findById` 的答案（它的 cwd 过滤会用同一个 id 再建一个文件）。
    //  * id 在 `host.restart` **之前**读进局部变量：那次调用一进去旧引擎就退场，`meta` 被清。
    //  * `-c` 由 `app.sessions.resumeLast` 决定，而那个开关读的是**注册表里的默认值**
    //    （`boolIn`）—— 「把默认值翻成开」这类改动必须同时改变行为，不只是设置页的显示。
    val restartBody = viewModel.substringAfter("suspend fun restartEngine(", "").take(6_000)
    check("找到了 restartEngine 的函数体", restartBody.isNotEmpty(), "marker not found")
    val pinAt = restartBody.indexOf("val resumeSessionId = _state.value.meta.sessionId")
    val restartAt = restartBody.indexOf("host.restart(")
    check(
        "restartEngine reads the id before it hands the new process its argv",
        pinAt >= 0 && restartAt >= 0 && pinAt < restartAt,
        "pinAt=$pinAt restartAt=$restartAt",
    )
    check(
        "restartEngine pins that id in the launch options",
        restartBody.contains("launch = launchOptions(continueSessionId = resumeSessionId)"),
    )
    // 断言的是**实参**形状，不是形参：`private fun launchOptions(continueSessionId: …)` 也以同一个
    // 前缀开头，只数前缀会把声明和注释里的引用都算进去。三条 `launch = ` 里只有一条带 id。
    // （计数先落到两个 val 里：模板表达式里再嵌套一层字符串字面量，是这个 harness 里没人需要的
    // 花活，读起来也更容易出错。）
    val noArgLaunches = Regex("launch = launchOptions\\(\\)").findAll(viewModel).count()
    val pinnedLaunches = Regex("launch = launchOptions\\(continueSessionId = ").findAll(viewModel).count()
    check(
        "`launch = ` 只有两种形状：两处无参（boot / 切工作区）+ 一处钉 id（进程内重启）",
        noArgLaunches == 2 && pinnedLaunches == 1,
        "no-arg=$noArgLaunches pinned=$pinnedLaunches",
    )
    val bootBody = viewModel
        .substringAfter("fun boot(rebuild: Boolean = false) {", "")
        .substringBefore("suspend fun restartEngine(")
    check("找到了 boot 的函数体", bootBody.isNotEmpty(), "marker not found")
    check(
        "cold start pins no id (it is `-c`, under `app.sessions.resumeLast`)",
        bootBody.contains("val launchOptions = launchOptions()") &&
            !bootBody.contains("continueSessionId"),
    )
    check(
        "switching workspace pins no id (the cwd changed, so the old id is not pi's answer)",
        viewModel
            // 窗口取到 `switchWorkspace` **自己**的结尾为止（下一个声明是 `wouldInterruptTurn`）：
            // 再往后就是 `launchOptions` 的声明，那里面当然有 `continueSessionId`。
            .substringAfter("suspend fun switchWorkspace(", "")
            .substringBefore("fun wouldInterruptTurn()")
            .let { it.contains("host.restart(") && !it.contains("continueSessionId") },
    )
    check(
        "`continueMostRecent` is the registry default, read through `boolIn`",
        viewModel.contains("continueMostRecent = resumeLastEnabled()") &&
            viewModel.substringAfter("private fun resumeLastEnabled()", "").take(700)
                .contains("boolIn(settingsStore)"),
    )
    // 删掉的那套机制**不许回来**：每个重启入口各自补一条 `switch_session`，正是「一次对话裂成
    // 好几段」的来源（两处收尾之间用户就能打字，消息落进新文件）。
    for (gone in listOf(
        "continueAfterRestart",
        "discardEmptySessionCreatedByRestart",
        "maybeResumeLastSession",
        "resumeAttempted",
        "continueFrom",
    )) {
        check(
            "the post-hoc switch-back is gone: no `$gone`",
            !viewModel.contains(gone),
            "重启后的接回只能由 argv 完成；事后再 `switch_session` 会与新引擎的首帧抢会话，" +
                "用户在那几帧里发的消息就落进另一个文件了。",
        )
    }

    // --------------------------------------------------------- the rule: no two truths
    val exposedFlags = APP_EXPOSED_PRE_SPAWN.flatMap { spellings(it.flag) }.toSet()
    val exposedEnv = APP_EXPOSED_PRE_SPAWN.flatMap { spellings(it.envVar) }.toSet()
    val coveredFlags = COVERED_BY_PI_SETTING.flatMap { spellings(it.flag) }.toSet()
    val coveredEnv = COVERED_BY_PI_SETTING.flatMap { spellings(it.envVar) }.toSet()
    check(
        "no exposed knob is also covered by a pi settings key",
        (exposedFlags intersect coveredFlags).isEmpty() && (exposedEnv intersect coveredEnv).isEmpty(),
        "overlap: ${(exposedFlags intersect coveredFlags) + (exposedEnv intersect coveredEnv)}. A key pi " +
            "already reads must not get a second channel: the CLI value wins (core/sdk.ts:258-262), so the " +
            "settings row would silently stop working.",
    )
    check(
        "every covered knob names the settings key that supersedes it",
        COVERED_BY_PI_SETTING.all { it.piSettingKey.isNotBlank() && it.why.isNotBlank() },
    )

    val skippedFlags = NOT_EXPOSED_PRE_SPAWN.flatMap { spellings(it.flag) }.toSet()
    val skippedEnv = NOT_EXPOSED_PRE_SPAWN.flatMap { spellings(it.envVar) }.toSet()
    check(
        "a knob is either exposed or skipped, never both",
        (exposedFlags intersect skippedFlags).isEmpty() && (exposedEnv intersect skippedEnv).isEmpty(),
        "overlap: ${(exposedFlags intersect skippedFlags) + (exposedEnv intersect skippedEnv)}",
    )
    check(
        "every skipped knob carries a reason",
        NOT_EXPOSED_PRE_SPAWN.all { it.reason.isNotBlank() },
        "a reason is mandatory: 'not done yet' and 'meaningless here' must stay distinguishable.",
    )
    check(
        "a pi setting key is not reported as a pre-spawn row: defaultTools",
        piPreSpawnKnob("defaultTools") == null,
        "defaultTools is a settings key pi reads at session build; it must not be in APP_EXPOSED_PRE_SPAWN.",
    )
    check(
        "an app-only row is not reported as a pre-spawn row: app.runtime.keepAlive",
        piPreSpawnKnob("app.runtime.keepAlive") == null,
    )

    // ------------------------------------------------------- the registry rows
    // A pre-spawn row has to sit in one of the two groups that document the
    // pre-spawn contract, and nowhere else:
    //
    //  * 运行时与诊断 → 进程 — the engine knobs (offline / cache retention /
    //    context files). This was the only home until the two prompt flags moved.
    //  * 提示词 — `app.runtime.systemPrompt` and `app.runtime.appendSystemPrompt`
    //    (`--system-prompt` / `--append-system-prompt`, `cli/args.ts:110` / `:112`).
    //    They are the same kind of value — written into pi's argv once, at spawn —
    //    but a user looks for them next to the model, not under 运行时: pi's own CLI
    //    keeps `--model` and `--system-prompt` adjacent (`cli/args.ts:108-112`), and
    //    the settings home orders the groups the same way.
    //
    // The predicate stays discriminating on purpose: a row moved anywhere else
    // fails, which is what this check is for. It is not `true`.
    //
    // `G_RESOURCES` was added for `app.extensions.args`: the row is a pre-spawn value
    // (it becomes argv at spawn, hence `RestartEngine`), and 扩展与资源 is the group a
    // user looks in for anything about extensions — the same "the row lives where the
    // user looks for it, and that choice is asserted rather than assumed" reasoning
    // that moved the two prompt flags into 提示词.
    val preSpawnGroups = listOf("group = G_RUNTIME", "group = G_PROMPTS", "group = G_RESOURCES")
    for (knob in APP_EXPOSED_PRE_SPAWN) {
        val block = blockFor(registry, knob.appKey)
        check(
            "the pre-spawn key is registered: ${knob.appKey}",
            block != null,
            "no `PiSetting(key = \"${knob.appKey}\")` in PiSettingsRegistry.kt — the table and the screen " +
                "have drifted.",
        )
        if (block == null) continue
        check(
            "the pre-spawn row is marked RestartEngine: ${knob.appKey}",
            block.contains("effective = EffectiveKind.RestartEngine"),
            "the value is written into pi's argv/env once, at spawn; a `NewSession` or `Reload` badge " +
                "promises a change the running process cannot make. See docs/pre-spawn-config.md.",
        )
        check(
            "the pre-spawn row lives in a pre-spawn group: ${knob.appKey}",
            preSpawnGroups.any { block.contains(it) },
            "pre-spawn rows live in 运行时与诊断 → 进程 (the engine knobs) or in 提示词 (the two prompt " +
                "flags, moved there because a user looks for them next to the model). A row anywhere else " +
                "hides that contract; put it back, or register the new group here and in " +
                "PiSettingsRegistry.kt. See docs/pre-spawn-config.md.",
        )
        check(
            "the pre-spawn row is read by the launch mapping: ${knob.appKey}",
            // Normally the launch mapping names the key itself. The pass-through row
            // is the one exception: its key is not a pi key, so it is spelled in the
            // app-only store and the mapping reaches it through that store — and both
            // halves are required, or a row nobody reads would still pass.
            viewModel.contains("\"${knob.appKey}\"") ||
                (
                    knob.channel == PiPreSpawnChannel.ExtensionFlagsPassThrough &&
                        extensionArgsStore.contains("\"${knob.appKey}\"") &&
                        viewModel.contains("extensionArgsStore.read()")
                    ),
            "PiSessionViewModel.kt never mentions this key, so the settings row writes a value no launch " +
                "reads — the §I11 defect.",
        )
    }
    check(
        "the launch mapping goes through the pure normaliser",
        viewModel.contains("PiLaunchOptions.fromSettingValues"),
        "the settings→process normalisation must stay in PiLaunchOptions.fromSettingValues so this harness " +
            "can execute it; re-read app/.../PiSessionViewModel.kt's launchOptions().",
    )

    if (failures > 0) {
        println("\nharness: FAILED ($failures)")
        exitProcess(1)
    }
    println("\nharness: OK")
}
