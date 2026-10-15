package com.killer560.hub.trajectories;

import com.killer560.hub.autopuzzles.AutoPuzzleUtil;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/**
 * Real bow-arrow / Ender Pearl trajectory prediction, ported from QUOI's own {@code Trajectories.kt}
 * (simplified: no impact-face "plane" render, no in-flight entity-blocks-the-shot check, and the bow
 * charge isn't partial-tick-interpolated - all cosmetic refinements, not correctness). Real mechanic:
 * simulates the exact real vanilla projectile physics (drag 0.99/tick both axes, gravity -0.05/tick for
 * arrows and -0.03/tick for pearls - real, well-documented vanilla constants) step by step, raycasting
 * each simulated step with a real {@code Level#clip} call (the same real technique
 * {@code DungeonBreakerFeature} already uses) to find where it would actually land. Real Ender Pearl
 * velocity is a flat 1.5; real bow arrow velocity scales with how long you've been drawing the bow
 * (real {@code LivingEntity#getUseItemRemainingTicks()}, against the real 72000-tick max draw time).
 * Spirit Pearls are correctly excluded (they don't behave like a normal thrown pearl). Skyblock shortbows
 * ({@link Shortbows}) show their full-power path the whole time they are held, one arc per arrow (three for the
 * Terminator); a normal bow shows only while drawn. Purely visual - never fires anything.
 */
public final class TrajectoriesFeature {

    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_GRAVITY = 0.03;
    /** A fully drawn vanilla bow's arrow speed (power 1.0 x 3.0), which every shortbow shot leaves at. */
    private static final double FULL_BOW_SPEED = 3.0;

