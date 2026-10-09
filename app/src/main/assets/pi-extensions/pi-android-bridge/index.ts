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
 *  - six tools are *declared* to the model — `android_status`, `android_ui`, `android_app`,
 *    `android_io`, `android_fs`, `android_shell` — and every finer-grained tool they expand
 *    into stays registered with `exposure: "deferred"`, so `tool_search` can still reach it
 *    without its schema and description being paid for on every turn;
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
 * （`basic` / `accessibility` / `shell`，共 3 组）。原来的 `storage` 与 `sensors`
 * 两组已并入 `basic`，所以文件、位置、传感器、手电筒这些工具现在都挂在 `basic` 上。
 *
 * 这里是一份**抄写**，不是第二份真相：注册时拿它去 `/app/health` 的
 * `capabilities[].id` 里查状态，而那份 JSON 就是授权页和桥端点共用的
 * `DeviceCapabilityState`（`DeviceBridgeRouter.kt` 的 `/app/health` 分支）。App 侧加一组
 * 能力时这里会查不到、对应工具不会注册（而不是猜着注册），所以错法是保守的。
 */
type DeviceCapabilityId = "basic" | "storage" | "accessibility" | "sensors" | "shell";

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
	 * 进不进提示词。`direct` = 常驻：每次请求都声明它的 schema 与 description；
	 * `deferred` = 注册但不声明，`tool_search` 能找到并按需激活（pi 的 `ToolExposure`，
	 * 见 `docs/extensions.md` 的 “Tool exposure”）。
	 */
	exposure: "direct" | "deferred";
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
 * pi 的工具分组名。声明与不声明的工具都归这一组，模型的工具列表与 tool_search 按它成组。
 */
const DEVICE_NAMESPACE: ToolNamespace = {
	name: "pi-android",
	description: "pi-android device bridge (android_*)",
};

