package dev.mcp.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.events.GuiEventListener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Ph8 M2.2: a small generic GUI protocol.
 *
 * <p>It only relies on vanilla Screen/AbstractWidget/EditBox/AbstractSliderButton. Modded
 * AbstractContainerScreens get their slots through the existing container_* tools; their standard
 * buttons/tabs can be clicked here. Fully custom canvas screens still need gui_click_at (next step).</p>
 */
public final class GuiTools {
    private GuiTools() {
    }

    public static JsonObject describe() {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        JsonObject o = new JsonObject();
        if (screen == null) {
            o.addProperty("open", false);
            return o;
        }
        o.addProperty("open", true);
        o.addProperty("class", screen.getClass().getName());
        o.addProperty("title", screen.getTitle() == null ? "" : screen.getTitle().getString());
        JsonArray widgets = new JsonArray();
        // Create's ValueSettingsScreen is a custom canvas, not an AbstractContainerScreen. Expose its
        // board value as one virtual widget so CR can read/set the speed controller without OCR.
        if (isCreateValueScreen(screen)) {
            JsonObject j = new JsonObject();
            j.addProperty("index", 0);
            j.addProperty("type", "CreateValueSettingsScreen");
            j.addProperty("kind", "create_value");
            j.addProperty("label", "Create value settings");
            j.addProperty("value", createValue(screen));
            j.addProperty("max", createMax(screen));
            j.addProperty("row", createRow(screen));
            widgets.add(j);
            o.add("widgets", widgets);
            return o;
        }
        List<? extends GuiEventListener> children = screen.children();
        for (int i = 0; i < children.size(); i++) {
            GuiEventListener child = children.get(i);
            if (!(child instanceof AbstractWidget w)) continue;
            JsonObject j = new JsonObject();
            j.addProperty("index", i);
            j.addProperty("type", w.getClass().getSimpleName());
            j.addProperty("label", w.getMessage() == null ? "" : w.getMessage().getString());
            j.addProperty("x", w.getX());
            j.addProperty("y", w.getY());
            j.addProperty("w", w.getWidth());
            j.addProperty("h", w.getHeight());
            j.addProperty("active", w.active);
            j.addProperty("visible", w.visible);
            if (w instanceof EditBox e) {
                j.addProperty("value", e.getValue());
                j.addProperty("kind", "editbox");
            } else if (w instanceof AbstractSliderButton s) {
                Double v = sliderValue(s);
                if (v != null) j.addProperty("value", v);
                j.addProperty("kind", "slider");
            } else {
                j.addProperty("kind", "widget");
            }
            widgets.add(j);
        }
        o.add("widgets", widgets);
        if (screen instanceof AbstractContainerScreen<?> cs) {
            JsonObject menu = new JsonObject();
            menu.addProperty("class", cs.getMenu().getClass().getName());
            menu.addProperty("slots", cs.getMenu().slots.size());
            o.add("menu", menu);
        }
        return o;
    }

