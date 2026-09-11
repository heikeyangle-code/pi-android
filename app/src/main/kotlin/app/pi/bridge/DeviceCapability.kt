package app.pi.bridge

/**
 * The five device-capability groups of the authorization page
 * (docs/pi-android-ui-spec.md §5.6, docs/pi-android-app-design.md §21.4).
 *
 * Every group is an explicit user opt-in. The bridge never infers consent from
 * the mere presence of an Android permission: an Android permission the app
 * happens to hold is *necessary* but not *sufficient*, because the user's
 * decision here is about what the agent may do, not about what the app can.
 *
 * [defaultEnabled] exists because the spec draws one deliberate line — the
 * mundane, non-observable group (clipboard, notifications, opening links,
 * sharing) is on out of the box, and the four surveillance/control groups
 * (存储 / 无障碍 / 位置·传感器·相机 / Shell) are off until the user says
 * otherwise. Nothing dangerous is ever defaulted on.
 */
enum class DeviceCapability(
    val id: String,
    /** Card title, Chinese: the UI language of the app. */
    val title: String,
    /** One-line summary shown under the title. */
    val summary: String,
    /**
     * What the agent would be able to do with this group. Rendered verbatim on
     * the card so the switch is never a mystery (spec §5.6: 每项显示当前能力清单).
     */
    val allows: List<String>,
    val defaultEnabled: Boolean,
) {
    Basic(
        id = "basic",
        title = "基础",
        summary = "剪贴板、通知、打开链接、分享、提示音与语音",
        allows = listOf(
            "读取与写入系统剪贴板",
            "发送系统通知（可点击回到会话）",
            "在屏幕上弹出短提示（Toast）与震动",
            "打开网址或交给其他 App 处理",
            "把文本分享到其他 App",
            "朗读文本（TTS）",
            "列出已安装应用并启动其中一个",
        ),
        defaultEnabled = true,
    ),

    Storage(
        id = "storage",
        title = "存储",
        summary = "读写用户授权（SAF）的目录，并把文件导出到公共 Download",
        allows = listOf(
            "读写你在本页授权的目录（SAF，重启后仍然有效）",
            "把 Agent 生成的文件写入 Download（用户可见、可撤销）",
            "从 Download 读回文件交给 Agent（API 33+ 只能读本应用自己的文件）",
        ),
        defaultEnabled = false,
    ),

    Accessibility(
        id = "accessibility",
        title = "无障碍",
        summary = "读取屏幕内容、点按、滑动、输入、截图，以及结束其他应用",
        allows = listOf(
            "读取当前屏幕的控件树（文本、按钮、输入框）",
            "点按控件、手势滑动、长按",
            "向输入框写入文本、执行返回/主页/最近任务",
            "截屏并把图片交给模型查看",
            "结束指定的用户应用（危险：可能丢失未保存内容）",
        ),
        defaultEnabled = false,
    ),

    Sensors(
        id = "sensors",
        title = "位置 · 传感器 · 相机",
        summary = "读取位置、电池与传感器数据，控制手电筒",
        allows = listOf(
            "读取当前定位（取决于系统是否已授予定位权限）",
            "列出传感器并读取一次采样值",
            "读取电池电量与充电状态",
            "开关手电筒（相机闪光灯）",
        ),
        defaultEnabled = false,
    ),

    Shell(
        id = "shell",
        title = "Shell",
        summary = "在设备上执行受策略守卫限制的命令（默认关闭；危险操作第一次确认后可选择本会话不再询问）",
        allows = listOf(
            "执行日常读命令（getprop、dumpsys、pm list、logcat、ls、cat、df、ps 等）",
            "执行日常写命令（cp、mv、rm、mkdir、sed、tar、curl 等），但只能写工作区之内",
            "在装有 Shizuku 的设备上以 ADB 身份（uid=2000）运行，从而使用 input、pm、am、settings get 等",
        ),
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
