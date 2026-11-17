package app.pi.engine

/** 真机实现：`AssetManager` 之上。单独成文件，好让 [SdkHostInstaller] 的逻辑不 import Android、能在 JVM harness 里跑。 */
class AndroidHostAssetSource(private val manager: android.content.res.AssetManager) : HostAssetSource {
    override fun list(dir: String): List<String> = manager.list(dir)?.toList().orEmpty()
    override fun read(path: String): ByteArray =
        manager.open(path, android.content.res.AssetManager.ACCESS_BUFFER).use { it.readBytes() }
}
