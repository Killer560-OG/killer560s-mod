package com.killer560.hub.terminaltrigger;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.terminalaura.TerminalStands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Terminal Triggerbot - the same shape as {@code SecretTriggerbotFeature}, for P3 terminals (killer560,
 * 2026-09-16: "a terminal trigger bot that will work the exact same as secret trigger bot, but just for
 * terminals"). Cheat build only, default OFF.
 * <p>
 * Look at an unopened terminal, wait the delay, click it once. The difference from
 * {@code TerminalAuraFeature} is the whole point: the aura opens anything that comes within range whether
 * you were looking at it or not, while this only ever acts on the one thing your crosshair is already on,
 * and never moves your camera to find it.
 * <p>
 * Like Secret Triggerbot, if the crosshair has left the terminal by the time the delay ends, the click is
 * dropped rather than sent late at whatever you are looking at now. Each terminal is clicked at most once
 * per world; the marker entity disappears when a terminal activates, so a reopened one is a new entity.
 * <p>
 * Terminals are marker armour stands and don't reliably appear in {@code Minecraft#hitResult}, so
 * "looking at" is resolved by {@link TerminalStands#raycast} against their own bounding boxes rather than
 * by reading the client's crosshair target.
 */
public final class TerminalTriggerbotFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-terminaltriggerbot");

    private static final Set<Integer> clicked = new HashSet<>();

    private static int pendingEntityId = -1;
    private static long triggerAtMs = 0L;
    private static long lastClickMs = 0L;
    private static Object lastLevel = null;

    private TerminalTriggerbotFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        TerminalTriggerbotConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(TerminalTriggerbotFeature::tick);
        LOGGER.info("[TerminalTriggerbot] Registered (default OFF)");
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            clicked.clear();
            pendingEntityId = -1;
        }
        TerminalTriggerbotConfig cfg = TerminalTriggerbotConfig.getInstance();
        if (!cfg.isEnabled()) {
            pendingEntityId = -1;
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }
        if (client.screen != null || player.isDeadOrDying()
                || !Floor7Tracker.inF7Boss() || !Floor7Tracker.inPhase(Floor7Tracker.Phase.P3)) {
            // A screen being open is the normal case right after a successful click - drop the pending
            // trigger rather than firing it at whatever is behind the terminal you just opened.
            pendingEntityId = -1;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClickMs < cfg.getCooldownMs()) {
            return;
        }

        TerminalStands.Hit hit = TerminalStands.raycast(client.level, player, cfg.getRange(),
                client.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        if (hit == null || clicked.contains(hit.stand().getId())) {
            pendingEntityId = -1;
            return;
        }
        int id = hit.stand().getId();
        if (pendingEntityId != id) {
            // Crosshair just landed on this one: start its delay.
            pendingEntityId = id;
            triggerAtMs = now + cfg.getDelayMs();
            return;
        }
        if (now < triggerAtMs) {
            return;
        }
        client.gameMode.interact(player, hit.stand(), new EntityHitResult(hit.stand(), hit.point()),
                InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        clicked.add(id);
        lastClickMs = now;
        pendingEntityId = -1;
        LOGGER.debug("[TerminalTriggerbot] Opened terminal entity {}", id);
    }
}
