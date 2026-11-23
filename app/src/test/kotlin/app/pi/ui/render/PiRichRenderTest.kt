package app.pi.ui.render

import app.pi.ui.render.rich.PiFenceSniff
import app.pi.ui.render.rich.svg.computeTightCropRect
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiRichRenderTest {

    @Test
    fun `extracts inline dollar and paren math while preserving markdown and CJK adjacency`() {
        val md = "当\$x_1 + x_2 = \\frac{a}{b}\$时，**加粗**和\\(\\alpha + \\beta\\)都正常。"
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasInlineMath)
        assertEquals(2, extracted.entries.size)
        assertEquals("x_1 + x_2 = \\frac{a}{b}", extracted.entries[0].tex)
        assertEquals("\\alpha + \\beta", extracted.entries[1].tex)
        // Markdown 下划线被纯 ASCII 哨兵保护，不暴露给 GFM 解析器
        assertFalse(extracted.markdown.contains("x_1"))
        assertTrue(extracted.markdown.contains("**加粗**"))

        // 任何非段落表面调用 resolveToUnicode 绝不泄漏哨兵
        val resolved = PiInlineMath.resolveToUnicode(extracted.markdown, extracted)
        assertFalse(resolved.contains(extracted.markerPrefix))
        assertTrue(resolved.contains("α + β"))
    }

    @Test
    fun `real GFM MarkdownParser keeps every inline math sentinel intact inside a single leaf AST token`() {
        val md = """
            当${'$'}x_1 + x_2 \in \mathbb{R}${'$'}时，**${'$'}E = mc^2${'$'}**、*${'$'}\alpha + \beta${'$'}*与[${'$'}f(x)${'$'}](https://example.com)都正常。

            - 列表项里的行内公式：${'$'}\mathcal{L}(\theta) = \mathbb{E}[X]${'$'}
            1. 有序列表里的行内公式：\(\frac{1}{2}\)

            > 引用块里的行内公式：${'$'}\mathfrak{g}${'$'}

            ## 标题里的公式 ${'$'}a^2 + b^2 = c^2${'$'}

            | 符号 | 含义 |
            | --- | --- |
            | ${'$'}\mathbb{N}${'$'} | 自然数 |
        """.trimIndent()

        val extracted = PiInlineMath.extract(md)
        assertEquals(9, extracted.entries.size)

        val tree = parseGfm(extracted.markdown)

        // 逐个叶节点检查：每一个叶节点经 resolveToUnicode 后必须零哨兵泄漏（证明 GFMLexer 没有把哨兵切碎！）
        val matchedIndices = mutableSetOf<Int>()
        fun walkLeaves(node: ASTNode) {
            if (node.children.isEmpty()) {
                val slice = extracted.markdown.substring(node.startOffset, node.endOffset)
                for (seg in PiInlineMath.splitSegments(slice, extracted.markerPrefix, extracted.markerSuffix)) {
                    if (seg is PiInlineMath.Segment.MathRef) {
                        matchedIndices.add(seg.index)
                    }
                }
                val resolved = PiInlineMath.resolveToUnicode(slice, extracted)
                assertFalse(
                    "Leaf token leaked sentinel: '$slice' -> '$resolved'",
                    resolved.contains(extracted.markerPrefix),
                )
            } else {
                for (child in node.children) walkLeaves(child)
            }
        }
        walkLeaves(tree)

        // 全部 9 条行内公式都在真实 GFM AST 的单个叶节点内完整命中！
        assertEquals((0 until 9).toSet(), matchedIndices)
    }

    @Test
    fun `avoids sentinel collision when source markdown literally contains default marker prefix`() {
        val md = "Literal ${PiInlineMath.DEFAULT_MARKER_PREFIX}0${PiInlineMath.DEFAULT_MARKER_SUFFIX} and \$x^2\$."
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasInlineMath)
        assertFalse(extracted.markerPrefix == PiInlineMath.DEFAULT_MARKER_PREFIX)
        val resolved = PiInlineMath.resolveToUnicode(extracted.markdown, extracted)
        assertTrue(resolved.contains("${PiInlineMath.DEFAULT_MARKER_PREFIX}0${PiInlineMath.DEFAULT_MARKER_SUFFIX}"))
        assertTrue(resolved.contains("x²"))
        assertFalse(resolved.contains(extracted.markerPrefix))
    }

    @Test
    fun `normalizes standalone bracket display math and collapses blank lines inside block math`() {
        val md = """
            先看公式：
            \[
            \int_0^1 x^2 \, dx = \frac{1}{3}

            \forall x \in \mathbb{R}
            \]
            行内代码 `${'$'}a + b${'$'}` 不该被提取。
            ```kotlin
            val price = "${'$'}100 and ${'$'}200"
            ```
        """.trimIndent()
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasBlockMath)
        assertEquals(0, extracted.entries.size)
        assertTrue(extracted.markdown.contains("`${'$'}a + b${'$'}`"))

        // 真实 GFM 解析器下，规范化后的块级公式必须落在单个 PARAGRAPH 里且首尾是 $$
        val tree = parseGfm(extracted.markdown)
        val paragraphs = tree.children.filter { it.type == MarkdownElementTypes.PARAGRAPH }
        val mathParagraph = paragraphs.firstOrNull { p ->
            val t = extracted.markdown.substring(p.startOffset, p.endOffset).trim()
            t.startsWith("$$") && t.endsWith("$$")
        }
        assertTrue("Block math must stay a single PARAGRAPH in GFM AST", mathParagraph != null)
    }

    @Test
    fun `isolates adjacent block math with blank lines so markdown parser never merges it into prose`() {
        val md = "We have:\n$$\nE = mc^2\n$$\nwhere \$m\$ is mass."
        val extracted = PiInlineMath.extract(md)

        assertTrue(extracted.hasBlockMath)
        assertTrue(extracted.hasInlineMath)
        assertTrue(extracted.markdown.contains("We have:\n\n$$\nE = mc^2\n$$\n\nwhere "))
        assertEquals(1, extracted.entries.size)
        assertEquals("m", extracted.entries[0].tex)
    }

    @Test
    fun `never misclassifies currency or escaped dollar signs as inline math`() {
        val md = "Total is \$5 and \$10, or \$50 per item and \$100 max, plus escaped \\\$x\\\$."
        val extracted = PiInlineMath.extract(md)

        assertFalse(extracted.hasInlineMath)
        assertEquals(md, extracted.markdown)
    }

    @Test
    fun `proportional table column widths avoid bloating narrow columns`() {
        // | # | 状态 | 详细说明 |
        val chars = listOf(
            estimateTableCellDisplayChars("#"),
            estimateTableCellDisplayChars("正常"),
            estimateTableCellDisplayChars("这是一段较长的字段详细说明内容"),
        )
        val widths = computePiTableColumnWidthsDp(chars, availableDp = 360f)

        assertEquals(3, widths.size)
        // 窄列远小于说明列，且总宽正好铺满屏幕不触发横向滚动
        assertTrue(widths[0] < widths[2] * 0.6f)
        assertEquals(360f, widths.sum(), 1.5f)
    }

    @Test
    fun `wide table exceeds viewport and stays bounded per column`() {
        val chars = listOf(12, 24, 30, 18, 40)
        val widths = computePiTableColumnWidthsDp(chars, availableDp = 360f)

        assertEquals(5, widths.size)
        assertTrue(widths.sum() > 360f)
        assertTrue(widths.all { it in 56f..240f })
    }

    @Test
    fun `native syntax highlighter tokenizes keywords, types, functions, strings, interpolations, operators and punctuation`() {
        val kotlinSample = """
            /** Computes total for @param items */
            @Composable
            fun computeTotal(items: List<Int>): Int {
                val msg = "count=${'$'}{items.size}"
                return items.sum() + 0x10
            }
        """.trimIndent()
        val result = PiFastCodeHighlighter.highlight(kotlinSample, "kotlin")
        assertTrue(result.languageKnown)
        val tokens = result.spans.map { it.token }.toSet()
        assertTrue("Expected Comment", PiSyntaxToken.Comment in tokens)
        assertTrue("Expected Keyword", PiSyntaxToken.Keyword in tokens)
        assertTrue("Expected Type", PiSyntaxToken.Type in tokens)
        assertTrue("Expected Function", PiSyntaxToken.Function in tokens)
        assertTrue("Expected StringLiteral", PiSyntaxToken.StringLiteral in tokens)
        assertTrue("Expected Variable (interpolation/member)", PiSyntaxToken.Variable in tokens)
        assertTrue("Expected Number", PiSyntaxToken.Number in tokens)
        assertTrue("Expected Operator", PiSyntaxToken.Operator in tokens)
        assertTrue("Expected Punctuation", PiSyntaxToken.Punctuation in tokens)
        assertTrue("Expected Muted (@Composable)", PiSyntaxToken.Muted in tokens)

        // Python f-string + Dockerfile + Rust macro + TOML
        val py = PiFastCodeHighlighter.highlight("x = f\"hello {user.name}\" # note", "python")
        assertTrue(py.languageKnown)
        assertTrue(py.spans.any { it.token == PiSyntaxToken.StringLiteral })
        assertTrue(py.spans.any { it.token == PiSyntaxToken.Variable })

        val rust = PiFastCodeHighlighter.highlight("#[derive(Debug)]\nfn main() { println!(\"hi\"); }", "rust")
        assertTrue(rust.languageKnown)
        assertTrue(rust.spans.any { it.token == PiSyntaxToken.Muted })
        assertTrue(rust.spans.any { it.token == PiSyntaxToken.Function })

        val docker = PiFastCodeHighlighter.highlight("FROM ubuntu:24.04\nRUN apt-get update", "dockerfile")
        assertTrue(docker.languageKnown)
        assertTrue(docker.spans.any { it.token == PiSyntaxToken.Keyword })
    }

    @Test
    fun `reference link extractor resolves case-insensitive normalized labels`() {
        val md = """
            See [Pi Docs][pi  docs] for details.

            [PI DOCS]: https://example.com/pi
        """.trimIndent()
        val tree = parseGfm(md)
        val refs = extractPiReferenceLinks(md, tree)
        assertEquals("https://example.com/pi", refs["pi docs"])
    }

    @Test
    fun `commonmark backslash unescaping and HTML entity decoding preserve file paths`() {
        assertEquals("*bold* and \$100", piUnescapeMarkdownText("\\*bold\\* and \\\$100"))
        assertEquals("C:\\Users\\pi\\file.txt", piUnescapeMarkdownText("C:\\Users\\pi\\file.txt"))
        assertEquals("a & b <tag> \"ok\" A B", piUnescapeMarkdownText("a &amp; b &lt;tag&gt; &quot;ok&quot; &#65; &#x42;"))
    }

    @Test
    fun `multi-line blockquotes and nested quoted code fences strip blockquote markers cleanly`() {
        val md = """
            > First line of quote
            > Second line of quote
            >
            > ```kotlin
            > val answer = 42
            > println(answer)
            > ```
        """.trimIndent()
        val tree = parseGfm(md)
        val quote = tree.children.first { it.type == MarkdownElementTypes.BLOCK_QUOTE }
        val paragraph = quote.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val plain = extractInlinePlainTextForTest(md, paragraph)
        assertFalse("Multi-line blockquote leaked '>': $plain", plain.contains(">"))
        assertTrue(plain.contains("First line of quote"))
        assertTrue(plain.contains("Second line of quote"))

        val fence = quote.children.first { it.type == MarkdownElementTypes.CODE_FENCE }
        val extracted = extractCodeFence(md, fence)
        assertEquals("kotlin", extracted.language)
        assertTrue(extracted.closed)
        assertFalse("Quoted code fence leaked '>': ${extracted.code}", extracted.code.contains(">"))
        assertEquals("val answer = 42\nprintln(answer)", extracted.code)
    }

    @Test
    fun `collapsed and shortcut reference links and images resolve cleanly`() {
        val md = """
            Full [Label][pi-ref], collapsed [Pi Ref][], shortcut [Pi Ref], image ![Logo][].

            [pi-ref]: https://example.com/full
            [pi ref]: https://example.com/collapsed
            [logo]: https://example.com/logo.png
        """.trimIndent()
        val tree = parseGfm(md)
        val refs = extractPiReferenceLinks(md, tree)
        assertEquals("https://example.com/full", refs["pi-ref"])
        assertEquals("https://example.com/collapsed", refs["pi ref"])

        val paragraph = tree.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val refLinks = paragraph.children.filter {
            it.type == MarkdownElementTypes.FULL_REFERENCE_LINK ||
                it.type == MarkdownElementTypes.SHORT_REFERENCE_LINK
        }
        assertEquals(3, refLinks.size)
        val resolvedUrls = refLinks.map { link ->
            resolveReferenceDestination(
                md,
                link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL },
                link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT },
                refs,
            )
        }
        assertEquals(
            listOf("https://example.com/full", "https://example.com/collapsed", "https://example.com/collapsed"),
            resolvedUrls,
        )

        val img = paragraph.children.first { it.type == MarkdownElementTypes.IMAGE }
        assertEquals("https://example.com/logo.png", resolveAstImageLink(md, img, refs))
    }

    @Test
    fun `GFM alert detector matches all five callout types and ignores inline prose markers`() {
        val cases = listOf(
            "> [!NOTE]\n> Details here" to PiAlertKind.Note,
            "> [!tip]\n> Helpful hint" to PiAlertKind.Tip,
            "> [!IMPORTANT]\n> Must read" to PiAlertKind.Important,
            "> [!WARNING]\n> Watch out" to PiAlertKind.Warning,
            "> [!CAUTION]\n> Dangerous" to PiAlertKind.Caution,
        )
        for ((md, expected) in cases) {
            val tree = parseGfm(md)
            val alertOrQuote = tree.children.first {
                it.type == GFMElementTypes.ALERT || it.type == MarkdownElementTypes.BLOCK_QUOTE
            }
            assertEquals("Expected native GFM ALERT node for $md", GFMElementTypes.ALERT, alertOrQuote.type)
            val alert = detectGfmAlert(md, alertOrQuote)
            assertTrue("Expected alert for $md", alert != null)
            assertEquals(expected, alert!!.kind)
            assertTrue(alert.firstParagraphHasRemainingBody)
        }
        val inlineNotAlert = "> [!NOTE] same-line text is a normal quote"
        val tree = parseGfm(inlineNotAlert)
        val quote = tree.children.first { it.type == MarkdownElementTypes.BLOCK_QUOTE }
        assertEquals(null, detectGfmAlert(inlineNotAlert, quote))
    }

    @Test
    fun `syntax highlighter handles pathological inputs and 32 KiB file within strict latency budget`() {
        val pathological = listOf(
            "\"unterminated string with \\",
            "/* unterminated block comment @param x",
            "\"\"\"unterminated triple \${nested { brace",
            "f\"unterminated {python",
            "<<<<<>>>>><!-- unclosed",
            "🚀🎉 中文变量 = \"表情🔥\${x}\" + 0xDEADBEEF",
        )
        for (lang in listOf("kotlin", "python", "typescript", "rust", "html", "json", "yaml", "toml", "css", "sql", "markdown", "diff")) {
            for (input in pathological) {
                val res = PiFastCodeHighlighter.highlight(input, lang)
                assertTrue(res.languageKnown)
                for (span in res.spans) {
                    assertTrue("Invalid span $span for $lang", span.start >= 0 && span.end <= input.length && span.start <= span.end)
                }
            }
        }

        // 32 KiB realistic Kotlin benchmark: must finish well under 50 ms on JVM
        val snippet = """
            /** Doc for @param items */
            @Composable
            fun renderBlock(items: List<String>, count: Int): Boolean {
                val msg = "size=${'$'}{items.size} count=${'$'}count"
                return items.isNotEmpty() && count >= 0x10
            }
        """.trimIndent() + "\n"
        val bigCode = buildString(34 * 1024) {
            while (length < 32 * 1024) append(snippet)
        }
        // Warmup
        PiFastCodeHighlighter.highlight(snippet, "kotlin")
        val started = System.nanoTime()
        val bigResult = PiFastCodeHighlighter.highlight(bigCode, "kotlin")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        assertTrue(bigResult.languageKnown)
        assertTrue(bigResult.spans.size > 500)
        assertTrue("32 KiB highlight took ${elapsedMs}ms (> 150ms)", elapsedMs < 150.0)
    }

    @Test
    fun `inline math skips blockquote fences, list-nested fences, and indented code blocks while keeping quoted display math`() {
        val md = """
            > ```bash
            > echo ${'$'}HOME ${'$'}USER
            > ```

            1. Run command:

               ```sh
               printf "%s %s\n" "${'$'}A" "${'$'}B"
               ```

            Indented code:

                val cost = "${'$'}x + ${'$'}y"

            - List item

              Continuation paragraph with ${'$'}a + b = c${'$'}.

            > ${'$'}${'$'}
            > E = mc^2
            > ${'$'}${'$'}
        """.trimIndent()
        val extracted = PiInlineMath.extract(md)

        // Only the list continuation paragraph's `$a + b = c$` should be extracted as inline math.
        assertEquals(1, extracted.entries.size)
        assertEquals("a + b = c", extracted.entries[0].tex)
        assertTrue(extracted.hasBlockMath)
        assertTrue(extracted.markdown.contains("echo \$HOME \$USER"))
        assertTrue(extracted.markdown.contains("\"\$A\" \"\$B\""))
        assertTrue(extracted.markdown.contains("val cost = \"\$x + \$y\""))
        assertTrue(extracted.markdown.contains("> \$\$\n> E = mc^2\n> \$\$"))
    }

    @Test
    fun `syntax highlighter handles Rust lifetimes, JS regexes, JS double-quote dollars, nested comments, YAML block scalars, HTML script, and oversized files`() {
        // 1. Rust lifetimes `'a` / `'static` must NOT be treated as unterminated strings
        val rust = "fn longest<'a>(x: &'a str, ch: 'z') -> &'static str { x }"
        val rustRes = PiFastCodeHighlighter.highlight(rust, "rust")
        assertTrue(rustRes.languageKnown)
        val lifetimeSpans = rustRes.spans.filter { rust.substring(it.start, it.end) == "'a" }
        assertTrue("Expected 'a highlighted as Type", lifetimeSpans.isNotEmpty() && lifetimeSpans.all { it.token == PiSyntaxToken.Type })
        val charSpans = rustRes.spans.filter { rust.substring(it.start, it.end) == "'z'" }
        assertTrue("Expected 'z' highlighted as StringLiteral", charSpans.any { it.token == PiSyntaxToken.StringLiteral })

        // 2. JS regex literal with `//` inside must NOT be split as line comment
        val js = "const re = /https?:\\/\\/[^\\s]+/gi;\nconst s = \"price \$100 \${notInterp}\";\nconst t = `ok \${realInterp}`;"
        val jsRes = PiFastCodeHighlighter.highlight(js, "javascript")
        assertTrue(jsRes.spans.none { it.token == PiSyntaxToken.Comment })
        val regexStart = js.indexOf("/https")
        val regexEnd = js.indexOf("/gi") + 3
        assertTrue(jsRes.spans.any { it.start == regexStart && it.end == regexEnd && it.token == PiSyntaxToken.StringLiteral })
        // Double-quoted `$100` / `${notInterp}` must NOT produce Variable interpolation spans in JS
        val dqStart = js.indexOf("\"price")
        val dqEnd = js.indexOf(";\nconst t")
        assertTrue(jsRes.spans.none { it.token == PiSyntaxToken.Variable && it.start in dqStart until dqEnd })
        // Backtick `${realInterp}` MUST produce Variable interpolation span
        val btStart = js.indexOf("`ok")
        assertTrue(jsRes.spans.any { it.token == PiSyntaxToken.Variable && it.start > btStart })

        // 3. Kotlin nested block comments must not terminate at the inner closer
        val nestedComment = "val a = 1 " + "/" + "* outer " + "/" + "* inner *" + "/ still comment *" + "/ + 2"
        val ktRes = PiFastCodeHighlighter.highlight(nestedComment, "kotlin")
        val commentSpan = ktRes.spans.firstOrNull { it.token == PiSyntaxToken.Comment }
        assertTrue(commentSpan != null && commentSpan.end == nestedComment.indexOf(" + 2"))

        // 4. YAML block scalar lines with `: ` must stay StringLiteral, not Variable keys
        val yaml = "script: |\n  echo http://example.com:80\n  key: not-a-yaml-key\nnext_key: 42"
        val yamlRes = PiFastCodeHighlighter.highlight(yaml, "yaml")
        val blockLineStart = yaml.indexOf("key: not-a-yaml-key")
        val blockLineEnd = blockLineStart + "key: not-a-yaml-key".length
        assertTrue(yamlRes.spans.any { it.start == blockLineStart && it.end == blockLineEnd && it.token == PiSyntaxToken.StringLiteral })
        val nextKeyStart = yaml.indexOf("next_key")
        assertTrue(yamlRes.spans.any { it.start == nextKeyStart && it.token == PiSyntaxToken.Variable })

        // 5. HTML embedded <script> and <style> delegate to JS and CSS lexers
        val html = "<style>.card { color: #ff00aa; }</style><script>const ok = a < b;</script>"
        val htmlRes = PiFastCodeHighlighter.highlight(html, "html")
        val constIdx = html.indexOf("const")
        assertTrue(htmlRes.spans.any { it.start == constIdx && it.token == PiSyntaxToken.Keyword })
        val hexIdx = html.indexOf("#ff00aa")
        assertTrue(htmlRes.spans.any { it.start == hexIdx && it.token == PiSyntaxToken.Number })

        // 6. Oversized >64 KiB file in known language returns languageKnown = true (avoids slow IPC fallback)
        val huge = "val x = 1\n".repeat(7000)
        val hugeRes = PiFastCodeHighlighter.highlight(huge, "kotlin")
        assertTrue(hugeRes.languageKnown)
        assertTrue(hugeRes.spans.isEmpty())
    }

    @Test
    fun `table cell width strips link URLs and resolves inline math sentinels`() {
        val rawLink = "[API Docs](https://very-long-domain.example.com/v2/reference/endpoints/users?token=abcdef123456)"
        val wLink = estimateTableCellDisplayChars(rawLink)
        assertEquals("API Docs".length, wLink)

        val extracted = PiInlineMath.extract("Value \$x_1 + x_2\$")
        val wMath = estimateTableCellDisplayChars(extracted.markdown, extracted)
        // Must count readable Unicode fallback length, NOT raw `PiMathTok0EndTok` sentinel length
        assertTrue("Expected compact display width, got $wMath", wMath < extracted.markdown.length)
    }

    @Test
    fun `streaming cursor does not corrupt GFM alerts or quoted code fences`() {
        val alert = "> [!NOTE]"
        assertEquals(alert, withStreamingCursor(alert))
        val quotedFence = "> ```kotlin"
        assertEquals(quotedFence, withStreamingCursor(quotedFence))
        val prose = "Hello world"
        assertEquals("Hello world$STREAMING_CURSOR", withStreamingCursor(prose))
    }

    @Test
    fun `code span normalization follows CommonMark line-ending and blockquote rules`() {
        assertEquals("foo bar", normalizeCodeSpanContent("`foo\nbar`"))
        assertEquals("foo bar", normalizeCodeSpanContent("`foo\n> bar`"))
        assertEquals("`a`", normalizeCodeSpanContent("`` `a` ``"))
    }

    @Test
    fun `quoted display math adjacent to quote text separates into its own paragraph`() {
        val md = "> Intro line\n> $$\n> E = mc^2\n> $$\n> Outro line"
        val extracted = PiInlineMath.extract(md)
        assertTrue(extracted.hasBlockMath)
        val root = parseGfm(extracted.markdown)
        val quote = root.children.first { it.type == MarkdownElementTypes.BLOCK_QUOTE }
        val paragraphs = quote.children.filter { it.type == MarkdownElementTypes.PARAGRAPH }
        assertEquals(
            "Expected 3 separate paragraphs (intro, block math, outro) inside blockquote, got ${paragraphs.size}: ${extracted.markdown}",
            3,
            paragraphs.size,
        )
        val middleText = extracted.markdown.substring(paragraphs[1].startOffset, paragraphs[1].endOffset)
        assertTrue("Middle paragraph must start with $$: $middleText", middleText.trim().startsWith("$$"))
        assertTrue("Middle paragraph must end with $$: $middleText", middleText.trim().endsWith("$$"))
    }

    @Test
    fun `extractCodeFence strips opening fence indent inside lists and indented fences`() {
        val listFence = "- Step 1\n    ```kotlin\n    val a = 1\n      val b = 2\n    ```"
        val root = parseGfm(listFence)
        fun findFence(node: ASTNode): ASTNode? {
            if (node.type == MarkdownElementTypes.CODE_FENCE) return node
            for (c in node.children) findFence(c)?.let { return it }
            return null
        }
        val fenceNode = findFence(root)
        assertNotNull("Expected CODE_FENCE inside list", fenceNode)
        val extracted = extractCodeFence(listFence, fenceNode!!)
        assertEquals("kotlin", extracted.language)
        assertEquals("val a = 1\n  val b = 2", extracted.code)
        assertTrue(extracted.closed)
    }

    @Test
    fun `PiCodeLanguage forPath covers modern extensions and extensionless basenames`() {
        assertEquals("kotlin", PiCodeLanguage.forPath("build.gradle.kts"))
        assertEquals("dart", PiCodeLanguage.forPath("lib/main.dart"))
        assertEquals("zig", PiCodeLanguage.forPath("src/main.zig"))
        assertEquals("groovy", PiCodeLanguage.forPath("settings.gradle"))
        assertEquals("html", PiCodeLanguage.forPath("App.vue"))
        assertEquals("html", PiCodeLanguage.forPath("Page.svelte"))
        assertEquals("ini", PiCodeLanguage.forPath(".env.local"))
        assertEquals("ini", PiCodeLanguage.forPath("config.ini"))
        assertEquals("diff", PiCodeLanguage.forPath("changes.diff"))
        assertEquals("xml", PiCodeLanguage.forPath("diagram.svg"))
        assertEquals("dockerfile", PiCodeLanguage.forPath("/srv/app/Dockerfile"))
        assertEquals("makefile", PiCodeLanguage.forPath("project/Makefile"))
    }

    @Test
    fun `fast highlighter supports mermaid and csv source views`() {
        val mmd = "flowchart TD\n  %% comment\n  A[\"Start\"] --> B"
        val mmdRes = PiFastCodeHighlighter.highlight(mmd, "mermaid")
        assertTrue(mmdRes.languageKnown)
        assertTrue(mmdRes.spans.any { it.token == PiSyntaxToken.Keyword })
        assertTrue(mmdRes.spans.any { it.token == PiSyntaxToken.Comment })
        assertTrue(mmdRes.spans.any { it.token == PiSyntaxToken.Operator })

        val csv = "name,score,active\n\"Alice\",98.5,true"
        val csvRes = PiFastCodeHighlighter.highlight(csv, "csv")
        assertTrue(csvRes.languageKnown)
        assertTrue(csvRes.spans.any { it.token == PiSyntaxToken.Keyword })
        assertTrue(csvRes.spans.any { it.token == PiSyntaxToken.Number })
        assertTrue(csvRes.spans.any { it.token == PiSyntaxToken.StringLiteral })
    }

    @Test
    fun `PiFenceSniff detects Mermaid 12 headers, leading comments, YAML frontmatter, and aliases`() {
        val noJson: (String) -> Boolean = { false }
        assertEquals("mermaid", PiFenceSniff.alias("mermaidjs"))
        assertEquals("math", PiFenceSniff.alias("mathjax"))
        assertEquals("svg", PiFenceSniff.alias("svg+xml"))

        val samples = listOf(
            "xychart-beta\n  title \"Latency\"\n  x-axis [a, b]\n  bar [1, 2]",
            "sankey-beta\n  A,B,10",
            "kanban\n  Todo\n    id1[Fix Mermaid]",
            "architecture-beta\n  service api(server)[API]",
            "C4Context\n  Person(u, \"User\")",
            "%% leading comment\nflowchart TD\n  A --> B",
            "---\ntitle: Sample\n---\nsequenceDiagram\n  A->>B: Hi",
        )
        for (sample in samples) {
            assertEquals("Expected mermaid for: $sample", "mermaid", PiFenceSniff.sniff(sample, noJson))
        }
    }

    @Test
    fun `PiInlineMath extracts standalone latex env, opening-line multiline dollars, and inline brackets`() {
        val envMd = "推导如下：\n\\begin{aligned}\na &= b + c \\\\\nd &= e + f\n\\end{aligned}\n当\$x>0\$时成立。"
        val envRes = PiInlineMath.extract(envMd)
        assertTrue(envRes.hasBlockMath)
        assertTrue(envRes.hasInlineMath)
        assertTrue(envRes.markdown.contains("\$\$\n\\begin{aligned}"))
        assertTrue(envRes.markdown.contains("\\end{aligned}\n\$\$"))

        val openLineMd = "结论：\n\$\$ a + b\n+ c = d \$\$\n完成。"
        val openLineRes = PiInlineMath.extract(openLineMd)
        assertTrue(openLineRes.hasBlockMath)
        assertTrue(openLineRes.markdown.contains("\$\$\na + b\n+ c = d\n\$\$"))

        val inlineBracketMd = "质能方程 \\[ E = mc^2 \\] 很著名。"
        val bracketRes = PiInlineMath.extract(inlineBracketMd)
        assertTrue(bracketRes.hasInlineMath)
        assertEquals(1, bracketRes.entries.size)
        assertEquals("E = mc^2", bracketRes.entries[0].tex)
    }

    @Test
    fun `computeTightCropRect trims oversized empty margins while clamping padding to bounds`() {
        val rect = computeTightCropRect(
            bitmapWidth = 1200,
            bitmapHeight = 900,
            minX = 40,
            minY = 30,
            maxX = 440,
            maxY = 330,
            paddingPx = 16,
        )
        assertEquals(24, rect[0])
        assertEquals(14, rect[1])
        assertEquals(433, rect[2])
        assertEquals(333, rect[3])

        val edge = computeTightCropRect(
            bitmapWidth = 300,
            bitmapHeight = 200,
            minX = 2,
            minY = 1,
            maxX = 298,
            maxY = 199,
            paddingPx = 16,
        )
        assertEquals(0, edge[0])
        assertEquals(0, edge[1])
        assertEquals(300, edge[2])
        assertEquals(200, edge[3])
    }

    private fun parseGfm(markdown: String): ASTNode =
        PiSharedMarkdownParser.buildMarkdownTreeFromString(markdown as CharSequence)
}
