# Ph3 — Feature Specifications (English)

Each feature is required to close the corresponding issues in `ISSUES.md`. Specs are written so the
implementation can be tested directly (see `ACCEPTANCE.md`).

---

## F1 — `stop_all` and job interruption

**Goal.** Any request to stop (human chat, subagent, agent decision) aborts all game activity within
one client tick.

**API.**
```
stop_all { reason?: string } → { stopped: { path: n, mine: n, batch: n, craft: n, ... }, baritone: bool, ms }
```

**Behaviour.**
- Every job implements `abort()` (or checks a shared `Cancellation` each tick).
- `stop_all` sets the flag on all jobs, cancels Baritone, clears `TickScheduler` tasks, releases the
  current mining target (send `stopDestroyBlock` semantics by resetting state), and closes transient
  screens opened by the bridge.
- Jobs that were cancelled report `state: "cancelled"`, `reason: "user_stop"`.
- Latency budget: ≤ 50 ms to mark, ≤ 1 tick to stop physical actions.

**Events.** `stop_all` also pushes an event `type:"stop"` so the agent/subagent share the same view.

**Agent policy.** On any chat containing "stop"/"停"/"别挖了": call `stop_all` **before** replying,
then answer in chat.

---

## F2 — Mining executor v2 (correct progress, verification, tools)

**Goal.** No more "cracks but never breaks", no false `mined` counts, no mining with an empty hand.

**Rules.**
1. For each target block:
   - Validate distance ≤ 4.4 (eye to block centre) and line-of-sight (optional).
   - Select the best **hotbar** tool for the block (`getDestroySpeed` comparison).
   - Check durability (`damage < maxDamage - 1`), else fail `tool_broken`.
   - Call `startDestroyBlock` **once**, then `continueDestroyBlock` every tick until the block state
     changes (air or replacement) or a per-block timeout (hardness-based, ≤ 8 s).
   - Verify the block state after completion; if unchanged → `no_progress` and stop the job.
2. Batch mining: run the same state machine per op, verify each op, and stop on the first hard
   failure (configurable `continueOnError=false` default).
3. Report per-op results: `{pos, block, tool, ticks, result: ok|reason}` in `metrics.ops`.
4. Do not restart a failed block automatically; the caller decides.

**Acceptance.** See `ACCEPTANCE.md` T2.

---

## F3 — 3D path planning: `dig_tunnel` and `bridge_route`

The human explicitly asked for two planners:

### F3.A `dig_tunnel` (secondary; for mines and being trapped)

```
dig_tunnel { to: [x,y,z] | {dy: n}, width?: 1|2, mode?: "stair"|"shaft", maxBlocks?: n }
```

- Deterministic geometry: staircase (1 block drop per 2 horizontal blocks) or vertical shaft.
- **Fluid check first**: scan the next 3 blocks of the planned path for water/lava/waterlogged; if
  found, fail `water_risk` (never dig into fluid).
- Always compute the **exit plan** before digging (staircase within the tunnel or a tower budget).
- Stop exactly at the target; report blocks mined and the exit route.

### F3.B `bridge_route` (primary; move + place blocks, BedWars-style)

```
bridge_route {
  waypoints: [[x,y,z], ...],       // required, 1..64
  material?: "auto" | item id,     // default auto: dirt > cobblestone > planks > stone bricks
  maxBlocks?: n,                   // safety budget
  allowDig?: bool                  // default false: no terrain damage unless asked
}
```

- **Pre-plan in 3D**: for each waypoint, compute a concrete plan:
  - vertical gain ≤ 1: walk/climb;
  - vertical gain 2–4: tower (jump + place under feet) or staircase blocks;
  - gaps/ledges: side-bridge (place blocks in front/below while walking, BedWars "bridge") with a
    support check at each step;
  - ceiling/door: if `allowDig`, mine the obstruction (via F2), otherwise fail `blocked`.
- **Block accounting**: before execution, return `plan: {blocksNeeded, perType, steps:[...]}`; the
  job refuses to start if `blocksNeeded > available` (unless `allowPartial`).
