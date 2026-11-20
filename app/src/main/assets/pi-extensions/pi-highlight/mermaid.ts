/**
 * Lazily loaded `grok-mermaid` — the renderer pi itself uses for ` ```mermaid `
 * blocks.
 *
 * Why this file exists: pi does **not** hand mermaid to highlight.js. It lexes the
 * markdown, finds top-level mermaid fences, runs `render(source)` from
 * `grok-mermaid` and re-emits the returned box-drawing art as inline code, colouring
 * each run by its semantic class (`modes/interactive/components/mermaid.ts:38-56`).
 * The art is a *layout* result, not a token stream — a phone cannot reproduce it by
 * colouring the source, and hand-porting the flowchart/sequence/class/ER/state layout
 * engine to Kotlin would not be the same renderer. So the engine stays where pi put
 * it (next to pi, in pi's own dependency tree) and the Android side keeps what it
 * already owns everywhere else: the **colour**, i.e. the class → pi-token mapping.
 *
 * That division is the same one `./hljs` follows, and so is the cost model:
 *
 *  - **nothing is loaded at activation.** Path resolution only. `grok-mermaid` — an
 *    ES module with a layout engine behind it — is imported on the first
 *    authenticated `/mermaid` request, never before;
 *  - the version is pi's, resolved from pi's package roots (`./hljs` exports the
 *    anchor list), so the art is the art pi draws, not a lookalike;
 *  - a missing module is an error the service can name (`ENGINE_UNAVAILABLE`), while
 *    a source `grok-mermaid` refuses to draw is `null` — pi's own "there is no art
 *    to show", after which both sides keep the fence's source.
 */

import { createRequire } from "node:module";
import { pathToFileURL } from "node:url";
import { anchors } from "./hljs";

/** One run of adjacent cells sharing one semantic class; `grok-mermaid`'s `Span`. */
export interface MermaidRun {
	text: string;
	/**
	 * `border` | `text` | `edge` | `edgeLabel` | `title` | `none`
	 * (`grok-mermaid` `dist/types.d.ts`). Passed through as-is: the Kotlin side owns
	 * the class → pi-token table, exactly as it owns the hljs scope table.
	 */
	cls: string;
}

export interface MermaidArt {
	/** One entry per art row; each row is the runs that tile it. */
	rows: MermaidRun[][];
	/** Display columns the widest row needs — `grok-mermaid`'s own number. */
	width: number;
	/** Source the flowchart grammar could not read; advisory, never a reason to withhold art. */
	warnings: string[];
}

/** The slice of `grok-mermaid` this service uses. Structural, so nothing is imported eagerly. */
interface MermaidModule {
	render(src: string): { styled: Array<Array<{ text: string; cls?: string; role?: string }>>; width: number; warnings?: string[] } | null;
}

let loaded: MermaidModule | null = null;
let pending: Promise<MermaidModule | null> | undefined;
let loadError: string | null = null;

/** Diagnostics for `/health`; reading this never loads anything. */
export interface MermaidLoadState {
	located: boolean;
	error: string | null;
}

export function mermaidState(): MermaidLoadState {
	return { located: loaded !== null, error: loadError };
}

/**
 * Import `grok-mermaid` from pi's own package roots, once.
 *
 * Resolved with `createRequire(...).resolve("grok-mermaid")` (path resolution, no
 * execution) and then imported **dynamically**, because the package is ESM-only
 * (`"type": "module"`, `exports: { ".": "./dist/index.js" }` in its package.json)
 * while this extension is loaded by pi through jiti. `await import()` is the one
 * form that works under either module system.
 *
 * @throws when no anchor can resolve it — the caller turns that into
 *   `ENGINE_UNAVAILABLE`, because "the module is not installed next to pi" is a
 *   configuration failure worth naming, not the same thing as "this source has no art".
 */
export async function locateMermaid(): Promise<MermaidModule> {
	if (loaded) {
		return loaded;
	}
	if (!pending) {
		pending = (async () => {
			const tried: string[] = [];
			// Pi 1.5.2 renamed `grok-mermaid` to `lovely-mermaid`; probe both so every pi build works.
			const packages = ["lovely-mermaid", "grok-mermaid"];
			for (const anchor of anchors()) {
				tried.push(anchor);
				let entry: string | null = null;
				for (const pkg of packages) {
					try {
						entry = createRequire(anchor).resolve(pkg);
						break;
					} catch {
						// try next package name
					}
				}
				if (!entry) {
					continue;
				}
				try {
					const module = (await import(pathToFileURL(entry).href)) as { render?: unknown };
					if (typeof module.render !== "function") {
						loadError = `mermaid 模块没有导出 render()：${entry}`;
						continue;
					}
					loaded = module as unknown as MermaidModule;
					loadError = null;
					return loaded;
				} catch (error) {
					loadError = error instanceof Error ? error.message : String(error);
				}
			}
			loadError = `找不到 lovely-mermaid / grok-mermaid（它应当是 pi 自己的依赖）。已尝试：${tried.join("、")}`;
			return null;
		})();
	}
	const located = await pending;
	if (!located) {
		pending = undefined;
		throw new Error(loadError ?? "找不到 lovely-mermaid / grok-mermaid。");
	}
	return located;
}

export async function renderArt(source: string): Promise<MermaidArt | null> {
	const active = await locateMermaid();
	const art = active.render(source);
	if (!art) {
		return null;
	}
	return {
		rows: art.styled.map((row) =>
			row.map((span) => ({ text: span.text, cls: span.cls ?? span.role ?? "text" })),
		),
		width: art.width,
		warnings: art.warnings ?? [],
	};
}
