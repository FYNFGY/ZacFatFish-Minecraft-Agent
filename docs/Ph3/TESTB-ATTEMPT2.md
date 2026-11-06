# Test B — attempt 2 (in progress)

Follow-up to `TESTB-FAILED.md`. Start condition: survival, existing items kept, diamond pickaxes cleared.
The user asked to leave the tunnel, walk 100 blocks away and restart Test B.

## Done so far

1. **Escaped the mine.** The 1x1 descent shaft at `(1717,-408)` was open from y=-58 to y=92; climbed it
   with the pillar (tower) in 3 chunks, y=-59 → **95**, no damage.
2. **Walked 101 blocks** away from the shaft (`1718,-408` → `1818,-407`).
3. **Restarted the survival chain**: chopped spruce logs, crafted planks/sticks/table, **2 wooden
   pickaxes + 2 stone pickaxes**, then descended with **staircases** from y=74 to **y=13**
   (300+ blocks), all in survival.
4. **Fixes exercised during the descent** (all built this session):
   - auto tool fetch: the miner now pulls a pickaxe from the main inventory into the hotbar
     (`Mining.fetchToolToHotbar`) — before this the "hand = dirt / wrong_tool" stalls;
   - non-strict tunnelling for stair/branch jobs: a stair may break an ore it cannot harvest
     (`create:zinc_ore` blocked the stairs before) and keeps going;
   - `wrong_tool` refusal still applies to intentional `mine_block` calls (diamond ore protection);
   - ore reporting (`ores` metric) is wired into `mine_block` / `branch_mine`.

## Current blocker

- The staircase descends fine in open rock but **stalls when the facing cell is air** (cave / end of a
  tunnel): `dig_shaft` fails `no_progress` with 0 steps instead of turning or digging a short
  horizontal approach. Current position `(1769,12,-478)`, y=12, hp 20.
- No iron ore within 20 blocks at y=13 (only 1 raw iron in the bag); the survival chain needs 3 iron
  ingots for an iron pickaxe before any diamond can be harvested.

## Next fix (not yet done)

1. `DigJob` stair recovery: if the planned step is unsupported, rotate the yaw by ±45° (up to 8 tries)
   and, if still blocked, mine a 2-block horizontal tunnel at feet level and retry.
2. Then: descend to y≈0..-16, find iron, smelt, craft the iron pickaxe, and only then go for diamonds
   (Y=-59) — with `ores` reporting + `collect_drops` after every pass.
