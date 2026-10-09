package app.pi.bridge

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** 通知环形缓冲的上限。 */
private const val MAX_ENTRIES = 60

/** 事件队列的上限。 */
private const val MAX_EVENTS = 200

/**
 * 事件类型常量。
 *
 * [EVENT_SNAPSHOT] 是连接时用 `getActiveNotifications()` 一次性灌入的全量事实，**不是**
 * 逐条的"刚到达"：服务被系统重启后，桌面上已经躺着的通知不该被当成新事件重放动作。
 */
private const val EVENT_SNAPSHOT = "snapshot"
private const val EVENT_POSTED = "posted"
private const val EVENT_REMOVED = "removed"
private const val EVENT_REPLIED = "replied"
private const val EVENT_REPLY_FAILED = "reply_failed"
private const val EVENT_DISMISSED = "dismissed"
private const val EVENT_SNOOZED = "snoozed"

/** 空白折叠 + 截断。通知正文经常是整段文字，入缓冲前先压成一行。 */
private fun clipText(raw: CharSequence?, max: Int = 200): String {
    val value = raw?.toString()?.trim().orEmpty()
    if (value.isEmpty()) return ""
    val collapsed = value.replace(Regex("\\s+"), " ")
    return if (collapsed.length <= max) collapsed else collapsed.substring(0, max) + "…"
}

/**
 * 通知读取、应答与事件总线。
 *
 * ### 它是什么
 *
 * 一个 [NotificationListenerService] 常驻实例（由用户在系统「通知使用权」里开启），
 * 加上三层进程内状态：
 *
 *  1. **环形缓冲** [Entry]：最近 [MAX_ENTRIES] 条通知（全量与增量合并），供
 *     `recent()` / `/app/health` 使用；
 *  2. **事件队列** [NotificationEvent]：`posted` / `removed` / `replied` /
 *     `reply_failed` / `dismissed` / `snoozed` 的统一流水，供 [PiAutomation] 的
 *     触发器消费；
 *  3. **活通知表**：key → [Notification]，应答（[reply]）与撤销（[dismiss]）要用
 *     它的 action PendingIntent 和 RemoteInput，这是唯一一份带"能不能回"的信息。
 *
 * 状态放在伴生对象而不是实例字段，是因为这个服务会被系统反复绑定/解绑（用户改权限、
 * 系统回收内存）：调用方问的是"这个进程最近看到过什么"，这属于进程，不属于某一次绑定。
 *
 * ### 为什么 `onNotificationPosted` 里只记录
 *
 * 该回调跑在系统主线程上，任何重活都会拖住系统 UI。这里只做字符串裁剪、入队和投递回调；
 * 真正的规则匹配与动作执行由 [PiAutomation] 自己的 HandlerThread 完成。
 *
 * ### 隐私边界
 *
 * 通知内容只活在本进程内存里：环形缓冲与事件队列都不落盘，进程被杀即消失，也只被
 * 规则匹配和本机 UI 读取。它不写日志、不发网络、不跨进程共享。用户在系统里关掉
 * 「通知使用权」时，[onListenerDisconnected] 会清空实例并停止投递。
 */
class PiNotificationListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    override fun onListenerConnected() {
        instance = this
        appContext = applicationContext
        // 连接时先灌入全量，再开始接收增量；此前用户桌面上已有的通知只记快照、不投递。
        seedActive()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        record(sbn, removed = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        record(sbn, removed = true)
    }

