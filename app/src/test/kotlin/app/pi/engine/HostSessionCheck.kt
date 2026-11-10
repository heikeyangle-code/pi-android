package app.pi.engine

import app.pi.host.OpenArgs
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

    // 发后不管：没有凭据的 prompt 预检通过、整轮失败 → 状态机 Busy/Ready 来回，不是卡死
    val events = java.util.Collections.synchronizedList(mutableListOf<PiEvent>())
    val collector = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { session.events.collect { events.add(it) } }
    session.prompt("你好")
    val rows = within(30_000) { session.publication.first { p -> p.rows.isNotEmpty() } }
    flag("prompt 的用户气泡进了 transcript（本地回显）", rows != null)
    delay(1500)
    flag("agent 事件到了界面事件流", events.any { it is PiEvent.AgentStart || it is PiEvent.AgentEnd || it is PiEvent.AgentSettled }, events.map { it.type }.toString())

    // 发后不管的命令失败 → Response(success=false)
    events.clear()
    session.fire("no.such.command")
    delay(800)
    val failed = events.filterIsInstance<PiEvent.Response>().firstOrNull()
    check("fire 失败变成 Response(success=false)", failed?.success to failed?.command, false to "no.such.command")

    // 未知对话框 id：respondUi 发出去不崩，宿主答 matched:false
    flag("respondUi 对未知 id 不抛", session.respondUi("nope", cancelled = true))

    // 收尾：等本轮结束再关，进程真的退出
    session.closeAfterSettling(timeoutMs = 30_000)
    delay(500)
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
    val pendingCall = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        val ex = runCatching { dying.commands.call("host.ping", timeoutMs = 20_000) }.exceptionOrNull()
        flag("进程被杀时在等的命令立刻失败", ex != null)
    }
    runCatching { dying.close() }
    within(5_000) { pendingCall.join() } ?: println("FAIL 在等的命令没有被释放").also { failures++ }
}
