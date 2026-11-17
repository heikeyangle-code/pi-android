// 最小的 stdio MCP 服务器（只为测试）：initialize / tools/list / tools/call。
import { createInterface } from "node:readline";
const send = (m) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", ...m }) + "\n");
createInterface({ input: process.stdin }).on("line", (line) => {
  let msg; try { msg = JSON.parse(line); } catch { return; }
  if (msg.id === undefined) return;
  if (msg.method === "initialize") send({ id: msg.id, result: { protocolVersion: msg.params?.protocolVersion ?? "2025-03-26", capabilities: { tools: {} }, serverInfo: { name: "echo", version: "1" } } });
  else if (msg.method === "tools/list") send({ id: msg.id, result: { tools: [{ name: "echo", description: "echo", inputSchema: { type: "object", properties: { text: { type: "string" } } } }] } });
  else if (msg.method === "tools/call") send({ id: msg.id, result: { content: [{ type: "text", text: String(msg.params?.arguments?.text ?? "") }] } });
  else send({ id: msg.id, result: {} });
});
