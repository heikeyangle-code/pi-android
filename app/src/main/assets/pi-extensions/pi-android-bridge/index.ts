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

const SCREEN_ACTIONS = ["look", "tap", "type", "swipe", "key", "wait", "shot", "steps"] as const;
const APP_ACTIONS = ["list", "launch", "stop"] as const;
const FILE_ACTIONS = ["dirs", "list", "read", "write", "export", "import", "download"] as const;
const COMM_ACTIONS = [
	"clipboard",
	"notify",
	"toast",
	"tts",
	"vibrate",
	"share",
	"open",
	"inbox",
	"reply",
	"dismiss",
	"snooze",
] as const;
const DEVICE_ACTIONS = ["battery", "torch", "location", "sensors", "sensor"] as const;

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
		Type.Literal("shot"),
		Type.Literal("steps"),
	],
	{ description: "look · tap · type · swipe · key · wait · shot · steps" },
);

const AppAction = Type.Union([Type.Literal("list"), Type.Literal("launch"), Type.Literal("stop")], {
	description: "list · launch · stop",
});

const FileAction = Type.Union(
	[
		Type.Literal("dirs"),
		Type.Literal("list"),
		Type.Literal("read"),
		Type.Literal("write"),
		Type.Literal("export"),
		Type.Literal("import"),
		Type.Literal("download"),
	],
	{ description: "dirs · list · read · write · export · import · download" },
);

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
		Type.Literal("reply"),
		Type.Literal("dismiss"),
		Type.Literal("snooze"),
	],
	{ description: "clipboard · notify · toast · tts · vibrate · share · open · inbox · reply · dismiss · snooze" },
);

const DeviceAction = Type.Union(
	[
		Type.Literal("battery"),
		Type.Literal("torch"),
		Type.Literal("location"),
		Type.Literal("sensors"),
		Type.Literal("sensor"),
	],
	{ description: "battery · torch · location · sensors · sensor" },
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
		],
		{ description: "step action" },
	),
	text: Type.Optional(Type.String({ description: "tap/swipe: match text; type: text to write" })),
	index: Type.Optional(Type.Number({ description: "node index" })),
	resourceId: Type.Optional(Type.String({ description: "resource id part" })),
	x: Type.Optional(Type.Number({ description: "x px" })),
	y: Type.Optional(Type.Number({ description: "y px" })),
	key: Type.Optional(Type.String({ description: "key name" })),
	direction: Type.Optional(StringEnum(["forward", "backward"] as const, { description: "scroll direction" })),
	submit: Type.Optional(Type.Boolean({ description: "type: press enter" })),
	waitMs: Type.Optional(Type.Number({ description: "wait timeout ms" })),
	gone: Type.Optional(Type.Boolean({ description: "wait for it to go" })),
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
	maxNodes?: number;
	index?: number;
	x?: number;
	y?: number;
	text?: string;
	desc?: string;
	resourceId?: string;
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
	const data = await bridgePost<DumpData>("/app/ui/dump", { filter: p.filter, maxNodes: p.maxNodes });
	const header =
		`[look] ${data.packageName} · ${data.screen.width}×${data.screen.height} · ` +
		`控件 ${data.nodeCount}/${data.totalNodeCount}${data.truncated ? "（已按上限截断）" : ""}`;
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
	if (!byIndex && !byPoint && !hasSelector(p)) {
		throw new Error(
			'[BAD_PARAM] android_screen 的 action="tap" 需要 index、x+y 或 text/desc/resourceId 之一。' +
				"\n提示：先 action=look 拿控件编号，编号比坐标稳。",
		);
	}
	await ensureDiffBaseline();
	const data = await bridgePost<ActionData>("/app/ui/tap", {
		index: p.index,
		x: p.x,
		y: p.y,
		longPress: p.longPress === true,
		...selectorBody({ text: p.text, desc: p.desc, resourceId: p.resourceId }),
	});
	const where = data.x !== undefined
		? `(${data.x}, ${data.y})`
		: byIndex
			? `#${p.index}`
			: (p.text ?? p.resourceId ?? p.desc ?? "目标");
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
		...selectorBody({ desc: p.desc, resourceId: p.resourceId }),
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

