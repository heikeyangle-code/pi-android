package app.pi.runtime.os

// 裸 JVM harness：Termux bootstrap 的解包（第一阶段）。
// 默认用内存里造的小 zip 验证规则（符号链接←格式、可执行位、zip-slip、原子改名、幂等）；
// 设了 PI_TERMUX_BOOTSTRAP_ZIP 就再对**真实**的官方 zip 跑一遍（条目/链接数、bash 可执行、链接解析）。

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private var failures = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $name") else { failures++; println("FAIL $name $detail") }
}

private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { z ->
        for ((n, c) in entries) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
    }
    return out.toByteArray()
}

fun main() {
    val tmp = Files.createTempDirectory("tb").toFile()

    val small = zipOf(
        "bin/" to "",
        "bin/bash" to "elf",
        "lib/libx.so" to "so",
        "libexec/tool" to "t",
        "lib/apt/methods/https" to "m",
        "etc/profile" to "p",
        "SYMLINKS.txt" to "/data/data/com.termux/files/usr/bin/bash←./bin/sh\n../lib/libx.so←./lib/liby.so\n",
    )
    val p1 = File(tmp, "a/usr")
    val r = TermuxBootstrap.extract(ByteArrayInputStream(small), p1)
    check("small: counts", r?.files == 5 && r.symlinks == 2, "$r")
    check("small: bin/bash executable", File(p1, "bin/bash").canExecute())
    check("small: libexec executable", File(p1, "libexec/tool").canExecute())
    check("small: apt method executable", File(p1, "lib/apt/methods/https").canExecute())
    check("small: etc not executable", !File(p1, "etc/profile").canExecute())
    check("small: lib .so not executable", !File(p1, "lib/libx.so").canExecute())
    check("small: abs symlink target kept verbatim", Files.readSymbolicLink(File(p1, "bin/sh").toPath()).toString() == "/data/data/com.termux/files/usr/bin/bash")
    check("small: relative symlink resolves", Files.isSymbolicLink(File(p1, "lib/liby.so").toPath()) && Files.readSymbolicLink(File(p1, "lib/liby.so").toPath()).toString() == "../lib/libx.so")
    check("small: staging gone", !File(p1.parentFile, "usr.staging").exists())
    check("small: second extract is a no-op", TermuxBootstrap.extract(ByteArrayInputStream(small), p1) == null)

    val evil = zipOf("../../escape" to "x")
    val p2 = File(tmp, "b/usr")
    val threw = runCatching { TermuxBootstrap.extract(ByteArrayInputStream(evil), p2) }.isFailure
    check("zip-slip rejected", threw)
    check("zip-slip leaves no prefix", !p2.exists())
    check("zip-slip wrote nothing outside", !File(tmp, "escape").exists() && !File(tmp.parentFile, "escape").exists())

    val badLink = zipOf("SYMLINKS.txt" to "x←./../../out\n")
    check("symlink escape rejected", runCatching { TermuxBootstrap.extract(ByteArrayInputStream(badLink), File(tmp, "c/usr")) }.isFailure)

    val real = System.getenv("PI_TERMUX_BOOTSTRAP_ZIP")
    if (real != null) {
        val p3 = File(tmp, "real/usr")
        val rr = File(real).inputStream().use { TermuxBootstrap.extract(it, p3) }
        println("real: $rr")
        check("real: has files and symlinks", rr != null && rr.files > 1000 && rr.symlinks > 500, "$rr")
        check("real: bash executable", File(p3, "bin/bash").canExecute())
        check("real: apt executable", File(p3, "bin/apt").canExecute())
        check("real: libtermux-exec present", File(p3, "lib/libtermux-exec-ld-preload.so").isFile)
        check("real: etc/apt/sources.list present", File(p3, "etc/apt/sources.list").isFile)
        check("real: second stage script present", File(p3, "etc/termux/termux-bootstrap/second-stage/termux-bootstrap-second-stage.sh").isFile)
        check("real: SYMLINKS.txt not left as a file", !File(p3, "SYMLINKS.txt").exists())
    } else {
        println("SKIP real zip (set PI_TERMUX_BOOTSTRAP_ZIP)")
    }
    tmp.deleteRecursively()
    if (failures > 0) { println("$failures FAILED"); System.exit(1) }
    println("ok")
}