    public static JsonObject click(int index) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) throw Errors.notLoaded("no screen is open");
        AbstractWidget w = widget(screen, index);
        if (w == null) throw Errors.internal("no widget at index " + index);
        if (!w.active || !w.visible) return result(false, "widget is not active/visible", index);
        int x = w.getX() + Math.max(1, w.getWidth() / 2);
        int y = w.getY() + Math.max(1, w.getHeight() / 2);
        boolean ok;
        try {
            ok = w.mouseClicked(x, y, 0);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            try {
                w.onClick(x, y);
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            }
        }
        return result(ok, ok ? "clicked" : "click was not handled", index);
    }

    public static JsonObject setValue(int index, double value) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) throw Errors.notLoaded("no screen is open");
        if (isCreateValueScreen(screen)) return setCreateValue(screen, value);
        AbstractWidget w = widget(screen, index);
        if (w == null) {
            throw Errors.internal("no widget at index " + index);
        }
        if (w instanceof EditBox e) {
            e.setValue(String.valueOf(value));
            return result(true, "editbox set", index);
        }
        if (w instanceof AbstractSliderButton s) {
            try {
                Field f = AbstractSliderButton.class.getDeclaredField("value");
                f.setAccessible(true);
                f.setDouble(s, Math.max(0.0, Math.min(1.0, value)));
                Method apply = AbstractSliderButton.class.getDeclaredMethod("applyValue");
                apply.setAccessible(true);
                apply.invoke(s);
                return result(true, "slider set", index);
            } catch (Throwable t) {
                return result(false, "slider set failed: " + t, index);
            }
        }
        return result(false, "widget is not a slider/editbox", index);
    }

    public static JsonObject close() {
        Minecraft.getInstance().setScreen(null);
        JsonObject o = Json.ok();
        o.addProperty("closed", true);
        return o;
    }

    public static JsonObject clickAt(int x, int y) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) throw Errors.notLoaded("no screen is open");
        boolean ok = screen.mouseClicked(x, y, 0);
        if (ok) screen.mouseReleased(x, y, 0);
        return result(ok, ok ? "clicked_at" : "click_at was not handled", -1);
    }

    public static JsonObject scroll(int x, int y, double delta) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null) throw Errors.notLoaded("no screen is open");
        boolean ok = screen.mouseScrolled(x, y, 0, delta);
        return result(ok, ok ? "scrolled" : "scroll was not handled", -1);
    }

    private static boolean isCreateValueScreen(Screen screen) {
        return screen != null && screen.getClass().getName().contains("ValueSettingsScreen");
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object board(Screen screen) {
        return field(screen, "board");
    }

    private static int createMax(Screen screen) {
        Object board = board(screen);
        if (board == null) return -1;
        try {
            Method m = board.getClass().getMethod("maxValue");
            Object v = m.invoke(board);
            return v instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int createValue(Screen screen) {
        Object settings = field(screen, "initialSettings");
        if (settings == null) return -1;
        try {
            Method m = settings.getClass().getMethod("value");
            Object v = m.invoke(settings);
            return v instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int createRow(Screen screen) {
        Object settings = field(screen, "initialSettings");
        if (settings == null) return 0;
        try {
            Method m = settings.getClass().getMethod("row");
            Object v = m.invoke(settings);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static JsonObject setCreateValue(Screen screen, double value) {
        int max = createMax(screen);
        if (max < 0) return result(false, "Create board maxValue unavailable", 0);
        int v = (int) Math.max(0, Math.min(max, Math.round(value)));
        try {
            Method m = screen.getClass().getMethod("getCoordinateOfValue", int.class, int.class);
            Object coord = m.invoke(screen, createRow(screen), v);
            double cx = 0;
            double cy = 0;
            if (coord != null) {
                Field fx = coord.getClass().getField("x");
                Field fy = coord.getClass().getField("y");
                cx = fx.getDouble(coord);
                cy = fy.getDouble(coord);
            }
            screen.mouseClicked(cx, cy, 0);
            screen.mouseReleased(cx, cy, 0);
            return result(true, "create value set to " + v, 0);
        } catch (Throwable t) {
            return result(false, "create value set failed: " + t, 0);
        }
    }

    private static JsonObject result(boolean ok, String message, int index) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("index", index);
        o.addProperty("message", message);
        return o;
    }

    private static AbstractWidget widget(Screen screen, int index) {
        List<? extends GuiEventListener> children = screen.children();
        if (index < 0 || index >= children.size()) return null;
        GuiEventListener child = children.get(index);
        return child instanceof AbstractWidget w ? w : null;
    }

    private static Double sliderValue(AbstractSliderButton s) {
        try {
            Field f = AbstractSliderButton.class.getDeclaredField("value");
            f.setAccessible(true);
            return f.getDouble(s);
        } catch (Throwable t) {
            return null;
        }
    }
}