- **Material priority**: low-value first (`dirt` > `cobblestone` > `planks` > `stone_bricks` >
  `andesite`/`granite`/`diorite`), never use ores, tools, or quest items.
- **Stop when arrived**: after each waypoint, verify the player's block position equals the waypoint
  (±1 block tolerance) and *stop placing immediately*; never over-build.
- Autonomy: when the target is a "hilltop" or "surface", the planner may add intermediate waypoints
  (e.g., every +3 Y) as long as the total stays within the block budget.

### F3.C Path rules: doors, gates, farms, no wall digging

- **Doors / fence gates**: passable only if opened. When the next path node is a door or gate,
  interact with it, verify `open=true`, then pass. If it cannot be opened, route around; never break
  it.
- **Farmland / crops**: high-cost cells. Avoid crossing fields unless the destination is inside the
  field; never jump on farmland (trampling).
- **No wall digging by default**: movement tools (`path_to`, `bridge_route`, `walk`) must use
  `allowDig=false`; digging through a building wall is allowed only with explicit `allowDig=true`
  and when no door/opening exists. Prefer doors, windows, existing tunnels.
- Result includes `route: "door" | "walk" | "dig" | "bridge"` for auditability.

### F3.D `branch_mine` (fishbone / 鱼骨挖矿法)

