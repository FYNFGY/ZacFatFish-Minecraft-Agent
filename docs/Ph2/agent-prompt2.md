**Unlimited token budget has been approved. There is no time limit for a single turn.**

# Handoff Prompt for the Next AI — MCP Minecraft Client-Control Project

You are taking over a completed implementation and a live in-game test. Read this document before changing any file, calling any tool, or issuing any Minecraft command. It is a handoff, not a fresh requirements document. Do not repeat work that is already done; start from the current state at the end of this document.

The user is testing the agent's real ability to operate a headed Minecraft client through MCP. The implementation itself is delivered. The immediate context is a survival-mode diamond-block building exercise, and the client is currently paused on `PauseScreen`.

## 0. Non-negotiable rules for this handoff

1. **Answer in English.** The user explicitly requires the next AI to answer in English. If the user later explicitly asks for Chinese, follow that newer instruction.
2. **Never print, log, or put the bridge bearer token into a response, a file, a screenshot, or the model context.** Tokens exist only in the instance JSON. Tool outputs are sanitized; keep it that way.
3. **The game server is read-only.** Never install, configure, or modify anything server-side. Multiplayer actions must remain observation/movement only; no taking from server containers, no mining/placing, no destructive commands.
4. **Headed client only.** No headless patch, no LWJGL replacement. A real render path is mandatory. If unattended operation is needed, use RDP/virtual display and keep the real client.
5. **Do not hand-mine diamond blocks.** The user explicitly interrupted the previous AI and said, in Chinese, that diamond blocks must not be hand-mined. Mining them by hand is slow and produces no drop. Stop, ask for a proper pickaxe or explicit permission, or leave the block.
6. **For the current building exercise, only block-acquisition commands are allowed.** No `/tp`, `/fill`, `/setblock`, `/gamemode`, or other Minecraft commands. Movement must be real movement/pathfinding; construction must use placement/mining. `/give` is allowed only to obtain blocks.
7. **Do not use `/fill` or `/setblock` to fix the old buried structure.** The user wants real movement/place/mine behavior. If removal is required, ask first; do not silently use commands.
8. **Do not claim untested behavior works.** Mark implemented-but-not-accepted paths honestly.
9. Do not use `AGENTS.md` for memory; DSH auto-injects it. Agent memory lives in `D:\mc\.mcp-agent`.

## 1. Mission and current status

The project is a **NeoForge 1.21.1 client mod + Node MCP server** that lets an LLM play Minecraft through MCP tools on a real headed client:

- the mod exposes perception, actions, jobs, containers, chat, recipes, screenshots, and Baritone pathfinding over a loopback HTTP bridge;
- the MCP server exposes these as `mcp__mc__*` tools to DSH and supports non-blocking job semantics;
- discovery is via an instance file + heartbeat, not pid scanning; the user starts the client themselves with PCL.

The implementation is complete and has passed the main definition-of-done evidence. A correct new diamond structure has been built on an exposed flat plane at `x=7..9, z=0, y=100..102`; two old diamond blocks remain at `(1,99,0)` and `(-1,99,0)` and must not be hand-mined. (This document was written before the 2026-10-29 acceptance pass; see the addendum at the end for the fixes and new evidence.)

## 2. User preferences and working style

- The user values **real GUI/gameplay control**, not only direct world editing.
- Provide **evidence**: coordinates, tool results, log excerpts, screenshot paths. Do not answer with vague status claims.
- Prefer a concise phase-by-phase report; long raw JSON dumps are not useful.
- Ask **one concrete question with options and a recommendation** when the goal is genuinely ambiguous.
- The user has granted an **unlimited token budget**; do not optimize for turn count at the expense of correctness.
- The user previously required Chinese progress reports, but the current handoff explicitly requires English. Follow the English requirement for this handoff and subsequent replies unless the user changes it.
- Do not destroy items/blocks or change game mode without a clear request. The user is watching the game.

## 3. Environment facts

| Item | Value |
|---|---|
| Working directory | `D:\mc` |
| Project root | `D:\mc\MCP Development` |
| Modpack | `假期增援计划` (Mechanomania family, ~250 mods) |
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.228 |
| Java | Oracle JDK 21.0.8, `D:\java\jdk-21_windows-x64_bin\jdk-21.0.8` |
| Node | 22.19.0, `D:\Node\node.exe` |
| Client version folder | `D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划` |
| Target multiplayer server | `zmcp3.rhymc.com:1069` (read-only for this agent) |
| MCP server | `127.0.0.1:57610/mcp` |
| Current client bridge | Port changing; latest observed `51983` |
| HMC-Specifics bridge for the same PCL client | PID `38748`, port `53883` |
| Default instance dir | `C:\Users\ALIENWARE\AppData\Local\state\headlessmc-mcp\instances` |
| Test instance dir | `D:\mc\MCP Development\runtime\state\instances` |
| DSH profile patch | `C:\Users\ALIENWARE\.dsh\profiles\desktop\cordis.patch.yml` |
| Memory dir | `D:\mc\.mcp-agent` |

The user starts the client with PCL; do not launch a second client or replace it. Several earlier test clients are gone; only the current PCL client should be live.

## 4. What was delivered

### 4.1 NeoForge client mod

