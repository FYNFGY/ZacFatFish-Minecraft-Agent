# agent-prompt3 — Minecraft MCP Agent, Round 3

You are taking over an existing Minecraft MCP agent project. This document is your instruction
manual. Read it fully, then read `ISSUES.md`, `FEATURES.md`, `ARCHITECTURE.md`, `ACCEPTANCE.md`,
`EXPERIENCE.md` in that order.

## 1. Mission

Make the agent **safe, responsive and reliable** in survival Minecraft, and implement the missing
navigation features. Concretely:

1. Fix every P0/P1 issue in `ISSUES.md` — the human experienced them live and asked for fixes.
2. Implement the new pathfinding modes (`bridge_route` primary, `dig_tunnel` secondary) and the
   HUD/inventory event stream.
3. Add container verification, water auto-escape, crash recovery, an experience knowledge base, and
   `inspect_container`.
4. Re-run the acceptance plan (`ACCEPTANCE.md`) and produce evidence.

## 2. Context you inherit

- Project root: `D:\mc\MCP Development` (mod in `mod/`, MCP server in `mcp-server/`, scripts in
  `scripts/`, docs in `docs/`).
- Mod: NeoForge 1.21.1 `mcpbridge`; build with `build.ps1`; install with `install.ps1 -VersionDir
  "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划"`.
- Current build at hand-off: `mcpbridge-0.1.0.jar`, 143,489 bytes, SHA-256
  `21AE16DB10FDAB3961E35A74ACBAFED98AAFA9754F6C8DD4955C93CB0C1DD350`, 42 MCP tools.
- MCP server: `mcp-server/src/server.js`, HTTP at `127.0.0.1:57610`, tools call the bridge
  `/rpc` (response shape `{ok:true,result:{...}}` — unwrap `result`).
- Game: `mcp-test-world`, survival, **no cheats** (`/tp`, `/give`, `/gamemode` are rejected).
- Human player: `FYNyesterday` (Chinese speaker; chats in Chinese in-game).
- Agent account: `DeepSeek` (offline), launched by `PCL\DeepSeekLaunch.bat` (quick-play into the
  world). PCL regenerates `LatestLaunch.bat`; keep `DeepSeekLaunch.bat` separate.
- The human's client and the agent's client may run at the same time; **pin the MCP server to the
  DeepSeek instance** (`mc_attach instanceFile:<absolute path>`) and never let it drift.

## 3. Non-negotiable rules (from the human)

1. **Stop immediately.** On any "stop / 停下 / 别挖了" → `stop_all` first, then reply. Latency ≤ 2 s.
2. **No blocking waits.** Never call `wait_events` in the main loop and never block on a job; only
   short status checks (`job_status` bursts ≤ 5 s). Actions ≤ 10–30 s.
3. **Never touch the human's account.** The pinned instance is the only target; if two clients are
   live and no pin is set, refuse to act.
4. **No cheats.** Do not plan around `/tp`, `/give`, `/gamemode`; they do not work in this save.
5. **Bridging material priority**: dirt > cobblestone > planks > stone bricks > andesite/granite/
   diorite; never use ores, tools, food or quest items.
6. **No over-building.** Compute the needed block count first, stop the moment the target is reached.
7. **Never loop a failed action.** One retry with a changed strategy at most, then fail loudly with a
   reason.
8. **Noise control.** Do not report every block placed/mined; report aggregated deltas, pickups,
   tool wear, food/health changes. Batch HUD/inventory changes.
9. **Trust the human.** If they say an action did not happen, resync/relog instead of arguing with
   client state.
10. **Doors and gates.** Fence gates block movement until opened; open them (interact) and verify
    `open=true`. Do not dig through house walls to leave — use the door. `allowDig=false` is the
    default for movement.
11. **Do not trample farms.** Route around farmland/crops unless the destination is inside them.
12. **Branch mining** (when asked to mine resources): 1×2 main corridor, branches every 3 blocks
    (2-block wall), branch length ~32, level ~Y=-54 or -59, fluid check every cell. Spec: F3.D.
13. **Downward digging**: never a 1×1 straight-down shaft. Default to a **staircase** (45°, 4-block
    headroom, torches every 5) or a **1×2 shaft** (sneak between columns, ladder/scaffold, pillar up
    to ascend). 1×1 only with explicit `allowStraightDown` plus bed/chest/ladder/water-bucket prep.
    Spec: F3.E.
14. **Lighting**: know block light / sky light at all times; hostile mobs spawn at block light 0
    (internal sky light ≤ 7). Light every tunnel (~torch every 6–7 blocks), keep working areas at
    block light ≥ 8, and never leave a dark mined area. Spec: F3.F.
