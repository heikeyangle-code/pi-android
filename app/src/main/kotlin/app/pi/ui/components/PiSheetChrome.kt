package app.pi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pi.ui.settings.PiSettingsMetrics

/**
 * 底部弹层「看起来是同一个东西」的那一层：视觉常量、把手、视觉壳。
 *
 * ## 为什么要有这个文件
 *
 * App 里有两套底部弹层外壳，**行为**不同而且应当不同：
 *
 *  - [PiSheet]（本包）：包装 M3 `ModalBottomSheet`。聊天与设置的弹层都走它，下滑关闭、
 *    点遮罩关闭、返回键关闭都是 M3 白送的；CI 规则只允许这个文件直调那个 API。
 *  - `ui/screens/WorkspaceChrome.kt` 里的 `WsSheet`：`Dialog` + 自绘遮罩 + 手写
 *    fade/rise + 拖拽关闭 + nestedScroll。工作区的面板要 ✕、要页脚槽、要 `maxHeight`
 *    与自己的动画时长，这些 M3 外壳里没有。
 *
 * 行为分开有理由，**外观分开没有**。上一轮已经把这些数字对齐了（圆角 16、把手 32×3、
 * 遮罩同一个字面量），但它们仍是分别写在两个文件里的两份实现，于是留下三处真实差异：
 * 顶部 1px 描边只有自绘那套有、把手下方 6dp 间距只有 M3 那套有、把手的圆角与颜色两处
 * 各写一遍。数字对齐挡不住这些 —— 改一处不会改另一处，而用户看到的是「两个弹层不是
 * 一个东西」。所以视觉部分收进这里：常量一处、把手一处、壳一处。
 *
 * 收进来的**只有视觉**：没有手势、没有动画、没有高度策略。壳里的 `content` 由调用方
 * 决定自己在 `Dialog` 里还是在 `ModalBottomSheet` 里。
 *
 * ## 为什么 [PiSheet] 不套 [PiSheetChromeFrame]
 *
 * `ModalBottomSheet` 的底色与圆角是它自己的参数（`containerColor` / `shape`），表面由
 * 它来画；再在 content 里套一层 frame 等于把同一个表面画两遍，两层只要有一点不一致，
 * 露出来的就是一圈更深的边。所以 [PiSheet] 直接把这些常量喂给 M3 的参数，只有自绘的
 * `WsSheet` 才需要 frame。两条路读的是同一份常量，这才是「同一个东西」；壳的形态不同
 * 只是各自的容器决定的。
 *
 * ## 数值的唯一出处
 *
 * 设置面 token 里已有的那几个（[PiSettingsMetrics.sheetTopRadius]、
 * `sheetHandleWidth/Height`、`sheetHeadTop/Bottom`、`sheetFooterTop/Bottom`、
 * `pageHorizontal`）在这里**转引**，不另抄一份数字：token 文件不归本批可改，而转引之后
 * 每个数字仍然只有一处字面量，弹层侧也只有一个入口。只属于弹层的几个（把手圆角 2、
 * 把手上 8 下 6、遮罩 `0x52000000`）则直接落在本文件 —— 它们此前是在两个文件里各写
 * 一遍的。
 */
internal object PiSheetChromeMetrics {

    /**
     * 顶部圆角 16（只圆上面两个角）：弹层是独立一档，不跟卡片/输入的 8/10 走。
     *
     * 自绘外壳用它裁面板（`clip`）；M3 外壳把它喂给 `shape` 参数。
     */
    val topRadius: Dp = PiSettingsMetrics.sheetTopRadius

    /** 面板形状：两个上角圆、两个下角贴屏底不圆。 */
    val shape: RoundedCornerShape = RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
    )

    /** 把手 `32×3`。 */
    val handleWidth: Dp = PiSettingsMetrics.sheetHandleWidth
    val handleHeight: Dp = PiSettingsMetrics.sheetHandleHeight

    /** 把手圆角 2：32×3 的细横条上，2 与胶囊肉眼无差，但两套壳必须同值。 */
    val handleRadius: Dp = 2.dp

    /** 把手上方 8、下方 6：这条 6 是「把手与下面第一行之间」的呼吸，两套壳共用。 */
    val handleTopPadding: Dp = 8.dp
    val handleBottomGap: Dp = 6.dp

    /** 头部 `12px 14px 8px`；14 与设置面的页边同值。 */
    val headTop: Dp = PiSettingsMetrics.sheetHeadTop
    val headBottom: Dp = PiSettingsMetrics.sheetHeadBottom
    val headHorizontal: Dp = PiSettingsMetrics.pageHorizontal

    /** 页脚 `10px 14px 14px`。 */
    val footerTop: Dp = PiSettingsMetrics.sheetFooterTop
    val footerBottom: Dp = PiSettingsMetrics.sheetFooterBottom
    val footerHorizontal: Dp = PiSettingsMetrics.pageHorizontal

    /**
     * 遮罩 `0x52`（82/255 ≈ 32%）黑。
     *
     * 为什么是整数而不是 `Color.Black.copy(alpha = 0.32f)`：浮点的 0.32 是 81.6/255，
     * 与稿子的 `rgba(0,0,0,.32)` 换算结果差一档；两套壳各写一遍就会各漂各的，用同一个
     * 字面量则改遮罩只需改一处。
     */
    val scrim: Color = Color(0x52000000)

    /** 面板底色 `surfaceContainerHigh`：M3 的 `containerColor`，也是自绘壳的 `background`。 */
    val containerColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /**
     * 把手色 `outlineVariant`（= 稿子里 `borderMuted` 的 55% 版本）。
     *
     * 两套壳以前一个用不透明的 `borderMuted`、一个用 `outlineVariant`：尺寸一样、深浅
     * 不一样，两个壳并排就看得出。
     */
    val handleColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outlineVariant
}

