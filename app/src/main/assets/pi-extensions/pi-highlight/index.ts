/**
 * pi-highlight — a pi extension that lets the Android app use **pi's own**
 * highlighter.
 *
 * The app renders markdown itself, and code fences need syntax colours. Running
 * highlight.js on Android means reimplementing a JavaScript engine (Rhino) and
 * paying 60-100 ms per block for it (`docs/syntax-highlight-eval.md`). This
 * guest already runs Node and already contains highlight.js 10.7.3 as one of
 * pi's dependencies, where a 24-line Kotlin block takes well under a
 * millisecond. So the extension publishes that engine on loopback and the app
 * calls it (`./service` documents the wire contract).
 *
 * Activation cost, deliberately: a 32-byte token, one small JSON file, and a
 * listening socket. **No language is registered and highlight.js is not even
 * required until the first authenticated `/highlight` request**, so a session
 * with no code blocks pays nothing at all. The socket is `unref()`ed and the
 * extension starts no timers, so it cannot keep pi alive or wake it up.
 *
 * The extension registers no tools and no commands: the consumer is the Android
 * app, not the model. It is the mirror image of `pi-android-bridge`, which is
 * the app serving the *model*.
 */

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { startHighlightService, type HighlightServiceHandle } from "./service";

/**
 * pi reloads extensions on `/reload`, and jiti may re-evaluate this module. The
 * handle lives on `globalThis` so a reload reuses the running service instead of
 * binding a second port and overwriting the token file with one the app cannot
 * see.
 */
const HANDLE_KEY = "__piAndroidHighlightService";

type GlobalWithService = typeof globalThis & {
	[HANDLE_KEY]?: Promise<HighlightServiceHandle | null>;
};

export default function activate(_pi: ExtensionAPI): void {
	const scope = globalThis as GlobalWithService;
	if (scope[HANDLE_KEY]) {
		return;
	}
	scope[HANDLE_KEY] = startHighlightService().catch(() => {
		// A failed start must never break pi: the app falls back to plain code
		// blocks, exactly as it does when no engine is running.
		return null;
	});
}

/** Diagnostics for the verification harness; not part of pi's API. */
export async function startedService(): Promise<HighlightServiceHandle | null> {
	const scope = globalThis as GlobalWithService;
	return scope[HANDLE_KEY] ?? null;
}
