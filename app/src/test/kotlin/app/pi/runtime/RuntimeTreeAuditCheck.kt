package app.pi.runtime

import java.io.File
import kotlin.system.exitProcess

// `RuntimeTreeAudit.kt` 的 bare-JVM harness —— 注册在 `tools/run-app-pure-checks.sh` 里叫
// `runtime-tree-audit`。
//
// 为什么这值得钉：它守的是"解包说成功、树里却少了 guest 起不来的东西"这一类故障的唯一
// 判据，以及 usrmerge 链接的唯一修复动作。两者错都不会抛异常：
//
//  - [RuntimeTreeAudit.missing] 少判一条 ⇒ 残树被当成完整树，之后每次启动都走快路径，
//    proot 只留下两句 `'/bin/bash' not found`，用户除了「重建运行时」无路可走（真机报告
//    2026-11-03 就是这个形状）；
//  - [RuntimeTreeAudit.repairUsrMergeLinks] 多删一个真目录 ⇒ 删的是用户自己的文件。所以
//    "那里是真实目录时绝不删除、只记录"这条在下面被当成断言，连同"第二次调用什么都不做"。
//
// 这里真的建目录和链接（`Files.createSymbolicLink`），因为嫌疑对象正是"链接建不出来时
// 树上剩下什么形状"；不真建就测不到悬空链接与真目录这两条分支。

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

/** 一个临时 rootfs，`usr/bin/{bash,sh,env}` 与 `opt/node/bin/node` 都是真文件。 */
private fun completeRootfs(tag: String): File {
    val root = File.createTempFile("pi-rootfs-$tag", "").let { file ->
        file.delete()
        file
    }
    root.mkdirs()
    listOf("usr/bin/bash", "usr/bin/sh", "usr/bin/dash", "usr/bin/env", "opt/node/bin/node").forEach { relative ->
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText("x")
        // The gate asks for "really executable", not "exists": a mode that lost its exec
        // bit is one of the shapes a failed extraction leaves behind.
        file.setExecutable(true, false)
    }
    return root
}

private fun link(root: File, name: String, target: String) {
    val path = File(root, name).toPath()
    java.nio.file.Files.isSymbolicLink(path).let { if (it) java.nio.file.Files.delete(path) }
    java.nio.file.Files.createSymbolicLink(path, java.nio.file.Path.of(target))
}

