# Ph5 Agent 架构（SR / CR / AR / ESRS / ESRA）与 Bridge 模组

来源：`docs/architechture.md`（用户新需求）。本文件把它落成可实现的组件、协议与验收。
**开发期 CR 不启用**（由用户分发测试任务）；发布时再开。

## 1. 角色与 DAG

```
            ┌────────────┐   chat   ┌────────────┐
            │    SR      │◀────────▶│    CR      │  (dev: disabled)
            │ Scheduler  │          │  Chatter   │
            └────┬───────┘          └────────────┘
         spawn/  │  ▲ events
        cancel   │  │
   ┌─────────────▼──┴──────┐   ┌──────────────────┐
   │         AR            │◀──│ ESRA (sensor)     │
   │  Affecter (actions)   │   │ stuck/watchdog    │
   └───────────┬───────────┘   └──────────────────┘
               │ tools
         ┌─────▼──────┐
         │  MCP tools │  (mine_block, path_to, ...)
         └────────────┘
   ESRS (sensor) ──► SR : player state + environment summary
```

规则（硬性）：
- 父子 Agent **不得互相等待**，唯一例外是 SR 等 CR 的回复。
- SR 空闲时向 CR 发任务请求；CR 汇总后回复 SR（dev 期此路关闭）。
- AR 的执行阻塞由 ESRA 上报（3 s 看门狗），AR 自行决策或上报 SR。
- SR 持有 TodoList；SR/CR 各自持有 Memory。

## 2. 组件落点

| 角色/能力 | 实现位置 | 说明 |
|---|---|---|
| SR | `mcp-server/src/agent/sr.mjs` | 调度器：读写 TodoList、派发任务给 AR、消费 ESRS 事件、处理中断 |
| CR | `mcp-server/src/agent/cr.mjs` | 聊天代理：解析玩家消息，区分闲聊/任务；dev 默认 disabled |
| AR | `mcp-server/src/agent/ar.mjs` | 动作执行器：调用 MCP 工具跑一个任务，订阅 ESRA 事件 |
| ESRS | `mcp-server/src/agent/sensors.mjs` (Esrs) | 轮询 daemon：血量/饱食/气泡/坐标变化、受伤原因、附近矿石 |
| ESRA | `mcp-server/src/agent/sensors.mjs` (Esra) | 任务级看门狗：搭高看 y、走路看 xz、下降看 z 异常；3 s 无变化 → 事件 |
| Bus | `mcp-server/src/agent/bus.mjs` | 进程内消息总线（角色间事件/请求/回复，带 seq） |
| TodoList | `mcp-server/src/agent/todolist.mjs` | 任务清单：id/标题/状态/优先级/依赖；持久化 |
| Memory | `mcp-server/src/agent/memory.mjs` | 键值记忆：坐标、结论、角色上下文；服务端可读文件 |
| Bridge Mod | `mod/src/main/java/dev/mcp/bridge/` | 客户端+服务端同一个 mod：看门狗、`/zff stop`、`zff_bridge.config`、服务端 Memory/TodoList |

## 3. 协议（进程内 → 落盘 → mod 侧）

### 3.1 事件（Bus）
```json
{ "seq": 12, "ts": 1793..., "from": "esrs", "to": "sr", "type": "player_state|damage|air_low|ore_near|stuck|task_done|task_failed|chat|task_request",
  "data": { ... } }
```

### 3.2 TodoList 文件（`runtime/agent/todolist.json`）
```json
{ "id": "t-7", "rev": 12, "tasks": [
  { "id": "t-7-1", "title": "挖 32 圆石", "status": "running|pending|done|failed|cancelled",
    "priority": 2, "createdAt": 0, "updatedAt": 0, "jobId": "j-…", "note": "" } ] }
```
- 服务端模式：同一份结构存到 mod 侧（`zff_bridge` 的 server storage），带 `serverId`/`actorId` 标识，
  两个 AI 共享同一份。

### 3.3 Memory 文件（`runtime/agent/memory.json` 或 `<workspace>/.mcp-agent/*`）
```json
{ "key": "stronghold.pos", "value": "0,0", "tags": ["testA"], "updatedAt": 0 }
```
- 键：点分命名；值：字符串/JSON；`role` 字段区分 sr/cr。

### 3.4 看门狗（3 s）
- 搭高：`y` 3 s 变化 < 0.5 → `stuck:tower`
- 走/跑/寻路：`xz` 3 s 变化 < 1.0 → `stuck:walk`
- 向下挖：`y` 变化方向异常（应该下降却上升）或 `z` 抖动 > 2 → `stuck:dig`
- 报告：`{type:"stuck", data:{mode, pos, delta, ticks, suggestion}}` → AR（并抄送 SR）

## 4. Bridge Mod 侧（ZacFatFish Bridge Mod）

1. 一个 mod，同时挂客户端与服务端（NeoForge `@Mod` 双端加载；服务端只跑通用逻辑）。
2. **看门狗全部由 mod 提供**（Java 侧 tick 级，比 Node 轮询更准）：`zff_bridge.config`
   - `watchdogMs = 3000`
   - `towerDeltaY = 0.5`
   - `walkDeltaXZ = 1.0`
   - `reportIntervalMs = 500`
3. **Memory / TodoList 存服务端**：`ServerLevel` 持久化（`SavedData`），带 `actorId`/`serverId`；
   客户端通过 bridge 的 RPC 读写（`/zff memory get|set`、`/zff todo ...` 或内部 RPC）。
4. **`/zff stop`**：管理员指令，取消所有 AI 任务、停 Baritone、写事件；MCP 客户端 SR 收到 `stop` 事件。
5. **无需额外开放服务端端口**：客户端 mod 与服务端 mod 走 Minecraft 自己的通道（自定义 payload 或
   客户端本地 HTTP + 服务端指令下发），服务端不监听外部端口。
6. **Baritone 为前置**（已满足）。
7. 兼容无其他模组的 NeoForge 存档；有模组时用可选依赖探测（`ModList.isLoaded`）。

## 5. 实施顺序（本轮）

1. `bus.mjs` + `todolist.mjs` + `memory.mjs`（含磁盘持久化与 `serverId/actorId` 字段）。
2. `sensors.mjs`（ESRS/ESRA，3 s 看门狗，真实调用 daemon）。
3. `sr.mjs`（最小可用：接收事件 → 生成/推进 TodoList → 交给 AR）/ `ar.mjs`（执行一个任务并汇报）。
4. `cr.mjs`（默认 disabled，`ZFF_CR=1` 才启动；dev 不启用）。
5. `run.mjs` CLI：`node mcp-server/src/agent/run.mjs sensors` 可独立跑传感器并打印事件（无需 LLM，便于测试）。
6. Java 侧：`zff_bridge.config` 读取 + `/zff stop` 指令 + 看门狗骨架 + 服务端 SavedData 存 Memory/TodoList。
7. 验收（用户分发）：见 `PLAN.md` 的 P5-2/P5-3 改写项。

## 6. 与旧需求的关系

`REQUIREMENT.md` 的 A-F 仍然有效，本文替换/新增：
- 感知类由 ESRS/ESRA 统一上报（D3/D4/D5 的实现载体）；
- 交互类由 AR 调用现有 MCP 工具（C1-C5）；
- 看门狗从 Node 轮询迁移到 mod 的 Java tick（F1 强化）；
- Memory/TodoList 从 workspace 文件升级为服务端存储（A3 强化）。
