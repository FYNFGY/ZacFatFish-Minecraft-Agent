# Ph4 - Architecture and code map

## Repository layout

```
D:\mc\MCP Development\
  mod\                      NeoForge 1.21.1 client mod (the "bridge")
    src\main\java\dev\mcp\bridge\*.java
  mcp-server\src\           Node MCP server + helpers
    server.js               MCP tools (zod schemas) -> store.rpc()
    mc-daemon.mjs           long-lived MCP session, HTTP :57611 /call, /batch, /health
    watchdog.mjs            crash detection + relaunch (opt-in, stop file)
    mcp-call.mjs, *.mjs     scripted experiments (rover*, hiker, volcano, eye*, ...)
  scripts\                  mc.ps1 wrapper, round3-tests*.ps1
  build.ps1                 javac + jar -> dist\mcpbridge-0.1.0.jar
  install.ps1               copies the jar into the game's mods folder
  start-mcp.ps1             node server.js --http --port 57610
  clean-start.ps1           kill all node/java, (optionally build+install), start MCP+daemon(+watchdog), start client
  stop-all.ps1              kill all node/java + write runtime\watchdog.stop
  docs\Ph1..Ph4\            reports
  evidence\                 JSON + PNG evidence
  runtime\                  logs, helper scripts, watchdog.stop
  vendor\cp\                compile classpath (NeoForge + mod jars)
```

## Java classes (mod/src/main/java/dev/mcp/bridge)

| Class | Role |
|---|---|
| `Tools` | tool dispatch (`case "x" -> ...`), job creation |
| `Jobs` | job manager: `Job`, `TickTask`, metrics/result/progress, `cancelTool`, `cancelAll` |
| `BridgeRuntime` | main loop: ticks jobs every client tick, heartbeat, HTTP endpoint |
| `Actions` | faceBlock (aim lock), selectSlot, findItemSlot, useBlock, placeBlock, mine helpers |
| `Mining` | reach, `bestToolSlot`, `quickestSlot`, `fetchToolToHotbar`, `tooWorn`, `maxTicks`, `changed`, ore summaries |
| `BlockMiner` | single-block state machine: aim -> start -> continue -> verify; strict/wrong-tool guard; records `brokenId` |
| `MineExecutor` | `mine_block` job over a list of positions, reports `ores` |
| `DigJob` | STAIR / SHAFT / UP / DESCEND / SPIRAL digging, hop logic, water guard, step placement |
| `BranchMineJob` | fishbone mining (corridor + branches + torches), reports `ores` |
| `TowerJob` | pillar up, headroom clearing, centering, hard-ceiling guard |
| `WalkToJob` | short direct walker, jumps 1-block steps, plants clearing, stuck fail |
| `PathJob` | Baritone `path_to` wrapper, steering, door/trapdoor opening, vegetation clearing |
| `MlgWaterJob` | walk off an edge, place water just before impact |
| `DumpJob` | junk dumping: drop a stack (server-synced), run away so it is not re-picked |
| `Containers` / `InventoryActions` / `ItemActions` / `EntityActions` / `LightTools` | containers, inventory, items, entities, light |
| `Perception` / `GameState` | block info (fluid, light, hardness), compact player state |
| `Errors` | error codes (`wrong_tool`, `tool_broken`, `blocked`, `water_risk`, `no_progress`, ...) |

## Runtime protocol

- MCP server: `node mcp-server/src/server.js --http --port 57610` (streamable HTTP).
- Daemon: `node mcp-server/src/mc-daemon.mjs` -> `POST http://127.0.0.1:57611/call {tool,args,timeoutMs}`.
- The mod opens a random loopback HTTP port with a Bearer token; the instance file
  `%LOCALAPPDATA%\state\headlessmc-mcp\instances\<uuid>.json` holds `port`, `token`, `heartbeatAt`.
- The daemon caches one MCP session -> 5-12 ms per tool call (vs ~400 ms for a fresh CLI session).
- Heartbeat every ~5 s; the instance is "live" only if `heartbeatAt` is fresh (<45 s).

## Build / restart workflow

```powershell
cd 'D:\mc\MCP Development'
# java files MUST be UTF-8 without BOM
.\build.ps1                       # javac -> dist\mcpbridge-0.1.0.jar
.\install.ps1 -VersionDir 'D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划'
.\clean-start.ps1                 # kills old node/java, starts MCP + daemon + client
.\clean-start.ps1 -Build          # same + build + install
.\stop-all.ps1                    # kill everything, no relaunch
```

- `clean-start.ps1` does **not** start the watchdog unless `-Watchdog`.
- `runtime\watchdog.stop` present => the watchdog exits instead of relaunching the game.
- The client is launched with `D:\SoftWare\PCL 正式版 2.12.2\PCL\DeepSeekSmoke.bat` (world "Smoke Test",
  folder `Nouveau monde (1)`). `DeepSeekLaunch.bat` opens a different world - do not mix them.

## Tool limits / gotchas

- `block_query`: max **64** positions per call (chunk larger scans).
- `scan_area`: `radius` 1..32, `maxBlocks` <= 6000; it is an X-ray today (see ISSUES P1-1).
- `mine_block.positions`: arrays of exactly **3 ints**.
- `dig_shaft.mode` values: `stair|shaft|up|down|spiral`; `down` currently maps to the broken vertical path.
- `screenshot` returns a PNG path (2560x1600).
- Java source must be **UTF-8 without BOM** or `javac` fails on `\ufeff`.
- PowerShell: write files with `[System.IO.File]::WriteAllText(path, text, UTF8Encoding($false))` to avoid BOM.
- Chinese paths must be written from a BOM-ed `.ps1` or they get mangled.
- Never edit `ItemStack` directly client-side (it desyncs the inventory).

## Worlds

- "Smoke Test" folder: `...\saves\Nouveau monde (1)` - the test world with cheats enabled.
- `mcp-test-world` / "Nouveau monde": the Ph3 beach/cave world (different).