/**
 * 常驻工具把一次调用转交给它展开的细粒度工具。
 *
 * 六个常驻工具只多一个 `action`，其余参数原样传下去；被展开的工具仍然注册着
 * （`exposure: "deferred"`），所以端点和结果文案只有一份。
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

const DEVICE_TOOLS: DeviceToolSpec[] = [
	// ----------------------------------------------------------- 常驻工具 ----
	//
	// 这六个是声明给模型的全部（`exposure: "direct"`，默认值）。其余工具注册着但
	// `exposure: "deferred"`：`tool_search` 能找到并按需激活，平时不占提示词。
	{
		name: "android_status",
		capability: null,
		exposure: "direct",
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
		capability: null,
		exposure: "deferred",
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
		exposure: "direct",
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
		capability: "accessibility",
		exposure: "deferred",
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
		capability: "accessibility",
		exposure: "deferred",
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
		capability: "accessibility",
		exposure: "deferred",
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
		capability: "accessibility",
		exposure: "deferred",
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
		capability: "accessibility",
		exposure: "deferred",
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
		capability: "accessibility",
		exposure: "deferred",
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
		capability: "accessibility",
		exposure: "deferred",
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
		exposure: "direct",
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
		exposure: "deferred",
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
		exposure: "direct",
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
		capability: "basic",
		exposure: "deferred",
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
		capability: "basic",
		exposure: "deferred",
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
		capability: "basic",
		exposure: "deferred",
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
		capability: "basic",
		exposure: "deferred",
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
		capability: "basic",
		exposure: "deferred",
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
		exposure: "direct",
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
		capability: "basic",
		exposure: "deferred",
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
		capability: "basic",
		exposure: "deferred",
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
		capability: "basic",
		exposure: "deferred",
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
		exposure: "deferred",
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
		exposure: "deferred",
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
		exposure: "direct",
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
description: "pi-android device tools (android_*): screen, apps, files, clipboard/notify/share, device shell. Read before acting on this phone."
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

提示词里只有这六个。其余细粒度工具（\`android_tap\`、\`android_files\`、\`android_device_state\` 等）注册着但默认不声明，需要时用 \`tool_search\` 找到并激活。

| 工具 | 覆盖 | action |
|---|---|---|
| \`android_status\` | 桥、能力组、前台、权限、Shell 后端与 uid、SAF 目录、工作区边界 | — |
| \`android_ui\` | 屏幕 | dump / tap / swipe / input / key / keyevent / screenshot |
| \`android_app\` | 应用 | list / launch / stop |
| \`android_io\` | 用户可见的输出 | clipboard / say / vibrate / share / open |
| \`android_fs\` | 文件 | list、read、write（授权目录 SAF）、download（公共 Download，要 op） |
| \`android_shell\` | 设备命令 | — |

- 能力组三组：basic（基础，默认开）/ accessibility（屏幕，默认关）/ shell（默认关）。关着的那组，它的工具既不注册也不进提示词。
- [DISABLED] / [NO_PERMISSION] 会把原因写在正文里，原样转述给用户，别重试；开启位置是「设置 → 设备能力」。
- 危险动作会弹确认；用户拒绝就停。
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

	"references/elevate.md": `# 拿到 ADB 身份（uid 2000）：本机无线调试自连

设备能力里最强的一档不是某个开关，而是以「ADB 身份」（uid 2000）执行命令：input、pm install/uninstall、am、settings get、dumpsys、screencap 这些系统操作只有它放行。

两条路：

- **Shizuku**（应用自己的通道）：用户在「设置 → 设备能力 → Shell」里打开 Shizuku，并在 Shizuku 里用系统「无线调试」启动它（Android 11+，全程在手机上，不需要电脑）。之后 android_status 会显示「Shizuku：已就绪（ADB 身份，uid=2000）」。
- **本机无线调试自连**（下面这一步）：在 guest 里用 adb 客户端连 127.0.0.1 上的无线调试端口，直接得到一个 uid 2000 的 shell。

## 前置：guest 里要有 adb 客户端

android_shell 的白名单里没有 adb，所以配对与连接都在 guest 的 bash 里做，先装一次：

\`\`\`bash
apt-get install -y adb        # Ubuntu guest（Termux 里是 pkg install android-tools）
adb version
\`\`\`

装不上就走 Shizuku 那条路。

## 第一次：配对（只做一次）

1. 用户打开「设置 → 开发者选项 → 无线调试」（先连一次 Wi-Fi，端口才会出现）。
2. 点「使用配对码配对设备」：屏幕给出 6 位配对码，以及一个 IP:端口（配对端口）。先试页面上的地址；很多设备上 127.0.0.1:端口 也能自连。
3. 在 guest 里配对：

\`\`\`bash
adb pair 127.0.0.1:<配对端口>     # 提示时输入那 6 位配对码
\`\`\`

4. 配对成功后，回到无线调试页看**连接端口**（与配对端口不同，每次重开无线调试都会变）。

## 之后：重连（不用再配对）

\`\`\`bash
adb connect 127.0.0.1:<连接端口>
adb devices                      # 列表里出现 127.0.0.1:<连接端口>  device
adb shell id                     # uid=2000(shell) 就是 ADB 身份
\`\`\`

## 连上之后能跑什么

- \`input keyevent …\`、\`input text …\`、\`input tap/swipe\`：坐标级与原始按键输入（无障碍通道只能做那几个全局动作）。
- \`pm list packages / install / uninstall\`、\`am start / force-stop\`、\`settings get\`、\`dumpsys\`、\`screencap\`、\`logcat -d\`。
- android_keyevent 走的就是这条身份；android_status 的「Shizuku：已就绪（ADB 身份，uid=2000）」是它在应用侧对应的读数。
- 走 android_shell 时仍然过它的命令头白名单与工作区写入边界；adb 本身不在白名单里，所以自连从 guest 的 bash 发起。

## 注意

- 无线调试端口每次重开都会变；连不上先回设置页确认端口。
- 断开 Wi-Fi 会关掉无线调试（本机自连也要 Wi-Fi 开着）。
- 这是用户自己的设备、自己的身份，不需要 root；开不开无线调试由用户决定。
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
		"- ADB identity (uid 2000, what input/pm/am/settings need) is reachable by a local wireless-debug self-connect; read references/elevate.md when needed.",
		"- If only the six resident android_* tools show up, `tool_search` is off: add it (and optionally `codemode`) to `defaultTools` in `/root/.pi/agent/settings.json` and restart the engine — that list is a full whitelist, extend it, never overwrite it.",
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
			exposure: tool.exposure,
			namespace: DEVICE_NAMESPACE,
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
