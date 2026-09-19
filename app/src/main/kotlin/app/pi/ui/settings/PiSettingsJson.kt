package app.pi.ui.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON shape helpers.
 *
 * Everything here is lossless in one direction only where that is safe: scalars
 * and arrays of scalars round-trip byte-for-byte through their raw text, so a
 * glob entry carrying an exclude marker, or a theme name pair such as
 * `"light/dark"`, is written back exactly as it was read.
 *
 * pi's schema is not uniformly string-valued, and pretending otherwise would
 * corrupt the file:
 *
 *  - `terminal.hyperlinks` and `terminal.trueColor` are `boolean | "auto"`, so
 *    "force on" has to write a real JSON `true`, not the string `"true"`.
 *  - `terminal.images` is `"kitty" | "iterm2" | "auto" | false`.
 *  - `outputPad` is `0 | 1`, i.e. numbers.
 *  - `compaction.modelOverrides` maps a model to an object, and `packages` may
 *    hold objects next to strings.
 *
 * So a list entry's value part is parsed as JSON when it looks like JSON (starts
 * with a brace or bracket) and as a typed scalar otherwise. Nested values are
 * rendered back as compact JSON, which is exactly what pi reads.
 */

private val jsonParser = Json {
    isLenient = false
    allowTrailingComma = false
    allowSpecialFloatingPointValues = true
}

internal fun JsonElement?.primitiveText(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

internal fun JsonElement?.stringValue(fallback: String): String = primitiveText() ?: fallback

internal fun JsonElement?.stringValueOrNull(): String? = primitiveText()

internal fun JsonElement?.boolValue(fallback: Boolean): Boolean =
    when (val text = primitiveText()) {
        null -> fallback
        else -> text.toBooleanStrictOrNull() ?: fallback
    }

internal fun JsonElement?.intValueOrNull(): Int? =
    primitiveText()?.let { text -> text.toIntOrNull() ?: text.toDoubleOrNull()?.toInt() }

internal fun JsonElement?.intValue(fallback: Int): Int = intValueOrNull() ?: fallback

/** `true` / `false` for booleans, a number for numerics, otherwise the text. */
internal fun scalarOf(text: String): JsonElement {
    val trimmed = text.trim()
    return when {
        trimmed.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        trimmed.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        trimmed.toLongOrNull() != null -> JsonPrimitive(trimmed.toLong())
        trimmed.toDoubleOrNull() != null -> JsonPrimitive(trimmed.toDouble())
        else -> JsonPrimitive(text)
    }
}

/**
 * Parses the value half of an editor line.
 *
 * Text that opens with a brace or bracket is JSON and is kept as JSON, so an
 * object-valued entry such as a per-model compaction override or a filtered
 * `packages` entry survives editing instead of being flattened into a string.
 * Everything else goes through [scalarOf].
 */
internal fun valueElementOf(text: String): JsonElement {
    val trimmed = text.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        val parsed = runCatching { jsonParser.parseToJsonElement(trimmed) }.getOrNull()
        if (parsed != null) return parsed
    }
    return scalarOf(trimmed)
}

/** Compact JSON for nested values, raw text for scalars and null. */
internal fun elementText(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonPrimitive -> element.content
    else -> element.toString()
}

/**
 * Raw, user-editable lines for a list-valued setting.
 *
 * Array settings yield one line per element (glob markers intact, object
 * elements kept as compact JSON). Object settings yield `key = value` lines in
 * their declared order.
 */
internal fun PiSetting.editableEntries(element: JsonElement?): List<String> = when (container) {
    PiValueContainer.Array -> (element as? JsonArray)?.map { elementText(it) } ?: emptyList()
    PiValueContainer.Object ->
        (element as? JsonObject)?.entries?.map { (name, value) -> "$name = ${elementText(value)}" }
            ?: emptyList()
}

/**
 * Rebuilds the value from edited lines. Blank lines are dropped; everything else
 * is preserved exactly, including exclusion markers and JSON sub-objects.
 */
internal fun PiSetting.elementFromEntries(entries: List<String>): JsonElement = when (container) {
    PiValueContainer.Array ->
        JsonArray(entries.map { it.trim() }.filter { it.isNotEmpty() }.map { valueElementOf(it) })

    PiValueContainer.Object ->
        JsonObject(
            entries.mapNotNull { line ->
                val text = line.trim()
                if (text.isEmpty()) return@mapNotNull null
                val separator = text.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = text.substring(0, separator).trim()
                val value = text.substring(separator + 1).trim()
                if (name.isEmpty()) null else name to valueElementOf(value)
            }.toMap(),
        )
}

