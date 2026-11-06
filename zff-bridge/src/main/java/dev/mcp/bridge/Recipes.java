package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Recipes are read from the live RecipeManager (KubeJS/datapack changes included), with JEI as a virtual-recipe fallback. */
public final class Recipes {
    private Recipes() {
    }

    public static JsonObject lookup(JsonObject args) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        String query = Json.requireStr(args, "query").trim();
        String kind = Json.str(args, "kind", "output").toLowerCase(Locale.ROOT);
        int limit = MathUtil.clamp(Json.i(args, "limit", 20), 1, 80);
        RecipeManager manager = level.getRecipeManager();
        if (manager == null) {
            throw Errors.notLoaded("recipe manager is not available yet");
        }
        Item target = findItem(query);
        String q = query.toLowerCase(Locale.ROOT);
        JsonArray out = new JsonArray();
        int matched = 0;
        boolean truncated = false;
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            if (out.size() >= limit) {
                truncated = true;
                break;
            }
            try {
                Recipe<?> recipe = holder.value();
                ItemStack result = recipe.getResultItem(level.registryAccess());
                boolean outputMatch = result != null && !result.isEmpty() && matches(result, target, q);
                boolean inputMatch = false;
                List<Ingredient> ingredients = recipe.getIngredients();
                if (!outputMatch && (kind.equals("input") || kind.equals("any"))) {
                    for (Ingredient ingredient : ingredients) {
                        for (ItemStack option : ingredient.getItems()) {
                            if (matches(option, target, q)) {
                                inputMatch = true;
                                break;
                            }
                        }
                        if (inputMatch) {
                            break;
                        }
                    }
                }
                if (kind.equals("output") && !outputMatch) {
                    continue;
                }
                if (kind.equals("input") && !inputMatch) {
                    continue;
                }
                if (kind.equals("any") && !outputMatch && !inputMatch) {
                    continue;
                }
                matched++;
                out.add(entry(holder, recipe, result, ingredients, outputMatch, inputMatch));
            } catch (Throwable ignored) {
                // A single broken/modded recipe must not make the whole lookup fail.
            }
        }

        JsonArray jei = null;
        boolean jeiAvailable = false;
        if (ModList.get().isLoaded("jei")) {
            try {
                jei = JeiRecipes.lookup(query, kind, Math.max(1, limit - out.size()));
                jeiAvailable = jei != null;
                if (jei != null && jei.size() > 0) {
                    for (int i = 0; i < jei.size() && out.size() < limit; i++) {
                        out.add(jei.get(i));
                        matched++;
                    }
                }
            } catch (Throwable ignored) {
                jeiAvailable = false;
            }
        }

        JsonObject o = Json.ok();
        o.addProperty("query", query);
        o.addProperty("kind", kind);
        o.addProperty("source", "minecraft");
        o.addProperty("jeiLoaded", ModList.get().isLoaded("jei"));
        o.addProperty("jeiLookupRan", jeiAvailable);
        o.addProperty("matched", matched);
        o.addProperty("truncated", truncated || out.size() >= limit);
        o.add("recipes", out);
        return o;
    }

    private static JsonObject entry(RecipeHolder<?> holder, Recipe<?> recipe, ItemStack result,
                                    List<Ingredient> ingredients, boolean outputMatch, boolean inputMatch) {
        JsonObject o = new JsonObject();
        o.addProperty("recipeId", holder.id().toString());
        o.addProperty("type", BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
        o.addProperty("serializer", BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()).toString());
        o.addProperty("station", BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());
        String group = recipe.getGroup();
        if (group != null && !group.isBlank()) {
            o.addProperty("group", group);
        }
        o.addProperty("outputMatch", outputMatch);
        o.addProperty("inputMatch", inputMatch);
        JsonObject out = new JsonObject();
        if (result != null && !result.isEmpty()) {
            out.addProperty("id", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
            out.addProperty("count", result.getCount());
            out.addProperty("name", result.getHoverName().getString());
        }
        o.add("output", out);
        JsonArray inputs = new JsonArray();
        int slots = 0;
        for (Ingredient ingredient : ingredients) {
            if (slots++ >= 12) {
                break;
            }
            JsonArray alternatives = new JsonArray();
            ItemStack[] options = ingredient.getItems();
            for (int i = 0; i < Math.min(8, options.length); i++) {
                alternatives.add(BuiltInRegistries.ITEM.getKey(options[i].getItem()).toString());
            }
            inputs.add(alternatives);
        }
        o.add("inputs", inputs);
        o.addProperty("source", "minecraft");
        return o;
    }

    static Item findItem(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(query);
        if (id != null) {
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return item;
            }
        }
        String q = query.toLowerCase(Locale.ROOT);
        for (Item item : BuiltInRegistries.ITEM) {
            try {
                if (item.getDescription().getString().toLowerCase(Locale.ROOT).contains(q)
                        || BuiltInRegistries.ITEM.getKey(item).toString().contains(q)) {
                    return item;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static boolean matches(ItemStack stack, Item target, String query) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (target != null && stack.getItem() == target) {
            return true;
        }
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().toLowerCase(Locale.ROOT);
        if (id.contains(query)) {
            return true;
        }
        try {
            return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query);
        } catch (Throwable t) {
            return false;
        }
    }

    static List<String> ids(Ingredient ingredient) {
        List<String> list = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            list.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        }
        return list;
    }
}
