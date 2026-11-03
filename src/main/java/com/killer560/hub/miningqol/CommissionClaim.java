package com.killer560.hub.miningqol;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.ChatObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Claiming a finished commission (killer560, 2026-11-03: "it should pathfind to the nearest emissary whenever it's time
 * to claim one if emissary pathfinding is on. if it is off then have it pulsate/highlight the royal pigeon in their
 * hotbar").
 *
 * <p>A claim is due while the tab shows a commission DONE, and for a few seconds after the chat line
 * "NAME Commission Complete! Visit the King to claim your rewards!" (the tab lags). With Route To Emissary on, the
 * {@link CommissionRoute} line goes to the nearest Emissary; with it off, the Royal Pigeon in his hotbar (if he has one)
 * pulses. Draws only: nothing is aimed, clicked or used.
 */
public final class CommissionClaim {

    private static final Pattern COMPLETE = Pattern.compile("^.{1,60} Commission Complete! Visit the King to claim your rewards!$");
    private static final long CHAT_HOLD_MS = 20_000;

    private static volatile long chatUntil = 0;
    private static int pulseFrames = 0;
    private static int lastPulseSlot = -1;

    private CommissionClaim() {
    }

    static void register() {
        ChatObserver.subscribe(CommissionClaim::onChat);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "mining_pigeon_pulse"),
                (graphics, deltaTracker) -> renderPulse(graphics));
    }

    static void onChat(Component message) {
        String raw = ChatObserver.strip(message);
        if (raw != null) {
            handle(raw, System.currentTimeMillis());
        }
    }

    /** A chat line (any thread). */
    static void handle(String raw, long now) {
        if (raw.length() <= 100 && COMPLETE.matcher(raw).matches()) {
            chatUntil = now + CHAT_HOLD_MS;
        }
    }

    /** Whether a finished commission waits to be claimed. */
    public static boolean claimDue() {
        return MiningState.dwarvenMines() && (MiningState.anyDone() || System.currentTimeMillis() < chatUntil);
    }

    /** The claim line goes to an Emissary (Commission Route on, Route To Emissary on, a claim due). */
    public static boolean routeToEmissary() {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        return cfg.isCommissionRoute() && cfg.isCommissionRouteEmissary() && claimDue();
    }

    /** The Royal Pigeon pulses (Commission Route on, Route To Emissary off, a claim due). */
    public static boolean pigeonPulse() {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        return cfg.isCommissionRoute() && !cfg.isCommissionRouteEmissary() && claimDue();
    }

    /** Emissary spots (the ones the Commission HUD labels). */
    static List<BlockPos> emissaries() {
        return CommissionHud.emissaryPositions();
    }

    /** The hotbar slot (0-8) holding a Royal Pigeon, or -1. */
    public static int pigeonSlot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (!s.isEmpty() && "ROYAL_PIGEON".equals(ItemIdentity.skyblockId(s))) {
                return i;
            }
        }
        return -1;
    }

    /** Pulse strength 0..1 at {@code ms}: a smooth beat about twice a second. */
    static float pulse(long ms) {
        return 0.5f + 0.5f * (float) Math.sin(ms / 1000.0 * Math.PI * 4);
    }

    private static void renderPulse(GuiGraphicsExtractor graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || com.killer560.hub.hud.HudVisibility.hidesHud() || !pigeonPulse()) {
            return;
        }
        int slot = pigeonSlot();
        if (slot < 0) {
            return;
        }
        int x = graphics.guiWidth() / 2 - 90 + slot * 20 + 2;
        int y = graphics.guiHeight() - 19;
        int a = (int) (70 + 150 * pulse(System.currentTimeMillis()));
        int fill = (a << 24) | 0x00FFD040;
        int border = ((Math.min(255, a + 40)) << 24) | 0x00FFAA00;
        graphics.fill(x - 2, y - 2, x + 18, y + 18, (a / 2 << 24) | 0x00FFD040);
        graphics.fill(x, y, x + 16, y + 16, fill);
        graphics.fill(x - 2, y - 2, x + 18, y - 1, border);
        graphics.fill(x - 2, y + 17, x + 18, y + 18, border);
        graphics.fill(x - 2, y - 2, x - 1, y + 18, border);
        graphics.fill(x + 17, y - 2, x + 18, y + 18, border);
        pulseFrames++;
        lastPulseSlot = slot;
    }

    // ---- testkit hooks

    public static void chatForTest(String raw) {
        handle(raw, System.currentTimeMillis());
    }

    public static void resetForTest() {
        chatUntil = 0;
        pulseFrames = 0;
        lastPulseSlot = -1;
    }

    public static int pulseFrames() {
        return pulseFrames;
    }

    public static int lastPulseSlot() {
        return lastPulseSlot;
    }
}
