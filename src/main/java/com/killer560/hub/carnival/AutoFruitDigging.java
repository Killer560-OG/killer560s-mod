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
 * within 4.5 blocks of your eyes (to the block's box); a pick farther away it walks to on the Forward key alone (turning
 * toward it, holding Forward, letting go once in reach; killer560 11-14: "the auto needs to be able to walk to spots as
 * well"), and says so once if five seconds of walking bring it no closer. Free Camera keeps your screen still while your body turns; only Escape or the
 * Emergency Cancel Key stops it, for the rest of that game. One tap a tick through {@link ActionGate}; a server correction is
 * reported and it carries on. Automation: can get you banned; keep it off on the main server. Off by default.
 *
 * <p>Unverified in game: that one left click digs a tile (Mining Fatigue is on during the game).
 */
public final class AutoFruitDigging {

    static final String NAME = "Auto Fruit Digging";
    static final double REACH = 4.5;
    private static boolean aiming;
    private static int pending = -1;
    private static int pendingDigs;
    private static long pendingSince;
    private static int tries;
    private static long nextTick;
    private static String warnedFar;
    private static int digs;
    private static String lastTarget;
    private static String lastMode;
    /** Attack held down on the tile until the sand breaks (vanilla's own hold builds the dig progress). */
    private static boolean holding;
    static final int HOLD_MAX_TICKS = 100;
    /** It walks onto every pick: walking starts when the feet are further than {@link #WALK_START} blocks (flat) from the
     *  tile's middle and stops inside {@link #WALK_TO}. His 11-14 game never walked: every pick was inside the 4.5 reach
     *  of where he stood, so the old reach rule kept the auto in place and he walked the board by hand. */
    static final double WALK_START = 2.0;
    static final double WALK_TO = 1.0;
    static final double WALK_YAW = 30;
    static final int WALK_GIVE_UP = 100;
    private static boolean walking;
    private static String walkLabel;
    private static double walkBest;
    private static long walkBestTick;
    private static int walkTicks;
    /** This walk's stop distance (rolled each walk, so it never stops on the same spot twice) and this pick's aim spot on
     *  the tile's top face (rolled each pick: a hand does not hit the exact middle). */
    private static double walkTo = WALK_TO;
    private static int aimCell = -1;
    private static double aimDx;
    private static double aimDz;

    private AutoFruitDigging() {
    }

    public static void register() {
        CarnivalInput.register();
        CarnivalInput.stopRule(NAME, () -> AutoFruitConfig.dig().isEnabled() && FruitDigging.active());
        CarnivalState.onGameStart(g -> {
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

    /**
     * Walks toward an out-of-reach pick on the Forward key only (a discrete press, never a position or velocity write):
     * turns the body toward the tile at eye height and holds Forward while the turn is within {@link #WALK_YAW} degrees,
     * until the tile is {@link #WALK_TO} blocks away. No progress for {@link #WALK_GIVE_UP} ticks gives up on that pick
     * with one line. A server correction is reported by {@code watchCorrections} and the walk carries on from there.
     */
    private static void walkToward(Minecraft mc, LocalPlayer player, BlockPos pos, double dist, long now, String label,
                                   boolean freeCamera) {
        letGo();
        if (!walking || !label.equals(walkLabel)) {
            walking = true;
            walkLabel = label;
            walkBest = dist;
            walkBestTick = now;
            walkTo = WALK_TO * Human.between(0.75, 1.25);
            ModChat.log(NAME, ModChat.text("Walking to " + label + "."));
        } else if (dist < walkBest - 0.1) {
            walkBest = dist;
            walkBestTick = now;
        } else if (now - walkBestTick > WALK_GIVE_UP) {
            warnedFar = label;
            CarnivalInput.say(NAME, "Could not walk to " + label + "; step closer to it.");
            stopWalking(mc);
            idle();
            return;
        }
        Vec3 aim = new Vec3(pos.getX() + 0.5, player.getEyeY(), pos.getZ() + 0.5);
        aiming = true;
        CarnivalInput.aim(NAME, player, aim, true, freeCamera);
        boolean forward = CarnivalInput.error(player, aim) < WALK_YAW;
        mc.options.keyUp.setDown(forward);
        if (forward) {
            walkTicks++;
        }
    }

    /** Harness: ticks Forward was held to walk to a pick. */
    public static int walkTicks() {
        return walkTicks;
    }

    private static void stopWalking(Minecraft mc) {
        if (walking) {
            walking = false;
            mc.options.keyUp.setDown(com.killer560.hub.islands.Islands.physical(mc, mc.options.keyUp));
        }
    }

    private static void idle() {
        stopWalking(Minecraft.getInstance());
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
                || CarnivalInput.stopped(NAME) || FruitDigging.gridOrigin() == null
                || FruitDigging.digs() >= FruitDiggingSolver.MAX_DIGS) {
            idle();
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
                nextTick = now + ActionGate.delayTicks(cfg.getDelayMs()) + Human.ticks(2, 0, 10);
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
        double distSq = BlockHits.boxDistanceSq(eye, box);
        double flat = Math.hypot(player.getX() - (pos.getX() + 0.5), player.getZ() - (pos.getZ() + 0.5));
        if (!r.label().equals(warnedFar) && flat > (walking ? walkTo : WALK_START)) {
            walkToward(mc, player, pos, flat, now, r.label(), cfg.isFreeCamera());
            // The shovel mode is set on the way (a use is no dig, so no dig shares its tick).
            if (now >= nextTick && !AutoShovelSwap.ready(player)) {
                AutoShovelSwap.step(mc, player, cfg.getDelayMs());
            }
            return;
        }
        stopWalking(mc);
        if (distSq > REACH * REACH) {
            return; // gave up walking to it and it is out of reach: the player steps closer
        }
        // A spot on the top face (a touch inside so the ray meets this block and not its neighbor's edge), rolled per pick.
        if (aimCell != r.cell()) {
            aimCell = r.cell();
            aimDx = Human.spread(0.12, 0.28);
            aimDz = Human.spread(0.12, 0.28);
        }
        Vec3 aim = new Vec3(pos.getX() + 0.5 + aimDx, pos.getY() + 0.98, pos.getZ() + 0.5 + aimDz);
        aiming = true;
        CarnivalInput.aim(NAME, player, aim, false, cfg.isFreeCamera());
        if (now < nextTick) {
            return;
        }
        // Its own shovel swap (no separate switch needed): done on ticks that are not digs, before the dig.
        // Never dig in the wrong mode: it used to dig anyway after 40 ticks, and Prevent Wrong Shovel then refused about
        // 250 clicks in his 11-14 game.
        if (!AutoShovelSwap.ready(player)) {
            AutoShovelSwap.step(mc, player, cfg.getDelayMs());
            return;
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
            ModChat.log(NAME, ModChat.text("Digging " + r.label() + " (" + r.shovel() + ")."));
        }
    }

    /** Harness: the tile ("C4") and shovel mode of the last dig tap. */
    /** Harness: stopped for this game by Escape or the Emergency Cancel Key. */
    public static boolean stoppedForTest() {
        return CarnivalInput.stopped(NAME);
    }

    public static String lastTarget() {
        return lastTarget + "/" + lastMode;
    }

    /** Harness: dig taps sent. */
    public static int digTaps() {
        return digs;
    }
}
