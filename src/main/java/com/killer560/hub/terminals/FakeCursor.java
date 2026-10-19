package com.killer560.hub.terminals;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

/**
 * The look of a person clicking, for screen sharing (killer560, 2026-10-19). Two parts, both only while Auto Terminals
 * clicks are landing:
 * <ul>
 *   <li><b>Hide Mouse Cursor</b>: the real pointer is hidden for the few seconds after an auto click, so nothing moves
 *       on its own, and shown again when the clicks stop. Nothing is hidden when no auto click has happened.</li>
 *   <li><b>Fake Cursor</b>: a pointer is drawn that travels to the slot each auto click lands on, with a short ripple
 *       where it clicked. It is drawn over the terminal only; it does not move the real pointer or click anything.</li>
 * </ul>
 * Both read {@link #clickAt}, which {@link TerminalSolverFeature#sendTerminalClick} calls after a real click went out.
 */
public final class FakeCursor {

    /** How long after the last auto click the pointer stays visible and hidden-state is held. */
    private static final long ACTIVE_MS = 3000L;
    private static final long RIPPLE_MS = 350L;
    /** Share of the remaining distance covered each frame - a quick glide, not a snap. */
    private static final float GLIDE = 0.35f;
    /** A small arrow, one row per entry; the value is the row's width in pixels. */
    private static final int[] ARROW = {1, 2, 3, 4, 5, 6, 7, 5, 3, 2};

    private static volatile int targetSlot = -1;
    private static volatile long clickedAtMs = 0L;
    private static float drawnX = -1f;
    private static float drawnY = -1f;
    private static boolean hidden;

    private FakeCursor() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FakeCursor::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) {
                return;
            }
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
                try {
                    render(container, graphics);
                } catch (RuntimeException e) {
                    // Cosmetic only: a bad frame must never reach the screen or the auto click.
                    targetSlot = -1;
                }
            });
        });
    }

    /** Called after a real auto click went out on {@code slotIndex} of the open terminal. */
    public static void clickAt(int slotIndex) {
        targetSlot = slotIndex;
        clickedAtMs = System.currentTimeMillis();
    }

    private static boolean recentlyClicked(long now) {
        return targetSlot >= 0 && now - clickedAtMs <= ACTIVE_MS;
    }

    private static void tick(Minecraft client) {
        com.killer560.hub.terminals.TerminalSolverConfig cfg = com.killer560.hub.terminals.TerminalSolverConfig.getInstance();
        boolean wantHidden = cfg.isHideMouseCursor() && cfg.isAutoTerminalsEnabled()
                && McCompat.screen(client) instanceof AbstractContainerScreen<?> && recentlyClicked(System.currentTimeMillis());
        if (client.getWindow() == null) {
            return;
        }
        long handle = client.getWindow().handle();
        if (wantHidden) {
            // Re-asserted every tick: the game sets its own cursor mode when a screen opens or closes.
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
            hidden = true;
        } else if (hidden) {
            GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            hidden = false;
        }
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        if (!com.killer560.hub.terminals.TerminalSolverConfig.getInstance().isFakeCursor()) {
            return;
        }
        long now = System.currentTimeMillis();
        int slotIndex = targetSlot;
        if (!recentlyClicked(now) || slotIndex >= screen.getMenu().slots.size()) {
            drawnX = -1f;
            return;
        }
        Slot slot = screen.getMenu().slots.get(slotIndex);
        AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
        float tx = acc.killer560smod$getLeftPos() + slot.x + 8f;
        float ty = acc.killer560smod$getTopPos() + slot.y + 8f;
        if (drawnX < 0f) {
            drawnX = tx;
            drawnY = ty;
        }
        drawnX += (tx - drawnX) * GLIDE;
        drawnY += (ty - drawnY) * GLIDE;
        int x = Math.round(drawnX);
        int y = Math.round(drawnY);

        // Click ripple at the slot, growing and fading for a moment after the click.
        long sinceClick = now - clickedAtMs;
        if (sinceClick < RIPPLE_MS) {
            float t = sinceClick / (float) RIPPLE_MS;
            int r = 3 + Math.round(9f * t);
            int alpha = Math.round(255f * (1f - t));
            int color = (alpha << 24) | 0xFFFFFF;
            g.outline(Math.round(tx) - r, Math.round(ty) - r, r * 2, r * 2, color);
        }

        // The arrow: a black edge (drawn one pixel larger) under a white fill.
        for (int row = 0; row < ARROW.length; row++) {
            int w = ARROW[row];
            g.fill(x - 1, y + row - 1, x + w + 1, y + row + 2, 0xFF000000);
        }
        for (int row = 0; row < ARROW.length; row++) {
            int w = ARROW[row];
            g.fill(x, y + row, x + w, y + row + 1, 0xFFFFFFFF);
        }
    }
}
