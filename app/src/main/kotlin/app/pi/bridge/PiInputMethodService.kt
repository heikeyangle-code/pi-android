package app.pi.bridge

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlinePresentationSpec
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 「输入法」这一路能力：目标不是给用户一个好用的键盘，而是做 **pi 的输入层** ——
 * 让 pi 能看到用户正在输入什么、能替用户改写、能提交，并把「用户实际提交了什么」
 * 留成一份内存里的历史。
 *
 * ## 隐私边界（写死在代码里，不靠调用方自觉）
 *
 *  - **密码框一律不记录内容。** 只要 [EditorInfo] 的 inputType 是
 *    `TYPE_TEXT_VARIATION_PASSWORD` / `..._VISIBLE_PASSWORD` / `..._WEB_PASSWORD` /
 *    `TYPE_NUMBER_VARIATION_PASSWORD`，[status] 的 `currentText` 就是 null，
 *    历史里也只留一条 `{"redacted": true, "fieldKind": "password", ...}` —— 证明
 *    「这里发生过输入」，但一个字符都不存。这条判断在写入历史的唯一入口
 *    ([record]) 里做，任何调用路径都绕不过去。
 *  - **只留内存。** 历史与剪贴板历史都是环形缓冲，进程一死就没了；不落盘、不打 Log。
 *  - **键盘 UI 存在的意义是把这件事显示出来。** 只有一行提示 + 一个「完成」键，
 *    用代码搭出来（没有布局资源）；提示行会写明当前正在读取哪个应用的输入框。
 *
 * ## 线程模型
 *
 * `InputConnection` 只能在主线程访问，而 pi 的动作（[insertText] / [replaceText] /
 * [submit] …）多半从桥接的请求线程进来，所以这些动作统一经 [onMain] 投递到主线程再
 * 执行；[currentText] 在非主线程上退化成最近一次快照。任何入口读不到输入框都返回
 * 「明确的失败原因」，绝不抛异常。
 *
 * AndroidManifest 的 `<service android:name=".PiInputMethodService">`（含
 * `android.view.im` → `@xml/pi_input_method` 的 meta-data）由波2 接线，本文件不碰公共清单。
 */
class PiInputMethodService : InputMethodService() {

    /** 提示行；[onStartInputView] 会把它更新成当前正在编辑的包名。 */
    private var hintView: TextView? = null

    /** 当前输入框的语义（包名/字段类型/imeOptions），由 [EditorInfo] 算出。 */
    @Volatile
    private var field: FieldInfo? = null

    /** 最近一次读到的文本；密码框时恒为 null。只在主线程写。 */
    @Volatile
    private var cachedText: String? = null

    /** 上一次的字段全文，用来算「这次新输入了什么」。密码框时恒为 null。 */
    private var lastText: String? = null

    /** 光标选区（[onUpdateSelection] / [onUpdateCursor] 维护），仅作诊断信息。 */
    @Volatile
    private var selectionStart: Int = -1

    @Volatile
    private var selectionEnd: Int = -1

    private val lock = Any()

    /** 输入历史环形缓冲；只留最近 [MAX_HISTORY] 条。 */
    private val buffer = ArrayDeque<Entry>()

    /** 剪贴板历史环形缓冲。 */
    private val clipboardBuffer = ArrayDeque<ClipEntry>()

    @Volatile
    private var clipboardManager: ClipboardManager? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 轮询只在输入视图显示期间运行，兜底少数不触发 onUpdate* 的变化。 */
    private val poller = object : Runnable {
        override fun run() {
            recordFieldChange("poll")
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }

    /** 一个输入框的语义快照。`kind == "password"` 时 [isPassword] 为 true。 */
    private data class FieldInfo(
        val packageName: String?,
        val kind: String,
        val inputType: Int,
        val imeOptions: Int,
        val hint: String?,
    ) {
        val isPassword: Boolean get() = kind == "password"
    }

    /**
     * 一条输入历史。
     *
     * `redacted` 为 true 时**只有** `source/package/fieldKind/at`，没有任何内容 ——
     * 密码框的事件就长这样，这是刻意的：audit 要能看见「发生了输入」，但不该看见内容。
     */
    private data class Entry(
        val at: Long,
        val source: String,
        val packageName: String?,
        val fieldKind: String,
        val redacted: Boolean,
        val inserted: String,
        val text: String,
        val detail: String?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("at", at)
            put("source", source)
            put("package", packageName ?: JSONObject.NULL)
            put("fieldKind", fieldKind)
            put("redacted", redacted)
            if (redacted) {
                put("note", "密码框：只记录发生了一次输入，不记录内容。")
            } else {
                put("inserted", inserted)
                put("chars", text.length)
                put("text", text)
            }
            if (detail != null) put("detail", detail)
        }
    }

