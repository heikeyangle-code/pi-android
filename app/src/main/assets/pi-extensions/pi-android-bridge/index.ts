/**
 * pi-android device bridge — pi extension.
 *
 * Registers the device capabilities the Android host app exposes as pi tools, all of
 * them talking to a loopback HTTP bridge that the app runs (`./client`). pi itself
 * deliberately ships no device integration; on Termux the documented answer is to
 * put `termux-*` shell commands in AGENTS.md. This extension is the app doing
 * better than that, without inventing an MCP server (pi has none on purpose):
 * extensions are the supported mechanism, and these are ordinary pi tools.
 *
 * Design rules followed here:
 *
 *  - the tool contract matches `docs/extensions.md` exactly: `{ name, label,
 *    description, parameters: typebox, execute(toolCallId, params, signal,
 *    onUpdate, ctx) }`;
 *  - errors are signalled by **throwing**, and the thrown message is already the
 *    Chinese explanation plus the user's next step, because a disabled capability
 *    must never be a silent failure;
 *  - every tool is *declared* to the model while its capability group is on, and a group
 *    that is off is not registered at all — so the prompt cost is the open groups' schemas
 *    and nothing else. Six of them (`android_status`, `android_ui`, `android_app`,
 *    `android_io`, `android_fs`, `android_shell`) merge the finer-grained tools by adding
 *    an `action`; the merged tools stay registered so an endpoint and its wording exist once;
 *  - every tool truncates its own output with pi's own utilities (50KB / 2000
 *    lines) and says when it did;
 *  - string enums use `StringEnum` for Google API compatibility.
 */

import type { AgentToolUpdateCallback, ExtensionAPI, ExtensionContext, ToolNamespace } from "@earendil-works/pi-coding-agent";
import {
	DEFAULT_MAX_BYTES,
	DEFAULT_MAX_LINES,
	formatSize,
	truncateHead,
	truncateTail,
} from "@earendil-works/pi-coding-agent";
import type { ImageContent, TextContent } from "@earendil-works/pi-ai";
import { Type, type Static, type TSchema, type TUnsafe } from "typebox";
import { BridgeError, bridgeGet, bridgeHealth, bridgePost, type HealthPayload } from "./client";

/**
 * `StringEnum`, transcribed from pi-ai (`packages/ai/src/utils/typebox-helpers.ts:14-26`)
 * instead of imported from `@earendil-works/pi-ai`.
 *
 * ## Why this is worth a local copy
 *
 * `import { StringEnum } from "@earendil-works/pi-ai"` looked like a four-line
 * helper and cost **17.8 seconds of every engine start**. pi's extension loader
 * gives extensions the SDK through jiti aliases, and the `@earendil-works/pi-ai`
 * alias resolves to the **compat entrypoint** (`ai/dist/compat.js`, a superset of
 * the core one — see `loader.ts:111-114`), whose import graph is every provider and
 * its SDK. jiti transpiles that graph file by file, so one value import from pi-ai
 * dragged the whole thing through Babel before the first message could be read.
 *
 * Measured on the phone this app runs on, with `PI_TIMING=1`: this extension's
 * module import was **17 763 ms**, while the other two extensions (which import no
 * value from the SDK's compat entry) were 48 ms and 120 ms. After this change the
 * same number is in the hundreds of milliseconds — `docs/known-gaps.md` §M9.
 *
 * The function itself depends only on `typebox`, which this file already imports,
 * so copying it is cheaper *and* removes a dependency rather than adding one. The
 * body is pi's, character for character; if pi's version changes, this is the line
 * to compare against.
 */
function StringEnum<T extends readonly string[]>(
	values: T,
	options?: { description?: string; default?: T[number] },
): TUnsafe<T[number]> {
	return Type.Unsafe<T[number]>({
		type: "string",
		enum: values as any,
		...(options?.description && { description: options.description }),
		...(options?.default && { default: options.default }),
	});
}

// ---------------------------------------------------------------------------
// shared plumbing
// ---------------------------------------------------------------------------

type ToolContent = Array<TextContent | ImageContent>;

interface ToolOutcome {
	content: ToolContent;
	details: Record<string, unknown>;
}

function textResult(text: string, details: Record<string, unknown> = {}): ToolOutcome {
	return { content: [{ type: "text", text }], details };
}

function imageResult(base64: string, mimeType: string, note: string, details: Record<string, unknown> = {}): ToolOutcome {
	return {
		content: [{ type: "image", data: base64, mimeType }, { type: "text", text: note }],
		details,
	};
}

/** pi's built-in budget: 50KB or 2000 lines, whichever comes first. */
function truncateForModel(text: string, label: string, mode: "head" | "tail" = "head"): string {
	const result = mode === "head"
		? truncateHead(text, { maxLines: DEFAULT_MAX_LINES, maxBytes: DEFAULT_MAX_BYTES })
		: truncateTail(text, { maxLines: DEFAULT_MAX_LINES, maxBytes: DEFAULT_MAX_BYTES });
	if (!result.truncated) return result.content;
	return (
		`${result.content}\n\n[${label} 输出被截断：显示 ${result.outputLines}/${result.totalLines} 行` +
		`（${formatSize(result.outputBytes)}/${formatSize(result.totalBytes)}，按${result.truncatedBy === "bytes" ? "字节" : "行数"}截断）。` +
		"如需其余内容，请缩小范围后重新调用。]"
	);
}

/** Turn a [BridgeError] into the sentence the model should relay verbatim. */
async function guarded(run: () => Promise<ToolOutcome>): Promise<ToolOutcome> {
	try {
		return await run();
	} catch (error) {
		if (error instanceof BridgeError) throw new Error(error.toModelMessage());
		throw error instanceof Error ? error : new Error(String(error));
	}
}

/**
 * The device-side node selector, as the bridge wants it: one nested object.
 *
 * Nested rather than flat so it can never be confused with a tool's own
 * parameters — `android_input` already has a `text` (the string to type) that
 * would otherwise be read as a node selector by name.
 */
function selectorBody(p: { text?: string; desc?: string; resourceId?: string; packageName?: string }): Record<string, unknown> {
	const selector: Record<string, unknown> = {};
	if (p.text !== undefined) selector.text = p.text;
	if (p.desc !== undefined) selector.desc = p.desc;
	if (p.resourceId !== undefined) selector.resourceId = p.resourceId;
	if (p.packageName !== undefined) selector.packageName = p.packageName;
	return Object.keys(selector).length > 0 ? { selector } : {};
}

/**
 * The verification every UI action carries back: the foreground package before
 * and after, and whether the screen changed at all. A gesture that was sent but
 * changed nothing is the failure mode a plain "已点按" hides.
 */
function actionEnvelope(data: ActionData): string {
	const bits: string[] = [];
	if (data.before !== undefined || data.after !== undefined) {
		bits.push(`前台 ${data.before ?? "?"} → ${data.after ?? "?"}`);
	}
	if (data.changed === false) bits.push("屏幕没有变化（动作可能没有生效）");
	else if (data.changed === true) bits.push("屏幕已变化");
	return bits.length > 0 ? `（${bits.join("；")}）` : "";
}

/**
 * A parameter mistake the schema cannot catch, in the bridge's own refusal shape
 * (`[CODE] 原因` + `提示：`), naming the parameter and its allowed values.
 */
function badParam(tool: string, param: string, allowed: string[], got: unknown): Error {
	const values = allowed.map((value) => `"${value}"`).join(" 或 ");
	return new Error(
		`[BAD_PARAM] ${tool} 的 ${param} 只能是 ${values}，收到 ${JSON.stringify(got)}。\n` +
			`提示：请按允许值重新调用 ${tool}。`,
	);
}

/**
 * 设备能力分组的 id，与 App 侧 `bridge/DeviceCapability.kt` 的 `id` 一一对应
 * （`basic` / `accessibility` / `ime` / `admin` / `shell`，共 5 组）。原来的 `storage` 与
 * `sensors` 两组已并入 `basic`，所以文件、位置、传感器、手电筒这些工具现在都挂在 `basic` 上；
 * 通知监听、自动化、VPN、投屏四种能力也归 `basic`，不另开开关。
 *
 * 这里是一份**抄写**，不是第二份真相：注册时拿它去 `/app/health` 的
 * `capabilities[].id` 里查状态，而那份 JSON 就是授权页和桥端点共用的
 * `DeviceCapabilityState`（`DeviceBridgeRouter.kt` 的 `/app/health` 分支）。App 侧加一组
 * 能力时这里会查不到、对应工具不会注册（而不是猜着注册），所以错法是保守的。
 */
type DeviceCapabilityId = "basic" | "storage" | "accessibility" | "sensors" | "ime" | "admin" | "shell";

interface DeviceToolSpec {
	name: string;
	/**
	 * 这个工具属于哪一组能力；`null` 表示不依赖任何一组（只有 `android_bridge_status`），
	 * 永远注册。
	 *
	 * 判据只能从两处读出来：工具用到的桥端点被 `DeviceBridgeRouter.kt` 的哪个
	 * `withCapability(...)` 包着，以及能力卡上 `DeviceCapability.kt` 的 `allows` 文案。
	 */
	capability: DeviceCapabilityId | null;
	/**
	 * `hidden` = 注册但不声明给模型。给的是那些「已经被某个合并入口转交」的细粒度工具：
	 * `android_ui` / `android_io` 的 action 表、`android_fs` 与 `android_status` 的
	 * `deviceTool(...)` 都直接调它们，所以能力一个不少，只是同一件事不在提示词里写两遍。
	 * 省略即 `direct`。与 `deferred` 的区别：`hidden` 不进提示词也不再需要任何
	 * 「抳回来」的机制（`tool_search`），因为调用入口本来就在。
	 */
	exposure?: "hidden";
	label: string;
	description: string;
	promptSnippet: string;
	promptGuidelines?: string[];
	parameters: TSchema;
	run: (params: Record<string, unknown>, ctx: ExtensionContext) => Promise<ToolOutcome>;
}

// Shapes of the bridge's JSON payloads, mirrored from DeviceBridgeRouter.kt.
interface DumpData {
	packageName: string;
	nodeCount: number;
	totalNodeCount: number;
	truncated: boolean;
	screen: { width: number; height: number };
	/** Always `display`: the unscaled pixel grid every x/y/region is in. */
	coordinateSpace?: string;
	snapshotId?: number;
	diff?: boolean;
	added?: number;
	changed?: number;
	removed?: number;
	unchanged?: number;
	text: string;
}
interface ScreenshotData {
	mimeType: string;
	base64: string;
	width: number;
	height: number;
	bytes: number;
	coordinateSpace?: string;
	/** Full-display size of the frame before crop/scale (display pixels). */
	sourceWidth?: number;
	sourceHeight?: number;
	/** image = (display - region.topLeft) * scale */
	scale?: number;
	region?: number[] | null;
	/** True when the caller asked for set-of-marks overlays (`marks=true`). */
	marks?: boolean;
	markedIndices?: number;
	marksNote?: string;
}
interface AppEntry {
	label: string;
	packageName: string;
	system: boolean;
	launchable: boolean;
}
interface AppsData {
	count: number;
	truncated: boolean;
	apps: AppEntry[];
	note?: string;
}
interface NodeInfo {
	index: number;
	class: string;
	text?: string;
	viewId?: string;
}
interface ActionData {
	mode?: string;
	target?: string | NodeInfo;
	index?: number;
	x?: number;
	y?: number;
	/** Foreground package before/after, plus whether the screen changed at all. */
	before?: string | null;
	after?: string | null;
	changed?: boolean;
	selector?: string;
}
interface ClipboardData {
	text: string;
	empty?: boolean;
	note?: string;
}
interface SensorEntry {
	name: string;
	type: number;
	typeName: string;
	vendor: string;
}
interface SensorsData {
	count: number;
	sensors: SensorEntry[];
}
interface SensorSampleData {
	name: string;
	typeName: string;
	values: number[];
	units: string;
}
interface ExportData {
	uri: string;
	displayName: string;
	location: string;
	bytes: number;
}
interface ImportData {
	displayName: string;
	bytes: number;
	truncated: boolean;
	text?: string;
	base64?: string;
}
interface SafRoot {
	name: string;
	uri: string;
	exists: boolean;
}
interface SafRootsData {
	count: number;
	roots: SafRoot[];
	note: string;
}
interface SafListData {
	path: string;
	count: number;
	truncated: boolean;
	entries: Array<{ name: string; directory: boolean; bytes: number; lastModified: number; uri: string }>;
}
interface SafReadData {
	path: string;
	uri: string;
	bytes: number;
	truncated: boolean;
	text?: string;
	base64?: string;
}
interface SafWriteData {
	path: string;
	uri: string;
	bytes: number;
	created: boolean;
	mimeType: string;
}
interface ShellData {
	stdout: string;
	stderr: string;
	exitCode: number;
	timedOut: boolean;
	/** Either stream stopped at the bridge's cap. Kept for older bridges. */
	truncated: boolean;
	/** Per stream, because *which* one was cut decides what the output means. */
	stdoutTruncated?: boolean;
	stderrTruncated?: boolean;
	backend: string;
	uid: number;
	backendLabel: string;
	note: string;
	policy?: string;
}

/**
 * 一次设备 Shell 调用的失败口径，抄 pi 0.86.1 的 `core/tools/bash.ts:363-374`：
 * 超时被杀、没有退出码、非零退出，**三种都算失败**；成功返回 null。
 *
 * ## 为什么是 throw，而不是像 0.85.1 那样把 `[退出码 N]` 写进正文再返回成功
 *
 * pi 的 `AgentToolResult`（`packages/agent/src/types.ts:383-396`）**没有** `isError`
 * 字段：扩展工具唯一的报错通道就是 throw（pi 的 agent loop 捕获后把结果标成错误）。
 * 返回成功会让一条失败的命令在 pi 的上下文里与成功无法区分 —— 这正是 0.86.0
 * 给 `user_bash` 换成 fail-closed、并在 #9577 里修「被信号杀掉的命令被报成成功 +
 * 部分输出」的那个形状。
 *
 * ## 为什么不"只 throw"：throw 会丢 `details`，所以正文要背住可解析的那部分
 *
 * pi 的错误结果只带正文，没有 `details`。所以失败时正文里保留 pi 的**原句**
 * `Command exited with code N` —— App 的 `ToolOutputParse.shellExitCode`
 * （`EXIT_CODE` 正则，`ui/blocks/ToolOutputParse.kt:235-239` 与 `:574`）就是按这句话
 * 解析退出码的，卡片上的「退出码 N」因此不丢；`fullOutputPath` / `truncation` 本工具
 * 从来没有写过（它们是 pi 内建 `bash` 的字段，通用卡片 `ToolCallBlock.kt:80-89` 读它们，
 * 对 `android_shell` 一直是空），所以这里不存在"丢字段"的回归。结构化的
 * `backend`/`uid` 只在成功路径保留：App 侧全树没有读者（grep 无命中）。
 *
 * 超时那一句**不带秒数**：pi 写的是 `Command timed out after N seconds`，而真正生效的
 * 超时由 App 侧钳制（`DeviceShell.kt:120` 把请求夹在 [500, 60000] ms，扩展拿不到它算出的
 * 那个值）。在这里复算一遍就是把 App 的钳制抄成第二份真相；宁可少一个数字，不编一个。
 */
function shellFailureStatus(data: ShellData): string | null {
	if (data.timedOut) return "Command timed out";
	if (typeof data.exitCode !== "number" || !Number.isFinite(data.exitCode)) {
		return "Command terminated without an exit code";
	}
	if (data.exitCode !== 0) return `Command exited with code ${data.exitCode}`;
	return null;
}

// ---------------------------------------------------------------------------
// tool registry
// ---------------------------------------------------------------------------

const ScreenKey = StringEnum(
	["back", "home", "recents", "notifications", "quicksettings", "powermenu", "lock", "screenshot", "split"] as const,
	{
		description: "Global action: lock needs Android 9+, screenshot 11+, split 12+. The accessibility channel does only these.",
	},
);

const ScreenshotFormat = StringEnum(["jpeg", "png"] as const, {
	description: "Image format: jpeg is smaller (default), png is lossless.",
});

/**
 * 四个合并工具（`android_ui` / `android_app` / `android_io` / `android_fs`）的 action。
 *
 * 用 `Type.Union` + `Type.Literal` 而不是 `StringEnum`：action 是合并出来的分支选择器，
 * 每个字面量都要在 schema 里单独摆出来，模型看到的才是闭集。`StringEnum`（`type:string` + `enum`）
 * 留给其它单值枚举（`kind` / `op` / `what` / `direction` / `format`）。
 */
const AppAction = Type.Union(
	[Type.Literal("list"), Type.Literal("launch"), Type.Literal("stop")],
	{ description: "list = list apps, launch = start one, stop = kill a user app's background process." },
);

const UiAction = Type.Union(
	[
		Type.Literal("dump"),
		Type.Literal("tap"),
		Type.Literal("swipe"),
		Type.Literal("input"),
		Type.Literal("key"),
		Type.Literal("keyevent"),
		Type.Literal("screenshot"),
	],
	{ description: "dump=read tree, tap, swipe/scroll, input=type, key=global action, keyevent=raw keys (Shizuku), screenshot." },
);

const IoAction = Type.Union(
	[
		Type.Literal("clipboard"),
		Type.Literal("say"),
		Type.Literal("vibrate"),
		Type.Literal("share"),
		Type.Literal("open"),
	],
	{ description: "clipboard=read/write, say=notify/toast/speak, vibrate, share=system sheet, open=URL/deep link." },
);

const FsAction = Type.Union(
	[
		Type.Literal("list"),
		Type.Literal("read"),
		Type.Literal("write"),
		Type.Literal("download"),
	],
	{ description: "list/read/write = authorized (SAF) dirs; download = public Download (needs op)." },
);

/**
 * 波2 六个新工具（输入法 / 设备管理员 / 通知监听 / 自动化 / 本地 VPN / 投屏）的 action。
 *
 * 与上面四个合并工具同一形状：`Type.Union` + `Type.Literal`，每个字面量单独摆出来，
 * 模型看到的才是闭集。
 */
