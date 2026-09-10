/**
 * pi-android device bridge — pi extension.
 *
 * Registers one tool per device capability the Android host app exposes, all of
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
import { StringEnum } from "@earendil-works/pi-ai";
import { Type, type Static, type TSchema } from "typebox";
import { BridgeError, bridgeGet, bridgeHealth, bridgePost, type HealthPayload } from "./client";
import { dangerLevelOf } from "./danger";

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
	text: string;
}
interface ScreenshotData {
	mimeType: string;
	base64: string;
	width: number;
	height: number;
	bytes: number;
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
	truncated: boolean;
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
		description:
			"要执行的系统全局动作。无障碍通道只能做这些；原始按键（enter/delete/方向键）用 android_keyevent，需要 Shizuku。",
	},
);

const ScreenshotFormat = StringEnum(["jpeg", "png"] as const, {
	description: "图片格式。jpeg 更小、更适合交给模型；png 无损。默认 jpeg。",
});

const DEVICE_TOOLS: DeviceToolSpec[] = [
	// ---------------------------------------------------------------- 诊断 ----
	{
		name: "android_bridge_status",
		label: "设备桥状态",
		description:
			"报告 pi-android 设备桥的运行状态：五组设备能力（基础/存储/无障碍/位置·传感器·相机/Shell）各自的开关与可用性、" +
			"无障碍服务状态、缺失的系统权限与截图支持情况。任何 android_* 工具返回 [DISABLED] 或 [NO_PERMISSION] 时，" +
			"先用本工具查清原因再回复用户。",
		promptSnippet: "检查 pi-android 设备桥与各设备能力的授权状态",
		promptGuidelines: [
			"当某个 android_* 工具返回失败时，用 android_bridge_status 查明是哪一组能力被关闭或缺少系统权限，再把原因原样转述给用户。",
			"用户问「你能做什么手机操作」时，用 android_bridge_status 列出当前真正可用的能力，不要凭猜测回答。",
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
			lines.push(
				`无障碍服务：${health.accessibilityRunning ? "运行中" : "未运行"}` +
					`（系统设置中${health.accessibilityEnabledInSettings ? "已启用" : "未启用"}）`,
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
				lines.push(`Shizuku：${shizuku.backendLabel}（${shizuku.note}）`);
			}
			const workspace = health.workspace;
			if (workspace) {
				lines.push(
					`Shell 写入边界：${workspace.shellPath ?? "未确定"}（guest 内是 ${workspace.guestPath}）` +
						"—— 工作区之内不拦，工作区之外会拒。",
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
						: "已授权（SAF）目录：无 —— 需要用户在「设置 → 设备能力 → 存储」授权目录后，android_files_* 才可用。",
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
		description:
			"读取当前屏幕的控件树，返回带编号的可读文本（每个控件给出 class、文本、资源 id、bounds 与 clickable/editable 等标记）。" +
			"这些编号用于 android_tap / android_input。需要「无障碍」能力与系统无障碍服务。",
		promptSnippet: "读取当前 Android 屏幕的控件树（带编号，供点按与输入使用）",
		promptGuidelines: [
			"在操作手机界面前先用 android_ui_dump 看清屏幕，再用它给出的编号调用 android_tap / android_input；不要凭记忆连点。",
			"android_ui_dump 之后界面可能已经变化，脚本式的连续点按前应重新 dump。",
		],
		parameters: Type.Object({
			filter: Type.Optional(
				Type.String({ description: "只保留文本/描述/资源 id 含该子串的控件及其祖先，用于在小屏上快速定位。" }),
			),
			maxNodes: Type.Optional(
				Type.Number({ description: `最多返回多少个控件，默认 ${400}，上限 2000。` }),
			),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { filter?: string; maxNodes?: number };
				const data = await bridgePost<DumpData>("/app/ui/dump", {
					filter: p.filter,
					maxNodes: p.maxNodes,
				});
				const header =
					`包名 ${data.packageName} · 屏幕 ${data.screen.width}×${data.screen.height} · ` +
					`控件 ${data.nodeCount}/${data.totalNodeCount}${data.truncated ? "（已按上限截断）" : ""}`;
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
		description:
			"点按屏幕上的控件或坐标。优先用 android_ui_dump 给出的 index（走控件的点击动作，最可靠），" +
			"也可以直接给 x/y 坐标。设置 longPress 为长按。需要「无障碍」能力。",
		promptSnippet: "点按屏幕控件（用 android_ui_dump 的编号）或坐标",
		promptGuidelines: [
			"android_tap 的 index 必须来自最近一次 android_ui_dump；如果返回 NOT_FOUND，先重新 dump 再点。",
		],
		parameters: Type.Object({
			index: Type.Optional(Type.Number({ description: "android_ui_dump 输出里的控件编号。" })),
			x: Type.Optional(Type.Number({ description: "横坐标（像素）。与 y 一起使用时忽略 index。" })),
			y: Type.Optional(Type.Number({ description: "纵坐标（像素）。" })),
			longPress: Type.Optional(Type.Boolean({ description: "是否长按，默认 false。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { index?: number; x?: number; y?: number; longPress?: boolean };
				const data = await bridgePost<ActionData>("/app/ui/tap", {
					index: p.index,
					x: p.x,
					y: p.y,
					longPress: p.longPress === true,
				});
				const target = typeof data.target === "string" ? data.target : p.index !== undefined ? `#${p.index}` : "";
				const where = data.x !== undefined ? `(${data.x}, ${data.y})` : target;
				return textResult(`已${p.longPress === true ? "长按" : "点按"} ${where}${target && where !== target ? ` ${target}` : ""}（方式：${data.mode}）`, {
					mode: data.mode,
				});
			}),
	},
	{
		name: "android_input",
		label: "输入文本",
		description:
			"向当前界面的输入框写入文本。默认写入获得焦点的输入框，也可以指定 android_ui_dump 里的 index。" +
			"先尝试直接写入（ACTION_SET_TEXT）；被拒绝时自动改用「剪贴板 + 粘贴」，代价是会替换系统剪贴板里的内容（返回里会说明）。" +
			"submit=true 时会尝试触发回车提交（Android 11+）。需要「无障碍」能力，且属于危险操作，首次会请求确认。" +
			"要输入 enter/delete/方向键，用 android_keyevent（需要 Shizuku）。",
		promptSnippet: "向屏幕上获得焦点的输入框写入文本",
		parameters: Type.Object({
			text: Type.String({ description: "要写入的文本（会替换输入框原有内容）。" }),
			index: Type.Optional(Type.Number({ description: "android_ui_dump 里的输入框编号；省略则用当前焦点。" })),
			submit: Type.Optional(Type.Boolean({ description: "写入后是否尝试回车提交，默认 false。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text: string; index?: number; submit?: boolean };
				const data = await bridgePost<{
					chars: number;
					submitted: boolean;
					submitHint?: string;
					mechanism?: string;
					clipboardNote?: string;
				}>("/app/ui/input", {
					text: p.text,
					index: p.index,
					submit: p.submit === true,
				});
				let text = `已写入 ${data.chars} 个字符${data.submitted ? "，并已提交" : ""}`;
				if (data.mechanism === "clipboard_paste") text += "（改用剪贴板粘贴完成）";
				if (data.clipboardNote) text += `\n${data.clipboardNote}`;
				if (data.submitHint) text += `\n${data.submitHint}`;
				return textResult(text, { chars: data.chars, submitted: data.submitted, mechanism: data.mechanism });
			}),
	},
	{
		name: "android_key",
		label: "系统按键",
		description:
			"执行系统全局动作：back（返回）、home（主页）、recents（最近任务）、notifications（通知栏）、quicksettings（快捷设置）、" +
			"powermenu（电源菜单）、lock（锁屏，Android 9+）、screenshot（系统截图，Android 11+）、split（分屏，Android 12+）。" +
			"需要「无障碍」能力。这些动作不需要额外权限；enter/delete/方向键等原始按键用 android_keyevent。",
		promptSnippet: "执行 Android 系统全局动作（back/home/recents/notifications/quicksettings/lock/screenshot…）",
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
		description:
			"向当前焦点注入原始按键（enter、del、tab、escape、dpad_up/down/left/right、move_end、page_down、数字键 F1 等）。" +
			"这需要 ADB 身份（uid=2000），也就是要装好并授权 Shizuku；没有 Shizuku 时它会明确拒绝而不是装作成功。" +
			"危险操作：按键会送到当前前台应用，等同于你亲手按。",
		promptSnippet: "注入原始按键（enter/del/方向键等；需要 Shizuku）",
		promptGuidelines: [
			"Android 的全局动作（返回/主页）用 android_key 就够了；android_keyevent 只在确实需要 enter、delete、方向键这类按键时使用。",
		],
		parameters: Type.Object({
			keys: Type.String({ description: "按键名，空格或逗号分隔，例如 \"ENTER\" 或 \"DPAD_DOWN DPAD_DOWN\"；KEYCODE_ 前缀可省略。" }),
			repeat: Type.Optional(Type.Number({ description: "重复次数 1–20，默认 1。" })),
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
		label: "滑动",
		description:
			"从 (x1,y1) 滑动到 (x2,y2)，用于滚动列表、关闭卡片、拖动等。需要「无障碍」能力。",
		promptSnippet: "在屏幕上滑动（滚动列表、翻页、拖动）",
		parameters: Type.Object({
			x1: Type.Number({ description: "起点横坐标。" }),
			y1: Type.Number({ description: "起点纵坐标。" }),
			x2: Type.Number({ description: "终点横坐标。" }),
			y2: Type.Number({ description: "终点纵坐标。" }),
			durationMs: Type.Optional(Type.Number({ description: "手势时长（毫秒），默认 300；越短越快。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { x1: number; y1: number; x2: number; y2: number; durationMs?: number };
				const data = await bridgePost<{ durationMs: number }>("/app/ui/swipe", {
					x1: p.x1,
					y1: p.y1,
					x2: p.x2,
					y2: p.y2,
					durationMs: p.durationMs,
				});
				return textResult(`已从 (${p.x1}, ${p.y1}) 滑动到 (${p.x2}, ${p.y2})，用时 ${data.durationMs}ms`, {
					durationMs: data.durationMs,
				});
			}),
	},
	{
		name: "android_screenshot",
		label: "截屏",
		description:
			"截取当前屏幕并以图片内容返回，模型可以直接看到画面。默认缩放到最长边 1280 像素的 JPEG。" +
			"需要「无障碍」能力、系统无障碍服务，以及 Android 11+。安全窗口（支付、密码界面）无法截取。",
		promptSnippet: "截取手机屏幕并把图片交给模型查看",
		promptGuidelines: [
			"当 android_ui_dump 的控件树读不出界面内容（自绘界面、游戏、视频、图片）时，改用 android_screenshot 直接看屏幕。",
		],
		parameters: Type.Object({
			format: Type.Optional(ScreenshotFormat),
			maxDimension: Type.Optional(Type.Number({ description: "最长边缩放上限（像素），默认 1280，范围 240–4096。" })),
			quality: Type.Optional(Type.Number({ description: "JPEG 质量 20–100，默认 82。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { format?: "jpeg" | "png"; maxDimension?: number; quality?: number };
				const data = await bridgePost<ScreenshotData>(
					"/app/screenshot",
					{ format: p.format, maxDimension: p.maxDimension, quality: p.quality },
					60_000,
				);
				const note =
					`截图 ${data.width}×${data.height}（${data.mimeType}，${formatSize(data.bytes)}）。` +
					"如果画面文字看不清，可以调大 maxDimension 或改用 png 重新截取。";
				return imageResult(data.base64, data.mimeType, note, {
					width: data.width,
					height: data.height,
					bytes: data.bytes,
				});
			}),
	},

	// ---------------------------------------------------------------- 应用 ----
	{
		name: "android_apps",
		label: "应用列表",
		description:
			"列出设备上已安装（默认只看可启动的）应用，可按名称或包名过滤。Android 11 起未声明 QUERY_ALL_PACKAGES 时只能看到系统允许可见的包。",
		promptSnippet: "列出已安装的 Android 应用（可按名称过滤）",
		promptGuidelines: [
			"调用 android_launch 之前先用 android_apps 查到精确的包名；android_launch 不接受模糊匹配。",
		],
		parameters: Type.Object({
			q: Type.Optional(Type.String({ description: "按名称或包名过滤的子串。" })),
			includeSystem: Type.Optional(Type.Boolean({ description: "是否包含系统应用，默认 false。" })),
			limit: Type.Optional(Type.Number({ description: "最多返回多少条，默认 60，上限 500。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { q?: string; includeSystem?: boolean; limit?: number };
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
				return textResult(truncateForModel(body, "应用列表") + note, { count: data.count });
			}),
	},
	{
		name: "android_launch",
		label: "启动应用",
		description: "按精确包名启动一个应用。包名从 android_apps 获取。",
		promptSnippet: "按包名启动一个 Android 应用",
		parameters: Type.Object({
			package: Type.String({ description: "精确包名，例如 org.telegram.messenger。" }),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { package: string };
				const data = await bridgePost<{ packageName: string; component: string }>("/app/apps/launch", {
					package: p.package,
				});
				return textResult(`已启动 ${data.packageName}${data.component ? `（${data.component}）` : ""}`, data as unknown as Record<string, unknown>);
			}),
	},
	{
		name: "android_stop_app",
		label: "结束应用",
		description:
			"结束一个用户安装的应用的后台进程。危险操作：会请求用户确认；系统应用、关键进程与 pi-android 自身一律拒绝。" +
			"只接受精确包名，不接受模糊匹配。",
		promptSnippet: "结束后台应用进程（危险操作，需用户确认）",
		parameters: Type.Object({
			package: Type.String({ description: "精确包名。" }),
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
		name: "android_notify",
		label: "发送通知",
		description: "发送一条系统通知，点击通知会回到 pi-android。适合长任务完成时告知用户。",
		promptSnippet: "发送一条 Android 系统通知",
		parameters: Type.Object({
			text: Type.String({ description: "通知正文。" }),
			title: Type.Optional(Type.String({ description: "通知标题，默认 pi。" })),
			id: Type.Optional(Type.Number({ description: "通知 id，用于覆盖同一通知；省略则自动生成。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text: string; title?: string; id?: number };
				const data = await bridgePost<{ id: number; channel: string }>("/app/notify", {
					text: p.text,
					title: p.title,
					id: p.id,
				});
				return textResult(`已发送通知（id=${data.id}）`, data as unknown as Record<string, unknown>);
			}),
	},
	{
		name: "android_toast",
		label: "短提示",
		description: "在屏幕上弹出一条 Toast 短提示（不进入通知栏）。适合「正在做什么」这类即时反馈。",
		promptSnippet: "弹出 Android Toast 短提示",
		parameters: Type.Object({
			text: Type.String({ description: "提示文本。" }),
			long: Type.Optional(Type.Boolean({ description: "是否使用长时长，默认 false。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text: string; long?: boolean };
				const data = await bridgePost<{ duration: string }>("/app/toast", { text: p.text, long: p.long === true });
				return textResult(`已显示提示（${data.duration}）`, data as unknown as Record<string, unknown>);
			}),
	},
	{
		name: "android_vibrate",
		label: "震动",
		description: "让手机震动。可指定时长或自定义节奏（毫秒数组，按「停-动-停-动」交替解释）。",
		promptSnippet: "让手机震动（可自定义节奏）",
		parameters: Type.Object({
			ms: Type.Optional(Type.Number({ description: "震动时长（毫秒），默认 200，上限 10000。" })),
			pattern: Type.Optional(
				Type.Array(Type.Number(), { description: "自定义节奏的毫秒数组，例如 [0, 120, 80, 120]；给了它则忽略 ms。" }),
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
		description:
			"把文本或链接交给系统分享面板，由用户选择目标应用。危险操作：等于以用户的名义把内容发出应用，会请求确认。",
		promptSnippet: "把文本或链接交给系统分享面板（危险操作，需用户确认）",
		parameters: Type.Object({
			text: Type.Optional(Type.String({ description: "分享的正文。" })),
			url: Type.Optional(Type.String({ description: "要附加的链接。" })),
			subject: Type.Optional(Type.String({ description: "标题/主题。" })),
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
		description:
			"用系统默认应用打开一个 URL（含自定义 scheme 的深链）。危险操作：会把控制权交给另一个应用，会请求确认。",
		promptSnippet: "用系统应用打开 URL 或深链（危险操作，需用户确认）",
		parameters: Type.Object({
			url: Type.String({ description: "完整 URL，必须带 scheme，例如 https://pi.dev 或 mailto:a@b.c。" }),
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
		name: "android_clipboard_get",
		label: "读剪贴板",
		description:
			"读取系统剪贴板的文本。注意 Android 10 起只有前台应用能读剪贴板，因此应用不在前台时读不到内容（会明确说明原因）。",
		promptSnippet: "读取系统剪贴板文本",
		parameters: Type.Object({}),
		run: async () =>
			guarded(async () => {
				const data = await bridgeGet<ClipboardData>("/app/clipboard");
				if (data.empty) {
					const note = data.note ? `\n${data.note}` : "";
					return textResult(`剪贴板为空或不可读。${note}`, { empty: true });
				}
				return textResult(
					`剪贴板内容（${data.text.length} 字符）：\n\n${truncateForModel(data.text, "剪贴板")}`,
					{ length: data.text.length },
				);
			}),
	},
	{
		name: "android_clipboard_set",
		label: "写剪贴板",
		description: "把文本写入系统剪贴板，用户随后可以在任意应用中粘贴。",
		promptSnippet: "把文本写入系统剪贴板",
		parameters: Type.Object({
			text: Type.String({ description: "要写入的文本。" }),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text: string };
				const data = await bridgePost<{ chars: number }>("/app/clipboard", { text: p.text });
				return textResult(`已写入剪贴板（${data.chars} 字符）`, data as unknown as Record<string, unknown>);
			}),
	},

	// -------------------------------------------------------------- 存储 ----
	{
		name: "android_export",
		label: "导出到 Download",
		description:
			"把文本或 base64 二进制内容写成公共 Download 目录里的文件，用户与其他应用都能看到。" +
			"API 29+ 走 MediaStore 不需要权限；Android 8/9 需要用户授予存储权限（应用会允许，并在「存储」卡里提供按钮）。" +
			"想要写进用户自己的目录（不受 Download 限制），用 android_files_write。" +
			"危险操作：内容离开应用沙箱，会请求确认。",
		promptSnippet: "把内容导出成 Download 目录里的文件（危险操作，需用户确认）",
		parameters: Type.Object({
			name: Type.String({ description: "文件名（不含路径），例如 report.md。" }),
			content: Type.Optional(Type.String({ description: "文本内容。" })),
			base64: Type.Optional(Type.String({ description: "二进制内容的 base64；与 content 二选一。" })),
			mimeType: Type.Optional(Type.String({ description: "MIME 类型，默认 text/plain。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { name: string; content?: string; base64?: string; mimeType?: string };
				const data = await bridgePost<ExportData>("/app/export", {
					name: p.name,
					content: p.content,
					base64: p.base64,
					mimeType: p.mimeType,
				});
				return textResult(
					`已导出到 ${data.location}/${data.displayName}（${formatSize(data.bytes)}）\nURI：${data.uri}`,
					data as unknown as Record<string, unknown>,
				);
			}),
	},
	{
		name: "android_import",
		label: "从 Download 读入",
		description:
			"按文件名读取公共 Download 目录里的文件。API 33+ 上只能读取本应用自己导出过的文件（系统不再给普通应用读别人文件的权限）；" +
			"API 30–32 需要用户授予存储权限。要读写用户自己指定的目录，用 android_files_read / android_files_write（SAF 授权，没有这个限制）。" +
			"危险操作：文件内容会进入模型上下文，会请求确认。",
		promptSnippet: "读取 Download 目录里的文件（危险操作，需用户确认）",
		parameters: Type.Object({
			name: Type.String({ description: "文件名（不含路径）。" }),
			maxBytes: Type.Optional(Type.Number({ description: "最多读取多少字节，默认 1MB，上限 4MB。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { name: string; maxBytes?: number };
				const data = await bridgePost<ImportData>("/app/import", { name: p.name, maxBytes: p.maxBytes });
				if (typeof data.text === "string") {
					return textResult(
						`已读入 ${data.displayName}（${formatSize(data.bytes)}${data.truncated ? "，已截断" : ""}）：\n\n` +
							truncateForModel(data.text, "文件内容"),
						{ bytes: data.bytes },
					);
				}
				return textResult(
					`已读入 ${data.displayName}（${formatSize(data.bytes)}），它是二进制文件。` +
						"如需查看，可以再调用一次并让用户确认，或改用 bash 工具在 guest 内处理。",
					{ bytes: data.bytes, binary: true },
				);
			}),
	},
	{
		name: "android_files_list",
		label: "已授权目录",
		description:
			"列出用户在「设置 → 设备能力 → 存储」授权给 Agent 的目录（SAF）及其内容。不传 path 时列出根目录名；" +
			"传 path 时路径写成「根目录名/相对路径」，例如 Documents/notes。这是读写用户自己的目录的方式，不受 Download 的限制。",
		promptSnippet: "列出用户授权（SAF）的目录与其中的文件",
		promptGuidelines: [
			"要读写用户自己的文件时，先用 android_files_list 看清有哪些已授权目录与它们的名字；路径必须以根目录名开头。",
		],
		parameters: Type.Object({
			path: Type.Optional(Type.String({ description: "「根目录名/相对路径」，省略则列出所有已授权根目录。" })),
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
		name: "android_files_read",
		label: "读授权目录里的文件",
		description:
			"读取用户已授权（SAF）目录里的一个文件，路径写成「根目录名/相对路径」。文本直接返回，二进制以 base64 返回。" +
			"危险操作：内容会进入模型上下文，会请求确认。",
		promptSnippet: "读取用户授权目录里的文件（危险操作，需用户确认）",
		parameters: Type.Object({
			path: Type.String({ description: "「根目录名/相对路径」，例如 Documents/notes/todo.md。" }),
			maxBytes: Type.Optional(Type.Number({ description: "最多读取多少字节，默认 1MB，上限 4MB。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { path: string; maxBytes?: number };
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
			}),
	},
	{
		name: "android_files_write",
		label: "写授权目录里的文件",
		description:
			"把文本或 base64 写进用户已授权（SAF）目录，路径写成「根目录名/相对路径」。不存在的中间目录会自动创建，同名文件会被覆盖。" +
			"危险操作：可能覆盖用户已有的文件，会请求确认。",
		promptSnippet: "写入用户授权目录里的文件（危险操作，需用户确认）",
		parameters: Type.Object({
			path: Type.String({ description: "「根目录名/相对路径」，例如 Documents/notes/todo.md。" }),
			content: Type.Optional(Type.String({ description: "文本内容。" })),
			base64: Type.Optional(Type.String({ description: "二进制内容的 base64；与 content 二选一。" })),
			mimeType: Type.Optional(Type.String({ description: "MIME 类型，默认 text/plain。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { path: string; content?: string; base64?: string; mimeType?: string };
				const data = await bridgePost<SafWriteData>("/app/files/write", {
					path: p.path,
					content: p.content,
					base64: p.base64,
					mimeType: p.mimeType,
				});
				return textResult(
					`已${data.created ? "创建" : "覆盖"} ${data.path}（${formatSize(data.bytes)}，${data.mimeType}）\nURI：${data.uri}`,
					data as unknown as Record<string, unknown>,
				);
			}),
	},

	// ------------------------------------------------- 位置 / 传感器 / 相机 ----
	{
		name: "android_location",
		label: "定位",
		description:
			"读取设备最近一次已知位置（经纬度、精度、provider、数据年龄）。需要「位置·传感器·相机」能力与系统定位权限。",
		promptSnippet: "读取设备最近一次已知位置",
		parameters: Type.Object({}),
		run: async () =>
			guarded(async () => {
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
					`位置：${data.latitude}, ${data.longitude}（精度约 ${Math.round(data.accuracyMeters)} 米，来自 ${data.provider}）${freshness}`,
					data as unknown as Record<string, unknown>,
				);
			}),
	},
	{
		name: "android_sensors",
		label: "传感器列表",
		description: "列出设备上的所有传感器（名称、类型、厂商、量程、分辨率）。",
		promptSnippet: "列出设备上的传感器",
		parameters: Type.Object({}),
		run: async () =>
			guarded(async () => {
				const data = await bridgeGet<SensorsData>("/app/sensors");
				const lines = data.sensors.map(
					(sensor) => `${sensor.typeName}（type=${sensor.type}）  ${sensor.name}  ${sensor.vendor}`,
				);
				return textResult(
					truncateForModel(`共 ${data.count} 个传感器：\n\n${lines.join("\n")}`, "传感器列表"),
					{ count: data.count },
				);
			}),
	},
	{
		name: "android_sensor",
		label: "读取传感器",
		description:
			"读取一次传感器采样值（例如 accelerometer、light、pressure）。先用 android_sensors 拿到 typeName；" +
			"有些传感器（如 step_counter）不适合单次采样。",
		promptSnippet: "读取一次传感器数值",
		parameters: Type.Object({
			typeName: Type.Optional(Type.String({ description: "传感器类型名，例如 accelerometer。" })),
			type: Type.Optional(Type.Number({ description: "传感器数字类型（Android Sensor.TYPE_*），与 typeName 二选一。" })),
			timeoutMs: Type.Optional(Type.Number({ description: "等待一次采样的超时（毫秒），默认 1500。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { typeName?: string; type?: number; timeoutMs?: number };
				const data = await bridgeGet<SensorSampleData>("/app/sensor", {
					typeName: p.typeName,
					type: p.type,
					timeoutMs: p.timeoutMs,
				});
				return textResult(
					`${data.name}（${data.typeName}）：${data.values.join(", ")}${data.units ? ` ${data.units}` : ""}`,
					data as unknown as Record<string, unknown>,
				);
			}),
	},
	{
		name: "android_battery",
		label: "电池",
		description: "读取电池电量百分比、充电状态与温度。",
		promptSnippet: "读取电池电量与充电状态",
		parameters: Type.Object({}),
		run: async () =>
			guarded(async () => {
				const data = await bridgeGet<{ percent: number; status: string; plugged: boolean; temperatureC: number }>(
					"/app/battery",
				);
				return textResult(
					`电量 ${data.percent}% · 状态 ${data.status} · ${data.plugged ? "已接电源" : "未接电源"} · ${data.temperatureC}°C`,
					data as unknown as Record<string, unknown>,
				);
			}),
	},
	{
		name: "android_torch",
		label: "手电筒",
		description: "打开或关闭手电筒（相机闪光灯）。某些 ROM 需要相机权限。",
		promptSnippet: "开关手电筒",
		parameters: Type.Object({
			on: Type.Boolean({ description: "true 打开，false 关闭。" }),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { on: boolean };
				const data = await bridgePost<{ on: boolean; cameraId: string }>("/app/torch", { on: p.on });
				return textResult(`手电筒已${data.on ? "打开" : "关闭"}（camera ${data.cameraId}）`, data as unknown as Record<string, unknown>);
			}),
	},

	// ---------------------------------------------------------------- 语音 ----
	{
		name: "android_tts",
		label: "语音朗读",
		description: "用系统 TTS 引擎朗读一段文本。适合用户不看屏幕时汇报结果。",
		promptSnippet: "用系统 TTS 朗读文本",
		parameters: Type.Object({
			text: Type.String({ description: "要朗读的文本。" }),
			language: Type.Optional(Type.String({ description: "BCP-47 语言标签，例如 zh-CN、en-US；默认系统语言。" })),
			rate: Type.Optional(Type.Number({ description: "语速 0.1–3.0，默认 1.0。" })),
			pitch: Type.Optional(Type.Number({ description: "音调 0.1–3.0，默认 1.0。" })),
		}),
		run: async (params) =>
			guarded(async () => {
				const p = params as { text: string; language?: string; rate?: number; pitch?: number };
				const data = await bridgePost<{ spoken: boolean; finished: boolean; language: string; chars: number }>(
					"/app/tts",
					{ text: p.text, language: p.language, rate: p.rate, pitch: p.pitch },
					60_000,
				);
				return textResult(
					`已朗读 ${data.chars} 个字符（${data.language}）${data.finished ? "，已读完" : "，仍在播放"}`,
					data as unknown as Record<string, unknown>,
				);
			}),
	},

	// --------------------------------------------------------------- Shell ----
	{
		name: "android_shell",
		label: "设备 Shell",
		description:
			"在设备上执行一条受策略守卫限制的 Shell 命令。默认关闭，需要用户显式开启「Shell」能力。" +
			"允许日常读写命令（getprop、dumpsys、pm list、logcat、ls、cat、cp、mv、rm、mkdir、sed、tar、grep、find、curl 等）；" +
			"未知命令一律拒绝。硬性禁用（与授权无关）：mount/umount、setenforce、setprop、settings put、mknod、dd、mkfs、pm clear/uninstall、su/sudo/magisk、/dev/block。" +
			"写入边界是用户选定的工作区：工作区之内（含它本身就是 DCIM、Pictures、Download、Android/data 之类目录时）不拦，工作区之外会被拒。" +
			"$(...) 与反引号默认拒绝，除非用户在「设置 → 设备能力 → Shell」打开「放宽模式」。" +
			"命令以当前 Shell 后端身份运行：装了且授权了 Shizuku 就是 ADB 身份（uid=2000），否则是应用自身身份（读不到其他应用与系统私有状态）。" +
			"要在工作区里跑构建、git、npm、rg 这类工具，请用 pi 的内置 bash —— 那是 guest 里的工作台，不受设备策略管辖。",
		promptSnippet: "执行受策略守卫限制的设备 Shell 命令（默认关闭；第一次会请求确认，可记住本会话）",
		promptGuidelines: [
			"用 android_shell 之前先想清楚这一步是不是真的需要设备级身份；在工作区里做文件操作，内置 bash 更快也更合适。",
			"android_shell 只写工作区之内；要写工作区之外的用户文件，用 android_files_write（需要用户先授权目录）。",
		],
		parameters: Type.Object({
			command: Type.String({ description: "要执行的命令。多个动作请拆成多次调用。" }),
			timeoutMs: Type.Optional(Type.Number({ description: "超时（毫秒），默认 15000，上限 60000。" })),
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
description: pi-android 设备环境说明与手机能力（android_ui_dump / android_tap / android_screenshot / android_apps 等）的用法。当任务需要在手机上操作界面、读取屏幕、截屏、查看应用、读写剪贴板、通知用户、定位或使用传感器时使用。
---

# pi-android 设备环境

你不是在电脑上，而是在一台 Android 手机里运行的 pi-android 客户端中。pi 内核跑在
proot 的 Ubuntu 用户态里，App 进程另外提供一组设备能力工具。

## 路径

| 位置 | 说明 |
|---|---|
| \`/root/.pi/agent\` | pi 的 agentDir：settings、extensions、skills、sessions、auth.json |
| \`/workspace\` | 当前工作区（App 私有存储，速度快，适合 build/npm） |
| \`/sdcard\`、\`/storage/emulated/0\` | 用户共享存储（FUSE，慢，适合放用户可见的文件） |
| \`/tmp\` | 可写的临时目录 |

## 手机能力工具

- 看屏幕：\`android_ui_dump\`（控件树，带编号）→ \`android_tap\`（按编号点按）→ 再 dump。
  文本/自绘界面用 \`android_screenshot\` 直接看图，\`android_input\` 写输入框。
- 应用：\`android_apps\` 列表 → \`android_launch\` 启动；\`android_stop_app\` 结束用户应用（危险，需确认）。
- 与用户交互：\`android_notify\`、\`android_toast\`、\`android_vibrate\`、\`android_tts\`。
- 数据：\`android_clipboard_get/set\`；\`android_export\` 写到公共 Download，\`android_import\` 读回；
  用户自己指定的目录用 \`android_files_list/read/write\`（需要他先在「设置 → 设备能力 → 存储」授权目录）。
- 设备状态：\`android_battery\`、\`android_location\`、\`android_sensors\`、\`android_torch\`。

## 规则

1. **能力默认关闭。** 用户没有授权的能力会返回 \`[DISABLED]\` 或 \`[NO_PERMISSION]\` 加一句中文原因。
   把这句话原样转述给用户，并告诉他去哪打开（「设置 → 设备能力」）。不要自己编造解释，也不要反复重试。
2. **危险操作会请求确认。** 结束应用、Shell、分享、打开链接、向输入框写入、跨沙箱读写文件、注入按键都会弹确认；
   确认框里有「同意并记住本次会话」——用户选了它，本会话内同类操作就不再问（结束会话后恢复）。用户拒绝时就停下来。
   没有确认通道时这些操作会被直接拒绝。
3. **点按要基于最新的屏幕。** \`android_tap\` 的编号来自最近一次 \`android_ui_dump\`；界面变化后要重新 dump。
4. **不要假装做过。** 工具失败就是失败；把工具的返回内容如实告诉用户。
5. **设备策略只管辖 android_* 工具。** 你在工作区里用内置 \`bash\` / read / write 跑 git、npm、rg、构建，
   不受任何设备策略限制——那是你的工作台。设备 Shell（\`android_shell\`）是另一回事：它有命令白名单、
   硬性禁用清单，而且只允许写工作区之内（工作区之外要写用户文件，用 \`android_files_write\`）。

## 与 Termux 的区别

pi 官方在 Termux 上的做法是调用 \`termux-open-url\`、\`termux-notification\` 之类的命令。
在这个 App 里请使用上面的 \`android_*\` 工具，它们不需要 Termux:API，并且会把失败原因说清楚。

## 设备 Shell 的实际能力

- 默认后端是应用自身身份，很多设备命令会因缺少权限而失败；用户装了并授权 **Shizuku** 之后，
  后端会变成 ADB 身份（uid=2000），\`input\`、\`pm\`、\`am\`、\`settings get\`、\`dumpsys\` 才真正可用。
  用 \`android_bridge_status\` 看当前后端，不要在报告里猜。
- 白名单、硬性禁用清单、写入边界都会写进工具的返回里（\`policy\` 字段）；被拒绝时先读它，不要重复重试同一条命令。
- \`$(...)\` 与反引号默认被拒，只有用户打开「放宽模式」才允许，而且那会同时放宽嵌套命令的检查。
`;
}

/** The system-prompt block appended for every turn. */
function environmentGuidance(): string {
	return [
		"",
		"## Android 设备环境（pi-android）",
		"",
		"你运行在 pi-android 客户端里：pi 内核在 proot 的 Ubuntu 中，App 额外提供了手机能力工具（全部以 android_ 开头）。",
		"",
		"- 工作区是 `/workspace`（快），用户共享存储是 `/sdcard`（慢，但用户可见）。",
		"- 需要在手机上操作界面时：先 `android_ui_dump` 看清屏幕，再用 `android_tap` / `android_input` / `android_swipe` 操作，`android_screenshot` 用来直接看画面。",
		"- 设备能力是按组授权的，默认只有「基础」组开启。被关闭的能力会返回 `[DISABLED]` 或 `[NO_PERMISSION]` 加一句中文原因；请把原因原样转述给用户，并指出「设置 → 设备能力」这个入口，不要自己猜测原因，也不要重复重试同一个调用。",
		"- 结束应用、Shell、分享、打开链接、向输入框写入、跨沙箱读写文件、注入按键属于危险操作：会请求用户确认（第一次确认时用户可以选择「同意并记住本次会话」），被拒绝时停下并告诉用户。",
		"- 设备策略**只**管辖 android_* 工具：工作区里的 git / npm / 构建用内置 bash 自由进行，不受设备策略影响。android_shell 有命令白名单、硬性禁用清单，并且只允许写工作区之内；要写用户指定的其他目录，请让用户先在「设置 → 设备能力 → 存储」授权目录，再用 android_files_write。",
		"- 想知道 Shell 后端是应用身份还是 Shizuku 的 ADB 身份（uid=2000），用 android_bridge_status 查，不要猜。",
		"- 想知道当前到底有哪些能力可用，调用 `android_bridge_status`。",
	].join("\n");
}

