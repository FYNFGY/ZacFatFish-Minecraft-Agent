# AI Player Manual — 让一个 Agent 进入服务器并开始玩

> 读者：要接手这个 Minecraft 账号的 Agent（LLM 主代理），以及它的子代理 / 运行时组件。
> 配套文件：[AIPlayer.md](AIPlayer.md) —— 角色卡（性格、说话方式、世界笔记，**由玩家手填**）。
> 工具细节全表见 [docs/Ph5/TOOLS.md](docs/Ph5/TOOLS.md)；架构由来见 [docs/Ph5/AGENT-ARCHITECTURE.md](docs/Ph5/AGENT-ARCHITECTURE.md)。

---

## 0. 30 秒上手清单

1. 客户端开着、已经进了世界/服务器 → `get_state` 应返回 `player`。
2. MCP server 在 `127.0.0.1:57610`，daemon 在 `127.0.0.1:57611`。
3. 先用 `mc_instances` 看有几个客户端实例；用 `mc_attach` 选中**自己的**那一个（别选人类玩家的）。
4. `get_state` → 确认 `player.name`、坐标、维度、服务器地址。
5. `chat_history` → 看玩家最近说了什么；`chat` → 打招呼。
6. 要开"多子代理 + Chatter"：`ZFF_CR=1 ZFF_BACKEND=server node mcp-server/src/agent/run.mjs chat`（见第 6 节）。

---

## 0.5 冷启动：从零开始（Windows）

> 机器上什么都没开（没有客户端、没有 MCP server、没有 daemon）时，按这一节做。
> **一条命令版**：`start-all.ps1` 会依次检查环境 → 起 MCP server → 起 daemon → 启动客户端 → 等实例文件 → 打印 `mc_attach` 参数。

```powershell
cd "D:\mc\MCP Development"
powershell -ExecutionPolicy Bypass -File .\start-all.ps1            # 全套（服务端 + 客户端）
powershell -ExecutionPolicy Bypass -File .\start-all.ps1 -NoClient  # 只起服务端
```

它会输出：

```
== 步骤 0：检查 Java / Node
==   java: java version "21.0.8"
==   node: v22.19.0
== 步骤 1：MCP server (127.0.0.1:57610)
== 步骤 2：daemon (127.0.0.1:57611)
== 步骤 3：启动客户端
== 步骤 4：实例已就绪
==   file   = C:\Users\...\instances\<uuid>.json
==   port   = 6xxxx
==   player = (还在主菜单，进世界后才有)
```

### 手动分步（脚本不可用时）

| # | 做什么 | 命令 | 成功判据 |
|---|---|---|---|
| 0 | 检查环境 | `java -version` / `node --version` | Java **21**、Node **22** |
| 1 | 装 mod（一次性） | `install.ps1 -VersionDir "<整合包>" -WithBaritone` | `mods\mcpbridge-0.1.0.jar` 和 `mods\baritone-api-neoforge-1.11.3.jar` 存在 |
| 2 | 确认账号 | 启动器带 `--username DSHDeepSeek --uuid d6a381d2-…` | `options.txt` 里有 `skipMultiplayerWarning:true` |
| 3 | 启动客户端 | `D:\SoftWare\PCL 正式版 2.12.2\PCL\DeepSeekWarehouse.bat`（直连 `zmcp3.rhymc.com:1069`） | 任务管理器出现 `java.exe`（约 40–90 秒）|
| 4 | 起 MCP server | `powershell -ExecutionPolicy Bypass -File start-mcp.ps1` | 打印 `started pid=… url=http://127.0.0.1:57610/mcp` + `health ok` |
| 5 | 起 daemon | `D:\Node\node.exe mcp-server\src\mc-daemon.mjs` | `127.0.0.1:57611` 可连接（日志 `runtime\mc-daemon.out.log`）|
| 6 | 附加实例 | `mc_instances` → `mc_attach {instanceFile:"<上一步 file>"}` | `good` / `player` 与 gameDir 对得上 |
| 7 | 验证 | `get_state {compact:true}`、`zff_status`、`chat_history` | `player.name == "DSHDeepSeek"` |
| 8 | （可选）子代理 + CR | `$env:ZFF_CR="1"; $env:ZFF_BACKEND="server"; node mcp-server/src/agent/run.mjs chat` | 日志 `CR enabled=true, persona=yes` |

