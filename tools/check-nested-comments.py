#!/usr/bin/env python3
"""Fail on a block comment opened inside another block comment in a .kt file.

Why this exists: Kotlin block comments NEST. Writing a path with a trailing slash-star
inside a KDoc - the way one writes `ui/` followed by two asterisks - opens a second
comment, which the closing delimiter of the KDoc then closes, leaving the outer comment
unterminated. The compiler swallows the rest of the file and reports a cascade of
unrelated errors at the top of it: misparsed declarations, "Parameter name expected",
"'key' overrides nothing", and finally one honest "Unclosed comment" at the last line.
Nothing in that output points at the line that is actually wrong.

That has happened five times in this repository, in five different files. Each time it
cost a full CI cycle and each time the fix was one character. Warnings in task briefs do
not work; a check does.

**The mirror image of the same mistake, found later:** a `*/` *inside* a KDoc closes it
early, and the remaining `*`-continuation lines become code. That is how a documented
MIME type - star, slash, star - broke `bridge/DeviceSystemActions.kt` and produced 32
"Expecting member declaration" errors in the APK jobs (the nesting check above ran green,
because no `/*` was involved). So the scan reports that too, and it can do it precisely
rather than heuristically: if the closer is followed, after any number of *blank* lines,
by a line that still begins with `*`, then whoever wrote the comment did not mean to close
it there. That signal is why the check does not just flag every `*/` - the close of a
one-line comment (`/** x */ fun f() {}`) is followed by code, and stays quiet.

The scan honours Kotlin's lexical rules - line comments, block comments (tracking depth),
double-quoted strings, triple-quoted raw strings and character literals - so a slash-star
inside a string is not reported, which is what makes this usable on real sources (a MIME
type such as image slash star is legitimate).

Usage:  python3 tools/check-nested-comments.py [root ...]
Exit:   0 clean, 1 nesting or early close found, 2 nothing scanned (so a broken
        invocation cannot look like a pass).
"""

from __future__ import annotations

import sys
from pathlib import Path


def scan(path: Path) -> list[tuple[int, str, str]]:
    """Return (line, snippet, kind) for every suspect block comment.

    kind is "nested" (a `/*` opened inside a comment) or "early" (a `*/` that closed a
    comment whose continuation lines follow it, so it closed before the author meant it
    to).
    """
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:  # a source file we cannot read is not a pass
        return [(0, f"could not read: {exc}", "nested")]

    hits: list[tuple[int, str, str]] = []
    depth = 0

    i = 0
    n = len(text)

    def line_of(pos: int) -> int:
        return text.count("\n", 0, pos) + 1

    def line_text(pos: int) -> str:
        """The whole line containing `pos`, trimmed.

        `pos` is absolute in the file; the line start is searched backwards rather than
        carried in a variable, because the original code's `line_start` was never updated
        and so printed the file's *first* line as the snippet.
        """
        start = text.rfind("\n", 0, pos) + 1
        end = text.find("\n", pos)
        return text[start : n if end == -1 else end].strip()

    def next_line_starts_comment_continuation(pos: int) -> bool:
        """True when the first non-blank line after `pos` begins with `*`.

        Blank lines are skipped (a KDoc paragraph gap is `*` on its own, so a blank line
        is not where a premature close hides); the first line that has any content decides.
        """
        j = text.find("\n", pos)
        if j == -1:
            return False
        while True:
            k = j + 1
            while k < n and text[k] in " \t\r":
                k += 1
            if k >= n:
                return False
            if text[k] == "\n":  # blank line - keep looking
                j = k
                continue
            return text[k] == "*"

    while i < n:
        ch = text[i]

        if ch == "\n":
            i += 1
            continue

        # --- inside a block comment -------------------------------------------------
        if depth > 0:
            if text.startswith("/*", i):
                hits.append((line_of(i), line_text(i), "nested"))
                depth += 1
                i += 2
                continue
            if text.startswith("*/", i):
                depth -= 1
                close_at = i
                i += 2
                if depth == 0 and next_line_starts_comment_continuation(i):
                    hits.append((line_of(close_at), line_text(close_at), "early"))
                continue
            i += 1
            continue

        # --- normal code ------------------------------------------------------------
        if text.startswith("//", i):
            nl = text.find("\n", i)
            i = n if nl == -1 else nl
            continue
        if text.startswith("/*", i):
            depth += 1
            i += 2
            continue
        if text.startswith('"""', i):
            end = text.find('"""', i + 3)
            i = n if end == -1 else end + 3
            continue
        if ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if ch == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        i += 1

    return hits


def main(argv: list[str]) -> int:
    roots = [Path(a) for a in argv[1:]] or [Path("app/src"), Path("rpc/src")]
    files = sorted(p for root in roots if root.exists() for p in root.rglob("*.kt"))
    if not files:
        print(f"nothing scanned under {', '.join(str(r) for r in roots)}", file=sys.stderr)
        return 2

    problems = 0
    for path in files:
        for line, snippet, kind in scan(path):
            problems += 1
            if kind == "early":
                print(f"::error file={path},line={line}::block comment closed early here: {snippet}")
                print(f"{path}:{line}: early block-comment close - this line's `*/` ends the "
                      f"comment, and the next non-blank line still starts with `*`, so those "
                      f"lines are code now. A MIME type (star-slash-star) or any `*/` inside a "
                      f"KDoc does this; write the three characters apart, or end the comment here.")
            else:
                print(f"::error file={path},line={line}::nested block comment opened here: {snippet}")
                print(f"{path}:{line}: nested block comment - a `/*` inside a KDoc swallows the "
                      f"rest of the file. Kotlin block comments nest; write `ui/` rather than `ui/**`.")

    if problems:
        print(f"\nnested-comments: FAILED - {problems} occurrence(s) in {len(files)} file(s)")
        return 1
    print(f"nested-comments: OK ({len(files)} Kotlin file(s) scanned)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
