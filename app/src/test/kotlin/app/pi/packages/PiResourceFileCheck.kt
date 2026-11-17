package app.pi.packages

// 裸 JVM harness：资源文件——宿主应答的解析、新建名字规则（与 SDK skills.js `validateName` 逐条对照，
// 宿主 `resources.file.create` 的 sdk-host 测试里是同一批用例）、写前预检（SKILL.md 缺 description 时 pi 不加载）。

import app.pi.packages.PiResourceFile.Creatable
import app.pi.packages.PiResourceFile.Kind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $name") else { failures++; println("FAIL $name $detail") }
}

fun main() {
    val doc = PiResourceFile.parseDoc(
        Json.parseToJsonElement(
            """{"path":"/a/skills/x/SKILL.md","kind":"skill","scope":"user","rel":"x/SKILL.md","text":"hi","size":2,"mtimeMs":1700000000123,"writable":true,"reason":null}""",
        ) as JsonObject,
    )
    check("解析：类型 / 层级 / 指纹 / 可写", doc.kind == Kind.Skill && doc.scope == PiLoadedResources.Scope.User && doc.size == 2L && doc.mtimeMs == 1700000000123L && doc.writable && doc.reason == null)
    val ro = PiResourceFile.parseDoc(Json.parseToJsonElement("""{"path":"/p","kind":null,"scope":null,"rel":"p","text":"","size":0,"mtimeMs":0,"writable":false,"reason":"来自包"}""") as JsonObject)
    check("解析：来自包的文件只读并带原因，类型未知", !ro.writable && ro.reason == "来自包" && ro.kind == Kind.Unknown)

    // 技能名：与 SDK validateName 同一批用例
    for ((n, bad) in listOf("made-skill" to false, "a" to false, "a1-b2" to false, "Bad" to true, "a--b" to true, "-a" to true, "a-" to true, "a b" to true, "" to true, "a_b" to true, "中文" to true, "a".repeat(65) to true, "a".repeat(64) to false)) {
        check("技能名「${n.take(8)}」${if (bad) "非法" else "合法"}", (PiResourceFile.skillNameError(n) != null) == bad)
    }
    for ((n, bad) in listOf("review" to false, "my.prompt" to false, "A_b-1" to false, "a b" to true, "a/b" to true, ".x" to true, "x." to true, "" to true, "../x" to true)) {
        check("文件名「$n」${if (bad) "非法" else "合法"}", (PiResourceFile.plainNameError(n) != null) == bad)
    }
    check("nameError 按类型选规则", PiResourceFile.nameError(Creatable.Skill, "Review") != null && PiResourceFile.nameError(Creatable.Prompt, "Review") == null)

    // frontmatter
    val fm = PiResourceFile.frontmatter("---\nname: x\ndescription: \"带引号\"\n  indented: no\n---\n正文: 不算")
    check("frontmatter：取 key: value、去引号、忽略缩进行与正文", fm == mapOf("name" to "x", "description" to "带引号"), fm.toString())
    check("frontmatter：没有围栏 → 空", PiResourceFile.frontmatter("# 标题\ndescription: x").isEmpty())
    check("frontmatter：围栏没闭合 → 空", PiResourceFile.frontmatter("---\ndescription: x\n").isEmpty())

    // 预检
    val ok = PiResourceFile.check(Kind.Skill, "x/SKILL.md", "---\nname: x\ndescription: 做事\n---\n")
    check("SKILL.md 合规：无提醒", ok.ok && ok.notices.isEmpty(), ok.toString())
    val noDesc = PiResourceFile.check(Kind.Skill, "x/SKILL.md", "---\nname: x\n---\n")
    check("缺 description：提醒 pi 不会加载（不拦保存，写到一半很常见）", noDesc.ok && noDesc.notices.any { it.contains("不会加载") }, noDesc.toString())
    val badName = PiResourceFile.check(Kind.Skill, "Bad/SKILL.md", "---\ndescription: d\n---\n")
    check("name 缺省取目录名，不合规给提醒", badName.notices.any { it.contains("Bad") }, badName.toString())
    check("技能目录里的其它文件不查", PiResourceFile.check(Kind.Skill, "x/references/a.md", "随便").notices.isEmpty())
    check("主题：非 JSON 拦住保存", !PiResourceFile.check(Kind.Theme, "t.json", "{").ok)
    check("主题：缺 name / colors 拦住保存", !PiResourceFile.check(Kind.Theme, "t.json", "{\"colors\":{}}").ok)
    check("主题：合格", PiResourceFile.check(Kind.Theme, "t.json", "{\"name\":\"t\",\"colors\":{\"a\":\"#fff\"}}").ok)
    check("扩展：只提醒是代码，不拦", PiResourceFile.check(Kind.Extension, "e.ts", "export default () => {").let { it.ok && it.notices.isNotEmpty() })
    check("提示 / 上下文：不查", PiResourceFile.check(Kind.Prompt, "p.md", "").ok && PiResourceFile.check(Kind.Context, "AGENTS.md", "").ok)

    if (failures > 0) { println("harness: $failures FAILED"); kotlin.system.exitProcess(1) }
    println("harness: OK (resource-file)")
}
