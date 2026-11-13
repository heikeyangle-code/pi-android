package app.pi.host

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * 「扩展 / MCP」界面要的宿主入口打成一包，由 ViewModel 在引擎起来时给出（没起来为 null）。
 * 界面不碰引擎会话本身：命令走 [ext]，进度 / 授权网址走 [events]，要用户贴回跳地址的提问走 [requests]，
 * 答复走 [respond]。
 */
class ExtensionsAccess(
    val ext: HostExtensions,
    val events: Flow<HostSignal.Event>,
    val requests: Flow<HostRequest>,
    val respond: suspend (requestId: String, value: JsonElement?, cancelled: Boolean) -> Unit,
)
