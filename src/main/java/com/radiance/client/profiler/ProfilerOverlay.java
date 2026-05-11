package com.radiance.client.profiler;

import com.radiance.client.RadianceClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class ProfilerOverlay {

    private static final int MAX_OVERLAY_ROWS = 18;
    private static final DateTimeFormatter DUMP_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static boolean overlayEnabled;
    private static boolean f8WasDown;
    private static boolean f9WasDown;

    private ProfilerOverlay() {
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            return;
        }

        long handle = client.getWindow().getHandle();
        boolean f8Down = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F8) == GLFW.GLFW_PRESS;
        boolean f9Down = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_F9) == GLFW.GLFW_PRESS;

        if (f8Down && !f8WasDown) {
            setEnabled(!overlayEnabled);
            client.inGameHud.getChatHud().addMessage(Text.literal(
                "Radiance profiler " + (overlayEnabled ? "enabled" : "disabled")));
        }
        if (f9Down && !f9WasDown) {
            dumpSnapshot();
            client.inGameHud.getChatHud().addMessage(Text.literal("Radiance profiler snapshot dumped"));
        }

        f8WasDown = f8Down;
        f9WasDown = f9Down;
    }

    public static void render(DrawContext context) {
        if (!overlayEnabled) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        String[] lines = nativeSnapshotText(MAX_OVERLAY_ROWS).split("\\R");
        int y = 6;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            context.drawText(client.textRenderer, line, 6, y, 0xE0FFFFFF, true);
            y += 10;
        }
    }

    public static void setEnabled(boolean enabled) {
        overlayEnabled = enabled;
        nativeSetEnabled(enabled);
        if (enabled) {
            nativeReset();
        }
    }

    public static void dumpSnapshot() {
        String text = nativeSnapshotText(64);
        Path path = RadianceClient.radianceDir.resolve(
            "profiler-" + LocalDateTime.now().format(DUMP_TIMESTAMP) + ".txt");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to dump Radiance profiler snapshot to " + path, e);
        }
    }

    public static native void nativeSetEnabled(boolean enabled);

    public static native boolean nativeIsEnabled();

    public static native void nativeReset();

    public static native String nativeSnapshotText(int maxRows);
}
