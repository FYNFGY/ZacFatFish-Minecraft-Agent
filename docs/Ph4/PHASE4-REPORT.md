# Ph4 - Phase 4 report (housekeeping) + final acceptance

Round: 2026-10-31, evening session. Build: `dist/mcpbridge-0.1.0.jar` (80 classes) installed into
the test instance; MCP server restarted with the new abort handling.

## 15. P1-11 - a user abort cancels the running MCP job (done)

Two client behaviours are handled in `mcp-server/src/server.js`:

1. **Request abort** - `extra.signal` from the MCP SDK: when the client cancels an in-flight tool
   call (`notifications/cancelled`), every job that this server started and that is still running is
   cancelled.
2. **Session drop** - some clients (DSH included) never send DELETE; they just close the SSE stream.
   A closed stream with no new request for 2 s is treated as the end of the session and cancels the
   same set of jobs (`[cancel-active] MCP session stream closed: 1/1 job(s) cancelled`).

Implementation details:
- `BridgeStore.activeJobs` tracks every `jobId` returned by the bridge; `job()` untracks terminal
  jobs and prunes entries older than 30 minutes; `cancel()` untracks immediately.
- `BridgeStore.cancelActiveJobs(reason)` cancels them in order and returns `{tracked, cancelled}`.
- The `stop_all` MCP tool now also cancels via this path and reports `cancelledViaMcp`.
- New tool `cancel_active` for an explicit agent-side cleanup.

Test (`mcp-server/test/p1-11-abort.mjs`, evidence `evidence/p1-11-abort.json`):

| scenario | method | result |
|---|---|---|
| sessionClose | start `walk(ticks=200)`, close the client 1.2 s later | job `cancelled` after ~3 s |
| requestAbort | raw JSON-RPC `scan_area` + `notifications/cancelled` while in flight | job `cancelled`; server log `[cancel-active] client aborted scan_area: 1/1 job(s) cancelled` |

## 16. Old drop/junk tools (done)

`drop_junk` is kept for compatibility but its description now starts with
`[DEPRECATED since Ph4: use delete_item (server-synced, verified)]`. `delete_item` remains the only
supported way to free slots (menu SWAP -> DROP_ALL_ITEMS -> verify empty -> run away, evidence
`ph4-p0-delete-item.json`).

## 17. Housekeeping scripts (verified unchanged and correct)

- `clean-start.ps1`: watchdog is opt-in (`-Watchdog`), the default start launches only MCP server,
  daemon and the client; it clears `runtime/watchdog.stop` on every start.
- `stop-all.ps1`: kills node/java/javaw and writes `runtime/watchdog.stop` (UTF-8 no BOM).
- `mcp-server/src/watchdog.mjs`: reads `runtime/watchdog.stop` (`STOP_FILE`) before relaunching, so
  a stopped session stays stopped until the next `clean-start.ps1`.

## Extra: P1-4 re-verified on the current build

The old `ph4-p2-stair-down-60-rock.json` was recorded before the `valuable()` narrow fix and ended
with `tool_low` at step 41. Re-ran on the current build from the surface at `1560,72,-440`:

- `evidence/ph4-p2-stair-down-60-r3.json`: `done`, **58 steps**, 177 blocks, y72 -> y13
  (ores seen: 4 zinc, 2 copper, 1 iron), no `blocked`, no `water_risk`.
- `evidence/ph4-p2-stair-down-60-r3b.json`: `done`, 1 more step to y=11 -> **59 steps / 61 blocks
  of descent**.
- An earlier leg (`ph4-p2-stair-down-60-r2.json`) descended 50 steps and stopped with `water_risk`
  at an aquifer - the guard works, and no `blocked` was returned.

## Ph4 acceptance summary

| phase | acceptance | evidence |
|---|---|---|
| P0-1 ghosts | 6 tunnel legs, 0 `ghost_block` | `ph4-p0-tunnel.json`, `ph4-p0-ghost-walk.json` |
| P1-8 watchdog | `stalled` in 3.7 s | `ph4-p0-stalled.json` |
| P1-5 tool policy | `tool_low` with 5 uses left, block intact | `ph4-p0-tool-low.json` |
| P1-6 delete | frees 1 slot, verified empty twice | `ph4-p0-delete-item.json` |
| P1-1 scan | doors pass, wall hides the room | `ph4-p1-scan-connected.json` |
| P1-1/P1-2 ore probe | hidden diamond reported and mined | `ph4-p1-ore-probe.json` |
| P1-7 eye | triangulation error 2.24 blocks | `ph4-p1-eye-protocol.json` |
| P1-3 stair up | 28-block climb + 3 round trips, 0 ghosts | `ph4-p2-stair-up-1.json`, `ph4-p2-stair-roundtrips.json` |
| P1-4 stair down | 59 steps / 61 blocks, `done` | `ph4-p2-stair-down-60-r3.json`, `-r3b.json` |
| P2-1 tunnel / CubeTest | pass, ratio < 3x, 0 ghosts | `ph4-p3-cubetest.json` |
| TestB v2 | diamond, zero commands, return | `testb3-*`, `testb4-verdict.json` |
| P1-11 abort | session close + request abort both cancel | `p1-11-abort.json` |
