/**
 * Client for the pi-android device bridge.
 *
 * The bridge is an HTTP server the host app runs on `127.0.0.1`. It is *not*
 * reachable by guessing: the app publishes a JSON file inside this guest with the
 * port and a bearer token, and this module finds it. The file is written to more
 * than one guest path (the engine's bind mounts differ between builds), so the
 * lookup is a documented probe rather than a single hardcoded path — with
 * `PI_ANDROID_BRIDGE_FILE` as an explicit override.
 *
 * Every refusal from the bridge arrives as `{ ok: false, code, reason, hint }`.
 * Those are converted into a [BridgeError] whose message is already the Chinese
 * sentence the model should relay: the design requires disabled capabilities to
 * produce a *reason*, never a silent failure, and the model is what the user
 * actually talks to.
 */

import { readFile } from "node:fs/promises";
import { join } from "node:path";

export interface BridgeCredentials {
	port: number;
	token: string;
	/** Which candidate path produced the credentials, for diagnostics only. */
	source: string;
}

export interface BridgeFailure {
	code: string;
	reason: string;
	hint?: string;
}

/** A refusal or transport failure, carrying the text meant for the user. */
export class BridgeError extends Error {
	readonly code: string;
	readonly hint: string | undefined;

	constructor(failure: BridgeFailure) {
		super(failure.reason);
		this.name = "BridgeError";
		this.code = failure.code;
		this.hint = failure.hint;
	}

	/** The message handed to the model, including the actionable hint. */
	toModelMessage(): string {
		const lines = [`[${this.code}] ${this.message}`];
		if (this.hint) lines.push(`提示：${this.hint}`);
		return lines.join("\n");
	}
}

const DEFAULT_PORT = 3175;
const TOKEN_FILE_NAME = "device-bridge.json";

let cached: BridgeCredentials | null = null;
let cachedFailures: string[] = [];

/** Every place the app may have published the credentials, most explicit first. */
export function candidatePaths(): string[] {
	const candidates: string[] = [];
	const explicit = process.env.PI_ANDROID_BRIDGE_FILE;
	if (explicit && explicit.trim().length > 0) candidates.push(explicit.trim());
	const agentDir = process.env.PI_CODING_AGENT_DIR;
	if (agentDir && agentDir.trim().length > 0) candidates.push(join(agentDir.trim(), TOKEN_FILE_NAME));
	const home = process.env.HOME && process.env.HOME.trim().length > 0 ? process.env.HOME.trim() : "/root";
	candidates.push(join(home, ".pi", "agent", TOKEN_FILE_NAME));
	candidates.push(join(home, ".pi", TOKEN_FILE_NAME));
	candidates.push(join("/root", ".pi", "agent", TOKEN_FILE_NAME));
	candidates.push(join("/root", ".pi", TOKEN_FILE_NAME));
	return [...new Set(candidates)];
}

interface TokenFileShape {
	port?: number;
	token?: string;
}

/** Read (and cache) the published credentials. */
export async function loadCredentials(force = false): Promise<BridgeCredentials> {
	if (cached && !force) return cached;
	const tried: string[] = [];
	for (const candidate of candidatePaths()) {
		tried.push(candidate);
		let raw: string;
		try {
			raw = await readFile(candidate, "utf8");
		} catch {
			continue;
		}
		let parsed: TokenFileShape;
		try {
			parsed = JSON.parse(raw) as TokenFileShape;
		} catch {
			continue;
		}
		if (typeof parsed.token !== "string" || parsed.token.length === 0) continue;
		const port = typeof parsed.port === "number" && parsed.port > 0 ? parsed.port : DEFAULT_PORT;
		cached = { port, token: parsed.token, source: candidate };
		cachedFailures = [];
		return cached;
	}
	cachedFailures = tried;
	throw new BridgeError({
		code: "DISABLED",
		reason:
			"读不到 pi-android 设备桥的 token 文件，因此无法调用任何手机能力。" +
			"设备桥要么没有启动，要么还没把 token 写进 guest。",
		hint:
			`请让用户在 pi-android 里打开「设置 → 设备能力」确认桥已监听（默认端口 ${DEFAULT_PORT}）。` +
			`已尝试的路径：${tried.join("、")}`,
	});
}

export interface HealthCapability {
	id: string;
	title: string;
	summary: string;
	enabled: boolean;
	enabledForSession: boolean;
	usable: boolean;
	reason?: string;
	allows: string[];
}

export interface HealthPayload {
	service: string;
	version: string;
	port: number;
	tokenFile: string;
	capabilities: HealthCapability[];
	accessibilityRunning: boolean;
	accessibilityEnabledInSettings: boolean;
	screenshotSupported: boolean;
	locationPermissionGranted: boolean;
	notificationPermissionGranted: boolean;
	vibratePermissionGranted: boolean;
	/**
	 * The pre-API-29 storage path. Reported so a model can explain why
	 * `android_export` fails on an old device instead of guessing.
	 */
	legacyStoragePermissionGranted?: boolean;
	/**
	 * The 放宽模式 switch, owned by the app. The permission gate reads it here so
	 * both enforcers use one boolean (see `danger.shellPrecheck`).
	 */
	shellSyntaxRelaxed?: boolean;
	/** The shell write boundary: the user's workspace, as the device shell sees it. */
	workspace?: { shellPath: string | null; guestPath: string; known: boolean };
	/** Shizuku (uid 2000 / root) status: the elevated shell backend. */
	shizuku?: {
		installed: boolean;
		binderAlive: boolean;
		permissionGranted: boolean;
		ready: boolean;
		uid: number;
		version: number;
		backendLabel: string;
		note: string;
	};
	/** What the permission gate last reported doing (display only). */
	gate?: {
		reported: boolean;
		sessionGrants?: string[];
		counts?: Record<string, number>;
		relaxedShellSyntax?: boolean;
		note?: string;
	};
	/** Granted SAF directories (the storage group). */
	saf?: { count: number; roots: string[] };
	/** The full shell policy, so a model can read what is allowed before trying. */
	shellPolicy?: {
		allowedCommands: string[];
		blocked: string[];
		writeBoundary: string[];
		syntax: string[];
		relaxedCost: string;
		elevatedBackend: boolean;
	};
	shellBackends: Array<{ id: string; label: string; available: boolean }>;
	androidRelease: string;
	sdkInt: number;
	auditLogPath: string;
}

