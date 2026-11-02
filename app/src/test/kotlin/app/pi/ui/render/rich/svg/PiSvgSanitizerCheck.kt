package app.pi.ui.render.rich.svg

// Bare-JVM harness for `PiSvgSanitizer.kt` — the security gate shared by the `svg`
// fence, Mermaid and MathJax (plan §八 / §十三 / §十五).
//
// Why it lives here instead of in a JUnit test: the sanitizer is deliberately pure
// Kotlin (no Compose, no Android, no XML library), and `tools/run-app-pure-checks.sh`
// is the only runner in this repo that compiles `app/src/test/**` on a bare JVM.
// The Android build never compiles unit-test sources, so a check that a green
// `assembleRelease` could not notice has to be executable by a script.
//
// What is actually asserted here, and why each one can be wrong silently:
//
//  - The **good** shapes Mermaid/MathJax really emit (`path`, `defs`, `use`,
//    `marker`, `clipPath`, `linearGradient`, `<style>` with `@keyframes` and
//    `!important`) survive. If they did not, the sanitizer would be "safe" by
//    making every diagram blank.
//  - The **hostile** shapes are gone: `<script>` (including its body — a removed
//    tag that leaves `alert(1)` as text is still a leak), `<foreignObject>`,
//    `on*` attributes, `javascript:`, external/`file:`/`data:` hrefs, `@import`,
//    external `url()`, DOCTYPE/ENTITY, and CSS-obfuscated forms (`&#106;`,
//    `\68`). The checks are on the **output**, not on a helper's return value,
//    because the contract is about what AndroidSVG is handed.
//  - The limits (2 MiB / 20,000 nodes / depth 64 / 8192 canvas) are boundaries:
//    the exact limit passes, one past it is rejected with a Chinese reason.
//  - Tag/attribute matching is case-insensitive while the output preserves
//    `linearGradient`'s case. Both halves matter: lowercasing the output would
//    break AndroidSVG, and case-sensitive matching would let `<SCRIPT>` through.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh
//
// (The parent agent registers it with that script; this file only assumes
// `PiSvgSanitizer.kt` is compiled with it.)

private var failures = 0
private var checks = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    checks++
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun ok(label: String, svg: String): String =
    when (val result = PiSvgSanitizer.sanitize(svg)) {
        is PiSvgSanitizeResult.Ok -> result.svg
        is PiSvgSanitizeResult.Rejected -> error("[$label] expected Ok, got Rejected: ${result.reason}")
    }

private fun rejected(label: String, svg: String): String =
    when (val result = PiSvgSanitizer.sanitize(svg)) {
        is PiSvgSanitizeResult.Rejected -> result.reason
        is PiSvgSanitizeResult.Ok -> error("[$label] expected Rejected, got Ok (${result.svg.length} chars)")
    }

