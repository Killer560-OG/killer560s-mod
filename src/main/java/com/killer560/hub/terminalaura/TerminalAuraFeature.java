package com.killer560.hub.terminalaura;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.fastleap.LeapManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Terminal Aura - opens P3 terminals by itself when you walk past one, cheat build only, default OFF.
 * A port of QUOI's {@code TerminalAura} (credited there to Kyleen), asked for by killer560 2026-09-16:
 * "it should be just like every other aura that you have implemented just for terminals".
 * <p>
 * This only <i>opens</i> a terminal - it right-clicks the "Inactive Terminal" armour stand. Solving what
 * is inside is Auto Terminals' job, and the two are independent: with Auto Terminals off, this just saves
 * the click that opens the GUI.
 * <p>
 * Deliberately does NOT rotate. A terminal you can already reach is a terminal you are already more or
 * less facing, and the interact is sent against the armour stand's own bounding box; snapping the camera
 * at each one would be both unnecessary and far more obvious than the click itself.
 */
public final class TerminalAuraFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-terminalaura");

    private static long lastClickMs = 0L;

    private TerminalAuraFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        TerminalAuraConfig.getInstance();
        ClientTickEvents.END_CLIENT_TICK.register(TerminalAuraFeature::tick);
        LOGGER.info("[TerminalAura] Registered (default OFF)");
    }

    private static void tick(Minecraft client) {
        TerminalAuraConfig cfg = TerminalAuraConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        Player player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            return;
        }
        // Any open screen means a terminal (or anything else) is already up - clicking another one
        // underneath it would queue a second GUI on top of the one being solved.
        if (client.screen != null || player.isDeadOrDying()) {
            return;
        }
        if (!Floor7Tracker.inF7Boss() || !Floor7Tracker.inPhase(Floor7Tracker.Phase.P3)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastClickMs < cfg.getDelayMs()) {
            return;
        }
        if (cfg.isGroundOnly() && !player.onGround()) {
            return;
        }
        if (cfg.isLeapDelayEnabled()
                && now - LeapManager.lastLeapMs() < (long) (cfg.getLeapDelaySeconds() * 1000.0)) {
            // Just landed from a leap: the terminals around the landing spot usually belong to whoever
            // you leapt to, so hold off rather than stealing the one they were walking into.
            return;
        }

        double range = cfg.getRange();
        if (range <= 0) {
            return;
        }
        Vec3 eyes = player.getEyePosition();
        double rangeSqr = range * range;
        List<ArmorStand> stands = TerminalStands.near(client.level, player, range);
        for (ArmorStand stand : stands) {
            Vec3 center = TerminalStands.center(stand);
            if (eyes.distanceToSqr(center) > rangeSqr) {
                continue;
            }
            // Aim the interact at where the line from your eyes actually meets the stand's box, the same
            // point a real right-click would report - not at the entity's origin.
            AABB box = stand.getBoundingBox().inflate(TerminalStands.INFLATE);
            Vec3 hit = box.clip(eyes, center).orElse(null);
            if (hit == null) {
                continue;
            }
            client.gameMode.interact(player, stand, new EntityHitResult(stand, hit), InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            lastClickMs = now;
            LOGGER.debug("[TerminalAura] Opened terminal entity {}", stand.getId());
            return; // one per pass, so the delay actually paces them
        }
    }
}
