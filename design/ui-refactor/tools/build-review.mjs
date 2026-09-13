// 生成三方向对比页（单文件、图片 base64 内嵌、手机浏览器可读），并拷到 Download。
// 用法：node tools/build-review.mjs
import fs from 'node:fs';

const ROOT = '/root/pi-android/design/ui-refactor';
const SHOTS = `${ROOT}/design-demos/shots`;

const directions = [
  {
    key: 'a',
    label: '方向 A · 仪表',
    logic: '秒数轮盘 #16 · Terminal-Core Soft-Futurism（等宽字 + 等距立方）',
    ref: '参照：Cursor 的开发者终端美学 × Teenage Engineering 工业极简',
    one: '信息 = 读数 + 轨道。整条转录读起来像一卷带刻度的记录纸。',
    screens: [
      '对话：屏顶横读数带（上下文环 + 输入/输出/缓存/费用）+ 一条竖执行轨道，块脚右端是耗时刻度',
      '会话列表：从对话页左上角会话名进入的覆盖层，列表 ↔ 会话树 同层切换',
      '工作区：项目现场（等距立方三层：工作目录 / .pi 资源 / 本次会话）',
      '设置首页：读数带 + 当前模型卡（7 级思考色温刻度）+ 设备/扩展/关于/终端 四入口 + 12 格 bento',
      '分组页：6 种行型全可点，含危险行确认态',
    ],
    signature:
      '块脚右端那条 1px 耗时刻度：长度 ∝ log₂ 毫秒、颜色取状态色——132ms 与 6.4 秒一眼可比。',
    risk: '几何示意（等距立方）与 12 格 bento 是三个方向里装饰性最强的部分，落地时要盯住「仪表盘 slop」。',
  },
  {
    key: 'b',
    label: '方向 B · 直接操作',
    logic: '现实参照（标杆迁移）· Linear 移动端',
    ref: '参照（已核实真实存在）：Linear 移动端 2025-10-16 改版 + 团队复盘《A calmer interface for a product in motion》',
    one: '结构要被感觉到、而不是被看到；每屏只有一个主操作；属性即内容。',
    screens: [
      '对话：一条执行轨道（节点 = pi 真实状态字形 + 真实耗时），紧跟独立的 diff 卡，用 pi 的三色行级纹理',
      '会话列表：左上角会话名进入的覆盖层，两套空态',
      '工作区：项目现场（页内 13px 标注每块数据来源）',
      '设置首页：搜索 + 当前模型卡 + 设备/扩展/终端/关于 + 12 分组动态摘要',
      '分组页：模型与推理，6 种行型 + 生效徽标 + 危险确认',
    ],
    signature: '执行轨道 + 思考等级 7 级色温刻度；会话行三行字段压成两行、属性右对齐。',
    risk: '最像「一个成熟产品」，但风格安全区最窄（最容易被人说成「像 Linear」）；Linear 的毛玻璃材质已按你的禁令换成 pi 自己的表面阶梯 + 1px 线。',
  },
  {
    key: 'c',
    label: '方向 C · 作者性',
    logic: '最佳设计师 · Panic Inc.（Cabel Sasser）',
    ref: '理由：三十年只把开发者的工具当器物做；等宽当主角、1px 线分层、颜色只做信号。',
    one: '零图标、零阴影、零渐变；层级只由字号、字重、1px 线和位置产生；状态永远「字 + 符号 + 颜色」三重编码。',
    screens: [
      '对话：挂在「执行脊」上的一条纸带——工具节点是状态字形，刻度长度 = log 真实耗时，diff 节点是双条刻度，思考节点是 4px 等级色条',
      '会话列表：左上角会话名进入的覆盖层，列表 ↔ 会话树 同层',
      '工作区：项目现场（cwd / 分支 / 信任 + 运行中的命令 + 本次改动 + 碰过的文件 + 该目录的 pi 资源）',
      '设置首页：搜索 + 当前模型卡 + 四个入口行 + 12 分组动态摘要',
      '分组页：模型与推理为默认，可切外观 / 安全与信任 / 运行时与诊断',
    ],
    signature: '执行脊——全稿唯一的发光只给「正在跑的那一步」；压缩处纸带断一次。',
    risk: '零图标 + 强等宽，对非开发者更冷；设置页密度偏高，需要用户已经熟悉这套术语。',
  },
];

const b64 = (p) => fs.readFileSync(p).toString('base64');

