package app.pi.packages

/**
 * 引擎重启的结果类型（成功 / 被拒 / 失败）。
 *
 * 以前这里是一台状态机：装包 / 改资源之后「有改动待重启」→ 等回合结束 → 确认 → 重启。那是 RPC 时代
 * 「资源只在进程启动时读一次」的产物。现在扩展、技能、提示词、主题、MCP 都走宿主的 `session.reload`
 * 热重载，不重启进程，状态机已删；只有「进程开关」（代理、内存上限这类只在新进程里生效的选项）
 * 才还需要真重启，结果就是下面这三种。
 */
object EngineRestartCoordinator {

    sealed interface Outcome {
        data object Ok : Outcome

        /** Nothing was changed; the engine is still running. */
        data class Refused(val message: String) : Outcome

        /** The engine was stopped and could not be brought back. */
        data class Failed(val message: String) : Outcome
    }
}

/**
 * Map the engine's richer result onto [EngineRestartCoordinator.Outcome].
 *
 * `RefusedNeedsProvisioning` is folded into [EngineRestartCoordinator.Outcome.Failed]
 * rather than `Refused`, because the runtime genuinely does need re-provisioning and
 * the user has to be told: `RuntimeProvisioner.wipe()` deletes the guest's
 * `/root/.pi/agent`, taking `trust.json` and every installed package with it. Calling
 * that "refused, nothing to see" would be the quiet version of a destructive event.
 */
fun app.pi.engine.PiEngineHost.Restart.asOutcome(): EngineRestartCoordinator.Outcome = when (this) {
    is app.pi.engine.PiEngineHost.Restart.Ok -> EngineRestartCoordinator.Outcome.Ok
    is app.pi.engine.PiEngineHost.Restart.RefusedTurnRunning ->
        EngineRestartCoordinator.Outcome.Refused(detail)
    is app.pi.engine.PiEngineHost.Restart.RefusedNeedsProvisioning ->
        EngineRestartCoordinator.Outcome.Failed(detail)
    is app.pi.engine.PiEngineHost.Restart.Failed ->
        EngineRestartCoordinator.Outcome.Failed(
            if (detail.isNullOrBlank()) message else "$message\n$detail",
        )
}
