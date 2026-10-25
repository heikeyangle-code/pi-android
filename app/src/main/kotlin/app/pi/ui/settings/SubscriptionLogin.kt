package app.pi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import app.pi.packages.PiAuthStorage
import app.pi.packages.PiCredentialService
import app.pi.packages.PiOAuthHost
import app.pi.ui.theme.PiSpacing
import app.pi.ui.theme.PiTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「订阅账号」一节：pi 支持哪些订阅、设备上哪些已经登录。
 *
 * ## 这一节为什么在「模型与供应商」屏里，而不是另开一页
 *
 * 订阅登录的结果落在 **`auth.json` 的同一个键空间**（`{ "anthropic": {type:"oauth",…} }`），
 * 和这里粘 API Key 写的是同一个文件；登录成功后，订阅厂商的模型也会出现在**同一张**
 * 「已导入的模型」列表里（pi 自带目录 + 已配置凭证）。分成两页会让「我的模型从哪来」再
 * 分叉一次 —— 用户问过的那句「模型菜单里有订阅的吗」就是分叉留下的疑问。
 *
 * ## 清单不是 App 硬编码的
 *
 * [providers] 来自 guest 里 `pi-ai` 的 `builtinProviders()` 过滤 `auth.oauth`
 * （[PiOAuthHost]），所以 pi 升级新增订阅提供商时这一节自动多一行；App 不需要发版。
 * 这也是为什么这一节没有"支持列表"文案：列表本身是运行时读出来的。
 *
 * @param onLogin 点某一行 → 宿主打开 [SubscriptionLoginSheet]。
 * @param onLogout 已登录的行：删掉 `auth.json` 里那条凭证（`PiCredentialService.remove`）。
 */
