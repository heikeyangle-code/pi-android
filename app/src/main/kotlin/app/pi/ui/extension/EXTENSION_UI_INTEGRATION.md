# 扩展 UI（SDK 版）

范围：`app/src/main/kotlin/app/pi/ui/extension/**` 与 `host/ExtUiState.kt`。
扩展对 `ctx.ui` 的每一次调用，宿主发成一个 `ui.*` 事件或一次提问（`docs/sdk-host/protocol.md`）；
App 这一侧分两类：**状态**（归并器）和**一次性**（对话框、通知、填输入框）。

## 状态：`ExtUiReducer` → `ExtUiState`

纯 Kotlin，harness `host-client` 钉规则。界面只画状态，不认事件。

| 状态 | 来自 | 画在 |
|---|---|---|
| `widgets` | `ui.widget`（字符串行）/ 组件工厂的 `ui.surface` 帧 | 输入框上下的面板（`ExtensionPanels.kt`） |
| `header` / `footer` | `ui.chrome` + 表面帧 | 顶栏下 / 输入框下的细栏（同一种面板卡） |
| `custom` | `ctx.ui.custom()` 的表面 | 底部面板，可交互时带按键条（`ExtensionSurfaces.kt`） |
| `statuses` | `ui.status` | 输入框上方一行可横滑的小标签，点开换行展开（`ExtensionStatusStrip`） |
| `working.message` | `ui.working` | 顶栏状态词（取代「工作中」） |
| `editorTakeover` | `ui.chrome{slot:editor,supported:false}` | 状态行里一枚灰标签 |
| `working.frames/visible`、`thinkingLabel` | `ui.workingIndicator` 等 | **已进状态，还没画** |

组件表面的规则（钉在 harness 里）：帧可能先于「属于谁」的宣布到达，所以先缓存；`rev` 不增的旧帧忽略；
表面被关或被替换后迟到的帧不复活；小组件按首次出现的顺序稳定。

## 一条绘制路径

宿主把组件渲染成**带 ANSI 的行**，字符串行也是行。所以面板、header、footer、custom 都走
`ExtensionSpans`（ANSI → 主题令牌）：等宽、一行不折行；表格/框线（`isPreformatted`）整块横滚，普通句子折行。
列数由界面量出（`columnsFor`）、经 `ui.resize` 告诉宿主，宿主据此重绘所有表面。

## custom 表面的输入与关闭

- 按键 = 终端字节序列（`SurfaceKeys`），经 `ui.input` 喂给组件的 `handleInput`；文字框里的字符逐个作为按键发送。
- 关闭：先发 Esc 让组件自己收尾；250 ms 后还在就 `ui.close` 强关。强关时宿主让扩展的 `await ctx.ui.custom()`
  以 `undefined` 返回（`surfaces.mjs` 的 `onClose`；sdk-host 测试钉了这一条，否则扩展会永远挂着）。

## 一次性：对话框与通知

`select / confirm / input / editor` 是宿主的 `ui.*` **提问**（`HostRequest`），答复走 `ui.respond`；
超时由宿主按 `timeout` 收回，迟到的回答不是错误。`notify` 进 snackbar 队列。`set_editor_text` 填输入框。
这部分没有 SDK 以外的行为，沿用 `ExtensionDialogs.kt` / `ExtensionUiHost.kt`。

## 不做

- 终端编辑器组件（`setEditorComponent`）：手机输入框接管不了，只在 sheet 里说明。
- 对任何具体扩展的适配：负载折叠、状态着色、按名字猜用途，都已删除。
