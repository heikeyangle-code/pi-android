package app.pi.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * pi 自己算出来的「实际会加载哪些资源」，以及每一条的开关状态。
 *
 * 数据来自 `assets/guest/pi-resources.mjs`：它在 guest 里调用 pi 的
 * `DefaultPackageManager.resolve()` 与 `pi config` 用的 `ConfigSelectorComponent`，所以
 * 这里的分组、来源、启用与否、项目对全局的「继承 / 加载 / 屏蔽」三态都是 pi 的结论，
 * App 不再靠扫目录去猜（扫目录看不到 git 包、`package.json` 里 `pi` 清单声明的资源，
 * 也看不到内置扩展）。本文件只做一件事：把那段 JSON 变成类型化的值。纯逻辑，可单测。
 */
object PiResourceCatalog {

    enum class Type(val key: String, val label: String) {
        Extensions("extensions", "扩展"),
        Skills("skills", "技能"),
        Prompts("prompts", "提示模板"),
        Themes("themes", "主题"),
        ;

        companion object {
            fun of(key: String): Type? = entries.firstOrNull { it.key == key }
        }
    }

    /** 项目层相对全局层的状态：照抄 `pi config` 项目视图里的 灰 / [+] / [-]。 */
    enum class Override {
        Inherit, Load, Unload,
        ;

        companion object {
            fun of(raw: String?): Override = when (raw) {
                "load" -> Load
                "unload" -> Unload
                else -> Inherit
            }
        }
    }

    enum class Layer(val key: String) { Global("global"), Project("project") }

    data class Item(
        val type: Type,
        val path: String,
        val name: String,
        val enabled: Boolean,
        val baseDir: String?,
        val override: Override,
        /** 项目视图里：这条其实是全局的（灰显，可用 + / - 覆盖）。 */
        val inherited: Boolean,
        val inheritedEnabled: Boolean?,
    )

    /** 一组资源的来源：一个包、`settings.json` 里直接列的、自动发现的目录，或内置。 */
    enum class SourceKind { Package, Builtin, Directory, Settings }

    data class Group(
        val key: String,
        /** pi 自己的组标题（`npm:foo (user)`、`User (~/.pi/agent/)`），不翻译。 */
        val label: String,
        val kind: SourceKind,
        val projectScoped: Boolean,
        val source: String,
        val items: List<Item>,
    ) {
        fun items(type: Type): List<Item> = items.filter { it.type == type }
    }

    data class Snapshot(
        val trusted: Boolean,
        val global: List<Group>,
        val project: List<Group>,
        /** pi 解析时没装上的包（`installFailures`）。 */
        val failures: List<String>,
    ) {
        fun layer(layer: Layer): List<Group> = if (layer == Layer.Global) global else project
        val total: Int get() = global.sumOf { it.items.size }
    }

    data class Toggled(val enabled: Boolean?, val override: Override)

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Error(val message: String) : Parsed<Nothing>
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 脚本把结果放在最后一行；前面可能有 node 的实验性警告，所以取最后一个 `{` 开头的行。 */
    private fun payload(text: String): JsonObject? {
        val line = text.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("{") } ?: return null
        return runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.errorOrNull(): String? = str("error")

    fun parseSnapshot(text: String): Parsed<Snapshot> {
        val root = payload(text) ?: return Parsed.Error("pi 没有返回可读的资源清单。")
        root.errorOrNull()?.let { return Parsed.Error(it) }
        fun groups(key: String): List<Group> = (root[key] as? JsonArray).orEmpty().mapNotNull { parseGroup(it) }
        return Parsed.Ok(
            Snapshot(
                trusted = (root["trusted"] as? JsonPrimitive)?.booleanOrNull ?: false,
                global = groups("global"),
                project = groups("project"),
                failures = (root["installFailures"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            ),
        )
    }

    private fun parseGroup(element: kotlinx.serialization.json.JsonElement): Group? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val source = obj.str("source") ?: return null
        val origin = obj.str("origin")
        val kind = when {
            source == "builtin" -> SourceKind.Builtin
            origin == "package" -> SourceKind.Package
            source == "auto" -> SourceKind.Directory
            else -> SourceKind.Settings
        }
        val items = (obj["subgroups"] as? JsonArray).orEmpty().flatMap { sub ->
            val s = runCatching { sub.jsonObject }.getOrNull() ?: return@flatMap emptyList()
            val type = Type.of(s.str("type") ?: "") ?: return@flatMap emptyList()
            (s["items"] as? JsonArray).orEmpty().mapNotNull { parseItem(type, it) }
        }
        return Group(
            key = obj.str("key") ?: source,
            label = obj.str("label") ?: source,
            kind = kind,
            projectScoped = obj.str("scope") == "project",
            source = source,
            items = items,
        )
    }

    private fun parseItem(type: Type, element: kotlinx.serialization.json.JsonElement): Item? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val path = obj.str("path") ?: return null
        return Item(
            type = type,
            path = path,
            name = obj.str("name") ?: path.substringAfterLast('/'),
            enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            baseDir = obj.str("baseDir"),
            override = Override.of(obj.str("override")),
            inherited = (obj["inherited"] as? JsonPrimitive)?.booleanOrNull ?: false,
            inheritedEnabled = (obj["inheritedEnabled"] as? JsonPrimitive)?.booleanOrNull,
        )
    }

    fun parseToggle(text: String): Parsed<Toggled> {
        val root = payload(text) ?: return Parsed.Error("pi 没有返回开关结果。")
        root.errorOrNull()?.let { return Parsed.Error(it) }
        if ((root["ok"] as? JsonPrimitive)?.booleanOrNull != true) return Parsed.Error("pi 没有确认这次修改。")
        return Parsed.Ok(
            Toggled(
                enabled = (root["enabled"] as? JsonPrimitive)?.booleanOrNull,
                override = Override.of(root.str("override")),
            ),
        )
    }

    /**
     * 这一条在某一层里「现在算不算开」。全局视图看 [Item.enabled]；项目视图里被覆盖的
     * 按覆盖说了算，没覆盖的跟随继承值（pi 的 `getInheritedEnabled`）。
     */
    fun effectiveEnabled(item: Item, layer: Layer): Boolean = when {
        layer == Layer.Global -> item.enabled
        item.override == Override.Load -> true
        item.override == Override.Unload -> false
        else -> item.enabled
    }
}
