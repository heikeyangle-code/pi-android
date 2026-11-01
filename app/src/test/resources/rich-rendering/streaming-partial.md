<!-- 本文件里的每一条都是**某一时刻的完整快照**：把 body 当成「现在屏幕上的全部文本」喂给管线，
     不是把多条拼起来。`expect=partial` 的含义见 README §2：流式中允许源码，闭合并稳定后必须
     转成 rich。`now=` 记录的是 c636c86 上这一帧会画成什么。 -->

<!-- @case st-unclosed-fence | now=source | expect=partial | 围栏只写了开头（` ```kotlin ` + 两行代码，没有收栏）：今天解析成「开放代码块」，整段按代码块画；因为内容还在变，高亮器 200 ms 防抖后仍不请求，所以是未上色代码块。这里允许源码；**收栏到达后**才允许 rich（高亮/文件名/行号）。 -->
```kotlin
fun main() {
    println("still typing")

<!-- @case st-closed-fence-then-growing | now=source | expect=partial | 围栏**已经闭合**、后面还在生成正文：这一帧里围栏可以（也应该）立即富渲染——`partial` 允许它已经是 rich，但要求后续 token 不得让它闪烁、消失或回退成源码。今天围栏是普通高亮代码块，正文 `**粗体` 还是字面星号。 -->
```json
{ "ok": true }
```
接下来是说明：**重点还没写完

<!-- @case st-unclosed-block-math | now=source | expect=partial | 块级公式只写了开定界符：`$$` 后面没有收尾。`PiLatex.prepare` 的块级正则要求成对出现，所以今天这些字符原样留在正文（`$$`、`\frac` 都可见）。允许源码/近似；闭合后才允许真排版。 -->
$$
\frac{a}{b} = \frac{1}{2

<!-- @case st-unclosed-inline-math | now=source | expect=partial | 行内公式只写了一半：`$E=mc` 没有收尾 `$`。今天 `PiLatex` 不匹配 → 原样显示（美元符仍在）。升级后**不得**因为「看起来像公式开头」就提前启动渲染任务。 -->
质能方程 $E=mc

<!-- @case st-json-half | now=source | expect=partial | JSON 生成到一半（数组没闭合、对象没闭合、围栏也没闭合）：今天整段按开放代码块画、不上色。**不允许**在 JSON 不完整时切换树状视图（方案 §十四.4）。 -->
```json
{
  "blocks": [
    { "kind": "assistant-text", "text": "正在输出"

<!-- @case st-html-half | now=source | expect=partial | HTML 生成到一半（`<details>`、`<summary>` 都还没闭合）：今天原文显示（标签字符可见）。**不允许**在 HTML 未闭合时提前生成可交互的折叠组件（方案 §十四.3）。 -->
<details>
  <summary>点击展开

<!-- @case st-mermaid-half | now=source | expect=partial | Mermaid 生成到一半（最后一条边没有目标）：`grok-mermaid` 的「丢掉最后一行重试」会把这条图救回来，但会在 `warnings` 里记一条；而 `PiCodeSurface` 的规则是**有 warnings 就不画图**，只画源码 + 一行「Mermaid 图未完整渲染：…」。所以今天这一帧是源码 + 警告行。升级后流式阶段可以继续显示源码，闭合后必须变成 SVG。 -->
```mermaid
flowchart TD
  A[开始] --> B{判断}
  B --> C[

<!-- @case st-table-half | now=source | expect=partial | GFM 表格只写了表头与半个分隔行（`| --- | --`）：分隔行未完成前它只是一个段落，不是表格。今天显示成带竖线的文字。升级后不得提前把半张表渲染成表格组件。 -->
| 阶段 | 产出 |
| --- | --

<!-- @case st-trailing-bold | now=source | expect=partial | 行尾未闭合的强调（`**重点`）：今天显示字面 `**`。这是最普通的流式中间态，允许源码；闭合后必须恢复粗体。它是「新增富渲染不得让普通 Markdown 回退」的对照组。 -->
这一段是普通文字，后面是**重点

<!-- @case st-ended-mid-fence | now=source | expect=source | **不是流式**：消息已经结束（`streaming=false`），但内容里有一个永远闭合不了的围栏。今天它仍是开放代码块——内容完整可见、不报错。升级后必须在「消息结束」时重新解析一次，但仍然只能显示源码，不能因为「结构不完整」把整块丢掉或打出错误。 -->
```bash
echo "message ended before the fence closed"
