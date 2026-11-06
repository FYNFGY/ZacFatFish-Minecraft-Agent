package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;

/** Tool dispatch. Synchronous tools still wait on a future completed by the client thread. */
public final class Tools {
    private Tools() {
    }

    public static Object call(String tool, JsonObject args, int requestedTimeout) {
        String name = tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "get_state", "state" -> sync("get_state", requestedTimeout, 2, GameState::snapshot);
            case "scan_area" -> sync("scan_area", requestedTimeout, 3, () -> Perception.scanArea(args));
            case "scan_connected" -> sync("scan_connected", requestedTimeout, 5, () -> {
                args.addProperty("mode", "connected");
                return ScanSurface.scan(args);
            });
            case "scan_surface" -> sync("scan_surface", requestedTimeout, 4, () -> ScanSurface.scan(args));
            case "block_query", "block_at" -> sync("block_query", requestedTimeout, 3, () -> Perception.blockQuery(args));
            case "entity_query" -> sync("entity_query", requestedTimeout, 3, () -> Perception.entityQuery(args));
            case "collision_query" -> sync("collision_query", requestedTimeout, 3, () -> Perception.collisionQuery(args));
            case "chat" -> chat(args, requestedTimeout);
            case "chat_history" -> sync("chat_history", requestedTimeout, 2, () -> {
                JsonObject o = Json.ok();
                int limit = MathUtil.clamp(Json.i(args, "limit", 40), 1, 200);
                o.add("messages", ChatLog.history(limit));
                return o;
            });
            case "command" -> sync("command", requestedTimeout, 2, () -> {
                String line = Json.requireStr(args, "command");
                String probe = line.startsWith("/") ? line.substring(1) : line;
                if (probe.startsWith("tp ") || probe.equals("tp")) {
                    Ghosts.expectTeleport();
                }
                return ChatLog.command(line);
            });
            case "recipe_lookup" -> sync("recipe_lookup", requestedTimeout, 5, () -> Recipes.lookup(args));
            case "path_to" -> pathTo(args, requestedTimeout);
            case "path_status" -> pathStatus(args);
            case "path_cancel" -> pathCancel(args);
            case "path_open" -> pathOpen(args, requestedTimeout);
            case "set_pathing_policy" -> sync("set_pathing_policy", requestedTimeout, 2, () -> {
                if (args.has("break") || args.has("place")) {
                    PathPolicy.set(Json.b(args, "break", PathPolicy.breakAllowed()),
                            Json.b(args, "place", PathPolicy.placeAllowed()));
                }
                JsonObject o = Json.ok();
                o.add("policy", PathPolicy.json());
                return o;
            });
            case "mine_block" -> Actions.mineBlock(args);
            case "place_block" -> Actions.placeBlock(args);
            case "batch" -> Actions.batch(args);
            case "tower" -> tower(args);
            case "fish" -> fish(args);
            case "combat_attack" -> combatAttack(args);
            case "kill_target_close" -> killTargetClose(args);
            case "kill_target_far" -> killTargetFar(args);
            case "escape" -> escape(args);
            case "self_protect" -> sync("self_protect", requestedTimeout, 2,
                    () -> SelfProtect.set(Json.b(args, "enabled", true)));
            case "bow_probe" -> sync("bow_probe", requestedTimeout, 2,
                    () -> CombatUtil.bowProbe(Minecraft.getInstance().player));
            case "walk" -> walk(args);
case "walk_to" -> walkTo(args);
            case "dump_junk" -> dumpJunk(args);
            case "delete_item" -> deleteItem(args);
            case "drop_junk" -> sync("drop_junk", requestedTimeout, 5, () -> InventoryActions.dropJunk(args));
            case "ore_log" -> oreLog(args);
            case "ghosts" -> ghostLog(args);
            case "select_slot" -> selectSlotTool(args);
            case "mlg_water" -> mlgWater(args);
            case "craft" -> craft(args);
            case "wait_events" -> waitEvents(args);
            case "loot_chest" -> lootChest(args);
            case "damage_log" -> sync("damage_log", requestedTimeout, 2, () -> damageLog(args));
            case "watch_damage" -> watchDamage(args);
            case "auto_respawn" -> sync("auto_respawn", requestedTimeout, 2,
                    () -> AutoRespawn.set(Json.b(args, "enabled", true)));
            case "use_block" -> sync("use_block", requestedTimeout, 5, () -> Actions.useBlock(args));
            case "break_entity" -> EntityActions.breakEntity(args);
            case "collect_drops" -> EntityActions.collectDrops(args);
            case "dig_shaft" -> digShaft(args);
            case "dig_sealed_shaft" -> {
                int level = Json.i(args, "level", Json.i(args, "targetY", Integer.MIN_VALUE));
                if (level == Integer.MIN_VALUE) {
                    throw new Errors.BridgeException(Errors.INTERNAL, "level is required (the target Y)");
                }
                int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 300), 10, 600);
                yield BridgeRuntime.jobs.create("dig_sealed_shaft", timeout, new SealShaftJob(level));
            }
            case "dig_tunnel" -> digTunnel(args);
            case "branch_mine" -> branchMine(args);
            case "throw_item" -> sync("throw_item", requestedTimeout, 5, () -> ItemActions.throwItem(args));
            case "eye_throw" -> eyeThrow(args);
            case "place_boat" -> sync("place_boat", requestedTimeout, 5, () -> ItemActions.placeBoat(args));
            case "track_entity" -> ItemActions.trackEntity(args);
            case "boat_sail" -> ItemActions.boatSail(args);
            case "light_map" -> sync("light_map", requestedTimeout, 5, () -> LightTools.lightMap(args));
            case "torch_at" -> sync("torch_at", requestedTimeout, 5, () -> LightTools.torchAt(args));
            case "entity_interact" -> await(Actions.entityInteract(args), timeoutMs(requestedTimeout, 10));
            case "container_open" -> await(Containers.open(args), timeoutMs(requestedTimeout, 10));
            case "container_read" -> await(Containers.read(Json.b(args, "full", false)), timeoutMs(requestedTimeout, 10));
            case "container_move" -> await(Containers.move(args), timeoutMs(requestedTimeout, 10));
            case "container_close" -> await(Containers.close(), timeoutMs(requestedTimeout, 10));
            case "resync" -> await(Containers.resync(), timeoutMs(requestedTimeout, 10));
            case "save_world" -> sync("save_world", requestedTimeout, 30, Tools::saveWorld);
            case "inventory_swap" -> sync("inventory_swap", requestedTimeout, 5, () -> InventoryActions.swap(args));
            case "inventory_slots" -> sync("inventory_slots", requestedTimeout, 2, () -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    throw Errors.notLoaded("the player is not in a world");
                }
                return GameState.slots(player);
            });
            case "hotbar" -> sync("hotbar", requestedTimeout, 5, () -> InventoryActions.hotbar());
            case "villager_trades", "trade_list" -> await(Containers.trades(), timeoutMs(requestedTimeout, 10));
            case "trade_select" -> await(Containers.tradeSelect(args), timeoutMs(requestedTimeout, 10));
            case "screenshot" -> await(ScreenshotCapture.request(), timeoutMs(requestedTimeout, 5));
            case "job_status" -> jobStatus(args);
            case "job_cancel" -> jobCancel(args);
            case "jobs_list" -> jobsList();
            // Ph5 T2: server-side memory and todo list (SavedData in <world>/data/zff_agent.dat).
            // These go through the mod channel, not through a port (T4).
            // These calls block the HTTP worker until the server answers; they must NOT run on the
            // client thread (the payload reply is completed there, which would deadlock).
            case "zff_todo_add" -> zff("todo.add", todoArgs(args));
            case "zff_todo_list" -> zff("todo.list", args);
            case "zff_todo_done" -> zff("todo.done", args);
            case "zff_todo_update" -> zff("todo.update", args);
            case "zff_todo_clear" -> zff("todo.clear", args);
            case "zff_status", "zff_server_status" -> zff("status", args);
            case "stop_all" -> sync("stop_all", requestedTimeout, 3,
                    () -> StopAll.run(Json.str(args, "reason", null)));
            case "bridge_info", "ping" -> bridgeInfo();
            case "baritone_status" -> baritoneStatus();
            // Ph6 M1: the environment inbox and the backend/negotiation state live in the client.
            case "env_read", "env" -> sync("env_read", requestedTimeout, 2, EnvSampler::snapshot);
            case "env_info" -> sync("env_info", requestedTimeout, 2, EnvInfo::snapshot);
            case "gui_describe" -> sync("gui_describe", requestedTimeout, 2, GuiTools::describe);
            case "gui_click" -> sync("gui_click", requestedTimeout, 2,
                    () -> GuiTools.click(Json.i(args, "index", -1)));
            case "gui_set" -> sync("gui_set", requestedTimeout, 2,
                    () -> GuiTools.setValue(Json.i(args, "index", -1), Json.d(args, "value", 0.0)));
            case "gui_click_at" -> sync("gui_click_at", requestedTimeout, 2,
                    () -> GuiTools.clickAt(Json.i(args, "x", 0), Json.i(args, "y", 0)));
            case "gui_scroll" -> sync("gui_scroll", requestedTimeout, 2,
                    () -> GuiTools.scroll(Json.i(args, "x", 0), Json.i(args, "y", 0), Json.d(args, "delta", 1.0)));
            case "gui_close" -> sync("gui_close", requestedTimeout, 2, GuiTools::close);
            case "create_probe" -> sync("create_probe", requestedTimeout, 2,
                    () -> CreateAdapter.probe(new net.minecraft.core.BlockPos(
                            Json.i(args, "x", 0), Json.i(args, "y", 0), Json.i(args, "z", 0))));
            case "create_scroll" -> sync("create_scroll", requestedTimeout, 2,
                    () -> CreateAdapter.scroll(new net.minecraft.core.BlockPos(
                            Json.i(args, "x", 0), Json.i(args, "y", 0), Json.i(args, "z", 0)), Json.i(args, "value", 0)));
            case "backend", "zff_backend" -> sync("backend", requestedTimeout, 2, Backend::toJson);
            // Ph6 §2.4: the mod store is written by the mod and only read by the agent.
            case "mod_store_flush" -> sync("mod_store_flush", requestedTimeout, 5, ModStore::flush);
            case "mod_store_status" -> sync("mod_store_status", requestedTimeout, 2, ModStore::status);
            case "chat_push_stats" -> sync("chat_push_stats", requestedTimeout, 2, ChatPush::stats);
            // Ph5: join / leave a multiplayer server without touching the GUI (network drops, restarts).
            case "connect" -> sync("connect", requestedTimeout, 25, () -> connectServer(args));
            case "disconnect" -> sync("disconnect", requestedTimeout, 10, () -> disconnectClient());
            // Ph6 ISSUE batch: the tools added while fixing ISSUE-2/3/4/5/11/13/14/15 live in their own
            // class so the fix work does not have to keep merging into this switch.
            default -> {
                Object delegated = IssueTools.call(name, args, requestedTimeout);
                if (delegated == null) {
                    throw Errors.unsupported("unknown tool: " + name);
                }
                yield delegated;
            }
        };
    }

    /** HMC-compatible one-line command endpoint. Never throws. */
    public static JsonObject command(String line, int timeoutSeconds) {
        String text = line == null ? "" : line.trim();
        JsonObject out = new JsonObject();
        out.addProperty("output", "");
        out.addProperty("timedOut", false);
        out.add("error", null);
        if (text.isEmpty()) {
            out.addProperty("error", Errors.INTERNAL);
            return out;
        }
        try {
            JsonObject result;
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.equals("state") || lower.equals("get_state")) {
                result = sync("get_state", timeoutSeconds, 2, GameState::snapshot);
            } else if (lower.equals("screenshot")) {
                result = await(ScreenshotCapture.request(), timeoutMs(timeoutSeconds, 5));
            } else if (lower.equals("info") || lower.equals("ping")) {
                result = bridgeInfo();
            } else if (lower.startsWith("chat ")) {
                result = sync("chat", timeoutSeconds, 2, () -> ChatLog.send(text.substring(5)));
            } else if (lower.startsWith("cmd ")) {
                result = sync("command", timeoutSeconds, 2, () -> ChatLog.command(text.substring(4)));
            } else if (lower.startsWith("/")) {
                result = sync("command", timeoutSeconds, 2, () -> ChatLog.command(text));
            } else if (lower.equals("help")) {
                result = Json.ok();
                result.addProperty("commands", "state | screenshot | info | chat <text> | cmd <command> | help");
            } else {
                out.addProperty("error", Errors.UNSUPPORTED);
                out.addProperty("output", "unknown command");
                return out;
            }
            out.addProperty("output", Json.GSON.toJson(result));
            return out;
        } catch (Errors.BridgeException e) {
            out.addProperty("error", e.code);
            if (Errors.TIMEOUT.equals(e.code)) {
                out.addProperty("timedOut", true);
            }
            out.addProperty("output", e.getMessage() == null ? "" : e.getMessage());
            return out;
        } catch (Throwable t) {
            out.addProperty("error", Errors.INTERNAL);
            out.addProperty("output", t.toString());
            return out;
        }
    }

    private static JsonObject chat(JsonObject args, int requestedTimeout) {
        String message = Json.str(args, "message", null);
        if (message == null || message.isBlank()) {
            JsonObject o = Json.ok();
            int limit = MathUtil.clamp(Json.i(args, "limit", 40), 1, 200);
            o.add("messages", ChatLog.history(limit));
            return o;
        }
        return sync("chat", requestedTimeout, 2, () -> ChatLog.send(message));
    }

    private static JsonObject damageLog(JsonObject args) {
        JsonObject o = Json.ok();
        o.add("events", DamageWatch.recent(MathUtil.clamp(Json.i(args, "limit", 10), 1, 40)));
        return o;
    }

    private static Object watchDamage(JsonObject args) {
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 30), 1, 600);
        long since = System.currentTimeMillis();
        return BridgeRuntime.jobs.create("watch_damage", timeout, new Jobs.TickTask() {
            @Override
            public void start(Jobs.Job job) {
                job.metric("since", since);
            }

            @Override
            public void tick(Jobs.Job job) {
                JsonObject last = DamageWatch.lastEvent();
                if (last != null && DamageWatch.lastEventAt() > since) {
                    job.succeed(last.deepCopy());
                }
            }
        });
    }

    /** Ph4 P1-7: one ender-eye throw with projectile capture, tracking and eye pickup. */
    private static Object eyeThrow(JsonObject args) {
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        return BridgeRuntime.jobs.create("eye_throw", timeout, new EyeJob());
    }

    private static Object tower(JsonObject args) {
        int blocks = MathUtil.clamp(Json.i(args, "blocks", 1), 1, 64);
        String item = Json.str(args, "item", null);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 180), 1, 600);
        return BridgeRuntime.jobs.create("tower", timeout, new TowerJob(blocks, item));
    }

    /** Ph8 M2.1: one cast only; timeout is the bite wait, job deadline adds a small margin. */
    private static Object fish(JsonObject args) {
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 90), 10, 180);
        return BridgeRuntime.jobs.create("fish", timeout + 10, new FishingJob(timeout));
    }

    private static Object combatAttack(JsonObject args) {
        int entityId = Json.i(args, "entityId", Integer.MIN_VALUE);
        if (entityId == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "entityId is required");
        }
        String weapon = Json.str(args, "weapon", "auto");
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 20), 2, 60);
        return BridgeRuntime.jobs.create("combat_attack", timeout,
                new CombatJob(entityId, weapon));
    }

    private static Object killTargetClose(JsonObject args) {
        String weapon = Json.str(args, "weapon", "");
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 120), 10, 600);
        return BridgeRuntime.jobs.create("kill_target_close", timeout, new KillTargetCloseJob(weapon));
    }

    private static Object killTargetFar(JsonObject args) {
        String weapon = Json.str(args, "weapon", "");
        double aimOffset = args.has("aimOffset") ? args.get("aimOffset").getAsDouble() : 0.0;
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 120), 10, 600);
        return BridgeRuntime.jobs.create("kill_target_far", timeout, new KillTargetFarJob(weapon, 64.0, aimOffset));
    }

    private static Object escape(JsonObject args) {
        int threshold = MathUtil.clamp(Json.i(args, "threshold", 5), 1, 30);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 120), 10, 600);
        return BridgeRuntime.jobs.create("escape", timeout, new EscapeJob(threshold));
    }

    private static Object mlgWater(JsonObject args) {
        float yaw = (float) Json.d(args, "yaw", Double.NaN);
        LocalPlayer player = Minecraft.getInstance().player;
        if (Float.isNaN(yaw)) {
            yaw = player == null ? 0.0f : player.getYRot();
        }
        int maxTicks = MathUtil.clamp(Json.i(args, "maxTicks", 200), 20, 600);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 30), 1, 600);
        return BridgeRuntime.jobs.create("mlg_water", timeout, new MlgWaterJob(yaw, maxTicks));
    }

