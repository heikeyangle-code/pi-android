package app.pi.host

import app.pi.rpc.PiJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

// `app.pi.host`（PiHostClient / HostFrame / HostState / HostCommands）的 bare-JVM harness，
// 登记在 tools/run-app-pure-checks.sh 里叫 `host-client`。
//
// 两组断言：
//  1. 规则：epoch 过滤、seq 跳号、应答配对、超时/取消清理 pending、进程退出、帧容错、状态取最新。
//     这些是协议里 App 必须守的三条规则（protocol.md），错了不会报错，只会让上个会话的尾巴画进新会话。
//  2. 回放：`tools/sdk-host/record.mjs` 从**真宿主 + 真 pi 1.5.0** 录下来的一整段会话
//     （握手 → open → snapshot → 发消息 → 新会话 → 错误应答），逐行喂给客户端，断言解析结果。
//     这是「Kotlin 解析的和 node 宿主真发的是同一种东西」的证据，不是手写的假帧。
//
// 不在这里、也不可能在这里的：真实进程的管道行为、proot / proroot 下的启动、手机上的耗时。

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name")
    else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}

private fun flag(name: String, ok: Boolean, detail: String = "") = check(name + if (!ok && detail.isNotEmpty()) " ($detail)" else "", ok, true)

private fun evt(seq: Long, epoch: Long, name: String, data: String = "{}") = """{"t":"evt","seq":$seq,"epoch":$epoch,"name":"$name","data":$data}"""
private fun ok(id: String, data: String = "{}") = """{"t":"res","id":"$id","ok":true,"data":$data}"""
private fun err(id: String, code: String, msg: String = "m") = """{"t":"res","id":"$id","ok":false,"error":{"code":"$code","message":"$msg"}}"""

/** 收集 signals 的小工具：先订阅（UNDISPATCHED），再喂行。 */
private class Collector(client: PiHostClient, scope: kotlinx.coroutines.CoroutineScope) {
    val got = java.util.Collections.synchronizedList(mutableListOf<HostSignal>())
    val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { client.signals.collect { got.add(it) } }
    val events get() = got.filterIsInstance<HostSignal.Event>()
    val resyncs get() = got.filterIsInstance<HostSignal.Resync>()
}

private fun obj(text: String): JsonObject = PiJson.parseObjectOrNull(text) ?: error("bad json in harness: $text")

fun main() {
    parsing()
    rules()
    commands()
    bridge()
    widgets()
    replay()
    if (failures > 0) { println("harness: FAILED ($failures)"); exitProcess(1) }
    println("harness: OK (host-client)")
}

private fun parsing() {
    check("res ok", HostFrame.parse(ok("c1", """{"a":1}""")), HostFrame.Res("c1", true, obj("""{"a":1}"""), null))
    val bad = HostFrame.parse(err("c2", "busy", "忙")) as HostFrame.Res
    check("res error 带 code/message", bad.error, HostError("busy", "忙", null))
    check("res ok=false 没给 error 也不丢", ((HostFrame.parse("""{"t":"res","id":"x","ok":false}""") as HostFrame.Res).error)?.code, "internal")
    val e = HostFrame.parse(evt(12, 2, "agent", """{"type":"agent_start"}""")) as HostFrame.Evt
    check("evt 字段", Triple(e.seq, e.epoch, e.name), Triple(12L, 2L, "agent"))
    val r = HostFrame.parse("""{"t":"req","id":"r7","method":"ui.select","params":{"title":"t"}}""") as HostFrame.Req
    check("req 字段", r.id to r.method, "r7" to "ui.select")
    check("未知 t → null", HostFrame.parse("""{"t":"zzz","id":"1"}"""), null)
    check("不是 JSON → null", HostFrame.parse("not json"), null)
    check("缺 id 的 res → null", HostFrame.parse("""{"t":"res","ok":true}"""), null)
    check("缺 seq 的 evt → null", HostFrame.parse("""{"t":"evt","name":"x"}"""), null)
    check("数组不是帧 → null", HostFrame.parse("[1,2]"), null)
    // U+2028 在 JSON 字符串里合法，不能被当行尾
    val sep = HostFrame.parse(evt(1, 0, "ui.notify", """{"message":"a b"}""")) as HostFrame.Evt
    flag("evt 数据里的 U+2028 原样保留", sep.data.toString().contains("a"))
}

