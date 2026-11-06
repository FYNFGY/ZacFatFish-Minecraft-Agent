# EXPERIENCE.md — Minecraft common sense for the agent

This file is the seed of a growing knowledge base. The agent must **append** to it after every
session (one section per session, newest last) and **read it before starting**. Keep entries short,
concrete, and world-specific where needed.

## How to use

- Read the whole file at the start of a session.
- Add new entries only when they are general enough to help future sessions (not one-off positions).
- Mark uncertain entries with `(unverified)`.
- Keep it under ~300 lines; prune duplicates.

---

## World `mcp-test-world` (NeoForge 1.21.1 "Mechanomania" pack)

- **No cheats.** `/tp`, `/gamemode`, `/give` are rejected in this save. Never plan around commands
  that need cheats. `/locate` is also unavailable.
- The ocean near the starting beach is frozen (snow/ice at y≈62); open water is rare near the shore.
- **Aquifer layer at y≈52–57** in the area x −1035..−1010, z 350..390. A dry probe of one column
  does **not** guarantee the neighbouring column is dry; water pockets are common.
- **Flowing water also drowns** and refills 1×1 shafts. Standing at the bottom of a flooded shaft
  keeps the head underwater; the `underWater` flag can stay true while `air` regenerates.
- A **1×1 vertical shaft** is easy to dig but hard to exit; always plan the ascent (staircase or
  tower with cheap blocks) before digging down.
- **Blast furnace** at the beach house `(-1005, 64, 360)`; a crafting table is nearby
  (`-1006, 64, 354`). Village beds exist in most houses.
- **Bed `occupied` state can stick.** Breaking the bed and placing it again clears it.
  Two players must both be in bed to skip the night.
- **Copper ore** is common around y≈47–56; **zinc ore** (`create:zinc_ore`) is exposed on the
  eastern hill around `(-979, 70, 360)`; **iron ore** around y≈28–34 under the village.
- The pack has **SimpleTomb**: on death, items go into a grave and a grave key is added to the
  inventory. Graves can be opened with the key.
- **Lootr chests are per-player**; a chest another player looted is still full for you.
- The client can crash in the **c2me** chunk system when the world unloads; the world save survives.
- **PCL regenerates `LatestLaunch.bat`** when the launcher is used, losing any appended
  `--quickPlaySingleplayer` argument. Keep a separate `DeepSeekLaunch.bat` for the agent.
- The window title is the modpack name ("Mechanomania"), not the world name — do not infer the
  instance from it.

## Tools and mechanics

- Stone pickaxe is the minimum tier for iron ore; wooden is not enough (no drop).
- A pickaxe only works from the **hotbar**; tools in the main inventory are not used by the
  bridge's mine tools (see `ISSUES.md` P2/P3).
- Breaking progress in Minecraft must be `startDestroyBlock` once and `continueDestroyBlock`
  every tick; restarting every tick shows cracks but never breaks the block.
- Shift-clicking a crafting result (or a container slot) can move a whole stack; verify with a
  read-back because the client and server can disagree.
- `/recipe give` is not available (no cheats); recipes unlock through normal play.
- Distant Horizons and other mods spam chat on startup; ignore system messages that are not
  player chat, join/leave, death, or commands.

## Pathing & mining conventions (learned 2026-10-30)

- **Fence gates block movement when closed.** A gate must be opened (interact) before walking
  through; pathing into a closed gate looks "stuck". Villages have many gates around farms.
- **Do not walk across farmland/crops** (trampling); route around fields or through gates.
- **Never dig through a house wall to leave.** Use the door; wall-digging is a last resort with
  explicit permission.
- **Boat**: 5 planks of one wood type → 1 boat (2 in the top corners + 3 across the bottom). Place it
  on water, then **interact** with the boat to mount. A boat is steered by movement **input** (forward
  + yaw), not by velocity; sneak to dismount. Use a boat for oceans/wide rivers — never swim across.
  **To recover a boat, hit it repeatedly** (bare hand = 1 dmg/hit, boat has ~4 HP → ~4 hits; a tool
  dealing >4 damage breaks it in one hit), then pick up the item (boats are non-stackable, 1 slot).
  Report recovery or a justified loss.
- **Branch mining (fishbone)** — verified against Minecraft Wiki *Tutorial:Mining → Branch Mining*:
  a 1×2 main corridor with side branches; spacing 2 = slow but thorough, 5 = fast but can miss small
  diamond/lapis blobs, **3 = recommended compromise**; branches ~32 blocks (one stone pickaxe's
  durability); mine level ~**Y=-54** (above lava lakes) or **-59** for diamonds. Always check for
  water before starting a corridor.
  Source: <https://minecraft.wiki/w/Tutorial:Mining#Branch_Mining>.
