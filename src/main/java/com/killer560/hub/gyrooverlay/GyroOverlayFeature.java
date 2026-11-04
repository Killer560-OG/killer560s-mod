package com.killer560.hub.gyrooverlay;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Gyrokinetic Wand Overlay (to-do Doc 2026-11-01, "other mods have it for you to reference"): Skyblocker's
 * {@code GyroOverlay}. While you hold the Gyrokinetic Wand (Skyblock id {@code GYROKINETIC_WAND}) in your main hand, the
 * ring its ability pulls mobs from - {@link #RADIUS} blocks around the block or entity you look at, up to
 * {@link #REACH} blocks away (Skyblocker's numbers) - is drawn there: a circle outline, a filled circle, or a sphere.
 * Render only: it raycasts on the client and sends nothing.
 */
public final class GyroOverlayFeature {

    public static final double RADIUS = 10.0;
    public static final double REACH = 24.0;
    private static final int SEGMENTS = 48;
    public static final String WAND_ID = "GYROKINETIC_WAND";

    private static long framesDrawn = 0;
    private static String lastShape = null;
    private static Vec3 lastCentre = null;

    private GyroOverlayFeature() {
    }

    public static void register() {
        GyroOverlayConfig.getInstance();
        LevelRenderEvents.COLLECT_SUBMITS.register(FeatureGuard.collectFeatures(
                FeatureBits.register("Gyro Overlay render", () -> GyroOverlayConfig.getInstance().isEnabledRaw()),
                GyroOverlayFeature::onWorldRender));
    }

    /** Whether {@code player} holds the wand in the main hand. */
    static boolean holdsWand(LocalPlayer player) {
        return player != null && WAND_ID.equals(ItemIdentity.skyblockId(player.getMainHandItem()));
    }

    private static void onWorldRender(LevelRenderContext context) {
        GyroOverlayConfig cfg = GyroOverlayConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (!cfg.isEnabled() || client.level == null || !holdsWand(client.player)) {
            return;
        }
        Entity camera = client.getCameraEntity();
        if (camera == null) {
            return;
        }
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        HitResult hit = camera.pick(REACH, partial, false);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return;
        }
        Vec3 at = hit.getLocation();
        float[] c = WorldRenderUtils.argbToFloats(cfg.getColor());
        switch (cfg.getStyle()) {
            case CIRCLE_OUTLINE -> {
                Vec3[] pts = new Vec3[SEGMENTS + 1];
                for (int i = 0; i <= SEGMENTS; i++) {
                    double a = Math.PI * 2.0 * i / SEGMENTS;
                    pts[i] = new Vec3(at.x + Math.cos(a) * RADIUS, at.y + 0.1, at.z + Math.sin(a) * RADIUS);
                }
                WorldRenderUtils.renderLineStrip(context, pts, c[0], c[1], c[2], c[3], cfg.getLineWidth());
            }
            case CIRCLE -> WorldRenderUtils.renderFilledCylinder(context, at, RADIUS, at.y + 0.1, at.y + 0.1, SEGMENTS,
                    c[0], c[1], c[2], c[3]);
            case SPHERE -> WorldRenderUtils.renderFilledSphere(context, at, RADIUS, 16, 32, c[0], c[1], c[2], c[3]);
        }
        framesDrawn++;
        lastShape = cfg.getStyle().name();
        lastCentre = at;
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    public static String lastShape() {
        return lastShape;
    }

    public static String lastCentre() {
        return lastCentre == null ? null : String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", lastCentre.x, lastCentre.y, lastCentre.z);
    }
}
