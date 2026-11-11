package app.pi.engine

import app.pi.host.HostAdvanced
import app.pi.host.OpenArgs
import app.pi.session.SessionLibrary
import app.pi.rpc.PiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

// `PiEngineSession` 接**真宿主 + 真 pi 1.5.0** 的端到端 harness，登记为 `host-session`。
//
// 与 HostClientCheck（回放录制）的区别：这里是真进程、真管道、真 `PiEngineSession`：
// 成帧器 → PiHostClient → 状态机 → transcript。回放测不到的东西在这里测：
//   · 先订阅后开读（host.ready 不丢）、握手后才 Ready、写线程 + flush 的真实往返；
//   · startHost 把「宿主明确拒绝」变成 Failed 而不是永远转圈；
//   · 真的发一条消息：agent 事件进 transcript、Busy → Ready；
//   · 发后不管的命令失败变成 PiEvent.Response(success=false)，界面的 lastError 路径靠它；
//   · closeAfterSettling 真的把进程收掉。
// 缺 node 或缺 build/pi-contract（`tools/fetch-pi-contract.sh`）时 SKIP，不当作失败。
//
// 不在这里：proot / proroot 下的启动、手机上的耗时、真实模型凭据下的完整一轮。

private var failures = 0
private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name")
    else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}
private fun flag(name: String, ok: Boolean, detail: String = "") = check(name + if (!ok && detail.isNotEmpty()) " ($detail)" else "", ok, true)

private suspend fun <T> within(ms: Long, block: suspend () -> T): T? = runCatching { withTimeout(ms) { block() } }.getOrNull()

fun main() {
    val root = File(System.getProperty("pi.repo.root") ?: ".")
    val piDir = root.resolve("build/pi-contract/node_modules/@earendil-works/pi-coding-agent")
    val hostMain = root.resolve("app/src/main/assets/guest/sdk-host/main.mjs")
    val node = listOf("node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull {
        runCatching { ProcessBuilder(it, "--version").redirectErrorStream(true).start().also { p -> p.waitFor() }.exitValue() == 0 }.getOrDefault(false)
    }
    if (node == null || !piDir.isDirectory || !hostMain.isFile) {
        println("SKIP host-session：需要 node 与 build/pi-contract（$piDir）")
        return
    }
    val tmp = Files.createTempDirectory("host-session").toFile()
    try {
        run(node, hostMain, piDir, tmp)
    } finally {
        tmp.deleteRecursively()
    }
    if (failures > 0) { println("harness: FAILED ($failures)"); exitProcess(1) }
    println("harness: OK (host-session)")
}

