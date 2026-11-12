package app.pi.ui.extension

// 裸 JVM harness：扩展面板的纯规则（ExtensionPanelRules.kt）。
// 钉三件编译器看不见的事：元素内换行不能被悄悄吞掉、病态输入有上限、宽度换算落在宿主能收的范围。

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $name") else { failures++; println("FAIL $name $detail") }
}

fun main() {
    check("普通行原样", panelRows(listOf("a", "b")) == listOf("a", "b"))
    check("元素内换行拆成多行（不丢后面的行）", panelRows(listOf("a\nb\nc", "d")) == listOf("a", "b", "c", "d"))
    check("空数组 = 无行", panelRows(emptyList()).isEmpty())
    val huge = panelRows(List(10) { "x\n".repeat(100) })
    check("行数有上限", huge.size == MAX_PANEL_ROWS, "${huge.size}")
    check("上限在拆分过程中就生效", panelRows(listOf("\n".repeat(1_000_000))).size == MAX_PANEL_ROWS)

    check("标题：分隔符换成空格", widgetCaption("web-activity") == "web activity")
    check("标题：下划线", widgetCaption("background_tasks") == "background tasks")
    check("标题：空 key 无标题", widgetCaption("  ") == null)

    check("列数：正常换算", columnsFor(widthPx = 1000, chromePx = 100, charPx = 10) == 90)
    check("列数：太窄取下限", columnsFor(widthPx = 50, chromePx = 100, charPx = 10) == MIN_COLUMNS)
    check("列数：太宽取上限", columnsFor(widthPx = 100_000, chromePx = 0, charPx = 10) == MAX_COLUMNS)
    check("列数：字符宽为 0 不除零", columnsFor(widthPx = 1000, chromePx = 0, charPx = 0) == MAX_COLUMNS)

    if (failures > 0) { println("harness: FAILED ($failures)"); kotlin.system.exitProcess(1) }
    println("harness: OK (panel-rules)")
}
