<!-- @case js-object | now=source | expect=rich | 嵌套对象：升级后为可折叠树状视图（对象/数组折叠、键值分色、复制键/值/完整 JSON 路径、原始代码与树切换）。今天只是 highlight.js 的 json 高亮代码块。 -->
```json
{
  "session": {
    "id": "s-01",
    "startedAt": 1761955200000,
    "workspace": "/root/project",
    "model": { "provider": "deepseek", "name": "deepseek-chat" }
  },
  "blocks": 13,
  "streaming": false
}
```

<!-- @case js-array-objects | now=source | expect=rich | 对象数组：折叠粒度是「每个元素」还是「整个数组」需要真机确认；这里固定输入形状（3 个对象、字段不一致）。 -->
```json
[
  { "kind": "assistant-text", "streaming": false },
  { "kind": "tool-call", "tool": "read", "ok": true },
  { "kind": "tool-diff", "files": 1, "added": 12, "removed": 3 }
]
```

<!-- @case js-scalars | now=source | expect=rich | 标量类型分色：字符串、数字、布尔、null 必须能区分（升级后），今天是同一种字符串色。 -->
```json
{
  "string": "文本",
  "number": 42,
  "float": 3.5,
  "true": true,
  "false": false,
  "nothing": null,
  "emptyArray": [],
  "emptyObject": {}
}
```

<!-- @case js-invalid | now=source | expect=source | 标签写 `json`、内容不是合法 JSON（尾逗号 + 单引号）：**不得切换树状视图**，必须回退代码块并保留全部原文。升级后的解析失败路径就是这一条。 -->
```json
{
  "trailing": 1,
  'single': "quotes",
}
```

<!-- @case js-inline-text | now=source | expect=source | 段落里的裸 JSON（没有围栏）：**不得猜成 JSON**、不得变成树。方案明确要求「不要把所有普通文本都猜成 JSON」，这条是护栏。 -->
接口返回 `{"ok": true}`，但这里只是正文里的一段文字，不是围栏。
