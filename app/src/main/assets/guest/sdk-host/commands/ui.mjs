/** App → 宿主的 UI 回路：对话框回答、组件输入、尺寸、输入框状态、色板。 */

import { ERR, HostError, bool, str } from "../protocol.mjs";

export function register(reg, { hub, surfaces, ui, themeHandle }) {
  reg.add("ui.respond", (args) => {
    const hit = hub.answer(str(args, "id"), args.value, bool(args, "cancelled", false));
    // 迟到的回答（超时已收回）不是错误：App 的对话框和宿主的计时器本来就会赛跑。
    return { matched: hit };
  });

  reg.add("ui.input", (args) => ({ handled: surfaces.input(str(args, "surfaceId"), args.data ?? "") }));

  reg.add("ui.resize", (args) => {
    const width = Number(args.width);
    if (!Number.isFinite(width) || width < 1) throw new HostError(ERR.BAD_ARGS, "width 必须是正数（字符列数）");
    surfaces.resize(width, Number(args.height));
  });

  reg.add("ui.close", (args) => ({ closed: surfaces.close(str(args, "surfaceId")) }));

  // App 把输入框文本同步给宿主：`getEditorText()` 读的就是它。
  // 同时回调 onTerminalInput 的订阅者（降级：给的是文本变化，不是真按键）。
  reg.add("ui.editorState", (args) => {
    ui.state.editorText = String(args.text ?? "");
    for (const handler of ui.state.terminalInputHandlers) {
      try {
        handler(ui.state.editorText);
      } catch {
        // 订阅者的异常不影响 App。
      }
    }
  });

  // App 主题换了：整体换色板，后续渲染生效；已挂载的表面全部重绘。
  reg.add("ui.palette", (args) => {
    if (typeof args.palette !== "object" || args.palette === null) throw new HostError(ERR.BAD_ARGS, "palette 必须是对象");
    themeHandle.setPalette(args.palette);
    surfaces.resize(surfaces.width, surfaces.height);
  });
}