Source: `D:\mc\MCP Development\mod\src\main\java\dev\mcp\bridge\`

- 22 Java source files, 31 classes in the final jar.
- Final jar: `D:\mc\MCP Development\dist\mcpbridge-0.1.0.jar`, size **99,474 bytes**, SHA-256 **1DEDD0EA583B0B98142FCCC80B805FF7050E452FB79E3738DA0EF34532BB15F5** (2026-10-29 acceptance build; the earlier delivery, 96,290 bytes / `F07ED22FF29266CE09EAD45F4A7A87EE5FC8DD9A0A6A82A72724934DB0B2B974`, is kept in `runtime/backup/`).
- Installed at `...\versions\假期增援计划\mods\mcpbridge-0.1.0.jar`; hash matches `dist`.
- Only `META-INF/neoforge.mods.toml`; **no `fabric.mod.json`**. Client-only `@Mod(value = "mcpbridge", dist = Dist.CLIENT)`.
- Uses real LWJGL/rendering; no headless patch.
- Registers `FMLClientSetupEvent`, `ClientTickEvent.Post`, `ClientChatReceivedEvent`, `ClientPlayerNetworkEvent.LoggingOut`, `RenderGuiEvent.Post`.

Key classes:

| Class | Role |
|---|---|
| `McpBridge` | Client-only mod entry; event registration |
| `BridgeRuntime` | Startup/shutdown, volatile world cache, tick updates |
| `HttpBridge` | Loopback HTTP server, auth, `/health`, `/info`, `/command`, `/rpc`, jobs |
| `InstanceRegistry` | Atomic instance JSON, 5 s heartbeat, 20 s expiry, delete on close |
| `Tools` | 31-tool dispatch; sync tools schedule main thread and wait on futures |
| `Jobs` | Job state machine, deadlines, monotonic progress, cancel, failAll |
| `MainThread` | `Minecraft.execute` + `CompletableFuture` bridge |
| `TickScheduler` | Poll conditions/actions on client ticks |
| `GameState` | Player/world/screen/inventory snapshot |
| `Perception` | `scan_area`, block/entity/collision queries, size caps |
| `Containers` | open/read/move/close/trades/trade_select |
| `Actions` | mine/place/use/entity interaction |
| `BaritoneControl` | Reflection wrapper for Baritone 1.11.3 (`baritoe`) |
| `PathJob` | Pathing job + per-tick steering workaround |
| `ChatLog` | Chat history/commands, response cap |
| `Recipes`, `JeiRecipes` | Runtime RecipeManager + JEI reflection |
| `ScreenshotCapture` | Main-thread screenshot capture |
| `BridgeConfig` | State dir/JVM properties |
| `Errors`, `Json`, `MathUtil` | Structured errors and serialization |

Final hardening in this jar:

- `Jobs.prune()` fixed against an infinite loop when all jobs are non-terminal.
- `ChatLog.history()` capped to ~28 KB and long lines truncated.
- `BridgeRuntime.stop()` no longer references `ScreenshotCapture`/`BaritoneControl` from the shutdown hook. This fixed a real `NoClassDefFoundError` on JVM shutdown; final shutdown regression was clean.
- `contentOf()` in the MCP server tolerates undefined values.
- `place_block` occupancy check bug fixed.
- `/info` uses a volatile runtime cache so HTTP threads never read the world.
- `BaritoneControl` sets `freeLook=false`/`blockFreeLook=false` and `PathJob` steers the camera toward the next path node.

### 4.2 Baritone

- Official `baritone-api-neoforge-1.11.3.jar` in `D:\mc\MCP Development\vendor\baritone\`; also installed in the pack's `mods`.
- SHA-1 matches the official asset.
- Runtime mod id: `baritoe`; logs: `Baritone 1.11.3 (baritoe)` and `Successfully loaded Mixin Connector [baritone.launch.BaritoneMixinConnector]`.
- Known harmless conflict: `MixinVec3i`/`efficient_hashing` logs a skipped `hashCode` overwrite. Pathing still works.
- Pathing is API-driven (`BaritoneAPI` reflection), not Baritone chat commands. This is intentional for reliable status.

### 4.3 Node MCP server

Source: `D:\mc\MCP Development\mcp-server\src\server.js`

- `@modelcontextprotocol/sdk` 1.32.2, `zod` 3.25.76.
- Exposes **31 tools**; supports `streamable-http` (primary, port 57610) and `stdio` fallback.
- HTTP mode: `/mcp`, `/health`.
- Discovery: reads instance JSONs from the default state dir, probes `/info`, prefers `inWorld=true`; manual attach by instance file or `port + tokenFile`.
- `mc_instances`, `mc_attach`, `bridge_info`.
- Memory tools: `memory_list`, `memory_read`, `memory_write` (atomic, path-safe).
- `screenshot` returns a path and can return an inline image.
- Response text capped; no token in any tool result.
- `scripts/smoke-client.mjs` uses the official SDK client.

Important: On this machine, `StdioClientTransport` fails with **`spawn EPERM`** because the DSH sandbox kills Node child processes. `stdio` is implemented but is **not usable here**; use streamable-http. Evidence: `D:\mc\MCP Development\evidence\stdio.txt`.

### 4.4 DSH integration

DSH profile patch was appended (not replaced):

```yaml
- insert:
    - id: mc-control
      name: "@deepseek-ai/dsh-mcp-client"
      config:
        serverName: mc
        transport: streamable-http
        url: http://127.0.0.1:57610/mcp
        toolCallTimeoutMs: 600002
        failOnStartupError: false
        reconnect:
          enabled: true
          initialDelayMs: 500
          maxDelayMs: 30000
          maxAttempts: 10
