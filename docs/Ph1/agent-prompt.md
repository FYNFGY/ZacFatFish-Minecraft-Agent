**Unlimited token budget has been approved. There is no time limit for a single turn.**

---

# Task Prompt — Implement the MCP Minecraft Client-Control Server

You are a senior engineer in two fields at once: **Minecraft NeoForge 1.21.1 client modding** and **MCP server engineering**. You will design and build a control layer that lets an LLM play Minecraft through MCP tools, on a heavily modded server, using a **real headed client** as the agent's body.

---

## 1. Authoritative inputs

Read these first, in this order:

1. `D:\mc\MCP Development\docs\Ph2\requirement.en.md` — the requirements (v2). **Authoritative.**
2. `D:\mc\MCP Development\docs\Ph1\requirement.md` — same document in Chinese.
3. `D:\mc\MCP Development\docs\Requirement.original.md` — the original v1, for intent only. Where v1 and v2 differ, **v2 wins**.

The requirements contain a §3 "Technical caveats" section. Those are **measured failures from a previous attempt**, not speculation. Treat every item there as a hard constraint.

---

## 2. Mission

Build and deliver:

- a **NeoForge 1.21.1 client mod** that exposes the control surface (perception, actions, jobs) over a loopback HTTP endpoint;
- an **MCP server** (or MCP-compatible endpoint) that exposes those capabilities as tools to an MCP client (DSH), with non-blocking job semantics;
- the glue that lets the MCP client discover and attach to a **client the user started manually** (PCL / official launcher).

You do **not** build a new launcher, and you do **not** replace the player's client.

---

## 3. Hard constraints (violating any of these = task failed)

1. **Headed client only.** Never implement off-screen rendering by replacing LWJGL, and never ship or recommend a "headless patch". A previous attempt did exactly that and produced 11 `Caught exception during event ... already registered` errors; with real LWJGL the same mod set produced **0**. If unattended operation is needed, use a virtual display / RDP session and keep the render path real.
2. **NeoForge metadata only.** Ship `META-INF/neoforge.mods.toml`; exclude `fabric.mod.json` from the jar. The modpack includes Sinytra Connector, which double-loads dual-metadata jars.
3. **No pid-based discovery.** Register via an instance file with a **heartbeat** (5 s refresh, expire > 20 s). Support manual attach by port/file.
4. **Loopback only + bearer token.** Bind to `127.0.0.1`. Generate a random token, write it to the instance file, never log it, never put it in tool output or model context.
5. **All world access on the client main thread** via `Minecraft.getInstance().execute(...)`; return results through a `CompletableFuture`. HTTP handler threads must never touch the world.
6. **Every tool is non-blocking**: immediate return with `jobId` for long work, plus `job_status` / `job_cancel`, per-call `timeoutSeconds`, structured errors, and decidable progress (`distanceLeft`, `blocksMined/total`) — never just "running".
7. **Read game data at runtime**, from `RecipeManager` / `Registry` / JEI. The pack rewrites recipes through KubeJS, including virtual recipes; hard-coded vanilla data will be wrong.
8. **Optional integrations are soft**: `ModList.get().isLoaded("<modid>")` + reflection; degrade gracefully when a mod is absent.
9. **Never modify the game server.** It is read-only for you (FTP/list/log inspection only, if you have it at all).
10. **Non-ASCII paths are normal here.** Use `Path`/`File` APIs and argument lists; never concatenate command strings, never put non-ASCII characters in `.bat` files.

---

## 4. Environment facts

| Item | Value |
|---|---|
| Modpack | `假期增援计划` (Mechanomania family), ~250 mods, Create / Apotheosis / SlashBlade / Kaleidoscope / KubeJS |
| Minecraft | 1.21.1 |
| Loader | **NeoForge 21.1.228** (Java 21; Oracle 21.0.8 verified) |
| Client version folder | `D:\SoftWare\PCL 正式版 2.12.2\MC资源\e3\EarthⅢ ver0.21fix2\.minecraft\versions\假期增援计划` |
| Target server | `zmcp3.rhymc.com:1069` (NeoForge, offline-mode verified, 20 slots) |
| Working directory | `D:\mc\MCP Development` |
| Reference assets | `D:\mc\tools\bridge-cmd.mjs` (bridge HTTP client), `D:\mc\tools\plain-java-launch.ps1` (PCL-equivalent launcher) |

