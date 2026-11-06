# Round 3 — Implementation Report (2026-10-30)

Autonomous development session (no human in-game). Goal: implement the Ph3 P0 features, verify them in
the live client, then measure and improve responsiveness.

## 1. What was built

### Bridge (Java, `mod/src/main/java/dev/mcp/bridge/`)

| File | Purpose |
|---|---|
| `StopAll.java` | Global stop: cancels every job, Baritone, scheduler tasks, closes screens, pushes a `stop` event. |
| `BlockMiner.java` | One-block mining state machine: start once, continue per tick, verify the change, tool/durability checks, reason codes (`out_of_reach`, `tool_broken`, `no_progress`, `unbreakable`). |
| `MineExecutor.java` | Mines a list of blocks with per-block verification and fail-fast. |
| `BatchJob.java` (rewritten mine op) | Uses `BlockMiner` per op, records per-op reasons, no more per-tick `startDestroyBlock` restarts. |
| `DigJob.java` | Safe staircase / 1×2 shaft descent with fluid probes and a solid-support check. 1×1 straight down is refused. |
| `BranchMineJob.java` | Fishbone mining: 1×2 corridor, branches every N blocks, walk back after each branch, torch pass, fluid probes. |
| `EntityActions.java` | `break_entity` (boats, minecarts, frames, paintings, armour stands) and `collect_drops`. |
| `ItemActions.java` | `throw_item` (eye of ender), `track_entity`, `place_boat`, `boat_sail`. |
| `LightTools.java` | `light_map` (dark-cell scan) and `torch_at` (place a torch on a solid neighbour). |
| `InventoryActions.java` | `inventory_swap` (menu-slot swap into the hotbar) and `hotbar`. |
| `Containers.java` | Added `resync`; open/move already verified with a read-back. |
| `Perception.java` / `GameState.java` | `block_query` and the player now expose block/sky light and fluid details (source/flowing/waterlogged). |
| `DigUtil.java` | Shared fluid/solid/movement helpers. |

### MCP server (`mcp-server/src/server.js`)

New tools: `stop_all`, `break_entity`, `collect_drops`, `dig_shaft`, `branch_mine`, `throw_item`,
`track_entity`, `place_boat`, `boat_sail`, `light_map`, `torch_at`, `triangulate` (pure math),
`inventory_swap`, `hotbar`, `resync`. `get_state` is now compact by default (≈3.5 KB, ~5 ms).
Tool count: **57**.

### Tooling

- `mcp-server/src/mc-daemon.mjs`: a persistent MCP session with a local HTTP API (`/call`, `/batch`,
  `/health`) — calls take **5–12 ms** instead of ~400 ms for a fresh CLI session.
- `mcp-server/src/probe-mine.mjs`, `probe-continuity.mjs`: reproducible probes.
- `scripts/mc.ps1`: thin PowerShell wrapper for the daemon.

## 2. Measured results (evidence in `evidence/`)

| Measurement | Result | Evidence |
|---|---|---|
| `get_state` via daemon | median **5 ms** (samples 4–9 ms), payload 3.5 KB | `round3-continuity.json` |
| `stop_all` (idle) | 5–22 ms, 0 jobs | `round3-smoke.json` |
| `stop_all` under load | 10–13 ms, walk/branch job `cancelled` with reason, player does not move after the stop (movedAfterStop=0) | `round3-smoke.json`, `round3-tests2.json` |
| `mine_block` single | 6 blocks of dirt: 1077–1171 ms each, 20 ticks/block | `round3-continuity.json` |
| `mine_block` batched (6 positions) | **6006 ms total** vs 6665 ms one-by-one (−10%) | `round3-continuity.json` |
| Failure taxonomy | `out_of_reach` at 10 blocks; `unbreakable` on bedrock; `blocked` when the step support is missing | `probe-mine.mjs` output |
| `branch_mine` | 64 blocks, 2 branches, 48 steps, **81 s** (~1.26 s/block) | `round3-tests2.json` |
| `light_map` | 13 dark cells at the cave village, min block light 0, `safe:false` | `round3-tests2.json` |
| `container_open` / `move` / `close` | open 52 ms (ChestMenu, 63 slots), move `verified:true` moved 57, close ok | `round3-tests3.json` |
| `inventory_swap` | pickaxe → hotbar 0, chest → hotbar 1 (server-synced) | `round3-tests3.json` |
| `place_block` | fixed support-face bug; chest placed (517 ms) | `round3-tests3.json` |