15. **Final tests need authorization.** Never start Test A (Stronghold run) or Test B (Fishbone
    diamond) on your own. Run the smoke tests and the acceptance suite first, present the evidence,
    and wait for the human to explicitly authorize the final tests.
16. **Entity-breaking**: boats, minecarts, item frames, paintings and armour stands are **entities** —
    recover them with `break_entity` (repeated hits; item frames/paintings one hit; boats ~4 bare-hand
    hits or one >4-damage hit), never with `mine_block`, and never break the block behind a frame.

## 4. The issues you must fix (summary; details in `ISSUES.md`)

| ID | Issue |
|---|---|
| P0 | Wrong-instance control with two clients |
| P1 | "Stop" not immediate (no `stop_all`, blocking jobs) |
| P2 | Repeated mining of an unbreakable block (cracks but no break) |
| P3 | Tool break does not abort the task |
| P4 | Client/server desync for containers/placement/pickups |
| P5 | Chest storage eats items |
| P6 | Drowning / water blindness (including flowing water) |
| P7 | Client crash with no recovery |
| P8 | Pathfinding cannot plan 3D routes |
| P9 | Agent waits on tool calls |
| P10 | No real-time HUD/inventory awareness |
| P11 | Item frames/containers invisible |
| P12 | No experience knowledge base |
| P13 | Tooling heavy for the agent |
| P14 | Pathing through fields, stuck on fence gates |
| P15 | Digging through house walls to get out |

## 5. New capabilities (details in `FEATURES.md`)

- **F1 `stop_all`** — global abort within one tick; every job cancellable.
- **F2 Mining executor v2** — start once + continue each tick, per-block verification, hotbar tool
  selection, `tool_broken` abort, reason codes.
- **F3 Path planners** — `dig_tunnel` (secondary; fluid-safe, exit plan) and `bridge_route`
  (primary; 3D waypoints, block budget & material priority, BedWars-style side bridging, stop at
  target). `climb_out` wrapper.
- **F4 Container verification & resync** — server round-trip, `serverConfirmed`, `server_rejected`,
  `resync`.
- **F5 Water subsystem** — source/flowing detection, auto-escape, dig refusal on `water_risk`,
  real jump-input swimming.
- **F6 HUD/inventory stream** — batched `hud`/`inv` events with priorities and noise filters.
- **F7 Crash watchdog** — detect client loss, save logs, auto-restart & rejoin, report.
- **F8 `inspect_container`** — item frames, furnaces, chests, signs, armour stands.
- **F9 Experience base** — read and extend `EXPERIENCE.md` every session.
- **F10 Latency/ergonomics** — compact state, small outputs, no waits.

## 6. Work order

**Phase 0 — safety net (do first)**
1. `stop_all` + cancellable jobs + `stop` event.
2. Fix the agent loop policy: short actions, status checks only.

