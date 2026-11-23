package app.pi.packages

import app.pi.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 项目信任：宿主 `project.trustState` 的读数，以及「该怎么对用户说」的全部规则。
 *
 * 判定本身是 SDK 的（`resolveProjectTrusted` + `ProjectTrustStore`，宿主逐条照抄顺序）：
 *  1. 本次会话的表态（`session`）；
 *  2. 信任库里**离项目最近**的一条（`store`）——信任一个父目录就覆盖整棵树，所以这条可能是「继承」来的；
 *  3. 项目里没有任何需要信任才加载的东西（`nothing-to-trust`）→ 信任，无所谓；
 *  4. 设置 `defaultProjectTrust` = `always` / `never`（`default`）；
 *  5. `ask`：先不信任，等用户表态（`ask`）。
 * 这里只负责把它们说成人话、并列出每个状态下**真能做**的动作；选项与官方弹窗
 * （`getProjectTrustOptions(includeSessionOnly)`）一一对应：信任 / 信任父目录 / 仅本次信任 / 不信任 / 仅本次不信任。
 */
object PiProjectTrust {

    enum class Source(val key: String) {
        Session("session"),
        Store("store"),
        NothingToTrust("nothing-to-trust"),
        Default("default"),
        Ask("ask"),
        Unknown(""),
        ;

        companion object {
            fun of(raw: String?): Source = entries.firstOrNull { it.key == raw && raw.isNotEmpty() } ?: Unknown
        }
    }

    /** 信任库里起作用的那一条。[inherited] = 它记的不是这个目录自己，而是某个上层目录。 */
    data class Entry(val path: String, val decision: Boolean, val inherited: Boolean)

    data class State(
        val cwd: String,
        val trusted: Boolean,
        /** 项目里有需要信任的东西、又没人表态、默认策略也是 ask：该弹窗问了。 */
        val undecided: Boolean,
        val source: Source,
        val entry: Entry?,
        /** 项目里有没有「需要信任才加载」的东西（`.pi` 下的设置 / 扩展 / 技能 / 提示 / 主题 / 系统提示，或 `.agents/skills`）。 */
        val needsTrust: Boolean,
        /** 全局设置 `defaultProjectTrust`：`ask` / `always` / `never`。 */
        val defaultProjectTrust: String,
        /** 上一级目录；根目录没有。 */
        val parent: String?,
    )

    /** `remember` 取值，与宿主 `controller.setProjectTrust` 一一对应。 */
    enum class Remember(val key: String) {
        Folder("folder"),
        Parent("parent"),
        Session("session"),
        Reset("reset"),
        ResetInherited("reset-inherited"),
    }

    data class Option(val remember: Remember, val trusted: Boolean, val label: String, val hint: String? = null) {
        /** 危险的方向（不信任之外的「信任」类）要让用户看清：信任 = 允许执行项目里的扩展代码。 */
        val grants: Boolean get() = trusted
    }

    fun parse(root: JsonObject): State {
        val entry = (root["entry"] as? JsonObject)?.let {
            val decision = (it["decision"] as? JsonPrimitive)?.booleanOrNull
            val path = str(it["path"])
            if (decision == null || path == null) null else Entry(path, decision, bool(it["inherited"]))
        }
        return State(
            cwd = str(root["cwd"]).orEmpty(),
            trusted = bool(root["trusted"]),
            undecided = bool(root["undecided"]),
            source = Source.of(str(root["source"])),
            entry = entry,
            needsTrust = bool(root["needsTrust"]),
            defaultProjectTrust = str(root["defaultProjectTrust"]) ?: "ask",
            parent = str(root["parent"]),
        )
    }

    /** 一句话结论（卡片标题）。 */
    fun headline(s: State): String = when (s.source) {
        Source.Session -> if (s.trusted) tr("本次会话信任", "Trusted for This Session") else tr("本次会话不信任", "Untrusted for This Session")
        Source.Store -> when {
            s.trusted && s.entry?.inherited == true -> tr("已信任（继承自上层目录）", "Trusted (Inherited from Parent)")
            s.trusted -> tr("已信任", "Trusted")
            s.entry?.inherited == true -> tr("不信任（继承自上层目录）", "Untrusted (Inherited from Parent)")
            else -> tr("不信任", "Untrusted")
        }
        Source.NothingToTrust -> tr("没有需要信任的内容", "No Trust Required")
        Source.Default -> if (s.trusted) tr("已信任（默认策略：总是信任）", "Trusted (Default: Always Trust)") else tr("不信任（默认策略：从不信任）", "Untrusted (Default: Never Trust)")
        Source.Ask -> tr("还没表态", "Decision Needed")
        Source.Unknown -> if (s.trusted) tr("已信任", "Trusted") else tr("未信任", "Untrusted")
    }