```

Copy at `D:\mc\MCP Development\dsh\mc-control.patch.yml`. DSH hot-reloads the client after patch edits; tools appear as `mcp__mc__*`. If the MCP server is restarted, DSH may keep a stale session and return `unknown MCP session`; touching the patch config (e.g. change `toolCallTimeoutMs` by 1) forces a reload. This was verified.

### 4.5 Scripts and docs

| Path | Purpose |
|---|---|
| `build.ps1` | Direct `javac` build against 87 local NeoForge/library jars; no Gradle |
| `install.ps1` | Copy jar; optional Baritone install |
| `start-mcp.ps1` | Start MCP server; production default state if no `-StateDir` |
| `launch-client.mjs` | Node arg-array launcher for tests; non-ASCII-safe; filters `--demo` |
| `scripts/prepare-cp.ps1` | Build classpath in ASCII `vendor\cp` |
| `scripts/prepare-natives.ps1` | Extract exact `natives-windows` x64 LWJGL DLLs |
| `scripts/focus-game.ps1` | Window focus helper |
| `scripts/call.mjs`, `probe-bridge.mjs`, `state-brief.mjs`, `screenshot` helpers | Bridge diagnostics |
| `scripts/path-test.mjs`, `path-track.mjs`, `cancel-test.mjs` | Path tests |
| `scripts/container-test.mjs`, `block-test.mjs` | Container/block tests |
| `scripts/scan-size.mjs`, `recipe-check.mjs`, `token-check.mjs` | Perception/size/token checks |
| `scripts/loot-take.mjs` | Takes all chest stacks; used in the loot test |
| `scripts/creative-find*.mjs` | Creative inventory page-search experiments |
| `INSTALL.md` | Install/run/attach/production vs test state |
| `TOOLS.md` | Tool contract |
| `TROUBLESHOOTING.md` | Failure modes/fixes |
| `ACCEPTANCE.md` | DoD evidence table |
| `README.md` | Architecture, quick start, known limits |
| `../../evidence/` | Logs, sizes, recipes, screenshots, DSH/HMC evidence |

## 5. Protocol and tool surface

### 5.1 Instance registration

`<state>/headlessmc-mcp/instances/<uuid>.json` contains:

```json
{
  "protocol": 1,
  "port": 51983,
  "token": "<random>",
  "bridgeVersion": "0.1.0",
  "loader": "NeoForge",
  "mcVersion": "1.21.1",
  "gameDir": "<absolute path>",
  "started": 1793200000000,
  "heartbeatAt": 1793200000000
}
```

- Heartbeat every 5 s; expiry after 20 s.
- No pid-based discovery.
- Token is 32 random bytes, loopback only, never logged.
- Default state dir can be overridden by `-Dmcp.bridge.state=...`; PCL uses the default.
- HMC-Specifics writes a separate numeric `<pid>.json`; the MCP store ignores entries without our heartbeat/protocol.

### 5.2 HTTP endpoints

```
GET  /health                  -> 200 "ok" (no auth)
GET  /info                    -> bridge info (Bearer)
POST /command?timeout=<sec>   -> {output, timedOut, error} (Bearer)
POST /rpc                     -> {tool, args, timeoutSeconds} (Bearer)
GET  /jobs                    -> job list
GET  /jobs/<id>               -> job state
POST /jobs/<id>/cancel        -> cancel
```

All world access is scheduled onto the client main thread. HTTP handlers never touch the world.

### 5.3 MCP tools (31)

Perception/state: `get_state`, `scan_area`, `block_query`, `entity_query`, `collision_query`, `chat_history`, `recipe_lookup`, `screenshot`, `bridge_info`.

Chat/command: `chat`, `command`.

Movement/jobs: `path_to`, `path_status`, `path_cancel`, `job_status`, `job_cancel`.

Actions: `mine_block`, `place_block`, `use_block`, `entity_interact`.

Containers/trading: `container_open`, `container_read`, `container_move`, `container_close`, `villager_trades`, `trade_select`.

Memory: `memory_list`, `memory_read`, `memory_write`.

Discovery/attach: `mc_instances`, `mc_attach`.

Job states: `queued → running → done | failed | cancelled`, monotonic progress, terminal `reason`, per-call `timeoutSeconds`.

Error taxonomy: `timeout`, `disconnected`, `not_loaded`, `no_permission`, `unsupported`, `internal`.

## 6. Build, install, and run

### Build

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\build.ps1"
```

- Uses JDK 21; no Gradle download.
- Classpath: `D:\mc\MCP Development\vendor\cp` (87 jars).
- Output: `dist\mcpbridge-0.1.0.jar`.
- Verify no `fabric.mod.json`; metadata only `META-INF/neoforge.mods.toml`.

### Install

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\install.ps1" `
  -VersionDir "D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划" `
  -WithBaritone
```

Or copy the jar manually. The user must restart the client to load a new jar.

### Start MCP server

```powershell
powershell -ExecutionPolicy Bypass -File "D:\mc\MCP Development\start-mcp.ps1"
```

- Default state dir: `%LOCALAPPDATA%\state\headlessmc-mcp\instances`.
- Port 57610; health `http://127.0.0.1:57610/health`.
- If using a test client launched with `--state-dir D:\mc\MCP Development\runtime\state`, pass the same `-StateDir`.
- In a DSH sandbox shell, a `Start-Process` child may be killed when the parent command ends. Run from a normal PowerShell window, keep a parent job, or use Task Scheduler. The current server is running as a managed background job; if it dies, restart it.

### Start the client

Use PCL normally, selecting the `假期增援计划` version with the mods installed. Do not use the Node test launcher for the user's normal play; it exists for tests only. If PCL opens `DemoIntroScreen` or `PauseScreen`, resolve it (resume/focus) before expecting ticks/actions to run.

## 7. Evidence and acceptance status

Definition of done:

