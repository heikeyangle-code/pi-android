package app.pi.host

import app.pi.rpc.ExtensionFlagArg
import app.pi.rpc.PiLaunchOptions
import kotlin.system.exitProcess

// PiLaunchOptions → session.open 参数。旧路径是命令行 flag，这里钉住「同样的设置，宿主收到同样的意思」。
private var failures = 0
private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name")
    else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}

fun main() {
    check("默认：什么都不发", PiLaunchOptions().toOpenArgs().toJson().toString(), "{}")
    val all = PiLaunchOptions(
        noExtensions = true, noSkills = true, noPromptTemplates = true, noThemes = true, noContextFiles = true,
        extensionFlags = listOf(ExtensionFlagArg("plan", null), ExtensionFlagArg("model-x", "a b")),
    ).toOpenArgs(sessionDir = "/s", trust = false).toJson()
    check("资源开关进 resources", all["resources"].toString(), """{"noExtensions":true,"noSkills":true,"noPromptTemplates":true,"noThemes":true,"noContextFiles":true}""")
    check("扩展 flag：无值=true，有值=字符串（含空格不用转义）", all["flags"].toString(), """{"plan":true,"model-x":"a b"}""")
    check("sessionDir 与 trust", all["sessionDir"].toString() + all["trust"].toString(), "\"/s\"false")
    val byPath = PiLaunchOptions(resumeSessionPath = "/s/a.jsonl", continueSessionId = "id1", continueMostRecent = true).toOpenArgs().toJson()
    check("给了路径：只按路径开（不再按 id）", byPath["session"].toString(), """{"file":"/s/a.jsonl"}""")
    check("没路径有 id：按 id", PiLaunchOptions(continueSessionId = "id1", continueMostRecent = true).toOpenArgs().toJson()["session"].toString(), """{"id":"id1"}""")
    check("都没有：续最近", PiLaunchOptions(continueMostRecent = true).toOpenArgs().toJson()["session"].toString(), """{"continue":true}""")
    if (failures > 0) { println("harness: FAILED ($failures)"); exitProcess(1) }
    println("harness: OK (host-launch)")
}