const ImeAction = Type.Union(
	[
		Type.Literal("insert"),
		Type.Literal("replace"),
		Type.Literal("delete"),
		Type.Literal("surround"),
		Type.Literal("submit"),
		Type.Literal("history"),
		Type.Literal("text"),
	],
	{ description: "text=read the field, surround=read the text around the caret, insert/replace=write, delete=chars around the caret, submit=editor action, history=past input." },
);

const AdminAction = Type.Union(
	[
		Type.Literal("status"),
		Type.Literal("capabilities"),
		Type.Literal("grant"),
		Type.Literal("hidden"),
		Type.Literal("suspend"),
		Type.Literal("uninstall-blocked"),
		Type.Literal("install-ca"),
		Type.Literal("always-on-vpn"),
		Type.Literal("lock-task"),
		Type.Literal("update-policy"),
		Type.Literal("status-bar"),
		Type.Literal("keyguard"),
		Type.Literal("camera"),
		Type.Literal("reboot"),
		Type.Literal("wipe"),
	],
	{ description: "status/capabilities=read; the rest are Device Owner / Profile Owner policy changes (reboot, wipe and lock-task are irreversible)." },
);

const NotifyAction = Type.Union(
	[
		Type.Literal("status"),
		Type.Literal("recent"),
		Type.Literal("reply"),
		Type.Literal("dismiss"),
		Type.Literal("dismiss-all"),
		Type.Literal("snooze"),
		Type.Literal("events"),
	],
	{ description: "status/recent/events=read; reply/dismiss/dismiss-all/snooze=act on one notification by its key." },
);

const AutomationAction = Type.Union(
	[
		Type.Literal("status"),
		Type.Literal("list"),
		Type.Literal("add"),
		Type.Literal("remove"),
		Type.Literal("apply"),
		Type.Literal("history"),
	],
	{ description: "status/list/history=read; add/remove=edit the rule set; apply=start the engine." },
);

const NetAction = Type.Union(
	[
		Type.Literal("status"),
		Type.Literal("start"),
		Type.Literal("stop"),
		Type.Literal("queries"),
		Type.Literal("blocklist"),
	],
	{ description: "status/queries=read the local VPN; start/stop=turn the tunnel on/off; blocklist=replace the DNS blocklist." },
);

const CaptureAction = Type.Union(
	[
		Type.Literal("status"),
		Type.Literal("consent"),
		Type.Literal("grab"),
	],
	{ description: "status=MediaProjection state; consent=show the system capture dialog on the device; grab=one JPEG frame." },
);

/**
 * `android_automation` 的 `add` 参数，镜像 `PiAutomation.Trigger` / `Condition` / `Action` / `Rule`。
 *
 * 字面量与 App 侧同名：`type` 的取值就是 `PiAutomation.TRIGGER_TYPES` / `ACTION_TYPES`，
 * 这里列成闭集，模型不会写出一个引擎认不出的 type 再等一句运行期报错。
 */
const AutomationTrigger = Type.Object({
	type: StringEnum(["time", "notification", "battery", "network", "screen", "app"] as const, {
		description: "What fires the rule.",
	}),
	intervalMinutes: Type.Optional(Type.Number({ description: "time: every N minutes; <=0 off." })),
	dailyAt: Type.Optional(Type.String({ description: "time: daily HH:mm." })),
	packageName: Type.Optional(Type.String({ description: "notification/app: limit to this package (app requires it)." })),
	keyword: Type.Optional(Type.String({ description: "notification: regex (substring when it does not compile) matched on title/text." })),
	below: Type.Optional(Type.Number({ description: "battery: fire below this percent, -1 off." })),
	above: Type.Optional(Type.Number({ description: "battery: fire above this percent, -1 off." })),
	network: Type.Optional(
		StringEnum(["wifi", "mobile", "ethernet", "none", "any"] as const, { description: "network: which kind fires it." }),
	),
	screenOn: Type.Optional(Type.Boolean({ description: "screen: true = screen on, false = screen off." })),
});

const AutomationCondition = Type.Object({
	logic: Type.Optional(StringEnum(["and", "or"] as const, { description: "How timeWindow and the battery bounds combine, default and." })),
	timeWindow: Type.Optional(Type.String({ description: "\"HH:mm-HH:mm\"; crosses midnight." })),
	batteryMin: Type.Optional(Type.Number({ description: "Battery >= this, -1 off." })),
	batteryMax: Type.Optional(Type.Number({ description: "Battery <= this, -1 off." })),
});

const AutomationRuleAction = Type.Object({
	id: Type.Optional(Type.String({ description: "Idempotency key; repeats inside idempotencyWindowMs are dropped." })),
	type: StringEnum(["notify", "shell", "ui", "toast", "vibrate"] as const, { description: "What the action does." }),
	title: Type.Optional(Type.String({ description: "notify: title." })),
	text: Type.Optional(Type.String({ description: "notify: body. toast: text." })),
	command: Type.Optional(Type.String({ description: "shell: the command, under the same policy as android_shell." })),
	timeoutMs: Type.Optional(Type.Number({ description: "shell: timeout ms, default 15000." })),
	operation: Type.Optional(
		StringEnum(["key", "input", "scroll", "dump", "tap", "longPress", "swipe"] as const, {
			description: "ui: which screen primitive to run.",
		}),
	),
	params: Type.Optional(
		Type.Record(Type.String(), Type.String(), {
			description: "ui: that primitive's arguments by name (e.g. { key: \"home\" }, { text: \"hi\", index: \"3\" }, selectorText, x1/y1/x2/y2/durationMs).",
		}),
	),
	milliseconds: Type.Optional(Type.Number({ description: "vibrate: duration ms, default 1500." })),
	pattern: Type.Optional(Type.Array(Type.Number(), { description: "vibrate: off-on-off-on pattern; overrides milliseconds." })),
	longDuration: Type.Optional(Type.Boolean({ description: "toast: use LENGTH_LONG." })),
	retries: Type.Optional(Type.Number({ description: "Retry count for a failed action, default 2." })),
	retryBackoffMs: Type.Optional(Type.Number({ description: "Retry backoff base in ms, default 500." })),
	idempotencyWindowMs: Type.Optional(Type.Number({ description: "Drop repeated runs of the same id inside this window, default 5000." })),
});

/** `android_ui` / `android_io` 的 action → 它展开成哪个细粒度工具。 */
const UI_ACTIONS: Record<string, string> = {
	dump: "android_ui_dump",
	tap: "android_tap",
	swipe: "android_swipe",
	input: "android_input",
	key: "android_key",
	keyevent: "android_keyevent",
	screenshot: "android_screenshot",
};

const IO_ACTIONS: Record<string, string> = {
	clipboard: "android_clipboard",
	say: "android_say",
	vibrate: "android_vibrate",
	share: "android_share",
	open: "android_open",
};

/** 合并工具里本该由原 schema 的 `required` 把关的字段，按 action 列出来。 */
const UI_REQUIRED: Record<string, string[]> = {
	input: ["text"],
	key: ["key"],
	keyevent: ["keys"],
};

const IO_REQUIRED: Record<string, string[]> = {
	say: ["kind", "text"],
	open: ["url"],
};

/**
 * pi 的工具分组名。模型的工具列表按它成组。
 */
const DEVICE_NAMESPACE: ToolNamespace = {
	name: "pi-android",
	description: "pi-android device bridge (android_*)",
};

/**
 * 常驻工具把一次调用转交给它展开的细粒度工具。
 *
 * 合并工具只多一个 `action`，其余参数原样传下去；被展开的工具仍然注册着，
 * 所以端点和结果文案只有一份。
 */
function deviceTool(name: string): DeviceToolSpec {
	const tool = DEVICE_TOOLS.find((entry) => entry.name === name);
	if (tool === undefined) throw new Error(`[BUG] 设备扩展里没有 ${name}，请用 /device-reload 重新加载扩展。`);
	return tool;
}

/** 去掉合并工具的 `action`，其余参数原样交给被展开的工具。 */
function withoutAction(params: Record<string, unknown>): Record<string, unknown> {
	const rest: Record<string, unknown> = {};
	for (const [key, value] of Object.entries(params)) {
		if (key !== "action") rest[key] = value;
	}
	return rest;
}

/**
 * 合并工具里本该由 schema 的 `required` 把关的字段。
 *
 * 合并之后每个参数都只能声明成可选（不同 action 的必填集不同），这条检查补回原工具
 * schema 的精度：缺字段当场抛 `[BAD_PARAM]`，不让桥端点去猜。
 */
function requireText(tool: string, action: string, params: Record<string, unknown>, names: string[]): void {
	for (const name of names) {
		const value = params[name];
		if (typeof value !== "string" || value.trim().length === 0) {
			throw new Error(
				`[BAD_PARAM] ${tool} 的 action="${action}" 需要 ${name}。\n提示：补上 ${name} 后重新调用 ${tool}。`,
			);
		}
	}
}

/** 展开一个 action 表：非法 action 抛 `[BAD_PARAM]`，必填项缺失也抛。 */
async function dispatch(
	tool: string,
	actions: Record<string, string>,
	required: Record<string, string[]>,
	params: Record<string, unknown>,
	ctx: ExtensionContext,
): Promise<ToolOutcome> {
	const action = typeof params.action === "string" ? params.action : "";
	const target = actions[action];
	if (target === undefined) throw badParam(tool, "action", Object.keys(actions), params.action);
	requireText(tool, action, params, required[action] ?? []);
	return deviceTool(target).run(withoutAction(params), ctx);
}

/** 一个状态字段的显示值：缺字段显示 `?`（而不是 `undefined`），对象按 JSON 摆出来。 */
function show(value: unknown): string {
	if (value === undefined || value === null) return "?";
	if (typeof value === "object") return JSON.stringify(value);
	return String(value);
}

/** 与 [requireText] 同一件事，给数字参数用；返回校验过的数字。 */
function requireNumber(tool: string, action: string, params: Record<string, unknown>, name: string): number {
	const value = params[name];
	if (typeof value !== "number" || !Number.isFinite(value)) {
		throw new Error(
			`[BAD_PARAM] ${tool} 的 action="${action}" 需要数字参数 ${name}。\n提示：补上 ${name} 后重新调用 ${tool}。`,
		);
	}
	return value;
}

/**
 * 与 [requireText] 同一件事，给布尔参数用。
 *
 * 不给默认值：`hidden` / `disabled` / `suspended` 这些参数只可能默认成其中一个方向，猜错
 * 就是替用户做了相反的决定（把「恢复应用」当成「隐藏应用」）。
 */
function requireBoolean(tool: string, action: string, params: Record<string, unknown>, name: string): boolean {
	const value = params[name];
	if (typeof value !== "boolean") {
		throw new Error(
			`[BAD_PARAM] ${tool} 的 action="${action}" 需要布尔参数 ${name}（true 或 false）。\n提示：补上 ${name} 后重新调用 ${tool}。`,
		);
	}
	return value;
}

/** 字符串数组参数；非法项丢掉，由调用方按「必填的列表不能为空」报 BAD_PARAM。 */
function stringList(value: unknown): string[] {
	if (!Array.isArray(value)) return [];
	return value.filter((item): item is string => typeof item === "string" && item.trim().length > 0);
}

/** 波1 动作函数的返回形状：成功 `{ok:true,…}`，失败 `{ok:false, reason, hint}`。 */
interface ActionPayload {
	ok?: boolean;
	reason?: string;
	hint?: string;
	note?: string;
}

/**
 * 把 `{ok:false}` 当失败报出去。
 *
 * 波1 的动作函数（`PiInputMethodService.withConnection`、`DeviceAdmin.denied`、
 * `PiNotificationListener.failure`）**不抛异常**，而是把失败写进返回值；端点若原样透传
 * 这个对象，这里就必须自己收下它。“把 `ok:false` 渲染成已完成”是这一层唯一会骗到模型的错：
 * 模型会据此认定操作生效，然后既不再试也不告诉用户。端点若已经把失败翻译成 denial，
 * `bridgePost` 就先抛了 `BridgeError`，根本走不到这里。
 */
function requireActionOk(tool: string, data: ActionPayload): void {
	if (data.ok !== false) return;
	const reason = data.reason ?? `${tool} 被设备拒绝，但没有给出原因。`;
	const hint = data.hint ? `\n提示：${data.hint}` : "";
	throw new Error(`[ACTION_FAILED] ${reason}${hint}`);
}

/**
 * 列表类端点的取值：把载荷里那个数组拿出来交给调用方渲染。
 *
 * 取「数组本身」而不是猜一个键名，是因为键名由 App 侧端点决定（波1 的 `recent()` / `events()`
 * / `history()` / `readQueries()` 返回的都是 JSONArray）。猜错的代价不是报错，而是把一屏通知
 * 渲染成「0 条」—— 看起来像设备上真的没有通知，模型会据此回答用户。
 */
function listField(data: Record<string, unknown>): unknown[] | null {
	for (const value of Object.values(data)) {
		if (Array.isArray(value)) return value;
	}
	return null;
}

/** 列表为空时报「0 条」而不是空正文；条目怎么渲染由调用方给，因为它才知道字段含义。 */
function listText(
	label: string,
	entries: unknown[],
	render: (entry: Record<string, unknown>, index: number) => string,
): string {
	if (entries.length === 0) return `${label}：0 条。`;
	const lines = entries.map((entry, index) => render((entry ?? {}) as Record<string, unknown>, index + 1));
	return `${label}：${entries.length} 条\n${lines.join("\n")}`;
}