| Item | Result | Evidence |
|---|---|---|
| Startup log 0 `Caught exception during event` | PASS | `../../evidence/client-log-checks.txt` |
| Multiplayer `get_state`/`screenshot` | PASS; `zmcp3.rhymc.com:1069`, players=1 | `../../evidence/multiplayer.txt`, screenshots |
| `scan_area(radius=16)` < 32 KB | PASS: 22,986 bytes, 795 blocks | `../../evidence/scan16-final.json` |
| Container open/take/read-back | PASS: 63 slots, 64 stone round-trip | `../../evidence/container.txt` |
| `path_to` 100 blocks | PASS: progress 100, ~18 s | `../../evidence/path-100.txt` |
| `path_cancel` | PASS: cancelled, stopped | `../../evidence/path-100.txt` |
| Memory across restart | PASS: `acceptance.md` read after restarts | `D:\mc\.mcp-agent\acceptance.md` |
| DSH manual client attach | PASS: `mc_attach` by file and port+tokenFile | `../../evidence/dsh.txt` |
| Shutdown regression | PASS: 0 `bridge shutdown failed`/`NoClassDefFoundError`; instance deleted | `../../evidence/client-log-checks.txt` |
| Token leak check | PASS: `token_leaks 0` | `../../evidence/token-check.txt` |

Additional verified:
- `place_block`/`mine_block` jobs done, block read-back correct.
- `recipe_lookup` matched 3 vanilla + 3 modded outputs, including KubeJS virtual recipe `minecraft:kjs/create_andesite_alloy`.
- DSH lists 31 tools; `bridge_info`, `get_state`, `screenshot`, `mc_instances`, `mc_attach`, memory calls work.
- Loot chest test: 21 stacks moved from a `lootr:lootr_chest` into the player inventory; all `verified=true`; chest empty; closed.
- Current new diamond build verified by block query.

Implemented but not fully runtime-accepted:
- `villager_trades` / `trade_select` (no complete trade executed).
- `entity_interact(action=trade)`.
- `container_move` split/toSlot variants.
- `use_block` (door/button/lever) final form.
- `chat` sending (history verified; not sent on the server).
- Timeout/disconnect job fault injection.
- Complex terrain/steep turns; only a 100-block straight platform was accepted.

## 8. Current runtime state at handoff

Observed at approximately **2026-10-28 23:40 Asia/Shanghai**:

- Client: PCL-started, real headed, NeoForge 1.21.1; bridge `51983`, instance `3e432707-efda-4347-a562-356cf237f6db.json` under default state; HMC PID `38748`, port `53883`.
- **Screen: `net.minecraft.client.gui.screens.PauseScreen` is open.** The game is paused. `get_state` works, but path/place/mine ticks may not progress until the player resumes and the window has focus. First action if work is needed: ask the user to click “Back to Game”, or use HMC `key escape`, then keep the window foreground.
- Player: `FYNyesterday`, **survival**, singleplayer, peaceful, position `(0.77, 100, -2.27)`, on ground; 20 health/food; no active jobs.
- Main hand: soul slab; offhand: book. Inventory: 62 stone, 11 potato, 2 soul slabs, 2 emerald, 2 apple, 2 onion, 2 bread, 1 Apotheosis gem, 1 necklace, 1 tomato seeds, 1 recall potion; **0 diamond blocks**.
- **New correct build** at `x=7..9, z=0, y=100..102`, on an exposed stone plane (`y=99` solid, `y=100..102` air):
  - `(7,100,0)` diamond, `(8,100,0)` air, `(9,100,0)` diamond
  - `(7,101,0)` air, `(8,101,0)` diamond, `(9,101,0)` air
  - `(7,102,0)` air, `(8,102,0)` diamond, `(9,102,0)` air
  - This is a vertical x-y plane at `z=0`, facing the player; it is genuinely on the surface, not buried.
- **Old build** at `x=-1..1, z=0`, built earlier at the ground layer (`y=99..101`) and perceived as buried:
  - `(0,101,0)`, `(0,100,0)` are now air.
  - **`(1,99,0)` and `(-1,99,0)` are still diamond blocks** (verified 2026-10-29; the earlier text wrongly listed `(-1,99,0)` as air). Do not hand-mine them. The user stopped the hand-mining. Leave them, or ask for a pickaxe/permission before removal.
- The correct rebuild used only `/give` for block acquisition, `path_to` for movement, and `place_block`/`mine_block` for construction. The earlier buried version used `/fill`, `/setblock`, `/gamemode`, and `/tp` before the user imposed the no-command constraint. Do not reintroduce those commands for the current exercise.
- MCP server `127.0.0.1:57610` health `ok`, production default state; DSH `mcp__mc__*` connected. The client was discovered automatically after PCL started.

If the client or MCP server is no longer running when you resume:
1. Start PCL and enter a world.
2. Start `start-mcp.ps1` from a normal PowerShell (default state).
3. Confirm `http://127.0.0.1:57610/health`.
4. If DSH reports an unknown session, touch the profile patch to reload the MCP client; then call `mcp__mc__bridge_info`.
5. If `not_loaded`, wait 5–10 s for the heartbeat, check the client log for `MCP bridge listening on 127.0.0.1:<port>`, and ensure the same state dir.

## 9. What the next AI should do

The immediate task is finished; the user has not stated the next objective. **Do not invent a new large task and do not start destroying/placing blocks without a request.**

Reasonable next steps, in order:

1. Ask the user how to handle the remaining old diamond block at `(1,99,0)`:
   - leave it as an abandoned reference
   - obtain/use a proper pickaxe (ask permission first)
   - or leave it until the user decides.
   Do **not** hand-mine it.
2. If the user asks for more building, use the exposed plane/site and follow the no-command constraint. Verify target blocks are air and support is solid before placing. Use temporary support and remove it with the correct tool, not by hand-mining diamond blocks.
3. If the user asks to continue MCP acceptance, run the remaining optional tests one at a time (villager trades, use_block, container split, chat send, disconnect/timeout). Prefer singleplayer; never modify the target server.
4. If the user asks for cleanup, ask before deleting the test world, screenshots, memory, or instance state. Do not delete the user's actual pack/world.
5. The longer-term roadmap is in `D:\mc\MCP Development\Todolist.md`: finish the local full test gate first, then develop the server-side NeoForge mod using the existing Minecraft connection (Minecraft custom payload), with **no additional server ports**. Do not start the server-mod phase without the user's explicit approval and the server owner's permission.

