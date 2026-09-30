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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Real Hypixel dungeon Wither/Blood Key ESP, ported from noamm's own {@code DoorKeys.kt}. Real dropped
 * door keys are real {@code ArmorStand}s with a fixed real display name ("Wither Key"/"Blood Key") -
 * this polls real rendered entities each tick (the same real technique Weirdos/Blaze/Livid Solver
 * already use tonight) rather than porting noamm's own entity-metadata-packet hook, since polling needs
 * no new mixin at all for a check this cheap. Highlights the real key with a box and an optional tracer
 * line from your eyes to it - never picks anything up.
 */
public final class DoorKeysFeature {

    private static Entity currentKey = null;
    private static float[] currentColor = null;

    private DoorKeysFeature() {
    }

    public static void register() {
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
            return;
        }
        if (currentKey != null && !currentKey.isRemoved()) {
            return;
        }
        currentKey = null;
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
        AABB box = currentKey.getBoundingBox().inflate(0.3, 0.3, 0.3);
        // killer560, 2026-09-27: "Door keys tracer line is still jacked up" - it was starting from
        // client.player.getEyePosition(), which only moves once per game tick and lagged behind the
        // smoothly-interpolated camera, reading as attached to the player's head instead of the crosshair.
        // WorldRenderUtils.tracerOrigin() is the camera's own position, nudged forward so the line doesn't
        // collapse to a dot when looking straight down it - see that method's doc.
        Vec3 tracerStart = WorldRenderUtils.tracerOrigin();
        // killer560, 2026-09-20: "make it so the wither key tracer goes to some fixed midpoint that is
        // unmoving. Right now it really bugs out." It was aimed at the dropped item's live position, and a
        // dropped item bobs up and down and spins forever - so the far end of the line jittered every frame.
        // Anchor it to the centre of the block the key is resting in instead: it stops moving the moment the
        // key settles, and still follows if the key is actually thrown somewhere else.
        Vec3 keyPos = currentKey.position();
        Vec3 target = new Vec3(Math.floor(keyPos.x) + 0.5, Math.floor(keyPos.y) + 0.5, Math.floor(keyPos.z) + 0.5);
        float thickness = cfg.getTracerThickness();

        if (!cfg.isThroughWalls()) {
            WorldRenderUtils.renderOutlineBox(context, box, currentColor[0], currentColor[1], currentColor[2], 1f, 2f);
            if (cfg.isShowTracer()) {
                WorldRenderUtils.renderLineStrip(context, List.of(tracerStart, target),
                        currentColor[0], currentColor[1], currentColor[2], 1f, thickness);
            }
            return;
        }

        McRender.inCameraSpace(context, ThroughWalls.LINES, (pose, buffer) -> {
            lineBox(pose, buffer, box, 2f);
            if (cfg.isShowTracer()) {
                line(pose, buffer, tracerStart, target, thickness);
            }
        });
    }

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    /** Wireframe box in the current colour - same Odin-derived edge order {@code WorldRenderUtils} uses. */
    private static void lineBox(PoseStack.Pose pose, VertexConsumer buffer, AABB b, float width) {
        float x0 = (float) b.minX, y0 = (float) b.minY, z0 = (float) b.minZ;
        float x1 = (float) b.maxX, y1 = (float) b.maxY, z1 = (float) b.maxZ;
        float[] corners = {
                x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0,
                x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1
        };
        for (int i = 0; i < EDGES.length; i += 2) {
            int i0 = EDGES[i] * 3;
            int i1 = EDGES[i + 1] * 3;
            segment(pose, buffer, corners[i0], corners[i0 + 1], corners[i0 + 2],
                    corners[i1], corners[i1 + 1], corners[i1 + 2], width);
        }
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
