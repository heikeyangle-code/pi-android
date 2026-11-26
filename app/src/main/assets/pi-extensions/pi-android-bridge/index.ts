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
 *  - the tool surface is **seven** tools (`android_status`, `android_screen`, `android_app`,
 *    `android_files`, `android_shell`, `android_comm`, `android_device`), each a closed
 *    `action` set instead of a family of aliases: one endpoint's description, snippet,
 *    guideline and schema are paid for once per request instead of once per alias
 *    (`docs/device-bridge-v2.md` §1-2);
 *  - an action reports its own effect: `tap` / `type` / `swipe` / `key` fetch `/app/ui/diff`
 *    immediately after acting and say whether the screen actually moved (§3);
 *  - with no capability group usable, **nothing** is registered — not even the status tool:
 *    the system prompt then carries no device text at all, and the app's
 *    「设置 → 设备能力」 page is what explains the state;
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
import { BridgeError, bridgeGet, bridgeHealth, bridgePost, loadInitialUsableCapabilities, type HealthPayload } from "./client";

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
 * parameters — `android_screen` already has a `text` (the string to type, or the
 * text to match) that would otherwise be read as a node selector by name.
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
 * A parameter mistake the schema cannot catch, in the bridge's own refusal shape
 * (`[CODE] 原因` + `提示：`), naming the parameter and its allowed values.
 *
 * 合并工具的每个参数都只能是可选（不同 action 的必填集不同），所以「缺了哪个参数」
 * 这条精度由各 action 自己补回来 —— 这个函数就是那句拒绝的形状。
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
 * （`basic` / `accessibility` / `shell`，共 3 组）。原来的 `storage` 与
 * `sensors` 两组已并入 `basic`，所以文件、位置、传感器、手电筒这些工具现在都挂在 `basic` 上；
 * 通知监听也归 `basic`，不另开开关。
 *
 * 这个联合类型里**只放 App 侧真的有的组**。多写一个成员不会让任何工具成功注册
 * （`shouldRegister` 拿它去 `usable.has` 里查，永远查不到），却会让「这组能力是存在的」
 * 这句话变成假的。
 *
 * 这里是一份**抄写**，不是第二份真相：注册时拿它去 `/app/health` 的
 * `capabilities[].id` 里查状态，而那份 JSON 就是授权页和桥端点共用的
 * `DeviceCapabilityState`（`DeviceBridgeRouter.kt` 的 `/app/health` 分支）。App 侧加一组
 * 能力时这里会查不到、对应工具不会注册（而不是猜着注册），所以错法是保守的。
 */
type DeviceCapabilityId = "basic" | "accessibility" | "shell";

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

/**
 * v2 的 7 个工具：`status` / `screen` / `app` / `files` / `shell` / `comm` / `device`。
 *
 * 合并不是为了少写几行，而是为了少付每轮的 token：`android_ui` / `ui_dump` / `tap` /
 * `input` / `key` / `keyevent` / `swipe` / `screenshot` 讲的是同一块屏幕，`io` / `fs` /
 * `files` / `files_list` / `download` 讲的是同一批文件 —— 每多一个工具就要多付一份
 * `description`、`promptSnippet`、`promptGuidelines` 和整份参数 schema。合并后一张
 * action 表只付一次（`docs/device-bridge-v2.md` §2）。
 *
 * 合并会让 schema 失去 `required` 的精度（不同 action 的必填集不同），所以每个 action
 * 自己用 `[BAD_PARAM]` 把它补回来；`tap` / `type` / `swipe` / `key` 之后还会立刻取一次
 * `/app/ui/diff`，把「界面到底变没变」写进同一份结果（同文件 §3）。
 */

const SCREEN_ACTIONS = ["look", "tap", "type", "swipe", "key", "wait", "idle", "shot", "steps"] as const;
const APP_ACTIONS = ["list", "launch", "stop"] as const;
const COMM_ACTIONS = [
	"clipboard",
	"notify",
	"toast",
	"tts",
	"vibrate",
	"share",
	"open",
	"inbox",
	"events",
	"reply",
	"dismiss",
	"snooze",
] as const;
const DEVICE_ACTIONS = ["info", "network", "volume", "battery", "torch", "location", "sensors", "sensor"] as const;

/** 全局动作键（无障碍 `performGlobalAction` 认得的那几个）。 */
const GLOBAL_KEYS = [
	"back",
	"home",
	"recents",
	"notifications",
	"quicksettings",
	"powermenu",
	"lock",
	"screenshot",
	"split",
];

/** `steps` 一次最多几步。超过就当场拒绝：一个调用连做十几步，出问题时没人知道错在第几步。 */
const STEPS_LIMIT = 8;

const ScreenAction = Type.Union(
	[
		Type.Literal("look"),
		Type.Literal("tap"),
		Type.Literal("type"),
		Type.Literal("swipe"),
		Type.Literal("key"),
		Type.Literal("wait"),
		Type.Literal("idle"),
		Type.Literal("shot"),
		Type.Literal("steps"),
	],
	{ description: "look|tap|type|swipe|key|wait|idle|shot|steps" },
);

const AppAction = Type.Union([Type.Literal("list"), Type.Literal("launch"), Type.Literal("stop")], {
	description: "list · launch · stop",
});

const CommAction = Type.Union(
	[
		Type.Literal("clipboard"),
		Type.Literal("notify"),
		Type.Literal("toast"),
		Type.Literal("tts"),
		Type.Literal("vibrate"),
		Type.Literal("share"),
		Type.Literal("open"),
		Type.Literal("inbox"),
		Type.Literal("events"),
		Type.Literal("reply"),
		Type.Literal("dismiss"),
		Type.Literal("snooze"),
	],
	{ description: "clipboard · notify · toast · tts · vibrate · share · open · inbox · events · reply · dismiss · snooze" },
);

const DeviceAction = Type.Union(
	[
		Type.Literal("info"),
		Type.Literal("network"),
		Type.Literal("volume"),
		Type.Literal("battery"),
		Type.Literal("torch"),
		Type.Literal("location"),
		Type.Literal("sensors"),
		Type.Literal("sensor"),
	],
	{ description: "info · network · volume · battery · torch · location · sensors · sensor" },
);

/** One `steps` entry: the same parameters as the action it names. */
const StepSpec = Type.Object({
	action: Type.Union(
		[
			Type.Literal("look"),
			Type.Literal("tap"),
			Type.Literal("type"),
			Type.Literal("swipe"),
			Type.Literal("key"),
			Type.Literal("wait"),
			Type.Literal("idle"),
		],
		{ description: "step action" },
	),
	text: Type.Optional(Type.String({ description: "match or input text" })),
	targetText: Type.Optional(Type.String({ description: "type: selector text of input field" })),
	desc: Type.Optional(Type.String({ description: "contentDesc part" })),
	index: Type.Optional(Type.Number({ description: "node index" })),
	resourceId: Type.Optional(Type.String({ description: "view id part" })),
	anchor: Type.Optional(Type.String({ description: "nearby anchor text" })),
	occurrence: Type.Optional(Type.Number({ description: "1-based match index" })),
	longPress: Type.Optional(Type.Boolean({ description: "tap: long press" })),
	x: Type.Optional(Type.Number({ description: "x px" })),
	y: Type.Optional(Type.Number({ description: "y px" })),
	x1: Type.Optional(Type.Number({ description: "swipe start x" })),
	y1: Type.Optional(Type.Number({ description: "swipe start y" })),
	x2: Type.Optional(Type.Number({ description: "swipe end x" })),
	y2: Type.Optional(Type.Number({ description: "swipe end y" })),
	durationMs: Type.Optional(Type.Number({ description: "swipe duration ms" })),
	key: Type.Optional(Type.String({ description: "key name" })),
	repeat: Type.Optional(Type.Number({ description: "key repeat count" })),
	direction: Type.Optional(StringEnum(["forward", "backward"] as const, { description: "scroll dir" })),
	submit: Type.Optional(Type.Boolean({ description: "submit after type" })),
	waitMs: Type.Optional(Type.Number({ description: "timeout ms" })),
	gone: Type.Optional(Type.Boolean({ description: "wait gone" })),
});

const DEVICE_NAMESPACE: ToolNamespace = {
	name: "pi-android",
	description: "pi-android device bridge (android_*)",
};

/** 缺字段时的统一说法：报缺哪个参数、下一步怎么办。 */
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

