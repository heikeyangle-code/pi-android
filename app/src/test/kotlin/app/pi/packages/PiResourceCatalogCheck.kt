package app.pi.packages

// Pure-logic checks for `PiResourceCatalog`（解析 pi-resources.mjs 的输出）。跑法见 tools/run-app-pure-checks.sh。

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private const val LIST = """
(node:1) ExperimentalWarning: noise
{"trusted":true,"global":[{"key":"k1","label":"npm:foo (user)","origin":"package","scope":"user","source":"npm:foo","subgroups":[{"type":"extensions","items":[{"path":"/p/a.ts","name":"a.ts","enabled":false,"baseDir":"/p"}]},{"type":"skills","items":[{"path":"/p/s/SKILL.md","name":"s","enabled":true,"baseDir":"/p"}]}]},{"key":"k2","label":"Built-in","origin":"top-level","scope":"user","source":"builtin","subgroups":[{"type":"extensions","items":[{"path":"builtin:mcp","name":"mcp","enabled":true,"baseDir":null}]}]}],"project":[{"key":"k3","label":"Project (.pi/)","origin":"top-level","scope":"project","source":"auto","subgroups":[{"type":"prompts","items":[{"path":"/w/.pi/prompts/x.md","name":"x.md","enabled":true,"baseDir":"/w/.pi","override":"unload","inherited":false,"inheritedEnabled":null}]}]}],"installFailures":["npm:bad: 404"]}
"""

fun main() {
    val ok = PiResourceCatalog.parseSnapshot(LIST) as PiResourceCatalog.Parsed.Ok
    val snap = ok.value
    check("trusted", snap.trusted, true)
    check("全局两组", snap.global.size, 2)
    check("包组类型", snap.global[0].kind, PiResourceCatalog.SourceKind.Package)
    check("内置组类型", snap.global[1].kind, PiResourceCatalog.SourceKind.Builtin)
    check("目录组类型", snap.project[0].kind, PiResourceCatalog.SourceKind.Directory)
    check("项目组标记", snap.project[0].projectScoped, true)
    check("包里扩展 1 条", snap.global[0].items(PiResourceCatalog.Type.Extensions).size, 1)
    check("关闭的条目", snap.global[0].items[0].enabled, false)
    check("覆盖：屏蔽", snap.project[0].items[0].override, PiResourceCatalog.Override.Unload)
    check("覆盖缺省=继承", snap.global[0].items[0].override, PiResourceCatalog.Override.Inherit)
    check("安装失败", snap.failures, listOf("npm:bad: 404"))
    check("项目层屏蔽即关", PiResourceCatalog.effectiveEnabled(snap.project[0].items[0], PiResourceCatalog.Layer.Project), false)
    check("全局层看 enabled", PiResourceCatalog.effectiveEnabled(snap.global[0].items[0], PiResourceCatalog.Layer.Global), false)
    check("脚本报错", PiResourceCatalog.parseSnapshot("""{"error":"boom"}""") , PiResourceCatalog.Parsed.Error("boom"))
    check("没有 JSON", PiResourceCatalog.parseSnapshot("garbage") is PiResourceCatalog.Parsed.Error, true)
    val t = PiResourceCatalog.parseToggle("""{"ok":true,"enabled":null,"override":"load"}""") as PiResourceCatalog.Parsed.Ok
    check("开关结果", t.value, PiResourceCatalog.Toggled(null, PiResourceCatalog.Override.Load))
    val t2 = PiResourceCatalog.parseToggle("""{"ok":true,"enabled":false,"override":null,"trusted":true,"global":[{"key":"k","label":"L","origin":"top-level","scope":"user","source":"builtin","subgroups":[{"type":"extensions","items":[{"path":"builtin:mcp","name":"mcp","enabled":false,"baseDir":null}]}]}],"project":[],"installFailures":[]}""") as PiResourceCatalog.Parsed.Ok
    check("开关结果带新清单", t2.value.snapshot?.global?.single()?.items?.single()?.enabled, false)
    val u = PiResourceCatalog.parseUpdates("""noise
{"updates":[{"source":"npm:foo","name":"foo","type":"npm","scope":"user"}]}""") as PiResourceCatalog.Parsed.Ok
    check("可更新", u.value, listOf(PiResourceCatalog.Update("npm:foo", "foo", "npm", "user")))
    check("无更新", (PiResourceCatalog.parseUpdates("""{"updates":[]}""") as PiResourceCatalog.Parsed.Ok).value, emptyList<PiResourceCatalog.Update>())

    // 与其它 harness 同一条收尾行：`tools/run-app-pure-checks.sh` 认的是
    // `harness: OK`，没有它就算 exit 0 也判失败（第一次合并这里就只印了 PASS 行）。
    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
