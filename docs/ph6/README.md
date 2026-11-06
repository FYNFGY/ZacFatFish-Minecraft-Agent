# Ph6 — ZFF MC

Ph6 把项目从"开发工具"变成"能陪玩的 AI 玩家"：**CR 主代理**、子代理（SR / AR / ESRC）、聊天与环境 inbox、
双 store、服务端可选，以及 DSH 插件 **ZFF MC**。

## 按这个顺序读

| # | 文件 | 为什么 |
|---|---|---|
| 1 | [`agent-prompt6.md`](agent-prompt6.md) | 任务：目标、决定、里程碑、验收 |
| 2 | [`HANDOVER.md`](HANDOVER.md) | Ph5 结束时上一任 Agent 的交接 |
| 3 | [`ARCHITECTURE-PH6.md`](ARCHITECTURE-PH6.md) | 拓扑、原语、时序、状态机（设计契约） |
| 4 | [`ISSUE.md`](ISSUE.md) | Ph6 必须修的真实陪玩问题 |
| 5 | 本轮的实测报告 ↓ | 设计哪里落地了、哪里偏了、哪里没做成 |
| 6 | [`reference/`](reference/) | 支撑文档（中文原文） |

## 本轮（Ph6 实现）报告

| 里程碑 | 报告 | 结论一句话 |
|---|---|---|
| **M0 + M1** | [`REPORT-M0-M1.md`](REPORT-M0-M1.md) | 本地双客户端打通；**阻塞工具被一句 `鱼…` 在 125 ms 内打断**（预算 2500 ms），`chat_turn` 随后读消息并清标志 |
| **M2** | [`REPORT-M2.md`](REPORT-M2.md) | 旧 Node 运行时全部删除、`mcp-client.mjs` 迁移完成、双 store 落地；子代理/抢占是契约 + 提示词，硬隔离依赖 `dsh-background-agents` |
| **M3** | [`REPORT-M3.md`](REPORT-M3.md) | 三态全部真机通过；顺手修掉 Ph5 遗留的 `PayloadRegistrar.optional()` 缺陷（`optional()` 是拷贝构造，返回值不能丢） |
| **M4** | [`REPORT-M4.md`](REPORT-M4.md) | 插件 `D:\mc\zff-mc` 完成，离线自检 163/163；**从未在真宿主加载过**（不重启 DSH 的硬约束） |
| **M5** | [`M5-MEMORY-DESIGN.md`](M5-MEMORY-DESIGN.md) | 只是提案：6 个待玩家拍板的问题，**没有动实现** |
| ISSUE 批量 | [`REPORT-ISSUES.md`](REPORT-ISSUES.md) | ISSUE-2/3/4/5/8/11/12/13/15 已修，14 条真机断言见 `evidence/ph6-m2-issues-live.json` |
| M0.5 插件 API | [`PH6-M0.5-PLUGIN-API.md`](PH6-M0.5-PLUGIN-API.md) | turn-end **不存在**；用户消息注入 / preset / 工具 + 设置页都可行 |
| system prompt | [`ZFF-MC-SYSTEM-PROMPT.md`](ZFF-MC-SYSTEM-PROMPT.md) | ZFF MC 模式的 system prompt 从哪来、怎么改；已把首句改成 `You will be a player in a Minecraft world.` |
| 事故记录 | [`INCIDENT-2026-11-01-PLUGIN-BOOT.md`](INCIDENT-2026-11-01-PLUGIN-BOOT.md) | 插件 client bundle 非幂等 → DSH 启动失败；根因、修复、防复发（含 `scripts/zff-mc-toggle.mjs` 一键开关） |

## 实现状态