    private TrajectoriesFeature() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(TrajectoriesFeature::onWorldRender);
    }

    /** Test views (testkit 1080-1083): every render call, the frames that drew at least one arc, and what the last
     *  call drew - how many arcs, of which kind, each arc's absolute yaw, the shared pitch, the launch speed and the
     *  simulated points. Reset at the top of every call, so a frame that draws nothing reads 0 arcs. */
    private static long renderCalls;
    private static long framesDrawn;
    private static int lastArcCount;
    private static String lastKind = "NONE";
    private static float[] lastYaws = new float[0];
    private static float lastPitch;
    private static double lastSpeed;
    private static final List<List<Vec3>> lastArcs = new ArrayList<>();

    public static long renderCalls() {
        return renderCalls;
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    public static int lastArcCount() {
        return lastArcCount;
    }

    public static String lastKind() {
        return lastKind;
    }

    public static float[] lastYaws() {
        return lastYaws.clone();
    }

    public static float lastPitch() {
        return lastPitch;
    }

    public static double lastSpeed() {
        return lastSpeed;
    }

    /** Copies of the last call's arcs (each its simulated points, the impact last when it hit a block). */
    public static List<List<Vec3>> lastArcs() {
        List<List<Vec3>> out = new ArrayList<>();
        for (List<Vec3> arc : lastArcs) {
            out.add(new ArrayList<>(arc));
        }
        return out;
    }

    private static void onWorldRender(LevelRenderContext context) {
        renderCalls++;
        lastArcCount = 0;
        lastKind = "NONE";
        lastYaws = new float[0];
        lastSpeed = 0;
        lastArcs.clear();
        TrajectoriesConfig cfg = TrajectoriesConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || McCompat.screen(client) != null || client.player == null || client.level == null) {
            return;
        }
        Player player = client.player;
        ItemStack heldItem = player.getMainHandItem();
        Item item = heldItem.getItem();
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        if (Shortbows.isShortbow(heldItem)) {
            // killer560 (2026-10-15): a shortbow fires the moment it is clicked, always at full power, so its path shows
            // the whole time it is held - no draw. A Terminator's three arrows each get their own arc, the side ones at
            // the same pitch and +-5 degrees of yaw. Its arrows leave from the eye (QUOI's arrowOrigin), the rest from
            // the vanilla bow's hand offset.
            if (!cfg.isShowShortbows()) {
                return;
            }
            boolean terminator = Shortbows.isTerminator(heldItem);
            Vec3 eye = player.getEyePosition();
            float[] offsets = Shortbows.yawOffsets(Shortbows.arrowCount(heldItem));
            float[] yaws = new float[offsets.length];
            for (int i = 0; i < offsets.length; i++) {
                yaws[i] = yaw + offsets[i];
                Vec3 origin = AutoPuzzleUtil.arrowOrigin(eye, yaws[i], terminator);
                renderTrajectory(context, client.level, player, origin, yaws[i], pitch, false, FULL_BOW_SPEED);
            }
            record("SHORTBOW", yaws, pitch, FULL_BOW_SPEED);
            return;
        }
        if (cfg.isShowBows() && item instanceof BowItem) {
            // A normal bow shows only while it is being drawn (it used to read an idle bow as fully drawn: no use
            // ticks left gave 72000 - 0), with vanilla's draw curve (BowItem.getPowerForTime) for the speed.
            if (!player.isUsingItem() || player.getUsedItemHand() != InteractionHand.MAIN_HAND) {
                return;
            }
            float t = (72000 - player.getUseItemRemainingTicks()) / 20f;
            float power = Math.min((t * t + t * 2f) / 3f, 1f);
            if (power < 0.1f) {
                return; // vanilla releases nothing below 0.1
            }
            double speed = power * FULL_BOW_SPEED;
            renderTrajectory(context, client.level, player, handOrigin(player), yaw, pitch, false, speed);
            record("BOW", new float[]{yaw}, pitch, speed);
            return;
        }
        if (cfg.isShowPearls() && item instanceof EnderpearlItem) {
            String name = ChatFormatting.stripFormatting(heldItem.getHoverName().getString());
            if (name == null || !name.toLowerCase(java.util.Locale.ROOT).contains("spirit")) {
                renderTrajectory(context, client.level, player, handOrigin(player), yaw, pitch, true, 1.5);
                record("PEARL", new float[]{yaw}, pitch, 1.5);
            }
        }
    }

    private static void record(String kind, float[] yaws, float pitch, double speed) {
        lastKind = kind;
        lastYaws = yaws;
        lastPitch = pitch;
        lastSpeed = speed;
        lastArcCount = lastArcs.size();
        if (lastArcCount > 0) {
            framesDrawn++;
        }
    }

    private static Vec3 handOrigin(Player player) {
        double yawRad = Math.toRadians(player.getYRot());
        return player.position().add(-Math.cos(yawRad) * 0.16, player.getEyeHeight() - 0.1, -Math.sin(yawRad) * 0.16);
    }

    private static void renderTrajectory(LevelRenderContext context, Level level, Player player, Vec3 start,
                                          float yaw, float pitch, boolean isPearl, double velocityMultiplier) {
        int range = TrajectoriesConfig.getInstance().getRange();
        Vec3 position = start;
        Vec3 motion = lookVector(yaw, pitch).normalize().scale(velocityMultiplier);

        List<Vec3> points = new ArrayList<>();
        AABB impactBox = null;

        for (int i = 0; i < range; i++) {
            points.add(position);
            Vec3 nextPosition = position.add(motion);
            HitResult hit = level.clip(new ClipContext(position, nextPosition,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));

            if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
                points.add(blockHit.getLocation());
                Vec3 loc = blockHit.getLocation();
                impactBox = new AABB(loc.x - 0.15, loc.y - 0.15, loc.z - 0.15,
                        loc.x + 0.15, loc.y + 0.15, loc.z + 0.15);
                break;
            }

            position = nextPosition;
            double drag = isPearl ? PEARL_DRAG : ARROW_DRAG;
            double gravity = isPearl ? PEARL_GRAVITY : ARROW_GRAVITY;
            motion = new Vec3(motion.x * drag, motion.y * drag - gravity, motion.z * drag);
        }

        if (points.size() >= 2) {
            WorldRenderUtils.renderLineStrip(context, points, 0.3f, 0.9f, 1.0f, 1f, 2f);
            lastArcs.add(points);
        }
        if (impactBox != null) {
            WorldRenderUtils.renderOutlineBox(context, impactBox, 0.3f, 0.9f, 1.0f, 1f, 2f);
        }
    }

    private static Vec3 lookVector(float yaw, float pitch) {
        double yawRad = Math.toRadians(-yaw - 180.0);
        double pitchRad = Math.toRadians(-pitch);
        double horizontal = -Math.cos(pitchRad);
        return new Vec3(Math.sin(yawRad) * horizontal, Math.sin(pitchRad), Math.cos(yawRad) * horizontal);
    }
}
