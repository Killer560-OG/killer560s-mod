package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

/**
 * Auto Zombie Shootout (cheat build only): while you play Zombie Shootout holding the Carnival Dart Tube, turns your view
 * to the best target in sight - a lit Redstone Lamp first (Lamps First), then the zombie worth the most and soonest
 * gone (the Zombie Shootout Helper's pick) - through Auto Routes' eased rotation, following it as it walks, and taps Use
 * (or Attack, Shoot With) once the crosshair is on a target, at most once per Shot Delay. Free Camera keeps your screen
 * still while your body turns. Moving the mouse yourself stops it for the rest of that game. You are held still by the
 * game, and it never moves you. Every tap goes through {@link ActionGate} at the start of the tick; a server correction
 * is reported and it carries on. Automation: can get you banned; keep it off on the main server. Off by default.
 *
 * <p>Unverified in game: whether the dart is instant or a projectile that needs leading (it aims at the box center).
 */
public final class AutoZombieShootout {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final String NAME = "Auto Zombie Shootout";
    /** The tick the auto may aim again after he took the camera (it never stays stopped: 11-11 it fired once, then stood
     *  down for the whole game). */
    private static long pausedUntil;
    private static boolean toldPause;
    /** Targets given up on (never came under the crosshair), by key, until the tick: it moves on to the next one. */
    private static final java.util.Map<Object, Long> SKIP = new java.util.HashMap<>();
    private static Object stuckKey;
    private static long stuckSince;
    static final int TAKEOVER_PAUSE_TICKS = 60;
    static final int STUCK_TICKS = 30;
    static final int SKIP_TICKS = 80;
    private static boolean running;
    private static Object aimed;
    private static long aimedAtTick;
    private static long nextShotTick;
    private static int shots;

    private AutoZombieShootout() {
    }

    public static void register() {
        CarnivalInput.register();
        ZombieShootout.addWant(() -> ShootoutShotConfig.auto().isEnabledRaw());
        CarnivalState.onGameStart(g -> {
            pausedUntil = 0;
            toldPause = false;
            SKIP.clear();
            stuckKey = null;
        });
        int bit = FeatureBits.register(NAME, () -> ShootoutShotConfig.auto().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoZombieShootout.tick", bit,
                AutoZombieShootout::tick));
    }

    private static void idle() {
        if (running) {
            running = false;
            CarnivalInput.release(NAME);
        }
        aimed = null;
    }

    private static void tick(Minecraft mc) {
        ShootoutShotConfig cfg = ShootoutShotConfig.auto();
        LocalPlayer player = mc.player;
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !ZombieShootout.active()
                || (cfg.isTubeOnly() && !ZombieTriggerbot.isTube(player.getMainHandItem()))) {
            idle();
            return;
        }
        long now = ActionGate.currentTick();
        if (CarnivalInput.userTookOver(NAME, player)) {
            pausedUntil = now + TAKEOVER_PAUSE_TICKS;
            idle();
            if (!toldPause) {
                toldPause = true;
                CarnivalInput.say(NAME, "Paused: you moved the camera. It carries on after a moment.");
            }
            return;
        }
        if (now < pausedUntil) {
            idle();
            return;
        }
        SKIP.values().removeIf(until -> until <= now);
        CarnivalInput.watchCorrections(NAME);
        ZombieShootout.Target best = ZombieShootout.pickBest(ZombieShootout.targets(),
                t -> (cfg.isLampsFirst() || t.lamp() == null) && usable(player, t));
        if (best == null && !cfg.isLampsFirst()) {
            best = ZombieShootout.pickBest(ZombieShootout.targets(), t -> usable(player, t));
        }
        if (best == null) {
            idle();
            return;
        }
        if (!running) {
            running = true;
            LOGGER.info("[{}] aiming", NAME);
        }
        CarnivalInput.aim(NAME, player, best.aimPoint(), best.zombie() != null, cfg.isFreeCamera());
        ZombieShootout.Target hit = ZombieShootout.targetUnderCrosshair(player, ZombieTriggerbot.RANGE);
        // Aimed at a target that never comes under the crosshair: give it up for a while and take the next one.
        Object bestKey = key(best);
        if (hit != null || !bestKey.equals(stuckKey)) {
            stuckKey = bestKey;
            stuckSince = now;
        } else if (now - stuckSince >= STUCK_TICKS) {
            SKIP.put(bestKey, now + SKIP_TICKS);
            LOGGER.info("[{}] gave up on {} (never under the crosshair)", NAME, best.label());
            stuckKey = null;
        }
        if (hit == null) {
            aimed = null;
            return;
        }
        Object key = key(hit);
        if (!key.equals(aimed)) {
            aimed = key;
            aimedAtTick = now + ActionGate.delayTicks(cfg.getAimDelayMs());
        }
        if (now < aimedAtTick || now < nextShotTick) {
            return;
        }
        if (CarnivalInput.tap(ZombieTriggerbot.key(mc, cfg))) {
            shots++;
            nextShotTick = ZombieTriggerbot.nextShot(now, cfg);
            com.killer560.hub.util.ModChat.log(NAME, com.killer560.hub.util.ModChat.text("Shot at " + hit.label()
                    + " (" + hit.points() + " points)."));
        }
    }

    private static Object key(ZombieShootout.Target t) {
        return t.zombie() != null ? (Object) t.zombie().getId() : t.lamp();
    }

    /** A target the auto may shoot: in sight, not given up on, and not a lamp it would have to look nearly straight down
     *  at (one under his feet). */
    private static boolean usable(LocalPlayer player, ZombieShootout.Target t) {
        if (SKIP.containsKey(key(t)) || !ZombieShootout.inSight(player, t)) {
            return false;
        }
        return t.lamp() == null || !tooSteep(player, t.aimPoint());
    }

    /** Whether {@code point} is more than 75 degrees below the eye (a floor lamp he stands on). */
    static boolean tooSteep(LocalPlayer player, net.minecraft.world.phys.Vec3 point) {
        net.minecraft.world.phys.Vec3 d = point.subtract(player.getEyePosition());
        return d.y < 0 && -d.y > Math.sqrt(d.x * d.x + d.z * d.z) * 3.73;
    }

    /** Harness: shots fired. */
    public static int shots() {
        return shots;
    }

    /** Harness: whether it is turning the view now. */
    public static boolean aiming() {
        return running;
    }
}
