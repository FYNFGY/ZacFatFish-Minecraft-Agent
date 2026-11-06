# Round 2 Session — Issue Report (English)

Session: 2026-10-29 23:19 → 2026-10-30 00:15 (local time)
World: `mcp-test-world` — survival, LAN (hosted by the agent's client), **cheats disabled**
Agent account: `DeepSeek` (offline); human player: `FYNyesterday`
Bridge: `mcpbridge-0.1.0.jar` 143,489 bytes, 42 MCP tools

This report lists every problem observed during the session. Each entry has the symptom, the
human's own words where available, evidence, root cause, impact, the required fix, and the
acceptance test that must pass next round.

## Summary

| ID | Priority | Issue | Short symptom |
|---|---|---|---|
| P0 | High | Wrong-instance control with two clients | The agent moved the human's character and looted a chest into their inventory |
| P1 | Critical | "Stop" is not immediate | User said "别挖了" three times; agent kept pathing/mining |
| P2 | Critical | Repeated mining of an unbreakable block | Cracks appear but the block never breaks |
| P3 | High | Tool break does not abort the task | Pickaxe broke mid-task, job kept going |
| P4 | Critical | Client/server desync for containers & inventory | Agent "opened" 4 chests; server saw none; drops un-pickable |
| P5 | High | Chest storage eats items | Stored planks/logs/copper vanished |
| P6 | High | Drowning and water blindness | Fell into an aquifer, sank, nearly died; flowing water also drowns |
| P7 | High | Client crash with no recovery | Client crashed in c2me; PCL reported a Minecraft error |
| P8 | High | Pathfinding cannot plan 3D routes | Only "tower up"; no idea when enough blocks were placed |
| P9 | Critical | Agent waits on tool calls | User: "你在 wait！我不允许等待任何工具调用完成" |
| P10 | High | No HUD/inventory change awareness | Health/food/XP/inventory deltas not tracked in real time |
| P11 | Medium | Item frames / container contents invisible | Cannot read what is inside item frames or other containers |
| P12 | Medium | No experience/knowledge base | Same mistakes repeated across sessions |
| P13 | Medium | Tooling is heavy for the agent | Huge JSON outputs, long thinking, slow responses |

---

## P0 — Wrong-instance control when two clients are running

**Symptom.** With two game windows open (DeepSeek and FYNyesterday), the MCP server re-discovered
instances every ~20 s and picked the one with the newest heartbeat, so consecutive tool calls went
to different clients. Part of the agent's actions (walk path, chest loot) executed on the human's
account.

**Evidence.** In-game chat showed the human's surprise; the agent's own `get_state` calls alternated
between two players. Root fix applied during the session: `mc_attach` pins an instance and
`BridgeStore.ensure()` keeps the pinned/sticky instance while its registration is fresh.

**Root cause.** Instance selection was implicit ("newest heartbeat") and re-evaluated periodically;
there was no per-call target and no warning when multiple clients were live.

**Impact.** Wrong-account actions are a trust-breaking bug; the human lost control of their avatar
for a few seconds and received items they did not ask for.

**Required fix.**
1. Keep the pin; never auto-switch while the pinned instance is alive.
2. Expose `instance` as an explicit optional argument on every tool (or a `use_instance` scope), and
   return the target player name in every response header.
3. Refuse mutating tools when two or more live instances exist and no pin is set.
4. Add `mc_instances --players` output with player names, world, and health so the agent can verify.

**Acceptance.** With two clients running, 20 consecutive state reads and 5 mutating actions all hit
the same pinned player; the response header always names the target player.

---

## P1 — "Stop" does not stop the agent immediately

**Symptom.** The human typed "别挖了" (stop digging); the agent was inside a long path job and did
not react for tens of seconds, appearing stuck. The human repeated the request three times and
eventually hit the character with a sword to get attention.

**Human quotes.**
- "别挖了"
- "别挖了呀求你了，又卡住了"
- "你没有立刻停下来，我们的MCP工具还有问题"

**Evidence.** Chat log timestamps show the request at 1793289560 and the agent's first stop
acknowledgement at 1793289663 (~100 s later, after several job waits).

**Root cause.**
1. The main loop is synchronous: while a blocking call (job wait, script loop) is in flight the
   agent cannot read chat.
2. There is no priority abort at the tool layer; jobs run to completion or timeout.
3. The driving scripts (dig/climb) loop up to N rounds with their own stall counter and never check
   for a user stop signal.

**Impact.** The human cannot interrupt a misbehaving agent; the session becomes frustrating.

**Required fix (see FEATURES F1).**
1. New tool `stop_all`: cancel every job, clear queues, stop Baritone, release mining state, within
   one client tick.
2. Every job checks a cancellation flag each tick; `stop_all` sets it and marks jobs `cancelled`
   with reason `user_stop`.
3. Scripts poll a stop flag between rounds (or are replaced by jobs).
4. Agent policy: no blocking waits; actions ≤10–30 s; on any "stop/停下/别挖了" run `stop_all` first.
5. The monitoring subagent calls `stop_all` directly when it relays a stop message.

**Acceptance.** From the subagent receiving "stop" to all jobs being `cancelled` and Baritone
stopped: ≤2 s, verified by `jobs_list` showing zero running jobs.

---

## P2 — Repeated attempts to mine a block that never breaks

**Symptom.** The agent repeatedly targeted the same block. The human could see the block's breaking
cracks but the block never disappeared; the agent retried until a timeout, then moved on or tried
again. The human noted that this **usually happens during pathfinding** (Baritone digging through a
tunnel), not during deliberate mining.

**Human quote.** "你一直反复但失败得挖掘一个方块…（我能看到石头破裂）" — "you kept mining one block
and could not break it (I could see the stone cracking)".

**Evidence.** `Actions.mineBlock` calls `startDestroyBlock` on every tick (line ~314); `BatchJob`
does the same per op tick (line ~166). `batch` counted out-of-reach blocks as "mined" because the
final verification only checks a subset and `unverified` entries were ignored. Stone pickaxes were
in the main inventory, not the hotbar, so the bridge mined by hand and hit the per-block timeout.

**Root cause.**
1. **Mining progress reset every tick** — calling `startDestroyBlock` repeatedly restarts destroy
   progress; the client shows cracks but progress never accumulates. This is the primary cause.
2. **Reach**: batch ops targeted 3–5 steps ahead (up to ~6.8 blocks); the server rejected them but
   the batch counted them as done.
3. **Tool selection**: tools are only usable from the hotbar; the bridge did not swap/select.
4. **No per-block verification**: no "did this block actually change?" check per op.
5. **No progress detection** in the driving scripts; they retried identical requests.

**Impact.** The agent looks broken, wastes time, and can damage the terrain randomly.

**Required fix (FEATURES F2).**
1. Mining executor: `startDestroyBlock` **once** per target, then `continueDestroyBlock` every tick
   until the block state changes (or timeout), then `stopDestroyBlock` if needed.
2. Per-block verification: read the block after completion; if unchanged, fail the op with a reason
   (`out_of_reach`, `wrong_tool`, `unbreakable`, `protected`, `no_progress`).
3. Tool selection from the hotbar (best tier for the block), durability check; abort on
   `tool_broken`.
4. Fail-fast: never retry the same block more than once; report a compact error.
5. Scripts: on `no_progress`, change strategy once, then stop with a loud error.

**Acceptance.** Mining a stone block with a stone pickaxe in the hotbar breaks it in one pass
(no repeated restarts); a targeted unbreakable/out-of-reach block yields `failed` with the reason in
≤2 s and the job stops.

---

## P3 — Tool break does not abort the task

**Symptom.** The pickaxe broke mid-dig; the job kept calling mining actions with an empty hand.

**Human quote.** "稿子爆了会触发时间终止当前任务" — a broken tool must terminate the current task.

**Required fix.** Before each block, check the selected stack: empty or `damage >= maxDamage` →
fail with `tool_broken`, do not retry. Include tool durability in job metrics. The driver should
then craft or request a new tool, not continue.

**Acceptance.** With a 1-durability pickaxe, mining the second block fails as `tool_broken` and no
further mining call is made for that job.

---

## P4 — Client/server desync for containers, placement and pickups

**Symptom.** The agent reported "opened 4 chests, all empty"; the human, who sees the server, said
"你还没有开" (you did not open them). Block placement failed with "placement was not confirmed by
the client". Drops at 1 block distance were not picked up while the client reported 36/36 slots.

**Evidence.** `container_open` returns a client-side snapshot; `place_block` verifies client-side
after 3 ticks; `get_state` inventory is client-side.

**Root cause.** The bridge trusts client-side container/screen state. When the client and server
diverge (screen not opened server-side, inventory desync), operations look successful locally but
the server rejects them.

**Impact.** False reports, lost items, and the agent arguing with the human about reality.

**Required fix (FEATURES F4).**
1. `container_open`/`read`: verify with a server round trip (containerId + slot count + one
   non-destructive click/read), and report `serverConfirmed: true/false`.
2. Any move op: read back the source and destination after 4–8 ticks; if unchanged, fail with
   `server_rejected`.
3. `inventory resync` tool: force a client resync (e.g., close/reopen a container, or request a
   relog via the launch script) and report the new state.
4. Policy: if the human says an action did not happen, trust the human; stop and resync.

**Acceptance.** Opening a chest returns `serverConfirmed`; moving one item verifies both slots; a
forced desync test is detected and repaired by `resync`.

---

## P5 — Chest storage eats items

**Symptom.** The agent stored planks, logs and raw copper in a crafted chest; a later read showed the
chest empty and the items were gone from the inventory too.

**Evidence.** Screenshot `evidence/bug-chest-desync-2026-10-30.png` (empty chest screen + chat).
Chest at `(-1006, 64, 357)`; items stored: potatoes 18, apples 23, planks 8, logs 6, raw copper 10,
etc. After the desync: chest empty, inventory without the items.

**Root cause.** Same as P4: quick-move clicks were applied to a stale menu; the client removed the
items locally, the server never moved them.

**Required fix.** Same as P4 plus: storage operations must be verified server-side before reporting
success; large transfers should be chunked (one stack at a time) with read-back; keep a local
"expected inventory" ledger to detect divergence.

**Acceptance.** Storing 5 stacks and reading back the chest shows exactly the same items; no item
disappears from the ledger.

---

## P6 — Drowning and water blindness

**Symptom.** The agent dug a vertical shaft and broke into an aquifer at y≈57. It kept sinking from
y=56 to y=34 while `air` went negative; health dropped from 20 to 12. Earlier the character had
spawned in the ocean and nearly drowned. `walk up` did not reliably surface when standing on the
bottom of a 1-deep pocket.

**Evidence.** `damage_log` events with `cause:"drown"`, `air` −19..0; chat "你还好吗"; subagent
drowning alerts.

**Root cause.**
1. No fluid-aware planning: the shaft was dug straight down without checking the water table;
   probing one column did not cover neighbours.
2. No automatic escape when `air` drops; the agent only reacted after the human asked.
3. `walk up` sets vertical velocity, which Minecraft's water physics can override when the player is
   grounded in shallow water; swimming up must use the jump input / `isSwimming` state.
4. **Flowing water also drowns** — the agent only considered source blocks.

**Required fix (FEATURES F5).**
1. Fluid awareness: report source vs flowing water, waterlogged blocks, fluid at feet/head, air
   supply, and a `waterRisk` flag for the planned dig path.
2. Auto-escape: when `air < 150` or `underWater` persists > 2 s, immediately swim up / mine to air /
   move to the nearest dry cell; never continue the current dig.
3. Dig planning must refuse to mine into fluid (scan the next 3 blocks for water/lava).
4. `walk` swim mode must use the real jump input (set `input.jumping`) and verify upward movement.

**Acceptance.** In a purpose-built 3-deep pool the agent surfaces within 3 s and `air` returns to
max; a dig plan that would hit an aquifer is rejected with `water_risk` before mining.

---

## P7 — Client crash with no recovery

**Symptom.** During the session the agent's client crashed (c2me chunk system stack trace); PCL
reported a Minecraft error. The bridge disappeared (`disconnected` → `not_loaded`). The world save
survived, but the agent had no automatic recovery.

**Required fix (FEATURES F7).**
1. Watchdog on the agent side: detect the client process exit / bridge loss within 5 s.
2. Capture the crash log tail and `hs_err`/crash reports into `runtime/crashes/`.
3. Auto-restart the client with the quick-play launch script and rejoin the world; verify in-world
   within 3 min; report the crash to the human with the cause.
4. Do not spam-restart: max 2 attempts, then report and stop.

**Acceptance.** Killing the client process leads to an automatic rejoin within 3 min and a chat
message explaining the crash.

---

## P8 — Pathfinding cannot plan 3D routes (mine-rescue event)

**Symptom.** In the "mine rescue" the agent only knew "tower up with blocks". It did not know when it
had placed enough blocks, could not bridge sideways to a landing point, and got stuck in tunnels
where Baritone refused to path. It also dug into an aquifer.

**Human request (verbatim intent).**
- Add a **digging mode** (secondary): for mining tunnels and when trapped.
- Add a **move + place-blocks mode**: pre-plan a 3D route (move to X, place two blocks up, then
  bridge sideways like BedWars, move to Y, place blocks onward…), estimate the number and type of
  blocks needed, prefer low-value blocks.

**Required fix (FEATURES F3).** Implement two planners:
- **A. `dig_tunnel`** (secondary): staircase/shaft digging with fluid checks, exit plan, block
  budget, stop when the target Y/Z is reached.
- **B. `bridge_route`**: given a 3D waypoint list, compute the block placements (tower, side-bridge,
  gap-jump), the exact block count and material priority (`dirt > cobblestone > planks > stone
  bricks`), execute with per-block verification, and stop exactly at the target. It must also
  detect "enough blocks placed" (arrived at the target Y) instead of over-building.
- Both must integrate with `stop_all` and never mine into fluid.

**Acceptance.** From a 1×1 shaft at y=28, `climb_out` reaches the surface using ≤ the planned number
of blocks and reports the exact consumption; from a hill base at y=64, `bridge_route` reaches a
target at y=70 with a pre-computed block count (±1) and no wall damage.

---

## P9 — The agent waits on tool calls instead of checking state

**Symptom.** The human observed the agent "in wait" for long periods; commands did not respond until
a job finished.

**Human quote.** "你在 wait！我不允许等待任何工具调用完成，你只能查状态，并及时中断任务" — "you are
waiting! I don't allow waiting for any tool call to finish; you may only check state and interrupt
tasks in time".

**Required fix.** No blocking calls in the main loop: fire jobs and poll `job_status` in short bursts
(≤5 s), or use the event stream. Every action must be short (≤10–30 s). The monitoring subagent is
the only long-running listener and it must be able to call `stop_all`.

**Acceptance.** A 60 s job is started and the agent still answers a chat message and cancels within
2 s; `jobs_list` shows no unknown running jobs.

---

## P10 — No real-time HUD / inventory awareness

**Symptom.** The agent did not notice health, food, XP, air or inventory changes promptly. It also
reported block count changes that were irrelevant and missed pickups.

**Human request.** "你必须立刻知道你的血量条，经验条，饱食度，背包物品的变化… 你还得具备批量处理这些
消息队列的能力，不要一次处理一个。部分变化可以报告给 subagent，比如饱食度没下降到需要吃饭的程度就不
需要 subagent 发 message."

**Required fix (FEATURES F6).**
1. A per-tick HUD snapshot: health, max health, absorption, food, saturation, XP level/progress, air,
   armour durability, selected slot.
2. Inventory delta tracking: which stacks changed by how much; suppress noise from placing/mining
   own blocks, but report pickups, tool wear, food consumption, and unexpected losses.
3. **Batched delivery**: coalesce changes over 200–500 ms into one `state_delta` event; never emit
   one event per item.
4. Configurable thresholds for the subagent (food below 6, health below 8, air below 150, tool below
   10% durability) so low-value changes are not relayed.

**Acceptance.** During a 30 s mining session the agent receives ≤10 batched deltas; a pickup of 3
items appears in one delta; an uneaten full food bar produces zero events.

---

## P11 — Item frames and container contents are invisible

**Symptom.** The agent could not answer what was inside item frames or non-chest containers.

**Required fix (FEATURES F8).** `inspect_container` covering chests, barrels, furnaces, hoppers,
shulker boxes, item frames (item + rotation), armour stands (equipment), and signs (text). Also
extend `entity_query` to include item-frame contents and `block_query` to expose block-entity data
where cheap.

**Acceptance.** `inspect_container` on an item frame returns the item id and rotation; on a furnace
returns input/fuel/output; on a chest returns all slots.

---

## P12 — No experience/knowledge base

**Symptom.** The same mistakes repeated across sessions (aquifers, bed occupied state, no-cheats).

**Required fix (FEATURES F9).** Create and maintain `docs/Ph3/EXPERIENCE.md` (seed provided). The
agent reads it at session start and appends one section per session; prune duplicates. Keep entries
short and general.

**Acceptance.** The next session's notes contain at least 5 new verified entries and no duplicated
entries; the agent cites the file when planning a dig.

---

## P13 — Tooling is heavy for the agent (output size, thinking time)

**Symptom.** Large JSON payloads (inventory dumps, scan results) and long reasoning loops made
responses slow.

**Required fix.**
1. Compact `get_state` mode (default) with only changed/summary fields; full mode on demand.
2. Paginate/summarise large scans; cap text output at ~8 KB with a summary line.
3. Prefer one batch call over many; the server should return counts and diffs.
4. Keep docs short; the agent should not re-read huge files every turn.

**Acceptance.** `get_state` compact response ≤ 2 KB; a typical action turn (state → act → verify)
completes in ≤ 3 tool calls.

---

## P14 — Pathfinding walks through fields and gets stuck on fence gates

**Symptom.** The path planner routed the agent straight through the village farm; closed fence
gates blocked the route and the agent got stuck against them.

**Human quote.** "你的寻路指示你穿越田地，但是被栅栏门卡住" — the path goes through the field and gets
stuck on the fence gate.

**Root cause.** The planner treats every non-solid block as passable and has no notion of doors,
fence gates or crops; Baritone's own path can also fail at closed gates. There is no "open the
gate" action.

**Required fix (FEATURES F3.C).**
1. Path cost model: doors and fence gates are **passable but require opening** (interact once);
   farmland/crops are high-cost (trampling) and avoided unless the destination is inside.
2. When the next path node is a door/gate: `use_block` to open it, verify the block state
   (`open=true`), then walk through; if it stays closed, route around.
3. Never walk over crops if an alternative exists.

**Acceptance.** From the village square to a house across a fenced field, the agent opens the gate,
passes through, and does not trample crops; the route is reported.

---

## P15 — Digging through house walls to get out

**Symptom.** When leaving a building the agent sometimes mined a wall instead of using the door.

**Human quote.** "出门的时候没必要挖墙出去，我看你有的时候是挖墙出去的" — no need to dig through the wall
to go out.

**Root cause.** The planner has no "use the door" preference and treats walls as diggable when the
route is blocked; `allowDig` was effectively always on.

**Required fix (FEATURES F3.C).**
1. Default `allowDig=false` for all movement; digging is only allowed when the caller explicitly
   asks (mining tasks) or when no door/route exists **and** the caller passes `allowDig=true`.
2. Before digging, check for a door/fence gate/window/opening within a small radius and route
   through it.
3. Report `route: "door"` vs `route: "dig"` in the result.

**Acceptance.** From inside a village house, `bridge_route`/`path_to` to an outside point exits
through the door and reports `route:"door"`; no wall block is broken.

---

## Chat log excerpts (for traceability)

```
<FYNyesterday> 你好喵~
<FYNyesterday> 我们先睡觉吧
<FYNyesterday> 你能睡在你旁边的床上吗
<FYNyesterday> 我的床被占用就挖了重放
<FYNyesterday> 我们去挖铁
<FYNyesterday> 你主手不要有魂符
<FYNyesterday> 我刚刚被重置了，东西全没了
<FYNyesterday> 我来救你
<FYNyesterday> 别挖了
<FYNyesterday> 别挖了呀求你了，又卡住了
<FYNyesterday> 你没有立刻停下来，我们的MCP工具还有问题
<FYNyesterday> 你一直反复但失败得挖掘一个方块…（我能看到石头破裂）
<FYNyesterday> 稿子爆了会触发时间终止当前任务
<FYNyesterday> 不要有任何的wait任务
<FYNyesterday> 你还没有开
<FYNyesterday> 你定位的箱子是不是定位错了
<FYNyesterday> 你的背包好像满了，拾取不了掉落物品
```
