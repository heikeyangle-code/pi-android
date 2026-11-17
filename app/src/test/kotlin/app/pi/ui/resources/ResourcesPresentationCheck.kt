package app.pi.ui.resources

// 裸 JVM harness：「扩展与资源」页的全部判断——已加载资源的解析（形状取自真实宿主输出）、
// 开关清单与实际加载按路径对上、概览数字、分段与搜索、诊断说成人话、上下文行，
// 以及项目信任的说法与可选动作。

import app.pi.packages.PiLoadedResources
import app.pi.packages.PiLoadedResources.DiagKind
import app.pi.packages.PiProjectTrust
import app.pi.packages.PiProjectTrust.Remember
import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.packages.PiResourceCatalog.Type
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $name") else { failures++; println("FAIL $name $detail") }
}

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

private const val AGENT = "/tmp/x/agent"

// 与真实宿主 `resources.loaded` 同形（来自 sdk-host 测试时的输出）。
private val LOADED_JSON = """
{
 "extensions":[
  {"path":"/ext/probe.mjs","resolvedPath":"/ext/probe.mjs","hidden":false,
   "source":{"path":"/ext/probe.mjs","source":"cli","scope":"temporary","origin":"top-level","baseDir":null},
   "commands":[{"name":"probe-ping","description":"probe command"}],
   "tools":[{"name":"probe_tool","description":"probe"}],
   "flags":[{"name":"probe-mode","description":"probe flag","type":"string"},{"name":"probe-loud","description":null,"type":"boolean"}],
   "shortcuts":[],"events":["session_start","tool_call"]},
  {"path":"builtin:mcp","resolvedPath":"builtin:mcp","hidden":true,"source":null,"commands":[],"tools":[],"flags":[],"shortcuts":[],"events":["session_start"]},
  {"path":"builtin:codemode","resolvedPath":"builtin:codemode","hidden":false,"source":null,"commands":[],"tools":[],"flags":[],"shortcuts":[],"events":[]}
 ],
 "extensionErrors":[{"path":"/ext/bad.mjs","message":"SyntaxError"}],
 "extensionWarnings":[],
 "skills":[
  {"name":"user-skill","description":"用户级技能","filePath":"$AGENT/skills/user-skill/SKILL.md","baseDir":"$AGENT/skills/user-skill","disableModelInvocation":false,
   "source":{"path":"$AGENT/skills/user-skill/SKILL.md","source":"auto","scope":"user","origin":"top-level","baseDir":"$AGENT"}},
  {"name":"manual","description":"","filePath":"$AGENT/skills/manual/SKILL.md","baseDir":"$AGENT/skills/manual","disableModelInvocation":true,"source":null}
 ],
 "skillDiagnostics":[{"type":"collision","message":"dup","path":null,"collision":{"resourceType":"skill","name":"user-skill","winnerPath":"/a/b/c/d/SKILL.md","loserPath":"/e/SKILL.md","winnerSource":"auto","loserSource":null}}],
 "prompts":[{"name":"p1","description":"提示","argumentHint":"<x>","filePath":"$AGENT/prompts/p1.md","body":{"text":"你好 ${'$'}1","truncated":true,"size":9000},"source":null}],
 "promptDiagnostics":[],
 "themes":[],
 "themeDiagnostics":[{"type":"error","message":"invalid theme","path":"$AGENT/themes/t1.json","collision":null}],
 "contextFiles":[{"path":"/work/AGENTS.md","body":{"text":"# a","truncated":false,"size":3}}],
 "systemPrompt":"/work/.pi/SYSTEM.md",
 "appendSystemPrompt":["$AGENT/APPEND_SYSTEM.md"]
}
""".trimIndent()

private fun item(type: Type, path: String, name: String = path.substringAfterLast('/'), enabled: Boolean = true, override: PiResourceCatalog.Override = PiResourceCatalog.Override.Inherit) =
    PiResourceCatalog.Item(type, path, name, enabled, null, override, false, null)

private fun group(key: String, kind: PiResourceCatalog.SourceKind, projectScoped: Boolean, items: List<PiResourceCatalog.Item>, label: String = key) =
    PiResourceCatalog.Group(key, label, kind, projectScoped, key, items)

