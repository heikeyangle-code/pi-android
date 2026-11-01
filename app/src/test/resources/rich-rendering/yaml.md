<!-- @case ym-map-list | now=source | expect=rich | Map 套 List、List 套 Map、标量与引号：升级后为可折叠树（Map/List/Scalar、层级折叠、原始 YAML 与树切换）。今天只是 highlight.js 的 yaml 高亮代码块。 -->
```yaml
session:
  id: s-01
  workspace: /root/project
  streaming: false
blocks:
  - kind: assistant-text
    text: "含: 冒号与 # 井号的字符串"
  - kind: tool-call
    tool: read
    ok: true
limits:
  mermaidKib: 64
  svgMib: 2
```

<!-- @case ym-multidoc | now=source | expect=rich | 多文档（`---` 分隔）：树状视图必须把它们并排成多个根，或者按文档分组；不得只显示第一份。 -->
```yaml
---
kind: baseline
commit: c636c86
---
kind: target
phase: 1
```

<!-- @case ym-anchors | now=source | expect=rich | 锚点/别名/合并键：安全加载允许锚点，但合并键（`<<`）与别名必须被解析成普通值或至少不报错。升级后不得因为锚点而拒绝整份文档。 -->
```yaml
defaults: &defaults
  retries: 3
  timeoutMs: 750
prod:
  <<: *defaults
  timeoutMs: 1500
staging:
  <<: *defaults
```

<!-- @case ym-dangerous-tag | now=source | expect=source | **危险标签**：`!!python/object/apply` 与 `!!javax.script.ScriptEngineManager` 绝不能被实例化。今天不解析所以安全；升级后必须用安全加载器，遇到自定义标签**回退源码**而不是执行。 -->
```yaml
safe: 1
evil: !!python/object/apply:os.system ["echo hacked"]
alsoEvil: !!javax.script.ScriptEngineManager []
```

<!-- @case ym-malformed | now=source | expect=source | 坏 YAML（Tab 缩进 + 重复键 + 悬空冒号）：解析失败必须回退源码，保留全部原文。 -->
```yaml
root:
	tabbed: true
dup: 1
dup: 2
dangling:
```