> 顺序可以调换，但有两条依赖：**daemon 必须在 MCP server 之后**（它连 `MCP_URL`）；**`mc_attach` 必须在实例文件出现之后**。
>
> 自检：`node scripts\doctor.mjs` —— 逐项检查 Java / Node / MCP server / daemon / 实例文件 / `get_state`，失败项会打印修复命令。

### 冷启动排错

| 现象 | 原因 / 处理 |
|---|---|
| `java not found` | 用 `-Java` 指定路径，或确认 `D:\java\jdk-21_windows-x64_bin\jdk-21.0.8` |
| 客户端起来了但 MCP 找不到实例 | mod 没装进 **这个版本目录** 的 `mods\`；或客户端还停在加载画面 |
| `57610` 被占用 | 换端口：`start-mcp.ps1 -Port 57620`，daemon 用 `MCP_URL` 指过去 |
| 客户端卡在"多人游戏警告" | `options.txt` 加 `skipMultiplayerWarning:true` |
| 进服被踢 `ModMismatchDisconnectedScreen` | 客户端模组集合与服务端不一致；本 mod 是可选通道，纯客户端玩家不用装 |
| 被踢 `You are not white-listed` | 服务端控制台 `whitelist add DSHDeepSeek` |
| 实例文件有多个 | 选 `player` 是 `DSHDeepSeek`、`inWorld` 为真的那个；人类玩家的不要动 |

### 关停顺序

1. 子代理：`node mcp-server/src/agent/run.mjs stop`，再结束 `run.mjs` 进程；
2. 存档：`save_world`（整合服走 `saveEverything`，别用 `/save-all`）；
3. 客户端：正常退出（直接杀进程会丢最后一次自动存档）；
4. MCP server / daemon：结束进程即可（无状态，实例文件会自动过期）。

---

## 0.6 运行纪律（硬性）

1. **在游玩过程中，除非游戏掉线，否则不能停止输出。**
   每一轮都要有动作或汇报（观察 → 执行 → 说一句）；不许静默、不许空转、不许用"等确认"当停工理由。
2. 掉线是唯一允许暂停的条件：`not_loaded` / `no live Minecraft bridge registration` / 客户端进程消失。
   检测到后立刻走 §0.5 的冷启动恢复，恢复后第一句先报"回来了"。
3. 恢复后**先看背包和坐标**——断线重连时服务端可能把人物数据回滚到上次保存。
4. 长任务必须轮询 `job_status` 到终态；失败不算停，换方法重试（最多三轮，然后换目标并说明）。
5. 这条纪律已写进 [AIPlayer.md](AIPlayer.md) 的「§0 铁律」，角色卡与手册必须一致。

> 冷启动、进服、接管这三步在 [AIPlayer.md](AIPlayer.md) §1–§3 里是**自足**的：新 Agent 只看那一页就能把游戏拉起来并进入游玩状态，本手册用于查细节。

---

## 1. 体系结构（谁在控制谁）

```
[LLM 主代理 / MCP client (DSH)] ── MCP streamable-http ──► 127.0.0.1:57610/mcp
        │
        ▼
[mcp-server  Node 22]  ── 实例发现 / job 代理 / 记忆文件
        │  HTTP 127.0.0.1:57611/call  (daemon)
        ▼
[客户端 mod  mcpbridge 0.1.0  NeoForge 1.21.1]
        │  Minecraft 主线程 (ClientTickEvent)
        ▼
[真实有头客户端] ── 正常多人连接 ──► 目标服务器（zmcp3.rhymc.com:1069 等）
```

- **没有任何服务端端口**：服务端只装同一个 mod，走原版自定义 payload（`mcpbridge:hello|rpc_request|rpc_response|stop`）。
- 所有世界操作都在 `Minecraft.getInstance().execute(...)` 里跑；HTTP 线程不碰世界。
- 长任务返回 `jobId`，状态机 `queued → running → done | failed | cancelled`。

---

## 2. 进入服务器

### 2.1 前置

| 项 | 要求 |
|---|---|
| 客户端 | NeoForge 21.1.228 / MC 1.21.1 / Java 21，**有头**（真实 LWJGL） |
| mod | `mcpbridge-0.1.0.jar` 放 `mods/`（服务端同 jar，可选通道，纯客户端玩家不用装） |
| Baritone | `baritone-api-neoforge-1.11.3.jar`（`path_to/path_open/mine` 等寻路指令依赖它） |
| 账号 | 服务器的离线名固定为 **`DSHDeepSeek`**（UUID = `OfflinePlayer:DSHDeepSeek` → `d6a381d2-0654-38af-93b3-d8421e21b6ee`）；每次进服都以此名登录 |

安装：

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\install.ps1" `
  -VersionDir "<...>\.minecraft\versions\<整合包名>" -WithBaritone
