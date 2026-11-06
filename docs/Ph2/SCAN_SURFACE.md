# Surface scan, action batches and parallel perception (2026-10-29)

This build adds the section 14 `scan_surface` contract plus the batched-action and parallel-perception
optimisations requested after the first survival session.

## `scan_surface`

Surface-only perception. It floods the open space reachable from the player's **eye**, then returns only
the blocks that touch that space, so fully buried blocks are never reported.

Args:

| arg | default | notes |
|---|---|---|
| `center` | player block | `[x,y,z]` flood origin (the eye position is always used as the ray origin) |
| `radius` | 16 | 1..64, Euclidean bound |
| `mode` | `connected` | `connected` = reachable surface; `los` = 360° visibility filter; `fov` = visibility inside the current view cone |
| `fovDegrees` | 70 | used by `mode:"fov"` |
| `maxRaySteps` | 64 | per-ray step cap for los/fov |
| `maxSurface` | 4000 | cap on reported surface entries (nearest first) |
| `maxVisitedCells` | 250000 | flood cap |
| `timeBudgetMs` | 1500 | flood/ray time budget; the result is marked truncated when exceeded |
| `heightRadius` | min(16, radius) | local height map / pit area |
| `includeEntities` | true | nearby entities as `[type,x,y,z,distance]` |

Output (compact arrays):

```json
{"ok":true,"mode":"connected","center":[-225,-60,-4],"eye":[...],"radius":8,
 "surface":[[x,y,z,"minecraft:oak_planks",1], ...],
 "heightMap":[[x,z,y,"minecraft:grass_block"], ...],
 "pits":[[x,z,floorY,depth,"minecraft:air"], ...],
 "doors":[[x,y,z,id,open,facing], ...],
 "containers":[[x,y,z,id], ...],
 "openCells":123,"surfaceCount":745,"visibleSurfaceCount":745,"occludedSurfaceCount":0,
 "unloaded":0,"truncated":true,"truncatedReason":"byteCap","bytes":23987}
```

Face bits: `1=up, 2=down, 4=north, 8=south, 16=west, 32=east`.

Classification (runtime, no hardcoded block lists):

- air and fluids are open;
- containers (chest/barrel/shulker/any `Container` block entity) are barriers and are reported in `containers`;
- doors/trapdoors/fence gates are open only when their `open` property is true; closed ones are barriers;
- fences, walls and iron bars are barriers;
- blocks with a non-empty collision shape are barriers **unless** they do no occlude (glass, panes, many
  transparent blocks), which stay sight-open so a glazed room is still visible;
- other non-colliding blocks (plants, torches) are open.

`los` / `fov` are a **second-stage filter** over the connected surface: face sample points are ray-marched
from the eye (bounded by `maxRaySteps`), and a block is visible when at least one sample has a clear line.
In these modes `surface` contains only visible entries and `visibleSurfaceCount` / `occludedSurfaceCount`
are both reported; `heightMap`, `pits`, `doors` and `containers` still describe the connected space so
navigation does not lose the map by turning around.

Response size: the implementation trims the farthest surface entries first, then the height map, then pits,
until the JSON is under 24 KB (`truncated/truncatedReason:"byteCap"`), because the MCP server truncates tool
text at 30 KB.

## `batch`

Runs a chain of operations as one job without any per-action HTTP round trip:

```json
{"ops":[{"op":"place","x":1,"y":64,"z":0,"item":"minecraft:oak_planks","face":"up"},
        {"op":"mine","x":1,"y":64,"z":0},
        {"op":"use","x":2,"y":64,"z":0}],
 "timeoutSeconds":300}
```

- `place`/`mine`/`use` are the only ops, matching the requested tool-stream scope (no crafting/inventory
  logic is mixed into the build stream).
- Verification happens **once at the end** (`verifiedOk`, `unverified`, `failed`, `errors`), not after every
  block. Poll `job_status` until terminal.
- Auto face selection picks a solid neighbour when `face` is omitted; `item` is matched against the hotbar.

## `parallel`

Runs 2–4 short calls concurrently and returns every result:

```json
{"calls":[{"tool":"scan_area","args":{"radius":6}},{"tool":"get_state","args":{}}]}
```

This is how the agent observes the environment or inventory **while a path job is moving**: `path_to`
returns immediately, then `parallel` issues perception calls that execute between movement ticks.

## Crafting one item at a time

`container_move` gained `mode:"take_one"`: it performs a single `PICKUP` on the result slot, stores the
cursor into the inventory and verifies the crafted item count increased. The previous `quick` mode is a
vanilla shift-click and keeps crafting until the inputs are exhausted — that is what consumed all planks in
the first survival run. Use `take_one` (or a batch of `take_one` calls) when materials are scarce.

## Path camera

`BaritoneControl.steer` now interpolates yaw/pitch (max 9°/tick yaw, 6°/tick pitch) instead of snapping to
the next node every tick, which removes most of the camera shake; `PathJob` sets pitch to 0 when the goal is
reached so the horizon is level at the end.
