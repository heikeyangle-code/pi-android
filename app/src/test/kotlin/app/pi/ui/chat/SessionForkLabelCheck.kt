package app.pi.ui.chat

// A bare-JVM harness for the one rule behind a session row's 「来自〈父会话名〉」 line.
// Android-free on purpose (no `android.*`, no Compose), and it does not touch
// `PiSessionStore` — see `SessionForkLabel.kt` for why the rule was lifted out of the
// session screen at all.
//
// What it pins, and why each one is worth pinning:
//
//  1. **The name comes from pi's own field.** `parentSession` is written into the
//     child's header by `/fork`, `/clone` and `newSession({ parentSession })` alike
//     (`core/session-manager.ts:1681`、`:1854`；`docs/session-format.md:72-76`), and the
//     row shows the parent's `displayName` (session name if set, else first user
//     message). Both branches of that are pinned: a named parent and an unnamed one.
//  2. **A missing parent degrades, it does not invent.** When the parent is not among
//     the rows the answer is null, because the only other handle is the *file name* —
//     and printing `2024-06-01T00-00-00-000Z_parent.jsonl` as if it were a session name
//     is exactly the kind of made-up reading the docs forbid. The screen then says
//     only that the session has a parent.
//  3. **It matches on the file name's last segment only.** `parentSession` is a *guest*
//     path (pi resolves it inside the runtime) while the rows carry host `File`s; the
//     prefixes differ by construction. A path that merely *contains* a row's name must
//     not match, and a guest prefix must not be required to match.
//  4. **Nothing distinguishes fork from clone.** Both produce the same field and the
//     file has no second marker, so both resolve to the same relation. This assertion
//     exists so a future edit cannot quietly start claiming "forked" for a copy.
//  5. **Blank/absent fields are null, not an empty name** — an empty `parentSession`, a
//     trailing separator, or no field at all.
//  6. **One `id` in two rows cannot confuse it.** `PiSessionStore.list()` now keeps both
//     halves of a split conversation under one id (`mergeSameIdRow`'s
//     `PrefixRelation.Disjoint`). This rule's input has no `id` at all, and the two
//     halves are two different *file names* while `parentSession` records one *path* — so
//     the recorded half wins and the rule never picks between halves. See §6.

var failures = 0

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) {
        println("PASS $name")
    } else {
        failures++
        println("FAIL $name\n  expected: $expected\n  actual:   $actual")
    }
}

