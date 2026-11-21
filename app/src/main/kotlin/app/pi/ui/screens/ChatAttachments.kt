package app.pi.ui.screens

import androidx.compose.ui.semantics.contentDescription
import app.pi.ui.blocks.PiImageCache
import app.pi.ui.blocks.decodePiImage
import app.pi.ui.blocks.piImageDecodeGate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pi.i18n.tr
import app.pi.protocol.PiImage
import app.pi.ui.chat.PiFileMentions
import app.pi.ui.components.PiSheet
import app.pi.ui.components.PiType
import app.pi.ui.theme.PiShapes
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import kotlinx.coroutines.sync.withPermit

/** 聊天页附件：选择后的压缩、落到工作区的拷贝、缩略图。从 ChatScreen.kt 拆出。 */


/**
 * Put a picked non-image file where the agent can read it, and answer with the
 * workspace-relative path to insert.
 *
 * ## Why a copy, and not a device path
 *
 * This is the implementation of the sentence that used to sit above the *old* code
 * as its stated design — 「把文件放到 agent 能读到的地方」 — and which that code did
 * not follow: it guessed a phone path instead (`/storage/emulated/0/…`, a
 * `_data` column, `raw:`), and on any source that would not answer with one it failed
 * with 「这个来源没有可用的文件路径」. On Android 10+ that is nearly every source: the
 * system picker hands back a `content://` URI from Files/Downloads/Recent/cloud
 * providers, `_data` is unset for a virtual document, and a cloud provider has no
 * filesystem path at all. The user's report — 「除了图片，其他的东西我点完发送，它说
 * 『这个来源没有可用的文件路径』」 — is that failure, and it is deterministic rather
 * than intermittent.
 *
 * A guessed path was also only ever *half* a fix. The proot bind makes
 * `/storage/emulated/0/…` the same spelling inside the guest, but the guest runs as
 * this app's uid under `untrusted_app` (`bridge/DeviceWorkspace.kt:54-58`): a shared
 * path is visible and may still be unreadable, and the app cannot tell the difference
 * from here. The failure then happens *inside pi*, about a file the user never named —
 * strictly worse than the error above.
 *
 * A copy has neither problem. The workspace is app-private storage that the engine
 * already mounts (`PtyLauncher.workspaceHost`), the app writes the bytes itself, and
 * the path handed over is one the guest demonstrably owns. It is also the shape pi's
 * own terminal uses for a dropped file: the drop becomes a **path in the prompt**, not
 * a payload.
 *
 * The path is **relative to the workspace** (`attachments/report.pdf`), which is what
 * `@` mentions insert too (`ui/chat/PiFileMentions.kt`). Relative resolves against the
 * engine's cwd, which *is* the workspace, so the guest/host "one directory, two
 * spellings" trap (`GuestWorkspacePath`) never arises. The old comment's worry that
 * the two shapes would collide is answered by making them the same shape.
 *
 * ## What the user is told
 *
 * Success names the file it placed, because the workspace is a directory the user can
 * see from 工作区 and an unexplained new file there is worse than the copy. Failure
 * gives the actual cause ([WorkspaceCopy]) and inserts **nothing** — never a
 * `content://` string and never a guessed path.
 *
 * ## The bound is the **disk**, not the message
 *
 * This used to be bounded by [AttachmentBudget.MESSAGE_BYTES] (23.95 MB) — the number that
 * exists because a message's inline base64 must fit inside one JSONL record. That number
 * has nothing to do with this path: what is produced here is a **relative path in the
 * composer** (`attachments/<name>`, a dozen bytes) and the agent reads the file off disk
 * itself. The user's report is the consequence — 「发个文件还说太大，复制不进去，太傻逼了」:
 * a 30 MB PDF was refused, and the copy is exactly how it was supposed to become readable.
 *
 * So the only bound here is free space, and it is checked twice: up front and then against
 * every chunk through [AttachmentBudget.workspaceSpace], with
 * [AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES] left unspent. `StatFs` is read **once**
 * per copy (`availableBytesOf`) and the running total is subtracted locally — this file is
 * the only writer of the target, so a second syscall per chunk would buy nothing but
 * latency on a 2 GB stream. A filesystem that will not report its free space is
 * [AttachmentBudget.DiskSpace.Unknown]: the copy proceeds, and a genuine failure surfaces
 * as [WorkspaceCopy.WriteFailed].
 *
 * The copy is still **streamed**: one 64 KiB buffer, so a 2 GB provider document never
 * lives in memory. What changed is only which question stops it.
 */
internal fun copyIntoWorkspace(context: Context, uri: android.net.Uri): WorkspaceCopy {
    // Opened first, so a source that will not open is [WorkspaceCopy.Unreadable] rather
    // than a directory problem — the same order, and the same two failure arms, as before.
    val source = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        ?: return WorkspaceCopy.Unreadable
    return source.use { input -> copyAttachment(context, input, displayNameOf(context, uri)) }
}