/** Short human-readable rendering of a value, used by rows, summaries and search. */
internal fun PiSetting.display(element: JsonElement?): String {
    val present = element != null && element !is JsonNull
    return when (kind) {
        PiRowKind.Switch -> if (element.boolValue(defaultValue?.boolValue(false) ?: false)) "开" else "关"

        PiRowKind.Value -> {
            if (!present) {
                "未设置"
            } else {
                val raw = element.primitiveText() ?: element.toString()
                val option = options.firstOrNull { it.wire == raw }
                option?.label ?: raw.ifEmpty { "未设置" }
            }
        }

        PiRowKind.Number -> {
            val number = element.intValueOrNull()
            if (number == null) "未设置" else number.toString() + (unit?.let { " $it" } ?: "")
        }

        PiRowKind.Text -> {
            val text = element.primitiveText()
            if (text.isNullOrEmpty()) "未设置" else text
        }

        PiRowKind.List -> {
            val count = when (container) {
                PiValueContainer.Array -> (element as? JsonArray)?.size ?: 0
                PiValueContainer.Object -> (element as? JsonObject)?.size ?: 0
            }
            if (count == 0) emptyListLabel else "$count 项"
        }

        PiRowKind.Action -> ""
    }
}

/** The value a store holds for a setting, falling back to the registry default. */
internal fun PiSetting.current(store: PiSettingsStore): JsonElement? = store.read(key) ?: defaultValue

/** The value a store holds for a setting, or [fallback] when unset. */
internal fun PiSetting.boolIn(store: PiSettingsStore, fallback: Boolean = false): Boolean {
    val explicit = store.read(key)
    if (explicit != null) return explicit.boolValue(fallback)
    return defaultValue?.boolValue(fallback) ?: fallback
}

internal fun PiSetting.intIn(store: PiSettingsStore, fallback: Int): Int {
    val explicit = store.read(key)
    if (explicit != null) return explicit.intValue(fallback)
    return defaultValue?.intValue(fallback) ?: fallback
}

internal fun PiSetting.textIn(store: PiSettingsStore, fallback: String = ""): String =
    store.read(key)?.primitiveText() ?: defaultValue?.primitiveText() ?: fallback

/** Number of items currently in a list-valued setting. */
internal fun PiSetting.countIn(store: PiSettingsStore): Int {
    val element = store.read(key) ?: defaultValue
    return when (container) {
        PiValueContainer.Array -> (element as? JsonArray)?.size ?: 0
        PiValueContainer.Object -> (element as? JsonObject)?.size ?: 0
    }
}

internal fun PiSetting.isExplicit(store: PiSettingsStore): Boolean = store.read(key) != null

/**
 * 这一行即将写入的值，pi 能不能接受。
 *
 * 判定本身在 `PiSettingsValidation.kt`（纯函数、不依赖 `PiSetting`、不 import Compose），因为
 * bare-JVM 的 `settings-validation` harness 要把它和 `main` 一起编译——本文件里的这些
 * `PiSetting` 扩展做不到这一点。这里只负责把行的 `key` 接上去。
 *
 * 编辑器接线后必须按结果分档处置：`Rejected` **禁止保存**并在 sheet 内联报错
 * （`httpIdleTimeoutMs: null` 会让 pi 起不来，见 `docs/settings-audit-impl.md` §B1）；
 * `Suspicious` 提示但允许保存（pi 自己就允许）。
 */
internal fun PiSetting.verdictForWrite(value: JsonElement): PiValueVerdict =
    piValueVerdict(key, value)

/**
 * 对象型列表编辑器（`compaction.modelOverrides` / `thinkingBudgets` / `modelThinkingLevels`）
 * 里**一行**的判定，消息指到具体是哪一行。
 */
internal fun PiSetting.verdictForLine(name: String, value: JsonElement): PiValueVerdict =
    piObjectLineVerdict(key, name, value)

/**
 * 这个列表行当前的行文本能不能保存；返回要显示的问题句，能保存则返回 null。
 *
 * 两个来源的问题，都是"保存下去就坏"：
 *
 *  1. **pi 会 `throw` 的值** —— 交给 `PiSettingsValidation`（纯函数、有 bare-JVM harness）。
 *     例：`compaction.modelOverrides` 的 `x = abc` → pi 在每回合的压缩判定里抛
 *     （`core/settings-manager.ts:854-880` → `core/agent-session.ts:540`）。
 *  2. **我们自己会静默丢掉的行** —— 没有 `=` 的行、`=` 前为空的行。`elementFromEntries`
 *     现在把它们 `mapNotNull` 掉，用户敲的字就这么没了。这不是 pi 的规则，是我们自己的
 *     解析器的数据丢失，所以同样拒绝保存。
 *
 * `Suspicious` **不拦**：pi 接受它（数组/对象里多一个它不读的键、`thinkingBudgets` 里一个
 * 它只透传给厂商的值），把它做成硬错误就是发明一条 pi 没有的规范。
 */
internal fun PiSetting.validateEntries(entries: List<String>): String? {
    if (container != PiValueContainer.Object) return null
    entries.forEachIndexed { index, line ->
        val text = line.trim()
        if (text.isEmpty()) return@forEachIndexed
        val separator = text.indexOf('=')
        if (separator <= 0) {
            return "第 ${index + 1} 行没有 `=`，保存下去它会被丢掉。写成 `名字 = 值`。"
        }
        val name = text.substring(0, separator).trim()
        if (name.isEmpty()) {
            return "第 ${index + 1} 行 `=` 前面没有名字，保存下去它会被丢掉。"
        }
        val value = valueElementOf(text.substring(separator + 1).trim())
        when (val verdict = verdictForLine(name, value)) {
            is PiValueVerdict.Rejected -> return verdict.message
            else -> Unit
        }
    }
    return null
}