function requireNumber(tool: string, action: string, params: Record<string, unknown>, name: string): number {
	const value = params[name];
	if (typeof value !== "number" || !Number.isFinite(value)) {
		throw new Error(
			`[BAD_PARAM] ${tool} 的 action="${action}" 需要数字参数 ${name}。\n提示：补上 ${name} 后重新调用 ${tool}。`,
		);
	}
	return value;
}

/** 一个状态字段的显示值：缺字段显示 `?`（而不是 `undefined`）。 */
function show(value: unknown): string {
	if (value === undefined || value === null) return "?";
	if (typeof value === "object") return JSON.stringify(value);
	return String(value);
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
 * 设备侧的动作函数（`PiNotificationListener` 的几个）**不抛异常**，而是把失败写进返回值。
 * 「把 `ok:false` 渲染成已完成」是这一层最容易骗到模型的错：模型会据此认定操作生效，
 * 然后既不再试也不告诉用户。端点若已经把失败翻译成 denial，`bridgePost` 就先抛了。
 */
function requireActionOk(tool: string, data: ActionPayload): void {
	if (data.ok !== false) return;
	const reason = data.reason ?? `${tool} 被设备拒绝，但没有给出原因。`;
	const hint = data.hint ? `\n提示：${data.hint}` : "";
	throw new Error(`[ACTION_FAILED] ${reason}${hint}`);
}

/** 列表类端点的取值：把载荷里那个数组拿出来交给调用方渲染。 */
function listField(data: Record<string, unknown>): unknown[] | null {
	for (const value of Object.values(data)) {
		if (Array.isArray(value)) return value;
	}
	return null;
}

/** 列表为空时报「0 条」而不是空正文。 */
function listText(
	label: string,
	entries: unknown[],
	render: (entry: Record<string, unknown>, index: number) => string,
): string {
	if (entries.length === 0) return `${label}：0 条。`;
	const lines = entries.map((entry, index) => render((entry ?? {}) as Record<string, unknown>, index + 1));
	return `${label}：${entries.length} 条\n${lines.join("\n")}`;
}

// --------------------------------------------------------------- 动作反馈 ----

/** `/app/ui/diff` 里的元素引用（`ElementRef.brief()`：key / text / bounds / resourceId）。 */
interface DiffRef {
	key?: string;
	text?: string;
	resourceId?: string;
}

interface DiffData {
	/** true = 这台设备还没有可比对的上一屏（`diffSinceLast` 只在有基线时才 diff）。 */
	first?: boolean;
	same?: boolean;
	packageName?: string;
	packageChanged?: boolean;
	counts?: { added?: number; removed?: number; changed?: number };
	added?: Array<{ after?: DiffRef | null }>;
	removed?: Array<{ before?: DiffRef | null }>;
	changed?: Array<{ after?: DiffRef | null; fields?: string[] }>;
}

/**
 * diff 的基线只有 `/app/ui/elements` 会建。
 *
 * `DeviceUiAutomation.elements()` 才写 `lastElementsJson`（`DeviceUiAutomation.kt:1829`），
 * `dump()` 不写（它写的是 `lastSnapshot`，那是给 `index` 用的）。所以第一次动作前要先静默
 * 建一次基线，否则 `diff` 永远回 `first:true`，反馈层就只能说「还没得比」。
 *
 * 它**不碰** `lastSnapshot`，所以 `look` 拿到的编号在这之后依然有效。
 * 只建一次：失败也置位，不让每个动作都白付一次往返 —— 动作自己的报错比这里更具体。
 */
let diffBaselineReady = false;

/**
 * 注册那一刻的能力组，留给运行期回答「这个 action 现在能不能用」。
 *
 * 工具是按**组**注册的，而 v2 把几个组的分支合进了同一个工具：`android_app(action="stop")`
 * 要无障碍，可它挂在 `basic` 下。只开 basic 时工具在表里、动作却调不动 —— 与其让桥回一个
 * 端点级的 NO_PERMISSION，不如在这里就说清缺哪一组（`[DISABLED]` 是 v1 就定下的口径）。
 */
let usableGroups: ReadonlySet<string> = new Set();

async function ensureDiffBaseline(): Promise<void> {
	if (diffBaselineReady) return;
	diffBaselineReady = true;
	try {
		await bridgePost("/app/ui/elements", { maxNodes: 400 });
	} catch {
		// 建不起来就先欠着：反馈层会按 `first` 老实说「还没有上一屏」。
	}
}

function refLabel(ref: DiffRef | null | undefined): string {
	if (ref === null || ref === undefined) return "?";
	if (typeof ref.text === "string" && ref.text.length > 0) return ref.text;
	if (typeof ref.resourceId === "string" && ref.resourceId.length > 0) return ref.resourceId;
	if (typeof ref.key === "string" && ref.key.length > 0) return ref.key;
	return "?";
}

/** 一句话里最多点几个名字：再多就不如让模型自己 look。 */
function brief(items: string[], limit = 4): string {
	return items.length <= limit ? items.join("、") : `${items.slice(0, limit).join("、")}…`;
}

/**
 * 把 diff 载荷压成「变了没有 + 一句话」。
 *
 * 三种结论必须分得清，因为模型的下一个动作完全不同：
 *  - `changed:true`  —— 生效了，接着往下走；
 *  - `changed:false` ——「界面没变，可能没点中」，换编号/坐标或先 look；
 *  - `changed:null`  —— 还没有上一屏，不能说变了也不能说没变（编一个就是骗它重试）。
 */
function verdictOf(diff: DiffData): { text: string; details: Record<string, unknown> } {
	const added = (diff.added ?? []).map((entry) => refLabel(entry.after));
	const removed = (diff.removed ?? []).map((entry) => refLabel(entry.before));
	const updated = (diff.changed ?? []).map((entry) => refLabel(entry.after));
	const screen = typeof diff.packageName === "string" ? diff.packageName : undefined;
	if (diff.first === true) {
		return {
			text: "还没有上一屏可对比：先 action=look 看一眼，下一次动作就能比出变化。",
			details: { changed: null, screen, added, removed, updated },
		};
	}
	if (diff.same === true) {
		return {
			text: "界面没变，可能没点中：换一个编号/坐标再试，或先 action=look 看当前屏幕。",
			details: { changed: false, screen, added, removed, updated },
		};
	}
	const parts: string[] = [];
	if (added.length > 0) parts.push(`出现 ${added.length} 个：${brief(added)}`);
	if (removed.length > 0) parts.push(`消失 ${removed.length} 个：${brief(removed)}`);
	if (updated.length > 0) parts.push(`变化 ${updated.length} 个：${brief(updated)}`);
	if (parts.length === 0) parts.push(diff.packageChanged === true ? "前台应用换了" : "节点表有变化");
	return {
		text: `界面变了：${parts.join("；")}`,
		details: { changed: true, screen, added, removed, updated },
	};
}

/**
 * 动作 → 立刻取一次 diff → 一句话。
 *
 * diff 拿不到（无障碍在动作中途断开、端点拒绝）时**只报动作本身**：把反馈层的失败
 * 说成动作失败，是这一层唯一会骗到模型的错。
 */
async function withFeedback(
	line: string,
	details: Record<string, unknown> = {},
	notes: string[] = [],
): Promise<ToolOutcome> {
	let diff: DiffData | null = null;
	try {
		diff = await bridgePost<DiffData>("/app/ui/diff", {});
	} catch {
		return textResult([line, ...notes, "（这次取不到界面变化：diff 端点没有返回）"].join("\n"), details);
	}
	const verdict = verdictOf(diff);
	return textResult([line, ...notes, verdict.text].join("\n"), { ...details, ...verdict.details });
}

// ------------------------------------------------------------------ 屏幕 ----

interface ScreenParams {
	action?: string;
	filter?: string;
	diff?: boolean;
	maxNodes?: number;
	index?: number;
	x?: number;
	y?: number;
	text?: string;
	targetText?: string;
	desc?: string;
	resourceId?: string;
	anchor?: string;
	occurrence?: number;
	longPress?: boolean;
	direction?: string;
	x1?: number;
	y1?: number;
	x2?: number;
	y2?: number;
	durationMs?: number;
	submit?: boolean;
	key?: string;
	repeat?: number;
	waitMs?: number;
	gone?: boolean;
	format?: "jpeg" | "png";
	maxDimension?: number;
	region?: number[];
	marks?: boolean;
	steps?: Record<string, unknown>[];
}

function hasSelector(p: ScreenParams): boolean {
	return (
		(typeof p.text === "string" && p.text.length > 0) ||
		(typeof p.desc === "string" && p.desc.length > 0) ||
		(typeof p.resourceId === "string" && p.resourceId.length > 0)
	);
}

/** `key` 归一化：Kotlin 侧同样去掉 `-` / `_` 并小写（`DeviceUiAutomation.kt:1132`）。 */
function normalizedKey(key: string): string {
	return key.trim().toLowerCase().replace(/[-_]/g, "");
}

async function screenLook(p: ScreenParams): Promise<ToolOutcome> {
	const data = await bridgePost<DumpData>("/app/ui/dump", {
		filter: p.filter,
		maxNodes: p.maxNodes,
		diff: p.diff === true,
	});
	const diffTag = data.diff ? ` · diff(+${data.added ?? 0}/~${data.changed ?? 0}/-${data.removed ?? 0})` : "";
	const header =
		`[look] ${data.packageName} · ${data.screen.width}×${data.screen.height} · ` +
		`控件 ${data.nodeCount}/${data.totalNodeCount}${diffTag}${data.truncated ? "（已按上限截断）" : ""}`;
	const body = data.text.length > 0 ? data.text : "（没有可读控件：界面可能是空白，或无障碍服务被限制）";
	return textResult(`${header}\n\n${truncateForModel(body, "屏幕控件树")}`, {
		packageName: data.packageName,
		nodeCount: data.nodeCount,
		truncated: data.truncated,
	});
}

async function screenTap(p: ScreenParams): Promise<ToolOutcome> {
	const byIndex = typeof p.index === "number";
	const byPoint = typeof p.x === "number" && typeof p.y === "number";
	const useSmartSelect = (typeof p.anchor === "string" && p.anchor.length > 0) || typeof p.occurrence === "number";
	if (!byIndex && !byPoint && !hasSelector(p) && !useSmartSelect) {
		throw new Error(
			'[BAD_PARAM] android_screen 的 action="tap" 需要 index、x+y 或 text/desc/resourceId/anchor 之一。' +
				"\n提示：先 action=look 拿控件编号，编号比坐标稳。",
		);
	}
	await ensureDiffBaseline();
	let tapBody: Record<string, unknown>;
	if (useSmartSelect && !byIndex && !byPoint) {
		const resolved = await bridgePost<{
			found?: boolean;
			strategy?: string;
			element?: { bounds?: { left: number; top: number; right: number; bottom: number }; text?: string };
		}>("/app/ui/select", {
			text: p.text,
			desc: p.desc,
			resourceId: p.resourceId,
			anchorText: p.anchor,
			occurrence: p.occurrence,
			timeoutMs: p.waitMs ?? 1500,
		});
		const b = resolved.element?.bounds;
		if (!resolved.found || !b) {
			throw new Error(
				`[NOT_FOUND] 智能选择器未找到目标（text=${p.text ?? ""}, anchor=${p.anchor ?? ""}, occurrence=${p.occurrence ?? ""}）。\n提示：先 action=look 确认当前屏幕内容。`,
			);
		}
		tapBody = {
			x: Math.round((b.left + b.right) / 2),
			y: Math.round((b.top + b.bottom) / 2),
			longPress: p.longPress === true,
		};
	} else {
		tapBody = {
			index: p.index,
			x: p.x,
			y: p.y,
			longPress: p.longPress === true,
			...selectorBody({ text: p.text, desc: p.desc, resourceId: p.resourceId }),
		};
	}
	const data = await bridgePost<ActionData>("/app/ui/tap", tapBody);
	const where = data.x !== undefined
		? `(${data.x}, ${data.y})`
		: byIndex
			? `#${p.index}`
			: (p.text ?? p.resourceId ?? p.desc ?? p.anchor ?? "目标");
	return withFeedback(
		`[tap] 已${p.longPress === true ? "长按" : "点按"} ${where}（${data.mode ?? "?"}）`,
		{ mode: data.mode, index: p.index },
	);
}

async function screenType(p: ScreenParams, params: Record<string, unknown>): Promise<ToolOutcome> {
	requireText("android_screen", "type", params, ["text"]);
	await ensureDiffBaseline();
	const data = await bridgePost<{
		chars: number;
		submitted: boolean;
		mechanism?: string;
		clipboardNote?: string;
		submitHint?: string;
	}>("/app/ui/input", {
		text: p.text,
		index: p.index,
		submit: p.submit === true,
		...selectorBody({ text: p.targetText, desc: p.desc, resourceId: p.resourceId }),
	});
	const line =
		`[type] 已写入 ${data.chars} 个字符${data.submitted ? "，并已提交" : ""}` +
		`${data.mechanism === "clipboard_paste" ? "（改用剪贴板粘贴完成）" : ""}`;
	const notes = [data.clipboardNote, data.submitHint].filter(
		(note): note is string => typeof note === "string" && note.length > 0,
	);
	return withFeedback(line, { chars: data.chars, submitted: data.submitted, mechanism: data.mechanism }, notes);
}

async function screenSwipe(p: ScreenParams): Promise<ToolOutcome> {
	if (p.direction !== undefined) {
		if (!hasSelector(p) && typeof p.index !== "number") {
			throw new Error(
				'[BAD_PARAM] android_screen 的 action="swipe" 用 direction 滚动时还要指明滚哪个控件：' +
					"index、text 或 resourceId 至少给一个。" +
					"\n提示：先 action=look 找带 scrollable 的控件。",
			);
		}
		await ensureDiffBaseline();
		const data = await bridgePost<ActionData>("/app/ui/scroll", {
			direction: p.direction,
			index: p.index,
			...selectorBody({ text: p.text, resourceId: p.resourceId }),
		});
		return withFeedback(
			`[swipe] 已${p.direction === "forward" ? "向后" : "向前"}滚动（${data.mode ?? "?"}）`,
			{ mode: data.mode },
		);
	}
	if (p.x1 === undefined || p.y1 === undefined || p.x2 === undefined || p.y2 === undefined) {
		throw new Error(
			'[BAD_PARAM] android_screen 的 action="swipe" 需要 x1/y1/x2/y2，或者 direction + index/text/resourceId。' +
				"\n提示：滚动列表优先用 direction，坐标滑动留给画布类界面。",
		);
	}
	await ensureDiffBaseline();
	const data = await bridgePost<ActionData & { durationMs: number }>("/app/ui/swipe", {
		x1: p.x1,
		y1: p.y1,
		x2: p.x2,
		y2: p.y2,
		durationMs: p.durationMs,
	});
	return withFeedback(
		`[swipe] 已从 (${p.x1}, ${p.y1}) 划到 (${p.x2}, ${p.y2})，${data.durationMs}ms`,
		{ durationMs: data.durationMs },
	);
}

async function screenKey(p: ScreenParams, params: Record<string, unknown>): Promise<ToolOutcome> {
	requireText("android_screen", "key", params, ["key"]);
	const raw = typeof p.key === "string" ? p.key : "";
	await ensureDiffBaseline();
	if (GLOBAL_KEYS.includes(normalizedKey(raw))) {
		const data = await bridgePost<{ key: string; mode: string }>("/app/ui/key", { key: raw });
		return withFeedback(`[key] 已执行全局动作 ${data.key}（${data.mode}）`, { key: data.key, mode: data.mode });
	}
	const data = await bridgePost<{ keys: string[]; repeat: number; mode: string; backend: string }>(
		"/app/ui/keyevent",
		{ keys: raw, repeat: p.repeat },
	);
	return withFeedback(
		`[key] 已注入 ${data.keys.join(" ")}${data.repeat > 1 ? ` ×${data.repeat}` : ""}（${data.mode}，后端 ${data.backend}）`,
		{ keys: data.keys, mode: data.mode, backend: data.backend },
	);
}

async function screenWait(p: ScreenParams, _params: Record<string, unknown>): Promise<ToolOutcome> {
	if (!hasSelector(p)) {
		throw new Error(
			'[BAD_PARAM] android_screen 的 action="wait" 需要 text、desc 或 resourceId 之一。' +
				"\n提示：wait 只认选择器（text/desc/resourceId），不认编号或坐标。",
		);
	}
	const data = await bridgePost<{
		found?: boolean;
		gone?: boolean;
		selector?: string;
		elapsedMs?: number;
		polls?: number;
		node?: { text?: string; description?: string; resourceId?: string };
	}>("/app/ui/wait", {
		selector: {
			...(typeof p.text === "string" ? { text: p.text } : {}),
			...(typeof p.desc === "string" ? { desc: p.desc } : {}),
			...(typeof p.resourceId === "string" ? { resourceId: p.resourceId } : {}),
		},
		timeoutMs: p.waitMs ?? 5000,
		gone: p.gone === true,
	});
	const what = data.gone === true ? "已消失" : "已出现";
	const label = data.node ? ` ${data.node.text ?? data.node.description ?? data.node.resourceId ?? ""}`.trimEnd() : "";
	return textResult(
		`[wait] ${what}：${data.selector ?? "选择器"}（${data.elapsedMs ?? 0}ms，${data.polls ?? 0} 次检查）${label}`,
		{ found: data.found, gone: data.gone, elapsedMs: data.elapsedMs },
	);
}

async function screenIdle(p: ScreenParams): Promise<ToolOutcome> {
	const data = await bridgePost<{ idle?: boolean; elapsedMs?: number; polls?: number }>(
		"/app/ui/idle",
		{ timeoutMs: p.waitMs ?? 3000 },
	);
	return textResult(
		`[idle] ${data.idle === true ? "界面已稳定" : "等待超时"}（${data.elapsedMs ?? 0}ms）`,
		{ idle: data.idle, elapsedMs: data.elapsedMs },
	);
}

async function screenShot(p: ScreenParams): Promise<ToolOutcome> {
	const data = await bridgePost<ScreenshotData>(
		"/app/screenshot",
		{ format: p.format, maxDimension: p.maxDimension, region: p.region, marks: p.marks === true },
		60_000,
	);
	const scale =
		data.scale !== undefined && data.scale !== 1
			? `，缩放 ×${data.scale.toFixed(3)}（原图 ${data.sourceWidth}×${data.sourceHeight}）`
			: "";
	const notes = [
		`[shot] ${data.width}×${data.height}（${data.mimeType}，${formatSize(data.bytes)}）${scale}`,
	];
	if (data.marks) {
		notes.push(`已叠加 Set-of-Marks 编号框（${data.markedIndices ?? 0} 个可交互目标，可直接用 index 点击）。`);
	}
	if (data.region) notes.push(`裁剪：[${data.region.join(", ")}]`);
	return imageResult(data.base64, data.mimeType, notes.join("\n"), {
		width: data.width,
		height: data.height,
		bytes: data.bytes,
		scale: data.scale,
		region: data.region,
		marks: data.marks,
	});
}

/** 一步的结果压成一行：`[tap] 已点按 #3（action_click） → 界面变了：出现 1 个：发送`。 */
function oneLine(outcome: ToolOutcome): string {
	const first = outcome.content.find((part) => part.type === "text");
	const text = first !== undefined && first.type === "text" ? first.text : "";
	return text.split("\n").join(" → ");
}

async function screenSteps(p: ScreenParams, params: Record<string, unknown>): Promise<ToolOutcome> {
	const steps = Array.isArray(p.steps) ? p.steps : [];
	if (steps.length === 0) {
		throw new Error(
			'[BAD_PARAM] android_screen 的 action="steps" 需要 steps 数组（最多 8 步）。' +
				'\n提示：每步形如 {"action":"tap","text":"搜索框"}。',
		);
	}
	if (steps.length > STEPS_LIMIT) {
		throw new Error(
			`[BAD_PARAM] android_screen 的 action="steps" 最多 ${STEPS_LIMIT} 步，收到 ${steps.length} 步。` +
				"\n提示：拆成两次调用，每次不超过 8 步。",
		);
	}
	// 先把每一步的 action 校验完再动手：打错一个字就「跑了前三步然后停」是最难查的
	// 失败方式 —— 屏幕已经被动过，模型却不知道从哪一步开始重来。
	const allowed = ["look", "tap", "type", "swipe", "key", "wait", "idle"];
	for (const [index, raw] of steps.entries()) {
		const step = (raw ?? {}) as Record<string, unknown>;
		const name = typeof step.action === "string" ? step.action : "";
		if (!allowed.includes(name)) {
			throw new Error(
				`[BAD_PARAM] android_screen 的 steps[${index}].action 只能是 ${allowed.join("/")}，收到 ${JSON.stringify(step.action)}。`,
			);
		}
	}
	const lines: string[] = [];
	let last: Record<string, unknown> = {};
	for (const [index, raw] of steps.entries()) {
		const step = (raw ?? {}) as Record<string, unknown>;
		const name = typeof step.action === "string" ? step.action : "";
		try {
			const outcome = await screenAction({ ...step, action: name } as Record<string, unknown>);
			last = outcome.details;
			lines.push(`${index + 1}. ${oneLine(outcome)}`);
		} catch (error) {
			const reason = error instanceof Error ? error.message : String(error);
			lines.push(`${index + 1}. [失败] ${reason}`);
			return textResult(
				`[steps] 第 ${index + 1}/${steps.length} 步失败，已停下：\n${lines.join("\n")}`,
				{ failedAt: index + 1, steps: steps.length },
			);
		}
	}
	return textResult(`[steps] ${steps.length} 步全部完成：\n${lines.join("\n")}`, {
		steps: steps.length,
		...last,
	});
}

async function screenAction(params: Record<string, unknown>): Promise<ToolOutcome> {
	return guarded(async () => {
		const p = params as ScreenParams;
		const action = typeof p.action === "string" ? p.action : "";
		switch (action) {
			case "look":
				return screenLook(p);
			case "tap":
				return screenTap(p);
			case "type":
				return screenType(p, params);
			case "swipe":
				return screenSwipe(p);
			case "key":
				return screenKey(p, params);
			case "wait":
				return screenWait(p, params);
			case "idle":
				return screenIdle(p);
			case "shot":
				return screenShot(p);
			case "steps":
				return screenSteps(p, params);
			default:
				throw badParam("android_screen", "action", [...SCREEN_ACTIONS], p.action);
		}
	});
}

// -------------------------------------------------------------------- 应用 ----

interface AppParams {
	action?: string;
	q?: string;
	includeSystem?: boolean;
	limit?: number;
	package?: string;
}

async function appAction(params: Record<string, unknown>): Promise<ToolOutcome> {
	return guarded(async () => {
		const p = params as AppParams;
		const action = typeof p.action === "string" ? p.action : "";
		if (action === "list") {
			const data = await bridgeGet<AppsData>("/app/apps", {
				q: p.q,
				includeSystem: p.includeSystem,
				limit: p.limit,
			});
			const lines = data.apps.map(
				(app) => `${app.label}  ${app.packageName}${app.system ? "  [系统]" : ""}${app.launchable ? "" : "  [不可启动]"}`,
			);
			if (lines.length === 0) lines.push("（没有匹配的应用）");
			const body = `${lines.join("\n")}\n\n共 ${data.count} 条${data.truncated ? "（已截断）" : ""}`;
			const note = data.note ? `\n\n说明：${data.note}` : "";
			return textResult(`[list] ${truncateForModel(body, "应用列表")}${note}`, { count: data.count });
		}
		if (action === "launch") {
			requireText("android_app", "launch", params, ["package"]);
			const data = await bridgePost<{
				packageName: string;
				component: string;
				mode?: string;
				foreground?: string;
				verified?: boolean;
				note?: string;
			}>("/app/apps/launch", { package: p.package });
			// 桥自己会复核前台包名：被 Android 10+ 丢掉的那次启动会以错误回来，
			// 所以能走到这里就等于真的启动了。
			const how = data.verified
				? `，前台已切到 ${data.foreground ?? data.packageName}（${data.mode}）`
				: "（未能验证前台，以 android_status 的「前台」行为准）";
			return textResult(
				`[launch] 已启动 ${data.packageName}${data.component ? `（${data.component}）` : ""}${how}` +
					`${data.note ? `\n${data.note}` : ""}`,
				{ packageName: data.packageName, foreground: data.foreground },
			);
		}
		if (action === "stop") {
			requireText("android_app", "stop", params, ["package"]);
			if (!usableGroups.has("accessibility")) {
				throw new Error(
					'[DISABLED] android_app 的 action="stop" 属于无障碍组，它现在没有开：' +
						"结束应用走的是无障碍通道，不是基础组。" +
						"\n提示：让用户在「设置 → 设备能力」打开「屏幕自动化」并启用无障碍服务后重试；不要换工具绕过。",
				);
			}
			const data = await bridgePost<{ packageName: string; mode: string; note?: string }>("/app/apps/stop", {
				package: p.package,
			});
			return textResult(
				`[stop] 已请求结束后台进程：${data.packageName}（${data.mode}）${data.note ? `\n${data.note}` : ""}`,
				{ packageName: data.packageName, mode: data.mode },
			);
		}
		throw badParam("android_app", "action", [...APP_ACTIONS], p.action);
	});
}

// ------------------------------------------------------------------ Shell ----

interface ShellParams {
	command?: string;
	timeoutMs?: number;
}

async function shellRun(params: Record<string, unknown>): Promise<ToolOutcome> {
	return guarded(async () => {
		const p = params as ShellParams;
		requireText("android_shell", "run", params, ["command"]);
		const command = typeof p.command === "string" ? p.command : "";
		const data = await bridgePost<ShellData>("/app/shell", { command, timeoutMs: p.timeoutMs }, 90_000);
		const parts: string[] = [`$ ${command}`];
		if (data.stdout.trim().length > 0) parts.push(data.stdout.trimEnd());
		if (data.stderr.trim().length > 0) parts.push(`[stderr]\n${data.stderr.trimEnd()}`);
		parts.push(`[退出码 ${data.exitCode}${data.timedOut ? "，超时被杀" : ""}]`);
		const clipped = [data.stdoutTruncated ? "stdout" : "", data.stderrTruncated ? "stderr" : ""].filter(
			(name) => name.length > 0,
		);
		if (clipped.length > 0) {
			parts.push(`[输出被截断：${clipped.join(" 与 ")} 只保留了前 50 KB；要看全部请缩小输出]`);
		} else if (data.truncated) {
			parts.push("[输出被截断：stdout 或 stderr 只保留了前 50 KB；要看全部请缩小输出]");
		}
		parts.push(`[后端 ${data.backendLabel}，uid=${data.uid}]`);
		parts.push(data.note);
		const text = truncateForModel(parts.join("\n"), "Shell 输出", "tail");
		const failure = shellFailureStatus(data);
		if (failure !== null) throw new Error(`${text}\n\n${failure}`);
		return textResult(text, { exitCode: data.exitCode, backend: data.backend, uid: data.uid });
	});
}

// -------------------------------------------------------------- 对外动作 ----

interface CommParams {
	action?: string;
	text?: string;
	title?: string;
	key?: string;
	url?: string;
	subject?: string;
	ms?: number;
	language?: string;
	rate?: number;
	long?: boolean;
	limit?: number;
	since?: number;
}

async function commAction(params: Record<string, unknown>): Promise<ToolOutcome> {
	return guarded(async () => {
		const p = params as CommParams;
		const action = typeof p.action === "string" ? p.action : "";
		switch (action) {
			case "clipboard": {
				if (typeof p.text === "string") {
					const data = await bridgePost<{ chars: number }>("/app/clipboard", { text: p.text });
					return textResult(`已写入剪贴板（${data.chars} 字符）`, { chars: data.chars });
				}
				const data = await bridgeGet<ClipboardData>("/app/clipboard");
				if (data.empty) {
					return textResult(`剪贴板为空或不可读。${data.note ? `\n${data.note}` : ""}`, { empty: true });
				}
				return textResult(
					`剪贴板内容（${data.text.length} 字符）：\n\n${truncateForModel(data.text, "剪贴板")}`,
					{ length: data.text.length },
				);
			}
			case "notify": {
				requireText("android_comm", "notify", params, ["text"]);
				const data = await bridgePost<{ id: number; channel: string }>("/app/notify", {
					text: p.text,
					title: p.title,
				});
				return textResult(`已发送通知（id=${data.id}）`, { id: data.id, channel: data.channel });
			}
			case "toast": {
				requireText("android_comm", "toast", params, ["text"]);
				const data = await bridgePost<{ duration: string }>("/app/toast", { text: p.text, long: p.long === true });
				return textResult(`已显示提示（${data.duration}）`, { duration: data.duration });
			}
			case "tts": {
				requireText("android_comm", "tts", params, ["text"]);
				const data = await bridgePost<{ spoken: boolean; finished: boolean; language: string; chars: number }>(
					"/app/tts",
					{ text: p.text, language: p.language, rate: p.rate },
					60_000,
				);
				return textResult(
					`已朗读 ${data.chars} 个字符（${data.language}）${data.finished ? "，已读完" : "，仍在播放"}`,
					{ chars: data.chars, language: data.language },
				);
			}
			case "vibrate": {
				const data = await bridgePost<{ ok: boolean }>("/app/vibrate", { ms: p.ms });
				return textResult(`已触发震动${typeof p.ms === "number" ? `（${p.ms}ms）` : ""}`, { ok: data.ok });
			}
			case "share": {
				const data = await bridgePost<{ ok: boolean; action: string }>("/app/share", {
					text: p.text,
					url: p.url,
					subject: p.subject,
				});
				return textResult("已打开系统分享面板，请让用户选目标应用。", { ok: data.ok, action: data.action });
			}
			case "open": {
				requireText("android_comm", "open", params, ["url"]);
				const data = await bridgePost<{ ok: boolean; action: string }>("/app/open", { url: p.url });
				return textResult(`已请求打开 ${p.url}`, { ok: data.ok, action: data.action });
			}
			case "inbox": {
				const status = await bridgeGet<
					ActionPayload & {
						connected?: boolean;
						enabledInSettings?: boolean;
						state?: string;
						activeCount?: number;
						replyableCount?: number;
						eventCount?: number;
					}
				>("/app/notify/status");
				const state = status.connected === true
					? "已连接"
					: status.enabledInSettings === true
						? "已授权，系统还没绑定服务（稍等约 1 秒）"
						: "没有通知使用权";
				const head = [
					`通知监听：${state}（${show(status.state)}）`,
					`当前 ${show(status.activeCount)} 条，其中可回复 ${show(status.replyableCount)} 条`,
				];
				if (status.connected !== true) {
					if (typeof status.note === "string") head.push("", status.note);
					return textResult(head.join("\n"), status);
				}
				const recent = await bridgePost<ActionPayload & Record<string, unknown>>("/app/notify/recent", {
					limit: p.limit,
				});
				const entries = listField(recent);
				if (entries === null) return textResult(head.join("\n"), recent);
				return textResult(
					`${head.join("\n")}\n\n${listText("最近通知", entries, (entry) => {
						const rawText = typeof entry.text === "string" ? entry.text : "";
						const rawBig = typeof entry.bigText === "string" ? entry.bigText : "";
						const bestBody = rawBig.length > rawText.length ? rawBig : rawText;
						const body = bestBody.length > 0 ? `：${bestBody}` : "";
						const flags = [
							entry.replyable === true ? "可回复" : "",
							entry.isOngoing === true ? "常驻" : "",
							entry.isClearable === false ? "不可撤销" : "",
							entry.removed === true ? "已移除" : "",
						].filter((flag) => flag.length > 0);
						return `- [${show(entry.key)}] ${show(entry.packageName)}｜${show(entry.title)}${body}` +
							`${flags.length > 0 ? `（${flags.join("、")}）` : ""}`;
					})}`,
					recent,
				);
			}
			case "events": {
				const data = await bridgeGet<
					ActionPayload & {
						count?: number;
						cursor?: number;
						events?: Array<Record<string, unknown>>;
					}
				>("/app/notify/events", {
					since: p.since,
					limit: p.limit,
				});
				const entries = Array.isArray(data.events) ? data.events : [];
				const body = listText("通知事件流", entries, (ev) => {
					const rawText = typeof ev.text === "string" ? ev.text : "";
					const rawBig = typeof ev.bigText === "string" ? ev.bigText : "";
					const best = rawBig.length > rawText.length ? rawBig : rawText;
					const msg = best.length > 0 ? `：${best}` : "";
					const pkg = typeof ev.packageName === "string" && ev.packageName.length > 0 ? ` ${ev.packageName}` : "";
					const title = typeof ev.title === "string" && ev.title.length > 0 ? `｜${ev.title}` : "";
					return `- #${show(ev.seq)} [${show(ev.type)}]${pkg}${title}${msg}${ev.detail ? `（${ev.detail}）` : ""}`;
				});
				return textResult(
					truncateForModel(`${body}\n\n最新游标 cursor=${show(data.cursor)}（下次传 since=${show(data.cursor)} 只取增量）`, "通知事件"),
					{ count: entries.length, cursor: data.cursor },
				);
			}
			case "reply": {
				requireText("android_comm", "reply", params, ["key", "text"]);
				const data = await bridgePost<ActionPayload & { chars?: number }>("/app/notify/reply", {
					key: p.key,
					text: p.text,
				});
				requireActionOk("android_comm", data);
				return textResult(`已回复通知「${p.key}」（${data.chars ?? 0} 字符）。`, data);
			}
			case "dismiss": {
				if (typeof p.key === "string" && p.key.trim().length > 0) {
					const data = await bridgePost<ActionPayload>("/app/notify/dismiss", { key: p.key });
					requireActionOk("android_comm", data);
					return textResult(`已撤销通知「${p.key}」。`, data);
				}
				const data = await bridgePost<ActionPayload>("/app/notify/dismiss-all", {});
				requireActionOk("android_comm", data);
				return textResult("已撤销所有可撤销的通知。", data);
			}
			case "snooze": {
				requireText("android_comm", "snooze", params, ["key"]);
				const ms = requireNumber("android_comm", "snooze", params, "ms");
				const data = await bridgePost<ActionPayload & { durationMs?: number }>("/app/notify/snooze", {
					key: p.key,
					ms,
				});
				requireActionOk("android_comm", data);
				return textResult(`已把通知「${p.key}」延后 ${data.durationMs ?? ms} 毫秒。`, data);
			}
			default:
				throw badParam("android_comm", "action", [...COMM_ACTIONS], p.action);
		}
	});
}

// -------------------------------------------------------------- 设备读数 ----

interface DeviceParams {
	action?: string;
	on?: boolean;
	typeName?: string;
	type?: number;
	timeoutMs?: number;
	stream?: string;
	level?: number;
	percent?: number;
}

async function deviceAction(params: Record<string, unknown>): Promise<ToolOutcome> {
	return guarded(async () => {
		const p = params as DeviceParams;
		const action = typeof p.action === "string" ? p.action : "";
		switch (action) {
			case "info": {
				const data = await bridgeGet<{
					manufacturer: string;
					brand: string;
					model: string;
					device: string;
					androidRelease: string;
					sdkInt: number;
					abis: string[];
					screenWidthPx: number;
					screenHeightPx: number;
					densityDpi: number;
					ramAvailableBytes: number;
					ramTotalBytes: number;
					lowMemory: boolean;
					storageAvailableBytes: number;
					storageTotalBytes: number;
					locale: string;
					timezone: string;
				}>("/app/device/info");
				const lines = [
					`机型：${data.manufacturer} ${data.model}（${data.brand}/${data.device}）`,
					`系统：Android ${data.androidRelease}（SDK ${data.sdkInt}）· ABI ${(data.abis ?? []).join(", ")}`,
					`屏幕：${data.screenWidthPx}×${data.screenHeightPx}（${data.densityDpi} dpi）`,
					`内存：可用 ${formatSize(data.ramAvailableBytes)} / 共 ${formatSize(data.ramTotalBytes)}${data.lowMemory ? "（低内存）" : ""}`,
					`存储：可用 ${formatSize(data.storageAvailableBytes)} / 共 ${formatSize(data.storageTotalBytes)}`,
					`区域：${data.locale} · 时区 ${data.timezone}`,
				];
				return textResult(lines.join("\n"), data as unknown as Record<string, unknown>);
			}
			case "network": {
				const data = await bridgeGet<{
					connected: boolean;
					validated: boolean;
					metered: boolean;
					transports: string[];
					localIpv4: string | null;
				}>("/app/network");
				const transports = Array.isArray(data.transports) && data.transports.length > 0
					? data.transports.join(" + ")
					: "无";
				const status = data.connected ? (data.validated ? "已联网（已验证）" : "已连接（未验证公网）") : "离线";
				const lines = [
					`网络：${status} · 通道 ${transports} · ${data.metered ? "计费网络" : "非计费"}`,
					`本机 IPv4：${data.localIpv4 ?? "无"}`,
				];
				return textResult(lines.join("\n"), data as unknown as Record<string, unknown>);
			}
			case "volume": {
				const hasWrite = typeof p.level === "number" || typeof p.percent === "number";
				if (hasWrite) {
					const data = await bridgePost<{
						stream: string;
						level: number;
						max: number;
						percent: number;
						ringerMode: string;
						updated?: boolean;
					}>("/app/volume", {
						stream: p.stream,
						level: p.level,
						percent: p.percent,
					});
					return textResult(
						`已将 ${data.stream} 音量调至 ${data.level}/${data.max}（${data.percent}%，响铃模式 ${data.ringerMode}）`,
						data as unknown as Record<string, unknown>,
					);
				}
				const data = await bridgeGet<{
					ringerMode: string;
					stream?: string;
					level?: number;
					max?: number;
					percent?: number;
					streams?: Array<{ stream: string; level: number; max: number; percent: number }>;
				}>("/app/volume", { stream: p.stream });
				if (Array.isArray(data.streams)) {
					const lines = data.streams.map((s) => `- ${s.stream}: ${s.level}/${s.max} (${s.percent}%)`);
					return textResult(
						`响铃模式：${data.ringerMode}\n${lines.join("\n")}`,
						data as unknown as Record<string, unknown>,
					);
				}
				return textResult(
					`${data.stream ?? p.stream ?? "volume"}：${data.level}/${data.max}（${data.percent}%，响铃模式 ${data.ringerMode}）`,
					data as unknown as Record<string, unknown>,
				);
			}
			case "battery": {
				const data = await bridgeGet<{ percent: number; status: string; plugged: boolean; temperatureC: number }>(
					"/app/battery",
				);
				return textResult(
					`电量 ${data.percent}% · ${data.status} · ${data.plugged ? "已接电源" : "未接电源"} · ${data.temperatureC}°C`,
					{ percent: data.percent, plugged: data.plugged },
				);
			}
			case "torch": {
				if (typeof p.on !== "boolean") {
					throw new Error(
						'[BAD_PARAM] android_device 的 action="torch" 需要 on（true 开 / false 关）。' +
							"\n提示：on 不给默认值 —— 猜错就是替用户做了相反的决定。",
					);
				}
				const data = await bridgePost<{ on: boolean; cameraId: string }>("/app/torch", { on: p.on });
				return textResult(`手电筒已${data.on ? "打开" : "关闭"}（camera ${data.cameraId}）`, { on: data.on });
			}
			case "location": {
				const data = await bridgeGet<{
					latitude: number;
					longitude: number;
					accuracyMeters: number;
					provider: string;
					ageSeconds: number;
					stale: boolean;
				}>("/app/location");
				const freshness = data.stale
					? `（数据已有 ${data.ageSeconds} 秒，可能已过时）`
					: `（${data.ageSeconds} 秒前）`;
				return textResult(
					`位置：${data.latitude}, ${data.longitude}（约 ${Math.round(data.accuracyMeters)} 米，来自 ${data.provider}）${freshness}`,
					data as unknown as Record<string, unknown>,
				);
			}
			case "sensors": {
				const data = await bridgeGet<SensorsData>("/app/sensors");
				const lines = data.sensors.map(
					(sensor) => `${sensor.typeName}（type=${sensor.type}）  ${sensor.name}  ${sensor.vendor}`,
				);
				return textResult(
					truncateForModel(`共 ${data.count} 个传感器：\n\n${lines.join("\n")}`, "传感器列表"),
					{ count: data.count },
				);
			}
			case "sensor": {
				const data = await bridgeGet<SensorSampleData>("/app/sensor", {
					typeName: p.typeName,
					type: p.type,
					timeoutMs: p.timeoutMs,
				});
				return textResult(
					`${data.name}（${data.typeName}）：${data.values.join(", ")}${data.units ? ` ${data.units}` : ""}`,
					data as unknown as Record<string, unknown>,
				);
			}
			default:
				throw badParam("android_device", "action", [...DEVICE_ACTIONS], p.action);
		}
	});
}

// -------------------------------------------------------------------- 状态 ----

async function statusBridge(): Promise<ToolOutcome> {
	return guarded(async () => {
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
		lines.push(
			health.foreground
				? `前台：${health.foreground.packageName ?? "未知"}${health.foreground.class ? `/${health.foreground.class}` : ""}`
				: "前台：未知（无障碍服务未连接，读不到前台应用）",
		);
		if (health.audit?.lastUnpaired) lines.push(`上次执行异常终止：${health.audit.lastUnpaired}`);
		lines.push(
			`无障碍：${
				health.accessibilityState === "connected"
					? "运行中"
					: health.accessibilityState === "enabled_not_connected"
						? "已启用，正在连接（稍等重试）"
						: health.accessibilityRunning
							? "运行中"
							: "未运行"
			}（系统设置中${health.accessibilityEnabledInSettings ? "已启用" : "未启用"}）`,
		);
		if (health.notificationListenerState) {
			const nl = health.notificationListenerState === "connected"
				? "已连接"
				: health.notificationListenerState === "enabled_not_connected"
					? "已授权，正在绑定"
					: "未开启";
			lines.push(`通知监听：${nl}`);
		}
		lines.push(`截图：${health.screenshotSupported ? "支持" : "不支持（需要 Android 11+）"}`);
		lines.push(
			`系统权限：定位${health.locationPermissionGranted ? "已授予" : "未授予"} · ` +
				`通知${health.notificationPermissionGranted ? "已授予" : "未授予"} · ` +
				`相机(手电筒)${health.cameraPermissionGranted ? "已授予" : "未授予"} · ` +
				`震动${health.vibratePermissionGranted ? "已授予" : "未授予"}`,
		);
		const groups = health.capabilities.map((capability) => {
			const state = !capability.enabled
				? "已关闭"
				: capability.enabledForSession === false
					? "本会话禁用"
					: capability.usable
						? "可用"
						: `已开启但不可用${capability.reason ? `（${capability.reason}）` : ""}`;
			return `${capability.id}=${state}`;
		});
		lines.push(`能力：${groups.join(" · ")}`);
		const shizuku = health.shizuku;
		if (shizuku) {
			const identity = shizuku.ready
				? shizuku.uid === 0
					? "已就绪（root，uid=0）"
					: `已就绪（ADB 身份，uid=${shizuku.uid}）`
				: "未就绪";
			lines.push(`Shell：${identity} —— ${shizuku.note}`);
		}
		return textResult(lines.join("\n"), {
			connected: true,
			port: health.port,
			capabilities: health.capabilities,
		});
	});
}

// ------------------------------------------------------------------ 注册表 ----

interface DeviceToolSpec {
	name: string;
	/** 这个工具属于哪一组能力；`null` 表示不依赖任何一组（只有 `android_status`），只要有任意一组可用即激活。 */
	capability: DeviceCapabilityId | null;
	label: string;
	description: string;
	promptSnippet: string;
	promptGuidelines?: string[];
	parameters: TSchema;
	run: (params: Record<string, unknown>, ctx: ExtensionContext) => Promise<ToolOutcome>;
}

const DEVICE_TOOLS: DeviceToolSpec[] = [
	{
		name: "android_status",
		capability: null,
		label: "设备状态",
		description: "Phone bridge status: enabled capability groups, foreground app, permissions, and shell identity.",
		promptSnippet: "Inspect phone bridge status, foreground app, permissions & Shizuku shell identity",
		parameters: Type.Object({}),
		run: () => statusBridge(),
	},
	{
		name: "android_screen",
		capability: "accessibility",
		label: "屏幕",
		description: "Phone UI automation: look, tap, type, swipe, key, wait, idle, shot(marks), steps(<=8).",
		promptSnippet: "Read and automate the phone UI (look, tap, type, swipe, key, wait, idle, shot, steps)",
		promptGuidelines: [
			"For phone UI automation, follow look -> act (tap/type/swipe/key returns a UI diff) -> if unchanged, look again or use shot(marks=true).",
			"Prefer node index, resourceId, or anchor over raw pixel coordinates; batch predictable flows with action=\"steps\" (max 8).",
		],
		parameters: Type.Object({
			action: ScreenAction,
			steps: Type.Optional(Type.Array(StepSpec, { description: "batch <=8 steps" })),
			filter: Type.Optional(Type.String({ description: "look: filter text" })),
			diff: Type.Optional(Type.Boolean({ description: "look: diff only" })),
			maxNodes: Type.Optional(Type.Number({ description: "look: node cap" })),
			index: Type.Optional(Type.Number({ description: "node index from look or shot(marks=true)" })),
			x: Type.Optional(Type.Number({ description: "x px (display space)" })),
			y: Type.Optional(Type.Number({ description: "y px (display space)" })),
			text: Type.Optional(Type.String({ description: "tap/wait: match text; type: text to input" })),
			targetText: Type.Optional(Type.String({ description: "type: match input field by hint/current text" })),
			desc: Type.Optional(Type.String({ description: "contentDesc part" })),
			resourceId: Type.Optional(Type.String({ description: "view id part" })),
			anchor: Type.Optional(Type.String({ description: "tap: nearby anchor text" })),
			occurrence: Type.Optional(Type.Number({ description: "tap: 1-based match #" })),
			longPress: Type.Optional(Type.Boolean({ description: "tap: long press" })),
			direction: Type.Optional(StringEnum(["forward", "backward"] as const, { description: "scroll dir" })),
			x1: Type.Optional(Type.Number()),
			y1: Type.Optional(Type.Number()),
			x2: Type.Optional(Type.Number()),
			y2: Type.Optional(Type.Number()),
			durationMs: Type.Optional(Type.Number()),
			submit: Type.Optional(Type.Boolean({ description: "type: submit" })),
			key: Type.Optional(Type.String({ description: "back/home/recents/lock/screenshot or KEYCODE_*" })),
			repeat: Type.Optional(Type.Number()),
			waitMs: Type.Optional(Type.Number()),
			gone: Type.Optional(Type.Boolean()),
			format: Type.Optional(StringEnum(["jpeg", "png"] as const)),
			maxDimension: Type.Optional(Type.Number()),
			region: Type.Optional(Type.Array(Type.Number(), { description: "crop [l,t,r,b]" })),
			marks: Type.Optional(Type.Boolean({ description: "shot: overlay numbered tap boxes" })),
		}),
		run: (params) => screenAction(params),
	},
	{
		name: "android_app",
		capability: "basic",
		label: "应用",
		description: "Android apps: list, launch, stop.",
		promptSnippet: "List, launch, or stop Android apps",
		promptGuidelines: ["android_app(action=\"stop\") kills background processes of a user app; unsaved state may be lost."],
		parameters: Type.Object({
			action: AppAction,
			package: Type.Optional(Type.String({ description: "exact package, e.g. org.telegram.messenger" })),
			q: Type.Optional(Type.String({ description: "list: filter on name or package" })),
			includeSystem: Type.Optional(Type.Boolean({ description: "list: include system apps" })),
			limit: Type.Optional(Type.Number({ description: "list: row cap, default 60" })),
		}),
		run: (params) => appAction(params),
	},
	{
		name: "android_shell",
		capability: "shell",
		label: "设备 Shell",
		description: "Android host /system/bin/sh command (Shizuku uid=2000/root when authorized, else app uid).",
		promptSnippet: "Run Android host shell commands (pm/am/settings/dumpsys via Shizuku, or app uid)",
		promptGuidelines: [
			"Use standard bash for Linux guest work (git, npm, files, ~/projects, /sdcard); use android_shell only for Android host commands.",
			"Ask the user to confirm before running destructive android_shell commands (pm uninstall/clear, rm -rf, dd, reboot).",
		],
		parameters: Type.Object({
			command: Type.String({ description: "one command; split several actions into calls" }),
			timeoutMs: Type.Optional(Type.Number({ description: "timeout ms, default 15000, cap 60000" })),
		}),
		run: (params) => shellRun(params),
	},
	{
		name: "android_comm",
		capability: "basic",
		label: "对外动作",
		description: "Comms: clipboard, notify/toast/tts/vibrate, share, open URL/intent, inbox/events/reply/dismiss/snooze.",
		promptSnippet: "Clipboard, notifications (post/read/reply/events), toast, TTS, vibrate, share & open URLs",
		parameters: Type.Object({
			action: CommAction,
			text: Type.Optional(Type.String({ description: "body; clipboard reads when omitted" })),
			title: Type.Optional(Type.String({ description: "notify: title, default pi" })),
			key: Type.Optional(Type.String({ description: "reply/dismiss/snooze: key from inbox" })),
			url: Type.Optional(Type.String({ description: "share: link; open: full URL or intent://" })),
			subject: Type.Optional(Type.String({ description: "share: title" })),
			ms: Type.Optional(Type.Number({ description: "vibrate ms / snooze delay ms" })),
			language: Type.Optional(Type.String({ description: "tts: BCP-47, e.g. zh-CN" })),
			rate: Type.Optional(Type.Number({ description: "tts: rate 0.1-3.0" })),
			long: Type.Optional(Type.Boolean({ description: "toast: long duration" })),
			limit: Type.Optional(Type.Number({ description: "inbox/events: row cap" })),
			since: Type.Optional(Type.Number({ description: "events: cursor from previous events call" })),
		}),
		run: (params) => commAction(params),
	},
	{
		name: "android_device",
		capability: "basic",
		label: "设备信息与硬件",
		description: "Device hardware & state: info, network, volume, battery, torch, location, sensors, sensor.",
		promptSnippet: "Read/control phone hardware & state (info, network, volume, battery, torch, location, sensors)",
		parameters: Type.Object({
			action: DeviceAction,
			on: Type.Optional(Type.Boolean({ description: "torch: true on, false off" })),
			stream: Type.Optional(StringEnum(["music", "ring", "notification", "alarm", "voice"] as const, { description: "volume: audio stream" })),
			level: Type.Optional(Type.Number({ description: "volume: target integer level (0..max)" })),
			percent: Type.Optional(Type.Number({ description: "volume: target percentage (0..100)" })),
			typeName: Type.Optional(Type.String({ description: "sensor: type name, e.g. accelerometer" })),
			type: Type.Optional(Type.Number({ description: "sensor: Android Sensor.TYPE_*" })),
			timeoutMs: Type.Optional(Type.Number({ description: "sensor: sample timeout ms, default 1500" })),
		}),
		run: (params) => deviceAction(params),
	},
];

// ---------------------------------------------------------------------------
// Compact dynamic prompt section (zero generated skill files, zero overhead when off)
// ---------------------------------------------------------------------------

/**
 * Remove any legacy `<agentDir>/skills/pi-android-device` directory left by older builds.
 * Device tool guidance now lives cleanly inside each tool's native `promptSnippet` /
 * `promptGuidelines` and the dynamic `phone_bridge` system prompt section — no synthetic
 * skill file is ever created or registered.
 */
async function cleanupLegacySkillDir(): Promise<void> {
	try {
		const { rm } = await import("node:fs/promises");
		const { join } = await import("node:path");
		const home = process.env.HOME && process.env.HOME.trim().length > 0 ? process.env.HOME.trim() : "/root";
		const agentDir = process.env.PI_CODING_AGENT_DIR && process.env.PI_CODING_AGENT_DIR.trim().length > 0
			? process.env.PI_CODING_AGENT_DIR.trim()
			: join(home, ".pi", "agent");
		await rm(join(agentDir, "skills", "pi-android-device"), { recursive: true, force: true });
	} catch {
		// best effort
	}
}

/**
 * Tailored strictly to the currently usable capability groups and injected into
 * Pi SDK 1.6.1's structured `event.systemPromptOptions.sections["phone_bridge"]`
 * ONLY when at least one group is active (0 tokens when all groups are disabled).
 */
function environmentGuidance(usable: ReadonlySet<string>): string {
	const lines: string[] = [
		"This session runs inside Linux on an Android phone (`~/projects` = fast local projects, `/sdcard` = shared phone storage, `~/.pi/agent` = Pi config).",
		"Active `android_*` tools control the Android host directly; disabled groups are unmounted. Call `android_status` if a permission or service seems missing.",
	];
	if (usable.has("accessibility")) {
		lines.push(
			"Screen automation (`android_screen`): `look` -> `tap`/`type`/`swipe`/`key` (every action appends a UI diff verdict); if unchanged or sparse, `look` again or use `shot(marks=true)`.",
		);
	}
	if (usable.has("shell")) {
		lines.push(
			"Host shell (`android_shell`): runs `/system/bin/sh` on Android (uid=2000/root via Shizuku, else app sandbox). Keep Linux dev tasks in `bash`.",
		);
	}
	return lines.join("\n");
}

const ALL_DEVICE_TOOL_NAMES = new Set(DEVICE_TOOLS.map((tool) => tool.name));

async function usableCapabilities(): Promise<ReadonlySet<string> | null> {
	try {
		const health = await bridgeHealth();
		return new Set(health.capabilities.filter((capability) => capability.usable).map((capability) => capability.id));
	} catch {
		return null;
	}
}

/**
 * 零上下文占用硬约束：
 * - 当没有任何设备能力可用（全关或桥未连接）时，**包括 `android_status` 在内所有工具全部停用**，
 *   不注入任何系统提示词段 → 上下文占用严格为 0 token；
 * - 只要至少开启 1 组可用能力，才激活对应能力组的工具与 `android_status`，并在每轮 `before_agent_start`
 *   通过 `pi.setActiveTools()` + 官方结构化 `systemPromptOptions.sections["phone_bridge"]` 实时热同步。
 */
function shouldActivate(spec: DeviceToolSpec, usable: ReadonlySet<string> | null): boolean {
	if (usable === null || usable.size === 0) return false;
	if (spec.capability === null) return true;
	return usable.has(spec.capability);
}

function syncActiveDeviceTools(pi: ExtensionAPI, usable: ReadonlySet<string> | null): boolean {
	usableGroups = usable ?? new Set<string>();
	const desired = new Set<string>();
	for (const tool of DEVICE_TOOLS) {
		if (shouldActivate(tool, usable)) desired.add(tool.name);
	}
	try {
		const current = pi.getActiveTools();
		const currentDevice = current.filter((name) => ALL_DEVICE_TOOL_NAMES.has(name));
		const same =
			currentDevice.length === desired.size &&
			currentDevice.every((name) => desired.has(name));
		if (!same) {
			const nonDevice = current.filter((name) => !ALL_DEVICE_TOOL_NAMES.has(name));
			pi.setActiveTools([...nonDevice, ...desired]);
		}
	} catch {
		// older/mocked host without setActiveTools: defaultActive already took effect at load time
	}
	return desired.size > 0;
}

export default async function (pi: ExtensionAPI) {
	// Fast cold-start path: read the usableCapabilities snapshot just written to
	// device-bridge.json by DeviceBridgeController.start() (0 ms, no loopback HTTP /
	// undici warm-up on boot). Falls back to /app/health only if the field is absent.
	const snapshotUsable = await loadInitialUsableCapabilities();
	const initialUsable = snapshotUsable === undefined ? await usableCapabilities() : snapshotUsable;
	usableGroups = initialUsable ?? new Set<string>();

	// Sweep any legacy auto-generated pi-android-device skill folder asynchronously.
	void cleanupLegacySkillDir();

	for (const tool of DEVICE_TOOLS) {
		pi.registerTool({
			name: tool.name,
			label: tool.label,
			description: tool.description,
			promptSnippet: tool.promptSnippet,
			promptGuidelines: tool.promptGuidelines,
			parameters: tool.parameters,
			namespace: DEVICE_NAMESPACE,
			defaultActive: shouldActivate(tool, initialUsable),
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

	// 每轮发送前实时探测 `/app/health` 并热同步活跃设备工具与结构化提示词段：
	// - 使用 Pi SDK 1.6.1 原生 `event.systemPromptOptions.sections["phone_bridge"]`，绝不返回
	//   `{ systemPrompt }`（避免触发 `forceSystemPrompt` 破坏结构化提示词 diff 与缓存、或冻结旧工具列表）；
	// - 关闭全部设备能力时，所有 `android_*` 工具立刻移出活跃表并移除 `phone_bridge` 段 → 严格 0 Token 占用。
	pi.on("before_agent_start", async (event) => {
		const liveUsable = await usableCapabilities();
		const hasActive = syncActiveDeviceTools(pi, liveUsable);
		const sections = (event as { systemPromptOptions?: { sections?: Record<string, string> } }).systemPromptOptions?.sections;
		if (!hasActive || liveUsable === null) {
			if (sections && "phone_bridge" in sections) {
				delete sections.phone_bridge;
			}
			return undefined;
		}
		if (sections) {
			sections.phone_bridge = environmentGuidance(liveUsable);
			return undefined;
		}
		// Fallback only for mocked/legacy hosts without structured systemPromptOptions.sections
		const guidance = environmentGuidance(liveUsable);
		if (event.systemPrompt.includes("## Phone bridge (pi-android)")) return undefined;
		return { systemPrompt: `${event.systemPrompt}\n\n## Phone bridge (pi-android)\n${guidance}` };
	});

	// TUI mode keeps a lightweight footer status probe; App SDK host shows live state in 「设置 → 设备能力」.
	pi.on("session_start", async (_event, ctx) => {
		if (process.env.PI_SDK_HOST === "1") return;
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
