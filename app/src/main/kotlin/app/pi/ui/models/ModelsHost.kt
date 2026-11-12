package app.pi.ui.models

import app.pi.host.HostAdvanced
import app.pi.host.HostAuth
import app.pi.host.HostRequest
import app.pi.host.HostSignal
import app.pi.rpc.PiResponses
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 「模型与供应商」页要用到的宿主能力，收成一个对象：页面不认识 `PiEngineSession`，
 * 也就不必知道引擎怎么起、怎么重启——引擎不在时 ViewModel 直接给 null，页面只说一句话。
 */
class ModelsHost(
    val auth: HostAuth,
    val advanced: HostAdvanced,
    /** 宿主的事件（要 `auth.event`）。 */
    val events: Flow<HostSignal.Event>,
    /** 宿主的提问（要 `auth.prompt`）。 */
    val requests: SharedFlow<HostRequest>,
    /** 答复一条提问：[value] 为输入；[cancelled] 为用户取消。 */
    val answer: suspend (requestId: String, value: String?, cancelled: Boolean) -> Unit,
    /** 此刻可用的全部模型（`model.list`）。 */
    val models: suspend () -> List<PiResponses.ModelInfo>,
    /** 选中某个模型作为当前模型（`model.set`）。 */
    val selectModel: suspend (provider: String, modelId: String) -> Unit,
    /** 当前模型 `provider/id`，没有则 null。 */
    val currentModel: () -> Pair<String, String>?,
)
