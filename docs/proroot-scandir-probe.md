# 给 App 内 AI 的探测提示词（proroot `scandir` 缺陷）

把下面「提示词正文」整段复制给 App 里的 agent（用 **proroot** 运行时）。它只做只读探测，
不改任何东西。把它的输出原样带回来即可。

目的：判断三件事。

1. **前提成不成立**——`opendir` 在失败的路径上是不是真的能用（整个修法的地基）。
2. **修法有没有生效**——`NODE_OPTIONS` 与兜底模块在不在。
3. **哪些路径失败、哪些成功**——把范围钉死，供后续判断。

---

## 提示词正文

> 你现在是在一台 Android 手机上的 Linux 客户机里运行（宿主 App 用的是 proroot 这个用户态
> 容器运行时）。我要你做一次**只读的诊断**，不要修改任何文件、不要安装任何东西、不要重启
> 任何服务。全部用 bash 工具执行，然后按我要求的格式回报。
>
> 背景（你不需要验证它，只需要知道我在看什么）：这个运行时把宿主目录"绑定"到客户机路径上，
> 而在被它重写的路径上，libc 的 `scandir(3)` 可能返回 ENOENT，而 `opendir(3)`+`readdir(3)`
> 是好的。Node 的 `fs.readdirSync` 走的是 `scandir`，所以它可能失败；`/bin/ls` 走的是
> `opendir`+`readdir`，所以它应该成功。我要看的就是这个分叉到底出现在哪些路径上。
>
> **A. 基本信息**，执行：
>
> ```bash
> node --version
> echo "NODE_OPTIONS=[${NODE_OPTIONS-未设置}]"
> ls -l /opt/pi/scandir-fix.mjs 2>&1
> grep -c . /proc/self/mountinfo 2>/dev/null
> ```
>
> **B. 逐路径对照**，执行下面这一整段（原样复制，不要改动路径列表）：
>
> ```bash
> node -e '
> const fs=require("fs");
> const paths=[
>  "/","/etc","/usr","/opt/pi","/opt/pi/node_modules",
>  "/root","/root/.pi","/root/.pi/agent","/root/.pi/agent/extensions",
>  "/workspace","/workspace/pi","/workspace/pi/workspaces",
>  "/tmp","/dev/shm","/sdcard"
> ];
> console.log("path\tstat\topendir\treaddirSync");
> for(const p of paths){
>   let st,op,rd;
>   try{ st=fs.statSync(p).isDirectory()?"dir":"file" }catch(e){ st="ERR:"+e.code }
>   try{ const h=fs.opendirSync(p); let n=0; while(h.readSync())n++; h.closeSync(); op=n }catch(e){ op="ERR:"+e.code }
>   try{ rd=fs.readdirSync(p).length }catch(e){ rd="ERR:"+e.code }
>   console.log(p+"\t"+st+"\t"+op+"\t"+rd);
> }
> '
> ```
>
> **C. 同一批路径用 `/bin/ls` 数一遍**，执行：
>
> ```bash
> for p in / /etc /usr /opt/pi /root /root/.pi /root/.pi/agent /root/.pi/agent/extensions /workspace /workspace/pi /workspace/pi/workspaces /tmp /dev/shm /sdcard; do
>   n=$(ls -A "$p" 2>/dev/null | wc -l); c=$?
>   echo -e "$p\t/bin/ls=$n\texit=$c"
> done
> ```
>
> **D. 你自己的工作目录**，执行：
>
> ```bash
> pwd
> node -e 'const fs=require("fs");const d=process.cwd();try{const h=fs.opendirSync(d);let n=0;while(h.readSync())n++;h.closeSync();console.log("opendir OK",n)}catch(e){console.log("opendir FAIL",e.code)};try{console.log("readdirSync OK",fs.readdirSync(d).length)}catch(e){console.log("readdirSync FAIL",e.code)}'
> ls -A | wc -l
> ```
>
> **E. 回报格式**——只回报下面格式，不要加解释、不要加建议：
>
> ```
> [A] node=<版本>  NODE_OPTIONS=<值或"未设置">  scandir-fix.mjs=<存在/不存在>
> [B] 把表格原样贴出（path/stat/opendir/readdirSync 四列）
> [C] 把列表原样贴出（path//bin/ls=/exit=）
> [D] pwd=<路径>  opendir=<结果>  readdirSync=<结果>  ls=<条数>
> [E] 一句话：哪些路径 readdirSync 失败而 opendir 成功
> ```
>
> 如果某条命令本身报错（比如 `node` 找不到），把**原始报错**贴出来，不要改写。

---

## 我怎么读这份输出

| 现象 | 含义 | 下一步 |
|---|---|---|
| `opendir` 成功、`readdirSync` 失败（同一路径） | **前提成立** | 修法可用，验证修完是否恢复 |
| 两者**都失败** | 前提不成立 | 兜底要改走子进程 `/bin/ls`（它已实测是好的） |
| `opendir` 与 `readdirSync` **都成功**，且 `/bin/ls` 也对 | 那时没有复现 | 换一条确定失败的路径再测（用 `[B]` 里 `stat=dir` 但 `readdirSync` 报错的那些） |
| `NODE_OPTIONS=未设置` 或 `scandir-fix.mjs=不存在` | **修法没接上** | 查注入点与资产写入，不是模块本身的问题 |

**修完之后重跑同一段提示词**，`[A]` 应变成
`NODE_OPTIONS=--import=/opt/pi/scandir-fix.mjs`、`scandir-fix.mjs=存在`，
而 `[B]` 里此前 `ERR:ENOENT` 的行应当全部变成**条数**。
