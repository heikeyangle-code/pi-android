package app.pi.ui.settings

import app.pi.settings.PiSettingsFileStore
import app.pi.settings.PiSettingsScopes
import app.pi.settings.SettingsScope
import kotlinx.serialization.json.JsonElement

/**
 * 设置页「全局 / 本项目」的那一层：读照旧（pi 看到的合并值），**写和删明确落到所选层级**。
 *
 * 以前 `PiSettingsFileStore.write` 是隐式的：本项目文件里恰好有这个键就写进项目，否则写进全局 ——
 * 用户从头到尾不知道自己改的是哪一份，也没有任何办法**创建**一个项目覆盖。这里把它摊开：
 *  - [project] = false：写全局。若本项目另有同名覆盖，pi 仍用项目的 —— 行上会标出「本项目」，
 *    告诉用户为什么改了没变化；
 *  - [project] = true：写 `<工作区>/.pi/settings.json`；「恢复默认」删的是这一层的覆盖，值回到继承全局。
 * 不按层级的键（`app.*`、全局独有键、资源数组）原样走 [base]。
 */
class ScopedSettingsStore(
    private val base: PiSettingsStore,
    private val files: PiSettingsFileStore?,
    val project: Boolean,
) : PiSettingsStore {

    override fun read(key: String): JsonElement? = base.read(key)

    override fun write(key: String, value: JsonElement) {
        val f = files
        if (f != null && scoped(key)) {
            f.writeIn(if (project) SettingsScope.Project else SettingsScope.Global, key, value)
        } else {
            base.write(key, value)
        }
    }

    override fun remove(key: String) {
        val f = files
        if (f != null && scoped(key)) {
            f.removeIn(if (project) SettingsScope.Project else SettingsScope.Global, key)
        } else {
            base.remove(key)
        }
    }

    /** 本项目文件里现在是否写着这个键（行上「本项目」标记的依据）。 */
    fun projectDefines(key: String): Boolean = files?.projectDefines(key) == true

    /** 有没有「本项目」这一层（没有工作区文件路径时界面不画切换）。 */
    fun hasProject(): Boolean = files?.hasProjectScope() == true

    // 全局视图下：只有 pi 设置键才显式落全局；`app.*` 等走 base 的旧路径。
    // 项目视图下：只有能覆盖的键才落项目 —— 其余键（全局独有等）界面里根本不出现，这里再挡一次。
    private fun scoped(key: String): Boolean =
        !key.startsWith("app.") && !key.contains("[]") && (!project || PiSettingsScopes.overridableInProject(key))
}
