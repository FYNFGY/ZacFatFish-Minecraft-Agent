# Test A result - "find the stronghold with eyes of ender" (2026-10-31, stopped by user)

## Verdict: **做不到 (not feasible) in this world** - the stronghold is under the ocean floor

## What was established (works)

- Ender-eye protocol works: `eye_throw` gives the bearing from a 12-block flight (vanilla eyes break
  after ~12 blocks), the bearing equals the direction to the stronghold, and the dropped eye is walked
  to and picked up.
- Four throws from different places all point at the same spot:

| throw from | bearing |
|---|---|
| `1547.5, -440.5` | 74.11 deg |
| `1521.6, -515.6` | 71.28 deg |
| `1289.6, -437.4` | 71.26 deg |
| `-440.3, -345.5` | -51.88 deg (= 308.1) |

  Ray intersection (`triangulate`) lands on **(1, 0)**; distance from the first ray ~1608 blocks.
  All four bearings agree with a target at (0,0) to within 0.05 deg, so the horizontal position is
  reliable (error ~ a few blocks).

## Why it is not feasible

- The target (0, 0) is **open ocean**: the column at (0,0) is water from y=62 down to y=34, sand at
  y=32, stone at y=30 and below. Nothing but the seabed within 64 blocks (scan_surface: 327 sand
  cells, no land above y=60).
- The stronghold itself is inside/below that seabed (solid stone at y=30..18 at the probed columns),
  so reaching it means **digging a shaft through ~30 blocks of water and then through rock**.
- Doing that safely needs water handling that this tool set does not have yet:
  a sealed shaft (blocks placed above the head while digging), a water-breathing source, sponges or a
  sand/gravel plug, and a way to swim out with loot. `dig_shaft` refuses water (`water_risk`) by
  design (P0-1 safety), and the player drowned once while testing boat placement in the same sea
  (grave key `simpletomb:grave_key` was the only item left).
- Time cost: the ocean between the start area and (0,0) is ~1000 blocks wide; a boat crossing plus an
  underwater shaft would be a long, high-risk operation with the current primitives.

## What would make it feasible (next phase candidates)

1. **Sealed-shaft primitive**: dig down 2, place a block above the head, repeat - keeps the shaft dry
   under water (needs a place-block-above action and a "don't drown" check).
2. **Water-breathing / potion support**: brew or carry water breathing, or use a conduit.
3. **Drain the work area**: sponge or sand plug the shaft entrance, then dig like on land.
4. **Underwater pathing**: let Baritone/our walker swim to the seabed and dig horizontally into the
   sea floor, entering the stronghold from the side instead of from above.

## Evidence

`evidence/testa3-eye5.json` (bearing), `testa3-column.json` (water column at 0,0),
`testa3-scan-near0.json` / `testa3-scan64.json` (seabed only), `evidence/testa2-tri1.json`
(triangulation -> (1,0)), `testa3-state2.json` (drowning at air 59 while probing).
