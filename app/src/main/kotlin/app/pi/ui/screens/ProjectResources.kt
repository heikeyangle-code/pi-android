package app.pi.ui.screens

import app.pi.packages.PiResourceDiscovery
import app.pi.rpc.ToolCall
import app.pi.rpc.ToolDiff
import app.pi.rpc.TranscriptItem
import app.pi.runtime.GuestWorkspacePath
import app.pi.ui.blocks.argString
import java.io.File

/**
 * The facts the project screen shows, derived from things that already exist.
 *
 * ## Why this is a file of its own
 *
 * `01-design-spec.md:97` 的硬约束是「不要发明 pi 没有的数据」：凡是工作区上出现的
 * 东西，都必须能说出它来自 pi 的哪条 RPC / 哪个文件。下面每个函数都把「来自哪里」
 * 写在自己的 KDoc 里，并且都是**纯函数**（`File`、`List<TranscriptItem>`，没有
 * Context、没有 Compose）——`05-compose-migration-plan.md` §6.1 的 bare-JVM harness
 * 就是为这类东西准备的，而工作区的数据推导正是最容易悄悄编造数据的地方。
 *
 * ## What is deliberately not here
 *
 * **git 改动**。设计稿（`06-v2-construction-reference.md` 的 phone29/32）画了
 * 「分支 main · 3 个文件有改动」与「这个目录没有未提交的改动」，B3 **没有**做这两个
 * 字段，理由不是懒：
 *
 *  - pi 只从 git 读一个东西——当前分支名——而且只喂它自己的 TUI footer
 *    （`core/footer-data-provider.ts:127`，`modes/interactive/components/footer.ts:117`）。
 *    `modes/rpc/rpc-types.ts:20-74` 的 `RpcCommand` 联合里没有任何 branch / status /
 *    diff / checkpoint 命令，所以 **RPC 通道拿不到**。
 *  - 应用确实能自己跑 `git status`（`packages/GuestCommand.kt` 就是「在 guest 里跑一条
 *    非交互命令」的既有通道，`ui/chat/PiMentionSource.kt:20-45` 用它跑 fd）。但工作区是
 *    `<files>/pi/workspaces/workspace-1`，引擎把它 bind 到 guest 的 `/workspace`
 *    （`PiEngineHost.kt:548-555`）——**它不是 git 仓库**，用户真正的项目可以不在这里。
 *    在它上面跑 `git status` 只会得到「not a git repository」，而把那个失败画成
 *    「没有未提交的改动」就是把一次失败说成一条事实。要真做这一块，先要有一个「哪个
 *    目录是仓库」的产品决定，那不是 B3 的范围。
 *
 * 因此这一屏没有「分支」行、也没有「改动 / 0」行。拿不到就不显示，而不是拿一个恒为空
 * 或恒为错的读数占位。
 */
internal object PiProject {

    /**
     * 当前工作目录在界面上的名字。
     *
     * 与会话列表用**同一套规则**（`screens/SessionsScreen.kt:379-385` 的
     * `groupLabel`），因为两屏说的是同一个目录：本应用自己的工作区叫「工作区」，其余取
     * 末段。规则重复一次而不是抽成公共函数，是因为抽出去要动 `SessionsScreen.kt`，而那
     * 是 B4 的文件（本轮不许碰）。
     *
     * `cwd` 是 pi 在会话头部记下的 **guest** 写法
     * （`/workspace/pi/workspaces/workspace-1`），直接把路径印在界面上既没人能读，也是
     * 全应用唯一会露出内部目录的地方。
     */
    fun workspaceName(cwd: String): String {
        val clean = cwd.trimEnd('/')
        if (clean.isEmpty()) return "工作目录未记录"
        val workspacePrefix = GuestWorkspacePath.GUEST_ROOT + "/" + GuestWorkspacePath.RELATIVE
        if (clean == workspacePrefix) return "工作区"
        return clean.substringAfterLast('/').ifEmpty { clean }
    }

