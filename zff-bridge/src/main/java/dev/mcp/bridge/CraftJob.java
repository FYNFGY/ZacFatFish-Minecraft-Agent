package dev.mcp.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafts a recipe with the vanilla recipe-placement packet (one click fills the grid) and shift-clicks
 * the result. 3x3 recipes open a nearby crafting table. A JEI-style fallback clicks the ingredients
 * into the grid one by one, which also works when the vanilla recipe book has not unlocked the recipe.
 */
public final class CraftJob implements Jobs.TickTask {
    private final String item;
    private final int count;
    private final BlockPos table;
    private final boolean forceManual;
    private RecipeHolder<CraftingRecipe> recipe;
    private BlockPos openTable;
    private boolean openedTable;
    private boolean needsTable;
    private boolean manual;
    private int phase;
    private int ticks;
    private int made;
    private int placements;
    private int baseline;
    private int containerId = -1;
    private final List<Integer> gridSlots = new ArrayList<>();
    private final List<Ingredient> gridIngredients = new ArrayList<>();
    private int manualIndex;
    private int manualState;
    private int manualWait;
    private int manualSrc = -1;
    private int invStart = 10;

    public CraftJob(String item, int count, BlockPos table, boolean forceManual) {
        this.item = item;
        this.count = MathUtil.clamp(count, 1, 64);
        this.table = table;
        this.forceManual = forceManual;
    }