    /** 一条通知在缓冲里的样子：字段与 [toJson] 一一对应。 */
    data class Entry(
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val bigText: String,
        val subText: String,
        val postTime: Long,
        val isOngoing: Boolean,
        val isClearable: Boolean,
        val category: String,
        val channelId: String,
        val actions: List<ActionSummary>,
        val replyable: Boolean,
        val removed: Boolean,
    ) {
        /** 单个 action 的摘要：有没有可填字的关键信息，全在 [hasRemoteInput]。 */
        data class ActionSummary(val title: String, val hasRemoteInput: Boolean) {
            fun toJson(): JSONObject = JSONObject()
                .put("title", title)
                .put("hasRemoteInput", hasRemoteInput)
        }

        fun toJson(): JSONObject {
            val actionArray = JSONArray()
            for (action in actions) actionArray.put(action.toJson())
            return JSONObject().apply {
                put("key", key)
                put("packageName", packageName)
                put("title", title)
                put("text", text)
                put("bigText", bigText)
                put("subText", subText)
                put("postTime", postTime)
                put("isOngoing", isOngoing)
                put("isClearable", isClearable)
                put("category", category)
                put("channelId", channelId)
                put("actions", actionArray)
                put("replyable", replyable)
                put("removed", removed)
            }
        }

        companion object {
            fun from(sbn: StatusBarNotification, removed: Boolean): Entry {
                val notification = sbn.notification
                val extras = notification?.extras
                val actions: Array<Notification.Action> = notification?.actions ?: emptyArray()
                val summaries = ArrayList<ActionSummary>(actions.size)
                var replyable = false
                for (action in actions) {
                    val inputs = action.remoteInputs
                    val hasInput = inputs != null && inputs.isNotEmpty()
                    if (hasInput) replyable = true
                    summaries.add(ActionSummary(clipText(action.title, max = 60), hasInput))
                }
                return Entry(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = clipText(extras?.getCharSequence(Notification.EXTRA_TITLE)),
                    text = clipText(extras?.getCharSequence(Notification.EXTRA_TEXT)),
                    bigText = clipText(extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                    subText = clipText(extras?.getCharSequence(Notification.EXTRA_SUB_TEXT), max = 120),
                    postTime = sbn.postTime,
                    isOngoing = sbn.isOngoing,
                    isClearable = sbn.isClearable,
                    category = notification?.category.orEmpty(),
                    channelId = notification?.channelId.orEmpty(),
                    actions = summaries,
                    replyable = replyable,
                    removed = removed,
                )
            }
        }
    }

    /** 事件流水的一条：谁、什么时候、发生了什么。 */
    data class NotificationEvent(
        val seq: Long,
        val type: String,
        val key: String,
        val packageName: String,
        val title: String,
        val text: String,
        val bigText: String,
        val subText: String,
        val at: Long,
        val detail: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("seq", seq)
            put("type", type)
            put("key", key)
            put("packageName", packageName)
            put("title", title)
            put("text", text)
            put("bigText", bigText)
            put("subText", subText)
            put("at", at)
            put("detail", detail)
        }
    }