private fun rules() = runBlocking {
    // —— 配对：乱序应答各回各的 ——
    run {
        val sent = mutableListOf<String>()
        val c = PiHostClient({ sent.add(it) })
        val a = async(start = CoroutineStart.UNDISPATCHED) { c.callObject("a.cmd") }
        val b = async(start = CoroutineStart.UNDISPATCHED) { c.callObject("b.cmd") }
        check("两条命令已发出，编号不同", sent.size, 2)
        c.onLine(ok("c2", """{"who":"b"}"""))
        c.onLine(ok("c1", """{"who":"a"}"""))
        check("乱序应答配对 a", a.await()["who"], JsonPrimitive("a"))
        check("乱序应答配对 b", b.await()["who"], JsonPrimitive("b"))
        check("配对后 pending 清空", c.pendingCount, 0)
        val line = obj(sent[0])
        check("命令行形状", listOf(line["id"], line["cmd"], line["args"]), listOf(JsonPrimitive("c1"), JsonPrimitive("a.cmd"), JsonObject(emptyMap())))
    }
    // —— 错误应答变成带 code 的异常 ——
    run {
        val c = PiHostClient({ })
        val x = async(start = CoroutineStart.UNDISPATCHED) { runCatching { c.call("x") }.exceptionOrNull() }
        c.onLine(err("c1", "unknown_cmd", "未知命令"))
        val ex = x.await() as HostException
        check("错误码透传", ex.code to ex.message, "unknown_cmd" to "未知命令")
    }
    // —— 超时：清 pending，迟到的应答不炸 ——
    run {
        val c = PiHostClient({ })
        val ex = runCatching { c.call("slow", timeoutMs = 40) }.exceptionOrNull() as? HostException
        check("超时错误码", ex?.code, HostException.TIMEOUT)
        check("超时后 pending 清空", c.pendingCount, 0)
        c.onLine(ok("c1")) // 迟到
        check("迟到应答被丢弃、不抛", c.pendingCount, 0)
    }
    // —— 取消：清 pending ——
    run {
        val c = PiHostClient({ })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { c.call("never") }
        check("等待中 pending=1", c.pendingCount, 1)
        job.cancelAndJoin()
        check("取消后 pending 清空", c.pendingCount, 0)
    }
    // —— 写失败 ——
    run {
        val c = PiHostClient({ throw IOException("broken pipe") })
        val ex = runCatching { c.call("x") }.exceptionOrNull() as? HostException
        check("写失败 → io，pending 清空", ex?.code to c.pendingCount, HostException.IO to 0)
    }
    // —— 进程退出：在等的全部失败，之后的调用直接抛 ——
    run {
        val c = PiHostClient({ })
        val a = async(start = CoroutineStart.UNDISPATCHED) { runCatching { c.call("a") }.exceptionOrNull() }
        c.close("宿主已退出")
        check("close 让在等的失败", (a.await() as? HostException)?.code, HostException.CLOSED)
        val later = runCatching { c.call("b") }.exceptionOrNull() as? HostException
        check("close 之后的 call 直接抛", later?.code, HostException.CLOSED)
        flag("isClosed", c.isClosed)
    }
    // —— epoch：旧代号的迟到事件被丢 ——
    run {
        val c = PiHostClient({ })
        val col = Collector(c, this)
        c.onLine(evt(1, 1, "session.bound"))
        c.onLine(evt(2, 2, "session.bound"))
        c.onLine(evt(3, 1, "agent", """{"type":"message_update"}""")) // 上个会话的尾巴
        c.onLine(evt(4, 2, "agent", """{"type":"agent_start"}"""))
        delay(20)
        check("当前 epoch 跟上去", c.epoch.value, 2L)
        check("旧 epoch 事件被丢", col.events.map { it.seq }, listOf(1L, 2L, 4L))
        check("丢弃计数", c.staleDropped, 1L)
        check("被丢的事件不触发 Resync", col.resyncs.size, 0)
        col.job.cancelAndJoin()
    }
    // —— seq 跳号：先 Resync、再事件 ——
    run {
        val c = PiHostClient({ })
        val col = Collector(c, this)
        c.onLine(evt(1, 1, "a")); c.onLine(evt(2, 1, "a")); c.onLine(evt(5, 1, "a"))
        delay(20)
        check("跳号发 Resync（在跳号事件之前）", col.got.map { (it as? HostSignal.Resync)?.let { "R" } ?: "E${(it as HostSignal.Event).seq}" }, listOf("E1", "E2", "R", "E5"))
        c.onLine(evt(5, 1, "dup"))
        delay(20)
        check("重复 seq 被忽略", col.events.size, 3)
        col.job.cancelAndJoin()
    }
    // —— 垃圾行与未知帧：忽略、不抛 ——
    run {
        val c = PiHostClient({ })
        c.onLine("garbage"); c.onLine("""{"t":"future","x":1}""")
        check("无法识别的行只计数", c.ignoredLines, 2L)
    }
    // —— 宿主提问 + 回答 ——
    run {
        val sent = mutableListOf<String>()
        val c = PiHostClient({ sent.add(it) })
        val got = java.util.Collections.synchronizedList(mutableListOf<HostRequest>())
        val j = launch(start = CoroutineStart.UNDISPATCHED) { c.requests.collect { got.add(it) } }
        c.onLine("""{"t":"req","id":"r9","method":"ui.confirm","params":{"title":"确认?"}}""")
        delay(20)
        check("收到提问", got.map { it.id to it.method }, listOf("r9" to "ui.confirm"))
        val resp = async(start = CoroutineStart.UNDISPATCHED) { c.respond("r9", JsonPrimitive(true)) }
        c.onLine(ok("c1", """{"matched":true}"""))
        resp.await()
        val line = obj(sent.single())
        check("respond 走 ui.respond", line["cmd"], JsonPrimitive("ui.respond"))
        check("respond 参数", (line["args"] as JsonObject).toString(), """{"id":"r9","value":true}""")
        val s2 = mutableListOf<String>()
        val c2 = PiHostClient({ s2.add(it) })
        val r2 = async(start = CoroutineStart.UNDISPATCHED) { c2.respond("r9", cancelled = true) }
        c2.onLine(ok("c1")); r2.await()
        check("取消回答", ((obj(s2.single())["args"]) as JsonObject).toString(), """{"id":"r9","cancelled":true}""")
        j.cancelAndJoin()
    }
    // —— 状态取最新：晚到的旧快照不覆盖新的 ——
    run {
        val st = HostStateStore()
        flag("首个快照被采纳", st.offer(obj("""{"epoch":1,"seq":10,"isStreaming":true}""")))
        flag("更新的被采纳", st.offer(obj("""{"epoch":1,"seq":12,"isStreaming":false}""")))
        flag("更旧的（晚到的命令应答）被拒", !st.offer(obj("""{"epoch":1,"seq":11,"isStreaming":true}""")))
        check("状态仍是最新的", st.state.value?.isStreaming, false)
        flag("换会话（epoch 更大）即使 seq 小也采纳", st.offer(obj("""{"epoch":2,"seq":3,"sessionId":"s2"}""")))
        flag("旧 epoch 的快照被拒", !st.offer(obj("""{"epoch":1,"seq":99}""")))
        check("sessionId", st.state.value?.sessionId, "s2")
        flag("null 不采纳", !st.offer(null))
        flag("offerEvent 取 data.state", st.offerEvent(obj("""{"reason":"x","state":{"epoch":2,"seq":4,"thinkingLevel":"high"}}""")))
        check("thinkingLevel", st.state.value?.thinkingLevel, "high")
    }
}

