# Ph4 - README

Working directory: `D:\mc\MCP Development`

Ph3 produced a large MCP bridge for Minecraft (NeoForge 1.21.1) with ~58 tools, a job system and
several pathing/mining primitives. Test A (stronghold + End portal) and Test B (survival diamond) are
both **failed** (see `TESTAB-FAILED.md`). Ph4 is about fixing the foundations before re-running them.

## Files in this folder

| File | Content |
|---|---|
| `TESTAB-FAILED.md` | verdict of Test A / Test B and what worked |
| `ISSUES.md` | the full issue register (P0-P2) with root causes and proposed fixes |
| `PLAN.md` | the ordered development plan with acceptance criteria |
| `agent-prompt4.md` | onboarding prompt for the next AI (environment, architecture, rules) |
| `EXPERIENCE.md` | practical gameplay guide: what to bring, how to mine, how not to die |
| `ARCHITECTURE.md` | code map and runtime layout |

## Where the evidence lives

- `D:\mc\MCP Development\evidence\` - JSON results + screenshots of every run (Ph3).
- `D:\mc\MCP Development\docs\Ph3\` - Ph3 reports (design, acceptance, issues, feature list).
- `D:\mc\MCP Development\runtime\` - logs (`build*.log`, `daemon*.log`, `watchdog*.log`), scripts.

## The one-sentence lesson from Ph3

> The bridge can drive the client, but it does not yet **know** the world: it cannot see walls, it
> breaks blocks the server may not agree on, and it cannot notice that it is stuck. Ph4 is about giving
> it eyes (connected scan + ore probes), safe hands (ghost-free mining, synced inventory) and legs
> (tunnel pathing, reliable stairs).

## Ph4 status after the fix round (2026-10-31)

- Phase 0-3 acceptance: PASS (see PHASE0-REPORT, PHASE12-REPORT, PHASE4-REPORT, TESTB4-RESULT).
- Test A: the stronghold was located at (0,0) with eyes of ender, but it lies under the ocean floor,
  so it is marked **做不到 (not feasible)** with the current primitives - see `TESTA-RESULT.md`.
- Fixes delivered this round: save-before-restart (`save_world`), plant clearing, `place_boat` /
  `boat_sail` - see `FIXES-2026-10-31.md`.