## 3. Bugs found and fixed during the round

1. **`place_block` support face inverted** — the auto path clicked the opposite side of the support,
   so every placement failed with "support block is not solid". Fixed (support = target + neighbour,
   click the neighbour's face toward the target).
2. **`resync` slot indices** — now returns `menuSlot` (InventoryMenu index) so `inventory_swap` works.
3. **`collect_drops` never finished** — it waited for an empty radius forever and counted wrong. Now
   it tracks progress, gives up with `reason:"stuck"` after 80 ticks without approach, and reports
   `initial - remaining` as the collected count.
4. **Staircase clearance** — a walked staircase needs 3 blocks of clearance at the destination
   (Y−1 hole, Y, Y+1 head while crossing the lip); mining only 2 blocked the player. Fixed, plus a
   small hop during the walk step.
5. **Dig at the world bottom** — correctly fails `blocked` when the step support would be below Y=−64.

## 4. Not yet live-tested (compiled, needs specific setup)

- `break_entity`: the nearby item frames are on an upper village level and `path_to` could not reach
  them in this world. Needs a reachable frame/armour stand.
- `boat_sail`, `place_boat`: need a water body and a boat item.
- `throw_item`, `track_entity`, `triangulate`: need an eye of ender (not in this world).
- `torch_at`: the player has no torches and there is no coal within 32 blocks.
- `dig_shaft` at depth: the test location is at Y=−63 (world bottom −64), so only the guard was
  exercised.
- `watchdog` (client crash recovery), `inspect_container`, door-preferring pathing: not implemented.

## 5. Responsiveness notes

- The daemon removes almost all of the agent-side latency (5–12 ms/call).
- Mining is at vanilla speed (20 ticks for dirt with a pickaxe, ~12 for stone); batching is the only
  real lever (one job for N blocks).
- Polling granularity (60–150 ms) dominates short actions: a 1 s mine shows as ~1.09 s.
- Recommendation for the agent loop: batch independent actions, poll `job_status` at 60 ms, and use
  `stop_all` first on any stop request.

## 6. Addendum — crash watchdog and the cave-aware branch mine (same session)

### Watchdog (`mcp-server/src/watchdog.mjs`)

- Watches the instance registry heartbeat (fresh ≤ 45 s) and `java.exe`; on a loss it archives the
  last 160 log lines to `runtime/crashes/`, then relaunches `DeepSeekLaunch.bat`.
- Restart mechanism: `cmd /c start "" /D <PCL dir> <bat>` (the earlier `spawn(bat, shell:true)` and
  `powershell Start-Process` variants silently failed from Node, so this is the tested path).
- Grace period 120 s after a restart and a budget of 2 restarts per 30 min to avoid restart storms.
- **End-to-end test**: killed the client at 11:38:53 → the watchdog detected the loss, relaunched,
  java reappeared after **51 s**, the world was back (same player position) after **108 s**.
  Evidence: `evidence/round3-watchdog.json`, `runtime/watchdog.log`.

### Branch mine: walk through caves instead of refusing

With the old check, a branch mine started inside a cave/room failed immediately with
`blocked: cave ahead` (observed in all four directions). Now the job walks through an open cell when
it has a solid floor, up to 8 free steps, and only fails if the open space is too large or there is no
floor. This makes `branch_mine` usable when the player starts in a cave village.

### Final regression (after the fixes)

`evidence/round3-final.json`: state median 13 ms, `stop_all` 14 ms, dirt mined in 15 ticks (856–937 ms,
verified air), `light_map` 27 dark cells with min light 0, `resync` 13 ms, and **`branch_mine` now
completes**: 18 blocks + 1 branch in 23.7 s (~1.3 s/block) after the cave-walk fix (previously it
failed immediately with `blocked: cave ahead` in all directions).


## 7. Addendum 2 — compact state deltas, endurance and the action cycle

### `get_state` deltas (F6 partial)

The MCP server compares each compact `get_state` with the previous one and attaches a `delta` object:
changed core stats (health, food, air, XP, block light, fluid at feet, used slots) plus per-item
inventory changes. Verified: placing a chest returned
`{"inventory":[{"id":"minecraft:dirt","delta":2,"now":91},{"id":"minecraft:chest","delta":-1,"now":3}]}`;
an idle call has no `delta`. This removes the need for a separate HUD-polling tool.

### Endurance (20-block target, 9 available)

`evidence/round3-endurance.json`: 9 dirt blocks in 2 batched jobs, **0 failures**, every target
verified air, avg **1074 ms/block** (20 ticks of mining + ~70 ms overhead).

### Action cycle

`evidence/round3-cycles.json`: 8 cycles of `get_state → block_query → mine_block → get_state`:
median **1138 ms** (min 1119, max 1204). With 20 ticks (1000 ms) of vanilla dirt mining, the agent
overhead is **≈140 ms per cycle** including three MCP calls and one job poll.

### Stack

- `mc-daemon` (port 57611) — persistent session; `/call`, `/batch`, `/health`.
- `watchdog` — client recovery (tested: relaunch 51 s, world back 108 s).
- `start-mcp.ps1` — MCP server (port 57610), 57 tools.
- Build: `mcpbridge-0.1.0.jar`, 193,504 bytes, SHA-256 starts `C3B5D2537D530994`.

### How to resume

1. `.\start-mcp.ps1`
2. start `mcp-server/src/mc-daemon.mjs` (working dir = project root)
3. start `mcp-server/src/watchdog.mjs`
4. `Start-Process 'D:\SoftWare\PCL 正式版 2.12.2\PCL\DeepSeekLaunch.bat'`
5. wait ~100 s for the world; if the store did not pin it, `mc_attach` to the live instance file
6. use `scripts/mc.ps1 -Tool get_state` (or the daemon `/call`) for ~5–12 ms calls

## 8. Addendum 3 — path rules: doors/gates (`path_open`)

Implemented `PathOpenJob` (`path_open` tool): Baritone pathing plus a door sweep every 8 ticks —
any closed door / fence gate / trapdoor within 4 blocks is opened (state `OPEN`), so village farms no
longer block the route. Stuck detection: 7 s without progress triggers a wider sweep, 11 s fails with
`blocked` and the number of doors opened.

**Live test**: from `(-216,-63,-22)` the job walked up 3 blocks through the cave village to
`(-224,-60,-6)` (**done**), opening **1 door** on the way (evidence: `evidence/round3-path-open.json`).
Farmland avoidance is not enforced (Baritone decides the route); the gate-stuck failure that the human
reported is fixed.

## 9. Addendum 4 — walk-step jumping and final state

- `BranchMineJob.walkStep` and `DigJob.walkForward` now hop when the player is blocked by a one-block
  lip (`horizontalCollision` + no movement for >6 ticks). This fixes `no_progress: walk step made no
  progress` in cramped village rooms. Verified: the branch mine in the cramped spot completes
  (yaw -16 -> done).
- Final tool count: **58** (added `path_open`).
- Final build: `mcpbridge-0.1.0.jar`, 197,102 bytes (68 classes).
- Evidence index: `evidence/round3-{smoke,tests2,tests3,continuity,endurance,cycles,watchdog,final,path-open,torch}.json`
  and `round3-final-screenshot.png`.

## 10. Addendum 5 — `break_entity` and `collect_drops` live (final gap closed)

`path_open` reached the upper village (`-221,-60,-5`), 7 item frames were in range, and
`break_entity` on frame id 40 finished in **2 hits**, dropping **crossbow x1 + item_frame x1**;
`collect_drops` then collected **2** items and stopped with `reason:"stuck"` once nothing was
approachable. Evidence: `evidence/round3-break-entity.json`.

Updated status: `break_entity` and `collect_drops` are now **live-tested**. Remaining untested by
environment: `boat_sail`/`throw_item` (no boat/eye/water at the test spot), deep `dig_shaft`
(world bottom), farmland avoidance (Baritone route; gates now open).
