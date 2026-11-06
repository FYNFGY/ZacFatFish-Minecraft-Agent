package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JEI is optional. Everything here is reflective so the bridge loads and runs when JEI is absent.
 * JEI's recipe lookup is the only source of KubeJS/virtual recipes that never become vanilla Recipe objects.
 */
public final class JeiRecipes {
    private static final Logger LOG = LoggerFactory.getLogger("mcpbridge/jei");
    private static volatile boolean warned;

    private JeiRecipes() {
    }

    public static JsonArray lookup(String query, String kind, int limit) {
        try {
            Object runtime = runtime();
            if (runtime == null) {
                return null;
            }
            Class<?> roleClass = Class.forName("mezz.jei.api.recipe.RecipeIngredientRole");
            Class<?> typeClass = Class.forName("mezz.jei.api.ingredients.IIngredientType");
            Object itemType = Class.forName("mezz.jei.api.constants.VanillaTypes").getField("ITEM_STACK").get(null);
            Item item = Recipes.findItem(query);
            if (item == null) {
                return new JsonArray();
            }
            Object helpers = invoke(runtime, "mezz.jei.api.runtime.IJeiRuntime", "getJeiHelpers");
            Object focusFactory = invoke(helpers, "mezz.jei.api.helpers.IJeiHelpers", "getFocusFactory");
            Object recipeManager = invoke(runtime, "mezz.jei.api.runtime.IJeiRuntime", "getRecipeManager");
            JsonArray out = new JsonArray();
            Set<String> seen = new HashSet<>();
            boolean wantOutput = kind.equals("output") || kind.equals("any");
            boolean wantInput = kind.equals("input") || kind.equals("any");
            if (wantOutput) {
                collect(recipeManager, focusFactory, item, itemType, roleClass, typeClass, "OUTPUT", out, seen, limit);
            }
            if (out.size() < limit && wantInput) {
                collect(recipeManager, focusFactory, item, itemType, roleClass, typeClass, "INPUT", out, seen, limit);
            }
            return out;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                LOG.warn("JEI recipe lookup unavailable: {}", t.toString());
            }
            return null;
        }
    }

    private static void collect(Object recipeManager, Object focusFactory, Item item, Object itemType,
                                Class<?> roleClass, Class<?> typeClass, String roleName,
                                JsonArray out, Set<String> seen, int limit) throws Exception {
        Object role = null;
        for (Object candidate : roleClass.getEnumConstants()) {
            if (String.valueOf(candidate).equals(roleName)) {
                role = candidate;
                break;
            }
        }
        if (role == null) {
            return;
        }
        ItemStack stack = new ItemStack(item);
        Object focus = invoke(focusFactory, "mezz.jei.api.recipe.IFocusFactory", "createFocus",
                new Class[]{roleClass, typeClass, Object.class}, role, itemType, stack);
        List<Object> focuses = List.of(focus);
        Object catLookup = invoke(recipeManager, "mezz.jei.api.recipe.IRecipeManager", "createRecipeCategoryLookup");
        catLookup = invoke(catLookup, "mezz.jei.api.recipe.IRecipeCategoriesLookup", "limitFocus",
                new Class[]{Collection.class}, focuses);
        catLookup = invoke(catLookup, "mezz.jei.api.recipe.IRecipeCategoriesLookup", "includeHidden");
        Stream<?> categories = (Stream<?>) invoke(catLookup, "mezz.jei.api.recipe.IRecipeCategoriesLookup", "get");
        int categoryCount = 0;
        try {
            var it = categories.iterator();
            while (it.hasNext() && categoryCount++ < 60 && out.size() < limit) {
                Object category = it.next();
                if (category == null) {
                    continue;
                }
                Object recipeType = invoke(category, "mezz.jei.api.recipe.category.IRecipeCategory", "getRecipeType");
                if (recipeType == null) {
                    continue;
                }
                Object lookup = invoke(recipeManager, "mezz.jei.api.recipe.IRecipeManager", "createRecipeLookup",
                        new Class[]{Class.forName("mezz.jei.api.recipe.RecipeType")}, recipeType);
                lookup = invoke(lookup, "mezz.jei.api.recipe.IRecipeLookup", "limitFocus",
                        new Class[]{Collection.class}, focuses);
                lookup = invoke(lookup, "mezz.jei.api.recipe.IRecipeLookup", "includeHidden");
                Stream<?> recipes = (Stream<?>) invoke(lookup, "mezz.jei.api.recipe.IRecipeLookup", "get");
                int recipeCount = 0;
                try {
                    var rit = recipes.iterator();
                    while (rit.hasNext() && recipeCount++ < 100 && out.size() < limit) {
                        Object recipe = rit.next();
                        String id = recipeId(category, recipe, recipeType, recipeCount);
                        if (!seen.add(id)) {
                            continue;
                        }
                        Object supplier = invoke(recipeManager, "mezz.jei.api.recipe.IRecipeManager", "getRecipeIngredients",
                                new Class[]{Class.forName("mezz.jei.api.recipe.category.IRecipeCategory"), Object.class},
                                category, recipe);
                        if (supplier == null) {
                            continue;
                        }
                        JsonArray outputs = ingredients(supplier, roleClass, "OUTPUT");
                        JsonArray inputs = ingredients(supplier, roleClass, "INPUT");
                        if (outputs.size() == 0 && inputs.size() == 0) {
                            continue;
                        }
                        JsonObject o = new JsonObject();
                        o.addProperty("recipeId", id);
                        o.addProperty("type", typeUid(recipeType));
                        o.addProperty("station", typeUid(recipeType));
                        try {
                            Object title = invoke(category, "mezz.jei.api.recipe.category.IRecipeCategory", "getTitle");
                            if (title != null) {
                                o.addProperty("category", String.valueOf(title));
                            }
                        } catch (Throwable ignored) {
                        }
                        o.add("outputs", outputs);
                        o.add("inputs", inputs);
                        o.add("output", outputs.size() > 0 ? outputs.get(0) : new JsonObject());
                        o.addProperty("source", "jei");
                        o.addProperty("matchedRole", roleName);
                        out.add(o);
                    }
                } finally {
                    close(recipes);
                }
            }
        } finally {
            close(categories);
        }
    }

    private static JsonArray ingredients(Object supplier, Class<?> roleClass, String roleName) throws Exception {
        JsonArray out = new JsonArray();
        Object role = null;
        for (Object candidate : roleClass.getEnumConstants()) {
            if (String.valueOf(candidate).equals(roleName)) {
                role = candidate;
                break;
            }
        }
        if (role == null) {
            return out;
        }
        Object listObj = invoke(supplier, "mezz.jei.api.ingredients.IIngredientSupplier", "getIngredients",
                new Class[]{roleClass}, role);
        if (!(listObj instanceof List<?> list)) {
            return out;
        }
        for (int i = 0; i < Math.min(16, list.size()); i++) {
            Object typed = list.get(i);
            if (typed == null) {
                continue;
            }
            try {
                Object opt = invoke(typed, "mezz.jei.api.ingredients.ITypedIngredient", "getItemStack");
                if (opt instanceof Optional<?> o && o.isPresent() && o.get() instanceof ItemStack stack && !stack.isEmpty()) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                    item.addProperty("count", stack.getCount());
                    item.addProperty("name", stack.getHoverName().getString());
                    out.add(item);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static String recipeId(Object category, Object recipe, Object recipeType, int index) {
        try {
            Object id = invoke(category, "mezz.jei.api.recipe.category.IRecipeCategory", "getRegistryName",
                    new Class[]{Object.class}, recipe);
            if (id != null) {
                return String.valueOf(id);
            }
        } catch (Throwable ignored) {
        }
        return typeUid(recipeType) + "#" + index;
    }

    private static String typeUid(Object recipeType) {
        try {
            Object uid = invoke(recipeType, "mezz.jei.api.recipe.RecipeType", "getUid");
            if (uid != null) {
                return String.valueOf(uid);
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(recipeType);
    }

    private static Object runtime() throws Exception {
        Class<?> internal = Class.forName("mezz.jei.common.Internal");
        Method get = internal.getMethod("getJeiRuntime");
        return get.invoke(null);
    }

    private static Object invoke(Object target, String iface, String method, Class<?>[] params, Object... args) throws Exception {
        Method m = Class.forName(iface).getMethod(method, params);
        return m.invoke(target, args);
    }

    private static Object invoke(Object target, String iface, String method) throws Exception {
        return invoke(target, iface, method, new Class<?>[0]);
    }

    private static void close(Stream<?> stream) {
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }
}
