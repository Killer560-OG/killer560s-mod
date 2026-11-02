package com.killer560.hub.goldor;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.cheatutils.WitherEspFeature;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.concurrent.ThreadLocalRandom;
import com.killer560.hub.compat.McCompat;

/**
 * Wither Boss Triggerbot (Goldor Triggerbot until 2026-11-01; "All Wither Bosses" widens it past Goldor, and it works
 * from any range) - killer560, 2026-09-21: "Create goldore triggerbot with cps range". While the crosshair is
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
 * Clicks at the START of the client tick, like every clean trigger bot. It used to click from the world-render hook
 * for sub-tick spacing, and a click sent from a frame follows that tick's movement packet: GrimAC Post on every
 * attack (testkit 787, 2026-10-11). The Aim Delay is now whole ticks, rounded up ({@link ActionGate#delayTicks}).
 * The CPS band still holds on average: each click is scheduled from the previous one's DUE tick (fractional), not
 * from the tick it went out on, so a 16 CPS roll (1.25 ticks) comes out as gaps of 1 and 2 ticks averaging 16, not a
 * flat 10. At most one click a tick, so nothing above 20 CPS (the gate's cap anyway).
 */
public final class GoldorTriggerbotFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-goldortriggerbot");

    /** This feature's own {@code ActionGate} actor (a {@code Kind.WORLD} one). */
    private static final ActionGate.Actor GATE_ACTOR = ActionGate.Actor.GOLDOR_TRIGGER;

    /** Said once per session, not once per tick. */
    private static boolean warnedOutOfMeleeRange;
    private static int aimTargetId = -1;
    /** The client tick ({@link ActionGate#currentTick}) the next click is due on; fractional so a rolled CPS between
     *  20 and 10 is kept on average rather than rounded to one of them. */
    private static double nextClickDueTick = 0.0;
    private static Object lastLevel = null;

    private GoldorTriggerbotFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        GoldorTriggerbotConfig.getInstance();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("GoldorTriggerbotFeature.tick", GoldorTriggerbotFeature::tick));
        LOGGER.debug("[GoldorTriggerbot] Registered (default OFF)");
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            clearAim();
        }
        GoldorTriggerbotConfig cfg = GoldorTriggerbotConfig.getInstance();
        if (!cfg.isEnabled()) {
            clearAim();
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
        } else if (McCompat.screen(client) != null) {
            // Any screen, not just a container: with a menu up the mouse is not steering the crosshair, so
            // "aimed at Goldor" is a stale reading of where you were looking. The gate refuses container
            // screens by itself; this is the stricter half the gate deliberately leaves to the feature.
            gate = "a screen is open";
        } else if (player.isDeadOrDying()) {
            gate = "dead";
        } else if (!WitherEspFeature.isWitherBossActive(client)) {
            gate = "not in the F7/M7 boss";
        } else if (!classAllowed(cfg)) {
            gate = "class not selected";
        } else if (!cfg.isAllWithers()
                && !Floor7Tracker.inPhase(Floor7Tracker.Phase.P3) && !Floor7Tracker.inPhaseAt(Floor7Tracker.Phase.P3)) {
            // Chat-driven phase OR the y-band one, so joining mid-run (no Goldor line seen) still works, and so
            // does p3sim, where the phase lines arrive exactly as they do on Hypixel.
            gate = "not in P3";
        }
        if (gate != null) {
            clearAim();
            return;
        }

        long now = ActionGate.currentTick();
        Entity target = aimedAtBoss(client, player, GoldorTriggerbotConfig.AIM_DISTANCE, 1.0f);
        if (target == null) {
            clearAim();
            return;
        }
        if (target.getId() != aimTargetId) {
            // Crosshair just landed on him: serve the aim delay before the first click of this acquisition
            // (0 ms clicks on this same tick).
            aimTargetId = target.getId();
            nextClickDueTick = now + ActionGate.delayTicks(cfg.getAimDelayMs());
        }
        if (now < nextClickDueTick) {
            return;
        }
        // An ATTACK only reaches about 3 blocks (a Reach violation names the distance past that, 2026-09-28), but a left
        // click is not only an attack: from range it is what a vanilla miss sends - the swing alone - and that is what
        // a Terminator, a mage's beam and Hypixel's other left-click weapons fire on (killer560, 2026-11-01: "i cranked
        // range to 50 and on goldore it only worked if i could mele him ... I could be holding a bow or my mage beam").
        // So in reach it attacks, out of reach it swings; and where vanilla would hit a BLOCK under the crosshair (and
        // start digging it) nothing is sent, because the swing alone is not what that click would have been.
        boolean melee = true;
        if (cfg.getClickType() == GoldorTriggerbotConfig.ClickType.ATTACK) {
            double reachSq = com.killer560.hub.util.BlockHits.boxDistanceSq(
                    player.getEyePosition(), target.getBoundingBox());
            double limit = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
            melee = reachSq <= limit * limit;
            if (!melee && client.hitResult != null
                    && client.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                if (!warnedOutOfMeleeRange) {
                    warnedOutOfMeleeRange = true;
                    LOGGER.info("[Goldor] Aimed at the boss from {} blocks but a block is under the crosshair - a left "
                            + "click there would dig it, so nothing is sent", String.format(java.util.Locale.US, "%.1f", Math.sqrt(reachSq)));
                }
                return;
            }
        }
        // The one-interaction-per-tick gate, checked after the interval has elapsed and before anything is sent
        // or rolled: a denied tick must leave the aim and the due time exactly where they are.
        if (!ActionGate.tryAct(GATE_ACTOR)) {
            return;
        }
        if (cfg.getClickType() == GoldorTriggerbotConfig.ClickType.ATTACK) {
            if (melee) {
                client.gameMode.attack(player, target);
                attacks++;
            } else {
                swings++;
            }
            com.killer560.hub.compat.McCompat.swingAttack(player);
        } else {
            uses++;
            // Terminator / Juju: the shot is a plain item use in the direction you are already looking - the same
            // call AutoI4Feature fires the bow with. useItem does its own swing handling, so none is sent here. A block
            // click unpaired on the server gets vanilla's block half first (util/RightClick).
            com.killer560.hub.util.RightClick.beforePlainUse(client, player, "[Goldor] Shot");
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        // From the due tick, not from now, so the band's average survives tick rounding; a click held back (gate,
        // reach) carries at most a tick of backlog, so it never comes out as a burst.
        nextClickDueTick = Math.max(nextClickDueTick, now - 1) + rollIntervalMs(cfg) / 50.0;
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

    private static int attacks;
    private static int swings;
    private static int uses;

    /** Clicks sent this session, {attacks, ranged swings, uses} - for the testkit. */
    public static int[] clickCounts() {
        return new int[]{attacks, swings, uses};
    }

    /** Your class (tab list / override) is one of the selected ones; an unknown class passes only when all are on. */
    private static boolean classAllowed(GoldorTriggerbotConfig cfg) {
        boolean all = true;
        for (com.killer560.hub.dungeonclass.DungeonClass c : com.killer560.hub.dungeonclass.DungeonClass.values()) {
            all &= cfg.isClassEnabled(c);
        }
        if (all) {
            return true;
        }
        return cfg.isClassEnabled(com.killer560.hub.leapmenu.PartyTracker.selfClass());
    }

    private static void clearAim() {
        aimTargetId = -1;
    }
}
