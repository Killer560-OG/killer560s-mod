package com.killer560.hub.trajectories;

import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
 * Spirit Pearls are correctly excluded (they don't behave like a normal thrown pearl). Purely visual -
 * never fires anything.
 */
public final class TrajectoriesFeature {

    private static final double ARROW_DRAG = 0.99;
    private static final double ARROW_GRAVITY = 0.05;
    private static final double PEARL_DRAG = 0.99;
    private static final double PEARL_GRAVITY = 0.03;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-trajectories");
    private static String diagLastMode = "none";

    private TrajectoriesFeature() {
    }

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(TrajectoriesFeature::onWorldRender);
    }

    private static void onWorldRender(LevelRenderContext context) {
        TrajectoriesConfig cfg = TrajectoriesConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.screen != null || client.player == null || client.level == null) {
            return;
        }
        Player player = client.player;
        ItemStack heldItem = player.getMainHandItem();
        Item item = heldItem.getItem();
        // Diagnostic (2026-09-14): state-change only - which trajectory (if any) is being drawn.
        String diagMode = cfg.isShowBows() && item instanceof BowItem ? "BOW"
                : cfg.isShowPearls() && item instanceof EnderpearlItem ? "PEARL" : "none";
        if (!diagMode.equals(diagLastMode)) {
            LOGGER.info("[Trajectories] mode {} -> {} (held \"{}\", range={})", diagLastMode, diagMode,
                    heldItem.isEmpty() ? "" : heldItem.getHoverName().getString(), cfg.getRange());
            diagLastMode = diagMode;
        }

        if (cfg.isShowBows() && item instanceof BowItem) {
            float charge = Math.min((72000 - player.getUseItemRemainingTicks()) / 20f, 1.0f) * 2f;
            renderTrajectory(context, client.level, player, false, Math.max(charge, 0f) * 1.5);
        }
        if (cfg.isShowPearls() && item instanceof EnderpearlItem) {
            String name = ChatFormatting.stripFormatting(heldItem.getHoverName().getString());
            if (name == null || !name.toLowerCase(java.util.Locale.ROOT).contains("spirit")) {
                renderTrajectory(context, client.level, player, true, 1.5);
            }
        }
    }

    private static void renderTrajectory(LevelRenderContext context, Level level, Player player,
                                          boolean isPearl, double velocityMultiplier) {
        int range = TrajectoriesConfig.getInstance().getRange();
        double yawRad = Math.toRadians(player.getYRot());
        Vec3 spawnOffset = new Vec3(-Math.cos(yawRad) * 0.16, player.getEyeHeight() - 0.1, -Math.sin(yawRad) * 0.16);
        Vec3 position = player.position().add(spawnOffset);
        Vec3 motion = lookVector(player.getYRot(), player.getXRot()).normalize().scale(velocityMultiplier);

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