    private data class ClipEntry(val at: Long, val text: String)

    /**
     * 一次字段读取的结果。
     *
     * [startOffset] 来自 `ExtractedText.startOffset`（返回文本在整框里的起点）；
     * [partial] 为 true 表示返回的只是分片，拼不回全文 —— 平台在超长输入框上会这样。
     */
    private data class FieldText(val text: String, val startOffset: Int, val partial: Boolean)

    // ------------------------------------------------------------- lifecycle ----

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerClipboard()
    }

    override fun onCreateInputView(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        hintView = TextView(this).apply {
            text = "PI 输入桥：正在读取输入框内容"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val done = Button(this).apply {
            text = "完成"
            isAllCaps = false
            // 「完成」只收起键盘。输入法停在屏幕上没有用途，收起是用户主动结束
            // 这次「读取」的唯一入口。
            setOnClickListener { requestHideSelf(0) }
        }
        row.addView(hintView)
        row.addView(done)
        return row
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        applyEditorInfo(attribute)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyEditorInfo(info)
        hintView?.text = field?.packageName
            ?.let { "PI 输入桥：正在读取 $it 的输入框" }
            ?: "PI 输入桥：正在读取输入框"
        // 立即收一次：应用刚把焦点交给输入框时光标没动过，onUpdate* 不会触发，
        // 只靠轮询会晚 [POLL_MS] 才留下第一条记录。
        recordFieldChange("start")
        mainHandler.removeCallbacks(poller)
        mainHandler.postDelayed(poller, POLL_MS)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        mainHandler.removeCallbacks(poller)
        recordFieldChange("finish")
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        recordFieldChange("finish")
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        // 打字、粘贴、移动光标都会走到这里，是最及时的一次「文本变化」通知；
        // 轮询只当作它偶尔不触达时的兜底。
        recordFieldChange("selection")
    }

    override fun onUpdateCursor(newCursor: Rect?) {
        super.onUpdateCursor(newCursor)
        recordFieldChange("cursor")
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText?) {
        super.onUpdateExtractedText(token, text)
        // 整段文本变化：这是「用户实际输入了什么」最可靠的一次回调。
        recordFieldChange("extracted")
    }

    /**
     * 硬件按键 / 外部键盘路径。只记「不会自己改文本」的按键（回车/方向/制表等）：
     * 可打印键和删除键造成的文本变化已经由 [onUpdateExtractedText] /
     * [onUpdateSelection] 以内容形式记下，这里再记会重复。
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!event.isPrintingKey && keyCode != KeyEvent.KEYCODE_DEL) {
            record("key", inserted = "", detail = KeyEvent.keyCodeToString(keyCode))
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Android 11+ 的内联补全注册。低版本（本应用 minSdk 26）直接返回 null 优雅降级。
     *
     * 这里登记一个最小的呈现规格：桥接暂时没有自己的候选 UI，但把它注册出来，
     * 系统才会把内联补全这条通道保留给当前输入法（也是波2 将来接候选的挂点）。
     */
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            val spec = InlinePresentationSpec.Builder(Size(120, 40), Size(360, 120)).build()
            InlineSuggestionsRequest.Builder(listOf(spec)).build()
        }.getOrNull()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(poller)
        unregisterClipboard()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 读 ----

    /**
     * 当前输入框里的文本，读不到返回 null。
     *
     * [InputConnection] 只允许在主线程访问，而调用方（`/app/health`、导出）多半在
     * 桥接的请求线程上。所以非主线程一律返回上一次快照缓存 —— 宁可返回略旧的文本，
     * 也不要跨线程访问一个非线程安全对象。
     */
    fun currentText(): String? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            readField()?.let {
                cachedText = if (field?.isPassword == true) null else it.text
                return cachedText
            }
        }
        return cachedText
    }

    /**
     * 最近 [limit] 条输入历史，最新的一条在最后。
     *
     * 每条只记 [clip] 后的摘要：缓冲区的用途是「回溯用户刚才输入/提交了什么」，
     * 不是完整归档，保留全文只会让一次 `status()` 回复变成几十 KB。
     */
    fun readHistory(limit: Int = MAX_HISTORY): JSONArray {
        val cap = limit.coerceIn(1, MAX_HISTORY)
        return synchronized(lock) {
            val records = buffer.toList()
            JSONArray().apply {
                for (index in (records.size - cap).coerceAtLeast(0) until records.size) {
                    put(records[index].toJson())
                }
            }
        }
    }

    fun clearHistory(): JSONObject {
        val removed = synchronized(lock) {
            val size = buffer.size
            buffer.clear()
            size
        }
        return JSONObject().put("ok", true).put("cleared", removed)
    }

    /** 最近 [limit] 条剪贴板历史，最新的一条在最后。 */
    fun readClipboardHistory(limit: Int = MAX_CLIPBOARD): JSONArray {
        val cap = limit.coerceIn(1, MAX_CLIPBOARD)
        return synchronized(lock) {
            val records = clipboardBuffer.toList()
            JSONArray().apply {
                for (index in (records.size - cap).coerceAtLeast(0) until records.size) {
                    put(
                        JSONObject()
                            .put("at", records[index].at)
                            .put("text", records[index].text),
                    )
                }
            }
        }
    }

    /** 给 `/app/health` 用的自述：是否启用/是否默认、当前字段类型、记录条数。 */
    fun status(): JSONObject = JSONObject().apply {
        val info = field
        val password = info?.isPassword == true
        val text = currentText()
        put("running", true)
        put("enabled", available(this@PiInputMethodService))
        put("default", isDefaultInputMethod(this@PiInputMethodService))
        put("package", info?.packageName ?: JSONObject.NULL)
        put("fieldKind", info?.kind ?: "unknown")
        put("isPassword", password)
        put("imeOptions", info?.imeOptions ?: 0)
        put("fieldHint", if (password) JSONObject.NULL else info?.hint?.let { clip(it, 80) } ?: JSONObject.NULL)
        put("selectionStart", selectionStart)
        put("selectionEnd", selectionEnd)
        // 密码框不报内容，连长度都不报：长度本身也能泄密（例如 PIN 位数）。
        put("currentText", if (password) JSONObject.NULL else text?.let { clip(it) } ?: JSONObject.NULL)
        put("currentTextChars", if (password) 0 else text?.length ?: 0)
        put("historySize", synchronized(lock) { buffer.size })
        put("clipboardHistorySize", synchronized(lock) { clipboardBuffer.size })
        put("history", readHistory())
        put("clipboard", readClipboardHistory())
    }

    // ------------------------------------------------------------------ 写 ----

    /** 在当前光标处插入文本（`commitText`）。 */
    fun insertText(text: String): JSONObject = withConnection("insertText") { connection ->
        val applied = connection.commitText(text, CURSOR_END)
        if (applied) record("pi-insert", text, null)
        applied
    }

    /** 设置并显示组合区文本（`setComposingText`），用于预编辑。 */
    fun setComposingText(text: String): JSONObject = withConnection("setComposingText") { connection ->
        val applied = connection.setComposingText(text, CURSOR_END)
        if (applied) record("pi-composing", text, null)
        applied
    }

    /**
     * 用 [text] 替换整个输入框内容。
     *
     * Android 13 给 `InputConnection` 加了原生 `replaceText`，这里优先用它；低版本
     * 退化成「删掉光标前后全部内容再提交」——两者对调用方的语义一致（整框替换）。
     */
    fun replaceText(text: String): JSONObject = withConnection("replaceText") { connection ->
        val applied = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val length = (connection.getTextBeforeCursor(TEXT_WINDOW, 0)?.length ?: 0) +
                (connection.getTextAfterCursor(TEXT_WINDOW, 0)?.length ?: 0)
            connection.replaceText(0, length, text, CURSOR_END, null)
        } else {
            val before = connection.getTextBeforeCursor(TEXT_WINDOW, 0)?.length ?: 0
            val after = connection.getTextAfterCursor(TEXT_WINDOW, 0)?.length ?: 0
            connection.deleteSurroundingText(before, after) && connection.commitText(text, CURSOR_END)
        }
        if (applied) record("pi-replace", text, null)
        applied
    }

    /** 删除光标前 [before] 个、后 [after] 个字符。 */
    fun deleteSurrounding(before: Int, after: Int): JSONObject = withConnection("deleteSurrounding") { connection ->
        val applied = connection.deleteSurroundingText(before.coerceAtLeast(0), after.coerceAtLeast(0))
        if (applied) {
            record("pi-delete", "", "before=${before.coerceAtLeast(0)} after=${after.coerceAtLeast(0)}")
        }
        applied
    }

    /** 设置选区（[start] == [end] 即移动光标）。 */
    fun setSelection(start: Int, end: Int): JSONObject = withConnection("setSelection") { connection ->
        connection.setSelection(start.coerceAtLeast(0), end.coerceAtLeast(0))
    }

    /**
     * 提交当前编辑器动作（发送/搜索/完成…）。
     *
     * 动作取自输入框的 `imeOptions`；取不到时回落到 `IME_ACTION_DONE`，
     * 这样在普通输入框里等于「回车」。
     */
    fun submit(): JSONObject = withConnection("submit") { connection ->
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val masked = options and EditorInfo.IME_MASK_ACTION
        val action = if (masked == EditorInfo.IME_ACTION_NONE) EditorInfo.IME_ACTION_DONE else masked
        val applied = connection.performEditorAction(action)
        if (applied) record("pi-submit", "", null)
        applied
    }

    /** 执行一次明确的编辑器动作码（`EditorInfo.IME_ACTION_*`）。 */
    fun performEditorAction(action: Int): JSONObject = withConnection("performEditorAction") { connection ->
        val applied = connection.performEditorAction(action)
        if (applied) record("pi-action", "", "action=$action")
        applied
    }

    // ------------------------------------------------------- 写入的实现细节 ----

    /**
     * 所有写操作统一走这里：投递到主线程、拿当前连接、把结果翻译成
     * `{ok:true,...}` 或 `{ok:false, reason:...}`，永不抛异常。
     */
    private fun withConnection(action: String, block: (InputConnection) -> Boolean): JSONObject {
        val result = onMain {
            val connection = currentInputConnection
                ?: return@onMain failure(action, "没有可写的输入框（本应用不是当前输入法，或输入框没有焦点）。")
            runCatching { block(connection) }.fold(
                onSuccess = { applied ->
                    if (applied) {
                        JSONObject().put("ok", true).put("action", action)
                    } else {
                        failure(action, "输入框拒绝了这次操作（可能是只读控件或自绘输入框）。")
                    }
                },
                onFailure = { failure(action, "执行失败：${it.message ?: it.javaClass.simpleName}") },
            )
        }
        return result ?: failure(action, "主线程没有及时响应（等待超时 ${MAIN_TIMEOUT_MS}ms）。")
    }

    private fun failure(action: String, reason: String): JSONObject =
        JSONObject().put("ok", false).put("action", action).put("reason", reason)

    /** 把 [block] 放到主线程执行；已在主线程则直接执行。超时返回 null。 */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val gate = CountDownLatch(1)
        val holder = AtomicReference<T>()
        mainHandler.post {
            runCatching { holder.set(block()) }
            gate.countDown()
        }
        return if (gate.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) holder.get() else null
    }

    // ----------------------------------------------------------- 历史记录 ----

    /**
     * 读一次字段全文，并在文本真的变化时落一条历史。
     *
     * 之所以用「全文差分」而不是监听某个 key：本输入法的键盘没有字母键，用户的输入
     * 可能来自系统语音、外部键盘或应用自己的粘贴，唯一可靠的共同信号是输入框文本
     * 的变化，所以差分才是「用户实际提交了什么」的忠实来源。
     */
    private fun recordFieldChange(source: String) {
        val info = field
        val read = readField() ?: return
        if (info?.isPassword == true) {
            // 密码框：连「上一次的全文」都不缓存（缓存本身就是记录内容）。
            // 每次变化只留一条 redacted 事件。
            cachedText = null
            lastText = null
            record(source, inserted = "", detail = null)
            return
        }
        cachedText = read.text
        val previous = lastText
        if (previous == read.text) return
        lastText = read.text
        val inserted = if (previous == null) "" else insertedFragment(previous, read.text)
        record(source, inserted, null)
    }

    /** 写入历史缓冲；这是唯一入口，密码框的「不记内容」在这里兜底。 */
    private fun record(source: String, inserted: String, detail: String?) {
        val info = field
        val password = info?.isPassword == true
        // 刷新一次全文，作为这条记录的上下文；密码框不读、也不缓存。
        if (!password) readField()?.let { cachedText = it.text }
        val entry = Entry(
            at = System.currentTimeMillis(),
            source = source,
            packageName = info?.packageName,
            fieldKind = info?.kind ?: "unknown",
            redacted = password,
            inserted = if (password) "" else clip(inserted, 200),
            text = if (password) "" else clip(cachedText ?: "", 400),
            detail = detail,
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_HISTORY) buffer.removeFirst()
        }
    }

    private fun applyEditorInfo(info: EditorInfo?) {
        if (info == null) {
            field = null
            lastText = null
            return
        }
        val kind = fieldKind(info.inputType)
        val password = kind == "password"
        field = FieldInfo(
            packageName = info.packageName?.toString(),
            kind = kind,
            inputType = info.inputType,
            imeOptions = info.imeOptions,
            // 密码框连 hint 都不留：hint 偶尔会带上「请输入 6 位密码」这类信息。
            hint = if (password) null else info.hintText?.toString()?.takeIf { it.isNotBlank() },
        )
        lastText = null
        // 切到密码框时把上一次（可能不是密码框的）文本缓存一起清掉，避免它残留到
        // status() 的任何一条路径上。
        if (password) cachedText = null
    }

    /**
     * 读字段全文。
     *
     * 先试 `getExtractedText`：`partialStartOffset < 0` 表示返回的就是整个输入框，
     * 一次就能拿到全文。否则（分片，[FieldText.partial] 为 true）退回
     * `getTextBeforeCursor` + `getTextAfterCursor` 拼出光标两侧的完整窗口；
     * 两者都不可用时，至少把分片连同 `startOffset` 报出来。
     */
    private fun readField(): FieldText? {
        val connection = currentInputConnection ?: return null
        val request = ExtractedTextRequest().apply {
            hintMaxChars = TEXT_WINDOW
            hintMaxLines = 16
            flags = 0
        }
        val extracted = runCatching {
            connection.getExtractedText(request, InputConnection.GET_EXTRACTED_TEXT_MONITOR)
        }.getOrNull()
        val extractedText = extracted?.text?.toString()
        if (extracted != null && extracted.partialStartOffset < 0 && extractedText != null) {
            return FieldText(extractedText, extracted.startOffset, false)
        }
        val before = runCatching { connection.getTextBeforeCursor(TEXT_WINDOW, 0) }.getOrNull()
        val after = runCatching { connection.getTextAfterCursor(TEXT_WINDOW, 0) }.getOrNull()
        if (before == null && after == null) {
            return extractedText?.let { FieldText(it, extracted.startOffset, true) }
        }
        return FieldText(before?.toString().orEmpty() + after?.toString().orEmpty(), 0, false)
    }

    // ------------------------------------------------------------- 剪贴板 ----

    /**
     * 登记剪贴板监听。
     *
     * Android 10+ 只有「有焦点」或「默认输入法」的应用能读剪贴板 —— 而本应用一旦
     * 被设为默认输入法就随时可读，所以监听放在输入法里最合适。读不到时静默跳过，
     * 不报错。
     */
    private fun registerClipboard() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboardManager = manager
        runCatching { manager.addPrimaryClipChangedListener(clipboardListener) }
    }

    private fun unregisterClipboard() {
        val manager = clipboardManager ?: return
        runCatching { manager.removePrimaryClipChangedListener(clipboardListener) }
    }

    private fun captureClip() {
        val manager = clipboardManager ?: return
        val text = runCatching {
            manager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()
        if (text.isNullOrEmpty()) return
        val entry = ClipEntry(System.currentTimeMillis(), clip(text, 200))
        synchronized(lock) {
            if (clipboardBuffer.peekLast()?.text == entry.text) return
            clipboardBuffer.addLast(entry)
            while (clipboardBuffer.size > MAX_CLIPBOARD) clipboardBuffer.removeFirst()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 输入历史环形缓冲上限：够回溯一次输入过程，又不会随会话无限涨。 */
        private const val MAX_HISTORY = 64

        /** 剪贴板历史上限。 */
        private const val MAX_CLIPBOARD = 16

        /** 轮询间隔；只在输入视图显示时运行。 */
        private const val POLL_MS = 600L

        /** getTextBeforeCursor/AfterCursor 的最大字符窗口。 */
        private const val TEXT_WINDOW = 2000

        /** 提交文本时把光标放到末尾。 */
        private const val CURSOR_END = 1

        /** 主线程投递的等待上限。 */
        private const val MAIN_TIMEOUT_MS = 2000L

        @Volatile
        private var instance: PiInputMethodService? = null

        /** 活着的服务实例；没启用或没连接时为 null。 */
        fun running(): PiInputMethodService? = instance

        /** 系统登记的组件 id，`设置 → 语言和输入法` 里看到的就是它。 */
        fun componentId(context: Context): String =
            "${context.packageName}/${PiInputMethodService::class.java.name}"

        /** 本应用的输入法是否已在系统里启用（启用不等于正在使用）。 */
        fun available(context: Context): Boolean {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return false
            val enabled = runCatching { manager.enabledInputMethodList }.getOrNull() ?: return false
            val expected = componentId(context)
            return enabled.any { it.id == expected || it.id.startsWith("${context.packageName}/") }
        }

        /** 系统当前默认输入法是不是本文件这个服务。 */
        fun isDefaultInputMethod(context: Context): Boolean = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull() == componentId(context)

        /** 服务没跑时的自述，仍然把「启用了吗」告诉调用方。 */
        fun status(context: Context): JSONObject = instance?.status() ?: JSONObject().apply {
            put("running", false)
            put("enabled", available(context))
            put("default", isDefaultInputMethod(context))
            put("package", JSONObject.NULL)
            put("fieldKind", "unknown")
            put("isPassword", false)
            put("imeOptions", 0)
            put("fieldHint", JSONObject.NULL)
            put("selectionStart", -1)
            put("selectionEnd", -1)
            put("currentText", JSONObject.NULL)
            put("currentTextChars", 0)
            put("historySize", 0)
            put("clipboardHistorySize", 0)
            put("history", JSONArray())
            put("clipboard", JSONArray())
        }

        /**
         * 无参重载，对应派单里写的 `status(): JSONObject` 对接面。
         *
         * 它拿不到 Context，所以「是否启用」只能退化成 false；`/app/health` 应该用
         * [status] 的带参版本，才能把启用状态一并报出来。
         */
        fun status(): JSONObject = instance?.status() ?: JSONObject().apply {
            put("running", false)
            put("enabled", false)
            put("default", false)
            put("package", JSONObject.NULL)
            put("fieldKind", "unknown")
            put("isPassword", false)
            put("imeOptions", 0)
            put("fieldHint", JSONObject.NULL)
            put("selectionStart", -1)
            put("selectionEnd", -1)
            put("currentText", JSONObject.NULL)
            put("currentTextChars", 0)
            put("historySize", 0)
            put("clipboardHistorySize", 0)
            put("history", JSONArray())
            put("clipboard", JSONArray())
            put("note", "输入法服务未运行；要拿到启用状态请调用 PiInputMethodService.status(context)。")
        }
    }
}

/** 单条记录/自述里的文本摘要：压平空白并截断，避免一条超长文本把回复撑爆。 */
private val PI_IME_WHITESPACE = Regex("\\s+")

private fun clip(text: String, max: Int = 400): String {
    val flat = PI_IME_WHITESPACE.replace(text, " ").trim()
    return if (flat.length <= max) flat else flat.take(max) + "…"
}

/**
 * 从 [old] 到 [new] 的「插入片段」：取公共前缀与公共后缀之外的新增部分。
 * 纯删除返回空串（删除本身就是一种变化，由 [Entry.toJson] 的空 `inserted` 表达）。
 */
private fun insertedFragment(old: String, new: String): String {
    var prefix = 0
    val max = minOf(old.length, new.length)
    while (prefix < max && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return new.substring(prefix, new.length - suffix)
}
