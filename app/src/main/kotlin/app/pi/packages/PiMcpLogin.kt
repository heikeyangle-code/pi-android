package app.pi.packages

import app.pi.runtime.PtyLauncher

/**
 * 在 guest 里跑 pi 自己的 `pi mcp login <名字>` / `logout`。
 *
 * OAuth 是 pi 的实现（动态注册、PKCE、token 刷新、`mcp-auth.json`），App 一行都不重写：
 * 这里只负责把命令跑起来、把授权网址**在命令还没结束时**交给界面去打开浏览器，然后等结果。
 *
 * 浏览器回调为什么能到：pi 在 guest 里监听 `127.0.0.1:<端口>/callback`，而 proot/proroot 不隔离
 * 网络命名空间，手机上的浏览器访问 127.0.0.1 就是同一个回环 —— 与终端里手动登录等价。
 *
 * 取消：[GuestCommand.run] 目前只有超时能结束进程，所以「取消」只是界面不再等；pi 那一侧在
 * [timeoutSeconds] 到点后自己退出（`--timeout`）。把超时放短一点（默认 3 分钟）就是为了让一个被放弃的
 * 登录不会长时间占着回调端口。
 */
class PiMcpLogin(
    private val layout: AgentLayout,
    private val guest: GuestCommand,
) {
    sealed interface Result {
        data class Ok(val message: String, val alreadySignedIn: Boolean) : Result
        data class Failed(val message: String) : Result
    }

    fun login(server: String, timeoutSeconds: Int = 180, onUrl: (String) -> Unit): Result {
        notReady()?.let { return it }
        if (!isSafeMcpServerName(server)) return Result.Failed("服务器名不合法：$server")
        val seen = StringBuilder()
        var announced = false
        val outcome = guest.run(
            guestCommand = "exec ${layout.guestNode} ${layout.guestEngineCli} mcp login " +
                "${PtyLauncher.Shell.quote(server)} --timeout $timeoutSeconds",
            cwd = layout.guestWorkspace,
            timeoutMs = (timeoutSeconds + 30) * 1000L,
            onOutput = { chunk ->
                synchronized(seen) {
                    seen.append(chunk)
                    if (!announced) {
                        PiMcpLoginText.authorizationUrl(seen.toString())?.let { url ->
                            announced = true
                            onUrl(url)
                        }
                    }
                }
            },
        )
        return classify(outcome)
    }

    fun logout(server: String): Result {
        notReady()?.let { return it }
        if (!isSafeMcpServerName(server)) return Result.Failed("服务器名不合法：$server")
        val outcome = guest.run(
            guestCommand = "exec ${layout.guestNode} ${layout.guestEngineCli} mcp logout ${PtyLauncher.Shell.quote(server)}",
            cwd = layout.guestWorkspace,
            timeoutMs = GuestCommand.LIST_TIMEOUT_MS,
        )
        return if (outcome.ok) {
            Result.Ok(outcome.combined.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "已退出登录。", false)
        } else {
            classify(outcome)
        }
    }

    private fun notReady(): Result.Failed? = when {
        !layout.runtimeReady() -> Result.Failed("运行时尚未就绪，暂时无法执行 pi 命令。")
        !layout.engineInstalled() -> Result.Failed("引擎未安装。")
        else -> null
    }

    private fun classify(outcome: GuestCommand.Outcome): Result {
        outcome.launchError?.let { return Result.Failed("无法启动运行时：$it") }
        if (outcome.timedOut) return Result.Failed("等待登录超时，没有收到浏览器的授权回调。")
        val text = outcome.combined
        if (outcome.exitCode == 0 && PiMcpLoginText.signedIn(text)) {
            return Result.Ok(
                text.lines().lastOrNull { it.contains("igned in") }?.trim() ?: "已登录。",
                PiMcpLoginText.alreadySignedIn(text),
            )
        }
        if (outcome.exitCode == 0) return Result.Ok(text.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "完成。", false)
        return Result.Failed(PiMcpLoginText.failureLine(text) ?: "登录失败（退出码 ${outcome.exitCode}）。")
    }
}
