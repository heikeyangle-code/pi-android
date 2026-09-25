/**
 * pi-background — 长任务不再把工具调用占满。
 *
 * ## 它回答什么
 *
 * 「我正在跑一个要十几分钟的命令，这段时间里模型一句话都说不了。」
 *
 * 模型的输出只能出现在两次工具调用之间（这是协议属性，不是客户端的缺陷）：一个工具
 * 在 `execute` 里阻塞多久，这一轮就多久没有可以承载文字的位置。所以解决办法不是让模型
 * 边跑边说，而是**把长活挪出工具调用**：
 *
 *  1. `bg_start` 起一个分离的 guest 进程，**立刻**返回 job id（这次工具调用当场结束，
 *     模型可以继续说话、也可以去干别的）；
 *  2. 进程结束（或超时、被 kill）时，扩展用 `pi.sendMessage(..., {triggerTurn: true})`
 *     往会话里投一条消息 —— 模型因此拿到一个新回合，把结果说出来。
 *
 * 这三步与 pi 自己的文档一致：`docs/extensions.md` 的能力表把「Send user or custom
 * messages」列为 `pi.sendUserMessage()` / `pi.sendMessage()`，`docs/rpc-commands.md:31`
 * 更进一步说明扩展命令在 streaming 期间也立即执行、由 `pi.sendMessage()` 自己管理 LLM
 * 交互 —— 也就是说「工具还在跑」并不阻止扩展投递消息。
 *
 * ## 为什么这是扩展，而不是改 pi
 *
 * 本仓库的承诺是「官方 pi 一个字节没改」，而 pi 的扩展机制本来就是为这件事存在的
 * （`docs/extensions.md`：注册工具、命令、事件，以及向会话发消息）。这个扩展只用 pi 公开
 * 的 API 与 Node 内建模块，没有新依赖，也不需要引擎侧的改动。
 *
 * ## 与设备桥的分工
 *
 * 「通知模型」与「通知手机前的人」是两条通道，这里两条都用：
 *
 *  - 模型：`pi.sendMessage()`（上面第 2 步）；
 *  - 人：`ctx.ui.notify()`（TUI/RPC 有 UI 时）。需要在通知栏里留一条时，模型可以自己调
 *    设备桥的 `android_say` —— 那是 app 的能力，不属于这个扩展。
 *
 * ## 状态放在哪
 *
 * 日志与索引写在 `<agentDir>/background-jobs/`（`PI_CODING_AGENT_DIR`，app 已经把它钉在
 * guest 的 `/root/.pi/agent`）。放在 agent 目录里而不是 `/tmp`：那个目录在易失树之外，
 * 引擎重启、运行时重装都不会把用户正在等的日志删掉；`jobs.json` 让重启后的 `bg_list`
 * 仍然说得出「有哪些任务、结束没有」。
 *
 * 索引只用于**显示**：进程句柄活在内存里，重启后只能读出「最后记录的状态」，读不出
 * 退出码；这一点在 `bg_status` 的返回里写明，而不是假装它还活着。
 */

