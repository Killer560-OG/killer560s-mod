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
 * no new mixin at all for a check this cheap. Marks the real key with a circle on the ground of the radius it is picked
 * up from, and an optional tracer line from your eyes to it - never picks anything up.
 * <p>
 * The circle (killer560, 2026-10-09: "instead of the hitbox have it show a circle that is where you can pick it up
 * from; make sure it matches the new update"): Hypixel SkyBlock 0.27.2 (2026-10-06, hypixelskyblock.minecraft.wiki
 * Changelog/2026/October 6, "Folf Shard &amp; Dungeon Improvements") "Increased the pickup range of Wither Keys and
 * Blood Keys in the Catacombs by 5 blocks". Its radius is {@link DungeonKeys#pickupRange} of the Key Base Range setting
 * (Auto Clear tab, 1 block by default, which nothing published states) - the same number Auto Clear walks to, so the
 * two can never disagree. It lies on the first solid block under the key.
 */
public final class DoorKeysFeature {

    private static Entity currentKey = null;
    private static float[] currentColor = null;
    /** The circle's centre (on the ground under the key) and radius: {x, y, z, r}, or null. */
    private static volatile double[] ring = null;
    private static final int RING_SEGMENTS = 64;
    /** How far below the key the ground is looked for. */
    private static final int GROUND_SEARCH = 6;

    private DoorKeysFeature() {
    }

    public static void register() {
        DungeonKeys.register();
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("DoorKeysFeature.tick", DoorKeysFeature::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(DoorKeysFeature::onWorldRender);
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
        if (currentKey != null && !currentKey.isRemoved()) {
            ring = ringFor(client, currentKey);
            return;
        }
        currentKey = null;
        ring = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand)) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(entity.getName().getString());
            if (name == null) {
                continue;
            }
            if ("Wither Key".equals(name) && cfg.isHighlightWither()) {
                currentKey = entity;
                currentColor = new float[]{0.1f, 0.1f, 0.1f};
                break;
            }
            if ("Blood Key".equals(name) && cfg.isHighlightBlood()) {
                currentKey = entity;
                currentColor = new float[]{0.8f, 0.05f, 0.05f};
                break;
            }
        }
        if (currentKey != null) {
            ring = ringFor(client, currentKey);
        }
    }

    /** The pickup circle for {@code key}: centred under it on the top of the first solid block below. */
    private static double[] ringFor(Minecraft client, Entity key) {
        Vec3 p = key.position();
        net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos(
                Math.floor(p.x), Math.floor(p.y), Math.floor(p.z));
        double groundY = p.y;
        for (int i = 0; i <= GROUND_SEARCH; i++) {
            if (!client.level.getBlockState(at).isAir()) {
                groundY = at.getY() + 1.0;
                break;
            }
            at.move(0, -1, 0);
        }
        double radius = com.killer560.hub.autosecret.AutoSecretConfig.getInstance().keyPickupRange();
        return new double[]{p.x, groundY + 0.02, p.z, radius};
    }

    /** For the testkit: the circle being drawn, {x, y, z, radius}, or null. */
    public static double[] pickupRing() {
        double[] r = ring;
        return r == null ? null : r.clone();
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
        List<Vec3> points = new java.util.ArrayList<>(RING_SEGMENTS + 1);
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double a = Math.PI * 2.0 * i / RING_SEGMENTS;
            points.add(new Vec3(circle[0] + Math.cos(a) * circle[3], circle[1], circle[2] + Math.sin(a) * circle[3]));
        }
        // killer560, 2026-09-27: "Door keys tracer line is still jacked up" - it was starting from
        // client.player.getEyePosition(), which only moves once per game tick and lagged behind the
        // smoothly-interpolated camera, reading as attached to the player's head instead of the crosshair.
        // WorldRenderUtils.tracerOrigin(context) is the camera's own position, nudged forward so the line doesn't
        // collapse to a dot when looking straight down it - see that method's doc.
        Vec3 tracerStart = WorldRenderUtils.tracerOrigin(context);
        // killer560, 2026-09-20: "make it so the wither key tracer goes to some fixed midpoint that is
        // unmoving. Right now it really bugs out." It was aimed at the dropped item's live position, and a
        // dropped item bobs up and down and spins forever - so the far end of the line jittered every frame.
        // Anchor it to the centre of the block the key is resting in instead: it stops moving the moment the
        // key settles, and still follows if the key is actually thrown somewhere else.
        Vec3 keyPos = currentKey.position();
        Vec3 target = new Vec3(Math.floor(keyPos.x) + 0.5, Math.floor(keyPos.y) + 0.5, Math.floor(keyPos.z) + 0.5);
        float thickness = cfg.getTracerThickness();

        if (!cfg.isThroughWalls()) {
            WorldRenderUtils.renderLineStrip(context, points, currentColor[0], currentColor[1], currentColor[2], 1f, 2f);
            if (cfg.isShowTracer()) {
                WorldRenderUtils.renderLineStrip(context, List.of(tracerStart, target),
                        currentColor[0], currentColor[1], currentColor[2], 1f, thickness);
            }
            return;
        }

        McRender.inCameraSpace(context, ThroughWalls.LINES, (pose, buffer) -> {
            for (int i = 0; i + 1 < points.size(); i++) {
                line(pose, buffer, points.get(i), points.get(i + 1), 2f);
            }
            if (cfg.isShowTracer()) {
                line(pose, buffer, tracerStart, target, thickness);
            }
        });
    }

    private static void line(PoseStack.Pose pose, VertexConsumer buffer, Vec3 from, Vec3 to, float width) {
        segment(pose, buffer, (float) from.x, (float) from.y, (float) from.z,
                (float) to.x, (float) to.y, (float) to.z, width);
    }

    private static void segment(PoseStack.Pose pose, VertexConsumer buffer, float sx, float sy, float sz,
                                float ex, float ey, float ez, float width) {
        float dx = ex - sx, dy = ey - sy, dz = ez - sz;
        float r = currentColor[0], g = currentColor[1], b = currentColor[2];
        buffer.addVertex(pose, sx, sy, sz).setColor(r, g, b, 1f).setNormal(pose, dx, dy, dz).setLineWidth(width);
        buffer.addVertex(pose, ex, ey, ez).setColor(r, g, b, 1f).setNormal(pose, dx, dy, dz).setLineWidth(width);
    }
}
