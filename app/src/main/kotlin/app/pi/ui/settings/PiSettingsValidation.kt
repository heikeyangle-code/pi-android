package app.pi.ui.settings

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按 **pi 真正实现的解析语义**判定一个值能不能被 pi 接受。
 *
 * ## 为什么要有这个文件，为什么不放在 `PiSettingsJson.kt`
 *
 * 这里必须是**纯函数**、**不依赖 `PiSetting`**、**不 import Compose**：
 * `tools/run-app-pure-checks.sh` 的 bare-JVM harness 要把这个文件和它的 `main` **一起编译**，
 * 而 `PiSettingsJson.kt` 里的 `PiSetting.editableEntries`/`display` 等声明依赖 `PiSetting`
 * （在导入 Compose 的 `PiSettingsRegistry.kt` 里），一起编就带进 Material 图标，编译不过。
 * 所以判定逻辑放这里（可被 harness 覆盖），`PiSetting` 的桥接留在 `PiSettingsJson.kt`。
 *
 * ## 判定的三档，而不是布尔
 *
 * `Settings` 的 getter 对坏值有两种相反的处置，这不是一枚布尔能表达的事：
 *
 *  - **`Rejected`** —— pi **抛异常**。这不是"值不好看"，是把配置文件弄坏：`httpIdleTimeoutMs`
 *    为 `null` 时 `pi --mode rpc` **启动**就抛（`main.ts:850-851` → `settings-manager.ts:936`
 *    → `:186-196` `parseTimeoutSetting`），`compaction.reserveTokens` 为 `null` 时每个回合的
 *    压缩判定都抛（`settings-manager.ts:854-880` → `core/agent-session.ts:540`）。
 *    编辑器必须**拒绝保存**这种值。
 *  - **`Suspicious`** —— pi 不抛，但这个值会被当成数字/枚举/对象用，行为会坏或者厂商会报错
 *    （例如 `thinkingBudgets` 里的字符串、`modelThinkingLevels` 里拼错的等级）。
 *    编辑器应当提示但可以允许（pi 自己就允许）。
 *  - **`Ok`** —— pi 会原样接受。
 *
 * ## 逐条的 pi 依据（HEAD bbb61e3）
 *
 *  | 键 | pi 的实现 | 判定依据 |
 *  |---|---|---|
 *  | `httpIdleTimeoutMs` / `websocketConnectTimeoutMs` | `parseTimeoutSetting(value, name)`：先 `parseHttpIdleTimeoutMs(value)`；解析不出**且 `value !== undefined`** 就 `throw` | `core/settings-manager.ts:186-196`；`core/http-dispatcher.ts:19-35` |
 *  | `compaction.reserveTokens` / `keepRecentTokens` | `ordinary !== undefined && (typeof !== "number" \|\| !Number.isSafeInteger \|\| < 0)` → `throw` | `core/settings-manager.ts:854-880` |
 *  | `compaction.modelOverrides` | 条目必须 `isMergeableObject`，其 `reserveTokens`/`keepRecentTokens` 必须是非负安全整数，否则 `throw` | 同上 |
 *  | `thinkingBudgets` | `getThinkingBudgets()` **原样返回**，一路透到 provider（`?? default`） | `core/settings-manager.ts:1174-1176`、`core/sdk.ts:370`、`packages/ai/src/types.ts:100-105` |
 *  | `modelThinkingLevels` | `Record<"provider/modelId", ThinkingLevel>`，`ThinkingLevel = minimal\|low\|medium\|high\|xhigh\|max` | `packages/ai/src/types.ts:83`、`core/settings-manager.ts:111`、`:808-810` |
 *  | `outputPad` | `this.settings.outputPad === 0 ? 0 : 1` —— **只有恰好是数字 `0` 才是 0，其它一切都当 1**，从不抛 | `core/settings-manager.ts:1372-1374` |
 *
 * `retry.maxRetries`/`baseDelayMs`/`maxAgentDelayMs`/`provider.*`/`branchSummary.reserveTokens`
 * 在 pi 里是 `?? default`，**对任何值都不抛**，所以这里一律 `Ok`（它们的上界只由注册表的
 * `min`/`max` 表达，见 [piNumberTextVerdict]）。
 */

