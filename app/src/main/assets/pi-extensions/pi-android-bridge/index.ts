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
 *  - every tool truncates its own output with pi's own utilities (50KB / 2000
 *    lines) and says when it did;
 *  - string enums use `StringEnum` for Google API compatibility.
 */

import type { AgentToolUpdateCallback, ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
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

interface DeviceToolSpec {
	name: string;
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

const DEVICE_TOOLS: DeviceToolSpec[] = [
	// ---------------------------------------------------------------- 诊断 ----
	{
		name: "android_bridge_status",
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
		name: "android_ui_dump",
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
		label: "应用列表 / 启动",
		description: "List installed apps, or launch one by exact package name (find it with action=\"list\" first).",
		promptSnippet: "List or launch apps",
		parameters: Type.Object({
			action: StringEnum(["list", "launch"] as const, {
				description: "list = list apps, launch = start one.",
			}),
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
				throw badParam("android_app", "action", ["list", "launch"], p.action);
			}),
	},
	{
		name: "android_stop_app",
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
		name: "android_say",
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
		name: "android_download",
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
		label: "设备 Shell",
		description: "Whitelisted shell; unknown refused. Blocked: mount/umount, setenforce, setprop, settings put, mknod, dd, mkfs, pm clear|uninstall, su|sudo|magisk, /dev/block. Writes only in workspace; Shizuku = uid 2000, else app uid.",
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
				return textResult(truncateForModel(parts.join("\n"), "Shell 输出", "tail"), {
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
const SKILL_SENTINEL = `name: ${SKILL_NAME}`;

function skillMarkdown(): string {
	return `---
name: ${SKILL_NAME}
description: pi-android device tools: screen, apps, files, notifications, sensors.
---

# pi-android device environment

pi runs in a proot Ubuntu; the app provides the android_* tools.

## Paths

| Path | Meaning |
|---|---|
| \`/root/.pi/agent\` | pi agentDir: settings, extensions, skills, sessions, auth.json |
| \`/workspace\` | workspace (app-private, fast, for build/npm) |
| \`/sdcard\`, \`/storage/emulated/0\` | shared storage (FUSE, slow, visible) |
| \`/tmp\` | writable temp |

## Tools

- Screen: \`android_ui_dump\` → \`android_tap\` (index or text/desc/resourceId resolved on-device) → \`android_input\`;
  wait with waitForText/waitForId. Custom-drawn UI: \`android_screenshot\` (region, marks).
- Apps: \`android_app\`, \`android_stop_app\` (confirm). User: \`android_say\`, \`android_vibrate\`.
- Data: \`android_clipboard\`, \`android_download\`, \`android_files_list\`/\`android_files\` (SAF dirs the user authorized).
- State: \`android_device_state\`, \`android_torch\`.

## Rules

1. Capabilities are off by default; \`[DISABLED]\`/\`[NO_PERMISSION]\` carry a Chinese reason — relay it verbatim, say
   where to enable it (Settings → Device capabilities), don't retry.
2. Dangerous actions confirm ("allow and remember for this session" silences that class until the session ends);
   refusal = stop.
3. Indices are valid only within the latest \`android_ui_dump\`; prefer selectors. The reply's "foreground A → B" /
   "screen did not change" is the self-check.
4. Never pretend: report tool output as it is.
5. Policy covers only \`android_*\`; workspace bash/read/write are unrestricted. \`android_shell\` has a whitelist, a
   hard blocklist and writes only in the workspace.

## vs Termux

Use the \`android_*\` tools; upstream Termux commands don't apply.

## Device shell

- Default backend is the app uid; with **Shizuku** authorized it is ADB (uid=2000) and \`input\`, \`pm\`, \`am\`,
  \`settings get\`, \`dumpsys\` work; read it from \`android_bridge_status\`.
- Whitelist, blocklist and write boundary are echoed in the reply (\`policy\`).
- \`$(...)\`/backticks need the user's relaxed mode.
`;
}

/** The system-prompt block appended for every turn. */
function environmentGuidance(): string {
	return [
		"",
		"## Android device environment (pi-android)",
		"",
		"pi runs in proot Ubuntu; tools are android_*.",
		"- `/workspace` fast, `/sdcard` slow; device policy covers only android_* tools, workspace bash unrestricted.",
		"- Dangerous device actions confirm; on refusal, stop.",
	].join("\n");
}

// ---------------------------------------------------------------------------
// extension entry point
// ---------------------------------------------------------------------------

export default function (pi: ExtensionAPI) {
	for (const tool of DEVICE_TOOLS) {
		pi.registerTool({
			name: tool.name,
			label: tool.label,
			description: tool.description,
			promptSnippet: tool.promptSnippet,
			promptGuidelines: tool.promptGuidelines,
			parameters: tool.parameters,
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

	// Tell the agent what environment it woke up in, every turn. The sentinel
	// guards against double-appending if this extension is loaded twice.
	pi.on("before_agent_start", async (event) => {
		// Sentinel must match the heading `environmentGuidance()` emits, or the
		// block is appended twice per turn.
		if (event.systemPrompt.includes("## Android device environment (pi-android)")) return undefined;
		return { systemPrompt: event.systemPrompt + environmentGuidance() };
	});

	// Contribute the environment skill. `resources_discover` can only hand back
	// *paths*, so the file is written on the way in; a failure there is silent by
	// design because the system-prompt block above already carries the essentials.
	pi.on("resources_discover", async () => {
		try {
			const { mkdir, writeFile } = await import("node:fs/promises");
			const { join } = await import("node:path");
			const home = process.env.HOME && process.env.HOME.trim().length > 0 ? process.env.HOME.trim() : "/root";
			const agentDir = process.env.PI_CODING_AGENT_DIR && process.env.PI_CODING_AGENT_DIR.trim().length > 0
				? process.env.PI_CODING_AGENT_DIR.trim()
				: join(home, ".pi", "agent");
			const skillDir = join(agentDir, "skills", SKILL_NAME);
			await mkdir(skillDir, { recursive: true });
			await writeFile(join(skillDir, "SKILL.md"), skillMarkdown(), "utf8");
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
