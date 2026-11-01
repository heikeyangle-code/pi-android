<!-- @case mm-flowchart | now=approx | expect=rich | flowchart（含 subgraph、多种节点形状、边标签）。今天 `grok-mermaid 0.2.3` 画 Unicode 框线图，靠 horizontalScroll 看全；升级后应为可缩放 SVG。 -->
```mermaid
flowchart TD
  A[收到模型输出] --> B{有围栏吗}
  B -- 有 --> C[解析 info string]
  B -- 没有 --> D[按段落渲染]
  C --> E{语言已知}
  E -- mermaid --> F[请求图引擎]
  E -- 其它 --> G[代码高亮]
  subgraph 引擎
    F --> H[生成 SVG]
  end
```

<!-- @case mm-sequence | now=approx | expect=rich | sequenceDiagram：participant / 实线虚线箭头 / Note / alt。今天 ASCII 时序图。 -->
```mermaid
sequenceDiagram
  participant U as 用户
  participant A as App
  participant K as 引擎
  U->>A: 发送消息
  A->>K: RPC prompt
  alt 成功
    K-->>A: text_delta
    A-->>U: 增量渲染
  else 失败
    K-->>A: error
    A-->>U: 错误块
  end
  Note over A,K: 流式期间不落盘
```

<!-- @case mm-class | now=approx | expect=rich | classDiagram：继承、组合、可见性标记。今天 ASCII 类图。 -->
```mermaid
classDiagram
  class Renderer {
    +String id
    +render(source) String
  }
  class MarkdownRenderer {
    +String flavor
  }
  class MermaidRenderer {
    +String engine
    +renderSvg(source) String
  }
  Renderer <|-- MarkdownRenderer
  Renderer <|-- MermaidRenderer
  MermaidRenderer *-- "1" Cache
```

<!-- @case mm-state | now=approx | expect=rich | stateDiagram-v2：[*] 起止、命名迁移、复合状态。今天 ASCII 状态图。 -->
```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Streaming: 收到 token
  Streaming --> Streaming: 下一条 token
  Streaming --> Settled: message_end
  Settled --> Idle: 新消息
  Settled --> [*]
```

<!-- @case mm-er | now=approx | expect=rich | erDiagram：实体、关系基数、属性（PK）。今天 ASCII ER 图。 -->
```mermaid
erDiagram
  SESSION ||--o{ MESSAGE : contains
  MESSAGE ||--o{ BLOCK : renders
  SESSION {
    string id PK
    long startedAt
  }
  BLOCK {
    string kind
    int ordinal
  }
```

<!-- @case mm-gantt | now=source | expect=rich | gantt：`grok-mermaid 0.2.3` **不认甘特图**（其 README 明列只支持 flowchart/graph、state、class、ER、sequence），所以今天渲染结果是源码块。升级后必须由 Mermaid.js 出 SVG；这一条是「今天源码 → 升级后图形」的样本。 -->
```mermaid
gantt
  title 迭代计划
  dateFormat YYYY-MM-DD
  section 基线
  夹具与文档 :a1, 2026-11-01, 3d
  section 增强
  围栏与树状视图 :a2, after a1, 7d
  Mermaid SVG :a3, after a2, 10d
```

<!-- @case mm-mindmap | now=source | expect=rich | mindmap：今天不支持（同上）→ 源码。 -->
```mermaid
mindmap
  root((富渲染))
    代码围栏
      语言与文件名
      行号与折叠
    图形
      Mermaid
      SVG
    数据
      JSON
      XML
      YAML
```

<!-- @case mm-pie | now=source | expect=rich | pie：今天不支持 → 源码。 -->
```mermaid
pie title 渲染耗时占比
  "解析" : 40
  "布局" : 35
  "绘制" : 25
```

<!-- @case mm-gitgraph | now=source | expect=rich | gitGraph：今天不支持 → 源码。 -->
```mermaid
gitGraph
  commit id: "c636c86"
  branch feature/rich-rendering
  commit id: "phase-0-baseline"
  commit id: "phase-1-registry"
  checkout main
  merge feature/rich-rendering
```

<!-- @case mm-timeline | now=source | expect=rich | timeline：今天不支持 → 源码。 -->
```mermaid
timeline
  title 升级阶段
  2026-11 : Phase 0 基线
  2026-12 : Phase 1 基础增强
  2027-01 : Phase 2 SVG
```

<!-- @case mm-quadrant | now=source | expect=rich | quadrantChart：今天不支持 → 源码。 -->
```mermaid
quadrantChart
  title 风险与收益
  x-axis 低风险 --> 高风险
  y-axis 低收益 --> 高收益
  quadrant-1 谨慎
  quadrant-2 优先
  quadrant-3 不做
  quadrant-4 可做
  "Mermaid SVG": [0.70, 0.90]
  "MathJax": [0.55, 0.85]
```

<!-- @case mm-sankey | now=source | expect=rich | sankey-beta：今天不支持 → 源码。语法是 Mermaid 10+ 的 `sankey-beta`，Phase 2 选定的 Mermaid.js 版本必须真的支持它，否则这条会永远停在源码。 -->
```mermaid
sankey-beta
A,B,10
B,C,6
B,D,4
```