/** [piValueVerdict] 的结果。`Rejected` 必须阻止保存，`Suspicious` 应当提示。 */
internal sealed interface PiValueVerdict {
    /** pi 会原样接受。 */
    data object Ok : PiValueVerdict

    /** pi 会 `throw`。写下去等于把配置弄坏（`httpIdleTimeoutMs` 会让引擎起不来）。 */
    data class Rejected(val message: String) : PiValueVerdict

    /** pi 不抛，但这个值会让行为变坏或让厂商报错。 */
    data class Suspicious(val message: String) : PiValueVerdict
}

/** 数字编辑器"这串文本能不能存"的结果。 */
internal sealed interface PiNumberVerdict {
    /** 可保存，[value] 是**原样解析**出来的整数（没有四舍五入）。 */
    data class Parsed(val value: Int) : PiNumberVerdict

    /** 不是整数（含空串、小数、带字母）。编辑器必须禁用保存，不许悄悄写旧值。 */
    data object NotAnInteger : PiNumberVerdict

    /** 是整数但在本行的 `min`/`max` 之外。 */
    data class OutOfRange(val value: Int, val min: Int, val max: Int) : PiNumberVerdict
}

internal const val KEY_HTTP_IDLE_TIMEOUT_MS = "httpIdleTimeoutMs"
internal const val KEY_WEBSOCKET_CONNECT_TIMEOUT_MS = "websocketConnectTimeoutMs"
internal const val KEY_COMPACTION_RESERVE_TOKENS = "compaction.reserveTokens"
internal const val KEY_COMPACTION_KEEP_RECENT_TOKENS = "compaction.keepRecentTokens"
internal const val KEY_COMPACTION_MODEL_OVERRIDES = "compaction.modelOverrides"
internal const val KEY_THINKING_BUDGETS = "thinkingBudgets"
internal const val KEY_MODEL_THINKING_LEVELS = "modelThinkingLevels"
internal const val KEY_OUTPUT_PAD = "outputPad"

/** pi 的 `ThinkingLevel`（`packages/ai/src/types.ts:83`）。`off` 不在其中。 */
internal val PI_THINKING_LEVELS: Set<String> = setOf("minimal", "low", "medium", "high", "xhigh", "max")

/**
 * `modelThinkingLevels` 的值域比 `thinkingBudgets` 宽一档：pi 的 `ModelThinkingLevel` 是
 * `"off" | ThinkingLevel`（`packages/ai/src/types.ts:84`），而注册表的 `thinkingLevelOptions`
 * （`PiThinkingLevel.entries`）也含 `off`。这里**接受**它——判成"可疑"就是发明一条 pi 没有的
 * 边界，而这个校验器的判据是"pi 能不能接受"。
 */
internal val PI_MODEL_THINKING_LEVELS: Set<String> = PI_THINKING_LEVELS + "off"

/** `ThinkingBudgets` 只认这四个等级（`packages/ai/src/types.ts:100-105`）。 */
internal val PI_THINKING_BUDGET_FIELDS: Set<String> = setOf("minimal", "low", "medium", "high")

/** `compaction.modelOverrides` 的条目里 pi 会读的字段（`CompactionModelOverride`）。 */
internal val PI_COMPACTION_OVERRIDE_FIELDS: Set<String> = setOf("reserveTokens", "keepRecentTokens")

/** 两个走 `parseTimeoutSetting` 的超时键：它们的合法域是**同一套**。 */
internal fun isTimeoutSettingKey(key: String): Boolean =
    key == KEY_HTTP_IDLE_TIMEOUT_MS || key == KEY_WEBSOCKET_CONNECT_TIMEOUT_MS

