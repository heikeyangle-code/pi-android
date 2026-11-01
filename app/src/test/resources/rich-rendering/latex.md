<!-- @case lx-inline-dollar | now=approx | expect=rich | 行内 `$…$`：今天由 `PiLatex.prepare` 在 Markdown 解析**之前**换成 Unicode 文本（`E=mc²`），所以段落里能排出来，但不是真正的数学排版。升级后应为行内 SVG/排版，且**不能**被 Markdown 二次解释。 -->
质能方程 $E=mc^2$ 由爱因斯坦在 1905 年提出。

<!-- @case lx-inline-paren | now=approx | expect=rich | `\(…\)`：解析器 0.7.9 **不认**这个定界符（只认 `$`），今天完全靠 `PiLatex` 预处理；Unicode 替换成立。升级后同样要支持行内排版。 -->
勾股定理 \(a^2+b^2=c^2\) 在直角三角形中成立。

<!-- @case lx-block-dollar | now=approx | expect=rich | 块级 `$$…$$` + `aligned`：今天写成多行 Unicode 网格（`PiLatex` 的 layout 支），并且用 NBSP 保护前导缩进、对含网格的消息打开 `eolAsNewLine` 与等宽段落。升级后必须保留多行结构与 `&` 对齐关系。 -->
$$
\begin{aligned}
E &= mc^2 \\
F &= ma \\
p &= mv
\end{aligned}
$$

<!-- @case lx-block-bracket | now=approx | expect=rich | 块级 `\[…\]`：解析器同样不认，今天靠 `PiLatex` 的行首判定（≤3 空格，`\]` 后到行尾）；Unicode 网格。升级后要保留 `\(...\)`/`\[...\]` 支持。 -->
\[
\sum_{n=1}^{\infty} \frac{1}{n^2} = \frac{\pi^2}{6}
\]

<!-- @case lx-matrix | now=approx | expect=rich | 3×3 `pmatrix`：今天画 Unicode 网格（框线 + 逐行补齐）。升级后真正的矩阵排版。 -->
$$
\begin{pmatrix}
a & b & c \\
d & e & f \\
g & h & i
\end{pmatrix}
$$

<!-- @case lx-cases | now=approx | expect=rich | `cases` 分段函数：今天画 Unicode 网格（左花括号 + 两列）。升级后真正的 cases 排版。 -->
$$
f(x) = \begin{cases}
1 & x > 0 \\
0 & x = 0 \\
-1 & x < 0
\end{cases}
$$

<!-- @case lx-gather | now=approx | expect=rich | `gather` 多行居中：今天画网格；升级后由 MathJax/KaTeX 排版。 -->
$$
\begin{gather}
a = b + c \\
d = e + f
\end{gather}
$$

<!-- @case lx-inline-matrix | now=approx | expect=rich | 行内环境：`PiLatex` 的环境分支**不受 display 门控**，所以行内 pmatrix 今天也会写成多行网格（并因此触发整条消息的等宽段落）。升级后行内环境不能退化成行内一览无余的 Unicode。 -->
矩阵 $\begin{pmatrix}1 & 0 \\ 0 & 1\end{pmatrix}$ 是单位矩阵。

<!-- @case lx-unknown-macro | now=source | expect=source | 未知宏 `\foo` / `\unknowncmd`：`PiLatex` 归约失败 → 原文回退（今天正文里原样显示 `$\foo{bar}$`）。升级后 MathJax/KaTeX 若也失败，必须保留原文或 PiLatex 结果，**不得空白**。 -->
公式 $\foo{bar} + \unknowncmd{x}$ 应当原样保留。

<!-- @case lx-code-guard | now=source | expect=source | 行内代码里的 `$…$` 不是公式：`PiLatex.prepare` 先跳过行内代码，再找公式。护栏：升级后的 annotator/占位方案不得把代码里的美元符吃掉。 -->
行内代码 `$E=mc^2$` 不应变成公式。

<!-- @case lx-fence-guard | now=source | expect=source | 围栏代码里的公式定界符不是公式：`FENCE` 状态跳过整块围栏。护栏：升级后块级公式识别不得穿透代码块。 -->
```latex
$$
E = mc^2
$$
```

<!-- @case lx-price | now=source | expect=source | 价格不是公式：`价格 $5 与 $10` 今天保持正文（`PiLatex` 的 `$` 规则带 `(?<!\s)` 与 `(?![\p{L}\p{N}])`）。护栏：新公式识别不得把普通货币文本吃掉。 -->
这本书 $5 与那本 $10，都不是公式。

<!-- @case lx-midline-display | now=approx | expect=rich | **既有偏差**：行中的 `$$x^2$$` 今天会变成独占一段的显示式（`PiLatex` 的 `$$` 分支没有行首锚点，pi 只在行首才当块级）。升级后应改为行内或由父代理裁定保持现状；无论哪种，`now=approx` 记录的是「今天画成了块」。 -->
abc $$x^2$$ def
