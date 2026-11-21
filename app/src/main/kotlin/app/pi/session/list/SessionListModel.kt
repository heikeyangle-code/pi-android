package app.pi.session.list

/**
 * 会话列表（pi 的 `/resume` 选择器）里**行文字**的规则，纯函数、无 Android 依赖。
 *
 * 对照 SDK `dist/modes/interactive/components/session-selector.js`：
 *  - [formatSessionDate]  ← `formatSessionDate`
 *  - [treePrefix]         ← `SessionList.buildTreePrefix`
 *  - [displayText]        ← `render` 里「名字，否则第一条消息；控制字符换成空格再 trim」
 *  - [rightText]          ← `render` 里的 `rightPart`（`<cwd，仅 all 范围> <条数> <年龄>`）
 *
 * 行的**顺序、树形、过滤、搜索**不在这里：它们由宿主（`commands/sessionlist.mjs`）用官方的
 * `filterAndSortSessions` 与移植的 `buildSessionTree` 算好，经 `session.list` 带过来。
 * 这里只管「一行怎么写出来」，由 `SessionListModelCheck` 对照真组件的渲染结果逐行比对
 * （标准答案 `tools/collect-session-list-fixtures.mjs`）。
 */

private const val MINUTE = 60_000L
private const val HOUR = 3_600_000L
private const val DAY = 86_400_000L

/** `formatSessionDate`：now / Nm / Nh / Nd / Nw / Nmo / Ny（向下取整，与官方的边界一致）。 */
fun formatSessionDate(ageMs: Long): String {
    val mins = Math.floorDiv(ageMs, MINUTE)
    val hours = Math.floorDiv(ageMs, HOUR)
    val days = Math.floorDiv(ageMs, DAY)
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}

/** 官方的字符前缀：`│  ` / `   ` 接 `├─ ` / `└─ `；根（depth 0）没有前缀。手机上画成真线条时只用层数与 isLast。 */
fun treePrefix(depth: Int, ancestorContinues: List<Boolean>, isLast: Boolean): String {
    if (depth == 0) return ""
    val sb = StringBuilder()
    for (c in ancestorContinues) sb.append(if (c) "│  " else "   ")
    sb.append(if (isLast) "└─ " else "├─ ")
    return sb.toString()
}

/** 有会话名用会话名，否则第一条消息；`[\x00-\x1f\x7f]` 换成空格再 trim（官方 `render`）。 */
fun displayText(name: String?, firstMessage: String): String {
    val raw = name ?: firstMessage
    val sb = StringBuilder(raw.length)
    for (ch in raw) sb.append(if (ch.code <= 0x1f || ch.code == 0x7f) ' ' else ch)
    return sb.toString().trim()
}

/** 右侧文字：`<cwd（all 范围且 cwd 非空）> <条数> <年龄>`；cwd 已由宿主按官方 `shortenHomePath` 缩成 `~`。 */
fun rightText(cwdShort: String, messageCount: Int, ageMs: Long, showCwd: Boolean): String {
    val base = "$messageCount ${formatSessionDate(ageMs)}"
    return if (showCwd && cwdShort.isNotEmpty()) "$cwdShort $base" else base
}

/**
 * 手机上的年龄说法：数值与档位**就是** [formatSessionDate]（官方规则的唯一实现），
 * 根据语言输出「刚刚 / 3小时前 / 2个月前」或「just now / 3h ago / 2mo ago」。
 */
fun sessionAgeLabel(ageMs: Long, zh: Boolean = true): String {
    val short = formatSessionDate(ageMs)
    if (short == "now") return if (zh) "刚刚" else "just now"
    if (!zh) return "$short ago"
    val digits = short.takeWhile { it.isDigit() }
    return when (short.removePrefix(digits)) {
        "m" -> "${digits}分钟前"
        "h" -> "${digits}小时前"
        "d" -> "${digits}天前"
        "w" -> "${digits}周前"
        "mo" -> "${digits}个月前"
        else -> "${digits}年前"
    }
}
