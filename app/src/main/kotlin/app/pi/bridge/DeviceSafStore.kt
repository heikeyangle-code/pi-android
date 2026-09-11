package app.pi.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the pi-side permission gate has approved in this session.
 *
 * The gate itself runs *inside the guest* (it is a pi extension), so the app cannot
 * observe it directly — the extension reports here through `POST /app/gate/report`,
 * and this ledger is what the authorization page displays. That direction matters:
 * a relaxation the user cannot see is exactly what the design forbids, and the
 * report is labelled as "reported by the extension" in the UI because the app has
 * no way to verify it.
 *
 * Nothing in this object changes policy. The gate's own in-process memory is the
 * enforcement; this is the visible trace of it.
 */
object DeviceApprovalLedger {

    private const val MAX_TOOLS = 32
    private const val MAX_TEXT = 120

    private data class Snapshot(
        val sessionGrants: List<String>,
        val counts: Map<String, Int>,
        val relaxedShellSyntax: Boolean,
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
            relaxedShellSyntax = payload.optBoolean("relaxedShellSyntax", false),
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
                put("note", "扩展还没有上报过（或 App 刚重启）。危险操作会在每次会话开始时重新询问。")
                return@apply
            }
            put("sessionGrants", JSONArray(current.sessionGrants))
            put("counts", JSONObject(current.counts as Map<*, *>))
            put("relaxedShellSyntax", current.relaxedShellSyntax)
            put("reportedAt", current.reportedAt)
            put("ageSeconds", (System.currentTimeMillis() - current.reportedAt) / 1000)
            put("note", current.note)
        }
    }

    /** Lines for the authorization page. */
    fun summaryLines(): List<String> {
        val current = snapshot
            ?: return listOf(
                "扩展还没有上报审批状态。危险操作（Shell、结束应用、跨沙箱读写…）在每次会话开始时都会重新询问。",
            )
        val lines = mutableListOf<String>()
        if (current.sessionGrants.isEmpty()) {
            lines.add("本会话已记住同意的危险工具：无。每个危险操作都会单独询问。")
        } else {
            lines.add("本会话已记住同意的危险工具：${current.sessionGrants.joinToString("、")}（不会再询问直到本会话结束）。")
        }
        if (current.counts.isNotEmpty()) {
            val counted = current.counts.entries
                .sortedByDescending { it.value }
                .joinToString("、") { "${it.key}×${it.value}" }
            lines.add("本会话审批次数：$counted")
        }
        lines.add("以上由 pi 侧扩展上报（App 无法独立验证），上报于 ${current.reportedAt / 1000} 秒时间戳。")
        return lines
    }
}

/**
 * The SAF (Storage Access Framework) directory grants of the 「存储」 group.
 *
 * Why this exists at all: the page used to say 「已授权目录：无」 with the reason
 * "a picker needs an Activity". The picker *does* need an Activity, and this app
 * has one — the device-capability screen is a composable inside `MainActivity`, so
 * it can launch `ACTION_OPEN_DOCUMENT_TREE` itself, and a persisted URI permission
 * survives restarts. That turns the storage group from "the app's own Downloads
 * exports" into "whatever directory the user points at", which is the designed
 * behaviour (design §21.4「存储：SAF 指定目录读写」and UI spec §5.6「显示已授权目录列表」).
 *
 * Reading and writing only: there is deliberately no delete endpoint, because the
 * design says 读写 and a delete is the one operation a user cannot undo.
 */
class DeviceSafStore private constructor(context: android.content.Context) {

    private val appContext: android.content.Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    data class Grant(val uri: String, val name: String)

    /** The grants, in the order they were added. */
    fun grants(): List<Grant> {
        val raw = prefs.getString(KEY_GRANTS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Grant>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val uri = item.optString("uri")
            if (uri.isEmpty()) continue
            out.add(Grant(uri, item.optString("name").ifEmpty { uri }))
        }
        return out
    }