private static Object dumpJunk(JsonObject args) {
        int target = MathUtil.clamp(Json.i(args, "target", 8), 1, 20);
        int run = MathUtil.clamp(Json.i(args, "run", 6), 3, 12);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 120), 1, 600);
        boolean all = Json.b(args, "all", false);
        return BridgeRuntime.jobs.create("dump_junk", timeout, new DumpJob(target, run, all));
    }

    /** Ph4 P1-6: server-synced deletion (menu SWAP + DROP_ALL_ITEMS + verification). */
    private static Object deleteItem(JsonObject args) {
        String item = Json.str(args, "item", null);
        int slot = Json.i(args, "slot", -1);
        int run = MathUtil.clamp(Json.i(args, "run", 6), 0, 12);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        if ((item == null || item.isBlank()) && (slot < 0 || slot > 35)) {
            throw new Errors.BridgeException(Errors.INTERNAL, "give item (id) or slot (0..35)");
        }
        return BridgeRuntime.jobs.create("delete_item", timeout, new DeleteItemJob(item, slot, run));
    }

    private static JsonObject oreLog(JsonObject args) {
        JsonObject o = Json.ok();
        o.add("ores", OreProbe.recent(MathUtil.clamp(Json.i(args, "limit", 20), 1, 100)));
        o.addProperty("total", OreProbe.total());
        return o;
    }

    private static JsonObject ghostLog(JsonObject args) {
        JsonObject o = Json.ok();
        o.add("events", Ghosts.recent(MathUtil.clamp(Json.i(args, "limit", 20), 1, 100)));
        o.addProperty("total", Ghosts.total());
        if (Json.b(args, "check", false)) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && Minecraft.getInstance().level != null) {
                net.minecraft.core.BlockPos ghost = Ghosts.checkStuck(player, Minecraft.getInstance().level);
                o.addProperty("stuckInAir", ghost != null);
                if (ghost != null) {
                    o.add("stuckAt", Json.intArray(ghost.getX(), ghost.getY(), ghost.getZ()));
                }
            }
        }
        return o;
    }

    private static Object selectSlotTool(JsonObject args) {
        int slot = Json.i(args, "slot", -1);
        if (slot < 0 || slot > 8) {
            throw new Errors.BridgeException(Errors.INTERNAL, "slot must be 0..8");
        }
        return sync("select_slot", 5, 3, () -> {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                throw Errors.notLoaded("the player is not in a world");
            }
            Actions.selectSlot(player, slot);
            JsonObject o = Json.ok();
            o.addProperty("selectedSlot", player.getInventory().selected);
            o.add("mainHand", GameState.stack(player.getMainHandItem()));
            return o;
        });
    }

