package app.pi.highlight

import app.pi.ui.render.PiSyntaxToken

/**
 * highlight.js scope → [PiSyntaxToken], transcribed 1:1 from pi's
 * `buildCliHighlightTheme` in
 * `packages/coding-agent/src/modes/interactive/theme/theme.ts` (line ~1036).
 *
 * The table is deliberately *here* and not in the Node extension. The extension
 * speaks raw highlight.js scopes — the backend's own vocabulary — and this side
 * owns the mapping onto pi's tokens, because [PiSyntaxToken] is the app's
 * contract and a future palette change must not require shipping a new guest
 * extension. It also keeps exactly one place that can disagree with
 * `PiCodeHighlight.kt`'s KDoc, instead of two.
 *
 * Resolution order is pi's `getScopeFormatter` + `getActiveFormatter`, in this
 * order:
 *
 *  1. the innermost open scope that resolves wins (`subst` inside `string`
 *     resolves through its parent, which is why runs carry a whole stack);
 *  2. exact scope;
 *  3. the scope's prefix before the first `.` (`meta.string` → `meta`);
 *  4. the scope's prefix before the first `-` (`meta-keyword` → `meta`);
 *  5. `default` — which pi's CLI theme does **not** define, so an unmatched
 *     scope renders in the block's own colour. There is no token for that here
 *     either: [tokenFor] returns `null` and the span is dropped.
 */
internal object PiHighlightScopes {

    private val table: Map<String, PiSyntaxToken> = mapOf(
        "keyword" to PiSyntaxToken.Keyword,
        "built_in" to PiSyntaxToken.Type,
        "literal" to PiSyntaxToken.Number,
        "number" to PiSyntaxToken.Number,
        "regexp" to PiSyntaxToken.StringLiteral,
        "string" to PiSyntaxToken.StringLiteral,
        "comment" to PiSyntaxToken.Comment,
        "doctag" to PiSyntaxToken.Comment,
        "meta" to PiSyntaxToken.Muted,
        "function" to PiSyntaxToken.Function,
        "title" to PiSyntaxToken.Function,
        "class" to PiSyntaxToken.Type,
        "type" to PiSyntaxToken.Type,
        "tag" to PiSyntaxToken.Punctuation,
        "name" to PiSyntaxToken.Keyword,
        "attr" to PiSyntaxToken.Variable,
        "variable" to PiSyntaxToken.Variable,
        "params" to PiSyntaxToken.Variable,
        "operator" to PiSyntaxToken.Operator,
        "punctuation" to PiSyntaxToken.Punctuation,
        "emphasis" to PiSyntaxToken.Emphasis,
        "strong" to PiSyntaxToken.Strong,
        "link" to PiSyntaxToken.Link,
        "addition" to PiSyntaxToken.DiffAdded,
        "deletion" to PiSyntaxToken.DiffRemoved,
    )

    /**
     * @param scopes the open scopes at one run, outermost first.
     * @return the token pi would colour that run with, or `null` for "leave it
     *   in the block's colour".
     */
    fun tokenFor(scopes: List<String>): PiSyntaxToken? {
        for (index in scopes.indices.reversed()) {
            resolve(scopes[index])?.let { return it }
        }
        return null
    }

    private fun resolve(scope: String): PiSyntaxToken? {
        table[scope]?.let { return it }
        val dot = scope.indexOf('.')
        if (dot > 0) table[scope.substring(0, dot)]?.let { return it }
        val dash = scope.indexOf('-')
        if (dash > 0) table[scope.substring(0, dash)]?.let { return it }
        return null
    }
}
