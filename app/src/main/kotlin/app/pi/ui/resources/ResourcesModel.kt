package app.pi.ui.resources

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pi.host.ExtensionsAccess
import app.pi.i18n.tr
import app.pi.packages.PiLoadedResources
import app.pi.packages.PiProjectTrust
import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.packages.PiResourceFile
import app.pi.packages.PiResourceCatalog.Parsed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 「扩展与资源」整组页面共用的一份状态。三个读数都来自宿主（SDK），这里不推算：
 *  - [snapshot]：开关清单（`resources.list`，含没开的、按来源分组）；
 *  - [loaded]：pi **此刻实际加载**的（`resources.loaded`：描述、扩展注册了什么、冲突与错误）；
 *  - [trust]：项目信任现状（`project.trustState`）。
 * 改动（开关、信任）写完后重读这三份；开关要让**已在跑的会话**读到，得 `session.reload`，
 * 所以一次改动只记 [pendingReload]，由用户点「重新加载」一次性生效（连点三个开关不用重载三遍）。
 */
@Stable
internal class ResourcesModel(
    private val access: ExtensionsAccess,
    private val scope: CoroutineScope,
) {
    var snapshot by mutableStateOf<PiResourceCatalog.Snapshot?>(null)
        private set
    var loaded by mutableStateOf(PiLoadedResources.Loaded.EMPTY)
        private set

    /** false = 还没读到（界面显示「读取中」而不是 0 个）。 */
    var hasLoaded by mutableStateOf(false)
        private set
    var trust by mutableStateOf<PiProjectTrust.State?>(null)
        private set
    var layer by mutableStateOf(Layer.Global)
    var loading by mutableStateOf(false)
        private set
    var reloading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busyKey by mutableStateOf<String?>(null)
        private set
    var pendingReload by mutableStateOf(false)
        private set

    /** 项目没被信任时，项目层的东西 pi 根本不读：界面只给全局层。 */
    val projectUsable: Boolean get() = trust?.trusted != false

    val effectiveLayer: Layer get() = if (projectUsable) layer else Layer.Global

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch { readAll(); loading = false }
    }

    private suspend fun readAll() {
        coroutineScope {
            val list = async { access.resources.list() }
            val live = async { access.resources.loaded() }
            val tr = async { access.resources.trustState() }
            var problem: String? = null
            when (val r = list.await()) {
                is Parsed.Ok -> snapshot = r.value
                is Parsed.Error -> problem = r.message
            }
            when (val r = live.await()) {
                is Parsed.Ok -> { loaded = r.value; hasLoaded = true }
                is Parsed.Error -> problem = problem ?: r.message
            }
            when (val r = tr.await()) {
                is Parsed.Ok -> trust = r.value
                is Parsed.Error -> problem = problem ?: r.message
            }
            error = problem
        }
    }

    fun toggle(item: PiResourceCatalog.Item) {
        if (busyKey != null) return
        val key = item.type.key + ":" + item.path
        busyKey = key
        val target = effectiveLayer
        scope.launch {
            when (val r = access.resources.toggle(target, item)) {
                is Parsed.Ok -> {
                    pendingReload = true
                    error = null
                    r.value.snapshot?.let { snapshot = it }
                    if (r.value.snapshot == null) (access.resources.list() as? Parsed.Ok)?.let { snapshot = it.value }
                }
                is Parsed.Error -> error = r.message
            }
            busyKey = null
        }
    }

    // ---- 资源文件（宿主 `resources.file.*`）。改完只记「待重新加载」，由用户点一次「重新加载」。 ----

    suspend fun readFile(path: String) = access.resources.readFile(path)

    suspend fun writeFile(path: String, text: String, stamp: PiResourceFile.Stamp?): Parsed<PiResourceFile.Stamp> =
        access.resources.writeFile(path, text, stamp).also { if (it is Parsed.Ok) markChanged() }

    suspend fun createFile(kind: PiResourceFile.Kind, project: Boolean, name: String?): Parsed<String> =
        access.resources.createFile(kind, project, name).also { if (it is Parsed.Ok) markChanged() }

    suspend fun deleteFile(path: String): Parsed<Unit> =
        access.resources.deleteFile(path).also { if (it is Parsed.Ok) markChanged() }

    private suspend fun markChanged() {
        pendingReload = true
        // 清单（开关那张表）也随之变：新建的出现、删掉的消失。
        (access.resources.list() as? Parsed.Ok)?.let { snapshot = it.value }
    }

    /** `session.reload`：扩展 / 技能 / 提示 / 主题 / 上下文文件热重载，不重启进程。 */
    fun reload() {
        if (reloading) return
        reloading = true
        scope.launch {
            try {
                access.ext.reload()
                pendingReload = false
                error = null
                readAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: tr("重新加载失败", "Reload failed")
            }
            reloading = false
        }
    }

    /** 表态后宿主已写信任库、已让会话重载；读回新的结论与资源。失败返回宿主原话。 */
    fun setTrust(option: PiProjectTrust.Option, onDone: (String?) -> Unit = {}) {
        scope.launch {
            when (val r = access.resources.setTrust(option)) {
                is Parsed.Ok -> {
                    trust = r.value
                    pendingReload = false
                    readAll()
                    onDone(null)
                }
                is Parsed.Error -> onDone(r.message)
            }
        }
    }
}