```

### 2.2 启动客户端并连服务器

两种方式，任选：

- **人类方式**：PCL 启动整合包 → 多人游戏 → 双击服务器条目。
- **Agent 方式（推荐）**：用带 `--quickPlayMultiplayer` 的启动脚本直接进服：

```bat
javaw.exe ... --gameDir "<...>\versions\<整合包>" --quickPlayMultiplayer "zmcp3.rhymc.com:1069"
```

> 若客户端停在"多人游戏警告"，在 `options.txt` 里加 `skipMultiplayerWarning:true`。
> 连不上时先看客户端日志末尾：`ModMismatchDisconnectedScreen` 说明客户端 mod 集合与服务端不一致
> （本 mod 是可选通道，纯客户端玩家不会被踢）。

### 2.3 启动 MCP server

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\start-mcp.ps1"
# 默认 http://127.0.0.1:57610/mcp，日志 runtime\mcp-server.err.log
```

### 2.4 找到并附加到客户端实例

- 实例文件：`%LOCALAPPDATA%\state\headlessmc-mcp\instances\<uuid>.json`（5 秒心跳，20 秒过期）。
- `mc_instances` 列出所有实例（port / pid / gameDir / inWorld / player）。
- `mc_attach` 二选一：
  - `{"instanceFile":"<path>.json"}`
  - `{"port":<random>,"tokenFile":"<path>"}`
- **多开时务必确认 gameDir / player**：别把人类玩家的窗口当成自己的（本项目里人类客户端叫 `假期增援计划（人类用户）`）。

### 2.5 健康检查（每次接管都跑）

```
get_state {compact:true}        # player.name / pos / dimension / server / hp / food
zff_status {}                   # 服务端视角：在线玩家、watchdog、memory/todo 计数
chat_history {limit:20}         # 玩家说了什么
bridge_info {}                  # 缓存值！只用于看 mod 版本/能力，不用于判断在线
```

判断"活着"只信 `get_state`；`bridge_info` 的 `inWorld` 是缓存，可能过期。

---

## 3. MCP 工具地图（84 个）

> 参数细节与返回值示例见 [docs/Ph5/TOOLS.md](docs/Ph5/TOOLS.md)。这里按"什么时候用"分组。

### 3.1 状态与感知

| 工具 | 用途 | 要点 |
|---|---|---|
| `get_state` | 玩家/世界快照 | `compact:true` 去掉大字段；含 `world.time`（昼夜/天气，Ph5 D5）|
| `scan_connected` | 连通表面扫描 | 正常探索首选 |
| `scan_surface` | 表面 flood fill | `radius`≤64，`mode=connected|los|fov`；返回 `surface/heightMap/containers/entities`；**可能 truncated** |
| `scan_area` | 暴力球扫 | **调试/X-ray 用，勿用于游戏决策**；返回 `truncated` |
| `block_query` | 精确查方块 | `positions` 列表，可带 `includeProperties` |
| `entity_query` | 附近实体 | 玩家/怪物/掉落物/容器实体 |
| `collision_query` | 碰撞检查 | 放方块前确认 |
| `damage_log` / `watch_damage` | 受伤记录 | 受伤原因排查 |
| `light_map` / `torch_at` | 光照/插火把 | 防刷怪 |
| `screenshot` | 抓真实画面 | `inline:true` 直接给视觉模型 |

### 3.2 移动与寻路

| 工具 | 用途 | 要点 |
|---|---|---|
| `walk` | 按世界朝向走一小段 | `yaw/ticks/jump/sprint/up`；最稳的"挪两步" |
| `walk_to` | 平面走到坐标 | 短线用，遇障碍会卡 |
| `path_to` | Baritone 寻路 | **只收整数坐标**；`range` 到点半径；返回 jobId |
| `path_status` / `path_cancel` / `path_open` | 路径控制 | 取消要带 jobId |
| `set_pathing_policy` | 寻路策略 | 允许/禁止破坏、跳跃等 |
| `tower` | 搭柱上升 | 看门狗 mode=tower |

