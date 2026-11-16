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
 * still while your body turns. Only Escape or the Emergency Cancel Key stops it for the rest of that game; moving the mouse never does. You are held still by the
 * game, and it never moves you. Every tap goes through {@link ActionGate} at the start of the tick; a server correction
 * is reported and it carries on. Automation: can get you banned; keep it off on the main server. Off by default.
 *
 * <p>It works the aim out itself ({@link Lead}): a walking zombie is led by its smoothed speed times the dart's flight
 * time at the measured dart speed plus the round trip, the aim is raised for the dart's drop, and each dart's closest
 * pass teaches a bounded lead and height for the rest of that game. It shoots only when the look is inside the target's
 * fire window ({@code Target.fireWindow}), one dart per target: the target is left alone until that dart has had time to
 * arrive, and the aim moves on meanwhile (every zombie dies to one dart, killer560 11-14).
 */
public final class AutoZombieShootout {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final String NAME = "Auto Zombie Shootout";
    /** The tick the auto may aim again after he took the camera (it never stays stopped: 11-11 it fired once, then stood
     *  down for the whole game). */
    /** Targets given up on (never came under the crosshair), by key, until the tick: it moves on to the next one. */
    private static final java.util.Map<Object, Long> SKIP = new java.util.HashMap<>();
    private static Object stuckKey;
    private static long stuckSince;
    static final int STUCK_TICKS = 30;
    static final int SKIP_TICKS = 80;
    private static boolean running;
    private static Object aimed;
    private static long aimedAtTick;
    private static Object scatterKey;
    private static double scatterUp;
    private static double scatterSide;
    private static long nextShotTick;
    private static int shots;

    private AutoZombieShootout() {
    }

    public static void register() {
        CarnivalInput.register();
        CarnivalInput.stopRule(NAME, () -> ShootoutShotConfig.auto().isEnabled() && ZombieShootout.active());
        ZombieShootout.addWant(() -> ShootoutShotConfig.auto().isEnabledRaw());
        CarnivalState.onGameStart(g -> {
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
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !ZombieShootout.active() || CarnivalInput.stopped(NAME)
                || (cfg.isTubeOnly() && !ZombieTriggerbot.isTube(player.getMainHandItem()))) {
            idle();
            return;
        }
        long now = ActionGate.currentTick();
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
        // Darts fly and drop: aim where the target will be, raised for the drop, and shoot only when the look is inside
        // the target's own fire window (every zombie dies to one dart, so a near miss costs the shot).
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 aim = best.dartAim(eye);
        // A spot somewhere on the target, rolled once per target (a hand never lands on the exact middle twice): up to
        // 0.35 up or down the body, a hair sideways (the fire window already allows for the lead's own error).
        Object bestKey = key(best);
        if (!bestKey.equals(scatterKey)) {
            scatterKey = bestKey;
            scatterUp = Human.spread(0.18, 0.35);
            scatterSide = Human.spread(0.04, 0.08);
        }
        double dx = aim.x - eye.x;
        double dz = aim.z - eye.z;
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        aim = aim.add(-dz / len * scatterSide, scatterUp, dx / len * scatterSide);
        CarnivalInput.aim(NAME, player, aim, best.zombie() != null, cfg.isFreeCamera());
        // A zombie that just turned is let settle on its new heading first: his pincarn6 game lost darts to zombies that
        // reversed while the dart flew.
        ZombieShootout.Target hit = CarnivalInput.error(player, aim) <= best.fireWindow(eye)
                && (best.zombie() == null || Lead.ready(best.zombie().getId()) && Lead.steady(best.zombie().getId()))
                ? best : null;
        // Aimed at a target that never comes under the crosshair: give it up for a while and take the next one.
        if (hit != null || !bestKey.equals(stuckKey)) {
            stuckKey = bestKey;
            stuckSince = now;
        } else if (now - stuckSince >= STUCK_TICKS) {
            SKIP.put(bestKey, now + SKIP_TICKS);
            LOGGER.info("[{}] gave up on {} (never under the crosshair)", NAME, best.label());
            stuckKey = null;
        }
        // The reaction time runs from picking the target, not from the last tick it sat under the crosshair: restarting it
        // on every tick the aim or the steady gate slipped held the dart back for good (testkit 3826: 4 darts in 600 ticks).
        Object key = bestKey;
        if (!key.equals(aimed)) {
            aimed = key;
            aimedAtTick = now + ActionGate.delayTicks(cfg.getAimDelayMs()) + Human.ticks(1.5, 0, 6);
        }
        if (hit == null || now < aimedAtTick || now < nextShotTick) {
            return;
        }
        if (CarnivalInput.tap(ZombieTriggerbot.key(mc, cfg))) {
            shots++;
            nextShotTick = ZombieTriggerbot.nextShot(now, cfg);
            // One dart per target: it is left alone until that dart has had time to arrive (it dies, or it is a miss
            // and the next shot uses what the miss taught Lead), and the aim moves on meanwhile.
            // Held past the next shot too: a hold that ran out before the shot delay did sent the aim back, so every dart
            // went at the first zombie and the second was never shot (testkit 3822).
            SKIP.put(key, Math.max(nextShotTick + 5, now + Math.min(40, Lead.dartTicks(eye, aim) + 4
                    + 2L * Math.max(0, com.killer560.hub.util.PingTracker.automationMs()) / 50)));
            Lead.noteShot(hit.zombie() != null ? hit.zombie().getId() : Integer.MIN_VALUE, eye, now,
                    new net.minecraft.world.phys.Vec3(-dz / len * scatterSide, scatterUp, dx / len * scatterSide));
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
