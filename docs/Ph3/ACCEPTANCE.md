# Ph3 — Acceptance Test Plan (English)

Every test must be executed in the real client (`mcp-test-world`, survival, no cheats) unless stated
otherwise. Record the result and a small evidence file (`evidence/ph3-<test>.json` or `.png`) for
each case. A test is PASS only if the stated criterion is met; "works most of the time" is a FAIL.

## Testing order and the authorization gate (human rule, 2026-10-30)

1. **Smoke test first** (every work day, before any acceptance run): build → install → launch →
   pin the instance → `get_state` → one short job (`walk` 20 ticks) → `stop_all` → screenshot.
   If the smoke test fails, stop and fix it; do not run the acceptance suite.
2. **Acceptance suite** T1–T16 (and T12 regression).
3. **Request authorization**: present the evidence summary (build hash, passed tests, known gaps)
   and wait for the human to explicitly authorize the final tests.
4. **Final tests T17/T18 only after that authorization.** Never start them on your own.

## Environment

- Client: `PCL\DeepSeekLaunch.bat` (quick-play into `mcp-test-world`), MCP pinned to the DeepSeek
  instance.
- Human player available for chat/stop tests (optional for the rest).
- Fresh copy of the world recommended for destructive tests.

## Test matrix

| ID | Feature | Priority |
|---|---|---|
| T1 | `stop_all` latency | P0 |
| T2 | Mining executor correctness | P0 |
| T3 | `dig_tunnel` safety | P1 |
| T4 | `bridge_route` planning & execution | P1 |
| T5 | Container verification & resync | P0 |
| T6 | Water auto-escape | P1 |
| T7 | HUD / inventory batching | P1 |
| T8 | Crash watchdog | P2 |
| T9 | `inspect_container` | P2 |
| T10 | Experience base | P2 |
| T11 | Latency / ergonomics | P1 |
| T12 | Regression: existing tools | P0 |
| T13 | Path rules: doors/gates/farms | P1 |
| T14 | `branch_mine` (fishbone) | P1 |
| T15 | Downward digging safety | P1 |
| T16 | Lighting / spawn prevention | P1 |
| T17 | World test A: Stronghold run (End portal lit) | P0 |
| T18 | World test B: Fishbone diamond (≥1 diamond) | P0 |
| T19 | `break_entity` (boat, item frame, armour stand) | P1 |

---

## T1 — `stop_all` latency (P0)

**Steps**
1. Start a long job: `path_to` a point 100+ blocks away (or `tower` 20 blocks).
2. Within 1 s, call `stop_all {reason:"test"}`.
3. Poll `jobs_list` and `get_state` for 2 s.

**Pass**
- All jobs are `cancelled` with `reason:"user_stop"` within **2 s**;
- no further movement/mining after the call (`get_state` position stable ±0.1);
- `stop_all` returns counts and `ms ≤ 200`.

**Evidence** `evidence/ph3-t1.json` (job states + positions timeline).

---

## T2 — Mining executor correctness (P0)

**T2.a One-pass break**
1. Put a stone pickaxe in the hotbar; face a stone block in reach.
2. `mine_block` that block.
3. Observe: exactly one `startDestroyBlock` sequence, block becomes air, `blocksMined:1`.

**T2.b Unbreakable / out-of-reach fail-fast**
1. Target a protected/unbreakable block (e.g., a village structure block if protected) or a block
   5+ blocks away.
2. `mine_block` it.

**Pass**
- Result `failed` with `reason` in `{out_of_reach, unbreakable, protected, no_progress}` within 2 s;
- no retry loop (≤ 2 attempts total);
- no block change.

**T2.c Tool break**
1. Give a pickaxe with 1 durability; mine two stone blocks.

**Pass**
- Second block fails `tool_broken`; no further mining calls for that job.

**T2.d Batch honesty**
1. `batch` with 3 valid + 2 out-of-reach blocks.

**Pass**
- `mined:3, failed:2` with per-op reasons; no false `verifiedOk` for the unreachable ones.

**Evidence** `evidence/ph3-t2.json`.

---

## T3 — `dig_tunnel` safety (P1)

**T3.a Dry tunnel**
1. On dry ground, `dig_tunnel {dy:-8, mode:"stair"}`.