fun main() {
    val rows = listOf(
        SessionFileRow("2024-06-01T00-00-00-000Z_parent.jsonl", "父会话的第一句"),
        SessionFileRow("2024-06-01T00-00-10-000Z_named-parent.jsonl", "给父起的名字"),
    )

    // 1. The name pi records, resolved through the row it names.
    check(
        "1.1 有名字的父：取 pi 的会话名",
        forkParentNameOf("/root/.pi/agent/sessions/2024-06-01T00-00-10-000Z_named-parent.jsonl", rows),
        "给父起的名字",
    )
    check(
        "1.2 没起名的父：取那一行自己的显示名（第一句提问）",
        forkParentNameOf("/root/.pi/agent/sessions/2024-06-01T00-00-00-000Z_parent.jsonl", rows),
        "父会话的第一句",
    )
    // The guest prefix is not part of the comparison: a path from another root (the
    // grouped layout pi writes without `--session-dir`) still resolves.
    check(
        "1.3 分组布局的 guest 路径（目录不同、文件名相同）也能命中",
        forkParentNameOf("/root/.pi/agent/sessions/--root--/2024-06-01T00-00-00-000Z_parent.jsonl", rows),
        "父会话的第一句",
    )

    // 2. Missing parent → null, never the file name.
    check(
        "2.1 父不在列表里 → null（不许拿文件名当名字）",
        forkParentNameOf("/root/.pi/agent/sessions/2024-05-01T00-00-00-000Z_gone.jsonl", rows),
        null,
    )
    check(
        "2.2 路径里的目录名恰好等于某一行的文件名 → 不命中",
        forkParentNameOf("/root/.pi/agent/sessions/2024-06-01T00-00-00-000Z_parent.jsonl/extra.jsonl", rows),
        null,
    )

    // 3. Empty / absent / degenerate values.
    check("3.1 没有父字段 → null", forkParentNameOf(null, rows), null)
    check("3.2 空的父字段 → null", forkParentNameOf("", rows), null)
    check("3.3 只有空白的父字段 → null", forkParentNameOf("   ", rows), null)
    check("3.4 以分隔符结尾 → null（末段是空串，不是名字）", forkParentNameOf("/root/.pi/agent/sessions/", rows), null)
    check("3.5 列表为空 → null", forkParentNameOf("/root/.pi/agent/sessions/x.jsonl", emptyList()), null)

    // 4. `/fork` and `/clone` write the same field, so they read the same way: the rule
    //    says "came from X", never "was forked".
    //    The guard that actually bites: the answer is a *name* and nothing else. If this
    //    rule ever started returning "分叉自 X" / "复制自 X", the app would be claiming an
    //    action the file does not record.
    //    **The call site owes the same restraint, and this harness cannot check it** —
    //    `SessionsScreen.SessionRow` therefore prefixes the name with 「来自」, not
    //    「分叉自」. That is the whole reason the row's wording must not drift back: the
    //    function would keep passing while the screen lied.
    val answers = rows.map { forkParentNameOf("/root/.pi/agent/sessions/${it.fileName}", rows) }
    check(
        "4.1 结果只是名字，不含任何动作词（pi 的文件不记「分叉」还是「复制」）",
        answers.filter { it != null && (it.contains("分叉") || it.contains("复制")) },
        emptyList<String?>(),
    )
    check(
        "4.2 同一批行里两条子会话各自指向不同的父",
        listOf(
            forkParentNameOf("/root/.pi/agent/sessions/2024-06-01T00-00-00-000Z_parent.jsonl", rows),
            forkParentNameOf("/root/.pi/agent/sessions/2024-06-01T00-00-10-000Z_named-parent.jsonl", rows),
        ),
        listOf("父会话的第一句", "给父起的名字"),
    )

    // 5. Duplicate file names (the same conversation copied into both of pi's layouts):
    //    the first row wins, deterministically — the caller passes its own order, and
    //    `PiSessionStore.list` has already deduplicated by session identity.
    check(
        "5.1 同名两行：按调用方给的顺序取第一个，结果是确定的",
        forkParentNameOf(
            "/root/.pi/agent/sessions/dup.jsonl",
            listOf(SessionFileRow("dup.jsonl", "第一份"), SessionFileRow("dup.jsonl", "第二份")),
        ),
        "第一份",
    )

    // 6. **One `id`, two rows** — the shape `PiSessionStore.list()` now keeps
    //    (`mergeSameIdRow`'s `PrefixRelation.Disjoint`: a conversation split into two
    //    files that are not prefixes of each other). The rule is deliberately immune:
    //    its input is `(fileName, displayName)` and has **no `id` field at all**, and
    //    the two halves of a split are two *different files* (pi names them
    //    `<fileTimestamp>_<id>.jsonl`, so one id twice means two timestamps ⇒ two file
    //    names). `parentSession` records a *path*, so it can only ever name one of them
    //    — the half that was actually written down. Matching by id would have had to
    //    pick between the halves; matching by file name does not.
    check(
        "6.1 一个 id 两行（劈开的两半，文件名不同）：命中写下 path 的那一半，不去两半里挑",
        forkParentNameOf(
            "/root/.pi/agent/sessions/2024-06-01T00-00-00-000Z_split.jsonl",
            listOf(
                SessionFileRow("2024-06-01T00-00-00-000Z_split.jsonl", "前半截"),
                SessionFileRow("2024-06-01T00-00-30-000Z_split.jsonl", "后半截"),
            ),
        ),
        "前半截",
    )
    //    The other half's file name is *not* a match: the answer is null, not "the other
    //    row with the same id" — i.e. the rule never guesses across a split.
    check(
        "6.2 指向没被记下来的那一半时，不许拿同 id 的另一行顶上",
        forkParentNameOf(
            "/root/.pi/agent/sessions/2024-06-99T00-00-00-000Z_split.jsonl",
            listOf(
                SessionFileRow("2024-06-01T00-00-00-000Z_split.jsonl", "前半截"),
                SessionFileRow("2024-06-01T00-00-30-000Z_split.jsonl", "后半截"),
            ),
        ),
        null,
    )
    //    And the child's own row can be split too: both halves carry the same
    //    `parentSession`, so both resolve to the same parent name. The caller keys its
    //    lookup by path, so each half still gets its own answer.
    check(
        "6.3 子会话自己被劈成两半时，两半问出同一个父名（调用方按路径分别查）",
        listOf(
            forkParentNameOf(
                "/root/.pi/agent/sessions/2024-06-01T00-00-00-000Z_split.jsonl",
                listOf(
                    SessionFileRow("2024-06-01T00-00-00-000Z_split.jsonl", "前半截"),
                    SessionFileRow("2024-06-01T00-00-30-000Z_split.jsonl", "后半截"),
                ),
            ),
            forkParentNameOf(
                "/root/.pi/agent/sessions/2024-06-01T00-00-00-000Z_split.jsonl",
                listOf(
                    SessionFileRow("2024-06-01T00-00-00-000Z_split.jsonl", "前半截"),
                    SessionFileRow("2024-06-01T00-00-30-000Z_split.jsonl", "后半截"),
                ),
            ),
        ),
        listOf("前半截", "前半截"),
    )

    println(if (failures == 0) "\nharness: OK (all checks passed)" else "\nharness: FAILED ($failures)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