fun main() {
    // 1. 完整树：四条关键路径都在（`bin` 是链接时也一样），一个都不许报。
    run {
        val root = completeRootfs("ok")
        link(root, "bin", "usr/bin")
        link(root, "sbin", "usr/sbin")
        check("complete tree reports nothing missing", RuntimeTreeAudit.missing(root), emptyList<String>())
        val repair = RuntimeTreeAudit.repairUsrMergeLinks(root)
        check("complete tree repairs nothing", repair.repaired, emptyList<String>())
        check("complete tree leaves nothing alone", repair.leftAlone, emptyList<String>())
        check("complete tree fails nothing", repair.failed, emptyList<String>())
        root.deleteRecursively()
    }

    // 2. 真机形状：`usr/bin/bash` 在，但 `bin` 链接根本不存在 ⇒ /bin/bash 与 /bin/sh 都缺。
    run {
        val root = completeRootfs("nolink")
        check(
            "missing /bin link is reported as bin/bash and bin/sh",
            RuntimeTreeAudit.missing(root),
            listOf("bin/bash", "bin/sh"),
        )
        val repair = RuntimeTreeAudit.repairUsrMergeLinks(root)
        check("missing /bin link is repaired", repair.repaired, listOf("bin"))
        check("repaired link resolves", File(root, "bin/bash").isFile, true)
        check("after repair nothing is missing", RuntimeTreeAudit.missing(root), emptyList<String>())
        // 幂等：第二遍不写任何东西（快路径不会每轮重建链接）。
        check("repair is idempotent", RuntimeTreeAudit.repairUsrMergeLinks(root).repaired, emptyList<String>())
        root.deleteRecursively()
    }

    // 3. 悬空链接（指向一个不存在的目标）：同一个判据，同一处修复。
    run {
        val root = completeRootfs("dangling")
        link(root, "bin", "usr/bin-old")
        check("dangling /bin is missing bin/bash", RuntimeTreeAudit.missing(root).contains("bin/bash"), true)
        val repair = RuntimeTreeAudit.repairUsrMergeLinks(root)
        check("dangling /bin is relinked", repair.repaired, listOf("bin"))
        check(
            "relinked /bin points at usr/bin again",
            runCatching { java.nio.file.Files.readSymbolicLink(File(root, "bin").toPath()).toString() }.getOrNull(),
            "usr/bin",
        )
        check("relinked tree has no missing paths", RuntimeTreeAudit.missing(root), emptyList<String>())
        root.deleteRecursively()
    }

    // 4. `bin` 是**真实目录**（上一版把链接解引用成了副本）：绝不删除，只记录；树下仍然可用。
    run {
        val root = completeRootfs("realdir")
        File(root, "bin").mkdirs()
        File(root, "bin/bash").writeText("x")
        File(root, "bin/sh").writeText("x")
        check("a real /bin directory satisfies the audit", RuntimeTreeAudit.missing(root), emptyList<String>())
        val repair = RuntimeTreeAudit.repairUsrMergeLinks(root)
        check("a real /bin directory is left alone", repair.leftAlone, listOf("bin"))
        check("a real /bin directory is not a failure", repair.failed, emptyList<String>())
        check("a real /bin directory keeps its contents", File(root, "bin/bash").isFile, true)
        check("a real /bin directory is not a link", java.nio.file.Files.isSymbolicLink(File(root, "bin").toPath()), false)
        root.deleteRecursively()
    }

    // 5. 目标目录不在（载荷没解出来）：链接不动，也不假装失败——缺的是载荷，不是链接。
    run {
        val root = completeRootfs("notarget")
        File(root, "usr/bin").deleteRecursively()
        val repair = RuntimeTreeAudit.repairUsrMergeLinks(root)
        check("no target directory means no repair", repair.repaired, emptyList<String>())
        check("no target directory is not a link failure", repair.failed, emptyList<String>())
        check("no target directory still reports every critical path", RuntimeTreeAudit.missing(root).size, 3)
        root.deleteRecursively()
    }

    // 6. 引擎本体也算关键路径：`opt/node` 没解出来同样要判成"树不完整"。
    run {
        val root = completeRootfs("nonode")
        link(root, "bin", "usr/bin")
        File(root, "opt/node/bin/node").delete()
        check(
            "a missing node binary is reported",
            RuntimeTreeAudit.missing(root),
            listOf("opt/node/bin/node"),
        )
        root.deleteRecursively()
    }

    // 7. 失败页正文：缺哪个、`/bin` 是什么、下一步是什么，全都要能被截图带走。
    run {
        val root = completeRootfs("failure")
        File(root, "usr/bin/bash").delete()
        val missing = RuntimeTreeAudit.missing(root)
        val text = RuntimeTreeAudit.failure(root, missing, RuntimeTreeAudit.Repair())
        check("failure names bin/bash", text.contains("/bin/bash"), true)
        check("failure names bin/sh", text.contains("/bin/sh"), true)
        check("failure describes /bin", text.contains("/bin："), true)
        check("failure says it is not a permission problem", text.contains("不是权限问题"), true)
        check("failure offers the rebuild", text.contains("重建运行时"), true)
        check("failure names the repair that failed", text.contains("没能补回的链接"), false)
        check(
            "failure names the repaired link when one was rebuilt",
            RuntimeTreeAudit.failure(root, missing, RuntimeTreeAudit.Repair(repaired = listOf("bin")))
                .contains("补回 usrmerge 链接：/bin"),
            true,
        )
        root.deleteRecursively()
    }

    // 8. describe：它的每一行都是用户报告里唯一能证明的东西，所以逐条钉。
    run {
        val root = completeRootfs("describe")
        link(root, "bin", "usr/bin")
        val text = RuntimeTreeAudit.describe(root, available = 12345L)
        check("describe names the rootfs", text.contains("rootfs = ${root.path}"), true)
        check("describe reports /bin as a link", text.contains("/bin：符号链接 -> usr/bin（目标存在）"), true)
        check("describe lists the critical paths", text.contains("/bin/bash：普通文件"), true)
        check("describe reports free space", text.contains("可用空间：12345 字节"), true)
        check("describe lists the root's children", text.contains("根目录（"), true)

        // 空树：根目录 0 项，且不伪装成"读到了什么"。
        val empty = File.createTempFile("pi-rootfs-empty", "").let { it.delete(); it }
        empty.mkdirs()
        val emptyText = RuntimeTreeAudit.describe(empty, available = 0L)
        check("describe says an empty tree is empty", emptyText.contains("根目录（0 项）"), true)
        check("describe calls a missing /bin missing", emptyText.contains("/bin：不存在"), true)
        empty.deleteRecursively()
        root.deleteRecursively()
    }

    // 9. 顶层条目上限：一个装满 40 项的根目录不许把失败页撑爆，但必须说清截掉了多少。
    run {
        val root = completeRootfs("many")
        (1..40).forEach { File(root, "dir-$it").mkdirs() }
        val total = root.list()!!.size
        val text = RuntimeTreeAudit.describe(root, available = 0L)
        check(
            "describe caps the listing",
            text.contains("还有 ${total - RuntimeTreeAudit.MAX_ROOT_ENTRIES} 项"),
            true,
        )
        root.deleteRecursively()
    }

    // 10. 闸门（`failures`）：四种"存在但用不了"必须各说各的，因为这四种在设备上全都
    // 只表现为 proot 的一句 `not found`，而修法分别是重解载荷 / 修权限 / 修链接 / 拒绝。
    run {
        val root = completeRootfs("gate")
        link(root, "bin", "usr/bin")
        val reqs = RuntimeTreeAudit.requirementsFor("ubuntu-base")
        check("a complete ubuntu tree passes the gate", RuntimeTreeAudit.failures(root, reqs), emptyList<String>())

        // 不可执行：`exists()` 答 true，闸门必须答 false。
        File(root, "usr/bin/bash").setExecutable(false, false)
        check(
            "a non-executable bash fails the gate",
            RuntimeTreeAudit.failures(root, reqs).any { it.contains("/bin/bash") && it.contains("没有可执行权限") },
            true,
        )
        File(root, "usr/bin/bash").setExecutable(true, false)

        // 目录冒充可执行文件。
        File(root, "usr/bin/dash").delete()
        File(root, "usr/bin/dash").mkdirs()
        check(
            "a directory where dash should be fails the gate",
            RuntimeTreeAudit.failures(root, reqs).any { it.contains("/usr/bin/dash") && it.contains("是目录") },
            true,
        )
        File(root, "usr/bin/dash").delete()
        File(root, "usr/bin/dash").writeText("x")
        File(root, "usr/bin/dash").setExecutable(true, false)

        // 0 字节：写了一半的载荷就是这个形状。
        File(root, "usr/bin/env").writeText("")
        check(
            "an empty env fails the gate",
            RuntimeTreeAudit.failures(root, reqs).any { it.contains("/usr/bin/env") && it.contains("0 字节") },
            true,
        )
        File(root, "usr/bin/env").writeText("x")
        File(root, "usr/bin/env").setExecutable(true, false)
        root.deleteRecursively()
    }

    // 11. guest 绝对链接（`/usr/local/bin/node -> /opt/node/bin/node`）必须按 **guest 语义**
    // 解析：宿主上 `/opt/node/...` 不存在，按宿主语义判会让每一个装好的设备都报"目标不存在"。
    // 这条是误报防线，不是功能。
    run {
        val root = completeRootfs("absolute")
        link(root, "bin", "usr/bin")
        File(root, "usr/local/bin").mkdirs()
        link(root, "usr/local/bin/node", "/opt/node/bin/node")
        check(
            "a guest-absolute link resolves inside the rootfs",
            RuntimeTreeAudit.failures(root, RuntimeTreeAudit.requirementsFor("node")),
            emptyList<String>(),
        )
        // 目标真没了才报。
        File(root, "opt/node/bin/node").delete()
        check(
            "a guest-absolute link with no target fails",
            RuntimeTreeAudit.failures(root, RuntimeTreeAudit.requirementsFor("node"))
                .any { it.contains("/usr/local/bin/node") && it.contains("目标不存在") },
            true,
        )
        root.deleteRecursively()
    }

    // 12. 相对链接用 `..` 爬出 rootfs：那是载荷能让我们执行树外文件的一条路，必须被判出来。
    run {
        val root = completeRootfs("escape")
        File(root, "bin").mkdirs()
        File(root, "bin/bash").delete()
        // The target must *exist* for this to be an escape (otherwise it is reported as
        // "target missing", which is also true but less specific).
        val outside = File(root.parentFile.parentFile, "outside-bash")
        outside.writeText("x")
        outside.setExecutable(true, false)
        java.nio.file.Files.createSymbolicLink(
            File(root, "bin/bash").toPath(),
            java.nio.file.Path.of("../../../outside-bash"),
        )
        val problems = RuntimeTreeAudit.failures(root, RuntimeTreeAudit.requirementsFor("ubuntu-base"))
        check(
            "a relative link climbing out of the rootfs is reported",
            problems.any { it.contains("/bin/bash") && it.contains("rootfs 之外") },
            true,
        )
        // 悬空上级链接同样要点名，否则"缺 bash"和"bin 断了"在报告里没有区别。
        val dangling = completeRootfs("ancestor")
        link(dangling, "bin", "usr/bin-typo")
        check(
            "a dangling ancestor link is named",
            RuntimeTreeAudit.failures(dangling, RuntimeTreeAudit.requirementsFor("ubuntu-base"))
                .any { it.contains("悬空链接") },
            true,
        )
        dangling.deleteRecursively()
        outside.delete()
        root.deleteRecursively()
    }

    // 13. 载荷失败页：必须说清"哪个载荷、缺什么、digest 没写、下次会重解"——用户不用点
    // 重建，我们也不用再猜。
    run {
        val root = completeRootfs("payload")
        val text = RuntimeTreeAudit.payloadFailure(
            "ubuntu-base",
            root,
            listOf("/bin/bash：不存在"),
        )
        check("payload failure names the payload", text.contains("载荷 ubuntu-base"), true)
        check("payload failure quotes the problem", text.contains("/bin/bash：不存在"), true)
        check("payload failure promises the re-extract", text.contains("digest **没有**写入"), true)
        check("payload failure names the next launch", text.contains("下次启动会重新解它"), true)
        root.deleteRecursively()
    }

    if (failures != 0) {
        println("harness: FAILED ($failures)")
        exitProcess(1)
    }
    println("harness: OK")
}
