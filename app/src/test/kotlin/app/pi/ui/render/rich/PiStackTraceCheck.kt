package app.pi.ui.render.rich

// Bare-JVM harness for `PiStackTrace.kt` — the five-dialect stack-trace
// recognizer.
//
// The dangerous failures here are not parse errors, they are *claims*: a prose
// sentence becoming a Java trace, a `(Native Method)` frame being reported as a
// file, a JavaScript frame's `.ts` path being read as Kotlin, a Python
// `<stdin>` being offered as a clickable file, or a 50/50 Java/JS mix being
// assigned to whichever parser ran first. Each of those is silent on a device —
// the card just says something the trace never said — so the checks below assert
// exactly the `file`/`line` nullability, not only the happy path.
//
// Run it by hand:
//
//   tools/run-app-pure-checks.sh

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

private fun parsed(result: StackTraceResult): ParsedStackTrace {
    check("(fixture) expected a parse, got $result", result is StackTraceResult.Parsed, true)
    return (result as? StackTraceResult.Parsed)?.trace
        ?: ParsedStackTrace(StackTraceKind.JavaKotlin, null, null, emptyList())
}

private fun failureOf(result: StackTraceResult): StackTraceFailure? =
    (result as? StackTraceResult.Failure)?.reason

private val JAVA_TRACE = "java.lang.IllegalStateException: boom\n" +
    "\tat com.example.Foo.bar(Foo.kt:42)\n" +
    "\tat com.example.Main.main(Main.kt:10)"