fun main() {
    // ======================================================== A. legitimate SVG
    run {
        val good = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 200" width="400" height="200">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0" stop-color="#fff"/>
                  <stop offset="1" stop-color="#000"/>
                </linearGradient>
                <marker id="arrow" markerWidth="10" markerHeight="10" refX="5" refY="5">
                  <path d="M0 0 L10 5 L0 10 Z" fill="#333"/>
                </marker>
                <clipPath id="cp"><rect width="100" height="100"/></clipPath>
              </defs>
              <path d="M0 0 L10 10" marker-end="url(#arrow)" fill="url(#g)" clip-path="url(#cp)"/>
              <use href="#arrow" x="10" y="10"/>
            </svg>
        """.trimIndent()
        val out = ok("A1", good)
        check("A1 普通 SVG 的 path/defs/use/marker/clipPath/gradient 全部保留", out.contains("<linearGradient id=\"g\""), true)
        check("A2 marker 定义保留", out.contains("<marker id=\"arrow\""), true)
        check("A3 clipPath 保留", out.contains("<clipPath id=\"cp\">"), true)
        check("A4 内部引用 url(#id) 保留", out.contains("marker-end=\"url(#arrow)\""), true)
        check("A5 内部 href(#id) 保留", out.contains("<use href=\"#arrow\""), true)
        check("A6 xmlns 不应被当成危险 URL 删掉", out.contains("xmlns=\"http://www.w3.org/2000/svg\""), true)
    }

    run {
        val style = """
            <svg viewBox="0 0 10 10">
              <style>
                @keyframes dash { from { stroke-dashoffset: 10; } to { stroke-dashoffset: 0; } }
                .a { fill: url(#grad); stroke: red; }
                .b { fill: url(https://evil.example/x.svg#g); }
                @import url("https://evil.example/x.css");
                .c { background: url(data:image/png;base64,AAAA); }
                .d { color: green; }
                .e { stroke-width: 2 !important; }
              </style>
              <rect class="a"/>
            </svg>
        """.trimIndent()
        val out = ok("A7", style)
        check("A7 <style> 标签本身保留（整段不误删）", out.contains("<style>"), true)
        check("A8 安全规则 fill: url(#grad) 保留", out.contains("fill: url(#grad)"), true)
        check("A9 Mermaid 需要的 @keyframes 保留", out.contains("@keyframes dash"), true)
        check("A10 !important 保留", out.contains("stroke-width: 2 !important"), true)
        check("A11 外部 url() 的声明被移除", out.contains("evil.example"), false)
        check("A12 @import 被移除", out.contains("@import"), false)
        check("A13 data: url() 的声明被移除", out.contains("data:image"), false)
        check("A14 其他安全规则仍在", out.contains("color: green"), true)
    }

    run {
        val out = ok("A15", "<svg><rect style=\"fill:url(#g);stroke:url(https://evil.example/s);fill-opacity:0.5\"/></svg>")
        check("A15 style 属性只删危险声明", out.contains("fill:url(#g)"), true)
        check("A16 style 属性里的外部 url() 被删", out.contains("stroke:url"), false)
        check("A17 style 属性里的安全声明保留", out.contains("fill-opacity:0.5"), true)
    }

    run {
        val out = ok("A18", "<svg><rect fill=\"url(#grad)\" stroke=\"url( #other )\"/></svg>")
        check("A18 表现属性里的内部 url() 保留", out.contains("fill=\"url(#grad)\""), true)
        check("A19 表现属性的 url() 允许空白", out.contains("url( #other )"), true)
    }

    // ============================================================ B. script / HTML
    run {
        val out = ok("B1", "<svg><script type=\"text/javascript\">alert(\"x\")</script><rect width=\"10\" height=\"10\"/></svg>")
        check("B1 script 标签被移除", out.contains("<script"), false)
        check("B2 script 正文也被移除（不能漏成普通文本）", out.contains("alert"), false)
        check("B3 script 之后的正常元素保留", out.contains("<rect width=\"10\" height=\"10\"/>"), true)
    }
    check(
        "B4 自闭合 script 被移除",
        ok("B4", "<svg><script/></svg>").contains("<script"),
        false,
    )
    run {
        val out = ok("B5", "<svg><foreignObject width=\"10\" height=\"10\"><div xmlns=\"http://www.w3.org/1999/xhtml\">secret</div></foreignObject><rect/></svg>")
        check("B5 foreignObject 被移除", out.lowercase().contains("foreignobject"), false)
        check("B6 foreignObject 里的文本不泄漏", out.contains("secret"), false)
        check("B7 foreignObject 之后的元素保留", out.contains("<rect/>"), true)
    }
    check(
        "B8 大写 FOREIGNOBJECT 同样被移除",
        ok("B8", "<svg><FOREIGNOBJECT><div>boom</div></FOREIGNOBJECT></svg>").lowercase().contains("foreignobject"),
        false,
    )
    check(
        "B9 iframe / object / embed 被移除",
        ok("B9", "<svg><iframe/><object/><embed/><rect/></svg>").lowercase().contains("iframe"),
        false,
    )

    // ========================================================== C. attributes
    run {
        val out = ok("C1", "<svg><rect onclick=\"steal()\" onLoad=\"x()\" width=\"10\" height=\"10\"/></svg>")
        check("C1 onclick 被移除", out.contains("onclick"), false)
        check("C2 onLoad（大小写混合）被移除", out.contains("onLoad"), false)
        check("C3 正常属性保留", out.contains("width=\"10\""), true)
    }
    run {
        val out = ok("C4", "<svg><a href=\"javascript:alert(1)\"><text>hi</text></a></svg>")
        check("C4 javascript: href 被移除", out.contains("href"), false)
        check("C5 链接文字保留", out.contains("hi"), true)
    }
    check(
        "C6 实体混淆的 javascript: 也被移除",
        ok("C6", "<svg><rect fill=\"&#106;avascript:alert(1)\"/></svg>").contains("javascript"),
        false,
    )
    check(
        "C7 纯 javascript: 表现属性被移除",
        ok("C7", "<svg><rect fill=\"javascript:alert(1)\"/></svg>").contains("fill"),
        false,
    )
    check(
        "C8 外部 https href 被移除",
        ok("C8", "<svg><use xlink:href=\"https://evil.example/x.svg#a\"/></svg>").contains("href"),
        false,
    )
    check(
        "C9 file: href 被移除",
        ok("C9", "<svg><use href=\"file:///etc/passwd\"/></svg>").contains("href"),
        false,
    )
    check(
        "C10 data: href 被移除",
        ok("C10", "<svg><image href=\"data:image/png;base64,AAAA\"/></svg>").contains("data:"),
        false,
    )
    run {
        val out = ok("C11", "<svg><use xlink:href=\"#ok\"/><use XLINK:HREF=\"#Case\"/></svg>")
        check("C11 内部 xlink:href 保留", out.contains("xlink:href=\"#ok\""), true)
        check("C12 href 大小写不敏感，内部引用保留", out.contains("XLINK:HREF=\"#Case\""), true)
    }
    check(
        "C13 表现属性里的外部 url() 被移除",
        ok("C13", "<svg><rect fill=\"url(https://evil.example/x.svg#g)\"/></svg>").contains("fill"),
        false,
    )
    check(
        "C14 xml:base 被移除（会改写相对 URL 的基准）",
        ok("C14", "<svg xml:base=\"https://evil.example/\"><use href=\"#a\"/></svg>").contains("xml:base"),
        false,
    )

    // ================================================== D. text stays untouched
    check(
        "D1 正文里的 data:/javascript: 不被误删",
        ok("D1", "<svg><text>data: file:///tmp and javascript: are just text</text></svg>")
            .contains("javascript: are just text"),
        true,
    )

    // ======================================================== E. declarations
    run {
        val reason = rejected(
            "E1",
            "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\">&xxe;</svg>",
        )
        check("E1 DOCTYPE/ENTITY 被拒绝", reason.contains("DOCTYPE") || reason.contains("ENTITY"), true)
        check("E2 拒绝原因使用中文", reason.any { it.code in 0x4E00..0x9FFF }, true)
    }
    check(
        "E3 小写 doctype 同样被拒绝",
        rejected("E3", "<!doctype svg><svg/>").isNotBlank(),
        true,
    )
    check(
        "E4 注释里的 DOCTYPE 不是声明，不拒绝",
        ok("E4", "<!-- <!DOCTYPE x> --><svg/>").contains("<svg/>"),
        true,
    )
    check(
        "E5 CDATA 里的 DOCTYPE 不是声明，不拒绝",
        ok("E5", "<svg><![CDATA[<!DOCTYPE x>]]></svg>").contains("<![CDATA[<!DOCTYPE x>]]>"),
        true,
    )
    run {
        val out = ok("E6", "<?xml version=\"1.0\"?><?xml-stylesheet href=\"https://evil.example/x.css\"?><svg/>")
        check("E6 处理指令（可引用外部样式表）被丢弃", out.contains("xml-stylesheet"), false)
        check("E7 丢弃 PI 后仍是合法 SVG", out.contains("<svg/>"), true)
    }

    // ================================================================ F. size
    run {
        val huge = "<svg>" + "a".repeat(PiSvgSanitizer.MAX_BYTES) + "</svg>"
        val reason = rejected("F1", huge)
        check("F1 超过 2 MiB 被拒绝", reason.contains("2 MiB"), true)
    }
    check("F2 空输入被拒绝", rejected("F2", "").isNotBlank(), true)
    check("F3 纯空白输入被拒绝", rejected("F3", "   \n\t").isNotBlank(), true)

    // ============================================================ G. canvas
    check(
        "G1 根 width 超过 8192 被拒绝",
        rejected("G1", "<svg width=\"9000\" height=\"100\"/>").contains("8192"),
        true,
    )
    check(
        "G2 根 height 超过 8192 被拒绝",
        rejected("G2", "<svg width=\"100\" height=\"9000\"/>").contains("8192"),
        true,
    )
    check(
        "G3 viewBox 宽超过 8192 被拒绝",
        rejected("G3", "<svg viewBox=\"0 0 9000 100\"/>").contains("8192"),
        true,
    )
    check(
        "G4 100% 宽高不按绝对值拒绝（Mermaid 的常见输出）",
        ok("G4", "<svg width=\"100%\" height=\"100%\" viewBox=\"0 0 400 200\"/>").contains("100%"),
        true,
    )
    check(
        "G5 8192 边界本身允许",
        ok("G5", "<svg width=\"8192\" height=\"8192\"/>").contains("<svg width=\"8192\""),
        true,
    )
    check(
        "G6 大写 HEIGHT 也受 8192 限制",
        rejected("G6", "<svg width=\"100\" HEIGHT=\"9000\"/>").contains("8192"),
        true,
    )
    check(
        "G7 可换算的 cm 单位参与上限判断",
        rejected("G7", "<svg width=\"300cm\"/>").contains("8192"),
        true,
    )
    check(
        "G8 无穷大的 width 被拒绝",
        rejected("G8", "<svg width=\"1e999\"/>").contains("有限"),
        true,
    )
    check(
        "G9 病态超长 viewBox 被拒绝",
        rejected("G9", "<svg viewBox=\"" + " ".repeat(1100) + "\"/>").contains("viewBox"),
        true,
    )

    // ================================================================ H. case
    run {
        val out = ok(
            "H1",
            "<SVG VIEWBOX=\"0 0 100 100\"><DEFS><LINEARGRADIENT ID=\"G\"/></DEFS>" +
                "<USE XLINK:HREF=\"#G\"/><RECT ONCLICK=\"bad()\" FILL=\"red\"/></SVG>",
        )
        check("H1 大写标签名保留原拼写（AndroidSVG 依赖 linearGradient 的大小写）", out.contains("<LINEARGRADIENT ID=\"G\"/>"), true)
        check("H2 大写 ONCLICK 被移除", out.contains("ONCLICK"), false)
        check("H3 大写 XLINK:HREF 内部引用保留", out.contains("XLINK:HREF=\"#G\""), true)
        check("H4 大写 VIEWBOX 被识别", out.contains("VIEWBOX=\"0 0 100 100\""), true)
        check("H5 大写 SCRIPT 被移除", ok("H5", "<svg><SCRIPT>alert(1)</SCRIPT></svg>").contains("alert"), false)
    }

    // ============================================================== I. limits
    run {
        // root(1) + 63 个 g = 64 层，正好在上限上。
        val atLimit = "<svg>" + "<g>".repeat(63) + "</g>".repeat(63) + "</svg>"
        check("I1 64 层嵌套允许", ok("I1", atLimit).contains("<g>"), true)

        // root(1) + 64 个 g = 65 层。
        val overLimit = "<svg>" + "<g>".repeat(64) + "</g>".repeat(64) + "</svg>"
        check("I2 65 层嵌套被拒绝", rejected("I2", overLimit).contains("64"), true)
    }
    run {
        // root(1) + 19,999 个 rect = 20,000 个节点。
        val atLimit = "<svg>" + "<rect/>".repeat(19_999) + "</svg>"
        check("I3 20,000 节点允许", ok("I3", atLimit).contains("<rect/>"), true)

        // root(1) + 20,000 个 rect = 20,001 个节点。
        val overLimit = "<svg>" + "<rect/>".repeat(20_000) + "</svg>"
        check("I4 20,001 节点被拒绝", rejected("I4", overLimit).contains("20000"), true)
    }

    // ================================================= K. obfuscated dangerous CSS
    run {
        val out = ok(
            "K1",
            "<svg><style>@IMPORT url(\"https://evil.example/a.css\");" +
                "@im\\70 ort url(https://evil.example/b.css);" +
                ".x{fill:url(\\68 ttps://evil.example/c.svg#g)}</style><rect/></svg>",
        )
        check("K1 大写 @IMPORT 被移除", out.contains("@IMPORT"), false)
        check("K2 CSS 转义 @im\\70 ort 被识别", out.contains("@im"), false)
        check("K3 CSS 转义 url(\\68 ttps://…) 被识别", out.contains("evil.example"), false)
    }
    check(
        "K4 style 属性里 CSS 转义的 javascript: 被移除",
        ok("K4", "<svg><rect style=\"fill:url(\\6a avascript:alert(1))\"/></svg>").contains("javascript"),
        false,
    )
    check(
        "K5 换行/实体混淆的 javascript: 被移除",
        ok("K5", "<svg><rect fill=\"java&#x0A;script:alert(1)\"/></svg>").contains("javascript"),
        false,
    )
    check(
        "K6 CDATA 里的危险 CSS 同样被过滤",
        ok("K6", "<svg><style><![CDATA[.a{fill:url(https://evil.example/x.svg#g)}]]></style></svg>")
            .contains("evil.example"),
        false,
    )
    check(
        "K7 style 里的 DOCTYPE 被拒绝",
        rejected("K7", "<svg><style><!DOCTYPE svg></style></svg>").contains("DOCTYPE"),
        true,
    )
    check(
        "K8 foreignObject 嵌套同名元素也整体移除",
        ok("K8", "<svg><foreignObject><foreignObject>deep</foreignObject></foreignObject><rect/></svg>")
            .lowercase()
            .contains("foreignobject"),
        false,
    )

    // ========================================================= J. bad structure
    check("J1 根元素不是 <svg> 被拒绝", rejected("J1", "<html><body/></html>").contains("<svg>"), true)
    check("J2 多个根元素被拒绝", rejected("J2", "<svg/><svg/>").isNotBlank(), true)
    check("J3 标签不匹配被拒绝", rejected("J3", "<svg><g></svg>").isNotBlank(), true)
    check("J4 根元素未闭合被拒绝", rejected("J4", "<svg><g></g>").isNotBlank(), true)
    check("J5 根元素之前的非空文本被拒绝", rejected("J5", "hello<svg/>").isNotBlank(), true)
    check("J6 根元素之前的空白允许", ok("J6", " \n <svg/>").contains("<svg/>"), true)
    check("J7 UTF-8 BOM 被剥掉，不当作根外文本", ok("J7", "\uFEFF<svg/>").contains("<svg/>"), true)

    println(if (failures == 0) "\nharness: OK ($checks checks)" else "\nharness: FAILED ($failures/$checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
