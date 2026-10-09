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
 *    the capability opt-in, the shell whitelist and its 放宽模式 syntax rule, and the
 *    workspace write boundary. Those are what the user's consent actually rests on.
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
 *  3. **Shell asks instead of refusing.** The hard blocklist is empty on both sides
 *     (`danger.FORBIDDEN_SHELL_PATTERNS` mirrors `DeviceShellGuard.hardBlocks`), so
 *     there is nothing left that can never be allowed: `needsApproval` names the
 *     irreversible commands (a `dd` write, `mkfs`, `/dev/block`, `pm clear`) and the
 *     user is asked, while every other command runs without a dialog.
 *
 *     The same rule decides `android_admin`: only `action="wipe"` is asked about,
 *     through `needsApprovalForAdmin`. Every other admin action is a read or is
 *     reversible, and asking about those would be the noise that turns a confirmation
 *     into a reflex.
 */

import type { ExtensionAPI, ToolCallEvent } from "@earendil-works/pi-coding-agent";
import { reportGate } from "./pi-android-bridge/client";
import {
	dangerLevelOf,
	describeDangerousCall,
	isDeviceTool,
	needsApproval,
	needsApprovalForAdmin,
	type ApprovalRule,
} from "./pi-android-bridge/danger";

/** The three answers the confirmation dialog offers. */
const CHOICE_ONCE = "仅允许这一次";
const CHOICE_REMEMBER = "同意并记住本次会话";
const CHOICE_DENY = "拒绝";

/** Tools the user approved; cleared on every session start. */
const sessionGrants = new Set<string>();

/**
 * The key an approval is remembered under.
 *
 * Three cases, and the first is the one that had to change:
 *
 *  - **A tool with a per-call rule list** (`android_shell`, `android_admin`) is
 *    remembered **per matched rule**, never per tool. Keying those by tool name meant
 *    one 「同意并记住本次会话」 on `pm clear` also silenced `mkfs`, `dd` and
 *    `rm -rf /sdcard` for the rest of the session — seven curated rules collapsing to
 *    zero on a single click, which is the opposite of what a curated list is for. The
 *    rule's `id` is a short readable name, `grantLabel` shows it, so the scope is
 *    stated in the dialog and in the confirmation note as well as here.
 *  - **A direction** (`android_download`, `android_files`) keeps `op` in the key: those
 *    used to be two tools with two separate grants, and remembering
 *    `android_files_write` never silenced `android_files_read`.
 *  - **Everything else** keeps its bare tool name, exactly as before.
 */
function grantKey(toolName: string, input: Record<string, unknown>, rule: ApprovalRule | null): string {
	if (rule !== null) return `${toolName}:${rule.id}`;
	const op = input.op;
	return typeof op === "string" && op.length > 0 ? `${toolName}:${op}` : toolName;
}

/** What the user is shown for a remembered grant — `android_files（write）`. */
function grantLabel(toolName: string, key: string): string {
	return key === toolName ? toolName : `${toolName}（${key.slice(toolName.length + 1)}）`;
}

