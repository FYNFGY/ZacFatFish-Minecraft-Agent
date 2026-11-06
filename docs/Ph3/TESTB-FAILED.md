# Test B — FAILED (2026-10-30)

Environment: Smoke Test world, survival, current items kept, diamond pickaxes discarded.
Task: fishbone-mine at least one diamond in survival.

**Verdict: FAILED.** The user found diamonds inside the tunnel I had already dug; I did not notice
them, and when I went back for them the last two were destroyed without drops.

## What happened (user-observed + log evidence)

1. **Diamonds were mined but never noticed.** `branch_mine` broke diamond ore while carving the
   tunnels; the job reported only `blocksMined` / `branchesDone` / `torchesPlaced` — no ore
   information, and the drops were left on the tunnel floor. My end-of-pass check only looked at the
   inventory, so it saw nothing.
2. **The iron pickaxe was one hit from breaking** (`damage 249 / maxDamage 250`). `Mining.bestToolSlot`
   correctly skipped a worn tool, but then returned the next "best" hotbar slot — **dirt** — and the
   miner happily tried to break deepslate with dirt: 201 ticks of "no progress", no error about the
   tool.
3. **A stone pickaxe destroyed two diamond ores with no drops.** Diamond ore requires an iron pickaxe
   for drops (`requiresCorrectToolForDrops`); the stone pickaxe broke the block and yielded nothing.
   Nothing in the code prevented mining a block with a tool that cannot produce its drop.
4. **Jumping in the mine.** The tower job tried to pillar up inside a 2-high tunnel; there is no room
   for the jump and the placement window is never reached, so it kept hopping (the user saw this as
   "jumping in place").
5. **Vertical 1x1 descent.** The descent used `mode=down` (mine the block under the feet, drop one
   block). It is dangerous (no exit, can open into lava/water) — stairs or a spiral are required.

## Fixes implemented after the failure

| # | Fix | Where |
|---|---|---|
| 1 | A block that requires a correct tool will **never** be mined with a tool that cannot drop it: `bestToolSlot` filters by `requiresCorrectToolForDrops` | `Mining.bestToolSlot` |
| 2 | When no correct tool exists, the miner fails immediately with `wrong_tool` ("mining it would yield no drops") instead of switching to dirt/stone and silently grinding | `BlockMiner` |
| 3 | Worn tools are skipped; if nothing usable remains the job fails `tool_broken` (no more falling back to a random block item) | `Mining`, `BlockMiner` |
| 4 | Vertical descent is no longer allowed: `dig_shaft mode=down` is aliased to a **staircase** (verified: job metric `mode=stair`) | `Tools.digShaft` |
| 5 | Pillaring underground fails fast: if the ceiling at feet+2 is a hard block (>1.2 hardness) the tower stops with `blocked` "ceiling too low (deepslate) - use stairs instead of pillaring underground". Soft ceilings (leaves/wood) are still cleared | `TowerJob.headroomState` |

Verification on the same tunnel (after rebuild):

```
tower -> failed blocked   msg: ceiling too low (minecraft:deepslate) - use stairs instead of pillaring underground
dig_shaft mode=down -> done   mode: stair
```

## Still missing (next)

- **Ore awareness**: mining jobs must report ores encountered (`diamond_ore`, `deepslate_diamond_ore`,
  `gold`, `redstone`, `lapis`, `iron`, `coal`) and auto-collect their drops before the job ends.
- **Auto tool management**: detect a nearly-broken pickaxe and craft/switch to a spare *before* mining
  anything valuable.
- **Spiral stair descent** (true spiral, 2-wide) for safer vertical travel than a plain staircase.
- **Underground navigation**: prefer 2-high tunnels with stairs, never pillar in a tunnel.
