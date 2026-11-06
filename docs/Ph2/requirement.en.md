# MCP Minecraft Client-Control Server — Requirements (Revised v2)

> Audience: the AI/engineer implementing this server. v1 is `requirement.original.md`.
> Every change in v2 comes from a real integration attempt (DSH + HeadlessMC + this modpack).
> **§3 lists measured pitfalls, not speculation.**

---

## 0. Changes vs. v1

| # | v1 | v2 (revised) | Reason |
|---|---|---|---|
| 1 | "NewForge 1.21.1" | **NeoForge 21.1.1**, server actually `21.1.228`, Java 21 | Name was wrong; the exact version must be pinned or the handshake fails (§3.7) |
| 2 | Runtime shape unspecified | **Headed client + loopback bridge**; headless patches forbidden | A headless LWJGL patch breaks mod initialization (§3.1) |
| 3 | "Every tool is non-blocking" | Explicit **submit / poll / cancel** contract + suggested timeouts | "Non-blocking" alone is not implementable (§2.4) |
| 4 | "Mods may be installed server-side" | Explicit **client vs. server responsibility table** | Perception, pathfinding and container UI must live client-side (§3.2) |
| 5 | Working dir `D:/mc/Development` | **`D:\mc\MCP Development`** | Actual path |
| 6 | Loader metadata not mentioned | **`META-INF/neoforge.mods.toml` only**; no `fabric.mod.json` | The pack ships Sinytra Connector, which double-loads dual-metadata jars (§3.4) |
| 7 | Process discovery not mentioned | **Registration file + heartbeat**; never rely on pid liveness | Sandboxed/restricted processes are invisible to the server process (§3.3) |
| 8 | Transport not mentioned | **Loopback HTTP (streamable-http) first**, stdio as fallback | stdio children fail on temp-dir restrictions (§3.5) |
| 9 | "See how MineFlyer does it" | Concrete **data-source APIs and their limits** | MineFlyer may not target 1.21.1; see §2.3 |
| 10 | "Game source may be modified" | **Recipes/data must be read at runtime** (KubeJS) | The pack heavily rewrites recipes via KubeJS (§3.7) |

---

## 1. Goal and shape

**Goal**: let an LLM play continuously on a **NeoForge 1.21.1 modded server** through MCP tools — move, gather, build, use containers, trade, chat.

**Shape (hard constraints)**:

```
[LLM / MCP client]
        │  MCP over loopback HTTP (Bearer token)
        ▼
[client mod: mcp-bridge]  ← runs inside the real client process; calls Minecraft/Baritone/JEI APIs
        │  same process: render thread + network thread
        ▼
[NeoForge 1.21.1 client] ── normal multiplayer ──► [target server]
```

- **Headed client only** (renders, real LWJGL). No "headless / off-screen render patch" of any kind.
- The client is started normally by the user (PCL / official launcher). The mod only exposes a control interface; it **must not take over the launcher**.
- Server-side helper mods are allowed, but **everything player-visible must be implemented client-side** (§3.2).

---

## 2. Functional requirements

### 2.1 Pathfinding

- Use **Baritone**, driven by chat commands (`#goto x y z`, `#mine <block>`, `#follow player`), with completion detected from Baritone's state/output.
- **Verify first**: confirm a usable Baritone build exists for NeoForge 1.21.1. If not:
  - Plan B: server-side "fake player entity + server-side pathfinding";
  - Plan C: in-mod client A* (walk/jump/bridge only, no complex mining) and declare the capability downgrade to the LLM.
- Pathfinding must be **asynchronous**: `start_path` returns a `jobId` immediately; `path_status` polls `{state: running|done|failed, distanceLeft, currentGoal}`; `path_cancel` aborts.

### 2.2 Interaction

1. **Containers**: chests, barrels, shulker boxes, ender chests, plus modded containers (Create vaults, Sophisticated Storage, …).
   - Must support: read slots, take, put, quick-move (shift-click), split by count.
   - **Recommended**: server-authoritative operations (custom packet → server `Container` mutation); client `ClickSlot` only as fallback, always followed by read-back verification.
   - Containers that are not `AbstractContainerMenu` (e.g. inside a Create contraption) must use that mod's API or be explicitly reported as unsupported — never blind-clicked.
2. **Break/place blocks**: equivalent to player input. Support target coordinate, facing, tool switching, continuous mining, placement face/orientation.
3. **Crafting table / JEI recipes**: query via the **JEI API** (client), returning `{outputs, inputs[], station, recipeId}`; must handle KubeJS-generated virtual recipes. Read-only, so it can return synchronously.
4. **Mob interaction**: villager trading (open screen, enumerate offers, select and complete), feeding/breeding, milking, shearing.
5. **Doors/buttons/levers**: `use` interaction; return block state before/after.
6. `(*)` **Carry On**: pick up / place entities and chests allowed by a server-side config whitelist.
7. `(*)` **Create and addons**: contraption interaction (RPM, stress, belts, arms). **Scope must be enumerated explicitly**; implement only a short, named list of actions.

