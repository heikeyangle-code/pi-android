package app.pi.ui.render.rich

/**
 * The parsed form of a fenced code block's **info string** — what the JetBrains
 * Markdown parser hands to `MarkdownCodeFence` as `FENCE_LANG`
 * (`org.jetbrains:markdown`'s `MarkdownCodeFence.info`) and what
 * `docs/plans/rich-rendering-upgrade.md` §三.1 calls "代码围栏附加属性".
 *
 * ## The defect this type exists for
 *
 * `FENCE_LANG` is the **whole** info string, not just the language. The current
 * `PiCodeLanguage.normalize()` lowercases that whole string and hands it to the
 * highlighter, so
 *
 * ```text
 * ```typescript title="src/main.ts"
 * ```
 *
 * asks highlight.js for the language `typescript title="src/main.ts"` — which is
 * not a language, so that block renders uncoloured. Phase 1 needs two separate
 * answers out of the same string: a language for the highlighter/classifier, and
 * a file name for the block's header.
 *
 * ## The grammar accepted here
 *
 * The info string is a whitespace-separated list of **bare words** and
 * `key=value` **attributes**, with two small allowances:
 *
 *  - values may be single- or double-quoted, so they may contain spaces
 *    (`title="src/main.ts"`, `title='my notes.md'`);
 *  - `{` and `}` act as separators (the Pandoc `{.kotlin}` shape), and a leading
 *    `.` is dropped from a language word (`.kotlin` → `kotlin`).
 *
 * Everything else is deliberately literal:
 *
 *  - **`language`** is the first bare word that is a plausible language name
 *    (letters/digits plus `_ + # - .`, at most [MAX_LANGUAGE_CHARS] characters),
 *    lower-cased. A bare word cannot be mistaken for the file-name keys, so
 *    `title ts` still finds `ts`; an invalid token such as `<script>` is skipped
 *    rather than becoming the language.
 *  - **`title`** comes from `title=`, `filename=` or `file=`, in that precedence
 *    order (a later duplicate of the *same* key wins, as with any attribute).
 *    `title=""` counts as absent. Empty titles never hide the next key.
 *  - **`attributes`** keeps every well-formed `key=value`, including the three
 *    file-name keys, keyed by lower-cased name. Values are **raw text**: nothing
 *    is executed, and the only escapes recognised are `\"`, `\'` and `\\`
 *    *inside a quoted value* — no shell expansion, no HTML entity decoding, no
 *    variable interpolation (`$HOME` stays the five characters `$HOME`).
 *  - **`raw`** is the info string exactly as received, so a caller that copies
 *    the fence or re-displays its header never loses what the model wrote.
 *
 * ## Corruption is local
 *
 * A malformed attribute may only cost **itself**. ` ```ts title="unclosed ` still
 * yields `language = "ts"`, `title = null`: the unterminated quote ends the scan
 * (there is no reliable way to know where the value stops) but does not revoke a
 * language already found. Likewise ` ```ts title = "x" ` degrades to language
 * `ts` with no title: the `=` must directly follow the key, so `title` stays a
 * bare word, is recognised as a file-name key, and is *not* promoted to language.
 *
 * Pure Kotlin: no Compose, no Android. The type is compiled into the bare-JVM
 * harnesses (`FenceInfoCheck.kt`) alongside its callers.
 */
