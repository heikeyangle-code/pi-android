package app.pi.runtime

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.system.exitProcess

// 用**真实载荷**跑一遍解包器 —— 注册在 `tools/run-app-pure-checks.sh` 里叫
// `runtime-extract-real`，CI 的 pure-checks 作业会先把 `ubuntu-base` 下载到
// `build/downloads/ubuntu-base.tar.gz` 再跑它。
//
// 为什么必须有一条这样的检查：`runtime-payload-state` 只把 `RuntimeProvisioner` 当**源码
// 文本**读（那个文件 import 了 AssetManager/Dispatchers，裸 JVM 编译不了），而 `TarExtractor`
// 的单测用的是手造的小 tar。两者都证明了"逻辑对"，却没有一条证明过**APK 里那份 30 MB 的
// Ubuntu 压缩包经过 app 自己的解包器之后，真的有一个能执行的 `/bin/bash`**。
//
// 2026-11-03 的真机报告就是从这个缺口里出来的：刚安装的设备解包"成功"、`.stamp` 已写，
// proot 却报 `'/bin/bash' not found`。真机上的触发条件（文件系统拒绝 symlink、备份恢复了
// 半棵树）在这里复现不了，但**结果**必须能在这里被判死：
//
//   ① 真实载荷解出来必须有 `bin -> usr/bin`、可执行的 `bin/bash`/`bin/sh`/`usr/bin/env`、
//      `usr/bin/dash`、两条硬链接（`perl5.38.2`、`uncompress`）；
//   ② `RuntimeTreeAudit`（生产代码里那道闸门）必须对真实树答"完整"；
//   ③ 一个**建不出来的符号链接**必须让解包失败并点名条目 —— 这是"静默降级成空目录"那条
//      老回退的反向断言，用注入的 linker 制造，真机上对应 `symlink(2)` 被拒绝。
//
// 载荷不在（本地没跑过 assembler）时打印 SKIPPED 并成功退出：这条检查的价值在 CI，而
// CI 的下载步骤就在它前面；本地手跑不应该因为没下载就红。

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** 载荷位置，按 CI → 本地 assemble → 源码树 的顺序找。 */
private fun payload(): File? {
    // `pi.repo.root` is passed by tools/run-app-pure-checks.sh; the relative spellings are
    // for a hand-run `java -cp` from the repository root.
    val repo = System.getProperty("pi.repo.root")?.takeIf { it.isNotBlank() }?.let(::File)
    val candidates = listOfNotNull(
        System.getenv("PI_UBUNTU_TGZ")?.takeIf { it.isNotBlank() }?.let(::File),
        repo?.resolve("build/downloads/ubuntu-base.tar.gz"),
        repo?.resolve("app/src/main/assets/runtime/ubuntu-base.tgz"),
        File("build/downloads/ubuntu-base.tar.gz"),
        File("app/src/main/assets/runtime/ubuntu-base.tgz"),
    )
    return candidates.firstOrNull { it.isFile }
}

