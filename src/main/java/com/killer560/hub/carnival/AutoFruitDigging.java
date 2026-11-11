package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Auto Fruit Digging (cheat build only): while you play Fruit Digging, turns your view through Auto Routes' eased
 * rotation to the tile the Fruit Digging Solver picks and, once your crosshair is on it, taps your Attack key once to
 * dig it - then waits for the dig to land (the tile stops being sand) before the next, at least Dig Delay apart. With
 * Auto Shovel Swap on it waits (up to two seconds) for the shovel to be in the solver's mode first. It digs only tiles
 * within 4.5 blocks of your eyes (to the block's box) and never walks: stand in the middle of the grid, or step closer
 * when it says the tile is out of reach. Free Camera keeps your screen still while your body turns; moving the mouse
 * yourself stops it for the rest of that game. One tap a tick through {@link ActionGate}; a server correction is
 * reported and it carries on. Automation: can get you banned; keep it off on the main server. Off by default.
 *
 * <p>Unverified in game: that one left click digs a tile (Mining Fatigue is on during the game).
 */
public final class AutoFruitDigging {

    static final String NAME = "Auto Fruit Digging";
    static final double REACH = 4.5;
    private static boolean stoppedThisGame;
    private static boolean aiming;
    private static int pending = -1;
    private static int pendingDigs;
    private static long pendingSince;
    private static int tries;
    private static long nextTick;
    private static long swapWaitSince = -1;
    private static String warnedFar;
    private static int digs;
    private static String lastTarget;
    private static String lastMode;

    private AutoFruitDigging() {
    }

    public static void register() {
        CarnivalInput.register();
        CarnivalState.onGameStart(g -> {
            stoppedThisGame = false;
            pending = -1;
            warnedFar = null;
        });
        int bit = FeatureBits.register(NAME, () -> AutoFruitConfig.dig().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoFruitDigging.tick", bit,
                AutoFruitDigging::tick));
    }

    private static void idle() {
        if (aiming) {
            aiming = false;
            CarnivalInput.release(NAME);
        }
    }

    private static void tick(Minecraft mc) {
        AutoFruitConfig cfg = AutoFruitConfig.dig();
        LocalPlayer player = mc.player;
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !FruitDigging.active()
                || stoppedThisGame || FruitDigging.gridOrigin() == null
                || FruitDigging.digs() >= FruitDiggingSolver.MAX_DIGS) {
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
        long now = ActionGate.currentTick();
        // A dig sent: wait for the tile to stop being sand (or the dig count to move) before choosing again.
        if (pending >= 0) {
            boolean landed = !FruitDigging.cell(pending).diggable || FruitDigging.digs() > pendingDigs;
            if (landed) {
                pending = -1;
                tries = 0;
                nextTick = now + ActionGate.delayTicks(cfg.getDelayMs());
            } else if (now - pendingSince < 30) {
                return;
            } else if (++tries >= 3) {
                ModChat.log(NAME, ModChat.text("Tile " + FruitDiggingSolver.label(pending)
                        + " did not dig after 3 tries; waiting for the next pick."));
                pending = -1;
                idle();
                return;
            } else {
                pending = -1;
            }
        }
        FruitDiggingSolver.Recommendation r = FruitDigging.recommendation();
        if (r == null || !FruitDigging.cell(r.cell()).diggable) {
            return;
        }
        BlockPos pos = FruitDigging.posOf(r.cell());
        AABB box = new AABB(pos);
        Vec3 eye = player.getEyePosition();
        if (BlockHits.boxDistanceSq(eye, box) > REACH * REACH) {
            if (!r.label().equals(warnedFar)) {
                warnedFar = r.label();
                CarnivalInput.say(NAME, "Tile " + r.label() + " is out of reach; step closer to it.");
            }
            idle();
            return;
        }
        // The top face's middle, a touch inside so the ray meets this block and not its neighbor's edge.
        Vec3 aim = new Vec3(pos.getX() + 0.5, pos.getY() + 0.98, pos.getZ() + 0.5);
        aiming = true;
        CarnivalInput.aim(NAME, player, aim, false, cfg.isFreeCamera());
        if (now < nextTick) {
            return;
        }
        if (AutoFruitConfig.swap().isEnabled() && !AutoShovelSwap.ready(player)) {
            if (swapWaitSince < 0) {
                swapWaitSince = now;
            }
            if (now - swapWaitSince < 40) {
                return;
            }
        }
        HitResult hit = mc.hitResult;
        // GrimAC RotationBreak (experimental): the dig goes out before this tick's move, so the look the server last
        // heard must already strike the tile; while the turn is still landing, wait a tick.
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK || !bh.getBlockPos().equals(pos)
                || !com.killer560.hub.util.DigAim.heardLookStrikes(player, pos)) {
            return;
        }
        if (CarnivalInput.tap(mc.options.keyAttack)) {
            digs++;
            pending = r.cell();
            lastTarget = r.label();
            lastMode = r.shovel();
            pendingDigs = FruitDigging.digs();
            pendingSince = now;
            swapWaitSince = -1;
            ModChat.log(NAME, ModChat.text("Digging " + r.label() + " (" + r.shovel() + ")."));
        }
    }

    /** Harness: the tile ("C4") and shovel mode of the last dig tap. */
    public static String lastTarget() {
        return lastTarget + "/" + lastMode;
    }

    /** Harness: dig taps sent. */
    public static int digTaps() {
        return digs;
    }
}
