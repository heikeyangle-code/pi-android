package app.pi.ui.render.rich.html

import kotlin.system.exitProcess

// Bare-JVM harness for `PiHtmlPolicy.kt` — the safe bare-HTML subset of
// `§六`/`§十五` in `docs/plans/rich-rendering-upgrade.md`.
//
// The Compose renderer cannot be compiled here (no android.jar, no Compose jar in
// `tools/run-app-pure-checks.sh`), so this file pins the half that can still be
// wrong quietly: which tags survive, which attributes are copied, which URLs are
// rejected, how many times an entity is decoded, and the three resource bounds.
// The real jsoup (1.18.3, the catalog's version) is on the harness classpath, so
// these are statements about the parser the app will actually use.
//
// Every `check` compares the **whole cleaned fragment**. "Contains no
// `onerror=`" style assertions can pass while the sanitiser is emitting a second,
// unexamined copy of the input; exact output makes that impossible.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh
//
// (Registered there as `html-policy`, together with `PiHtmlDocument.kt` so the
// policy/document boundary is compiled in the same closure.)

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

private fun ok(html: String, limits: PiHtmlPolicyLimits = PiHtmlPolicyLimits()): String {
    return when (val result = PiHtmlPolicy.sanitize(html, limits)) {
        is PiHtmlPolicyResult.Ok -> result.cleanedHtml
        is PiHtmlPolicyResult.Rejected -> error("expected Ok, got Rejected(${result.reason}) for: $html")
    }
}

private fun refused(html: String, limits: PiHtmlPolicyLimits = PiHtmlPolicyLimits()): PiHtmlRejectReason {
    return when (val result = PiHtmlPolicy.sanitize(html, limits)) {
        is PiHtmlPolicyResult.Ok -> error("expected Rejected, got Ok(${result.cleanedHtml}) for: $html")
        is PiHtmlPolicyResult.Rejected -> result.reason
    }
}

