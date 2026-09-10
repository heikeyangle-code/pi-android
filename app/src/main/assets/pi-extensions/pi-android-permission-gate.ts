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
 * ## Jurisdiction: device tools only — this is a hard rule, not a default
 *
 * The handler returns `undefined` immediately unless the tool name is in the
 * `android_*` namespace (`isDeviceTool`). pi's own `bash`, `read`, `write`, `edit`,
 * skills, and every other extension's tools are **never** inspected, whatever their
 * arguments look like. `bash` running `rm -rf build` inside the guest workspace is
 * pi doing its job in its own workbench; blocking it would not make the device
 * safer, it would just break the agent. The same rule applies with 放宽模式 on:
 * relaxing the device shell's syntax must not reach into the guest environment.
 *
 * What that leaves, honestly:
 *
 *  - the gate is a *confirmation* layer over the model's device actions. The guest
 *    holds the bridge token and could call the HTTP endpoints from `bash` without
 *    ever passing through here, so this layer is a speed bump against a bad
 *    decision, not a security boundary;
 *  - the parts that cannot be bypassed from the guest are in the app's process:
 *    the capability opt-in, the shell whitelist / hard blocklist, and the workspace
 *    write boundary. Those are what the user's consent actually rests on.
 *
 * ## The three properties that matter here
 *
 *  1. **Fail closed without a UI.** With `ctx.hasUI === false` (pi's print/json
 *     modes) a dangerous device action is *blocked*, never silently allowed. The
 *     same is true if the confirmation dialog throws or is dismissed.
 *  2. **Approval is remembered for the session, and only the session.** The dialog
 *     is a three-way choice — once / remember for this session / deny — and the
 *     remembered set is cleared on every `session_start`. That kills confirmation
 *     fatigue (which turns real approvals into a reflex) without turning into a
 *     permanent grant; a new session asks again.
 *  3. **A second, independent pre-check for shell, sharing one switch with Kotlin.**
 *     A command that can never be allowed is refused outright instead of being
 *     offered to the user. The list is mirrored from `DeviceShellGuard.hardBlocks`,
 *     and the 放宽模式 flag is read from `/app/health` — the same boolean the Kotlin
 *     guard enforces — so the dialog and the guard cannot disagree about `$(...)`.
 */

import type { ExtensionAPI, ToolCallEvent } from "@earendil-works/pi-coding-agent";
import { bridgeHealth, reportGate } from "./pi-android-bridge/client";
import {
	DANGEROUS_TOOLS,
	dangerLevelOf,
	describeDangerousCall,
	isDeviceTool,
	shellPrecheck,
} from "./pi-android-bridge/danger";

/** The three answers the confirmation dialog offers. */
const CHOICE_ONCE = "仅允许这一次";
const CHOICE_REMEMBER = "同意并记住本次会话";
const CHOICE_DENY = "拒绝";

/** Tools the user approved; cleared on every session start. */
const sessionGrants = new Set<string>();

/** How many times each dangerous tool has been approved in this process. */
const approvals = new Map<string, number>();

/** The install notice is worth showing once per process, not once per session. */
let announced = false;

/** Cached 放宽模式 flag, with the time it was read (loopback call, so cheap). */
let relaxedShellSyntax = false;
let relaxedFetchedAt = 0;
const RELAXED_TTL_MS = 3000;

/**
 * The 放宽模式 switch, read from the app rather than duplicated here.
 *
 * On any failure the answer is `false`: if the bridge is unreachable the shell tool
 * is going to fail anyway, and assuming "relaxed" would be the one mistake this
 * design cannot make — the two sides disagreeing about what is permitted.
 */
async function currentRelaxed(): Promise<boolean> {
	if (Date.now() - relaxedFetchedAt < RELAXED_TTL_MS) return relaxedShellSyntax;
	try {
		const health = await bridgeHealth();
		relaxedShellSyntax = health.shellSyntaxRelaxed === true;
	} catch {
		relaxedShellSyntax = false;
	}
	relaxedFetchedAt = Date.now();
	return relaxedShellSyntax;
}

/**
 * Publish the session's approval state to the app so the user can *see* the
 * relaxation on the 设备能力 page. Display only — the app cannot verify it, and the
 * UI says as much — so a failure here is deliberately silent.
 */
async function publish(note: string): Promise<void> {
	try {
		await reportGate({
			sessionGrants: [...sessionGrants],
			counts: Object.fromEntries(approvals),
			relaxedShellSyntax,
			note,
		});
	} catch {
		// The gate's enforcement does not depend on the report landing.
	}
}