private fun commands() = runBlocking {
    val sent = mutableListOf<String>()
    val c = PiHostClient({ sent.add(it) })
    val cmds = HostCommands(c)
    // chat.send：Started + state 入库
    val d = async(start = CoroutineStart.UNDISPATCHED) { cmds.send("hi", followUp = true) }
    c.onLine(ok("c1", """{"disposition":"queued","state":{"epoch":1,"seq":5,"isStreaming":true,"model":{"provider":"p","id":"m"}}}"""))
    check("send 预检结果", d.await(), SendDisposition.Queued)
    check("send 命令参数", (obj(sent[0])["args"] as JsonObject).toString(), """{"message":"hi","behavior":"followUp"}""")
    check("应答里的 state 进了 store", cmds.stateStore.state.value?.modelId, "m")
    // OpenArgs 形状：未设置的不发
    check("OpenArgs 默认是空对象", OpenArgs().toJson().toString(), "{}")
    val full = OpenArgs(sessionFile = "/a.jsonl", continueRecent = true, noSkills = true, builtins = mapOf("mcp" to false), trust = false).toJson()
    check("OpenArgs 嵌套形状", full.toString(), """{"session":{"file":"/a.jsonl","continue":true},"resources":{"noSkills":true},"builtins":{"mcp":false},"trust":false}""")
    // hello
    val h = async(start = CoroutineStart.UNDISPATCHED) { cmds.hello() }
    c.onLine(ok("c2", """{"protocol":1,"engine":"coding-agent","node":"v22","commands":["a","b"],"capabilities":["ui.dialogs"]}"""))
    val hello = h.await()
    check("hello 解析", Triple(hello.protocol, hello.engine, hello.capabilities), Triple(1, "coding-agent", setOf("ui.dialogs")))
    // 旧宿主没有 engine 字段：默认 coding-agent
    val h2 = async(start = CoroutineStart.UNDISPATCHED) { cmds.hello() }
    c.onLine(ok("c3", """{"protocol":1}"""))
    check("缺 engine 默认 coding-agent", h2.await().engine, "coding-agent")
    // 通用口
    val sc = async(start = CoroutineStart.UNDISPATCHED) { cmds.sdkCall("session", "getSessionStats", listOf(JsonArray(emptyList()))) }
    c.onLine(ok("c4", """{"result":{"x":1}}"""))
    check("sdkCall 结果", sc.await()["result"], obj("""{"x":1}"""))
    check("sdkCall 命令", obj(sent.last())["cmd"], JsonPrimitive("sdk.call"))
}