/**
 * `parseTimeoutSetting` 的合法域（`core/settings-manager.ts:186-196` + `core/http-dispatcher.ts:19-35`）。
 *
 * 合法：**键缺省**（调用方不调用本函数）、一个有限的非负数字、`"disabled"`（大小写不敏感）、
 * 或一个能解析成有限非负数字的字符串（含 `"0"`、`"300000"`）。
 *
 * 非法（pi 会抛）：`null`、空串、`"abc"`、负数、`true`、对象/数组。
 * 注意 `null` 是**最危险**的那个：JS 里 `null !== undefined`，所以它走 `throw` 而不是"用默认值"。
 */
internal fun piTimeoutVerdict(value: JsonElement): PiValueVerdict = when (value) {
    is JsonNull -> PiValueVerdict.Rejected(
        "pi 对 null 会直接抛错（parseHttpIdleTimeoutMs(null) 判不出值，而 null 不是 undefined），" +
            "引擎可能起不来。请删除这个键，而不是把它设为空。",
    )

    is JsonPrimitive -> when {
        value.isString -> {
            val text = value.content.trim()
            when {
                text.equals("disabled", ignoreCase = true) -> PiValueVerdict.Ok
                text.isEmpty() -> PiValueVerdict.Rejected("空字符串在 pi 里也会抛错（它不是 undefined）。")
                else -> {
                    val number = text.toDoubleOrNull()
                    if (number == null || !number.isFinite() || number < 0) {
                        PiValueVerdict.Rejected("「$text」不是 pi 认的超时值：要毫秒数（0 表示不超时）或 disabled。")
                    } else {
                        PiValueVerdict.Ok
                    }
                }
            }
        }

        else -> {
            // 布尔、以及 JSON 里写不出来的非有限数（1e999 解析成 Infinity）都在这里被拒。
            val number = value.content.toDoubleOrNull()
            if (value.content == "true" || value.content == "false" || number == null || !number.isFinite() || number < 0) {
                PiValueVerdict.Rejected("「${value.content}」不是 pi 认的超时值：要非负的毫秒数或 disabled。")
            } else {
                PiValueVerdict.Ok
            }
        }
    }

    else -> PiValueVerdict.Rejected("超时值只能是数字或 disabled。")
}

/**
 * `getCompactionTokenSetting` 的合法域：非负的**安全整数**（`Number.isSafeInteger`）。
 *
 * `null`、字符串、小数、负数、超过 2^53-1 都会让 pi 抛错（`core/settings-manager.ts:860-867`）。
 */
internal fun piTokenCountVerdict(value: JsonElement, fieldName: String): PiValueVerdict {
    if (value is JsonNull) {
        return PiValueVerdict.Rejected("pi 对 $fieldName 的 null 会抛错（typeof null 不是 number）。请删除这个键。")
    }
    val primitive = value as? JsonPrimitive
        ?: return PiValueVerdict.Rejected("$fieldName 必须是整数。")
    // pi 的第一道检查就是 `typeof ordinary !== "number"`，所以 `"16384"`（字符串）和
    // `true`（布尔）都在这里被拒——内容能转成数字不算数，JSON 类型不算数才是判据。
    if (primitive.isString) {
        return PiValueVerdict.Rejected("$fieldName 必须是 JSON 数字，字符串不算（pi 检查的是 typeof）。")
    }
    val text = primitive.content
    val number = text.toDoubleOrNull()
        ?: return PiValueVerdict.Rejected("$fieldName 必须是整数，「$text」不是。")
    if (!number.isFinite() || number < 0 || number != Math.floor(number) || number > MAX_SAFE_INTEGER) {
        return PiValueVerdict.Rejected("$fieldName 必须是非负整数（pi 的校验就是这一条）。")
    }
    return PiValueVerdict.Ok
}