| 阶段 | 状态 |
|---|---|
| Ph1–Ph5 | 已实现并验证（见 `../Ph5/`） |
| Ph6 设计 | 已完成 |
| Ph6 M0 / M1 | ✅ 实现 + 真机验收（含证据） |
| Ph6 M2 | ✅ 能落在仓库里的部分；⚠️ 子代理硬隔离缺宿主插件 |
| Ph6 M3 | ✅ 三态全部真机通过（server / local(mismatch) / local） |
| Ph6 M4 | ✅ 代码 + 离线自检；❌ 未在真宿主加载 |
| Ph6 M5 | 📝 只有设计提案，等玩家商讨 |

## 未完成与风险（下一位先看这里）

1. **插件一次都没在真宿主加载过**（本场禁止重启 DSH）。第一次装必然要排错，重点见 `REPORT-M4.md` §6。
2. **提示词不是真 system prompt**：`system-prompt/assemble` 的载荷形状没有证据，插件没挂它；现在是"每轮第一条消息 + preset persona"。
3. **子代理硬隔离缺宿主插件**：没装 `dsh-background-agents` 时，SR/AR/ESRC 的"只读/只动作"只是提示词约束。
4. **续跑精度**：没有 turn-end 钩子，靠 2 s 轮询 `agent.status` 的 `running→idle` 跳变。
5. **`Flag_User_Chat` 只有 drain 能清**：没有续跑的会话（普通模式）被标志挡住时，只能手动 `chat_turn`。
6. **M5 记忆一行没动**：schema/迁移/清理/`zff_agent.dat` 导入四件事都等玩家拍板（`M5-MEMORY-DESIGN.md`）。
7. **`baritoe` 拼写**：`neoforge.mods.toml` 里 Baritone 的可选依赖 modId 写成了 `baritoe`（Ph5 遗留），
   所以"没装 Baritone"时不会给出清晰缺失提示；`path_to` 运行时会自己报 `unsupported`，不影响使用。

## 快速上手（本机）

```powershell
# 1) 服务与本地服
powershell -File start-mcp.ps1                     # MCP server 57610 + ingest 57612
node mcp-server\src\mc-daemon.mjs                   # daemon 57611（另开窗口）
# 本地 NeoForge 服：server\ 下 java @user_jvm_args.txt @libraries\net\neoforged\neoforge\21.1.228\win_args.txt nogui

# 2) 客户端（只装 mcpbridge 的干净实例；257 mod 的整合包连不上本地服，见 REPORT-M0-M1 §5）
D:\SoftWare\PCL 正式版 2.12.2\PCL\DeepSeekPh6Min.bat      # D:\mc\client-test   DSHDeepSeek
D:\SoftWare\PCL 正式版 2.12.2\PCL\DeepSeekPh6Player2.bat  # D:\mc\client-test2  FYNyesterday（只用来聊天）
node scripts\ph6-connect-all.mjs                    # 两个客户端都进服

# 3) 验收
node test\ph6-chat-smoke.mjs                        # 离线 19 项
node test\ph6-m1-live.mjs                           # 真机 21 项（含聊天中断计时，实测 125 ms）
node test\ph6-issues-live.mjs                       # 真机 14 项（ISSUE 批量）
node test\ph6-safety-live.mjs                       # 真机 9 项（硬安全 + 管理员下线 dry-run）
node scripts\ph6-probe.mjs --state server           # 记录 backend 与延迟
```

启动脚本用 `scripts/make-local-launcher.mjs` 生成：它复制玩家现有 PCL 脚本的 JVM/classpath，**只改 quickPlay
目标**，遇到 `rhymc.com` 直接拒绝生成。

## 硬性规则

- 只在**本地**服务器测试；**禁止**连接 `zmcp3.rhymc.com:1069`。
- 不安装 vanilla server jar；用 NeoForge 服的「同版 / 删 jar / 旧 jar」三态。
- 绝不操作人类玩家的客户端实例（gameDir 含"人类用户"）。
- 记忆设计（M5）必须先和玩家商讨再实现。

## 语言

本目录全部中文。`AIPlayer.md` 与 `AIPlayerManual.md` 本来就是中文（玩家与人设都是中文）。
`reference/` 保留原文副本，不翻译、不改写。
