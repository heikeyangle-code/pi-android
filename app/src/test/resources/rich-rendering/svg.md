<!-- @case sv-basic | now=source | expect=rich | 基本图形（rect/circle/line/text）：升级后本地解析 + 等比例绘制 + 缩放/平移/适应屏幕/源码切换/复制源码。今天只是 highlight.js 的 xml 着色代码块（`svg` 在 hljs 10.7.3 里是 `xml` 的别名）。 -->
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200" width="400" height="200">
  <rect x="10" y="10" width="180" height="80" rx="8" fill="#1f6feb" stroke="#0b3d91"/>
  <circle cx="300" cy="50" r="36" fill="none" stroke="#d29922" stroke-width="3"/>
  <line x1="10" y1="150" x2="390" y2="150" stroke="#8b949e" stroke-width="1"/>
  <text x="20" y="180" font-size="14" fill="#c9d1d9">基线：源码</text>
</svg>
```

<!-- @case sv-defs-use-marker-clip-gradient | now=source | expect=rich | **AndroidSVG 兼容性关键样本**：defs / use / marker / clipPath / linearGradient / style。方案 §八 要求实际验证 Mermaid.js 与 MathJax 的输出能被正确绘制——这几种元素就是最容易丢的。今天仍是源码块。 -->
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 220" width="420" height="220">
  <defs>
    <linearGradient id="g1" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#1f6feb"/>
      <stop offset="100%" stop-color="#3fb950"/>
    </linearGradient>
    <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5"
            markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="#c9d1d9"/>
    </marker>
    <clipPath id="clip">
      <rect x="20" y="20" width="160" height="60" rx="10"/>
    </clipPath>
    <path id="tick" d="M0,0 L0,8" stroke="#8b949e"/>
  </defs>
  <rect x="20" y="20" width="160" height="60" fill="url(#g1)" clip-path="url(#clip)"/>
  <line x1="30" y1="120" x2="380" y2="120" stroke="#c9d1d9" stroke-width="2" marker-end="url(#arrow)"/>
  <use href="#tick" x="60" y="130"/>
  <use href="#tick" x="160" y="130"/>
  <text x="30" y="190" font-size="13" fill="#c9d1d9">defs / use / marker / clipPath / gradient</text>
</svg>
```

<!-- @case sv-script-onload | now=source | expect=source | **脚本与事件属性**：`<script>`、`onload`、`onclick`、`javascript:` 一律禁止。要求：不执行、不请求，整块回退源码。今天安全（不解析），升级后必须由 `PiSvgSanitizer` 兜住。 -->
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100" onload="alert('x')">
  <script>alert('svg script')</script>
  <a href="javascript:alert(2)">
    <rect x="10" y="10" width="80" height="40" onclick="alert(3)"/>
  </a>
</svg>
```

<!-- @case sv-foreignObject | now=source | expect=source | `foreignObject` 内嵌 HTML：方案 §八 明确禁止。要求回退源码，不得把里面的 HTML 塞进渲染管线。 -->
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 120">
  <foreignObject x="0" y="0" width="300" height="120">
    <div xmlns="http://www.w3.org/1999/xhtml">内嵌 HTML</div>
  </foreignObject>
</svg>
```

<!-- @case sv-external-href | now=source | expect=source | 外部资源引用（`image href` 指向 https、`use href` 指向本地文件）：禁止网络请求、禁止读本地文件。要求回退源码；即使图能画，也不得为了它去取任何字节。 -->
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 160">
  <image href="https://example.com/tracker.png" x="0" y="0" width="200" height="160"/>
  <use href="file:///etc/passwd#x"/>
</svg>
```

<!-- @case sv-wide-viewbox | now=source | expect=rich | 超宽画布（viewBox 2400×300）：升级后必须等比例缩放 / 拖动平移 / 适应屏幕，右侧文字不得被裁掉；也必须受画布尺寸上限保护（超限回退源码）。今天只是源码块。 -->
```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2400 300" width="2400" height="300">
  <rect x="0" y="0" width="2400" height="300" fill="#0d1117"/>
  <text x="40" y="100" font-size="40" fill="#58a6ff">左端</text>
  <text x="1180" y="100" font-size="40" fill="#3fb950">中点</text>
  <text x="2200" y="100" font-size="40" fill="#f85149">右端（必须能平移到）</text>
</svg>
```
