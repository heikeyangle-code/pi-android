package app.pi.host

import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceCatalog.Parsed
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 「资源」（`pi config` 的那张清单）走宿主：`resources.list` / `resources.toggle` / `packages.updates`。
 *
 * 以前这是 `PiResources`：每次读都在 guest 里**另起一个 node 进程**把引擎冷启动一遍（几秒），
 * 自己再解析输出。宿主在引擎进程里就有 SDK 的 `DefaultPackageManager` + `ConfigSelectorComponent`，
 * 读开关是一次函数调用。宿主吐的 JSON 与旧脚本同形，所以解析仍用 [PiResourceCatalog] 里那几个纯函数；
 * 失败是宿主的原话（[HostException.message]），不改写。
 *
 * 引擎没起来时没有宿主可问：返回一句明确的话，而不是空清单。
 */
class HostResources(private val commands: () -> HostCommands?) {

    suspend fun list(): Parsed<PiResourceCatalog.Snapshot> =
        run("resources.list", JsonObject(emptyMap()), PiResourceCatalog::parseSnapshot)

    suspend fun toggle(layer: PiResourceCatalog.Layer, item: PiResourceCatalog.Item): Parsed<PiResourceCatalog.Toggled> =
        run(
            "resources.toggle",
            JsonObject(mapOf(
                "scope" to JsonPrimitive(layer.key),
                "type" to JsonPrimitive(item.type.key),
                "path" to JsonPrimitive(item.path),
            )),
        ) { text -> PiResourceCatalog.parseToggle(withOk(text)) }

    /** 官方 `checkForAvailableUpdates()`：只查不装，要联网。 */
    suspend fun updates(): Parsed<List<PiResourceCatalog.Update>> =
        run("packages.updates", JsonObject(emptyMap()), PiResourceCatalog::parseUpdates)

    /** 项目信任的表态（官方弹窗的回答）：写进 pi 的信任库并重载会话。 */
    suspend fun setTrust(trusted: Boolean): Parsed<Boolean> {
        val c = commands() ?: return Parsed.Error(NO_ENGINE)
        return try {
            c.setTrust(trusted)
            Parsed.Ok(trusted)
        } catch (e: HostException) {
            Parsed.Error(e.message ?: e.code)
        }
    }

    private suspend fun <T> run(cmd: String, args: JsonObject, parse: (String) -> Parsed<T>): Parsed<T> {
        val c = commands() ?: return Parsed.Error(NO_ENGINE)
        return try {
            parse(c.call(cmd, args, HostCommands.LONG_TIMEOUT_MS).data.toString())
        } catch (e: HostException) {
            Parsed.Error(e.message ?: e.code)
        }
    }

    /** 宿主的 toggle 应答没有旧脚本那个 `ok:true`；失败走异常，所以走到这里就是成功。 */
    private fun withOk(text: String): String =
        if (text.startsWith("{")) "{\"ok\":true," + text.substring(1) else text

    private companion object {
        const val NO_ENGINE = "引擎还没起来，起来之后才能读资源。"
    }
}
