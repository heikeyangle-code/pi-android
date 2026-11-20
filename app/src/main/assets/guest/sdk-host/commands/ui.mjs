/** App → 宿主的 UI 回路：对话框回答、组件键盘/鼠标输入、尺寸、输入框状态、补全、色板。 */

import { ERR, HostError, bool, str } from "../protocol.mjs";

export function register(reg, { hub, surfaces, ui, themeHandle }) {
  reg.add("ui.respond", (args) => {
    const hit = hub.answer(str(args, "id"), args.value, bool(args, "cancelled", false));
    // 迟到的回答（超时已收回）不是错误：App 的对话框和宿主的计时器本来就会赛跑。
    return { matched: hit };
  });

  reg.add("ui.input", (args) => ({ handled: surfaces.input(str(args, "surfaceId"), args.data ?? "") }));

  reg.add("ui.mouse", (args) => ({ handled: surfaces.mouse(str(args, "surfaceId"), args) }));

  reg.add("ui.resize", (args) => {
    const width = Number(args.width);
    if (!Number.isFinite(width) || width < 1) throw new HostError(ERR.BAD_ARGS, "width 必须是正数（字符列数）");
    surfaces.resize(width, Number(args.height));
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

  // 同步 App 侧的工具展开状态（双向一致）。
  reg.add("ui.setToolsExpanded", (args) => {
    ui.context.setToolsExpanded(bool(args, "expanded", false));
    return { expanded: ui.state.toolsExpanded };
  });

  // App 主题换了：整体换色板，后续渲染生效；已挂载的表面全部重绘。
  reg.add("ui.palette", (args) => {
    if (typeof args.palette !== "object" || args.palette === null) throw new HostError(ERR.BAD_ARGS, "palette 必须是对象");
    themeHandle.setPalette(args.palette);
    surfaces.resize(surfaces.width, surfaces.height);
  });
}