private fun bridge() {
    fun req(method: String, params: String) = HostRequest("r1", method, obj(params))
    val sel = HostUiBridge.requestToLegacy(req("ui.select", """{"title":"选一个","options":["a","b"],"timeout":5000}"""))!!
    check("select 对话框", listOf(sel.uiId, sel.method, sel.title, sel.options, sel.timeoutMs), listOf("r1", "select", "选一个", listOf("a", "b"), 5000L))
    val conf = HostUiBridge.requestToLegacy(req("ui.confirm", """{"title":"t","message":"确定吗"}"""))!!
    check("confirm 对话框", conf.method to conf.message, "confirm" to "确定吗")
    val ed = HostUiBridge.requestToLegacy(req("ui.editor", """{"title":"t","prefill":"初始"}"""))!!
    check("editor 预填文本", ed.text, "初始")
    val inp = HostUiBridge.requestToLegacy(req("ui.input", """{"title":"t","placeholder":"ph","timeout":null}"""))!!
    check("input：timeout=null 不当成 0", inp.timeoutMs to inp.placeholder, null to "ph")
    check("auth.prompt 不是旧对话框 → null", HostUiBridge.requestToLegacy(req("auth.prompt", """{"prompt":{}}""")), null)

    val n = HostUiBridge.eventToLegacy("ui.notify", obj("""{"message":"hi","tone":"warning"}"""))!!
    check("notify", n.method to n.notifyType, "notify" to "warning")
    val st = HostUiBridge.eventToLegacy("ui.status", obj("""{"key":"k","text":"忙"}"""))!!
    check("setStatus", Triple(st.method, st.statusKey, st.statusText), Triple("setStatus", "k", "忙"))
    val stClear = HostUiBridge.eventToLegacy("ui.status", obj("""{"key":"k","text":null}"""))!!
    check("setStatus 清除（text=null）", stClear.statusText, null)
    check("setTitle", HostUiBridge.eventToLegacy("ui.title", obj("""{"title":"标题"}"""))!!.title, "标题")
    check("set_editor_text", HostUiBridge.eventToLegacy("ui.editorText", obj("""{"text":"x"}"""))!!.text, "x")
    check("不认识的事件 → null", HostUiBridge.eventToLegacy("ui.working", obj("""{"message":"m"}""")), null)
}

