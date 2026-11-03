package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.MouseButtons;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;

/**
 * Held Left Click diagnostics (killer560, 2026-11-03: "while mining something keeps making my left click stop holding
 * break"). Nothing in the mod's code was found that lets go of the attack key or stops a dig in the Dwarven Mines
 * (mining-1103: every key release, screen, mouse, slot, item and dig path was checked against 26.3's javap). On 26.3 a held
 * dig ends for good, until a fresh press, only when a screen opens (Gui.setScreen calls KeyMapping.releaseAll and the
 * re-grab sets missTime) or the operating system sends a left-button release (a focus or capture loss). This logs which of
 * the two it was the next time it happens, so the cause can be read off his log instead of guessed. Read only: nothing is
 * pressed, released, sent or changed.
 *
 * <p>Two lines, both "[AttackHold]" in killer560smod-miningqol, at most one a second:
 * <ul>
 *   <li>A screen initialized while the attack key is held: the screen's class and the code that opened it.</li>
 *   <li>The attack key went up while the game had seen no left-button release (a screen's release, or none at all),
 *       or while the window had lost focus.</li>
 * </ul>
 */
public final class AttackHoldProbe {

    private static final Logger LOGGER = ModLog.get("killer560smod-miningqol");
    private static final long MIN_GAP_MS = 1000L;

    private static boolean wasDown = false;
    private static long lastLogMs = 0L;
    private static int screenLines = 0;
    private static int releaseLines = 0;

    private AttackHoldProbe() {
    }

    static void register() {
        ScreenEvents.BEFORE_INIT.register((client, screen, w, h) -> {
            try {
                onScreen(client, screen);
            } catch (RuntimeException ignored) {
                // diagnostics only
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (RuntimeException ignored) {
                // diagnostics only
            }
        });
    }

    private static boolean watching(Minecraft client) {
        return client.player != null && client.level != null && MiningState.onMiningIsland();
    }

    private static void onScreen(Minecraft client, Screen screen) {
        // The physical button: Gui.setScreen has already released the key mapping by the time a screen initializes.
        if (!watching(client) || !MouseButtons.isDown(0) || screen instanceof ChatScreen || !gap()) {
            return;
        }
        screenLines++;
        StringBuilder trace = new StringBuilder();
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        int shown = 0;
        for (int i = 2; i < st.length && shown < 14; i++) {
            String c = st[i].getClassName();
            if (c.startsWith("java.") || c.contains("AttackHoldProbe") || c.contains("fabricmc.fabric.impl")) {
                continue;
            }
            trace.append(" <- ").append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(st[i].getMethodName());
            shown++;
        }
        LOGGER.info("[AttackHold] screen {} opened while you held left click (this releases it){}",
                screen == null ? "null" : screen.getClass().getName(), trace);
    }

    private static void tick(Minecraft client) {
        boolean down = client.options.keyAttack.isDown();
        if (wasDown && !down && watching(client)) {
            boolean buttonStillDown = MouseButtons.isDown(0);
            boolean active = client.isWindowActive();
            Screen screen = McCompat.screen(client);
            // A plain let-go of the button is the normal case and is not logged.
            if ((buttonStillDown || !active || screen != null) && gap()) {
                releaseLines++;
                LOGGER.info("[AttackHold] left click let go without your release: button still down {}, window focused {},"
                                + " screen {}, mouse grabbed {}", buttonStillDown, active,
                        screen == null ? "none" : screen.getClass().getSimpleName(), client.mouseHandler.isMouseGrabbed());
            }
        }
        wasDown = down;
    }

    private static boolean gap() {
        long now = System.currentTimeMillis();
        if (now - lastLogMs < MIN_GAP_MS) {
            return false;
        }
        lastLogMs = now;
        return true;
    }

    // ---- testkit hooks
    public static int screenLines() {
        return screenLines;
    }

    public static int releaseLines() {
        return releaseLines;
    }
}
