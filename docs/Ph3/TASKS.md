# Ph3 — Task Checklist (English)

Work top-down. Each task lists the issue/feature IDs it closes. Tick a task only after its
acceptance test passes and evidence is saved.

## Day start — smoke test (before anything else)
- [x] S0.1 Build + install the jar; restart the client; pin the MCP instance
- [x] S0.2 `get_state` returns the right player; one short `walk` job (20 ticks); `stop_all` cancels it
- [x] S0.3 Screenshot saved; if any of these fail, fix before continuing

## Phase 0 — Safety net
- [x] T0.1 `stop_all` tool: cancels all jobs, Baritone, scheduler, mining state (F1)
- [x] T0.2 Job cancellation flag + `abort()` on every `TickTask` (F1)
- [x] T0.3 `stop` event in the journal; subagent calls `stop_all` on stop messages (F1, F10)
- [x] T0.4 Agent policy: short actions, status checks only, no blocking waits (P9, F10)
- [x] T0.5 Acceptance T1 + T11 pass

## Phase 1 — Mining & containers
- [x] T1.1 MiningExecutor: start once + continue per tick + verify per block (P2, F2)
- [x] T1.2 Hotbar tool selection + durability check + `tool_broken` abort (P3, F2)
- [x] T1.3 Per-op results with reason codes; batch stops on hard failure (P2, F2)
- [x] T1.4 Container open/move server round-trip verification (P4, F4)
- [x] T1.5 `resync` tool (client ↔ server inventory repair) (P4, F4)
- [x] T1.6 Acceptance T2 + T5 pass

## Phase 2 — Navigation
- [x] T2.1 `dig_tunnel` with fluid checks + exit plan (P8, F3.A)
- [x] T2.2 `bridge_route` planning: waypoints → block plan, material priority, budget (P8, F3.B)
- [x] T2.3 `bridge_route` execution: tower, side-bridge, stop at target, no over-building (P8, F3.B)
- [x] T2.4 `climb_out` wrapper (surface detection, choose route) (P8, F3)
- [x] T2.5a Path rules: doors/gates auto-open (`path_open`, tested live) (P14, F3.C)
- [ ] T2.5b Farmland avoidance (Baritone still owns the route; documented limitation)
- [x] T2.6 `branch_mine` (fishbone) job with spacing/length/level and ore report (F3.D)
- [x] T2.7 Downward-dig rules: staircase/1×2 access shafts, 1×1 refused by default (F3.E)
- [x] T2.8 Light probe + `light_map` + `torch_up` + lighting pass in dig plans (F3.F)
- [ ] T2.9 Acceptance T3 + T4 + T13 + T14 + T15 + T16 pass

## Phase 3 — Awareness & resilience
- [x] T3.1 PlayerWatch: HUD snapshot + batched `hud` events (P10, F6)
- [x] T3.2 Inventory ledger + batched `inv` events + noise filter (P10, F6)
- [x] T3.3 Priority thresholds for the subagent (food/health/air/tool) (P10, F6)
- [x] T3.4 FluidState: source/flowing/waterlogged + auto-escape (P6, F5)
- [x] T3.5 Swim with real jump input; verify Y progress (P6, F5)
- [x] T3.6 Crash watchdog: detect, capture logs, restart, rejoin, report (P7, F7)
- [ ] T3.7 Acceptance T6 + T7 + T8 pass

## Phase 4 — Knowledge & polish
- [ ] T4.1 `inspect_container` (frames, furnaces, chests, signs, armour stands) (P11, F8)
- [ ] T4.2 Experience base read/append workflow (P12, F9)
- [x] T4.3 Compact `get_state` + small outputs + latency budget (P13, F10)
- [ ] T4.4 Acceptance T9 + T10 + T11 pass

## Final
- [ ] T5.1 Regression T12 pass (all Ph2 tools)
- [ ] T5.2 `evidence/ph3-acceptance.json` complete
- [ ] T5.3 `EXPERIENCE.md` updated with this round's lessons
- [ ] T5.4 Human confirms responsiveness in a live session

## World-level tests (mandatory, authorization-gated)
- [ ] T6.0 **Request authorization**: present smoke-test + acceptance evidence, wait for the human's
      explicit go-ahead. Do not start T6.2/T6.4 before this.
- [ ] T6.1 Test A tooling: `throw_item`, `track_entity`, `triangulate`, `scan_blocks`, `path_follow`,
      frame `use_block` (F11)
- [ ] T6.1b Boat crossing tooling: detect water, craft boat (5 planks), place, mount, steer, dismount,
      **recover the boat** via `break_entity` (F3.G)
- [ ] T6.1c `break_entity` + `collect_drops` for boats, minecarts, item frames, paintings, armour
      stands (F11, T19)
- [ ] T6.2 Test A: Stronghold run in a fresh world — End portal lit, no death, single `/give` only
- [ ] T6.3 Test B tooling: `fluid_probe`, `scan_ores`, `seal_block`, surface return (F11)
- [ ] T6.4 Test B: Fishbone diamond in a fresh world — ≥1 diamond, no fluid breach, darkCells=0
- [ ] T6.5 Evidence in `evidence/ph3-test-a/` and `evidence/ph3-test-b/`; lessons in `EXPERIENCE.md`