### 2.3 World perception

| Requirement | Client-side data source | Limitation |
|---|---|---|
| Chat list + speak + commands | `ClientPacketListener` chat events + own ring buffer | The client keeps only the last 100 messages; persist long-term history yourself |
| Blocks around/in front, with state | `ClientLevel.getBlockState(pos)`; unloaded chunks return empty | **No data for unloaded chunks**; approach and wait for load |
| Entity types and positions | `ClientLevel.entitiesForRendering()` + `Entity.getType()/position()` | Only **client-tracked** entities; nothing far away |
| Collision / interaction volumes | `BlockState.getCollisionShape(...)`, `getInteractionShape(...)`, `Entity.getBoundingBox()` | One tick of lag vs. the server |
| Time | `level.getDayTime()`, `getGameTime()` | — |
| Player state | position/facing/health/food/xp/dimension/gamemode from `LocalPlayer` | — |

Additional requirements:
- Provide `scan_area(center, radius, filter)`: one structured snapshot of **blocks + entities + items**, with a `truncated` flag, so the LLM does not burn tokens querying block by block.
- Coordinates are **integer world coordinates**; state whether they denote a block or its center.

### 2.4 MCP interface (non-blocking protocol)

**Every tool must**:

1. return in under ~1 s; long tasks return `{jobId}`;
2. come with `job_status(jobId)` and `job_cancel(jobId)`;
3. accept `timeoutSeconds` (defaults below, cap 600);
4. on timeout return `{ok:false, partial:true, error:"timeout"}` — never throw;
5. report **decidable progress** (`distanceLeft`, `blocksMined/total`), not just "running".

Suggested tool set (English ids; descriptions may be Chinese):

| Tool | Blocking | Default timeout | Notes |
|---|---|---|---|
| `get_state` | sync | 2 s | position/health/dimension/time/players |
| `scan_area` | sync | 3 s | blocks/entities snapshot within radius |
| `chat` / `command` | sync | 2 s | speak, run `/cmd` |
| `path_to` / `path_status` / `path_cancel` | async | 300 s | Baritone |
| `mine_block` / `place_block` | async | 60 s | target coordinate + face |
| `container_open` / `container_move` / `container_close` | sync | 10 s | read/take/put, verify after acting |
| `recipe_lookup` | sync | 5 s | JEI query |
| `entity_interact` | sync | 10 s | villager/animal/door/button |
| `screenshot` | sync | 5 s | returns a file path (for vision models) |

**Threading rules**:

- HTTP threads **must not touch the world directly**. Every game action goes through `Minecraft.getInstance().execute(runnable)` onto the **client main thread**, with a `CompletableFuture` carrying the result back.
- Multi-tick actions (mining, pathing, container screens) are **tick-polled tasks**; never `while`-loop inside one tick.
- On disconnect or dimension change, every in-flight job must fail with `reason=disconnect`.

### 2.5 Context memory

- Location: `<workspace>/.mcp-agent/` (`README.md` index + topic files).
- **Do not use `AGENTS.md`**: DSH injects it as workspace instructions into every session that touched that directory, polluting unrelated sessions.
- Writes must be **append + atomic replace** (temp file, then `move`) so a crash never leaves a half-written file.

---

## 3. Technical caveats (measured, mandatory)

### 3.1 Do not patch headless rendering (most important)

Same 253-mod client:

- **HeadlessMC headless launch** (LWJGL replaced by stubs) → **11** `Caught exception during event ... already registered` errors; FML's parallel init batches were aborted; `glitchcore/owo/create_connected/ritchiesprojectilelib` never registered their network channels; the server rejected the client with `Incompatible client`.
- **Plain java / PCL launch** (real LWJGL) → **0** such errors; joined successfully.

Conclusion: **never implement off-screen rendering by replacing LWJGL**. If unattended operation is required, use a virtual display / remote desktop session and keep the render path real.

### 3.2 Client vs. server responsibilities

| Capability | Must live in | Why |
|---|---|---|
| Perception (blocks/entities/chat) | client | Data is synced to the client; the server cannot know what this player can see |
| Pathing/movement/mining/placing | client | Equivalent to player input |
| Container UI actions | client-initiated + **server verification recommended** | Client can cheat; server is authoritative |
| Cross-player read-only data (TPS, whitelist) | server | — |

**Do not** implement the "agent" purely server-side: that degenerates into a Carpet-style fake player with no vision or container UI, and servers may forbid it.

### 3.3 Process discovery: never use pid liveness

