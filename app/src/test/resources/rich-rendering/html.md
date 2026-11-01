<!-- @case ht-details-summary | now=source | expect=rich | details/summary：升级后转成 Compose 折叠组件（默认遵循 HTML 的折叠行为，支持嵌套、各自独立的展开状态）。今天整块是原文，标签字符都在屏幕上。 -->
<details>
  <summary>点击查看详细解释</summary>
  <p>这里是补充说明。</p>
  <details>
    <summary>嵌套一层</summary>
    <ul>
      <li>第一项</li>
      <li>第二项</li>
    </ul>
  </details>
</details>

<!-- @case ht-block-div-p | now=source | expect=rich | 块级白名单（div/p/blockquote/ul/ol/li/hr/figure/figcaption）：升级后转成 Compose 块结构。今天照 pi 的规则「原文当文字排」：`raw.trim()`，内部换行保留。 -->
<div class="card">
  <p>第一段。</p>
  <blockquote>引用一行。</blockquote>
  <ul>
    <li>无序一</li>
    <li>无序二</li>
  </ul>
  <hr>
  <figure>
    <figcaption>图注文字</figcaption>
  </figure>
</div>

<!-- @case ht-inline-format | now=source | expect=rich | 行内白名单（strong/b/em/i/del/s/mark/u/code/kbd/sup/sub/span/br/a）：升级后是行内富文本。今天这些标签是**正文里的可见字符**（`PiHtml.inlineTextAt` 原样 append）。 -->
这是 <strong>粗体</strong>、<em>斜体</em>、<del>删除</del>、<mark>高亮</mark>、<code>code</code>、<kbd>Ctrl</kbd>、H<sub>2</sub>O、x<sup>2</sup>、<span class="x">span</span>，还有<br>换行与<a href="https://example.com">链接</a>。

<!-- @case ht-dangerous | now=source | expect=source | **危险标签**：script/onclick/onerror/javascript: URL。要求：绝不执行、绝不请求；内容不得静默消失（安全降级或整块回退源码）。今天是原文（安全，因为根本不解析），但**升级后若做 HTML 渲染，这条就是安全门槛**。 -->
<div onclick="steal()">
  <script>alert('x')</script>
  <img src="x" onerror="alert(1)">
  <a href="javascript:alert(2)">点我</a>
</div>

<!-- @case ht-embed-tags | now=source | expect=source | iframe/object/embed/form/input：一律禁止。要求整块安全回退（可读的源码或无害文本），不得加载任何外部资源、不得生成可提交的表单。 -->
<iframe src="https://example.com/embed"></iframe>
<object data="movie.swf"></object>
<embed src="movie.swf">
<form action="https://example.com/collect">
  <input name="q" value="secret">
</form>

<!-- @case ht-unknown-tag | now=source | expect=source | 未知标签（`<pi-card>`、`<my-widget>`、自闭合未知元素）：**安全降级**，保留里面的全部文字，不得整块删掉。方案 §六.4 明确禁止「像简单过滤器一样把未知内容全删」。 -->
<pi-card title="自定义">
  <pi-row>未知标签里的文字必须保留</pi-row>
</pi-card>
<my-widget data-x="1" />

<!-- @case ht-entities | now=source | expect=source | HTML 实体（`&amp;` `&lt;` `&gt;` `&nbsp;` `&#39;` `&#x1F600;`）：**口径待父代理裁定**。今天它们是原文（不解码）；升级后如果解码，复制与搜索的行为都会变。基线先按「保留原文」记录，任何解码都必须是显式决定。 -->
<p>实体：&amp; &lt; &gt; &quot; &nbsp; &#39; &#x1F600;</p>

<!-- @case ht-table | now=source | expect=rich | HTML 表格（table/thead/tbody/tr/th/td）：升级后转为表格组件（可横滑、表头区分）。今天整块是原文。注意与 GFM 表格是两条不同的入口。 -->
<table>
  <thead>
    <tr><th>阶段</th><th>产出</th></tr>
  </thead>
  <tbody>
    <tr><td>Phase 0</td><td>基线</td></tr>
    <tr><td>Phase 1</td><td>Rich Block Registry</td></tr>
  </tbody>
</table>

<!-- @case ht-comment | now=source | expect=source | 真实 HTML 注释（不是本目录的 `@case` 注解）：今天原文可见；升级后按 HTML 语义注释不显示。**口径待裁定**：注释不是可见内容，但它也是模型输出的一部分，删除必须有明确依据。基线按「保留原文」记录。 -->
<div>
  <!-- 这条注释属于 HTML，不属于可见内容 -->
  <p>注释下面才是正文。</p>
</div>
