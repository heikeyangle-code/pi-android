package app.pi.host

import app.pi.rpc.PiJson
import app.pi.rpc.QueueMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

// `HostEngineApi` 的 bare-JVM harness，登记为 `host-api`。
//
// 它回答一个问题：**旧的响应读取器（`PiResponses.*`）读得懂新宿主的真数据吗？**
// 做法：把 `tools/sdk-host/record.mjs` 从真宿主 + 真 pi 录下的应答按命令名排好队，
// 假传输在客户端每发一条命令时就把对应的真应答喂回去，然后调 `HostEngineApi` 的每一个方法，
// 断言读出来的东西是对的 —— 不是「没抛」，是字段对。
// 数据形状对不上的话，ViewModel 里表现出来是「模型列表空了」「会话统计全 0」这种安静的错误，
// 所以必须在这里钉死。
//
// 不在这里的：真的有模型凭据时的应答（录的是无凭据的宿主，`model.set`/`session.compact` 成功路径没有样本）。

private var failures = 0
private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name")
    else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}
private fun flag(name: String, ok: Boolean, detail: String = "") = check(name + if (!ok && detail.isNotEmpty()) " ($detail)" else "", ok, true)

private fun obj(text: String): JsonObject = PiJson.parseObjectOrNull(text) ?: error("bad json: $text")

fun main() {
    val root = File(System.getProperty("pi.repo.root") ?: ".")
    val rows = root.resolve("app/src/test/resources/sdk-host/session.jsonl").readLines().filter { it.isNotBlank() }.map { obj(it) }

    // 录制里：app 行给出 id→命令名，host 行给出该 id 的应答。按命令名排队。
    val idToCmd = HashMap<String, String>()
    val replies = HashMap<String, ArrayDeque<JsonObject>>()
    for (row in rows) {
        val line = obj((row["line"] as JsonPrimitive).content)
        if ((row["dir"] as JsonPrimitive).content == "app") {
            idToCmd[(line["id"] as JsonPrimitive).content] = (line["cmd"] as JsonPrimitive).content
        } else if ((line["t"] as? JsonPrimitive)?.content == "res") {
            val cmd = idToCmd[(line["id"] as JsonPrimitive).content] ?: continue
            replies.getOrPut(cmd) { ArrayDeque() }.addLast(line)
        }
    }

    lateinit var client: PiHostClient
    client = PiHostClient({ sent ->
        val frame = obj(sent)
        val id = (frame["id"] as JsonPrimitive).content
        val cmd = (frame["cmd"] as JsonPrimitive).content
        val next = replies[cmd]?.removeFirstOrNull()
            ?: error("录制里没有（更多的）$cmd 应答 —— 重录：node tools/sdk-host/record.mjs")
        client.onLine(JsonObject(next + ("id" to JsonPrimitive(id))).toString())
    })
    val cmds = HostCommands(client)
    val api = HostEngineApi(cmds)

    runBlocking {
        // 读
        val state = api.getState()
        flag("getState：sessionId / 会话文件", state.sessionId != null && state.sessionFile != null)
        flag("getState：messageCount 来自 messageCounts.total", state.messageCount >= 0)
        check("getState：不在流式", state.isStreaming, false)
        flag("getState：模型被解析", state.model != null, "快照里的 model 只有 provider/id/name/reasoning —— 读取器要的更多就在这里暴露")

        val msgs = api.getMessages()
        flag("getMessages：有消息", msgs.isNotEmpty(), "${msgs.size}")
        val entries = api.getEntries()
        flag("getEntries：有条目和 leafId", entries.entries.isNotEmpty() && entries.leafId != null)
        val tree = api.getTree()
        flag("getTree：有节点", tree.tree.isNotEmpty())
        val forks = api.getForkMessages()
        check("getForkMessages：那一条 hello", forks.map { it.text }, listOf("hello"))
        check("getLastAssistantText：没有回答时为 null", api.getLastAssistantText(), null)
        val stats = api.getSessionStats()
        check("getSessionStats：一条用户消息", stats.userMessages, 1)
        val models = api.getAvailableModels()
        flag("getAvailableModels：非空且带 provider", models.isNotEmpty() && models.all { it.provider != null })
        check("getAvailableThinkingLevels", api.getAvailableThinkingLevels(), listOf("off", "minimal", "low", "medium", "high", "max"))
        val slash = api.getCommands()
        flag("getCommands：包含内置 mcp", slash.any { it.name == "mcp" }, slash.map { it.name }.take(5).toString())

        // 写
        api.setThinkingLevel("low")
        check("cycleThinkingLevel：返回新等级", api.cycleThinkingLevel()?.level, "medium")
        api.setSteeringMode(QueueMode.All)
        api.setFollowUpMode(QueueMode.OneAtATime)
        api.setAutoCompaction(false)
        api.setAutoRetry(false)
        val cleared = api.clearQueue()
        check("clearQueue：两个空队列", cleared.steering to cleared.followUp, emptyList<String>() to emptyList<String>())
        api.setSessionName("录制会话")
        val bash = api.bash("echo hi")
        check("bash：输出与退出码", bash.output to bash.exitCode, "hi\n" to 0)
        val cycled = api.cycleModel()
        flag("cycleModel：返回新模型", cycled?.model?.id != null)
        val path = api.exportHtml()
        flag("exportHtml：返回路径", path.endsWith(".html"), path)

        // 失败路径：压缩一个太小的会话 → 宿主 internal 错误，变成带 code 的异常
        val compact = runCatching { api.compact() }.exceptionOrNull() as? HostCommandException
        check("compact：失败带协议错误码", compact?.code, "internal")
        flag("compact：原因里有宿主的原话", compact?.reason?.contains("Nothing to compact") == true, compact?.reason ?: "null")

        // 会话替换
        check("newSession：cancelled=false", api.newSession().cancelled, false)
        flag("新会话后 stateStore 采纳了应答里的 state（epoch 更大）", (cmds.stateStore.state.value?.epoch ?: 0L) >= 2L)

        // 未知命令（通用口）→ 异常带码
        val unknown = runCatching { cmds.call("no.such.command") }.exceptionOrNull() as? HostException
        check("未知命令：unknown_cmd", unknown?.code, "unknown_cmd")
    }

    if (failures > 0) { println("harness: FAILED ($failures)"); exitProcess(1) }
    println("harness: OK (host-api)")
}