### 3.3 挖掘 / 建造 / 交互

`mine_block`（`positions` ≤64，返回 jobId）、`dig_shaft`、`dig_sealed_shaft`（水下密封井）、`dig_tunnel`、`branch_mine`、`place_block`、`use_block`（`face` 朝上 = 地面播种/放置）、`break_entity`、`entity_interact`（`action:trade`）、`collect_drops`、`drop_junk`/`dump_junk`/`delete_item`、`throw_item`、`eye_throw`、`track_entity`、`place_boat`/`boat_sail`、`mlg_water`、`auto_respawn`。

### 3.4 容器与物品栏

| 工具 | 用途 | 要点 |
|---|---|---|
| `container_open` | 右键开容器 | **站到 4 格内**；返回 `slots`（含 `playerInventory` 标记）；`slots.length==0` 视为失败 |
| `container_read` | 重读菜单 | 比 `open` 的 slots 更完整 |
| `container_move` | 点击槽位 | `fromSlot` = **菜单槽索引**；`mode=quick` 为 shift-click（容器↔玩家背包）|
| `container_close` | 关闭 | 每次操作完都关 |
| `loot_chest` | 一键拿走 27 槽 | 有破坏性，先确认 |
| `inventory_slots` / `hotbar` / `inventory_swap` / `select_slot` | 背包操作 | `select_slot` 只切快捷栏 0–8；主背包用 `inventory_swap` |
| `villager_trades` / `trade_select` | 村民交易 | — |

### 3.5 聊天 / 命令 / 事件

| 工具 | 用途 | 要点 |
|---|---|---|
| `chat` | 发聊天或 `/命令` | 中文务必走 UTF-8（见 8.5）|
| `chat_history` | 只读历史 | 含 `type`（chat/system）、`outgoing`、`time` |
| `command` | 执行服务器命令 | 返回 `known`：命令是否在当前命令树（权限不足时为 false）|
| `wait_events` | 阻塞等事件 | `since`+`cursor`，聊天/受伤/死亡/复活/`stuck`/`zff_stop` |

### 3.6 任务（job）

`job_status {jobId}` → `state/progress/metrics/reason`；`job_cancel {jobId}`；`stop_all` 一次性取消全部并停 Baritone。
**规则**：`mine_block/dig_*/path_to/tower/craft/collect_drops/...` 都是异步，必须轮询到终态再继续。

### 3.7 服务端共享记忆 / 待办（mod 侧 SavedData）

`zff_status`、`zff_memory_set|get|list|forget`、`zff_todo_add|list|done|update|clear`。
数据在 `<world>/data/zff_agent.dat`，**同一世界所有客户端共享**，重启不丢。

### 3.8 本地记忆文件

`memory_write {name,content,mode}` / `memory_read` / `memory_list` → `<workspace>/.mcp-agent/`。
适合放：目标、坐标、踩坑结论、交接说明。

### 3.9 实例 / 杂项

`mc_instances`、`mc_attach`、`bridge_info`、`resync`（关界面+取消 job+停 Baritone+背包快照）、`save_world`（整合服里走 `MinecraftServer.saveEverything`，**不是** `/save-all`）。

### 3.10 批量 / 并行

`batch`、`parallel`：多个调用打包，减少往返；不适合长任务（长任务走 job）。

---

## 4. 作业模型与超时

```jsonc
// 启动
{"jobId":"j-a-1b2c","state":"queued"}
// 轮询
{"state":"running","progress":42,"metrics":{"blocksMined":12,"total":30}}
// 结束
{"state":"done"|"failed"|"cancelled","reason":"stalled|out_of_reach|timeout|...","metrics":{...}}
```

- `timeoutSeconds` 是**作业内部**的预算，不是 RPC 超时；RPC 由 `timeoutMs` 控制。
- 失败原因常见：`out_of_reach`（目标 >4.5 格）、`stalled`（看门狗 3 秒无进展）、`cancelled`。
- 同一实体同时只该有一个移动类 job；切目标先 `job_cancel`。

---

## 5. 多子代理协作架构（SR / CR / AR / ESRS / ESRA）