## 10. Open risks and known caveats

- **Pause/focus:** Singleplayer pauses on focus loss. A path/action can appear stuck. Keep the client foreground; use RDP/virtual display for unattended play. `pauseOnLostFocus:false` did not reliably prevent this.
- **Baritone look:** This pack's Baritone look behavior does not rotate the player; `PathJob` steers every tick. Straight 100-block path works; complex terrain is not fully accepted.
- **Server:** First join to `zmcp3.rhymc.com:1069` once failed with a transient “Erreur de protocole réseau”; retry worked. Do not attribute that to the bridge.
- **stdio:** `StdioClientTransport` fails with `spawn EPERM` in this DSH sandbox. Use streamable-http.
- **MCP server process:** Started as a managed background job in the previous session; if the DSH app/session closes, it may stop. Restart from a normal PowerShell. `Start-Process` from a DSH sandbox shell can be reaped.
- **DSH session:** Restarting the MCP server can leave a stale MCP session; touch the patch to reload. `toolCallTimeoutMs` may be 600002 in the actual profile.
- **Tokens:** Instance JSONs contain bearer tokens. Never read/print them. `mc_instances` and all tool outputs are sanitized.
- **Test artifacts:** `../../runtime/state`, test screenshots, `mcp-test-world`, and the old HMC/test registrations may remain. They are not production data. Ask before deleting.
- **Game directory changes:** The previous AI added `mcpbridge-0.1.0.jar` and Baritone to the version mods, created/copied `mcp-test-world`, and edited the DSH profile patch. No server-side files were changed.
- **No hardcoded data:** Recipes come from `RecipeManager`/JEI at runtime; do not replace with static tables.

## 11. Important fixes and lessons (do not regress)

- LWJGL x86 DLL corruption came from matching `natives-windows` as a substring; extract only `:natives-windows$`. Correct `lwjgl.dll` is 479,232 bytes.
- PowerShell 5.1 `.Arguments` corrupted non-ASCII paths; use Node `spawn` with an argument array or `Path`/argument lists.
- Baritone pathing initially walked the wrong way; `configureForPathing()` plus per-tick `steer()` fixed it.
- `place_block` falsely failed after a successful placement because the occupancy check ran on the next tick; moved inside `if (!sent)`.
- `BridgeStore.listInstances` stored `_token` while `probe` read `token`, causing 401; fixed to `token: inst.token`; sanitized output unchanged.
- MCP selected a menu-only client; fixed by probing all live instances and preferring `info.inWorld=true`.
- HTTP threads read world state; fixed with a volatile runtime cache and main-thread scheduling.
- `Jobs.prune()` could spin forever when all jobs were non-terminal; fixed with a single scan.
- Shutdown hook referenced unloaded `ScreenshotCapture`, causing `NoClassDefFoundError`; final jar closes only HTTP/registry with per-step catches. Shutdown regression is clean.
- Creative inventory search experiments did not reliably filter the creative pages; direct commands were later authorized for block acquisition. Do not repeat the GUI-search detour unless asked.

## 12. File index