interface Envelope {
	ok?: boolean;
	code?: string;
	reason?: string;
	hint?: string;
	data?: unknown;
}

type QueryValue = string | number | boolean | undefined;

function buildUrl(port: number, path: string, query?: Record<string, QueryValue>): string {
	const url = new URL(`http://127.0.0.1:${port}${path}`);
	if (query) {
		for (const [key, value] of Object.entries(query)) {
			if (value === undefined || value === null) continue;
			url.searchParams.set(key, String(value));
		}
	}
	return url.toString();
}

/**
 * One bridge call. Retries exactly once after re-reading the token file, because
 * the app mints a new token on every start and a long-lived guest session can
 * outlive one.
 */
async function request<T>(
	method: "GET" | "POST",
	path: string,
	body?: Record<string, unknown>,
	query?: Record<string, QueryValue>,
	timeoutMs = 30_000,
): Promise<T> {
	let credentials: BridgeCredentials;
	try {
		credentials = await loadCredentials();
	} catch (error) {
		if (error instanceof BridgeError) throw error;
		throw new BridgeError({ code: "ERROR", reason: String(error) });
	}

	let lastError: unknown = null;
	for (let attempt = 0; attempt < 2; attempt += 1) {
		const current = attempt === 0 ? credentials : await loadCredentials(true);
		let response: Response;
		try {
			response = await fetch(buildUrl(current.port, path, query), {
				method,
				headers: {
					"content-type": "application/json",
					authorization: `Bearer ${current.token}`,
				},
				body: body === undefined ? undefined : JSON.stringify(body),
				signal: AbortSignal.timeout(timeoutMs),
			});
		} catch (error) {
			lastError = error;
			// Transport failure: the app may have just restarted the bridge, so a
			// fresh token read is worth one retry.
			if (attempt === 0) continue;
			break;
		}

		const text = await response.text();
		let envelope: Envelope;
		try {
			envelope = JSON.parse(text) as Envelope;
		} catch {
			throw new BridgeError({
				code: "ERROR",
				reason: `设备桥返回了非 JSON 响应（HTTP ${response.status}）：${text.slice(0, 200)}`,
			});
		}

		if (response.status === 401 && attempt === 0) {
			// Stale token: mint-read again and retry once.
			cached = null;
			continue;
		}
		if (envelope.ok === false) {
			throw new BridgeError({
				code: typeof envelope.code === "string" ? envelope.code : "ERROR",
				reason: typeof envelope.reason === "string" ? envelope.reason : `设备桥拒绝了 ${path}`,
				hint: typeof envelope.hint === "string" ? envelope.hint : undefined,
			});
		}
		if (!response.ok) {
			throw new BridgeError({
				code: "ERROR",
				reason: `设备桥返回 HTTP ${response.status}（${path}）。`,
			});
		}
		// Two envelope shapes exist on the wire and both are intentional:
		//   `{ ok, data }`  — the capability-gated endpoints (`BridgeHttpResponse.ok`)
		//   `{ ok, ...flat }` — `/app/health`, `/app/capabilities`, `/app/audit` and
		//                       the gate report, which use `okRaw` because they are
		//                       how a caller discovers *why* something else is refused.
		// Reading only `data` made `bridgeHealth()` resolve to `undefined`, which
		// silently disabled everything that reads it — including the 放宽模式 flag.
		// (Found by tools-style runtime harness: node gate-check, 2026-09-10.)
		return (envelope.data ?? envelope) as T;
	}

	const reason = lastError instanceof Error ? lastError.message : String(lastError);
	throw new BridgeError({
		code: "DISABLED",
		reason:
			`连不上 pi-android 设备桥（127.0.0.1:${credentials.port}）：${reason}。` +
			"设备桥只在 pi-android 应用进程存活时运行。",
		hint: "请让用户打开 pi-android，并在「设置 → 设备能力」确认状态显示为「已监听」。",
	});
}

export function bridgeGet<T>(path: string, query?: Record<string, QueryValue>, timeoutMs?: number): Promise<T> {
	return request<T>("GET", path, undefined, query, timeoutMs);
}

export function bridgePost<T>(
	path: string,
	body: Record<string, unknown> = {},
	timeoutMs?: number,
): Promise<T> {
	return request<T>("POST", path, body, undefined, timeoutMs);
}

export async function bridgeHealth(): Promise<HealthPayload> {
	return bridgeGet<HealthPayload>("/app/health", undefined, 5000);
}

/**
 * Tell the app what the permission gate is currently doing, so the 设备能力 page can
 * show it. Display only: nothing here changes policy, and the gate's own in-process
 * memory is what enforces it. The UI labels the result as extension-reported.
 */
export async function reportGate(payload: {
	sessionGrants: string[];
	counts: Record<string, number>;
	relaxedShellSyntax: boolean;
	note: string;
}): Promise<void> {
	await bridgePost<unknown>("/app/gate/report", payload, 3000);
}

/** Diagnostics only: which paths were probed, when credentials were missing. */
export function lastProbedPaths(): string[] {
	return cachedFailures;
}