Verified against the Minecraft Wiki (`Tutorial:Mining` → *Branch Mining*): a central 1×2 corridor
with side branches; spacing 2 exposes almost every block but is slow, spacing 5 can miss small
diamond/lapis blobs, and a **3-block spacing (2-block wall) is the recommended thoroughness/efficiency
compromise**; branches ~32 blocks long (a stone pickaxe's durability) and the mining level ~Y=-54
(above lava lakes) or -59 (diamonds). Cite:
<https://minecraft.wiki/w/Tutorial:Mining#Branch_Mining>.

```
branch_mine {
  start: [x,y,z],          // where the access shaft begins (surface or existing tunnel)
  level?: number,          // target Y, default -54 (safe) / -59 (diamonds)
  spacing?: 2|3|4|5,       // blocks between branch tunnels, default 3
  branchLength?: number,   // default 32
  branches?: number,       // default 8
  material?: "auto"|id,    // for the access staircase blocks, default auto
  maxBlocks?: number
}
```

Execution:
1. `dig_tunnel` a 1×2 staircase (or reuse an existing shaft) to `level`.
2. Dig the main corridor (1×2) along +X for `branchLength * branches` blocks.
3. Every `spacing` blocks, dig a 1×2 branch perpendicular (`±Z`), length `branchLength`, with a 1×1
   tip extension (optional).
4. After each branch, return to the main corridor; keep a map of mined cells to avoid re-mining.
5. Fluid check on every planned cell (aquifers); abort `water_risk` if water/lava is in the next 3
   blocks of the corridor or branch.
6. Report: `blocksMined`, `oresFound` (scan for the requested ore ids in a 12-block radius after
   each branch), `branchesDone`, `timeMs`.

**Acceptance.** From a surface start at a dry column, `branch_mine {level:-54, spacing:3,
branchLength:16, branches:4}` completes 4 branches, mines no water, reports ores found, and returns
to the corridor after every branch.

**Acceptance (F3.C).** Path through a fenced field opens the gate and avoids crops; exiting a house
uses the door with `allowDig=false`.

### F3.E Downward digging (access shafts) — never 1×1 straight down

Verified against the Minecraft Wiki (`Tutorial:Mining` → *Shaft mining*, *Vertical shaft mining*):
1×1 "dig straight down" has a 62% chance of reaching bedrock, 27% of landing in a cave, **8% lava**
and 3% ravines (100 tests, Mumbo Jumbo). It also has no return path.

Rules for `branch_mine` / `dig_tunnel` access shafts:
1. **Preferred: staircase** — 45°, one block across per block down, **4 blocks of headroom** above
   each step (not 3) so the climb back does not hit the head; torch every 5 steps; 2-high body
   clearance. Straight staircase is the default for the agent.
2. **Fast + safe: 1×2 shaft** — mine the block adjacent to the one you stand on, step down, mine the
   block you stood on and the one below, place a ladder/scaffold every block; repeat. "Even simpler"
   variant: sneak between the two columns and mine straight down (sneak prevents falling), switching
   columns every ~4 blocks; ascend by pillaring with the stone you mined. ~80% of a stone pickaxe.
3. **Spiral staircase** (2×2/3×3) — stays in one chunk, often safer/faster to bedrock.
4. **1×1 vertical is allowed only with explicit `allowStraightDown=true`** and full preparation
   (bed/chest at the top, ladders, water bucket, blocks in hotbar). Default: refused.
5. Always carry a water bucket + a stack of blocks in the hotbar for lava; check the next 3 blocks
   for fluids; in this world the aquifer at y≈52–57 makes blind shafts especially dangerous.

```
dig_shaft { start:[x,y,z], level: number, mode?: "stair"|"1x2"|"spiral", allowStraightDown?: false }
```

**Acceptance.** See `ACCEPTANCE.md` T15.

### F3.F Lighting and spawn prevention

Verified against the Minecraft Wiki (`Light` → *Effects of light → Mobs*): since 1.18 (Java), most
hostile mobs need **internal sky light ≤ 7 and block light = 0** to spawn; a torch has level 14 and
loses 1 per block, so a torch keeps the floor around it ≥ 8 for ~6–7 blocks. At midnight the internal
sky light is 4 (≤ 7), so the surface still spawns mobs at night; thunderstorms spawn "as if sky light
were 5". Passive mobs need block light ≥ 9 when not exposed to sky.
Source: <https://minecraft.wiki/w/Light#Mobs>.

Rules:
1. `get_state` and `block_query` expose `blockLight`, `skyLight`, `internalLight` at feet/head and for
   queried positions.
2. `light_map {center, radius}` returns a 2D map of light levels at feet level and a list of dark
   cells (`blockLight == 0`) within a radius — used after digging and at night.
3. `torch_up {area|path}` places torches from the hotbar/inventory so that every walkable cell has
   block light ≥ 8; spacing ~6–7 blocks in 1×2 tunnels; skips cells that already have a light source
   (torch, lantern, glowstone, lava).
4. Digging plans (`dig_tunnel`, `branch_mine`, `dig_shaft`) must include a lighting pass: after every
   ~8 blocks of tunnel, place a torch; refuse to leave a mined area with `blockLight == 0` cells
   (report `darkCells` count).
5. Night/storm policy: when `skyLight` is not enough (night, thunder, or underground), the agent
   keeps the working area lit; never mine or idle in the dark.
6. If no torches are available, craft them (sticks + coal/charcoal) or report `no_light_source`.

**Acceptance.** See `ACCEPTANCE.md` T16.

---

## F4 — Container and inventory verification

**Goal.** The bridge must never report a container action that the server did not accept.

**Rules.**
1. `container_open`: after the screen opens, confirm `containerId`, `slotCount` and at least one
   slot identity with a follow-up read; return `serverConfirmed`.
2. `container_move`: read the source and destination before and after; if the expected change is
   missing after 4–8 ticks → `server_rejected` (do not report success).
3. `inventory_resync`: force a resync (reopen a container, or restart the client via the launcher)
   and return the fresh inventory; used when the human says an action did not happen.
4. Container ops are chunked (≤ 3 stacks per request) with a read-back between chunks.

**Acceptance.** See `ACCEPTANCE.md` T5.

---

## F5 — Water / fluid subsystem

**Rules.**
1. State exposes: `inWater`, `underWater`, `eyeInWater`, `swimming`, `air`, `maxAir`,
   `fluidAtFeet` (id + level + source/flowing), `fluidAtHead`, `waterlogged`.
2. Auto-escape policy (always on): if `underWater` and `air < 150`, or `underWater` persists > 2 s,
   immediately: swim up (real jump input), or mine a neighbouring air cell, or move to the nearest
   dry block; then report the event.
3. Dig planning refuses water/lava in the next 3 blocks.
4. `walk` swim mode must set the jump input (`player.input.jumping = true` when `isInWater`) and
   verify the Y delta; fall back to teleport-free escape if no progress after 10 ticks.

**Acceptance.** See `ACCEPTANCE.md` T6.

---

## F6 — HUD + inventory delta stream (batched)

**Events.**
```
type: "hud",     data: {hp, absorption, food, saturation, xpLevel, xpProgress, air, armor, selectedSlot}
type: "inv",     data: {changes: [{slot, item, delta}], totals: {...}}
```
- Emitted at most every 250 ms, coalesced; only changed fields.
- Inventory deltas: compare against a per-tick ledger; ignore changes caused by the agent's own
  place/mine of the same item within 1 s (noise), but report pickups, tool wear, food use, and any
  unexpected loss.
- Priority levels: `critical` (hp < 8, air < 150, food < 3), `normal` (pickups, tool durability drop
  below 10%), `low` (XP, small counts) — the subagent only relays critical/normal.

**Acceptance.** See `ACCEPTANCE.md` T7.

---

## F7 — Crash watchdog and auto-recovery

**Rules.**
1. The MCP server (or a small supervisor script) watches the client process / bridge heartbeat.
2. On loss: capture `logs/latest.log` tail + `hs_err*` into `runtime/crashes/<ts>/`.
3. Restart with the quick-play launch script (`DeepSeekLaunch.bat`), wait for `inWorld`, verify
   player and position, and report to chat: "crashed at X, restarted, back at Y".
4. Max 2 restart attempts per 10 minutes; then stop and report.

**Acceptance.** See `ACCEPTANCE.md` T8.

---

## F8 — `inspect_container` (item frames, furnaces, chests, signs, armour stands)

```
inspect_container { x,y,z } → { type, slots?, item?, rotation?, text?, equipment? }
```
- Item frame: item id/count + rotation.
- Chest/barrel/hopper/dispenser: slot list (id, count, damage).
- Furnace/blast furnace/smoker: input/fuel/output + burn/cook progress.
- Sign: front/back text.
- Armour stand: equipment slots.

**Acceptance.** See `ACCEPTANCE.md` T9.

---

## F9 — Experience base

- File: `docs/Ph3/EXPERIENCE.md` (seed provided).
- The agent reads it at session start; after each session appends a dated section.
- Entries must be general, verified, and short; prune duplicates; mark unverified items.

**Acceptance.** See `ACCEPTANCE.md` T10.

---

## F10 — Latency and agent ergonomics

**Rules.**
1. No blocking waits in the main loop (`wait_events` forbidden there; use only short `job_status`
   polls or the `poll_events` non-blocking variant).
2. Actions ≤ 10–30 s; long jobs are fire-and-forget with periodic short checks.
3. Compact `get_state` (≤ 2 KB), paginated scans, summary-first outputs.
4. The subagent is the only long listener; it filters events by priority and calls `stop_all` on
   stop requests.
5. Docs for the agent are short; the heavy detail lives in Ph3 for the human.

**Acceptance.** See `ACCEPTANCE.md` T11.

---

### F3.G Boat crossing (large water bodies)

Human rule for the world tests: **when an ocean/large water body is detected, build a boat and cross
by boat** — do not swim across.

1. **Detection**: `scan_surface`/`path_follow` reports the water width along the route; if the water
   span is > 16 blocks (or > 30 % of the next 64 blocks), switch to boat mode.
2. **Craft**: a boat needs **5 planks** of one wood type (recipe book: 2 in the top corners + 3 across
   the bottom). If no planks/wood, gather logs from nearby trees first (craft planks → boat).
3. **Place**: `use_block` on the water surface (place the boat entity); verify the entity exists.
4. **Mount**: interact with the boat (new `enter_vehicle`) and verify `player.vehicle` is the boat.
5. **Steer**: boats respond to **movement input**, not velocity — set `input.forward=true` and the
   player yaw toward the next waypoint every ~250 ms; re-aim to the far shore; avoid shores, lily
   pads and kelp; keep the boat centered in deep water.
6. **Dismount**: near the shore, stop input, sneak (shift) to exit, verify the player is on land.
7. **Recover the boat (human rule)**: boats are entities — **repeatedly hit** the boat until it drops
   as an item (a bare hand does 1 damage/hit and the boat has ~4 HP, so ~4 hits; a tool that deals
   **more than 4 damage** destroys it in one hit — check the sword/axe in hand). Then pick the item up
   and verify it is back in the inventory (boats are **non-stackable**, one slot each). **Never
   abandon the boat.** Only leave it behind if unreachable/despawned, and report the reason.
8. **Fallback**: if no wood/boat is available, swim with the real jump input and surface every few
   seconds (F5); never cross a large ocean by swimming unless the boat is impossible.
9. Report: water span, boat crafted/placed/recovered, crossing time, dismount position.

**Acceptance.** In Test A, a water crossing is done in a boat, **the boat is recovered afterwards**
(back in the inventory, or a justified loss is reported), no drowning. See `ACCEPTANCE.md` T17.

---

## F11 — World-level test tooling (Stronghold run + Fishbone diamond)

The human defined two mandatory in-game tests (see `agent-prompt3.md` §12). They need these tools:

### For the Stronghold run (Test A)

1. **`throw_item {item?}`** — right-click with the item in hand and return the spawned projectile
   entity id (e.g., `minecraft:eye_of_ender`).
2. **`track_entity {id, ticks}`** — sample positions over N ticks and return a direction vector,
   horizontal bearing (yaw), vertical sign, and distance travelled.
3. **`triangulate {rays:[{x,z,yaw}...]}`** — compute the intersection point and an error estimate
   (angle between rays must be > 25°, otherwise ask for another throw).
4. **`scan_blocks {center, radius, ids:[...]}`** — find `stone_bricks`, `end_portal_frame`,
   `end_portal` etc. and return clusters.
5. **`path_follow {waypoints, maxSeconds}`** — long-distance travel that avoids water/cliffs and
   replans on failure (no digging by default; F3.C rules apply).
6. **`use_block` on End portal frames** — detect the `eye` blockstate, place an eye (interact with the
   eye in hand), verify `eye=true`; report `already_filled` when the frame already has one.
7. Report: throws, shatters, distance travelled, time, frames pre-filled vs placed.

### For entity recovery / cleanup (boats, frames, paintings, armour stands)

1. **`break_entity {id?|type?, maxHits?, collect?}`** — break an **entity** (not a block) by attacking
   it until it is removed, then collect the drops:
   - **Boats / minecarts**: repeated hits until the vehicle drops as an item (boat ~4 HP → ~4 bare-hand
     hits, or one hit with a tool dealing >4 damage; minecart one hit). Boats are non-stackable.
   - **Item frames / glow item frames / paintings**: one hit drops the item/artwork (attached to a
     block; attack the entity, never the wall).
   - **Armour stands**: one hit drops the stand and the equipment it holds.
   - Returns `{type, hits, dropped:[item ids], pickedUp:[...], removed: true}`.
2. `collect_drops {radius}` — walk over nearby item entities and verify they entered the inventory.
3. Never use `mine_block` on these — they are entities; the block behind them must stay intact.

**Acceptance.** See `ACCEPTANCE.md` T19.

### For the Fishbone diamond (Test B)

1. `fluid_probe` (part of F5) — 3-block lookahead for water/lava/air.
2. `scan_ores {ids:["diamond_ore","deepslate_diamond_ore"], radius}` — after each branch.
3. `seal_block {pos}` — place a block to close a water/lava breach.
4. `branch_mine` (F3.D) + `torch_up`/`light_map` (F3.F).
5. `exit_to_surface` — staircase back up (F3.E), or `climb_out` (F3.B).

### Acceptance

- Test A: `end_portal` blocks exist; no death; no fluid breach; no command besides the single `/give`.
- Test B: ≥1 diamond collected; `darkCells=0`; no water/lava breach; safe return.

Both run in fresh default worlds, peaceful + survival (Test A allows only the one `/give`).

---

## Non-goals for this round

- Multi-player combat, villager trading optimisation, redstone, Create machinery.
- Any use of cheats (the save has none).
- Automatic building of large structures (only bridging/escape/tunnels).
