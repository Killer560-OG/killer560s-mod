package com.killer560.hub.terminatorclicker;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.trajectories.Shortbows;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shortbow Auto Clicker (cheat build, featH-1109, from the Odin sweep; every shortbow since 2026-11-09, the Terminator included): while a shortbow is in the main hand and the
 * use key is held, a left click is sent at a clicks per second picked between the set min and max each click, each interval jittered by up to 30 ms. A left click
 * is what Hypixel's left-click weapons fire on, and what a vanilla miss sends: the arm swing alone (the same "ranged
 * swing" Goldor Triggerbot sends), so nothing is attacked or dug. Where vanilla's left click would start digging (a block
 * under the crosshair) nothing is sent.
 * <p>
 * Through {@link ActionGate} (actor TERMINATOR_CLICK) at START_CLIENT_TICK, delays in whole ticks, each click scheduled from
 * the last one's due time so the average rate holds. His own right click goes after us in the tick, so a tick it goes in is
 * skipped (same rule as Goldor Triggerbot's attack: PacketOrderJ). Hold: nothing runs per tick while off or the key is up.
 */
public final class TerminatorClickerFeature {

    private static final ActionGate.Actor ACTOR = ActionGate.Actor.TERMINATOR_CLICK;

    private static double dueTick;
    private static int clicks;

    private TerminatorClickerFeature() {
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("TerminatorClicker", TerminatorClickerFeature::tick));
    }

    /** Clicks sent this session, for the testkit. */
    public static int clicks() { return clicks; }

    static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || !TerminatorClickerConfig.getInstance().isEnabled() || !client.options.keyUse.isDown()
                || McCompat.screen(client) != null || player.isDeadOrDying() || !SkyblockGate.allows()
                || !Shortbows.isShortbow(player.getMainHandItem())) {
            dueTick = 0;
            return;
        }
        long now = ActionGate.currentTick();
        if (now < dueTick || (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK)
                || ActionGate.hisUseGoesThisTick(client) || !ActionGate.tryAct(ACTOR)) {
            return;
        }
        McCompat.swingAttack(player);
        clicks++;
        TerminatorClickerConfig cfg = TerminatorClickerConfig.getInstance();
        double cps = ThreadLocalRandom.current().nextInt(cfg.getCpsMin(), cfg.getCpsMax() + 1);
        double ms = 1000.0 / cps + ThreadLocalRandom.current().nextDouble(-30, 30);
        dueTick = Math.max(dueTick, now - 1) + Math.max(50.0, ms) / 50.0;
    }
}
