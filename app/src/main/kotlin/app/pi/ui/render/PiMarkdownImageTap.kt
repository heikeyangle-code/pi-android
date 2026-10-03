package app.pi.ui.render

import app.pi.rpc.PiImage
import java.util.Base64

/**
 * 一次点按交给查看器的**那份 wire 值**，以及"这次到底有没有图可开"的判定。
 *
 * ## 为什么入口只有一条，而这张图必须变成 `PiImage`
 *
 * 正文里的一张图要被点开，App 里只有一条出口：`ChatScreen` 的
 * `viewedImage: PiImage?` → `PiImageViewer(image = …)`（`ui/screens/ChatScreen.kt:2786-2794`
 * 是那个宿主，`ui/blocks/PiImageViewer.kt:158` 是它收的参数）。也就是说
 * "把正文里的一张图交给查看器" = 把这张图变成 `PiImage(base64, mimeType)` —— 查看器要的
 * 就是这两样，它内部把它折成 `PiImageViewerSource.Wire`，而那个来源类型的**身份**
 * （`cacheKey`）就是这个 base64 字符串本身（`ui/blocks/PiImageViewer.kt:90-92`）。
 * 本地图与远端图在这条出口上没有区别：字节通道早就把 `data:`、guest 路径、`http(s)` 三种
 * link 都化成字节了（`bridge/GuestImageBytes.kt:299-316`），查看器要的是字节，不是路径。
 *
 * ## 为什么这两步是裸 JVM 上的纯逻辑
 *
 * "字节 → 交给查看器的值"是点开这一条路上唯一与 Android 无关的一步，所以它单独放在这里：
 * `app/src/test/kotlin/app/pi/ui/render/PiMarkdownImageTapCheck.kt` 在裸 JVM 上真跑它
 * （由 `tools/run-app-pure-checks.sh` 的注册项编译并执行）。这个文件**不许**长出
 * `android.*` import —— 一旦长出，纯检查就编译失败，那正是"它不再是纯逻辑"的信号。
 *
 * 这也是这里用 `java.util.Base64` 而不是 App 其它地方的 `android.util.Base64` 的**唯一**
 * 理由：后者一进来，本机（没有 android.jar）就编译不了，这一步也就没有可执行的验证。
 * 两边是同一个标准 base64 —— 查看器那一侧解码是
 * `Base64.decode(image.base64, Base64.DEFAULT)`（`ui/blocks/PiImageViewer.kt:689`），而这里
 * 编出来的串不含任何换行或空白，所以那条解码的宽松根本用不上。`minSdk = 26`
 * （`app/build.gradle.kts:42`），`java.util.Base64` 正是 API 26 加入的。
 *
 * ## 空字节 = 没有可打开的图
 *
 * [viewerImage] 对空字节返回 `null`，调用点据此**什么都不做**。不是防御性代码：字节通道
 * 自己就是"空 = 失败"的规矩（`bridge/GuestImageBytes.kt:346`、`:365`、`:484` 三处 0 字节
 * 都走 `fail()`），照抄它是为了不可能出现"打开了一张 0 字节的图、查看器报无法解码"这种界面。
 */
internal object PiMarkdownImageTap {

    /**
     * 这份字节交给查看器时的 wire 值；空字节没有可开的图，返回 `null`。
     *
     * `mimeType` **原样**带过去，不在这里推断：类型由字节通道认（魔数 → 来源声明 → 后缀，
     * `bridge/GuestImageBytes.kt:598-610` 的 `mimeOf`），它能给出的最坏答案是 `image/` 加一个
     * 通配符；这里再补一条规则就是同一件事的第二种说法。
     *
     * 同一批字节的两次调用给出**相等**的 `PiImage`（data class），也就是同一个 base64 ——
     * 这正是"打开、关掉、再打开同一张图"在查看器里是缓存命中的原因：解码的
     * `produceState` 键里带着 `image.cacheKey`（`ui/blocks/PiImageViewer.kt:298`）。
     */
    fun viewerImage(bytes: ByteArray, mimeType: String): PiImage? {
        if (bytes.isEmpty()) return null
        return PiImage(Base64.getEncoder().encodeToString(bytes), mimeType)
    }
}
