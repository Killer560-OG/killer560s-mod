package com.killer560.hub.storagesearch;

import static com.killer560.hub.util.BoxVerts.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McRender;

/**
 * World box around the chest a search result lives in - killer560 (2026-09-21): "if the item is in a chest it will
 * esp the chest and highlight the item inside of it". The highlight-inside half is the slot outline
 * {@link StorageSearchFeature} arms; this is the "which chest, over there" half.
 * <p>
 * Through-walls pipelines are built exactly the way {@code secretwaypoints/SecretWaypointsRenderer} builds its own
 * (vanilla's LINES / DEBUG_FILLED snippets with the depth state dropped), with storagesearch-specific pipeline ids
 * so they cannot collide with that feature's or Dungeon ESP's registrations. The whole thing early-returns on an
 * empty target list, so with nothing marked it costs one field read per frame.
 */
public final class StorageSearchEsp {

    /** At most this many chests are marked at once - clicking a new result replaces the oldest. */
    private static final int MAX_TARGETS = 8;
    private static final int COLOR_RGB = 0xCC6600;

    private record Target(BlockPos pos, long expiresAt) {
    }

    private static final List<Target> TARGETS = new ArrayList<>();

    private StorageSearchEsp() {
    }

    static void register() {
        RenderType unused = com.killer560.hub.util.EspTypes.LINES_THRU;
        LevelRenderEvents.COLLECT_SUBMITS.register(StorageSearchEsp::onWorldRender);
    }

    /** Marks a chest for {@code durationMs}. Re-marking the same chest just refreshes its timer. */
    public static void mark(BlockPos pos, long durationMs) {
        if (pos == null) return;
        long expiry = System.currentTimeMillis() + durationMs;
        TARGETS.removeIf(t -> t.pos().equals(pos));
        TARGETS.add(new Target(pos.immutable(), expiry));
        while (TARGETS.size() > MAX_TARGETS) {
            TARGETS.remove(0);
        }
    }

    public static void clear() { TARGETS.clear(); }

    private static void onWorldRender(LevelRenderContext context) {
        // Nothing marked is the normal case - this must cost effectively nothing then.
        if (TARGETS.isEmpty()) return;
        long now = System.currentTimeMillis();
        TARGETS.removeIf(t -> t.expiresAt() <= now);
        if (TARGETS.isEmpty()) return;
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isSearchChests() || !cfg.isChestEsp()) {
            TARGETS.clear();
            return;
        }
        boolean throughWalls = cfg.isEspThroughWalls();
        float r = ((COLOR_RGB >> 16) & 0xFF) / 255f;
        float g = ((COLOR_RGB >> 8) & 0xFF) / 255f;
        float b = (COLOR_RGB & 0xFF) / 255f;

        // One full pass PER RENDER TYPE, never interleaved per box - see the "Not building!" crash note in
        // SecretWaypointsRenderer for why alternating types inside the loop is not safe here. Two separate
        // inCameraSpace calls keep that property: each one is a single type and a single pass.
        // Boxes built now, from a snapshot: on 26.2 the callbacks run later in the frame, after a click may have
        // called mark()/clear() on TARGETS (ConcurrentModificationException while iterating it).
        final AABB[] snap = new AABB[TARGETS.size()];
        for (int i = 0; i < snap.length; i++) {
            snap[i] = boxFor(TARGETS.get(i).pos());
        }
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.fill(throughWalls),
                (pose, fillBuffer) -> {
                    for (AABB box : snap) {
                        filledBox(pose.pose(), fillBuffer, box, r, g, b, 0.25f);
                    }
                });
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.lines(throughWalls),
                (pose, lineBuffer) -> {
                    for (AABB box : snap) {
                        lineBox(pose, lineBuffer, box, r, g, b, 1f, 2.5f);
                    }
                });
    }

    /** A chest model is inset from its block and only 14/16 tall - a full block box floats visibly above it. */
    private static AABB boxFor(BlockPos pos) {
        return new AABB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625,
                pos.getX() + 0.9375, pos.getY() + 0.875, pos.getZ() + 0.9375);
    }

}
