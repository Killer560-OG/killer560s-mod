package com.killer560.hub.pathfinding;

import com.killer560.hub.util.ModChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Ender-Pearl-only gap crossing for the "Pearls Only" auto mode. CHEAT BUILD ONLY.
 * <p>
 * killer560, 2026-09-21: "make a 0 etherwarp mode that only uses pearls so it works without etherwarp" and
 * "my earlier test was without etherwarp at all but it said that it couldn't find fairy souls that needed
 * to be found on that island" - {@link AutoSoulRunner}'s WALK mode only ever falls back to an
 * {@link EtherwarpHopper} rescue hop when the graph path can't reach a soul on foot (a ledge, a gap); with
 * no etherwarp item that rescue silently does nothing and the soul gets skipped every time, forever. This
 * gives WALK-without-etherwarp a real rescue: solve a throw angle that actually lands a normal Ender Pearl
 * near the target, then throw it and wait for the server's own teleport (unlike Etherwarp there is no
 * client-side shortcut - a pearl is a real entity, so this only ever asks for the throw and watches the
 * player's own position for the jump).
 * <p>
 * The arc is the same real vanilla projectile physics {@link com.killer560.hub.trajectories.TrajectoriesFeature}
 * already renders for the player's own aim (drag 0.99/tick, gravity 0.03/tick, launch speed 1.5) - simulated here
 * instead of rendered, to solve for a pitch that lands within {@link #LANDING_TOLERANCE} of the target. Spirit
 * Pearls are excluded the same way Trajectories excludes them (name check - they don't throw like a normal pearl).
 */
public final class EnderPearlHopper {

    private static final double GRAVITY = 0.03;
    private static final double DRAG = 0.99;
    private static final double LAUNCH_SPEED = 1.5;
    private static final int MAX_SIM_TICKS = 140;
    private static final double LANDING_TOLERANCE = 3.0;
    private static final int HOP_TIMEOUT_TICKS = 100;
    /** How far the player must jump from the throw spot to count as "the pearl landed", real pearl teleports move
     *  you a real distance, so a small value here would false-positive on ordinary walking during the aim wait. */
    private static final double LANDED_DIST_SQ = 9.0;

    private static boolean busy;
    private static Vec3 expectedLanding;
    private static Runnable pendingDone;
    private static int waitTicks;
    private static Vec3 throwOrigin;

    private EnderPearlHopper() {
    }

    public static boolean isBusy() {
        return busy;
    }

    public static Vec3 expectedLanding() {
        return expectedLanding;
    }

    public static void cancel() {
        busy = false;
        pendingDone = null;
        expectedLanding = null;
        waitTicks = 0;
        throwOrigin = null;
    }

    /** Hotbar slot (0-8) holding a real, throwable Ender Pearl, or -1. Spirit Pearls never match. */
    public static int hotbarSlot() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return -1;
        }
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || stack.getItem() != Items.ENDER_PEARL) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            if (name != null && name.toLowerCase(Locale.ROOT).contains("spirit")) {
                continue; // Spirit Pearls read as a plain ENDER_PEARL client-side but teleport differently
            }
            return slot;
        }
        return -1;
    }

    public static boolean hasPearl() {
        return hotbarSlot() >= 0;
    }

    /** A solved throw: the yaw/pitch to use and where it actually lands. */
    public record Result(float yaw, float pitch, Vec3 landing) {
    }

    /** Rotation that lands a thrown pearl within {@link #LANDING_TOLERANCE} of {@code target}, or null when no
     *  sampled pitch gets close enough (out of real throwing range, or nothing to land on). */
    public static Result solve(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dz = target.z - eye.z;
        if (Math.sqrt(dx * dx + dz * dz) < 0.5) {
            return null; // basically standing on it already - walk it, don't throw
        }
        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        Result best = null;
        double bestErr = LANDING_TOLERANCE;
        // The real usable window for a Skyblock pearl clip before it either overshoots flat or barely leaves your
        // hand steep - sampled at 1 degree since a coarser step missed short-range clips.
        for (float pitch = -55f; pitch <= 25f; pitch += 1f) {
            Vec3 landing = simulate(eye, yaw, pitch);
            if (landing == null) {
                continue;
            }
            double err = landing.distanceTo(target);
            if (err < bestErr) {
                bestErr = err;
                best = new Result(yaw, pitch, landing);
            }
        }
        return best;
    }

    /** Same physics {@code TrajectoriesFeature} draws for a real thrown pearl - where it would actually land. */
    private static Vec3 simulate(Vec3 eye, float yaw, float pitch) {
        Minecraft client = Minecraft.getInstance();
        Level level = client.level;
        if (level == null) {
            return null;
        }
        double yawRad = Math.toRadians(-yaw - 180.0);
        double pitchRad = Math.toRadians(-pitch);
        double horizontal = -Math.cos(pitchRad);
        Vec3 motion = new Vec3(Math.sin(yawRad) * horizontal, Math.sin(pitchRad), Math.cos(yawRad) * horizontal)
                .normalize().scale(LAUNCH_SPEED);
        Vec3 pos = eye;
        for (int i = 0; i < MAX_SIM_TICKS; i++) {
            Vec3 next = pos.add(motion);
            HitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
                return blockHit.getLocation();
            }
            pos = next;
            motion = new Vec3(motion.x * DRAG, motion.y * DRAG - GRAVITY, motion.z * DRAG);
        }
        return pos;
    }

    /** Solves a throw at {@code target} and starts aiming for it (via {@link AutoWalker#lookAtAngle}); the actual
     *  throw happens once {@link #tick} sees the aim settle. @return false when no pearl or no solution exists. */
    public static boolean hop(Vec3 target, Runnable onDone) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || busy || hotbarSlot() < 0) {
            return false;
        }
        Result result = solve(player.getEyePosition(), target);
        if (result == null) {
            return false;
        }
        busy = true;
        waitTicks = 0;
        throwOrigin = player.position();
        expectedLanding = result.landing();
        pendingDone = onDone;
        AutoWalker.lookAtAngle(result.yaw(), result.pitch());
        return true;
    }

    /** Call every client tick (cheat build only, see {@link PathfindingFeature}): no-op unless {@link #isBusy()}. */
    public static void tick(Minecraft client) {
        if (!busy) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            cancel();
            return;
        }
        if (++waitTicks > HOP_TIMEOUT_TICKS) {
            ModChat.send("Auto Fairy Souls", ModChat.bad("Pearl throw timed out"), ModChat.dim(" - no landing detected."));
            finish();
            return;
        }
        if (waitTicks < 4) {
            return; // let AutoWalker's rotation smoothing (Rotation 360 rule) settle onto the solved angle first
        }
        if (AutoWalker.aimError() > 2.0f) {
            return;
        }
        if (waitTicks == 4 || waitTicks % 20 == 0) {
            int slot = hotbarSlot();
            if (slot < 0) {
                finish();
                return;
            }
            player.getInventory().setSelectedSlot(slot);
            client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        // No client-side teleport trick here (unlike Etherwarp) - the server moves us once the real pearl entity
        // lands, so a real position jump away from the throw spot is the only reliable "it landed" signal.
        if (throwOrigin != null && player.position().distanceToSqr(throwOrigin) > LANDED_DIST_SQ) {
            finish();
        }
    }

    private static void finish() {
        Runnable done = pendingDone;
        cancel();
        if (done != null) {
            done.run();
        }
    }

    public static void warnNoItem() {
        ModChat.send("Auto Fairy Souls", ModChat.bad("No Ender Pearl in the hotbar"), ModChat.dim(" - walking only."));
    }
}
