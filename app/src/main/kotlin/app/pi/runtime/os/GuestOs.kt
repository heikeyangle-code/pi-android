package app.pi.runtime.os

/**
 * 可选的「系统」：引擎（sdk-host）和终端跑在哪一套用户态里。
 *
 * - [Ubuntu]：默认。随包的 Ubuntu base + glibc Node，pi 的全部载荷都在这里。
 * - [Termux]：官方 Termux bootstrap（bionic）。官方源、`pkg` 原样可用。
 *
 * **两个系统共享同一条数据平面**：工作区（`/workspace/pi/workspaces/<名>`）、pi 的目录（`/root/.pi`，
 * 含会话、凭证、扩展、设置）、引擎代码（`/opt/pi`）。它们真正的家在 Ubuntu 的 rootfs 里，Termux 用 bind
 * 把同样的 guest 路径接进去——所以切换系统时，会话文件、cwd、信任键、模型配置一个字节都不变，
 * 对话在新系统里原地续上。分开的只有「系统自己的东西」：各自的软件包、各自的 HOME、各自的 shell 环境。
 */
enum class GuestOs(val id: String, val label: String) {
    Ubuntu("ubuntu", "Ubuntu"),
    Termux("termux", "Termux");

    companion object {
        fun of(id: String?): GuestOs = entries.firstOrNull { it.id == id } ?: Ubuntu
    }
}
