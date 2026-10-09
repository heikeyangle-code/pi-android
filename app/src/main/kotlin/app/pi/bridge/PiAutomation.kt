package app.pi.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 触发器类型名。用常量而不是散落的字面量：[PiAutomation.status] 会把它们原样发布给
 * 模型和 UI，一处拼错就会让"支持的触发器"列表和实际分支分叉。
 */
private const val TRIGGER_TIME = "time"
private const val TRIGGER_NOTIFICATION = "notification"
private const val TRIGGER_BATTERY = "battery"
private const val TRIGGER_NETWORK = "network"
private const val TRIGGER_SCREEN = "screen"
private const val TRIGGER_APP = "app"

/** 动作类型名，同上。 */
private const val ACTION_NOTIFY = "notify"
private const val ACTION_SHELL = "shell"
private const val ACTION_UI = "ui"
private const val ACTION_TOAST = "toast"
private const val ACTION_VIBRATE = "vibrate"

/** 发布给模型/UI 的完整清单，顺序即展示顺序。 */
private val TRIGGER_TYPES = listOf(
    TRIGGER_TIME,
    TRIGGER_NOTIFICATION,
    TRIGGER_BATTERY,
    TRIGGER_NETWORK,
    TRIGGER_SCREEN,
    TRIGGER_APP,
)

private val ACTION_TYPES = listOf(ACTION_NOTIFY, ACTION_SHELL, ACTION_UI, ACTION_TOAST, ACTION_VIBRATE)

/** 轮询周期：时间/电量/前台应用都靠它，1 秒足够且开销可忽略。 */
private const val TICK_MS = 1000L

/** 同一通知 key 在窗口内的多次更新合并成一次触发。 */
private const val NOTIFICATION_MERGE_WINDOW_MS = 1500L

/** 历史条数上限。 */
private const val HISTORY_LIMIT = 30

/** 单个动作的重试上限（真实尝试=1+retries）。 */
private const val MAX_RETRIES = 5

/** 指数退避的封顶，防止一条坏规则把工作线程长时间占住。 */
private const val MAX_BACKOFF_MS = 30_000L

/** 动作结果里 stdout/stderr 的裁剪长度。 */
private const val ACTION_OUTPUT_LIMIT = 4000

/** 空白折叠 + 截断，与通知组件的处理保持一致。 */
private fun clipText(raw: String?, max: Int = 400): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return ""
    val collapsed = value.replace(Regex("\\s+"), " ")
    return if (collapsed.length <= max) collapsed else collapsed.substring(0, max) + "…"
}

/** 电量百分比；[BatteryManager] 不支持时退回 `ACTION_BATTERY_CHANGED` 的粘性广播。 */
private fun batteryPercent(context: Context): Int? {
    val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    val capacity = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (capacity != null && capacity in 0..100) return capacity
    val sticky = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull() ?: return null
    val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    return level * 100 / scale
}

/** 网络种类：`wifi` / `mobile` / `ethernet` / `other` / `none`。 */
private fun networkKind(context: Context): String {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return "none"
    val network = manager.activeNetwork ?: return "none"
    val caps = manager.getNetworkCapabilities(network) ?: return "none"
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }
}

private fun isScreenOn(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return power.isInteractive
}

/** 当前前台包名；无障碍服务没连上时是 `null`（不是猜测）。 */
private fun foregroundPackage(): String? =
    DeviceAccessibilityService.running()?.rootInActiveWindow?.packageName?.toString()

/** `HH:mm`。解析不了就返回 `null`，让调用方跳过而不是按 0 点处理。 */
private fun parseClock(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim()) }.getOrNull()

/**
 * 时间窗，支持跨零点（`22:00-06:00`）。格式不合规时返回 `true`（不拦），
 * 因为一个写错的时间窗不该让规则永远沉默。
 */
private fun withinWindow(spec: String): Boolean {
    val parts = spec.split("-", limit = 2)
    if (parts.size != 2) return true
    val start = parseClock(parts[0]) ?: return true
    val end = parseClock(parts[1]) ?: return true
    val now = LocalTime.now()
    val nowMinute = now.hour * 60 + now.minute
    val startMinute = start.hour * 60 + start.minute
    val endMinute = end.hour * 60 + end.minute
    return if (startMinute <= endMinute) {
        nowMinute in startMinute..endMinute
    } else {
        nowMinute >= startMinute || nowMinute <= endMinute
    }
}

/**
 * 关键字匹配。优先当正则用，正则写错时退回不区分大小写的子串匹配——
 * 一个手滑的正则不该让规则静默失效。
 */