```
        ┌────────────┐  chat   ┌────────────┐
        │  SR 调度器  │◀───────▶│  CR 聊天代理 │
        └─────┬──────┘         └────────────┘
     spawn/   │  ▲ events
     cancel   │  │
        ┌─────▼──┴───────┐    ┌────────────────┐
        │  AR 动作执行器   │◀───│ ESRA 看门狗     │
        └─────┬──────────┘    └────────────────┘
              │ MCP tools
        ┌─────▼──────┐
        │  mcpbridge │
        └────────────┘
  ESRS 环境传感器 ──► SR
```

| 角色 | 文件 | 职责 |
|---|---|---|
| SR | `mcp-server/src/agent/sr.mjs` | 持有 TodoList、派发任务给 AR、消费 ESRS 事件、处理中断与 `/zff stop` |
| CR | `mcp-server/src/agent/cr.mjs` | 读聊天、区分闲聊/任务、回复玩家、把任务塞进 SR 队列 |
| AR | `mcp-server/src/agent/ar.mjs` | 一次执行一个任务（`spec:{tool,args}` 或 `{steps:[...]}`），轮询 job 到终态 |
| ESRS | `sensors.mjs` | 血量/饱食/氧气/位移 → `damage`/`air_low`/`hunger_low`/`player_state` |
| ESRA | `sensors.mjs` | 任务级 3 秒看门狗（tower 看 y、walk 看 xz、dig 看 y/xz）→ `stuck` |
| Bus | `core.mjs` | 进程内事件总线，`seq` 单调，落盘 `runtime/agent/events.jsonl` |
| TodoList | `todolist.mjs` | `runtime/agent/todolist.json`（或服务端 SavedData）|
| Memory | `memory.mjs` | `runtime/agent/memory.json`（或服务端 SavedData）|

**硬性规则**：父子代理不互相等待（唯一例外：SR 可以等 CR 的回复）；AR 阻塞由 ESRA 3 秒上报；SR 空闲时向 CR 要任务。

看门狗默认由 **mod 的 Java tick** 提供（`watchdogInMod:true`），Node 侧会自动让位，避免重复上报。

### 5.1 启动命令

```powershell
cd "D:\mc\MCP Development"
$env:ZFF_BACKEND = "server"          # memory/todo 放服务端（多客户端共享）
node mcp-server/src/agent/run.mjs status      # 看 todo/memory/backend
node mcp-server/src/agent/run.mjs sensors 10  # 只跑传感器，打印事件（不开 CR）
node mcp-server/src/agent/run.mjs demo 30     # 加两个演示任务，SR 驱动 AR
node mcp-server/src/agent/run.mjs chat        # 全栈 + CR 聊天应答（见第 6 节）
node mcp-server/src/agent/run.mjs stop        # 取消全部 job/任务
```

### 5.2 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `ZFF_BACKEND` | `file` | `server` = 记忆/待办存到 mod 的 `<world>/data/zff_agent.dat` |
| `ZFF_CR` | 未设 | `1` 才启用 Chatter（开发规则：默认关）|
| `ZFF_ACTOR_ID` | `ai-1` | 多 AI 共存时的身份 |
| `ZFF_SERVER_ID` | `local-dev` | 记忆隔离键 |
| `MC_ROOT` | `D:/mc/MCP Development` | workspace 根 |
| `MC_DAEMON` | `http://127.0.0.1:57611` | daemon 地址 |

### 5.3 任务格式

```jsonc
{"title":"挖 32 圆石","priority":2,"spec":{"tool":"mine_block","args":{"positions":[[0,60,0]]}}}
{"title":"补种小麦","priority":3,"spec":{"steps":[{"tool":"select_slot","args":{"slot":4}},{"tool":"use_block","args":{"x":1,"y":64,"z":2,"face":"up"}}]}}
```

没有 `spec` 的任务（例如聊天里来的自然语言任务）**不会被 AR 执行**——它只是在清单里等主代理（LLM）翻译成 `spec`。

---

## 6. 启用 Chatter（CR）

### 6.1 它做什么

1. 每 2 秒读 `chat_history`，只看 `type=="chat"` 且非 `outgoing` 的玩家消息。
2. 关键词判定（`去/挖/拿/建/找/放/砍/打/回/来/送/做/合成/任务/帮我/go/mine/build/craft/dig/chop`）：
   - 任务 → `chat_task` 事件 + 写入 TodoList + （autoReply 时）回一句"收到：…"
   - 闲聊 → `chat_smalltalk` 事件 + （autoReply 时）用**角色卡里的第一句人设**回复。
