# Ph4 - Test A / Test B verdict and hand-off

Date: 2026-10-31 (written right after the end of the Ph3 run).

## Verdict

| Test | Verdict | One-line reason |
|---|---|---|
| **Test A** - find the stronghold and activate the End portal (no `/locate`, only eyes of ender) | **NOT COMPLETED** | The stronghold was never reached. Ender-eye throwing was unreliable (the projectile could not be tracked reliably, eyes were re-thrown from the same spot, dropped eyes were not picked up) and the run ended with the player still ~hundreds of blocks from any stronghold. |
| **Test B** - peaceful survival, fishbone mine at least one diamond | **NOT ACCEPTED (declared failed)** | A diamond *was* picked up once (`deepslate_diamond_ore` mined with a self-crafted iron pickaxe), but the run depended on two `/tp` recoveries, several tool/inventory failures and could not be reproduced cleanly. Per the user's decision this does not count. |

Both tests are therefore **failed** for the official record; Ph4 starts from scratch.

## What worked (keep it)

- Full survival tool chain: hand-cut wood -> planks/sticks/table -> wooden -> stone -> **iron pickaxe**
  (furnace + coal, 3 iron ingots from mined ore).
- `mine_block` verified mining with aim lock (crosshair + hit result), `ores` reporting per job.
- Block queries / scans / containers are fast and reliable (daemon 5-12 ms per call).
- `tower` (pillar) works on a solid floor with clearance; `mlg_water` (fall 64 blocks -> water -> 0 damage)
  worked once the bucket aim was fixed.
- `dump_junk` (drop + run away) is server-synced and actually frees slots.
- The watchdog/relaunch loops are fixed (stop file, `clean-start.ps1` does not start it by default).

## What failed (summary, details in `ISSUES.md`)

1. **Ghost blocks after digging** - the dug block looks like air, but an invisible collider blocks the
   player (even when the human takes over). This is the most serious bug.
2. **No ore awareness in tunnels** - ores were walked past; `scan_area` behaves like an X-ray and its
   output is hard to interpret; there is no per-dig neighbour probe.
3. **Vertical mobility** - the staircase-up job cannot reliably climb out of a mine
   ("could not climb onto the step"), so returning to the surface needed `/tp`.
4. **Descent quality** - stair descent stalls on caves/water (`water_risk`, `no_progress`, `blocked`) and
   the visible motion looks like "jump two blocks, dig one, jump two, dig one".
5. **Tools** - near-broken pickaxes were skipped (looked like "standing still"); no policy for
   "use it up vs keep for repair".
6. **Inventory** - early client-side stack mutation desynced the inventory (fake empty slots);
   the fix is a sync delete, not a fake drop.
7. **Ender eyes** - repeated throws from one spot, no pickup of the dropped eye, unreliable tracking.
8. **Pathing has no progress watchdog** - the agent waits for the job to end, so a stuck path is only
   noticed minutes later.
9. **Pathing destroys plants** - the vegetation clear is too aggressive.
10. **Break/place is not optional** during pathing.
11. **User-aborted output does not cancel the running MCP task** (should be plugin-side).
12. Environment noise: duplicate clients, modpack freeze (movement ~1 block / 80 ticks) after long
    sessions, watchdog relaunch loop (fixed).

## Self-tests to implement next

- **CubeTest**: build a large solid cube, pick a random point inside, dig in from the surface, reach the
  point, then walk back out through the same tunnel (solid-block pathing, no ghost blocks).
- **TestB v2**: a brand-new survival world on **peaceful difficulty** (`/difficulty peaceful`),
  **zero commands**, fishbone mining until a diamond, with ore-awareness and a verified return trip
  along the tunnel.
