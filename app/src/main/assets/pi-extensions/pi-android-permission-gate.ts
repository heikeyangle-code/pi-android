/**
 * pi-android permission gate.
 *
 * pi ships no built-in approval system: the documented mechanism is an extension
 * that watches `tool_call` and returns `{ block: true, reason }`. This is the
 * device-bridge instance of that pattern, modelled on
 * `examples/extensions/permission-gate.ts`, and it exists because a phone agent is
 * categorically more dangerous than a desktop one — a wrong `android_stop_app` or
 * `android_shell` can lose work or hand the device to a stranger.
 *
 * Three properties are deliberate:
 *
 *  1. **Fail closed without a UI.** With `ctx.hasUI === false` (pi's print/json
 *     modes) a dangerous device action is *blocked*, never silently allowed. The
 *     same is true if the confirmation dialog throws.
 *  2. **A second, independent pre-check for shell.** A command that can never be
 *     allowed — block devices, SELinux, `setprop`, `settings put`, mount, clearing
 *     app data, privilege escalation, command substitution — is refused outright
 *     rather than offered to the user as a choice. The native guard enforces the
 *     same list; this copy exists so the user is not asked to approve something
 *     that will be rejected anyway.
 *  3. **A confirmation that names the act.** The dialog shows the exact command,
 *     URL, package or text, plus how many times this session has already approved
 *     this tool, so approval stays meaningful instead of becoming a reflex.
 */

import type { ExtensionAPI, ToolCallEvent } from "@earendil-works/pi-coding-agent";
import { DANGEROUS_TOOLS, dangerLevelOf, describeDangerousCall, shellPrecheck } from "./pi-android-bridge/danger";

/** How many times each dangerous tool has been approved in this process. */
const approvals = new Map<string, number>();

/** The install notice is worth showing once per process, not once per session. */
let announced = false;

export default function (pi: ExtensionAPI) {
	pi.on("tool_call", async (event: ToolCallEvent, ctx) => {
		const toolName = event.toolName;
		const level = dangerLevelOf(toolName);
		if (level !== "dangerous") return undefined;

		const input = event.input as Record<string, unknown>;

		// (2) Hard policy refusals, checked before anyone is asked to decide.
		if (toolName === "android_shell") {
			const command = typeof input.command === "string" ? input.command : "";
			if (command.trim().length === 0) {
				return { block: true, reason: "android_shell 需要一条命令，但它拿到的是空字符串。" };
			}
			const violation = shellPrecheck(command);
			if (violation !== null) {
				return {
					block: true,
					reason:
						`设备策略拒绝这条 Shell 命令（${violation}），它与用户授权无关，无法放行：\n\n  ${command}\n\n` +
						"请改用只读查询，或直接告诉用户这一步无法通过设备桥完成。",
				};
			}
		}

		// (1) No UI → no consent → no action.
		if (!ctx.hasUI) {
			return {
				block: true,
				reason:
					`危险设备操作「${toolName}」在没有确认通道（ctx.hasUI=false）时默认拒绝。` +
					"请在 pi-android 的对话或终端界面中重试，让用户自己决定是否授权。",
			};
		}

		const description = describeDangerousCall(toolName, input);
		const previous = approvals.get(toolName) ?? 0;
		const history =
			previous > 0 ? `\n\n（本会话已经批准过 ${previous} 次「${toolName}」。）` : "";

		let approved = false;
		try {
			approved = await ctx.ui.confirm(
				`⚠️ pi-android 请求执行危险设备操作：${toolName}`,
				`${description}${history}\n\n允许这一次吗？`,
			);
		} catch (error) {
			return {
				block: true,
				reason:
					`确认对话框不可用（${error instanceof Error ? error.message : String(error)}），` +
					`因此危险设备操作「${toolName}」被拒绝。`,
			};
		}

		if (!approved) {
			return {
				block: true,
				reason:
					`用户拒绝了这个设备操作：${toolName}。` +
					"请停下来，把用户拒绝这件事告诉他，不要重试，也不要用别的工具绕过。",
			};
		}

		approvals.set(toolName, previous + 1);
		return undefined;
	});

	// A visible, auditable marker of what this extension is doing, so a user who
	// reads pi's startup output knows the gate is installed rather than assuming it.
	pi.on("session_start", async (_event, ctx) => {
		if (!ctx.hasUI || announced) return;
		announced = true;
		ctx.ui.notify(
			`设备审批已启用：${DANGEROUS_TOOLS.length} 个危险手机操作会逐次请求确认` +
				"（结束应用、Shell、分享、打开链接、向输入框写入、跨沙箱读写文件）。",
			"info",
		);
	});
}
