package app.pi.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.pi.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * 原生应用内检查与覆盖安装更新（对接 GitHub Releases `heikeyangle-code/pi-mobile-agent`）：
 *  - 冷启动 0 开销：仅在进入设置页或用户手动点击「检查更新」时在 `Dispatchers.IO` 查询；
 *  - 自动匹配当前安装包的 `targetSdkVersion` 变体（`sideload28` vs `modern36`），避免系统因
 *    `INSTALL_FAILED_TARGET_SDK_VERSION_DOWNGRADE` 拒绝覆盖安装，并保留 `sideload28` 原生容器能力；
 *  - 流式下载到 `cacheDir/updates/`，实时回调进度并强校验文件长度与 GitHub `sha256` 摘要；
 *  - 通过 `FileProvider` + `REQUEST_INSTALL_PACKAGES` 调起系统安装器原地覆盖升级（签名一致，会话与配置完整保留）。
 */
object AppUpdater {

    const val REPO_SLUG = "heikeyangle-code/pi-mobile-agent"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_SLUG/releases"
    private const val LATEST_API_URL = "https://api.github.com/repos/$REPO_SLUG/releases/latest"
    private const val LIST_API_URL = "https://api.github.com/repos/$REPO_SLUG/releases?per_page=5"
    private const val CHECK_TTL_MS = 20 * 60_000L

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256: String? = null,
    ) {
        val isSideload28: Boolean get() = name.contains("sideload28", ignoreCase = true)
        val isModern36: Boolean get() = name.contains("modern36", ignoreCase = true)
    }

    data class ReleaseInfo(
        val tagName: String,
        val version: String,
        val title: String,
        val notes: String,
        val htmlUrl: String,
        val publishedAt: String?,
        val prerelease: Boolean,
        val assets: List<ReleaseAsset>,
    )

    data class CheckOutcome(
        val currentVersion: String,
        val targetSdk: Int,
        val latest: ReleaseInfo?,
        val matchedAsset: ReleaseAsset?,
        val hasUpdate: Boolean,
    )

    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) {
        val fraction: Float?
            get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
    }

    @Volatile
    private var cachedOutcome: CheckOutcome? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    fun peekCached(): CheckOutcome? = cachedOutcome

    fun clearCache() {
        cachedOutcome = null
        cachedAtMs = 0L
    }

    fun installedVersionName(context: Context): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"

    fun installedTargetSdk(context: Context): Int =
        context.applicationInfo.targetSdkVersion

    fun expectedVariantLabel(targetSdk: Int): String =
        if (targetSdk >= 30) "modern36" else "sideload28"

    suspend fun checkForUpdate(context: Context, force: Boolean = false): Result<CheckOutcome> {
        val now = System.currentTimeMillis()
        val existing = cachedOutcome
        if (!force && existing != null && (now - cachedAtMs) < CHECK_TTL_MS) {
            return Result.success(existing)
        }
        val currentVer = installedVersionName(context)
        val targetSdk = installedTargetSdk(context)
        return withContext(Dispatchers.IO) {
            runCatching {
                val release = fetchLatestRelease(currentVer)
                val asset = release?.let { pickBestApkAsset(it.assets, targetSdk) }
                val hasUpdate = release != null && isNewerVersion(current = currentVer, candidate = release.version)
                CheckOutcome(
                    currentVersion = currentVer,
                    targetSdk = targetSdk,
                    latest = release,
                    matchedAsset = asset,
                    hasUpdate = hasUpdate,
                ).also {
                    cachedOutcome = it
                    cachedAtMs = System.currentTimeMillis()
                }
            }
        }
    }

    suspend fun downloadApk(
        context: Context,
        asset: ReleaseAsset,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
            val finalFile = File(dir, safeName)
            val partFile = File(dir, "$safeName.part")

            // 若上次已完整下载且大小/哈希完全吻合，直接秒级复用，不重复跑流量
            if (finalFile.isFile && verifyFile(finalFile, asset.sizeBytes, asset.sha256)) {
                onProgress(DownloadProgress(finalFile.length(), finalFile.length()))
                return@runCatching finalFile
            }

            // 清理旧包与残片，避免占满应用缓存目录
            dir.listFiles()?.forEach { f ->
                if (f != partFile) runCatching { f.delete() }
            }

            val conn = openRedirectingGet(
                url = asset.downloadUrl,
                accept = "application/octet-stream",
                userAgent = "Pi-Android-Updater",
                readTimeoutMs = 45_000,
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException(tr("下载服务器返回 HTTP $code", "Download failed with HTTP $code"))
                }
                val contentLength = conn.contentLengthLong.takeIf { it > 0L } ?: asset.sizeBytes
                val digest = if (asset.sha256 != null) MessageDigest.getInstance("SHA-256") else null
                var downloaded = 0L
                var lastEmit = 0L

                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            digest?.update(buf, 0, n)
                            downloaded += n
                            val now = System.nanoTime()
                            if (now - lastEmit >= 75_000_000L) {
                                lastEmit = now
                                onProgress(DownloadProgress(downloaded, contentLength))
                            }
                        }
                        out.fd.sync()
                    }
                }
                onProgress(DownloadProgress(downloaded, contentLength))

                if (asset.sizeBytes > 0L && downloaded != asset.sizeBytes) {
                    partFile.delete()
                    throw IllegalStateException(
                        tr(
                            "安装包大小不完整（已收 ${formatBytes(downloaded)}，应为 ${formatBytes(asset.sizeBytes)}）",
                            "Downloaded APK size mismatch (${formatBytes(downloaded)} vs ${formatBytes(asset.sizeBytes)})",
                        ),
                    )
                }
                if (digest != null && asset.sha256 != null) {
                    val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualHex.equals(asset.sha256, ignoreCase = true)) {
                        partFile.delete()
                        throw IllegalStateException(
                            tr("安装包 SHA-256 校验未通过，已丢弃损坏文件", "APK SHA-256 verification failed; corrupted file discarded"),
                        )
                    }
                }
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    java.nio.file.Files.move(
                        partFile.toPath(),
                        finalFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                finalFile
            } catch (t: Throwable) {
                runCatching { partFile.delete() }
                throw t
            } finally {
                conn.disconnect()
            }
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    fun launchApkInstaller(context: Context, apkFile: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // --------------------------------------------------------- 纯逻辑（单测直接覆盖）

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").trim()

    /**
     * 语义化版本比较：`> 0` 表示 [a] 比 [b] 更新，`0` 相同，`< 0` 更旧。
     * 正式版高于同版本号的预发布版（如 `0.3.0 > 0.3.0-rc.1`）。
     */
    fun compareVersions(a: String, b: String): Int {
        val ca = normalizeVersion(a)
        val cb = normalizeVersion(b)
        if (ca == cb) return 0
        val mainA = ca.substringBefore('-').substringBefore('+')
        val mainB = cb.substringBefore('-').substringBefore('+')
        val partsA = mainA.split('.').map { it.toIntOrNull() ?: 0 }
        val partsB = mainB.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        val preA = ca.substringBefore('+').substringAfter('-', missingDelimiterValue = "")
        val preB = cb.substringBefore('+').substringAfter('-', missingDelimiterValue = "")
        return when {
            preA.isEmpty() && preB.isEmpty() -> 0
            preA.isEmpty() -> 1
            preB.isEmpty() -> -1
            else -> comparePrerelease(preA, preB)
        }
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val segA = a.split('.')
        val segB = b.split('.')
        val maxLen = maxOf(segA.size, segB.size)
        for (i in 0 until maxLen) {
            val sa = segA.getOrNull(i) ?: return -1
            val sb = segB.getOrNull(i) ?: return 1
            val na = sa.toIntOrNull()
            val nb = sb.toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                else -> sa.compareTo(sb, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    fun isNewerVersion(current: String, candidate: String): Boolean =
        compareVersions(candidate, current) > 0

    /**
     * 按当前已安装 APK 的 `targetSdkVersion` 智能匹配变体：
     *  - `targetSdk >= 30` → 优先选 `modern36`，否则兜底任意 `.apk`；
     *  - `targetSdk < 30`  → 优先选 `sideload28`，否则兜底任意 `.apk`。
     */
    fun pickBestApkAsset(assets: List<ReleaseAsset>, targetSdk: Int): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val neutral = apks.firstOrNull { !it.isModern36 && !it.isSideload28 }
        return if (targetSdk >= 30) {
            // modern36 (targetSdk 36) cannot downgrade to sideload28 (INSTALL_FAILED_TARGET_SDK_VERSION_DOWNGRADE)
            apks.firstOrNull { it.isModern36 } ?: neutral
        } else {
            // sideload28 must not silently upgrade to modern36 and lose native execve() capability
            apks.firstOrNull { it.isSideload28 } ?: neutral
        }
    }

    fun parseReleaseJson(rawJson: String): ReleaseInfo? {
        val obj = runCatching { Json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return null
        return parseReleaseObject(obj)
    }

    internal fun parseReleaseObject(obj: JsonObject): ReleaseInfo? {
        if (bool(obj, "draft") == true) return null
        val tag = str(obj, "tag_name")?.takeIf { it.isNotBlank() } ?: return null
        val version = normalizeVersion(tag)
        val title = str(obj, "name")?.takeIf { it.isNotBlank() } ?: tag
        val notes = str(obj, "body").orEmpty().trim()
        val htmlUrl = str(obj, "html_url")?.takeIf { it.isNotBlank() } ?: RELEASES_PAGE_URL
        val publishedAt = str(obj, "published_at")?.takeIf { it.isNotBlank() }?.take(10)
        val prerelease = bool(obj, "prerelease") == true
        val assets = (obj["assets"] as? JsonArray)?.mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            val name = str(a, "name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = str(a, "browser_download_url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val size = (a["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val digestRaw = str(a, "digest")?.trim()
            val sha256 = digestRaw
                ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.length == 64 }
            ReleaseAsset(name = name, downloadUrl = url, sizeBytes = size, sha256 = sha256)
        }.orEmpty()
        return ReleaseInfo(
            tagName = tag,
            version = version,
            title = title,
            notes = notes,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt,
            prerelease = prerelease,
            assets = assets,
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val mb = bytes / (1024.0 * 1024.0)
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
        val kb = bytes / 1024.0
        if (kb >= 1.0) return String.format(Locale.US, "%.0f KB", kb)
        return "$bytes B"
    }

    // --------------------------------------------------------- 内部网络与文件校验

    private fun fetchLatestRelease(userAgentVersion: String): ReleaseInfo? {
        val ua = "Pi-Android/$userAgentVersion"
        val direct = fetchJsonString(LATEST_API_URL, ua)
        if (direct != null) {
            parseReleaseJson(direct)?.let { return it }
        }
        val listRaw = fetchJsonString(LIST_API_URL, ua) ?: return null
        val arr = runCatching { Json.parseToJsonElement(listRaw) as? JsonArray }.getOrNull() ?: return null
        return arr.mapNotNull { (it as? JsonObject)?.let(::parseReleaseObject) }
            .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    private fun fetchJsonString(url: String, userAgent: String): String? {
        val conn = openRedirectingGet(
            url = url,
            accept = "application/vnd.github+json",
            userAgent = userAgent,
            readTimeoutMs = 15_000,
        )
        return try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) {
                throw IllegalStateException(tr("GitHub 接口返回 HTTP $code", "GitHub API returned HTTP $code"))
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openRedirectingGet(
        url: String,
        accept: String,
        userAgent: String,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", userAgent)
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (!loc.isNullOrBlank()) {
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
            }
            return conn
        }
        throw IllegalStateException(tr("下载重定向次数过多", "Too many redirects"))
    }

    private fun verifyFile(file: File, expectedSize: Long, expectedSha256: String?): Boolean {
        if (!file.isFile) return false
        if (expectedSize > 0L && file.length() != expectedSize) return false
        if (expectedSha256 == null) return file.length() > 0L
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            hex.equals(expectedSha256, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun str(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(o: JsonObject, k: String): Boolean? =
        (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
}