// ---------------------------------------------------------------------------
// extension entry point
// ---------------------------------------------------------------------------

export default function (pi: ExtensionAPI) {
	for (const tool of DEVICE_TOOLS) {
		const level = dangerLevelOf(tool.name) ?? "control";
		pi.registerTool({
			name: tool.name,
			label: tool.label,
			description: `${tool.description}（危险等级：${
				level === "read" ? "只读" : level === "control" ? "操作，无需确认" : "危险，需要用户确认"
			}）`,
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

	// Tell the agent what environment it woke up in, every turn. The sentinel
	// guards against double-appending if this extension is loaded twice.
	pi.on("before_agent_start", async (event) => {
		if (event.systemPrompt.includes("## Android 设备环境（pi-android）")) return undefined;
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

	// On session start, say something only when the user can act on it: a missing
	// bridge, or an accessibility service that is enabled but not running. In the
	// TUI mode the same probe also paints a footer status.
	pi.on("session_start", async (_event, ctx) => {
		if (!ctx.hasUI) return;
		try {
			const health = await bridgeHealth();
			if (health.accessibilityEnabledInSettings && !health.accessibilityRunning) {
				ctx.ui.notify(
					"设备桥：无障碍服务在系统设置里是启用的，但没有在运行。请到「设置 → 无障碍 → pi 设备桥」重新关闭再打开。",
					"warning",
				);
			}
			if (ctx.mode === "tui") {
				const usable = health.capabilities
					.filter((capability) => capability.usable)
					.map((capability) => capability.title);
				ctx.ui.setStatus(
					"pi-android-bridge",
					usable.length > 0 ? `设备桥：${usable.join("/")}` : "设备桥：无可用能力（到「设置 → 设备能力」开启）",
				);
			}
		} catch {
			ctx.ui.notify(
				"设备桥未连接：手机能力（android_* 工具）当前不可用。请打开 pi-android 的「设置 → 设备能力」确认状态为「已监听」。",
				"warning",
			);
			if (ctx.mode === "tui") ctx.ui.setStatus("pi-android-bridge", "设备桥：未连接");
		}
	});
}
