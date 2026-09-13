package app.pi.rpc

// A bare-JVM harness for `extension_error`'s attribution, which the transcript
// row and the snackbar both build through `ExtensionErrorText.kt`. Android-free
// on purpose: the file under test imports nothing at all, so this compiles and
// runs with the Kotlin stdlib alone. Registered in `tools/run-app-pure-checks.sh`
// as `extension-error-text`.
//
// What it pins, and why each one is worth pinning:
//
//  1. **A path never reaches the user.** pi's `extensionPath` is an absolute file
//     path (`rpc-mode.ts:348-350`), and user-visible copy in this app may not
//     contain one. The assertions below are the guard: whatever the input, the
//     result contains no `/` or `\`.
//  2. **A generic entry file is not an answer.** Most packaged extensions are
//     loaded from `index.ts`, so the raw file name would name every one of them
//     "index.ts". The directory wins there, which is the difference between an
//     attributable failure and a useless one.
//  3. **pi may send nothing.** `extensionPath` is optional on the wire; the
//     headline must still be a sentence rather than "扩展出错：null".

var failures = 0

fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    // ------------------------------------------- the file layout pi discovers
    check(
        "a bare file entry keeps its file name",
        extensionErrorName("/root/.pi/agent/extensions/pi-highlight.ts"),
        "pi-highlight.ts",
    )
    check(
        "an index entry is named by its directory",
        extensionErrorName("/root/.pi/agent/extensions/pi-highlight/index.ts"),
        "pi-highlight",
    )
    check(
        "a project extension is named the same way",
        extensionErrorName("/root/.pi/workspaces/workspace-1/.pi/extensions/gate/index.mjs"),
        "gate",
    )
    check(
        "a directory-only entry keeps its name",
        extensionErrorName("/root/.pi/agent/extensions/my-ext"),
        "my-ext",
    )
    check(
        "a name with no directory at all still resolves",
        extensionErrorName("pi-highlight.ts"),
        "pi-highlight.ts",
    )
    check(
        "a trailing separator is not a name",
        extensionErrorName("/root/.pi/agent/extensions/my-ext/"),
        "my-ext",
    )

    // ------------------------------------------------- what pi actually sent
    check("no path yields no name", extensionErrorName(null), null)
    check("an empty path yields no name", extensionErrorName(""), null)
    check("a blank path yields no name", extensionErrorName("   "), null)
    check("a bare separator yields no name", extensionErrorName("/"), null)

    // -------------------------------------------------------------- headline
    check(
        "a named failure names the extension",
        extensionErrorHeadline("/root/.pi/agent/extensions/pi-highlight/index.ts"),
        "扩展出错：pi-highlight",
    )
    check(
        "an unattributed failure is still a sentence",
        extensionErrorHeadline(null),
        "扩展出错",
    )

    // The one hard rule this file exists to enforce: no path fragments.
    val samples = listOf(
        "/root/.pi/agent/extensions/pi-highlight.ts",
        "/root/.pi/agent/extensions/pi-highlight/index.ts",
        "pi-highlight.ts",
        "/root/.pi/agent/extensions/my-ext/",
        "C:\\extensions\\gate\\index.js",
    )
    for (sample in samples) {
        val name = extensionErrorName(sample)
        check("[$sample] keeps no separator", name?.contains('/') ?: false, false)
        check("[$sample] keeps no backslash", name?.contains('\\') ?: false, false)
    }

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
