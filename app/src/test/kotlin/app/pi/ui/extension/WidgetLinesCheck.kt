package app.pi.ui.extension

// 裸 JVM harness：`setWidget` 一行是什么（ExtensionWidgetLines.kt）。只钉通用判断：
// 文字不被吞、超长截断、换行拆行有上限、`<PREFIX>_JSON:{` 折叠且标签只取决于前缀。

import app.pi.ui.blocks.TOOL_LINE_MAX_CHARS

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $name") else { failures++; println("FAIL $name $detail") }
}

fun main() {
    check("plain text is a Text row", widgetRow("hello") == WidgetRow.Text("hello"))
    check("prose that mentions a prefix is still text", widgetRow("FOO_JSON: hello") is WidgetRow.Text)
    val long = "x".repeat(TOOL_LINE_MAX_CHARS + 50)
    val cut = (widgetRow(long) as WidgetRow.Text).text
    check("over-long line is cut and marked", cut.length == TOOL_LINE_MAX_CHARS + 1 && cut.endsWith("…"))

    val a = widgetRow("""FOO_JSON:{"n":1,"t":123}""")
    val b = widgetRow("""FOO_JSON:{"n":2,"t":999}""")
    check("payload folds", a is WidgetRow.Folded)
    check("folded label depends only on the prefix", (a as WidgetRow.Folded).label == (b as WidgetRow.Folded).label && a.label == "FOO_JSON")
    check("malformed payload still folds", widgetRow("FOO_JSON:{oops") is WidgetRow.Folded)

    check("newline splits into rows", widgetRows(listOf("a\nb", "c")).size == 3)
    check("split is bounded", widgetRows(listOf("x\n".repeat(10_000))).size <= MAX_SPLIT_ROWS)

    check("short text needs no disclosure", !widgetNeedsDisclosure(listOf(WidgetRow.Text("hi"))))
    check("long text needs disclosure", widgetNeedsDisclosure(listOf(WidgetRow.Text("x".repeat(WIDGET_DISCLOSURE_CHARS + 1)))))
    check("folded always needs disclosure", widgetNeedsDisclosure(listOf(WidgetRow.Folded("P", "{}"))))

    check("caption humanises the key", widgetCaption("web-activity_log") == "web activity log")
    check("blank key has no caption", widgetCaption("  ") == null)

    if (failures > 0) { println("$failures FAILED"); System.exit(1) }
    println("ok")
}