    /** 这个结论的**后果**：信任 / 不信任各意味着 pi 做什么。官方弹窗的原意。 */
    fun explanation(s: State): String = when (s.source) {
        Source.Ask -> tr(
            "项目里有 .pi 的设置或资源。信任后 pi 才会加载它们、补装项目声明的包、执行项目里的扩展代码；现在先按不信任处理，项目里的这些都没有加载。",
            "This project contains .pi settings or resources. Trusting allows pi to load them, install declared project packages, and execute project extension code. Currently treated as untrusted.",
        )
        Source.NothingToTrust -> tr(
            "项目里没有 .pi 设置、扩展、技能、提示、主题，也没有 .agents/skills，信任与否目前没有影响。想预先表态也可以。",
            "No .pi settings, extensions, skills, prompts, themes, or .agents/skills were found in this project, so trust currently has no effect. You may still pre-configure trust.",
        )
        Source.Session -> tr(
            "只对这一次会话有效，没有写进信任库；重启引擎后回到信任库或默认策略的结论。",
            "Applies to this session only and is not saved to the trust store. Reverts after engine restart.",
        )
        Source.Default -> tr(
            "没有为这个项目单独表态，按全局默认信任策略（defaultProjectTrust）走；点下方「默认策略」可即时调整。",
            "No project-specific decision saved; following the global default project trust policy (tap Default Policy below to change).",
        )
        else -> if (s.trusted) {
            tr(
                "pi 会加载项目里的 .pi 设置与资源、补装项目声明的包，并执行项目里的扩展代码。",
                "Pi will load project .pi settings and resources, install declared packages, and execute project extensions.",
            )
        } else {
            tr(
                "项目里的 .pi 设置、扩展、技能、提示、主题、MCP 配置都不会加载，也不会补装项目的包。",
                "Project .pi settings, extensions, skills, prompts, themes, MCP configs, and packages will not be loaded.",
            )
        }
    }

    /** 记录在哪儿的一行说明（卡片副标题）；没有记录就是 null。 */
    fun recordNote(s: State): String? {
        val e = s.entry ?: return null
        return if (e.inherited) tr("记录在上层目录 ${e.path}", "Recorded on parent directory ${e.path}") else tr("记录在本项目", "Recorded on this project")
    }

    /**
     * 官方弹窗的选项（项目有需要信任的东西、又没表态时问用户）。
     * 顺序与官方一致；根目录没有「信任父目录」。
     */
    fun promptOptions(s: State): List<Option> = buildList {
        add(Option(Remember.Folder, true, tr("信任", "Trust"), tr("记在本项目", "Remember for this project")))
        s.parent?.let { add(Option(Remember.Parent, true, tr("信任上层目录", "Trust Parent Directory"), tr("记在 $it，它下面的项目都信任", "Remember on $it; trusts all projects underneath"))) }
        add(Option(Remember.Session, true, tr("仅本次信任", "Trust for Session"), tr("不写信任库", "Do not save to trust store")))
        add(Option(Remember.Folder, false, tr("不信任", "Don't Trust"), tr("记在本项目", "Remember for this project")))
        add(Option(Remember.Session, false, tr("仅本次不信任", "Don't Trust for Session"), tr("不写信任库", "Do not save to trust store")))
    }

    /**
     * 已经有结论之后，页面上还能改成什么：官方选项里去掉「当前就是这样」的那一个，
     * 再加上清除记录（回到没表态）——有记录的才给。
     */
    fun adjustments(s: State): List<Option> {
        val current = when (s.source) {
            Source.Session -> Remember.Session to s.trusted
            Source.Store -> {
                val e = s.entry
                when {
                    e == null -> null
                    e.inherited -> Remember.Parent to e.decision
                    else -> Remember.Folder to e.decision
                }
            }
            else -> null
        }
        val base = promptOptions(s).filterNot { current != null && it.remember == current.first && it.trusted == current.second }
        val entry = s.entry
        val clear = when {
            entry == null -> null
            entry.inherited -> Option(
                Remember.ResetInherited,
                false,
                tr("清除上层目录的记录", "Clear Parent Directory Decision"),
                tr("清除 ${entry.path} 的记录，它下面所有没单独表态的项目都会受影响", "Clears ${entry.path}; affects all projects underneath without their own decision"),
            )
            else -> Option(Remember.Reset, false, tr("清除本项目的记录", "Clear Project Decision"), tr("回到没表态", "Reset to undecided"))
        }
        return base + listOfNotNull(clear)
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun bool(e: JsonElement?): Boolean = (e as? JsonPrimitive)?.booleanOrNull == true
}
