# Ph5 第二轮报告（T1-T7 / T9，2026-10-31 晚）

承接 `PHASE5-PROGRESS.md` 的第一轮（Agent 骨架 + Bridge 第一批指令/配置）。本轮按用户给的优先级
做 **T1-T5（架构）**，然后 T6 / T7-D5 / T9；T8（水下原语）优先级最低，本轮没有实机验收。

## 1. 结果总览

| 任务 | 结果 | 证据 |
|---|---|---|
| **T1** 一个 mod 同时跑客户端 + 服务端 | ✅ 真专用服务端启动并初始化，`/zff status` 有服务端响应；字节码闸门 0 处客户端引用 | `ph5-server-side.json`、`ph5-server-sides.json`、`runtime/server-ph5.log` |
| **T2** Memory/TodoList 搬到服务端 SavedData | ✅ `<world>/data/zff_agent.dat`，跨 3 次重启持久；10 个 `zff_*` 工具走 mod 通道；单机客户端重启后 marker/todo 仍在 | `ph5-saveddata.json`、`ph5-saveddata-client.json` |
| **T3** 看门狗搬进 mod（Java tick） | ✅ 撞墙 3.27 s 收到 `stuck{mode:walk,source:mod}`，job metrics 记数；Node 端不再重复上报 | `ph5-watchdog-mod.json`、`ph5-agent-integration.json` |
| **T4** 不额外开服务端端口 | ✅ 专用服务端只监听 25565；客户端↔服务端走 `mcpbridge:*` payload | `ph5-no-server-port.json` |
| **T5** `/zff stop` 变成事件 | ✅ job 5 ms 内 cancelled + 11 ms 内 `zff_stop` 事件 + 服务端 todo 被取消 | `ph5-zff-stop-event.json` |
| **T6** CR 联调（开发期仍关闭） | ✅ 闲聊/任务分类、SR 建任务并派发、空闲回复不阻塞 | `ph5-cr.json` |
| **T7** D5 时间/天气感知 | ✅ `world.time`/`world.weather`，day/midnight/rain/clear 全部验证 | `ph5-time-weather.json` |
| **T9** 遗留项 | ⚠️ 部分：`collect_drops` 3D 兜底修复并验证；`place_boat` 轮询在成功放置时返回真实实体；`walk_to` 2D 限制写进文档 | `ph5-t9-collect3d.json`、`ph5-t9-leftovers.json` |
| **T8** 水下原语 | ⏳ 未做（`dig_sealed_shaft` 仍未实机验收；E2-E4 未实现） | — |

工具数 74 -> **84**（新增 `zff_status` + `zff_memory_*` 4 个 + `zff_todo_*` 5 个），`docs/Ph5/TOOLS.md` 已重新生成。
Java 类 83 -> 97（新增 7 个类，删除 1 个旧的客户端 `ZffCommand`）。

## 2. 架构（本轮最重要的变化）

```
                    ┌──────────────────────────── client JVM ────────────────────────────┐
  MCP server :57610 │  loopback HTTP (随机端口+token)                                     │
  daemon     :57611 │        │                                                           │
        └──────────►│  dev.mcp.bridge.client.*  (ZffClient / ZffRemote / ZffWatchdog)    │
                    │        │                          ▲                                │
                    │        │ PacketDistributor        │ IPayloadHandler                │
                    │        ▼                          │                                │
                    │  mcpbridge:rpc_request ──► [ Minecraft channel ] ──► rpc_response    │
                    └──────────────────────────────────────┬─────────────────────────────┘
                                                           │  (singleplayer = 同一 JVM 的逻辑服务端)
                    ┌──────────────── server side ─────────▼─────────────────────────────┐
                    │  ZffServer (指令 / payload / tick 采样)                            │
                    │  ZffSavedData  <world>/data/zff_agent.dat  (memory + todo)         │
                    │  ZffConfig (config/zff_bridge.config)                             │
                    └───────────────────────────────────────────────────────────────────┘
```

- 双端共用同一个 jar：`@Mod` 无 dist 限制；只有 `McpBridge -> ZffClient` 一条编译期引用，被
  `FMLEnvironment.dist == Dist.CLIENT` 守卫；其余跨端交互走 `ZffHooks`（服务端为 null -> no-op）。
- 服务端**不监听端口**：所有客户端↔服务端数据都走 Minecraft 自己的 play 通道。
- 看门狗只有一个来源：mod（`watchdogInMod=true`），Node 的 `sensors.mjs` 变成事件转发器。

## 3. 本轮发现并修掉的真问题

1. **`/save-all` 在本整合包里不存在**：`save_world` 原来只把命令发出去就返回 ok，
   客户端被 kill 时 SavedData/区块并没有落盘，重启会回档（实测丢了 marker 和 1 个 rev）。
   现在单机时在服务端线程调 `MinecraftServer.saveEverything(true,true,false)` 并等它写完
   （`method=integrated_server.saveEverything, flushed=true`），文件 mtime 在返回前就更新。
   **这是 F1「重启不回档」的真正修复。**
2. **`Level.isDay()` 在本客户端不可信**：它读 `skyDarken`，这里恒为 0，午夜也报白天。
   改成按 `dayTime % 24000` 计算，并把 `skyDarken` 一起返回便于排查。
3. **`collect_drops` 工具从不等 job 结束**：把"刚启动的 job"当结果读 `metrics.collected`（永远 0），
   于是每次都误走 3D 兜底并返回另一个刚启动的 job；早退条件 `state==done` 又会让真正需要兜底的情况跳过。
   已改成 `waitJob()` 轮询到终态 + 只在 `collected>0` 时早退。
4. **`CollectTask` 计数漏更新**：物品被捡完的那一帧直接走 `items.isEmpty()` 分支，metrics 仍是旧值
   （结果字段却是对的），导致"物品已进背包但 metrics=0"。已修。
5. **`place_boat` 的误导性 note**：轮询补齐实体后仍带着 "no boat entity appeared"，已替换。

## 4. 环境与命令（本轮新增）

```powershell
# 专用服务端（一次性安装已完成于 server\，运行靠这个）
node scripts/server-run.mjs                      # 后台跑 server\run.bat 的等价 JVM，日志 runtime/server.out.log
Set-Content runtime\server.stdin 'stop'          # 发控制台命令（stop/zff status/...）
node scripts/collect-server-evidence.mjs         # 采集 T1/T2/T4 证据
node scripts/check-sides.mjs                     # 服务端侧字节码闸门（0 客户端引用）
# 验收脚本
node test/ph5-watchdog.mjs          node test/ph5-zff-stop.mjs
node test/ph5-saveddata.mjs before|after
node test/ph5-time-weather.mjs      node test/ph5-agent-integration.mjs
node test/ph5-t9b-collect.mjs       node test/cr-chat.mjs
```

## 5. 仍未完成 / 下一步

1. **T8 水下原语**：`dig_sealed_shaft` 需要实机验收（本轮没做）；E2 上浮换气、E3 排水、E4 水下隧道未实现；
   Test A（强堡在 (0,0) 海底）仍然维持"不可行"，直到 T8 提供能力。
2. **C4 喂食动物**未专门验收；**C6 Carry On**、**C7 机械查询**未实现。
3. **T9.1**：`place_boat` 轮询在"放置成功"时验证过（返回 entityId=160、entity_query 能找到）；
   本轮后来在自建平台上放置失败（`result=FAIL`，玩家与水面同高、船 AABB 可能和玩家/平台冲突），
   属于放置前置条件问题，不是轮询逻辑问题。
4. 专用服务端用 `online-mode=false` + 平原地形，仅用于架构验收，不是可玩服。