    @Override
    public void start(Jobs.Job job) {
        job.metric("target", count);
        job.metric("made", 0);
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.getConnection() == null) {
            throw Errors.notLoaded("the player is not in a world");
        }
        if (item == null || item.isBlank()) {
            throw Errors.unsupported("item is required");
        }
        List<RecipeHolder<CraftingRecipe>> recipes = mc.getConnection().getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING);
        String vanillaId = item.contains(":") ? item : "minecraft:" + item;
        RecipeHolder<CraftingRecipe> fallback = null;
        for (RecipeHolder<CraftingRecipe> holder : recipes) {
            ItemStack result = holder.value().getResultItem(mc.level.registryAccess());
            if (result.isEmpty()
                    || !BuiltInRegistries.ITEM.getKey(result.getItem()).toString().equals(item)) {
                continue;
            }
            // Several mods register crafting recipes with the same output item; the vanilla one is
            // the one the recipe book can place and the one the player expects.
            if (holder.id().toString().equals(vanillaId)) {
                this.recipe = holder;
                break;
            }
            if (fallback == null) {
                fallback = holder;
            }
        }
        if (this.recipe == null) {
            this.recipe = fallback;
        }
        if (recipe == null) {
            throw Errors.unsupported("no crafting recipe for " + item);
        }
        job.metric("recipe", 0);
        int width = 3;
        int height = 3;
        if (recipe.value() instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
        } else if (recipe.value() instanceof net.minecraft.world.item.crafting.ShapelessRecipe shapeless) {
            int n = shapeless.getIngredients().size();
            width = n <= 2 ? 2 : 3;
            height = n <= 2 ? 1 : (n <= 4 ? 2 : 3);
        }
        boolean needsTable = width > 2 || height > 2;
        this.needsTable = needsTable;
        buildPlan(mc);
        manual = forceManual;
        if (needsTable) {
            BlockPos pos = table != null ? table : findTable(mc, player);
            if (pos == null) {
                throw Errors.unsupported("this recipe needs a crafting table; none within reach (pass table:[x,y,z])");
            }
            // 2026-11-02: if the table screen is already open, use it instead of right-clicking it again
            // (the old code re-opened while a screen was up and then timed out with "menu did not open").
            if (!(player.containerMenu instanceof net.minecraft.world.inventory.CraftingMenu)) {
                InteractionResult used = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                if (used == InteractionResult.FAIL) {
                    throw Errors.unsupported("the crafting table refused the interaction");
                }
            }
            openTable = pos;
            openedTable = true;
        } else if (player.containerMenu != player.inventoryMenu) {
            // A 2x2 recipe cannot use a table menu; close it so phase 1 can see the inventory menu.
            try {
                player.closeContainer();
                mc.setScreen(null);
            } catch (Throwable ignored) { /* cosmetic */ }
        }
        baseline = countInInventory(player, item);
        phase = 1;
    }

    @Override
    public void tick(Jobs.Job job) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            job.fail(Errors.DISCONNECTED, "the player left the world");
            return;
        }
        // Count what actually landed in the inventory: the result slot read can race with the
        // server's placement/take, and several mods can produce the same item id.
        int current = countInInventory(player, item);
        made = current - baseline;
        if (made >= count) {
            finish(job);
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (manual) {
            tickManual(job, player, menu);
            return;
        }
        switch (phase) {
            case 1 -> {
                // Wait for the right menu: a 3x3 recipe must go into the table's CraftingMenu, not
                // into the player's 2x2 inventory grid (placing too early silently produced nothing).
                boolean ready;
                if (needsTable) {
                    ready = menu instanceof net.minecraft.world.inventory.CraftingMenu;
                } else {
                    ready = menu == player.inventoryMenu;
                }
                if (ready) {
                    containerId = menu.containerId;
                    phase = 2;
                    ticks = 0;
                } else if (++ticks > 60) {
                    job.fail(Errors.TIMEOUT, needsTable
                            ? "the crafting table menu did not open"
                            : "no crafting menu opened");
                }
            }
            case 2 -> {
                if (++ticks >= 2) {
                    if (placements++ > 3) {
                        // The recipe book did not fill the grid (locked recipe / modded recipe):
                        // fall back to JEI-style ingredient clicks.
                        manual = true;
                        manualIndex = 0;
                        manualState = 0;
                        manualWait = 0;
                        return;
                    }
                    // false = place exactly one recipe worth; true would fill the whole grid and
                    // craft more than the requested count in one take.
                    mc.gameMode.handlePlaceRecipe(containerId, recipe, false);
                    phase = 3;
                    ticks = 0;
                }
            }
            case 3 -> {
                if (++ticks < 2) {
                    return;
                }
                ItemStack result = menu.getSlot(0).getItem();
                if (result.isEmpty()) {
                    phase = 2;
                    ticks = 0;
                    return;
                }
                int n = result.getCount();
                mc.gameMode.handleInventoryMouseClick(containerId, 0, 0, ClickType.QUICK_MOVE, player);
                ticks = 0;
            }
            default -> {
            }
        }
    }

    /** Builds the JEI-style ingredient plan: which grid slot receives which ingredient. */
    private void buildPlan(Minecraft mc) {
        gridSlots.clear();
        gridIngredients.clear();
        if (recipe == null) {
            return;
        }
        int gridSize = needsTable ? 3 : 2;
        invStart = needsTable ? 10 : 9;
        if (recipe.value() instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            List<Ingredient> ingredients = shaped.getIngredients();
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    int idx = r * w + c;
                    if (idx >= ingredients.size()) {
                        continue;
                    }
                    Ingredient ingredient = ingredients.get(idx);
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    gridSlots.add(1 + r * gridSize + c);
                    gridIngredients.add(ingredient);
                }
            }
        } else if (recipe.value() instanceof ShapelessRecipe shapeless) {
            List<Ingredient> ingredients = shapeless.getIngredients();
            for (int i = 0; i < ingredients.size() && i < gridSize * gridSize; i++) {
                Ingredient ingredient = ingredients.get(i);
                if (ingredient.isEmpty()) {
                    continue;
                }
                gridSlots.add(1 + i);
                gridIngredients.add(ingredient);
            }
        }
    }

    /** JEI-style crafting: pick up one item per ingredient, right-click it into the grid, return the rest. */
    private void tickManual(Jobs.Job job, LocalPlayer player, AbstractContainerMenu menu) {
        Minecraft mc = Minecraft.getInstance();
        if (menu == null) {
            return;
        }
        if (needsTable && !(menu instanceof net.minecraft.world.inventory.CraftingMenu)) {
            if (++ticks > 40) {
                job.fail(Errors.TIMEOUT, "the crafting table menu closed");
            }
            return;
        }
        if (!needsTable && menu != player.inventoryMenu) {
            if (++ticks > 40) {
                job.fail(Errors.TIMEOUT, "the inventory menu is not available");
            }
            return;
        }
        containerId = menu.containerId;
        switch (manualState) {
            case 0 -> {
                if (manualIndex >= gridSlots.size()) {
                    manualState = 3;
                    manualWait = 0;
                    return;
                }
                int gridSlot = gridSlots.get(manualIndex);
                if (!menu.getSlot(gridSlot).getItem().isEmpty()) {
                    manualIndex++;
                    return;
                }
                int src = findSourceSlot(menu, gridIngredients.get(manualIndex));
                if (src < 0) {
                    job.fail(Errors.UNSUPPORTED, "missing ingredient for the recipe, made " + made);
                    return;
                }
                mc.gameMode.handleInventoryMouseClick(containerId, src, 0, ClickType.PICKUP, player);
                manualSrc = src;
                manualState = 1;
            }
            case 1 -> {
                // Right-click places exactly one item from the cursor stack.
                mc.gameMode.handleInventoryMouseClick(containerId, gridSlots.get(manualIndex), 1,
                        ClickType.PICKUP, player);
                manualState = 2;
            }
            case 2 -> {
                mc.gameMode.handleInventoryMouseClick(containerId, manualSrc, 0, ClickType.PICKUP, player);
                manualIndex++;
                manualState = 0;
            }
            case 3 -> {
                if (++manualWait < 2) {
                    return;
                }
                ItemStack result = menu.getSlot(0).getItem();
                if (result.isEmpty()) {
                    job.fail(Errors.UNSUPPORTED, "manual placement produced no result, made " + made);
                    return;
                }
                mc.gameMode.handleInventoryMouseClick(containerId, 0, 0, ClickType.QUICK_MOVE, player);
                manualIndex = 0;
                manualState = 0;
                manualWait = 0;
            }
            default -> {
            }
        }
    }

    private int findSourceSlot(AbstractContainerMenu menu, Ingredient ingredient) {
        for (int i = invStart; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && ingredient.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private void finish(Jobs.Job job) {
        if (openedTable) {
            try {
                Minecraft.getInstance().player.closeContainer();
                Minecraft.getInstance().setScreen(null);
            } catch (Throwable ignored) {
                // the table screen is cosmetic
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", item);
        result.addProperty("made", made);
        if (openTable != null) {
            result.addProperty("table", openTable.getX() + "," + openTable.getY() + "," + openTable.getZ());
        }
        job.succeed(result);
    }

    private static int countInInventory(LocalPlayer player, String itemId) {
        if (player == null || itemId == null) {
            return 0;
        }
        int total = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static BlockPos findTable(Minecraft mc, LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        BlockPos best = null;
        double bestDistance = 5.0 * 5.0;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                        continue;
                    }
                    double distance = player.distanceToSqr(Vec3.atCenterOf(pos));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos.immutable();
                    }
                }
            }
        }
        return best;
    }

    @Override
    public void cancel(Jobs.Job job) {
        if (openedTable) {
            try {
                Minecraft.getInstance().player.closeContainer();
                Minecraft.getInstance().setScreen(null);
            } catch (Throwable ignored) {
                // nothing to close
            }
        }
    }
}
