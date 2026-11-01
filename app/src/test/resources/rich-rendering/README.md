# 富渲染固定夹具（`rich-rendering/`）

本目录是**升级前的行为基线输入**，只放数据，不含代码。它与
`docs/rich-rendering-baseline.md` 配对：文档记录「今天是什么样」，
这里记录「拿什么去量」。

**快照**：`main` / `c636c86`（2026-11-01）。`now=` 的值只描述**这个提交**的行为。
如果升级前有其它改动落到 `main`，必须重新核对 `now=`，不得为了迁就新代码改它。

## 1. 文件不是「一篇文档」，是一串独立用例

每个文件由若干**互相独立**的输入组成。消费方（harness、真机手工、截图）必须把它们
**分开**喂给渲染管线，不能把整个文件当成一条消息。

## 2. 用例注解（唯一格式）

每个用例以**一行**注解开头，位置在该用例正文之前：

```text
<!-- @case <id> | now=<state> | expect=<state> | <一句话说明> -->
```

- `<id>`：目录内唯一，前缀按文件分配（见 §5）。
- `now=`：**`c636c86` 上今天的行为**，取自 `docs/rich-rendering-baseline.md` §2 的
  逐项核对结果，不是猜的。
- `expect=`：升级完成后期望的行为。
- 说明字段：写清「富渲染」到底指什么（SVG / 树 / 折叠 / 真排版 …），或写清为什么
  必须回退源码。**只写状态词不算说明**。

取值词表（两个字段共用）：

| 值 | 含义 |
|---|---|
| `source` | 以源码/原文呈现。**包括带语法高亮的代码块**，以及正文里的原文文字。 |
| `approx` | 以本地近似形态呈现：Mermaid 的 Unicode 框线图（`grok-mermaid`）、PiLatex 的 Unicode 网格。它是「画了，但不是真正的图形/排版」。 |
| `rich` | 目标形态：SVG 图形、树状视图、Compose 折叠组件、真正的数学排版。 |
| `partial` | **只允许出现在 `expect=`**：流式未闭合期间允许的中间态（必须是 `source` 或 `approx`），结构闭合后必须变成 `rich` 或 `source`。 |

`now=rich` 目前**不应该出现**在任何用例里；出现即说明基线记错了（今天没有任何一类
内容走真正的富渲染）。`now=partial` 同理不出现。

## 3. 正文取法（可执行规则）

```text
注解行：  ^<!-- @case (\S+) \| now=(source|approx) \| expect=(source|rich|partial) \| (.*) -->$
          （必须按行匹配，即 MULTILINE —— 否则 ^/$ 只锚整份文件的首尾）
切分：    以每条注解行为界；正文 = 注解行之后、下一条注解行之前的全部行
修剪：    去掉正文首尾的空行（CommonMark / JSON / XML / YAML 都不受空行边界影响）
文件头：  第一条 @case 之前只允许 HTML 注释（说明用），它不是任何用例的输入
```

Kotlin 侧的等价实现（纯 stdlib，不需要 JUnit，也不需要 classpath 资源）：

```kotlin
private val CASE = Regex(
    """^<!-- @case (\S+) \| now=(source|approx) \| expect=(source|rich|partial) \| (.*) -->$""",
    RegexOption.MULTILINE,
)

fun cases(file: File): List<Case> {
    val text = file.readText()
    val marks = CASE.findAll(text).toList()
    return marks.mapIndexed { i, m ->
        val end = marks.getOrNull(i + 1)?.range?.first ?: text.length
        Case(
            id = m.groupValues[1],
            now = m.groupValues[2],
            expect = m.groupValues[3],
            note = m.groupValues[4],
            body = text.substring(m.range.last + 1, end)
                .lineSequence()
                .dropWhile { it.isBlank() }
                .toList()
                .dropLastWhile { it.isBlank() }
                .joinToString("\n"),
        )
    }
}
```

注意：注解本身是 HTML 注释，**在 Markdown 预览里看不见**，但解析器看得见。任何
harness 喂给渲染器之前必须先剥掉注解行；`html.md` 里有一条用例专门测真实 HTML 注释，
所以剥除规则只能匹配上面这个 `@case` 前缀，**不能**按「所有 `<!-- … -->`」剥。

路径解析与本仓库现有 harness 一致：`File(System.getProperty("pi.repo.root") ?: ".")`
（见 `PiLatexCheck.kt` / `PiHtmlCheck.kt`）。夹具缺失应当 `exit 2`，不得静默跳过。

## 4. 硬规则

1. **`now=` 是历史事实，不许改。** 新行为改变了某个用例的结果，就改 `expect=` 并在
   说明里写清哪一阶段改的；`now=` 一旦被改，这份夹具就不再是基线。
2. **一个文件不是一份文档**（§1）。合并成一个文件喂进去的结论没有意义。
3. 夹具里的内容**不得被「整理」**：缩进、尾随空格、CRLF、制表符、超长行、故意写坏
   的 JSON/XML/HTML 都是被测对象。
4. 新增用例只追加，不重排已有 `id`；真机截图按 `id` 命名（§6）。
5. 这里只是输入。**断言值不在这里**——升级前的行为在
   `docs/rich-rendering-baseline.md` §2，升级后的目标在该文档 §3 的对比口径里。

## 5. 本目录的文件与 id 前缀

| 文件 | id 前缀 | 覆盖 |
|---|---|---|
| `code-fence-metadata.md` | `cf-` | 围栏 info string：语言 + `title`/`filename`/`file`、未识别属性、无语言、大小写、`language-` 前缀、mermaid 带 title、超长行 |
| `diff.md` | `df-` | ` ```diff ` 围栏（统一 diff / git 头 / 伪装成 diff 的散文） |
| `mermaid.md` | `mm-` | 12 种 Mermaid 图；其中 7 种今天的 `grok-mermaid 0.2.3` 不认 |
| `latex.md` | `lx-` | 行内/块级、矩阵、aligned、gather、cases、行内矩阵、未知宏、代码/价格护栏、行中 `$$` 既有偏差 |
| `json.md` | `js-` | 对象、对象数组、标量类型、坏 JSON、正文里的裸 JSON |
| `xml.md` | `xm-` | 嵌套/属性/文本、声明/注释/CDATA、坏 XML、XXE |
| `yaml.md` | `ym-` | Map/List/标量、多文档、锚点、危险标签、坏 YAML |
| `html.md` | `ht-` | details/summary、块级、行内格式、危险标签、嵌入标签、未知标签、实体、表格、真实注释 |
| `svg.md` | `sv-` | 基本图形、defs/use/marker/clipPath/gradient、脚本、foreignObject、外部引用、超宽 viewBox |
| `malformed.md` | `mf-` | 不闭合围栏、坏 JSON/XML/HTML、坏 Mermaid、>400 行、深层嵌套、超长行 |
| `streaming-partial.md` | `st-` | 生成到一半的围栏/公式/JSON/HTML/Mermaid/表格/强调，以及中断 |

## 6. 真机截图命名

`docs/screenshots/` 下按 `rich-<id>-<theme>.jpg` 命名（`theme` = `light`/`dark`），
例如 `rich-mm-flowchart-dark.jpg`。录屏按 `rich-<id>-stream.mp4`。
唯一的例外是 `mf-*` 与 `st-*`：它们不单独截图，只作为录屏或崩溃排查的证据附在
`docs/rich-rendering-baseline.md` §5 的表格里。