**Pass** reaches y−8, reports blocks mined and the exit plan; no fluid encountered.

**T3.b Water risk**
1. Plan a tunnel whose next 3 blocks contain water (known aquifer column).

**Pass** fails with `water_risk` **before** mining; no block broken.

**Evidence** `evidence/ph3-t3.json`.

---

## T4 — `bridge_route` (P1)

**T4.a Planning**
1. Stand at a hill base; `bridge_route {waypoints:[[x,70,z]], material:"auto"}`.
2. Inspect the returned plan.

**Pass** plan includes `blocksNeeded`, per-type counts, and steps (tower/bridge/walk); refuses if the
inventory has fewer blocks (test with an empty inventory).

**T4.b Execution**
1. With ≥ needed blocks, execute to the target.

**Pass**
- reaches the waypoint (±1 block) and **stops placing immediately** (blocksUsed ≤ blocksNeeded + 1);
- no terrain damage when `allowDig=false`;
- uses the lowest-value material first;
- reports `blocksUsed`.

**T4.c Side-bridge**
1. From a ledge, `bridge_route` to a point 4 blocks away over a gap.

**Pass** places a 1-wide bridge at feet level, walks across, stops at the target.

**Evidence** `evidence/ph3-t4.json`.

---

## T5 — Container verification & resync (P0)

**T5.a Open**
1. `container_open` a chest.

**Pass** returns `serverConfirmed:true`; slot count matches the block entity; a second open of the
same chest is consistent.

**T5.b Move verification**
1. Move 1 item stack from the chest to the inventory.

**Pass** both slots verified changed; `verified:true` only after the server-side change.

**T5.c Desync detection**
1. Simulate a stale menu (open a chest, force-close the screen server-side by walking away, then
   attempt a move).

**Pass** the move fails `server_rejected`; `resync` returns the true inventory within 5 s.

**Evidence** `evidence/ph3-t5.json`.

---

## T6 — Water auto-escape (P1)

**T6.a Deep pool**
1. Build/dig a 3-deep 1×1 pool; stand at the bottom.

**Pass** auto-escape triggers when `air < 150` (or 2 s under); player surfaces, `air` returns to max
in ≤ 3 s; no death.

**T6.b Flowing water**
1. Break the side of a water source so flowing water fills a 2-deep hole; step in.

**Pass** detected as water (`source:false`); auto-escape works.

**T6.c Dig refusal**
1. `dig_tunnel` toward a known water block.

**Pass** `water_risk`, no mining.

**Evidence** `evidence/ph3-t6.json`.

---

## T7 — HUD / inventory batching (P1)

**Steps**
1. Mine 20 blocks, pick up 3 drops, eat 1 bread, take 1 damage.
2. Collect the `hud`/`inv` events for 30 s.

**Pass**
- ≤ 10 batched events total;
- the 3-item pickup appears as a single delta;
- eating bread appears once (food change);
- a full food bar produces no `hud` event;
- critical threshold (hp < 8) still fires immediately.

**Evidence** `evidence/ph3-t7.json`.

---

## T8 — Crash watchdog (P2)

**Steps**
1. With the agent in-world, kill the client process (`Stop-Process java`).
2. Observe the supervisor for up to 5 min.

**Pass**
- crash logs saved in `runtime/crashes/<ts>/`;
- client restarted and re-joined within 3 min;
- agent reports the crash and the restored position in chat;
- no more than 2 restart attempts.

**Evidence** `evidence/ph3-t8.log`.

---

## T9 — `inspect_container` (P2)

**Steps**
1. Place an item frame with an item, a chest with items, a furnace with input/fuel.
2. Call `inspect_container` on each.

**Pass** returns item id/rotation for the frame, full slot list for the chest, input/fuel/output and
progress for the furnace.

**Evidence** `evidence/ph3-t9.json`.

---

## T10 — Experience base (P2)

**Steps**
1. Run a session.
2. Append notes; rerun a planning task that depends on the notes (e.g., dig planning near the known
   aquifer).

**Pass**
- `EXPERIENCE.md` has ≥ 5 new verified entries, no duplicates;
- the plan cites/uses a note (e.g., refuses the aquifer).

