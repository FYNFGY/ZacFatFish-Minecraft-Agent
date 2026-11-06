# T3 - 看门狗搬进 mod（Java tick，3 s）

## 做了什么

1. 新增 `client/ZffWatchdog.java`：`ClientTickEvent.Post` 里每 `reportIntervalMs`（默认 500 ms）采样一次玩家坐标，
   遍历 `Jobs.active()` 的每个运行中 job（`Jobs.active()` 为本轮新增），按工具分模式判定：
   `tower` 看 |dy| < `towerDeltaY`、`walk/path` 看 |dxz| < `walkDeltaXZ`、`dig/mine` 看 dy 与 dxz 是否都不动；
   连续 `watchdogMs`（默认 3000 ms）没动就发事件。移动恢复后自动重置（长任务可多次上报）。
2. 事件写进既有 `EventJournal`：
   `{type:"stuck", taskId=jobId, tool, mode, x/y/z, dxz, dy, windowMs, count, wd, triggerMs, sampleMs, source:"mod", suggestion}`，
   MCP 侧用 `wait_events` 直接读；同时 `job.metric("stuck"/"stuckMode"/"stuckWindowMs")` 累加。
3. **采样偏差补偿**：采样器每 `reportIntervalMs` 才可能看到一次位移，所以观测到的静止窗口天然是
   `watchdogMs .. watchdogMs+interval`。触发阈值取 `watchdogMs - reportIntervalMs/2`（=2750 ms），
   保证事件在 3 s + 一个采样周期的预算内到达；事件里同时给出 `triggerMs`/`sampleMs`/`windowMs` 便于核对。
4. 单一来源：配置 `zff_bridge.config` 增加 `watchdogInMod=true`（+ `actorId`），`bridge_info` 暴露该字段。
   `sensors.mjs` 启动时问一次 `bridge_info`，`watchdogInMod=true` 时**关闭自己的 Node 看门狗**，改为轮询
   `wait_events {since}` 把 mod 的 `stuck`/`zff_stop` 事件转发到总线（并把 jobId 映射回 SR 的 taskId）。

## 验收（`test/ph5-watchdog.mjs`）

- 用 `/setblock` 在玩家正东侧放 2 格黑曜石墙，再跑 `walk {yaw:270,ticks:300}` 撞墙（jump=false，翻不过去）。
- 最近一次（最终 jar）：`stuck{mode:"walk", windowMs:3108, triggerMs:2750, sampleMs:500, source:"mod"}`，
  从 job 启动到 MCP 读到事件的 **latency = 3474 ms（≤3500）**，其中约 366 ms 是 MCP 侧轮询/往返开销；
  之前的运行在 3271-3560 ms 之间（采样相位不同）。
- `job_status.metrics` = `{stuck:1, stuckMode:"walk", stuckWindowMs:3108}`；随后 `job_cancel` -> `cancelled`；
- 玩家位移 0-0.2 格（确实没绕过去）。

## 证据

`evidence/ph5-watchdog-mod.json`（含每一步时间戳、事件原文、metrics、判定）、
`evidence/ph5-agent-integration.json`（Node 侧让位给 mod 看门狗：`watchdog_source=mod`、无 `source=node` 的 stuck）。
