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
    /** Attack held down on the tile until the sand breaks (vanilla's own hold builds the dig progress). */
    private static boolean holding;
    static final int HOLD_MAX_TICKS = 100;

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

    private static void letGo() {
        if (holding) {
            holding = false;
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyAttack.setDown(com.killer560.hub.islands.Islands.physical(mc, mc.options.keyAttack));
        }
    }

    private static void idle() {
        letGo();
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
                letGo();
                pending = -1;
                tries = 0;
                nextTick = now + ActionGate.delayTicks(cfg.getDelayMs());
            } else if (holding && now - pendingSince < HOLD_MAX_TICKS) {
                // Keep holding while the look still strikes the tile; a look that drifted off lets go and tries again.
                HitResult h = mc.hitResult;
                if (h instanceof BlockHitResult b && b.getBlockPos().equals(FruitDigging.posOf(pending))
                        && com.killer560.hub.util.DigAim.heardLookStrikes(player, FruitDigging.posOf(pending))) {
                    mc.options.keyAttack.setDown(true);
                    return;
                }
                letGo();
                pending = -1;
                return;
            } else if (now - pendingSince < 30 && !holding) {
                return;
            } else if (++tries >= 3) {
                letGo();
                ModChat.log(NAME, ModChat.text("Tile " + FruitDiggingSolver.label(pending)
                        + " did not dig after 3 tries; waiting for the next pick."));
                pending = -1;
                idle();
                return;
            } else {
                letGo();
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
        // Its own shovel swap (no separate switch needed): done on ticks that are not digs, before the dig.
        if (!AutoShovelSwap.ready(player)) {
            if (swapWaitSince < 0) {
                swapWaitSince = now;
            }
            if (now - swapWaitSince < 40) {
                AutoShovelSwap.step(mc, player, cfg.getDelayMs());
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
        // The dig starts through vanilla's own hold of Attack (startDestroyBlock, then continueDestroyBlock each tick), so
        // the sequence and progress are a real player's; one start a tick through the gate.
        if (ActionGate.hisClickQueued(mc.options.keyAttack) || ActionGate.hisUseGoesThisTick(mc)
                || !ActionGate.tryAct(ActionGate.Actor.CARNIVAL_WORLD)) {
            return;
        }
        CarnivalInput.countTap();
        holding = true;
        mc.options.keyAttack.setDown(true);
        {
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
