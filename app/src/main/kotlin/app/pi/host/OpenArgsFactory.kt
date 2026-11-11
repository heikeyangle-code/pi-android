package app.pi.host

import app.pi.rpc.PiLaunchOptions
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 用户在「启动前设置」里选的东西 → `session.open` 的参数。
 *
 * 旧路径把这些拼成 pi 的命令行 flag（`--no-extensions`、`--session-id` …）；宿主不解析命令行，
 * 直接收结构化参数，所以这里是**唯一**的翻译处。环境变量那部分（`PI_OFFLINE` 等）不在这里：
 * 它们仍由 [PiLaunchOptions.environment] 在进程启动时给。
 *
 * 会话怎么认回来（和旧语义的差别，有意为之）：
 *  - `resumeSessionPath` → 按**路径**打开。这是推荐路径：没有「按 id 找不到就用同一个 id 新建
 *    第二个文件」的坑（docs/sdk-host/sessions.md §1）。
 *  - 否则 `continueSessionId` → 按 id。宿主 `findById` 找不到时会新建同 id 的文件（与 pi CLI 一致），
 *    所以调用方应优先给路径。
 *  - 否则 `continueMostRecent` → 续最近一个。
 */
fun PiLaunchOptions.toOpenArgs(sessionDir: String? = null, trust: Boolean? = null): OpenArgs = OpenArgs(
    sessionFile = resumeSessionPath,
    sessionId = if (resumeSessionPath == null) continueSessionId else null,
    continueRecent = resumeSessionPath == null && continueSessionId == null && continueMostRecent,
    sessionDir = sessionDir,
    flags = extensionFlags.associate { it.name to (it.value?.let(::JsonPrimitive) ?: JsonPrimitive(true)) as JsonElement },
    noExtensions = noExtensions,
    noSkills = noSkills,
    noPromptTemplates = noPromptTemplates,
    noThemes = noThemes,
    noContextFiles = noContextFiles,
    trust = trust,
)
