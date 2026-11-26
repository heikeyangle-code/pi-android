package app.pi.ui.models

import app.pi.host.HostAdvanced
import app.pi.host.HostAuth
import app.pi.host.HostRequest
import app.pi.host.HostSignal
import app.pi.protocol.PiResponses
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 「模型与供应商」页要用到的宿主能力，收成一个对象：页面不认识 `PiEngineSession`，
 * 也就不必知道引擎怎么起、怎么重启——引擎不在时 ViewModel 直接给 null，页面只说一句话。
 */
class ModelsHost(
    val auth: HostAuth,
    val advanced: HostAdvanced,
    /** 宿主的事件（要 `auth.event` / `ui.cancel`）。 */
    val events: Flow<HostSignal.Event>,
    /** 宿主的提问（要 `auth.prompt`）。 */
    val requests: SharedFlow<HostRequest>,
    /** 答复一条提问：[value] 为输入；[cancelled] 为用户取消。 */
    val answer: suspend (requestId: String, value: String?, cancelled: Boolean) -> Unit,
    /** 此刻可用的全部模型（`model.list`）。 */
    val models: suspend () -> List<PiResponses.ModelInfo>,
    /** 目录里全部模型（含还没配置凭据的供应商），只取 [provider] 一家（`model.list {all}`）。 */
    val catalog: suspend (provider: String) -> List<PiResponses.ModelInfo>,
    /**
     * 选中某个模型作为当前模型（`model.set`）。[persist] = false 只改本会话；true 同时写进
     * 全局默认（SDK 的 `ModelMutationOptions.persist`）。
     */
    val selectModel: suspend (provider: String, modelId: String, persist: Boolean) -> Unit,
    /** 当前模型 `provider/id`，没有则 null。 */
    val currentModel: () -> Pair<String, String>?,
    /** 当前会话思考等级（SDK `session.thinkingLevel`）。 */
    val currentThinking: () -> String = { "medium" },
    /** 当前模型支持的思考等级列表（`thinking.levels`）。 */
    val thinkingLevels: suspend () -> List<String> = { emptyList() },
    /** 立即设置当前会话的思考等级（`thinking.set`）。 */
    val setThinking: suspend (level: String) -> Unit = {},
    /** 通过 SDK `SettingsManager.setDefaultThinkingLevel` 写入全局默认思考等级。 */
    val setDefaultThinkingLevel: suspend (level: String) -> Unit = {},
    /** 通过 SDK `SettingsManager.setModelThinkingLevel` 写入逐模型思考等级覆盖。 */
    val setModelThinkingLevel: suspend (provider: String, modelId: String, level: String) -> Unit = { _, _, _ -> },
    /** 通过 SDK `SettingsManager.removeModelThinkingLevel` 移除逐模型思考等级覆盖。 */
    val removeModelThinkingLevel: suspend (provider: String, modelId: String) -> Unit = { _, _ -> },
    /** 通过 SDK `SettingsManager.setEnabledModels` 持久化作用域/循环模型列表（空列表 = 清除限制）。 */
    val setEnabledModels: suspend (patterns: List<String>?) -> Unit = {},
    /** 通过 SDK `SettingsManager.setNotice(key, shown)` 切换细粒度对话诊断提示（Pi 1.6.1）。 */
    val setNotice: suspend (key: String, show: Boolean) -> Unit = { _, _ -> },
)