async function screenWait(p: ScreenParams, params: Record<string, unknown>): Promise<ToolOutcome> {
	if (!hasSelector(p) && typeof p.index !== "number") {
		throw new Error(
			'[BAD_PARAM] android_screen 的 action="wait" 需要 text、desc 或 resourceId 之一。' +
				"\n提示：wait 只认选择器，不认坐标。",
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

async function screenShot(p: ScreenParams): Promise<ToolOutcome> {
	const data = await bridgePost<ScreenshotData>(
		"/app/screenshot",
		{ format: p.format, maxDimension: p.maxDimension, region: p.region },
		60_000,
	);
	const scale =
		data.scale !== undefined && data.scale !== 1
			? `，已缩放 ×${data.scale.toFixed(3)}（原图 ${data.sourceWidth}×${data.sourceHeight}）`
			: "";
	const notes = [
		`[shot] ${data.width}×${data.height}（${data.mimeType}，${formatSize(data.bytes)}）${scale}`,
	];
	if (data.region) notes.push(`裁剪区域（display 像素）：[${data.region.join(", ")}]`);
	notes.push("文字看不清时：调大 maxDimension、改用 png，或用 region 只截那一块。");
	return imageResult(data.base64, data.mimeType, notes.join("\n"), {
		width: data.width,
		height: data.height,
		bytes: data.bytes,
		scale: data.scale,
		region: data.region,
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
	const allowed = ["look", "tap", "type", "swipe", "key", "wait"];
	for (const [index, raw] of steps.entries()) {
		const step = (raw ?? {}) as Record<string, unknown>;
		const name = typeof step.action === "string" ? step.action : "";
		if (!allowed.includes(name)) {
			throw new Error(
				`[BAD_PARAM] android_screen 的 steps[${index}].action 只能是 "look" / "tap" / "type" / ` +
					`"swipe" / "key" / "wait"，收到 ${JSON.stringify(step.action)}。` +
					"\n提示：一步都没执行（先校验再动手），改对再来。",
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
						"\n提示：让用户在「设置 → 设备能力」打开「屏幕」那一组后重试；不要换工具绕过。",
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

// -------------------------------------------------------------------- 文件 ----

interface FileParams {
	action?: string;
	path?: string;
	name?: string;
	content?: string;
	base64?: string;
	mimeType?: string;
	maxBytes?: number;
}

async function filesAction(params: Record<string, unknown>): Promise<ToolOutcome> {
	return guarded(async () => {
		const p = params as FileParams;
		const action = typeof p.action === "string" ? p.action : "";
		if (action === "dirs") {
			const data = await bridgeGet<SafRootsData>("/app/files");
			const lines = data.roots.map((root) => `- ${root.name}${root.exists ? "" : "（授权已失效）"}`);
			if (lines.length === 0) lines.push("（还没有授权任何目录）");
			return textResult(`已授权目录（${data.count}）：\n\n${lines.join("\n")}\n\n${data.note}`, {
				count: data.count,
			});
		}
		if (action === "list") {
			const data = await bridgePost<SafListData>("/app/files/list", { path: p.path });
			const lines = data.entries.map(
				(entry) => `${entry.directory ? "[目录]" : `[${formatSize(entry.bytes)}]`} ${entry.name}`,
			);
			if (lines.length === 0) lines.push("（空目录）");
			return textResult(
				truncateForModel(
					`${data.path}：\n\n${lines.join("\n")}\n\n共 ${data.count} 项${data.truncated ? "（已截断）" : ""}`,
					"目录列表",
				),
				{ path: data.path, count: data.count },
			);
		}
		if (action === "read") {
			requireText("android_files", "read", params, ["path"]);
			const data = await bridgePost<SafReadData>("/app/files/read", { path: p.path, maxBytes: p.maxBytes });
			if (typeof data.text === "string") {
				return textResult(
					`已读入 ${data.path}（${formatSize(data.bytes)}${data.truncated ? "，已截断" : ""}）：\n\n` +
						truncateForModel(data.text, "文件内容"),
					{ path: data.path, bytes: data.bytes },
				);
			}
			return textResult(
				`已读入 ${data.path}（${formatSize(data.bytes)}），它是二进制文件，以 base64 返回：\n\n` +
					truncateForModel(data.base64 ?? "", "base64"),
				{ path: data.path, bytes: data.bytes, binary: true },
			);
		}
		if (action === "write") {
			requireText("android_files", "write", params, ["path"]);
			const data = await bridgePost<SafWriteData>("/app/files/write", {
				path: p.path,
				content: p.content,
				base64: p.base64,
				mimeType: p.mimeType,
			});
			return textResult(
				`已${data.created ? "创建" : "覆盖"} ${data.path}（${formatSize(data.bytes)}，${data.mimeType}）\nURI：${data.uri}`,
				{ path: data.path, bytes: data.bytes, created: data.created },
			);
		}
		if (action === "export" || action === "download") {
			requireText("android_files", action, params, ["name"]);
			const data = await bridgePost<ExportData>("/app/export", {
				name: p.name,
				content: p.content,
				base64: p.base64,
				mimeType: p.mimeType,
			});
			return textResult(
				`已导出到 ${data.location}/${data.displayName}（${formatSize(data.bytes)}）\nURI：${data.uri}`,
				{ displayName: data.displayName, bytes: data.bytes },
			);
		}
		if (action === "import") {
			requireText("android_files", "import", params, ["name"]);
			const data = await bridgePost<ImportData>("/app/import", { name: p.name, maxBytes: p.maxBytes });
			if (typeof data.text === "string") {
				return textResult(
					`已读入 ${data.displayName}（${formatSize(data.bytes)}${data.truncated ? "，已截断" : ""}）：\n\n` +
						truncateForModel(data.text, "文件内容"),
					{ displayName: data.displayName, bytes: data.bytes },
				);
			}
			return textResult(
				`已读入 ${data.displayName}（${formatSize(data.bytes)}），它是二进制文件。` +
					"要看内容可以在 guest 里用 bash 处理。",
				{ displayName: data.displayName, bytes: data.bytes, binary: true },
			);
		}
		throw badParam("android_files", "action", [...FILE_ACTIONS], p.action);
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
		// 不写这一行，50 KiB 的上限对模型就是不可见的：被截断的 stdout 会看起来像
		// 完整输出，而「截断的 stdout」和「截断的 stderr」意思完全不同（少了结果 vs 少了原因）。
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
		if (data.policy) parts.push(data.policy);
		const text = truncateForModel(parts.join("\n"), "Shell 输出", "tail");
		// pi 的失败口径（`core/tools/bash.ts:368-373`）：超时 / 无退出码 / 非零退出都是
		// 错误，用 throw 表达；正文里保留 pi 的原句，App 的退出码解析因此仍然成立。
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
						const body = typeof entry.text === "string" && entry.text.length > 0 ? `：${entry.text}` : "";
						const flags = [
							entry.replyable === true ? "可回复" : "",
							entry.isOngoing === true ? "常驻" : "",
							entry.isClearable === false ? "不可撤销" : "",
						].filter((flag) => flag.length > 0);
						return `- [${show(entry.key)}] ${show(entry.packageName)}｜${show(entry.title)}${body}` +
							`${flags.length > 0 ? `（${flags.join("、")}）` : ""}`;
					})}`,
					recent,
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
}

async function deviceAction(params: Record<string, unknown>): Promise<ToolOutcome> {
	return guarded(async () => {
		const p = params as DeviceParams;
		const action = typeof p.action === "string" ? p.action : "";
		switch (action) {
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
		// 模型最需要的上下文就是「我接下来要动的是哪个应用」，所以先报前台。
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
		lines.push(`截图：${health.screenshotSupported ? "支持" : "不支持（需要 Android 11+）"}`);
		lines.push(
			`系统权限：定位${health.locationPermissionGranted ? "已授予" : "未授予"} · ` +
				`通知${health.notificationPermissionGranted ? "已授予" : "未授予"} · ` +
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
			// 身份，而不只是标签：「已就绪（ADB 身份，uid=2000）」才是模型判断
			// pm / input / screencap 能不能用的依据。
			const identity = shizuku.ready
				? shizuku.uid === 0
					? "已就绪（root，uid=0）"
					: `已就绪（ADB 身份，uid=${shizuku.uid}）`
				: "未就绪";
			lines.push(`Shell：${identity} —— ${shizuku.note}`);
		}
		const saf = health.saf;
		if (saf) {
			lines.push(
				saf.count > 0
					? `已授权（SAF）目录：${saf.roots.join("、")}`
					: "已授权（SAF）目录：无 —— 需要用户在「设置 → 设备能力 → 存储」授权",
			);
		}
		const gate = health.gate;
		if (gate?.reported) {
			const grants = gate.sessionGrants ?? [];
			lines.push(`本会话已记住的危险操作：${grants.length > 0 ? grants.join("、") : "无（每次都会问）"}`);
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
	/** 这个工具属于哪一组能力；`null` 表示不依赖任何一组（只有 `android_status`），永远注册。 */
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
		description: "Bridge status: capability groups, app, shell uid, SAF.",
		promptSnippet: "Bridge status",
		parameters: Type.Object({}),
		run: () => statusBridge(),
	},
	{
		name: "android_screen",
		capability: "accessibility",
		label: "屏幕",
		description: "Screen: look, tap, type, swipe, key, wait, shot, steps.",
		promptSnippet: "Screen read + act",
		promptGuidelines: [
			"Destructive actions ask first; if declined, stop and say so — never work around it.",
		],
		parameters: Type.Object({
			action: ScreenAction,
			steps: Type.Optional(Type.Array(StepSpec, { description: "up to 8 steps, stops at the first error" })),
			filter: Type.Optional(Type.String({ description: "look: keep nodes containing this" })),
			maxNodes: Type.Optional(Type.Number({ description: "look: node cap, default 400" })),
			index: Type.Optional(Type.Number({ description: "node index from look" })),
			x: Type.Optional(Type.Number({ description: "x in display px" })),
			y: Type.Optional(Type.Number({ description: "y in display px" })),
			text: Type.Optional(Type.String({ description: "tap/swipe: text to match; type: text to write" })),
			desc: Type.Optional(Type.String({ description: "contentDescription part" })),
			resourceId: Type.Optional(Type.String({ description: "resource id part, e.g. btn_send" })),
			longPress: Type.Optional(Type.Boolean({ description: "tap: long press" })),
			direction: Type.Optional(StringEnum(["forward", "backward"] as const, { description: "swipe: scroll direction" })),
			x1: Type.Optional(Type.Number({ description: "swipe: start x" })),
			y1: Type.Optional(Type.Number({ description: "swipe: start y" })),
			x2: Type.Optional(Type.Number({ description: "swipe: end x" })),
			y2: Type.Optional(Type.Number({ description: "swipe: end y" })),
			durationMs: Type.Optional(Type.Number({ description: "swipe: ms, default 300" })),
			submit: Type.Optional(Type.Boolean({ description: "type: press enter after" })),
			key: Type.Optional(Type.String({ description: "back/home/recents/lock… or ENTER, KEYCODE_*" })),
			repeat: Type.Optional(Type.Number({ description: "key: repeat 1-20" })),
			waitMs: Type.Optional(Type.Number({ description: "wait: timeout ms, default 5000" })),
			gone: Type.Optional(Type.Boolean({ description: "wait: until it disappears" })),
			format: Type.Optional(StringEnum(["jpeg", "png"] as const, { description: "shot: png is lossless" })),
			maxDimension: Type.Optional(Type.Number({ description: "shot: long side cap, default 1280" })),
			region: Type.Optional(Type.Array(Type.Number(), { description: "shot: crop [left,top,right,bottom]" })),
		}),
		run: (params) => screenAction(params),
	},
	{
		name: "android_app",
		capability: "basic",
		label: "应用",
		description: "App: list, launch, stop.",
		promptSnippet: "App list/launch/stop",
		promptGuidelines: ["stop kills the app's background process; unsaved work may be lost."],
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
		name: "android_files",
		capability: "basic",
		label: "文件",
		description: "Files: dirs, list, read, write, export, import, download.",
		promptSnippet: "SAF + Download files",
		parameters: Type.Object({
			action: FileAction,
			path: Type.Optional(Type.String({ description: "\"root/relative\"; omit on list for root names" })),
			name: Type.Optional(Type.String({ description: "Download file name, e.g. report.md" })),
			content: Type.Optional(Type.String({ description: "text content" })),
			base64: Type.Optional(Type.String({ description: "binary as base64, not with content" })),
			mimeType: Type.Optional(Type.String({ description: "MIME, default text/plain" })),
			maxBytes: Type.Optional(Type.Number({ description: "bytes to read, default 1MB, cap 4MB" })),
		}),
		run: (params) => filesAction(params),
	},
	{
		name: "android_shell",
		capability: "shell",
		label: "设备 Shell",
		description: "Device shell: any command, guarded by device policy.",
		promptSnippet: "Device shell",
		promptGuidelines: [
			"Shell is guarded by device policy: irreversible commands ask for confirmation.",
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
		description: "Comms: clipboard, notify/toast/tts, share, open, inbox.",
		promptSnippet: "Comms & clipboard",
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
			limit: Type.Optional(Type.Number({ description: "inbox: rows, default 20" })),
		}),
		run: (params) => commAction(params),
	},
	{
		name: "android_device",
		capability: "basic",
		label: "设备读数",
		description: "Device: battery, torch, location, sensors, sensor.",
		promptSnippet: "Device readings",
		parameters: Type.Object({
			action: DeviceAction,
			on: Type.Optional(Type.Boolean({ description: "torch: true on, false off" })),
			typeName: Type.Optional(Type.String({ description: "sensor: type name, e.g. accelerometer" })),
			type: Type.Optional(Type.Number({ description: "sensor: Android Sensor.TYPE_*" })),
			timeoutMs: Type.Optional(Type.Number({ description: "sensor: sample timeout ms, default 1500" })),
		}),
		run: (params) => deviceAction(params),
	},
];

// ---------------------------------------------------------------------------
// environment documentation (the app's equivalent of pi's Termux AGENTS.md)
// ---------------------------------------------------------------------------

const SKILL_NAME = "pi-android-device";

/**
 * 技能正文：`SKILL.md` 是路标（frontmatter + 一份索引 + 四句话的套路），细节按主题拆在
 * `references/*.md` 里；附件不进每轮提示词，进去的只有 frontmatter 的 name/description。
 *
 * **按开启的能力组裁剪**，因为技能是写给「这一轮真的存在的工具」看的：
 * 只开 `basic` 时 `references/ui.md` 根本不写、索引里也没有屏幕那一行；`shell` 段同理。
 * 模型因此不会读到一套它调不到的工具（那正是 v1 里「说明文字太多」的一半来源：
 * 技能在讲工具，而工具有没有注册是另一回事）。
 *
 * 键是相对技能目录（`<agentDir>/skills/<SKILL_NAME>/`）的路径，因为文件由下面
 * `resources_discover` 写到磁盘上。
 */
function skillFiles(usable: ReadonlySet<string>): Record<string, string> {
	const accessibility = usable.has("accessibility");
	const shell = usable.has("shell");
	const basic = usable.has("basic");

	const rows: string[] = [];
	if (accessibility) rows.push("| screen: read, tap, type, swipe, key, shot | about to touch the UI | `references/ui.md` |");
	rows.push("| paths, identity, boundary | unsure where a file lives, or running a device command | `references/env.md` |");

	const bullets: string[] = [];
	if (accessibility) {
		bullets.push(
			"- `[n]` in `look` output is the `index` for `tap` / `type`; use coordinates only without it.",
			"- The second line of an action result is the verdict: act on it before trying anything else.",
			"- Several actions at once: `action=\"steps\"` (max 8, stops at the first error and says which).",
		);
	}
	if (basic) {
		bullets.push(
			"- Files: `android_files` dirs/list/read/write on authorized dirs; export/import on public Download.",
			"- Notifications, clipboard, vibration, share, links: `android_comm`; battery, torch, location, sensors: `android_device`.",
		);
	}
	if (shell) {
		bullets.push("- Device commands: `android_shell` — the identity decides what works (`references/env.md`).");
	}

	const skill = `---
name: ${SKILL_NAME}
description: "How to drive this phone with the android_* tools. Read before acting on it."
---

# pi-android device environment

pi runs in proot Ubuntu; the \`android_*\` tools drive this phone.
A capability group that is off has **no tools at all**: the tool is absent, the call is not refused.

Loop: \`look\` → act (the result carries the diff) → nothing changed? look again.

| topic | read it when | file |
|---|---|---|
${rows.join("\n")}

${bullets.join("\n")}
`;

	const ui = `# Screen (android_screen)

One action per call; a missing parameter is reported by name.

| action | parameters | notes |
|---|---|---|
| look | filter, maxNodes | one screen as \`[n] class "text" clickable @[x,y][x,y]\` |
| tap | index, or x+y, or text/desc/resourceId, longPress | index first; it goes stale when the screen changes |
| type | text, index or desc/resourceId, submit | focused field, or the given index |
| swipe | direction + index/text, or x1/y1/x2/y2 | direction scrolls a list; coordinates for canvases |
| key | key, repeat | back/home/recents/lock/screenshot…; raw keys need Shizuku |
| wait | text or resourceId, waitMs, gone | waits for a node; gone=true waits for it to disappear |
| shot | format, region, maxDimension | screenshot; enlarge or crop when text is unreadable |

## The diff attached to every action

\`tap\` / \`type\` / \`swipe\` / \`key\` fetch \`/app/ui/diff\` right after acting, so the same result
carries the verdict:

- \`界面变了：…\` → \`changed: true\`: the nodes that appeared, disappeared or changed; continue.
- \`界面没变，可能没点中\` → \`changed: false\`: \`look\` again and pick another index, or pass
  text/resourceId. Do not repeat the identical call.
- \`还没有上一屏可对比\` → \`changed: null\`: no baseline yet; \`look\` once and the next action can
  be compared.

## Pitfalls

- NOT_FOUND: the index is stale (look again) or the text does not match (use resourceId).
- DISABLED / NO_PERMISSION: that group is off — read \`android_status\`, tell the user, do not retry.
- \`已按上限截断\`: narrow the read with filter / maxNodes instead of asking for more.
- Password fields: the tree cannot read them; \`type\` writes but nothing reads back.
`;

	const shellSection = shell
		? `
## Shell identity

\`android_shell\` has two backends: Shizuku (uid 2000) and the app's own uid.

- \`uid=2000\`: \`pm\`, \`settings\`, \`am\`, \`input\`, \`dumpsys\`, \`screencap\` all work.
- app uid: those commands fail with SecurityException — the command is not wrong.
- \`/data/data/<pkg>\` needs root; neither backend reaches it.

Irreversible commands (\`dd\`, \`mkfs\`, \`/dev/block\`, \`pm clear\`, \`pm uninstall\`) ask first.
When the user declines, stop — do not route around it.
`
		: "";

	const env = `# Paths, identity, boundary

| path | what it is |
|---|---|
| /workspace | the workspace (engine and terminal mount it separately) |
| /sdcard | shared phone storage; public Download goes through \`android_files\` export |
| ~/.pi/agent | pi's agent dir (SYSTEM.md, skills/, device-bridge.json) |

- Guest work (git, npm, builds) uses pi's \`bash\`, never \`android_shell\`.
- Files outside the authorized dirs: export to public Download, or have the user authorize a dir.
${shellSection}`;

	const files: Record<string, string> = { "SKILL.md": skill, "references/env.md": env };
	if (accessibility) files["references/ui.md"] = ui;
	return files;
}

/**
 * v1 写过、v2 不再有的附件：能力组一换，它们就必须从磁盘上消失。
 *
 * 列在这里而不是只靠「不写」，是因为 pi 只按目录扫盘 —— 索引里没写不等于文件不在，
 * 留着它模型读到的就是一份没人维护的旧说明。
 */
const STALE_SKILL_FILES = [
	"references/tools.md",
	"references/paths.md",
	"references/shell.md",
	"references/elevate.md",
	"references/pitfalls.md",
	"references/ui.md",
];

/**
 * 技能被写到磁盘上的哪个目录。
 *
 * 用 `PI_CODING_AGENT_DIR`（App 侧 `PiRuntime.agentDir` 就是 `<rootfs>/root/.pi/agent`），
 * **不涉及工作区** —— 换工作区不影响这个路径，也不需要为工作区准备第二份。
 *
 * 写（`resources_discover`）和删（能力全关时的清理）两边共用它，免得各算一遍算歪。
 */
async function skillDirectory(): Promise<string> {
	const { join } = await import("node:path");
	const home = process.env.HOME && process.env.HOME.trim().length > 0 ? process.env.HOME.trim() : "/root";
	const agentDir = process.env.PI_CODING_AGENT_DIR && process.env.PI_CODING_AGENT_DIR.trim().length > 0
		? process.env.PI_CODING_AGENT_DIR.trim()
		: join(home, ".pi", "agent");
	return join(agentDir, "skills", SKILL_NAME);
}

/**
 * The system-prompt block appended for every turn — **only while something is usable**.
 *
 * 这里只留“这台手机有 android_* 工具”这一句。另外三条（/workspace 与 /sdcard 的快慢、
 * uid 2000 怎么拿、危险动作会确认）都是 skill 附件里写得更详细的内容（
 * references/elevate.md 整整一篇讲无线调试，比这里一句话详细十倍）—— 常驻段重复它们
 * 只是在每轮里白占位置。
 *
 * 全关时不注入：那由 [hasDeclaredDeviceTools] 决定，见 before_agent_start 那个钩子。
 */
function environmentGuidance(): string {
	return [
		"",
		"## Android device environment (pi-android)",
		"",
		"pi runs in proot Ubuntu; the `android_*` tools drive this phone, and `android_screen` reports the diff of its own action. Read the pi-android-device skill before acting on it.",
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
 * 读不到（桥没起来、token 文件读不到、超时）就返回 `null`，调用方按「一组都不注册、
 * 只留常驻的 `android_status`」处理。桥不在时我们并不知道任何一组开着没有；这时注册全部
 * 工具等于把「注册了但调用失败」重新变成默认，而这次改动的全部意义就是不让不可用的东西进请求。
 * 对用户也不是死路：`android_status` 会说出「未连接」和下一步。
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
 * `capability === null` 的是 `android_status`：**常驻**（用户拍板的例外）。它是
 * `health.capabilities[].reason` 的唯一读者 —— 「某一组开着却不可用」是模型自己查不清的
 * 那条事实，而它只有一句中文 + 一个英文 snippet，常驻的成本比「模型不知道为什么调不动」
 * 便宜得多。
 *
 * 其余工具只在它那一组 `usable` 时注册 —— 关掉的能力，它的 `description`、
 * `promptSnippet`、`promptGuidelines` 和整份参数 schema 一个字都不进请求。
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
	usableGroups = usable ?? new Set<string>();

	// 能力全关（或桥根本没起来）时，把上一轮写出去的技能目录清掉 —— **必须在这里删**。
	//
	// 时机是全部理由：扩展工厂跑在 `ResourceLoader.reload()` 的 `loadFinalExtensionSet()`
	// 里（`core/resource-loader.js:410`），而磁盘上的技能是紧接着在 `updateSkillsFromPaths()`
	// （`:424`）被扫进列表的。**工厂在前，扫盘在后** —— 所以在这里删，这一次启动就干净了。
	//
	// 曾经把这段放在 `resources_discover` 里，那是错的：那个钩子在扫盘**之后**才跑
	// （`core/agent-session.js:2628`），删掉的目录已经被扫进技能列表，而且因为返回的
	// `skillPaths` 为空，`extendResources()` 不会重扫（`core/resource-loader.js:333`）——
	// 结果是「关了还要再启动一次才不显示」。实测发现（用户问「不开开关不显示吗？现在也显示啊」）。
	//
	// pi 只认磁盘：技能是靠扫目录发现的，不是靠钩子返回什么。所以「不显示」只能靠删。
	// 删的是本扩展自己那一个目录（`<agentDir>/skills/pi-android-device`），别的技能不碰。
	if (usable === null || usable.size === 0) {
		try {
			const { rm } = await import("node:fs/promises");
			await rm(await skillDirectory(), { recursive: true, force: true });
		} catch {
			// 尽力而为：清不掉只是技能多显示一会儿，不该让扩展加载不了。
		}
	}

	// 这一轮有没有**能力组**真的可用。`android_status` 的能力是 `null`（它常驻，用来解释
	// 「为什么不能用」），不能拿它当证据 —— 否则全关时也会以为有工具可用，那段环境说明
	// 就白进每一轮请求了。
	let anyDeviceToolRegistered = false;
	for (const tool of DEVICE_TOOLS) {
		if (!shouldRegister(tool, usable)) continue;
		if (tool.capability !== null) anyDeviceToolRegistered = true;
		pi.registerTool({
			name: tool.name,
			label: tool.label,
			description: tool.description,
			promptSnippet: tool.promptSnippet,
			promptGuidelines: tool.promptGuidelines,
			parameters: tool.parameters,
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

	// Tell the agent what environment it woke up in — **but only while it can act on it**.
	//
	// 这里曾经无条件追加：全关时那段文字照样每轮进请求，告诉模型一套它根本调不到的工具。
	// 现在的条件是「这一轮至少注册了一个能力组的工具」（就是上面那个
	// anyDeviceToolRegistered）。全关 = 只剩常驻的 android_status（它只解释「为什么不能
	// 用」）—— 那种情况下不说环境：模型真的做不了任何设备操作，这段说明只会每轮白占位置。
	//
	// Sentinel 防的是「扩展被加载两次」时的重复追加，不能替代上面那个条件。
	pi.on("before_agent_start", async (event) => {
		if (!anyDeviceToolRegistered) return undefined;
		if (event.systemPrompt.includes("## Android device environment (pi-android)")) return undefined;
		return { systemPrompt: event.systemPrompt + environmentGuidance() };
	});

	// Contribute the environment skill: SKILL.md plus its references/. `resources_discover`
// can only hand back *paths*, so every file is written on the way in.
//
// 这里**只管写**。能力全关时那份残留在工厂里就被删掉了（那里删才赶得上扫盘，见上面的长注释）。
// 写失败静默：技能没写出来只会让模型少一份参考，不该让扩展加载不了。
pi.on("resources_discover", async () => {
	try {
		const usable = await usableCapabilities();
		if (usable === null || usable.size === 0) return {};
		const { mkdir, rm, writeFile } = await import("node:fs/promises");
		const { dirname, join } = await import("node:path");
		const skillDir = await skillDirectory();
		const files = skillFiles(usable);
		// 上一版写过、这一版不再有的附件必须真的删掉：pi 只按目录扫盘，索引里没写不等于
		// 文件不在 —— 留着它，模型读到的就是一份没人维护的旧说明。
		for (const stale of STALE_SKILL_FILES) {
			if (files[stale] !== undefined) continue;
			await rm(join(skillDir, stale), { force: true });
		}
		for (const [relativePath, content] of Object.entries(files)) {
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
