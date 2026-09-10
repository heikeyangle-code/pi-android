/**
 * The danger classification, shared by the two extensions that need it.
 *
 * It lives in its own module so the device extension (which documents the level in
 * each tool description) and the permission gate (which enforces it) cannot drift
 * apart. It imports nothing, so the gate loads even if the bridge client cannot be
 * resolved.
 *
 * Three levels, and the reasoning for each:
 *
 *  - `read`      — inspects state. No side effect a user would notice.
 *  - `control`   — acts on the device, but inside the app's own sandbox or with an
 *                  effect the user sees and can undo (a toast, a vibration, a
 *                  launch, typing into a focused field). Asking for confirmation on
 *                  every tap would make the automation useless, which is how
 *                  permission fatigue turns into a rubber stamp.
 *  - `dangerous` — irreversible, or acts as the user toward other apps: stopping a
 *                  running app, sending arbitrary shell, sharing, opening an
 *                  arbitrary intent, typing into *another* app's field, and moving
 *                  files across the sandbox boundary. These require an explicit
 *                  `ctx.ui.confirm` on every call, and are **denied** when there is
 *                  no UI to ask through.
 */

export type DangerLevel = "read" | "control" | "dangerous";

export const DANGER_LEVELS: Record<string, DangerLevel> = {
	android_bridge_status: "read",
	android_ui_dump: "read",
	android_screenshot: "read",
	android_apps: "read",
	android_clipboard_get: "read",
	android_location: "read",
	android_sensors: "read",
	android_sensor: "read",
	android_battery: "read",

	android_tap: "control",
	android_key: "control",
	android_swipe: "control",
	android_toast: "control",
	android_vibrate: "control",
	android_clipboard_set: "control",
	android_notify: "control",
	android_launch: "control",
	android_torch: "control",
	android_tts: "control",

	android_input: "dangerous",
	android_stop_app: "dangerous",
	android_share: "dangerous",
	android_open: "dangerous",
	android_export: "dangerous",
	android_import: "dangerous",
	android_shell: "dangerous",
};

export const DANGEROUS_TOOLS: readonly string[] = Object.entries(DANGER_LEVELS)
	.filter(([, level]) => level === "dangerous")
	.map(([name]) => name);

export function dangerLevelOf(toolName: string): DangerLevel | undefined {
	return DANGER_LEVELS[toolName];
}

function asString(input: Record<string, unknown>, key: string): string {
	const value = input[key];
	return typeof value === "string" ? value : "";
}

function truncate(value: string, max: number): string {
	if (value.length <= max) return value;
	return `${value.slice(0, max)}…（共 ${value.length} 字符）`;
}

/**
 * A sentence a human can actually judge: the exact command, URL, package or text
 * that is about to leave the app's sandbox.
 */
export function describeDangerousCall(toolName: string, input: Record<string, unknown>): string {
	switch (toolName) {
		case "android_shell":
			return `执行设备 Shell 命令：\n\n  ${truncate(asString(input, "command"), 400)}\n\n命令会以 pi-android 自身的应用身份运行（不是 root），并受白名单与硬性禁用规则限制。`;
		case "android_stop_app":
			return `结束应用：\n\n  ${asString(input, "package")}\n\n后台进程会被结束，未保存的内容可能丢失。`;
		case "android_share":
			return `把内容分享给其他应用：\n\n  ${truncate(asString(input, "text") || asString(input, "url"), 300)}\n\n这会打开系统分享面板，等于以你的名义把内容交出去。`;
		case "android_open":
			return `打开链接：\n\n  ${truncate(asString(input, "url"), 300)}\n\n系统会跳转到能处理它的应用（浏览器、邮件、其他 App）。`;
		case "android_input":
			return `向当前界面的输入框写入文本：\n\n  ${truncate(asString(input, "text"), 300)}${input.submit === true ? "\n\n并尝试提交（回车）。" : ""}\n\n文本会进入其他应用的输入框，可能被直接发送出去。`;
		case "android_export":
			return `把文件写入公共 Download 目录：\n\n  ${asString(input, "name")}\n\n写入后设备上的其他应用与用户都能看到这个文件。`;
		case "android_import":
			return `从公共 Download 目录读入文件：\n\n  ${asString(input, "name")}\n\n文件内容会进入模型上下文。`;
		default:
			return `执行设备操作：${toolName}`;
	}
}

/**
 * A second copy of the native policy, on the guest side.
 *
 * The bridge enforces this too — that copy is the authoritative one, because the
 * app must not depend on an extension to stay safe. This one exists so an obviously
 * forbidden command is refused *without* bothering the user with a confirmation
 * dialog for something that can never be allowed.
 */
export const FORBIDDEN_SHELL_PATTERNS: ReadonlyArray<{ pattern: RegExp; label: string }> = [
	{ pattern: /(^|[\s;&|])mount(\s|$)/i, label: "挂载/卸载文件系统" },
	{ pattern: /(^|[\s;&|])umount(\s|$)/i, label: "挂载/卸载文件系统" },
	{ pattern: /\bsetenforce\b/i, label: "修改 SELinux 状态" },
	{ pattern: /\/sys\/fs\/selinux/i, label: "访问 SELinux 控制面" },
	{ pattern: /\bsetprop\b/i, label: "修改系统属性" },
	{ pattern: /\bsettings\s+(put|delete|reset)\b/i, label: "修改系统设置" },
	{ pattern: /\bpm\s+(clear|uninstall|disable|enable)\b/i, label: "清除/卸载/禁用应用" },
	{ pattern: /\bcmd\s+package\s+(clear|uninstall|disable|enable)\b/i, label: "清除/卸载/禁用应用" },
	{ pattern: /\bmknod\b/i, label: "创建设备节点" },
	{ pattern: /\bdd\b/i, label: "裸块写入" },
	{ pattern: /\bmkfs(\.|\s|$)/i, label: "格式化分区" },
	{ pattern: /\bfastboot\b/i, label: "刷机" },
	{ pattern: /\breboot\b/i, label: "重启设备" },
	{ pattern: /\bshutdown\b/i, label: "关机" },
	{ pattern: /\b(insmod|rmmod|modprobe)\b/i, label: "内核模块操作" },
	{ pattern: /(^|[\s;&|])(su|sudo|magisk)\b/i, label: "提权" },
	{ pattern: /(^|[\s;&|])eval\b/i, label: "动态求值" },
	{ pattern: /\bkillall\b/i, label: "批量结束进程" },
	{ pattern: /`/, label: "命令替换" },
	{ pattern: /\$\(/, label: "命令替换" },
	// Note: no leading \b here — there is no word boundary between a space and a
	// slash, so /\b\/dev\/block\b/ can never match. The harness caught that.
	{ pattern: /\/dev\/block\b/i, label: "访问块设备" },
	{ pattern: /\/dev\/mem\b/i, label: "访问物理内存" },
];

export function shellPrecheck(command: string): string | null {
	for (const { pattern, label } of FORBIDDEN_SHELL_PATTERNS) {
		if (pattern.test(command)) return label;
	}
	return null;
}
