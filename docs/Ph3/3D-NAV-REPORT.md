# 3D Navigation — Test Report (2026-10-30, Smoke Test world)

Goal: reliable, coherent 3D pathfinding in complex terrain (mountain slopes, cliffs, ridges), with
step-by-step planning and immediate awareness of "no progress". Cheats were enabled in this world, so a
test kit (diamond pickaxe, dirt, torches, bread) was granted with `/give`.

## What was built during this session

| Piece | Purpose | State |
|---|---|---|
| `WalkToJob` / `walk_to` | Short direct walker toward (x,z): sets yaw+velocity, hops 1-block steps, fails `no_progress` when it stops moving | new, works |
| `TowerJob` headroom clearing | Before jumping, mines up to 3 blocks above the head, so the pillar placement window is actually reached | fixed, works |
| `DigJob mode=down` / `descend` | Controlled 1-block-at-a-time descent: probes the block below and the landing, refuses fluid/holes | new, works |
| `DigJob mode=up` | Staircase climb (mine step+headroom, hop) | drafted; the hop is unreliable on cliff edges |
| `rover*.mjs`, `hiker.mjs` | Prototype planners (column probes, height-map A* / contour legs) | prototypes, unstable on steep peaks |

## Results

| Test | Result |
|---|---|
| Baritone long path to the mountain | walked ~170 blocks and climbed y=92 → 193, then stuck at a 26-block vertical cliff |
| Contour traverse at constant height | `path_to` walked (1878,236,1424) → (1866,236,1434) — clean |
| Pillar up (tower, 40 blocks) | **worked**: y=186 → 226, then `path_to` reached the target **(1888,219,1416), dist 0.6** |
| Controlled descent | `mode=down` 5 blocks: done (4 steps, 5 blocks mined) |
| `walk_to` | moves reliably in short legs (moved 0.8–10 blocks per call) |
| Local planners on the peak | oscillated: waypoints re-selected around the same spot, several legs `no_progress` |
| **Safety** | **2 fall deaths** during walk-off fallbacks: (1892,208,1416) and (1866,172,1426) — 20 damage, respawn at spawn with a grave key |

## Analysis

1. **Baritone is a walking pathfinder.** It follows terrain, climbs one-block steps and is happy on
   ridges/slopes, but it cannot climb a vertical wall. Getting stuck at the cliff base was expected;
   the *recovery* is what needs work.
2. **The pillar is safe but the human explicitly does not want it as the default** ("don't build a
   skyscraper from flat ground") — it should only be a last resort and short.
3. **Local planners oscillate** because the candidate scoring is greedy: with a mountain in the way,
   the "closest reachable cell" can be behind the player, so it walks back and forth. A planner needs
   a global-ish height map and a route that follows a contour (switchbacks), plus a memory of visited
   cells.
4. **The most damaging failure is the fallback, not the pathing**: "walk off the ledge" killed the
   character twice. Every movement primitive must include a *fall guard* (stop when the drop ahead is
   more than 2 blocks) and the planner must never choose a step whose surface is far below.
5. **Awareness works**: the damage journal caught both falls with coordinates; the character always
   ended at the spawn with a grave key (SimpleTomb).

## Next steps (planned, not done)

1. **Fall guard** in `walk_to`: look one block ahead; if the surface drops > 2, brake and report
   `drop_ahead` instead of walking off.
2. **Hiker v2**: every leg probes the waypoint column and uses the real surface Y (partially done);
   prefer constant-height legs; climb only when the slope allows (+1); never use walk-off; small tower
   (≤3) only when a 1–2 block step is missing *and* the target is above.
3. **Global height map**: build a coarse height map of the loaded area (chunk-sampled), run A* on it
   for the route, then execute short legs along the route with the fall-guarded primitives.
4. **Acceptance**: three routes (ridge, cliff, cave exit) × three runs each — no death, no stuck
   longer than 10 s, and the agent reports `drop_ahead`/`blocked` instead of falling.
