# ZFF MC 完整安装指南（给另一个用户从零复现）

> 目标：在一台**干净**的 Windows 机器上，把「DSH + ZFF MC 插件 + MCP server + Minecraft mod」整套装起来，
> 让 AI 在你的真实 Minecraft 客户端里陪你玩。
> 全程大约 20 分钟。每一步都有**验收动作**，做完再往下走。

---

## 0. 你会装什么（先看懂这张图）

```
┌─ DSH 桌面版 ──────────────────────────────┐
│  profile: desktop                          │
│   ├─ 插件 @local/zff-mc   ← 本仓库的 plugin│
│   └─ MCP client (mc-control) ──┐           │
└────────────────────────────────┼───────────┘
                                 │ HTTP 127.0.0.1:57610/mcp
                     ┌───────────▼────────────┐
                     │ MCP server（Node）      │  57610 MCP / 57611 聊天推送 / 57612  ingest
                     └───────────┬────────────┘
                                 │ TCP 127.0.0.1:57610 之外的本地 socket
                     ┌───────────▼────────────┐
                     │ Minecraft 客户端 + mod  │  ← 你自己开的游戏
                     └────────────────────────┘
```

**插件不启动游戏**。你自己开 Minecraft，AI 通过 MCP server 连上游戏里的 mod，然后接手操作。

## 1. 前置条件

| 需要 | 版本 / 说明 |
|---|---|
| Windows | 10 / 11 x64 |
| DSH 桌面版 | 0.2.1-rc.3（安装后 `%LOCALAPPDATA%\Programs\DeepSeek Harness\`） |
| Node.js | ≥ 22（`node -v` 能跑就行；DSH 自带 node 也可） |
| Minecraft | Java 版 **1.21.1** + **NeoForge 21.1.228**（mod 只支持这个组合） |
| 你的整合包 | 可以带 mod，但必须能装下这个 NeoForge 版本 |

## 2. 准备文件（release 目录里就这几样）

```
release/
├─ ZFF MCP Server.zip   ← **放到你的工作目录根下**（插件会自动解压并启动，不用 npm install）
├─ ZFF Bridge.jar       ← 放进 .minecraft\mods
├─ zff-mc-plugin\       ← 插件包（index.js / client.js / src / defaults / presets / package.json …）
├─ start-mcp.ps1        ← 可选：手动起 server
└─ README.md / INSTALL.md / LICENSE / SHA256SUMS.txt
```

> Ph7 起插件**自己在工作目录里找** `ZFF MCP Server.zip` 或 `zff-mcp-server/`，有 zip 就解压、没 server 就启：
> 不需要再把 `mcp-server\` 单独拷到某个固定位置，也不需要预装依赖（zip 里带 `node_modules`）。
> 工作目录里两样都没有时，插件**不会**续跑，只会提示你把 zip 放进去（Agent 不会被自动唤醒）。
> 插件包不要放中文路径、不要放 OneDrive 同步目录（后面要 `link:` 到 profile）。

## 3. 装 DSH 并建 profile

1. 安装 DSH 桌面版，先启动一次再完全退出（托盘退出）。
2. 打开 PowerShell：

```powershell
$dsh = "$env:LOCALAPPDATA\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd"
& $dsh --version
& $dsh plugin --profile desktop add "link:C:/zff/plugin"
```

**验收**：`C:\Users\<你>\.dsh\profiles\desktop\package.json` 里出现

```json
"@local/zff-mc": "link:C:/zff/plugin"
```

并且 `dsh.profile.bundles` 里多了一行 `"@local/zff-mc"`。
（`plugin add` 会自动改这两处；没自动加 bundle 就手动补。）

## 4. 配 profile 的 patch（MCP 连接）

编辑 `C:\Users\<你>\.dsh\profiles\desktop\cordis.patch.yml`，**追加**：

```yaml
- insert:
    - id: mc-control
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: mc
        transport: streamable-http
        url: http://127.0.0.1:57610/mcp
        toolCallTimeoutMs: 600002
        failOnStartupError: false        # MCP server 没起也不拦启动
        reconnect:
          enabled: true
          initialDelayMs: 500
          maxDelayMs: 30000
          maxAttempts: 10