import { spawn, type ChildProcess } from "node:child_process";
import { closeSync, existsSync, mkdirSync, openSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import {
	DEFAULT_MAX_BYTES,
	DEFAULT_MAX_LINES,
	formatSize,
	truncateTail,
} from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

/** One known job. `exited` is recorded in the index; `running` only exists in memory. */
interface Job {
	id: string;
	command: string;
	cwd: string;
	logPath: string;
	startedAt: number;
	/** Present only while this engine process owns the child. */
	pid?: number;
	status: "running" | "exited";
	exitCode?: number | null;
	signal?: string | null;
	finishedAt?: number;
}

/** The last N jobs survive a restart; older ones are dropped from the index, not deleted. */
const INDEX_LIMIT = 50;

/** How much of the tail a completion message carries. The full log is on disk. */
const NOTIFY_TAIL_LINES = 20;

function agentDir(): string {
	return process.env.PI_CODING_AGENT_DIR ?? join(homedir(), ".pi", "agent");
}

function jobsDir(): string {
	return join(agentDir(), "background-jobs");
}

function indexFile(): string {
	return join(jobsDir(), "jobs.json");
}

function jobId(): string {
	// `bg-<epoch base36>-<pid>`: sortable, unique enough, and readable in a log path.
	return `bg-${Date.now().toString(36)}-${process.pid.toString(36)}`;
}

/**
 * Read the on-disk index. Never throws: a corrupt index must not take the extension (and
 * with it the whole session) down, so a parse failure is reported as an empty list and the
 * caller still gets the jobs it can see in memory.
 */
function readIndex(): Job[] {
	try {
		const raw = readFileSync(indexFile(), "utf8");
		const parsed: unknown = JSON.parse(raw);
		return Array.isArray(parsed) ? (parsed as Job[]) : [];
	} catch {
		return [];
	}
}

function writeIndex(jobs: Job[]): void {
	try {
		mkdirSync(jobsDir(), { recursive: true });
		writeFileSync(indexFile(), JSON.stringify(jobs.slice(-INDEX_LIMIT), null, 1));
	} catch {
		// A read-only or full agent dir is the guest's problem, not this extension's: the
		// job still runs, and its log still lands. Swallowing here keeps a listing tool
		// from failing a session.
	}
}

function readTail(path: string, lines: number): string {
	try {
		const text = readFileSync(path, "utf8");
		const truncated = truncateTail(text, {
			maxBytes: DEFAULT_MAX_BYTES,
			maxLines: DEFAULT_MAX_LINES,
		});
		const body = truncated.content.split("\n");
		return body.slice(Math.max(0, body.length - lines)).join("\n");
	} catch (error) {
		return `（读不到日志 ${path}：${String(error)}）`;
	}
}

function elapsedSeconds(job: Job): number {
	return ((job.finishedAt ?? Date.now()) - job.startedAt) / 1000;
}

export default function (pi: ExtensionAPI) {
	/** Jobs this process owns (child handle present) plus jobs discovered from the index. */
	const jobs = new Map<string, Job>();
	const children = new Map<string, ChildProcess>();

	/** The most recent tool context: `sendMessage` needs no context, `ui.notify` does. */
	let lastCtx: ExtensionContext | undefined;

	function persist(): void {
		writeIndex([...jobs.values()].sort((a, b) => a.startedAt - b.startedAt));
	}

	/** Load whatever a previous engine run left behind, newest first. */
	function hydrate(): void {
		for (const job of readIndex()) {
			if (!jobs.has(job.id)) {
				// A job the index says was running cannot be resumed: the child died with the
				// engine that owned it. Recording that honestly beats listing a corpse as live.
				jobs.set(job.id, job.status === "running" ? { ...job, status: "exited", signal: "engine-restart" } : job);
			}
		}
	}

	/**
	 * The whole point of the extension: tell the model a job is done, in a way that gives it
	 * a turn to speak.
	 *
	 * `triggerTurn: true` starts that turn; `deliverAs: "followUp"` hands the message over
	 * **after** the pending work finishes, so a completion never cuts into a tool call the
	 * model is in the middle of. Together they are what turns "the job finished" into "the
	 * agent said something about it".
	 */
	async function announceCompletion(job: Job): Promise<void> {
		const code = job.exitCode === null || job.exitCode === undefined ? "信号 " + (job.signal ?? "?") : `exit ${job.exitCode}`;
		const ok = job.exitCode === 0;
		const header = `[bg] ${job.id} ${ok ? "完成" : "结束（非零）"}：${code}，用时 ${elapsedSeconds(job).toFixed(1)}s`;
		const text = [
			header,
			`命令：${job.command}`,
			`目录：${job.cwd}`,
			`日志：${job.logPath}`,
			`末尾 ${NOTIFY_TAIL_LINES} 行：`,
			readTail(job.logPath, NOTIFY_TAIL_LINES),
		].join("\n");
		try {
			await pi.sendMessage(
				{
					customType: "pi-background",
					content: text,
					display: true,
					details: { id: job.id, exitCode: job.exitCode, logPath: job.logPath, seconds: elapsedSeconds(job) },
				},
				{ triggerTurn: true, deliverAs: "followUp" },
			);
		} catch {
			// If the session cannot take a message (engine shutting down), the log path is
			// still the answer, and `bg_list` shows the exit code next time.
		}
		if (lastCtx?.hasUI) {
			try {
				lastCtx.ui.notify(`${header}（日志：${job.logPath}）`, ok ? "info" : "warning");
			} catch {
				// A UI that went away is not a failure of the job.
			}
		}
	}

	function startJob(command: string, cwd: string): Job {
		const dir = jobsDir();
		mkdirSync(dir, { recursive: true });
		const id = jobId();
		const logPath = join(dir, `${id}.log`);
		const job: Job = { id, command, cwd, logPath, startedAt: Date.now(), status: "running" };
		// `detached: true` + `unref()`: the job must outlive the tool call, and it must not
		// keep this process alive on its own. `stdio` goes to a file rather than a pipe —
		// a pipe nobody reads fills and blocks the child.
		const fd = openSync(logPath, "a");
		const child = spawn("/bin/bash", ["-lc", command], {
			cwd,
			detached: true,
			stdio: ["ignore", fd, fd],
			env: process.env,
		});
		closeSync(fd);
		job.pid = child.pid ?? undefined;
		jobs.set(id, job);
		children.set(id, child);
		persist();

		child.on("close", (code, signal) => {
			const current = jobs.get(id);
			if (!current) return;
			current.status = "exited";
			current.exitCode = code;
			current.signal = signal;
			current.finishedAt = Date.now();
			children.delete(id);
			persist();
			void announceCompletion(current);
		});
		child.on("error", (error) => {
			const current = jobs.get(id);
			if (!current) return;
			current.status = "exited";
			current.exitCode = -1;
			current.signal = `spawn失败: ${String(error)}`;
			current.finishedAt = Date.now();
			children.delete(id);
			persist();
			void announceCompletion(current);
		});
		child.unref();
		return job;
	}

	function findJob(idOrPrefix: string): Job | undefined {
		const direct = jobs.get(idOrPrefix);
		if (direct) return direct;
		const matches = [...jobs.values()].filter((job) => job.id.startsWith(idOrPrefix));
		return matches.length === 1 ? matches[0] : undefined;
	}

	function describe(job: Job): string {
		const state = job.status === "running" ? "运行中" : `已结束（${job.exitCode ?? "信号 " + (job.signal ?? "?")}）`;
		return `${job.id}  ${state}  ${elapsedSeconds(job).toFixed(1)}s  ${job.command}`;
	}

	hydrate();
	pi.on("session_start", async () => {
		hydrate();
	});

	pi.registerTool({
		name: "bg_start",
		label: "启动后台任务",
		description:
			"在 guest 里启动一个**不阻塞**的命令并立刻返回 job id。用于会跑很久的活" +
			"（构建、测试、装包、等 CI）：工具调用当场结束，模型可以继续对话；任务结束时" +
			"扩展会往会话里投一条消息（含退出码与日志末尾），模型因此拿到一个回合来汇报。" +
			"输出写在 <agentDir>/background-jobs/<id>.log，用 bg_output 读、bg_kill 停。",
		promptSnippet: "bg_start: 起一个长任务，立刻返回，完成时自动汇报",
		promptGuidelines: [
			"预计超过约 30 秒的命令用 bg_start，不要用 bash 干等：等它会把这一轮占满，期间无法回话。",
			"bg_start 返回后不要再轮询 bg_status；完成时会有消息进来，那时再读日志。",
		],
		parameters: Type.Object({
			command: Type.String({ description: "要跑的 shell 命令（/bin/bash -lc）" }),
			cwd: Type.Optional(Type.String({ description: "工作目录（guest 路径），默认当前会话目录" })),
		}),
		async execute(_toolCallId: string, params: { command: string; cwd?: string }, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const cwd = params.cwd && params.cwd.trim().length > 0 ? params.cwd : ctx.cwd;
			const job = startJob(params.command, cwd);
			return {
				content: [
					{
						type: "text" as const,
						text:
							`已启动 ${job.id}（pid ${job.pid ?? "?"}）。\n` +
							`命令：${job.command}\n目录：${job.cwd}\n日志：${job.logPath}\n` +
							`任务结束时会有消息进来；现在可以继续做别的事，不需要等它。`,
					},
				],
				details: { id: job.id, pid: job.pid, logPath: job.logPath },
			};
		},
	});

	pi.registerTool({
		name: "bg_list",
		label: "列出后台任务",
		description: "列出后台任务（内存里的句柄 + 磁盘索引，含重启前的记录）。",
		parameters: Type.Object({}),
		async execute(_toolCallId: string, _params: Record<string, never>, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const all = [...jobs.values()].sort((a, b) => b.startedAt - a.startedAt);
			if (all.length === 0) return { content: [{ type: "text" as const, text: "没有后台任务。" }] };
			return {
				content: [{ type: "text" as const, text: all.map(describe).join("\n") }],
				details: { count: all.length },
			};
		},
	});

	pi.registerTool({
		name: "bg_status",
		label: "后台任务状态",
		description: "一个后台任务的状态、退出码、用时与日志末尾。",
		parameters: Type.Object({
			id: Type.String({ description: "bg_start 返回的 id，可以只给前缀" }),
			lines: Type.Optional(Type.Number({ description: "日志末尾行数，默认 40" })),
		}),
		async execute(_toolCallId: string, params: { id: string; lines?: number }, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const job = findJob(params.id);
			if (!job) throw new Error(`没有这个 id 的后台任务：${params.id}（用 bg_list 看有哪些）`);
			const lines = Math.max(1, Math.min(params.lines ?? 40, 500));
			const head = describe(job);
			// Say what the record can and cannot answer: after an engine restart the exit code
			// is a recorded value, not a live one.
			const caveat = job.status === "running" && !children.has(job.id) ? "（内存里没有它的句柄：这台引擎没在管它，状态来自磁盘索引）" : "";
			return {
				content: [
					{
						type: "text" as const,
						text: `${head}${caveat}\n日志：${job.logPath}\n末尾 ${lines} 行：\n${readTail(job.logPath, lines)}`,
					},
				],
				details: { id: job.id, status: job.status, exitCode: job.exitCode, logPath: job.logPath },
			};
		},
	});

	pi.registerTool({
		name: "bg_output",
		label: "读后台任务日志",
		description: "读一个后台任务的日志末尾（超长时按 pi 自己的上限截断）。",
		parameters: Type.Object({
			id: Type.String({ description: "任务 id（可前缀）" }),
			lines: Type.Optional(Type.Number({ description: "末尾行数，默认 200" })),
		}),
		async execute(_toolCallId: string, params: { id: string; lines?: number }, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const job = findJob(params.id);
			if (!job) throw new Error(`没有这个 id 的后台任务：${params.id}`);
			const lines = Math.max(1, Math.min(params.lines ?? 200, 2000));
			const size = existsSync(job.logPath) ? statSync(job.logPath).size : 0;
			return {
				content: [
					{
						type: "text" as const,
						text: `${job.id}（日志 ${formatSize(size)}）末尾 ${lines} 行：\n${readTail(job.logPath, lines)}`,
					},
				],
				details: { id: job.id, logPath: job.logPath, bytes: size },
			};
		},
	});

	pi.registerTool({
		name: "bg_kill",
		label: "停止后台任务",
		description: "停掉一个后台任务：先给整个进程组 SIGTERM，约 3 秒后仍在就 SIGKILL。",
		parameters: Type.Object({
			id: Type.String({ description: "任务 id（可前缀）" }),
		}),
		async execute(_toolCallId: string, params: { id: string }, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const job = findJob(params.id);
			if (!job) throw new Error(`没有这个 id 的后台任务：${params.id}`);
			const child = children.get(job.id);
			if (!child || child.pid === undefined) {
				return {
					content: [
						{
							type: "text" as const,
							text: `${job.id} 没有可停的句柄（状态：${job.status}）。它可能已经结束，或这个进程不是启动它的那一个引擎。`,
						},
					],
				};
			}
			// Negative pid: the whole process **group**. `detached: true` made the child a
			// group leader, so this is what stops its children too — killing just the shell
			// would leave the build it spawned running.
			let signal = "SIGTERM";
			try {
				process.kill(-child.pid, "SIGTERM");
			} catch {
				signal = "SIGTERM 已被拒绝或进程已退出";
			}
			await new Promise((resolve) => setTimeout(resolve, 3000));
			if (children.has(job.id)) {
				try {
					process.kill(-child.pid, "SIGKILL");
					signal = "SIGTERM → SIGKILL";
				} catch {
					// Already gone: the close handler will have recorded it.
				}
			}
			return {
				content: [{ type: "text" as const, text: `已对 ${job.id} 发送 ${signal}。日志：${job.logPath}` }],
				details: { id: job.id, signal },
			};
		},
	});

	// A slash command, for the human side of the same question ("还有什么在跑？").
	pi.registerCommand("bg", {
		description: "列出后台任务（与 bg_list 同源）",
		handler: async (_args, ctx) => {
			lastCtx = ctx;
			const all = [...jobs.values()].sort((a, b) => b.startedAt - a.startedAt);
			ctx.ui.notify(all.length === 0 ? "没有后台任务。" : all.map(describe).join("\n"), "info");
		},
	});
}
