/**
 * The danger classification and shell pre-check, shared by the two extensions that
 * need it.
 *
 * It lives in its own module so the device extension (which documents the level in
 * each tool description) and the permission gate (which enforces it) cannot drift
 * apart. It imports nothing, so the gate loads even if the bridge client cannot be
 * resolved.
 *
 * ## Jurisdiction (read this before adding a tool name)
 *
 * This map — and therefore the permission gate — covers **only the device tools**
 * (`android_*`) and the Kotlin endpoints behind them. It deliberately says nothing
 * about pi's own tools.
 *
 * pi's built-in `bash`, `read`, `write`, `edit`, skills and every other extension's
 * tools run *inside the guest Linux environment*, and that environment **is pi's
 * workbench**: `git`, `npm test`, `rg`, a build. Gating those on "does this command
 * text look dangerous" would tie pi's hands and destroy the product — and it would
 * also be dishonest, because the guest can reach the device bridge's HTTP port
 * directly with the token it already has. The gate is a *confirmation* layer on the
 * model's device actions, not a sandbox for the guest; the parts that cannot be
 * bypassed from the guest are the Kotlin capability switch, the whitelist/hard-block
 * policy and the workspace write boundary.
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
 *                  files across the sandbox boundary. These are denied when there is
 *                  no UI to ask through, and the first approval may be remembered
 *                  for the rest of the session (see the permission gate).
 */

export type DangerLevel = "read" | "control" | "dangerous";

/** Every tool the device extension registers is namespaced with this prefix. */
export const DEVICE_TOOL_PREFIX = "android_";

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
	android_files_list: "read",

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
	android_keyevent: "dangerous",
	android_stop_app: "dangerous",
	android_share: "dangerous",
	android_open: "dangerous",
	android_export: "dangerous",
	android_import: "dangerous",
	android_files_read: "dangerous",
	android_files_write: "dangerous",
	android_shell: "dangerous",
};

export const DANGEROUS_TOOLS: readonly string[] = Object.entries(DANGER_LEVELS)
	.filter(([, level]) => level === "dangerous")
	.map(([name]) => name);

/**
 * True when a tool name belongs to the device layer — the gate's whole jurisdiction.
 *
 * Prefix-based on purpose: a *new* device tool that nobody remembered to add to
 * `DANGER_LEVELS` is still the gate's business (and is treated as dangerous below),
 * while `bash` / `read` / `write` / `edit` / skills / other extensions' tools are
 * never touched, whatever their arguments look like.
 */
export function isDeviceTool(toolName: string): boolean {
	return typeof toolName === "string" && toolName.startsWith(DEVICE_TOOL_PREFIX);
}

/**
 * The level of a device tool. An `android_*` name missing from the map is
 * `dangerous` — fail closed for the namespace we own, never for a name we do not.
 */
export function dangerLevelOf(toolName: string): DangerLevel | undefined {
	const known = DANGER_LEVELS[toolName];
	if (known !== undefined) return known;
	return isDeviceTool(toolName) ? "dangerous" : undefined;
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
			return `执行设备 Shell 命令：\n\n  ${truncate(asString(input, "command"), 400)}\n\n命令受白名单、硬性禁用清单与「工作区写入边界」限制；以当前 Shell 后端身份运行（装了 Shizuku 就是 ADB 级 uid=2000，否则是应用自身身份）。`;
		case "android_stop_app":
			return `结束应用：\n\n  ${asString(input, "package")}\n\n后台进程会被结束，未保存的内容可能丢失。`;
		case "android_share":
			return `把内容分享给其他应用：\n\n  ${truncate(asString(input, "text") || asString(input, "url"), 300)}\n\n这会打开系统分享面板，等于以你的名义把内容交出去。`;
		case "android_open":
			return `打开链接：\n\n  ${truncate(asString(input, "url"), 300)}\n\n系统会跳转到能处理它的应用（浏览器、邮件、其他 App）。`;
		case "android_input":
			return `向当前界面的输入框写入文本：\n\n  ${truncate(asString(input, "text"), 300)}${input.submit === true ? "\n\n并尝试提交（回车）。" : ""}\n\n文本会进入其他应用的输入框，可能被直接发送出去；直接写入被拒绝时会改用剪贴板粘贴（会替换剪贴板内容）。`;
		case "android_keyevent":
			return `注入原始按键：\n\n  ${asString(input, "keys")}${Number(input.repeat ?? 1) > 1 ? ` ×${input.repeat}` : ""}\n\n按键会送到当前焦点所在的应用，等同于你亲手按。`;
		case "android_export":
			return `把文件写入公共 Download 目录：\n\n  ${asString(input, "name")}\n\n写入后设备上的其他应用与用户都能看到这个文件。`;
		case "android_import":
			return `从公共 Download 目录读入文件：\n\n  ${asString(input, "name")}\n\n文件内容会进入模型上下文。`;
		case "android_files_read":
			return `读取用户已授权（SAF）目录里的文件：\n\n  ${asString(input, "path")}\n\n文件内容会进入模型上下文。`;
		case "android_files_write":
			return `写入用户已授权（SAF）目录：\n\n  ${asString(input, "path")}\n\n这个目录之外的一切不受影响，但目录之内的现有文件可能被覆盖。`;
		default:
			return `执行设备操作：${toolName}`;
	}
}

