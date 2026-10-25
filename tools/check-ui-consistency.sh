#!/usr/bin/env bash
# tools/check-ui-consistency.sh —— UI 一致性回归守卫。
#
# 这个脚本存在的原因：App 的 UI 正在逐批统一到一套 token / 组件上——弹层外壳
# `ui/components/PiSheet.kt`、顶栏动作 `PiTopBarIcon`、设置卡 `PiSettingsCard`
# （默认 1px 描边）与卡间距 `PiSettingsMetrics.groupGap`——而「统一」最容易被
# 下一批新代码悄悄推翻：一个直接 new 出来的 `ModalBottomSheet`、一行
# `MaterialTheme.typography.xxx`、一个新写死的 `12.dp`，都不会让编译器报错，
# 只会让界面慢慢退回统一之前。所以把不变量写成脚本，接进 CI。
#
# 规则：
#   1）硬失败：`app/src/main/kotlin` 下只有 `ui/components/PiSheet.kt` 可以直接调
#      `ModalBottomSheet(`。`WorkspaceChrome.kt` 的 `WsSheet` 是手写 overlay，
#      本来就不调 M3 的 sheet，所以无需在白名单里再放行一次。
#   2）棘轮：`ui/screens`、`ui/chat`、`ui/settings` 里的 `MaterialTheme.typography.*`
#      **按文件**冻结当前计数；任何文件比基线多一处即失败。总数下降时提示可以
#      下调基线。按文件而不是按总数，是因为总数相抵（这里删一处、那里加一处）
#      会把新增的违规洗掉。
#   3）棘轮：同样三个目录里的裸 `.dp` / `.sp` 字面量总数冻结，超过基线即失败。
#
# TODO(UI 一致性): 规则 2 现在**不是**「出现即失败」，而是「按文件计数棘轮」——
#   存量 52 处还没迁到统一排版 token，直接硬失败会让 CI 立刻变红（规则 2 的
#   `MaterialTheme.typography.*` 是这一批迁移的终点）。等这些调用全部迁走、
#   `R2_BASELINE` 每一行都降到 0 之后，把规则 2 改成硬失败：任何出现即 exit 1。
#   规则 3 同理：基线是当前值 dp 398 / sp 18，清零（或迁到 token）后再收紧成
#   「禁止新增」。在那之前，棘轮至少保证存量只减不增。
#
#   基线取数时点：2026-10-25 09:07 UTC 的工作树（UI token 迁移刚落定时）。
#   若之后迁移继续改动这三个目录：先跑 `--report` 看现状，只在计数**下降**时
#   下调基线；上升说明新增了裸尺寸或排版调用，应改代码，或由评审明确接受后
#   才动基线（那等于把回归写成预期）。
#
# 用法：
#   bash tools/check-ui-consistency.sh            # CI 门禁：违规 exit 1
#   bash tools/check-ui-consistency.sh --report   # 只打印现状，永远 exit 0
#
# 只依赖 grep / sed / awk / find（排序、计数、求和都用它们完成，不用 wc/sort/
# dirname 之类的外部命令），在 GitHub 的 ubuntu-latest runner 与本地都一样跑。
#
# 判定细节（有意为之的取舍，改规则前先读）：
#   * 纯注释行（首个非空白字符为 `*`、`//`、`/*`）不参与计数：这个仓库的 KDoc
#     经常提到 `ModalBottomSheet`、`.dp` 这类字样，把文档当违规会把规则变成
#     噪音。行尾注释仍然算——宁可多报一行，也不要漏掉真正的代码。
#   * `ModalBottomSheet(` 严格匹配带左括号的调用；KDoc 里常写的裸
#     `ModalBottomSheet`（不带括号）不会误报。
#   * 基线按仓库当前状态写死；`--report` 打印出来的数字就是基线该取的值。
#   * 路径都从脚本自身位置推导，因此可以从任意工作目录调用。
#
# 退出码：0 = 通过（--report 恒为 0）；1 = 有规则未通过；2 = 脚本无法运行
# （源码目录缺失等）。

set -u

MODE="check"
for arg in "$@"; do
  case "$arg" in
    --report)
      MODE="report"
      ;;
    -h|--help)
      printf 'usage: %s [--report]\n' "$0"
      printf '  --report  只打印现状，不因违规返回非零\n'
      exit 0
      ;;
    *)
      printf 'unknown argument: %s\n' "$arg" >&2
      printf 'usage: %s [--report]\n' "$0" >&2
      exit 2
      ;;
  esac
done

