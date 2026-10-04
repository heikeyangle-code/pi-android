package app.pi.ui.render

// 裸图片 URL → `![](...)` 这条纯逻辑的检查。由 `tools/run-app-pure-checks.sh` 的
// `image-links` 项编译并运行（闭包：本文件 + `PiImageLinks.kt`，两个都不许 import android.*）。
//
// 为什么要有它：改写的**每一条规则都是"不改"**（别碰代码块、别碰带别的文字的行、别碰
// 选项里的 URL、括号不成对时别动、别碰表格分隔行、别碰转义的 `|`），而唯一"改"的那条
// （整行/整格就是一个白名单后缀的 URL）会把那行字**从转写里换掉**。这类改动错了是安静的：
// 画出来的图没错，但用户原来的文字被吃掉了一段，或者一个本来能点的链接变成一块
// 「图片地址…（当前无法显示）」。所以规则这里逐条钉住，包括"除改写段之外逐字节相同"。
//
// 解析器那一侧的事实**不在这个文件里**（裸 JVM 装载不到 `org.jetbrains:markdown`）：
// 哪些写法真的会解析成 `IMAGE`、以及 `LINK_DESTINATION` 里到底是什么，是 `PiImageLinks.kt`
// 的类注释里那张表的来源（用与本 App 同一个 pin 的解析器实跑 AST 得到）。
//
// 手工跑：
//
//   KOTLINC_CP="$(find -L build/pure-checks/kotlinc -name '*.jar' | tr '\n' ':')"; KOTLINC_CP=${KOTLINC_CP%:}
//   LIB_CP="$(find -L build/pure-checks/lib -name '*.jar' | tr '\n' ':')"; LIB_CP=${LIB_CP%:}
//   java -cp "$KOTLINC_CP:$LIB_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib \
//     -jvm-target 17 -classpath "$LIB_CP" -d /tmp/piimg/out \
//     app/src/test/kotlin/app/pi/ui/render/PiImageLinksCheck.kt \
//     app/src/main/kotlin/app/pi/ui/render/PiImageLinks.kt
//   java -Dpi.repo.root=. -cp "/tmp/piimg/out:$LIB_CP" app.pi.ui.render.PiImageLinksCheckKt

import java.io.File
import kotlin.system.exitProcess

private val ROOT: File = File(System.getProperty("pi.repo.root") ?: ".")

private var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** 源码文本的比较要无视缩进 —— 判据是"这句话还在"，不是排版。 */
private fun normalized(text: String): String = text.replace(Regex("[ \t]+"), " ")