private fun spawn(node: String, hostMain: File, piDir: File, tmp: File, name: String): PiEngineSession {
    val work = tmp.resolve("$name/work").also { it.mkdirs() }
    val agent = tmp.resolve("$name/agent").also { it.mkdirs() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return PiEngineSession.spawn(
        argv = listOf(node, hostMain.path, "--cwd", work.path, "--agent-dir", agent.path, "--session-dir", agent.resolve("sessions").path, "--pi-dir", piDir.path),
        env = emptyMap(),
        cwd = work,
        scope = scope,
    )
}

private fun run(node: String, hostMain: File, piDir: File, tmp: File) = runBlocking {
    // —— 正常路径 ——
    val session = spawn(node, hostMain, piDir, tmp, "a")
    check("握手前是 Starting", session.state.value, PiEngineSession.EngineState.Starting)
    session.startHost(OpenArgs(trust = false), timeoutMs = 120_000)
    val reached = within(120_000) { session.state.first { it == PiEngineSession.EngineState.Ready } }
    check("open 成功后 Ready", reached, PiEngineSession.EngineState.Ready)
    check("握手结果：协议与引擎", session.hostHello?.protocol to session.hostHello?.engine, 1 to "coding-agent")
    flag("握手结果带能力表", session.hostHello?.capabilities?.isNotEmpty() == true)
    flag("记录了就绪耗时", (PiEngineSession.lastServingMs ?: -1) >= 0)
    val st = session.commands.stateStore.state.value
    flag("open 应答里的 state 进了 store", st?.sessionId != null && st.cwd != null, st?.raw.toString())

    val api = session.api.getState()
    check("api.getState 与 store 同一会话", api.sessionId, st?.sessionId)
    flag("api.getAvailableModels 非空", session.api.getAvailableModels().isNotEmpty())

    // —— 会话库：宿主机路径 ↔ guest 路径；这里 guest 与宿主机是同一棵目录，所以换算是恒等的，
    //    但越界拒绝、改名、删除、搜索都是真的在跑宿主命令。
    val agentDir = tmp.resolve("a/agent")
    val lib = SessionLibrary(agentDir, agentDir.path) { session.commands }
    val sdir = agentDir.resolve("sessions").also { it.mkdirs() }
    val fx = sdir.resolve("2026-01-01T00-00-00-000Z_lib-1.jsonl")
    fx.writeText(
        listOf(
            """{"type":"session","version":3,"id":"lib-1","timestamp":"2026-01-01T00:00:00.000Z","cwd":"${tmp.resolve("a/work").path}"}""",
            """{"type":"message","id":"e1","parentId":null,"timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"user","content":[{"type":"text","text":"独特词 quokka-lime 怎么用\n第二行"}],"timestamp":1767225601000}}""",
        ).joinToString("\n") + "\n",
    )
    val libRows = lib.list()
    val row = libRows.firstOrNull { it.id == "lib-1" }
    flag("SessionLibrary.list 读到宿主给的行", row != null, libRows.toString())
    check("行的字段：文件/标题取首行/条数", Triple(row?.file?.name, row?.title, row?.messageCount), Triple(fx.name, "独特词 quokka-lime 怎么用", 1))
    flag("行的时间来自 ISO 字符串", (row?.startedAt ?: 0L) > 1_700_000_000_000L)
    val libHits = lib.search("QUOKKA-lime")
    flag("SessionLibrary.search 命中且带片段", libHits.any { it.session.id == "lib-1" && it.snippet.contains("quokka-lime") })
    check("改名成功", lib.rename(fx, "库改名"), true)
    check("改名后列表里是新名字", lib.list().firstOrNull { it.id == "lib-1" }?.displayName, "库改名")
    check("会话根之外的路径换不出来 → 改名/删除返回 false", lib.rename(File("/etc/passwd"), "x") to lib.delete(File("/etc/passwd")), false to false)
    check("删除成功且文件没了", lib.delete(fx) to fx.exists(), true to false)
    check("删不存在的 → false", lib.delete(fx), false)

    // 发后不管：没有凭据的 prompt 预检通过、整轮失败 → 状态机 Busy/Ready 来回，不是卡死
    val events = java.util.Collections.synchronizedList(mutableListOf<PiEvent>())
    val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { session.events.collect { events.add(it) } }
    session.prompt("你好")
    val rows = within(30_000) { session.publication.first { p -> p.rows.isNotEmpty() } }
    flag("prompt 的用户气泡进了 transcript（本地回显）", rows != null)
    within(20_000) { while (events.none { it is PiEvent.AgentStart || it is PiEvent.AgentEnd || it is PiEvent.AgentSettled }) delay(50) }
    flag("agent 事件到了界面事件流", events.any { it is PiEvent.AgentStart || it is PiEvent.AgentEnd || it is PiEvent.AgentSettled }, events.map { it.type }.toString())

    // —— 标签 / 上下文编辑 / 分支路径：用刚才那轮留下的真条目 ——
    within(60_000) { session.state.first { it == PiEngineSession.EngineState.Ready } }
    val firstId = session.api.getEntries().entries.firstOrNull()?.id
    flag("会话里有条目可操作", firstId != null)
    if (firstId != null) {
        session.commands.setLabel(firstId, "书签A")
        val tree = session.api.getTree()
        fun labelOf(nodes: List<app.pi.rpc.SessionTreeNode>): String? = nodes.firstNotNullOfOrNull { n -> if (n.entry.id == firstId) n.label else labelOf(n.children) }
        check("session.label 之后树节点带标签", labelOf(tree.tree), "书签A")
        session.commands.setLabel(firstId, null)
        check("清除标签", labelOf(session.api.getTree().tree), null)
        val path = session.commands.branchPath()
        flag("session.path 返回当前分支", (path["entries"] as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() == true)
        // 第一条常是 model_change 之类的状态条目 —— SDK 明确拒绝编辑它们（原话原样透出）；要隐藏的是用户消息。
        val refused = runCatching { session.commands.hideFromContext(firstId) }.exceptionOrNull()
        flag("不可编辑的条目被 SDK 拒绝，原因原话透出", refused == null || refused.message?.contains("editable") == true, refused?.message ?: "")
        val userId = (path["entries"] as kotlinx.serialization.json.JsonArray).mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .firstOrNull { e -> (e["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "message" && ((e["message"] as? kotlinx.serialization.json.JsonObject)?.get("role") as? kotlinx.serialization.json.JsonPrimitive)?.content == "user" }
            ?.get("id")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        flag("找到用户消息条目", userId != null)
        if (userId != null) {
            val hid = runCatching { session.commands.hideFromContext(userId) }
            flag("session.contextEdit 隐藏用户消息（只追加条目，不删记录）", hid.isSuccess, hid.exceptionOrNull()?.message ?: "")
            flag("隐藏之后原条目仍在记录里", session.api.getEntries().entries.any { it.id == userId })
        }
    }

    // —— HostAdvanced：类型化的高级命令（真宿主） ——
    val adv = HostAdvanced(session.commands)
    val allModels = session.api.getAvailableModels()
    if (allModels.size >= 2) {
        val refs = allModels.take(2).map { HostAdvanced.ModelRef(it.provider.orEmpty(), it.id.orEmpty()) }
        check("scoped 设置返回个数", adv.setScopedModels(refs), 2)
        check("scoped 读回", adv.scopedModels().map { it.id }, refs.map { it.id })
        check("scoped 清空", adv.setScopedModels(emptyList()), 0)
    }
    check("cache 模式往返", adv.setCacheMode("idle").mode to adv.cacheStatus().mode, "idle" to "idle")
    adv.setCacheMode("off")
    flag("systemPrompt 非空", adv.systemPrompt().length > 50)
    flag("tools 带详情与 callable", adv.tools().let { t -> t.isNotEmpty() && t.any { it.active } && t.all { it.name.isNotEmpty() } })
    check("queueMessages 空", adv.queueMessages().let { it.steering.isEmpty() && it.followUp.isEmpty() }, true)
    val jsonlPath = adv.exportJsonl()
    flag("exportJsonl 写出文件", java.io.File(jsonlPath).isFile, jsonlPath)
    val badProv = runCatching { adv.setProvider("x", kotlinx.serialization.json.JsonObject(mapOf("baseUrl" to kotlinx.serialization.json.JsonPrimitive(5)))) }.exceptionOrNull()
    flag("setProvider 非法 → SDK 原话", badProv?.message?.contains("must be string") == true, badProv?.message.orEmpty())
    val okProv = kotlinx.serialization.json.JsonObject(mapOf(
        "baseUrl" to kotlinx.serialization.json.JsonPrimitive("http://127.0.0.1:1/v1"), "apiKey" to kotlinx.serialization.json.JsonPrimitive("k"),
        "api" to kotlinx.serialization.json.JsonPrimitive("openai-completions"),
        "models" to kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive("m1"))))),
    ))
    adv.setProvider("mine", okProv)
    check("modelsConfig 读到刚保存的供应商", adv.modelsConfig().providers.keys, setOf("mine"))
    adv.removeProvider("mine")
    check("删除后没有", adv.modelsConfig().providers.keys, emptySet<String>())

    // 发后不管的命令失败 → Response(success=false)
    events.clear()
    session.fire("no.such.command")
    within(10_000) { while (events.none { it is PiEvent.Response }) delay(50) }
    val failed = events.filterIsInstance<PiEvent.Response>().firstOrNull()
    check("fire 失败变成 Response(success=false)", failed?.success to failed?.command, false to "no.such.command")

    // 未知对话框 id：respondUi 发出去不崩，宿主答 matched:false
    flag("respondUi 对未知 id 不抛", session.respondUi("nope", cancelled = true))

    // 收尾：等本轮结束再关，进程真的退出
    session.closeAfterSettling(timeoutMs = 30_000)
    within(10_000) { while (session.state.value != PiEngineSession.EngineState.Stopped && session.state.value != PiEngineSession.EngineState.Failed) delay(50) }
    flag("closeAfterSettling 之后进程退出", session.state.value == PiEngineSession.EngineState.Stopped || session.state.value == PiEngineSession.EngineState.Failed, session.state.value.toString())
    collector.cancel()

    // —— 宿主拒绝：协议版本不对时不能一直 Starting ——
    // 用一个只会回 hello 的假宿主：protocol=99。
    val fake = tmp.resolve("fake.mjs").also {
        it.writeText(
            """
            process.stdout.write(JSON.stringify({t:"evt",seq:1,epoch:0,name:"host.ready",data:{}})+"\n");
            let buf="";process.stdin.on("data",d=>{buf+=d;let i;while((i=buf.indexOf("\n"))>=0){const f=JSON.parse(buf.slice(0,i));buf=buf.slice(i+1);
              process.stdout.write(JSON.stringify({t:"res",id:f.id,ok:true,data:{protocol:99,engine:"coding-agent",commands:[],capabilities:[]}})+"\n");}});
            """.trimIndent(),
        )
    }
    val badProc = PiEngineSession.spawn(listOf(node, fake.path), emptyMap(), tmp, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    badProc.startHost(OpenArgs(), timeoutMs = 10_000)
    val failedState = within(15_000) { badProc.state.first { it == PiEngineSession.EngineState.Failed } }
    check("协议版本不符 → Failed（不是永远 Starting）", failedState, PiEngineSession.EngineState.Failed)
    flag("原话留在 lastOpenError", badProc.lastOpenError?.contains("v99") == true, badProc.lastOpenError ?: "null")
    badProc.close()

    // —— 进程退出：在等的命令以 closed 失败，而不是等满超时 ——
    val dying = spawn(node, hostMain, piDir, tmp, "b")
    delay(800)
    // 用 session.open：加载 SDK 要好几秒，保证进程被收掉时命令还在途中（ping 太快，会赢过 close）。
    val pendingCall = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        val ex = runCatching { dying.commands.open(OpenArgs(), timeoutMs = 60_000) }.exceptionOrNull()
        flag("进程被杀时在等的命令立刻失败", ex != null)
    }
    runCatching { dying.close() }
    within(5_000) { pendingCall.join() } ?: println("FAIL 在等的命令没有被释放").also { failures++ }
}