**Phase 1 — mining & containers (the human's daily pain)**
3. MiningExecutor v2 (F2) with per-block verification and tool checks.
4. Container verification + resync (F4).

**Phase 2 — navigation**
5. `dig_tunnel` (F3.A) with fluid checks and exit plan.
6. `bridge_route` (F3.B) with planning, block accounting, side-bridging, stop-at-target.
7. `climb_out` wrapper.

**Phase 3 — awareness & resilience**
8. PlayerWatch HUD/inventory deltas (F6).
9. Water auto-escape (F5).
10. Crash watchdog (F7).

**Phase 4 — knowledge & polish**
11. `inspect_container` (F8).
12. Experience base (F9).
13. Compact state / latency (F10).

Do not start a phase before the previous phase's P0 acceptance tests pass.

## 7. Development workflow

1. Edit Java in `mod/src/main/java/dev/mcp/bridge/`; build with `build.ps1`; install with
   `install.ps1`; restart the client with `DeepSeekLaunch.bat`.
2. Edit the MCP server in `mcp-server/src/`; restart it (`node server.js --http --port 57610
   --workspace D:\mc`), then pin to the instance.
3. Scripts in `scripts/` are the agent's drivers; they must be short-lived and cancellable.
4. For every change, run the corresponding acceptance test and save evidence under `evidence/`.
5. Keep `docs/Ph3/` up to date; append session notes to `EXPERIENCE.md`.

## 8. Testing rules

- Use the real client and real survival; no creative.
- Reset state between destructive tests (or use a copy of the world).
- Record: build hash, test id, steps, result, evidence path, time.
- A test that "sometimes works" is a FAIL.

## 9. Communication with the human

- In-game chat: Chinese, short (one line), no spam; answer questions directly.
- When you stop after a request, say what you stopped and why.
- When something fails, say the reason (`out_of_reach`, `water_risk`, `server_rejected`, …), not
  "it doesn't work".
- Docs and code comments: English.

## 10. Known pitfalls (see `EXPERIENCE.md` for details)

- Aquifer layer y≈52–57 near the cave village; dry probes are column-local.
- Flowing water drowns and refills shafts.
- Beds can stay `occupied`; break and replace.
- Lootr chests are per-player.
- SimpleTomb graves need the key.
- PCL regenerates the launch script.
- `/tp` etc. are rejected (no cheats).
- The client may crash in c2me; the watchdog must recover it.

## 11. Definition of done

- All P0 acceptance tests pass; no P1 regression.
- `stop_all` ≤ 2 s end-to-end.
- Mining never loops on an unbreakable block; tool break aborts.
- Containers verified server-side; no item loss.
- `bridge_route` reaches a 3D target with a pre-computed block count and stops exactly there.
- HUD/inventory changes arrive batched within 250 ms.
- Crash recovery works.
- **Both world-level tests pass**: Stronghold run (End portal lit) and Fishbone diamond (≥1 diamond).
- `EXPERIENCE.md` updated; `evidence/ph3-acceptance.json` complete.
- The human confirms the next live session feels responsive (no "you are waiting", no "you are
  stuck on a block").

## 12. World-level tests (the round's success criteria)

**Testing order and the authorization gate (human rule, 2026-10-30).**

1. **Self-test first.** At the start of the work day, before touching anything heavy, run a smoke
   test of the dev setup: build, install, launch, pin the instance, `get_state`, one short job
   (e.g., `walk` 20 ticks), one `stop_all`, one screenshot. If the smoke test fails, fix that first.
2. **Then the acceptance suite** (`ACCEPTANCE.md` T1–T16, plus the T12 regression).
3. **Then ask for authorization.** Present a short evidence summary (build hash, tests passed,
   known gaps) and **wait for the human to explicitly authorize the final tests**.
4. **Only then run Test A and Test B**, in fresh worlds, with the rules below.

Never start Test A or Test B without that explicit authorization.

Two acceptance tests are played in **brand-new worlds**, default world type, **peaceful + survival**
(confirmed by the human):

### Test A — Stronghold run (light the End portal)

- Cheats are enabled **only** to run `/give @s minecraft:ender_eye 64` once; **no other command** is
  allowed (`/locate` is forbidden). The 64 eyes belong to Test A only — Test B uses no cheats at all.
- Throw eyes to triangulate the stronghold, travel there, dig down with a **staircase**, find the
  portal room, fill all 12 frames, activate the portal. **Do not enter the End.**
- **Water rule (human request):** when a large water body (ocean/sea/large lake) is detected on the
  route, do **not** swim across it — gather wood if needed, craft a **boat** (5 planks), place it on
  the water, mount it, sail to the far shore, and dismount. **After crossing, recover the boat** —
  hit it repeatedly until it drops as an item (a bare hand needs ~4 hits; a tool/weapon dealing more
  than 4 damage breaks it in one hit), then pick it up (boats are non-stackable). Never abandon it.
  Report the boat crossing and the recovery.
- Mechanics to respect: an eye flies ~12 blocks toward the nearest stronghold and has a 20% chance to
  shatter; it flies **up** while >12 blocks from the staircase chunk and **down** when closer; 12 eyes
  are required; each frame has a 10% chance to generate pre-filled.
- Pass: `minecraft:end_portal` blocks exist in the world; report throws, shatters, distance, time,
  frames pre-filled vs placed; no death; no fluid breach; no command other than the single `/give`.

### Test B — Fishbone diamond (find at least one diamond)

- **No cheats.** Start from scratch: punch wood, craft tools, staircase down to **Y=-54** (optionally
  -59), then fishbone mine: 1×2 main corridor, branches every **3 blocks** (2-block wall), 16–24 long,
  torches every 6–7 blocks, light check at the end.
- Hazard protocol: probe the target + 3 blocks ahead for water/lava/air; water/lava → seal the hole and
  skip that branch; cave → place torches and route around (do not explore deep); sand/gravel → side-mine
  or skip; keep a 2-block floor above lava.
- Pass: **≥1 diamond** in the inventory; `darkCells=0` in the mined area; no water/lava breach; safe
  return to the surface; report coordinates, blocks mined, branches, time, ore counts.

Both tests are mandatory. Evidence goes to `evidence/ph3-test-a/` and `evidence/ph3-test-b/`, and the
lessons are appended to `EXPERIENCE.md`.

## 13. Reference documents
- `ISSUES.md` — all problems with root causes and required fixes.
- `FEATURES.md` — feature specs and acceptance hooks.
- `ARCHITECTURE.md` — implementation guide (jobs, executor, planner, watcher, protocol).
- `ACCEPTANCE.md` — test matrix T1–T18.
- `EXPERIENCE.md` — Minecraft common sense to read and grow.
- `../Ph2/TOOLS.md` — current tool reference; `../Ph2/ACCEPTANCE.md` — previous round results.