const DEVICE_TOOLS: DeviceToolSpec[] = [
	// ----------------------------------------------------------- 常驻工具 ----
	//
	// 声明给模型的全部就是「当前开着的那几组」。一组关掉 = 它的工具根本不注册，
	// 所以提示词的成本只等于开着的那几组的 schema，不需要额外的按需加载机制。
	{
		name: "android_status",
		capability: null,
		label: "设备桥状态",
		description: "Bridge + capability groups, foreground app, accessibility, permissions, shell backend/uid, SAF dirs, workspace boundary.",
		promptSnippet: "Bridge + capability status",
		promptGuidelines: [
			"On [DISABLED]/[NO_PERMISSION] read android_status; relay its reason, no retry.",
		],
		parameters: Type.Object({}),
		run: (params, ctx) => deviceTool("android_bridge_status").run(params, ctx),
	},
	{
		name: "android_bridge_status",
		exposure: "hidden",
		capability: null,
		label: "设备桥状态",
		description: "Bridge + capability state: groups on/usable, accessibility, missing permissions, screenshot support.",
		promptSnippet: "Bridge + capability status",
		promptGuidelines: [
			"On [DISABLED]/[NO_PERMISSION] read android_bridge_status; relay its reason, no retry.",
		],
		parameters: Type.Object({}),
		run: async () => {
			let health: HealthPayload;
			try {
				health = await bridgeHealth();
			} catch (error) {
				const text = error instanceof BridgeError
					? error.toModelMessage()
					: `设备桥不可用：${String(error)}`;
				return textResult(`设备桥状态：未连接\n\n${text}`, { connected: false });
			}
			const lines: string[] = [];
			lines.push(`设备桥：运行中（127.0.0.1:${health.port}，协议 v${health.version}）`);
			lines.push(`设备：Android ${health.androidRelease}（SDK ${health.sdkInt}）`);
			// Decisive context for an agent: which app it is about to act on. Printed
			// before anything else so a stale assumption is visible immediately.
			if (health.foreground) {
				lines.push(`前台：${health.foreground.packageName ?? "未知"}${health.foreground.class ? `/${health.foreground.class}` : ""}`);
			} else {
				lines.push("前台：未知（无障碍服务未连接，无法读取前台应用）");
			}
			if (health.audit?.lastUnpaired) {
				lines.push(`上次执行异常终止：${health.audit.lastUnpaired}`);
			}
			lines.push(
				`无障碍服务：${
					health.accessibilityState === "connected"
						? "运行中"
						: health.accessibilityState === "enabled_not_connected"
							? "已启用，正在连接中（稍等重试即可）"
							: health.accessibilityRunning
								? "运行中"
								: "未运行"
				}（系统设置中${health.accessibilityEnabledInSettings ? "已启用" : "未启用"}）`,
			);
			lines.push(`截图：${health.screenshotSupported ? "支持" : "不支持（需要 Android 11+）"}`);
			lines.push(
				`系统权限：定位${health.locationPermissionGranted ? "已授予" : "未授予"} · ` +
					`通知${health.notificationPermissionGranted ? "已授予" : "未授予"} · ` +
					`震动${health.vibratePermissionGranted ? "已授予" : "未授予"}`,
			);
			lines.push("");
			lines.push("能力分组：");
			for (const capability of health.capabilities) {
				const state = !capability.enabled
					? "已关闭"
					: capability.enabledForSession === false
						? "本会话暂时禁用"
						: capability.usable
							? "可用"
							: "已开启但不可用";
				lines.push(`- ${capability.title}（${capability.id}）：${state}`);
				if (!capability.usable && capability.reason) lines.push(`    原因：${capability.reason}`);
			}
			const shells = health.shellBackends.map((backend) => `${backend.label}${backend.available ? "" : "（不可用）"}`);
			lines.push("");
			lines.push(`Shell 后端：${shells.length > 0 ? shells.join("、") : "无"}`);
			const shizuku = health.shizuku;
			if (shizuku) {
				// The identity, not just a label: "已就绪（ADB 身份，uid=2000）" is what
				// tells the model that input/am/screencap are actually available.
				const identity = shizuku.ready
					? shizuku.uid === 0
						? "已就绪（root，uid=0）"
						: `已就绪（ADB 身份，uid=${shizuku.uid}）`
					: "未就绪";
				lines.push(`Shizuku：${identity} —— ${shizuku.note}`);
			}
			const workspace = health.workspace;
			if (workspace) {
				// Two guest spellings of one host directory exist, and they are not
				// interchangeable: the engine mounts the workspace at
				// `/workspace/<relative-to-filesDir>` (which is this process's cwd), while
				// the app's terminal tab mounts the same host directory at plain
				// `/workspace`. Both are printed so a model never has to guess.
				const aliases = workspace.guestPathAliases?.filter((alias) => alias !== workspace.guestPath) ?? [];
				lines.push(
					`Shell 写入边界：${workspace.shellPath ?? "未确定"}（guest 内是 ${workspace.guestPath}` +
						(aliases.length > 0 ? `；终端标签页是 ${aliases.join("、")}` : "") +
						"）—— 工作区之内不拦，工作区之外会拒。",
				);
			}
			if (typeof health.shellSyntaxRelaxed === "boolean") {
				lines.push(`Shell 放宽模式：${health.shellSyntaxRelaxed ? "已开启（$(...)、反引号、sh/eval 都允许）" : "已关闭（默认）"}`);
			}
			const saf = health.saf;
			if (saf) {
				lines.push(
					saf.count > 0
						? `已授权（SAF）目录：${saf.roots.join("、")}`
						: "已授权（SAF）目录：无 —— 需要用户在「设置 → 设备能力 → 存储」授权目录后，android_files 才可用。",
				);
			}
			const gate = health.gate;
			if (gate?.reported) {
				const grants = gate.sessionGrants ?? [];
				lines.push(
					`本会话已记住同意的设备操作：${grants.length > 0 ? grants.join("、") : "无（每个危险操作都会单独询问）"}`,
				);
			}
			lines.push(`审计日志：${health.auditLogPath}`);
			return textResult(lines.join("\n"), {
				connected: true,
				port: health.port,
				capabilities: health.capabilities,
			});
		},
	},

	// ------------------------------------------------------------ 屏幕 / UI ----
	{
		name: "android_ui",
		capability: "accessibility",
		label: "屏幕操作",
		description: "Screen: action=dump|tap|swipe|input|key|keyevent|screenshot. Dump first; its indices feed tap and input.",
		promptSnippet: "Read screen / tap / type / swipe / screenshot",
		promptGuidelines: [
			"Dump before acting; on NOT_FOUND re-dump or pass text/desc/resourceId to action=tap.",
			"Custom UI / games / video: action=screenshot (crop with region); raw keys need Shizuku.",
		],
		parameters: Type.Object({
			action: UiAction,
			filter: Type.Optional(
				Type.String({ description: "dump: keep nodes whose text/description/id contains this, plus ancestors." }),
			),
			maxNodes: Type.Optional(Type.Number({ description: "dump: max nodes, default 400, cap 2000." })),
			waitForText: Type.Optional(
				Type.String({ description: "dump: wait until a node text contains this; NOT_FOUND on timeout." }),
			),
			waitForId: Type.Optional(
				Type.String({ description: "dump: wait until a node resource id contains this." }),
			),
			waitMs: Type.Optional(Type.Number({ description: "dump: wait timeout ms, default 5000, cap 30000." })),
			diff: Type.Optional(Type.Boolean({ description: "dump: only changes since the last dump, default false." })),
			index: Type.Optional(Type.Number({ description: "tap/input: node index from the last dump." })),
			x: Type.Optional(Type.Number({ description: "tap: X in display px; with y, index is ignored." })),
			y: Type.Optional(Type.Number({ description: "tap: Y in display px." })),
			text: Type.Optional(
				Type.String({ description: "tap/swipe: match node text (substring, case-insensitive). input: the text to write." }),
			),
			desc: Type.Optional(Type.String({ description: "tap/input: match contentDescription substring." })),
			resourceId: Type.Optional(Type.String({ description: "tap/input/swipe: match resource id substring, e.g. btn_send." })),
			longPress: Type.Optional(Type.Boolean({ description: "tap: long-press, default false." })),
			x1: Type.Optional(Type.Number({ description: "swipe: start x in display px." })),
			y1: Type.Optional(Type.Number({ description: "swipe: start y." })),
			x2: Type.Optional(Type.Number({ description: "swipe: end x." })),
			y2: Type.Optional(Type.Number({ description: "swipe: end y." })),
			durationMs: Type.Optional(Type.Number({ description: "swipe: gesture ms, default 300." })),
			direction: Type.Optional(
				StringEnum(["forward", "backward"] as const, {
					description: "swipe: scroll the node at index/text/resourceId instead of dragging.",
				}),
			),
			submit: Type.Optional(Type.Boolean({ description: "input: attempt enter after writing, default false." })),
			key: Type.Optional(ScreenKey),
			keys: Type.Optional(
				Type.String({ description: "keyevent: space/comma-separated, e.g. \"ENTER\"; KEYCODE_ prefix optional." }),
			),
			repeat: Type.Optional(Type.Number({ description: "keyevent: 1-20, default 1." })),
			format: Type.Optional(ScreenshotFormat),
			maxDimension: Type.Optional(Type.Number({ description: "screenshot: longest side cap in px, default 1280 (240-4096)." })),
			quality: Type.Optional(Type.Number({ description: "screenshot: JPEG quality 20-100, default 82." })),
			region: Type.Optional(
				Type.Array(Type.Number(), { description: "screenshot: crop [left,top,right,bottom] in display px." }),
			),
			marks: Type.Optional(Type.Boolean({ description: "screenshot: draw the last dump's clickable indices, default false." })),
		}),
		run: (params, ctx) => dispatch("android_ui", UI_ACTIONS, UI_REQUIRED, params, ctx),
	},
	{
		name: "android_ui_dump",
		exposure: "hidden",
		capability: "accessibility",
		label: "读取屏幕",
		description: "Read the screen node tree (indexed); indices feed android_tap and android_input. Coordinates are display pixels.",
		promptSnippet: "Read screen tree (indexed)",
		promptGuidelines: [
			"Dump before acting; on NOT_FOUND re-dump or pass text/desc/id to android_tap; wait via waitForText/waitForId.",
		],
		parameters: Type.Object({
			filter: Type.Optional(
				Type.String({ description: "Keep nodes whose text/description/resource id contains this, plus ancestors." }),
			),
			maxNodes: Type.Optional(
				Type.Number({ description: `Max nodes, default ${400}, cap 2000.` }),
			),
			waitForText: Type.Optional(
				Type.String({ description: "Wait until a node text contains this, then dump; NOT_FOUND on timeout." }),
			),
			waitForId: Type.Optional(
				Type.String({ description: "Wait until a node resource id contains this, then dump." }),
			),
			waitMs: Type.Optional(
				Type.Number({ description: "Timeout ms, default 5000, cap 30000; with waitForText/waitForId." }),
			),
			diff: Type.Optional(
				Type.Boolean({ description: "Only changes since the last dump (added/changed/removed), default false." }),
			),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					filter?: string;
					maxNodes?: number;
					waitForText?: string;
					waitForId?: string;
					waitMs?: number;
					diff?: boolean;
				};
				const waiting = p.waitForText !== undefined || p.waitForId !== undefined;
				const data = await bridgePost<DumpData>("/app/ui/dump", {
					filter: p.filter,
					maxNodes: p.maxNodes,
					diff: p.diff === true,
					...(waiting
						? {
								selector: { text: p.waitForText, resourceId: p.waitForId },
								waitMs: p.waitMs ?? 5000,
							}
						: {}),
				});
				const header =
					`包名 ${data.packageName} · 屏幕 ${data.screen.width}×${data.screen.height} · ` +
					`控件 ${data.nodeCount}/${data.totalNodeCount}` +
					`${data.coordinateSpace ? ` · 坐标 ${data.coordinateSpace}` : ""}` +
					`${data.diff ? ` · 差异 新增${data.added ?? 0}/变化${data.changed ?? 0}/消失${data.removed ?? 0}` : ""}` +
					`${data.truncated ? "（已按上限截断）" : ""}`;
				const body = data.text.length > 0 ? data.text : "（没有可读控件：界面可能是空白，或无障碍服务被限制）";
				return textResult(`${header}\n\n${truncateForModel(body, "屏幕控件树")}`, {
					packageName: data.packageName,
					nodeCount: data.nodeCount,
					truncated: data.truncated,
				});
			}),
	},
	{
		name: "android_tap",
		exposure: "hidden",
		capability: "accessibility",
		label: "点按",
		description: "Tap by dump index (most reliable), x/y, or text/desc/resourceId resolved on-device. longPress for long press.",
		promptSnippet: "Tap node or coordinate",
		parameters: Type.Object({
			index: Type.Optional(Type.Number({ description: "Node index from the last dump." })),
			x: Type.Optional(Type.Number({ description: "X in display px; with y, index is ignored." })),
			y: Type.Optional(Type.Number({ description: "Y in display px." })),
			text: Type.Optional(Type.String({ description: "Match node text (substring, case-insensitive); exclusive with index/x/y." })),
			desc: Type.Optional(Type.String({ description: "Match contentDescription substring." })),
			resourceId: Type.Optional(Type.String({ description: "Match resource id substring, e.g. btn_send." })),
			longPress: Type.Optional(Type.Boolean({ description: "Long-press, default false." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					index?: number;
					x?: number;
					y?: number;
					text?: string;
					desc?: string;
					resourceId?: string;
					longPress?: boolean;
				};
				const data = await bridgePost<ActionData>("/app/ui/tap", {
					index: p.index,
					x: p.x,
					y: p.y,
					longPress: p.longPress === true,
					...selectorBody(p),
				});
				const target = typeof data.target === "string" ? data.target : p.index !== undefined ? `#${p.index}` : "";
				const where = data.x !== undefined ? `(${data.x}, ${data.y})` : target || data.selector || "";
				return textResult(
					`已${p.longPress === true ? "长按" : "点按"} ${where}${target && where !== target ? ` ${target}` : ""}（方式：${data.mode}）${actionEnvelope(data)}`,
					{ mode: data.mode, changed: data.changed, after: data.after },
				);
			}),
	},
	{
		name: "android_input",
		exposure: "hidden",
		capability: "accessibility",
		label: "输入文本",
		description: "Type into the focused field or a dump index; falls back to clipboard paste (replaces the clipboard) when refused.",
		promptSnippet: "Type into a field",
		parameters: Type.Object({
			text: Type.String({ description: "Text to write (replaces field contents)." }),
			index: Type.Optional(Type.Number({ description: "Field index from the last dump; omit for the focused field." })),
			desc: Type.Optional(Type.String({ description: "Match the field by contentDescription substring." })),
			resourceId: Type.Optional(Type.String({ description: "Match the field by resource id substring, e.g. input_box." })),
			submit: Type.Optional(Type.Boolean({ description: "Attempt enter after writing, default false." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text: string; index?: number; desc?: string; resourceId?: string; submit?: boolean };
				const data = await bridgePost<{
					chars: number;
					submitted: boolean;
					submitHint?: string;
					mechanism?: string;
					clipboardNote?: string;
					before?: string | null;
					after?: string | null;
					changed?: boolean;
				}>("/app/ui/input", {
					text: p.text,
					index: p.index,
					submit: p.submit === true,
					...selectorBody({ desc: p.desc, resourceId: p.resourceId }),
				});
				let text = `已写入 ${data.chars} 个字符${data.submitted ? "，并已提交" : ""}`;
				if (data.mechanism === "clipboard_paste") text += "（改用剪贴板粘贴完成）";
				text += actionEnvelope(data);
				if (data.clipboardNote) text += `\n${data.clipboardNote}`;
				if (data.submitHint) text += `\n${data.submitHint}`;
				return textResult(text, { chars: data.chars, submitted: data.submitted, mechanism: data.mechanism });
			}),
	},
	{
		name: "android_key",
		exposure: "hidden",
		capability: "accessibility",
		label: "系统按键",
		description: "Run a system global action. For raw keys (enter/delete/arrows) use android_keyevent (needs Shizuku).",
		promptSnippet: "Global action (back/home/lock)",
		promptGuidelines: [
			"Raw keys need android_keyevent + Shizuku; else android_key.",
		],
		parameters: Type.Object({
			key: ScreenKey,
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { key: string };
				const data = await bridgePost<{ key: string; mode: string }>("/app/ui/key", { key: p.key });
				return textResult(`已执行按键 ${data.key}（${data.mode}）`, data as unknown as Record<string, unknown>);
			}),
	},
	{
		name: "android_keyevent",
		exposure: "hidden",
		capability: "accessibility",
		label: "注入原始按键",
		description: "Inject raw keys into the focused window via Shizuku (ADB uid=2000); refuses without it.",
		promptSnippet: "Inject raw keys (Shizuku)",
		parameters: Type.Object({
			keys: Type.String({ description: "Space/comma-separated, e.g. \"ENTER\" or \"DPAD_DOWN DPAD_DOWN\"; KEYCODE_ prefix optional." }),
			repeat: Type.Optional(Type.Number({ description: "1-20, default 1." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { keys: string; repeat?: number };
				const data = await bridgePost<{ keys: string[]; repeat: number; mode: string; backend: string }>(
					"/app/ui/keyevent",
					{ keys: p.keys, repeat: p.repeat },
				);
				return textResult(
					`已注入 ${data.keys.join(" ")}${data.repeat > 1 ? ` ×${data.repeat}` : ""}（${data.mode}，后端 ${data.backend}）`,
					data as unknown as Record<string, unknown>,
				);
			}),
	},
	{
		name: "android_swipe",
		exposure: "hidden",
		capability: "accessibility",
		label: "滑动 / 滚动",
		description: "Swipe in display pixels, or scroll the node at index/selector with direction (accessibility scroll action).",
		promptSnippet: "Swipe or scroll a node",
		parameters: Type.Object({
			x1: Type.Optional(Type.Number({ description: "Start x in display pixels." })),
			y1: Type.Optional(Type.Number({ description: "Start y." })),
			x2: Type.Optional(Type.Number({ description: "End x." })),
			y2: Type.Optional(Type.Number({ description: "End y." })),
			durationMs: Type.Optional(Type.Number({ description: "Gesture ms, default 300." })),
			direction: Type.Optional(
				StringEnum(["forward", "backward"] as const, {
					description: "Scroll the node instead: forward/backward; needs index/selector.",
				}),
			),
			index: Type.Optional(Type.Number({ description: "Scrollable node index." })),
			text: Type.Optional(Type.String({ description: "Match the scrollable node by text substring." })),
			resourceId: Type.Optional(Type.String({ description: "Match the scrollable node by resource id substring." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					x1?: number;
					y1?: number;
					x2?: number;
					y2?: number;
					durationMs?: number;
					direction?: string;
					index?: number;
					text?: string;
					resourceId?: string;
				};
				if (p.direction !== undefined) {
					if (p.index === undefined && p.text === undefined && p.resourceId === undefined) {
						throw new Error(
							"[BAD_PARAM] android_swipe 用 direction 滚动时还需要指明滚哪个控件：index、text 或 resourceId 至少给一个。" +
								"\n提示：先在 android_ui_dump 里找带 scrollable 的控件，或直接给它一个 text/resourceId。",
						);
					}
					const data = await bridgePost<ActionData>("/app/ui/scroll", {
						direction: p.direction,
						index: p.index,
						...selectorBody({ text: p.text, resourceId: p.resourceId }),
					});
					return textResult(
						`已${p.direction === "forward" ? "向后" : "向前"}滚动列表（方式：${data.mode}）${actionEnvelope(data)}`,
						{ mode: data.mode, changed: data.changed },
					);
				}
				if (p.x1 === undefined || p.y1 === undefined || p.x2 === undefined || p.y2 === undefined) {
					throw new Error(
						"[BAD_PARAM] android_swipe 需要 x1/y1/x2/y2，或者 direction + index/selector（滚动控件）。" +
							"\n提示：滚动列表优先用 direction，坐标滑动留给画布类界面。",
					);
				}
				const data = await bridgePost<ActionData & { durationMs: number }>("/app/ui/swipe", {
					x1: p.x1,
					y1: p.y1,
					x2: p.x2,
					y2: p.y2,
					durationMs: p.durationMs,
				});
				return textResult(
					`已从 (${p.x1}, ${p.y1}) 滑动到 (${p.x2}, ${p.y2})，用时 ${data.durationMs}ms${actionEnvelope(data)}`,
					{ durationMs: data.durationMs, changed: data.changed },
				);
			}),
	},
	{
		name: "android_screenshot",
		exposure: "hidden",
		capability: "accessibility",
		label: "截屏",
		description: "Capture the screen (Android 11+); region crops it, marks draws the last dump's indices. Secure windows fail.",
		promptSnippet: "Capture the screen",
		promptGuidelines: [
			"Custom UI/games/video: android_screenshot; crop small text with region.",
		],
		parameters: Type.Object({
			format: Type.Optional(ScreenshotFormat),
			maxDimension: Type.Optional(Type.Number({ description: "Longest side cap in px, default 1280 (240-4096)." })),
			quality: Type.Optional(Type.Number({ description: "JPEG quality 20–100, default 82." })),
			region: Type.Optional(
				Type.Array(Type.Number(), { description: "Crop [left,top,right,bottom] in display px; omit for full screen." }),
			),
			marks: Type.Optional(
				Type.Boolean({ description: "Draw the last dump's clickable indices, default false." }),
			),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					format?: "jpeg" | "png";
					maxDimension?: number;
					quality?: number;
					region?: number[];
					marks?: boolean;
				};
				const data = await bridgePost<ScreenshotData>(
					"/app/screenshot",
					{
						format: p.format,
						maxDimension: p.maxDimension,
						quality: p.quality,
						region: p.region,
						marks: p.marks === true,
					},
					60_000,
				);
				const mapping =
					data.scale !== undefined && data.scale !== 1
						? `，已缩放 ×${data.scale.toFixed(3)}（原图 ${data.sourceWidth}×${data.sourceHeight}，坐标空间 ${data.coordinateSpace ?? "display"}）`
						: "";
				const notes: string[] = [
					`截图 ${data.width}×${data.height}（${data.mimeType}，${formatSize(data.bytes)}）${mapping}。`,
				];
				if (data.region) notes.push(`裁剪区域（display 像素）：[${data.region.join(", ")}]。`);
				if (data.marks === true || data.markedIndices !== undefined) {
					notes.push(`已标注 ${data.markedIndices ?? 0} 个可点控件编号，可以直接用这些编号调用 android_tap。`);
				}
				if (data.marksNote) notes.push(data.marksNote);
				notes.push("如果画面文字看不清，可以调大 maxDimension、改用 png，或用 region 只截需要的那一块。");
				return imageResult(data.base64, data.mimeType, notes.join(""), {
					width: data.width,
					height: data.height,
					bytes: data.bytes,
					scale: data.scale,
					coordinateSpace: data.coordinateSpace,
				});
			}),
	},

	// ---------------------------------------------------------------- 应用 ----
	{
		name: "android_app",
		capability: "basic",
		label: "应用列表 / 启动 / 结束",
		description: "List apps, launch one by exact package (find it with action=\"list\" first), or stop its background process.",
		promptSnippet: "List / launch / stop apps",
		parameters: Type.Object({
			action: AppAction,
			q: Type.Optional(Type.String({ description: "Substring filter on name or package; list only." })),
			includeSystem: Type.Optional(Type.Boolean({ description: "Include system apps, default false; list only." })),
			limit: Type.Optional(Type.Number({ description: "Max rows, default 60, cap 500; list only." })),
			package: Type.Optional(Type.String({ description: "Exact package, e.g. org.telegram.messenger; required for launch." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { action: string; q?: string; includeSystem?: boolean; limit?: number; package?: string };
				if (p.action === "list") {
					const data = await bridgeGet<AppsData>("/app/apps", {
						q: p.q,
						includeSystem: p.includeSystem,
						limit: p.limit,
					});
					const lines = data.apps.map(
						(app) =>
							`${app.label}  ${app.packageName}` +
							`${app.system ? "  [系统]" : ""}${app.launchable ? "" : "  [不可启动]"}`,
					);
					if (lines.length === 0) lines.push("（没有匹配的应用）");
					const body = `${lines.join("\n")}\n\n共 ${data.count} 条${data.truncated ? "（已截断）" : ""}`;
					const note = data.note ? `\n\n说明：${data.note}` : "";
					return textResult(`[action list] ${truncateForModel(body, "应用列表")}${note}`, { count: data.count });
				}
				if (p.action === "launch") {
					if (typeof p.package !== "string" || p.package.trim().length === 0) {
						throw new Error(
							"[BAD_PARAM] android_app 的 action=\"launch\" 需要 package（精确包名，先用 action=\"list\" 查）。" +
								"\n提示：请传入 package 后重新调用 android_app。",
						);
					}
					const data = await bridgePost<{
						packageName: string;
						component: string;
						mode?: string;
						foreground?: string;
						verified?: boolean;
						movedToFront?: boolean;
						note?: string;
					}>("/app/apps/launch", {
						package: p.package,
					});
					// The bridge verifies that the foreground package really changed; a
					// launch that was dropped by Android 10+ arrives as an error instead,
					// so reaching this line means it happened.
					const how = data.verified
						? `，前台已切到 ${data.foreground ?? data.packageName}（${data.mode}）`
						: "（未能验证前台，请以 android_bridge_status 的「前台」行为准）";
					return textResult(
						`[action launch] 已启动 ${data.packageName}${data.component ? `（${data.component}）` : ""}${how}` +
							`${data.note ? `\n${data.note}` : ""}`,
						data as unknown as Record<string, unknown>,
					);
				}
				if (p.action === "stop") {
					if (typeof p.package !== "string" || p.package.trim().length === 0) {
						throw new Error(
							"[BAD_PARAM] android_app 的 action=\"stop\" 需要 package（精确包名，先用 action=\"list\" 查）。" +
								"\n提示：请传入 package 后重新调用 android_app。",
						);
					}
					const data = await bridgePost<{ packageName: string; mode: string; note?: string }>("/app/apps/stop", {
						package: p.package,
					});
					const note = data.note ? `\n${data.note}` : "";
					return textResult(
						`[action stop] 已请求结束后台进程：${data.packageName}（${data.mode}）${note}`,
						data as unknown as Record<string, unknown>,
					);
				}
				throw badParam("android_app", "action", ["list", "launch", "stop"], p.action);
			}),
	},
	{
		name: "android_stop_app",
		capability: "accessibility",
		label: "结束应用",
		description: "Kill a user app's background process; system apps, critical processes and pi-android are refused.",
		promptSnippet: "Kill a background app",
		parameters: Type.Object({
			package: Type.String({ description: "Exact package name." }),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { package: string };
				const data = await bridgePost<{ packageName: string; mode: string; note?: string }>("/app/apps/stop", {
					package: p.package,
				});
				const note = data.note ? `\n${data.note}` : "";
				return textResult(`已请求结束后台进程：${data.packageName}（${data.mode}）${note}`, data as unknown as Record<string, unknown>);
			}),
	},

	// ------------------------------------------------------------ 交互/输出 ----
	{
		name: "android_io",
		capability: "basic",
		label: "对外输出",
		description: "User-visible output: action=clipboard|say|vibrate|share|open. share opens the system sheet, open launches a URL.",
		promptSnippet: "Clipboard / notify / vibrate / share / open",
		parameters: Type.Object({
			action: IoAction,
			text: Type.Optional(
				Type.String({ description: "clipboard: text to write (omit to read). say: text to send or read. share: body to share." }),
			),
			kind: Type.Optional(
				StringEnum(["notification", "toast", "speak"] as const, {
					description: "say: notification, toast, or speak.",
				}),
			),
			title: Type.Optional(Type.String({ description: "say: notification title, default pi." })),
			id: Type.Optional(Type.Number({ description: "say: notification id (overwrites the same one); default auto." })),
			long: Type.Optional(Type.Boolean({ description: "say: toast long duration, default false." })),
			language: Type.Optional(Type.String({ description: "say: BCP-47 tag, e.g. zh-CN; default system." })),
			rate: Type.Optional(Type.Number({ description: "say: speech rate 0.1-3.0, default 1.0." })),
			pitch: Type.Optional(Type.Number({ description: "say: pitch 0.1-3.0, default 1.0." })),
			ms: Type.Optional(Type.Number({ description: "vibrate: length ms, default 200, cap 10000." })),
			pattern: Type.Optional(
				Type.Array(Type.Number(), { description: "vibrate: pattern ms, e.g. [0,120,80,120]; overrides ms." }),
			),
			url: Type.Optional(Type.String({ description: "share: link to attach. open: full URL, mailto:, or intent://…." })),
			subject: Type.Optional(Type.String({ description: "share: title." })),
		}),
		run: (params, ctx) => dispatch("android_io", IO_ACTIONS, IO_REQUIRED, params, ctx),
	},
	{
		name: "android_say",
		exposure: "hidden",
		capability: "basic",
		label: "通知 / 短提示 / 朗读",
		description: "Post a notification, show a short on-screen message, or read text with TTS.",
		promptSnippet: "Notify / toast / speak",
		parameters: Type.Object({
			kind: StringEnum(["notification", "toast", "speak"] as const, {
				description: "notification, toast, or speak.",
			}),
			text: Type.String({ description: "Text to send or read." }),
			title: Type.Optional(Type.String({ description: "Notification title, default pi." })),
			id: Type.Optional(Type.Number({ description: "Notification id (overwrites the same one); default auto." })),
			long: Type.Optional(Type.Boolean({ description: "Long duration, default false." })),
			language: Type.Optional(Type.String({ description: "BCP-47 tag, e.g. zh-CN; default system." })),
			rate: Type.Optional(Type.Number({ description: "Speech rate 0.1-3.0, default 1.0." })),
			pitch: Type.Optional(Type.Number({ description: "Pitch 0.1-3.0, default 1.0." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					kind: string;
					text: string;
					title?: string;
					id?: number;
					long?: boolean;
					language?: string;
					rate?: number;
					pitch?: number;
				};
				if (p.kind === "notification") {
					const data = await bridgePost<{ id: number; channel: string }>("/app/notify", {
						text: p.text,
						title: p.title,
						id: p.id,
					});
					return textResult(`[kind notification] 已发送通知（id=${data.id}）`, data as unknown as Record<string, unknown>);
				}
				if (p.kind === "toast") {
					const data = await bridgePost<{ duration: string }>("/app/toast", { text: p.text, long: p.long === true });
					return textResult(`[kind toast] 已显示提示（${data.duration}）`, data as unknown as Record<string, unknown>);
				}
				if (p.kind === "speak") {
					const data = await bridgePost<{ spoken: boolean; finished: boolean; language: string; chars: number }>(
						"/app/tts",
						{ text: p.text, language: p.language, rate: p.rate, pitch: p.pitch },
						60_000,
					);
					return textResult(
						`[kind speak] 已朗读 ${data.chars} 个字符（${data.language}）${data.finished ? "，已读完" : "，仍在播放"}`,
						data as unknown as Record<string, unknown>,
					);
				}
				throw badParam("android_say", "kind", ["notification", "toast", "speak"], p.kind);
			}),
	},
	{
		name: "android_vibrate",
		exposure: "hidden",
		capability: "basic",
		label: "震动",
		description: "Vibrate the phone.",
		promptSnippet: "Vibrate the phone",
		parameters: Type.Object({
			ms: Type.Optional(Type.Number({ description: "Length ms, default 200, cap 10000." })),
			pattern: Type.Optional(
				Type.Array(Type.Number(), { description: "Pattern ms, e.g. [0,120,80,120] (off-on-off-on); overrides ms." }),
			),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { ms?: number; pattern?: number[] };
				const data = await bridgePost<{ ok: boolean }>("/app/vibrate", { ms: p.ms, pattern: p.pattern });
				return textResult("已触发震动", data as unknown as Record<string, unknown>);
			}),
	},
	{
		name: "android_share",
		exposure: "hidden",
		capability: "basic",
		label: "分享",
		description: "Share text or a link via the system sheet; to open a URL use android_open.",
		promptSnippet: "Share to another app",
		parameters: Type.Object({
			text: Type.Optional(Type.String({ description: "Body to share." })),
			url: Type.Optional(Type.String({ description: "Link to attach." })),
			subject: Type.Optional(Type.String({ description: "Title." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text?: string; url?: string; subject?: string };
				const data = await bridgePost<{ ok: boolean; action: string }>("/app/share", {
					text: p.text,
					url: p.url,
					subject: p.subject,
				});
				return textResult("已打开系统分享面板，请让用户选择目标应用。", data as unknown as Record<string, unknown>);
			}),
	},
	{
		name: "android_open",
		exposure: "hidden",
		capability: "basic",
		label: "打开链接",
		description: "Open a URL/deep link with the default app; intent: URLs fall back to browser_fallback_url.",
		promptSnippet: "Open URL / deep link",
		parameters: Type.Object({
			url: Type.String({
				description:
					"Full URL: https://…, mailto:…, or intent://…#Intent;scheme=…;package=…;S.browser_fallback_url=…;end.",
			}),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { url: string };
				const data = await bridgePost<{ ok: boolean; action: string }>("/app/open", { url: p.url });
				return textResult(`已请求打开 ${p.url}`, data as unknown as Record<string, unknown>);
			}),
	},

	// ------------------------------------------------------------ 剪贴板 ----
	{
		name: "android_clipboard",
		exposure: "hidden",
		capability: "basic",
		label: "剪贴板",
		description: "Pass text to write the clipboard, omit it to read; reads work only in the foreground (Android 10+).",
		promptSnippet: "Read/write clipboard",
		parameters: Type.Object({
			text: Type.Optional(Type.String({ description: "Text to write; omit to read the clipboard." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text?: string };
				if (typeof p.text === "string") {
					const data = await bridgePost<{ chars: number }>("/app/clipboard", { text: p.text });
					return textResult(`[clipboard write] 已写入剪贴板（${data.chars} 字符）`, data as unknown as Record<string, unknown>);
				}
				const data = await bridgeGet<ClipboardData>("/app/clipboard");
				if (data.empty) {
					const note = data.note ? `\n${data.note}` : "";
					return textResult(`[clipboard read] 剪贴板为空或不可读。${note}`, { empty: true });
				}
				return textResult(
					`[clipboard read] 剪贴板内容（${data.text.length} 字符）：\n\n${truncateForModel(data.text, "剪贴板")}`,
					{ length: data.text.length },
				);
			}),
	},

	// -------------------------------------------------------------- 存储 ----
	{
		name: "android_fs",
		capability: "basic",
		label: "文件",
		description: "Files: action=list|read|write on user-authorized (SAF) dirs, or action=download for the public Download folder (needs op).",
		promptSnippet: "Authorized-dir / Download files",
		parameters: Type.Object({
			action: FsAction,
			path: Type.Optional(
				Type.String({ description: "list: omit for root names, else \"root/relative\". read/write: \"root/relative\"." }),
			),
			op: Type.Optional(
				StringEnum(["write", "read"] as const, { description: "download: write = export, read = import." }),
			),
			name: Type.Optional(Type.String({ description: "download: file name, no path; e.g. report.md." })),
			content: Type.Optional(Type.String({ description: "write/download: text (not with base64)." })),
			base64: Type.Optional(Type.String({ description: "write/download: binary as base64 (not with content)." })),
			mimeType: Type.Optional(Type.String({ description: "write/download: MIME type, default text/plain." })),
			maxBytes: Type.Optional(Type.Number({ description: "read/download: max bytes, default 1MB, cap 4MB." })),
		}),
		run: async (params, ctx) => {
			const action = typeof params.action === "string" ? params.action : "";
			const rest = withoutAction(params);
			if (action === "list" || action === "download") {
				if (action === "download") {
					requireText("android_fs", action, params, ["op", "name"]);
					return deviceTool("android_download").run(rest, ctx);
				}
				return deviceTool("android_files_list").run(rest, ctx);
			}
			if (action === "read" || action === "write") {
				requireText("android_fs", action, params, ["path"]);
				return deviceTool("android_files").run({ ...rest, op: action }, ctx);
			}
			throw badParam("android_fs", "action", ["list", "read", "write", "download"], params.action);
		},
	},
	{
		name: "android_download",
		exposure: "hidden",
		capability: "basic",
		label: "公共 Download 读写",
		description: "Read/write the public Download folder; user-authorized dirs use android_files. write: no permission on API 29+, storage permission on 8/9. read: own exports only on 33+, storage permission on 30-32.",
		promptSnippet: "Public Download files",
		parameters: Type.Object({
			op: StringEnum(["write", "read"] as const, {
				description: "write = export, read = import.",
			}),
			name: Type.String({ description: "File name, no path; e.g. report.md." }),
			content: Type.Optional(Type.String({ description: "Text; with write, not base64." })),
			base64: Type.Optional(Type.String({ description: "Binary as base64; with write, not content." })),
			mimeType: Type.Optional(Type.String({ description: "MIME type, default text/plain." })),
			maxBytes: Type.Optional(Type.Number({ description: "Max bytes, default 1MB, cap 4MB." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					op: string;
					name: string;
					content?: string;
					base64?: string;
					mimeType?: string;
					maxBytes?: number;
				};
				if (p.op === "write") {
					const data = await bridgePost<ExportData>("/app/export", {
						name: p.name,
						content: p.content,
						base64: p.base64,
						mimeType: p.mimeType,
					});
					return textResult(
						`[op write] 已导出到 ${data.location}/${data.displayName}（${formatSize(data.bytes)}）\nURI：${data.uri}`,
						data as unknown as Record<string, unknown>,
					);
				}
				if (p.op === "read") {
					const data = await bridgePost<ImportData>("/app/import", { name: p.name, maxBytes: p.maxBytes });
					if (typeof data.text === "string") {
						return textResult(
							`[op read] 已读入 ${data.displayName}（${formatSize(data.bytes)}${data.truncated ? "，已截断" : ""}）：\n\n` +
								truncateForModel(data.text, "文件内容"),
							{ bytes: data.bytes },
						);
					}
					return textResult(
						`[op read] 已读入 ${data.displayName}（${formatSize(data.bytes)}），它是二进制文件。` +
							"如需查看，可以再调用一次并让用户确认，或改用 bash 工具在 guest 内处理。",
						{ bytes: data.bytes, binary: true },
					);
				}
				throw badParam("android_download", "op", ["write", "read"], p.op);
			}),
	},
	{
		name: "android_files_list",
		exposure: "hidden",
		capability: "basic",
		label: "已授权目录",
		description: "List user-authorized (SAF) dirs; omit path for root names. Read/write them with android_files.",
		promptSnippet: "List authorized dirs",
		parameters: Type.Object({
			path: Type.Optional(Type.String({ description: "Omit for root names; else \"root/relative\"." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { path?: string };
				if (!p.path || p.path.trim().length === 0) {
					const data = await bridgeGet<SafRootsData>("/app/files");
					const lines = data.roots.map((root) => `- ${root.name}${root.exists ? "" : "（授权已失效）"}`);
					if (lines.length === 0) lines.push("（还没有授权任何目录）");
					return textResult(`已授权目录（${data.count}）：\n\n${lines.join("\n")}\n\n${data.note}`, {
						count: data.count,
					});
				}
				const data = await bridgePost<SafListData>("/app/files/list", { path: p.path });
				const lines = data.entries.map(
					(entry) => `${entry.directory ? "[目录]" : `[${formatSize(entry.bytes)}]`} ${entry.name}`,
				);
				if (lines.length === 0) lines.push("（空目录）");
				return textResult(
					truncateForModel(`${data.path}：\n\n${lines.join("\n")}\n\n共 ${data.count} 项${data.truncated ? "（已截断）" : ""}`, "目录列表"),
					{ count: data.count },
				);
			}),
	},
	{
		name: "android_files",
		exposure: "hidden",
		capability: "basic",
		label: "授权目录读写",
		description: "Read/write files under a user-authorized (SAF) dir. write: creates parent dirs, overwrites. read: text direct, binary as base64. Public Download uses android_download.",
		promptSnippet: "Authorized-dir files",
		parameters: Type.Object({
			op: StringEnum(["write", "read"] as const, {
				description: "write or read.",
			}),
			path: Type.String({ description: "\"root/relative\", e.g. Documents/notes/todo.md; must start with an authorized root name." }),
			content: Type.Optional(Type.String({ description: "Text; with write, not base64." })),
			base64: Type.Optional(Type.String({ description: "Binary as base64; with write, not content." })),
			mimeType: Type.Optional(Type.String({ description: "MIME type, default text/plain." })),
			maxBytes: Type.Optional(Type.Number({ description: "Max bytes, default 1MB, cap 4MB." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					op: string;
					path: string;
					content?: string;
					base64?: string;
					mimeType?: string;
					maxBytes?: number;
				};
				if (p.op === "read") {
					const data = await bridgePost<SafReadData>("/app/files/read", { path: p.path, maxBytes: p.maxBytes });
					if (typeof data.text === "string") {
						return textResult(
							`[op read] 已读入 ${data.path}（${formatSize(data.bytes)}${data.truncated ? "，已截断" : ""}）：\n\n` +
								truncateForModel(data.text, "文件内容"),
							{ path: data.path, bytes: data.bytes },
						);
					}
					return textResult(
						`[op read] 已读入 ${data.path}（${formatSize(data.bytes)}），它是二进制文件，以 base64 返回：\n\n` +
							truncateForModel(data.base64 ?? "", "base64"),
						{ path: data.path, bytes: data.bytes, binary: true },
					);
				}
				if (p.op === "write") {
					const data = await bridgePost<SafWriteData>("/app/files/write", {
						path: p.path,
						content: p.content,
						base64: p.base64,
						mimeType: p.mimeType,
					});
					return textResult(
						`[op write] 已${data.created ? "创建" : "覆盖"} ${data.path}（${formatSize(data.bytes)}，${data.mimeType}）\nURI：${data.uri}`,
						data as unknown as Record<string, unknown>,
					);
				}
				throw badParam("android_files", "op", ["write", "read"], p.op);
			}),
	},

	// ------------------------------------------------- 位置 / 传感器 / 相机 ----
	{
		name: "android_device_state",
		capability: "basic",
		label: "设备状态",
		description: "Read battery, last known location, the sensor list, or one sensor sample.",
		promptSnippet: "Battery / location / sensors",
		parameters: Type.Object({
			what: StringEnum(["battery", "location", "sensors", "sensor"] as const, {
				description: "battery = level, location = position (needs the system location permission), sensors = list, sensor = one sample.",
			}),
			typeName: Type.Optional(
				Type.String({ description: "Sensor type name, e.g. accelerometer; with sensor, not type." }),
			),
			type: Type.Optional(
				Type.Number({ description: "Android Sensor.TYPE_*; with sensor, not typeName." }),
			),
			timeoutMs: Type.Optional(Type.Number({ description: "Sample timeout ms, default 1500." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { what: string; typeName?: string; type?: number; timeoutMs?: number };
				if (p.what === "battery") {
					const data = await bridgeGet<{ percent: number; status: string; plugged: boolean; temperatureC: number }>(
						"/app/battery",
					);
					return textResult(
						`[what battery] 电量 ${data.percent}% · 状态 ${data.status} · ${data.plugged ? "已接电源" : "未接电源"} · ${data.temperatureC}°C`,
						data as unknown as Record<string, unknown>,
					);
				}
				if (p.what === "location") {
					const data = await bridgeGet<{
						latitude: number;
						longitude: number;
						accuracyMeters: number;
						provider: string;
						ageSeconds: number;
						stale: boolean;
					}>("/app/location");
					const freshness = data.stale
						? `（数据已有 ${data.ageSeconds} 秒，可能已经过时）`
						: `（${data.ageSeconds} 秒前）`;
					return textResult(
						`[what location] 位置：${data.latitude}, ${data.longitude}（精度约 ${Math.round(data.accuracyMeters)} 米，来自 ${data.provider}）${freshness}`,
						data as unknown as Record<string, unknown>,
					);
				}
				if (p.what === "sensors") {
					const data = await bridgeGet<SensorsData>("/app/sensors");
					const lines = data.sensors.map(
						(sensor) => `${sensor.typeName}（type=${sensor.type}）  ${sensor.name}  ${sensor.vendor}`,
					);
					return textResult(
						truncateForModel(`[what sensors] 共 ${data.count} 个传感器：\n\n${lines.join("\n")}`, "传感器列表"),
						{ count: data.count },
					);
				}
				if (p.what === "sensor") {
					const data = await bridgeGet<SensorSampleData>("/app/sensor", {
						typeName: p.typeName,
						type: p.type,
						timeoutMs: p.timeoutMs,
					});
					return textResult(
						`[what sensor] ${data.name}（${data.typeName}）：${data.values.join(", ")}${data.units ? ` ${data.units}` : ""}`,
						data as unknown as Record<string, unknown>,
					);
				}
				throw badParam("android_device_state", "what", ["battery", "location", "sensors", "sensor"], p.what);
			}),
	},
	{
		name: "android_torch",
		capability: "basic",
		label: "手电筒",
		description: "Flashlight (camera LED) on/off; some ROMs need the camera permission.",
		promptSnippet: "Flashlight on/off",
		parameters: Type.Object({
			on: Type.Boolean({ description: "true = on, false = off." }),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { on: boolean };
				const data = await bridgePost<{ on: boolean; cameraId: string }>("/app/torch", { on: p.on });
				return textResult(`手电筒已${data.on ? "打开" : "关闭"}（camera ${data.cameraId}）`, data as unknown as Record<string, unknown>);
			}),
	},

	// --------------------------------------------------------------- Shell ----
	{
		name: "android_shell",
		capability: "shell",
		label: "设备 Shell",
		description: "Whitelisted shell; unknown heads refused. Writes only inside the workspace; Shizuku = uid 2000, else the app uid. `$(...)`/backticks need relaxed mode.",
		promptSnippet: "Guarded device shell",
		promptGuidelines: [
			"android_shell only for device identity; workspace git/npm/builds use bash.",
		],
		parameters: Type.Object({
			command: Type.String({ description: "One command; split multiple actions into separate calls. `$(...)`/backticks need relaxed mode." }),
			timeoutMs: Type.Optional(Type.Number({ description: "Timeout in ms, default 15000, cap 60000." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { command: string; timeoutMs?: number };
				const data = await bridgePost<ShellData>("/app/shell", { command: p.command, timeoutMs: p.timeoutMs }, 90_000);
				const parts: string[] = [];
				parts.push(`$ ${p.command}`);
				if (data.stdout.trim().length > 0) parts.push(data.stdout.trimEnd());
				if (data.stderr.trim().length > 0) parts.push(`[stderr]\n${data.stderr.trimEnd()}`);
				parts.push(`[退出码 ${data.exitCode}${data.timedOut ? "，超时被杀" : ""}]`);
				// Without this line the bridge's 50 KiB cap was invisible to the model:
				// `ShellData.truncated` existed but nothing read it, so a clipped
				// `stdout` (or a `stderr` cut while stdout looked complete) arrived
				// looking like the whole output. Name the stream, because the two mean
				// different things: a clipped stdout is a lost result, a clipped stderr
				// is a lost explanation.
				const clipped = [data.stdoutTruncated ? "stdout" : "", data.stderrTruncated ? "stderr" : ""].filter(
					(name) => name.length > 0,
				);
				if (clipped.length > 0) {
					parts.push(`[输出被截断：${clipped.join(" 与 ")} 只保留了前 50 KB；要看全部请缩小命令的输出]`);
				} else if (data.truncated) {
					// A bridge that predates the per-stream fields: it can say "something
					// was cut" but not which.
					parts.push("[输出被截断：stdout 或 stderr 只保留了前 50 KB；要看全部请缩小命令的输出]");
				}
				parts.push(`[后端 ${data.backendLabel}，uid=${data.uid}]`);
				parts.push(data.note);
				if (data.policy) parts.push(data.policy);
				const text = truncateForModel(parts.join("\n"), "Shell 输出", "tail");
				// pi 的失败口径（`core/tools/bash.ts:368-373`）：超时 / 无退出码 / 非零
				// 退出都是错误，用 throw 表达。见 `shellFailureStatus` 的 KDoc：正文里
				// 保留 pi 的原句，App 的退出码解析因此仍然成立。
				const failure = shellFailureStatus(data);
				if (failure !== null) throw new Error(`${text}\n\n${failure}`);
				return textResult(text, {
					exitCode: data.exitCode,
					backend: data.backend,
					uid: data.uid,
				});
			}),
	},

	// ------------------------------------------------------------ 输入法 ----
	//
	// PI 自己就是输入法（`PiInputMethodService`）：这些动作只在「PI 是当前输入法、且某个
	// 输入框有焦点」时成立。密码框读不到内容 —— App 侧连长度都不报，所以 `text` 读到空
	// 不等于输入框是空的。
	{
		name: "android_ime",
		capability: "ime",
		label: "输入法",
		description: "PI as the active IME: action=insert|replace|delete|surround|submit|history|text on the focused field.",
		promptSnippet: "Read/write the focused field via PI's IME",
		promptGuidelines: [
			"Needs PI selected as the system input method; a password field reads as empty, not as an error.",
			"action=text reads the field, action=submit sends the editor action (send/search/done).",
		],
		parameters: Type.Object({
			action: ImeAction,
			text: Type.Optional(Type.String({ description: "insert/replace: the text to write." })),
			before: Type.Optional(Type.Number({ description: "delete: characters before the caret, default 0." })),
			after: Type.Optional(Type.Number({ description: "delete: characters after the caret, default 0." })),
			editorAction: Type.Optional(
				Type.Number({ description: "submit: an explicit EditorInfo.IME_ACTION_* code; omit to use the field's imeOptions (enter)." }),
			),
			limit: Type.Optional(Type.Number({ description: "history: entries, default 20." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					action?: string;
					text?: string;
					before?: number;
					after?: number;
					editorAction?: number;
					limit?: number;
				};
				const action = typeof p.action === "string" ? p.action : "";
				switch (action) {
					case "insert":
					case "replace": {
						requireText("android_ime", action, params, ["text"]);
						const text = p.text ?? "";
						const data = await bridgePost<ActionPayload>(`/app/ime/${action}`, { text });
						requireActionOk("android_ime", data);
						return textResult(
							action === "insert"
								? `已在光标处插入 ${text.length} 个字符。`
								: `已整体替换输入框内容（${text.length} 字符）。`,
							data,
						);
					}
					case "delete": {
						const data = await bridgePost<ActionPayload>("/app/ime/delete", { before: p.before, after: p.after });
						requireActionOk("android_ime", data);
						return textResult(`已删除光标前 ${p.before ?? 0} 个、后 ${p.after ?? 0} 个字符。`, data);
					}
					case "surround": {
						const data = await bridgePost<
							ActionPayload & {
								selectionStart?: number;
								selectionEnd?: number;
								before?: string | null;
								after?: string | null;
								fieldKind?: string;
								isPassword?: boolean;
							}
						>("/app/ime/surround", {});
						const before = typeof data.before === "string" ? data.before : "（读不到）";
						const after = typeof data.after === "string" ? data.after : "（读不到）";
						const field = `${show(data.fieldKind)}${data.isPassword === true ? "，密码框" : ""}`;
						const lines = [
							`光标位置：${show(data.selectionStart)} → ${show(data.selectionEnd)}（${field}）`,
							`光标前：${before}`,
							`光标后：${after}`,
						];
						if (typeof data.note === "string") lines.push("", data.note);
						return textResult(lines.join("\n"), data);
					}
					case "submit": {
						const data = await bridgePost<ActionPayload>("/app/ime/submit", { action: p.editorAction });
						requireActionOk("android_ime", data);
						return textResult(
							typeof p.editorAction === "number"
								? `已提交编辑器动作码 ${p.editorAction}。`
								: "已提交编辑器动作（用输入框的 imeOptions；普通输入框里等于回车）。",
							data,
						);
					}
					case "history": {
						const data = await bridgePost<ActionPayload & Record<string, unknown>>("/app/ime/history", { limit: p.limit });
						const entries = listField(data);
						if (entries === null) return textResult(`输入历史：设备没有返回列表。\n${JSON.stringify(data)}`, data);
						return textResult(
							listText("输入历史", entries, (entry) => {
								const when = typeof entry.at === "number" ? new Date(entry.at).toISOString() : "?";
								if (entry.redacted === true) return `- ${when} ${show(entry.source)}：密码框，只记录了「发生了一次输入」`;
								const detail = typeof entry.detail === "string" ? `（${entry.detail}）` : "";
								return `- ${when} ${show(entry.source)}：${show(entry.inserted)}${detail}`;
							}),
							data,
						);
					}
					case "text": {
						const data = await bridgeGet<ActionPayload & { text?: string | null; chars?: number }>("/app/ime/text");
						if (typeof data.text !== "string") {
							// null 不等于空：密码框与「还没有快照」都是 null，端点把原因写在 note 里。
							const note = typeof data.note === "string" ? `\n${data.note}` : "";
							return textResult(`读不到输入框文本：没有可读的输入框（密码框一律不返回内容）。${note}`, data);
						}
						if (data.text.length === 0) return textResult("当前输入框是空的（0 字符）。", data);
						return textResult(
							`当前输入框（${data.chars ?? data.text.length} 字符）：\n\n${truncateForModel(data.text, "输入框文本")}`,
							data,
						);
					}
					default:
						throw badParam("android_ime", "action", ["insert", "replace", "delete", "surround", "submit", "history", "text"], p.action);
				}
			}),
	},

	// -------------------------------------------------------- 设备管理员 ----
	//
	// `DeviceAdmin`（Device Owner / Profile Owner / 普通设备管理员）里的策略动作。身份决定
	// 能力：普通设备管理员几乎什么都做不了，`action=capabilities` 会如实报出当前身份下每项
	// 能不能做 —— 不要用一次失败的调用去倒推。
	//
	// **不可逆、或影响其他应用的动作**（调用前要在正文里向用户说明后果，被拒绝就停）：
	//  - reboot：设备立刻重启，未保存的内容丢失；
	//  - wipe：Device Owner 下等于恢复出厂设置，Profile Owner 下清空工作资料；执行后数据不再
	//    存在，系统立即重启；
	//  - grant：直接改写**别的应用**的运行时权限授予状态（授予/拒绝/交回系统），等于替用户
	//    回答系统权限弹窗；
	//  - hidden：目标应用从启动器消失（数据保留），用户会以为它被卸载了；
	//  - suspend：目标应用被强制停止、通知被抑制、无法启动；
	//  - install-ca：装一张**系统级且持久**的 CA 证书，卸载本应用也不会移除，之后设备信任
	//    该证书签发的任何站点；
	//  - lock-task：把设备锁进专用设备（Kiosk）模式，只有清单里的应用能启动；空清单解除。
	{
		name: "android_admin",
		capability: "admin",
		label: "设备管理员",
		description: "Device Owner policies: action=status|capabilities|grant|hidden|suspend|uninstall-blocked|install-ca|always-on-vpn|lock-task|update-policy|status-bar|keyguard|camera|reboot|wipe.",
		promptSnippet: "Device admin / Device Owner policies",
		promptGuidelines: [
			"Read action=capabilities first: most actions need Device Owner or Profile Owner.",
			"wipe = factory reset and reboot = reboot now: state the consequence in the reply before calling.",
			"grant rewrites another app's runtime permission; install-ca is system-wide and persistent; hidden/suspend change what the user sees.",
		],
		parameters: Type.Object({
			action: AdminAction,
			package: Type.Optional(
				Type.String({ description: "grant/hidden/uninstall-blocked/always-on-vpn: the target package (always-on-vpn: omit to turn it off)." }),
			),
			permission: Type.Optional(Type.String({ description: "grant: the runtime permission, e.g. android.permission.CAMERA." })),
			state: Type.Optional(Type.Number({ description: "grant: 0 = hand back to the system, 1 = grant, 2 = deny." })),
			hidden: Type.Optional(Type.Boolean({ description: "hidden: true = hide the app, false = unhide." })),
			packages: Type.Optional(Type.Array(Type.String(), { description: "suspend/lock-task: the package list (lock-task: empty = off)." })),
			suspended: Type.Optional(Type.Boolean({ description: "suspend: true = suspend, false = unsuspend." })),
			blocked: Type.Optional(Type.Boolean({ description: "uninstall-blocked: true = block, false = allow." })),
			base64: Type.Optional(
				Type.String({ description: "install-ca: the DER certificate, base64-encoded (installCaCert takes DER, not PEM)." }),
			),
			lockdown: Type.Optional(Type.Boolean({ description: "always-on-vpn: block all networking while the VPN is down, default false." })),
			mode: Type.Optional(
				StringEnum(["automatic", "postpone", "windowed", "clear"] as const, {
					description: "update-policy: which system-update policy to set.",
				}),
			),
			windowStartMinutes: Type.Optional(Type.Number({ description: "update-policy windowed: start minute of day (0-1439)." })),
			windowEndMinutes: Type.Optional(Type.Number({ description: "update-policy windowed: end minute of day." })),
			disabled: Type.Optional(Type.Boolean({ description: "status-bar/keyguard/camera: true = disable, false = restore." })),
			flags: Type.Optional(Type.Number({ description: "wipe: extra DevicePolicyManager flags, default 0." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					action?: string;
					package?: string;
					permission?: string;
					state?: number;
					hidden?: boolean;
					packages?: string[];
					suspended?: boolean;
					blocked?: boolean;
					base64?: string;
					lockdown?: boolean;
					mode?: string;
					windowStartMinutes?: number;
					windowEndMinutes?: number;
					disabled?: boolean;
					flags?: number;
				};
				const action = typeof p.action === "string" ? p.action : "";
				switch (action) {
					case "status": {
						const data = await bridgeGet<ActionPayload & { deviceOwner?: boolean; profileOwner?: boolean; adminActive?: boolean }>("/app/admin/status");
						const role = data.deviceOwner === true
							? "Device Owner"
							: data.profileOwner === true
								? "Profile Owner"
								: data.adminActive === true
									? "普通设备管理员（现代策略需要 Device Owner / Profile Owner）"
									: "没有设备管理员身份";
						return textResult(`设备管理员身份：${role}\n${typeof data.note === "string" ? data.note : ""}`, data);
					}
					case "capabilities": {
						const data = await bridgeGet<Record<string, unknown>>("/app/admin/capabilities");
						const table = Object.entries(data).filter(([, value]) => typeof value === "boolean");
						if (table.length === 0) return textResult(`设备管理员能力：设备没有返回能力表。\n${JSON.stringify(data)}`, data);
						const lines = table.map(([name, allowed]) => `- ${name}：${allowed === true ? "可执行" : "不可执行"}`);
						return textResult(`设备管理员能力（当前身份下）：\n${lines.join("\n")}`, data);
					}
					case "grant": {
						requireText("android_admin", action, params, ["package", "permission"]);
						const state = requireNumber("android_admin", action, params, "state");
						const data = await bridgePost<ActionPayload>("/app/admin/grant", {
							package: p.package,
							permission: p.permission,
							state,
						});
						requireActionOk("android_admin", data);
						const label = state === 0 ? "交回系统" : state === 1 ? "授予" : state === 2 ? "拒绝" : `state=${state}`;
						return textResult(`已把 ${p.package} 的 ${p.permission} 改为「${label}」（等于替用户回答了系统权限弹窗）。`, data);
					}
					case "hidden": {
						requireText("android_admin", action, params, ["package"]);
						const hidden = requireBoolean("android_admin", action, params, "hidden");
						const data = await bridgePost<ActionPayload>("/app/admin/hidden", { package: p.package, hidden });
						requireActionOk("android_admin", data);
						return textResult(
							`已${hidden ? "隐藏" : "恢复"}应用 ${p.package}${hidden ? "（用户从启动器看不到它，数据保留）" : ""}。`,
							data,
						);
					}
					case "suspend": {
						const packages = stringList(p.packages);
						if (packages.length === 0) {
							throw new Error("[BAD_PARAM] android_admin 的 action=\"suspend\" 需要非空的 packages（包名数组）。\n提示：补上 packages 后重新调用 android_admin。");
						}
						const suspended = requireBoolean("android_admin", action, params, "suspended");
						const data = await bridgePost<ActionPayload>("/app/admin/suspend", { packages, suspended });
						// 部分失败（系统回一个「没能挂起」的包名列表）在端点那里已经变成 denial，并把
						// 包名写在正文里，所以这里不需要再分一次支。
						requireActionOk("android_admin", data);
						return textResult(`已${suspended ? "挂起" : "恢复"} ${packages.length} 个应用。`, data);
					}
					case "uninstall-blocked": {
						requireText("android_admin", action, params, ["package"]);
						const blocked = requireBoolean("android_admin", action, params, "blocked");
						const data = await bridgePost<ActionPayload>("/app/admin/uninstall-blocked", { package: p.package, blocked });
						requireActionOk("android_admin", data);
						return textResult(`已${blocked ? "阻止" : "允许"}卸载 ${p.package}。`, data);
					}
					case "install-ca": {
						requireText("android_admin", action, params, ["base64"]);
						const data = await bridgePost<ActionPayload & { bytes?: number }>("/app/admin/install-ca", { base64: p.base64 });
						requireActionOk("android_admin", data);
						return textResult(
							`已安装 CA 证书（${show(data.bytes)} 字节）—— 系统级且持久，卸载 pi-android 也不会移除。`,
							data,
						);
					}
					case "always-on-vpn": {
						const target = typeof p.package === "string" && p.package.trim().length > 0 ? p.package : null;
						const data = await bridgePost<ActionPayload>("/app/admin/always-on-vpn", { package: target, lockdown: p.lockdown === true });
						requireActionOk("android_admin", data);
						return textResult(
							target === null
								? "已关闭常驻（Always-on）VPN。"
								: `已把常驻 VPN 设为 ${target}${p.lockdown === true ? "（lockdown：VPN 断开时阻断全部网络）" : ""}。`,
							data,
						);
					}
					case "lock-task": {
						if (!Array.isArray(p.packages)) {
							throw new Error("[BAD_PARAM] android_admin 的 action=\"lock-task\" 需要 packages（包名数组；空数组表示关闭 Lock Task）。\n提示：补上 packages 后重新调用 android_admin。");
						}
						const packages = stringList(p.packages);
						const data = await bridgePost<ActionPayload>("/app/admin/lock-task", { packages });
						requireActionOk("android_admin", data);
						return textResult(
							packages.length === 0
								? "Lock Task 清单已设为空（等于关闭专用设备模式）。"
								: `已把 ${packages.length} 个应用加入 Lock Task 清单：${packages.join("、")}。`,
							data,
						);
					}
					case "update-policy": {
						requireText("android_admin", action, params, ["mode"]);
						const data = await bridgePost<ActionPayload>("/app/admin/update-policy", {
							mode: p.mode,
							windowStartMinutes: p.windowStartMinutes,
							windowEndMinutes: p.windowEndMinutes,
						});
						requireActionOk("android_admin", data);
						const window = typeof p.windowStartMinutes === "number" && typeof p.windowEndMinutes === "number"
							? `（安装窗口：每天第 ${p.windowStartMinutes}–${p.windowEndMinutes} 分钟）`
							: "";
						return textResult(`系统更新策略已设为「${p.mode}」${window}。`, data);
					}
					case "status-bar":
					case "keyguard":
					case "camera": {
						const disabled = requireBoolean("android_admin", action, params, "disabled");
						const data = await bridgePost<ActionPayload>(`/app/admin/${action}`, { disabled });
						requireActionOk("android_admin", data);
						const what = action === "status-bar" ? "状态栏" : action === "keyguard" ? "锁屏" : "相机";
						return textResult(`${what}已${disabled ? "禁用" : "恢复"}。`, data);
					}
					case "reboot": {
						const data = await bridgePost<ActionPayload>("/app/admin/reboot", {});
						requireActionOk("android_admin", data);
						return textResult("重启指令已交给系统：设备会立即重启，未保存的内容会丢失。", data);
					}
					case "wipe": {
						const data = await bridgePost<ActionPayload>("/app/admin/wipe", { flags: p.flags });
						requireActionOk("android_admin", data);
						return textResult(`擦除指令已被系统接受（flags=${p.flags ?? 0}）：设备会立即重启，被擦除的数据不再存在。`, data);
					}
					default:
						throw badParam(
							"android_admin",
							"action",
							[
								"status",
								"capabilities",
								"grant",
								"hidden",
								"suspend",
								"uninstall-blocked",
								"install-ca",
								"always-on-vpn",
								"lock-task",
								"update-policy",
								"status-bar",
								"keyguard",
								"camera",
								"reboot",
								"wipe",
							],
							p.action,
						);
				}
			}),
	},

	// ------------------------------------------------------------ 通知 ----
	//
	// 通知监听（`PiNotificationListener`）：只有在系统「通知使用权」里勾选过 PI、且服务已连上
	// 时才有内容。`recent` 是当前缓冲区（按 key 去重，最新在末），`events` 是到达/移除/应答的
	// 流水；回复、撤销、延后要的是**当前那条**通知的 key，它只存在于这两个列表的输出里。
	{
		name: "android_notify",
		capability: "basic",
		label: "通知监听",
		description: "Notification listener: action=status|recent|reply|dismiss|dismiss-all|snooze|events.",
		promptSnippet: "Read/reply/dismiss notifications",
		promptGuidelines: [
			"reply needs a notification with a RemoteInput action; action=recent marks those as 可回复.",
			"dismiss/snooze take the key printed by recent or events; a stale key is refused, do not retry it.",
		],
		parameters: Type.Object({
			action: NotifyAction,
			key: Type.Optional(Type.String({ description: "reply/dismiss/snooze: the notification key from action=recent." })),
			text: Type.Optional(Type.String({ description: "reply: the text to send." })),
			ms: Type.Optional(Type.Number({ description: "snooze: milliseconds to postpone it, >0 and capped at 24h." })),
			limit: Type.Optional(Type.Number({ description: "recent/events: entries, default 20 / 50." })),
			since: Type.Optional(
				Type.Number({ description: "events: only events newer than this sequence number (the cursor the previous call printed)." }),
			),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { action?: string; key?: string; text?: string; ms?: number; limit?: number; since?: number };
				const action = typeof p.action === "string" ? p.action : "";
				switch (action) {
					case "status": {
						const data = await bridgeGet<
							ActionPayload & {
								connected?: boolean;
								enabledInSettings?: boolean;
								state?: string;
								activeCount?: number;
								replyableCount?: number;
								eventCount?: number;
							}
						>("/app/notify/status");
						const state = data.connected === true
							? "已连接"
							: data.enabledInSettings === true
								? "已授权，但系统还没绑定服务（稍等约 1 秒）"
								: "没有通知使用权";
						const lines = [
							`通知监听：${state}（${show(data.state)}）`,
							`当前通知 ${show(data.activeCount)} 条，其中可回复 ${show(data.replyableCount)} 条；事件流水 ${show(data.eventCount)} 条`,
						];
						if (typeof data.note === "string") lines.push("", data.note);
						return textResult(lines.join("\n"), data);
					}
					case "recent": {
						const data = await bridgePost<ActionPayload & Record<string, unknown>>("/app/notify/recent", { limit: p.limit });
						const entries = listField(data);
						if (entries === null) return textResult(`最近通知：设备没有返回列表。\n${JSON.stringify(data)}`, data);
						return textResult(
							listText("最近通知", entries, (entry) => {
								const body = typeof entry.text === "string" && entry.text.length > 0 ? `：${entry.text}` : "";
								const flags = [
									entry.replyable === true ? "可回复" : "",
									entry.isOngoing === true ? "常驻" : "",
									entry.isClearable === false ? "不可撤销" : "",
								].filter((flag) => flag.length > 0);
								return `- [${show(entry.key)}] ${show(entry.packageName)}｜${show(entry.title)}${body}${flags.length > 0 ? `（${flags.join("、")}）` : ""}`;
							}),
							data,
						);
					}
					case "reply": {
						requireText("android_notify", action, params, ["key", "text"]);
						const text = p.text ?? "";
						const data = await bridgePost<ActionPayload & { chars?: number }>("/app/notify/reply", { key: p.key, text });
						requireActionOk("android_notify", data);
						return textResult(`已回复通知「${p.key}」（${data.chars ?? text.length} 字符）。`, data);
					}
					case "dismiss": {
						requireText("android_notify", action, params, ["key"]);
						const data = await bridgePost<ActionPayload>("/app/notify/dismiss", { key: p.key });
						requireActionOk("android_notify", data);
						return textResult(`已撤销通知「${p.key}」。`, data);
					}
					case "dismiss-all": {
						const data = await bridgePost<ActionPayload>("/app/notify/dismiss-all", {});
						requireActionOk("android_notify", data);
						return textResult("已撤销所有可撤销的通知。", data);
					}
					case "snooze": {
						requireText("android_notify", action, params, ["key"]);
						const ms = requireNumber("android_notify", action, params, "ms");
						const data = await bridgePost<ActionPayload & { durationMs?: number }>("/app/notify/snooze", { key: p.key, ms });
						requireActionOk("android_notify", data);
						return textResult(`已把通知「${p.key}」延后 ${data.durationMs ?? ms} 毫秒。`, data);
					}
					case "events": {
						const data = await bridgePost<ActionPayload & { cursor?: number } & Record<string, unknown>>("/app/notify/events", {
							limit: p.limit,
							since: p.since,
						});
						const entries = listField(data);
						if (entries === null) return textResult(`通知事件：设备没有返回列表。\n${JSON.stringify(data)}`, data);
						const cursor = typeof data.cursor === "number" ? `\n（cursor=${data.cursor}；下次带 since 只取增量）` : "";
						return textResult(
							listText("通知事件", entries, (entry) => {
								const when = typeof entry.at === "number" ? new Date(entry.at).toISOString() : "?";
								const detail = typeof entry.detail === "string" ? `：${entry.detail}` : "";
								return `- ${when} [${show(entry.seq)}] ${show(entry.type)} ${show(entry.packageName)} ${show(entry.title)}${detail}`;
							}) + cursor,
							data,
						);
					}
					default:
						throw badParam(
							"android_notify",
							"action",
							["status", "recent", "reply", "dismiss", "dismiss-all", "snooze", "events"],
							p.action,
						);
				}
			}),
	},

	// ------------------------------------------------------------ 自动化 ----
	//
	// 规则引擎（`PiAutomation`）：规则落盘，引擎启动后才会真的触发；触发器与动作的
	// 可用条件（通知监听、无障碍、Shell 后端）由 `status` 的 readiness 如实报出。每个端点
	// 自己都会先 `apply`（幂等），所以 `add` 之后不需要再手动启动一次。
	{
		name: "android_automation",
		capability: "basic",
		label: "自动化",
		description: "Automation rules: action=status|list|add|remove|apply|history.",
		promptSnippet: "Automation rules (trigger → actions)",
		promptGuidelines: [
			"add takes name + trigger + actions; every endpoint starts the engine itself, so the rule takes effect at once.",
			"A rule's actions run under the same device policy as the tools they mirror (shell, ui, notify…).",
		],
		parameters: Type.Object({
			action: AutomationAction,
			id: Type.Optional(Type.String({ description: "remove: the rule id from action=list." })),
			name: Type.Optional(Type.String({ description: "add: rule name." })),
			enabled: Type.Optional(Type.Boolean({ description: "add: whether the rule is active, default true." })),
			priority: Type.Optional(Type.Number({ description: "add: higher runs first, default 0." })),
			cooldownMs: Type.Optional(Type.Number({ description: "add: per-rule dedup window in ms, default 60000." })),
			trigger: Type.Optional(AutomationTrigger),
			condition: Type.Optional(AutomationCondition),
			actions: Type.Optional(Type.Array(AutomationRuleAction, { description: "add: what to do when the trigger fires." })),
			limit: Type.Optional(Type.Number({ description: "history: entries, default 20." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					action?: string;
					id?: string;
					name?: string;
					enabled?: boolean;
					priority?: number;
					cooldownMs?: number;
					trigger?: Record<string, unknown>;
					condition?: Record<string, unknown>;
					actions?: Array<Record<string, unknown>>;
					limit?: number;
				};
				const action = typeof p.action === "string" ? p.action : "";
				switch (action) {
					case "status": {
						const data = await bridgeGet<
							ActionPayload & {
								started?: boolean;
								ruleCount?: number;
								enabledCount?: number;
								historyCount?: number;
								readiness?: Record<string, unknown> | null;
							}
						>("/app/automation/status");
						const lines = [
							`自动化引擎：${data.started === true ? "已启动" : "未启动（用 action=apply 启动）"}`,
							`规则 ${show(data.ruleCount)} 条（启用 ${show(data.enabledCount)}）；触发记录 ${show(data.historyCount)} 条`,
						];
						const readiness = data.readiness;
						if (readiness !== undefined && readiness !== null) {
							const facts = Object.entries(readiness).map(([name, value]) => `${name}=${show(value)}`);
							lines.push(`可用条件：${facts.join(" ")}`);
						}
						if (typeof data.note === "string") lines.push("", data.note);
						return textResult(lines.join("\n"), data);
					}
					case "list": {
						const data = await bridgePost<ActionPayload & Record<string, unknown>>("/app/automation/list", {});
						const entries = listField(data);
						if (entries === null) return textResult(`自动化规则：设备没有返回列表。\n${JSON.stringify(data)}`, data);
						return textResult(
							listText("自动化规则", entries, (entry) => {
								const trigger = (entry.trigger ?? {}) as Record<string, unknown>;
								const actions = Array.isArray(entry.actions) ? entry.actions : [];
								const kinds = actions.map((item) => show((item as Record<string, unknown>).type)).join("+");
								const state = entry.enabled === false ? "已停用" : "启用";
								return `- [${show(entry.id)}] ${show(entry.name)}（${state}，priority ${show(entry.priority)}）${show(trigger.type)} → ${kinds.length > 0 ? kinds : "无动作"}`;
							}),
							data,
						);
					}
					case "add": {
						requireText("android_automation", action, params, ["name"]);
						const actions = Array.isArray(p.actions) ? p.actions : [];
						if (actions.length === 0) {
							throw new Error("[BAD_PARAM] android_automation 的 action=\"add\" 需要至少一个 actions 项（type=notify|shell|ui|toast|vibrate）。\n提示：补上 actions 后重新调用 android_automation。");
						}
						const data = await bridgePost<ActionPayload & { rule?: { id?: string; name?: string } }>("/app/automation/add", {
							name: p.name,
							enabled: p.enabled ?? true,
							priority: p.priority ?? 0,
							cooldownMs: p.cooldownMs,
							trigger: p.trigger,
							condition: p.condition,
							actions,
						});
						requireActionOk("android_automation", data);
						// 端点在 `rule` 里回真正入库的那份副本（id 由它补齐）。
						const stored = data.rule ?? {};
						return textResult(
							`已添加规则「${stored.name ?? p.name}」${typeof stored.id === "string" ? `（id=${stored.id}）` : ""}。`,
							data,
						);
					}
					case "remove": {
						requireText("android_automation", action, params, ["id"]);
						const data = await bridgePost<ActionPayload & { id?: string }>("/app/automation/remove", { id: p.id });
						// 没有这条规则时端点是 NOT_FOUND denial（那里才有 hint），不在这里重报一次。
						requireActionOk("android_automation", data);
						return textResult(`已删除规则 ${p.id}。`, data);
					}
					case "apply": {
						const data = await bridgePost<ActionPayload>("/app/automation/apply", {});
						requireActionOk("android_automation", data);
						return textResult("自动化引擎已启动：规则会按触发器与条件执行，可用条件见 action=status 的 readiness。", data);
					}
					case "history": {
						const data = await bridgePost<ActionPayload & Record<string, unknown>>("/app/automation/history", { limit: p.limit });
						const entries = listField(data);
						if (entries === null) return textResult(`触发记录：设备没有返回列表。\n${JSON.stringify(data)}`, data);
						return textResult(
							listText("触发记录", entries, (entry) => {
								const when = typeof entry.at === "number" ? new Date(entry.at).toISOString() : "?";
								const actions = Array.isArray(entry.actions) ? entry.actions.length : 0;
								return `- ${when} ${show(entry.ruleName)}（${show(entry.ruleId)}）${show(entry.reason)}，${actions} 个动作`;
							}),
							data,
						);
					}
					default:
						throw badParam(
							"android_automation",
							"action",
							["status", "list", "add", "remove", "apply", "history"],
							p.action,
						);
				}
			}),
	},

	// ---------------------------------------------------------------- VPN ----
	//
	// 本地 VPN（`PiVpnService`）：TUN 只看得见明文 DNS 与 TCP/UDP 的目的地址，HTTPS 载荷没有
	// root 解不了（status 的 note 会复述这条边界）。`start` 需要用户先在设备上给过一次 VPN
	// 授权；没有授权时 App 侧会拒，原因写在 status 的 lastError 里。
	{
		name: "android_net",
		capability: "basic",
		label: "本地 VPN",
		description: "Local VPN: action=status|start|stop|queries|blocklist (plaintext DNS + flow metadata only).",
		promptSnippet: "Local VPN / DNS log",
		promptGuidelines: [
			"start needs the VPN authorization the user gives on the device; a refusal names it in status.lastError.",
			"queries shows domains and whether the blocklist matched; https payloads stay invisible.",
		],
		parameters: Type.Object({
			action: NetAction,
			sessionName: Type.Optional(Type.String({ description: "start: tunnel name shown in the system VPN dialog." })),
			dnsServers: Type.Optional(Type.Array(Type.String(), { description: "start: DNS servers handed to the system, default [\"10.0.0.2\"]." })),
			upstreamDns: Type.Optional(Type.String({ description: "start: the resolver the tunnel asks, default 8.8.8.8." })),
			routes: Type.Optional(Type.Array(Type.String(), { description: "start: routes like 10.0.0.0/24; derived from the address when empty." })),
			allowedPackages: Type.Optional(Type.Array(Type.String(), { description: "start: only these apps go through the tunnel (allowlist)." })),
			disallowedPackages: Type.Optional(Type.Array(Type.String(), { description: "start: these apps bypass the tunnel." })),
			mtu: Type.Optional(Type.Number({ description: "start: MTU, default 1500." })),
			interceptDns: Type.Optional(Type.Boolean({ description: "start: answer blocked queries locally instead of only logging them, default true." })),
			blocklist: Type.Optional(Type.Array(Type.String(), { description: "start: domains to block (suffix match), added alongside action=blocklist." })),
			limit: Type.Optional(Type.Number({ description: "queries: entries, default 100." })),
			domains: Type.Optional(Type.Array(Type.String(), { description: "blocklist: the domains to block; replaces the current list (empty clears it)." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as {
					action?: string;
					sessionName?: string;
					dnsServers?: string[];
					upstreamDns?: string;
					routes?: string[];
					allowedPackages?: string[];
					disallowedPackages?: string[];
					mtu?: number;
					interceptDns?: boolean;
					blocklist?: string[];
					limit?: number;
					domains?: string[];
				};
				const action = typeof p.action === "string" ? p.action : "";
				switch (action) {
					case "status": {
						const data = await bridgeGet<
							ActionPayload & {
								running?: boolean;
								sessionName?: string;
								address?: string;
								proxyPort?: number;
								seenDomains?: number;
								blockedCount?: number;
								lastError?: string;
								dns?: { servers?: string[]; upstream?: string; intercept?: boolean; blocklist?: number };
								split?: { mode?: string; allowed?: string[]; disallowed?: string[] };
							}
						>("/app/vpn/status");
						const dns = data.dns ?? {};
						const split = data.split ?? {};
						const lines = [
							`本地 VPN：${data.running === true ? "运行中" : "未运行"}${typeof data.sessionName === "string" ? `（${data.sessionName}）` : ""}`,
							`地址 ${show(data.address)}；DNS ${Array.isArray(dns.servers) ? dns.servers.join("、") : "?"} → 上游 ${show(dns.upstream)}（拦截 ${dns.intercept === true ? "开" : "关"}，黑名单 ${show(dns.blocklist)} 条）`,
							`分应用 ${show(split.mode)}；已见域名 ${show(data.seenDomains)} 个；累计拦截 ${show(data.blockedCount)} 次；本地代理端口 ${show(data.proxyPort)}`,
						];
						if (typeof data.lastError === "string" && data.lastError.length > 0) lines.push(`上次错误：${data.lastError}`);
						if (typeof data.note === "string") lines.push("", data.note);
						return textResult(lines.join("\n"), data);
					}
					case "start": {
						const data = await bridgePost<
							ActionPayload & { running?: boolean; requested?: boolean; proxyPort?: number; lastError?: string }
						>("/app/vpn/start", {
							sessionName: p.sessionName,
							dnsServers: p.dnsServers,
							upstreamDns: p.upstreamDns,
							routes: p.routes,
							allowedPackages: p.allowedPackages,
							disallowedPackages: p.disallowedPackages,
							mtu: p.mtu,
							interceptDns: p.interceptDns,
							blocklist: p.blocklist,
						});
						requireActionOk("android_net", data);
						// 端点不说「已启动」：隧道是服务在另一次调用里建立的，「running」才是事实。
						const port = typeof data.proxyPort === "number" && data.proxyPort > 0 ? `，本地代理端口 ${data.proxyPort}` : "";
						const started = data.running === true
							? `本地 VPN 隧道已建立${port}。`
							: `已请求启动本地 VPN，但隧道还没建立（running=${show(data.running)}）${port}；用 action=status 确认，几秒后还是 false 就是系统没建立它。`;
						const error = typeof data.lastError === "string" && data.lastError.length > 0 ? `\n上次错误：${data.lastError}` : "";
						return textResult(`${started}${error}`, data);
					}
					case "stop": {
						const data = await bridgePost<ActionPayload & { wasRunning?: boolean }>("/app/vpn/stop", {});
						requireActionOk("android_net", data);
						return textResult(`已停止本地 VPN${data.wasRunning === false ? "（它本来就没在运行）" : ""}。`, data);
					}
					case "queries": {
						const data = await bridgePost<ActionPayload & Record<string, unknown>>("/app/vpn/queries", { limit: p.limit });
						const entries = listField(data);
						if (entries === null) return textResult(`DNS 查询：设备没有返回列表。\n${JSON.stringify(data)}`, data);
						return textResult(
							listText("DNS 查询", entries, (entry) => {
								const when = typeof entry.at === "number" ? new Date(entry.at).toISOString() : "?";
								return `- ${when} ${show(entry.name)} ${show(entry.type)} 来自 ${show(entry.client)}${entry.blocked === true ? "（已拦截）" : ""}`;
							}),
							data,
						);
					}
					case "blocklist": {
						if (!Array.isArray(p.domains)) {
							throw new Error("[BAD_PARAM] android_net 的 action=\"blocklist\" 需要 domains（域名数组；空数组表示清空）。\n提示：补上 domains 后重新调用 android_net。");
						}
						const domains = stringList(p.domains);
						const data = await bridgePost<ActionPayload & { count?: number }>("/app/vpn/blocklist", { domains });
						requireActionOk("android_net", data);
						return textResult(
							domains.length === 0
								? "DNS 黑名单已清空。"
								: `DNS 黑名单已设为 ${show(data.count)} 条：${domains.join("、")}。`,
							data,
						);
					}
					default:
						throw badParam("android_net", "action", ["status", "start", "stop", "queries", "blocklist"], p.action);
				}
			}),
	},

	// -------------------------------------------------------------- 投屏 ----
	//
	// MediaProjection（`PiScreenCapture`）：必须先由用户在 pi-android 的界面上点一次系统授权
	// 对话框，否则 `grab` 没有帧。`consent` 只回答「现在需不需要授权」—— 授权这一下模型和设
	// 备桥都代替不了用户；Android 14+ 还需要 mediaProjection 类型的前台服务。
	{
		name: "android_capture",
		capability: "basic",
		label: "投屏截图",
		description: "MediaProjection capture: action=status|consent|grab (one JPEG frame; system audio has its own limits).",
		promptSnippet: "Projection capture / audio",
		promptGuidelines: [
			"consent only reports whether authorization is still needed; the user must tap the system dialog in the pi-android UI.",
			"grab returns one image, or says the screen did not change (pass force=true to re-encode anyway).",
		],
		parameters: Type.Object({
			action: CaptureAction,
			quality: Type.Optional(Type.Number({ description: "grab: JPEG quality 20-100, default 70." })),
			force: Type.Optional(Type.Boolean({ description: "grab: re-encode even when the picture did not change, default false." })),
			waitMs: Type.Optional(Type.Number({ description: "grab: how long to wait for the first frame, default 800 ms." })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { action?: string; quality?: number; force?: boolean; waitMs?: number };
				const action = typeof p.action === "string" ? p.action : "";
				switch (action) {
					case "status": {
						const data = await bridgeGet<
							ActionPayload & {
								capturing?: boolean;
								width?: number;
								height?: number;
								frameCount?: number;
								fps?: number;
								audio?: { recording?: boolean; bytes?: number; file?: string };
							}
						>("/app/capture/status");
						const audio = data.audio ?? {};
						const lines = [
							`投屏：${data.capturing === true ? `进行中（${show(data.width)}×${show(data.height)}）` : "未授权或未开始（用 action=consent 授权）"}`,
							`帧 ${show(data.frameCount)} 个，${show(data.fps)} fps；音频录制 ${audio.recording === true ? `进行中（${show(audio.bytes)} 字节 → ${show(audio.file)}）` : "未开始"}`,
						];
						if (typeof data.note === "string") lines.push("", data.note);
						return textResult(lines.join("\n"), data);
					}
					case "consent": {
						const data = await bridgePost<ActionPayload & { capturing?: boolean }>("/app/capture/consent", {});
						requireActionOk("android_capture", data);
						return textResult(
							typeof data.note === "string"
								? data.note
								: "投屏授权只能由用户在 pi-android 界面上点一次系统对话框；模型与设备桥都代替不了这一下。",
							data,
						);
					}
					case "grab": {
						const data = await bridgePost<ActionPayload & { grabbed?: boolean; base64?: string; mimeType?: string; bytes?: number }>(
							"/app/capture/grab",
							{ quality: p.quality, force: p.force === true, waitMs: p.waitMs },
							60_000,
						);
						if (typeof data.base64 !== "string" || data.base64.length === 0) {
							const why = data.note ?? data.reason ?? "画面没有变化，或还没投屏授权";
							return textResult(
								`没有取到新的一帧：${why}。\n提示：先看 action=status 的 capturing；没有授权就先 action=consent。`,
								data,
							);
						}
						const size = typeof data.bytes === "number" ? `（${formatSize(data.bytes)}）` : "";
						return imageResult(data.base64, data.mimeType ?? "image/jpeg", `投屏截图${size}。`, data);
					}
					default:
						throw badParam("android_capture", "action", ["status", "consent", "grab"], p.action);
				}
			}),
	},
];

// ---------------------------------------------------------------------------
// environment documentation (the app's equivalent of pi's Termux AGENTS.md)
// ---------------------------------------------------------------------------

const SKILL_NAME = "pi-android-device";

/**
 * 技能正文：`SKILL.md` 只是路标（frontmatter + 一张索引表），细节按主题拆在
 * `references/*.md` 里；附件不进每轮提示词，进去的只有 frontmatter 的 name/description。
 *
 * 键是相对技能目录（`<agentDir>/skills/<SKILL_NAME>/`）的路径，因为文件由下面
 * `resources_discover` 写到磁盘上。
 */
const SKILL_FILES: Record<string, string> = {
	"SKILL.md": `---
name: ${SKILL_NAME}
description: "pi-android device tools (android_*): screen, apps, files, clipboard/notify/share, device shell, IME, device admin, automation, local VPN, screen capture. Read before acting on this phone."
---

# pi-android 设备环境

pi 跑在 proot Ubuntu 里；这台手机上的一切操作都走 android_* 工具。先读这张表，再按需打开附件。

| 主题 | 什么时候读 | 附件 |
|---|---|---|
| 屏幕与输入 | 读屏、点按、输入、滑动、按键、截图 | \`references/ui.md\` |
| 六个常驻工具 | 记不清 action 或参数 | \`references/tools.md\` |
| 设备 Shell | 要在设备上跑命令、看 uid 与写入边界 | \`references/shell.md\` |
| 路径 | /workspace、/sdcard、agentDir 各是什么 | \`references/paths.md\` |
| 常见坑 | 报 [DISABLED] / NOT_FOUND / 截断时 | \`references/pitfalls.md\` |
| ADB 身份 | 要 input/pm/am/settings，要 uid 2000 | \`references/elevate.md\` |
`,

	"references/ui.md": `# 屏幕与输入（android_ui）

| action | 做什么 | 关键参数 |
|---|---|---|
| \`dump\` | 读屏幕控件树（带编号） | filter、maxNodes、diff、waitForText/waitForId/waitMs |
| \`tap\` | 点按 | index，或 x/y，或 text/desc/resourceId；longPress |
| \`swipe\` | 坐标滑动，或滚动控件 | x1/y1/x2/y2/durationMs，或 direction+index/text/resourceId |
| \`input\` | 向输入框写文本 | text，index/desc/resourceId，submit |
| \`key\` | 全局动作 | key = back/home/recents/notifications/quicksettings/powermenu/lock/screenshot/split |
| \`keyevent\` | 注入原始按键（要 ADB 身份） | keys、repeat |
| \`screenshot\` | 截屏（Android 11+） | format、maxDimension、quality、region、marks |

规则：

1. 先 dump 再动作。编号只在最近一次 dump 里有效；能按 text/desc/resourceId 定位就别记编号。
2. 每个动作的返回都带「前台 A → B」和「屏幕已变化 / 没有变化」。说没有变化就是真没生效，别当成功报。
3. NOT_FOUND 是选择器没命中：换 text/desc/resourceId，或用 waitForText/waitForId 等它出现。
4. 画布/游戏/视频这类读到不内容的界面用 screenshot；小字用 region 裁，看不清就调大 maxDimension 或改 png。
5. keyevent 走 ADB 身份（uid 2000），见 references/elevate.md；key 走无障碍，不需要。
6. 密码框、银行类安全窗口系统禁止截屏，这是平台限制。
`,

	"references/tools.md": `# 六个常驻工具

下面这张表是合并后的六个入口；每个入口的 action 各对应一个注册着的细粒度工具。开关打开的那一组，它的工具全部声明给模型。

| 工具 | 覆盖 | action |
|---|---|---|
| \`android_status\` | 桥、能力组、前台、权限、Shell 后端与 uid、SAF 目录、工作区边界 | — |
| \`android_ui\` | 屏幕 | dump / tap / swipe / input / key / keyevent / screenshot |
| \`android_app\` | 应用 | list / launch / stop |
| \`android_io\` | 用户可见的输出 | clipboard / say / vibrate / share / open |
| \`android_fs\` | 文件 | list、read、write（授权目录 SAF）、download（公共 Download，要 op） |
| \`android_shell\` | 设备命令 | — |

- 能力组五组：basic（基础）/ accessibility（屏幕）/ ime（输入法）/ admin（设备管理员）/ shell。**五组默认全关**：一组都不开时只有 android_status 会注册，其余工具既不注册也不进提示词；要用哪组在「设置 → 设备能力」里开。
- [DISABLED] / [NO_PERMISSION] 会把原因写在正文里，原样转述给用户，别重试；开启位置是「设置 → 设备能力」。
- 危险动作会弹确认；用户拒绝就停。

## 另外六个工具

上面那张表是常驻的六个；下面这六个注册着但不声明，它们的参数 schema 平时不占提示词。

| 工具 | 能力组 | 覆盖 | action |
|---|---|---|---|
| \`android_ime\` | ime | 经 PI 输入法读/写当前聚焦的输入框 | insert / replace / delete / surround / submit / history / text |
| \`android_admin\` | admin | 设备管理员 / Device Owner 策略 | status / capabilities / grant / hidden / suspend / uninstall-blocked / install-ca / always-on-vpn / lock-task / update-policy / status-bar / keyguard / camera / reboot / wipe |
| \`android_notify\` | basic | 通知监听：读、回复、撤销、延后、事件流水 | status / recent / reply / dismiss / dismiss-all / snooze / events |
| \`android_automation\` | basic | 自动化规则（触发器 + 条件 + 动作） | status / list / add / remove / apply / history |
| \`android_net\` | basic | 本地 VPN：隧道、DNS 查询记录、黑名单 | status / start / stop / queries / blocklist |
| \`android_capture\` | basic | 投屏（MediaProjection）截图与系统音频 | status / consent / grab |

- 不可逆、或影响其他应用的动作：\`android_admin\` 的 reboot / wipe / grant / hidden / suspend / install-ca / lock-task。调用前先在正文里说明后果；被拒绝就停，别换条路再试。
- \`android_ime\` 只在 PI 是当前输入法时有内容可读；密码框读不到（连长度都不报）；surround 读的是光标前后的文本，不是改选区。
- \`android_capture\` 的 consent 只回答「现在需不需要授权」；真正那一下要用户在 pi-android 界面上点系统对话框，然后 grab。
- \`android_automation\` 的每个端点自己会先启动引擎（apply 幂等），add 之后不用再手动启动。
`,

	"references/shell.md": `# 设备 Shell（android_shell）

- 一条命令一次调用；\`$(...)\` 与反引号要用户开「放宽模式」。
- 命令头是白名单：getprop、dumpsys、logcat、pm、am、cmd、settings、wm、screencap、input、getevent、ls、cat、find、sed、awk、tar、curl…，未列出的默认拦截。
- 只能写工作区之内，工作区外会拒；回复里的 policy 行会复述这次实际生效的规则。
- 后端与身份写在回复的 [后端 …，uid=N]：默认是应用自身 uid；拿到 ADB 身份后是 uid 2000，input、pm、am、settings get、dumpsys 这些才真正可用（见 references/elevate.md）。
- 每条流 50 KB 上限；被截断时点名是 stdout 还是 stderr。
- 超时 / 没有退出码 / 非零退出都算失败，正文保留 pi 的原句 Command exited with code N。
`,

	"references/paths.md": `# 路径

| 路径 | 是什么 |
|---|---|
| \`/root/.pi/agent\` | pi agentDir：settings、extensions、skills、sessions、auth.json |
| \`/workspace\` | 工作区（应用私有、快，编译 / npm 用） |
| \`/sdcard\`、\`/storage/emulated/0\` | 共享存储（FUSE，慢，用户可见） |
| \`/tmp\` | 可写临时目录 |

- 设备策略只管 android_* 工具；工作区里的 bash/read/write 不受限。
- android_shell 以工作区为写入边界；guest 里工作区的挂载点有两个拼写，看 android_status 的「Shell 写入边界」行。
- 本技能的附件在 <agentDir>/skills/pi-android-device/ 下（上面表格里的路径都相对该目录）。
`,

	"references/pitfalls.md": `# 常见坑

1. [DISABLED] / [NO_PERMISSION]：那组能力关着或系统权限没给。原因在正文里，原样转述，别重试。
2. NOT_FOUND：控件没命中 —— 重新 dump，或换 text/desc/resourceId，或 waitForText/waitForId。
3. 编号失效：index 只属于最近一次 dump；跨步骤用选择器。
4. 动作「成功」但界面没变：返回里的「屏幕没有变化」就是判据，别当成功报。
5. 二进制读回：android_fs 以 base64 返回；要处理就在工作区里用 bash 解。
6. 剪贴板读取：Android 10+ 只有前台应用读得到；为空就是看不到，别反复试。
7. 截屏失败：密码框、银行类安全窗口系统禁止截屏。
8. 输出被截断：缩小范围重跑（Shell 每流 50 KB；工具结果 50 KB / 2000 行）。
9. 危险操作会确认：用户拒绝就停，别换条路再试。
`,

	"references/elevate.md": `# ADB identity (uid 2000)

input / pm / am / settings need uid 2000. Trigger: those fail as the app uid.
Alternative: the user starts Shizuku from Wireless debugging; android_status then reports uid 2000.

## 0. Client (once per rootfs)
    command -v adb || apt-get install -y adb

## 1. Pair (once)
Drive Settings with android_ui; read the screen with android_ui(action="dump").
  a. About phone -> tap "OS version" x7 (skip if Developer options exist)
  b. Developer options -> Wireless debugging -> ON (needs Wi-Fi)
  c. Tap "Pair device with pairing code" -> dump -> read the code and ip:port
  d. adb pair <ip>:<pairport> <code>

## 2. Connect (every session)
  a. Wireless debugging page -> dump -> read "IP address & port"
  b. adb connect <ip>:<connectport>
  c. adb shell id   ->   uid=2000(shell)

## 3. Fallback
Ask the user to open Wireless debugging and read out the code / port.
Ports change every time; Wi-Fi off = wireless debugging off.
`,
};

/** The system-prompt block appended for every turn. */
function environmentGuidance(): string {
	return [
		"",
		"## Android device environment (pi-android)",
		"",
		"pi runs in proot Ubuntu; tools are android_*.",
		"- `/workspace` fast, `/sdcard` slow; device policy covers only android_* tools, workspace bash unrestricted.",
		"- When input/pm/am/settings fail (they need the ADB identity, uid 2000), that identity is reachable by a local wireless-debug self-connect - read references/elevate.md and follow it. The first time it needs the user to open Wireless debugging and hand over the pairing code.",
		"- Dangerous device actions confirm; on refusal, stop.",
	].join("\n");
}

// ---------------------------------------------------------------------------
// 注册哪些工具
// ---------------------------------------------------------------------------

/**
 * 读一次 `/app/health`，得出“此刻哪些能力分组可用”，给注册循环做过滤。
 *
 * 判据是 `capabilities[].usable`，**不**再叠一次 `enabled`：App 侧的
 * `DeviceCapabilityStore.state()` 已经把三件事合进 `usable`
 * （`DeviceCapabilityStore.kt:151`：`usable = persisted && !sessionOff && denial == null`），
 * 所以 `usable` 严格强于“开关开着”。再与一次 `enabled` 只会是同一件事的第二份真相，
 * 而且会把“开关开着但系统侧还没就绪”的那一组（例如无障碍已启用但服务还没连上）当成可用 ——
 * 那正是这次要消灭的“注册了却调不动”。
 *
 * 读不到（桥没起来、token 文件读不到、超时）就返回 `null`，调用方按“一组都不注册、
 * 只留 `android_status`（和它展开的 `android_bridge_status`）”处理。桥不在时我们并不知道任何
 * 一组开着没有；这时注册全部
 * 工具等于把“注册了但调用失败”重新变成默认，而这次改动的全部意义就是不让不可用的东西进请求。
 * 对用户也不是死路：`android_status` 永远注册，它会说出“未连接”和下一步。
 */
async function usableCapabilities(): Promise<ReadonlySet<string> | null> {
	try {
		const health = await bridgeHealth();
		return new Set(health.capabilities.filter((capability) => capability.usable).map((capability) => capability.id));
	} catch {
		return null;
	}
}

/**
 * 这个工具此刻该不该注册。
 *
 * `capability === null` 的是 `android_status` / `android_bridge_status`：它是“为什么不可用”的
 * 唯一解释通道
 * （`health.capabilities[].reason` 只有它会读给模型听），成本约 100 字符，所以永远注册。
 * 其余工具只在它那一组 `usable` 时注册 —— 关掉的能力，它的 `description`、
 * `promptSnippet`、`promptGuidelines` 和参数 schema 一个字都不进请求。
 */
function shouldRegister(spec: DeviceToolSpec, usable: ReadonlySet<string> | null): boolean {
	if (spec.capability === null) return true;
	return usable !== null && usable.has(spec.capability);
}

// ---------------------------------------------------------------------------
// extension entry point
// ---------------------------------------------------------------------------

/**
 * 工厂是 `async` 的，这是 pi 支持的形状：`ExtensionFactory = (pi) => void | Promise<void>`
 * （pi 1.1.0 `core/extensions/types.d.ts:1494`），加载器 `await factory(load.api)` 之后才
 * `commit()`（`core/extensions/loader.js:514`），所以这里 `await` 出来的能力状态在
 * 任何工具被调用之前就已经定下。
 */
export default async function (pi: ExtensionAPI) {
	// 只注册“当前可用”的能力对应的工具。
	//
	// ## 为什么不热生效（以及这句事实写在哪两处）
	//
	// `pi.registerTool` 只在扩展工厂跑的时候登记一次，pi 没有“每次请求重算工具表”的钩子。
	// 这个工厂不是在进程启动时跑一次就完了：pi 每次 `createRuntime()` 都会重新加载扩展 ——
	// 引擎启动、新会话、fork / switch_session / resume、`ctx.reload()`（`/device-reload`）
	// 都走这条路（pi 1.1.0：`core/agent-session-runtime.js` 的 `newSession()` → `createRuntime()`
	// → `core/agent-session-services.js:69` 的 `resourceLoader.reload()` →
	// `core/resource-loader.js:355` 的 `clearExtensionCache()`）。
	//
	// 所以：能力开关的改动在**下一个新会话 / 重载扩展 / 引擎重启**之后生效，而不是当场生效。
	// App 侧 `ui/device/DeviceCapabilityScreen.kt` 在「能力授权」标题下写了同一句话，
	// 用户和模型看到的是同一个口径。
	const usable = await usableCapabilities();
	for (const tool of DEVICE_TOOLS) {
		if (!shouldRegister(tool, usable)) continue;
		pi.registerTool({
			name: tool.name,
			label: tool.label,
			description: tool.description,
			promptSnippet: tool.promptSnippet,
			promptGuidelines: tool.promptGuidelines,
			parameters: tool.parameters,
			namespace: DEVICE_NAMESPACE,
			...(tool.exposure === undefined ? {} : { exposure: tool.exposure }),
			async execute(
				_toolCallId: string,
				params: Static<TSchema>,
				_signal: AbortSignal | undefined,
				_onUpdate: AgentToolUpdateCallback<Record<string, unknown>> | undefined,
				ctx: ExtensionContext,
			): Promise<ToolOutcome> {
				return tool.run(params as Record<string, unknown>, ctx);
			},
		});
	}

	// Reload without restarting the engine.
	//
	// pi watches no files (its only `fs.watch` is the git-branch footer), so a newly
	// installed extension / skill / prompt template is invisible until something
	// re-scans. `ctx.reload()` is the official hook for that: `core/extensions/types.ts`
	// declares it on the command context, and `rpc-mode.ts` wires it to
	// `session.reload()`. Exposing it as a slash command is what lets the app do
	// `prompt("/device-reload")` after an install instead of killing and restarting
	// the whole engine process.
	//
	// The notify happens *before* the await on purpose: reload invalidates this runner
	// and the context that came with it (`agent-session.ts:889` — "This extension ctx
	// is stale after ... ctx.reload()"), so there is no fresh ctx to speak through
	// afterwards. A caller who needs proof that the scan finished can wait for the
	// `session_start` event with `reason: "reload"`, which pi emits at the end.
	pi.registerCommand("device-reload", {
		description: "Re-scan extensions, skills and prompts (no engine restart)",
		handler: async (_args, ctx) => {
			ctx.ui.notify("正在重新扫描扩展、技能与提示模板…", "info");
			await ctx.reload();
		},
	});

	// pi's in-session tree navigation, as a command the app can dispatch.
	//
	// ## Why a command is the only way in
	//
	// `navigateTree` moves the leaf **inside the current session file** — unlike `fork`,
	// which writes a new one (`core/agent-session.ts:3126-3127`). The RPC protocol has no
	// command for it (`modes/rpc/rpc-types.ts:20-74`: the whole tree surface is `get_tree`,
	// `get_entries`, `fork`, `clone`, `switch_session`, `new_session`), but `rpc-mode.ts`
	// **does** wire it for extensions (`commandContextActions.navigateTree`, `:329-335`),
	// and `ctx.navigateTree` is declared on the command context
	// (`core/extensions/types.ts:375-379`).
	//
	// The app dispatches it as a `prompt` whose text starts with `/`
	// (`agent-session.ts:1183-1184` → `_tryExecuteExtensionCommand`), the same shape
	// `device-reload` above uses. Two properties of that path matter to the caller and are
	// relied on by `PiSessionViewModel.navigateTo`:
	//
	//  - the handler is **awaited before the RPC response is emitted**
	//    (`agent-session.ts:1184-1188` calls `preflightResult(true)` after the await, and
	//    `rpc-mode.ts:401-406` outputs the success response from there), so a caller that
	//    awaits the response knows the navigation is over. There is no event otherwise: pi
	//    emits the *extension* event `session_tree` (`:3315-3321`) and RPC forwards
	//    `AgentSessionEvent` only (`rpc-mode.ts:356`);
	//  - an **unregistered** command name is not an error — `_tryExecuteExtensionCommand`
	//    returns false (`:1338`) and the text goes to the model as an ordinary user turn
	//    (`:1198-1218`). The app therefore checks `get_commands` before dispatching.
	//
	// ## The argument text
	//
	// pi splits the command line on the **first space only** (`:1333-1335`), so everything
	// after the command name arrives verbatim — including spaces, quotes and newlines. The
	// app sends one JSON object for that reason (`ui/chat/PiTreeNavigation.kt`
	// `navigateCommandArgs`), and `JSON.parse` is the whole parser here:
	// `customInstructions` is text a user typed, so a positional grammar would be ambiguous
	// the first time it contained a space.
	//
	// ## What it does not decide
	//
	// Nothing about the *question* ("Summarize branch?") — that is the caller's, and the app
	// asks it with pi's own three answers, honouring `branchSummary.skipPrompt`
	// (`interactive-mode.ts:5236-5263`). This handler maps exactly the four options
	// `navigateTree` takes, reports pi's outcome, and hands back the text pi says belongs in
	// the editor. The app applies the "only into an empty editor" half of that rule
	// (`:5313`), because `ctx.ui.getEditorText()` is hardwired to `""` in RPC mode
	// (`rpc-mode.ts:248-252`) and this handler cannot read the editor at all.
	pi.registerCommand("pi-android-navigate", {
		description: "Move the session leaf to a previous point, optionally summarizing the abandoned branch",
		handler: async (args, ctx) => {
			const raw = args.trim();
			if (raw.length === 0) {
				ctx.ui.notify("pi-android-navigate 需要一段 JSON 参数，但它拿到的是空字符串。", "error");
				return;
			}

			let parsed: {
				targetId?: unknown;
				summarize?: unknown;
				customInstructions?: unknown;
				replaceInstructions?: unknown;
				label?: unknown;
			};
			try {
				parsed = JSON.parse(raw);
			} catch (error) {
				ctx.ui.notify(
					`pi-android-navigate 的参数不是合法 JSON（${error instanceof Error ? error.message : String(error)}），所以没有跳转。`,
					"error",
				);
				return;
			}

			const targetId = typeof parsed.targetId === "string" ? parsed.targetId : "";
			if (targetId.length === 0) {
				ctx.ui.notify("pi-android-navigate 的参数里没有 targetId，所以没有跳转。", "error");
				return;
			}
			const summarize = parsed.summarize === true;
			const customInstructions =
				typeof parsed.customInstructions === "string" ? parsed.customInstructions : undefined;
			const replaceInstructions =
				typeof parsed.replaceInstructions === "boolean" ? parsed.replaceInstructions : undefined;
			const label = typeof parsed.label === "string" ? parsed.label : undefined;

			try {
				const result = await ctx.navigateTree(targetId, {
					summarize,
					customInstructions,
					replaceInstructions,
					label,
				});

				// **Only `cancelled` is readable here**, and that is worth stating precisely
				// because it is narrower than the feature:
				//
				//   - `AgentSession.navigateTree` returns
				//     `{ editorText?, cancelled, aborted?, summaryEntry? }`
				//     (`core/agent-session.ts:3139`) and pi's own TUI reads all of it
				//     (`interactive-mode.ts:5299-5315`, including the "only into an empty
				//     editor" test at `:5313`);
				//   - but the extension-facing wiring in RPC mode returns exactly
				//     `{ cancelled: result.cancelled }` and drops the rest
				//     (`modes/rpc/rpc-mode.ts:329-335`) — which is also all its declared type
				//     promises (`core/extensions/types.ts:375-379`).
				//
				// So out here an **aborted** summarization is indistinguishable from a
				// completed navigation, and the target message's text cannot be handed back
				// for editing. Both are pi's own omissions in RPC mode, not decisions of this
				// extension, and the app says so where it matters rather than inventing a
				// substitute.
				if (result.cancelled) {
					ctx.ui.notify(
						"这次跳转被一个扩展取消了（session_before_tree 返回 cancel），会话位置没有改变。",
						"warning",
					);
					return;
				}
				ctx.ui.notify("已跳到所选位置。", "info");
			} catch (error) {
				// `navigateTree` throws for the states it refuses rather than reporting them:
				// a turn still streaming (`:3140-3142`), a compaction in progress
				// (`:3143-3147`), a summary with no model (`:3157-3159`), an unknown entry id
				// (`:3162-3164`). Each message is pi's own sentence, quoted, because the app
				// cannot translate a fact it does not own. Catching here also keeps the
				// refusal off the `extension_error` channel, which the app shows as "an
				// extension crashed".
				ctx.ui.notify(
					`跳转没有完成，pi 说：${error instanceof Error ? error.message : String(error)}`,
					"error",
				);
			}
		},
	});

	// Tell the agent what environment it woke up in, every turn. The sentinel
	// guards against double-appending if this extension is loaded twice.
	pi.on("before_agent_start", async (event) => {
		// Sentinel must match the heading `environmentGuidance()` emits, or the
		// block is appended twice per turn.
		if (event.systemPrompt.includes("## Android device environment (pi-android)")) return undefined;
		return { systemPrompt: event.systemPrompt + environmentGuidance() };
	});

	// Contribute the environment skill: SKILL.md plus its references/. `resources_discover`
	// can only hand back *paths*, so every file is written on the way in. A group of
	// capabilities that is not usable contributes nothing — with no group open the android_*
	// tools are not registered either, and a skill describing tools the model does not have is
	// worse than no skill. A write failure is still silent by design: the system-prompt block
	// above carries the essentials.
	pi.on("resources_discover", async () => {
		try {
			const usable = await usableCapabilities();
			if (usable === null || usable.size === 0) return {};
			const { mkdir, writeFile } = await import("node:fs/promises");
			const { dirname, join } = await import("node:path");
			const home = process.env.HOME && process.env.HOME.trim().length > 0 ? process.env.HOME.trim() : "/root";
			const agentDir = process.env.PI_CODING_AGENT_DIR && process.env.PI_CODING_AGENT_DIR.trim().length > 0
				? process.env.PI_CODING_AGENT_DIR.trim()
				: join(home, ".pi", "agent");
			const skillDir = join(agentDir, "skills", SKILL_NAME);
			for (const [relativePath, content] of Object.entries(SKILL_FILES)) {
				const target = join(skillDir, relativePath);
				await mkdir(dirname(target), { recursive: true });
				await writeFile(target, content, "utf8");
			}
			return { skillPaths: [skillDir] };
		} catch {
			return {};
		}
	});

	// On session start the app shows nothing at the bottom of the screen — and in RPC
	// mode it does not even probe: device health is *persistent* state with a
	// permanent home, and 设置 → 设备能力 shows the bridge's status, the accessibility
	// service's state and every capability. Announcing it again at every session start
	// was a snackbar the user had to dismiss for information that had not changed, and
	// the probe itself was a loopback HTTP call in front of the session's first
	// message. A genuine failure still surfaces where it happens: the `android_*` tool
	// call reports it, with the address and the next step.
	//
	// TUI mode keeps the probe, because in a terminal there is no page to look at
	// instead — it paints the same information as a footer status.
	pi.on("session_start", async (_event, ctx) => {
		if (!ctx.hasUI || ctx.mode !== "tui") return;
		void bridgeHealth()
			.then((health) => {
				const usable = health.capabilities
					.filter((capability) => capability.usable)
					.map((capability) => capability.title);
				ctx.ui.setStatus(
					"pi-android-bridge",
					usable.length > 0 ? `设备桥：${usable.join("/")}` : "设备桥：无可用能力（到「设置 → 设备能力」开启）",
				);
			})
			.catch(() => {
				ctx.ui.setStatus("pi-android-bridge", "设备桥：未连接");
			});
	});
}