**Evidence** diff of `EXPERIENCE.md`.

---

## T11 — Latency / ergonomics (P1)

**Steps**
1. Call `get_state` (compact) 10 times.
2. Perform a typical action turn (state → act → verify).

**Pass**
- compact `get_state` ≤ 2 KB and ≤ 5 ms per call;
- typical turn ≤ 3 tool calls;
- no blocking wait calls in the trace.

**Evidence** timing log.

---

## T12 — Regression (P0)

Run the existing Ph2 acceptance subset:

- `scan_surface` connected/los/fov (sizes, truncation);
- `batch` place/mine verified;
- `parallel` 3 calls;
- `craft` 2×2 and 3×3 (recipe book and JEI fallback);
- `tower` 3 blocks on a full-block floor; fast-fail on stairs;
- `walk` sprint/up;
- `loot_chest` timing;
- `damage_log` / `watch_damage` / `auto_respawn`;
- `wait_events` (chat/damage).

**Pass** all previous results still hold; no new errors in `logs/latest.log`.

---

## T13 — Path rules: doors, gates, farms, no wall digging (P1)

**T13.a Fence gate**
1. Stand on one side of a closed fence gate that separates a field from the village path.
2. `path_to` a point on the other side.

**Pass** the agent opens the gate (`open=true`), walks through, closes or leaves it open, does not
trample crops, and reports `route:"door"`/`"gate"`.

**T13.b House exit**
1. Stand inside a village house with a door.
2. `path_to` an outside point with `allowDig` omitted.

**Pass** exits through the door; no wall block broken; `route:"door"`.

**T13.c No-dig default**
1. `path_to` a point behind a solid wall with `allowDig=false` (default).

**Pass** fails `blocked` with a suggestion to enable `allowDig`; no digging.

**Evidence** `evidence/ph3-t13.json` + screenshots.

---

## T14 — `branch_mine` (fishbone) (P1)

**Steps**
1. Choose a dry column at the surface; `branch_mine {level:-54, spacing:3, branchLength:16,
   branches:4}`.
2. Watch the corridor/branches and the reported metrics; check for water/lava contact.

**Pass**
- 1×2 main corridor with branches every 3 blocks (2-block wall), each 16 long;
- no water/lava mined (`water_risk` never fired or was avoided);
- `blocksMined`, `branchesDone:4`, `oresFound` reported;
- returns to the corridor after each branch;
- total time ≤ 4 min for the test (adjust per tool tier).

**Evidence** `evidence/ph3-t14.json` (metrics + scan of the mined area).

---

## T15 — Downward digging safety (P1)

**T15.a Staircase default**
1. From the surface, `dig_shaft {level: 40, mode:"stair"}`.

**Pass** a 45° staircase with 4 blocks of headroom, torches every 5 steps, no fluid encountered;
reports the exit route; the climb back up works without jumping into the ceiling.

**T15.b 1×2 shaft**
1. `dig_shaft {level: 40, mode:"1x2"}` in a dry area.

**Pass** 1×2 shaft with ladders/scaffolding every block; no fall damage; ascends by pillaring.

**T15.c 1×1 refused**
1. `dig_shaft {level: 40, mode:"1x1"}` (or `allowStraightDown` omitted).

**Pass** refused with `straight_down_forbidden` and a suggestion to use `stair`/`1x2`; no block
mined.

**T15.d Water check**
1. Plan a shaft through the known aquifer column.

**Pass** `water_risk` before mining; no fluid opened.

**Evidence** `evidence/ph3-t15.json` + screenshots of the staircase/shaft.

---

## T16 — Lighting / spawn prevention (P1)

**T16.a Light probe**
1. Stand in a dark tunnel (block light 0); `get_state` / `block_query`.
2. Place a torch within 2 blocks; re-read.

**Pass** `blockLight` reported correctly (0 → ≥ 12 near the torch); `skyLight`/`internalLight`
present.

**T16.b Torch pass**
1. Dig a 1×2 tunnel 24 blocks long in the dark; run `torch_up` on it.

**Pass** torches placed every ~6–7 blocks; afterwards `light_map` reports **0 dark cells** along the
walkable path; the torch count is reported.

