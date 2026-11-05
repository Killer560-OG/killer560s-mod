package com.killer560.hub.util;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Test hook (docs/TESTING-HOOKS.md): draws one green test shape through one of the shared highlight helper families, from
 * COLLECT_SUBMITS, so a pixel test can put water behind / around / in front of it (testkit 1103-esp-depth-*). Null does
 * nothing. Families: thru-fill, depth-fill, block-fill (BoxBatch, the mining highlights' path), waypoint-fill (Secret
 * Waypoints' own types; waypoint-depth its depth-tested one), thru-outline, depth-outline, thru-line, depth-line.
 */
public final class EspDepthTestHook {

    private EspDepthTestHook() {
    }

    private static volatile String family = null;
    private static volatile AABB box = null;
    private static final BoxBatch BATCH = new BoxBatch();

    public static void setForced(String fam, AABB b) {
        box = b;
        family = fam;
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(EspDepthTestHook::draw);
    }

    private static void draw(LevelRenderContext ctx) {
        String f = family;
        AABB b = box;
        if (f == null || b == null) {
            return;
        }
        float[] rgba = {0f, 1f, 0f, 0.5f};
        switch (f) {
            case "thru-fill" -> WorldRenderUtils.renderFilledBoxes(ctx, new AABB[]{b}, rgba, 1, 1f, true);
            case "depth-fill" -> WorldRenderUtils.renderFilledBoxes(ctx, new AABB[]{b}, rgba, 1, 1f, false);
            case "block-fill" -> {
                BATCH.clear();
                BATCH.add(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, 0f, 1f, 0f, 0.5f);
                BATCH.drawFilled(ctx, EspTypes.FILLED);
            }
            case "waypoint-fill", "waypoint-depth" ->
                    com.killer560.hub.secretwaypoints.SecretWaypointsRenderer.drawForTest(ctx, b, f.equals("waypoint-fill"));
            case "thru-outline" -> WorldRenderUtils.renderOutlineBoxes(ctx, new AABB[]{b}, new float[]{0f, 1f, 0f, 1f}, 1, 8f, true);
            case "depth-outline" -> WorldRenderUtils.renderOutlineBoxes(ctx, new AABB[]{b}, new float[]{0f, 1f, 0f, 1f}, 1, 8f, false);
            case "thru-line", "depth-line" -> {
                Vec3 c = b.getCenter();
                WorldRenderUtils.renderLine(ctx, new Vec3(c.x, b.minY, c.z), new Vec3(c.x, b.maxY, c.z), 0f, 1f, 0f, 1f, 10f,
                        f.startsWith("thru"));
            }
            default -> {
            }
        }
    }
}
