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

The scan honours Kotlin's lexical rules - line comments, block comments (tracking depth),
double-quoted strings, triple-quoted raw strings and character literals - so a slash-star
inside a string is not reported, which is what makes this usable on real sources (a MIME
type such as image slash star is legitimate).

Usage:  python3 tools/check-nested-comments.py [root ...]
Exit:   0 clean, 1 nesting found, 2 nothing scanned (so a broken invocation cannot look
        like a pass).
"""

from __future__ import annotations

import sys
from pathlib import Path


def scan(path: Path) -> list[tuple[int, str]]:
    """Return (line, snippet) for every block comment opened while already inside one."""
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as exc:  # a source file we cannot read is not a pass
        return [(0, f"could not read: {exc}")]

    hits: list[tuple[int, str]] = []
    depth = 0
    line_start = 0
    i = 0
    n = len(text)

    def line_of(pos: int) -> int:
        return text.count("\n", 0, pos) + 1

    while i < n:
        ch = text[i]

        if ch == "\n":
            i += 1
            continue

        # --- inside a block comment -------------------------------------------------
        if depth > 0:
            if text.startswith("/*", i):
                hits.append((line_of(i), text[line_start : text.find("\n", i) if "\n" in text[i:] else n].strip()))
                depth += 1
                i += 2
                continue
            if text.startswith("*/", i):
                depth -= 1
                i += 2
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
        for line, snippet in scan(path):
            problems += 1
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
