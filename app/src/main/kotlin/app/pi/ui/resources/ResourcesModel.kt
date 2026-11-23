package app.pi.ui.resources

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pi.host.ExtensionsAccess
import app.pi.i18n.tr
import app.pi.packages.PiLoadedResources
import app.pi.packages.PiProjectTrust
import app.pi.packages.PiResourceCatalog
import app.pi.packages.PiResourceCatalog.Layer
import app.pi.packages.PiResourceCatalog.Parsed
import app.pi.packages.PiResourceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「扩展与资源」与「系统提示词与上下文」共用的统一状态源。
 * 全部读数来自宿主（SDK），不在客户端猜测或缓存过时状态：
 *  - [snapshot]：开关清单（`resources.list`）；
 *  - [loaded]：pi 此刻实际加载的生效资源（`resources.loaded`）；
 *  - [trust]：项目信任现状（`project.trustState`）；
 *  - [dataVersion]：每次刷新、写文件、删文件、重载完成后自增，驱动界面即时同步。
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
    var dataVersion by mutableIntStateOf(0)
        private set

    private var refreshQueued = false
    private val reloadMutex = Mutex()

    /** 项目没被信任时，项目层的东西 pi 根本不读：界面只给全局层。 */
    val projectUsable: Boolean get() = trust?.trusted != false

    val effectiveLayer: Layer get() = if (projectUsable) layer else Layer.Global

    fun refresh() {
        if (loading) {
            refreshQueued = true
            return
        }
        loading = true
        scope.launch {
            try {
                do {
                    refreshQueued = false
                    readAll()
                } while (refreshQueued)
            } finally {
                loading = false
            }
        }
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
            dataVersion++
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
                    dataVersion++
                }
                is Parsed.Error -> error = r.message
            }
            busyKey = null
        }
    }

    // ---- 资源文件（宿主 `resources.file.*`） ----

    suspend fun readFile(path: String) = access.resources.readFile(path)

    suspend fun writeFile(path: String, text: String, stamp: PiResourceFile.Stamp?): Parsed<PiResourceFile.Stamp> =
        access.resources.writeFile(path, text, stamp).also { if (it is Parsed.Ok) markChanged() }

    suspend fun createFile(kind: PiResourceFile.Kind, project: Boolean, name: String?): Parsed<String> =
        access.resources.createFile(kind, project, name).also { if (it is Parsed.Ok) markChanged() }

    suspend fun deleteFile(path: String): Parsed<Unit> =
        access.resources.deleteFile(path).also { if (it is Parsed.Ok) markChanged() }

    private suspend fun markChanged() {
        pendingReload = true
        (access.resources.list() as? Parsed.Ok)?.let { snapshot = it.value }
        dataVersion++
    }

    /**
     * 同步挂起版热重载：串行等待上一次重载完成，再执行 `session.reload` + 重读全部生效状态。
     * 避免并发保存时跳过重载却误报「已生效」。成功返回 null，失败返回可读错误文案。
     */
    suspend fun reloadAndRefresh(): String? = reloadMutex.withLock {
        reloading = true
        try {
            access.ext.reload()
            pendingReload = false
            error = null
            readAll()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: tr("重新加载失败", "Reload failed")
            error = msg
            msg
        } finally {
            reloading = false
        }
    }

    /** `session.reload`：扩展 / 技能 / 提示 / 主题 / 上下文文件热重载，不重启进程。 */
    fun reload() {
        if (reloading) return
        scope.launch { reloadAndRefresh() }
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

    /** 改写官方全局 `defaultProjectTrust`（ask / always / never）并立刻同步当前项目信任态与资源。 */
    fun setDefaultProjectTrust(policy: String, onDone: (String?) -> Unit = {}) {
        scope.launch {
            when (val r = access.resources.setDefaultProjectTrust(policy)) {
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