/** `compaction.modelOverrides` 的单个条目（`provider/modelId` → 值）。 */
internal fun piCompactionOverrideEntryVerdict(name: String, value: JsonElement): PiValueVerdict {
    if (value !is JsonObject) {
        return PiValueVerdict.Rejected(
            "「$name」的值必须是对象（例如 {\"reserveTokens\": 12000}）；" +
                "pi 对非对象条目会直接抛错。",
        )
    }
    for ((field, entryValue) in value) {
        if (field !in PI_COMPACTION_OVERRIDE_FIELDS) {
            return PiValueVerdict.Suspicious(
                "「$name」里的 $field pi 不读（它只读 reserveTokens / keepRecentTokens），会一直留在文件里。",
            )
        }
        when (val verdict = piTokenCountVerdict(entryValue, "$name.$field")) {
            is PiValueVerdict.Ok -> Unit
            else -> return verdict
        }
    }
    return PiValueVerdict.Ok
}

/** 整个 `compaction.modelOverrides` 对象。 */
internal fun piCompactionOverridesVerdict(value: JsonElement): PiValueVerdict {
    if (value is JsonNull) return PiValueVerdict.Ok // 键存在但为 null：pi 只会读到 undefined 层级，不抛。
    val object_ = value as? JsonObject
        ?: return PiValueVerdict.Suspicious("modelOverrides 应当是 { \"厂商/模型\": {…} } 形式。")
    for ((name, entry) in object_) {
        // pi 按精确的 `${provider}/${modelId}` 查表，键里没有 `/` 的条目永远不会被匹配到。
        val keyed = piCompactionOverrideEntryVerdict(name, entry)
        if (keyed !is PiValueVerdict.Ok) return keyed
        if (!name.contains('/')) {
            return PiValueVerdict.Suspicious(
                "「$name」里没有斜杠：pi 用 \"厂商/模型 ID\" 精确匹配，这一条永远不会生效。",
            )
        }
    }
    return PiValueVerdict.Ok
}

/** 整个 `thinkingBudgets` 对象：pi 不校验，值会被当成 token 数发给厂商。 */
internal fun piThinkingBudgetsVerdict(value: JsonElement): PiValueVerdict {
    if (value is JsonNull) return PiValueVerdict.Ok
    val object_ = value as? JsonObject
        ?: return PiValueVerdict.Suspicious("思考预算应当是 { \"等级\": 数字 } 形式。")
    for ((name, budget) in object_) {
        if (name !in PI_THINKING_BUDGET_FIELDS) {
            return PiValueVerdict.Suspicious(
                "「$name」不是 pi 的等级（只有 minimal / low / medium / high），会被忽略但留在文件里。",
            )
        }
        val primitive = budget as? JsonPrimitive
        val number = (primitive?.takeIf { !it.isString }?.content)?.toDoubleOrNull()
        if (number == null || !number.isFinite() || number < 0 || number != Math.floor(number)) {
            return PiValueVerdict.Suspicious(
                "「$name」的预算必须是整数 token 数；pi 不会拦它，但厂商会收到非法值。",
            )
        }
    }
    return PiValueVerdict.Ok
}

/** 整个 `modelThinkingLevels` 对象：键是 `厂商/模型 ID`，值必须是 pi 的六个等级之一。 */
internal fun piModelThinkingLevelsVerdict(value: JsonElement): PiValueVerdict {
    if (value is JsonNull) return PiValueVerdict.Ok
    val object_ = value as? JsonObject
        ?: return PiValueVerdict.Suspicious("逐模型思考等级应当是 { \"厂商/模型\": 等级 } 形式。")
    for ((name, level) in object_) {
        val wire = (level as? JsonPrimitive)?.content
        if (wire == null || wire !in PI_MODEL_THINKING_LEVELS) {
            return PiValueVerdict.Suspicious(
                "「$name」的等级要是 ${PI_MODEL_THINKING_LEVELS.joinToString(" / ")} 之一。",
            )
        }
        if (!name.contains('/')) {
            return PiValueVerdict.Suspicious("「$name」里没有斜杠：pi 用 \"厂商/模型 ID\" 精确匹配。")
        }
    }
    return PiValueVerdict.Ok
}

