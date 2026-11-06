package dev.mcp.bridge;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Screenshot requests are fulfilled on the render thread inside RenderGuiEvent.Post.
 * HTTP threads never touch the framebuffer.
 */
public final class ScreenshotCapture {
    private static final Logger LOG = LoggerFactory.getLogger("mcpbridge/screenshot");
    private static final ConcurrentLinkedQueue<Request> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private ScreenshotCapture() {
    }

    private static final class Request {
        final boolean withGui;
        final CompletableFuture<JsonObject> future = new CompletableFuture<>();

        private Request(boolean withGui) {
            this.withGui = withGui;
        }
    }

    public static CompletableFuture<JsonObject> request() {
        return request(false);
    }

    public static CompletableFuture<JsonObject> request(JsonObject args) {
        return request(Json.b(args, "withGui", false));
    }

    /**
     * ISSUE-15: {@code withGui=false} 保持旧行为（世界画面）；{@code true} 时先把 GUI 的
     * GUI 顶点缓冲 flush 到主渲染目标，再抓主渲染目标，这样书/告示牌/容器的界面会进到图里。
     */
    public static CompletableFuture<JsonObject> request(boolean withGui) {
        Request request = new Request(withGui);
        QUEUE.add(request);
        return request.future;
    }

    /** Runs on the render thread. */
    public static void tick(RenderGuiEvent.Post event) {
        Request request = QUEUE.poll();
        if (request == null) {
            return;
        }
        boolean flushed = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget target = mc.getMainRenderTarget();
            if (target == null) {
                request.future.completeExceptionally(Errors.internal("main render target is unavailable"));
                return;
            }
            if (request.withGui) {
                // RenderGuiEvent.Post 在 GuiGraphics.flush() 之前触发；不 flush 的话 GUI 还在顶点
                // 缓冲里，直接抓主目标只有 3D 画面（这正是 ISSUE-15 的现象）。
                try {
                    event.getGuiGraphics().flush();
                    flushed = true;
                } catch (Throwable t) {
                    LOG.debug("could not flush the GUI before the screenshot: {}", t.toString());
                }
            }
            NativeImage image = Screenshot.takeScreenshot(target);
            if (image == null) {
                request.future.completeExceptionally(Errors.internal("screenshot capture returned no image"));
                return;
            }
            Path dir = BridgeRuntime.screenshotDir;
            Files.createDirectories(dir);
            String suffix = request.withGui ? "-gui" : "";
            String name = "shot-" + System.currentTimeMillis() + "-" + SEQ.incrementAndGet() + suffix + ".png";
            Path file = dir.resolve(name);
            int width = image.getWidth();
            int height = image.getHeight();
            try {
                image.writeToFile(file);
            } finally {
                image.close();
            }
            JsonObject o = Json.ok();
            o.addProperty("path", file.toAbsolutePath().toString());
            o.addProperty("width", width);
            o.addProperty("height", height);
            o.addProperty("bytes", Files.size(file));
            o.addProperty("takenAt", System.currentTimeMillis());
            o.addProperty("withGui", request.withGui);
            o.addProperty("guiFlushed", flushed);
            request.future.complete(o);
        } catch (Throwable t) {
            LOG.debug("screenshot failed: {}", t.toString());
            request.future.completeExceptionally(Errors.internal("screenshot failed: " + t, t));
        }
    }

    public static void failAll(String reason) {
        Request request;
        while ((request = QUEUE.poll()) != null) {
            request.future.completeExceptionally(Errors.internal(reason));
        }
    }
}
