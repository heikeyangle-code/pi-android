package app.pi.packages

/** 在 guest 里跑 `pi mcp list --json`（会真的去连每个已启用的服务器，所以只在用户点「检测」时跑）。 */
class PiMcpCheck(private val layout: AgentLayout, private val guest: GuestCommand) {
    sealed interface Result {
        data class Ok(val report: PiMcpStatus.Report) : Result
        data class Failed(val message: String) : Result
    }

    fun run(): Result {
        if (!layout.runtimeReady()) return Result.Failed("运行时尚未就绪。")
        if (!layout.engineInstalled()) return Result.Failed("引擎未安装。")
        val outcome = guest.run(
            guestCommand = "exec ${layout.guestNode} ${layout.guestEngineCli} mcp list --json",
            cwd = layout.guestWorkspace,
            timeoutMs = 90_000L,
        )
        outcome.launchError?.let { return Result.Failed("无法启动运行时：$it") }
        if (outcome.timedOut) return Result.Failed("检测超时：有服务器一直连不上。")
        val report = PiMcpStatus.parse(outcome.stdout.ifBlank { outcome.combined })
            ?: return Result.Failed(outcome.combined.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "没有读到结果。")
        return Result.Ok(report)
    }
}
