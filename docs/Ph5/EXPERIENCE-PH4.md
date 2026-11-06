# Ph4 - EXPERIENCE (how to actually play)

Practical survival knowledge accumulated while playing. Read this before a mining/exploration run.
(World-specific coordinates are in `docs/Ph3/EXPERIENCE.md`.)

## Before going mining / travelling

- **Pickaxes: bring 3-4 spare ones**, never just one. Each block costs 1 durability.
  Rough budget: wooden 59, stone 131, iron 250, diamond 1561 uses.
- **Bring 2-3 crafting tables** (one to leave at the mine mouth, one to carry), a **furnace**,
  coal/charcoal, and 2-3 stacks of building blocks (dirt/cobble) - you will need steps, patches and
  pillars.
- **Bring 2-3 chests** and place them: one at the mine entrance (ore storage), one at the work site
  (junk storage). This is how you keep the backpack from filling up.
- Torches: 2 stacks minimum (light + route markers). Food: 8+. Water bucket: always.
- Leave base with **at least 10 free slots**. A full inventory means drops stay on the floor.
- Write down the entrance coordinates before entering the tunnel.

## Tools

- A tool that cannot produce the drop **destroys the block**: a stone pickaxe on diamond/gold/redstone/
  lapis ore = the ore vanishes, no item. Iron pickaxe or better for those.
- When a pickaxe is below ~20 % durability, switch to a spare **before** reaching a valuable ore.
- Craft pickaxes on-site: crafting table + sticks + cobblestone (3 cobble + 2 sticks = 1 stone pickaxe).
- Repair or discard worn tools deliberately; do not carry a 2-use diamond pickaxe into a lava cave.

## Inventory

- The backpack is the real limit: when it is full you cannot pick up anything (a mined diamond can be
  lost that way).
- Store junk (dirt, gravel, andesite, deepslate, extra cobble) in the chest at the work site.
- Never throw valuable items away to make room; throw away the cheapest junk.
- Keep the hotbar arranged: pickaxe / blocks / torch / water / food always in the same slots.

## Digging

- **Never dig a 1x1 vertical shaft downwards**: you cannot climb out, and it can open into lava or water.
  Use a staircase (1 block down per 1 block forward) or a spiral.
- Place a torch every ~8 blocks; use a different pattern for the way out (e.g. torches on the left when
  going in, right when coming back).
- Never mine the block directly above your head (gravel/sand falls on you).
- Carry a water bucket: lava pocket or a fall -> place water.
- Fishbone mining for diamonds: main corridor at **Y = -59**, side branches every 3 blocks, 12-16 blocks long.
- Mine the stone **around** an ore first, then the ore, so the ore is exposed and you see it.
- When a corridor opens into a cave, stop digging and inspect; do not continue blindly.

## Navigating

- In tunnels and shafts **you cannot pillar up** (ceiling too low). Plan the way back with stairs from
  the start - the way out is harder than the way in.
- Mark junctions with a block of a different type (e.g. cobble pillar) so you can find the turn.
- Keep the entrance direction in mind: at every junction, know which way is "out".

## Survival

- Eat before a dangerous section; hunger stops regeneration.
- Sleep in a bed when possible (sets the respawn point near the work site).
- Fall damage: a water bucket placed just before landing saves you (MLG water).
- Keep 1-2 empty buckets for water/lava handling.

## Modpack notes (Mechanomania / NeoForge 1.21.1)

- **Create** adds `create:zinc_ore` (stone pickaxe is enough for zinc; it is not a diamond-tier ore).
- **SimpleTomb**: on death the items go to a grave; a grave key appears in the inventory.
- If the client's movement becomes absurdly slow (walking ~1 block per 80 ticks) while the bridge still
  answers, it is a client/modpack freeze: restart the client, it is not a logic bug.
- Chests from **Lootr** are per-player.

## Agent-specific habits that paid off

- Check `get_state` (position, hp, inventory slots) before and after every segment.
- One action at a time, verify the result, then continue; long "do everything" scripts hide failures.
- When a job returns `no_progress` / `blocked`, stop and re-plan instead of retrying the same action.
- Keep the evidence: coordinates, job ids, durations, `ores` metrics.
