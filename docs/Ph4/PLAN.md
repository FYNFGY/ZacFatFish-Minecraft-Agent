# Ph4 - Development plan for tomorrow

Order matters: P0 first, then the pathing primitives, then the tests. Every task has an acceptance
criterion that can be measured and stored in `evidence/`.

## Phase 0 - stabilise (must be done before any new test)

1. **Ghost blocks (P0-1)**
   - remove every `mc.gameMode.destroyBlock` call for solid blocks;
   - only mark a block broken when the world block state changes and stays changed for 3 ticks;
   - add a ghost detector (3x3 under the player + `horizontalCollision` with air) that reports
     `ghost_block {pos}` and fails the job.
   - **Accept**: dig a 40-block tunnel, walk back and forth through it 3 times with `walk_to`, zero
     `ghost_block` reports.
2. **Progress watchdog (P1-8)**
   - `walk_to` / `path_to` / dig jobs fail with `stalled` when the position moves < 1 block in 60 ticks;
   - the MCP layer forwards the event so the agent can react immediately.
   - **Accept**: a deliberately blocked path returns `stalled` within 3 s.
3. **Tool policy (P1-5)**
   - `tool_status` in `get_state` (damage / max / tier);
   - mining stops with `tool_low` when the tool has < 10 durability left and the block is valuable.
   - **Accept**: mining iron ore with a 5-durability pickaxe returns `tool_low` and does not break it.
4. **Sync inventory delete (P1-6)**
   - remove the old fake-drop code path;
   - implement `delete_item` via a server-synced route (chest-in-chest or THROW click), documented;
   - **Accept**: deleting a stack frees the slot and a re-open of the inventory shows the same slots
     (no fake empty slots), verified twice with a 2 s gap.

## Phase 1 - perception and ore awareness

5. **Connected-surface scan (P1-1)**
   - `scan_connected {radius}` (radius may be larger, e.g. 48-64): flood fill from the player through
     **passable** cells - air, plants, water, doors, fence gates, trapdoors (they do NOT cut the region) -
     and return the reachable air cells plus every solid block adjacent to them (the surface of all rooms
     you can walk into). Walls still hide what is behind them.
   - `scan_area` gets `xray: true` in its schema and is documented as debug-only.
   - **Accept**: inside a tunnel the scan returns only tunnel surfaces (no ores behind walls); two rooms
     joined by a door return the surfaces of **both** rooms; a wall with a room behind it (no door) hides
     that room.
6. **Ore probe stream (P1-1/P1-2)**
   - after every mined block, the job emits the 6 neighbours (position, id, ore?);
   - a subagent consumes the stream and reports ore coordinates;
   - the agent paths to a free cell **next to** the ore and runs one `mine_block`, then `collect_drops`.
   - **Accept**: in a tunnel with a hidden diamond ore 3 blocks behind the wall, the ore is reported
     and mined without destroying it.
7. **Ender-eye protocol (P1-7)**
   - capture the projectile in the first 4 ticks, track to landing, pick the eye back up;
   - two-point triangulation, no repeated throws from one spot.
   - **Accept**: a triangulated stronghold estimate with two bearings whose error is < 30 blocks.

## Phase 2 - movement primitives

8. **Stair up / step up (P1-3)**
   - deterministic climb loop, reused by `walk_to`;
   - **Accept**: climb 30 blocks up a 1-wide shaft and come back down, 3 times.
9. **Clean stair descent + dynamic re-plan (P1-4)**
   - one step per cycle, cave/water check, new endpoint on blockage;
   - **Accept**: descend 60 blocks through mixed rock/cave terrain without `blocked`.
10. **Solid-rock tunnel A -> B (P2-1)**
    - `dig_tunnel {from, to}` 2-high corridor with torches, then walk back to A;
    - dynamic endpoint re-selection on obstacles.
    - **Accept**: CubeTest (below) passes.
11. **Pathing policy toggles (P1-10)** `allowBreak` / `allowPlace`, switchable mid-path.
12. **Plant clearing (P1-9)** only when actually blocked; no mass destruction.

## Phase 3 - tests

13. **CubeTest** (self-test 1)
    - build a solid cube ~24x24x24, pick a random point inside, dig in from the surface, reach it,
      then return to the start along the tunnel.
    - **Accept**: reach + return, no ghost blocks, path length < 3x the straight line.
14. **TestB v2** (self-test 2)
    - fresh survival world with difficulty set to **peaceful** as pre-test setup (one `/difficulty peaceful`
      before the run; **zero commands during the run**),
      fishbone mining for a diamond, ore awareness live, return along the tunnel to the surface.
    - **Accept**: `minecraft:diamond` in the inventory, no `/` command used, return trip completed.

## Phase 4 - housekeeping

15. User-abort cancels the running MCP task (plugin side, P1-11).
16. Remove the old drop/junk tools from the tool list (or mark deprecated).
17. Keep `clean-start.ps1` (no watchdog by default), `stop-all.ps1`, and the `watchdog.stop` guard.