3. 把最后一条玩家消息写进 Memory `chat.last_player`。
4. SR 空闲时发 `task_request`，CR 立刻回 `chat_reply`（不阻塞 SR）。

### 6.2 两种开启方式

```powershell
# A. 只给这一次 run 打开（推荐）
node mcp-server/src/agent/run.mjs chat

# B. 全局打开，其它命令也带 CR
$env:ZFF_CR = "1"
node mcp-server/src/agent/run.mjs demo 30
```

`chat` 模式 = sensors + SR + AR + CR(autoReply=true)，一直跑到 Ctrl+C / `timeout`。
启动日志会打印 `CR enabled=true, persona=yes|no`。

### 6.3 人设从哪来

`AIPlayer.md` 里这一节：

```markdown
## 性格 / Personality
（这里写 3–8 行，CR 会取第一行作为闲聊回复；主代理会整段读入作为行为基调）
```

CR 启动时 `loadPersona()` 解析该节，存进 Memory `persona.text`。文件不存在也不报错，只是 `persona=no`。

### 6.4 关闭

- 前台：Ctrl+C（会 flush TodoList）。
- 后台：`node mcp-server/src/agent/run.mjs stop`，再结束该 node 进程。
- 服务端侧：管理员 `/zff stop` 会取消全部 job 与任务，并写 `zff_stop` 事件（CR/SR 都会收到）。

> 开发期规则：默认 `ZFF_CR` 不设，避免"人还在测试、AI 已经抢话"。发布/正式陪玩时再开。

---

## 7. 角色卡 AIPlayer.md

- 这是**玩家手填**的文件：性格、语气、禁忌、目标、世界笔记。
- 主代理每次接管都应该先读它；CR 只读 `## 性格 / Personality` 一节。
- 建议每次都把"今天要做什么 / 不要做什么"写进 `## 本次目标`，主代理会优先执行。

模板见 [AIPlayer.md](AIPlayer.md)。

---

## 8. 安全与踩坑（都是实际踩过的）

### 8.1 不要碰人类的客户端

`mc_instances` 里可能有多个 bridge。人类玩家的实例（例如 gameDir 含"人类用户"）**只读不改**：不要点、不要移动、不要 `stop_all`。选错实例会替玩家操作角色。

### 8.2 容器：先站近，再开

- `container_open` 在**超出 4.5 格**时不会报错，而是返回一个空的 `slots`（"空菜单"）。
  **判据**：`slots.length === 0` → 当成失败，先 `path_to`/`walk` 靠近再开。
- 我因此误判过"160 个箱子全是空的"，实际全满。
- `container_move` 的 `fromSlot` 是**菜单槽索引**（容器 0..N-1，玩家背包 N..N+35），不是背包槽。
  `mode=quick` 就是 shift-click；目标容器满时不会移动，也不会报错 → **必须回读**。
- 一次只搬 1 叠并回读，比"一次搬 20 叠"安全得多。

### 8.3 坐标与寻路

- `path_to` 只接受**整数**：`Math.floor` 一下，否则 `Expected integer, received float`。
- 走廊/箱子缝里 Baritone 可能 `path failed`；先用 `walk` 挪出窄处再 `path_to`。
- `walk_to` 遇障碍会卡住；长距离一律 `path_to`。

### 8.4 扫描截断

`scan_surface` / `scan_area` 有字节上限，会 `truncated:true`。**不要用一次扫描当全量**：
按 x 分行、分批扫，或改用 `scan_area(filter:...)` 精确取某类方块。

### 8.5 中文与编码（Windows）

- PowerShell 命令行里拼 JSON 会把中文变成 `????`；**先把文本写进 UTF-8 文件，再用 Node 读文件发送**。
- 游戏内中文聊天已验证可行（`chat` + UTF-8 文件）。

### 8.6 断线与回滚

- 网络抖动会掉线；重连后**人物数据可能回滚**到服务器上次保存（背包物品消失，世界改动通常还在）。
- 重连后先 `get_state` 确认背包/坐标，再继续任务；重要物品不要长时间拿在身上。

### 8.7 其它

- `bridge_info` 是缓存，判断在线用 `get_state`。
- `save_world` 在整合服里是 `MinecraftServer.saveEverything(...)`，不要用 `/save-all`（可能不存在）。
- 没有专门的"环顾"工具：用 `walk` 的 `yaw` 转身，或 `screenshot` 看画面。
- 破坏性操作（挖建筑、倒岩浆、清箱子）**先问玩家**。

