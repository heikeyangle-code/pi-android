package app.pi.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun versionComparisonHandlesPrefixesMultiDigitAndPrereleases() {
        assertTrue(AppUpdater.isNewerVersion(current = "0.2.0", candidate = "v0.2.1"))
        assertTrue(AppUpdater.isNewerVersion(current = "0.2.1", candidate = "0.10.0"))
        assertFalse(AppUpdater.isNewerVersion(current = "0.2.1", candidate = "v0.2.1"))
        assertFalse(AppUpdater.isNewerVersion(current = "0.2.1", candidate = "0.2.0"))
        // Stable release is newer than pre-release of the same version
        assertTrue(AppUpdater.isNewerVersion(current = "0.3.0-rc.1", candidate = "0.3.0"))
        assertFalse(AppUpdater.isNewerVersion(current = "0.3.0", candidate = "0.3.0-rc.1"))
        assertTrue(AppUpdater.isNewerVersion(current = "0.2.1", candidate = "0.3.0-beta.1"))
        assertTrue(AppUpdater.isNewerVersion(current = "0.3.0-rc.2", candidate = "0.3.0-rc.10"))
        assertFalse(AppUpdater.isNewerVersion(current = "0.3.0-rc.10", candidate = "0.3.0-rc.2"))
    }

    @Test
    fun pickBestApkAssetMatchesTargetSdkVariantWithoutCrossVariantDowngrade() {
        val sideload = AppUpdater.ReleaseAsset(
            name = "pi-android-0.3.0-sideload28-arm64.apk",
            downloadUrl = "https://example.com/sideload28.apk",
            sizeBytes = 64_000_000L,
        )
        val modern = AppUpdater.ReleaseAsset(
            name = "pi-android-0.3.0-modern36-arm64.apk",
            downloadUrl = "https://example.com/modern36.apk",
            sizeBytes = 64_000_000L,
        )
        val checksum = AppUpdater.ReleaseAsset(
            name = "SHA256SUMS.txt",
            downloadUrl = "https://example.com/sums.txt",
            sizeBytes = 128L,
        )
        val assets = listOf(checksum, modern, sideload)

        assertEquals(sideload, AppUpdater.pickBestApkAsset(assets, targetSdk = 28))
        assertEquals(modern, AppUpdater.pickBestApkAsset(assets, targetSdk = 36))
        // Must never cross-install sideload28 on modern36 or modern36 on sideload28
        assertEquals(null, AppUpdater.pickBestApkAsset(listOf(sideload), targetSdk = 36))
        assertEquals(null, AppUpdater.pickBestApkAsset(listOf(modern), targetSdk = 28))
        val generic = AppUpdater.ReleaseAsset("pi-android-0.3.0.apk", "https://example.com/app.apk", 64_000_000L)
        assertEquals(generic, AppUpdater.pickBestApkAsset(listOf(generic), targetSdk = 36))
        assertEquals(generic, AppUpdater.pickBestApkAsset(listOf(generic), targetSdk = 28))
    }

    @Test
    fun parseReleaseJsonExtractsAssetsAndGitHubSha256Digest() {
        val sha = "a".repeat(64)
        val raw = """
            {
              "tag_name": "v0.3.0",
              "name": "Pi Android 0.3.0",
              "draft": false,
              "prerelease": false,
              "published_at": "2026-11-24T10:00:00Z",
              "html_url": "https://github.com/heikeyangle-code/pi-android/releases/tag/v0.3.0",
              "body": "## Highlights\n- Unified models screen\n- In-app updater",
              "assets": [
                {
                  "name": "pi-android-0.3.0-sideload28-arm64.apk",
                  "browser_download_url": "https://github.com/heikeyangle-code/pi-android/releases/download/v0.3.0/pi-android-0.3.0-sideload28-arm64.apk",
                  "size": 67108864,
                  "digest": "sha256:$sha"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdater.parseReleaseJson(raw)
        assertNotNull(parsed)
        assertEquals("0.3.0", parsed!!.version)
        assertEquals("2026-11-24", parsed.publishedAt)
        assertEquals(1, parsed.assets.size)
        assertEquals(sha, parsed.assets[0].sha256)
        assertTrue(parsed.assets[0].isSideload28)
    }
}