    /**
     * Persist a tree the user just picked. Returns the stored grant, or null when
     * the platform refused to make it persistable (some providers do).
     */
    fun add(treeUri: android.net.Uri, displayName: String?): Grant? {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(treeUri, flags)
            true
        }.getOrDefault(false)
        val file = runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, treeUri) }
            .getOrNull()
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: file?.name
            ?: treeUri.lastPathSegment
            ?: "已授权目录"
        val grant = Grant(treeUri.toString(), uniqueName(name, treeUri.toString()))
        val updated = grants().filterNot { it.uri == grant.uri } + grant
        save(updated)
        return if (persisted) grant else null
    }

    fun remove(uri: String): Boolean {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                android.net.Uri.parse(uri),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val before = grants()
        val remaining = before.filterNot { it.uri == uri }
        save(remaining)
        return remaining.size != before.size
    }

    /** How many directories the user has granted. */
    fun grantCount(): Int = grants().size

    /** The live tree documents, skipping any grant the system has revoked. */
    fun roots(): List<androidx.documentfile.provider.DocumentFile> = grants().mapNotNull { grant ->
        runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, android.net.Uri.parse(grant.uri))
        }.getOrNull()
    }

    // ------------------------------------------------------------- operations ----

    /** `GET /app/files`: the roots, so the model knows what it may address. */
    fun describe(): JSONObject {
        val entries = JSONArray()
        for (grant in grants()) {
            val file = runCatching {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, android.net.Uri.parse(grant.uri))
            }.getOrNull()
            entries.put(
                JSONObject().apply {
                    put("name", grant.name)
                    put("uri", grant.uri)
                    put("exists", file?.exists() == true)
                },
            )
        }
        return JSONObject().apply {
            put("count", entries.length())
            put("roots", entries)
            put(
                "note",
                if (entries.length() == 0) {
                    "用户还没有授权任何 SAF 目录。请让他在「设置 → 设备能力 → 存储」点「授权目录」，" +
                        "选一个文件夹（例如 Documents 或某个项目目录），之后就能读写它里面的文件。"
                } else {
                    "路径写成「根目录名/相对路径」，例如 ${entries.optJSONObject(0)?.optString("name")}/notes/todo.md。"
                },
            )
        }
    }

    /** `POST /app/files/list` — no path lists the roots themselves. */
    fun list(path: String?): JSONObject {
        val clean = path?.trim().orEmpty()
        if (clean.isEmpty()) return describe()
        val target = resolve(clean)
            ?: throw notFound(clean)
        if (!target.isDirectory) {
            throw DeviceActionException(
                DeviceDenial(DeviceDenial.BAD_REQUEST, "「$clean」不是目录。"),
            )
        }
        val children = runCatching { target.listFiles() }.getOrElse { error ->
            throw revoked(clean, error)
        }
        val entries = children
            .sortedWith(compareByDescending<androidx.documentfile.provider.DocumentFile> { it.isDirectory }.thenBy { it.name })
            .take(500)
            .map { file ->
                JSONObject().apply {
                    put("name", file.name ?: "")
                    put("directory", file.isDirectory)
                    put("bytes", file.length())
                    put("lastModified", file.lastModified())
                    put("uri", file.uri.toString())
                }
            }
        val array = JSONArray()
        for (entry in entries) array.put(entry)
        return JSONObject().apply {
            put("path", clean)
            put("count", array.length())
            put("truncated", children.size > array.length())
            put("entries", array)
        }
    }

    /** `POST /app/files/read` — text or base64, bounded. */
    fun read(path: String, maxBytes: Int): JSONObject {
        val clean = path.trim()
        if (clean.isEmpty()) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "读取文件需要 path。"))
        }
        val target = resolve(clean) ?: throw notFound(clean)
        if (target.isDirectory) {
            throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "「$clean」是目录，请用 android_files_list。"))
        }
        val limit = maxBytes.coerceIn(1024, 4 * 1024 * 1024)
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(target.uri)?.use { input ->
                val all = input.readBytes()
                if (all.size > limit) all.copyOf(limit) else all
            }
        }.getOrElse { error -> throw revoked(clean, error) }
            ?: throw DeviceActionException(DeviceDenial(DeviceDenial.ERROR, "无法打开「$clean」的输入流。"))

        val text = bytes.all { byte ->
            val value = byte.toInt() and 0xFF
            value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 128
        }
        return JSONObject().apply {
            put("path", clean)
            put("uri", target.uri.toString())
            put("bytes", bytes.size)
            put("truncated", bytes.size >= limit)
            if (text) put("text", String(bytes, Charsets.UTF_8))
            else put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        }
    }

    /** `POST /app/files/write` — creates the file and any missing parent directories. */
    fun write(path: String, text: String?, base64: String?, mimeType: String): JSONObject {
        val clean = path.trim().trimStart('/')
        if (clean.isEmpty() || !clean.contains('/')) {
            throw DeviceActionException(
                DeviceDenial(
                    code = DeviceDenial.BAD_REQUEST,
                    reason = "写入需要「根目录名/相对路径」形式的 path（例如 Documents/notes/todo.md）。",
                    hint = "先用 android_files_list 看已授权的根目录名。",
                ),
            )
        }
        val bytes = when {
            base64 != null -> runCatching { android.util.Base64.decode(base64, android.util.Base64.DEFAULT) }
                .getOrElse {
                    throw DeviceActionException(DeviceDenial(DeviceDenial.BAD_REQUEST, "base64 无法解码：${it.message}"))
                }

            text != null -> text.toByteArray(Charsets.UTF_8)
            else -> throw DeviceActionException(
                DeviceDenial(DeviceDenial.BAD_REQUEST, "写入需要 content（文本）或 base64（二进制）之一。"),
            )
        }
        // A write has to *create* its target, so it walks the parent chain and makes
        // the missing directories itself rather than resolving an existing document.
        val slot = resolveForWrite(clean) ?: throw notFound(clean)
        val parent = slot.parent
        val leafName = slot.name
        val existing = runCatching { parent.findFile(leafName) }.getOrNull()
        if (existing != null && existing.isDirectory) {
            throw DeviceActionException(
                DeviceDenial(DeviceDenial.BAD_REQUEST, "「$clean」是一个目录，不能被文件覆盖。"),
            )
        }
        val existed = existing != null
        val file = if (existed) {
            existing!!
        } else {
            // A specific MIME type is what the user sees in Files; some providers
            // refuse it, so the documented generic type is the fallback.
            runCatching { parent.createFile(mimeType.ifBlank { "application/octet-stream" }, leafName) }
                .getOrNull()
                ?: runCatching { parent.createFile("application/octet-stream", leafName) }.getOrNull()
                ?: throw DeviceActionException(
                    DeviceDenial(
                        code = DeviceDenial.ERROR,
                        reason = "SAF 目录拒绝创建「$clean」（提供方可能只读，或文件名不合法）。",
                    ),
                )
        }
        runCatching {
            appContext.contentResolver.openOutputStream(file.uri, "wt")?.use { stream -> stream.write(bytes) }
                ?: throw DeviceActionException(DeviceDenial(DeviceDenial.ERROR, "无法打开「$clean」的输出流。"))
        }.getOrElse { error -> throw revoked(clean, error) }

        return JSONObject().apply {
            put("path", clean)
            put("uri", file.uri.toString())
            put("bytes", bytes.size)
            put("created", !existed)
            put("mimeType", file.type ?: mimeType)
        }
    }

    /** Lines for the storage card. */
    fun summaryLines(): List<String> {
        val current = grants()
        if (current.isEmpty()) {
            return listOf("已授权目录：无。点「授权目录」选一个文件夹（Documents、某个项目目录等），Agent 就能读写它。")
        }
        return listOf("已授权目录（Agent 可读写）：" + current.joinToString("、") { it.name })
    }

    // ---------------------------------------------------------------- helpers ----

    /**
     * Address an **existing** path written as `rootName/relative/parts`: the first
     * segment selects the grant, the rest is walked with `findFile`. Returns null at
     * the first missing component, which callers turn into a NOT_FOUND that names the
     * path (a silent null is how "the file just is not there" gets misreported).
     */
    private fun resolve(path: String): androidx.documentfile.provider.DocumentFile? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var current = rootFor(parts[0]) ?: return null
        for (index in 1 until parts.size) {
            current = runCatching { current.findFile(parts[index]) }.getOrNull() ?: return null
        }
        return current
    }

    /** Where a write lands: the directory that must contain it, and the file name. */
    private data class WriteSlot(
        val parent: androidx.documentfile.provider.DocumentFile,
        val name: String,
    )

    /**
     * The write counterpart of [resolve]: it creates the intermediate directories a
     * path implies, and hands back the *parent* plus the leaf name so `write` can
     * call `createFile` on it.
     *
     * This is deliberately separate from [resolve] rather than a `create: Boolean`
     * flag: a read that quietly creates directories would be a side effect nobody
     * asked for, and the earlier version of this file tried to fake a
     * "not-yet-existing DocumentFile" instead, which no provider actually supports.
     */
    private fun resolveForWrite(path: String): WriteSlot? {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        var current = rootFor(parts[0]) ?: return null
        for (index in 1 until parts.size - 1) {
            val part = parts[index]
            val existing = runCatching { current.findFile(part) }.getOrNull()
            current = existing
                ?: runCatching { current.createDirectory(part) }.getOrNull()
                ?: return null
        }
        return WriteSlot(current, parts.last())
    }

    /** The live tree document of a granted root, by its display name. */
    private fun rootFor(name: String): androidx.documentfile.provider.DocumentFile? {
        val grant = grants().firstOrNull { it.name == name }
            ?: grants().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return null
        return runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(appContext, android.net.Uri.parse(grant.uri))
        }.getOrNull()
    }

    private fun notFound(path: String): DeviceActionException = DeviceActionException(
        DeviceDenial(
            code = DeviceDenial.NOT_FOUND,
            reason = "在已授权目录里找不到「$path」。",
            hint = "先用 android_files_list 看根目录名与目录内容；路径是「根目录名/相对路径」。",
        ),
    )

    private fun revoked(path: String, error: Throwable): DeviceActionException = DeviceActionException(
        DeviceDenial(
            code = DeviceDenial.NO_PERMISSION,
            reason = "访问「$path」失败，SAF 授权可能已经失效：${error::class.java.simpleName}: ${error.message}",
            hint = "请让用户在「设置 → 设备能力 → 存储」重新授权该目录。",
        ),
    )

    private fun uniqueName(name: String, uri: String): String {
        val taken = grants().filterNot { it.uri == uri }.map { it.name }.toSet()
        if (name !in taken) return name
        var index = 2
        while ("$name ($index)" in taken) index++
        return "$name ($index)"
    }

    private fun save(grants: List<Grant>) {
        val array = JSONArray()
        for (grant in grants) {
            array.put(JSONObject().put("uri", grant.uri).put("name", grant.name))
        }
        prefs.edit().putString(KEY_GRANTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "pi-device-saf"
        private const val KEY_GRANTS = "grants"

        @Volatile
        private var instance: DeviceSafStore? = null

        fun get(context: android.content.Context): DeviceSafStore {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: DeviceSafStore(context).also { instance = it }
            }
        }
    }
}