    /**
     * 这次会话碰过的文件，最新一次在前。
     *
     * ## 来源
     *
     * 转录里每个 `ToolCall` 的参数（`rpc/Transcript.kt:64-96`）。路径用
     * `blocks/ToolOutputParse.kt:697-704` 的 `argString` 读——它认的键就是 pi 自己
     * schema 的拼法（`read`/`write` 用 `path`，`edit` 用 `file_path`，见
     * `core/tools/renderers/edit.ts:59-64`），所以这里不新写一套键名猜测。
     *
     * `ToolDiff`（`rpc/Transcript.kt:103-117`）带 `added`/`removed`，按路径并进同一行，
     * 于是「改了多少行」是 pi 解析出来的数字，不是应用数出来的。
     *
     * ## 为什么「读到」也算碰过
     *
     * 因为这一屏回答的是「这次会话在这个目录里干了什么」，而 agent 反复读同一个文件
     * 也是答案的一部分；设计稿的 fixture（`direction-b-v2.html:1301-1305`）里既有
     * `mine` 的行也有 `mine:false` 的行，说明这一列本来就不只是「写过的」。改动量只在
     * pi 给了 diff 时出现（`+N −M`），读操作没有。
     *
     * ## 这不是 pi 的一个字段
     *
     * 这一列是应用**从转录推导**的，所以界面上必须说清来源（「来自这次会话的工具调用」），
     * 不能写成「pi 报告的文件」。同一份数据也解释了一个限制：冷启动时转录是空的
     * （`resumeLast` 默认关，`03-navigation-decision.md`），所以一个刚开的会话本来就该
     * 是空态。
     */
    fun touchedFiles(items: List<TranscriptItem>): List<ProjectFile> {
        val byPath = LinkedHashMap<String, ProjectFile>()
        for (item in items) {
            when (item) {
                is ToolCall -> {
                    val path = argString(item.args, "file_path", "path") ?: continue
                    val action = ProjectFileAction.of(item.toolName) ?: continue
                    // 同一路径保留最新一次（转录有序，后来的覆盖先前的），但已经拿到的
                    // diff 统计不丢——`edit` 与紧随其后的 `ToolDiff` 是两条 item。
                    val previous = byPath[path]
                    byPath[path] = ProjectFile(
                        path = path,
                        action = action,
                        at = maxOf(item.ts, previous?.at ?: 0L),
                        added = previous?.added ?: 0,
                        removed = previous?.removed ?: 0,
                    )
                }

                is ToolDiff -> {
                    if (item.path.isEmpty()) continue
                    val previous = byPath[item.path]
                    byPath[item.path] = ProjectFile(
                        path = item.path,
                        action = previous?.action ?: ProjectFileAction.Edited,
                        at = maxOf(item.ts, previous?.at ?: 0L),
                        added = item.added,
                        removed = item.removed,
                    )
                }

                else -> Unit
            }
        }
        return byPath.values.sortedByDescending { it.at }
    }

    /**
     * 这个目录里的 `.pi` 资源（技能 / 提示词 / 主题）。
     *
     * 与设置里的「扩展包与项目信任」页用**同一个读取器**
     * （`packages/PiResourceDiscovery.kt:74-82`，它复刻的是 pi 自己的收集规则：skills 要
     * `<dir>/<name>/SKILL.md`，prompts/themes 按后缀递归收集，
     * `core/package-manager.ts:645-653`）。这里只读**项目作用域**一个根。
     *
     * ## 为什么只列项目作用域
     *
     * 设计稿那一栏的 aside 就是 `.pi`，讲的是「这个目录的资源」。全局资源在
     * `<agentDir>`（`PiPaths.agentDir`，挂在 guest 的 `/root/.pi/agent`）——那是 pi 自己
     * 的目录，不是这个项目的，混进来会让人以为是项目自带的。要看全局的，设置里有整整
     * 一页。
     *
     * 目录不存在时返回空表，而空表是**真的答案**（这个项目确实还没有 `.pi`），空态文案
     * 由调用方给。
     */
    fun projectResources(projectConfigDir: File): List<PiResourceDiscovery.Found> =
        PiResourceDiscovery.discover(projectConfigDir, PiResourceDiscovery.Found.Scope.Project)
}

/** One file this session touched, as the project screen shows it. */
internal data class ProjectFile(
    /** pi's argument spelling, i.e. the path it was handed — not a host path. */
    val path: String,
    val action: ProjectFileAction,
    /** Wall clock of the newest touch, for ordering. */
    val at: Long,
    /** Lines added, from pi's own diff for this path (`ToolDiff.added`); 0 when it gave none. */
    val added: Int,
    val removed: Int,
) {
    /** pi gave a diff for this path, so a `+N −M` reading is a fact and not a guess. */
    val hasDiff: Boolean get() = added > 0 || removed > 0
}

/**
 * What the session did to a file, in the app's own words.
 *
 * Only `read` / `write` / `edit` get an entry: `grep`, `find` and `ls` take a directory or
 * a pattern rather than a file, so `argString(args, "path")` on them names a *directory*,
 * and listing a directory as a touched file would be wrong. The set matches the tools whose
 * cards the transcript draws as file operations (`blocks/BlockRenderer.kt:99-114`).
 */
internal enum class ProjectFileAction(val label: String) {
    /** `read` — the file went into the model's context. */
    Read("读到"),

    /** `write` — pi wrote the whole file. */
    Wrote("写入"),

    /** `edit` — pi changed part of it; the diff that follows says how much. */
    Edited("编辑"),
    ;

    companion object {
        fun of(toolName: String): ProjectFileAction? = when (toolName) {
            "read" -> Read
            "write" -> Wrote
            "edit" -> Edited
            else -> null
        }
    }
}
