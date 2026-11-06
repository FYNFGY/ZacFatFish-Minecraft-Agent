# Ph3 — Minecraft MCP Agent, Round 3

This folder is the hand-off package for the next development round of the Minecraft MCP agent.
It documents everything that went wrong during the live survival session on **2026-10-29 23:19 →
2026-10-30 00:15**, the new capabilities the human asked for, the technical design, the acceptance
plan, a seed experience base, and the consolidated prompt for the next agent.

## Read order

1. **`agent-prompt3.md`** — the master prompt. Read this first.
2. `ISSUES.md` — every problem observed in the session (English), with root causes and required fixes.
3. `FEATURES.md` — the new features to build (pathfinding A/B, HUD stream, `stop_all`, container
   verification, water subsystem, crash watchdog, experience base, latency rules).
4. `ARCHITECTURE.md` — how to build it (jobs, events, tools, protocols).
5. `ACCEPTANCE.md` — measurable acceptance tests and required evidence.
6. `EXPERIENCE.md` — seed "Minecraft common sense" notes and how to grow the file.
7. `TASKS.md` — the same work as a tickable checklist (phases 0–4).
8. `ROUND3-REPORT.md` — what was implemented in round 3, measured timings, bugs fixed, evidence.

## Round-3 status (2026-10-30)

Implemented and live-tested: `stop_all`, mining executor v2 (`BlockMiner`/`MineExecutor`, per-block
verification, reason codes), `dig_shaft` (stair guard), `branch_mine` (fishbone with torch pass and
cave-walk), `break_entity`, `collect_drops`, `throw_item`/`track_entity`/`place_boat`/`boat_sail`,
`light_map`/`torch_at`, `inventory_swap`/`hotbar`, `resync`, container verification, compact
`get_state`, plus two new services: **`mc-daemon`** (persistent MCP session, 5–12 ms/call) and
**`watchdog`** (client crash recovery: detected and relaunched in 51 s, world back in 108 s).

See `ROUND3-REPORT.md` for numbers and evidence. Not yet live-tested: `break_entity` on a real frame,
`boat_sail`, `throw_item` (no boat/eye/water at the test spot), `dig_shaft` at depth,
`inspect_container`, door-preferring pathing.

## Session workflow (human rule, 2026-10-30)

1. **Smoke test first** — build, install, launch, pin, `get_state`, one short job, `stop_all`,
   screenshot. Fix any failure before anything else.
2. Run the acceptance suite (T1–T16 + regression).
3. Present evidence and **wait for the human's explicit authorization** before Test A / Test B.
4. Only then run the two world-level final tests (T17/T18).

## Session facts (for context)

- World: `mcp-test-world`, survival, hosted on LAN by the agent's client.
- Agent account: `DeepSeek` (offline launch script `PCL\DeepSeekLaunch.bat`); human: `FYNyesterday`.
- Bridge build during the session: `mcpbridge-0.1.0.jar`, 143,489 bytes, 42 MCP tools.
- Cheats are **disabled** in this save (`/tp` was rejected twice with "Commande inconnue").
- The world is heavily modded (NeoForge 1.21.1 "Mechanomania / 假期增援计划"): Create, Apotheosis,
  Lootr, SimpleTomb, Touhou Little Maid, SlashBlade, Distant Horizons, c2me, etc.

## Status of the Ph2 tooling at the end of the session

Working: `get_state`, `scan_area`, `scan_surface`, `batch`, `parallel`, `damage_log`,
`watch_damage`, `auto_respawn`, `tower`, `walk` (sprint/up), `loot_chest`, `craft` (recipe book +
JEI-style fallback), `wait_events`, `container_*`, `entity_query`, `block_query`, `path_*`,
`mine_block`, `place_block`, `use_block`, `screenshot`, `chat`, `command`.

The problems in `ISSUES.md` are about correctness, latency, and robustness — not about missing
basic tools.