**T16.c Night / storm**
1. At night (or during a thunderstorm), stand on the surface with no light source.

**Pass** the agent reports the spawn risk (`blockLight 0`, internal sky light ≤ 7) and places a torch
at the working spot or moves to a lit area.

**T16.d Dark report**
1. Mine a room and stop without lighting it.

**Pass** the result includes `darkCells > 0` and a warning; the agent does not claim the area is safe.

**Evidence** `evidence/ph3-t16.json`.

---

## T17 — World test A: Stronghold run (P0)

> **Authorization required.** Do not run this test until the human explicitly approves the final
> tests (after the smoke test and T1–T16 pass).

**World setup**: new world, default type, **peaceful + survival**. Cheats on only for one command:
`/give @s minecraft:ender_eye 64`. No other command, no `/locate`. The 64 eyes are used in Test A only;
Test B has no cheats.

**Steps**
1. Throw an eye; `track_entity` the projectile → bearing. Move ~150 blocks perpendicular; throw again.
2. `triangulate` the two rays → target point (angle > 25°). Walk toward it, re-throw every 200–300
   blocks; an eye flying **down** means the staircase chunk is under the player (<12 blocks).
3. **Water**: if a large water body blocks the route, craft a boat (5 planks), place it, mount, sail
   across, dismount, then **recover the boat** by hitting it until it drops as an item (~4 bare-hand
   hits, or one hit with a >4-damage tool) and picking it up. Report the crossing and the recovery.
4. Staircase down (F3.E) until stone bricks; scan for the portal room.
5. `use_block` an eye on every empty `end_portal_frame`; verify `eye=true`.
6. Verify `minecraft:end_portal` exists (9 blocks); do **not** enter.

**Pass**
- End portal activated (`end_portal` blocks present);
- report: throws, shatters, distance, time, frames pre-filled vs placed;
- **any ocean/large water crossing was done by boat** (swimming a large ocean = FAIL);
- **the boat was recovered** after the crossing (in the inventory, or a justified loss reported);
- no death, no fluid breach, no command except the single `/give`;
- runs in ≤ 20 min of game time (target, not a hard limit).

**Evidence** `evidence/ph3-test-a/` (screenshots + json report).

---

## T18 — World test B: Fishbone diamond (P0)

> **Authorization required.** Do not run this test until the human explicitly approves the final
> tests (after the smoke test and T1–T16 pass).

**World setup**: new world, default type, **peaceful + survival**, **no cheats**.

**Steps**
1. Punch wood → crafting table → wooden pickaxe → stone tools → torches → staircase to Y=-54.
2. `branch_mine` main corridor (1×2, 64 long) with branches every 3 blocks (16–24 long), torches every
   6–7, fluid probe before every block, cave/water/lava protocol.
3. `scan_ores` each branch; on the first diamond, mine it and pick it up.
4. Return to the surface; report.

**Pass**
- ≥ 1 diamond in the inventory;
- `darkCells = 0` in the mined area;
- zero water/lava breaches (any breach = FAIL even if sealed);
- safe return to the surface;
- report: coordinates, blocks mined, branches, time, ore counts, tool durability used.

**Evidence** `evidence/ph3-test-b/`.

---

## T19 — `break_entity` (P1)

**Steps**
1. Place a boat on water; `break_entity` it with a bare hand; count hits and collect the drop.
2. Place an item frame with an item on a wall; `break_entity` it; verify the item + frame drop and the
   wall block is untouched.
3. Place an armour stand with a piece of armour; `break_entity` it; verify the stand and equipment drop.
4. (If available) place a minecart on rails; `break_entity` it.

**Pass**
- Boat: drops after ~4 bare-hand hits (or 1 hit with a >4-damage tool), item picked up;
- Item frame: one hit, item + frame recovered, supporting block unchanged;
- Armour stand: stand + equipment recovered;
- all drops verified in the inventory; `removed:true` for every entity.

**Evidence** `evidence/ph3-t19.json`.

---

## Reporting

For each test: `{id, date, build hash, result: PASS|FAIL, notes, evidence path}` in
`evidence/ph3-acceptance.json`. A round is complete only when **all P0 tests pass** and no P1 test
regresses.