data class FenceInfo(
    /** The lower-cased language word, or `""` when the fence named none. */
    val language: String,
    /** `title=` / `filename=` / `file=`, in that precedence order, or `null`. */
    val title: String?,
    /** Every well-formed attribute, lower-cased key → raw value. */
    val attributes: Map<String, String>,
    /** The info string exactly as received (`""` for a fence with none). */
    val raw: String,
) {
    /** True when the fence named no language, i.e. `FENCE_LANG` was empty. */
    val isEmpty: Boolean get() = language.isEmpty()

    companion object {
        /** A fence with no info string at all. */
        val EMPTY = FenceInfo(language = "", title = null, attributes = emptyMap(), raw = "")

        /** A language word longer than this is not a language, it is noise. */
        private const val MAX_LANGUAGE_CHARS = 48

        /** An attribute name longer than this is dropped (see [isAttributeName]). */
        private const val MAX_ATTRIBUTE_NAME_CHARS = 64

        /**
         * A value longer than this is dropped rather than truncated: a title is a
         * header, and a silently shortened path is worse than no path.
         */
        private const val MAX_ATTRIBUTE_VALUE_CHARS = 2048

        /** The three spellings of "the file this fence came from", in precedence order. */
        private val TITLE_KEYS = setOf("title", "filename", "file")

        /**
         * Parse a `FENCE_LANG` info string.
         *
         * `null` and `""` both mean "no info string"; [raw] keeps the empty string
         * for both, which is what the fence itself contains.
         *
         * @param raw the complete info string, typically
         *   `MarkdownCodeFence.info` — never the fence body.
         */
        fun parse(raw: String?): FenceInfo {
            if (raw == null) return EMPTY
            if (raw.isEmpty()) return EMPTY

            var language = ""
            var title: String? = null
            var filename: String? = null
            var file: String? = null
            val attributes = LinkedHashMap<String, String>()

            fun addAttribute(name: String, value: String) {
                if (!isAttributeName(name) || value.length > MAX_ATTRIBUTE_VALUE_CHARS) return
                val key = name.lowercase()
                attributes[key] = value
                if (value.isEmpty()) return
                when (key) {
                    "title" -> title = value
                    "filename" -> filename = value
                    "file" -> file = value
                }
            }

            fun considerBareWord(word: String) {
                if (language.isNotEmpty()) return
                val lower = word.lowercase()
                if (lower in TITLE_KEYS) return
                language = languageWord(word) ?: return
            }

            val text = raw
            val n = text.length
            var i = 0
            while (i < n) {
                while (i < n && text[i].isWhitespace()) i++
                if (i >= n) break

                // Pandoc-style grouping. Treated as separators, never as content.
                if (text[i] == '{' || text[i] == '}') {
                    i++
                    continue
                }

                val wordStart = i
                while (i < n) {
                    val ch = text[i]
                    if (ch.isWhitespace() || ch == '=' || ch == '"' || ch == '\'' ||
                        ch == '{' || ch == '}'
                    ) {
                        break
                    }
                    i++
                }
                val word = text.substring(wordStart, i)

                if (i < n && text[i] == '=') {
                    // key=value. The `=` must follow the key directly; whitespace
                    // before it is not allowed. Whitespace *after* `=` is skipped
                    // only when a quoted value follows, so `title= label=x` degrades
                    // to an empty title + a `label` attribute instead of swallowing
                    // `label=x` as the title.
                    i++
                    var valueLookahead = i
                    while (valueLookahead < n && text[valueLookahead].isWhitespace()) valueLookahead++

                    if (valueLookahead < n && (text[valueLookahead] == '"' || text[valueLookahead] == '\'')) {
                        i = valueLookahead
                        val quote = text[i]
                        i++
                        val value = StringBuilder()
                        var closed = false
                        while (i < n) {
                            val ch = text[i]
                            if (ch == '\\' && i + 1 < n) {
                                val next = text[i + 1]
                                if (next == '\\' || next == quote) {
                                    value.append(next)
                                    i += 2
                                    continue
                                }
                                // Any other escape is kept as written: values are
                                // not executed and are not decoded.
                                value.append(ch)
                                i++
                                continue
                            }
                            if (ch == quote) {
                                closed = true
                                i++
                                break
                            }
                            value.append(ch)
                            i++
                        }
                        if (!closed) {
                            // Corrupt attribute: drop it and stop scanning. A
                            // language found before this point is kept.
                            i = n
                            break
                        }
                        addAttribute(word, value.toString())
                    } else {
                        val valueStart = i
                        while (i < n && !text[i].isWhitespace()) i++
                        addAttribute(word, text.substring(valueStart, i))
                    }
                    continue
                }

                // `title"x"` / a stray quoted token: a malformed attribute. Skip
                // only the quoted run; the word before it still counts as a word
                // (so `ts"x"` keeps `ts` as the language).
                if (i < n && (text[i] == '"' || text[i] == '\'')) {
                    considerBareWord(word)
                    val next = skipQuoted(text, i)
                    if (next < 0) break
                    i = next
                    continue
                }

                considerBareWord(word)
            }

            return FenceInfo(
                language = language,
                // Precedence is by key, not by position: `filename="a" title="b"`
                // is title `b`, which is what the three spellings mean.
                title = title ?: filename ?: file,
                attributes = attributes,
                raw = raw,
            )
        }

        /**
         * A bare info-string word → a language, or `null` when it cannot be one.
         *
         * The character set is the union of the names fenced blocks actually use:
         * `kotlin`, `c++`, `c#`, `objective-c`, `f#`, `tsx`, `json`.
         */
        private fun languageWord(word: String): String? {
            val candidate = if (word.startsWith('.')) word.substring(1) else word
            if (candidate.isEmpty() || candidate.length > MAX_LANGUAGE_CHARS) return null
            if (!candidate[0].isLetterOrDigit()) return null
            if (!candidate.all { it.isLanguageChar() }) return null
            return candidate.lowercase()
        }

        private fun Char.isLanguageChar(): Boolean =
            isLetterOrDigit() || this == '_' || this == '+' || this == '#' ||
                this == '-' || this == '.'

        /** `key`-shaped words only; this is what keeps `data-x` and rejects `<x>`. */
        private fun isAttributeName(word: String): Boolean {
            if (word.isEmpty() || word.length > MAX_ATTRIBUTE_NAME_CHARS) return false
            return word.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ':' }
        }

        /**
         * Index just past the closing quote that starts at [start], or `-1` when
         * the quote never closes. Backslash escapes the next character, matching
         * the quoted-value reader above.
         */
        private fun skipQuoted(text: String, start: Int): Int {
            val quote = text[start]
            var i = start + 1
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == quote) return i + 1
                i++
            }
            return -1
        }
    }
}