fun main() {
    // ====================================================== A. allowed structure
    run {
        check(
            "A1 a nested block/inline tree is rebuilt verbatim",
            ok("<div><p>Hello <strong>world</strong> and <em>you</em>.</p></div>"),
            "<div><p>Hello <strong>world</strong> and <em>you</em>.</p></div>",
        )
        check("A2 p", ok("<p>t</p>"), "<p>t</p>")
        check("A3 div", ok("<div>t</div>"), "<div>t</div>")
        check("A4 details/summary", ok("<details><summary>s</summary>t</details>"), "<details><summary>s</summary>t</details>")
        check("A5 blockquote", ok("<blockquote>t</blockquote>"), "<blockquote>t</blockquote>")
        check("A6 ul/li", ok("<ul><li>a</li><li>b</li></ul>"), "<ul><li>a</li><li>b</li></ul>")
        check("A7 ol/li", ok("<ol><li>a</li></ol>"), "<ol><li>a</li></ol>")
        check(
            "A8 table gets the parser's tbody",
            ok("<table><tr><td>t</td></tr></table>"),
            "<table><tbody><tr><td>t</td></tr></tbody></table>",
        )
        check(
            "A9 table sections are preserved",
            ok("<table><thead><tr><th>h</th></tr></thead><tbody><tr><td>d</td></tr></tbody></table>"),
            "<table><thead><tr><th>h</th></tr></thead><tbody><tr><td>d</td></tr></tbody></table>",
        )
        check("A10 pre keeps text", ok("<pre>a\n  b</pre>"), "<pre>a\n  b</pre>")
        check("A11 hr", ok("<hr>"), "<hr>")
        check("A12 figure/figcaption", ok("<figure><figcaption>c</figcaption></figure>"), "<figure><figcaption>c</figcaption></figure>")
        check("A13 strong", ok("<strong>b</strong>"), "<strong>b</strong>")
        check("A14 b", ok("<b>b</b>"), "<b>b</b>")
        check("A15 em", ok("<em>i</em>"), "<em>i</em>")
        check("A16 i", ok("<i>i</i>"), "<i>i</i>")
        check("A17 del", ok("<del>d</del>"), "<del>d</del>")
        check("A18 s", ok("<s>s</s>"), "<s>s</s>")
        check("A19 mark", ok("<mark>m</mark>"), "<mark>m</mark>")
        check("A20 u", ok("<u>u</u>"), "<u>u</u>")
        check("A21 code", ok("<code>c</code>"), "<code>c</code>")
        check("A22 kbd", ok("<kbd>k</kbd>"), "<kbd>k</kbd>")
        check("A23 sup", ok("<sup>2</sup>"), "<sup>2</sup>")
        check("A24 sub", ok("<sub>2</sub>"), "<sub>2</sub>")
        check("A25 span", ok("<span>s</span>"), "<span>s</span>")
        check("A26 br", ok("<p>a<br>b</p>"), "<p>a<br>b</p>")
        check("A27 a without href", ok("<a>label</a>"), "<a>label</a>")
        check("A28 img http", ok("<img src=\"https://x/y.png\">"), "<img src=\"https://x/y.png\">")
        check("A29 empty input is an empty fragment", ok(""), "")
        check("A30 blank text is preserved, not trimmed", ok("  a   b  "), "  a   b  ")
        check("A31 a comment is dropped", ok("<!-- hi --><p>x</p>"), "<p>x</p>")
        check(
            "A32 the empty element set is preserved",
            ok("<span></span><b></b>"),
            "<span></span><b></b>",
        )
        check("A33 text is not trimmed inside a paragraph", ok("<p> a </p>"), "<p> a </p>")
    }

    // ====================================================== B. dangerous tags
    run {
        val dangerous = listOf(
            "script" to "<script>alert(1)</script>",
            "style" to "<style>p{color:red}</style>",
            "iframe" to "<iframe src=\"https://x\">fallback</iframe>",
            "object" to "<object data=\"x\"><p>fallback</p></object>",
            "embed" to "<embed src=\"https://x\">",
            "form" to "<form action=\"https://x\"><p>keep</p></form>",
            "input" to "<input value=\"secret\">",
            "button" to "<button>click</button>",
            "textarea" to "<textarea>secret</textarea>",
            "select" to "<select><option>a</option></select>",
            "option" to "<option>a</option>",
            "link" to "<link rel=\"stylesheet\" href=\"https://x/y.css\">",
            "meta" to "<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\">",
            "base" to "<base href=\"https://evil/\">",
        )
        for ((tag, html) in dangerous) {
            check("B1 <$tag> and its content are dropped", ok(html), "")
        }
        check(
            "B2 text around a dropped textarea survives",
            ok("a<textarea>secret</textarea>b"),
            "ab",
        )
        check(
            "B3 a script between paragraphs leaves both paragraphs",
            ok("<p>a</p><script>alert(1)</script><p>b</p>"),
            "<p>a</p><p>b</p>",
        )
        check(
            "B4 a style block between paragraphs leaves both paragraphs",
            ok("<p>a</p><style>p{}</style><p>b</p>"),
            "<p>a</p><p>b</p>",
        )
        check(
            "B5 a dangerous tag nested in an allowed one is dropped",
            ok("<div><p>a</p><iframe src=\"x\">b</iframe><p>c</p></div>"),
            "<div><p>a</p><p>c</p></div>",
        )
        check("B6 dangerous names are case-insensitive", ok("<SCRIPT>alert(1)</SCRIPT>"), "")
        check(
            "B7 a dangerous tag with event attributes is still dropped",
            ok("<script src=\"https://x\" onerror=\"y\">z</script>"),
            "",
        )
        check(
            "B8 a form drop takes its allowed-looking children with it",
            ok("<form><p>keep-text</p><details><summary>s</summary>d</details></form>"),
            "",
        )
        check(
            "B9 an input is a void element and leaves nothing",
            ok("<p>a</p><input value=\"v\"><p>b</p>"),
            "<p>a</p><p>b</p>",
        )
        check(
            "B10 a button's label is dropped with the button",
            ok("<p>a</p><button>click</button><p>b</p>"),
            "<p>a</p><p>b</p>",
        )
    }

    // ====================================================== C. unknown tags
    run {
        check("C1 an unknown tag is unwrapped, its text survives", ok("<foo>keep</foo>"), "keep")
        check(
            "C2 an unknown tag keeps an allowed child",
            ok("<foo>keep <b>bold</b></foo>"),
            "keep <b>bold</b>",
        )
        check(
            "C3 an unknown tag's attributes are all dropped",
            ok("<foo bar=\"1\" onclick=\"x\">t</foo>"),
            "t",
        )
        check(
            "C4 an svg wrapper is unwrapped and its script dropped",
            ok("<svg onload=\"x\"><script>alert(1)</script><text>safe</text></svg>"),
            "safe",
        )
        check(
            "C5 a math wrapper is unwrapped and its script dropped",
            ok("<math><mtext><script>alert(1)</script>safe</mtext></math>"),
            "safe",
        )
        check(
            "C6 template content is re-examined, not trusted",
            ok("<template><b>x</b><script>y</script></template>"),
            "<b>x</b>",
        )
        check("C7 noscript text is kept as text", ok("<noscript>fallback</noscript>"), "fallback")
        check(
            "C8 xmp content is escaped text, never markup",
            ok("<xmp><script>alert(1)</script></xmp>"),
            "&lt;script&gt;alert(1)&lt;/script&gt;",
        )
        check(
            "C9 plaintext content is escaped text, never markup",
            ok("<plaintext><script>alert(1)</script>"),
            "&lt;script&gt;alert(1)&lt;/script&gt;",
        )
        check(
            "C10 a noscript image with a relative src is dropped",
            ok("<noscript><img src=\"x\" onerror=\"y\"></noscript>"),
            "",
        )
        check(
            "C11 an unknown wrapper between allowed blocks disappears",
            ok("<div>a</div><foo><p>b</p></foo><div>c</div>"),
            "<div>a</div><p>b</p><div>c</div>",
        )
    }

    // ====================================================== D. attributes
    run {
        check(
            "D1 a keeps href and title",
            ok("<a href=\"https://x?a=1&amp;b=2\" title=\"T &amp; U\">x</a>"),
            "<a href=\"https://x?a=1&amp;b=2\" title=\"T &amp; U\">x</a>",
        )
        check(
            "D2 a drops onclick/style/class/id",
            ok("<a href=\"https://x\" onclick=\"x\" style=\"y\" class=\"c\" id=\"i\">x</a>"),
            "<a href=\"https://x\">x</a>",
        )
        check(
            "D3 attribute names are case-insensitive",
            ok("<A HREF=\"https://x\" TITLE=\"t\" ONCLICK=\"y\">x</A>"),
            "<a href=\"https://x\" title=\"t\">x</a>",
        )
        check(
            "D4 details keeps a boolean open",
            ok("<details open><summary>s</summary>d</details>"),
            "<details open><summary>s</summary>d</details>",
        )
        check(
            "D5 open=\"false\" is still a boolean attribute",
            ok("<details open=\"false\"><summary>s</summary>d</details>"),
            "<details open><summary>s</summary>d</details>",
        )
        check(
            "D6 details without open stays closed",
            ok("<details><summary>s</summary>d</details>"),
            "<details><summary>s</summary>d</details>",
        )
        check(
            "D7 details drops ontoggle",
            ok("<details open ontoggle=\"x\"><summary>s</summary>d</details>"),
            "<details open><summary>s</summary>d</details>",
        )
        check(
            "D8 ol keeps a negative start",
            ok("<ol start=\"-3\"><li>a</li></ol>"),
            "<ol start=\"-3\"><li>a</li></ol>",
        )
        check("D9 ol drops a leading plus", ok("<ol start=\"+3\"><li>a</li></ol>"), "<ol><li>a</li></ol>")
        check("D10 ol drops a non-number", ok("<ol start=\"abc\"><li>a</li></ol>"), "<ol><li>a</li></ol>")
        check("D11 ol drops an empty start", ok("<ol start=\"\"><li>a</li></ol>"), "<ol><li>a</li></ol>")
        check(
            "D12 td keeps colspan and rowspan",
            ok("<table><tr><td colspan=\"2\" rowspan=\"3\">x</td></tr></table>"),
            "<table><tbody><tr><td colspan=\"2\" rowspan=\"3\">x</td></tr></tbody></table>",
        )
        check(
            "D13 th keeps colspan and rowspan",
            ok("<table><tr><th colspan=\"2\" rowspan=\"3\">x</th></tr></table>"),
            "<table><tbody><tr><th colspan=\"2\" rowspan=\"3\">x</th></tr></tbody></table>",
        )
        check(
            "D14 colspan 0 is dropped",
            ok("<table><tr><td colspan=\"0\">x</td></tr></table>"),
            "<table><tbody><tr><td>x</td></tr></tbody></table>",
        )
        check(
            "D15 colspan above the cap is dropped",
            ok("<table><tr><td colspan=\"1001\">x</td></tr></table>"),
            "<table><tbody><tr><td>x</td></tr></tbody></table>",
        )
        check(
            "D16 colspan at the cap is kept",
            ok("<table><tr><td colspan=\"1000\">x</td></tr></table>"),
            "<table><tbody><tr><td colspan=\"1000\">x</td></tr></tbody></table>",
        )
        check(
            "D17 colspan non-numeric is dropped",
            ok("<table><tr><td colspan=\"wide\">x</td></tr></table>"),
            "<table><tbody><tr><td>x</td></tr></tbody></table>",
        )
        check(
            "D18 img keeps only src",
            ok("<img src=\"https://x/y.png\" alt=\"a\" style=\"s\" onerror=\"x\">"),
            "<img src=\"https://x/y.png\">",
        )
        check(
            "D19 a drops target/rel/download",
            ok("<a href=\"https://x\" target=\"_blank\" rel=\"noopener\" download=\"f\">x</a>"),
            "<a href=\"https://x\">x</a>",
        )
        check(
            "D20 blockquote drops cite",
            ok("<blockquote cite=\"https://x\"><p>q</p></blockquote>"),
            "<blockquote><p>q</p></blockquote>",
        )
        check(
            "D21 a src is not an a attribute",
            ok("<a src=\"https://x\">y</a>"),
            "<a>y</a>",
        )
        check(
            "D22 a duplicate href does not overwrite the safe one",
            ok("<a href=\"https://ok\" href=\"javascript:bad\">x</a>"),
            "<a href=\"https://ok\">x</a>",
        )
        check(
            "D23 title entity is decoded once and re-escaped once",
            ok("<a href=\"https://x\" title=\"a&amp;b\">x</a>"),
            "<a href=\"https://x\" title=\"a&amp;b\">x</a>",
        )
        check(
            "D24 an invalid href keeps the element, text and title",
            ok("<a href=\"javascript:alert(1)\" title=\"t\">x</a>"),
            "<a title=\"t\">x</a>",
        )
        check(
            "D25 supported attributes is the complete vocabulary",
            PiHtmlPolicy.isAllowedAttribute("a", "href") &&
                PiHtmlPolicy.isAllowedAttribute("a", "title") &&
                PiHtmlPolicy.isAllowedAttribute("details", "open") &&
                PiHtmlPolicy.isAllowedAttribute("ol", "start") &&
                PiHtmlPolicy.isAllowedAttribute("td", "colspan") &&
                PiHtmlPolicy.isAllowedAttribute("td", "rowspan") &&
                PiHtmlPolicy.isAllowedAttribute("th", "colspan") &&
                PiHtmlPolicy.isAllowedAttribute("th", "rowspan") &&
                PiHtmlPolicy.isAllowedAttribute("img", "src"),
            true,
        )
        check(
            "D26 style/class/id/on* are not in any vocabulary",
            PiHtmlPolicy.isAllowedAttribute("a", "onclick") ||
                PiHtmlPolicy.isAllowedAttribute("div", "style") ||
                PiHtmlPolicy.isAllowedAttribute("div", "class") ||
                PiHtmlPolicy.isAllowedAttribute("div", "id") ||
                PiHtmlPolicy.isAllowedAttribute("img", "alt") ||
                PiHtmlPolicy.isAllowedAttribute("img", "onerror") ||
                PiHtmlPolicy.isAllowedAttribute("td", "background"),
            false,
        )
        check(
            "D27 tag helpers agree with the sets",
            PiHtmlPolicy.isAllowedTag("a") &&
                PiHtmlPolicy.isAllowedTag("DIV") &&
                !PiHtmlPolicy.isAllowedTag("script") &&
                PiHtmlPolicy.isDangerousTag("SCRIPT") &&
                !PiHtmlPolicy.isDangerousTag("foo"),
            true,
        )
        check(
            "D28 every dangerous tag is outside the allowed set",
            PiHtmlPolicy.DANGEROUS_TAGS.any { it in PiHtmlPolicy.ALLOWED_TAGS },
            false,
        )
        check("D29 the block vocabulary is complete", PiHtmlPolicy.BLOCK_TAGS.size, 18)
        check("D30 the inline vocabulary is complete", PiHtmlPolicy.INLINE_TAGS.size, 16)
        check("D31 the dangerous vocabulary is complete", PiHtmlPolicy.DANGEROUS_TAGS.size, 14)
    }

    // ====================================================== E. URLs
    run {
        val allowed = listOf(
            "http://example.com/a",
            "https://example.com/a?b=1&amp;c=2",
            "HTTPS://EXAMPLE.COM/X",
            "mailto:someone@example.com",
            "mailto:someone@example.com?subject=hi",
        )
        for (url in allowed) {
            check("E1 an allowed link survives: $url", ok("<a href=\"$url\">x</a>"), "<a href=\"$url\">x</a>")
        }

        val rejected = listOf(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "&#106;avascript:alert(1)",
            "java&#x09;script:alert(1)",
            "java&#x0A;script:alert(1)",
            "java&#x0D;script:alert(1)",
            "java&amp;#x09;script:alert(1)",
            "javascript&colon;alert(1)",
            "javascript&#58;alert(1)",
            "java script:alert(1)",
            "java\tscript:alert(1)",
            "java\nscript:alert(1)",
            "\u0000javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "file://C:/Windows/win.ini",
            "vbscript:msgbox(1)",
            "blob:https://example.com/x",
            "about:blank",
            "foo/bar",
            "/etc/passwd",
            "./x",
            "../x",
            "#anchor",
            "?q=1",
            "//evil.example/x",
            "\\\\evil.example\\x",
            "https:",
            "mailto:",
            "",
            "   ",
        )
        for (url in rejected) {
            check("E2 a refused link becomes plain text: $url", ok("<a href=\"$url\">x</a>"), "<a>x</a>")
        }

        check(
            "E3 an image accepts http",
            ok("<img src=\"http://x/y.png\">"),
            "<img src=\"http://x/y.png\">",
        )
        check(
            "E4 an image refuses mailto",
            ok("<img src=\"mailto:a@b\">"),
            "",
        )
        check(
            "E5 an image refuses data",
            ok("<img src=\"data:image/png;base64,AAAA\">"),
            "",
        )
        check(
            "E6 an image refuses a relative path",
            ok("<img src=\"images/x.png\">"),
            "",
        )
        check(
            "E7 an image refuses a protocol-relative host",
            ok("<img src=\"//evil.example/x.png\">"),
            "",
        )
        check(
            "E8 an image with no src is dropped",
            ok("<img alt=\"x\">"),
            "",
        )
        check(
            "E9 uppercase IMG/SRC is recognized",
            ok("<IMG SRC=\"https://x/y.png\" ALT=\"a\">"),
            "<img src=\"https://x/y.png\">",
        )
        check(
            "E10 an accepted URL keeps its query and re-escapes &",
            ok("<a href=\"https://x?a=1&b=2\">x</a>"),
            "<a href=\"https://x?a=1&amp;b=2\">x</a>",
        )
        check(
            "E11 controls are stripped from an accepted URL",
            ok("<a href=\"https://x/a&#x09;b\">x</a>"),
            "<a href=\"https://x/ab\">x</a>",
        )
        check(
            "E12 surrounding spaces are trimmed from an accepted URL",
            ok("<a href=\"  https://x  \">x</a>"),
            "<a href=\"https://x\">x</a>",
        )
        check(
            "E13 a mailto link is not an image",
            PiHtmlPolicy.sanitizeUrl("mailto:a@b", allowMailto = false),
            null,
        )
        check("E14 scheme helper accepts https", PiHtmlPolicy.sanitizeUrl("https://x"), "https://x")
        check("E15 scheme helper rejects javascript", PiHtmlPolicy.sanitizeUrl("javascript:x"), null)
        check("E16 scheme helper rejects a fragment", PiHtmlPolicy.sanitizeUrl("#x"), null)
        check("E17 scheme helper rejects protocol-relative", PiHtmlPolicy.sanitizeUrl("//x"), null)
        check("E18 scheme helper rejects an empty scheme", PiHtmlPolicy.sanitizeUrl("https:"), null)
        check("E19 scheme helper decodes nested numeric entities", PiHtmlPolicy.sanitizeUrl("java&amp;#x6a;ascript:x"), null)
        check("E20 scheme helper decodes nested named entities", PiHtmlPolicy.sanitizeUrl("java&amp;Tab;script:x"), null)
        check("E21 scheme helper decodes a numeric colon", PiHtmlPolicy.sanitizeUrl("https&#58;//x"), "https&#58;//x")
        check("E22 scheme helper trims spaces", PiHtmlPolicy.sanitizeUrl("  https://x  "), "https://x")
        check("E23 scheme helper rejects a NUL-prefixed scheme", PiHtmlPolicy.sanitizeUrl("\u0000javascript:x"), null)
        check("E24 scheme helper rejects an empty value", PiHtmlPolicy.sanitizeUrl(""), null)
        check("E25 a relative image source is not copied", PiHtmlPolicy.sanitizeUrl("x.png", allowMailto = false), null)
        check(
            "E26 a bad href never leaves a javascript attribute behind",
            ok("<a href=\"javascript:alert(1)\">x</a>").contains("javascript:"),
            false,
        )
        check(
            "E27 a no-break space is trimmed from an accepted URL",
            PiHtmlPolicy.sanitizeUrl("\u00A0https://x\u00A0"),
            "https://x",
        )
        check(
            "E28 a no-break space cannot hide a bad scheme",
            ok("<a href=\"\u00A0javascript:alert(1)\">x</a>"),
            "<a>x</a>",
        )
    }

    // ====================================================== F. entities
    run {
        check(
            "F1 an escaped tag stays text",
            ok("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>"),
            "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>",
        )
        check(
            "F2 a double-escaped tag is decoded once, not twice",
            ok("<p>&amp;lt;b&amp;gt;</p>"),
            "<p>&amp;lt;b&amp;gt;</p>",
        )
        check("F3 decimal entities become text", ok("<p>&#60;b&#62;</p>"), "<p>&lt;b&gt;</p>")
        check("F4 hex entities become text", ok("<p>&#x3C;b&#x3E;</p>"), "<p>&lt;b&gt;</p>")
        check("F5 an entity decodes to a letter, not a tag", ok("<p>&#106;avascript:</p>"), "<p>javascript:</p>")
        check("F6 &amp;amp; decodes once", ok("<p>&amp;amp;</p>"), "<p>&amp;amp;</p>")
        check("F7 &amp; decodes once", ok("<p>a &amp; b</p>"), "<p>a &amp; b</p>")
        check("F8 nbsp is preserved", ok("<p>a&nbsp;b</p>"), "<p>a&nbsp;b</p>")
        check("F9 a quote in text is not escaped", ok("<p>&quot;q&quot;</p>"), "<p>\"q\"</p>")
        check("F10 a raw less-than is escaped in text", ok("<p>1 < 2</p>"), "<p>1 &lt; 2</p>")
        check(
            "F11 an entity in href decodes once and re-escapes once",
            ok("<a href=\"https://x?a=1&amp;b=2\">x</a>"),
            "<a href=\"https://x?a=1&amp;b=2\">x</a>",
        )
        check(
            "F12 an entity-encoded scheme is refused",
            ok("<a href=\"&#106;avascript:alert(1)\">x</a>"),
            "<a>x</a>",
        )
        check(
            "F13 an entity-encoded tag inside text cannot become markup",
            ok("<p>&lt;img src=x onerror=y&gt;</p>"),
            "<p>&lt;img src=x onerror=y&gt;</p>",
        )
        check(
            "F14 whitespace text nodes keep newlines",
            ok("<div>\n  a\n  b\n</div>"),
            "<div>\n  a\n  b\n</div>",
        )
    }

    // ====================================================== G. nesting and depth
    run {
        val nested64 = "<div>".repeat(64) + "x" + "</div>".repeat(64)
        check("G1 64 nested divs are allowed", ok(nested64).isNotEmpty(), true)
        check("G2 65 nested divs are refused", refused("<div>".repeat(65) + "x" + "</div>".repeat(65)), PiHtmlRejectReason.TooDeep)
        check(
            "G3 unknown wrappers count toward depth",
            refused("<foo>".repeat(65) + "x" + "</foo>".repeat(65)),
            PiHtmlRejectReason.TooDeep,
        )
        check(
            "G4 64 unknown wrappers are allowed",
            ok("<foo>".repeat(64) + "x" + "</foo>".repeat(64)),
            "x",
        )
        check(
            "G5 a depth-2 chain is allowed at maxDepth 2",
            ok("<div><p>x</p></div>", PiHtmlPolicyLimits(maxDepth = 2)),
            "<div><p>x</p></div>",
        )
        check(
            "G6 a depth-3 chain is refused at maxDepth 2",
            refused("<div><div><p>x</p></div></div>", PiHtmlPolicyLimits(maxDepth = 2)),
            PiHtmlRejectReason.TooDeep,
        )
        check(
            "G7 an unknown wrapper does not hide depth",
            refused("<foo><bar><p>x</p></bar></foo>", PiHtmlPolicyLimits(maxDepth = 2)),
            PiHtmlRejectReason.TooDeep,
        )
        check(
            "G8 deep inline nesting is bounded too",
            refused("<b>".repeat(65) + "x" + "</b>".repeat(65)),
            PiHtmlRejectReason.TooDeep,
        )
        check(
            "G9 the bound is inclusive at depth 1",
            ok("<p>x</p>", PiHtmlPolicyLimits(maxDepth = 1)),
            "<p>x</p>",
        )
    }

    // ====================================================== H. resource limits
    run {
        check("H1 the default limits are the plan's numbers", PiHtmlPolicy.LIMITS.maxChars, 256 * 1024)
        check("H2 the default depth is 64", PiHtmlPolicy.LIMITS.maxDepth, 64)
        check("H3 the default node bound is 20,000", PiHtmlPolicy.LIMITS.maxNodes, 20_000)

        val atLimit = "a".repeat(PiHtmlPolicy.LIMITS.maxChars)
        check("H4 exactly maxChars is allowed", ok(atLimit).length, PiHtmlPolicy.LIMITS.maxChars)
        check(
            "H5 one char over maxChars is refused",
            refused(atLimit + "a"),
            PiHtmlRejectReason.TooLarge,
        )
        check(
            "H6 a small maxChars refuses earlier",
            refused("<p>abcdef</p>", PiHtmlPolicyLimits(maxChars = 5)),
            PiHtmlRejectReason.TooLarge,
        )
        check(
            "H7 six nodes are refused at maxNodes 5",
            refused("<p>a</p><p>b</p><p>c</p>", PiHtmlPolicyLimits(maxNodes = 5)),
            PiHtmlRejectReason.TooManyNodes,
        )
        check(
            "H8 six nodes are allowed at maxNodes 6",
            ok("<p>a</p><p>b</p><p>c</p>", PiHtmlPolicyLimits(maxNodes = 6)),
            "<p>a</p><p>b</p><p>c</p>",
        )
        check(
            "H9 an empty element is one node at maxNodes 1",
            ok("<p></p>", PiHtmlPolicyLimits(maxNodes = 1)),
            "<p></p>",
        )
        check(
            "H10 a dropped dangerous subtree does not spend the node budget",
            ok("<script>1234567890</script><p>y</p>", PiHtmlPolicyLimits(maxNodes = 2)),
            "<p>y</p>",
        )
        check(
            "H11 a dropped dangerous subtree does not spend the depth budget",
            ok("<script>".repeat(100) + "x" + "</script>".repeat(100), PiHtmlPolicyLimits(maxDepth = 2)),
            "",
        )
        check(
            "H12 maxChars must be positive",
            runCatching { PiHtmlPolicyLimits(maxChars = 0) }.exceptionOrNull() is IllegalArgumentException,
            true,
        )
        check(
            "H13 maxDepth must be positive",
            runCatching { PiHtmlPolicyLimits(maxDepth = 0) }.exceptionOrNull() is IllegalArgumentException,
            true,
        )
        check(
            "H14 maxNodes must be positive",
            runCatching { PiHtmlPolicyLimits(maxNodes = 0) }.exceptionOrNull() is IllegalArgumentException,
            true,
        )
        check(
            "H15 hostile input never throws",
            runCatching { PiHtmlPolicy.sanitize("<div>" + "<script>".repeat(1000) + "x") }.isSuccess,
            true,
        )
    }

    // ====================================================== I. details/summary
    run {
        check(
            "I1 an open details keeps open",
            ok("<details open><summary>点击查看详细解释</summary><p>补充说明。</p></details>"),
            "<details open><summary>点击查看详细解释</summary><p>补充说明。</p></details>",
        )
        check(
            "I2 nested details are independent",
            ok("<details><summary>a</summary><details open><summary>b</summary><p>c</p></details></details>"),
            "<details><summary>a</summary><details open><summary>b</summary><p>c</p></details></details>",
        )
        check(
            "I3 summary is allowed without details",
            ok("<summary>s</summary>"),
            "<summary>s</summary>",
        )
        check(
            "I4 details keeps open but drops every other attribute",
            ok("<details open name=\"n\" ontoggle=\"x\" class=\"c\" style=\"s\"><summary>s</summary></details>"),
            "<details open><summary>s</summary></details>",
        )
        check(
            "I5 summary attributes are all dropped",
            ok("<summary onclick=\"x\" class=\"c\">s</summary>"),
            "<summary>s</summary>",
        )
    }

    // ====================================================== J. safety vectors
    run {
        check(
            "J1 img onerror cannot ride along",
            ok("<img src=\"https://x/y.png\" onerror=\"alert(1)\">"),
            "<img src=\"https://x/y.png\">",
        )
        check(
            "J2 a div style expression is dropped",
            ok("<div style=\"background:url(javascript:alert(1))\">x</div>"),
            "<div>x</div>",
        )
        check(
            "J3 a style block is dropped whole",
            ok("<style>@import url(https://evil/x.css);</style><p>x</p>"),
            "<p>x</p>",
        )
        check(
            "J4 an iframe srcdoc cannot become a document",
            ok("<iframe srcdoc=\"&lt;script&gt;alert(1)&lt;/script&gt;\"></iframe>"),
            "",
        )
        check(
            "J5 an object fallback is dropped with the object",
            ok("<object data=\"javascript:alert(1)\"><p>fallback</p></object>"),
            "",
        )
        check(
            "J6 an embed is dropped",
            ok("<embed src=\"https://x\">"),
            "",
        )
        check(
            "J7 a base tag cannot retarget links",
            ok("<base href=\"https://evil/\"><a href=\"https://ok/\">x</a>"),
            "<a href=\"https://ok/\">x</a>",
        )
        check(
            "J8 a meta refresh is dropped",
            ok("<meta http-equiv=\"refresh\" content=\"0;url=javascript:alert(1)\"><p>x</p>"),
            "<p>x</p>",
        )
        check(
            "J9 a comment containing a script is dropped, not rendered",
            ok("<!-- <script>alert(1)</script> --><p>x</p>"),
            "<p>x</p>",
        )
        check(
            "J10 a body with onload is unwrapped and the handler dropped",
            ok("<body onload=\"alert(1)\">hi</body>"),
            "hi",
        )
        check(
            "J11 an image cannot use javascript:",
            ok("<img src=\"javascript:alert(1)\">"),
            "",
        )
        check(
            "J12 an image cannot use file:",
            ok("<img src=\"file:///etc/passwd\">"),
            "",
        )
        check(
            "J13 an svg onload is dropped with the wrapper",
            ok("<svg onload=\"alert(1)\"><text>t</text></svg>"),
            "t",
        )
        check(
            "J14 an onclick on an allowed inline tag is dropped",
            ok("<span onclick=\"alert(1)\">t</span>"),
            "<span>t</span>",
        )
        check(
            "J15 a table cell background is dropped",
            ok("<table><tr><td background=\"javascript:alert(1)\">x</td></tr></table>"),
            "<table><tbody><tr><td>x</td></tr></tbody></table>",
        )
        check(
            "J16 a formaction on a dropped button cannot survive",
            ok("<button formaction=\"javascript:alert(1)\">x</button>"),
            "",
        )
        check(
            "J17 a title attribute is not URL-validated but stays text",
            ok("<a href=\"https://x\" title=\"javascript:alert(1)\">x</a>"),
            "<a href=\"https://x\" title=\"javascript:alert(1)\">x</a>",
        )
    }

    // ====================================================== K. round trips
    run {
        val sample = """
            <div class="card" id="x" style="color:red">
              <h1 onclick="x">not allowed</h1>
              <p>Hello <strong>world</strong> &amp; <em>friends</em>.</p>
              <details open ontoggle="x"><summary>More</summary><p>Inside.</p></details>
              <ul><li>a</li><li>b</li></ul>
              <table><tr><th colspan="2">h</th></tr><tr><td>1</td><td>2</td></tr></table>
              <a href="https://example.com?a=1&amp;b=2" title="t" onclick="x">link</a>
              <img src="https://example.com/i.png" onerror="x">
              <script>alert(1)</script>
              <foo>kept text</foo>
            </div>
        """.trimIndent()
        val once = ok(sample)
        check(
            "K1 the sanitizer is idempotent",
            ok(once),
            once,
        )
        check("K2 no script survives", once.contains("<script"), false)
        check("K3 no event handler survives", once.contains("onclick=") || once.contains("ontoggle="), false)
        check("K4 no style attribute survives", once.contains("style="), false)
        check("K5 no class/id survives", once.contains("class=") || once.contains(" id="), false)
        check("K6 the unknown wrapper's text survives", once.contains("kept text"), true)
        check("K7 the allowed link survives", once.contains("href=\"https://example.com?a=1&amp;b=2\""), true)
        check("K8 the allowed image survives", once.contains("src=\"https://example.com/i.png\""), true)
        check("K9 the details stays open", once.contains("<details open>"), true)
        check("K10 the table columns stay", once.contains("<th colspan=\"2\">h</th>"), true)
        check("K11 a second sanitize is a fixed point", ok(ok(sample)), once)
        check(
            "K12 text content is preserved across the round trip",
            once.contains("Hello <strong>world</strong> &amp; <em>friends</em>."),
            true,
        )
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) exitProcess(1)
}
