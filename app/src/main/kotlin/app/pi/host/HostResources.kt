package app.pi.host

import app.pi.packages.PiLoadedResources
import app.pi.packages.PiProjectTrust
import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceFile
import app.pi.packages.PiResourceCatalog.Parsed
import kotlinx.serialization.json.Json
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

    /** 此刻**实际加载**的资源：扩展（含注册了什么）、技能、提示、主题、上下文文件、系统提示，以及 SDK 的诊断。 */
    suspend fun loaded(): Parsed<PiLoadedResources.Loaded> =
        run("resources.loaded", JsonObject(emptyMap())) { text ->
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            if (o == null) Parsed.Error("宿主返回的资源清单读不懂。") else Parsed.Ok(PiLoadedResources.parse(o))
        }

    /** 项目信任的现状（`project.trustState`）：结论、来源、记录、默认策略。 */
    suspend fun trustState(): Parsed<PiProjectTrust.State> = trust { it.trustState() }

    /** 项目信任的表态（官方弹窗的回答）：写进 pi 的信任库（或只记本次）并重载会话；应答是新的状态。 */
    suspend fun setTrust(option: PiProjectTrust.Option): Parsed<PiProjectTrust.State> =
        trust { it.setTrust(option.trusted, option.remember.key) }

    // ---- 资源文件：技能 / 提示 / 主题 / 扩展 / 上下文文件。位置白名单与「项目未信任不让动」在宿主判。 ----

    suspend fun readFile(path: String): Parsed<PiResourceFile.Doc> =
        fileCall("resources.file.read", mapOf("path" to JsonPrimitive(path))) { PiResourceFile.parseDoc(it) }

    /** [stamp] 是打开时读到的指纹；宿主发现文件已被别处改过会拒绝（原话：「文件在你编辑期间被改过…」）。 */
    suspend fun writeFile(path: String, text: String, stamp: PiResourceFile.Stamp?): Parsed<PiResourceFile.Stamp> =
        fileCall(
            "resources.file.write",
            buildMap {
                put("path", JsonPrimitive(path))
                put("text", JsonPrimitive(text))
                if (stamp != null) {
                    put("expectSize", JsonPrimitive(stamp.size))
                    put("expectMtimeMs", JsonPrimitive(stamp.mtimeMs))
                }
            },
        ) { PiResourceFile.parseStamp(it) }

    /** 新建一个资源文件（技能 / 提示 / 扩展 / 上下文）；返回它的路径。已存在会被拒绝。 */
    suspend fun createFile(kind: PiResourceFile.Kind, project: Boolean, name: String?): Parsed<String> =
        fileCall(
            "resources.file.create",
            buildMap {
                put("kind", JsonPrimitive(kind.key))
                put("scope", JsonPrimitive(if (project) "project" else "user"))
                if (name != null) put("name", JsonPrimitive(name))
            },
        ) { (it["path"] as? JsonPrimitive)?.content.orEmpty() }

    suspend fun deleteFile(path: String): Parsed<Unit> =
        fileCall("resources.file.delete", mapOf("path" to JsonPrimitive(path))) { }

    private suspend fun <T> fileCall(cmd: String, args: Map<String, kotlinx.serialization.json.JsonElement>, parse: (JsonObject) -> T): Parsed<T> {
        val c = commands() ?: return Parsed.Error(NO_ENGINE)
        return try {
            Parsed.Ok(parse(c.call(cmd, JsonObject(args), HostCommands.LONG_TIMEOUT_MS).data))
        } catch (e: HostException) {
            Parsed.Error(e.message ?: e.code)
        }
    }

    private suspend fun trust(call: suspend (HostCommands) -> HostReply): Parsed<PiProjectTrust.State> {
        val c = commands() ?: return Parsed.Error(NO_ENGINE)
        return try {
            Parsed.Ok(PiProjectTrust.parse(call(c).data))
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
