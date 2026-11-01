<!-- @case xm-nested | now=source | expect=rich | 节点树 + 属性 + 文本节点：升级后为可折叠树（属性单列、文本单独一行、复制节点路径、原始 XML/树切换）。今天只是 highlight.js 的 xml 高亮代码块。 -->
```xml
<config>
  <server host="127.0.0.1" port="3090" scheme="http">
    <name>pi bridge</name>
    <timeoutMs>750</timeoutMs>
  </server>
  <features>
    <feature enabled="true">images</feature>
    <feature enabled="false">ansi</feature>
  </features>
</config>
```

<!-- @case xm-declaration-comment-cdata | now=source | expect=rich | XML 声明、注释、CDATA、命名空间属性：树状视图必须能跳过声明/注释、把 CDATA 当文本节点，并能展示 `xmlns:` 属性。 -->
```xml
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:x="urn:example">
  <!-- 一条真实注释 -->
  <entry>
    <title>基线</title>
    <content type="html"><![CDATA[<p>原样保留的 <b>HTML</b></p>]]></content>
  </entry>
</feed>
```

<!-- @case xm-malformed | now=source | expect=source | 坏 XML（未闭合 + 错误嵌套 + 裸 `&`）：解析必须失败并回退源码。升级后不得出现「半棵树 + 丢失原文」。 -->
```xml
<root>
  <a>open
  <b>mismatched</a>
  裸 & 号
</root>
```

<!-- @case xm-xxe | now=source | expect=source | **XXE**：外部实体必须不被解析、文件不得被读取（`/etc/passwd`），失败回退源码。今天是原文（安全，因为根本不解析）；升级后若引入 XML 解析器，这一条是最低安全门槛。 -->
```xml
<?xml version="1.0"?>
<!DOCTYPE foo [
  <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<root>&xxe;</root>
```
