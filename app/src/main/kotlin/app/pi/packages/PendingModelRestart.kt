package app.pi.packages

import android.content.Context
import app.pi.PiApplication

/**
 * 「App 自己写过模型或凭证文件，而引擎从那以后还没重启过」—— 一个 App 私有的时间戳。
 *
 * ## 为什么不能再用「清单里的模型不在引擎快照里」当判据
 *
 * 用户原话：「明明啥也没干，也显示到什么模型没生效，非让我重启」。旧判据是
 * `PiModelInventory.Status.PENDING_RESTART` 的两半：有凭证 + 引擎的
 * `get_available_models` 里没有它。那一半在两种完全正常的情况下都成立，于是重启卡
 * 自己冒出来：
 *
 *  - 引擎没在跑（这里已经用 `engineKnown` 挡住一部分，但卡片仍按厂商出）；
 *  - 模型来自 pi 的官方目录（`models-store.json`），pi 只在启动时把它并进快照
 *    （`core/model-runtime.ts:180`，RPC 下没有任何命令 refresh，`modes/rpc/rpc-mode.ts:490-493`），
 *    于是「目录里有、快照里没有」的条目本来就是常态，与"我刚改过配置"无关。
 *
 * 真正需要重启的只有一件事：**本 App 刚刚写过 `models.json` / `auth.json` / 选择键**，
 * 而 pi 只在进程启动时读这三份文件。所以判据改成这个标记：
 *
 *  1. [PiCredentialService.save] / [PiCredentialService.remove] **成功写盘之后**调 [mark]；
 *  2. 引擎开始服务时（`PiEngineSession.probeServing` 拿到 `get_state` 的真实回答，
 *     与 `lastServingMs` 同一个点）调 [clear] —— 重启成功也会走到那里；
 *  3. 卡片只在「标记置位 + 引擎在跑」时出现，于是重启成功 = 标记被清 = 卡片自己消失，
 *     不需要「重启后仍未生效」那种状态。
 *
 * ## 为什么是 SharedPreferences 而不是注册表里的一行
 *
 * 它不是用户可设的值，是 App 自己的记账（用户裁定：不要新增注册表行）。`settings.json`
 * 也不能用：那正是这条标记在描述的文件之一，写进去会让"App 写过文件"这件事本身又改一次
 * 文件。与 `RuntimePreferences` / `DeviceCapabilityStore` 同一种存法：一个私有 prefs 文件。
 *
 * 存储的是一个毫秒时间戳而不是布尔：真机上排查"到底是哪次保存之后没重启"时，时间点比
 * 一个 true 值有用，而代价是同一个 `Long` 槽。读的是 [PiApplication.context]（与
 * `RuntimePreferences.shared()` 同一个入口），这样没有 Context 的 `PiCredentialService`
 * 与引擎侧都能调；`Application.onCreate` 之前调用一律当作"没有标记"。
 */
object PendingModelRestart {

    /** Private to the app; named after the thing it remembers. */
    const val PREFS_NAME = "pi-pending-model-restart"

    /** 写下标记的时刻（毫秒）。没有这个键 = 没有待生效的改动。 */
    const val KEY_WRITTEN_AT = "writtenAt"

    /** 写盘成功之后置位。重复调用只是把时间戳推后，幂等。 */
    fun mark() {
        val store = prefs() ?: return
        store.edit().putLong(KEY_WRITTEN_AT, System.currentTimeMillis()).apply()
    }

    /** 引擎开始服务时清位。没有标记时是空操作，所以"每个引擎启动都调"是安全的。 */
    fun clear() {
        val store = prefs() ?: return
        store.edit().remove(KEY_WRITTEN_AT).apply()
    }

    /** 是否有「App 写过、引擎还没读过」的模型/凭证改动。读不到 prefs 时当作 false。 */
    fun isPending(): Boolean = (prefs()?.getLong(KEY_WRITTEN_AT, 0L) ?: 0L) > 0L

    private fun prefs() = runCatching {
        PiApplication.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()
}
