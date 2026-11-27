/** App → 宿主的 UI 回路：对话框回答、组件键盘/鼠标输入、尺寸、输入框状态、补全、色板。 */

import { ERR, HostError, bool, str } from "../protocol.mjs";

export function register(reg, { controller, hub, surfaces, ui, themeHandle }) {
  reg.add("ui.respond", (args) => {
    const hit = hub.answer(str(args, "id"), args.value, bool(args, "cancelled", false));
    // 迟到的回答（超时已收回）不是错误：App 的对话框和宿主的计时器本来就会赛跑。
    return { matched: hit };
  });

  reg.add("ui.input", (args) => {
    const surfaceId = str(args, "surfaceId");
    const raw = args.data ?? "";
    const intercepted = ui.dispatchTerminalInput(raw);
    if (intercepted.consumed) return { handled: true, consumed: true };
    return { handled: surfaces.input(surfaceId, intercepted.data), consumed: false };
  });

  reg.add("ui.mouse", (args) => ({ handled: surfaces.mouse(str(args, "surfaceId"), args) }));

  reg.add("ui.resize", (args) => {
    const width = Number(args.width);
    if (!Number.isFinite(width) || width < 1) throw new HostError(ERR.BAD_ARGS, "width 必须是正数（字符列数）");
    const surfaceId = typeof args.surfaceId === "string" && args.surfaceId.trim() ? args.surfaceId.trim() : null;
    surfaces.resize(width, Number(args.height), surfaceId);
  });

  reg.add("ui.close", (args) => ({ closed: surfaces.close(str(args, "surfaceId")) }));

  // App 把输入框文本同步给宿主：`getEditorText()` 读的就是它。
  reg.add("ui.editorState", (args) => {
    ui.state.editorText = String(args.text ?? "");
    return { length: ui.state.editorText.length };
  });

  // 原始按键/文本输入经过扩展注册的 `onTerminalInput` 链，返回是否消费及改写后的内容。
  reg.add("ui.terminalInput", (args) => ui.dispatchTerminalInput(args.data ?? ""));

  // 查询扩展通过 `addAutocompleteProvider` 注册的补全候选。
  reg.add("ui.autocomplete", async (args) => {
    const lines = Array.isArray(args.lines) ? args.lines.map(String) : [String(args.text ?? ui.state.editorText ?? "")];
    const cursorLine = Number.isFinite(Number(args.cursorLine)) ? Math.max(0, Math.min(lines.length - 1, Math.floor(Number(args.cursorLine)))) : lines.length - 1;
    const lineLen = (lines[cursorLine] ?? "").length;
    const cursorCol = Number.isFinite(Number(args.cursorCol)) ? Math.max(0, Math.min(lineLen, Math.floor(Number(args.cursorCol)))) : lineLen;
    return await ui.queryAutocomplete({ lines, cursorLine, cursorCol, force: bool(args, "force", false) });
  });

  reg.add("ui.autocompleteApply", (args) => {
    const lines = Array.isArray(args.lines) ? args.lines.map(String) : [String(args.text ?? ui.state.editorText ?? "")];
    const cursorLine = Number.isFinite(Number(args.cursorLine)) ? Math.max(0, Math.min(lines.length - 1, Math.floor(Number(args.cursorLine)))) : lines.length - 1;
    const lineLen = (lines[cursorLine] ?? "").length;
    const cursorCol = Number.isFinite(Number(args.cursorCol)) ? Math.max(0, Math.min(lineLen, Math.floor(Number(args.cursorCol)))) : lineLen;
    const item = typeof args.item === "object" && args.item !== null ? args.item : { value: String(args.value ?? ""), label: String(args.label ?? args.value ?? "") };
    return ui.applyAutocomplete({ lines, cursorLine, cursorCol, item, prefix: String(args.prefix ?? "") });
  });

  // 同步 App 侧的工具展开状态（双向一致）。
  reg.add("ui.setToolsExpanded", (args) => {
    ui.context.setToolsExpanded(bool(args, "expanded", false));
    return { expanded: ui.state.toolsExpanded };
  });

  // 按需把扩展注册的自定义编辑器（`setEditorComponent`）以交互表面打开，关闭/提交时把草稿同步回原生输入框。
  reg.add("ui.openCustomEditor", () => ui.openCustomEditor());

  // App 主题换了：更新色板并重建官方 `sdk.Theme` 写入全局槽，触发组件与扩展表面同步重绘。
  reg.add("ui.palette", (args) => {
    if (typeof args.palette !== "object" || args.palette === null) throw new HostError(ERR.BAD_ARGS, "palette 必须是对象");
    const name = typeof args.name === "string" && args.name.trim() ? args.name.trim() : undefined;
    const changed = themeHandle.setPalette(args.palette, name);
    surfaces.notifyColorScheme?.(themeHandle.theme.appearance);
    if (changed && controller.sdk) {
      controller.hub.emit("render.invalidate", { kind: "theme" });
    }
    surfaces.repaintAll();
  });
}