fun main() {
    // =================================================== A. Java / Kotlin
    run {
        val trace = parsed(PiStackTrace.parse(JAVA_TRACE))
        check("A1 kind", trace.kind, StackTraceKind.JavaKotlin)
        check("A2 exception type", trace.exceptionType, "java.lang.IllegalStateException")
        check("A3 message", trace.message, "boom")
        check("A4 frame count", trace.frames.size, 2)
        check("A5 first function", trace.frames[0].function, "com.example.Foo.bar")
        check("A6 first file", trace.frames[0].file, "Foo.kt")
        check("A7 first line", trace.frames[0].line, 42)
        check("A8 second function", trace.frames[1].function, "com.example.Main.main")
        check("A9 second file", trace.frames[1].file, "Main.kt")
        check("A10 second line", trace.frames[1].line, 10)
        check("A11 not truncated", trace.framesTruncated, false)
        check("A12 raw keeps the frame", trace.frames[0].raw, "at com.example.Foo.bar(Foo.kt:42)")

        val inThread = parsed(
            PiStackTrace.parse(
                "Exception in thread \"main\" java.lang.RuntimeException: x\n" +
                    "\tat a.B.c(B.java:1)\n" +
                    "\tat a.D.e(D.java:2)",
            ),
        )
        check("A13 `Exception in thread` prefix", inThread.exceptionType, "java.lang.RuntimeException")
        check("A14 message after the prefix", inThread.message, "x")
        check("A15 java frames recognized", inThread.frames.size, 2)

        val causedBy = parsed(
            PiStackTrace.parse("Caused by: java.util.NoSuchElementException\n\tat x.Y.z(Y.kt:3)"),
        )
        check("A16 a Caused-by-only header is the exception", causedBy.exceptionType, "java.util.NoSuchElementException")
        check("A17 its message is absent, not empty", causedBy.message, null)
        check("A18 frame file", causedBy.frames[0].file, "Y.kt")

        val native = parsed(
            PiStackTrace.parse(
                "java.lang.RuntimeException: x\n" +
                    "\tat java.lang.Object.wait(Native Method)\n" +
                    "\tat com.a.B.c(B.kt:5)",
            ),
        )
        check("A19 Native Method keeps the function", native.frames[0].function, "java.lang.Object.wait")
        check("A20 Native Method gets no file", native.frames[0].file, null)
        check("A21 Native Method gets no line", native.frames[0].line, null)
        check("A22 the real frame keeps its file", native.frames[1].file, "B.kt")

        val unknownSource = parsed(
            PiStackTrace.parse("java.lang.RuntimeException: x\n\tat a.B.c(Unknown Source)"),
        )
        check("A23 Unknown Source gets no file", unknownSource.frames[0].file, null)
        check("A24 Unknown Source gets no line", unknownSource.frames[0].line, null)

        val noLine = parsed(
            PiStackTrace.parse("java.lang.RuntimeException: x\n\tat a.B.c(B.kt)"),
        )
        check("A25 a file without a line keeps the file", noLine.frames[0].file, "B.kt")
        check("A26 a file without a line has no line", noLine.frames[0].line, null)

        val android = parsed(
            PiStackTrace.parse("android.os.RemoteException: x\n\tat app//com.a.B.c(B.kt:5)"),
        )
        check("A27 the android module prefix stays in the function", android.frames[0].function, "app//com.a.B.c")
        check("A28 its file is still read", android.frames[0].file, "B.kt")

        val noMessage = parsed(
            PiStackTrace.parse("java.lang.NullPointerException\n\tat a.B.c(B.kt:1)\n\tat a.D.e(D.kt:2)"),
        )
        check("A29 a bare type header", noMessage.exceptionType, "java.lang.NullPointerException")
        check("A30 absent message stays null", noMessage.message, null)

        val custom = parsed(
            PiStackTrace.parse("org.example.CustomFailure: boom\n\tat a.B.c(C.kt:1)"),
        )
        check("A31 a custom throwable class is still the exception type", custom.exceptionType, "org.example.CustomFailure")
        check("A32 its message", custom.message, "boom")

        val proseHeader = parsed(
            PiStackTrace.parse("problem: x\n\tat a.B.c(C.kt:1)"),
        )
        check("A33 a lowercase prose word is not invented into a type", proseHeader.exceptionType, null)
        check("A34 the frame is still real", proseHeader.frames[0].file, "C.kt")
    }

    // =================================================== B. JavaScript / TypeScript
    run {
        val trace = parsed(
            PiStackTrace.parse(
                "TypeError: Cannot read properties of undefined (reading 'x')\n" +
                    "    at foo (/app/src/index.js:10:5)\n" +
                    "    at bar (/app/src/index.ts:20:3)",
            ),
        )
        check("B1 kind", trace.kind, StackTraceKind.JavaScript)
        check("B2 type", trace.exceptionType, "TypeError")
        check("B3 message", trace.message, "Cannot read properties of undefined (reading 'x')")
        check("B4 frame count", trace.frames.size, 2)
        check("B5 first function", trace.frames[0].function, "foo")
        check("B6 first file", trace.frames[0].file, "/app/src/index.js")
        check("B7 first line", trace.frames[0].line, 10)
        check("B8 second file", trace.frames[1].file, "/app/src/index.ts")
        check("B9 second line", trace.frames[1].line, 20)

        val bare = parsed(PiStackTrace.parse("Error: boom\n    at /app/x.js:1:1"))
        check("B10 a bare V8 location is a frame", bare.frames[0].file, "/app/x.js")
        check("B11 its line", bare.frames[0].line, 1)
        check("B12 it has no function", bare.frames[0].function, null)

        val anonymous = parsed(
            PiStackTrace.parse(
                "TypeError: x\n" +
                    "    at Object.<anonymous> (/app/dist/bundle.js:1:1)\n" +
                    "    at <anonymous>",
            ),
        )
        check("B13 anonymous function name is kept", anonymous.frames[0].function, "Object.<anonymous>")
        check("B14 its file is read", anonymous.frames[0].file, "/app/dist/bundle.js")
        check("B15 `<anonymous>` is not a file", anonymous.frames[1].file, null)
        check("B16 `<anonymous>` is not a line", anonymous.frames[1].line, null)

        val indexOnly = parsed(
            PiStackTrace.parse(
                "TypeError: x\n" +
                    "    at foo (/app/x.js:1:1)\n" +
                    "    at async Promise.all (index 0)",
            ),
        )
        check("B17 a frame with `index 0` gets no file", indexOnly.frames[1].file, null)
        check("B18 but keeps its function", indexOnly.frames[1].function, "async Promise.all")

        val uncaught = parsed(
            PiStackTrace.parse("Uncaught TypeError: x\n    at foo (http://localhost:3000/x.js:1:1)"),
        )
        check("B19 Uncaught is stripped from the type", uncaught.exceptionType, "TypeError")
        check("B20 a URL file is kept literally", uncaught.frames[0].file, "http://localhost:3000/x.js")
        check("B21 its line", uncaught.frames[0].line, 1)

        val code = parsed(
            PiStackTrace.parse(
                "Error [ERR_MODULE_NOT_FOUND]: Cannot find package 'x'\n    at foo (/app/x.js:1:1)",
            ),
        )
        check("B22 a Node error code is part of the type", code.exceptionType, "Error [ERR_MODULE_NOT_FOUND]")
        check("B23 its message", code.message, "Cannot find package 'x'")

        val fileUrl = parsed(
            PiStackTrace.parse("Error: x\n    at foo (file:///app/x.js:1:1)"),
        )
        check("B24 a file: URL is kept literally", fileUrl.frames[0].file, "file:///app/x.js")

        val nodeInternal = parsed(
            PiStackTrace.parse("Error: x\n    at Module._load (node:internal/modules/cjs/loader:1228:15)"),
        )
        check("B25 a node: location is read", nodeInternal.frames[0].file, "node:internal/modules/cjs/loader")
        check("B26 its line", nodeInternal.frames[0].line, 1228)

        val tsx = parsed(
            PiStackTrace.parse("Error: x\n    at App (/app/src/App.tsx:3:7)"),
        )
        check("B27 tsx frames are JavaScript", tsx.kind, StackTraceKind.JavaScript)
        check("B28 their file", tsx.frames[0].file, "/app/src/App.tsx")
    }

    // =================================================== C. Python
    run {
        val trace = parsed(
            PiStackTrace.parse(
                "Traceback (most recent call last):\n" +
                    "  File \"/app/main.py\", line 10, in foo\n" +
                    "    bar()\n" +
                    "ValueError: boom",
            ),
        )
        check("C1 kind", trace.kind, StackTraceKind.Python)
        check("C2 type", trace.exceptionType, "ValueError")
        check("C3 message", trace.message, "boom")
        check("C4 frame count", trace.frames.size, 1)
        check("C5 function", trace.frames[0].function, "foo")
        check("C6 file", trace.frames[0].file, "/app/main.py")
        check("C7 line", trace.frames[0].line, 10)

        val stdin = parsed(
            PiStackTrace.parse(
                "Traceback (most recent call last):\n" +
                    "  File \"<stdin>\", line 1, in <module>\n" +
                    "NameError: name 'x' is not defined",
            ),
        )
        check("C8 <stdin> is not a file", stdin.frames[0].file, null)
        check("C9 <module> is a function", stdin.frames[0].function, "<module>")
        check("C10 its line is still known", stdin.frames[0].line, 1)

        val frozen = parsed(
            PiStackTrace.parse(
                "Traceback (most recent call last):\n" +
                    "  File \"<frozen importlib._bootstrap>\", line 1, in _call_with_frames_removed\n" +
                    "ImportError: no module",
            ),
        )
        check("C11 <frozen …> is not a file", frozen.frames[0].file, null)

        val chained = parsed(
            PiStackTrace.parse(
                "Traceback (most recent call last):\n" +
                    "  File \"a.py\", line 1, in f\n" +
                    "KeyError: 'x'\n" +
                    "\n" +
                    "During handling of the above exception, another exception occurred:\n" +
                    "\n" +
                    "Traceback (most recent call last):\n" +
                    "  File \"b.py\", line 2, in g\n" +
                    "RuntimeError: y",
            ),
        )
        check("C12 the top-level (last) exception wins", chained.exceptionType, "RuntimeError")
        check("C13 its message", chained.message, "y")
        check("C14 both traceback sections contribute frames", chained.frames.size, 2)
        check("C15 first file", chained.frames[0].file, "a.py")
        check("C16 second file", chained.frames[1].file, "b.py")

        val bare = parsed(
            PiStackTrace.parse(
                "Traceback (most recent call last):\n" +
                    "  File \"a.py\", line 1, in f\n" +
                    "KeyboardInterrupt",
            ),
        )
        check("C17 a bare exception name", bare.exceptionType, "KeyboardInterrupt")
        check("C18 its message is absent", bare.message, null)

        val colon = parsed(
            PiStackTrace.parse(
                "Traceback (most recent call last):\n" +
                    "  File \"a.py\", line 1, in f\n" +
                    "ValueError: a: b",
            ),
        )
        check("C19 a message may contain colons", colon.message, "a: b")
    }

    // =================================================== D. Rust
    run {
        val trace = parsed(
            PiStackTrace.parse(
                "thread 'main' panicked at src/main.rs:10:5:\n" +
                    "boom\n" +
                    "note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace",
            ),
        )
        check("D1 kind", trace.kind, StackTraceKind.Rust)
        check("D2 Rust has no exception type", trace.exceptionType, null)
        check("D3 message from the next line", trace.message, "boom")
        check("D4 the panic site is a frame", trace.frames.size, 1)
        check("D5 panic frame file", trace.frames[0].file, "src/main.rs")
        check("D6 panic frame line", trace.frames[0].line, 10)
        check("D7 panic frame has no function", trace.frames[0].function, null)

        val old = parsed(PiStackTrace.parse("thread 'main' panicked at 'boom', src/main.rs:10:5"))
        check("D8 the pre-1.73 quoted format", old.message, "boom")
        check("D9 old-format file", old.frames[0].file, "src/main.rs")
        check("D10 old-format line", old.frames[0].line, 10)

        val backtrace = parsed(
            PiStackTrace.parse(
                "thread 'main' panicked at src/main.rs:10:5:\n" +
                    "boom\n" +
                    "stack backtrace:\n" +
                    "   0: rust_begin_unwind\n" +
                    "             at ./src/main.rs:10:5\n" +
                    "   1: core::panicking::panic_fmt\n" +
                    "             at ./rustc/abc/library/core/src/panicking.rs:75:14",
            ),
        )
        check("D11 panic site plus two backtrace frames", backtrace.frames.size, 3)
        check("D12 first backtrace function", backtrace.frames[1].function, "rust_begin_unwind")
        check("D13 first backtrace file", backtrace.frames[1].file, "./src/main.rs")
        check("D14 first backtrace line", backtrace.frames[1].line, 10)
        check("D15 second backtrace function", backtrace.frames[2].function, "core::panicking::panic_fmt")
        check("D16 second backtrace file", backtrace.frames[2].file, "./rustc/abc/library/core/src/panicking.rs")
        check("D17 second backtrace line", backtrace.frames[2].line, 75)

        val hashed = parsed(
            PiStackTrace.parse(
                "thread 'main' panicked at src/main.rs:1:1:\n" +
                    "boom\n" +
                    "stack backtrace:\n" +
                    "   0: main::foo::h0123456789abcdef\n" +
                    "             at ./src/main.rs:1:1",
            ),
        )
        check("D18 the symbol hash is stripped for display", hashed.frames[1].function, "main::foo")
        check("D19 the raw line still has it", hashed.frames[1].raw, "at ./src/main.rs:1:1")
    }

    // =================================================== E. Go
    run {
        val trace = parsed(
            PiStackTrace.parse(
                "panic: runtime error: index out of range [1] with length 1\n" +
                    "\n" +
                    "goroutine 1 [running]:\n" +
                    "main.foo()\n" +
                    "\t/app/main.go:10 +0x20\n" +
                    "main.main()\n" +
                    "\t/app/main.go:20 +0x30",
            ),
        )
        check("E1 kind", trace.kind, StackTraceKind.Go)
        check("E2 Go has no exception type", trace.exceptionType, null)
        check("E3 message", trace.message, "runtime error: index out of range [1] with length 1")
        check("E4 frame count", trace.frames.size, 2)
        check("E5 first function", trace.frames[0].function, "main.foo")
        check("E6 first file", trace.frames[0].file, "/app/main.go")
        check("E7 first line", trace.frames[0].line, 10)
        check("E8 second function", trace.frames[1].function, "main.main")
        check("E9 second line", trace.frames[1].line, 20)

        val deadlock = parsed(
            PiStackTrace.parse(
                "fatal error: all goroutines are asleep - deadlock!\n" +
                    "\n" +
                    "goroutine 1 [running]:\n" +
                    "main.main()\n" +
                    "\t/app/main.go:5 +0x1",
            ),
        )
        check("E10 a fatal error is a Go trace", deadlock.kind, StackTraceKind.Go)
        check("E11 its message", deadlock.message, "all goroutines are asleep - deadlock!")

        val createdBy = parsed(
            PiStackTrace.parse(
                "panic: boom\n" +
                    "\n" +
                    "goroutine 1 [running]:\n" +
                    "main.main()\n" +
                    "\t/app/main.go:5 +0x1\n" +
                    "created by main.main in goroutine 1\n" +
                    "\t/app/main.go:10 +0x2",
            ),
        )
        check("E12 a `created by` frame", createdBy.frames.size, 2)
        check("E13 its function", createdBy.frames[1].function, "main.main")
        check("E14 its file", createdBy.frames[1].file, "/app/main.go")
        check("E15 its line", createdBy.frames[1].line, 10)

        val panicOnly = parsed(PiStackTrace.parse("panic: boom"))
        check("E16 a bare panic first line is recognized", panicOnly.kind, StackTraceKind.Go)
        check("E17 its message", panicOnly.message, "boom")
        check("E18 it has no frames", panicOnly.frames, emptyList<StackFrame>())
    }

    // =================================================== F. honesty: tie and prose
    run {
        val tie = PiStackTrace.parse(
            "Error: boom\n" +
                "    at foo (A.kt:1)\n" +
                "    at bar (B.js:1:1)",
        )
        check("F1 a 50/50 Java/JS mix is not guessed", failureOf(tie), StackTraceFailure.NotRecognized)

        check(
            "F2 ordinary prose is not a trace",
            failureOf(PiStackTrace.parse("The build failed.\nSee the log above.")),
            StackTraceFailure.NotRecognized,
        )
        check(
            "F3 a line that merely contains `at` is not a frame",
            failureOf(PiStackTrace.parse("We met at noon (in the park) [1]")),
            StackTraceFailure.NotRecognized,
        )
        check(
            "F4 a panic sentence mid-block is not Go",
            failureOf(PiStackTrace.parse("Note this: panic: not really a Go dump")),
            StackTraceFailure.NotRecognized,
        )
        check(
            "F5 a Java frame with an unknown location is not a file",
            PiStackTrace.parse("java.lang.RuntimeException: x\n\tat a.B.c(Native Method)")
                .let { parsed(it).frames[0].file },
            null,
        )

        val javaNativeOnly = parsed(
            PiStackTrace.parse("java.lang.RuntimeException: x\n\tat java.lang.Object.wait(Native Method)"),
        )
        check("F6 the java.* header decides an unknown-location stack", javaNativeOnly.kind, StackTraceKind.JavaKotlin)

        val jsAnonymousOnly = parsed(
            PiStackTrace.parse("TypeError: x\n    at <anonymous>"),
        )
        check("F7 a V8-only location decides JS", jsAnonymousOnly.kind, StackTraceKind.JavaScript)
        check("F8 its header type", jsAnonymousOnly.exceptionType, "TypeError")
    }

    // =================================================== G. bounds and fallbacks
    run {
        check("G1 streaming stays source", failureOf(PiStackTrace.parse(JAVA_TRACE, settled = false)), StackTraceFailure.NotSettled)
        check("G2 blank is empty", failureOf(PiStackTrace.parse("  \n ")), StackTraceFailure.Empty)
        check("G3 empty is empty", failureOf(PiStackTrace.parse("")), StackTraceFailure.Empty)

        val small = "java.lang.RuntimeException: x\n\tat a.B.c(C.kt:1)"
        check(
            "G4 exactly maxChars is allowed",
            PiStackTrace.parse(small, limits = StackTraceLimits(maxChars = small.length)) is StackTraceResult.Parsed,
            true,
        )
        check(
            "G5 one character over maxChars falls back",
            failureOf(PiStackTrace.parse(small, limits = StackTraceLimits(maxChars = small.length - 1))),
            StackTraceFailure.TooLarge,
        )

        val truncated = parsed(
            PiStackTrace.parse(
                "java.lang.RuntimeException: x\n" +
                    "\tat a.B.one(A.kt:1)\n" +
                    "\tat a.B.two(A.kt:2)\n" +
                    "\tat a.B.three(A.kt:3)",
                limits = StackTraceLimits(maxFrames = 1),
            ),
        )
        check("G6 frames are truncated, not dropped", truncated.frames.size, 1)
        check("G7 truncation is reported", truncated.framesTruncated, true)
        check("G8 the exception survives truncation", truncated.exceptionType, "java.lang.RuntimeException")

        check("G9 the default char bound is 256 KiB", PiStackTrace.DEFAULT_LIMITS.maxChars, 256 * 1024)
        check("G10 the default frame bound is 200", PiStackTrace.DEFAULT_LIMITS.maxFrames, 200)
    }

    // =================================================== H. Caused by 链：外层报异常，末帧是最里层
    // 为什么必须钉：链式异常既可以显示最外层的类型/消息，也可以显示最里层的，两种都"看
    // 起来对"。代码的选择是**第一个异常头**（最外层）配**文档顺序**的帧（于是最里层原因
    // 的帧是最后一条）。选反了不会报错，只会把根因的消息藏起来、或把包装层的消息当根因。
    run {
        val chain = parsed(
            PiStackTrace.parse(
                "java.lang.RuntimeException: outer\n" +
                    "\tat a.B.c(B.kt:1)\n" +
                    "Caused by: java.io.IOException: inner\n" +
                    "\tat a.D.e(D.kt:2)\n" +
                    "\t... 5 more",
            ),
        )
        check("H1 链的类型是最外层那个", chain.exceptionType, "java.lang.RuntimeException")
        check("H2 消息也取最外层（Caused by 的消息不覆盖它）", chain.message, "outer")
        check("H3 内层帧也收进来（文档顺序）", chain.frames.size, 2)
        check("H4 第一条帧属于最外层异常", chain.frames[0].file, "B.kt")
        check("H5 最后一条帧属于最里层原因", chain.frames.last().file, "D.kt")
        check("H6 `... 5 more` 不是帧", chain.frames.none { it.raw.contains("more") }, true)

        val suppressed = parsed(
            PiStackTrace.parse(
                "java.lang.RuntimeException: x\n" +
                    "\tat a.B.c(B.kt:1)\n" +
                    "\tSuppressed: java.io.IOException: y\n" +
                    "\t\tat a.D.e(D.kt:2)",
            ),
        )
        check("H7 Suppressed 段的帧也按文档顺序收进来", suppressed.frames.size, 2)
        check("H8 Suppressed 不替换主异常", suppressed.exceptionType, "java.lang.RuntimeException")
    }

    // =================================================== I. 协程帧（kotlinx.coroutines）
    // 为什么必须钉：协程轨迹的帧是普通 `at …(File.kt:42)`，`kotlinx.` 前缀并不在
    // `isJavaNamespace` 里（它只认 `kotlin.`），所以方言只能靠帧本身认出来；而挂起函数的
    // 类名带 `$`，截断它会得到一个不存在的类名。这两件事错了都不会抛异常。
    run {
        val coroutine = parsed(
            PiStackTrace.parse(
                "kotlinx.coroutines.JobCancellationException: Job was cancelled\n" +
                    "\tat kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)\n" +
                    "\tat kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)\n" +
                    "\tat app.pi.ui.chat.ChatViewModel\$send\$1.invokeSuspend(ChatViewModel.kt:42)",
            ),
        )
        check("I1 协程异常也是 Java/Kotlin", coroutine.kind, StackTraceKind.JavaKotlin)
        check("I2 kotlinx 的类型逐字保留", coroutine.exceptionType, "kotlinx.coroutines.JobCancellationException")
        check("I3 协程帧的函数名", coroutine.frames[0].function, "kotlinx.coroutines.DispatchedTask.run")
        check("I4 协程帧的文件", coroutine.frames[0].file, "DispatchedTask.kt")
        check("I5 挂起函数的 `$` 类名不被截断", coroutine.frames[2].function, "app.pi.ui.chat.ChatViewModel\$send\$1.invokeSuspend")
        check("I6 三帧都在", coroutine.frames.size, 3)
    }

    println(if (failures == 0) "\nharness: OK ($checks checks)" else "\nharness: FAILED ($failures of $checks)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
