package app.pi.ui.settings

import app.pi.i18n.tr

/** 「已发现的资源」这类事实行里，一份资源来自哪（优先级即 [rank]，小者先生效）。 */
internal enum class DiscoverySource(val rank: Int) {
    /**
     * 工作区 `<workspace>/.pi/<kind>`。
     *
     * **词必须与徽标同源**：这一行说「项目（<工作区>/.pi）」，包管理页那一行的来源徽标说
     * 「项目 .pi」——同一个地方两处用同一个词（`PackageStrings.ORIGIN_PROJECT`）。先前事实行
     * 只写路径、徽标只写「全局」，读者得自己猜它们是一回事。括号里的路径是补一次的对得上的
     * 凭证，不是第二个名字。
     */
    ProjectPi(0),

    /** 工作区 `<workspace>/.agents/<kind>`。 */
    Agents(1),

    /** agent dir（`~/.pi/agent/<kind>`，engine bind 成 guest 的那份）——与徽标的「全局」同一个词。 */
    Global(2),

    /** 已装资源包自带的（`packages` 里那些）。 */
    Package(3),

    /** 由扩展自己写出去的（目前只有设备桥那个技能）。 */
    Extension(4);

    val label: String
        get() = when (this) {
            ProjectPi -> tr("项目（<工作区>/.pi）", "Project (<workspace>/.pi)")
            Agents -> ".agents"
            Global -> tr("全局（~/.pi/agent）", "Global (~/.pi/agent)")
            Package -> tr("已装包", "Installed package")
            Extension -> tr("扩展贡献", "Extension")
        }
}
