/**
 * The highlight service: loopback HTTP + bearer token, the same shape as the
 * app's device bridge (`app/src/main/kotlin/app/pi/bridge/DeviceBridgeHttp.kt`)
 * with the roles reversed. There, the Android app listens and the guest's
 * extension calls; here the Android app calls and the guest's extension listens,
 * because highlight.js has to run under V8 next to pi rather than under a
 * JavaScript engine reimplemented on Android.
 *
 * Contract (`POST /highlight`, `Authorization: Bearer <token>`):
 *
 *   request   { "code": "...", "language": "kotlin" }
 *   response  { "ok": true, "data": {
 *                 "language": "kotlin", "known": true,
 *                 "spans": [ { "start": 0, "end": 7, "scopes": ["keyword"] } ],
 *                 "codeUnits": 214, "ms": 1.8, "hljs": "10.7.3" } }
 *
 * Failures are the bridge's envelope — `{ ok: false, code, reason, hint? }` —
 * so the Kotlin client can treat *any* non-`ok` answer, and any transport
 * failure, as "render this block plain".
 *
 * Security and cost properties, in the order they are checked:
 *
 *  - the socket is bound to `127.0.0.1` only, so nothing off-device can reach it;
 *  - the bearer token is checked *before* anything else happens, and compared in
 *    constant time; an unauthenticated request cannot cause a request body to be
 *    parsed, a language to be loaded or a grammar to run;
 *  - the body is capped, so a malformed or hostile caller cannot make the pi
 *    engine allocate without bound;
 *  - the service does nothing while idle: no timers, no polling, no keep-alive
 *    work, no background registration. A request is the only thing that runs code,
 *    and the first *authenticated* request is the only thing that loads
 *    highlight.js (`./hljs`);
 *  - the listening socket is `unref()`ed and every response closes its
 *    connection, so this server can never keep pi's process alive by itself.
 *
 * The service only exists while a pi engine is running. That is acceptable —
 * code blocks in the transcript come from live sessions — and it is why every
 * failure path on the client side ends in plain, uncoloured code rather than an
 * error.
 */

