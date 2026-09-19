package app.pi.settings

// A bare-JVM harness for the 「Pi 文件」screen's pure logic (`app.pi.settings.PiFiles`),
// meant to be registered in `tools/run-app-pure-checks.sh` as `pi-files`:
//
//   run_harness pi-files \
//     app.pi.settings.PiFilesCheckKt \
//     "$ROOT/app/src/test/kotlin/app/pi/settings/PiFilesCheck.kt" \
//     "$ROOT/app/src/main/kotlin/app/pi/settings/PiFiles.kt" \
//     "$ROOT/app/src/main/kotlin/app/pi/packages/PiJsonComments.kt"
//
// Why these assertions and not others: the three things this screen can get wrong are
// (1) letting a user write a file pi will never read, or that pi rewrites underneath
// them, (2) letting them write JSON that makes pi **throw** (not fall back) — the
// `null` cases below are exactly the ones `core/settings-manager.ts:188-197`, `:860-877`
// and `:868-872` throw on — and (3) writing outside the root it claims to be browsing.
// All three are decided by pure functions here, so none of them needs a device.
//
// Android-free on purpose: `java.io.File`, kotlinx.serialization and the stdlib only.
// No `main` argument is read except `pi.repo.root`, which this harness does not need.

import java.io.File
import java.nio.file.Files
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

private fun writable(vararg paths: String) {
    for (path in paths) {
        val access = piFilesAccessFor(path)
        check("可写：$path", access == PiFilesAccess.Writable, "得到 $access")
    }
}

private fun readOnly(vararg paths: String) {
    for (path in paths) {
        val access = piFilesAccessFor(path)
        check("只读：$path", access == PiFilesAccess.ReadOnly, "得到 $access")
        check(
            "只读有原因：$path",
            piFilesReadOnlyReason(path).isNotBlank(),
        )
    }
}

private fun blockers(path: String, text: String): List<String> = checkPiFileWrite(path, text).blockers

private fun accepted(path: String, text: String): Boolean = checkPiFileWrite(path, text).ok