const section = (d) => {
  const phones = [1, 2, 3, 4, 5]
    .map((i) => {
      const f = `${SHOTS}/${d.key}-phone${i}.png`;
      if (!fs.existsSync(f)) return '';
      return `<figure><img src="data:image/png;base64,${b64(f)}" alt="${d.label} 第 ${i} 台"><figcaption>第 ${i} 台</figcaption></figure>`;
    })
    .join('\n');
  const board = `${SHOTS}/${d.key}-board.png`;
  const boardTag = fs.existsSync(board)
    ? `<figure><img src="data:image/png;base64,${b64(board)}" alt="${d.label} 五台平铺" style="border:1px solid #303036"><figcaption>五台平铺全貌（2400px，手机上可横向滑动）</figcaption></figure>`
    : '';
  return `
<section id="${d.key}">
  <h2>${d.label}</h2>
  <p class="logic">${d.logic}</p>
  <p class="ref">${d.ref}</p>
  <p class="one">${d.one}</p>
  <h3>五台屏</h3>
  <ol>${d.screens.map((s) => `<li>${s}</li>`).join('')}</ol>
  <h3>那处 120%</h3>
  <p>${d.signature}</p>
  <h3>代价（我作为艺术总监的诚实提醒）</h3>
  <p class="risk">${d.risk}</p>
  <div class="phones">${phones}</div>
  <details><summary>五台平铺全貌</summary>${boardTag}</details>
</section>`;
};

const html = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>pi 安卓客户端 · UI 重构 · 三方向初稿</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@400;500;700&family=JetBrains+Mono:wght@400;500&display=swap" rel="stylesheet">
<style>
  :root{
    --accent:#8ABEB7; --border-muted:#505050; --success:#B5BD68; --error:#CC6666; --warning:#FFFF00;
    --text:#D4D4D4; --muted:#808080; --dim:#666666;
    --page:#18181E; --card:#1E1E24; --surf:#232329; --surf-high:#29292F; --surf-highest:#303036;
  }
  *{box-sizing:border-box}
  html{-webkit-text-size-adjust:100%}
  body{
    margin:0; background:var(--page); color:var(--text);
    font-family:"Noto Sans SC","PingFang SC","Microsoft YaHei",system-ui,sans-serif;
    font-size:15px; line-height:1.7; font-synthesis:none; line-break:strict; overflow-wrap:anywhere;
  }
  header{padding:28px 18px 8px; border-bottom:1px solid var(--border-muted)}
  h1{font-size:22px; line-height:1.3; margin:0 0 6px}
  .sub{color:var(--muted); font-size:13px; margin:0 0 4px}
  nav{display:flex; gap:8px; padding:14px 18px; position:sticky; top:0; background:var(--page); border-bottom:1px solid var(--border-muted); z-index:9}
  nav a{color:var(--accent); text-decoration:none; font-size:13px; border:1px solid var(--border-muted); border-radius:999px; padding:4px 12px}
  section{padding:22px 18px; border-bottom:1px solid var(--border-muted)}
  h2{font-size:19px; margin:0 0 2px}
  h3{font-size:13px; color:var(--muted); font-weight:500; margin:18px 0 4px}
  p{margin:4px 0}
  .logic{color:var(--accent); font-size:13px}
  .ref,.sub{color:var(--muted); font-size:12.5px}
  .one{font-size:15px}
  .risk{color:var(--muted); font-size:13.5px}
  ol{margin:4px 0 0; padding-left:1.25em}
  ol li{margin:5px 0; font-size:14px}
  .phones{display:flex; flex-direction:column; gap:14px; margin-top:16px}
  figure{margin:0}
  img{width:100%; height:auto; display:block; border-radius:8px; background:var(--card)}
  figcaption{color:var(--dim); font-size:11.5px; margin-top:5px}
  details{margin-top:16px; border-top:1px solid var(--border-muted); padding-top:10px}
  summary{color:var(--muted); font-size:13px}
  footer{padding:22px 18px 60px; color:var(--muted); font-size:13px}
  code{font-family:"JetBrains Mono",ui-monospace,monospace; font-size:12.5px; color:var(--accent)}
</style>
</head>
<body>
<header>
  <h1>pi 安卓客户端 · UI 重构</h1>
  <p class="sub">三个方向初稿 · 同一批真实内容 · 同一尺寸（Android 412×892）· 颜色 100% 来自 pi 主题令牌</p>
  <p class="sub">已定的产品裁决：底部三项导航（对话 / 工作区 / 设置）；会话列表 = 对话页左上角进入的覆盖层；工作区 = 项目现场；终端 = 设置里的入口行；冷启动默认新会话。</p>
</header>
<nav><a href="#a">方向 A 仪表</a><a href="#b">方向 B 直接操作</a><a href="#c">方向 C 作者性</a></nav>
${directions.map(section).join('\n')}
<footer>
  <p>看完之后告诉我要哪个，或者要混合（例如「C 的执行脊 + B 的设置页」）。选定后我会：把那一版做扎实（补齐剩余屏幕与状态）、写 <code>direction-approved.md</code>、再用它去改 Compose 实现。</p>
  <p>本页图片是 <code>design/ui-refactor/design-demos/shots/</code> 里的截图；原始 HTML 在 <code>design-demos/direction-{a,b,c}.html</code>，可点击可交互。</p>
</footer>
</body>
</html>`;

fs.writeFileSync(`${ROOT}/review.html`, html);
const dest = '/sdcard/Download/pi-UI重构-三方向对比.html';
fs.copyFileSync(`${ROOT}/review.html`, dest);
console.log('review.html', (html.length / 1024 / 1024).toFixed(2) + ' MB');
console.log('copied →', dest);
