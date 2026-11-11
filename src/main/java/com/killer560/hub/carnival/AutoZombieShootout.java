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
    private static boolean stoppedThisGame;
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
        CarnivalState.onGameStart(g -> stoppedThisGame = false);
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
                || stoppedThisGame || (cfg.isTubeOnly() && !ZombieTriggerbot.isTube(player.getMainHandItem()))) {
            idle();
            return;
        }
        if (CarnivalInput.userTookOver(NAME, player)) {
            stoppedThisGame = true;
            idle();
            CarnivalInput.say(NAME, "Stopped for this game: you moved the camera.");
            return;
        }
        CarnivalInput.watchCorrections(NAME);
        ZombieShootout.Target best = ZombieShootout.pickBest(ZombieShootout.targets(),
                t -> (cfg.isLampsFirst() || t.lamp() == null) && ZombieShootout.inSight(player, t));
        if (best == null && !cfg.isLampsFirst()) {
            best = ZombieShootout.pickBest(ZombieShootout.targets(), t -> ZombieShootout.inSight(player, t));
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
        long now = ActionGate.currentTick();
        if (hit == null) {
            aimed = null;
            return;
        }
        Object key = hit.zombie() != null ? (Object) hit.zombie().getId() : hit.lamp();
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

    /** Harness: shots fired. */
    public static int shots() {
        return shots;
    }

    /** Harness: whether it is turning the view now. */
    public static boolean aiming() {
        return running;
    }
}