private fun matchesKeyword(pattern: String, haystack: String): Boolean {
    val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
    return if (regex != null) regex.containsMatchIn(haystack) else haystack.contains(pattern, ignoreCase = true)
}

/**
 * 「触发器 → 条件 → 动作」的有界规则引擎。
 *
 * ### 为什么不是一堆 if-else
 *
 * 规则是数据（[Rule]），引擎是解释器：触发器产出一个候选 [(规则, 原因)]，条件过滤，
 * 去重/冷却后串行执行动作，全部结果进历史。这样 [status] 能如实告诉模型"支持哪些
 * 触发器/动作、现在哪些真的能触发"，而不是让模型去猜源码。
 *
 * ### 边界与防抖
 *
 *  - **串行**：所有触发都投递到一条 HandlerThread（[apply] 启动），多规则同时命中时
 *    按 [Rule.priority] 从高到低依次执行；`ui` 动作在阻塞调用里跑完才继续，绝不并发。
 *  - **去重**：规则级 [Rule.cooldownMs] 窗口内只触发一次；同一通知 key 的多次更新在
 *    [NOTIFICATION_MERGE_WINDOW_MS] 内合并；同一动作 id 在 [Action.idempotencyWindowMs]
 *    内重复执行会被跳过并标记为合并。
 *  - **有界恢复**：动作失败按指数退避重试，最多 [Action.retries] 次，超限放弃并记录，
 *    绝不无脑循环；策略类拒绝（`retryable=false`）不重试。
 *  - **条件触发是边沿的**：电量/网络/屏幕/前台应用只在"条件从假变真"时触发，引擎启动时
 *    已经成立的条件只做基线，不重放动作。
 *
 * ### 持久化
 *
 * 规则落盘到 `context.filesDir/pi-automation/rules.json`，历史落盘到 `history.json`，
 * 重启后由 [apply] 恢复。解析全程用 `opt*` + 默认值，坏 JSON 只会丢字段，不会抛异常。
 */
object PiAutomation {

    /** 触发器：类型 + 该类型用得上的字段，全部带默认值。 */
    data class Trigger(
        val type: String = TRIGGER_TIME,
        /** `time`：每 N 分钟。<=0 表示不用这个分支。 */
        val intervalMinutes: Int = 0,
        /** `time`：每天 `HH:mm`。 */
        val dailyAt: String = "",
        /** `notification` / `app`：限定包名；空 = 不限（`app` 必填）。 */
        val packageName: String = "",
        /** `notification`：标题或正文里的关键字（按正则解释，写错退回子串）。 */
        val keyword: String = "",
        /** `battery`：低于该值触发；-1 = 不限。 */
        val below: Int = -1,
        /** `battery`：高于该值触发；-1 = 不限。 */
        val above: Int = -1,
        /** `network`：`wifi` / `mobile` / `ethernet` / `none` / `any`。 */
        val network: String = "",
        /** `screen`：true = 亮屏触发，false = 熄屏触发。 */
        val screenOn: Boolean = true,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", type)
            put("intervalMinutes", intervalMinutes)
            put("dailyAt", dailyAt)
            put("packageName", packageName)
            put("keyword", keyword)
            put("below", below)
            put("above", above)
            put("network", network)
            put("screenOn", screenOn)
        }