    companion object {

        private val lock = Any()

        /** 最近通知，key → Entry；靠插入顺序当时间序（重投会先删后插，天然移到末尾）。 */
        private val entries = LinkedHashMap<String, Entry>()

        /** 只对"还活着"的通知保留可应答的 [Notification]（撤销/应答要它的 PendingIntent）。 */
        private val live = HashMap<String, Notification>()

        /** 统一事件队列（有界）。 */
        private val events = ArrayDeque<NotificationEvent>()

        /** 事件订阅者；[PiAutomation] 在这里挂触发器。 */
        private val listeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()

        @Volatile
        private var instance: PiNotificationListener? = null

        @Volatile
        private var appContext: Context? = null

        @Volatile
        private var sequence: Long = 0

        @Volatile
        private var lastPostTime: Long = 0

        @Volatile
        private var lastKey: String? = null

        /** 服务是否已连上；只有连上时读/撤销/应答才可能成功。 */
        fun isConnected(): Boolean = instance != null

        /**
         * 这个组件现在能不能用。
         *
         * 必须同时"连上"且"在设置里启用"：连接本身就蕴含启用，写两个条件是为了让
         * [context] 有意义，也让"用户关掉通知使用权后服务还没解绑"这种瞬间返回 false。
         */
        fun available(context: Context): Boolean = instance != null && isEnabledInSettings(context)

        /** [available] 的无参形式，供只拿到进程状态的调用方使用。 */
        fun available(): Boolean = instance != null

        /**
         * 用户是否在系统「通知使用权」里勾选了本应用。
         *
         * 这是"设置里的开关"，与 [isConnected] 是两件事：开关打开但系统还没绑定服务时，
         * 前者为真、后者为假——这个区分对应 `enabled_not_connected` 状态，UI 要能显示。
         */
        fun isEnabledInSettings(context: Context): Boolean = runCatching {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }.getOrDefault(false)

        /** 稳定状态名，供 `/app/health` 与 UI 共用。 */
        fun stateName(context: Context): String = when {
            isConnected() -> "connected"
            isEnabledInSettings(context) -> "enabled_not_connected"
            else -> "not_enabled"
        }

        /** 订阅事件流（到达/移除/应答成功失败都会投递）。 */
        fun addListener(listener: (JSONObject) -> Unit) {
            listeners.addIfAbsent(listener)
        }

        /** 退订。 */
        fun removeListener(listener: (JSONObject) -> Unit) {
            listeners.remove(listener)
        }

        /** 环形缓冲，最新在前；带 `removed` 标记，调用方自己决定要不要过滤。 */
        fun recent(limit: Int = 20): List<JSONObject> {
            val safe = limit.coerceIn(1, MAX_ENTRIES)
            return synchronized(lock) {
                entries.values.toList().asReversed().take(safe).map { it.toJson() }
            }
        }

        /** 事件流水，最新在前。 */
        fun events(limit: Int = 50): List<JSONObject> {
            val safe = limit.coerceIn(1, MAX_EVENTS)
            return synchronized(lock) {
                events.toList().asReversed().take(safe).map { it.toJson() }
            }
        }

        /** 自 [since] 之后的事件，按时间正序。消费方拿一次游标后只取增量。 */
        fun eventsSince(since: Long): List<JSONObject> = synchronized(lock) {
            events.filter { it.seq > since }.map { it.toJson() }
        }

        /** 当前事件序号，配合 [eventsSince] 当游标。 */
        fun latestSequence(): Long = sequence

        /**
         * 用 `getActiveNotifications()` 取当前桌面上的全量通知（系统给的事实核对），
         * 返回同 [recent] 形状的列表。未连接时返回空表——绝不拿缓冲冒充"系统现状"。
         */
        fun snapshot(): List<JSONObject> {
            val service = instance ?: return emptyList()
            val active = runCatching { service.getActiveNotifications() }.getOrNull() ?: return emptyList()
            return active.map { Entry.from(it, removed = false).toJson() }
        }

        /** 给 `/app/health` 的状态对象。 */
        fun status(context: Context): JSONObject {
            val connected = isConnected()
            val enabled = isEnabledInSettings(context)
            val (count, replyable, eventCount) = synchronized(lock) {
                Triple(
                    entries.values.count { !it.removed },
                    entries.values.count { it.replyable && !it.removed },
                    events.size,
                )
            }
            val postTime = lastPostTime
            val key = lastKey
            return JSONObject().apply {
                put("connected", connected)
                put("enabledInSettings", enabled)
                put("state", stateName(context))
                put("activeCount", count)
                put("replyableCount", replyable)
                put("capacity", MAX_ENTRIES)
                put("eventCount", eventCount)
                put("eventCapacity", MAX_EVENTS)
                put("lastPostTime", if (postTime > 0) postTime else JSONObject.NULL)
                put("lastKey", key ?: JSONObject.NULL)
                put(
                    "note",
                    when {
                        connected -> "通知监听已连接：可读取、应答、撤销与延后通知。"
                        enabled -> "已在系统里授权，但服务还没被绑定；等约 1 秒，或重启本应用。"
                        else -> "没有通知使用权。到 系统设置 → 通知 → 设备与应用通知 → 通知使用权 里勾选 PI。"
                    },
                )
            }
        }

        /** [status] 的无参形式：连上之前也能回答"没连上"。 */
        fun status(): JSONObject = appContext?.let { status(it) } ?: JSONObject().apply {
            put("connected", isConnected())
            put("enabledInSettings", false)
            put("state", if (isConnected()) "connected" else "unknown")
            put("note", "通知监听还没有上下文（服务未连接、[apply] 也未调用过）。")
        }

        /**
         * 真发一条回复：找到带 RemoteInput 的 action，把文本塞进它的
         * `PendingIntent` 发出去。
         *
         * 不是空壳接口——[Notification.Action] 的 remoteInputs 只有在"当前进程是通知
         * 监听者"时才会被系统填充，所以这份实现只能在服务连着时成功，失败会明确回报。
         */
        fun reply(key: String, text: String): JSONObject {
            val notification = synchronized(lock) { live[key] }
                ?: return failure("没有可应答的通知「$key」：它可能已被撤销、被系统回收，或服务刚重启。")
            val context = appContext
                ?: return failure("通知监听服务还没连上，无法发送回复。")
            val actions: Array<Notification.Action> = notification.actions ?: emptyArray()
            for (action in actions) {
                val inputs = action.remoteInputs
                if (inputs == null || inputs.isEmpty()) continue
                val remote = inputs[0]
                val pending = action.actionIntent ?: continue
                val intent = Intent()
                val results = Bundle().apply { putCharSequence(remote.resultKey, clipText(text, max = 4000)) }
                RemoteInput.addResultsToIntent(arrayOf(remote), intent, results)
                val sent = runCatching { pending.send(context, 0, intent); true }.getOrDefault(false)
                if (sent) {
                    publishEvent(EVENT_REPLIED, key, "", "已回复 ${text.length} 字符：${notificationText(notification)}")
                    return JSONObject().put("ok", true).put("key", key).put("chars", text.length)
                }
                publishEvent(EVENT_REPLY_FAILED, key, "", "系统拒绝发送回复：${notificationText(notification)}")
                return failure("系统拒绝了这条回复（通知可能已过期或被替换）。")
            }
            publishEvent(EVENT_REPLY_FAILED, key, "", "该通知没有可填写文字的 RemoteInput")
            return failure("这条通知没有可填写文字的动作，无法回复。")
        }

        /** 撤销一条通知。 */
        fun dismiss(key: String): JSONObject {
            val service = instance ?: return failure("通知监听服务未连接，无法撤销。")
            val ok = runCatching { service.cancelNotification(key); true }.getOrDefault(false)
            if (!ok) return failure("系统拒绝了撤销「$key」。")
            publishEvent(EVENT_DISMISSED, key, "", "撤销")
            return JSONObject().put("ok", true).put("key", key)
        }

        /** 撤销全部（只对可撤销的通知生效）。 */
        fun dismissAll(): JSONObject {
            val service = instance ?: return failure("通知监听服务未连接，无法撤销。")
            val ok = runCatching { service.cancelAllNotifications(); true }.getOrDefault(false)
            if (!ok) return failure("系统拒绝了全部撤销。")
            publishEvent(EVENT_DISMISSED, "", "", "全部撤销")
            return JSONObject().put("ok", true)
        }

        /** 延后一条通知 [ms] 毫秒。 */
        fun snooze(key: String, ms: Long): JSONObject {
            val service = instance ?: return failure("通知监听服务未连接，无法延后。")
            val duration = ms.coerceIn(0L, 24L * 60L * 60L * 1000L)
            val ok = runCatching { service.snoozeNotification(key, duration); true }.getOrDefault(false)
            if (!ok) return failure("系统拒绝了延后「$key」。")
            publishEvent(EVENT_SNOOZED, key, "", "延后 ${duration}ms")
            return JSONObject().put("ok", true).put("key", key).put("durationMs", duration)
        }

        // ------------------------------------------------------------ 内部 ----

        private fun failure(reason: String): JSONObject =
            JSONObject().put("ok", false).put("reason", reason)

        /** 把一条 [StatusBarNotification] 记进缓冲、活表与事件队列。 */
        private fun record(sbn: StatusBarNotification?, removed: Boolean) {
            if (sbn == null) return
            val entry = Entry.from(sbn, removed)
            synchronized(lock) {
                entries.remove(entry.key)
                if (removed) {
                    live.remove(entry.key)
                } else {
                    sbn.notification?.let { live[entry.key] = it }
                }
                entries[entry.key] = entry
                while (entries.size > MAX_ENTRIES) {
                    val oldest = entries.keys.firstOrNull() ?: break
                    entries.remove(oldest)
                    live.remove(oldest)
                }
                lastPostTime = entry.postTime
                lastKey = entry.key
            }
            publishEvent(
                if (removed) EVENT_REMOVED else EVENT_POSTED,
                entry.key,
                entry.packageName,
                if (removed) "移除" else "到达",
                entry,
            )
        }

        /** 连接时用全量通知填缓冲；不逐条投递，避免重启后重放动作。 */
        private fun seedActive() {
            val service = instance ?: return
            val active = runCatching { service.getActiveNotifications() }.getOrNull() ?: return
            synchronized(lock) {
                entries.clear()
                live.clear()
                for (sbn in active) {
                    val entry = Entry.from(sbn, removed = false)
                    entries[entry.key] = entry
                    sbn.notification?.let { live[entry.key] = it }
                    while (entries.size > MAX_ENTRIES) {
                        val oldest = entries.keys.firstOrNull() ?: break
                        entries.remove(oldest)
                        live.remove(oldest)
                    }
                }
            }
            publishEvent(EVENT_SNAPSHOT, "", "", "全量快照 ${active.size} 条", null)
        }

        private fun publishEvent(
            type: String,
            key: String,
            packageName: String,
            detail: String,
            entry: Entry? = null,
        ) {
            val event = synchronized(lock) {
                sequence += 1
                val created = NotificationEvent(
                    seq = sequence,
                    type = type,
                    key = key,
                    packageName = packageName.ifEmpty { entry?.packageName.orEmpty() },
                    title = entry?.title.orEmpty(),
                    text = entry?.text.orEmpty(),
                    bigText = entry?.bigText.orEmpty(),
                    subText = entry?.subText.orEmpty(),
                    at = System.currentTimeMillis(),
                    detail = detail,
                )
                events.addLast(created)
                while (events.size > MAX_EVENTS) events.removeFirst()
                created
            }
            val json = event.toJson()
            for (listener in listeners) runCatching { listener(json) }
        }

        /** 从活通知里取正文，给事件 detail 用（不参与匹配，只帮助人读日志）。 */
        private fun notificationText(notification: Notification): String =
            clipText(notification.extras?.getCharSequence(Notification.EXTRA_TEXT), max = 60)
    }
}