export default function (pi: ExtensionAPI) {
	pi.on("tool_call", async (event: ToolCallEvent, ctx) => {
		const toolName = event.toolName;

		// ---- Jurisdiction: everything that is not a device tool is not ours. ----
		if (!isDeviceTool(toolName)) return undefined;

		const level = dangerLevelOf(toolName);
		if (level !== "dangerous") return undefined;

		const input = event.input as Record<string, unknown>;

		// (3) Hard policy refusals, checked before anyone is asked to decide.
		if (toolName === "android_shell") {
			const command = typeof input.command === "string" ? input.command : "";
			if (command.trim().length === 0) {
				return { block: true, reason: "android_shell 需要一条命令，但它拿到的是空字符串。" };
			}
			const relaxed = await currentRelaxed();
			const violation = shellPrecheck(command, relaxed);
			if (violation !== null) {
				return {
					block: true,
					reason:
						`设备策略拒绝这条 Shell 命令（${violation}），它与用户授权无关，无法放行：\n\n  ${command}\n\n` +
						"请改用别的命令或别的工具，或直接告诉用户这一步无法通过设备桥完成。",
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

		// (2) Already remembered for this session: do not ask again.
		if (sessionGrants.has(toolName)) {
			approvals.set(toolName, (approvals.get(toolName) ?? 0) + 1);
			await publish(`${toolName} 在本会话内已被记住，未再次询问。`);
			return undefined;
		}

		const description = describeDangerousCall(toolName, input);
		const previous = approvals.get(toolName) ?? 0;
		const history = previous > 0
			? `\n\n（本会话你已经批准过 ${previous} 次「${toolName}」，但每次都问过你。）`
			: "";

		let choice: string | undefined;
		try {
			choice = await ctx.ui.select(
				`⚠️ pi-android 请求执行危险设备操作：${toolName}`,
				[CHOICE_ONCE, CHOICE_REMEMBER, CHOICE_DENY],
				{ timeout: 120_000 },
			);
		} catch (error) {
			return {
				block: true,
				reason:
					`确认对话框不可用（${error instanceof Error ? error.message : String(error)}），` +
					`因此危险设备操作「${toolName}」被拒绝。`,
			};
		}

		if (choice === CHOICE_REMEMBER) {
			sessionGrants.add(toolName);
			approvals.set(toolName, previous + 1);
			await publish(`用户选择了「${CHOICE_REMEMBER}」：${toolName} 在本会话内不再询问。`);
			await ctx.ui.notify(
				`已记住：本会话不再询问「${toolName}」。结束会话后会恢复逐次确认。`,
				"info",
			);
			return undefined;
		}

		if (choice === CHOICE_ONCE) {
			approvals.set(toolName, previous + 1);
			await publish(`用户只允许了这一次：${toolName}。`);
			return undefined;
		}

		// Denied, dismissed, or an answer we do not recognise.
		await publish(`用户拒绝或取消了：${toolName}。`);
		return {
			block: true,
			reason:
				`用户拒绝了这个设备操作：${toolName}。` +
				"请停下来，把用户拒绝这件事告诉他，不要重试，也不要用别的工具绕过。",
		};
	});

	// A visible, auditable marker of what this extension is doing, so a user who
	// reads pi's startup output knows the gate is installed rather than assuming it.
	pi.on("session_start", async (_event, ctx) => {
		// Every session starts from zero: the remembered approvals are session-scoped
		// by definition, and a stale set would be a permanent grant nobody agreed to.
		const hadGrants = sessionGrants.size > 0;
		sessionGrants.clear();
		approvals.clear();
		relaxedFetchedAt = 0;
		const relaxed = await currentRelaxed();
		await publish("新会话开始：危险操作的审批记录已清空。");

		if (!ctx.hasUI) return;
		if (!announced || hadGrants) {
			announced = true;
			ctx.ui.notify(
				`设备审批已启用：${DANGEROUS_TOOLS.length} 个危险手机操作会请求确认，` +
					"确认框里可以选择「同意并记住本次会话」。设备策略只管辖 android_* 工具，" +
					"不影响 pi 在工作区里跑命令。",
				"info",
			);
		}
		if (relaxed) {
			ctx.ui.notify(
				"Shell 放宽模式处于开启状态：$(...) 与反引号、sh/eval/source 都会被允许，" +
					"写入边界与白名单从此只约束最外层命令。可在「设置 → 设备能力 → Shell」关闭。",
				"warning",
			);
		}
	});
}