private fun widgets() {
    val sw = SurfaceWidgets()
    fun ev(name: String, json: String) = sw.onEvent(name, obj(json))
    val lines = ev("ui.widget", """{"key":"w","lines":["l1","l2"],"surfaceId":null,"placement":"belowEditor"}""")
    check("文本行小组件", lines, listOf(SurfaceWidgets.Update("w", listOf("l1", "l2"), "belowEditor")))
    check("清除 = 空行", ev("ui.widget", """{"key":"w","lines":null,"surfaceId":null,"placement":null}""").single().lines, emptyList<String>())
    // 宿主先 paint 再宣布：帧先到，缓存；宣布时补发
    check("帧先于宣布：先不出", ev("ui.surface", """{"surfaceId":"s1","key":"widget:t","mode":"widget","rev":1,"lines":["a","b"]}"""), emptyList<SurfaceWidgets.Update>())
    check("宣布时补发缓存的帧", ev("ui.widget", """{"key":"t","lines":null,"surfaceId":"s1","placement":"aboveEditor"}"""), listOf(SurfaceWidgets.Update("t", listOf("a", "b"), "aboveEditor")))
    check("新帧更新", ev("ui.surface", """{"surfaceId":"s1","key":"widget:t","mode":"widget","rev":2,"lines":["c"]}""").single().lines, listOf("c"))
    check("旧帧（rev 不增）被忽略", ev("ui.surface", """{"surfaceId":"s1","key":"widget:t","mode":"widget","rev":2,"lines":["old"]}"""), emptyList<SurfaceWidgets.Update>())
    check("custom 表面不算小组件", ev("ui.surface", """{"surfaceId":"s9","key":"k","mode":"custom","rev":1,"lines":["x"]}"""), emptyList<SurfaceWidgets.Update>())
    check("关闭 = 清除", ev("ui.close", """{"surfaceId":"s1","key":"widget:t","mode":"widget"}"""), listOf(SurfaceWidgets.Update("t", emptyList(), null)))
    check("关闭后迟到的帧不复活", ev("ui.surface", """{"surfaceId":"s1","key":"widget:t","mode":"widget","rev":3,"lines":["z"]}"""), emptyList<SurfaceWidgets.Update>())
    // 表面被字符串行替换：旧表面的迟到帧不能盖掉新内容
    ev("ui.surface", """{"surfaceId":"s2","key":"widget:u","mode":"widget","rev":1,"lines":["f"]}""")
    ev("ui.widget", """{"key":"u","lines":null,"surfaceId":"s2","placement":null}""")
    ev("ui.widget", """{"key":"u","lines":["plain"],"surfaceId":null,"placement":null}""")
    check("被替换后旧表面的帧忽略", ev("ui.surface", """{"surfaceId":"s2","key":"widget:u","mode":"widget","rev":9,"lines":["late"]}"""), emptyList<SurfaceWidgets.Update>())
    check("旧界面事件形状", HostUiBridge.widgetToLegacy(SurfaceWidgets.Update("w", listOf("l1"), "belowEditor"))!!.let { Triple(it.widgetKey, it.widgetLines, it.widgetPlacement) }, Triple("w", listOf("l1"), "belowEditor"))
}