```

同时把 ZFF MC 的工作区钉死（**必须**与 MCP server 的 `--workspace` 一致）：

```yaml
- id: zff-mc
  name: "@local/zff-mc"
  config:
    workspaceDir: "C:\\zff\\workspace"
    adminPlayers: ["你的游戏名"]
```

**验收**：`node -e "console.log(require('js-yaml').load(require('fs').readFileSync(process.argv[1],'utf8')).length)" <那个文件>` 不报错（或用任意 YAML 校验器）。

## 5. MCP server：插件会自己起（也可以手动起）

**推荐什么都不做**：把 `ZFF MCP Server.zip` 放工作目录根下，插件在第一次进入 ZFF MC Mode 时会
自动解压到 `<工作目录>\zff-mcp-server\`，然后 spawn
`node src/server.js --http --port 57610 --workspace "<工作目录>"`，日志在
`<工作目录>\runtime\zff-mcp-server.{out,err}.log`。

想手动起（比如排查）：

```powershell
powershell -ExecutionPolicy Bypass -File .\start-mcp.ps1 -Workspace "C:\你的工作目录"
```

**验收**（任一）：

```powershell
(Invoke-WebRequest http://127.0.0.1:57610/health -UseBasicParsing).Content      # ok
(Invoke-WebRequest http://127.0.0.1:57610/instances -UseBasicParsing).Content   # {"ok":true,"workspace":"…","instances":[…]}
```

> ⚠️ 同一个端口只能属于**一个工作目录**：如果你手动起的 server 指向 A 目录，而会话在 B 目录，
> 插件会报 `server_workspace_mismatch` 并停下（不会拿错目录的 runtime 去跑）。

## 6. 装 mod 到游戏

把 `mcpbridge.jar` 放进**你要玩的那个整合包**的 `.minecraft\mods\` 里。
（PCL：选中版本 → 打开版本文件夹 → `mods`。整合包自带 mod 也不冲突。）

**验收**：启动游戏，主菜单正常进入；日志里没有 `Incompatible client! Please use NeoForge 21.1.228`。

## 7. 启动 DSH 并选模式

1. 完全启动 DSH（不是"新开窗口"，是重新起进程）。
2. 新建会话 → 模式下拉选 **ZFF MC Mode**（不是 whale_craft 的「MC模式」）。
3. 输入框那一行右侧应出现 **「▶ 开始游戏 · 设置 · ?」**；已有会话的标题条右侧也是这三个。

**验收**：让 AI 调一次 `zff_status`，返回里 `isZffMode: true`、`presetId: "zff-mc"`、`run.state: "idle"`（默认 ▶）。

## 8. 开游戏 → 点 ▶ → AI 接手

**玩法核心是一个开关**（2026-11-01 玩家定的语义）：

| 按钮 | 含义 | 行为 |
|---|---|---|
| **▶ 开始游戏** | 已结束（默认，DSH 重启后也回到这里） | 不注入任何续跑；游戏工具只读放行（能问"我在哪"），改变世界的工具被拒 |
| **⏸ 进行中** | 游戏进行中 | 自动续跑；再点一下 = 结束游戏 → 立刻停止续跑 + 给当前轮一条"停手"提示 |

1. 你自己启动 Minecraft（PCL / 官方启动器都行），进存档（单机记得**允许作弊**，见下）或服务器，能自由走动。
2. 回到 DSH 对话，点 **▶ 开始游戏**（按钮变成 ⏸）。
   - 游戏还没进世界也能点：按钮旁会显示「（等待客户端进世界）」，一进世界就开始续跑。
3. 插件按 `launcher.playerName`（设置 → 游戏）在实例列表里找**同名**客户端：
   - 找到 1 个 → 接管；
   - 一个都没有 / 有 2 个同名 / 有实例还没进世界拿不到名字 → **不续跑**，只提示你去处理。
4. AI 会调 `mcp__mc__get_state` 确认在线，然后开始干活。想让它停，点 **⏸**；想让它继续，再点 **▶**。

**单机必须允许作弊**（否则 `/give`、`/setblock` 这些指令全部失败，方块测试也没法做）：
进世界后按 `Esc` → **Open to LAN** → `Allow Commands: ON` → `Start LAN World`。

**验收**：`mcp__mc__get_state` 返回里有你的坐标/血量/维度，且 `inWorld: true`。
`mcp__mc__mc_instances`（或 `GET /instances`）里你的实例 `username` 等于配置的 `playerName`。

## 9. 设置页（4 个标签，保存即生效）

| 标签 | 作用 | 存哪 |
|---|---|---|
| 提示词 A/B/C | 预览默认值 / 在线编辑 / 上传 `.md` / 恢复默认；B 清空则该段不注入；每份 ≤64 KB | `~/.dsh/zff-mc/prompts/*.md` |
| 游戏 | 你的游戏名、服务器地址（只作为"事实"写进提示词） | `~/.dsh/zff-mc/settings.json` |
| 续跑 | 总开关、从聊天读"叫停"、空转 N（20）、自主 M（5）、低耗间隔… | 同上 |
| 管理员 | `adminPlayers`（每行一个；留空 = 谁都不能让 AI 下线） | 同上 |
| 聊天规则 | 中断正则（`^鱼`）、必须回复的关键词、豁免工具 | `<工作区>/runtime/zff-chat-rules.json`（热加载） |

> 提示词是「下一个会话生效」，其余**保存立即生效**。

## 10. 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| 启动弹框 `web boot: 1 entry did not activate` + `@local/zff-mc: import failed` | ① 客户端 bundle 重复执行 ② 插件 `apply()` 抛错 ③ **客户端模块 id ≠ 包名**（历史三连） | 当前版本已修；若复现，看 `%APPDATA%\@deepseek-ai\dsh-desktop\logs\crash-*.log` |
| 设置页报 `unknown endpoint GET /api/zff-mc/settings` | 宿主还在跑旧代码 | **完全重启 DSH** |
| `mcp__mc__*` 工具不出现 | MCP server 没起 / `mc-control` 没配 / 端口被占 | 见第 5 步；`netstat -ano | findstr 57610` |
| 工具出现但调不动 | 游戏没开 / mod 版本不匹配 | 看游戏日志的 mod 加载行；确认 NeoForge 21.1.228 |
| AI 不动 | `zff_status` 看 `paused` / `terminated` / 掉线；模式是不是 ZFF MC Mode | 说「继续」解除暂停 |
| 插件把 DSH 搞挂 | 任何插件 bug | `node scripts\zff-mc-toggle.mjs --off` 摘掉后重启 |

**日志位置**

- 插件：`C:\Users\<你>\.dsh\zff-mc\logs\zff-mc.log`
- DSH 崩溃：`%APPDATA%\@deepseek-ai\dsh-desktop\logs\crash-*.log`
- MCP server：`start-mcp.ps1` 的窗口输出
- 游戏：`.minecraft\logs\latest.log`

## 11. 卸载

```powershell
$dsh = "$env:LOCALAPPDATA\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd"
& $dsh plugin --profile desktop remove "@local/zff-mc"
```

再从 `cordis.patch.yml` 删掉 `zff-mc` 那一段、`mods` 里删掉 jar、`~/.dsh/zff-mc` 可留可删。

## 12. 装完自检（可选，30 秒）

```powershell
node C:\zff\plugin\selfcheck.mjs            # 期望 148/148
node C:\zff\plugin\..\test\zff-client-bundle.mjs   # 期望 7/7
```

两项全绿，说明插件的契约（刹车/注入/提示词/下线/工具/打包）在你这台机器上也成立。

## MCP 状态栏（2026-11-01 新增）

标题条（或新会话页）那一行除了 ▶/⏸、设置、团队，还多了一条 **MCP 状态栏**：

```
● MCP 运行中  pid 1234  工作目录 D:\MC Agent Play  面板启动          [关闭 MCP]
桥接实例（1）：DSHDeepSeek@65146(世界内)
```

- **● 灰** = 未启动；**● 绿** = 健康；**● 黄** = 进程在但健康检查不通（挂起）。
- 「启动 MCP」在当前会话的工作目录启动：**有解压好的文件夹就用；只有 zip 才会解压**。
- 「关闭 MCP」会把 **server + 聊天守护进程 + 它们的子进程**整棵树杀掉（不是只杀一个）。
- 点 ▶ 时如果 MCP 没起来：**自动启动**；起不来、或已有别的工作目录的 server 挂着 → 弹窗说明并**保持 ▶**。
- 游戏进行中 MCP 掉了 → 自动切回 ▶（暂停）。
- DSH 关掉后，server 30 秒收不到心跳会自己退出，不会留孤儿进程。