/**
 * 一个将被写到 [key] 的值的判定。未列出的键一律 `Ok` —— 这不是"没检查"，是 pi 确实不校验
 * （`?? default` 型的 getter），硬造一条 pi 没有的边界就是发明规范。
 */
internal fun piValueVerdict(key: String, value: JsonElement): PiValueVerdict = when {
    isTimeoutSettingKey(key) -> piTimeoutVerdict(value)
    key == KEY_COMPACTION_RESERVE_TOKENS || key == KEY_COMPACTION_KEEP_RECENT_TOKENS ->
        piTokenCountVerdict(value, key)

    key == KEY_COMPACTION_MODEL_OVERRIDES -> piCompactionOverridesVerdict(value)
    key == KEY_THINKING_BUDGETS -> piThinkingBudgetsVerdict(value)
    key == KEY_MODEL_THINKING_LEVELS -> piModelThinkingLevelsVerdict(value)
    key == KEY_OUTPUT_PAD -> piOutputPadVerdict(value)
    else -> PiValueVerdict.Ok
}

/**
 * `outputPad` 的**真实**语义（`core/settings-manager.ts:1372-1374`）：
 * `getOutputPad()` 是 `settings.outputPad === 0 ? 0 : 1`。
 *
 * 也就是说 pi **从不抛**，而且只有"恰好是数字 0"才是 0，`"0"`、`false`、`null`、`2`
 * 全部会被当成 **1**。注册表现在没有这一行（`docs/settings-review.md` §1.2 已移除），
 * 这个函数是给未来重新引入时用的：它表达的是"能不能被 pi 接受"，而不是"值好不好看"。
 */
internal fun piOutputPadVerdict(value: JsonElement): PiValueVerdict {
    val primitive = value as? JsonPrimitive
    if (primitive != null && !primitive.isString && (primitive.content == "0" || primitive.content == "1")) {
        return PiValueVerdict.Ok
    }
    return PiValueVerdict.Suspicious(
        "pi 只认数字 0 和 1；其它值（包括字符串 \"0\"、false、null）一律会被当成 1。",
    )
}

/**
 * 列表编辑器里一行 `key = value` 的判定。
 *
 * [name]/[value] 是**结构化之后**的名字与值（不是原始文本），所以这里复用整对象的校验器：
 * 把这一行单独装进一个单条目对象再过一遍，得到的消息就能指到具体是哪一行。
 */
internal fun piObjectLineVerdict(key: String, name: String, value: JsonElement): PiValueVerdict {
    val single = JsonObject(mapOf(name to value))
    return when (key) {
        KEY_COMPACTION_MODEL_OVERRIDES -> piCompactionOverrideEntryVerdict(name, value)

        KEY_THINKING_BUDGETS -> piThinkingBudgetsVerdict(single)
        KEY_MODEL_THINKING_LEVELS -> piModelThinkingLevelsVerdict(single)
        else -> PiValueVerdict.Ok
    }
}

/**
 * 数字编辑器文本的**严格**解析：只认整数，不四舍五入。
 *
 * 有意区别于 `intValueOrNull`（`PiSettingsJson.kt`，它把 `"1.5"` 读成 1）：那是**渲染**一个
 * 已经存在的值时的宽容，而这里是**写**一个用户刚敲的值——把 1.5 悄悄写成 1 就是"界面说的和
 * 文件里的不一样"，正是这个批量要修的 B6。
 */
internal fun parseEditedInt(text: String): Int? = text.trim().toIntOrNull()

/** 数字编辑器文本 + 本行的 `min`/`max`：能不能存，以及不能存的原因。 */
internal fun piNumberTextVerdict(text: String, min: Int, max: Int): PiNumberVerdict {
    val parsed = parseEditedInt(text) ?: return PiNumberVerdict.NotAnInteger
    if (parsed < min || parsed > max) return PiNumberVerdict.OutOfRange(parsed, min, max)
    return PiNumberVerdict.Parsed(parsed)
}

/** `Number.isSafeInteger` 的上界（2^53 − 1）。 */
private const val MAX_SAFE_INTEGER: Double = 9007199254740991.0
