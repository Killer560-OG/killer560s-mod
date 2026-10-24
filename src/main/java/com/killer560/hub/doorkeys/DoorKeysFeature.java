package com.killer560.hub.doorkeys;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Real Hypixel dungeon Wither/Blood Key ESP, ported from noamm's own {@code DoorKeys.kt}. Real dropped
 * door keys are real {@code ArmorStand}s with a fixed real display name ("Wither Key"/"Blood Key") -
 * this polls real rendered entities each tick (the same real technique Weirdos/Blaze/Livid Solver
 * already use tonight) rather than porting noamm's own entity-metadata-packet hook, since polling needs
 * no new mixin at all for a check this cheap. Marks the real key with a circle at the key's own height of the radius it
 * is picked up from (or a shaded sphere of it), and an optional tracer line from your eyes to it - never picks anything up.
 * <p>
 * The circle (killer560, 2026-10-09: "instead of the hitbox have it show a circle that is where you can pick it up
 * from; make sure it matches the new update"): Hypixel SkyBlock 0.27.2 (2026-10-06, hypixelskyblock.minecraft.wiki
 * Changelog/2026/October 6, "Folf Shard &amp; Dungeon Improvements") "Increased the pickup range of Wither Keys and
 * Blood Keys in the Catacombs by 5 blocks". Its radius is {@link DungeonKeys#pickupRange} of the Key Base Range setting
 * (Auto Clear tab, 1 block by default, which nothing published states) - the same number Auto Clear walks to, so the
 * two can never disagree.
 * <p>
 * killer560, 2026-10-15: "Radius is just a circle drawn at its height." The circle is centered on the key's stand at the
 * stand's own height - the point every pickup check measures his feet to, and the sphere's equator - where until then it
 * lay on the first solid block under the key.
 */
public final class DoorKeysFeature {

    private static Entity currentKey = null;
    private static float[] currentColor = null;
    private static boolean currentBlood = false;
    /** The circle's center (the key's stand, at its height) and radius: {x, y, z, r}, or null. */
    private static volatile double[] ring = null;
    private static final int RING_SEGMENTS = 64;
    /** How opaque the Sphere style's shading is: enough to see the edge of the range, not enough to hide the room. */
    private static final float SPHERE_ALPHA = 0.22f;

    private DoorKeysFeature() {
    }