private static Object walkTo(JsonObject args) {
        double x = Json.d(args, "x", Double.NaN);
        double z = Json.d(args, "z", Double.NaN);
        if (Double.isNaN(x) || Double.isNaN(z)) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x and z are required");
        }
        double range = Json.d(args, "range", 1.0);
        int maxTicks = MathUtil.clamp(Json.i(args, "maxTicks", 300), 10, 1200);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        boolean allowBreak = Json.b(args, "allowBreak", true);
        return BridgeRuntime.jobs.create("walk_to", timeout, new WalkToJob(x, z, range, maxTicks, allowBreak));
    }

    private static Object walk(JsonObject args) {
        float yaw = (float) Json.d(args, "yaw", Double.NaN);
        int ticks = MathUtil.clamp(Json.i(args, "ticks", 10), 1, 400);
        boolean jump = Json.b(args, "jump", false);
        boolean sprint = Json.b(args, "sprint", false);
        boolean up = Json.b(args, "up", false);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 60), 1, 600);
        return BridgeRuntime.jobs.create("walk", timeout, new WalkJob(yaw, ticks, jump, sprint, up));
    }

    private static Object digShaft(JsonObject args) {
        int level = Json.i(args, "level", Json.i(args, "targetY", Integer.MIN_VALUE));
        if (level == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "level is required (the target Y)");
        }
        String mode = Json.str(args, "mode", "stair").toLowerCase(Locale.ROOT);
        boolean allowStraight = Json.b(args, "allowStraightDown", false);
        DigJob.Mode m;
        switch (mode) {
            case "stair", "stairs", "staircase" -> m = DigJob.Mode.STAIR;
            case "shaft", "1x2" -> m = DigJob.Mode.SHAFT;
            case "up", "climb", "stairs_up" -> m = DigJob.Mode.UP;
            case "spiral" -> m = DigJob.Mode.SPIRAL;
            case "down", "descend" -> m = DigJob.Mode.SPIRAL; // no vertical 1x1: spiral staircase instead
            case "1x1", "straight", "vertical" -> {
                if (!allowStraight) {
                    throw new Errors.BridgeException(Errors.STRAIGHT_DOWN_FORBIDDEN,
                            "1x1 straight-down shafts are forbidden; use mode=stair or mode=shaft (1x2)");
                }
                throw new Errors.BridgeException(Errors.UNSUPPORTED,
                        "1x1 straight-down is not implemented even with allowStraightDown; use stair or shaft");
            }
            default -> throw new Errors.BridgeException(Errors.INTERNAL, "unknown mode: " + mode);
        }
        float yaw = (float) Json.d(args, "yaw", Double.NaN);
        int maxBlocks = MathUtil.clamp(Json.i(args, "maxBlocks", 4000), 8, 20_000);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 900), 1, 1200);
        return BridgeRuntime.jobs.create("dig_shaft", timeout, new DigJob(level, m, yaw, maxBlocks));
    }

    /** Ph4 P2-1: straight solid-rock tunnel A -> B, 1 wide and 2 high, with torches and a walk back. */
    private static Object digTunnel(JsonObject args) {
        BlockPos from = posArg(args, "from");
        BlockPos to = posArg(args, "to");
        if (from == null || to == null) {
            throw new Errors.BridgeException(Errors.INTERNAL, "from and to are required ([x,y,z] each)");
        }
        if (from.distSqr(to) > 512.0 * 512.0) {
            throw new Errors.BridgeException(Errors.INTERNAL, "the tunnel is too long (max 512 blocks)");
        }
        int maxBlocks = MathUtil.clamp(Json.i(args, "maxBlocks", 6000), 8, 20_000);
        boolean torches = Json.b(args, "torches", true);
        boolean returnTrip = Json.b(args, "returnTrip", true);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 1800), 1, 3600);
        return BridgeRuntime.jobs.create("dig_tunnel", timeout,
                new TunnelJob(from, to, maxBlocks, torches, returnTrip));
    }

    private static BlockPos posArg(JsonObject args, String key) {
        JsonArray a = Json.arr(args, key);
        if (a == null || a.size() < 3) {
            return null;
        }
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static Object branchMine(JsonObject args) {        int corridorLength = Json.i(args, "corridorLength", 32);
        int spacing = Json.i(args, "spacing", 3);
        int branchLength = Json.i(args, "branchLength", 16);
        int branches = Json.i(args, "branches", 4);
        float yaw = (float) Json.d(args, "yaw", Double.NaN);
        boolean left = !"right".equalsIgnoreCase(Json.str(args, "side", "left"));
        int maxBlocks = JsonUtilClamp(Json.i(args, "maxBlocks", 4000), 8, 20_000);
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 900), 1, 1200);
        return BridgeRuntime.jobs.create("branch_mine", timeout,
                new BranchMineJob(corridorLength, spacing, branchLength, branches, yaw, left, maxBlocks));
    }

    private static int JsonUtilClamp(int value, int min, int max) {
        return MathUtil.clamp(value, min, max);
    }

    private static Object craft(JsonObject args) {        String item = Json.str(args, "item", null);
        int count = MathUtil.clamp(Json.i(args, "count", 1), 1, 64);
        BlockPos table = null;
        if (args.has("table") && args.get("table").isJsonArray()) {
            JsonArray coords = args.getAsJsonArray("table");
            if (coords.size() >= 3) {
                table = new BlockPos(coords.get(0).getAsInt(), coords.get(1).getAsInt(), coords.get(2).getAsInt());
            }
        }
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 120), 1, 600);
        boolean manual = Json.b(args, "manual", false);
        return BridgeRuntime.jobs.create("craft", timeout, new CraftJob(item, count, table, manual));
    }

    /** Blocks on the HTTP worker thread until an event (chat/damage/death) arrives or the timeout passes. */
    private static JsonObject waitEvents(JsonObject args) {
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 20), 0, 120);
        int limit = MathUtil.clamp(Json.i(args, "limit", 20), 1, 100);
        int sinceArg = Json.i(args, "since", -1);
        long deadline = System.currentTimeMillis() + timeout * 1000L;
        long started = System.currentTimeMillis();
        JsonObject result;
        while (true) {
            result = sinceArg >= 0 ? EventJournal.since(sinceArg, limit) : EventJournal.takeDelivered(limit);
            JsonArray events = result.getAsJsonArray("events");
            if (!events.isEmpty() || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        result.addProperty("waitedMs", System.currentTimeMillis() - started);
        return result;
    }

    private static Object lootChest(JsonObject args) {
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        int timeout = MathUtil.clamp(Json.i(args, "timeoutSeconds", 30), 1, 600);
        return BridgeRuntime.jobs.create("loot_chest", timeout, new LootChestJob(new BlockPos(x, y, z)));
    }

    private static Object pathTo(JsonObject args, int requestedTimeout) {
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        if (!BaritoneControl.isLoaded()) {
            throw Errors.unsupported("Baritone (mod id baritoe) is not installed");
        }
        int range = MathUtil.clamp(Json.i(args, "range", 0), 0, 64);
        int timeout = timeoutSeconds(requestedTimeout, 300);
        Boolean allowBreak = args.has("allowBreak") ? Json.b(args, "allowBreak", false) : null;
        Boolean allowPlace = args.has("allowPlace") ? Json.b(args, "allowPlace", false) : null;
        return BridgeRuntime.jobs.create("path_to", timeout, new PathJob(x, y, z, range, allowBreak, allowPlace));
    }

    private static Object pathOpen(JsonObject args, int requestedTimeout) {
        int x = Json.i(args, "x", Integer.MIN_VALUE);
        int y = Json.i(args, "y", Integer.MIN_VALUE);
        int z = Json.i(args, "z", Integer.MIN_VALUE);
        if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
            throw new Errors.BridgeException(Errors.INTERNAL, "x, y and z are required");
        }
        if (!BaritoneControl.isLoaded()) {
            throw Errors.unsupported("Baritone (mod id baritoe) is not installed");
        }
        int range = MathUtil.clamp(Json.i(args, "range", 0), 0, 64);
        int timeout = timeoutSeconds(requestedTimeout, 300);
        return BridgeRuntime.jobs.create("path_open", timeout, new PathOpenJob(x, y, z, range));
    }

    private static JsonObject pathStatus(JsonObject args) {        Jobs.Job job = requireJob(args);
        if (!"path_to".equals(job.tool)) {
            throw Errors.unsupported("job is not a path job");
        }
        return job.toJson();
    }

    private static JsonObject pathCancel(JsonObject args) {
        Jobs.Job job = requireJob(args);
        if (!"path_to".equals(job.tool)) {
            throw Errors.unsupported("job is not a path job");
        }
        JsonObject result = MainThread.await(MainThread.submit("path_cancel", () -> {
            BridgeRuntime.jobs.cancel(job.id);
            return job.toJson();
        }), 5_000);
        return result;
    }

    private static JsonObject jobStatus(JsonObject args) {
        return requireJob(args).toJson();
    }

    private static JsonObject jobCancel(JsonObject args) {
        Jobs.Job job = requireJob(args);
        return MainThread.await(MainThread.submit("job_cancel", () -> {
            BridgeRuntime.jobs.cancel(job.id);
            return job.toJson();
        }), 5_000);
    }

    private static Jobs.Job requireJob(JsonObject args) {
        String id = Json.str(args, "jobId", null);
        if (id == null || id.isBlank()) {
            throw new Errors.BridgeException(Errors.INTERNAL, "jobId is required");
        }
        Jobs.Job job = BridgeRuntime.jobs == null ? null : BridgeRuntime.jobs.get(id);
        if (job == null) {
            throw new Errors.BridgeException(Errors.INTERNAL, "unknown job id");
        }
        return job;
    }

    private static JsonObject jobsList() {
        JsonObject o = Json.ok();
        o.add("jobs", BridgeRuntime.jobs == null ? new JsonArray() : BridgeRuntime.jobs.list());
        return o;
    }

    static JsonObject bridgeInfo() {
        JsonObject o = bridgeInfo0();
        // 2026-11-01 需求 4：没进世界时也要能按**登录账号**标记实例（玩家名要进世界才有）
        try {
            var user = net.minecraft.client.Minecraft.getInstance().getUser();
            if (user != null && user.getName() != null && !user.getName().isBlank()) {
                o.addProperty("account", user.getName());
            }
        } catch (Throwable ignored) {
            // 离线/测试环境可能拿不到，不影响其它字段
        }
        return o;
    }

    private static JsonObject bridgeInfo0() {
        JsonObject o = Json.ok();
        o.addProperty("name", "mcp-bridge");
        o.addProperty("version", BridgeConfig.VERSION);
        o.addProperty("loader", BridgeConfig.LOADER);
        o.addProperty("protocol", BridgeConfig.PROTOCOL);
        o.addProperty("mcVersion", BridgeConfig.MC_VERSION);
        // These fields are maintained by BridgeRuntime.tick() on the client thread.
        o.addProperty("inWorld", BridgeRuntime.inWorld);
        if (BridgeRuntime.playerName != null) {
            o.addProperty("player", BridgeRuntime.playerName);
        }
        if (BridgeRuntime.dimension != null) {
            o.addProperty("dimension", BridgeRuntime.dimension);
        }
        o.addProperty("baritoneLoaded", BaritoneControl.isLoaded());
        o.addProperty("uptimeMs", System.currentTimeMillis() - BridgeRuntime.startedAt);
        // Ph5: the mod owns the watchdog (T3) and the memory/todo store lives on the server (T2).
        o.addProperty("side", "client");
        o.addProperty("watchdogInMod", ZffConfig.watchdogInMod);
        o.addProperty("watchdogMs", ZffConfig.watchdogMs);
        o.addProperty("reportIntervalMs", ZffConfig.reportIntervalMs);
        o.addProperty("crEnabled", ZffConfig.crEnabled);
        o.addProperty("actorId", ZffConfig.actorId);
        o.addProperty("stuckEvents", dev.mcp.bridge.client.ZffWatchdog.eventCount());
        o.addProperty("storage", "server:data/zff_agent.dat");
        // Ph6 M3: which half is actually active. `backend` is "server" only when the server half answered
        // the negotiation with the same protocol; otherwise the client is fully usable in local mode.
        o.add("backend", Backend.toJson());
        o.addProperty("backendLabel", Backend.label());
        // Ph6 M1: the chat push is the path that makes "秒回" possible; its counters show a silent outage.
        o.add("chatPush", ChatPush.stats());
        o.add("modStore", ModStore.status());
        return o;
    }

    private static JsonObject baritoneStatus() {
        return MainThread.await(MainThread.submit("baritone_status", () -> {
            BaritoneControl.Status s = BaritoneControl.status();
            JsonObject o = Json.ok();
            o.addProperty("loaded", BaritoneControl.isLoaded());
            o.addProperty("pathing", s.pathing);
            o.addProperty("active", s.active);
            o.addProperty("hasPath", s.hasPath);
            if (s.etaTicks != null) {
                o.addProperty("etaTicks", s.etaTicks);
            }
            if (s.pathLength != null) {
                o.addProperty("pathLength", s.pathLength);
            }
            if (s.pathIndex != null) {
                o.addProperty("pathIndex", s.pathIndex);
            }
            if (s.error != null) {
                o.addProperty("error", s.error);
            }
            return o;
        }), 3_000);
    }

    private static JsonObject sync(String tool, int requestedTimeout, int defaultSeconds,
                                   java.util.concurrent.Callable<JsonObject> action) {
        return MainThread.await(MainThread.submit(tool, action), timeoutMs(requestedTimeout, defaultSeconds));
    }

    /**
     * Saves the world before a restart (F1). A killed client never saves the integrated server, so the
     * world would reload at the last autosave and the player would be back at spawn.
     *
     * <p>Ph5: {@code /save-all} is not available in every mod pack (this one strips it), and "command
     * sent" is not "data on disk". In single player the mod therefore calls
     * {@code MinecraftServer.saveEverything(flush=true)} on the server thread and only returns after
     * the data storage was written; nothing is lost when the process is killed right afterwards. On a
     * dedicated server the client can only ask, so the vanilla command stays as the fallback.</p>
     */
    static JsonObject saveWorld() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.connection == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        JsonObject o = Json.ok();
        MinecraftServer server = null;
        try {
            server = Minecraft.getInstance().getSingleplayerServer();
        } catch (Throwable ignored) {
            // not an integrated server
        }
        if (server != null && server.isRunning()) {
            MinecraftServer integrated = server;
            java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
            integrated.execute(() -> {
                try {
                    done.complete(integrated.saveEverything(true, true, false));
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
            Boolean saved = MainThread.await(done, 60_000);
            o.addProperty("method", "integrated_server.saveEverything");
            o.addProperty("flushed", true);
            o.addProperty("saved", Boolean.TRUE.equals(saved));
            o.addProperty("command", "");
            o.addProperty("note", "the integrated server wrote chunks and SavedData before this returned");
            return o;
        }
        player.connection.sendCommand("save-all");
        o.addProperty("method", "command:/save-all");
        o.addProperty("flushed", false);
        o.addProperty("command", "save-all");
        o.addProperty("note", "multiplayer/dedicated: the server decides when to write");
        return o;
    }

    // ------------------------------------------------------------------ multiplayer connect

    /**
     * Joins a server from wherever the client is (title screen, disconnect screen). The address is
     * validated and the connection runs through the vanilla {@link ConnectScreen}, so the mod does not
     * re-implement the handshake - it only presses "join" for the agent.
     */
    private static JsonObject connectServer(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            throw new Errors.BridgeException(Errors.INTERNAL, "already connected to a world; call disconnect first");
        }
        String host = Json.str(args, "host", null);
        if (host == null || host.isBlank()) {
            throw new Errors.BridgeException(Errors.INTERNAL, "host is required, e.g. {\"host\":\"play.example.com\",\"port\":25565}");
        }
        int port = MathUtil.clamp(Json.i(args, "port", 25565), 1, 65535);
        String address = host.contains(":") ? host : host + ":" + port;
        if (!net.minecraft.client.multiplayer.resolver.ServerAddress.isValidAddress(address)) {
            throw new Errors.BridgeException(Errors.INTERNAL, "invalid server address: " + address);
        }
        net.minecraft.client.multiplayer.resolver.ServerAddress serverAddress =
                net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address);
        net.minecraft.client.multiplayer.ServerData data = new net.minecraft.client.multiplayer.ServerData(
                host, serverAddress.toString(), net.minecraft.client.multiplayer.ServerData.Type.OTHER);
        net.minecraft.client.gui.screens.Screen parent = mc.screen != null
                ? mc.screen
                : new net.minecraft.client.gui.screens.TitleScreen();
        net.minecraft.client.gui.screens.ConnectScreen.startConnecting(parent, mc, serverAddress, data, false, null);
        JsonObject o = Json.ok();
        o.addProperty("connecting", serverAddress.getHost() + ":" + serverAddress.getPort());
        o.addProperty("host", serverAddress.getHost());
        o.addProperty("port", serverAddress.getPort());
        return o;
    }

    /** Leaves the current world or server and returns to the title screen. */
    private static JsonObject disconnectClient() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            throw Errors.notLoaded("not connected to anything");
        }
        mc.disconnect();
        JsonObject o = Json.ok();
        o.addProperty("disconnected", true);
        return o;
    }
    /**
     * One server-side memory/todo operation over the mod channel. The payload reply is completed on the
     * client thread, so this blocks the HTTP worker (never the game thread) until the server answers.
     */
    private static JsonObject zff(String op, JsonObject args) {
        JsonObject out = dev.mcp.bridge.client.ZffRemote.call(op, args, 12_000);
        if (out.has("ok") && !out.get("ok").getAsBoolean() && out.has("error")) {
            throw new Errors.BridgeException(Errors.SERVER_REJECTED, out.get("error").getAsString());
        }
        return out;
    }

    /** {@code spec} may be given as an object; the server stores it as a JSON string. */
    private static JsonObject todoArgs(JsonObject args) {
        JsonObject out = args.deepCopy();
        if (args.has("spec") && args.get("spec").isJsonObject()) {
            String spec = args.get("spec").toString();
            out.remove("spec");
            out.addProperty("spec", spec);
        }
        if (args.has("tags") && args.get("tags").isJsonArray()) {
            String tags = args.get("tags").toString();
            out.remove("tags");
            out.addProperty("tags", tags);
        }
        return out;
    }

    private static JsonObject await(java.util.concurrent.CompletableFuture<JsonObject> future, long timeoutMs) {
        return MainThread.await(future, timeoutMs);
    }

    private static int timeoutSeconds(int requested, int def) {
        int v = requested > 0 ? requested : def;
        return MathUtil.clamp(v, 1, 600);
    }

    private static long timeoutMs(int requested, int def) {
        // Synchronous tools must not hold an HTTP worker for minutes; long work uses jobs.
        return Math.min(30, timeoutSeconds(requested, def)) * 1000L;
    }
}



