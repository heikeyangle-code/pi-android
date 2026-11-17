package app.pi.packages

// Pure-logic checks for `PiMcpConfig` (pi 的 mcp.json 编辑)。跑法见 tools/run-app-pure-checks.sh。
// 钉住的是「不弄丢用户内容」与 docs/mcp.md 的规则：未知键保留、损坏文件不覆盖、
// - 与 _ 视为同名、command 不是 shell 串、sse 被拒、OAuth 与 Authorization 互斥、
// 以及从别的客户端导入时的转换。

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

fun main() {
    // ---------------------------------------------------------------- 名字
    check("合法名字", PiMcpConfig.checkName("my-server_1", emptyList()), null)
    check("空名字被拒", PiMcpConfig.checkName("", emptyList()) != null, true)
    check("空格被拒", PiMcpConfig.checkName("a b", emptyList()) != null, true)
    check("点号被拒", PiMcpConfig.checkName("a.b", emptyList()) != null, true)
    check("- 与 _ 视为同名", PiMcpConfig.checkName("dev-radius", listOf("dev_radius")) != null, true)
    check("完全同名被拒", PiMcpConfig.checkName("x", listOf("x")) != null, true)
    check("编辑自己时不算冲突（调用方排除自己）", PiMcpConfig.checkName("x", listOf("y")), null)

    // ---------------------------------------------------------------- 字段
    check("command 不能是 shell 串", PiMcpConfig.checkCommand("npx -y foo") != null, true)
    check("带路径的 command 放行", PiMcpConfig.checkCommand("/data/my tool/bin") , null)
    check("单个可执行文件放行", PiMcpConfig.checkCommand("npx"), null)
    check("sse 端点被拒", PiMcpConfig.checkUrl("https://a.com/sse") != null, true)
    check("/mcp 放行", PiMcpConfig.checkUrl("https://a.com/mcp"), null)
    check("非 http 被拒", PiMcpConfig.checkUrl("ftp://a.com/mcp") != null, true)
    check("超时空 = 默认", PiMcpConfig.checkTimeout(""), null)
    check("超时 0 被拒", PiMcpConfig.checkTimeout("0") != null, true)
    check("超时非数字被拒", PiMcpConfig.checkTimeout("abc") != null, true)
    check("环境变量解析", PiMcpConfig.parseEnv("A=1\nB=x=y").map, mapOf("A" to "1", "B" to "x=y"))
    check("环境变量缺 = 被拒", PiMcpConfig.parseEnv("A").error != null, true)
    check("请求头解析", PiMcpConfig.parseHeaders("X-A: b: c").map, mapOf("X-A" to "b: c"))
    check("工具暴露值非法被拒", PiMcpConfig.parseToolExposure("a=bogus").error != null, true)
    check("工具暴露合法", PiMcpConfig.parseToolExposure("get_*=codemode\nx=hidden").error, null)

    // ---------------------------------------------------------------- 往返与保留未知键
    val original = obj(
        """{"autoEnableCodemode":false,"mcpServers":{"a":{"command":"npx","args":["-y","pkg"],"cwd":"~/w","futureKey":{"k":1},"enabled":false},"b":{"url":"https://x/mcp"}}}""",
    )
    val doc = PiMcpConfig.parse(PiMcpConfig.render(original))
    val a = doc.server("a")!!
    check("读出两条", doc.servers.map { it.name }, listOf("a", "b"))
    check("a 是命令式且已关", a.kind to a.enabled, PiMcpConfig.Kind.Stdio to false)
    check("b 是网址式且默认开", doc.server("b")!!.kind to doc.server("b")!!.enabled, PiMcpConfig.Kind.Http to true)
    val draft = PiMcpConfig.toDraft(a)
    check("表单参数一行一个", draft.args, "-y\npkg")
    val edited = PiMcpConfig.toConfig(draft.copy(description = "d"), a.config)
    check("编辑保留未知键", edited["futureKey"], obj("""{"k":1}""")["k"]?.let { obj("""{"k":1}""") })
    check("编辑保留 enabled", (edited["enabled"] as JsonPrimitive).content, "false")
    check("编辑保留 cwd", (edited["cwd"] as JsonPrimitive).content, "~/w")
    val after = PiMcpConfig.upsert(original, "a", "a", edited)
    check("根上其他键保留", after["autoEnableCodemode"], JsonPrimitive(false))
    check("改写后键序不变", (after["mcpServers"] as JsonObject).keys.toList(), listOf("a", "b"))
    val renamed = PiMcpConfig.upsert(original, "a", "z", edited)
    check("改名原位替换", (renamed["mcpServers"] as JsonObject).keys.toList(), listOf("z", "b"))
    check("删除", (PiMcpConfig.remove(original, "a")["mcpServers"] as JsonObject).keys.toList(), listOf("b"))

    // 换类型：命令式 → 网址式要清掉 command/args/cwd
    val toHttp = PiMcpConfig.toConfig(draft.copy(http = true, url = "https://h/mcp"), a.config)
    check("换网址式清掉 command", toHttp["command"], null)
    check("换网址式清掉 args", toHttp["args"], null)
    check("换网址式清掉 cwd", toHttp["cwd"], null)
    check("网址写入", (toHttp["url"] as JsonPrimitive).content, "https://h/mcp")

    // ---------------------------------------------------------------- 开关与暴露
    val off = PiMcpConfig.setEnabled(original, "b", false)
    check("关 = enabled:false", ((off["mcpServers"] as JsonObject)["b"] as JsonObject)["enabled"], JsonPrimitive(false))
    val on = PiMcpConfig.setEnabled(off, "b", true)
    check("开 = 删键", ((on["mcpServers"] as JsonObject)["b"] as JsonObject).containsKey("enabled"), false)
    check("默认暴露不写键", PiMcpConfig.toConfig(PiMcpConfig.Draft(name = "n", command = "npx"), null).containsKey("exposure"), false)
    check("direct 写键", (PiMcpConfig.toConfig(PiMcpConfig.Draft(name = "n", command = "npx", exposure = "direct"), null)["exposure"] as JsonPrimitive).content, "direct")
    check("codemode-deferred 当 codemode", PiMcpConfig.normalizeExposure("codemode-deferred"), "codemode")
    check("autoEnableCodemode 缺省为开", PiMcpConfig.autoEnableCodemode(obj("{}")), true)
    check("autoEnableCodemode:false 为关", PiMcpConfig.autoEnableCodemode(original), false)
    check("重新打开 = 删键", PiMcpConfig.setAutoEnableCodemode(original, true).containsKey("autoEnableCodemode"), false)

    // ---------------------------------------------------------------- 项目覆盖
    val ov = PiMcpConfig.setProjectOverride(obj("""{"mcpServers":{}}"""), "g", enabled = false, globalEnabled = true)
    check("项目里关全局的 = 写一条只含 enabled 的覆盖", (ov["mcpServers"] as JsonObject)["g"], obj("""{"enabled":false}"""))
    check("覆盖条目被识别为 Override", PiMcpConfig.parse(PiMcpConfig.render(ov)).server("g")!!.kind, PiMcpConfig.Kind.Override)
    val back = PiMcpConfig.setProjectOverride(ov, "g", enabled = true, globalEnabled = true)
    check("与全局一致时收回覆盖", (back["mcpServers"] as JsonObject).containsKey("g"), false)

    // ---------------------------------------------------------------- 令牌 / OAuth
    val withToken = PiMcpConfig.toConfig(PiMcpConfig.Draft(name = "d", http = true, url = "https://d/mcp", bearer = "\${DOCS_TOKEN}"), null)
    check("令牌写成 Bearer 头", ((withToken["headers"] as JsonObject)["Authorization"] as JsonPrimitive).content, "Bearer \${DOCS_TOKEN}")
    val cmdToken = PiMcpConfig.toConfig(PiMcpConfig.Draft(name = "d", http = true, url = "https://d/mcp", bearer = "!echo Bearer x"), null)
    check("! 命令整条作为值", ((cmdToken["headers"] as JsonObject)["Authorization"] as JsonPrimitive).content, "!echo Bearer x")
    val tokenBack = PiMcpConfig.toDraft(PiMcpConfig.Server("d", withToken))
    check("令牌读回令牌栏", tokenBack.bearer to tokenBack.headers, "\${DOCS_TOKEN}" to "")
    check(
        "令牌与 OAuth 互斥",
        PiMcpConfig.check(PiMcpConfig.Draft(name = "d", http = true, url = "https://d/mcp", bearer = "t", oauthScope = "x"), emptyList()) != null,
        true,
    )
    check(
        "CIMD 不能带客户端 ID",
        PiMcpConfig.check(PiMcpConfig.Draft(name = "d", http = true, url = "https://d/mcp", oauthCimd = true, oauthClientId = "x"), emptyList()) != null,
        true,
    )
    check(
        "回调地址必须是回环 http",
        PiMcpConfig.check(PiMcpConfig.Draft(name = "d", http = true, url = "https://d/mcp", oauthCallbackUrl = "https://evil.com/callback"), emptyList()) != null,
        true,
    )
    check(
        "回调端口范围",
        PiMcpConfig.check(PiMcpConfig.Draft(name = "d", http = true, url = "https://d/mcp", oauthCallbackPort = "70000"), emptyList()) != null,
        true,
    )
    val oauthCfg = PiMcpConfig.toConfig(
        PiMcpConfig.Draft(name = "d", http = true, url = "https://d/mcp", oauthClientId = "cid", oauthCallbackPort = "8765"),
        obj("""{"url":"https://d/mcp","oauth":{"unknownSub":true}}"""),
    )
    val oauthObj = oauthCfg["oauth"] as JsonObject
    check("OAuth 保留未知子键", oauthObj["unknownSub"], JsonPrimitive(true))
    check("OAuth 端口写成数字", oauthObj["callbackPort"], JsonPrimitive(8765))

    // ---------------------------------------------------------------- 导入
    val claude = PiMcpConfig.importJson(
        obj("""{"mcpServers":{"keep":{"command":"x"}}}"""),
        """{"mcpServers":{"fs":{"command":"npx","args":["-y","p"]},"keep":{"command":"y"},"bad name":{"url":"https://a/mcp"},"old":{"url":"https://a/sse"}}}""",
        "imported",
    )
    check("导入：新增", claude.added, listOf("fs", "bad_name"))
    check("导入：同名与 sse 跳过", claude.skipped.size, 2)
    check("导入：同名不覆盖", (((claude.root["mcpServers"] as JsonObject)["keep"]) as JsonObject)["command"], JsonPrimitive("x"))
    val vscode = PiMcpConfig.importJson(obj("{}"), """{"servers":{"a":{"command":"c","env":{"K":"${'$'}{input:key}"}}}}""", "i")
    check("VS Code 的 input 变量被跳过并说明", vscode.added.isEmpty() && vscode.skipped.size == 1, true)
    val opencode = PiMcpConfig.importJson(
        obj("{}"),
        """{"mcpServers":{"o":{"type":"local","command":["npx","-y","pkg"],"environment":{"T":"{env:TOK}"}}}}""",
        "i",
    )
    val oc = (opencode.root["mcpServers"] as JsonObject)["o"] as JsonObject
    check("OpenCode：command 数组拆开", oc["command"] to oc["args"], JsonPrimitive("npx") to JsonArray(listOf(JsonPrimitive("-y"), JsonPrimitive("pkg"))))
    check("OpenCode：environment → env 且变量改写", ((oc["env"] as JsonObject)["T"] as JsonPrimitive).content, "\${TOK}")
    check("OpenCode：type 去掉", oc.containsKey("type"), false)
    val single = PiMcpConfig.importJson(obj("{}"), """{"command":"npx","args":["a"]}""", "solo")
    check("裸单条用后备名", single.added, listOf("solo"))
    check("非 JSON 报错", PiMcpConfig.importJson(obj("{}"), "nope", "i").error != null, true)

    // ---------------------------------------------------------------- 文件
    val dir = File(System.getProperty("java.io.tmpdir"), "mcp-check-${System.nanoTime()}").apply { mkdirs() }
    val file = PiMcpFile(File(dir, "mcp.json"))
    check("文件不存在 = 空且不算损坏", file.read().let { it.servers.isEmpty() && !it.corrupt }, true)
    check("首次写入成功", file.update { PiMcpConfig.upsert(it, null, "s", obj("""{"command":"npx"}""")) }, null)
    check("写入后读得到", file.read().servers.map { it.name }, listOf("s"))
    check("文件以换行结尾", File(dir, "mcp.json").readText().endsWith("\n"), true)
    File(dir, "mcp.json").writeText("{ not json")
    check("损坏文件被识别", file.read().corrupt, true)
    check("损坏文件拒绝写入", file.update { it } != null, true)
    check("损坏文件内容原样保留", File(dir, "mcp.json").readText(), "{ not json")
    check("没有遗留临时文件", dir.listFiles()!!.map { it.name }, listOf("mcp.json"))
    dir.deleteRecursively()

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