---

## 9. 参考

| 文档 | 内容 |
|---|---|
| [AIPlayer.md](AIPlayer.md) | 角色卡（玩家手填）|
| [docs/Ph5/TOOLS.md](docs/Ph5/TOOLS.md) | 84 个工具的完整参数/返回 |
| [docs/Ph5/AGENT-ARCHITECTURE.md](docs/Ph5/AGENT-ARCHITECTURE.md) | SR/CR/AR/ESRS/ESRA 设计与协议 |
| [docs/Ph5/PHASE5-PROGRESS.md](docs/Ph5/PHASE5-PROGRESS.md) | 各任务状态与证据 |
| [README.md](README.md) | 安装、启动、DSH 接入 |
| [docs/Ph2/TROUBLESHOOTING.md](docs/Ph2/TROUBLESHOOTING.md) | 客户端/模组/连接排错 |

---

## 10. Ph6 之后有什么变了（2026-11-01）

### 10.1 SR/CR/AR 的 Node 运行时没了

`mcp-server/src/agent/{sr,cr,ar,sensors,run,todolist,memory}.mjs` **全部删除**。现在的角色划分是：

| 角色 | 谁 | 怎么起 |
|---|---|---|
| **CR**（主 Agent） | DSH 会话里的主代理（建议 reasoning=low） | 用插件 **ZFF MC** 的 `ZFF MC Mode` 开会话 |
| **SR / AR / ESRC** | DSH 子代理 | CR 用 `subagent` 派单；工具面靠提示词（硬隔离要装 `dsh-background-agents`） |

脚本里如果还要调工具，用 `mcp-server/src/lib/mcp-client.mjs`（`call()` / `tool()`，和旧 `agent/core.mjs` 同形）。

### 10.2 新原语（只给 CR 用）

| 工具 | 说明 |
|---|---|
| `chat_turn {message?, drain?, ack?}` | 说完再听。`drain:false` 只发不听且**不清** `Flag_User_Chat`；`message` 可传数组（≤5） |
| `env_read` | 读客户端常驻采样（位置/血量/氧气/时间/天气/当前 job/附近/警报/staleMs） |
| `store_get/set/list/delete` | CR store 可写；`store:{store:"mod"}` 只读客户端写的 `mod-store.json` |
| `zff_preempt` | 抢占时记一条 `preempted` 进 CR store |
| `zff_chat_stats` | 聊天中断诊断（unread / cursor / 标志 / 规则） |

### 10.3 聊天中断（`Flag_User_Chat`）

玩家发了匹配规则的消息（默认 `^鱼`）后，MCP server 会把**除 `chat_turn` 以外**的所有工具调用挡下来，
返回 `{ok:false,error:"user_chat_pending"}`；阻塞中的 `wait_events` 最多 2 s 就返回同样的中性错误
（**不会取消底层任务**）。正确反应只有一条：立刻 `chat_turn`。

### 10.4 服务端可选

`bridge_info.backendLabel` 有三种：`server`（服务端装了且协议一致）/ `local(mismatch)`（装了但版本不同，
会同时给 `clientVersion` 与 `serverVersion`）/ `local`（服务端没装）。三态客户端都能玩，
只有共享记忆、待办、`/zff stop` 广播需要 `server`。

### 10.5 新增/修好的工具

`wake`（下床）、`eat` / `use_item`（完整进食状态机）、`read_book` / `read_sign` / `item_info`（读书/读牌子/看组件）、
`screenshot_gui`（含 GUI 的截图）、`walk_dir`（方向语义化）、`path_open_ex`；
`place_block` 会自动把物品从背包换到快捷栏、`use_block` 支持 `mode:"item_on"` 与 `sneak:true`、
`scan_surface` 的 `doors` 现在真的有内容、`entity_query` 大半径改分环扫描、`throw_item` 支持 `holdTicks`。

---

## 11. 变更记录

- 2026-10-31：首版。覆盖进入服务器、84 工具地图、job 模型、SR/CR/AR 架构与 `run.mjs chat` 启用方式、角色卡约定、8 类实战坑。
- 2026-11-01（Ph6）：删除 Node 运行时（改 CR 主代理 + 子代理）；新增 `chat_turn`/`env_read`/`store_*` 与聊天中断；
  服务端改为可选（三态）；新增 9 个工具、修 8 个 ISSUE。
