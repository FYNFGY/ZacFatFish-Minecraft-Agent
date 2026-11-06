# Ph3 — Architecture & Implementation Guide (English)

This document describes how to build the features in `FEATURES.md` on top of the existing
`mcpbridge` NeoForge mod and the Node MCP server. Read `ISSUES.md` for the why.

## 1. Components

```
Minecraft client (NeoForge 1.21.1)
 ├─ mcpbridge mod (Java)                  ← game-side tools & jobs
 │   ├─ Tools / Actions                  ← dispatch + block/entity actions
 │   ├─ Jobs / TickScheduler             ← per-tick job engine (must be cancellable)
 │   ├─ MiningExecutor (new)             ← one state machine for all mining
 │   ├─ Movement: WalkJob / TowerJob     ← existing, extend for swim/bridge
 │   ├─ PathPlanner (new)                ← bridge_route + dig_tunnel plans
 │   ├─ PlayerWatch (new)                ← HUD + inventory deltas, coalesced
 │   ├─ FluidState (new)                 ← water/lava source/flow detection
 │   ├─ EventJournal                     ← events with seq + priority
 │   └─ InstanceRegistry                  ← registration file + heartbeat
 ├─ MCP server (Node)                    ← tool surface, instance pinning, supervision
 └─ Supervisor script (new)              ← crash watchdog, restart, logs
```

## 2. Job engine changes

### 2.1 Cancellation

```java
public interface TickTask {
    void start(Job job);
    void tick(Job job);
    default void abort(Job job) {}       // NEW: called by stop_all / cancel
}
```

- Add `Job.cancel(String reason)`: sets state `CANCELLED`, reason, calls `task.abort`, updates time.
- `Jobs.cancelAll(String reason)` + `BridgeRuntime.stopAll(reason)`:
  - cancel every non-terminal job (path, mine, batch, craft, tower, walk, combat…),
  - `BaritoneControl.cancel()` and clear `TickScheduler` tasks,
  - reset the mining executor state (send `stopDestroyBlock`-equivalent by clearing target),
  - close screens opened by the bridge,
  - push an `EventJournal` event `type:"stop"`.
- `stop_all` tool returns per-tool counts + elapsed ms.
- Every long loop in job code checks `job.isCancelled()` each tick; scripts call `stop_all` (or
  `job_cancel`) instead of sleeping.

### 2.2 Error codes

Add to `Errors`: `OUT_OF_REACH`, `WRONG_TOOL`, `TOOL_BROKEN`, `NO_PROGRESS`, `UNBREAKABLE`,
`PROTECTED`, `WATER_RISK`, `SERVER_REJECTED`, `BLOCKED`, `BUDGET_EXCEEDED`.

## 3. MiningExecutor (new)

One class used by `mine_block`, `batch` (mine ops), `dig_tunnel`, `bridge_route` and any other
digging:

```
state per target: IDLE → STARTED → DIGGING → VERIFY → DONE|FAILED
```

Rules:
- compute reach, pick hotbar tool (`Inventory.getBestTool`-like scan by destroy speed), check
  durability;
- `startDestroyBlock` once (store target pos + face), then `continueDestroyBlock` each tick;
- per-block timeout = `max(40, hardness * 60)` ticks (cap 8 s);
- verify `level.getBlockState(pos) != original` (air/replacement) before `DONE`;
- on failure return `{pos, result, reason}` and do **not** retry automatically;
- batch ops run this sequentially; `continueOnError` default false;
- tool break → `TOOL_BROKEN`, abort the whole job.

## 4. PathPlanner (new)

### 4.1 `dig_tunnel`

Inputs: target (`to` or `dy`), mode (`stair`/`shaft`), width (1–2), `maxBlocks`.

Plan:
1. Compute the sequence of block positions for the geometry.
2. For every position, probe `FluidState` for water/lava in the block and its two neighbours; if any,
   fail `WATER_RISK` with the offending coordinate.
