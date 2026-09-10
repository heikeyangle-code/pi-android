/**
 * highlight.js HTML → character-range runs.
 *
 * This is a port of pi's `renderHighlightedHtml` +
 * `packages/coding-agent/src/utils/syntax-highlight.ts` (10.7.3 output), with one
 * substitution: pi emits *coloured text* (it applies its theme and hands the
 * string to the terminal), whereas the Android app needs **offsets** it can turn
 * into Compose `AnnotatedString` spans. The walk, the scope stack, the entity
 * decoding and the "innermost open span wins" rule are pi's, unchanged, so the
 * boundaries are the ones pi would have coloured.
 *
 * Two details are load-bearing and were copied deliberately rather than
 * reinvented:
 *
 *  - the span-open check (`isSpanOpenTagStart`) requires the char after `<span`
 *    to be a tag terminator, so a literal `<spanish` in the code is not mistaken
 *    for a tag — a real failure mode when highlighting prose or HTML snippets;
 *  - `decodeHtmlEntityAt` is pi's, including its 16-character cap and the
 *    "unknown entity stays literal" fallback. Offsets are counted in *decoded*
 *    UTF-16 units, which is what Compose's AnnotatedString indexes, so a single
 *    mistake here would slide every colour in the block (or, worse, paint the
 *    wrong tokens). The caller compares the returned `codeUnits` with the length
 *    of the original code string and refuses the whole result on a mismatch.
 */

/** Half-open UTF-16 range `[start, end)` plus the hljs scopes open over it. */
export interface HighlightRun {
	/** First UTF-16 index of the run in the *decoded* text. */
	start: number;
	/** One past the last UTF-16 index. */
	end: number;
	/** Open highlight.js scopes, outermost first. Never empty in a result. */
	scopes: string[];
}

export interface DecodedEntity {
	text: string;
	/** Characters consumed in the *source* HTML, including `&` and `;`. */
	length: number;
}

const SPAN_CLOSE = "</span>";
const HIGHLIGHT_CLASS_PREFIX = "hljs-";

/** pi's own entity table (`utils/html.ts`), minus nothing. */
function decodeHtmlEntity(entity: string): string | undefined {
	switch (entity) {
		case "amp":
			return "&";
		case "lt":
			return "<";
		case "gt":
			return ">";
		case "quot":
			return '"';
		case "apos":
			return "'";
	}

	if (entity.startsWith("#x") || entity.startsWith("#X")) {
		return decodeCodePoint(Number.parseInt(entity.slice(2), 16));
	}

	if (entity.startsWith("#")) {
		return decodeCodePoint(Number.parseInt(entity.slice(1), 10));
	}

	return undefined;
}

function decodeCodePoint(codePoint: number): string | undefined {
	if (!Number.isInteger(codePoint) || codePoint < 0 || codePoint > 0x10ffff) {
		return undefined;
	}
	return String.fromCodePoint(codePoint);
}

/**
 * Decode the entity starting at `index` (which must be a `&`), or `undefined`
 * when the text is not an entity this renderer knows — in which case the caller
 * keeps the `&` as ordinary text, exactly as pi does.
 */
export function decodeHtmlEntityAt(html: string, index: number): DecodedEntity | undefined {
	const semicolonIndex = html.indexOf(";", index + 1);
	if (semicolonIndex === -1 || semicolonIndex - index > 16) {
		return undefined;
	}

	const entity = html.slice(index + 1, semicolonIndex);
	const decoded = decodeHtmlEntity(entity);
	if (decoded === undefined) {
		return undefined;
	}

	return { text: decoded, length: semicolonIndex - index + 1 };
}

function getScopeFromSpanTag(tag: string): string | undefined {
	const match = /\sclass\s*=\s*(?:"([^"]*)"|'([^']*)')/.exec(tag);
	const classValue = match?.[1] ?? match?.[2];
	if (!classValue) {
		return undefined;
	}

	for (const className of classValue.split(/\s+/)) {
		if (className.startsWith(HIGHLIGHT_CLASS_PREFIX)) {
			return className.slice(HIGHLIGHT_CLASS_PREFIX.length);
		}
	}

	return undefined;
}

function isSpanOpenTagStart(html: string, index: number): boolean {
	if (!html.startsWith("<span", index)) {
		return false;
	}
	const nextChar = html[index + "<span".length];
	return nextChar === ">" || nextChar === " " || nextChar === "\t" || nextChar === "\n" || nextChar === "\r";
}

function sameScopes(a: string[], b: string[]): boolean {
	if (a.length !== b.length) {
		return false;
	}
	for (let i = 0; i < a.length; i++) {
		if (a[i] !== b[i]) {
			return false;
		}
	}
	return true;
}

export interface HtmlRunsResult {
	runs: HighlightRun[];
	/**
	 * Number of UTF-16 code units in the decoded text. The caller compares this
	 * with the original code's length: equal means every offset in `runs` lands
	 * on the character it was measured against.
	 */
	codeUnits: number;
}

/**
 * Walk highlight.js HTML and return the runs that carry a scope.
 *
 * Text without any open `hljs-*` span produces no run at all: pi renders it with
 * the block's own colour, which is what "no span" already means on the Compose
 * side, and dropping it keeps the payload small (a code block has far more
 * punctuation than tokens).
 */
export function htmlToRuns(html: string): HtmlRunsResult {
	const runs: HighlightRun[] = [];
	/** Start of the text currently being accumulated, or -1 when idle. */
	let bufferStart = -1;
	/** UTF-16 index in the decoded text. */
	let position = 0;
	const scopes: Array<string | undefined> = [];

	const openBuffer = (): void => {
		if (bufferStart < 0) {
			bufferStart = position;
		}
	};

	const flush = (end: number): void => {
		if (bufferStart < 0) {
			return;
		}
		const defined: string[] = [];
		for (const scope of scopes) {
			if (scope !== undefined) {
				defined.push(scope);
			}
		}
		if (defined.length > 0 && end > bufferStart) {
			const previous = runs[runs.length - 1];
			if (previous && previous.end === bufferStart && sameScopes(previous.scopes, defined)) {
				// Adjacent text under the same scope stack: one run, not two.
				// highlight.js already merges such spans, but an entity between
				// them ("&lt;") splits the buffer on the way through.
				previous.end = end;
			} else {
				runs.push({ start: bufferStart, end, scopes: defined });
			}
		}
		bufferStart = -1;
	};

	let index = 0;
	while (index < html.length) {
		if (isSpanOpenTagStart(html, index)) {
			const tagEndIndex = html.indexOf(">", index + 5);
			if (tagEndIndex !== -1) {
				flush(position);
				const tag = html.slice(index, tagEndIndex + 1);
				scopes.push(getScopeFromSpanTag(tag));
				index = tagEndIndex + 1;
				continue;
			}
		}

		if (html.startsWith(SPAN_CLOSE, index)) {
			flush(position);
			if (scopes.length > 0) {
				scopes.pop();
			}
			index += SPAN_CLOSE.length;
			continue;
		}

		if (html[index] === "&") {
			const decoded = decodeHtmlEntityAt(html, index);
			if (decoded) {
				openBuffer();
				position += decoded.text.length;
				index += decoded.length;
				continue;
			}
		}

		// An unmatched surrogate half also counts as one UTF-16 unit, which is
		// why this is a plain +1 rather than an iteration over code points.
		openBuffer();
		position += 1;
		index += 1;
	}

	flush(position);
	return { runs, codeUnits: position };
}