The user starts the client themselves. You may add your mod jar(s) to the version's `mods` folder; you may not otherwise change the pack.

---

## 5. Working method (follow the order)

**Phase 0 — Plan and gate.**
Read the docs, then reply with a **one-page plan and risk list**, and resolve the gating question: *is there a usable Baritone build for NeoForge 1.21.1?* If not, state which fallback you will take (server-side fake player, or in-mod A*) and what capability is lost. Do not write code before this answer.

**Phase 1 — Minimal vertical slice.**
Mod skeleton + loopback HTTP server + `/health` + `get_state` + `screenshot`. Prove that an external process can call them and read the result. This is the point where the whole architecture is validated.

**Phase 2 — Perception.**
`scan_area`, `chat`, `command`, `recipe_lookup`, block/entity/collision queries per §2.3 of the requirements.

**Phase 3 — Movement.**
Baritone wrapper with the job protocol (`path_to` / `path_status` / `path_cancel`), plus cancellation on disconnect.

**Phase 4 — Interaction.**
Containers (read/take/put/verify), break/place, villager trading, doors/buttons.

**Phase 5 — Hardening.**
Memory files, response size caps, crash resilience, acceptance run.

---

## 6. Contract details you must implement

**Instance registration** — `<state>/headlessmc-mcp/instances/<uuid>.json`:

```json
{
  "protocol": 1,
  "port": 57551,
  "token": "<random>",
  "bridgeVersion": "0.1.0",
  "loader": "NeoForge",
  "mcVersion": "1.21.1",
  "gameDir": "<absolute path>",
  "started": 1793121172035,
  "heartbeatAt": 1793121190000
}
```

**Control endpoint**:

```
GET  /health                  → 200 "ok" (no auth)
GET  /info                    → {name, version, loader, protocol}   (Bearer)
POST /command?timeout=<sec>   → body: one command line              (Bearer)
                               → {output, timedOut, error}
```

**Job lifecycle**: `queued → running → done | failed | cancelled`, with a monotonically increasing `progress` field and a terminal `reason`.

**Error taxonomy** (use these strings): `timeout`, `disconnected`, `not_loaded`, `no_permission`, `unsupported`, `internal`.

---

## 7. Definition of done

- Client startup log contains **0** `Caught exception during event`.
- `get_state` and `screenshot` work while in a multiplayer world.
- `scan_area(radius=16)` returns valid JSON under 32 KB.
- A container round-trip (open → take one stack → read-back) verifies.
- `path_to` walks 100 blocks without stalling, and cancels cleanly.
- A task survives a client restart via the memory files.
- The MCP client can attach to a client the user started manually, without pid checks.

Report phase by phase: what works, the evidence (log excerpt, coordinates, screenshot path), and open risks. If you are blocked, ask **one concrete question** with the options and your recommendation — not a vague status.

---

## 8. Anti-patterns (do not do these)

- Blocking an HTTP handler while waiting for game ticks.
- Polling a world object from a non-main thread "just for a read".
- Logging the bearer token, account passwords, or session tokens.
- Hard-coding recipes, loot tables, or block/item registries.
- Adding `fabric.mod.json` for convenience.
- Launching the client yourself with a headless patch, or asking the user to.
- Touch-modifying the server (config, mods, world) to make your client work.
- Returning unbounded world dumps into the model context.
- Using `AGENTS.md` for agent memory (DSH auto-injects it as workspace instructions).

---

## 9. Ask before you do

- Changing anything in the user's game directory beyond adding your own mod jar(s).
- Any server-side installation or server config change.
- Any approach that requires the user to reinstall or re-create the pack.

Start by restating the mission in three sentences, then give the Phase-0 plan and the Baritone gate answer.

---

**Unlimited token budget has been approved. There is no time limit for a single turn.**
