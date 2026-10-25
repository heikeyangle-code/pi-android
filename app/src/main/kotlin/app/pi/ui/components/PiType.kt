package app.pi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.pi.ui.theme.PiTheme

/**
 * 行标题、屏标题与顶栏紧凑标签：`06 §2` 五档（12 / 13 / 14 / 15 / 17）里上面那两级，
 * 加上对话页顶栏自己的 16 / 18 / 13 三个角色（见文件末尾 [topBarTitle] 等）。
 *
 * ## 为什么这是两个「角色」，不是随手开的第六档
 *
 * `PiTextStyles` 收了下面三级（12 / 13 / 14）的形态：`meta`、`mono`、`monoSmall`、
 * `prose`，外加代码块的 `code`；15 与 17 这两级一直挂在 M3 的槽上。
 * `docs/pi-android-ui-spec.md` §2.2 的细则原话：「15 与 17 两档不在 `PiTextStyles` 里：
 * 它们来自 M3 的 `bodyLarge`（15/23，行标题）与 `titleMedium`（17/24，屏标题），也就是
 * `piTypography` 给的行/屏标题步进。」也就是说这两级**本来就在设计表里** —— 本对象没有新增
 * 字号，只是给它们各起一个名字，好让调用点不再借 M3 的槽。
 *
 * 收口的动因是同一个角色长出过两种尺寸：设置面的行标题一度写成 `prose`（14/500），工作区的
 * 行标题是 `bodyLarge` + Medium（15/500），于是同一个 App 的行标题差一档。行标题与屏标题在
 * v2 里是固定的两步 —— 行 15、屏 17 —— 所以它们各是一个**角色**（在哪个屏幕都是这个值），
 * 而不是「每屏自己挑一个够大的槽」。
 *
 * ## 为什么两个都随字号档整体平移
 *
 * `app.appearance.fontSize` 的四档（−1/0/+1/+2，`PiTypographyProfile.fontSizeOffsetSp`）
 * 让五档角色整体长大或缩小，M3 的槽也会被 `piTypography(fontSizeOffsetSp)` 平移。把这两个
 * 角色钉死成常量，会让「更大」档里的标题比同一屏的正文还小一圈 —— 那正是本对象要消灭的第二种
 * 度量。所以字号与行高一起加偏移：−1 / 0 / +1 / +2 四档上 [rowTitle] 是 14/22、15/23、
 * 16/24、17/25（[title] 是 16/23、17/24、18/25、19/26）。基线取
 * `PiTheme.text` 的 `meta`：系统字体，`piPrecise()`（无字体自带内边距、行距居中、首末行裁掉）
 * 已经写进角色里，`copy` 会把它带过来。
 *
 * ## [rowTitle] 的 15/23 出自哪里，为什么不用 `prose`
 *
 * 15/23 是 `06 §2`「标题 15/500」那一档；行高 23 与本 App 里 M3 `bodyLarge` 的取值逐字相同
 * （`ui/theme/PiTheme.kt` 的 `piTypography`：`15.sp / 23.sp`）—— 本轮把那条路径换成这里，
 * 数字一个没动。不用 `prose` 的原因：`prose` 不是行标题档，它是 14/23 Regular 的**正文**
 * （助手消息、错误句子），比行标题小一档、轻两个字重。拿它当行标题，设置面的行就是 14/500
 * 而工作区的是 15/500，又回到本对象要收掉的不一致上。
 *
 * ## 各用在哪
 *
 *  - [rowTitle]：设置面的行标题（`PiSettingsRows` 的 `RowBody`）、工作区与「Pi 文件」列表的
 *    行、菜单/浮层里的一条动作。
 *  - [title]：sheet 头、卡片头、屏内标题、空态标题。
 *
 * 等宽的行标题（文件名、路径）不另开角色：`rowTitle.copy(fontFamily = PiTheme.text.mono.fontFamily)`，
 * 字号与行高仍归这里管。
 */
object PiType {

    /** `15 + 字号偏移` / `23 + 字号偏移` 的 Medium：行标题。见对象 KDoc。 */
    val rowTitle: TextStyle
        @Composable
        @ReadOnlyComposable
        get() {
            val shift = PiTheme.typography.fontSizeOffsetSp
            return PiTheme.text.meta.copy(
                fontSize = (15 + shift).sp,
                lineHeight = (23 + shift).sp,
                fontWeight = FontWeight.Medium,
            )
        }

    /** `17 + 字号偏移` / `24 + 字号偏移` 的 SemiBold：sheet / 卡片 / 屏内标题。 */
    val title: TextStyle
        @Composable
        @ReadOnlyComposable
        get() {
            val shift = PiTheme.typography.fontSizeOffsetSp
            return PiTheme.text.meta.copy(
                fontSize = (17 + shift).sp,
                lineHeight = (24 + shift).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

    /**
     * 对话页顶栏的会话标题：`16 + 字号偏移` / `22 + 字号偏移` 的 SemiBold。
     *
     * 顶栏从两行（标题 + 引擎状态）收成一行 56dp 之后，标题比屏标题档小一档才留得下
     * 呼吸；仍然随 `app.appearance.fontSize` 平移，不钉死。
     */
    val topBarTitle: TextStyle
        @Composable
        @ReadOnlyComposable
        get() {
            val shift = PiTheme.typography.fontSizeOffsetSp
            return PiTheme.text.meta.copy(
                fontSize = (16 + shift).sp,
                lineHeight = (22 + shift).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

    /**
     * 没有会话标题时的「会话 ▾」：`18 + 字号偏移` / `24 + 字号偏移` SemiBold。
     *
     * 比 [topBarTitle] 大一档：它没有具体名字，要在同一位置撑起“这是会话入口”的重量。
     */
    val topBarPlaceholder: TextStyle
        @Composable
        @ReadOnlyComposable
        get() {
            val shift = PiTheme.typography.fontSizeOffsetSp
            return PiTheme.text.meta.copy(
                fontSize = (18 + shift).sp,
                lineHeight = (24 + shift).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

    /**
     * 顶栏模型 chip 这类紧凑标签：`13 + 字号偏移` / `18 + 字号偏移` 的 Medium，无衬线。
     *
     * 它和 [app.pi.ui.theme.PiTextStyles.mono] 的 13sp 不是一回事：那是机器语言（路径、
     * 命令），这是控件上的文字，所以走系统字面。
     */
    val chipLabel: TextStyle
        @Composable
        @ReadOnlyComposable
        get() {
            val shift = PiTheme.typography.fontSizeOffsetSp
            return PiTheme.text.meta.copy(
                fontSize = (13 + shift).sp,
                lineHeight = (18 + shift).sp,
                fontWeight = FontWeight.Medium,
            )
        }
}