- **Never dig a 1×1 shaft straight down** — 8% lava / 3% ravine / 27% cave risk (wiki: 100 tests) and
  no return path. Use a **staircase** (45°, 4 blocks headroom, torches every 5) or a **1×2 shaft**
  (sneak between the two columns, ladder/scaffold every block, pillar up to ascend).
  Spiral staircases stay in one chunk. Source: <https://minecraft.wiki/w/Tutorial:Mining#Vertical_shaft_mining>.
- Carry a **water bucket + a stack of blocks** in the hotbar when digging down (lava, falls).
- **Light / mob spawning (1.18+, Java):** hostile mobs need internal sky light ≤ 7 **and block light
  = 0**; a torch (14) keeps ~6–7 blocks of floor at ≥ 8, so place torches every 6–7 blocks in
  tunnels. At midnight internal sky light is 4, so the surface still spawns mobs at night;
  thunderstorms spawn as if sky light were 5. Passive mobs need block light ≥ 9 when not under sky.
  Always light tunnels, caves, and rooms (a roof blocks sky light).
  Source: <https://minecraft.wiki/w/Light#Mobs>.
- A 1×1 vertical shaft is a trap without an exit plan; branch mining uses a staircase access shaft.

## World tests (round 3 rules, confirmed by the human)

- Both tests use **new default worlds, peaceful + survival**.
- **Stronghold run**: only `/give @s minecraft:ender_eye 64` is allowed; no `/locate`, no other
  command. Eyes fly ~12 blocks toward the nearest stronghold, **20% shatter**; above 12 blocks from
  the staircase chunk they fly **up**, closer they fly **down**; 12 eyes activate the portal; each
  frame has a **10%** chance to be pre-filled. The staircase chunk center is chunk (4,~,4).
- **Diamond run**: **no cheats**, from scratch. Fishbone at Y=-54 (or -59 for diamonds); main corridor
  1×2, branches every 3 blocks, 16–24 long; stop at the **first** diamond (1 is enough to pass).

- **Item frames, paintings, armour stands, boats and minecarts are entities**, not blocks. Recover them
  by attacking (item frames/paintings: 1 hit; armour stand: 1 hit drops stand + equipment; boat ~4
  bare-hand hits or 1 hit with a >4-damage tool; minecart 1 hit). Never mine the wall behind a frame.

## Agent behaviour lessons

- If the human says an action did not happen, **trust them** and resync (relog) instead of arguing
  with client-side state.
- Never block on a tool call when the human is present; check state and chat between short actions.
- When something is "stuck", stop and change strategy after one retry; never loop the same request.

## Round-3 engineering lessons (2026-10-30)

- Mining must be a state machine: `startDestroyBlock` once, `continueDestroyBlock` per tick, verify the
  block changed. Restarting every tick shows cracks but never breaks (the old bug).
- Tools are only usable from the **hotbar**; `inventory_swap` (InventoryMenu SWAP click) is the
  server-synced way to move a pickaxe/block into it.
- `place_block` needs the *support block's face*: picking the solid neighbour at `target.relative(dir)`
  and clicking `dir.getOpposite()` is the correct combination (the inverse never places).
- A walked staircase needs **3 cells** of clearance at the destination (Y−1 hole, Y, Y+1 head while
  crossing the lip), otherwise the player is blocked.
- Branch mining from inside a cave fails unless the job is allowed to walk through open cells with a
  solid floor (cap it, e.g., 8 free steps, so it does not wander into a cavern).
- Agent-side latency dominates: a fresh MCP session per call costs ~400 ms; a persistent daemon makes
  it 5–12 ms. Batch independent actions into one job and poll at ~60 ms.
- Client crashes are recoverable: relaunch via `cmd /c start "" /D <PCL dir> <bat>` (spawn from Node
  must go through cmd; direct `spawn(bat)` / hidden PowerShell Start-Process did not start java).
---

## Session Ph4 - gameplay experience (2026-10-31)

The full practical guide now lives in `docs/Ph4/EXPERIENCE.md` (read it before a mining run).
Highlights learned the hard way:

- Bring **3-4 pickaxes**, **2-3 chests** and **2-3 crafting tables**; place a chest at the mine mouth and
  one at the work site, store junk there instead of carrying it.
- A full backpack cannot pick up drops: a mined diamond can stay on the floor. Keep >= 10 free slots.
- A tool that cannot produce the drop destroys the ore (stone pickaxe on diamond ore = no diamond).
- Never dig 1x1 straight down; use stairs. In tunnels you cannot pillar up - plan the way back first.
- Fishbone: main corridor Y=-59, branches every 3 blocks.
- If the client moves absurdly slowly (~1 block / 80 ticks) while the bridge answers, restart the client
  (modpack freeze, not a logic bug).