/**
 * The same copy, from bytes the caller already holds: the **inline fallback** for a picked
 * image ([fallbackInline] in the picker callback).
 *
 * It exists because an image that cannot be inlined is still a file the agent should be
 * able to read, and because the bytes in hand are the user's own pick — writing the
 * *re-encoded* picture instead would hand the agent a different file than the one that was
 * chosen. The name goes through the same sanitiser and the same
 * `attachments/`-containment guard as any other copy, and the space rule is the same one.
 */
internal fun copyBytesIntoWorkspace(context: Context, bytes: ByteArray, displayName: String?): WorkspaceCopy =
    java.io.ByteArrayInputStream(bytes).use { copyAttachment(context, it, displayName) }


/** Where one copy will land, after every guard has passed. */
private class AttachmentTarget(val directory: java.io.File, val file: java.io.File, val relativePath: String)


/**
 * The file one copy will land in, or null when the workspace cannot be created or the name
 * would not land inside `attachments/`.
 *
 * Both guards are unchanged and neither depends on the other: the name comes from
 * [app.pi.ui.chat.sanitizeAttachmentName] and [app.pi.ui.chat.uniqueAttachmentName], and
 * then the **canonical path** of the file about to be written must be exactly
 * `<attachments>/<that name>`. A name that escapes the directory is a path this app would
 * be writing on a stranger's behalf.
 */
private fun attachmentTarget(context: Context, displayName: String?): AttachmentTarget? {
    val directory = java.io.File(app.pi.runtime.PtyLauncher.workspaceHost(context), ATTACHMENTS_DIR)
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val name = app.pi.ui.chat.uniqueAttachmentName(
        app.pi.ui.chat.sanitizeAttachmentName(displayName),
    ) { candidate -> java.io.File(directory, candidate).exists() }
    val target = java.io.File(directory, name)
    val root = runCatching { directory.canonicalPath }.getOrNull() ?: return null
    val resolved = runCatching { target.canonicalPath }.getOrNull() ?: return null
    if (resolved != "$root${java.io.File.separator}$name") return null
    return AttachmentTarget(directory, target, "$ATTACHMENTS_DIR/$name")
}


/**
 * Stream [input] into a new file under `attachments/`, bounded only by free space.
 *
 * The space reading is taken once, before the first byte is written ([availableBytesOf]);
 * the running total is then compared against it chunk by chunk. That is the honest reading
 * for a number the user is shown: if some other writer fills the disk while this copy runs,
 * the `write` throws and the answer is [WorkspaceCopy.WriteFailed] — never a made-up figure.
 */
private fun copyAttachment(context: Context, input: java.io.InputStream, displayName: String?): WorkspaceCopy {
    val target = attachmentTarget(context, displayName) ?: return WorkspaceCopy.WriteFailed
    val available = availableBytesOf(target.directory)
    val margin = AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES
    val streamed = runCatching {
        target.file.outputStream().use { sink -> copyStreamed(input, sink, available, margin) }
    }.getOrNull()
    return when (streamed) {
        is Streamed.Done -> WorkspaceCopy.Copied(target.relativePath)

        // **No half file is ever left behind**, for either reason: the user was told this
        // file did not make it, so a truncated copy in 工作区 would be a lie. (Only the
        // over-limit arm deleted before; a mid-write failure used to leave its partial.)
        is Streamed.NoSpace -> {
            runCatching { target.file.delete() }
            WorkspaceCopy.OutOfSpace(
                neededBytes = streamed.neededBytes,
                availableBytes = streamed.availableBytes,
                marginBytes = streamed.marginBytes,
            )
        }

        null -> {
            runCatching { target.file.delete() }
            WorkspaceCopy.WriteFailed
        }
    }
}


/** What one [copyStreamed] did. */
private sealed interface Streamed {
    data object Done : Streamed

    /**
     * The disk ran out at [neededBytes] (a **lower bound** — the source had that many
     * bytes to give). [availableBytes] and [marginBytes] are the two numbers the sentence
     * needs, carried rather than recomputed.
     */
    data class NoSpace(val neededBytes: Long, val availableBytes: Long, val marginBytes: Long) : Streamed
}


/**
 * The streaming loop: one 64 KiB buffer, and [AttachmentBudget.workspaceSpace] asked before
 * every write.
 *
 * `availableBytes` is the free space read once before the copy started; `total` is what has
 * been written. The question asked is about the **next** chunk (`total + read`), so a chunk
 * is never written into the margin. `null` means the filesystem would not say, and then no
 * chunk is refused here.
 */
