# 交接说明：上一任 Agent 做了什么（Ph5 收尾 → Ph6 立项）

> 2026-11-01 由离任 Agent 编写。请先读本文件，再读 `agent-prompt6.md`。
> 本目录所有文档均为中文；`AIPlayer.md` / `AIPlayerManual.md` 本来就是中文，无需处理。

---

## 1. 一句话总结

我接手了 Ph5 的交接，在真实客户端上复核了 Ph1–Ph5 的工具，然后和玩家在他的服务器上**实打实地玩了几个小时**
（收农田、整理仓库、睡觉、顺手补工具缺口）。那场游戏暴露了一批工具与编排问题，都记进了 `ISSUE.md`。
之后我和玩家一起把 Ph6 架构定了下来（CR 主代理、子代理、聊天/环境 inbox、服务端可选、**ZFF MC** 插件），
写成了 `agent-prompt6.md` + `ARCHITECTURE-PH6.md`。
**Ph6 的代码一行都没写**——仓库现在处于"Ph5 已验证、Ph6 已定稿"的状态。

---

## 2. 本场开始时的状态

- Ph1–Ph5 代码已在：NeoForge 客户端+服务端 mod（`mcpbridge-0.1.0.jar`）、**84 个工具**的 MCP server、
  SR/CR/AR/ESRS/ESRA 的 Node 运行时、服务端 `SavedData` 记忆/待办。
- 已验证的能力：移动、寻路、挖/放、容器、合成、实体、扫描、截图、聊天、命令、job 取消、存档。
- mod 同时装在玩家服务器的**客户端与服务端**（`zmcp3.rhymc.com:1069`）。

---

## 3. 本场我做了什么

### 3.1 游戏内实操（在玩家服务器上，现在已禁止用于测试）

| 事项 | 结果 |
|---|---|
| 收农田 | 45 株成熟作物 → 小麦 43、小麦种子 107、辣椒 31、辣椒种子 19 |
| 补种农田 | 81 格耕地，补种 78 格（小麦 57、辣椒 18、水稻 3） |
| 盘点仓库 | 两排 48 个箱子、1768 叠、434 种物品、约 59,590 件 |
| 合并大宗物品 | 泥土 768 → `-4806,68,1152`；杉木原木 896 → `-4806,69,1153` |
| 设重生点 | `-4800,72,1130` 红床（"Point de réapparition défini"） |
| 陪玩家过夜 | day 486 → 487 |
| 跟随玩家 | 读服务端坐标后整数化传 `path_to`（长跟随循环会被中断，别用） |

### 3.2 事故（改变了 Ph6 的设计）

1. **背包回滚**：掉线重连后服务端把人物数据还原到上次保存——收的作物消失，**没有死亡/受伤记录**。
   教训：重要物品别长时间拿在身上；每次重连先看背包。
2. **仓库丢物品**：目标箱满/菜单已关时 `container_move` **静默失败**；两次取出 6 叠 + 14 叠都没投进去，
   共丢约 13 叠作物。教训：**一次只搬 1 叠并回读**。
3. **"箱子全是空的"是误判**：`container_open` 在**超过 4.5 格**时返回空菜单而不是报错。
   教训：判据必须是 `slots.length === 0`，先走近。
4. **客户端自己退出**：21:12 左右 `java.exe` 消失，gameDir 下没有 `latest.log`、没有 crash-report，原因不明；
   `start-all.ps1` 能拉回来。
5. **CR 自问自答**：服务器把自己发的话回声成普通聊天，不做 `self` 过滤就会无限回复。
   已在 `cr.mjs` 加 `selfName` 守卫（Ph6 要保留这个行为）。

### 3.3 我加的代码与脚本

| 文件 | 内容 |
|---|---|
| `mcp-server/src/agent/run.mjs` | 新增 `chat` 命令 = 传感器 + SR + AR + CR 在游戏里回话 |
| `mcp-server/src/agent/cr.mjs` | 从 `AIPlayer.md` 读人设、`autoReply`、`selfName` 防自答 |
| `start-all.ps1` | 冷启动：环境检查 → MCP server → daemon → 客户端 → 打印实例文件 |
| `scripts/doctor.mjs` | 自检：Java/Node/MCP/daemon/实例/`get_state`/账号/服务器 |
| `scripts/say.mjs` | UTF-8 安全地发游戏聊天（文本走文件，不走 PowerShell 命令行） |
| `scripts/chat-listen.mjs` | 轮询 `chat_history`，把新消息追加到 `runtime/chat-live.log` |
| `scripts/harvest.mjs`、`harvest2.mjs` | 带距离判断的成熟作物收割 |
| `scripts/audit-rows.mjs`、`consolidate.mjs`、`sort2.mjs`、`sort-to-target.mjs`、`collect-deposit.mjs` | 仓库盘点/合并实验 |
| `scripts/make-inventory-doc.mjs` | 从盘点 JSON 生成 `docs/STORAGE-INVENTORY.md` |
| `scripts/set-spawn.mjs`、`follow-player.mjs`、`remember-city.mjs` | 设重生点、跟随、写记忆 |

