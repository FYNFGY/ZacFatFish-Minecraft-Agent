# 19 — 源码地图

## MCP Server

| 路径 | 作用 |
|---|---|
| `mcp-server/src/server.js` | 122 个工具注册、HTTP 路由、schema、job/chat/store 接入 |
| `mcp-server/src/lib/emergency.mjs` | InfoTxt 队列、tool_low/combat/fall 行 |
| `mcp-server/src/lib/jobs.mjs` | （如存在）Job 辅助 |
| `mcp-server/src/lib/chat-sanitize.mjs` | 聊天/命令清洗 |
| `mcp-server/src/lib/reachability.mjs` | 本地 reachable_synthese 算法 |
| `mcp-server/src/lib/pendulum.mjs` | Pendulum MCP TCP 短连接路由 |
| `mcp-server/src/lib/store.mjs` | CR store / mod store 读取 |
| `mcp-server/src/lib/chat-hub.mjs` | 未读聊天/cursor/Flag_User_Chat |

## Bridge（NeoForge）

| 路径 | 作用 |
|---|---|
| `mod-mdk/src/main/java/dev/mcp/bridge/BridgeRuntime.java` | 启动 HTTP bridge、tick、注册心跳 |
| `.../HttpBridge.java` | HTTP 工具/Job 接口 |
| `.../Jobs.java` | Job 生命周期、cancel、active |
| `.../Tools.java` | 工具 switch、参数解析、Job 创建 |
| `.../CombatUtil.java` | 索敌、路径判定、盾/右键、攻击、护甲报告 |
| `.../PathReachability.java` | <16 步可行走 BFS |
| `.../KillTargetCloseJob.java` | 近战、盾、10s noHitTicks |
| `.../KillTargetFarJob.java` | 远程、弓/弩、举盾间隔、蓄力/release |
| `.../EscapeJob.java` | 逃跑 |
| `.../SelfProtect.java` | 自卫、苦力怕盾/逃 |
| `.../CombatJob.java` | 旧单次攻击（保留兼容） |
| `.../EventPush.java` | 伤害/坠落/工具耐久/战斗事件 |
| `.../EnvInfo.java` | 环境/声音/季节/Pendulum 探测 |
| `.../CreateAdapter.java` | Create ScrollValueBehaviour 直连适配 |
| `.../GuiTools.java` | 通用 GUI 协议 |

## 插件

| 路径 | 作用 |
|---|---|
| `zff-mc-plugin/index.js` | DSH 插件、续跑、zff_* 工具、下线、提示词 |
| `zff-mc-plugin/client.js` | 客户端 UI/团队面板 |
| `zff-mc-plugin/defaults/` | 默认提示词/配置 |
| `zff-mc-plugin/presets/` | 预设 |

## 自动化脚本

| 路径 | 作用 |
|---|---|
| `scripts/build-release.mjs` | 构建 release |
| `scripts/tool.mjs` | 通过 daemon 调 MCP 工具 |
| `scripts/start-mcp.mjs` / `stop-mcp` | 启停 |
| `test/run-all.mjs` | 测试套件 |
| `evidence/` | 证据 JSON |

## 版本控制建议

- 每次 release 更新 `release/SHA256SUMS.txt`
- 不把测试存档、日志、screenshots 提交进 release
- 新工具必须同时更新 `docs/MCP-TOOLS.md` 与 `docs/ph9/04-tool-contracts.md`
