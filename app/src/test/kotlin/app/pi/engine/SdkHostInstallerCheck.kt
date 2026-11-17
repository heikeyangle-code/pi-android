package app.pi.engine

import java.io.File
import java.nio.file.Files
import kotlin.system.exitProcess

// `SdkHostInstaller` / `SdkHostLaunch` 的 bare-JVM harness，登记为 `sdk-host-install`。
//
// 安装器错了不会报错：少复制一个文件 = 宿主起来后在某条命令里才 import 失败；旧文件没删 =
// 改名后的宿主还在加载上一版的模块。所以这里断言的是**同步语义**（写 / 不写 / 删），
// 并且用**真的 assets 目录**走一遍，再用 node 把装出来的那份真起一次，看它发 host.ready ——
// 证明「复制出来的树」是能跑的，而不只是文件数对。
//
// 不在这里：AssetManager 本身（要设备）、rootfs 里 /opt/node 的路径是否存在（运行时自检管）、
// proot / proroot 下的启动（要真机，docs/sdk-host/runtimes.md 清单）。

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("PASS $name")
    else { failures++; println("FAIL $name\n  expected: $expected\n  actual:   $actual") }
}

/** 目录当 assets：`root` 就是 `assets/`。 */
private class DirAssets(private val root: File) : HostAssetSource {
    override fun list(dir: String) = root.resolve(dir).list()?.sorted().orEmpty()
    override fun read(path: String) = root.resolve(path).readBytes()
}

/** 内存 assets：路径 → 字节。 */
private class MapAssets(private val files: Map<String, String>) : HostAssetSource {
    override fun list(dir: String): List<String> {
        val prefix = if (dir.endsWith("/")) dir else "$dir/"
        return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
    }
    override fun read(path: String) = files.getValue(path).toByteArray()
}

fun main() {
    val tmp = Files.createTempDirectory("sdk-host-install").toFile()
    try {
        sync(tmp)
        launch()
        real(tmp)
    } finally {
        tmp.deleteRecursively()
    }
    if (failures > 0) { println("harness: FAILED ($failures)"); exitProcess(1) }
    println("harness: OK (sdk-host-install)")
}

private fun sync(tmp: File) {
    val target = tmp.resolve("rootfs/opt/pi/sdk-host")
    val v1 = MapAssets(mapOf(
        "guest/sdk-host/main.mjs" to "main v1",
        "guest/sdk-host/commands/host.mjs" to "host v1",
        "guest/sdk-host/ui/theme.mjs" to "theme v1",
    ))
    val r1 = SdkHostInstaller(v1).install(target)
    check("首次：全部写入", r1.written.sorted(), listOf("commands/host.mjs", "main.mjs", "ui/theme.mjs"))
    check("首次：内容正确", target.resolve("commands/host.mjs").readText(), "host v1")
    val r2 = SdkHostInstaller(v1).install(target)
    check("再装一次：零写入", r2.written, emptyList<String>())
    check("再装一次：全部 unchanged", r2.unchanged, 3)

    // 升级：改一个、删一个（theme 没了）、加一个
    val v2 = MapAssets(mapOf(
        "guest/sdk-host/main.mjs" to "main v2",
        "guest/sdk-host/commands/host.mjs" to "host v1",
        "guest/sdk-host/commands/new.mjs" to "new",
    ))
    val r3 = SdkHostInstaller(v2).install(target)
    check("升级：只写变了和新增的", r3.written.sorted(), listOf("commands/new.mjs", "main.mjs"))
    check("升级：源里没了的被删（不留旧模块）", r3.removed, listOf("ui/theme.mjs"))
    check("升级：旧文件确实不在", target.resolve("ui/theme.mjs").exists(), false)
    check("升级：空目录被清掉", target.resolve("ui").exists(), false)
    check("升级：无 .partial 残留", target.walkTopDown().none { it.name.endsWith(".partial") }, true)

    // 目标里被人改坏：恢复
    target.resolve("main.mjs").writeText("tampered")
    val r4 = SdkHostInstaller(v2).install(target)
    check("被改坏的文件会被恢复", r4.written, listOf("main.mjs"))
    check("恢复后内容", target.resolve("main.mjs").readText(), "main v2")

    // 同目录别的东西不碰：只管 sdk-host 这棵树
    val sibling = tmp.resolve("rootfs/opt/pi/scandir-fix.mjs").also { it.writeText("keep") }
    SdkHostInstaller(v2).install(target)
    check("不碰 /opt/pi 里别的文件", sibling.readText(), "keep")

    // 空 assets：明确报错，而不是装出一个空目录
    val failure = runCatching { SdkHostInstaller(MapAssets(emptyMap())).install(tmp.resolve("x")) }.exceptionOrNull()
    check("assets 里没有宿主 → 抛 IOException", failure is java.io.IOException, true)
}

private fun launch() {
    val cmd = SdkHostLaunch.command(cwd = "/workspace/pi/workspaces/workspace-1", agentDir = "/root/.pi/agent")
    check(
        "默认命令",
        cmd,
        "exec /opt/node/bin/node '/opt/pi/sdk-host/main.mjs' --cwd '/workspace/pi/workspaces/workspace-1' " +
            "--agent-dir '/root/.pi/agent' --session-dir '/root/.pi/agent/sessions' " +
            "--pi-dir '/opt/pi/node_modules/@earendil-works/pi-coding-agent'",
    )
    val q = SdkHostLaunch.command(cwd = "/w/it's here", agentDir = "/a", extensions = listOf("/e/x.mjs"))
    check("路径里的单引号被转义", q.contains("--cwd '/w/it'\\''s here'"), true)
    check("扩展用 --extension", q.endsWith(" --extension '/e/x.mjs'"), true)
}

/** 用真的 assets 目录装一遍，再把装出来的那份用 node 起一次。 */
private fun real(tmp: File) {
    val root = File(System.getProperty("pi.repo.root") ?: ".")
    val assets = root.resolve("app/src/main/assets")
    if (!assets.resolve("guest/sdk-host/main.mjs").isFile) { failures++; println("FAIL 找不到真 assets: $assets"); return }
    val target = tmp.resolve("real/opt/pi/sdk-host")
    val r = SdkHostInstaller(DirAssets(assets)).install(target)
    val onDisk = assets.resolve("guest/sdk-host").walkTopDown().count { it.isFile }
    check("真 assets：文件数一致", r.total, onDisk)
    check("真 assets：入口存在", target.resolve("main.mjs").isFile, true)
    check("真 assets：二次安装零写入", SdkHostInstaller(DirAssets(assets)).install(target).written, emptyList<String>())

    val node = listOf("node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull {
        runCatching { ProcessBuilder(it, "--version").redirectErrorStream(true).start().also { p -> p.waitFor() }.exitValue() == 0 }.getOrDefault(false)
    }
    if (node == null) { println("SKIP 装出来的宿主起一次：本机没有 node"); return }
    val work = tmp.resolve("real/work").also { it.mkdirs() }
    val agent = tmp.resolve("real/agent").also { it.mkdirs() }
    val p = ProcessBuilder(node, target.resolve("main.mjs").path, "--cwd", work.path, "--agent-dir", agent.path, "--pi-dir", tmp.resolve("nope").path)
        .redirectErrorStream(false).start()
    val first = p.inputStream.bufferedReader().readLine()
    p.destroyForcibly()
    check("装出来的宿主能起、第一帧是 host.ready", first?.contains("\"name\":\"host.ready\""), true)
}