### 3.4 我加的文档

| 文件 | 内容 |
|---|---|
| `AIPlayerManual.md` | Agent 怎么进服、84 工具地图、job 模型、SR/CR/AR 架构、CR 启用、踩坑 |
| `AIPlayer.md` | 角色卡（鲸鱼娘女仆、DeepSeek 民间形象）+ 世界笔记；性格一节由玩家手改 |
| `docs/CLIENT-ONLY-ANALYSIS.md` | 仅客户端方案的可行性/代价分析、四条通道对比、迁移计划 |
| `docs/STORAGE-INVENTORY.md` | 两排仓库的逐箱清单（生成物） |
| `docs/ph6/*` | 本文件、`agent-prompt6.md`、`ARCHITECTURE-PH6.md`、`ISSUE.md`、`reference/` |

### 3.5 账号与启动器

- 离线账号改为 **`DSHDeepSeek`**（UUID `d6a381d2-0654-38af-93b3-d8421e21b6ee`）。
- `DeepSeekWarehouse.bat`、`DeepSeekHeadless.bat` 已改（`--username DSHDeepSeek` + 新 UUID）。
- 换名字 = 服务器上的**新玩家**：背包清空、出生在世界出生点 `(11,76,8)`。

---

## 4. 交接时的机器状态

| 项目 | 值 |
|---|---|
| 工作区 | `D:\mc\MCP Development` |
| Java / Node | `D:\java\jdk-21_windows-x64_bin\jdk-21.0.8` / `D:\Node\node.exe`（v22.19.0） |
| MCP server | `127.0.0.1:57610`（`start-mcp.ps1`），daemon `127.0.0.1:57611` |
| 客户端实例文件 | `%LOCALAPPDATA%\state\headlessmc-mcp\instances\*.json` |
| 本地测试服 | `D:\mc\MCP Development\server`（NeoForge、25565、offline、flat、世界 `ZffServerTest`） |
| 玩家服务器 | `zmcp3.rhymc.com:1069` —— **禁止用于测试** |
| 人类客户端实例 | `versions\假期增援计划（人类用户）` —— **绝对不要操作** |

开工前先跑 `node scripts\doctor.mjs`，红了先修。

---

## 5. 没做完的事

- **Ph6 代码零实现**：`agent-prompt6.md` 里的计划全部未动。
- 记忆/store 的正式设计留到 **M5**，必须先和玩家商讨。
- 插件 API 验证（**M0.5**）没做。
- `ISSUE.md` 里的问题全部未修（只有 CR 自答循环做了个临时守卫）。

---

## 6. 十条不用再踩的坑

1. 不要在玩家服务器上测试；用本地三态（同版 / 删 jar / 旧 jar）。
2. 隔空开箱返回空菜单——先看 `slots.length === 0`，再走近。
3. `container_move` 不会告诉你"箱子满"——每次移动后回读，一次一叠。
4. `path_to` 只收整数坐标，小数会被 schema 校验拒绝。
5. 走廊里 Baritone 会 `path failed`；先用 `walk` 挪出窄处。
6. `scan_surface` 会截断：同一片农田两次扫描得到 45 与 81 格。别把一次扫描当全量。
7. `bridge_info` 是缓存——判断在线用 `get_state`。
8. 清理过期的 `instances\*.json`（我清出 169 个，最老 55 小时）。
9. PowerShell 拼中文会变 `????`——写 UTF-8 文件让 Node 发。
10. 重连后人物数据可能回滚，先看背包再继续。

---

## 7. 语言约定

- 本目录（`docs/ph6/`）全部中文。
- `AIPlayer.md` / `AIPlayerManual.md` 保持中文（玩家是中文，人设也是中文；Chatter 读的就是中文那行）。
- `docs/ph6/reference/` 是支撑文档的**原文副本**（本来就是中文），保持逐字不改；需要英文版再按需翻译，
  不要直接改副本。

---

## 8. 从哪开始

1. 读 `agent-prompt6.md`（任务）与 `ARCHITECTURE-PH6.md`（设计）。
2. 跑 `node scripts\doctor.mjs`，红了先修。
3. **先做 M0.5**（插件 API 验证），再碰 M4；结果先汇报，别在假设上开工。
4. 从 **M0** 开始，然后是 **M1**；记忆最后做（M5，先和玩家商讨）。