fun main() {
    val loaded = PiLoadedResources.parse(obj(LOADED_JSON))

    // ---- 解析 ----
    check("扩展：注册了什么都解出来", loaded.extensions[0].commands.single().name == "probe-ping" && loaded.extensions[0].tools.single().name == "probe_tool" &&
        loaded.extensions[0].flags.map { it.isBoolean } == listOf(false, true) && loaded.extensions[0].registered == 4)
    check("扩展来源：scope 与 origin", loaded.extensions[0].source?.scope == PiLoadedResources.Scope.Temporary && loaded.extensions[0].source?.origin == PiLoadedResources.Origin.TopLevel)
    check("隐藏的内置扩展不给用户看", loaded.visibleExtensions.map { it.path } == listOf("/ext/probe.mjs", "builtin:codemode"))
    check("技能：描述 / 仅手动 / 来源", loaded.skills[0].description == "用户级技能" && !loaded.skills[0].disableModelInvocation && loaded.skills[1].disableModelInvocation &&
        loaded.skills[0].source?.scope == PiLoadedResources.Scope.User && loaded.skills[1].source == null)
    check("提示：参数提示与被截断的预览", loaded.prompts[0].argumentHint == "<x>" && loaded.prompts[0].body.truncated && loaded.prompts[0].body.size == 9000L)
    check("诊断三类各归各", loaded.skillDiagnostics.single().kind == DiagKind.Collision && loaded.themeDiagnostics.single().kind == DiagKind.Error)
    check("问题总数 = 诊断 + 扩展错误 + 扩展警告", loaded.problemCount == 3, loaded.problemCount.toString())
    check("没有的字段不炸：空输入 = 空读数", PiLoadedResources.parse(obj("{}")) == PiLoadedResources.Loaded.EMPTY)
    check("JSON null 当成没有", PiLoadedResources.parse(obj("""{"systemPrompt":null,"extensions":[{"path":"p","description":null}]}""")).systemPrompt == null)

    // ---- 清单与实际加载按路径对上 ----
    val skillOn = item(Type.Skills, "$AGENT/skills/user-skill/SKILL.md", "user-skill")
    val skillOff = item(Type.Skills, "$AGENT/skills/old/SKILL.md", "old", enabled = false)
    val skillGhost = item(Type.Skills, "$AGENT/skills/ghost/SKILL.md", "ghost")
    check("技能对上：描述与 /skill: 标签", infoFor(skillOn, true, loaded).let { it.loaded && it.description == "用户级技能" && it.tags == listOf("/skill:user-skill") })
    check("仅手动调用的技能有标记且没有描述不写空串", infoFor(item(Type.Skills, "$AGENT/skills/manual/SKILL.md"), true, loaded).let { it.description == null && "仅手动调用" in it.tags })
    check("关掉的资源没有「未加载」标签（本来就不该加载）", infoFor(skillOff, false, loaded).tags.isEmpty() && !infoFor(skillOff, false, loaded).loaded)
    check("开着却没加载 = 「未加载」，让人去看问题", infoFor(skillGhost, true, loaded).tags == listOf("未加载"))
    check("扩展：N 命令 · N 工具 · N 参数", infoFor(item(Type.Extensions, "/ext/probe.mjs"), true, loaded).tags == listOf("1 命令", "1 工具", "2 参数"))
    check("内置扩展按 builtin: 名字对上；只注册事件的写「监听事件」", infoFor(item(Type.Extensions, "builtin:mcp"), true, loaded).tags == listOf("监听事件"))
    check("什么都没注册也没事件 = 没有标签", infoFor(item(Type.Extensions, "builtin:codemode"), true, loaded).tags.isEmpty())
    check("提示：/名字 与参数提示", infoFor(item(Type.Prompts, "$AGENT/prompts/p1.md"), true, loaded).tags == listOf("/p1", "<x>"))

    // ---- 概览 ----
    val snap = PiResourceCatalog.Snapshot(
        trusted = true,
        global = listOf(
            group("pkg", PiResourceCatalog.SourceKind.Package, false, listOf(skillOn, skillOff)),
            group("builtin", PiResourceCatalog.SourceKind.Builtin, false, listOf(item(Type.Extensions, "builtin:mcp", "mcp"), item(Type.Extensions, "builtin:codemode", "codemode", enabled = false))),
        ),
        project = listOf(group("proj", PiResourceCatalog.SourceKind.Directory, true, listOf(item(Type.Skills, "/w/.pi/skills/p/SKILL.md", "p", enabled = true, override = PiResourceCatalog.Override.Unload)))),
        failures = listOf("npm:gone（没装好）"),
    )
    val ov = overview(loaded, snap, Layer.Global)
    check("概览：已加载数来自实际加载，不是清单", ov.loaded[Type.Extensions] == 2 && ov.loaded[Type.Skills] == 2 && ov.loaded[Type.Prompts] == 1 && ov.loaded[Type.Themes] == 0)
    check("概览：已关闭数来自清单", ov.disabled[Type.Skills] == 1 && ov.disabled[Type.Extensions] == 1 && ov.disabled[Type.Prompts] == 0)
    check("概览：项目层按覆盖算（被屏蔽也算关）", overview(loaded, snap, Layer.Project).disabled[Type.Skills] == 1)
    check("概览：问题与没装上的包", ov.problems == 3 && ov.installFailures == 1 && ov.contextFiles == 1)
    check("没读到清单时概览不炸", overview(loaded, null, Layer.Global).let { it.installFailures == 0 && it.disabled.values.all { n -> n == 0 } })
    check("类型副标题", typeSummary(Type.Skills, ov) == "2 个已加载 · 1 个已关闭" && typeSummary(Type.Prompts, ov) == "1 个已加载" && typeSummary(Type.Themes, ov) == "没有")
    check("只有关闭没有加载", typeSummary(Type.Skills, ov.copy(loaded = mapOf(Type.Skills to 0))) == "1 个已关闭")

    // ---- 分段 ----
    val secs = sections(Type.Skills, snap, Layer.Global, loaded, "")
    check("只留这一类、没有该类的组不出现", secs.size == 1 && secs[0].group.key == "pkg" && secs[0].rows.map { it.item.name } == listOf("old", "user-skill"))
    check("行带有效状态与补充信息", secs[0].rows.first { it.item.name == "user-skill" }.let { it.effective && it.info.loaded } && !secs[0].rows.first { it.item.name == "old" }.effective)
    check("搜索匹配描述", sections(Type.Skills, snap, Layer.Global, loaded, "用户级").single().rows.single().item.name == "user-skill")
    check("搜索匹配路径、多词不分顺序", sections(Type.Skills, snap, Layer.Global, loaded, "skill user").isNotEmpty())
    check("没命中 = 没有段", sections(Type.Skills, snap, Layer.Global, loaded, "zzz").isEmpty())
    val mixed = PiResourceCatalog.Snapshot(true, listOf(
        group("b", PiResourceCatalog.SourceKind.Builtin, false, listOf(item(Type.Skills, "/b"))),
        group("d", PiResourceCatalog.SourceKind.Directory, false, listOf(item(Type.Skills, "/d"))),
        group("s", PiResourceCatalog.SourceKind.Settings, false, listOf(item(Type.Skills, "/s"))),
        group("p", PiResourceCatalog.SourceKind.Package, false, listOf(item(Type.Skills, "/p"))),
    ), emptyList(), emptyList())
    check("段顺序：包 → 设置里列的 → 目录 → 内置", sections(Type.Skills, mixed, Layer.Global, loaded, "").map { it.group.key } == listOf("p", "s", "d", "b"))
    val projSecs = sections(Type.Skills, snap, Layer.Project, loaded, "")
    check("项目视图：全局的段标灰，项目的不标", projSecs.single().inherited.not() && projSecs.single().group.projectScoped)
    check("项目视图里的全局段是继承", sections(Type.Skills, PiResourceCatalog.Snapshot(true, emptyList(), snap.global, emptyList()), Layer.Project, loaded, "").all { it.inherited })

    // ---- 诊断 ----
    check("冲突说清谁赢谁输", describeDiagnostic(loaded.skillDiagnostics.single()) == "技能「user-skill」重名：用了 …/c/d/SKILL.md，忽略了 /e/SKILL.md", describeDiagnostic(loaded.skillDiagnostics.single()))
    check("普通诊断带路径", describeDiagnostic(loaded.themeDiagnostics.single()) == "invalid theme（…/agent/themes/t1.json）", describeDiagnostic(loaded.themeDiagnostics.single()))
    check("扩展的加载错误也走同一套", diagnosticsFor(Type.Extensions, loaded).single().kind == DiagKind.Error && describeDiagnostic(diagnosticsFor(Type.Extensions, loaded).single()) == "SyntaxError（/ext/bad.mjs）")
    check("每类诊断归每类", diagnosticsFor(Type.Skills, loaded).size == 1 && diagnosticsFor(Type.Prompts, loaded).isEmpty() && diagnosticsFor(Type.Themes, loaded).size == 1)
    check("诊断标签", DiagKind.entries.map { diagnosticLabel(it) }.toSet().size == 3)
    check("短路径：不超过三段原样；超过留末三段", shortPath("/a/b/c") == "/a/b/c" && shortPath("/a/b/c/d") == "…/b/c/d")

    // ---- 上下文 ----
    check("上下文行的顺序（SDK 拼系统提示的顺序）：替换 → 追加 → 文件", contextRows(loaded).map { it.role } == listOf("替换系统提示", "追加到系统提示", "上下文文件"))
    check("上下文文件带大小", contextRows(loaded)[2].size == 3L && contextRows(PiLoadedResources.Loaded.EMPTY).isEmpty())

    // ---- 项目信任 ----
    fun trust(source: String, trusted: Boolean, undecided: Boolean = false, entry: String = "null", needs: Boolean = true, dflt: String = "ask", parent: String? = "/w") =
        PiProjectTrust.parse(obj("""{"cwd":"/w/p","trusted":$trusted,"undecided":$undecided,"source":"$source","entry":$entry,"needsTrust":$needs,"defaultProjectTrust":"$dflt","parent":${if (parent == null) "null" else "\"$parent\""}}"""))
    val ask = trust("ask", false, undecided = true)
    check("解析：来源与字段", ask.source == PiProjectTrust.Source.Ask && ask.undecided && !ask.trusted && ask.needsTrust && ask.parent == "/w" && ask.entry == null)
    check("解析：信任库记录与继承", trust("store", true, entry = """{"path":"/w","decision":true,"inherited":true}""").entry == PiProjectTrust.Entry("/w", true, true))
    check("未知来源不炸", PiProjectTrust.parse(obj("""{"source":"future"}""")).source == PiProjectTrust.Source.Unknown)
    check("没表态：先不信任，要说清此刻没加载", PiProjectTrust.headline(ask) == "还没表态" && PiProjectTrust.explanation(ask).contains("没有加载"))
    check("官方弹窗的五个选项，顺序一致", PiProjectTrust.promptOptions(ask).map { it.remember to it.trusted } ==
        listOf(Remember.Folder to true, Remember.Parent to true, Remember.Session to true, Remember.Folder to false, Remember.Session to false))
    check("根目录没有「信任上层目录」", PiProjectTrust.promptOptions(trust("ask", false, parent = null)).none { it.remember == Remember.Parent })
    check("信任的结论与记录位置", PiProjectTrust.headline(trust("store", true, entry = """{"path":"/w/p","decision":true,"inherited":false}""")) == "已信任" &&
        PiProjectTrust.recordNote(trust("store", true, entry = """{"path":"/w/p","decision":true,"inherited":false}""")) == "记录在本项目")
    val inh = trust("store", true, entry = """{"path":"/w","decision":true,"inherited":true}""")
    check("继承的信任要说是继承的", PiProjectTrust.headline(inh).contains("继承") && PiProjectTrust.recordNote(inh)!!.contains("/w"))
    check("不信任也分继承", PiProjectTrust.headline(trust("store", false, entry = """{"path":"/w","decision":false,"inherited":true}""")) == "不信任（继承自上层目录）")
    check("没有需要信任的东西", PiProjectTrust.headline(trust("nothing-to-trust", true, needs = false)) == "没有需要信任的内容")
    check("默认策略两个方向", PiProjectTrust.headline(trust("default", true, dflt = "always")).contains("总是信任") && PiProjectTrust.headline(trust("default", false, dflt = "never")).contains("从不信任"))
    check("仅本次：说清没写信任库", PiProjectTrust.headline(trust("session", true)) == "本次会话信任" && PiProjectTrust.explanation(trust("session", true)).contains("没有写进信任库"))
    check("信任的后果含「执行项目里的扩展代码」", PiProjectTrust.explanation(trust("store", true, entry = """{"path":"/w/p","decision":true,"inherited":false}""")).contains("执行项目里的扩展代码"))
    val own = trust("store", true, entry = """{"path":"/w/p","decision":true,"inherited":false}""")
    check("调整：去掉「当前就是这样」，加上清除记录", PiProjectTrust.adjustments(own).none { it.remember == Remember.Folder && it.trusted } &&
        PiProjectTrust.adjustments(own).any { it.remember == Remember.Folder && !it.trusted } && PiProjectTrust.adjustments(own).last().remember == Remember.Reset)
    check("调整：继承的给「清除上层目录的记录」并警告影响面", PiProjectTrust.adjustments(inh).last().let { it.remember == Remember.ResetInherited && it.hint!!.contains("所有没单独表态") })
    check("调整：仅本次当前态不再列出", PiProjectTrust.adjustments(trust("session", true)).none { it.remember == Remember.Session && it.trusted })
    check("调整：没有记录就没有清除项", PiProjectTrust.adjustments(trust("default", true, dflt = "always")).none { it.remember == Remember.Reset || it.remember == Remember.ResetInherited })
    check("remember 的 key 与宿主一致", Remember.entries.map { it.key } == listOf("folder", "parent", "session", "reset", "reset-inherited"))

    // ---- 扩展包来源（解析是 SDK parseSource，这里只说人话） ----
    val npmRange = sourceView("npm:@a/b@^1.2.0", "npm", "@a/b", "^1.2.0", false)
    check("npm 版本范围：不是固定，更新不跳过", npmRange.headline == "npm 包 @a/b" && npmRange.tags == listOf("版本范围 ^1.2.0") && !npmRange.pinned && npmRange.warning == null)
    val npmPinned = sourceView("npm:x@1.2.3", "npm", "x", "1.2.3", true)
    check("npm 精确版本：固定，标出更新会跳过", npmPinned.pinned && npmPinned.tags == listOf("固定 @1.2.3", "更新会跳过"))
    check("npm 不带版本：没有版本标签", sourceView("npm:x", "npm", "x", null, false).tags.isEmpty())
    val gitPinned = sourceView("git:github.com/u/r@v1", "git", "github.com/u/r", "v1", true)
    check("git 带 ref：固定到 ref，更新会跳过", gitPinned.pinned && gitPinned.tags == listOf("固定到 v1", "更新会跳过") && gitPinned.headline == "git 仓库 github.com/u/r")
    check("git 没 ref：跟随默认分支", sourceView("git:github.com/u/r", "git", "github.com/u/r", null, false).tags == listOf("跟随默认分支"))
    val bare = sourceView("foo", "local", "foo", null, false)
    check("裸名字（没 npm: 前缀）：pi 当本地路径，要警告并给出正确写法", bare.warning?.contains("npm:foo") == true && bare.headline == "本地路径 foo")
    for (p in listOf("/sdcard/x", "./x", "../x", "~/x", "file:///x", "C:\\x")) check("本地路径写法「$p」不警告", sourceView(p, "local", p, null, false).warning == null)
    check("无法识别：给出两种正确写法", sourceView("???", "unknown", null, null, false).warning?.contains("npm:") == true)
    check("包的标签：层级 + 种类 + 过滤 + 没装好", packageTags("project", true, false, npmPinned) == listOf("本项目", "固定 @1.2.3", "更新会跳过", "只加载其中一部分", "没装好"))
    check("looksLikePath：裸名字和 npm: 都不是路径", !looksLikePath("foo") && !looksLikePath("npm:foo") && !looksLikePath("a/b"))

    if (failures > 0) { println("\nharness: FAILED ($failures)"); System.exit(1) }
    println("\nharness: OK (all checks passed)")
}