private fun copyStreamed(
    input: java.io.InputStream,
    sink: java.io.OutputStream,
    availableBytes: Long?,
    marginBytes: Long,
): Streamed {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return Streamed.Done
        if (read == 0) continue
        val needed = total + read
        when (val space = AttachmentBudget.workspaceSpace(needed, availableBytes, marginBytes)) {
            AttachmentBudget.DiskSpace.Room, AttachmentBudget.DiskSpace.Unknown -> {
                sink.write(buffer, 0, read)
                total = needed
            }
            is AttachmentBudget.DiskSpace.Insufficient -> return Streamed.NoSpace(
                neededBytes = space.neededBytes,
                availableBytes = space.availableBytes,
                marginBytes = marginBytes,
            )
        }
    }
}


/**
 * Free bytes on the filesystem [directory] sits on, or null when it cannot be read.
 *
 * `StatFs` on the directory itself, not on the workspace root or the app's `filesDir`: the
 * attachments directory is where the bytes land, and on a device with an adopted external
 * `filesDir` those are not necessarily the same filesystem. A reading of 0 or a throw is
 * reported as **unknown** rather than as "no space" — an unreadable number must not become
 * a refusal (`RuntimeSpaceBudget.shortfall` makes the same call for the same reason).
 */
private fun availableBytesOf(directory: java.io.File): Long? = runCatching {
    android.os.StatFs(directory.path).availableBytes.takeIf { it > 0L }
}.getOrNull()


/** One chunk of a workspace copy. 64 KiB, the same size the old bounded copy used. */
private const val COPY_BUFFER_BYTES = 64 * 1024


/** The picker's own name for a document, or null when the provider will not say. */
internal fun displayNameOf(context: Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column < 0 || !cursor.moveToFirst()) null else cursor.getString(column)
        }
}.getOrNull()?.takeIf { it.isNotBlank() }


/** Where picked non-image files are placed inside the workspace. */
private const val ATTACHMENTS_DIR = "attachments"


/** One picked file that was put into the workspace. */
internal sealed interface WorkspaceCopy {
    /** The workspace-relative path to insert, e.g. `attachments/report.pdf`. */
    data class Copied(val relativePath: String) : WorkspaceCopy

    /** The provider would not open the document: permission, or it is gone. */
    data object Unreadable : WorkspaceCopy

    /**
     * The disk filled up (or was already too full to keep
     * [AttachmentBudget.WORKSPACE_SPACE_MARGIN_BYTES] spare); the partial file was removed.
     *
     * Not a size limit: this path's only bound is free space, and the numbers come from the
     * measurement that stopped the copy rather than from a constant.
     */
    data class OutOfSpace(
        val neededBytes: Long,
        val availableBytes: Long,
        val marginBytes: Long,
    ) : WorkspaceCopy

    /** The workspace could not be created, or the copy failed — disk full, read-only. */
    data object WriteFailed : WorkspaceCopy
}


/**
 * One pending attachment: a real thumbnail, not a file name.
 *
 * The decode is the same shared one the transcript uses ([decodePiImage]) and it
 * runs off the main thread, because a camera JPEG is not composition work. A
 * payload the platform codec refuses still gets a tappable chip with its MIME
 * type, so the user can see *what* will be sent and remove it.
 *
 * The decode is bounded by [ATTACHMENT_THUMB_SIZE] instead of left at its natural size: a
 * staged photo is up to pi's 2000 px ceiling and this paints a 48 dp square. The note
 * inside has the arithmetic and the shared decode gate.
 */
private val ATTACHMENT_THUMB_SIZE = 56.dp