3. Compute the exit plan (stair back up, or the tower budget with the player's current blocks).
4. Execute block-by-block with MiningExecutor; walk between steps (short WalkJob bursts).
5. Stop at the target; return `{reached, blocks, exitPlan}`.

### 4.2 `bridge_route`

Inputs: waypoints (1–64), material (`auto` or id), `maxBlocks`, `allowDig`.

Planning phase (no world changes):
1. Segment the waypoints. For each segment compute the required moves:
   - gain 0: walk / step;
   - gain 1: jump or one stair block;
   - gain 2–4: tower (place under feet while jumping) — needs `ceil(gain)` blocks;
   - gap/ledge: side-bridge — one block per horizontal step, placed at feet level in front, then step
     onto it;
   - blocked and `allowDig`: mine (MiningExecutor) — counted in blocks but not material.
2. Estimate `blocksNeeded` per material by scanning the inventory (`dirt`, `cobblestone`, `planks`,
   `stone_bricks`, then any cheap block).
3. Refuse if `blocksNeeded > available` and `allowPartial=false`; return the plan for the caller.

Execution phase:
1. Walk to the segment start (short `path_to` with 10 s cap or `walk` bursts).
2. Tower/side-bridge with per-block placement verification (`place_block` v2: read back the block
   after 2–3 ticks, fail `SERVER_REJECTED` otherwise).
3. After each waypoint, verify the player's block position; if arrived, **stop placing**.
4. Track and report `blocksUsed` / `blocksRemaining`.
5. Abort on `stop_all`, on tool/material shortage, or if a placement fails twice.

Material priority: `dirt` > `cobblestone` > `planks` > `stone_bricks` > `andesite/granite/diorite` >
any other cheap full block. Never use ores, tools, food, or quest items.

### 4.3 `climb_out`

Thin wrapper: choose `bridge_route` when the exit is open to the sky, `dig_tunnel` when trapped
underground with no sky access. It must know the **surface Y** (from `scan_surface` heightMap or the
last known surface) and stop exactly there.

## 5. PlayerWatch (HUD + inventory)

Runs every client tick:
- snapshot: hp/max/absorption, food/saturation, xp level/progress, air, armour durability, selected
  slot;
- inventory ledger: compare the 41 inventory slots + armour/offhand each tick; produce
  `{slot, item, delta}`;
- coalescing: accumulate in a buffer, flush at most every 250 ms (or immediately for critical
  changes: hp < 8, air < 150, food < 3, death);
- noise filter: ignore deltas that match the agent's own placement/mining within the last 1 s;
- push to `EventJournal` with `type:"hud"` / `type:"inv"` and priority.

## 6. FluidState

Helpers:
- `isWater(pos)`, `isLava(pos)`, `isSource(pos)` (block state `FluidState.isSource()`),
  `isWaterlogged(pos)`, `fluidAt(feet/head)`.
- `scanLine(start, dir, n)` → first fluid in the next n blocks.
- Used by `dig_tunnel`, `bridge_route`, `GameState`, and the auto-escape policy.

## 7. Container verification

- `Containers.open`: after the screen opens, confirm `containerId` + `slotCount` + a sentinel slot
  identity read twice 1 tick apart; else `SERVER_REJECTED`.
- `Containers.move`: read both slots, click, wait 4–8 ticks, read again; `verified` only if the
  expected change happened.
- `Containers.resync`: close and reopen the player inventory / force a client resync; if still
  divergent, call the supervisor to relog.
- `inspect_container`: block entities (`ChestBlockEntity`, `FurnaceBlockEntity`, `BarrelBlockEntity`,
  `HopperBlockEntity`, `ShulkerBoxBlockEntity`, `SignBlockEntity`) and entities (`ItemFrame`,
  `ArmorStand`).

## 8. MCP server changes (Node)

- Register: `stop_all`, `bridge_route`, `dig_tunnel`, `climb_out`, `poll_events` (non-blocking),
  `inspect_container`, `resync`, `set_filters` (subagent thresholds).
- `get_state` default compact (≤ 2 KB); `compact:false` for full.
- `jobs_list`: include `cancelledReason`, `abortedBy`, `metrics`.
- Instance pinning: keep the current pin logic; add a response header `targetPlayer` to every tool
  result; refuse mutating tools with >1 live instance and no pin.
- Supervisor: `watchdog.mjs` — checks the client process + bridge heartbeat every 2 s; on loss
  captures logs, restarts `DeepSeekLaunch.bat`, waits for `inWorld`, reports.

## 9. Subagent protocol

- The subagent only calls `poll_events` (short, non-blocking) or the CLI equivalent, and `stop_all`
  on stop messages.
- It filters by priority using `set_filters` thresholds; relays only:
  - player chat (always),
  - `stop`/danger events (critical),
  - health/air critical, food low,
  - join/leave/death/crash.
- Message format: `【急·停止】…`, `【聊天】…`, `【受伤】…`, `【状态】…`; no duplicates within 1 s.

## 10. File layout (proposed)

```
mod/src/main/java/dev/mcp/bridge/
  MiningExecutor.java      (new)
  PathPlanner.java         (new)
  BridgeRouteJob.java      (new)
  DigTunnelJob.java        (new)
  PlayerWatch.java         (new)
  FluidState.java          (new)
  InspectContainer.java    (new)
  ... existing files modified ...
mcp-server/src/
  server.js                (new tools, compact state, headers)
  watchdog.mjs             (new supervisor)
scripts/
  bridge-test.mjs          (acceptance scenarios)
docs/Ph3/                  (this folder)
```

## 11. Performance and latency budget

- `stop_all` ≤ 50 ms server-side + 1 tick.
- `get_state` compact ≤ 2 KB, ≤ 5 ms RPC.
- HUD/inv flush ≤ 250 ms.
- Mining per block: no fixed sleep; tick-driven.
- Bridge route: plan in ≤ 50 ms (no world reads beyond a 5×5×5 local scan).
- Agent: no blocking calls; each turn ≤ 3 tool calls typical.

## 12. Risks

- Minecraft's client is authoritative for some container state; server verification is best-effort.
  When in doubt, trust the human and resync.
- Water physics may override velocity hacks; always use the jump input for swimming.
- Modded blocks may have unusual hardness/tools; the executor must fail with a reason, not loop.
- The c2me crash is environmental; the watchdog must handle it, not fix it.