/**
 * 弹层顶部的把手本体，**含把手下方那 6dp 间距**，全 App 同一个。
 *
 * 为什么把「下方 6dp」放在这个组件里而不是留给两个壳各自加：这条间距就是把手与标题之间
 * 的距离，它属于把手这块视觉，而不是属于哪一种容器。放在外面，两套壳就会一个有一个没有
 * —— 上一轮正是这样。
 *
 * @param modifier 调用方加在外层 `Box` 上的修饰符（宽度、对齐之类）。默认值让上一轮的
 *   无参调用照旧成立。
 */
@Composable
fun PiSheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = PiSheetChromeMetrics.handleTopPadding,
                bottom = PiSheetChromeMetrics.handleBottomGap,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(
                    width = PiSheetChromeMetrics.handleWidth,
                    height = PiSheetChromeMetrics.handleHeight,
                )
                .background(
                    PiSheetChromeMetrics.handleColor,
                    RoundedCornerShape(PiSheetChromeMetrics.handleRadius),
                ),
        )
    }
}

/**
 * 弹层外壳的**视觉部分**：底色 + 顶部圆角 + 可选 1px 上沿 + content 槽。
 *
 * 它只负责看起来像不像同一个弹层：不接管手势、不接管动画、不接管高度。要不要
 * `Dialog`、要不要 `ModalBottomSheet`、面板能长多高、下滑怎么关，全部由调用方决定 ——
 * 这里的 `content` 就是一个普通的内容槽。
 *
 * ## 为什么没有那条 1px 上沿（统一为没有）
 *
 * 稿子（`workspace-final.html:514-532`）的 `.b-sheet` 有一条 `border-top:1px`，自绘
 * 外壳当年照抄了它。**这一轮统一时把它去掉了**，理由有两条：
 *
 *  1. M3 那套（聊天六个弹层、设置三处）从来没有这条线。弹层的底是
 *     `surfaceContainerHigh`，它与页面的分界已经由「遮罩压暗 + 上面两个圆角」给出；
 *     再加一条 1px 的亮线，自绘面板就比别的弹层多一个框，并排打开时最扎眼的就是它。
 *  2. 「一致」只能两边一起加或一起不加。一起加等于改掉 App 里已经稳定的全部 M3 弹层，
 *     收益是一条线，成本是九个弹层的观感都动一遍 —— 不成比例。所以统一为没有。
 *
 * [topEdge] 参数仍然保留，因为「有没有上沿」本来就是视觉壳的职责：将来设计若重新要它，
 * 应该只在这一处打开，而不是回到 `WsSheet` 里再写一份贴着圆角的描边（原来那份是靠
 * 把线描在圆角内半径上才没被 `clip` 切掉半个像素的，重写一次就得再想一遍）。
 *
 * @param modifier 外壳自身的修饰符（宽度之类）。
 * @param topEdge 上沿描边色；`null`（默认，也是目前唯一的取值）= 不画。目前唯一的调用方
 *   是 `WsSheet`，它传的就是默认值 —— 两个壳统一为「没有上沿」，理由见上面。
 */
@Composable
internal fun PiSheetChromeFrame(
    modifier: Modifier = Modifier,
    topEdge: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val edge = if (topEdge == null) Modifier else Modifier.piSheetTopEdge(topEdge)
    Column(
        modifier = modifier
            // 顺序与自绘外壳原来那串一致：先按形状裁，再铺底色，最后描边（描边要在裁
            // 之后，否则 1px 的外半边会被裁掉）。
            .clip(PiSheetChromeMetrics.shape)
            .background(PiSheetChromeMetrics.containerColor)
            .then(edge),
        content = content,
    )
}

/**
 * 面板上沿 1px：**只画上面一条**，并且贴着两个上圆角走（`Modifier.border()` 是四面一起
 * 画的，这里要的只是一条线）。
 *
 * 中心线往里让半个线宽：`clip` 会把描边的外半边切掉，不让的话 1px 只剩 0.5px。
 * 半径同步减去这半个线宽，弧线才和圆角的切线接得上。
 *
 * 目前没有调用方传 [PiSheetChromeFrame.topEdge]，但这条线留在壳里而不是删掉，理由见
 * [PiSheetChromeFrame] 的 KDoc。
 */
private fun Modifier.piSheetTopEdge(color: Color): Modifier = drawWithCache {
    val half = PiSettingsMetrics.hairline.toPx() / 2f
    val radius = (PiSheetChromeMetrics.topRadius.toPx() - half)
        .coerceAtLeast(0f)
        .coerceAtMost(size.height / 2f)
    val top = Path().apply {
        moveTo(half, half + radius)
        arcTo(Rect(half, half, half + 2f * radius, half + 2f * radius), 180f, 90f, false)
        lineTo(size.width - half - radius, half)
        arcTo(
            Rect(
                size.width - half - 2f * radius,
                half,
                size.width - half,
                half + 2f * radius,
            ),
            270f,
            90f,
            false,
        )
    }
    val stroke = Stroke(width = PiSettingsMetrics.hairline.toPx())
    onDrawWithContent {
        drawContent()
        drawPath(top, color, style = stroke)
    }
}