private fun replay() = runBlocking {
    val root = File(System.getProperty("pi.repo.root") ?: ".")
    val file = root.resolve("app/src/test/resources/sdk-host/session.jsonl")
    if (!file.isFile) { failures++; println("FAIL fixture 不存在: $file（node tools/sdk-host/record.mjs 生成）"); return@runBlocking }
    val rows = file.readLines().filter { it.isNotBlank() }.map { obj(it) }

    val sentLines = mutableListOf<String>()
    val c = PiHostClient({ sentLines.add(it) })
    val col = Collector(c, this)
    val results = linkedMapOf<String, Result<Any?>>()
    val jobs = mutableListOf<Job>()
    var expectedSends = 0

    for (row in rows) {
        val line = (row["line"] as JsonPrimitive).content
        if ((row["dir"] as JsonPrimitive).content == "app") {
            val cmd = obj(line)
            val name = (cmd["cmd"] as JsonPrimitive).content
            val args = cmd["args"] as JsonObject
            expectedSends++
            val key = name + "#" + expectedSends
            jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                results[key] = runCatching { c.call(name, args, timeoutMs = 5_000) }
            }
            // 客户端必须发出和录制时逐字相同的命令行（编号、字段顺序都一样）
            check("回放：命令 $name 与录制一致", sentLines.lastOrNull()?.let { obj(it) }, cmd)
        } else {
            c.onLine(line)
        }
    }
    jobs.forEach { it.join() }
    delay(50)

    check("回放：命令全部发出", sentLines.size, expectedSends)
    check("回放：pending 清空", c.pendingCount, 0)
    check("回放：没有 Resync（真宿主的 seq 连续）", col.resyncs.size, 0)
    check("回放：没有被丢的旧 epoch 事件", c.staleDropped, 0L)
    check("回放：没有无法识别的行", c.ignoredLines, 0L)
    check("回放：最终 epoch（session.new 之后）", c.epoch.value, 2L)

    val firstEvent = col.events.first()
    check("回放：第一条是 host.ready", firstEvent.name, "host.ready")
    check("回放：session.bound 出现两次（open + new）", col.events.count { it.name == "session.bound" }, 2)

    val helloRes = results.entries.first { it.key.startsWith("host.hello") }.value.getOrThrow() as JsonObject
    check("回放：hello.protocol", helloRes["protocol"], JsonPrimitive(1))
    check("回放：hello.engine", helloRes["engine"], JsonPrimitive("coding-agent"))
    val hello = HostHello(
        1, "coding-agent", null,
        (helloRes["commands"] as JsonArray).map { (it as JsonPrimitive).content }.toSet(),
        (helloRes["capabilities"] as JsonArray).map { (it as JsonPrimitive).content }.toSet(),
    )
    flag("回放：命令表含 chat.send / session.open / sdk.call", listOf("chat.send", "session.open", "sdk.call").all { it in hello.commands })

    // chat.send 的真应答 → SendDisposition.Started，并带 state
    val sendRes = results.entries.first { it.key.startsWith("chat.send") }.value.getOrThrow() as JsonObject
    check("回放：chat.send disposition", sendRes["disposition"], JsonPrimitive("started"))

    // 错误应答的真样本
    val bad = results.entries.first { it.key.startsWith("no.such.command") }.value.exceptionOrNull() as? HostException
    check("回放：未知命令 → unknown_cmd", bad?.code, "unknown_cmd")

    // 状态：用真快照喂 store，字段取得出来
    val snap = results.entries.filter { it.key.startsWith("session.snapshot") }.map { it.value.getOrThrow() as JsonObject }
    val store = HostStateStore()
    flag("回放：第一份快照被采纳", store.offer(snap[0]))
    flag("回放：session.new 后的快照（epoch 更大）被采纳", store.offer(snap[1]))
    flag("回放：先喂新、再喂旧，旧的被拒", !store.offer(snap[0]))
    val s = store.state.value!!
    check("回放：快照 epoch", s.epoch, 2L)
    flag("回放：快照有 sessionId 与 cwd", s.sessionId != null && s.cwd != null)
    check("回放：新会话不在流式", s.isStreaming, false)

    // 事件里的 state 事件解析
    val stateEvts = col.events.filter { it.name == "state" }
    flag("回放：真 state 事件可被 offerEvent 解析", stateEvts.isNotEmpty() && HostStateStore().offerEvent(stateEvts.first().data))

    col.job.cancelAndJoin()
}