/** How many times each dangerous tool has been approved in this process. */
const approvals = new Map<string, number>();

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
		const input = event.input as Record<string, unknown>;

		// (3) Two rules are about the *call*, not the tool.
		//
		// The level is a property of the tool, but what is worth asking about belongs to
		// one call: an irreversible shell command, or `android_admin(action="wipe")`.
		// `android_admin` is `control` because eight of its nine actions are reads or are
		// reversible — raising the whole tool would put a dialog in front of
		// `action="status"` too, and a confirmation that fires on the wrong thing is how a
		// confirmation becomes a reflex. So these two decide for themselves, and the level
		// check below covers the tools that have no rule of their own.
		let approvalReason: ApprovalRule | null = null;
		if (toolName === "android_shell") {
			const command = typeof input.command === "string" ? input.command : "";
			approvalReason = needsApproval(command);
		} else if (toolName === "android_admin") {
			approvalReason = needsApprovalForAdmin(input);
		}

		if (toolName === "android_shell" || toolName === "android_admin") {
			// A tool with its own rule: a call that matched none of them runs silently.
			if (approvalReason === null) return undefined;
		} else if (level !== "dangerous") {
			// No rule of its own and not a dangerous tool: not the gate's business.
			return undefined;
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

		// (2) Already remembered for this session: do not ask again. The key carries the
		// matched rule, so this only skips the *same* consequence — see [grantKey].
		const key = grantKey(toolName, input, approvalReason);
		if (sessionGrants.has(key)) {
			approvals.set(toolName, (approvals.get(toolName) ?? 0) + 1);
			await publish(`${grantLabel(toolName, key)} 在本会话内已被记住，未再次询问。`);
			return undefined;
		}

		const description = describeDangerousCall(toolName, input);
		// What the model (and therefore the user) is told the decision was about. The tool
		// name alone reads as `android_shell`, which tells the user nothing: the rule's short
		// id is what distinguishes 「用户拒绝了卸载应用」 from 「用户拒绝了 android_shell」.
		const subject = approvalReason === null ? toolName : `${toolName}（${approvalReason.id}）`;
		const previous = approvals.get(toolName) ?? 0;
		const history = previous > 0
			? `\n\n（本会话你已经批准过 ${previous} 次「${toolName}」，但每次都问过你。）`
			: "";
		// The rule's consequence sentence is what made the call worth asking about, so it
		// belongs in the question.
		const reason = approvalReason === null ? "" : `\n\n［${approvalReason.label}］`;

		let choice: string | undefined;
		try {
			choice = await ctx.ui.select(
				`⚠️ pi-android 请求执行危险设备操作：${toolName}${reason}\n\n${description}${history}`,
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
			sessionGrants.add(key);
			approvals.set(toolName, previous + 1);
			const label = grantLabel(toolName, key);
			await publish(`用户选择了「${CHOICE_REMEMBER}」：${label} 在本会话内不再询问。`);
			await ctx.ui.notify(
				`已记住：本会话不再询问「${label}」。结束会话后会恢复逐次确认。`,
				"info",
			);
			return undefined;
		}

		if (choice === CHOICE_ONCE) {
			approvals.set(toolName, previous + 1);
			await publish(`用户只允许了这一次：${toolName}。`);
			return undefined;
		}

		if (choice === CHOICE_DENY) {
			await publish(`用户拒绝了这个操作：${subject}。`);
			return {
				block: true,
				reason:
					`用户拒绝了「${subject}」。` +
					"请停下来，把用户拒绝这件事告诉他，不要重试，也不要用别的工具绕过。",
			};
		}

		// No answer. pi auto-resolves a timed-out dialog with `undefined`
		// (`docs/rpc-extension-ui.md`, "select"): the agent-side does it, the extension just
		// sees the value. Reporting that as a refusal would put a decision in the user's
		// mouth — the model tells them they refused something they never saw.
		//
		// It must not invite a retry either. The model cannot know whether the user is at the
		// phone, so 「可以再请求一次」 turns into a blind retry: another 120 s, and if they are
		// still away, another timeout. Report it and let the user say when to try again.
		await publish(`确认对话框超时或没有回答：${subject}。`);
		return {
			block: true,
			reason:
				`确认对话框没有得到回答（120 秒超时或被关掉），所以「${subject}」没有执行。` +
				"这不等于用户拒绝 —— 只是没人回答。不要自己重试：先告诉他这一步还在等他确认，" +
				"他说可以了你再调一次。",
		};
	});

	// The gate never speaks on the bottom of the screen. The fact it used to announce
	// there is *persistent* state, and it has a home where the user can read it whenever
	// they want instead of having it pushed at every session start:
	//
	//   - the remembered approvals and the approval count → 设置 → 设备能力 → 本会话的审批
	//     (kept truthful by `publish` below, which is the only reporting channel).
	//
	// A per-session snackbar for an unchanged setting is confirmation fatigue with a
	// zero information rate, and it also cost display-only latency in front of every
	// session's first message.
	pi.on("session_start", async (_event, _ctx) => {
		// Every session starts from zero: the remembered approvals are session-scoped
		// by definition, and a stale set would be a permanent grant nobody agreed to.
		sessionGrants.clear();
		approvals.clear();

		// Fire-and-forget: `session_start` must return before pi attaches the JSONL
		// stdin reader (`core/agent-session.ts:2468-2491` from `modes/rpc/rpc-mode.ts:316`),
		// so awaiting a loopback call here would run in front of the user's first message.
		void publish("新会话开始：危险操作的审批记录已清空。");
	});
}
