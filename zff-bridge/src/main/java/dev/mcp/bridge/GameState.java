package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Player/world/screen snapshot. Runs on the client thread. */
public final class GameState {
    private GameState() {
    }

    public static JsonObject snapshot() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        JsonObject out = Json.ok();
        out.add("player", player(mc, player, level));
        out.add("world", world(mc, player, level));
        out.add("screen", screen(mc, player));
        out.add("bridge", bridge());
        out.add("damage", DamageWatch.recent(5));
        out.add("autoRespawn", AutoRespawn.status());
        out.add("ghostEvents", Ghosts.recent(3));
        out.addProperty("ghostCount", Ghosts.total());
        return out;
    }

    private static JsonObject player(Minecraft mc, LocalPlayer player, ClientLevel level) {
        JsonObject p = new JsonObject();
        p.addProperty("name", player.getName().getString());
        p.addProperty("uuid", player.getUUID().toString());
        p.addProperty("x", MathUtil.round2(player.getX()));
        p.addProperty("y", MathUtil.round2(player.getY()));
        p.addProperty("z", MathUtil.round2(player.getZ()));
        BlockPos bp = player.blockPosition();
        p.addProperty("blockX", bp.getX());
        p.addProperty("blockY", bp.getY());
        p.addProperty("blockZ", bp.getZ());
        p.addProperty("yaw", MathUtil.round2(player.getYRot()));
        p.addProperty("pitch", MathUtil.round2(player.getXRot()));
        p.addProperty("health", MathUtil.round2(player.getHealth()));
        p.addProperty("maxHealth", MathUtil.round2(player.getMaxHealth()));
        p.addProperty("food", player.getFoodData().getFoodLevel());
        p.addProperty("saturation", MathUtil.round2(player.getFoodData().getSaturationLevel()));
        p.addProperty("xpLevel", player.experienceLevel);
        p.addProperty("xpProgress", MathUtil.round2(player.experienceProgress));
        p.addProperty("dimension", level.dimension().location().toString());
        p.addProperty("onGround", player.onGround());
        p.addProperty("sprinting", player.isSprinting());
        p.addProperty("sneaking", player.isShiftKeyDown());
        // Ph6 ISSUE-2: 睡觉状态是服务端同步的 entity data，不是 screen；原来 get_state 完全看不出来。
        p.addProperty("sleeping", player.isSleeping());
        player.getSleepingPos().ifPresentOrElse(
                bed -> p.add("bedPos", Json.intArray(bed.getX(), bed.getY(), bed.getZ())),
                () -> p.add("bedPos", null));
        p.addProperty("sleepTimer", player.getSleepTimer());
        p.addProperty("inWater", player.isInWater());
        p.addProperty("underWater", player.isUnderWater());
        p.addProperty("swimming", player.isSwimming());
        p.addProperty("eyeInWater", player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
        p.addProperty("air", player.getAirSupply());
        p.addProperty("maxAir", player.getMaxAirSupply());
        p.addProperty("alive", player.isAlive());
        BlockPos eyePos = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        p.addProperty("feetBlock",
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString());
        p.addProperty("eyeBlock",
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(eyePos).getBlock()).toString());
        // Round-3: light levels (mob spawning) and the fluid under the feet (water/lava planning).
        p.addProperty("blockLight", level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, bp));
        p.addProperty("skyLight", level.getBrightness(net.minecraft.world.level.LightLayer.SKY, bp));
        p.addProperty("headBlockLight", level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eyePos));
        p.addProperty("headSkyLight", level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eyePos));
        net.minecraft.world.level.material.FluidState feetFluid = level.getBlockState(bp).getFluidState();
        if (!feetFluid.isEmpty()) {
            p.addProperty("fluidAtFeet", BuiltInRegistries.FLUID.getKey(feetFluid.getType()).toString());
            p.addProperty("fluidAtFeetSource", feetFluid.isSource());
        } else {
            p.addProperty("fluidAtFeet", "none");
        }
        p.addProperty("selectedSlot", player.getInventory().selected);
        p.add("mainHand", stack(player.getMainHandItem()));
        p.add("offHand", stack(player.getOffhandItem()));
        // Ph4 P1-5: tool_status (damage / max / remaining / tier) for the whole inventory.
        p.add("toolStatus", toolStatus(player));
        GameType mode = mc.gameMode == null ? null : mc.gameMode.getPlayerMode();
        p.addProperty("gameMode", mode == null ? "unknown" : mode.getName());
        p.add("inventory", inventory(player));
        return p;
    }

    /** All 36 inventory slots with menu-slot indices (9..35 bag, 36..44 hotbar). */
    public static JsonObject slots(LocalPlayer player) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            JsonObject o = new JsonObject();
            o.addProperty("invSlot", i);
            o.addProperty("menuSlot", i < 9 ? 36 + i : i);
            o.addProperty("hotbar", i < 9);
            if (s.isEmpty()) {
                o.addProperty("id", "minecraft:air");
                o.addProperty("count", 0);
            } else {
                o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
                o.addProperty("count", s.getCount());
                if (s.getMaxDamage() > 0) {
                    o.addProperty("damage", s.getDamageValue());
                    o.addProperty("maxDamage", s.getMaxDamage());
                    o.addProperty("remaining", s.getMaxDamage() - s.getDamageValue());
                }
            }
            arr.add(o);
        }
        JsonArray armor = new JsonArray();
        for (int i = 0; i < 4; i++) {
            armor.add(stack(player.getInventory().getArmor(i)));
        }
        JsonObject o = Json.ok();
        o.addProperty("selectedSlot", player.getInventory().selected);
        o.add("slots", arr);
        o.add("armor", armor);
        return o;
    }

    /** Tool durability overview: the main hand plus every damageable stack in the inventory. */
    private static JsonObject toolStatus(LocalPlayer player) {        JsonObject ts = new JsonObject();
        JsonObject main = Mining.toolStatus(player.getMainHandItem());
        ts.add("mainHand", main);
        JsonArray tools = new JsonArray();
        int pickaxes = 0;
        int low = 0;
        JsonObject bestPick = null;
        for (int i = 0; i < 36; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.isEmpty() || s.getMaxDamage() <= 0) {
                continue;
            }
            JsonObject t = Mining.toolStatus(s);
            t.addProperty("slot", i);
            t.addProperty("hotbar", i < 9);
            tools.add(t);
            if (Json.b(t, "low", false)) {
                low++;
            }
            if ("pickaxe".equals(Json.str(t, "type", ""))) {
                pickaxes++;
                int remaining = Json.i(t, "remaining", 0);
                if (bestPick == null || remaining > Json.i(bestPick, "remaining", 0)) {
                    bestPick = t;
                }
            }
        }
        ts.add("tools", tools);
        ts.addProperty("pickaxes", pickaxes);
        ts.addProperty("lowTools", low);
        if (bestPick != null) {
            ts.add("bestPickaxe", bestPick);
        }
        return ts;
    }

    private static JsonObject inventory(LocalPlayer player) {        Inventory inv = player.getInventory();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int used = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            used++;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(s.getItem());
            counts.merge(id.toString(), s.getCount(), Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        JsonArray items = new JsonArray();
        for (int i = 0; i < Math.min(20, top.size()); i++) {
            Map.Entry<String, Integer> e = top.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getKey());
            o.addProperty("count", e.getValue());
            items.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("usedSlots", used);
        o.addProperty("freeSlots", 36 - used);
        o.add("items", items);
        JsonArray hotbar = new JsonArray();
        for (int i = 0; i < 9; i++) {
            hotbar.add(stack(inv.getItem(i)));
        }
        o.add("hotbar", hotbar);
        JsonArray armor = new JsonArray();
        for (int i = 0; i < 4; i++) {
            armor.add(stack(inv.getArmor(i)));
        }
        o.add("armor", armor);
        return o;
    }

    /** Human readable half of the day, 0 = sunrise. */
    private static String phaseName(long tod) {
        if (tod < 1000L) return "sunrise";
        if (tod < 6000L) return "morning";
        if (tod < 11000L) return "day";
        if (tod < 13000L) return "sunset";
        if (tod < 17000L) return "night";
        if (tod < 23000L) return "midnight";
        return "dawn";
    }
    private static JsonObject world(Minecraft mc, LocalPlayer player, ClientLevel level) {
        JsonObject w = new JsonObject();
        w.addProperty("dimension", level.dimension().location().toString());
        w.addProperty("dayTime", level.getDayTime());
        w.addProperty("gameTime", level.getGameTime());
        w.addProperty("timeOfDay", level.getDayTime() % 24000L);
        w.addProperty("day", level.getDayTime() / 24000L);
        w.addProperty("raining", level.isRaining());
        w.addProperty("thundering", level.isThundering());
        // Ph5 D5 (requirement "时间感知"): the flat fields above stay for compatibility, the nested
        // objects are what an agent should read: day/light state and the current weather.
        // NOTE: Level.isDay() is derived from the client's skyDarken field, which some packs/shaders
        // leave at 0 (that made it report "day" at midnight). The day/night flag is therefore computed
        // from the time of day, with the raw skyDarken exposed for debugging.
        boolean fixedTime = level.dimensionType().hasFixedTime();
        long tod = ((level.getDayTime() % 24000L) + 24000L) % 24000L;
        boolean isDay = fixedTime || tod < 12000L;
        int moonPhase = (int) ((level.getDayTime() / 24000L) % 8L);
        w.addProperty("isDay", isDay);
        w.addProperty("moonPhase", moonPhase);
        JsonObject time = new JsonObject();
        time.addProperty("dayTime", level.getDayTime());
        time.addProperty("timeOfDay", tod);
        time.addProperty("day", level.getDayTime() / 24000L);
        time.addProperty("isDay", isDay);
        time.addProperty("isNight", !fixedTime && tod >= 13000L && tod < 23000L);
        time.addProperty("phase", phaseName(tod));
        time.addProperty("moonPhase", moonPhase);
        time.addProperty("fixedTime", fixedTime);
        time.addProperty("sunAngle", MathUtil.round2(level.getTimeOfDay(1.0f) * 360.0f));
        time.addProperty("skyDarken", level.getSkyDarken());
        w.add("time", time);
        JsonObject weather = new JsonObject();
        weather.addProperty("rain", level.isRaining());
        weather.addProperty("thunder", level.isThundering());
        weather.addProperty("rainLevel", MathUtil.round2(level.getRainLevel(1.0f)));
        weather.addProperty("thunderLevel", MathUtil.round2(level.getThunderLevel(1.0f)));
        w.add("weather", weather);
        w.addProperty("difficulty", level.getDifficulty().name().toLowerCase());
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            w.addProperty("server", server.ip);
            w.addProperty("serverName", server.name);
        } else {
            w.addProperty("server", "singleplayer");
        }
        JsonArray players = new JsonArray();
        if (mc.getConnection() != null) {
            List<PlayerInfo> online = new ArrayList<>(mc.getConnection().getOnlinePlayers());
            online.sort(Comparator.comparing(pi -> pi.getProfile().getName()));
            for (int i = 0; i < Math.min(80, online.size()); i++) {
                PlayerInfo info = online.get(i);
                JsonObject o = new JsonObject();
                o.addProperty("name", info.getProfile().getName());
                o.addProperty("uuid", info.getProfile().getId().toString());
                GameType gm = info.getGameMode();
                o.addProperty("gameMode", gm == null ? "unknown" : gm.getName());
                o.addProperty("latency", info.getLatency());
                players.add(o);
            }
            w.addProperty("playerCount", online.size());
        } else {
            w.addProperty("playerCount", 1);
        }
        w.add("players", players);
        return w;
    }

    private static JsonObject screen(Minecraft mc, LocalPlayer player) {
        JsonObject s = new JsonObject();
        s.addProperty("inWorld", true);
        if (mc.screen != null) {
            s.addProperty("open", true);
            s.addProperty("class", mc.screen.getClass().getName());
            String title;
            try {
                title = mc.screen.getTitle().getString();
            } catch (Throwable t) {
                title = "";
            }
            s.addProperty("title", title);
        } else {
            s.addProperty("open", false);
        }
        s.addProperty("menuOpen", player.containerMenu != null && player.containerMenu != player.inventoryMenu);
        if (player.containerMenu != null) {
            s.addProperty("menuClass", player.containerMenu.getClass().getName());
            s.addProperty("menuSlots", player.containerMenu.slots.size());
        }
        return s;
    }

    private static JsonObject bridge() {
        JsonObject b = new JsonObject();
        b.addProperty("name", "mcp-bridge");
        b.addProperty("version", BridgeConfig.VERSION);
        b.addProperty("protocol", BridgeConfig.PROTOCOL);
        b.addProperty("loader", BridgeConfig.LOADER);
        b.addProperty("mcVersion", BridgeConfig.MC_VERSION);
        b.addProperty("uptimeMs", System.currentTimeMillis() - BridgeRuntime.startedAt);
        b.addProperty("baritoneLoaded", BaritoneControl.isLoaded());
        return b;
    }

    public static JsonObject stack(ItemStack stack) {
        JsonObject o = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            o.addProperty("id", "minecraft:air");
            o.addProperty("count", 0);
            return o;
        }
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getHoverName().getString());
        if (stack.isDamageableItem()) {
            o.addProperty("damage", stack.getDamageValue());
            o.addProperty("maxDamage", stack.getMaxDamage());
        }
        if (!stack.getComponentsPatch().isEmpty()) {
            o.addProperty("hasComponents", true);
            String tag = String.valueOf(stack.getComponentsPatch());
            o.addProperty("componentPreview", tag.length() > 200 ? tag.substring(0, 200) + "..." : tag);
        }
        return o;
    }
}
