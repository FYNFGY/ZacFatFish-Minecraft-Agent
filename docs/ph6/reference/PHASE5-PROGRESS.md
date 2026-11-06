# Ph5 - Agent architecture + Bridge mod progress（2026-10-31 晚）

按用户要求，本轮优先做 `docs/architechture.md` 的**新 Agent 架构**与**服务端模组**相关开发。
**CR（Chatter）开发期关闭**（`ZFF_CR=1` 才启用），测试任务由用户分发。

## 1. 已实现：Agent 运行时（Node，`mcp-server/src/agent/`）

| 文件 | 角色 | 说明 |
|---|---|---|
| `core.mjs` | 基础设施 | 事件总线（seq/ts/from/to/type/data + JSONL 日志 `runtime/agent/events.jsonl`）、daemon `/call` 客户端、`SERVER_ID`/`ACTOR_ID` |
| `todolist.mjs` | SR 的任务清单 | `add/update/next/running/cancelRunning`，持久化 `runtime/agent/todolist.json`（带 serverId/actorId，服务端存储预留同结构） |
| `memory.mjs` | SR/CR 的记忆 | 键值 + role + tags，持久化 `runtime/agent/memory.json` |
| `sensors.mjs` | ESRS + ESRA | 500 ms 轮询 `get_state`：`player_state/damage/air_low/air_critical/hunger_low`；任务看门狗 **3 s**（tower 看 y、walk 看 xz、dig 看 dy/xz），产出 `stuck` 事件 |
| `ar.mjs` | AR（执行者） | 跑 `{tool,args}` 或 `{steps:[...]}`，job 轮询、注册 ESRA 看门狗、可被取消、上报 `task_started/task_progress/task_done/task_failed` |
| `sr.mjs` | SR（调度者） | 持有 TodoList，空闲时向 CR 发 `task_request`（**不等待**，CR 回复异步到达），对 `air_low/air_critical/damage/stuck` 立即中断 AR 并插入安全任务 |
| `cr.mjs` | CR（聊天） | **默认 disabled**；启用后轮询 `chat_history`，按关键词区分闲聊/任务，任务转 `chat_task` 给 SR |
| `run.mjs` | CLI | `sensors [s]` / `demo [s]` / `status` / `stop` |

### 实机验证

- `node src/agent/run.mjs sensors 6`：输出 `player_state`（`hp20 food20 air300 pos…`），
  `[CR] disabled (dev)`。日志 `runtime/agent/events.jsonl`。
- `node src/agent/run.mjs demo 25`：SR 依次派发 2 个任务（`get_state`、`walk 40 tick`），
  事件流 `task_started → task_progress → task_done`，ESRS 同时报 `player_state`（移动 2.2 格/次）；
  空闲时 `task_request` → CR（disabled）回 `chat_reply`，SR 不被阻塞。
  最终 `{"dispatched":2,"failed":0,"interrupted":0}`。
- 测试任务清单会持久化，`run.mjs status` 可查。

## 2. 已实现：Bridge Mod 的 Ph5 部分（Java）

| 文件 | 作用 |
|---|---|
| `ZffConfig.java` | 读 `config/zff_bridge.config`（key=value，缺省自动生成）：`watchdogMs=3000`、`towerDeltaY=0.5`、`walkDeltaXZ=1.0`、`reportIntervalMs=500`、`crEnabled=false` |
| `ZffCommand.java` | 客户端指令 `/zff stop`、`/zff status`、`/zff reload` |
| `McpBridge.java` | 注册 `RegisterClientCommandsEvent`；启动时 `ZffConfig.load()` |

### 实机验证

- 首次启动自动生成 `…\versions\假期增援计划\config\zff_bridge.config`（368 B，默认值）。
- `/zff stop`：先起一个 400 tick 的 `walk` job，再发 `/zff stop`，job 状态变为
  `cancelled`、`reason="zff_stop_command"` → 证据 `evidence/ph5-zff-stop.json`。
- `/zff status`、`/zff reload` 已注册；客户端指令的回显走本地聊天，不会进入 `chat_history`
  （`chat_history` 只抓网络聊天包），所以用 job 状态验证 stop。

## 3. 未完成（下一步）

1. **服务端模组化**：当前 mod 是 `dist = Dist.CLIENT`；需求要求"客户端和服务端同一个模组"。
   需要拆 `common/server/client` 源集：服务端加载通用部分（看门狗、指令、SavedData），客户端加载
   bridge 的 HTTP/动作部分。
2. **Memory/TodoList 存服务端**：用 `SavedData`（`ServerLevel` 持久化）+ `actorId`，客户端经通道读写。
   当前是 `runtime/agent/*.json`（本地文件），结构与服务端版本一致，便于迁移。
3. **看门狗移入 mod**：现在 ESRA 是 Node 500 ms 轮询；需求要求"所有看门狗由 Bridge 模组提供"，
   应改成 Java tick 实现并把 `stuck` 事件推给 MCP（`wait_events`/事件日志）。
4. **无需额外端口的服务端通信**：客户端本地 HTTP + 服务端指令/自定义 payload，服务端不监听端口。
5. **`/zff stop` 通知 MCP**：现在通过 job 取消间接可见；应写一条 `stop` 事件到事件日志，供 SR 直接消费。
6. CR 启用与发布开关（`crEnabled` 已就位，逻辑待联调）。
7. 水下原语 P5-1（密封竖井）代码已在（`SealShaftJob`），仍未实机验收——优先级低于架构任务。

## 4. 第二轮已完成（2026-10-31 晚，T1-T7/T9）

上面第 1-5 条**全部完成**，详见 `ROUND2-REPORT.md` 与 `REPORT-T1..T9.md`：

- T1 一个 mod 双端：真专用服务端（`server/`）加载成功、`/zff status` 有服务端响应、
  字节码闸门 `scripts/check-sides.mjs` 0 处客户端引用；
- T2 Memory/TodoList -> `ZffSavedData`（`<world>/data/zff_agent.dat`），10 个 `zff_*` MCP 工具，跨重启持久；
- T3 看门狗搬进 Java（`client/ZffWatchdog`），Node 端改为事件转发；
- T4 服务端只监听 25565，客户端↔服务端走 `mcpbridge:*` payload；
- T5 `/zff stop` 发布 `zff_stop` 事件，SR 直接消费；
- T6 CR 分类/派发/空闲回复联调（开发期仍 `crEnabled=false`）；T7 D5 时间/天气感知；
- T9 `collect_drops` 的 3D 兜底与计数 bug 修复、`place_boat` 轮询 note 修正。
- 顺带修了 `save_world`：本整合包没有 `/save-all`，改成单机时调用
  `MinecraftServer.saveEverything(true,true,false)` 并等落盘（重启不再回档）。
- 第 7 条（水下原语）仍未实机验收，E2-E4 未实现。
