package app.pi.bridge

/**
 * The three device-capability groups of the authorization page
 * (docs/pi-android-ui-spec.md §5.6, docs/pi-android-app-design.md §21.4).
 *
 * Every group is an explicit user opt-in. The bridge never infers consent from
 * the mere presence of an Android permission: an Android permission the app
 * happens to hold is *necessary* but not *sufficient*, because the user's
 * decision here is about what the agent may do, not about what the app can.
 *
 * [defaultEnabled] 现在三组全是 `false` —— 用户要求「默认全关」：装完之后一组都不开，
 * 要用哪组由用户在「设置 → 设备能力」里自己说。字段保留而不是删掉，是因为它记录的是
 * 「这个 App 有没有替用户做过默认决定」；将来若某一组该默认开，改一个布尔值即可。
 *
 * 通知监听没有自己的分组：它并入 基础（contract 里写死的那条）。
 * 把「通知监听」单开一个开关看起来更缜密，但它与 android_say（同一个通知权限/同一个
 * 用户预期）拆成两组之后，用户要对两个开关做同一个决定 —— 分组多一层，判断反而多一层。
 */
enum class DeviceCapability(
    val id: String,
    /** Card title, Chinese: the UI language of the app. */
    val title: String,
    /** One-line summary shown under the title. */
    val summary: String,
    val defaultEnabled: Boolean,
) {
    Basic(
        id = "basic",
        title = "基础",
        summary = "剪贴板、通知、打开链接、分享、提示音与语音，以及文件、定位、传感器、电量与手电筒",
        defaultEnabled = false,
    ),

    Accessibility(
        id = "accessibility",
        title = "屏幕",
        summary = "读屏/点按/输入/滑动/按键/截图",
        defaultEnabled = false,
    ),

    Shell(
        id = "shell",
        title = "Shell",
        summary = "在设备上执行受策略守卫限制的命令（默认关闭；危险操作第一次确认后可选择本会话不再询问）",
        defaultEnabled = false,
    ),

    ;

    companion object {
        fun fromId(id: String?): DeviceCapability? =
            if (id == null) null else entries.firstOrNull { it.id == id }
    }
}

/**
 * A capability group's live state, as shown on the authorization card and as
 * reported to the model.
 *
 * [reason] is the text the model is told to relay verbatim when the group is not
 * usable, so a refusal is never a silent failure (design §21.4).
 */
data class DeviceCapabilityState(
    val capability: DeviceCapability,
    val enabled: Boolean,
    val enabledForSession: Boolean,
    val usable: Boolean,
    val reason: String?,
)