/**
 * The device shell's **hard blocklist**, mirrored from the Kotlin guard
 * (`DeviceShellGuard.hardBlocks`).
 *
 * The bridge enforces this too — that copy is the authoritative one, because the
 * app must not depend on an extension to stay safe. This one exists so an obviously
 * forbidden command is refused *without* bothering the user with a confirmation
 * dialog for something that can never be allowed. The two lists are deliberately
 * identical in *coverage* and deliberately short: each entry earns its place by
 * "needs privilege the app does not have, so it can only fail" or "irreversible
 * device damage if it ever ran". A path-based rule does **not** belong here — the
 * workspace is the write boundary, and it is enforced on the Kotlin side where it
 * cannot be bypassed.
 *
 * **One honest difference from the Kotlin list:** eleven of the twelve patterns
 * here carry the `i` flag, and the Kotlin `Regex`es are case-sensitive. So this
 * pre-filter also refuses `MOUNT` / `DD`-style spellings that the authoritative
 * guard would let through (where they simply fail: `MOUNT` is not a binary). That
 * asymmetry is left alone rather than "aligned": tightening the Kotlin guard would
 * refuse prose that merely mentions a token, and loosening this one would gain
 * nothing the guard does not already cover.
 */
export const FORBIDDEN_SHELL_PATTERNS: ReadonlyArray<{ pattern: RegExp; label: string }> = [
	{ pattern: /(^|[\s;&|()])mount(\s|$)/i, label: "挂载/卸载文件系统（需要 root，只会失败）" },
	{ pattern: /(^|[\s;&|()])umount(\s|$)/i, label: "挂载/卸载文件系统（需要 root，只会失败）" },
	{ pattern: /\bsetenforce\b/i, label: "修改 SELinux 状态（需要 root）" },
	{ pattern: /\bsetprop\b/i, label: "修改系统属性，或改设备设置（硬性策略）" },
	{ pattern: /\bsettings\s+(put|delete|reset)\b/i, label: "修改系统设置（硬性策略）" },
	{ pattern: /\bmknod\b/i, label: "创建设备节点（需要 root）" },
	{ pattern: /\bdd\b/i, label: "裸写入，可能覆盖分区或整盘数据" },
	{ pattern: /\bmkfs(\.[a-z0-9]+)?(\s|$)/i, label: "格式化文件系统（不可逆）" },
	{ pattern: /\bpm\s+(clear|uninstall)\b/i, label: "清除应用数据或卸载应用（不可逆）" },
	{ pattern: /\bcmd\s+package\s+(clear|uninstall)\b/i, label: "清除应用数据或卸载应用（不可逆）" },
	{ pattern: /(^|[\s;&|()])(su|sudo|magisk)(\s|$)/i, label: "提权（root）" },
	// Note: no leading \b here — there is no word boundary between a space and a
	// slash, so /\/dev\/block\b/ can never match. The harness caught that.
	{ pattern: /\/dev\/block/, label: "访问块设备（写入等于改分区）" },
];

/**
 * The syntax the 放宽模式 switch (「设置 → 设备能力 → Shell」) turns on.
 *
 * The switch is stored by the Kotlin side ([DeviceCapabilityStore.isShellSyntaxRelaxed])
 * and read back through `/app/health`, so this list and the Kotlin guard's
 * substitution check flip together. A mode only one side honoured would be worse
 * than no mode: the dialog would let something through that the guard then refuses,
 * with no way for the user to tell which side was wrong.
 */
export const RELAXED_ONLY_PATTERNS: ReadonlyArray<{ pattern: RegExp; label: string }> = [
	{ pattern: /`/, label: "命令替换（反引号）" },
	{ pattern: /\$\(/, label: "命令替换 $(...)" },
];

/**
 * @param relaxed the 放宽模式 flag, read from `/app/health`. When the bridge cannot
 *   be reached the caller passes `false` — fail closed.
 * @returns the label of the violated rule, or null when the command may proceed to
 *   the confirmation step (or straight to execution, for a remembered approval).
 */
export function shellPrecheck(command: string, relaxed = false): string | null {
	for (const { pattern, label } of FORBIDDEN_SHELL_PATTERNS) {
		if (pattern.test(command)) return label;
	}
	if (!relaxed) {
		for (const { pattern, label } of RELAXED_ONLY_PATTERNS) {
			if (pattern.test(command)) return label;
		}
	}
	return null;
}