@Composable
internal fun AttachmentThumb(
    image: PiImage,
    index: Int,
    onRemove: () -> Unit,
) {
    val thumbPx = with(LocalDensity.current) { ATTACHMENT_THUMB_SIZE.roundToPx() }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, image.base64, thumbPx) {
        value = withContext(Dispatchers.IO) {
            piImageDecodeGate.withPermit {
                PiImageCache.readThrough(image.base64, thumbPx, thumbPx) {
                    decodePiImage(image.base64, thumbPx, thumbPx)
                }
            }
        }
    }
    val palette = PiTheme.palette
    Box(
        modifier = Modifier
            .padding(end = 8.dp, top = 4.dp)
            .size(ATTACHMENT_THUMB_SIZE + 6.dp),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(ATTACHMENT_THUMB_SIZE)
                .clickable(onClickLabel = tr("移除第 ${index + 1} 张图片", "Remove image ${index + 1}"), onClick = onRemove),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(contentAlignment = Alignment.Center) {
                val decoded = bitmap
                if (decoded != null) {
                    Image(
                        bitmap = decoded.asImageBitmap(),
                        contentDescription = tr("待发送的第 ${index + 1} 张图片，点击移除", "Pending image ${index + 1}, tap to remove"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        image.mimeType.substringAfter('/').ifEmpty { tr("图片", "Image") },
                        style = PiTheme.text.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Surface(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = palette.error,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "×",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}


/**
 * Read at most `limit + 1` bytes, so "bigger than the limit" is answered without
 * ever materialising the whole input.
 *
 * `InputStream.readNBytes`/`readBytes()` allocate for whatever the provider offers;
 * a gallery or cloud provider can offer hundreds of megabytes. One byte past the
 * limit is all the caller's comparison needs, and it bounds both the allocation and
 * the time spent on a file that is about to be rejected.
 *
 * The limit callers pass is [AttachmentBudget.MAX_PICKED_IMAGE_BYTES]: the app's own
 * memory guard, not pi's rule and not the message's budget — pi's resize has no input
 * bound at all, and the image is compressed after this read, so a file over the guard
 * is refused for what it costs to hold, not for how big it would have been on the wire.
 */
internal fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val cap = limit + 1
    val out = java.io.ByteArrayOutputStream(minOf(cap, 64 * 1024))
    val buffer = ByteArray(64 * 1024)
    while (out.size() < cap) {
        val read = input.read(buffer, 0, minOf(buffer.size, cap - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}


/**
 * pi's inline-image normalization on Android's codecs — the thin half that needs a
 * `Bitmap`.
 *
 * Every number and every ordering decision comes from [AttachmentBudget] (pi's
 * `maxWidth`/`maxHeight`, the 4.5 MB base64 ceiling, the quality ladder, the
 * three-quarter shrink, and which encodings to try when); this function only performs
 * them. That split is deliberate: it is the difference between "the harness checks the
 * sizes and orderings that decide whether a picture fits" and "the harness checks
 * nothing, because `BitmapFactory` needs a phone".
 *
 * ## pi's flow, and where this differs
 *
 *  0. **EXIF 方向先摆正**（`image-resize-core.ts:74-76` → `exif-orientation.ts`）：pi 在算
 *     `originalWidth`/`originalHeight` **之前**就把图转正，所以后面每一步 —— 快速路径的判断、
 *     目标尺寸、3/4 收缩、质量阶梯 —— 看到的都是**摆正后**的长宽。App 这边这一步是
 *     `PiExifOrientation.read` 读出方向、`Matrix` 施加同一个置换（`BitmapFactory` 自己不看
 *     EXIF，`Bitmap.compress` 也不写 EXIF）。少了这一步，竖拍的照片（像素 4032×3024 +
 *     EXIF orientation 6）会被算成 2000×1500 的横图 —— 模型收到一张侧倒的图。
 *  1. **Fast path** (`image-resize-core.ts:82-93`): a picture already within both limits
 *     goes on the wire **byte for byte**, with its own MIME type. This is the only path
 *     that does not re-encode, and it is what keeps an ordinary photo from being
 *     re-compressed for nothing. One condition is added to pi's: the MIME must be one pi
 *     accepts inline ([AttachmentBudget.piInlineSupported]). pi converts everything else
 *     to PNG first (`image-process.ts:49-65`); without this, a `image/heic` under the
 *     limits would be forwarded as HEIC and rejected by the provider — a case pi cannot
 *     have.
 *  2. **Resize loop** (`:95-160`): clamp the long edge to 2000, then try the encodings at
 *     that size, then shrink by three quarters and try again, down to 1×1. pi uses
 *     Lanczos3 through Photon; Android's `Bitmap.createScaledBitmap(..., filter = true)`
 *     is bilinear, which is a resampling difference, not a size or an ordering one — it
 *     is listed as device-only in the change report.
 *
 *     **为什么这里不给 `BitmapFactory` 设 `inSampleSize`**：pi 的每一轮收缩都是从**全分辨率**
 *     的 `image` 重新 `photon.resize` 的（`image-resize-core.ts:109`，`image` 是 `:74` 解码出来
 *     的那一张）。解码期先按整倍数丢掉一半像素，等于让第 2 轮之后的每一次收缩都从比 pi 更少的
 *     像素重采样 —— 尺寸对了，细节比 pi 少。省内存的收益不值得在这里制造一个内容层面的差异
 *     （峰值内存是设备问题，列在变更报告里）。
 *  3. **候选顺序与源无关**（`AttachmentBudget.encodings`）：pi 对**每一个**进循环的源都先推
 *     PNG 候选，装不下再走 JPEG 阶梯（`image-resize-core.ts:112-114`、`:122`）—— 包括一张
 *     不透明的相机 JPEG。这里照抄，不按 MIME 也不按 `Bitmap.hasAlpha()` 分流：旧的「只在
 *     PNG 源或带 alpha 时才试 PNG」把截图上的字压糊了（同一张 2400×600 的图形，PNG 1 184 593 B
 *     对 JPEG80 134 870 B）。两笔随之而来的价钱（每次重编码多一次全尺寸 PNG 编码；装得下时
 *     内联体积大几倍，于是同一条消息装得下的图变少）写在 `AttachmentBudget` 的类 KDoc 里。
 *
 * Returns null when the bytes do not decode, when the orientation copy cannot be allocated,
 * or when even 1×1 cannot be encoded under [limits]. Which of the three happened is not
 * knowable here, so the caller says the general thing.
 *
 * [limits] is `AttachmentBudget.limitsFor(state.meta.model?.inputLimits)`: the model's own
 * profile when pi published one, pi's defaults otherwise, with the record's budget as a
 * ceiling. The **fast path** below uses the same limits, so a model with a smaller profile
 * no longer forwards an original the model cannot take.
 *
 * ## `autoResize = false`：pi 的 `images.autoResize`
 *
 * pi 的开关（`settings-manager.ts:68` 定义、`:1404` 的 `getImageAutoResize()`，默认 `true`；
 * 唯一的分支在 `image-process.ts:86`）关掉的是**缩放**，不是归一化 —— `:78` 的
 * `normalizeImage` 照跑：`image/png|jpeg|gif|webp` 原样通过，其余（heic/bmp/tiff）**转成
 * PNG**（`image-convert.ts:23-30`，那次转换里也摆正 EXIF），然后 base64 原样送出。所以这条路
 * 交给 [withAutoResizeOff] 做同样两件事，尺寸、候选序、质量阶梯一个都不参与。
 *
 * **消息预算没有跟着关掉，这是 App 的边界、不是 pi 的。** 预压缩在这边还担着另一件事：暂存的
 * base64 会进 composer 状态，而一条消息的 base64 合计受
 * `JsonlFramer.DEFAULT_MAX_RECORD_CHARS − 64 KiB`（[AttachmentBudget.MESSAGE_BASE64_CHARS]）这条
 * **传输**上限约束 —— 超了的那条记录连 App 自己都读不回来（类 KDoc 有这段历史）。所以关掉自动
 * 缩放只是不再「为了模型的档位」压缩；判定仍在调用方（`AttachmentBudget.decide`），装不下时照样
 * 降级成「放进工作区 + 给路径」，而不是把预压缩和预算一起删掉。
 *
 * Runs on `Dispatchers.IO` — one full-size decode plus one encode is tens of
 * milliseconds and a few megabytes, which must not happen on the frame thread. A model
 * profile *larger* than pi's default makes that first attempt proportionally more expensive
 * (a 4000px decode, not 2000px): that is the cost of no longer throwing away detail the
 * model accepts, and `maxBase64Chars` is still what decides when the loop stops.
 */
internal fun compressAttachment(
    bytes: ByteArray,
    mime: String,
    limits: AttachmentBudget.Limits,
    autoResize: Boolean,
): PiImage? {
    // Header only: `inJustDecodeBounds` reads the size without allocating pixels, which
    // is also how the fast path is decided without decoding anything.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val storedWidth = bounds.outWidth
    val storedHeight = bounds.outHeight
    // 这里同时是「这堆字节到底是不是一张图」的判据：`images.autoResize: false` 那条路也走它，
    // 所以关掉预压缩不会把一份解不开的字节当成图发出去。
    if (storedWidth <= 0 || storedHeight <= 0) return null

    // EXIF 方向**先**读，而且要用它把「存储长宽」换成「摆正后长宽」：pi 的
    // `originalWidth`/`originalHeight` 取自摆正之后的图（`image-resize-core.ts:74-79`），
    // 而 `BitmapFactory` 给的是**存储**尺寸。摆正只换方向、不换像素数，所以解码取样
    // （`inSampleSize`）仍然按存储像素算 —— 本函数不设 `inSampleSize`（见 KDoc），这条只是
    // 提醒后来者别把两者混在一起：解码出来还是 storedWidth×storedHeight 个像素。
    val orientation = PiExifOrientation.read(bytes)
    val transform = PiExifOrientation.transformFor(orientation)
    val (width, height) = PiExifOrientation.orientedSize(storedWidth, storedHeight, transform)

    // 用户关掉了 pi 的 `images.autoResize`：不做预压缩（pi 的同一分支也不做）。
    if (!autoResize) return withAutoResizeOff(bytes, mime, transform)

    if (width <= limits.maxWidth &&
        height <= limits.maxHeight &&
        AttachmentBudget.piInlineSupported(mime) &&
        AttachmentBudget.base64Chars(bytes.size) < limits.maxBase64Chars
    ) {
        return PiImage(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)
    }

    // 50MP/108MP 手机相机原图若不设 inSampleSize 直接全分辨率解成 ARGB_8888，单张就需要
    // 200~400 MB 堆内存，中端机直接 OOM。这里按 2 的幂次安全预采样：只要解出来的像素仍
    // 至少保留目标尺寸的 2 倍（例如目标 2000px → 至少保留 4000px），既为双线性缩小保留充足
    // 超采样细节，又能把 50MP~200MP 照片的峰值内存压低 4~16 倍。
    val sampleSize = computeSafeSampleSize(storedWidth, storedHeight, limits)
    val source = runCatching {
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            },
        )
    }.getOrNull() ?: return null
    // 摆正发生在**进入 3/4 + 质量阶梯循环之前**，与 pi 同序（`:74-76` 在 `:126-154` 之前）。
    val oriented = applyOrientation(source, transform)
    if (oriented == null) {
        source.recycle()
        return null
    }
    try {
        // 对不透明相机 JPEG 跳过无意义的全尺寸无损 PNG 编码：相机照片编成 2000px PNG 耗时
        // 高达数百毫秒且体积几乎必然远大于 JPEG80；对 PNG / WebP / 截图 / 带透明通道图片
        // 完整保留 pi 1.5.2 的 PNG vs 首档 JPEG 裁决，确保截图文字清晰无损。
        val skipLosslessPng = mime.substringBefore(';').trim().equals("image/jpeg", ignoreCase = true) &&
            !oriented.hasAlpha()
        var pngAtSize: PiImage? = null
        var verdictPending = false
        for (attempt in AttachmentBudget.attemptPlan(width, height, limits)) {
            if (skipLosslessPng && attempt.encoding is AttachmentBudget.Encoding.Png) continue
            val scaled = scaleForAttachment(oriented, attempt.width, attempt.height) ?: continue
            try {
                val encoded = encodeForAttachment(scaled, attempt.encoding)
                if (attempt.encoding is AttachmentBudget.Encoding.Png) {
                    pngAtSize = encoded
                    verdictPending = true
                    continue
                }
                if (verdictPending) {
                    verdictPending = false
                    val png = pngAtSize
                    pngAtSize = null
                    val ordered = if (AttachmentBudget.jpegBeforePng(png?.base64?.length, encoded?.base64?.length)) {
                        listOf(encoded, png)
                    } else {
                        listOf(png, encoded)
                    }
                    // pi's own test is strict (`encodedSize < maxBytes`).
                    for (candidate in ordered) {
                        if (candidate != null && candidate.base64.length < limits.maxBase64Chars) return candidate
                    }
                    continue
                }
                if (encoded == null) continue
                if (encoded.base64.length < limits.maxBase64Chars) return encoded
            } finally {
                // `scaleForAttachment` 在目标就是自己的尺寸时原样返回，那份由外层 finally 负责。
                if (scaled !== oriented) scaled.recycle()
            }
        }
        return null
    } finally {
        // `applyOrientation` 在方向为 1 时返回的就是 `source`，那一份只回收一次。
        if (oriented !== source) oriented.recycle()
        source.recycle()
    }
}

/**
 * 计算安全的 2 次幂 `inSampleSize`：保证解码后两轴都不低于目标最大边的 2 倍（超采样余量），
 * 仅在超高像素原图（如 50MP/108MP/200MP 相机照片）时启用降采样防止 `OutOfMemoryError`。
 */
private fun computeSafeSampleSize(
    storedWidth: Int,
    storedHeight: Int,
    limits: AttachmentBudget.Limits,
): Int {
    val ceiling = maxOf(limits.maxWidth, limits.maxHeight).coerceAtLeast(1) * 2
    var sample = 1
    while (storedWidth / (sample * 2) >= ceiling && storedHeight / (sample * 2) >= ceiling) {
        sample *= 2
    }
    return sample
}


/**
 * `images.autoResize: false` 那一条路：不做任何缩放，只做 pi 的 `normalizeImage` 那一步。
 *
 *  - pi 能直接内联的 MIME（[AttachmentBudget.piInlineSupported]）：`bytes` **逐字节**转发，
 *    连 EXIF 都留在里面 —— pi 也是把 `normalized.bytes` 原样 base64 送出
 *    （`image-process.ts:113-118`），所以这里与「快速路径」不是同一件事：那条还要求尺寸与
 *    字节数都在档位之内，这条一个都不看。
 *  - 其余 MIME：解码 → 摆正 → 按**原始尺寸**编码 PNG，因为 pi 会先把它转成 PNG
 *    （`image-process.ts:55-64` → `image-convert.ts:23-30`）。这里没有 `inSampleSize`：
 *    采样会让输出与 pi 那张全分辨率 PNG 不同，而这条路的存在意义就是「不替用户改尺寸」。
 *
 * 失败（解码不了、位图分配失败、编码失败）返回 null，调用方按「内联不成」降级 —— 与压缩那条路
 * 同一个出口，但句子不同（那边说「压不出足够小」，这边说不成的是转换）。
 *
 * [transform] 由调用方传入而不是在这里重读一遍 EXIF：方向只解析一次，两条分支用的是同一个值。
 */
private fun withAutoResizeOff(
    bytes: ByteArray,
    mime: String,
    transform: PiExifOrientation.Transform,
): PiImage? {
    if (AttachmentBudget.piInlineSupported(mime)) {
        return PiImage(Base64.encodeToString(bytes, Base64.NO_WRAP), mime)
    }

    val source = runCatching {
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }.getOrNull() ?: return null
    try {
        val oriented = applyOrientation(source, transform) ?: return null
        try {
            return encodeForAttachment(oriented, AttachmentBudget.Encoding.Png)
        } finally {
            if (oriented !== source) oriented.recycle()
        }
    } finally {
        source.recycle()
    }
}


/**
 * 把解码后的位图摆正到显示方向，用的是 [PiExifOrientation.matrixFor] 那六个数字。
 *
 * `filter = false` 不是性能选择：那张映射是**整数到整数的置换**，重采样（`filter = true`）
 * 只会把每个像素和它邻居插在一起，把一张无损的旋转弄成一张糊的旋转。方向为 1（含「读不出
 * 方向」）时返回 `source` 本身，不复制 —— 绝大多数照片走这条，一次多余的位图分配都不该有。
 *
 * 返回 null 只可能是位图分配失败（OOM）；调用方按「压不出来」处理。
 */
private fun applyOrientation(source: Bitmap, transform: PiExifOrientation.Transform): Bitmap? {
    if (transform == PiExifOrientation.Transform.NONE) return source
    val matrix = Matrix().apply {
        setValues(PiExifOrientation.matrixFor(transform, source.width, source.height))
    }
    return runCatching {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }.getOrNull()
}


/**
 * One resize step; the source itself when the target is already its size.
 *
 * 缩得比 2 倍多时分步减半：`createScaledBitmap(filter = true)` 是双线性，只看相邻 2×2 个像素，
 * 一步缩 4000→1500 会直接漏掉中间的像素（锯齿、细线断掉、小字发糊）。pi 用的是 Lanczos3，
 * 看的是整片邻域；每步 ≤ 2 倍的双线性恰好等于 2×2 均值，连着做就是面积平均，细节保留得
 * 接近 Lanczos，且只改**像素怎么来**、不改尺寸与候选顺序（那些仍与 pi 一致）。
 */
private fun scaleForAttachment(source: Bitmap, width: Int, height: Int): Bitmap? {
    if (width == source.width && height == source.height) return source
    var current = source
    return runCatching {
        while (current.width >= width * 2 && current.height >= height * 2) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = next
        }
        val result = if (current.width == width && current.height == height) {
            current
        } else {
            Bitmap.createScaledBitmap(current, width, height, true)
        }
        if (result !== current && current !== source) current.recycle()
        result
    }.getOrElse {
        if (current !== source) current.recycle()
        null
    }
}


/** One encode step, as the wire value it becomes — the base64 the budget counts. */
private fun encodeForAttachment(bitmap: Bitmap, encoding: AttachmentBudget.Encoding): PiImage? {
    val out = java.io.ByteArrayOutputStream()
    val ok = when (encoding) {
        // PNG's quality parameter is ignored by the platform encoder; 100 is what pi's
        // `get_bytes()` is equivalent to (lossless).
        is AttachmentBudget.Encoding.Png -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        is AttachmentBudget.Encoding.Jpeg -> bitmap.compress(Bitmap.CompressFormat.JPEG, encoding.quality, out)
    }
    if (!ok) return null
    return PiImage(
        base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
        mimeType = when (encoding) {
            is AttachmentBudget.Encoding.Png -> "image/png"
            is AttachmentBudget.Encoding.Jpeg -> "image/jpeg"
        },
    )
}


/**
 * The sentence for a file that was put in the workspace **because it could not be inlined**,
 * with [why] being the reason the inline path gave up.
 *
 * It has to say three things, and each one is load-bearing: where the file went (the user
 * can see 工作区), that it did **not** travel inside the message (otherwise the next question
 * is "why did the model not see it"), and that the model can still read it (the path is in
 * the composer, and pi's agent resolves it like any other path).
 *
 * This is not the 「已放入工作区」 notice D47 deleted: that one repeated a path that was
 * already in the composer and nothing else. Here the message's shape differs from what the
 * user asked for, so the composer's own contents cannot explain it.
 */
internal fun inlineDowngradeText(relativePath: String, why: String): String =
    tr(
        "已放进工作区 $relativePath —— $why，所以没有随消息内联；" +
            "模型可以用工具读取这个文件。",
        "Saved to workspace at $relativePath — $why, so it was not inlined with the message. " +
            "The model can still read this file using tools.",
    )


/**
 * The sentence for a copy the disk refused, built only from the measurement that stopped it.
 *
 * 「至少需要」 is deliberate: [WorkspaceCopy.OutOfSpace.neededBytes] is the number of bytes
 * the source had already handed over, which is a lower bound on the file's size, not its
 * size. Saying "needs X" without the qualifier would be a claim this code cannot check.
 */
internal fun workspaceOutOfSpaceText(out: WorkspaceCopy.OutOfSpace): String =
    tr(
        "磁盘空间不足：这个文件至少需要 ${mibLabel(out.neededBytes)} MB，" +
            "工作区所在磁盘可用 ${mibLabel(out.availableBytes)} MB" +
            "（要留 ${mibLabel(out.marginBytes)} MB 余量）。" +
            "它没有被复制进工作区，也没有加进这条消息；腾出空间后可以再选一次。",
        "Not enough disk space: this file requires at least ${mibLabel(out.neededBytes)} MB, " +
            "with ${mibLabel(out.availableBytes)} MB available on the workspace disk " +
            "(${mibLabel(out.marginBytes)} MB reserve required). " +
            "The file was not copied to the workspace or added to this message. Free up space and try again.",
    )


/**
 * One count in MiB, one decimal, locale-stable.
 *
 * A plain formatter rather than a unit conversion: the copies count **base64
 * characters** for pi's ceiling and **bytes** for the message budget, and both are
 * shown in the same MiB so the two numbers can be compared. `%.1f` rather than an
 * integer, because pi's own ceiling is 4.5 and truncating it to "4" would understate
 * what the composer is allowed to send by half a megabyte.
 *
 * The [Long] overload is for the disk figures, which are byte counts from `StatFs` and can
 * legitimately exceed an `Int`.
 */
internal fun mibLabel(count: Int): String =
    String.format(java.util.Locale.US, "%.1f", count / 1024.0 / 1024.0)


internal fun mibLabel(count: Long): String =
    String.format(java.util.Locale.US, "%.1f", count / 1024.0 / 1024.0)

/**
 * 现代多源附件选择面板：相册图片（多选）、任意文件（多选）、粘贴剪贴板图片、快速 `@` 引用工作区文件。
 */
@Composable
internal fun AttachmentPickerSheet(
    hasClipboardImage: Boolean,
    stagedCount: Int,
    maxPerMessage: Int?,
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit,
    onPasteClipboardImage: () -> Unit,
    onInsertMention: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = PiTheme.palette
    PiSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PiSpacing.pageHorizontal)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tr("添加附件", "Add Attachment"),
                    modifier = Modifier.weight(1f),
                    style = PiType.title,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val limitNote = buildString {
                    if (stagedCount > 0) append(tr("已暂存 $stagedCount 张", "$stagedCount staged"))
                    if (maxPerMessage != null) {
                        if (isNotEmpty()) append(" · ")
                        append(tr("单条上限 $maxPerMessage 张", "Max $maxPerMessage per message"))
                    }
                }
                if (limitNote.isNotEmpty()) {
                    Text(
                        text = limitNote,
                        style = PiTheme.text.meta,
                        color = palette.muted,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachmentSourceTile(
                    title = tr("相册图片", "Photos"),
                    subtitle = tr("支持多选与智能压缩", "Multi-select with smart resize"),
                    glyph = tr("图", "IMG"),
                    tint = palette.accent,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onPickPhotos()
                    },
                )
                AttachmentSourceTile(
                    title = tr("本地文件", "Local Files"),
                    subtitle = tr("多选并拷入工作区", "Copy into workspace"),
                    glyph = tr("文", "DOC"),
                    tint = palette.success,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onPickFiles()
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AttachmentSourceTile(
                    title = tr("工作区文件", "Workspace Files"),
                    subtitle = tr("输入 @ 模糊引用", "Reference with @ mention"),
                    glyph = "@",
                    tint = palette.mdLink,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onInsertMention()
                    },
                )
                AttachmentSourceTile(
                    title = tr("剪贴板图片", "Clipboard Image"),
                    subtitle = if (hasClipboardImage) tr("检测到剪贴板图片", "Image detected in clipboard") else tr("读取剪贴板中的图片", "Paste image from clipboard"),
                    glyph = tr("贴", "PST"),
                    tint = if (hasClipboardImage) palette.warning else palette.muted,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onDismiss()
                        onPasteClipboardImage()
                    },
                )
            }
        }
    }
}

@Composable
private fun AttachmentSourceTile(
    title: String,
    subtitle: String,
    glyph: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, PiTheme.palette.borderMuted.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = glyph,
                    style = PiTheme.text.monoSmall.copy(fontWeight = FontWeight.Bold),
                    color = tint,
                )
            }
            Text(
                text = title,
                style = PiType.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = PiTheme.text.meta,
                color = PiTheme.palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