script_path="${BASH_SOURCE[0]}"
case "$script_path" in
  */*) script_dir="${script_path%/*}" ;;
  *)   script_dir="." ;;
esac
ROOT="$(cd "$script_dir/.." && pwd)"

SRC="$ROOT/app/src/main/kotlin"
UI="$SRC/app/pi/ui"
SHEET_ALLOWED="$UI/components/PiSheet.kt"
UI_GROUPS="screens chat settings"

if [ ! -d "$SRC" ]; then
  printf 'error: %s 不存在；本脚本必须从仓库内调用\n' "${SRC#$ROOT/}" >&2
  exit 2
fi
for g in $UI_GROUPS; do
  if [ ! -d "$UI/$g" ]; then
    printf 'error: %s 不存在；UI 目录结构变了，先更新本脚本\n' "${UI#$ROOT/}/$g" >&2
    exit 2
  fi
done

# ---------------------------------------------------------------------------
# 基线（棘轮）。规则 2 是按文件的计数，键为相对 ui/ 的路径。
# 增加基线不是修复：那是把回归写成预期。只有迁移让计数下降时才允许下调。
# ---------------------------------------------------------------------------
# 2026-10-25：`screens|chat|settings` 里的 Material 字号已全部迁移到 `PiTheme.text` /
# `PiType`，所以这里**归零**——规则 2 从此是硬失败：任何一处在三个目录里重新出现，
# 都会打印文件名与行号并 exit 1。这才是这条规则想要的终态（脚本头 TODO 的触发条件）。
R2_BASELINE=''

# 规则 3 的总数基线（三个目录合计，纯注释行不计）。迁移后的实测值，只许降。
DP_BASELINE=365
SP_BASELINE=11

R2_BASELINE_TOTAL="$(printf '%s\n' "$R2_BASELINE" | awk '{ s += $2 } END { printf "%d\n", s }')"

FAILED=0

# 换行符单独放进变量：`$(...)` 会吃掉末尾换行，拼多行文本时要显式补回来。
NL='
'

section() { printf '\n== %s ==\n' "$1"; }
fail_rule() { FAILED=$((FAILED + 1)); }

# 去掉纯注释行后的代码行。
code_only() { # $1 = file
  grep -v -E '^[[:space:]]*(\*|//|/\*)' "$1" 2>/dev/null
}

# 一个文件里某个 ERE 出现的次数（同一行出现多次也分别计数）。
code_count() { # $1 = file, $2 = ERE
  code_only "$1" | grep -oE "$2" 2>/dev/null | awk 'END { printf "%d\n", NR }'
}

baseline_for() { # $1 = 相对 ui/ 的路径；没有记录即 0
  printf '%s\n' "$R2_BASELINE" | while IFS=' ' read -r p n; do
    if [ "$p" = "$1" ]; then
      printf '%s\n' "$n"
      break
    fi
  done
}

# 规则 1 ---------------------------------------------------------------------
rule1() {
  section "规则 1：ModalBottomSheet 直调（硬失败）"
  printf '  唯一允许的文件：%s\n' "${SHEET_ALLOWED#$SRC/}"

  allowed_hits="$(grep -n -E 'ModalBottomSheet\(' "$SHEET_ALLOWED" 2>/dev/null \
    | grep -v -E '^[0-9]+:[[:space:]]*(\*|//|/\*)' \
    | sed "s|^|${SHEET_ALLOWED#$SRC/}:|")"

  violations="$(find "$SRC" -type f -name '*.kt' -print | while IFS= read -r f; do
      [ "$f" = "$SHEET_ALLOWED" ] && continue
      grep -n -E 'ModalBottomSheet\(' "$f" 2>/dev/null \
        | grep -v -E '^[0-9]+:[[:space:]]*(\*|//|/\*)' \
        | sed "s|^|${f#$SRC/}:|"
    done)"

  if [ -n "$violations" ]; then
    fail_rule
    printf '  [fail] 以下位置直接调用了 ModalBottomSheet(，应改用 PiSheet：\n'
    printf '%s\n' "$violations" | sed 's|^|    |'
    if [ "$MODE" = "check" ]; then
      printf '::error::ui-consistency: 发现 %d 处白名单外的 ModalBottomSheet 直调（规则 1）；改用 ui/components/PiSheet.kt\n' \
        "$(printf '%s\n' "$violations" | awk 'NF { n++ } END { printf "%d\n", n }')"
    fi
  else
    printf '  [ok] 没有白名单外的直调'
    if [ -n "$allowed_hits" ]; then
      printf '；唯一调用点 %s\n' "$allowed_hits"
    else
      printf '（连 PiSheet.kt 也没有？检查组件是否被改名）\n'
    fi
  fi
}

# 规则 2 ---------------------------------------------------------------------
rule2() {
  section "规则 2：screens/chat/settings 里的 MaterialTheme.typography.*（按文件棘轮）"

  total=0
  over=""
  table=""

  while IFS= read -r f; do
    rel="${f#$UI/}"
    n="$(code_count "$f" 'MaterialTheme\.typography\.')"
    total=$((total + n))
    base="$(baseline_for "$rel")"
    [ -n "$base" ] || base=0

    if [ "$n" -gt 0 ]; then
      table="$table$(printf '    %-44s %3d  (基线 %d)' "$rel" "$n" "$base")$NL"
    fi

    if [ "$n" -gt "$base" ]; then
      hits="$(grep -n -E 'MaterialTheme\.typography\.' "$f" 2>/dev/null \
        | grep -v -E '^[0-9]+:[[:space:]]*(\*|//|/\*)' \
        | sed "s|^|${f#$SRC/}:|")"
      over="$over$(printf '  [fail] %s：当前 %d 处 > 基线 %d 处' "$rel" "$n" "$base")$NL"
      over="$over$hits$NL"
    fi
  done <<EOF
$(find "$UI/screens" "$UI/chat" "$UI/settings" -type f -name '*.kt' -print)
EOF

  printf '  当前合计 %d 处 / 基线合计 %d 处\n' "$total" "$R2_BASELINE_TOTAL"

  if [ "$MODE" = "report" ]; then
    printf '  按文件（只列非零）：\n%s' "$table"
  fi

  if [ -n "$over" ]; then
    fail_rule
    printf '%s' "$over"
    if [ "$MODE" = "check" ]; then
      printf '::error::ui-consistency: screens/chat/settings 里 MaterialTheme.typography.* 出现增长（规则 2）；排版改走统一 token\n'
    fi
  else
    printf '  [ok] 没有文件超过基线\n'
    if [ "$total" -lt "$R2_BASELINE_TOTAL" ]; then
      printf '  [info] 总数比基线低 %d 处，可以下调 R2_BASELINE 里对应文件的数字\n' \
        "$((R2_BASELINE_TOTAL - total))"
    fi
  fi
}

# 规则 3 ---------------------------------------------------------------------
count_in_dir() { # $1 = dir, $2 = ERE
  find "$1" -type f -name '*.kt' -print | while IFS= read -r f; do
    code_count "$f" "$2"
  done | awk '{ s += $1 } END { printf "%d\n", s }'
}

rule3() {
  section "规则 3：裸 .dp / .sp 字面量（棘轮）"

  dp_total=0
  sp_total=0
  breakdown=""

  for g in $UI_GROUPS; do
    d="$(count_in_dir "$UI/$g" '[0-9]+\.dp')"
    s="$(count_in_dir "$UI/$g" '[0-9]+\.sp')"
    dp_total=$((dp_total + d))
    sp_total=$((sp_total + s))
    breakdown="$breakdown$(printf '    %-10s dp %3d   sp %2d\n' "$g" "$d" "$s")"
  done

  printf '  当前合计 dp %d / sp %d（基线 dp %d / sp %d）\n' \
    "$dp_total" "$sp_total" "$DP_BASELINE" "$SP_BASELINE"
  printf '  按目录：\n%s' "$breakdown"

  bad=0
  if [ "$dp_total" -gt "$DP_BASELINE" ]; then
    bad=1
    printf '  [fail] 裸 .dp 比基线多 %d 个（%d > %d）\n' \
      "$((dp_total - DP_BASELINE))" "$dp_total" "$DP_BASELINE"
  fi
  if [ "$sp_total" -gt "$SP_BASELINE" ]; then
    bad=1
    printf '  [fail] 裸 .sp 比基线多 %d 个（%d > %d）\n' \
      "$((sp_total - SP_BASELINE))" "$sp_total" "$SP_BASELINE"
  fi

  if [ "$bad" -eq 1 ]; then
    fail_rule
    printf '  尺寸请走已有的 token/组件（PiSettingsMetrics.*、PiSettingsCard 等），\n'
    printf '  不要在新代码里重新写死数字。\n'
    if [ "$MODE" = "check" ]; then
      printf '::error::ui-consistency: 裸 .dp/.sp 超过基线（规则 3）；尺寸改用 token\n'
    fi
  else
    printf '  [ok] 未超过基线\n'
    if [ "$dp_total" -lt "$DP_BASELINE" ] || [ "$sp_total" -lt "$SP_BASELINE" ]; then
      printf '  [info] 比基线低（dp %d/%d，sp %d/%d），可以下调 DP_BASELINE / SP_BASELINE\n' \
        "$dp_total" "$DP_BASELINE" "$sp_total" "$SP_BASELINE"
    fi
  fi
}

# ---------------------------------------------------------------------------
printf 'pi-android UI consistency\n'
printf '  源码根：%s\n' "${SRC#$ROOT/}"
if [ "$MODE" = "report" ]; then
  printf '  模式：--report（只打印，退出码固定 0）\n'
else
  printf '  模式：CI 门禁（违规 exit 1）\n'
fi

rule1
rule2
rule3

section "结果"
if [ "$FAILED" -eq 0 ]; then
  printf '  [ok] 3 条规则全部通过'
  if [ "$MODE" = "report" ]; then
    printf '（规则 2/3 为棘轮：基线见脚本内常量）\n'
  else
    printf '\n'
  fi
  exit 0
fi

printf '  [fail] %d 条规则未通过\n' "$FAILED"
if [ "$MODE" = "report" ]; then
  printf '  --report：只打印现状，退出码仍为 0\n'
  exit 0
fi
exit 1
