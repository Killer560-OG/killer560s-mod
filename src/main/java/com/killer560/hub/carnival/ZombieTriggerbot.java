package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Zombie Shootout Triggerbot (cheat build only, killer560 2026-10-25: "a triggerbot ... fires only when the crosshair
 * is already on a valid target", built like the Goldor, Terminal and Secret Triggerbots): while you play Zombie Shootout
 * holding the Carnival Dart Tube, the moment your crosshair is on a zombie or a lit Redstone Lamp (the Zombie Shootout
 * Helper's targets, ray-traced out to 64 blocks with nothing solid in front), it taps your Use key (or Attack, Shoot
 * With) once - after the Aim Delay on a new target, then at most once per Shot Delay (rolled up to 25% longer each
 * time). It never turns you: you aim, it clicks. Ticks at the start of the client tick through {@link ActionGate}.
 * Automation: can get you banned; keep it off on the main server. Off by default.
 *
 * <p>Unverified in game: that the Dart Tube shoots on right click (Use) and that its name holds "Dart Tube".
 */
public final class ZombieTriggerbot {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final double RANGE = 64;
    private static Object aimed;
    private static long aimedAtTick;
    private static long nextShotTick;
    private static int shots;

    private ZombieTriggerbot() {
    }

    public static void register() {
        CarnivalInput.register();
        ZombieShootout.addWant(() -> ShootoutShotConfig.triggerbot().isEnabledRaw());
        int bit = FeatureBits.register("Zombie Shootout Triggerbot", () -> ShootoutShotConfig.triggerbot().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ZombieTriggerbot.tick", bit,
                ZombieTriggerbot::tick));
    }

    /** Whether {@code stack} is the Carnival Dart Tube (by name: no item id is on record). */
    static boolean isTube(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String name = ChatObserver.stripCodes(stack.getHoverName().getString());
        return name != null && name.contains("Dart Tube");
    }

    static net.minecraft.client.KeyMapping key(Minecraft mc, ShootoutShotConfig cfg) {
        return cfg.getShootWith() == 1 ? mc.options.keyAttack : mc.options.keyUse;
    }

    /** The next shot's earliest tick: the Shot Delay, rolled up to a quarter longer so the gaps are not one constant. */
    static long nextShot(long now, ShootoutShotConfig cfg) {
        int ms = cfg.getShotDelayMs();
        ms += ThreadLocalRandom.current().nextInt(ms / 4 + 1);
        return now + ActionGate.delayTicks(ms);
    }

    private static void tick(Minecraft mc) {
        ShootoutShotConfig cfg = ShootoutShotConfig.triggerbot();
        LocalPlayer player = mc.player;
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !ZombieShootout.active()
                || (cfg.isTubeOnly() && !isTube(player.getMainHandItem()))) {
            aimed = null;
            return;
        }
        CarnivalInput.watchCorrections("Zombie Shootout Triggerbot");
        ZombieShootout.Target t = ZombieShootout.targetUnderCrosshair(player, RANGE);
        long now = ActionGate.currentTick();
        if (t == null) {
            aimed = null;
            return;
        }
        Object key = t.zombie() != null ? (Object) t.zombie().getId() : t.lamp();
        if (!key.equals(aimed)) {
            aimed = key;
            aimedAtTick = now + ActionGate.delayTicks(cfg.getAimDelayMs());
        }
        if (now < aimedAtTick || now < nextShotTick) {
            return;
        }
        if (CarnivalInput.tap(key(mc, cfg))) {
            shots++;
            nextShotTick = nextShot(now, cfg);
            LOGGER.info("[Zombie Shootout Triggerbot] shot at {} ({} points)", t.label(), t.points());
        }
    }

    /** Harness: shots fired. */
    public static int shots() {
        return shots;
    }
}
