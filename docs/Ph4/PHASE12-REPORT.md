# Ph4 Phase 1 + Phase 2 report

Date: 2026-10-31 (session 1, continued)
Build: `mcpbridge-0.1.0.jar` (80 classes), all sources UTF-8 without BOM.

## Phase 1 - perception

| Item | Result | Evidence |
|---|---|---|
| P1-1 `scan_connected` (no X-ray through walls) | **PASS** - two rooms joined by an oak door: the far room's red-wool floor was returned; the room behind a solid wall (blue wool) was not. Plugging the door with stone removed the far room from the result. | `evidence/ph4-p1-scan-connected.json`, `evidence/ph4-p1-scan-nodoor.json` |
| P1-2 ore probe stream | **PASS** - a diamond ore 2 cells behind the tunnel wall was invisible to `scan_connected`; mining the first wall block reported only stone (`ore_log` total 0), mining the second reported `diamond_ore` at `1435,61,-515`; the ore was mined with an iron pickaxe and the diamond collected (1 diamond in the inventory). | `evidence/ph4-p1-ore-probe.json` |
| P1-7 ender-eye protocol | **PASS** - throw 1 from `1553.5,-468.5` (bearing 73.22 deg, eye broke = vanilla 20 %), throw 2 from `1538.52,-512.53` 46.5 blocks away (bearing 71.58 deg, dropped eye picked back up). Triangulation: `(-2, 1)`; `/locate structure minecraft:stronghold` = `(0, ~, 0)` -> **error 2.24 blocks** (< 30). | `evidence/ph4-p1-eye-throw1.json`, `ph4-p1-eye-throw2.json`, `ph4-p1-eye-protocol.json` |

Tooling added: `scan_connected`, `ore_log` (ore probe stream), `eye_throw` (4-tick capture, 80-tick
tracking, pickup with a 20-block search), `triangulate` fixed for near-parallel rays (long-distance
targets), `set_pathing_policy` (P1-10: allowBreak/allowPlace, default both off).

## Phase 2 - movement primitives

| Item | Result | Evidence |
|---|---|---|
| P1-3 stair up | **PASS** - 28 blocks up a 1-wide staircase in solid stone, 27 steps, 84 blocks mined; then 3 round trips with `walk_to` (6/6 legs, ~175 ticks each, y 61 <-> 88, zero ghost events). | `evidence/ph4-p2-stair-up-1.json`, `evidence/ph4-p2-stair-roundtrips.json` |
| P1-4 stair down | **PASS in solid rock** - 60 blocks (y 92 -> 50), 41 steps, one step = one block of drop after the "only count a step when the player really dropped" fix. **Weak on sloped terrain**: the first surface attempt wandered uphill and ended `blocked` (see below). | `evidence/ph4-p2-stair-down-60-rock.json`, `evidence/ph4-p2-stair-down-60.json` |
| P2-1 `dig_tunnel` A->B + CubeTest | **PASS** - 24^3 stone cube (force-loaded + filled), entrance `1697,99,-690` to target `1712,99,-690`: 26 blocks mined, length 15, 1 torch, walked back to the start, then `walk_to` in and out (63 / 60 ticks). Path ratio 1.0 (< 3x), zero ghost events. | `evidence/ph4-p3-cubetest.json`, `evidence/ph4-p2-cubetest-tunnel.json` |
| P1-10 pathing policy toggles | implemented (`set_pathing_policy`, per-job `allowBreak`/`allowPlace`); `walk_to` no longer clears plants when `allowBreak=false` | - |

### Bugs found and fixed while testing

1. **Stair step accounting**: a step was counted when the player moved 0.85 blocks *inside the same
   cell*, so a descent could "advance" without going down and wander uphill. Now a step only counts
   when `feetY < startY`; otherwise it retries and fails with `no_progress` after 3 attempts.
2. **Stair up never reached the step**: the old loop walked toward the *centre* of a solid step
   (unreachable). It now walks to the face (0.82 blocks from the centre) and jumps.
3. **`dig_tunnel` counted 0 mined blocks**: the "already air" check ran before the miner's 3-tick
   confirmation, discarding the DONE outcome. The air check now only applies before a miner starts.
4. **Tool policy**: `tool_low` fired on *every* `requiresCorrectToolForDrops` block (stone!), and on
   a nearly broken stone pickaxe it blocked ordinary mining. Now only ores / hardness >= 10 are
   valuable, only iron+ tools are protected, and a healthy spare in the bag is used instead of
   failing.
5. **Empty hand could not mine** (hand-cut wood failed with `tool_broken`) - an empty hand is now a
   valid "no tool" and mines slowly.
6. **Scan byte cap** trimmed the `surface` array to zero before touching `heightMap`; it now keeps a
   minimum of 200 surface + 100 height-map entries.

### Known limitation

The 45-degree stair is reliable inside solid rock but cannot cross a cliff edge: when the block below
the next step is air in every direction the job stops with `blocked`. The plan's "dynamic re-planning"
(dig a horizontal approach, or place a support block) is not implemented yet; for TestB the descent is
started inside a slope and stays in rock.