| Path | Contents |
|---|---|
| `D:\mc\MCP Development\README.md` | Overview, quick start, DSH setup, limitations |
| `D:\mc\MCP Development\docs\Ph2\INSTALL.md` | Install/run/attach; default vs test state |
| `D:\mc\MCP Development\docs\Ph2\TOOLS.md` | Tool contracts |
| `D:\mc\MCP Development\docs\Ph2\TROUBLESHOOTING.md` | Known failures/fixes |
| `D:\mc\MCP Development\docs\Ph2\ACCEPTANCE.md` | DoD evidence table |
| `D:\mc\MCP Development\Todolist.md` | Local full-test gate + server-mod roadmap; no extra ports |
| `D:\mc\MCP Development\dist\mcpbridge-0.1.0.jar` | Final mod jar |
| `D:\mc\MCP Development\mod\src\main\java\dev\mcp\bridge\` | Mod source |
| `D:\mc\MCP Development\mcp-server\src\server.js` | MCP server |
| `D:\mc\MCP Development\mcp-server\src\smoke-client.mjs` | SDK smoke client |
| `D:\mc\MCP Development\start-mcp.ps1` | Production MCP start |
| `D:\mc\MCP Development\install.ps1` | Install mod |
| `D:\mc\MCP Development\build.ps1` | Build |
| `D:\mc\MCP Development\dsh\mc-control.patch.yml` | DSH profile insert |
| `D:\mc\MCP Development\evidence\` | Runtime evidence |
| `D:\mc\MCP Development\runtime\state\screenshots\` | Test screenshots (test state) |
| `C:\Users\ALIENWARE\AppData\Local\state\headlessmc-mcp\screenshots\` | Current PCL bridge screenshots |
| `D:\mc\.mcp-agent\` | Memory files |

## 13. Do-not list

- Do not hand-mine diamond blocks.
- Do not use `/tp`, `/fill`, `/setblock`, `/gamemode`, or other commands in the current building exercise; only block acquisition is allowed.
- Do not use commands to “fix” the old buried structure.
- Do not modify the target server, its config, or its world.
- Do not ship or recommend a headless patch.
- Do not add `fabric.mod.json`.
- Do not print, log, or commit tokens, session tokens, or account credentials.
- Do not hardcode recipes/registries/loot tables.
- Do not claim optional tools passed when they were not runtime-accepted.
- Do not delete user game/world data or test artifacts without asking.
- Do not use `AGENTS.md` as agent memory.

## 14. PLANNED — surface-only perception and loot-chest search

**Status: not implemented. This is a design requirement for the next AI; do not claim it has passed before it exists and is tested.**

### 14.1 Feasibility verdict

Feasible as an **approximate reachable-open-space surface scan**. It is not a pixel-perfect “player eye render”, but it can reliably exclude fully buried blocks and expose walls, floors, doors, containers, and pits that are adjacent to open space connected to the player.

The recommended model is:

1. flood-fill the open/transparent/visibly passable cells reachable from the player's eye (with runtime classification; opaque non-full blocks are obstacles);
2. collect solid blocks adjacent to those cells (surface blocks);
3. derive a compact height map/pit list from the exposed top faces;
4. optionally add a slower raycast/FOV mode for stricter visibility.

This is a better cost/accuracy tradeoff than raycasting every block, and it works for cave rooms, village interiors, and closed doors.

### 14.2 Proposed tool: `scan_surface`

Keep the existing `scan_area` contract; add a separate tool rather than silently changing existing output.

Proposed arguments:

- `center` / `x,y,z` (default: player eye/block position)
- `radius`: `1..64`; start with default `32`, test `48`, only allow `64` after benchmark
- `mode`: `connected` (default), `los` (360° line-of-sight from the eye), or `fov` (current camera view cone)
- `fovDegrees`: default `70`; used only by `mode:"fov"`
- `faceSamples`: `1`, `5`, or `9` sample points per exposed face; default `5`
- `maxRaySteps`: default `64` per ray; cap total work
- `includeEntities`: default `true`
- `maxSurface`: default `4000`, max `8000`
- `maxVisitedCells`: default `250000`
- `timeBudgetMs`: default `1500`; return a clearly marked partial result if exceeded
- `heightRadius`: default `16`; limits heightMap/pits to useful local area

If the measured worst case exceeds the 3 s sync timeout, make `scan_surface` a **job** (`jobId`, `job_status`, `job_cancel`) instead of blocking the MCP call. Do not block the client main thread for unbounded work.

### 14.3 Algorithm

**A. Open-cell test (`isSightOpen`)**

Evaluate at runtime; do not hardcode block IDs:

- air or fluid;
- collision shape is empty (plants, torches), or the block is genuinely non-occluding/transparent (glass, many transparent blocks);
- **do not treat every non-full collision block as sight-open**: opaque slabs, stairs, fences, chests, and barrels are obstacles. Classify by runtime occlusion/render shape plus block entity/state, not by “collision is not a full cube” alone;
- **containers** (chest/barrel/shulker/Lootr) and **closed doors** are always opaque for the flood and are collected as surface/POI blocks; do not flood through them;
- optionally expose a config flag for an approximate “non-full blocks are open” mode, but do not make it the default;
- doors, trapdoors, fence gates: special-case `open` property:
  - closed → treat as a barrier (do not flood through it);
  - open → open for sight/flood.

Modded blocks may be ambiguous. Expose the classification as a small configurable predicate, and test glass, leaves, slabs, stairs, doors, trapdoors, and Create-style transparent blocks.

**B. Connected flood fill**

- Start at the player's **eye block**, not the feet.
- 6-neighbour BFS (6 directions; not 26) bounded by radius and world build height.
- Skip chunks without `level.hasChunkAt(pos)`; count them as unloaded; do not force-load.
- Maintain a bitset/byte grid of visited cells; stop at `maxVisitedCells` or `timeBudgetMs`.
- Only reached open cells count. A sealed room with no window/open door is invisible; a room with glass may be visible through the glass.

**C. Surface extraction**

For every reached open cell, inspect its six neighbours:

- if the neighbour is a loaded, non-open block, add it to `surface` and set the corresponding **face bit**;
- deduplicate by position and OR the face bits;
- include doors as surface blocks even though they block the flood when closed;
- include containers as surface blocks and also in the `containers` list;
- do **not** include fully buried blocks (no reached open neighbour).

Face bits:

```text
1 = up, 2 = down, 4 = north, 8 = south, 16 = west, 32 = east
```

**D. Height map and pits**

For each nearby column (`heightRadius`):

- choose the nearest exposed `up` face at or below the player's eye (the local floor);
- emit compact `[x, z, y, id]`;
- detect a `pit` when the local floor is at least 2 blocks below neighbouring columns, or when no floor exists within the vertical range;
- emit `[x, z, floorY, depth, id]`; use `depth:null`/`floorY:null` for a void/unknown bottom.

This is what lets the model avoid holes; it is approximate for multi-level rooms and ceilings.

**E. Eye-visibility filter (optional `los` / `fov`)**

This is the direct answer to “can visibility constrain the scan?”: **yes**, but it must be a second-stage filter over the connected surface candidates, not the primary flood.

- Build the connected surface set first, then test its exposed faces against the eye.
- Ray origin: `player.getEyePosition()`; ray target: one or more sample points on the exposed face.
- Use a bounded voxel raycast (DDA) or `ClipContext`/`level.clip` with a runtime opacity predicate:
  - transparent/fluid blocks are passed through;
  - the first opaque block hit decides occlusion;
  - the candidate is visible only if the target block is reached before any opaque blocker.
- Sample 1/5/9 points per face. A single center ray easily misses wide blocks and corners; 5 samples is the default; 9 is the strict mode.
- `mode:"los"`: test rays in all directions from the eye (360° horizontally, normal vertical range). It removes geometry hidden behind walls/pillars but keeps the surrounding room in memory.
- `mode:"fov"`: additionally require the ray direction to be inside the player's current yaw/pitch/FOV cone. This is true “current screen” visibility, but it is volatile: turning around changes the answer, and objects behind the player disappear.
- In strict mode, `surface` contains only visible entries; report `visibleSurfaceCount` and `occludedSurfaceCount`. Do not silently replace the connected map; navigation should still use a connected scan.
- Cap the number of candidate faces and total ray steps. If the cap is reached, set `truncated`/`truncatedReason`; prefer a job over blocking the main thread for large radii.

**Recommendation:** `connected` is the default for building a stable environment/entrance map. Use `los` for “what can the player see from this spot” and `fov` only when the user explicitly wants the current camera view. For the loot workflow, use connected for the door/entrance and `los`/`fov` for final confirmation of the chest.

### 14.4 Proposed JSON shape

Keep it compact and bounded; do not return every block with full properties.

```json
{
  "mode": "los",
  "center": [x, y, z],
  "eye": [x, y, z],
  "radius": 32,
  "surface": [[x, y, z, "minecraft:stone_bricks", 1], ...],
  "heightMap": [[x, z, y, "minecraft:grass_block"], ...],
  "pits": [[x, z, null, 4, "minecraft:air"], ...],
  "doors": [[x, y, z, "minecraft:oak_door", false, "east"], ...],
  "containers": [[x, y, z, "minecraft:chest"], ...],
  "structureHints": [{"type": "minecraft:village", "distance": 24}],
  "openCells": 12345,
  "surfaceCount": 934,
  "visibleSurfaceCount": 421,
  "occludedSurfaceCount": 513,
  "unloaded": 0,
  "truncated": false,
  "truncatedReason": null,
  "bytes": 28740
}
```

- `surface` uses a compact array and a face bitmask; expose full collision/properties only on request.
- In `los`/`fov`, `surface` contains only currently visible entries; `heightMap`, `pits`, `doors`, and `containers` still come from the connected candidate scan so navigation does not lose the known map. Report both visible and occluded counts.
- `heightMap` and `pits` are limited to `heightRadius` and capped separately.
- `doors` and `containers` are deduplicated and sorted by distance.
- Keep a hard ~29 KB effective cap; drop far/less useful entries first, set `truncated`/`truncatedReason`.
- If a future schema needs named fields, keep the old `scan_area` unchanged for compatibility.

### 14.5 Loot-chest search workflow

After `scan_surface` exists, the chest workflow becomes:

1. **Structure hint**
   - Prefer the client structure manager (`StructureManager`/`getStructureWithPieceAt`/related runtime APIs) for loaded chunks.
   - Fallback heuristic: surface blocks/tags/classes associated with villages (beds, bell, composter, workstations, crops, paths) and count them as `villageLikely`.
   - Do not scan chest contents to decide the structure; that is a later step.
   - If structure APIs are unavailable client-side, clearly return a heuristic confidence instead of pretending certainty.

2. **Find an entrance**
   - Use `doors` from a `connected` scan; choose a reachable exterior door.
   - `path_to` a point 1–2 blocks outside the door; do not path through the wall.
   - `use_block` the door; verify the `open` property changed.
   - If it is a double door, open the other half too.
   - Re-run `scan_surface` after opening; the interior should now become reachable.
   - A `los`/`fov` scan can confirm that the door is currently visible, but do not use a strict FOV result as the only door map.

3. **Enter**
   - `path_to` a safe interior point; use `heightMap`/`pits` from the connected scan to avoid holes.
   - If pathing fails, return the observed door/height map to the model; do not teleport or use commands.

4. **Scan for containers**
   - Prefer `containers` from the connected scan; filter by runtime block tags/classes, not hardcoded IDs.
   - Use `los`/`fov` only as a **confirmation** that the chest is currently visible; a chest behind a table/pillar is still a valid target for pathing.
   - `path_to` near the chest, `container_open`, `container_read`.
   - Take only what the user requests; verify with `container_read`; `container_close`.

5. **Report**
   - Include structure confidence, door coordinates/state, interior scan, chest coordinates, and job/path evidence.
   - If the chest is unreachable, report the obstacle/pit instead of claiming success.

The existing `path_to`, `use_block`, `container_open/read/move/close`, `job_status`, and `job_cancel` already provide the action primitives; the missing part is the surface scan and the structure/door policy.

### 14.6 Limitations and acceptance

Known limitations:

- connected flood is **not** exact player FOV; it may include blocks visible from another position in the same open space;
- strict `fov` is camera-view-only and volatile: turning around changes it; `los` is 360° from the eye and is usually a better “not occluded” constraint;
- strict visibility can hide a chest/pit behind furniture or an edge; keep the connected map for navigation and use `los`/`fov` for confirmation;
- ray tests cost `candidates × faceSamples × maxRaySteps`; use caps and, for large radii, a job;
- glass/transparent blocks can make a visually sealed room visible; closed doors are intentionally barriers;
- transparent/non-full classification for modded blocks is approximate;
- heightMap/pits are local and not a full physics/navigation model;
- radius 48/64 needs benchmarks and caps; do not block the main thread;
- client structure data is only as complete as loaded chunks; the future server-side mod can query authoritative server structures;
- no extra ports are involved; the server-side version must still reuse the Minecraft connection.

Acceptance tests for the plan:

- [ ] `connected` includes an exposed chest in a room; `los` sees it only when unoccluded; `fov` excludes it when behind the player.
- [ ] A block behind a pillar in the same room: `connected` includes, `los` excludes, `fov` applies the additional view cone.
- [ ] A room with a chest behind a closed door: connected surface sees the door, not the interior chest; after opening, the interior/chest appear.
- [ ] A fully buried chest is absent from `surface`; an exposed chest is present.
- [ ] Glass/window: interior is visible but the door remains a barrier until opened.
- [ ] Sealed room without openings: interior not scanned.
- [ ] `pits` and `heightMap` identify a 2+ block drop and a void hole; a pit bottom hidden by an edge is still found by `connected`, but marked occluded by strict `los`/`fov`.
- [ ] Surface scan radius 32/48 returns under the response cap, with `truncated` when capped.
- [ ] A complete village/room loot run: detect structure → path to door → open → enter → scan → path to chest → open → take → verify.
- [ ] No new server listening port in the test environment.

Do not start implementing this section until the user asks; it is a **plan** and must not be reported as done.

## 15. No-screenshot default perception policy

**Adopt this policy now; structured UI tools are only partially implemented.**

Screenshots are **evidence/diagnostics**, not the primary perception channel. The agent should not need a screenshot to read chat, inspect a container, understand a screen, find a block, or verify a build.

### 15.1 Structured equivalents

| Need | Use instead of a screenshot |
|---|---|
| Chat / command output | `chat_history`; command responses appear there |
| Current screen / pause / menu | `get_state.screen` (`class`, `open`, `menuClass`, `menuSlots`) |
| Container contents | `container_open` + `container_read` |
| Item counts / inventory | `get_state.inventory` / `container_read` |
| World terrain / walls / pits | `scan_area` / planned `scan_surface` (`surface`, `heightMap`, `pits`, `faceMask`) |
| Block identity/properties | `block_query` |
| Entities/players | `entity_query` |
| Recipes | `recipe_lookup` |
| Path/job state | `path_status` / `job_status` |
| Build verification | `block_query` / `scan_surface` coordinates |
| Creative item picker | `container_read` on `CreativeModeInventoryScreen$ItemPickerMenu`, or a structured UI tool |
| GUI buttons/fields | structured UI state + `ui_click` / `ui_key` / `ui_text` (planned); HMC `gui`/`click`/`key` is a fallback |

### 15.2 Missing pieces

- Add `screen_state` to expose screen class/title, buttons, text fields, slots, selected tab/page, and enabled state.
- Add generic `ui_click`, `ui_key`, and `ui_text` tools so modded GUIs do not require pixels.
- Add structured text reading for signs/books/lecterns if the user needs it.
- Never OCR a screenshot for chat or item counts when `chat_history`/`container_read` already exist.

### 15.3 Boundaries

- **Current gameplay/perception workflows can be screenshot-free.**
- **Purely visual tasks** (aesthetic judgement, texture/color questions, visual rendering bugs, map art) still need a screenshot or a human description; they are not game-state perception.
- A modded GUI that renders a custom canvas without exposing menu slots/widgets cannot be fully controlled structurally; adding a dedicated integration is better than using a screenshot as an action source.
- The server-side mod must be screenshot-free by design: it has no rendering. The client can use structured server results; the future FOV/LOS scan uses camera vectors, not images.

### 15.4 Acceptance

- [ ] Run one complete chat/container/loot/build workflow with **zero `screenshot` calls**; all decisions come from structured tools.
- [ ] `get_state.screen` + `screen_state` are sufficient to detect PauseScreen, container screens, and creative inventory tabs.
- [ ] `chat_history` is the only source used for chat.
- [ ] A screenshot, if produced for the user, is not consumed by the agent loop and is not required for success.
- [ ] Log/evidence shows no screenshot dependency in the no-screenshot mode.

Do not remove the existing `screenshot` tool; make it **optional** so the user can still request visual evidence.

If the user’s next request is unclear, ask **one** concrete question with options and a recommendation. Otherwise, act, verify with real tool output, and report the evidence and remaining risk.

**Unlimited token budget has been approved. There is no time limit for a single turn.**

Answer in English.

## 16. Addendum — 2026-10-29 acceptance pass

Written by the next AI after resuming on a rebooted machine (the reported `PauseScreen` state was gone; PCL's `LatestLaunch.bat` was used to relaunch, and HMC-Specifics' in-game `/command` bridge was used for GUI input because the desktop was locked).

Delivered build: `mcpbridge-0.1.0.jar`, 99,474 bytes, SHA-256 `1DEDD0EA583B0B98142FCCC80B805FF7050E452FB79E3738DA0EF34532BB15F5`.

Fixed in this build (all verified against a real server, except where noted):

1. `trade_select` was a false positive: it only called the client menu's `tryMoveItems`, so the server never received payment, while the client predicted `uses += 2` and the tool returned `verified=true`. Rewritten to send `ServerboundSelectTradePacket`, wait for server-side payment, perform one `PICKUP` per trade, store the result in the inventory, and verify by item counts. `times=1` and `times=2` both confirmed (uses 5→6 and 6→8).
2. `TickScheduler.tick()` drained tasks scheduled during the same tick, so `poll(60)` "timed out" in one tick and every tick-based read-back was effectively instantaneous. Now each tick drains only the tasks queued before it started. This affects `container_open/read/move`, `trade_select`, and any future polling.
3. `PathJob` treated the singleplayer pause screen as a Baritone stall and failed the job with `internal` after 2 s. Paused ticks are now ignored; logout still maps to `disconnected`. **Not re-tested at runtime** (the HMC bridge did not register on the final client launch, so no pause menu could be opened).
4. `use_block` reported `before` and `after` from the post-click state; `before` is now captured before the interaction.
5. `entity_interact(action=trade)` now waits up to 40 ticks for the `MerchantMenu` and returns `menuOpen/menuClass`.

New tooling in this repo: `mcp-server/src/mcp-call.mjs` (MCP-level tool caller), `mcp-server/src/no-screenshot-run.mjs` (scripted zero-screenshot workflow), `scripts/hmc-call.ps1` (in-game HMC-Specifics command bridge), `scripts/join-world.ps1` (filtered world join with a UUID guard), `scripts/token-audit.mjs` (token leak scan).

Caveats added: two old diamond blocks (`(1,99,0)`, `(-1,99,0)`), the HMC bridge occasionally not registering, a GLFW `swapBuffers` hang when disconnecting from multiplayer on a locked desktop, and merchant trades losing cursor items if the menu is closed abnormally.
