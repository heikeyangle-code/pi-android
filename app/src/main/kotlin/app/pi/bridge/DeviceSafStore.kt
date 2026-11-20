package app.pi.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话内危险操作审批账本：由 pi 侧扩展通过 `POST /app/gate/report` 上报，供「设置 → 设备能力」展示。
 * （原 SAF 目录存储已移除：App 与工作区本身已拥有完整存储访问能力，无需再走 SAF 代理。）
 */
object DeviceApprovalLedger {

    private const val MAX_TOOLS = 32
    private const val MAX_TEXT = 120

    private data class Snapshot(
        val sessionGrants: List<String>,
        val counts: Map<String, Int>,
        val reportedAt: Long,
        val note: String,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    /** Replace the ledger with what the gate just reported. */
    fun report(payload: JSONObject) {
        val grants = mutableListOf<String>()
        payload.optJSONArray("sessionGrants")?.let { array ->
            for (index in 0 until minOf(array.length(), MAX_TOOLS)) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty()) grants.add(value.take(MAX_TEXT))
            }
        }
        val counts = mutableMapOf<String, Int>()
        payload.optJSONObject("counts")?.let { objectValue ->
            for (key in objectValue.keys()) {
                if (counts.size >= MAX_TOOLS) break
                val name = key.trim().take(MAX_TEXT)
                if (name.isNotEmpty()) counts[name] = objectValue.optInt(key, 0)
            }
        }
        snapshot = Snapshot(
            sessionGrants = grants,
            counts = counts,
            reportedAt = System.currentTimeMillis(),
            note = payload.optString("note").take(MAX_TEXT),
        )
    }

    fun clear() {
        snapshot = null
    }

    /** JSON for `/app/health` and `/app/gate/report`'s reply. */
    fun toJson(): JSONObject {
        val current = snapshot
        return JSONObject().apply {
            put("reported", current != null)
            if (current == null) {
                put("sessionGrants", JSONArray())
                put("counts", JSONObject())
                put("note", "当前会话暂无危险操作审批记录。")
                return@apply
            }
            put("sessionGrants", JSONArray(current.sessionGrants))
            put("counts", JSONObject(current.counts as Map<*, *>))
            put("reportedAt", current.reportedAt)
            put("ageSeconds", (System.currentTimeMillis() - current.reportedAt) / 1000)
            put("note", current.note)
        }
    }

    /** Lines for the authorization page. */
    fun summaryLines(): List<String> {
        val current = snapshot
            ?: return listOf(
                "当前会话暂无危险操作放行记录；危险操作（Shell、结束应用等）在每次新会话都会重新询问。",
            )
        val lines = mutableListOf<String>()
        if (current.sessionGrants.isEmpty()) {
            lines.add("已记住放行的危险工具：无（每次操作均单独询问）。")
        } else {
            lines.add("本会话已放行：${current.sessionGrants.joinToString("、")}")
        }
        if (current.counts.isNotEmpty()) {
            val counted = current.counts.entries
                .sortedByDescending { it.value }
                .joinToString("、") { "${it.key}×${it.value}" }
            lines.add("审批次数：$counted")
        }
        return lines
    }
}