@Composable
internal fun SubscriptionCard(
    providers: List<PiOAuthHost.Provider>,
    statuses: Map<String, PiAuthStorage.SubscriptionStatus>,
    loading: Boolean,
    problem: String?,
    onLogin: (PiOAuthHost.Provider) -> Unit,
    onLogout: (PiOAuthHost.Provider) -> Unit,
) {
    PiSettingsCard {
        Column(Modifier.padding(horizontal = PiSettingsMetrics.cardPadding, vertical = PiSettingsMetrics.rowPaddingVertical)) {
            Text("订阅账号", style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSettingsMetrics.supportingGap))
            Text(
                "用 Claude、ChatGPT、Copilot、Kimi、Grok 这些账号里的订阅额度。登录跑的是 pi 自己的流程，" +
                    "凭证写进 auth.json；登录完重启引擎，模型就会出现在下面的列表里。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            loading -> SubscriptionHint("正在读取 pi 支持的订阅…")
            problem != null -> SubscriptionHint(problem, warning = true)
            providers.isEmpty() -> SubscriptionHint("运行时的 pi 里没有可用的订阅登录（先启动一次引擎）。")
            else -> providers.forEach { provider ->
                SubscriptionRow(
                    provider = provider,
                    status = statuses[provider.id],
                    onLogin = { onLogin(provider) },
                    onLogout = { onLogout(provider) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionHint(text: String, warning: Boolean = false) {
    Text(
        text,
        modifier = Modifier.padding(
            start = PiSettingsMetrics.cardPadding,
            end = PiSettingsMetrics.cardPadding,
            bottom = PiSettingsMetrics.rowPaddingVertical,
        ),
        style = PiTheme.text.meta,
        color = if (warning) PiTheme.palette.warning else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 一行：pi 的订阅名 + 状态 + 动作。
 *
 * 状态只从文件读（[PiAuthStorage.SubscriptionStatus]）：token 是否过期不等于失效，
 * pi 会在下次请求时用 refresh token 续（`auth/resolve.ts:129-147`），所以过期显示成
 * 「已登录 · 已过期（下次使用会自动续）」，而不是画成未登录 —— 那会骗用户重新登一次。
 */
@Composable
private fun SubscriptionRow(
    provider: PiOAuthHost.Provider,
    status: PiAuthStorage.SubscriptionStatus?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val loggedIn = status != null
    val supporting = when {
        status == null -> provider.loginLabel ?: "未登录"
        status.expires == null -> "已登录"
        status.expires * 1_000L > System.currentTimeMillis() ->
            "已登录 · 有效期至 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(status.expires * 1_000L))}"

        status.hasRefresh -> "已登录 · 访问令牌已过期（下次使用会自动续）"
        else -> "已登录 · 访问令牌已过期（需要重新登录）"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PiSettingsMetrics.cardPadding,
                end = PiSettingsMetrics.cardPadding,
                top = PiSettingsMetrics.rowPaddingVertical,
                bottom = PiSettingsMetrics.rowPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PiSettingsMetrics.rowGap),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                provider.oauthName,
                style = PiTheme.text.prose.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                supporting,
                modifier = Modifier.padding(top = PiSettingsMetrics.supportingGap),
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        PiSettingsChip(
            text = if (loggedIn) "退出" else "登录",
            onClick = if (loggedIn) onLogout else onLogin,
        )
    }
}

/**
 * 登录流程的界面：pi 给什么提示就画什么。
 *
 * ## 状态机
 *
 * 全部来自 [PiOAuthHost.Event] 的直通事件，没有第二套：「等你完成浏览器」、
 * 设备码（大号代码 + 打开的地址 + 轮询中）、`select`（pi 自己给的选项，例如
 * 「在本机浏览器里完成 / 复制授权码」）、`text` / `secret` / `manual_code`（输入后回车）、
 * 成功、失败。
 *
 * ## 为什么粘贴框一直在
 *
 * 回环回调服务器在 guest 里监听 `127.0.0.1:53692`（anthropic）等端口，手机浏览器
 * 与 guest 共用同一个 loopback，所以大多数时候浏览器完成就自动回到这里。但
 * 「浏览器里最后停在了一个打不开的 localhost 页面」是这条路的**常见**结局（用户手动
 * 换设备、浏览器拦了 localhost、或端口被另一个登录实例占着），所以粘贴整条回调地址
 * 永远是可见的退路，而不是藏起来的高级选项。
 */
@Composable
internal fun SubscriptionLoginSheet(
    provider: PiOAuthHost.Provider,
    host: PiOAuthHost,
    service: PiCredentialService,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    // 起进程是 IO，不是组合：`host.login` 要 `ProcessBuilder.start()`，在组合里做会把
    // 主线程卡在 node 的 exec 上（冷启动几百毫秒起）。所以先交一个 null，LaunchedEffect
    // 在 IO 上起来，失败也变成这一页里的一句话而不是崩溃。
    var started by remember(provider.id) { mutableStateOf<Result<PiOAuthHost.Session>?>(null) }
    LaunchedEffect(provider.id) {
        started = withContext(Dispatchers.IO) { runCatching { host.login(provider.id) } }
    }
    val session = started?.getOrNull()
    DisposableEffect(session) { onDispose { session?.close() } }

    var status by remember(provider.id) { mutableStateOf<Step>(Step.Running) }
    var device by remember(provider.id) { mutableStateOf<Device?>(null) }
    var prompt by remember(provider.id) { mutableStateOf<PiOAuthHost.Event.Prompt?>(null) }
    var link by remember(provider.id) { mutableStateOf<String?>(null) }
    var pasted by remember(provider.id) { mutableStateOf("") }
    var askedPaste by remember(provider.id) { mutableStateOf(false) }

    LaunchedEffect(session) {
        val active = session ?: return@LaunchedEffect
        active.events.collect { event ->
            when (event) {
                is PiOAuthHost.Event.Credential -> {
                    val problem = service.setOAuthCredential(provider.id, event.credential)
                    status = if (problem == null) Step.Done else Step.Failed(problem)
                    if (problem == null) onSaved()
                }

                is PiOAuthHost.Event.Notify -> {
                    val raw = event.raw
                    when (raw["type"]?.jsonPrimitive?.content) {
                        "auth_url" -> {
                            link = raw["url"]?.jsonPrimitive?.content
                            status = Step.Running
                        }

                        "device_code" -> {
                            val next = Device(
                                userCode = raw["userCode"]?.jsonPrimitive?.content.orEmpty(),
                                verificationUri = raw["verificationUri"]?.jsonPrimitive?.content.orEmpty(),
                                expiresInSeconds = (raw["expiresInSeconds"] as? JsonPrimitive)?.content?.toIntOrNull(),
                            )
                            device = next
                            link = next.verificationUri
                            status = Step.Running
                        }

                        "progress" -> status = Step.Running
                        else -> Unit
                    }
                }

                is PiOAuthHost.Event.Prompt -> {
                    prompt = event
                    if (event.type == "manual_code") askedPaste = true
                }

                PiOAuthHost.Event.Cancelled -> if (status !is Step.Done) status = Step.Failed("已取消")
                is PiOAuthHost.Event.Error -> status = Step.Failed(event.message)
                is PiOAuthHost.Event.Providers -> Unit
            }
        }
    }

    PiSettingsSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = PiSettingsMetrics.pageHorizontal,
                    end = PiSettingsMetrics.pageHorizontal,
                    top = PiSettingsMetrics.sheetHeadTop,
                ),
        ) {
            Text(provider.oauthName, style = PiTypography.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PiSpacing.small))
            Text(
                "登录由 pi 自己的流程完成；完成后凭证写进 auth.json，重启引擎后模型才出现。",
                style = PiTheme.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PiSettingsMetrics.sheetHeadBottom))

            if (session == null) {
                Text(
                    started?.exceptionOrNull()?.let { "登录起不来：${it.message ?: it::class.java.simpleName}" }
                        ?: "正在启动登录…",
                    style = PiTheme.text.meta,
                    color = if (started?.exceptionOrNull() != null) {
                        PiTheme.palette.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.height(PiSpacing.small))
                TextButton(onClick = onDismiss) { Text("关闭") }
                Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
                return@Column
            }

            // 设备码：代码要大、要能被一眼抄下来。
            device?.let { code ->
                Text("在浏览器里输入这个代码", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PiSpacing.small))
                // 代码要显眼，但**不新开字号**：用设置面的标题档（17/24 SemiBold）套等宽字族。
                // 单体字号只在这一处出现的话，门禁的 `.sp` 棘轮会为它松一格，而代码块在
                // 17sp 的等宽 SemiBold 下已经足够抄写。
                Text(
                    code.userCode,
                    style = PiTypography.title.copy(fontFamily = PiTheme.text.mono.fontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(PiSpacing.small))
                Text(
                    if (code.expiresInSeconds != null) {
                        "有效期 ${code.expiresInSeconds / 60} 分钟；完成前这一页会一直等。"
                    } else {
                        "完成前这一页会一直等。"
                    },
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PiSpacing.gutter))
            }

            // 浏览器入口：auth_url 或设备码的验证地址。
            val target = link
            if (target != null) {
                TextButton(onClick = { runCatching { uriHandler.openUri(target) } }) {
                    Text("在浏览器中打开")
                }
                Spacer(Modifier.height(PiSpacing.small))
            }

            // pi 的提问：select 就是他给的选项，其余是输入框。
            prompt?.let { asking ->
                Text(asking.message, style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(PiSpacing.small))
                if (asking.type == "select") {
                    asking.options.forEach { option ->
                        TextButton(
                            onClick = {
                                session.answer(asking.promptId, option.id)
                                prompt = null
                            },
                        ) {
                            Text(if (option.description != null) "${option.label} · ${option.description}" else option.label)
                        }
                    }
                } else {
                    PiEditorField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = asking.placeholder ?: "粘贴到这里",
                        singleLine = asking.type != "manual_code",
                    )
                    Spacer(Modifier.height(PiSpacing.small))
                    TextButton(
                        enabled = pasted.isNotBlank(),
                        onClick = {
                            session.answer(asking.promptId, pasted)
                            pasted = ""
                            prompt = null
                        },
                    ) { Text("完成") }
                }
                Spacer(Modifier.height(PiSpacing.gutter))
            }

            // 一直可见的退路：整条回调地址粘回来。
            if (status !is Step.Done && !askedPaste) {
                Text(
                    "浏览器最后停在一个打不开的 localhost 页面？把地址栏整条粘到这里也算完成。",
                    style = PiTheme.text.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(PiSpacing.small))
                PiEditorField(
                    value = pasted,
                    onValueChange = { pasted = it },
                    label = "回调地址或授权码",
                    singleLine = false,
                )
                Spacer(Modifier.height(PiSpacing.small))
                TextButton(
                    enabled = pasted.isNotBlank(),
                    onClick = {
                        // pi 对 manual_code 有专门的解析（整条 URL、`code#state`、裸 code），
                        // 这里只在没有等待中的提示时把它当 manual_code 回给流程：pi 的
                        // `auth_url` 阶段本来就挂着一个 manual_code 提示（anthropic /
                        // openai / openrouter 都是这么实现的）。
                        val asking = prompt
                        if (asking != null) {
                            session.answer(asking.promptId, pasted)
                            prompt = null
                        }
                        pasted = ""
                    },
                ) { Text("用这段完成登录") }
                Spacer(Modifier.height(PiSpacing.gutter))
            }

            when (val state = status) {
                Step.Running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("等待完成…", style = PiTheme.text.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(PiSpacing.small))
                    TextButton(onClick = {
                        session.cancel()
                        onDismiss()
                    }) { Text("取消") }
                }

                Step.Done -> {
                    Text(
                        "已登录。重启引擎后，${provider.name} 的模型会出现在这一屏的列表里；这一屏顶部会出现重启卡。",
                        style = PiTheme.text.meta,
                        color = PiTheme.palette.success,
                    )
                    Spacer(Modifier.height(PiSpacing.small))
                    TextButton(onClick = onDismiss) { Text("好") }
                }

                is Step.Failed -> {
                    Text("登录失败：${state.message}", style = PiTheme.text.meta, color = PiTheme.palette.warning)
                    Spacer(Modifier.height(PiSpacing.small))
                    TextButton(onClick = {
                        session.cancel()
                        onDismiss()
                    }) { Text("关闭") }
                }
            }
            Spacer(Modifier.height(PiSettingsMetrics.sheetFooterTop))
        }
    }
}

private sealed interface Step {
    data object Running : Step
    data object Done : Step
    data class Failed(val message: String) : Step
}

private data class Device(val userCode: String, val verificationUri: String, val expiresInSeconds: Int?)