fun main() {
    // ------------------------------------------------------ 1. 该改的：正文一行
    run {
        val url = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8d/A_b.jpg/1920px-A_b.jpg"
        check("正文一行 → 图片", PiImageLinks.rewrite(url), "![]($url)")
        check("http:// 同样处理", PiImageLinks.rewrite("http://a/B.png"), "![](http://a/B.png)")
        check(
            "后缀大小写不敏感",
            PiImageLinks.rewrite("https://a/B.JPG"),
            "![](https://a/B.JPG)",
        )
        check(
            "查询串与片段不算进后缀",
            PiImageLinks.rewrite("https://a/B.webp?w=800#now"),
            "![](https://a/B.webp?w=800#now)",
        )
        check(
            "片段在查询串之前也认（先切 ? 再切 #）",
            PiImageLinks.rewrite("https://a/B.gif#x?y=1"),
            "![](https://a/B.gif#x?y=1)",
        )
        // 缩进 ≤3 空格是正文的一部分，要原样留在外面（列表续行就是这种形状）。
        check(
            "两空格缩进保留",
            PiImageLinks.rewrite("  https://a/B.png"),
            "  ![](https://a/B.png)",
        )
        check(
            "行尾空白保留",
            PiImageLinks.rewrite("https://a/B.png   "),
            "![](https://a/B.png)   ",
        )
        check(
            "CRLF 的 \\r 保留",
            PiImageLinks.rewrite("https://a/B.png\r\n下一行"),
            "![](https://a/B.png)\r\n下一行",
        )
        // 圆括号：成对时转义（见 PiImageLinks 类注释那张表），不成对时**整行不动**。
        check(
            "成对括号转义",
            PiImageLinks.rewrite("https://a/Foo_(bar).jpg"),
            "![](https://a/Foo_\\(bar\\).jpg)",
        )
        check(
            "不成对的括号：不改",
            PiImageLinks.rewrite("https://a/Foo_(bar.jpg"),
            "https://a/Foo_(bar.jpg",
        )
        check(
            "先闭后开的括号：不改",
            PiImageLinks.rewrite("https://a/Foo_)bar(.jpg"),
            "https://a/Foo_)bar(.jpg",
        )
    }

    // -------------------------------------------------- 2. 不该改的：正文一行
    run {
        val unchanged = listOf(
            "见 https://a/B.jpg 这里",
            "https://a/data.json",
            "https://a/index.html",
            "https://picsum.photos/1200/800",
            "https://a/B",
            "<https://a/B.jpg>",
            "https://a/B.jpg/",
            "    https://a/B.jpg",
            "\thttps://a/B.jpg",
            "`https://a/B.jpg`",
            "https://a/B|C.jpg",
            "https://a/B\\C.jpg",
            "https://a/B c.jpg",
            "ftp://a/B.jpg",
            "https://a/B.jpg（当前无法显示）",
            "![](https://a/B.jpg)",
            "图：https://a/B.jpg",
            "https://a/B.jpg https://a/C.jpg",
        )
        for (line in unchanged) {
            check("不改：$line", PiImageLinks.rewrite(line), line)
        }
        // 快路径要返回**同一个实例**（流式转写的绝大多数帧靠它零成本）。
        val plain = "这一整段没有任何链接，也没有冒号斜杠。\n第二行也一样。"
        check("无 `://` 时原样返回（同一实例）", PiImageLinks.rewrite(plain) === plain, true)
    }

    // --------------------------------------------------------------- 3. 代码
    run {
        val fenced = "```\nhttps://a/B.jpg\n```"
        check("围栏代码块不改", PiImageLinks.rewrite(fenced), fenced)
        val tilde = "~~~\nhttps://a/B.jpg\n~~~"
        check("~~~ 围栏也不改", PiImageLinks.rewrite(tilde), tilde)
        val fencedLang = "```kotlin\nval u = \"https://a/B.jpg\"\n```"
        check("带 info string 的围栏不改", PiImageLinks.rewrite(fencedLang), fencedLang)
        val longerClose = "````\nhttps://a/B.jpg\n````"
        check("四反引号围栏不改", PiImageLinks.rewrite(longerClose), longerClose)
        // **围栏结束之后**必须重新开始改写（状态机要退出来）。
        val after = "```\ncode\n```\nhttps://a/B.jpg"
        check("围栏之后照旧改写", PiImageLinks.rewrite(after), "```\ncode\n```\n![](${"https://a/B.jpg"})")
        // 围栏里写着 info string 的收栏不算收栏（CommonMark）。
        val notAClose = "```\nhttps://a/B.jpg\n```kotlin\nhttps://a/C.jpg\n```"
        check(
            "` ```kotlin ` 不是收栏",
            PiImageLinks.rewrite(notAClose),
            notAClose,
        )
        // 行内代码里的一行（整行就是一个代码 span）不改。
        check("行内代码不改", PiImageLinks.rewrite("`https://a/B.jpg`"), "`https://a/B.jpg`")
    }

    // -------------------------------------------------------------- 4. 表格
    run {
        val table = "| 说明 | 直链 |\n| --- | --- |\n| 山 | https://a/B.jpg |"
        check(
            "表格单元格被改写，表头与分隔行不动",
            PiImageLinks.rewrite(table),
            "| 说明 | 直链 |\n| --- | --- |\n| 山 | ![](https://a/B.jpg) |",
        )
        val noOuterPipes = "说明 | 直链\n--- | ---\nx | https://a/B.png"
        check(
            "无首尾 `|` 的表也认",
            PiImageLinks.rewrite(noOuterPipes),
            "说明 | 直链\n--- | ---\nx | ![](https://a/B.png)",
        )
        val spaces = "| a |   https://a/B.png   |"
        check(
            "格内空白原样保留",
            PiImageLinks.rewrite(spaces),
            "| a |   ![](https://a/B.png)   |",
        )
        val mixed = "| a | 见 https://a/B.png |"
        check("格内还有别的文字：不改", PiImageLinks.rewrite(mixed), mixed)
        val escaped = "| a | x \\| https://a/B.png |"
        check("转义的 `\\|` 不算分格符", PiImageLinks.rewrite(escaped), escaped)
        val twoUrls = "| https://a/B.png | https://a/C.png |"
        check(
            "一格一个 URL：两格都改",
            PiImageLinks.rewrite(twoUrls),
            "| ![](https://a/B.png) | ![](https://a/C.png) |",
        )
        val parens = "| x | https://a/Foo_(bar).jpg |"
        check(
            "格内括号同样转义",
            PiImageLinks.rewrite(parens),
            "| x | ![](https://a/Foo_\\(bar\\).jpg) |",
        )
    }

    // -------------------------------------------- 5. 不变量：幂等 + 逐字节
    run {
        val document = buildString {
            append("# 标题\n\n")
            append("一句话里有个 https://a/inside.jpg 链接。\n\n")
            append("https://a/B_c.jpg\n\n")
            append("| 说明 | 图 |\n| --- | --- |\n| 一 | https://a/D.png |\n\n")
            append("```\nhttps://a/kept.jpg\n```\n\n")
            append("最后一段。\n")
        }
        val once = PiImageLinks.rewrite(document)
        check("改写过两处", Regex("!\\[\\]\\(").findAll(once).count(), 2)
        check("幂等", PiImageLinks.rewrite(once), once)
        // 除了那两处，逐字节相同：把改写点换回原文应当得到原文档。
        val back = once
            .replace("![](https://a/B_c.jpg)", "https://a/B_c.jpg")
            .replace("![](https://a/D.png)", "https://a/D.png")
        check("除改写段外逐字节相同", back, document)
        check("空串", PiImageLinks.rewrite(""), "")
    }

    // ------------------------------------------------- 6. 接线（源码文本）
    run {
        val render = File(ROOT, "app/src/main/kotlin/app/pi/ui/render/PiMarkdown.kt")
        if (render.isFile) {
            check(
                "PiMarkdownText 真的调用改写",
                normalized(render.readText()).contains("val content = remember(prepared.text) { PiImageLinks.rewrite(prepared.text) }"),
                true,
            )
        } else {
            failures++
            println("FAIL 读不到 PiMarkdown.kt（pi.repo.root=${ROOT.absolutePath}）")
        }
        val components = File(ROOT, "app/src/main/kotlin/app/pi/ui/render/PiMarkdownComponents.kt")
        if (components.isFile) {
            val text = normalized(components.readText())
            // 表格里那一个"按单元格宽解码"的 transformer 实例必须真的装上，否则表格缩略图
            // 又会回到"按窗口 1080 px 解码"（40 张图 ≈ 50 MB，滚动卡顿的根因）。
            check(
                "PiTable 装上了单元格宽的 transformer",
                text.contains("val transformer = rememberPiGuestImageTransformer(cellWidthPx)") &&
                    text.contains("LocalImageTransformer provides transformer") &&
                    text.contains("LocalPiImageTransformer provides transformer"),
                true,
            )
            check(
                "单元格宽 = 列宽 − 两侧内边距",
                text.contains("(dimens.tableCellWidth - dimens.tableCellPadding * 2).roundToPx()"),
                true,
            )
        } else {
            failures++
            println("FAIL 读不到 PiMarkdownComponents.kt（pi.repo.root=${ROOT.absolutePath}）")
        }
    }

    if (failures > 0) {
        println("PiImageLinksCheck: $failures check(s) FAILED")
        exitProcess(1)
    }
    println("PiImageLinksCheck: harness: OK")
}
