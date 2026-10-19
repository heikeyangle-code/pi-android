/**
 * The danger classification and shell pre-check, shared by the two extensions that
 * need it.
 *
 * It lives in its own module so the device extension (which registers the tools) and
 * the permission gate (which enforces the level) cannot drift apart: every tool the
 * extension registers must have a level here, and every level must name a registered
 * tool (`ShellPolicyMirrorCheck`). It imports nothing, so the gate loads even if the
 * bridge client cannot be resolved.
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
 * bypassed from the guest are the Kotlin capability switch and the Kotlin guard's
 * hard-block list (currently empty, so in practice: the capability switch alone).
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
 *
 * A tool that merged several capabilities carries the **highest** level of its
 * branches, because the gate classifies a call by tool name: `android_app` lists apps
 * (read), launches them and stops them (control), so it is control;
 * `android_comm` reads the clipboard (read) and writes it (control), so it is control.
 * The per-call exceptions (`action="stop"`, the shell's irreversible commands) are not
 * levels at all — they are [approvalRuleFor]'s rules, checked per call.
 */

export type DangerLevel = "read" | "control" | "dangerous";

/** Every tool the device extension registers is namespaced with this prefix. */
export const DEVICE_TOOL_PREFIX = "android_";

export const DANGER_LEVELS: Record<string, DangerLevel> = {
	// --- v2 的 7 个工具 ---------------------------------------------------------
	//
	// 合并后的工具若按「各分支里最高的那一级」记，`android_screen` / `android_app` /
	// `android_files` / `android_comm` 都会变成 `dangerous` —— 而它们绝大多数调用是读屏、
	// 点一下、存个文件。每次都弹窗等于把确认变成条件反射，真正的确认也就不值钱了。
	//
	// 所以工具级的 `dangerous` 只剩 `android_shell` 一个；「按调用才危险」的分支
	// （`android_app` 的 `action="stop"`、shell 里那 7 条不可逆命令）由
	// [approvalRuleFor] 单独收窄 —— 判据是这一次调用，不是这个工具。
	android_status: "read",
	android_screen: "control",
	android_app: "control",
	android_files: "control",
	android_comm: "control",
	android_device: "control",
	android_shell: "dangerous",
};

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
			return `执行设备 Shell 命令：\n\n  ${truncate(asString(input, "command"), 400)}\n\n命令本身不做任何过滤，成败取决于身份：装了 Shizuku 是 ADB 级 uid=2000，否则是应用自身身份（pm、input、dumpsys、screencap 会以 SecurityException 失败）。`;
		case "android_app":
			return `结束应用：\n\n  ${asString(input, "package")}\n\n后台进程会被结束，未保存的内容可能丢失。`;
		default:
			return `执行设备操作：${toolName}`;
	}
}

/**
 * The device shell's **hard blocklist**, mirrored from the Kotlin guard
 * (`DeviceShellGuard.hardBlocks`).
 *
 * **It is empty, and deliberately so.** The device shell *asks* now instead of
 * refusing: an irreversible command is put in front of the user through
 * `needsApproval` below and everything else simply runs. The Kotlin guard stopped
 * refusing commands by name as well — no whitelist, no write boundary, no
 * substitution rule (`DeviceShellGuard.inspect`) — so this list is not a mirror of
 * an enforcement layer any more, only of what the two sides still agree is worth a
 * question. A pre-filter only earns its place by "this can never be allowed"; there
 * is no such command left, so there is no rule left.
 *
 * The Kotlin list is empty with it. The two are compared mechanically by the
 * `shell-policy-mirror` bare-JVM harness
 * (`app/src/test/kotlin/app/pi/bridge/ShellPolicyMirrorCheck.kt`, run by
 * `tools/run-app-pure-checks.sh`), which fails when either side gains, loses or
 * renames a rule — so a rule added back on one side alone is caught.
 *
 * Two shapes that still do not belong here if the list is ever repopulated: a rule
 * for something that can only fail (that is a hard block, not a question), and a
 * pattern loose enough to fire on an unrelated command — `/\bdd\b/i` matched
 * `dd.txt`, and a question that fires on the wrong thing turns the confirmation into
 * a reflex the user stops reading.
 */
export const FORBIDDEN_SHELL_PATTERNS: ReadonlyArray<{ pattern: RegExp; label: string }> = [];


