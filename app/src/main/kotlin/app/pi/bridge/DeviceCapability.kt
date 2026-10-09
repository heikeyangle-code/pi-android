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
 * [defaultEnabled] 现在五组全是 `false` —— 用户要求「默认全关」：装完之后一组都不开，
 * 要用哪组由用户在「设置 → 设备能力」里自己说。字段保留而不是删掉，是因为它记录的是
 * 「这个 App 有没有替用户做过默认决定」；将来若某一组该默认开，改一个布尔值即可。
 *
 * 通知监听、自动化、VPN 与投屏没有各自的分组：它们并入 基础（contract 里写死的那条）。
 * 把「通知监听」单开一个开关看起来更缜密，但它与 android_say（同一个通知权限/同一个
 * 用户预期）拆成两组之后，用户要对两个开关做同一个决定 —— 分组多一层，判断反而多一层。
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
        summary = "剪贴板、通知、打开链接、分享、提示音与语音，以及文件、定位、传感器、电量与手电筒",
        allows = listOf(
            "读取与写入系统剪贴板",
            "发送系统通知（可点击回到会话）",
            "在屏幕上弹出短提示（Toast）与震动",
            "打开网址或交给其他 App 处理",
            "把文本分享到其他 App",
            "朗读文本（TTS）",
            "列出已安装应用并启动其中一个",
            "读写你在本页授权的目录（SAF，重启后仍然有效）",
            "把 Agent 生成的文件写入 Download（用户可见、可撤销）",
            "从 Download 读回文件交给 Agent（API 33+ 只能读本应用自己的文件）",
            "读取当前定位（取决于系统是否已授予定位权限）",
            "列出传感器并读取一次采样值",
            "读取电池电量与充电状态",
            "开关手电筒（相机闪光灯）",
        ),
        defaultEnabled = false,
    ),

    Accessibility(
        id = "accessibility",
        title = "屏幕",
        summary = "读屏/点按/输入/滑动/按键/截图",
        allows = listOf(
            "读取当前屏幕的控件树（文本、按钮、输入框）",
            "点按控件、手势滑动、长按",
            "向输入框写入文本、执行返回/主页/最近任务",
            "截屏并把图片交给模型查看",
            "结束指定的用户应用（危险：可能丢失未保存内容）",
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

    Ime(
        id = "ime",
        title = "输入法",
        summary = "读取并改写你正在输入的内容，以及替你提交（发送/搜索）—— 密码框不读、不记内容",
        allows = listOf(
            "读取当前输入框里的文本、光标位置与所属应用（密码框一律不读，只留一条「发生过输入」的记录）",
            "在光标处插入文字、整框替换、删除光标前后的文字",
            "提交当前输入框的动作（发送 / 搜索 / 完成 / 回车）",
            "读取最近的输入历史与剪贴板历史，最旧的自动丢弃",
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