Measured: in a restricted environment the MCP server **cannot see** the java process started by the launcher; `Get-Process` and `list_instances` both treated a perfectly valid registration as "exited".

Requirements:

- After startup the client writes `instances/<uuid>.json`: `{protocol, pid, port, token, mcVersion, loader, gameDir, started, heartbeatAt}`;
- **`heartbeatAt` refreshes every 5 s**; the MCP side expires entries on heartbeat age (> 20 s) and **must not** depend on `pid`;
- delete the file on exit;
- support **manual attach by port/file** (config option) independent of auto-discovery.

### 3.4 Loader metadata: NeoForge only

The pack ships **Sinytra Connector**. Any jar that also contains `fabric.mod.json` is loaded a second time as a Fabric mod, causing duplicate registration. Measured: removing the bridge's `fabric.mod.json` removed those errors.

Requirement: ship only `META-INF/neoforge.mods.toml`; exclude `fabric.mod.json` in the build script.

### 3.5 Transport: loopback HTTP first

- stdio children fail under sandbox restrictions: measured Quarkus crashing because `%TEMP%\vertx-cache` could not be created.
- Recommendation: a `HttpServer` inside the mod bound to **127.0.0.1 only**; the MCP side connects with `transport: streamable-http`. If stdio is mandatory, point `java.io.tmpdir`, `jna.tmpdir` and `org.lwjgl.system.SharedLibraryExtractPath` at writable directories explicitly.

### 3.6 Non-ASCII paths and encoding

- The pack path contains Chinese characters and symbols (`EarthⅢ`, `假期增援计划`).
- Use `Path`/`File` APIs; if a command line is unavoidable, pass an **argument list** (Java `ProcessBuilder(List)`, .NET `ArgumentList`), never a concatenated string.
- Never put non-ASCII characters in Windows `.bat` files (mixing code pages 936/65001 turned the path into `?` — measured launch failure). Put them in a UTF-8 **with BOM** `.ps1`, or in Java/JSON.
- Add `-Dfile.encoding=UTF-8`.

### 3.7 Mod and data compatibility

- Target pack: **250+ mods** (Create family, Apotheosis, SlashBlade, Kaleidoscope, KubeJS, …).
- All optional integrations: `ModList.get().isLoaded("<modid>")` + reflection/soft deps; **no hard dependency**; degrade instead of crashing when a mod is absent.
- **Read recipes/loot/tags from `RecipeManager`/`Registry` at runtime**: the pack rewrites many recipes through KubeJS (including virtual recipes); hard-coding vanilla recipes will be wrong.
- Client and server versions must match **exactly**: target server is NeoForge **21.1.228**, MC 1.21.1, Java 21. A 21.1.235 client was rejected (measured).

### 3.8 Security

- HTTP bound to loopback + `Authorization: Bearer <token>`; token generated randomly, written to a file, **never logged or placed in model context**.
- Never leak credentials in tool results; log in via device-code / out-of-band flow.
- Tools must return a clear error when the player lacks permission (e.g. server-disabled commands).

### 3.9 Miscellaneous

- Declare `engines.dsh`. The DSH MCP client defaults to a **60 s per-call timeout**; long tasks must use the job protocol or the call is cut off (measured repeatedly).
- Cap each tool response (~32 KB); truncate with `truncated:true`.
- Crash resilience: heartbeat expiry cleans up stale registrations; all jobs become invalid after a client restart.

---

## 4. Deliverables and acceptance

| Deliverable | Acceptance criterion |
|---|---|
| Client mod (NeoForge 1.21.1) | Coexists with 250+ mods; startup log has **0** `Caught exception during event` |
| MCP server | Connectable from DSH via streamable-http; all tools listed |
| Tool set | Every tool in the table callable; long tasks pollable and cancellable |
| Pathfinding | Walks 100 blocks without stalling; failures carry a reason |
| Containers | Open chest → take one stack → read-back verifies |
| Recipes | Correct recipes for 3 vanilla + 3 modded items |
| Perception | `scan_area(radius 16)` < 32 KB, includes blocks and entities |
| Memory | A task can be resumed after a restart |
| Docs | Install steps, port/token config, troubleshooting (including §3) |

**Integration order**: single-player creative → target server (`zmcp3.rhymc.com:1069`, offline check, **read-only — never modify the server**) → production.

---

## 5. Environment

- Pack: `假期增援计划` (Mechanomania family), MC 1.21.1 / **NeoForge 21.1.228** / Java 21 (Oracle 21.0.8 verified)
- Target server: `zmcp3.rhymc.com:1069` (NeoForge, offline-mode verified, 20 slots)
- Working directory: `D:\mc\MCP Development`
- Reusable assets: `D:\mc\tools\bridge-cmd.mjs` (reference bridge HTTP client), `D:\mc\tools\plain-java-launch.ps1` (PCL-equivalent launcher)
