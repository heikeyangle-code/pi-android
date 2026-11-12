package app.pi.packages

import android.content.Context
import app.pi.engine.PiEngineHost
import app.pi.runtime.PtyLauncher
import java.io.File

/**
 * 向 pi 要「实际加载了哪些资源」，并替用户改开关。
 *
 * 全部走 guest 里的 `pi-resources.mjs`（pi 自己的包管理器 + `pi config` 的开关逻辑），
 * App 不重写 `+path/-path` 的写法，也不再扫目录猜。脚本每次启动都重写到
 * `<rootfs>/opt/pi/`，以免旧版本脚本留在 rootfs 里。
 */
class PiResources(
    private val context: Context,
    private val layout: AgentLayout,
    private val guest: GuestCommand,
) {
    private fun notReady(): String? = when {
        !layout.runtimeReady() -> "运行环境还没装好，先在对话页等它准备完。"
        !layout.engineInstalled() -> "引擎还没安装，装好之后才能读资源。"
        else -> null
    }

    private fun installScript() {
        val target = File(layout.paths.rootfs, "opt/pi/pi-resources.mjs")
        val bytes = context.assets.open(SCRIPT_ASSET).use { it.readBytes() }
        if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) return
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, target.name + ".partial")
        staging.writeBytes(bytes)
        if (!staging.renameTo(target)) {
            target.writeBytes(bytes)
            staging.delete()
        }
    }

    private fun command(trusted: Boolean, vararg words: String): String {
        val engineDir = "${PiEngineHost.ENGINE_GUEST_ROOT}/node_modules/${PiEngineHost.PI_PACKAGE}"
        val head = listOf(engineDir, layout.guestWorkspace, layout.guestAgentDir, if (trusted) "1" else "0")
        val all = (head + words).joinToString(" ") { PtyLauncher.Shell.quote(it) }
        return "exec ${layout.guestNode} /opt/pi/pi-resources.mjs $all"
    }

    private fun <T> run(
        trusted: Boolean,
        vararg words: String,
        parse: (String) -> PiResourceCatalog.Parsed<T>,
    ): PiResourceCatalog.Parsed<T> {
        notReady()?.let { return PiResourceCatalog.Parsed.Error(it) }
        return runCatching {
            installScript()
            val outcome = guest.run(
                guestCommand = command(trusted, *words),
                cwd = layout.guestWorkspace,
                timeoutMs = TIMEOUT_MS,
            )
            outcome.launchError?.let { return PiResourceCatalog.Parsed.Error(it) }
            if (outcome.timedOut) return PiResourceCatalog.Parsed.Error("读取资源超时。")
            parse(outcome.stdout).let { parsed ->
                if (parsed is PiResourceCatalog.Parsed.Error && outcome.stdout.isBlank()) {
                    PiResourceCatalog.Parsed.Error(outcome.stderr.trim().lines().lastOrNull().orEmpty().ifBlank { parsed.message })
                } else {
                    parsed
                }
            }
        }.getOrElse { PiResourceCatalog.Parsed.Error(it.message ?: it.javaClass.simpleName) }
    }

    fun list(trusted: Boolean): PiResourceCatalog.Parsed<PiResourceCatalog.Snapshot> =
        run(trusted, "list", parse = PiResourceCatalog::parseSnapshot)

    fun toggle(
        layer: PiResourceCatalog.Layer,
        item: PiResourceCatalog.Item,
        trusted: Boolean,
    ): PiResourceCatalog.Parsed<PiResourceCatalog.Toggled> =
        run(trusted, "toggle", layer.key, item.type.key, item.path, parse = PiResourceCatalog::parseToggle)

    /** pi 终端界面启动时问的那个检查：只查哪些包有新版本，不安装任何东西。要联网，所以只在用户点了才跑。 */
    fun updates(trusted: Boolean): PiResourceCatalog.Parsed<List<PiResourceCatalog.Update>> =
        run(trusted, "updates", parse = PiResourceCatalog::parseUpdates)

    private companion object {
        const val SCRIPT_ASSET = "guest/pi-resources.mjs"
        const val TIMEOUT_MS = 60_000L
    }
}
