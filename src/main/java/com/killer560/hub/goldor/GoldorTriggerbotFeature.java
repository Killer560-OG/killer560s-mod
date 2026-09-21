package com.killer560.hub.goldor;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.cheatutils.WitherEspFeature;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.ActionGate;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Goldor Triggerbot - killer560, 2026-09-21: "Create goldore triggerbot with cps range". While the crosshair is
 * actually on the F7/M7 P3 boss wither (Goldor), click him at a rate rolled inside the configured CPS band.
 * Cheat build only, default OFF.
 * <p>
 * The same shape as {@code SecretTriggerbotFeature} / {@code TerminalTriggerbotFeature} - it only ever acts on what
 * you are already aiming at and never moves the camera - with two differences:
 * <ul>
 * <li><b>Rate, not delay.</b> Instead of one fixed Delay slider the gap before every single click is rolled fresh
 * between the two ends of the CPS range (see {@link #rollIntervalMs}). Rolling once and reusing the value would put
 * a constant interval on the wire, which is the machine signature this whole subsystem exists to avoid - it is the
 * same reason {@code ActionGate} jitters its own one-tick floor. With the two ends set equal there is nothing left
 * to roll and only the gate's jitter varies the spacing, which is the player asking for a fixed rate.
 * <li><b>Aim is ray-traced, not read off {@code Minecraft#hitResult}.</b> The vanilla crosshair target stops at
 * entity interaction reach, so it can only ever see Goldor in melee; P3 is normally shot from across the arena.
 * The test here is the same {@code AABB#clip} against the boss's own hitbox that {@code TerminalStands#raycast}
 * and Blood Camp's {@code aimedAt} use, out to the Range setting - the hitbox itself, never inflated, so "on him"
 * means on him.
 * </ul>
 * Every click goes through {@link ActionGate}, which allows one automated interaction per client tick mod-wide and
 * refuses a world action outright while a container screen is open. A refused tick changes nothing here: the rolled
 * interval is not consumed, so the click simply lands on the next tick the gate allows.
 * <p>
 * Driven from the world-render hook rather than {@code END_CLIENT_TICK} for the same reason {@code ArrowAlignFeature}
 * is: a tick is 50 ms, so a tick-granular timer can only ever produce 20, 10 or 6.7 CPS and a 15-17 band would come
 * out as an alternation between 20 and 10. The gate still caps the real rate at one click per tick.
 */
public final class GoldorTriggerbotFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-goldortriggerbot");

    /**
     * TEMPORARY - {@code ActionGate.Actor} has no Goldor constant yet and this feature must not borrow another
     * one for good. The exact line to add is at the top of this wave's staging notes; until the main session adds
     * it, clicks are gated as {@code ARROW_ALIGN}, the closest existing actor (the other F7 P3 Goldor-arena
     * triggerbot, same {@code Kind.WORLD} and the same priority tier). Aiming at an arrow-align item frame and
     * aiming at Goldor are mutually exclusive, so sharing the slot cannot make the two fight over a tick.
     */
    private static final ActionGate.Actor GATE_ACTOR = ActionGate.Actor.GOLDOR_TRIGGER;

    private static int aimTargetId = -1;
    private static long nextClickAtMs = 0L;
    private static Object lastLevel = null;
    private static String lastGateLog = null;

    private GoldorTriggerbotFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        GoldorTriggerbotConfig.getInstance();
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(GoldorTriggerbotFeature::onRender);
        LOGGER.info("[GoldorTriggerbot] Registered (default OFF)");
    }

    private static void onRender(LevelRenderContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != lastLevel) {
            lastLevel = client.level;
            clearAim();
        }
        GoldorTriggerbotConfig cfg = GoldorTriggerbotConfig.getInstance();
        if (!cfg.isEnabled()) {
            clearAim();
            logGate("disabled");
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            clearAim();
            return;
        }

        String gate = null;
        if (!CheatUtils.isOnDungeonServer(client)) {
            gate = "not on hypixel/p3sim";
        } else if (client.screen != null) {
            // Any screen, not just a container: with a menu up the mouse is not steering the crosshair, so
            // "aimed at Goldor" is a stale reading of where you were looking. The gate refuses container
            // screens by itself; this is the stricter half the gate deliberately leaves to the feature.
            gate = "a screen is open";
        } else if (player.isDeadOrDying()) {
            gate = "dead";
        } else if (!WitherEspFeature.isWitherBossActive(client)) {
            gate = "not in the F7/M7 boss";
        } else if (!Floor7Tracker.inPhase(Floor7Tracker.Phase.P3) && !Floor7Tracker.inPhaseAt(Floor7Tracker.Phase.P3)) {
            // Chat-driven phase OR the y-band one, so joining mid-run (no Goldor line seen) still works, and so
            // does p3sim, where the phase lines arrive exactly as they do on Hypixel.
            gate = "not in P3";
        }
        if (gate != null) {
            clearAim();
            logGate(gate);
            return;
        }
        logGate("active");

        long now = System.currentTimeMillis();
        float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Entity target = aimedAtBoss(client, player, cfg.getRange(), partialTick);
        if (target == null) {
            clearAim();
            return;
        }
        if (target.getId() != aimTargetId) {
            // Crosshair just landed on him: serve the aim delay before the first click of this acquisition.
            aimTargetId = target.getId();
            nextClickAtMs = now + cfg.getAimDelayMs();
            return;
        }
        if (now < nextClickAtMs) {
            return;
        }
        // The one-interaction-per-tick gate, checked after the interval has elapsed and before anything is sent
        // or rolled: a denied tick must leave the aim and the due time exactly where they are.
        if (!ActionGate.tryAct(GATE_ACTOR)) {
            return;
        }
        if (cfg.getClickType() == GoldorTriggerbotConfig.ClickType.ATTACK) {
            client.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        } else {
            // Terminator / Juju: the shot is a plain item use in the direction you are already looking - the same
            // call AutoI4Feature fires the bow with. useItem does its own swing handling, so none is sent here.
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        nextClickAtMs = now + rollIntervalMs(cfg);
    }

    /**
     * A fresh interval for every click: a CPS uniformly inside the configured band, converted to milliseconds.
     * Rolled in the CPS domain because that is the unit the slider promises.
     */
    private static long rollIntervalMs(GoldorTriggerbotConfig cfg) {
        double min = cfg.getCpsMin();
        double max = Math.max(min, cfg.getCpsMax());
        double cps = max > min ? ThreadLocalRandom.current().nextDouble(min, max) : min;
        return Math.max(1L, Math.round(1000.0 / Math.max(1.0, cps)));
    }

    /**
     * The real boss wither whose hitbox the crosshair line actually passes through, nearest first, or null.
     * {@link WitherEspFeature#isRealWither} is Noamm's filter - Hypixel's invisible / 800-invulnerable-tick
     * display withers are not Goldor and must never be clicked.
     */
    private static Entity aimedAtBoss(Minecraft client, LocalPlayer player, double range, float partialTick) {
        Vec3 eyes = player.getEyePosition(partialTick);
        // The segment itself stops at Range, so the clip below is the range check - no separate distance test.
        Vec3 end = eyes.add(player.getViewVector(partialTick).scale(range));
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof WitherBoss) || !WitherEspFeature.isRealWither(entity)) {
                continue;
            }
            AABB box = entity.getBoundingBox();
            Vec3 hit = box.contains(eyes) ? eyes : box.clip(eyes, end).orElse(null);
            if (hit == null) {
                continue;
            }
            double distance = eyes.distanceToSqr(hit);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private static void clearAim() {
        aimTargetId = -1;
    }

    private static void logGate(String gate) {
        if (!gate.equals(lastGateLog)) {
            lastGateLog = gate;
            LOGGER.info("[GoldorTriggerbot] State: {}", gate);
        }
    }
}
