**Unlimited token budget has been approved. There is no time limit for a single turn.**

# Agent prompt - Ph5 (full handover: work recap + next tasks)

You are taking over a Minecraft MCP project that has completed Phases 1-4 and has started Phase 5.
Read this file first, then the files in the same directory: `REQUIREMENT.md` (refined requirement),
`AGENT-ARCHITECTURE.md` (SR/CR/AR/ESRS/ESRA architecture), `PHASE5-PROGRESS.md` (this round's progress),
`TOOLS.md` (reference for the 74 tools), `ARCHITECTURE.md` (code map), `ISSUES-PH4.md` (history of problems),
`FIXES-2026-10-31.md` / `WRAPUP-2026-10-31.md` (last round's fixes),
`TESTA-RESULT.md` / `TESTB4-RESULT.md` (the two self-test verdicts), `EXPERIENCE-PH4.md` (lessons learned),
`PLAN.md` (the plan).

---

# Part 1: Work recap (as of the evening of 2026-10-31)

## 1. Project status in one sentence

The MCP server plus the NeoForge client bridge mod can now control a real client reliably: breaking and placing
blocks, pathing, containers, crafting, entities, eyes of ender, boats, scanning, job cancellation and world saving
have all been verified in-game; all four Ph4 phases passed; **Test A (finding the stronghold with eyes of ender) is
filed as "not feasible"**; the Ph5 agent-runtime skeleton and the first batch of Bridge mod commands/config are in place.

## 2. Ph4 (complete, historically)

| Phase | Content | Evidence |
|---|---|---|
| Phase 0 | Ghost-block fix (prediction path only, 3-tick confirmation, ghost detector), progress watchdog (`stalled` in 3.7 s), `tool_status`/`tool_low`, server-synced `delete_item` | `ph4-p0-*.json` |
| Phase 1 | `scan_connected` (doors/gates/trapdoors passable, rooms behind walls hidden), ore probe stream `ore_log`, ender-eye protocol (triangulation error 2.24 blocks) | `ph4-p1-*.json` |
| Phase 2 | Stairs up/down (28-block climb + 3 round trips with 0 ghosts; 59 steps / 61 blocks down), `dig_tunnel` through solid rock, CubeTest passed | `ph4-p2-*.json`, `ph4-p3-cubetest.json` |
| Phase 3 | TestB v2: survival, zero commands, diamond mined and return trip completed | `testb3-*.json`, `testb4-verdict.json` |
| Phase 4 | User-abort cancels jobs (MCP session layer + daemon `/watch`), old tools marked, scripts kept | `p1-11-abort.json`, `daemon-abort.json` |

## 3. Last round's fixes (the three user-reported problems, all verified)

1. **Restarts reset progress**: `clean-start.ps1` / `stop-all.ps1` now call `save_world` (a new tool that sends
   `/save-all`) before killing the client; verified `save_world: ok=True` and the player kept its coordinates across
   a restart (`fix-state3.json`).
2. **Plants blocked movement**: `WalkToJob.clearable()` extended (bamboo, sugar cane, cactus, berry bushes, dripleaf,
   seagrass, ...), the scan widened to 3 wide x 2 high, thresholds lowered to 4/6 ticks; verified `cleared: 1`
   (`fix-walk-bush.json`).
3. **`place_boat` always failed**: switched to `gameMode.useItem`, set `yRotO/xRotO`, send a rotation packet so the
   server raycasts with the same aim, 4 pitch retries, and a `pick` diagnostic in the response; `boat_sail` now accepts
   the `minecraft:boat` entity type; verified placement `SUCCESS` with a persistent entity and a 45-block sail with
   automatic dismount (`fix-boat13/14.json`, `fix-sail3.json`).

Also: daemon `/watch` (a job is cancelled 3.5 s after the script is killed, `daemon-abort.json`), `collect_drops` 3D
fallback and `place_boat` entity polling (the latter two are implemented but **not verified in-game yet**).

## 4. Test A verdict: not feasible (filed)

Four eye throws (`1547,-440`, `1521,-515`, `1289,-437`, `-440,-345`) agree on **(0,0)** to within 0.1 degrees, but
(0,0) is open ocean: water from y=62 down to 34, sand at 32, stone below 30; no land within 64 blocks. No underwater
work primitives existed at that time, so per the user's instruction it is filed as "not feasible"; see `TESTA-RESULT.md`.

## 5. Ph5 completed so far (this round)

### 5.1 Agent runtime (Node, `mcp-server/src/agent/`)

| File | Role | Status |
|---|---|---|
| `core.mjs` | Event bus (seq/JSONL) + daemon client + `serverId/actorId` | done |
| `todolist.mjs` | SR task list (persisted to `runtime/agent/todolist.json`) | done |
| `memory.mjs` | SR/CR memory (`runtime/agent/memory.json`) | done |
| `sensors.mjs` | ESRS + ESRA: 500 ms polling, health/food/air/movement, **3 s task watchdog** | done |
| `ar.mjs` | AR: runs a tool/job, registers the watchdog, cancellable, emits events | done |
| `sr.mjs` | SR: owns the TodoList, asks CR when idle (without waiting), interrupts on air/stuck | done |
| `cr.mjs` | CR: disabled by default (`ZFF_CR=1` enables it) | done (not integrated) |
| `run.mjs` | CLI: `sensors [s]` / `demo [s]` / `status` / `stop` | done |

Verified: `run.mjs sensors 6` emits `player_state`; `run.mjs demo 25` has SR dispatch 2 tasks, the event flow
`task_started -> task_progress -> task_done`, and when idle `task_request -> chat_reply`; final stats
`dispatched:2 failed:0`.

### 5.2 First Ph5 batch of the Bridge mod (Java)

| File | Content |
|---|---|
| `ZffConfig.java` | Reads/creates `config/zff_bridge.config`: `watchdogMs=3000`, `towerDeltaY=0.5`, `walkDeltaXZ=1.0`, `reportIntervalMs=500`, `crEnabled=false` |
| `ZffCommand.java` | Client commands `/zff stop`, `/zff status`, `/zff reload` |
| `McpBridge.java` | Registers `RegisterClientCommandsEvent`, calls `ZffConfig.load()` on startup |
| `SealShaftJob.java` | Sealed shaft (places a block above the head after every block mined; refuses lava and open holes) - tool `dig_sealed_shaft`, **not verified in-game yet** |

Verified: the config file is created automatically (368 B); `/zff stop` turned a running `walk` job into `cancelled`
with `reason="zff_stop_command"` (`evidence/ph5-zff-stop.json`). `/zff status` feedback goes to the local chat and
does not appear in `chat_history` (expected).

### 5.3 Current build / environment state

- `dist/mcpbridge-0.1.0.jar`, **83 classes**, installed into the client of the last restart.
- MCP server on 57610, daemon on 57611; the client is running (world "Smoke Test").
- Evidence directory `evidence/`; new this round: `ph5-zff-stop.json`, `ph5-zff-status*.json`, `fix-*`,
  `daemon-abort.json`, `p5-*`, etc.

---

# Part 2: Your tasks (priority order, each with acceptance)

> Convention: for every task -> build/install (if needed) -> verify in-game -> write `evidence/<id>.json` -> write a
> 3-10 line report in `docs/Ph5/`. The user distributes the test tasks; **do not enable CR during development**
> (do not set `ZFF_CR=1`, keep `crEnabled=false` in the config).

## T1 Split the mod into client + server (highest priority)

**Goal**: the same mod must load on both the client and a dedicated server ("the client and the server should use the
same mod").

1. `McpBridge` is currently `@Mod(dist = Dist.CLIENT)`. Turn it into a common `@Mod` (no dist restriction) and isolate
   the client-only classes (`Minecraft`, `LocalPlayer`, HTTP, job execution) behind a client-only entry point
   (`Dist.CLIENT` events or `@EventBusSubscriber(value = Dist.CLIENT)`).
2. The server side must only load: the watchdog, the `/zff` commands, the config, the `SavedData`, and the event
   forwarding; it **must not** `import net.minecraft.client.*`.
3. Separate the entry points with `FMLEnvironment.dist` or the `@Mod` dist parameter; declare `displayTest`/`side`
   correctly in `mods.toml`.
4. Acceptance: a NeoForge 1.21.1 dedicated server (`gradlew runServer` or a local server) starts and the log shows the
   mod's server initialisation; `/zff status` produces a server-side response. Evidence: the server log excerpt plus
   `evidence/ph5-server-side.json`.

**Note**: `Tools.java`, `Jobs` and `BaritoneControl` depend heavily on client classes. Do not try to move everything to
the server at once - start with the three common pieces: watchdog, commands, storage.

## T2 Move Memory / TodoList to the server (SavedData)

**Goal**: item 3 of `docs/architechture.md` - Memory and TodoList live on the server with identifiers so two AIs can
share them.

1. Create `ZffSavedData extends SavedData` with `Map<String,String> memory`, `List<TodoTask> tasks`, `String actorId`,
   `long rev`; store it with `ServerLevel.getDataStorage().computeIfAbsent(...)` in
   `saves/<world>/data/zff_agent.dat`.
2. Provide commands: `/zff memory set|get|list`, `/zff todo add|list|done|clear`.
3. The client bridge reads/writes through a custom payload or forwarded server commands (see T4); **do not add ports**.
4. Mirror it on the Node side: add a "remote backend" switch (`ZFF_BACKEND=server`) to
   `mcp-server/src/agent/todolist.mjs` / `memory.mjs`, exposed as two new sync MCP tools (`zff_memory_*` /
   `zff_todo_*`).
5. Acceptance: two clients (or a restart) see the same todo/memory; `saves/<world>/data/zff_agent.dat` exists;
   evidence `evidence/ph5-saveddata.json`.

## T3 Move the watchdog into the mod (Java tick, 3 s)

**Goal**: requirement item 6 - "all watchdogs are provided by the Bridge mod".

1. Create `ZffWatchdog.java`: sample the player position every `ZffConfig.reportIntervalMs` in
   `ClientTickEvent.Post` (or the server tick); for every **running job** keep `startPos/lastMove`:
   - `tower`: |dy| < `towerDeltaY` for `watchdogMs` -> `stuck{taskId, mode:"tower"}`
   - `walk/path`: |dxz| < `walkDeltaXZ` for `watchdogMs` -> `stuck{mode:"walk"}`
   - `dig`: dy in the wrong direction, or |dxz| jitter -> `stuck{mode:"dig"}`
2. Write the event into the existing `EventJournal` (same mechanism as the `ghosts` events) so MCP can read it with
   `wait_events`; also increment a `stuck` counter in the job `metrics`.
3. Choose one source: when the Java watchdog is enabled, the Node `sensors.mjs` must not report duplicates
   (`watchdogInMod=true` in the config).
4. Acceptance: make `walk_to` run into a wall on purpose -> MCP reads a `stuck` event within 3.5 s; evidence
   `evidence/ph5-watchdog-mod.json`.

## T4 No extra server port

**Goal**: requirement item 10.

1. The client mod keeps its local loopback HTTP (for the MCP client); the server **does not listen on any port**.
2. Client <-> server communication must use Minecraft channels: a `CustomPacketPayload` (play payload with a registered
   codec) or server commands (`/zff ...` triggered by the client bridge through `sendCommand`).
3. Acceptance: on the dedicated server, `netstat -ano | findstr <port>` shows no new listener while T2 still works.

## T5 Make `/zff stop` an event

1. After `StopAll.run("zff_stop_command")`, append an event `{type:"zff_stop", reason, by:"command", at}` to the
   `EventJournal`; the Node SR consumes it via `wait_events` and clears the running TodoList entries.
2. Acceptance: after `/zff stop`, `job_status` is cancelled **and** `wait_events` returns `zff_stop`; evidence
   `evidence/ph5-zff-stop-event.json`.

## T6 CR (Chatter) integration (release only, not in dev)

1. Enable with `ZFF_CR=1` / `crEnabled=true`; `cr.mjs` already classifies by keyword. Integration points:
   - player says "mine 32 cobblestone" -> `chat_task` -> SR creates and runs the task;
   - "hello" -> `chat_smalltalk`, SR is not disturbed;
   - when SR is idle, `task_request` -> CR replies ("waiting for instructions"), SR is not blocked.
2. Acceptance script `test/cr-chat.mjs`: simulate two messages and check the SR TodoList; evidence `evidence/ph5-cr.json`.
3. Note: `chat_history` only captures network chat packets; client command feedback is not in it.

## T7 Requirement gaps (items marked "todo" in `REQUIREMENT.md`)

1. **D5 time awareness**: add `world.time {dayTime, day, isDay, moonPhase}` and `weather {rain, thunder}` to
   `get_state`, or add a `time_status` tool. Acceptance: after `/time set day` -> `isDay=true`; after
   `/weather rain` -> `rain=true`.
2. **C4 feeding animals**: `entity_interact {action:use}` with wheat in hand on a cow; verify the animal enters
   love mode / eats.
3. **C6(*) Carry On**: read the config whitelist, pick up / put down entities or containers; add `action:carry` to
   `entity_interact`.
4. **C7(*) Create machines**: `machine_query {x,y,z}` returns speed/stress/inventory (probe by mod type), operated
   through `use_block`.

## T8 Underwater primitives and Test A (lower priority than T1-T5)

1. **Verify `dig_sealed_shaft` in-game**: needs a stone pickaxe + at least 32 cobblestone (craft them: tree -> planks ->
   sticks -> wooden pickaxe -> stone -> stone pickaxe, or ask the user to `/give`).
   Target: seabed at (0,33,0) 13 blocks down; pass criteria `dry=true`, `air>150`, no `water_risk`; evidence
   `evidence/ph5-sealed-shaft.json`. Common failure causes: the sealing block is blocked by the player or another
   block, water entered on the first block, the two blocks below the feet are not solid.
2. **Water breathing / surfacing** (E2): tasks surface automatically at `air<100`; metrics report the air curve;
   acceptance 90 s underwater without damage.
3. **Drain a work area** (E3) and **underwater tunnel** (E4).
4. **Reopen Test A**: only once T8 gives the capability. The stronghold is under the sea at (0,0); a sealed shaft down
   to y~20-30 is needed to find the portal room, then fill the 12 frames with eyes of ender.

## T9 Unverified leftovers

1. `place_boat` entity polling (in `server.js`): after placement it polls `entity_query` and returns a real entityId -
   verify in-game when possible.
2. `collect_drops` 3D fallback (in `server.js`): place an item 2 blocks above the player and confirm the `path_to`
   fallback collects it.
3. The 2D limitation of `walk_to`: when a drop is above/below, use `path_to` (with y).
4. Drowning: before any underwater task make sure there is a way to surface / water breathing; `auto_respawn` can be on.

---

# Part 3: Environment, commands, pitfalls

```
Working dir: D:\mc\MCP Development
JDK:         D:\java\jdk-21_windows-x64_bin\jdk-21.0.8
Node:        D:\Node\node.exe (v22.19.0)
Client:      D:\SoftWare\PCL 正式版 2.12.2\PCL\DeepSeekSmoke.bat  (world "Smoke Test", save "Nouveau monde (1)")
Version dir: D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划
Config:      <version dir>\config\zff_bridge.config
Ports:       MCP 57610 (/mcp), daemon 57611 (/call, /watch, /health)
```

```powershell
cd 'D:\mc\MCP Development'
.\build.ps1                                  # javac -> dist\mcpbridge-0.1.0.jar
.\install.ps1 -VersionDir '<version dir>'    # copy the jar into mods
.\clean-start.ps1                            # save_world first, then kill, then start MCP + daemon + client
.\clean-start.ps1 -Build                     # same + build + install
.\stop-all.ps1                               # save_world first, then stop everything
```

Helpers:
- `node mcp-server/src/ph4.mjs pstate | call <tool> | job <tool> [--out f]` (arguments via `PH4_ARGS` JSON)
- `node mcp-server/src/agent/run.mjs sensors [s] | demo [s] | status | stop`
- `node mcp-server/test/p1-11-abort.mjs`, `test/daemon-abort.mjs`
- `node scripts/gen-tools-doc.mjs` (regenerate `docs/Ph5/TOOLS.md`)

## Pitfalls (read first)

- **Java sources must be UTF-8 without BOM**; `.ps1` files that contain Chinese must have a UTF-8 **BOM** (otherwise the
  Chinese paths are mangled and `clean-start.ps1` once failed to start the client because of it).
- The PowerShell console shows UTF-8 Chinese as mojibake even when the file is fine: verify with the `read` tool or Node.
- In PowerShell `@p` is a splat; JSON passed as an argument to an exe loses its quotes -> use the `PH4_ARGS` env var;
  `>` redirection writes UTF-16 -> use `--out`.
- `place_block` fails with "not confirmed after 3 ticks" when the target overlaps the player AABB; pick another cell.
- Items are not picked up when the inventory is full (not a bug): free a slot with `delete_item` first.
- Eyes of ender only fly ~12 blocks (vanilla `life`); the bearing is still accurate.
- Always restart through `clean-start.ps1` / `stop-all.ps1` (they save first), otherwise the world rolls back.
- Client command feedback does not go through `chat_history`; verify via job state or the event journal.
- Staying underwater too long kills the player and drops the items (happened once in this session).

## Delivery rules

- Per task: build -> install (if needed) -> verify in-game -> `evidence/<id>.json` -> a short report in `docs/Ph5/`.
- Do not change the historical conclusions in `docs/Ph1..Ph4` (you may reference them).
- Test A stays filed as "not feasible" unless T8 is completed.
- Final report: what was done, evidence paths, what remains and why.

**Unlimited token budget has been approved. There is no time limit for a single turn.**