    public static void register() {
        DungeonKeys.register();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DoorKeysFeature.tick", com.killer560.hub.util.FeatureBits.register("Door Keys tick", () -> DoorKeysConfig.getInstance().isEnabledRaw()), DoorKeysFeature::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(com.killer560.hub.util.FeatureGuard.afterFeatures(
                com.killer560.hub.util.FeatureBits.register("Door Keys render", () -> DoorKeysConfig.getInstance().isEnabledRaw()), DoorKeysFeature::onWorldRender));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Register the no-depth pipeline during client init, before the renderer precompiles its list -
            // same reason MobEsp's own EspRenderer.init() exists.
            RenderType unused = ThroughWalls.LINES;
        }
    }

    /** Copy of vanilla's line pipeline with the depth/stencil state removed, so the key's box and tracer draw
     *  through walls (cheat build only). Same technique NoammAddons' {@code lines_through_walls} and this
     *  mod's own {@code mobesp.EspRenderer} use; its copy is package-private there, hence this one. Holder
     *  idiom: built on the first touch, which {@link #register()} does at client init. */
    private static final class ThroughWalls {
        static final RenderType LINES = RenderType.create("killer560smod_doorkeys_lines_through_walls",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/doorkeys_lines_through_walls"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());
    }

    private static void tick(Minecraft client) {
        tickInner(client);
    }

    private static void tickInner(Minecraft client) {
        DoorKeysConfig cfg = DoorKeysConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isInDungeon() || DungeonState.isBossPhaseActive()
                || client.level == null) {
            currentKey = null;
            ring = null;
            return;
        }
        // A key whose Wither/Blood switch has since been turned off is let go (it used to stay drawn until it was picked up).
        if (currentKey != null && !currentKey.isRemoved()
                && (currentBlood ? cfg.isHighlightBlood() : cfg.isHighlightWither())) {
            ring = ringFor(client, currentKey);
            return;
        }
        currentKey = null;
        ring = null;
        if (!cfg.isHighlightWither() && !cfg.isHighlightBlood()) {
            return; // nothing to look for: no entity walk
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            // A key stand's "Wither Key" / "Blood Key" is its custom name (an unnamed stand is "Armor Stand"), so an
            // unnamed one - most of a dungeon's stands - is turned away before its name String is built (FPS pass).
            if (!(entity instanceof ArmorStand) || !entity.hasCustomName()) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(entity.getName().getString());
            if (name == null) {
                continue;
            }
            if ("Wither Key".equals(name) && cfg.isHighlightWither()) {
                currentKey = entity;
                currentColor = new float[]{0.1f, 0.1f, 0.1f};
                currentBlood = false;
                break;
            }
            if ("Blood Key".equals(name) && cfg.isHighlightBlood()) {
                currentKey = entity;
                currentColor = new float[]{0.8f, 0.05f, 0.05f};
                currentBlood = true;
                break;
            }
        }
        if (currentKey != null) {
            ring = ringFor(client, currentKey);
        }
    }

    /** The pickup circle for {@code key}: centered on its stand, at the stand's own height. */
    private static double[] ringFor(Minecraft client, Entity key) {
        Vec3 p = key.position();
        double radius = com.killer560.hub.autosecret.AutoSecretConfig.getInstance().keyPickupRange();
        double[] last = ring;
        if (last != null && last[0] == p.x && last[1] == p.y && last[2] == p.z && last[3] == radius) {
            return last; // unchanged: keep it, and the ring points built from it
        }
        return new double[]{p.x, p.y, p.z, radius};
    }

    /** The circle's points, built again only when the ring (the key moved) or the height it is drawn at changed. */
    private static double[] ringPointsFor = null;
    private static double ringPointsY = Double.NaN;
    private static Vec3[] ringPoints = null;

    private static Vec3[] ringPoints(double[] circle, double y) {
        if (circle != ringPointsFor || y != ringPointsY) {
            Vec3[] pts = new Vec3[RING_SEGMENTS + 1];
            for (int i = 0; i <= RING_SEGMENTS; i++) {
                double a = Math.PI * 2.0 * i / RING_SEGMENTS;
                pts[i] = new Vec3(circle[0] + Math.cos(a) * circle[3], y, circle[2] + Math.sin(a) * circle[3]);
            }
            ringPoints = pts;
            ringPointsFor = circle;
            ringPointsY = y;
        }
        return ringPoints;
    }

    /**
     * The height the Radius circle is drawn at: the middle of the key stand's box plus the Circle Height setting (killer560,
     * 2026-10-18: "The key's outline for the circle is too low" - it lay at the stand's feet). The Sphere style keeps its
     * equator at the stand's own position, the point every pickup check measures from, so it ignores this.
     */
    public static double circleY(double standY, double standBoxHeight, float lift) {
        return standY + standBoxHeight * 0.5 + lift;
    }

    /**
     * Where the tracer ends: the center of the block the key rests in (x and z), at the circle's own height with the Radius
     * style so the line meets the ring (killer560, 2026-10-18: "have the tracer point to the same height as the circle").
     * The Sphere style's circle is its equator at the stand's position, and its tracer keeps the block center's height.
     */
    public static Vec3 tracerTarget(Vec3 keyPos, double standBoxHeight, float lift, boolean sphere) {
        double y = sphere ? Math.floor(keyPos.y) + 0.5 : circleY(keyPos.y, standBoxHeight, lift);
        return new Vec3(Math.floor(keyPos.x) + 0.5, y, Math.floor(keyPos.z) + 0.5);
    }

    /** A color setting as r, g, b, a floats: {@code configured} (ARGB) unless it is {@link DoorKeysConfig#COLOR_AUTO},
     *  then {@code auto} with {@code autoAlpha}. */
    public static float[] resolveColor(int configured, float[] auto, float autoAlpha) {
        if (configured == DoorKeysConfig.COLOR_AUTO) {
            return new float[]{auto[0], auto[1], auto[2], autoAlpha};
        }
        return WorldRenderUtils.argbToFloats(configured);
    }

    /** For the testkit: the circle being drawn, {x, y, z, radius}, or null. With the Sphere style it is the sphere's
     *  equator ({@link #pickupSphere()} is the ball). */
    public static double[] pickupRing() {
        double[] r = ring;
        return r == null ? null : r.clone();
    }

    /** For the testkit: the sphere the Sphere style draws, {x, y, z, radius} - centered on the key's stand, whose
     *  position every pickup check measures his feet to - or null when no key is shown. */
    public static double[] pickupSphere() {
        Entity key = currentKey;
        double[] r = ring;
        if (key == null || r == null) {
            return null;
        }
        Vec3 p = key.position();
        return new double[]{p.x, p.y, p.z, r[3]};
    }

    /** For the testkit: the style the last frame drew ("radius", "sphere"), or "" before any. */
    private static volatile String lastDrawn = "";

    public static String lastDrawnStyle() {
        return lastDrawn;
    }

    private static void onWorldRender(LevelRenderContext context) {
        DoorKeysConfig cfg = DoorKeysConfig.getInstance();
        if (!cfg.isEnabled() || currentKey == null || currentKey.isRemoved()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        double[] circle = ring;
        if (circle == null) {
            return;
        }
        // Built on the ring's change, not every frame (65 Vec3 and a list per frame before; FPS pass).
        float[] circleRgba = resolveColor(cfg.getCircleColor(), currentColor, 1f);
        float[] tracerRgba = resolveColor(cfg.getTracerColor(), currentColor, 1f);
        boolean sphere = cfg.getRangeStyle() == DoorKeysConfig.RANGE_SPHERE;
        Vec3[] points = ringPoints(circle, sphere ? circle[1]
                : circleY(circle[1], currentKey.getBbHeight(), cfg.getCircleLift()));
        float circleWidth = cfg.getCircleThickness();
        // killer560, 2026-09-27: "Door keys tracer line is still jacked up" - it was starting from
        // client.player.getEyePosition(), which only moves once per game tick and lagged behind the
        // smoothly-interpolated camera, reading as attached to the player's head instead of the crosshair.
        // WorldRenderUtils.tracerOrigin(context) is the camera's own position, nudged forward so the line doesn't
        // collapse to a dot when looking straight down it - see that method's doc.
        Vec3 tracerStart = WorldRenderUtils.tracerOrigin(context);
        // killer560, 2026-09-20: "make it so the wither key tracer goes to some fixed midpoint that is
        // unmoving. Right now it really bugs out." It was aimed at the dropped item's live position, and a
        // dropped item bobs up and down and spins forever - so the far end of the line jittered every frame.
        // Anchor it to the center of the block the key is resting in instead: it stops moving the moment the
        // key settles, and still follows if the key is actually thrown somewhere else.
        Vec3 keyPos = currentKey.position();
        Vec3 target = tracerTarget(keyPos, currentKey.getBbHeight(), cfg.getCircleLift(), sphere);
        float thickness = cfg.getTracerThickness();
        if (sphere) {
            // A see-through shaded ball of the pickup radius around the stand: step inside it and the key is his.
            // The Wither Key's own near-black would vanish as a fill, so it is drawn as the light gray of its name.
            float[] c = currentColor[0] < 0.2f && currentColor[1] < 0.2f ? new float[]{0.75f, 0.75f, 0.8f} : currentColor;
            float[] fill = resolveColor(cfg.getFillColor(), c, SPHERE_ALPHA);
            WorldRenderUtils.renderFilledSphere(context, keyPos, circle[3], 12, 24, fill[0], fill[1], fill[2], fill[3]);
            lastDrawn = "sphere";   // the circle below is its equator
        } else {
            lastDrawn = "radius";
        }

        if (!cfg.isThroughWalls()) {
            WorldRenderUtils.renderLineStrip(context, points, circleRgba[0], circleRgba[1], circleRgba[2], circleRgba[3], circleWidth);
            if (cfg.isShowTracer()) {
                WorldRenderUtils.renderLineStrip(context, List.of(tracerStart, target),
                        tracerRgba[0], tracerRgba[1], tracerRgba[2], tracerRgba[3], thickness);
            }
            return;
        }

        McRender.inCameraSpace(context, ThroughWalls.LINES, (pose, buffer) -> {
            for (int i = 0; i + 1 < points.length; i++) {
                line(pose, buffer, points[i], points[i + 1], circleWidth, circleRgba);
            }
            if (cfg.isShowTracer()) {
                line(pose, buffer, tracerStart, target, thickness, tracerRgba);
            }
        });
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, Vec3 from, Vec3 to, float width, float[] rgba) {
        segment(pose, buffer, (float) from.x, (float) from.y, (float) from.z,
                (float) to.x, (float) to.y, (float) to.z, width, rgba);
    }

    private static void segment(PoseStack.Pose pose, VertexConsumer buffer, float sx, float sy, float sz,
                                float ex, float ey, float ez, float width, float[] rgba) {
        float dx = ex - sx, dy = ey - sy, dz = ez - sz;
        buffer.addVertex(pose, sx, sy, sz).setColor(rgba[0], rgba[1], rgba[2], rgba[3]).setNormal(pose, dx, dy, dz).setLineWidth(width);
        buffer.addVertex(pose, ex, ey, ez).setColor(rgba[0], rgba[1], rgba[2], rgba[3]).setNormal(pose, dx, dy, dz).setLineWidth(width);
    }
}