        companion object {
            fun fromJson(json: JSONObject): Trigger = Trigger(
                type = json.optString("type", TRIGGER_TIME),
                intervalMinutes = json.optInt("intervalMinutes", 0),
                dailyAt = json.optString("dailyAt", ""),
                packageName = json.optString("packageName", ""),
                keyword = json.optString("keyword", ""),
                below = json.optInt("below", -1),
                above = json.optInt("above", -1),
                network = json.optString("network", ""),
                screenOn = json.optBoolean("screenOn", true),
            )
        }
    }

    /**
     * 与触发器组合的条件：`and` / `or`、时间窗、电量区间。
     * 没写任何条件时恒为真（触发器说什么就是什么）。
     */
    data class Condition(
        val logic: String = "and",
        /** `HH:mm-HH:mm`；空 = 不限。支持跨零点。 */
        val timeWindow: String = "",
        /** 电量下限（含）；-1 = 不限。 */
        val batteryMin: Int = -1,
        /** 电量上限（含）；-1 = 不限。 */
        val batteryMax: Int = -1,
    ) {
        fun matches(context: Context): Boolean {
            val checks = ArrayList<Boolean>(2)
            if (timeWindow.isNotBlank()) checks.add(withinWindow(timeWindow))
            if (batteryMin >= 0 || batteryMax >= 0) {
                val level = batteryPercent(context)
                checks.add(
                    level != null &&
                        (batteryMin < 0 || level >= batteryMin) &&
                        (batteryMax < 0 || level <= batteryMax),
                )
            }
            if (checks.isEmpty()) return true
            return if (logic.equals("or", ignoreCase = true)) checks.any { it } else checks.all { it }
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("logic", logic)
            put("timeWindow", timeWindow)
            put("batteryMin", batteryMin)
            put("batteryMax", batteryMax)
        }

        companion object {
            fun fromJson(json: JSONObject): Condition = Condition(
                logic = json.optString("logic", "and"),
                timeWindow = json.optString("timeWindow", ""),
                batteryMin = json.optInt("batteryMin", -1),
                batteryMax = json.optInt("batteryMax", -1),
            )
        }
    }

    /**
     * 动作。[id] 是幂等键：引擎在 [idempotencyWindowMs] 内丢弃同一 id 的重复执行；
     * [retries] 是失败后的重试次数上限，退避基数是 [retryBackoffMs]。
     */
    data class Action(
        val id: String = "",
        val type: String = ACTION_TOAST,
        /** `notify` 标题；其它类型忽略。 */
        val title: String = "",
        /** `notify` 正文 / `toast` 文本。 */
        val text: String = "",
        /** `shell` 命令。 */
        val command: String = "",
        val timeoutMs: Int = 15_000,
        /** `ui` 操作：key / input / scroll / dump / tap / longPress / swipe。 */
        val operation: String = "",
        /** `ui` 参数，见 [runUiAction]。 */
        val params: Map<String, String> = emptyMap(),
        /** `vibrate` 时长（毫秒）。 */
        val milliseconds: Long = 1500,
        /** `vibrate` 波形；非空时优先于 [milliseconds]。 */
        val pattern: List<Long> = emptyList(),
        /** `toast` 用 LENGTH_LONG。 */
        val longDuration: Boolean = false,
        val retries: Int = 2,
        val retryBackoffMs: Long = 500,
        val idempotencyWindowMs: Long = 5000,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("type", type)
            put("title", title)
            put("text", text)
            put("command", command)
            put("timeoutMs", timeoutMs)
            put("operation", operation)
            val paramsJson = JSONObject()
            for ((paramKey, paramValue) in params) paramsJson.put(paramKey, paramValue)
            put("params", paramsJson)
            put("milliseconds", milliseconds)
            val patternJson = JSONArray()
            for (value in pattern) patternJson.put(value)
            put("pattern", patternJson)
            put("longDuration", longDuration)
            put("retries", retries)
            put("retryBackoffMs", retryBackoffMs)
            put("idempotencyWindowMs", idempotencyWindowMs)
        }

        companion object {
            fun fromJson(json: JSONObject): Action {
                val paramsJson = json.optJSONObject("params")
                val params = LinkedHashMap<String, String>()
                if (paramsJson != null) {
                    val keys = paramsJson.keys()
                    while (keys.hasNext()) {
                        val paramKey = keys.next()
                        params[paramKey] = paramsJson.optString(paramKey, "")
                    }
                }
                val patternJson = json.optJSONArray("pattern")
                val pattern = ArrayList<Long>()
                if (patternJson != null) {
                    for (index in 0 until patternJson.length()) {
                        val value = patternJson.optLong(index, -1L)
                        if (value >= 0) pattern.add(value)
                    }
                }
                return Action(
                    id = json.optString("id", ""),
                    type = json.optString("type", ACTION_TOAST),
                    title = json.optString("title", ""),
                    text = json.optString("text", ""),
                    command = json.optString("command", ""),
                    timeoutMs = json.optInt("timeoutMs", 15_000),
                    operation = json.optString("operation", ""),
                    params = params,
                    milliseconds = json.optLong("milliseconds", 1500L),
                    pattern = pattern,
                    longDuration = json.optBoolean("longDuration", false),
                    retries = json.optInt("retries", 2),
                    retryBackoffMs = json.optLong("retryBackoffMs", 500L),
                    idempotencyWindowMs = json.optLong("idempotencyWindowMs", 5000L),
                )
            }
        }
    }

    /** 一条规则。[priority] 越大越先执行；[cooldownMs] 是规则级去重窗口。 */
    data class Rule(
        val id: String = "",
        val name: String = "",
        val enabled: Boolean = true,
        val priority: Int = 0,
        val trigger: Trigger = Trigger(),
        val condition: Condition = Condition(),
        val actions: List<Action> = emptyList(),
        val cooldownMs: Long = 60_000,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("enabled", enabled)
            put("priority", priority)
            put("trigger", trigger.toJson())
            put("condition", condition.toJson())
            val actionArray = JSONArray()
            for (action in actions) actionArray.put(action.toJson())
            put("actions", actionArray)
            put("cooldownMs", cooldownMs)
        }

        companion object {
            fun fromJson(json: JSONObject): Rule {
                val actionArray = json.optJSONArray("actions")
                val actions = ArrayList<Action>()
                if (actionArray != null) {
                    for (index in 0 until actionArray.length()) {
                        actionArray.optJSONObject(index)?.let { actions.add(Action.fromJson(it)) }
                    }
                }
                return Rule(
                    id = json.optString("id", ""),
                    name = json.optString("name", ""),
                    enabled = json.optBoolean("enabled", true),
                    priority = json.optInt("priority", 0),
                    trigger = json.optJSONObject("trigger")?.let { Trigger.fromJson(it) } ?: Trigger(),
                    condition = json.optJSONObject("condition")?.let { Condition.fromJson(it) } ?: Condition(),
                    actions = actions,
                    cooldownMs = json.optLong("cooldownMs", 60_000L),
                )
            }
        }
    }

    private val lock = Any()

    @Volatile
    private var rules: List<Rule> = emptyList()

    /** 规则 id → 上次触发时刻，用于规则级冷却与时间间隔分支。 */
    private val lastFired = HashMap<String, Long>()

    /** 条件类触发器上一次的布尔值，用于边沿检测。 */
    private val lastCondition = HashMap<String, Boolean>()

    /** 时间触发器今天是否已经跑过：规则 id → `yyyy-MM-dd`。 */
    private val dailyFired = HashMap<String, String>()

    /** 同一通知 key 上次被处理的时间。 */
    private val notificationSeen = HashMap<String, Long>()

    /** 动作 id → 上次执行时刻，用于幂等窗口。 */
    private val actionLastRun = HashMap<String, Long>()

    /** 最近 [HISTORY_LIMIT] 次「规则 → 原因 → 动作 → 结果」。 */
    private val history = ArrayDeque<JSONObject>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var workerHandler: Handler? = null

    private val started = AtomicBoolean(false)

    private val receiversRegistered = AtomicBoolean(false)

    @Volatile
    private var loaded = false

    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 只把"去重新评估"这件事丢到工作线程：广播回调跑在主线程，不能在这里读电量/跑动作。
            workerHandler?.post { appContext?.let { runCatching { evaluateConditions(it) } } }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            workerHandler?.post { appContext?.let { runCatching { evaluateConditions(it) } } }
        }
    }

    private val notificationEventListener: (JSONObject) -> Unit = { event ->
        val handler = workerHandler
        if (handler != null) {
            handler.post { runCatching { onNotificationEvent(event) } }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            runCatching { evaluateTime(context) }
            runCatching { evaluateConditions(context) }
            workerHandler?.postDelayed(this, TICK_MS)
        }
    }

    // ------------------------------------------------------------ 对接面 ----

    /**
     * 引擎自身没有系统授权可查：时间/提示/震动/通知这些动作随时可用，能不能触发由
     * 各触发器的前置（通知使用权、无障碍、通知权限、Shell 后端）决定，[status] 里逐项报告。
     */
    @Suppress("UNUSED_PARAMETER")
    fun available(context: Context): Boolean = true

    /** 启动引擎：加载规则、注册网络/屏幕监听、订阅通知事件、开一条工作线程跑轮询。幂等。 */
    fun apply(context: Context): JSONObject {
        val app = context.applicationContext
        appContext = app
        ensureLoaded(app)
        if (started.compareAndSet(false, true)) {
            val thread = HandlerThread("pi-automation")
            thread.start()
            val handler = Handler(thread.looper)
            workerHandler = handler
            registerReceivers(app)
            PiNotificationListener.addListener(notificationEventListener)
            handler.post(tickRunnable)
        }
        return status(app)
    }

    /** 新增或覆盖（同 id 覆盖）一条规则，返回真正入库的副本（id 已补齐）。 */
    fun add(rule: Rule): Rule {
        val stored = if (rule.id.isBlank()) rule.copy(id = newRuleId()) else rule
        synchronized(lock) {
            rules = rules.filterNot { it.id == stored.id } + stored
            appContext?.let { writeRules(it) }
        }
        return stored
    }

    /** 删除一条规则；返回是否确实删掉了。 */
    fun remove(id: String): Boolean = synchronized(lock) {
        val previous = rules.size
        rules = rules.filterNot { it.id == id }
        val removed = rules.size != previous
        appContext?.let { writeRules(it) }
        removed
    }

    fun list(): List<Rule> = synchronized(lock) { rules.toList() }

    /** 最近 [limit] 次触发记录，最新在前。 */
    fun history(limit: Int = 20): List<JSONObject> = synchronized(lock) {
        history.toList().asReversed().take(limit.coerceIn(1, HISTORY_LIMIT))
    }

    fun status(): JSONObject = buildStatus(appContext)

    fun status(context: Context): JSONObject = buildStatus(context)

    // ------------------------------------------------------------ 状态 ----

    private fun buildStatus(context: Context?): JSONObject {
        val snapshot = synchronized(lock) { rules.toList() }
        val historyCount = synchronized(lock) { history.size }
        return JSONObject().apply {
            put("started", started.get())
            put("ruleCount", snapshot.size)
            put("enabledCount", snapshot.count { it.enabled })
            put("triggerTypes", stringArray(TRIGGER_TYPES))
            put("actionTypes", stringArray(ACTION_TYPES))
            put("historyCount", historyCount)
            put(
                "readiness",
                context?.let { readiness(it) } ?: JSONObject.NULL,
            )
            put(
                "note",
                if (started.get()) {
                    "自动化引擎在运行：规则 ${snapshot.size} 条，trigger 与动作的可用性见 readiness。"
                } else {
                    "自动化引擎未启动：先调用 PiAutomation.apply(context)。"
                },
            )
        }
    }

    private fun readiness(context: Context): JSONObject = JSONObject().apply {
        put("notificationListenerConnected", PiNotificationListener.isConnected())
        put("notificationAccessEnabled", PiNotificationListener.isEnabledInSettings(context))
        put("accessibilityConnected", DeviceAccessibilityService.isRunning())
        put("notificationPermissionGranted", DeviceCapabilityStore.get(context).hasNotificationPermission())
        put("shellBackend", DeviceShellGuard.active().id)
        put("shellElevated", DeviceShellGuard.hasElevatedBackend())
    }

    // ------------------------------------------------------- 触发器求值 ----

    private fun evaluateTime(context: Context) {
        val now = System.currentTimeMillis()
        val time = LocalDateTime.now()
        val matched = ArrayList<Pair<Rule, String>>()
        for (rule in currentRules()) {
            if (!rule.enabled) continue
            val trigger = rule.trigger
            if (trigger.type.lowercase() != TRIGGER_TIME) continue
            if (trigger.intervalMinutes > 0) {
                val interval = trigger.intervalMinutes.toLong() * 60_000L
                val last = lastFired[rule.id] ?: now.also { synchronized(lock) { lastFired[rule.id] = now } }
                if (now - last >= interval) matched.add(rule to "每 ${trigger.intervalMinutes} 分钟")
            }
            if (trigger.dailyAt.isNotBlank()) {
                val target = parseClock(trigger.dailyAt) ?: continue
                val today = time.toLocalDate().toString()
                val alreadyToday = synchronized(lock) { dailyFired[rule.id] } == today
                if (!alreadyToday && time.hour == target.hour && time.minute == target.minute) {
                    synchronized(lock) { dailyFired[rule.id] = today }
                    matched.add(rule to "每天 ${trigger.dailyAt}")
                }
            }
        }
        fireAll(context, matched, now)
    }

    /**
     * 电量/网络/屏幕/前台应用：算出一个布尔条件，只在 `false → true` 的边沿触发。
     * 首次观察只写基线，不触发——引擎重启不该把"电量本来就是低"当成刚发生。
     */
    private fun evaluateConditions(context: Context) {
        val now = System.currentTimeMillis()
        val battery = batteryPercent(context)
        val network = networkKind(context)
        val screenOn = isScreenOn(context)
        val foreground = foregroundPackage()
        val matched = ArrayList<Pair<Rule, String>>()
        for (rule in currentRules()) {
            if (!rule.enabled) continue
            val trigger = rule.trigger
            // 每个分支给出「条件当前成不成立」+ 人能读的原因；非条件类触发器直接跳过。
            val evaluation: Pair<Boolean, String> = when (trigger.type.lowercase()) {
                TRIGGER_BATTERY -> {
                    val level = battery ?: continue
                    val hit = (trigger.below >= 0 && level < trigger.below) ||
                        (trigger.above >= 0 && level > trigger.above)
                    hit to "电量 $level%（低于 ${trigger.below} / 高于 ${trigger.above}）"
                }

                TRIGGER_NETWORK -> {
                    val wanted = trigger.network.trim().lowercase()
                    val hit = when (wanted) {
                        "", "any" -> true
                        "none", "off", "disconnected" -> network == "none"
                        else -> network == wanted
                    }
                    hit to "网络 $network（期望 ${wanted.ifEmpty { "any" }}）"
                }

                TRIGGER_SCREEN -> (screenOn == trigger.screenOn) to "屏幕${if (screenOn) "亮" else "灭"}"

                TRIGGER_APP -> {
                    if (trigger.packageName.isBlank()) continue
                    (foreground == trigger.packageName) to
                        "前台应用 ${foreground ?: "未知"}（期望 ${trigger.packageName}）"
                }

                else -> continue
            }
            val condition = evaluation.first
            val previous = synchronized(lock) {
                val prior = lastCondition[rule.id]
                lastCondition[rule.id] = condition
                prior
            }
            if (previous == false && condition) matched.add(rule to evaluation.second)
        }
        fireAll(context, matched, now)
    }

    private fun onNotificationEvent(event: JSONObject) {
        if (!event.optString("type").equals(EVENT_POSTED_NAME, ignoreCase = true)) return
        val context = appContext ?: return
        val key = event.optString("key")
        val now = System.currentTimeMillis()
        if (key.isNotEmpty()) {
            val merged = synchronized(lock) {
                val last = notificationSeen[key]
                val inside = last != null && now - last < NOTIFICATION_MERGE_WINDOW_MS
                if (!inside) {
                    notificationSeen[key] = now
                    // 顺手 prune，避免一张长期不重启的进程里这个 map 只增不减。
                    if (notificationSeen.size > 512) {
                        val cutoff = now - NOTIFICATION_MERGE_WINDOW_MS * 4
                        notificationSeen.entries.removeAll { it.value < cutoff }
                    }
                }
                inside
            }
            if (merged) return
        }
        val packageName = event.optString("packageName")
        val haystack = buildString {
            append(event.optString("title")).append(' ')
            append(event.optString("text")).append(' ')
            append(event.optString("bigText")).append(' ')
            append(event.optString("subText"))
        }
        val matched = ArrayList<Pair<Rule, String>>()
        for (rule in currentRules()) {
            if (!rule.enabled) continue
            val trigger = rule.trigger
            if (trigger.type.lowercase() != TRIGGER_NOTIFICATION) continue
            if (trigger.packageName.isNotBlank() && trigger.packageName != packageName) continue
            if (trigger.keyword.isNotBlank() && !matchesKeyword(trigger.keyword, haystack)) continue
            matched.add(rule to "通知 $packageName：${event.optString("title")}")
        }
        fireAll(context, matched, now)
    }

    // ---------------------------------------------------------- 执行 ----

    /** 按优先级串行执行命中规则；冷却中、条件不满足、重复命中的直接跳过。 */
    private fun fireAll(context: Context, matched: List<Pair<Rule, String>>, now: Long) {
        if (matched.isEmpty()) return
        val seen = HashSet<String>()
        for ((rule, reason) in matched.sortedByDescending { it.first.priority }) {
            if (!seen.add(rule.id)) continue
            if (!rule.enabled) continue
            if (!rule.condition.matches(context)) continue
            val last = synchronized(lock) { lastFired[rule.id] }
            if (last != null && rule.cooldownMs > 0 && now - last < rule.cooldownMs) continue
            synchronized(lock) { lastFired[rule.id] = now }
            val results = JSONArray()
            for ((index, action) in rule.actions.withIndex()) {
                results.put(runActionWithRetry(context, rule, action, index))
            }
            recordHistory(context, rule, reason, now, results)
        }
    }

    /**
     * 一个动作的完整生命周期：幂等窗口检查 → 尝试 → 失败退避重试（有界）→ 结果。
     * 重试只在真的可能成功时发生：策略类拒绝（`retryable=false`）立即放弃。
     */
    private fun runActionWithRetry(context: Context, rule: Rule, action: Action, index: Int): JSONObject {
        val actionId = action.id.ifBlank { "${rule.id}#$index" }
        val now = System.currentTimeMillis()
        if (action.idempotencyWindowMs > 0) {
            val duplicate = synchronized(lock) {
                val last = actionLastRun[actionId]
                val inside = last != null && now - last < action.idempotencyWindowMs
                if (!inside) actionLastRun[actionId] = now
                inside
            }
            if (duplicate) {
                return JSONObject()
                    .put("id", actionId)
                    .put("type", action.type)
                    .put("ok", true)
                    .put("skipped", true)
                    .put("reason", "幂等窗口内重复请求，已合并")
            }
        }
        val maxAttempts = action.retries.coerceIn(0, MAX_RETRIES) + 1
        var attempt = 0
        var lastReason = "未知错误"
        while (attempt < maxAttempts) {
            if (attempt > 0) {
                val backoff = (action.retryBackoffMs * (1L shl (attempt - 1))).coerceIn(0L, MAX_BACKOFF_MS)
                runCatching { Thread.sleep(backoff) }
            }
            attempt += 1
            val outcome = runCatching { runActionOnce(context, rule, action, actionId) }
            val payload = outcome.getOrNull()
            val ok = outcome.isSuccess && (payload == null || payload.optBoolean("ok", true))
            if (ok) {
                return (payload ?: JSONObject())
                    .put("id", actionId)
                    .put("type", action.type)
                    .put("ok", true)
                    .put("attempts", attempt)
            }
            lastReason = outcome.exceptionOrNull()?.let { error ->
                if (error is DeviceActionException) error.denial.reason else error.message.orEmpty()
            } ?: payload?.optString("reason").orEmpty().ifEmpty { "动作返回失败" }
            val retryable = outcome.exceptionOrNull()?.let { error ->
                error !is DeviceActionException || error.denial.retryable
            } ?: true
            if (!retryable) break
        }
        return JSONObject()
            .put("id", actionId)
            .put("type", action.type)
            .put("ok", false)
            .put("attempts", attempt)
            .put("reason", "重试用尽：$lastReason")
    }

    private fun runActionOnce(context: Context, rule: Rule, action: Action, actionId: String): JSONObject =
        when (action.type.trim().lowercase()) {
            ACTION_NOTIFY -> DeviceSystemActions.notify(
                context,
                action.title.ifBlank { rule.name.ifBlank { "Pi 自动化" } },
                action.text.ifBlank { "规则「${rule.name.ifBlank { rule.id }}」已触发" },
                notificationIdFor(actionId),
            ).put("ok", true)

            ACTION_TOAST -> DeviceSystemActions.toast(context, action.text, action.longDuration).put("ok", true)

            ACTION_VIBRATE -> DeviceSystemActions.vibrate(
                context,
                action.milliseconds.toInt(),
                action.pattern.ifEmpty { null },
            ).put("ok", true)

            ACTION_SHELL -> {
                val command = action.command.trim()
                if (command.isEmpty()) {
                    JSONObject().put("ok", false).put("reason", "shell 动作的命令为空。")
                } else {
                    val result = DeviceShellGuard.active().run(command, action.timeoutMs.coerceIn(500, 60_000))
                    JSONObject()
                        .put("ok", result.exitCode == 0)
                        .put("exitCode", result.exitCode)
                        .put("backend", result.backend)
                        .put("timedOut", result.timedOut)
                        .put("stdout", clipText(result.stdout, ACTION_OUTPUT_LIMIT))
                        .put("stderr", clipText(result.stderr, ACTION_OUTPUT_LIMIT))
                        .put("reason", if (result.exitCode == 0) "" else "exit=${result.exitCode}")
                }
            }

            ACTION_UI -> runUiAction(action)

            else -> JSONObject().put("ok", false).put("reason", "未知动作类型「${action.type}」")
        }

    /**
     * `ui` 动作交给 [DeviceUiAutomation]。
     *
     * 手势（tap/swipe）是 suspend 的，这里用 [runBlocking] 同步等它完成——工作线程是
     * 引擎自己的，阻塞它是刻意的串行化：UI 动作绝不并发。不带选择器的参数一律由
     * `selector*` 前缀区分（`text` 在 `input` 里是"要输入的文字"）。
     */
    private fun runUiAction(action: Action): JSONObject {
        val service = DeviceAccessibilityService.running()
            ?: return JSONObject().put("ok", false).put("reason", "无障碍服务未连接：ui 动作无法执行。")
        val params = action.params
        val selector = selectorFrom(params)
        return when (action.operation.trim().lowercase()) {
            "key" -> DeviceUiAutomation.key(service, params["key"].orEmpty()).put("ok", true)

            "input" -> DeviceUiAutomation.input(
                service,
                params["text"].orEmpty(),
                params["index"]?.toIntOrNull(),
                params["submit"]?.toBooleanStrictOrNull() ?: false,
                selector,
            ).put("ok", true)

            "scroll" -> DeviceUiAutomation.scroll(
                service,
                selector,
                params["index"]?.toIntOrNull(),
                params["direction"].orEmpty(),
            ).put("ok", true)

            "dump" -> DeviceUiAutomation.dump(
                service,
                params["filter"],
                params["maxNodes"]?.toIntOrNull() ?: DeviceUiAutomation.DEFAULT_MAX_NODES,
                params["maxDepth"]?.toIntOrNull() ?: 32,
            ).put("ok", true)

            "tap" -> runBlocking {
                DeviceUiAutomation.tap(
                    service,
                    params["index"]?.toIntOrNull(),
                    params["x"]?.toIntOrNull(),
                    params["y"]?.toIntOrNull(),
                    false,
                    selector,
                )
            }.put("ok", true)

            "longpress", "long_press" -> runBlocking {
                DeviceUiAutomation.tap(
                    service,
                    params["index"]?.toIntOrNull(),
                    params["x"]?.toIntOrNull(),
                    params["y"]?.toIntOrNull(),
                    true,
                    selector,
                )
            }.put("ok", true)

            "swipe" -> runBlocking {
                DeviceUiAutomation.swipe(
                    service,
                    params["x1"]?.toIntOrNull() ?: 0,
                    params["y1"]?.toIntOrNull() ?: 0,
                    params["x2"]?.toIntOrNull() ?: 0,
                    params["y2"]?.toIntOrNull() ?: 0,
                    params["durationMs"]?.toIntOrNull() ?: 300,
                )
            }.put("ok", true)

            else -> JSONObject()
                .put("ok", false)
                .put("reason", "未知 ui 操作「${action.operation}」（可用：key/input/scroll/dump/tap/longPress/swipe）")
        }
    }

    /** 从 `selector*` 前缀的参数构造选择器；一个都没给时返回 `null`。 */
    private fun selectorFrom(params: Map<String, String>): DeviceUiAutomation.Selector? {
        val selector = DeviceUiAutomation.Selector(
            text = params["selectorText"],
            description = params["selectorDesc"],
            resourceId = params["resourceId"],
            packageName = params["selectorPackage"],
            className = params["className"],
            clickableOnly = params["clickable"]?.toBooleanStrictOrNull() ?: false,
        )
        return if (selector.isEmpty) null else selector
    }

    private fun recordHistory(context: Context, rule: Rule, reason: String, now: Long, results: JSONArray) {
        val entry = JSONObject().apply {
            put("at", now)
            put("ruleId", rule.id)
            put("ruleName", rule.name)
            put("priority", rule.priority)
            put("reason", reason)
            put("actions", results)
        }
        synchronized(lock) {
            history.addLast(entry)
            while (history.size > HISTORY_LIMIT) history.removeFirst()
            writeHistory(context)
        }
    }

    private fun currentRules(): List<Rule> = rules

    /** 手动拼 JSONArray：Android 自带的 `org.json` 没有公开的 `Collection` 构造器。 */
    private fun stringArray(values: List<String>): JSONArray {
        val array = JSONArray()
        for (value in values) array.put(value)
        return array
    }

    private fun newRuleId(): String = "r-" + UUID.randomUUID().toString().substring(0, 8)

    private fun notificationIdFor(actionId: String): Int = (actionId.hashCode() and 0x7fffffff) % 100_000 + 1

    // ---------------------------------------------------------- 启动 ----

    private fun registerReceivers(context: Context) {
        if (!receiversRegistered.compareAndSet(false, true)) return
        val networkFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(networkReceiver, networkFilter, Context.RECEIVER_NOT_EXPORTED)
                context.registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(networkReceiver, networkFilter)
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(screenReceiver, screenFilter)
            }
        }
    }

    // -------------------------------------------------------- 持久化 ----

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        rules = readRules(context)
        val restored = readHistory(context)
        synchronized(lock) {
            history.clear()
            for (entry in restored) history.addLast(entry)
        }
    }

    private fun rulesFile(context: Context): File = File(context.filesDir, "pi-automation/rules.json")

    private fun historyFile(context: Context): File = File(context.filesDir, "pi-automation/history.json")

    private fun writeRules(context: Context) {
        runCatching {
            val file = rulesFile(context)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (rule in rules) array.put(rule.toJson())
            file.writeText(array.toString())
        }
    }

    private fun readRules(context: Context): List<Rule> {
        return runCatching {
            val file = rulesFile(context)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            val parsed = ArrayList<Rule>(array.length())
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { parsed.add(Rule.fromJson(it)) }
            }
            parsed
        }.getOrDefault(emptyList())
    }

    private fun writeHistory(context: Context) {
        runCatching {
            val file = historyFile(context)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (entry in history) array.put(entry)
            file.writeText(array.toString())
        }
    }

    private fun readHistory(context: Context): List<JSONObject> {
        return runCatching {
            val file = historyFile(context)
            if (!file.exists()) return emptyList()
            val array = JSONArray(file.readText())
            val parsed = ArrayList<JSONObject>(array.length())
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { parsed.add(it) }
            }
            parsed
        }.getOrDefault(emptyList())
    }
}

/**
 * 通知事件里"到达"的类型名。
 *
 * 与 [PiNotificationListener] 的常量字面量对齐（那个常量是私有的）；这里单独声明一是
 * 让这份文件不依赖对方的可见性，二是这层映射本身就是两组件之间唯一的字符串契约。
 */
private const val EVENT_POSTED_NAME = "posted"
