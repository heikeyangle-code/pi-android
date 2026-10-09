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
 * A tool that merged two capabilities carries the **highest** level of its branches,
 * because the gate classifies a call by tool name: `android_app` lists apps (read) and
 * launches them (control), so it is control; `android_clipboard` reads (read) and
 * writes (control), so it is control. The merge is recorded as D32 in
 * `design/ui-refactor/07-construction-decisions.md`.
 */

export type DangerLevel = "read" | "control" | "dangerous";

/** Every tool the device extension registers is namespaced with this prefix. */
export const DEVICE_TOOL_PREFIX = "android_";

export const DANGER_LEVELS: Record<string, DangerLevel> = {
	// --- direct：常驻，进提示词 ----------------------------------------------
	//
	// 合并后的工具若按「各分支里最高的那一级」记，`android_ui` / `android_io` /
	// `android_fs` / `android_app` 都会变成 `dangerous` —— 而它们绝大多数调用是
	// 读屏、点一下、存个文件。每次都弹窗等于把确认变成条件反射，真正的确认也
	// 就不值钱了。所以只有 `android_shell` 留在 `dangerous`，其余四个是 `control`；
	// 需要用户过目的不可逆命令由 `needsApproval` 在 shell 那一支单独收窄。
	android_status: "read",
	android_ui: "control",
	android_app: "control",
	android_io: "control",
	android_fs: "control",
	android_shell: "dangerous",

	// --- 细粒度工具（合并入口展开的那些）--------------------------------------------------
	android_bridge_status: "read",
	android_ui_dump: "read",
	android_screenshot: "read",
	android_device_state: "read",
	android_files_list: "read",

	android_tap: "control",
	android_key: "control",
	android_swipe: "control",
	android_say: "control",
	android_vibrate: "control",
	android_clipboard: "control",
	android_torch: "control",
	android_input: "control",
	android_keyevent: "control",
	android_stop_app: "control",
	android_share: "control",
	android_open: "control",
	android_download: "control",
	android_files: "control",

	// 波2 的六个新工具（输入法 / 设备管理员 / 通知监听 / 自动化 / 本地 VPN / 投屏）按上面
	// 同一条规则记 `control`：每一个都是多 action 的合并体，既有「看一眼状态」的分支，也有
	// 改设备策略的分支；整块记成 `dangerous` 会让每次读通知都弹一次确认，确认也就贬值了。
	// 其中不可逆的分支（`android_admin` 的 wipe / reboot）由 App 侧端点在执行前取人工确认 ——
	// `DeviceAdmin.wipeData` 的 KDoc 明确要求波2 这么做；闸门这一层仍然只有一条判据：
	// 只有 `android_shell` 是 `dangerous`。
	android_ime: "control",
	android_admin: "control",
	android_notify: "control",
	android_automation: "control",
	android_net: "control",
	android_capture: "control",
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
			return `执行设备 Shell 命令：\n\n  ${truncate(asString(input, "command"), 400)}\n\n命令本身不做任何过滤，成败取决于身份：装了 Shizuku 是 ADB 级 uid=2000，否则是应用自身身份（pm、input、dumpsys、screencap 会以 SecurityException 失败）。`;
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
		case "android_download":
			return asString(input, "op") === "read"
				? `从公共 Download 目录读入文件：\n\n  ${asString(input, "name")}\n\n文件内容会进入模型上下文。`
				: `把文件写入公共 Download 目录：\n\n  ${asString(input, "name")}\n\n文件会离开沙箱，设备上的其他应用与用户都能看到。`;
		case "android_files":
			return asString(input, "op") === "read"
				? `读取用户已授权（SAF）目录里的文件：\n\n  ${asString(input, "path")}\n\n文件内容会进入模型上下文。`
				: `写入用户已授权（SAF）目录：\n\n  ${asString(input, "path")}\n\n这个目录之外的一切不受影响，但目录之内的现有文件可能被覆盖。`;
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
 * The syntax the 放宽模式 switch (「设置 → 设备能力 → Shell」) used to turn on.
 *
 * **No longer consulted by anything.** The Kotlin guard stopped checking command
 * substitution, so `shellPrecheck` below ignores this list, and the switch itself has
 * no effect (the UI no longer draws it). Kept only so the file still records what the
 * mode was — see the class comment on `DeviceShellGuard` for what replaced it.
 */
export const RELAXED_ONLY_PATTERNS: ReadonlyArray<{ pattern: RegExp; label: string }> = [
	{ pattern: /`/, label: "命令替换（反引号）" },
	{ pattern: /\$\(/, label: "命令替换 $(...)" },
];

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
 *  - `pm clear` / `cmd package clear` delete an app's entire data directory.
 *
 * The `dd` pattern requires whitespace after the command word on purpose. `/\bdd\b/i`
 * also matched `dd.txt` and `ls dd`, so the dialog asked about a file name; a
 * confirmation that fires on the wrong thing is how a confirmation becomes a reflex.
 */
export const NEEDS_APPROVAL_SHELL_PATTERNS: ReadonlyArray<{ pattern: RegExp; label: string }> = [
	{ pattern: /(^|[\s;&|()])dd\s/, label: "裸写入：目标若指向分区或整盘会被直接覆盖，数据无法恢复" },
	{ pattern: /\bmkfs(\.[a-z0-9]+)?(\s|$)/i, label: "格式化文件系统（不可逆，该分区上的数据全部丢失）" },
	{ pattern: /\/dev\/block/, label: "访问块设备：写入等于改分区表，可能让设备无法启动" },
	{ pattern: /\bpm\s+clear\b/i, label: "清除应用数据（不可逆：该应用的全部数据会被删光）" },
	{ pattern: /\bcmd\s+package\s+clear\b/i, label: "清除应用数据（不可逆：该应用的全部数据会被删光）" },
];

/**
 * @returns the label of the rule the command matched — the consequence to show the
 *   user — or null when the command may run without a confirmation.
 */
export function needsApproval(command: string): string | null {
	for (const { pattern, label } of NEEDS_APPROVAL_SHELL_PATTERNS) {
		if (pattern.test(command)) return label;
	}
	return null;
}

/**
 * @param relaxed the 放宽模式 flag, read from `/app/health`. When the bridge cannot
 *   be reached the caller passes `false` — fail closed.
 * @returns the label of the violated rule, or null when the command may proceed to
 *   the confirmation step (or straight to execution, for a remembered approval).
 */
/**
 * The device shell's "can never be allowed" pre-check.
 *
 * Both lists this used to consult are gone from the decision:
 *
 *  - `FORBIDDEN_SHELL_PATTERNS` is empty — clearing the blacklist was a deliberate
 *    user request, and it is mirrored by `DeviceShellGuard.hardBlocks`, also empty.
 *  - The 放宽模式 branch is gone with the substitution rule it guarded: the Kotlin
 *    guard stopped refusing `$(...)` and backticks too, along with the whitelist and
 *    the write boundary (see the class comment on `DeviceShellGuard`). Nothing on the
 *    app side refuses a shell command by its text any more, so a gate that did would
 *    be refusing something the bridge would have run.
 *
 * Kept as a function rather than deleted so the gate does not have to know which
 * side of that change it was built against. The only thing left in front of a shell
 * command is `needsApproval`'s irreversible short list.
 *
 * @param relaxed 放宽模式, read from `/app/health`. Ignored now.
 * @returns always `null` — nothing is refused before the confirmation step.
 */
export function shellPrecheck(command: string, relaxed = false): string | null {
	// `relaxed` 不再参与判定（替换检查在 App 侧也取消了）；`void` 一句是为了让
	// "参数未使用"这件事在代码里显式，而不是靠一个下划线命名去暗示。
	void relaxed;
	for (const { pattern, label } of FORBIDDEN_SHELL_PATTERNS) {
		if (pattern.test(command)) return label;
	}
	return null;
}