fun main() {
    // ---------------------------------------------------------------- 根
    val agent = File("/tmp/pi-files-check/agent")
    val project = File("/tmp/pi-files-check/ws/.pi")
    val roots = piFilesRoots(agent, project)
    check("两个根，顺序固定", roots.size == 2 && roots[0].kind == PiFilesRootKind.AgentDir &&
        roots[1].kind == PiFilesRootKind.ProjectPi)
    check("根的文件就是传入的那个目录", roots[0].file == agent && roots[1].file == project)
    check("根的标签是人话，不带路径", roots.none { it.label.contains("/") || it.label.contains("~") },
        roots.joinToString { it.label })

    // ---------------------------------------------------------------- 可写白名单
    writable(
        "settings.json",
        "models.json",
        "AGENTS.md",
        "AGENTS.override.md",
        "CLAUDE.md",
        "SYSTEM.md",
        "APPEND_SYSTEM.md",
        "themes/my-theme.json",
        "skills/review/SKILL.md",
        "prompts/fix.md",
        "extensions/hello/index.ts",
    )
    check("前导斜杠与 ./ 不影响判定", piFilesAccessFor("/settings.json") == PiFilesAccess.Writable &&
        piFilesAccessFor("./settings.json") == PiFilesAccess.Writable)

    // 默认拒绝：这些名字一个都不在 pi 的「用户文档」那一类里。
    readOnly(
        "auth.json",
        "models-store.json",
        "trust.json",
        "keybindings.json",
        "pi-debug.log",
        "sessions/2026-01-01_x.jsonl",
        "sessions/--workspace--/y.jsonl",
        "npm/node_modules/pi-skills/index.js",
        "bin/rg",
        "tools/whatever.ts",
        "themes/notes.txt",
        "settings.json.lock",
        "sessions.lock",
        "unknown.json",
        "../outside.json",
        "themes/../settings.json",
        "..",
        "",
    )
    check("auth.json 的只读原因指向凭据页", piFilesReadOnlyReason("auth.json").contains("API Key"))
    check(
        "sessions 的只读原因说明 pi 会追加/重写",
        piFilesReadOnlyReason("sessions/a.jsonl").contains("追加"),
    )
    check("锁目录的原因说明它是锁", piFilesReadOnlyReason("settings.json.lock").contains("锁"))
    check("lock 判定按后缀", isPiLockArtifact("x.lock") && !isPiLockArtifact("x.lock.json"))
    check(
        "目录档：白名单里的四个可写，其余只读",
        piFilesDirIsWritable("themes") && piFilesDirIsWritable("skills/a") &&
            !piFilesDirIsWritable("sessions") && !piFilesDirIsWritable("npm") &&
            !piFilesDirIsWritable("bin") && !piFilesDirIsWritable("") &&
            !piFilesDirIsWritable("../themes"),
    )

    // ---------------------------------------------------------------- 文档类别
    check("settings.json 是设置文档", piFilesDocFor("settings.json") == PiFilesDoc.Settings)
    check("models.json 是模型文档", piFilesDocFor("models.json") == PiFilesDoc.Models)
    check("themes 下的 json 是主题", piFilesDocFor("themes/dark.json") == PiFilesDoc.Theme)
    check("skills/ 优先于 .md", piFilesDocFor("skills/a/SKILL.md") == PiFilesDoc.Skill)
    check("prompts/ 优先于 .md", piFilesDocFor("prompts/a.md") == PiFilesDoc.Prompt)
    check("extensions/ 是扩展", piFilesDocFor("extensions/a/index.ts") == PiFilesDoc.Extension)
    check("根上的 AGENTS.md 是 markdown", piFilesDocFor("AGENTS.md") == PiFilesDoc.Markdown)
    check("未知 .json 归 Json", piFilesDocFor("notes.json") == PiFilesDoc.Json)
    check("未知后缀归 Text", piFilesDocFor("notes.txt") == PiFilesDoc.Text)

    // ---------------------------------------------------------------- settings.json
    check("合法 settings.json 通过", accepted("settings.json", """{"theme":"dark","app.x":1}"""))
    check("数组顶层被拒", blockers("settings.json", "[]").isNotEmpty())
    check("非 JSON 被拒", blockers("settings.json", "{theme: dark}").isNotEmpty())
    check("注释被拒（pi 用严格 JSON 读设置）", blockers("settings.json", "{//c\n\"theme\":\"dark\"}").isNotEmpty())

    // 这些正是 pi 会**抛异常**（而不是回退默认值）的形状。
    check("httpIdleTimeoutMs: null 被拒", blockers("settings.json", """{"httpIdleTimeoutMs":null}""").isNotEmpty())
    check(
        "websocketConnectTimeoutMs: null 被拒",
        blockers("settings.json", """{"websocketConnectTimeoutMs":null}""").isNotEmpty(),
    )
    check("httpIdleTimeoutMs: \"disabled\" 通过", accepted("settings.json", """{"httpIdleTimeoutMs":"disabled"}"""))
    check("httpIdleTimeoutMs: 字符串数字被拒", blockers("settings.json", """{"httpIdleTimeoutMs":"5"}""").isNotEmpty())
    check("httpIdleTimeoutMs: 负数被拒", blockers("settings.json", """{"httpIdleTimeoutMs":-1}""").isNotEmpty())
    check("httpIdleTimeoutMs: 小数被拒", blockers("settings.json", """{"httpIdleTimeoutMs":1.5}""").isNotEmpty())
    check("httpIdleTimeoutMs: 布尔被拒", blockers("settings.json", """{"httpIdleTimeoutMs":true}""").isNotEmpty())
    check("httpIdleTimeoutMs: 对象被拒", blockers("settings.json", """{"httpIdleTimeoutMs":{}}""").isNotEmpty())
    check("httpIdleTimeoutMs: 0 通过", accepted("settings.json", """{"httpIdleTimeoutMs":0}"""))
    check("httpIdleTimeoutMs: 300000 通过", accepted("settings.json", """{"httpIdleTimeoutMs":300000}"""))

    check("compaction 非对象被拒", blockers("settings.json", """{"compaction":5}""").isNotEmpty())
    check("compaction.reserveTokens: null 被拒", blockers("settings.json", """{"compaction":{"reserveTokens":null}}""").isNotEmpty())
    check("compaction.keepRecentTokens: 负数被拒", blockers("settings.json", """{"compaction":{"keepRecentTokens":-1}}""").isNotEmpty())
    check("compaction.reserveTokens: 字符串被拒", blockers("settings.json", """{"compaction":{"reserveTokens":"1"}}""").isNotEmpty())
    check("compaction.reserveTokens: 0 通过", accepted("settings.json", """{"compaction":{"reserveTokens":0,"keepRecentTokens":20000}}"""))
    check(
        "compaction.modelOverrides: 非对象条目被拒",
        blockers("settings.json", """{"compaction":{"modelOverrides":{"a/b":5}}}""").isNotEmpty(),
    )
    check(
        "compaction.modelOverrides: 条目本身的坏值被拒",
        blockers("settings.json", """{"compaction":{"modelOverrides":{"a/b":{"reserveTokens":null}}}}""").isNotEmpty(),
    )
    check(
        "compaction.modelOverrides: 合法覆盖通过",
        accepted("settings.json", """{"compaction":{"modelOverrides":{"openrouter/a/b":{"reserveTokens":400000}}}}"""),
    )
    check(
        "未知键不是错误（pi 会原样保留它们）",
        accepted("settings.json", """{"app.appearance.fontSize":1,"theme":"dark"}"""),
    )

    // ---------------------------------------------------------------- models.json
    check("合法 models.json 通过", accepted("models.json", """{"providers":{"local":{"baseUrl":"http://127.0.0.1:11434","models":[{"id":"m"}]}}}"""))
    check(
        "models.json 注释只提示不拦（pi 会剥掉再读）",
        checkPiFileWrite("models.json", "{//c\n\"providers\":{}}").notices.isNotEmpty() &&
            accepted("models.json", "{//c\n\"providers\":{}}"),
    )
    check("缺 providers 被拒", blockers("models.json", """{"providersX":{}}""").isNotEmpty())
    check("providers 非对象被拒", blockers("models.json", """{"providers":[]}""").isNotEmpty())
    check("厂商非对象被拒", blockers("models.json", """{"providers":{"a":1}}""").isNotEmpty())
    check("模型缺 id 被拒", blockers("models.json", """{"providers":{"a":{"models":[{"name":"x"}]}}}""").isNotEmpty())
    check("模型 id 空串被拒", blockers("models.json", """{"providers":{"a":{"models":[{"id":""}]}}}""").isNotEmpty())
    check("models 非数组被拒", blockers("models.json", """{"providers":{"a":{"models":{}}}}""").isNotEmpty())
    check("oauth 非 radius 被拒", blockers("models.json", """{"providers":{"a":{"oauth":true}}}""").isNotEmpty())
    check("oauth: radius 通过", accepted("models.json", """{"providers":{"a":{"baseUrl":"http://x","oauth":"radius"}}}"""))
    check(
        "modelOverrides 条目非对象被拒",
        blockers("models.json", """{"providers":{"a":{"modelOverrides":{"m":1}}}}""").isNotEmpty(),
    )

    // ---------------------------------------------------------------- themes
    check(
        "主题：name + colors 是必需的",
        blockers("themes/t.json", """{"colors":{"accent":"#fff"}}""").isNotEmpty(),
    )
    check("主题：colors 为空被拒", blockers("themes/t.json", """{"name":"t","colors":{}}""").isNotEmpty())
    check("主题：name 含 / 被拒", blockers("themes/t.json", """{"name":"a/b","colors":{"accent":"#fff"}}""").isNotEmpty())
    val goodTheme = checkPiFileWrite("themes/t.json", """{"name":"t","colors":{"accent":"#fff"}}""")
    check("主题：合法通过并带一条提示", goodTheme.ok && goodTheme.notices.isNotEmpty())
    check("主题：非 JSON 被拒", blockers("themes/t.json", "not json").isNotEmpty())

    // 其它 .json 只要求能解析；扩展只给提示、不拦。
    check("普通 .json 必须能解析", blockers("notes.json", "nope").isNotEmpty() && accepted("notes.json", """{"a":1}"""))
    check("扩展不校验语法", accepted("extensions/a/index.ts", "this is not valid typescript(") &&
        checkPiFileWrite("extensions/a/index.ts", "x(").notices.isNotEmpty())
    check("markdown 不校验", accepted("SYSTEM.md", "# 任意文本\n\n-----") && accepted("skills/a/SKILL.md", "任意"))

    // ---------------------------------------------------------------- 路径
    val tmp = Files.createTempDirectory("pi-files-check").toFile()
    val root = File(tmp, "agent").also { it.mkdirs() }
    val inside = File(root, "themes/t.json").also { it.parentFile.mkdirs(); it.writeText("{}") }
    val outside = File(tmp, "outside.json").also { it.writeText("{}") }
    check("根内路径可解析", piFilesRelativePath(root, inside) == "themes/t.json", piFilesRelativePath(root, inside).toString())
    check("根自己解析成空串", piFilesRelativePath(root, root) == "")
    check("根外路径解析成 null", piFilesRelativePath(root, outside) == null)
    check(
        "前缀相同的兄弟目录不算根内",
        piFilesRelativePath(File(tmp, "a"), File(tmp, "ab/x").also { it.parentFile.mkdirs(); it.writeText("") }) == null,
    )
    check("面包屑", piFilesBreadcrumb("agent 目录", "themes/t.json") == "agent 目录 / themes/t.json")
    check("面包屑（根）", piFilesBreadcrumb("项目 .pi", "") == "项目 .pi")
    check("父目录", piFilesParent("themes/t.json") == "themes" && piFilesParent("t.json") == "")
    check("子路径", piFilesChild("themes", "t.json") == "themes/t.json" && piFilesChild("", "a") == "a")

    println(
        "\npi-files: 根 ${roots.size} 个 · 可写示例 ${piFilesAccessFor("settings.json")} · " +
            "只读示例 ${piFilesAccessFor("auth.json")} · 7 类文档各有校验器",
    )
    println(if (failures == 0) "\nharness: OK" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