/**
 * The shell commands the user is asked to approve: the **irreversible** ones, and
 * only those.
 *
 * This is the whole of the device shell's danger list — `FORBIDDEN_SHELL_PATTERNS`
 * above refuses nothing any more — so the judgement "should the user see this
 * before it runs?" lives here. Each entry earns its place by "the damage cannot be
 * undone":
 *
 *  - `dd` writes over whatever it is pointed at, up to a whole block device;
 *  - `mkfs` formats a filesystem;
 *  - a path under `/dev/block` reaches the partitions behind the filesystems;
 *  - `pm clear` / `cmd package clear` delete an app's entire data directory;
 *  - `pm uninstall` / `cmd package uninstall` remove the app *and* that directory.
 *
 *
 * Each entry carries an `id` as well as a `label`, and the two do different jobs: the
 * `label` is the consequence sentence shown in the dialog, while the `id` is what an
 * 「同意并记住本次会话」 is remembered under. Two entries may share an `id` when they
 * are the same rule reached two ways — `pm clear` and `cmd package clear` are one
 * consequence, not two — and that is deliberate: remembering it should cover both,
 * and only both.
 *
 * The `dd` pattern requires whitespace after the command word on purpose. `/\bdd\b/i`
 * also matched `dd.txt` and `ls dd`, so the dialog asked about a file name; a
 * confirmation that fires on the wrong thing is how a confirmation becomes a reflex.
 */
export const NEEDS_APPROVAL_SHELL_PATTERNS: ReadonlyArray<{
	pattern: RegExp;
	id: string;
	label: string;
}> = [
	{
		pattern: /(^|[\s;&|()])dd\s/,
		id: "dd 裸写入",
		label: "裸写入：目标若指向分区或整盘会被直接覆盖，数据无法恢复",
	},
	{
		pattern: /\bmkfs(\.[a-z0-9]+)?(\s|$)/i,
		id: "格式化分区",
		label: "格式化文件系统（不可逆，该分区上的数据全部丢失）",
	},
	{
		pattern: /\/dev\/block/,
		id: "块设备",
		label: "访问块设备：写入等于改分区表，可能让设备无法启动",
	},
	{
		pattern: /\bpm\s+clear\b/i,
		id: "清除应用数据",
		label: "清除应用数据（不可逆：该应用的全部数据会被删光）",
	},
	{
		pattern: /\bcmd\s+package\s+clear\b/i,
		id: "清除应用数据",
		label: "清除应用数据（不可逆：该应用的全部数据会被删光）",
	},
	{
		pattern: /\bpm\s+uninstall\b/i,
		id: "卸载应用",
		label: "卸载应用（不可逆：应用与它的全部数据一起消失）",
	},
	{
		pattern: /\bcmd\s+package\s+uninstall\b/i,
		id: "卸载应用",
		label: "卸载应用（不可逆：应用与它的全部数据一起消失）",
	},
];

/** One rule that makes a call worth asking about. */
export interface ApprovalRule {
	/** Short, human-readable, and stable — this is what a remembered grant is keyed by. */
	readonly id: string;
	/** The consequence sentence shown in the dialog. */
	readonly label: string;
}

/** `android_app(action="stop")` 的规则：它不可逆，而且和 shell 的命令规则不是一回事。 */
export const APP_STOP_RULE: ApprovalRule = {
	id: "结束应用",
	label: "结束应用（后台进程会被杀掉，未保存的内容可能丢失）",
};

/**
 * 这一次调用值不值得问用户。
 *
 * 判据是**调用**而不是工具：`android_shell` 里 99% 的命令不值得问，`android_app` 的
 * `list` / `launch` 也不值得问；真正需要过目的是 shell 里那 7 条不可逆命令，和
 * `android_app(action="stop")`。返回 null = 不问。
 */
export function approvalRuleFor(toolName: string, input: Record<string, unknown>): ApprovalRule | null {
	if (toolName === "android_shell") {
		return needsApproval(typeof input.command === "string" ? input.command : "");
	}
	if (toolName === "android_app" && input.action === "stop") return APP_STOP_RULE;
	return null;
}

/**
 * @returns the rule the command matched — its consequence sentence for the dialog and
 *   the id a remembered grant is keyed by — or null when the command may run without a
 *   confirmation.
 */
export function needsApproval(command: string): ApprovalRule | null {
	for (const rule of NEEDS_APPROVAL_SHELL_PATTERNS) {
		if (rule.pattern.test(command)) return { id: rule.id, label: rule.label };
	}
	return null;
}