fun main() {
    val archive = payload()
    if (archive == null) {
        println(
            "harness: OK (skipped — no ubuntu-base payload found)\n" +
                "  CI downloads it into build/downloads/ubuntu-base.tar.gz before this step;\n" +
                "  locally: curl -fsSL <url from runtime.lock.json artifacts.ubuntuBase.url> -o build/downloads/ubuntu-base.tar.gz",
        )
        return
    }
    println("payload: ${archive.path} (${archive.length()} bytes)")

    val root = File.createTempFile("pi-real-rootfs", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }
    val started = System.nanoTime()
    val result = archive.inputStream().use { stream ->
        TarExtractor(root).extractGzip(stream)
    }
    val millis = (System.nanoTime() - started) / 1_000_000
    println("extracted: files=${result.files} bytes=${result.bytes} entries=${result.entries} links=${result.links} in ${millis} ms")

    // ① 数量级：一份 Ubuntu base 至少这么多个条目/文件/链接。少了就是"截断但没报错"。
    check("entries >= 6000", result.entries >= 6000, true)
    check("files >= 2500", result.files >= 2500, true)
    check("symlinks >= 150", result.links >= 150, true)
    check("bytes > 90 MiB", result.bytes > 90L * 1024 * 1024, true)

    // ② 能执行的那几个，一个都不能少 —— 这就是那条真机报错的反面。
    val bin = File(root, "bin")
    check("bin is a symlink", Files.isSymbolicLink(bin.toPath()), true)
    check(
        "bin points at usr/bin",
        runCatching { Files.readSymbolicLink(bin.toPath()).toString() }.getOrNull(),
        "usr/bin",
    )
    listOf("bin/bash", "bin/sh", "usr/bin/env", "usr/bin/dash", "usr/bin/bash").forEach { relative ->
        val file = File(root, relative)
        check("$relative is a file", file.isFile, true)
        check("$relative is executable", file.canExecute(), true)
        check("$relative is not empty", file.length() > 0L, true)
    }
    check("usr/bin/bash is the real bash (> 1 MiB)", File(root, "usr/bin/bash").length() > 1L * 1024 * 1024, true)
    check("/etc/passwd names root's shell as bash", File(root, "etc/passwd").readText().contains("/bin/bash"), true)

    // 硬链接（以复制代替）必须真的落成文件：静默跳过正是老代码的第二个洞。
    listOf("usr/bin/perl5.38.2", "usr/bin/uncompress").forEach { relative ->
        val file = File(root, relative)
        check("hardlink copy $relative exists", file.isFile, true)
        check("hardlink copy $relative is executable", file.canExecute(), true)
    }

    // ③ 生产代码里那道闸门必须对真实树答"完整"。它比上面的逐条断言更严格（还查 guest
    // 绝对链接、exec 位、0 字节），所以这条通过才说明"第一次安装会成功"。
    check(
        "the production gate passes the real tree",
        RuntimeTreeAudit.failures(root, RuntimeTreeAudit.requirementsFor("ubuntu-base")),
        emptyList<String>(),
    )
    check(
        "the real tree has no dangling usrmerge link to repair",
        RuntimeTreeAudit.repairUsrMergeLinks(root).repaired,
        emptyList<String>(),
    )

    // ④ "不能失败"那一半：这台设备**拒绝 symlink(2)** 时，解包必须照样给出可用的树 ——
    // 链接变成复制（`/bin`、`/lib` 等按内容铺开），而不是 2026-11-03 之前那样造一个空目录
    // 然后报"成功"。用一个必失败的 linker 制造这个环境：真机上对应 SELinux/文件系统拒绝。
    val fallback = File.createTempFile("pi-real-rootfs-refused", "").let { file ->
        file.delete()
        file.mkdirs()
        file
    }
    val degraded = archive.inputStream().use { stream ->
        TarExtractor(
            fallback,
            symlink = { _, _ -> throw IOException("Operation not permitted") },
        ).extractGzip(stream)
    }
    check("a refused symlink does not abort the extraction", degraded.links, 0L)
    check("every refused link was materialised instead", degraded.degraded >= 150L, true)
    check("bin/bash exists after the fallback", File(fallback, "bin/bash").isFile, true)
    check("bin/bash is executable after the fallback", File(fallback, "bin/bash").canExecute(), true)
    check(
        "bin/sh became a real file, not a link",
        Files.isSymbolicLink(File(fallback, "bin/sh").toPath()),
        false,
    )
    check("bin is a directory now, not a link", Files.isSymbolicLink(File(fallback, "bin").toPath()), false)
    check(
        "the production gate passes the fallback tree",
        RuntimeTreeAudit.failures(fallback, RuntimeTreeAudit.requirementsFor("ubuntu-base")),
        emptyList<String>(),
    )

    fallback.deleteRecursively()
    root.deleteRecursively()

    if (failures != 0) {
        println("harness: FAILED ($failures)")
        exitProcess(1)
    }
    println("harness: OK")
}