import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { chmod, mkdir, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { highlightToRuns, knownLanguages, knowsLanguage, loadState } from "./hljs";

export const SERVICE_NAME = "pi-android-highlight";
export const SERVICE_VERSION = "1";

/** Next to the device bridge's 3175, so the two are never confused. */
export const DEFAULT_PORT = 3176;

/** Credentials are published to this name, as `device-bridge.json` is for the bridge. */
export const TOKEN_FILE_NAME = "highlight-bridge.json";

/** 256 KB of JSON. The client refuses anything over `MAX_CODE_CHARS` long before this. */
const MAX_BODY_BYTES = 256 * 1024;

/** A fence longer than this renders plain: highlighting it would not be seen anyway. */
export const MAX_CODE_CHARS = 64 * 1024;

const PORT_ATTEMPTS = 10;

export interface HighlightServiceHandle {
	readonly port: number;
	readonly token: string;
	/** Guest paths the credentials were written to, for diagnostics. */
	readonly tokenFiles: string[];
	close(): Promise<void>;
}

interface Envelope {
	ok: boolean;
	code?: string;
	reason?: string;
	hint?: string;
	data?: unknown;
}

/** 0600: the proot guest runs as the app's uid, so owner-only is enough. */
async function writeTokenFile(path: string, payload: string): Promise<boolean> {
	try {
		await mkdir(dirname(path), { recursive: true });
		await writeFile(path, payload, { encoding: "utf8", mode: 0o600 });
		await chmod(path, 0o600);
		return true;
	} catch {
		return false;
	}
}

/** Every guest path the Android app might map back to a host path. */
function defaultTokenPaths(): string[] {
	const home = process.env.HOME?.trim() || "/root";
	const agentDir = process.env.PI_CODING_AGENT_DIR?.trim() || join(home, ".pi", "agent");
	// Both are published for the same reason the device bridge writes its token to
	// two host paths: which guest path survives depends on the engine's bind
	// mounts, and a missing token file is indistinguishable from "no engine".
	return [join(agentDir, TOKEN_FILE_NAME), join(home, ".pi", TOKEN_FILE_NAME)];
}

function sha256(value: string): Buffer {
	return createHash("sha256").update(value, "utf8").digest();
}

/** Constant-time compare that does not leak length. */
function tokenMatches(supplied: string, expected: string): boolean {
	return timingSafeEqual(sha256(supplied), sha256(expected));
}

function bearerOf(request: IncomingMessage): string {
	const raw = request.headers.authorization;
	if (typeof raw === "string" && raw.toLowerCase().startsWith("bearer ")) {
		return raw.slice(7).trim();
	}
	const header = request.headers["x-pi-android-highlight-token"];
	if (typeof header === "string") {
		return header.trim();
	}
	return "";
}

function send(response: ServerResponse, status: number, envelope: Envelope): void {
	const body = JSON.stringify(envelope);
	response.writeHead(status, {
		"content-type": "application/json; charset=utf-8",
		"content-length": Buffer.byteLength(body),
		"cache-control": "no-store",
		// Closing per response means no keep-alive socket can outlive the request.
		connection: "close",
	});
	response.end(body);
}

function fail(
	response: ServerResponse,
	status: number,
	code: string,
	reason: string,
	hint?: string,
): void {
	send(response, status, hint === undefined ? { ok: false, code, reason } : { ok: false, code, reason, hint });
}

interface BodyResult {
	text: string | null;
	/** Set when the caller sent more than the cap. */
	tooLarge: boolean;
}

function readBody(request: IncomingMessage): Promise<BodyResult> {
	return new Promise((resolve) => {
		const chunks: Buffer[] = [];
		let size = 0;
		let done = false;
		const finish = (result: BodyResult): void => {
			if (done) {
				return;
			}
			done = true;
			resolve(result);
		};
		request.on("data", (chunk: Buffer) => {
			size += chunk.length;
			if (size > MAX_BODY_BYTES) {
				finish({ text: null, tooLarge: true });
				return;
			}
			chunks.push(chunk);
		});
		request.on("end", () => finish({ text: Buffer.concat(chunks).toString("utf8"), tooLarge: false }));
		request.on("error", () => finish({ text: null, tooLarge: false }));
	});
}

interface HighlightRequest {
	code?: unknown;
	language?: unknown;
}

function handleHighlight(response: ServerResponse, request: IncomingMessage): Promise<void> {
	return readBody(request).then((body) => {
		if (body.tooLarge) {
			return fail(
				response,
				413,
				"TOO_LARGE",
				`请求体超过上限（${MAX_BODY_BYTES} 字节）。`,
				"代码块过大时请直接以纯文本渲染，不必高亮。",
			);
		}
		let parsed: HighlightRequest;
		try {
			parsed = JSON.parse(body.text ?? "") as HighlightRequest;
		} catch {
			return fail(response, 400, "BAD_REQUEST", "请求体不是合法 JSON。");
		}
		if (typeof parsed.code !== "string" || parsed.code.length === 0) {
			return fail(response, 400, "BAD_REQUEST", "缺少 code 字段（字符串，且不能为空）。");
		}
		if (typeof parsed.language !== "string" || parsed.language.trim().length === 0) {
			return fail(
				response,
				400,
				"BAD_REQUEST",
				"缺少 language 字段。pi 不做语言猜测，Android 端也不会传空语言。",
			);
		}
		if (parsed.code.length > MAX_CODE_CHARS) {
			return fail(
				response,
				413,
				"TOO_LARGE",
				`代码块 ${parsed.code.length} 字符，超过上限 ${MAX_CODE_CHARS}。`,
			);
		}
		const language = parsed.language.trim();

		// An empty listing means highlight.js itself could not be found next to pi.
		// That is a configuration failure worth naming, not "unknown language":
		// otherwise every block would silently render plain.
		let available: Set<string>;
		try {
			available = knownLanguages();
		} catch (error) {
			return fail(
				response,
				503,
				"ENGINE_UNAVAILABLE",
				`找不到 highlight.js：${error instanceof Error ? error.message : String(error)}。`,
			);
		}
		if (available.size === 0) {
			return fail(
				response,
				503,
				"ENGINE_UNAVAILABLE",
				`highlight.js 没有列出任何语言（${loadState().error ?? "未知原因"}）。`,
			);
		}

		// Unknown language is not an error: it is pi's normal "render plain" path
		// (hcl/graphql/fish/sass are not in highlight.js 10.7.3 at all). Aliases
		// count as known — `html` and `toml` have no file of their own but pi
		// colours them, so a directory listing alone would under-report.
		if (!knowsLanguage(language)) {
			return send(response, 200, {
				ok: true,
				data: { language, known: false, spans: [], codeUnits: parsed.code.length, hljs: loadState().hljsVersion },
			});
		}

		let result;
		try {
			result = highlightToRuns(parsed.code, language);
		} catch (error) {
			return fail(
				response,
				503,
				"ENGINE_UNAVAILABLE",
				`highlight.js 加载失败：${error instanceof Error ? error.message : String(error)}。`,
			);
		}
		if (result === null) {
			return fail(
				response,
				503,
				"ENGINE_UNAVAILABLE",
				`highlight.js 未能加载语言 ${language}。`,
				"引擎重启后会自动恢复；在此期间代码块以纯文本显示。",
			);
		}
		return send(response, 200, {
			ok: true,
			data: {
				language,
				known: true,
				spans: result.runs,
				// The client refuses the whole answer when this does not equal the
				// length of the code it sent: a mismatch would mean every offset is
				// shifted, which is worse than no colour.
				codeUnits: result.codeUnits,
				ms: result.ms,
				hljs: result.hljsVersion,
			},
		});
	});
}

function handleHealth(response: ServerResponse, port: number): void {
	const state = loadState();
	send(response, 200, {
		ok: true,
		data: {
			service: SERVICE_NAME,
			version: SERVICE_VERSION,
			port,
			pid: process.pid,
			// `located` is true once the module has been *found*; no language is
			// loaded until a request asks for one.
			located: state.located,
			engineLoaded: state.engineLoaded,
			hljs: state.hljsVersion,
			registeredLanguages: state.registeredLanguages,
			knownLanguages: state.located ? knownLanguages().size : 0,
			error: state.error,
		},
	});
}

async function route(
	request: IncomingMessage,
	response: ServerResponse,
	token: string,
	port: number,
): Promise<void> {
	if (!tokenMatches(bearerOf(request), token)) {
		// Nothing has been read from the request at this point, on purpose: an
		// unauthenticated caller must not be able to make this process work.
		return fail(response, 401, "UNAUTHORIZED", "highlight 服务 token 无效或缺失。");
	}

	const path = (request.url ?? "/").split("?")[0];
	if (path === "/health" && (request.method === "GET" || request.method === "HEAD")) {
		return handleHealth(response, port);
	}
	if (path === "/highlight" && request.method === "POST") {
		return handleHighlight(response, request);
	}
	if (path === "/health" || path === "/highlight") {
		return fail(response, 405, "METHOD_NOT_ALLOWED", `${path} 不接受 ${request.method ?? "该"} 方法。`);
	}
	return fail(response, 404, "NOT_FOUND", `未知路径 ${path}。`);
}

function listen(server: Server, port: number, attempt: number): Promise<number> {
	return new Promise((resolve, reject) => {
		const onError = (error: NodeJS.ErrnoException): void => {
			server.removeListener("listening", onListening);
			if (error.code === "EADDRINUSE" && attempt + 1 < PORT_ATTEMPTS) {
				resolve(listen(server, port + 1, attempt + 1));
				return;
			}
			reject(error);
		};
		const onListening = (): void => {
			server.removeListener("error", onError);
			resolve(port);
		};
		server.once("error", onError);
		server.once("listening", onListening);
		// Loopback only. Binding the wildcard address would put a JavaScript
		// grammar runner on the phone's LAN.
		server.listen(port, "127.0.0.1");
	});
}

export interface StartOptions {
	port?: number;
	tokenFilePaths?: string[];
}

/**
 * Start the service. Idempotence is the caller's problem (`./index` keeps one
 * instance per process); this function always creates a server.
 */
export async function startHighlightService(options: StartOptions = {}): Promise<HighlightServiceHandle> {
	const token = randomBytes(32).toString("base64url");
	const requested = options.port ?? Number(process.env.PI_ANDROID_HIGHLIGHT_PORT ?? DEFAULT_PORT);
	const server = createServer((request, response) => {
		void route(request, response, token, boundPort).catch(() => {
			try {
				fail(response, 500, "INTERNAL", "highlight 服务内部错误。");
			} catch {
				response.destroy();
			}
		});
	});
	// A malformed request must not be able to hold a socket open.
	server.headersTimeout = 5_000;
	server.requestTimeout = 10_000;
	server.on("clientError", (_error, socket) => socket.destroy());
	// Never keep pi's event loop alive: the engine's own handles decide when the
	// process exits, and an idle highlight service is not a reason to stay up.
	server.unref();

	let boundPort = Number.isFinite(requested) && requested > 0 ? requested : DEFAULT_PORT;
	boundPort = await listen(server, boundPort, 0);

	const tokenFiles: string[] = [];
	const payload = `${JSON.stringify(
		{
			service: SERVICE_NAME,
			version: SERVICE_VERSION,
			port: boundPort,
			token,
			pid: process.pid,
			createdAt: Date.now(),
		},
		null,
		2,
	)}\n`;
	for (const path of options.tokenFilePaths ?? defaultTokenPaths()) {
		if (await writeTokenFile(path, payload)) {
			tokenFiles.push(path);
		}
	}

	return {
		port: boundPort,
		token,
		tokenFiles,
		async close(): Promise<void> {
			for (const path of tokenFiles) {
				await rm(path, { force: true }).catch(() => undefined);
			}
			const withConnections = server as Server & { closeAllConnections?: () => void };
			// Every response already closed its own connection; this covers a
			// request that was still in flight at shutdown.
			withConnections.closeAllConnections?.();
			await new Promise<void>((resolve) => {
				const timer = setTimeout(resolve, 250);
				server.close(() => {
					clearTimeout(timer);
					resolve();
				});
			});
		},
	};
}
