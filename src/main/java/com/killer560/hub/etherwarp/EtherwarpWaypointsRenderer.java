package com.killer560.hub.etherwarp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import com.killer560.hub.compat.McRender;

/**
 * Box drawing for Etherwarp Waypoints - killer560, 2026-09-27: "Treat them essentially as secret waypoints
 * with how that works," so this is a deliberately small copy of
 * {@code com.killer560.hub.secretwaypoints.SecretWaypointsRenderer}: same no-depth "through walls" pipeline
 * construction (its own pipeline ids so they can't collide with that package's, or with P3 Nav's/Dungeon
 * ESP's), same fill/outline vertex layout, and the same "one getBuffer + one full pass PER render type,
 * never interleaved per box" rule that renderer's crash note explains (a level {@code BufferSource} only has
 * a few fixed buffers - the fill and outline types here are not among them, so both share its one SHARED
 * buffer, and alternating types mid-loop ends one builder out from under the other).
 * <p>
 * Left out on purpose: that renderer's scratch-array distance-cull batching. It exists there because a
 * dungeon floor's secret waypoints can be well over a hundred boxes a frame; an etherwarp waypoint list is
 * whatever killer560 personally placed in the one room he's standing in - a handful at most - so a plain
 * per-box loop costs nothing worth optimizing away.
 */
public final class EtherwarpWaypointsRenderer {

    private static final Logger LOGGER = ModLog.get("killer560smod-etherwarp");

    private EtherwarpWaypointsRenderer() {
    }

    /** One box to draw - built by {@link EtherwarpFeature} for the waypoints of the room you're in. */
    public record Entry(AABB box, float r, float g, float b, float a) {
    }

    /** Called from {@link EtherwarpFeature#register()} so the pipelines exist before the renderer's first draw. */
    static void init() {
        RenderType unused = ThroughWalls.LINES;
    }

    /** Holder idiom - built once, on {@link #init()}. */
    private static final class ThroughWalls {
        static final RenderType LINES = com.killer560.hub.util.EspTypes.LINES_THRU;

        static final RenderType FILLED = com.killer560.hub.util.EspTypes.FILLED_THRU;
    }

    public static void draw(LevelRenderContext context, List<Entry> entries, EtherwarpWaypointsConfig.Style style,
                     boolean throughWalls) {
        if (entries.isEmpty()) {
            return;
        }
        boolean fill = style != EtherwarpWaypointsConfig.Style.OUTLINE;
        boolean outline = style != EtherwarpWaypointsConfig.Style.FILLED;
        float fillAlphaScale = style == EtherwarpWaypointsConfig.Style.FILL_AND_OUTLINE ? 0.5f : 1.0f;

        try {
            // Two flat passes, never interleaved - see this class's doc for why. Two separate inCameraSpace
            // calls keep that: one render type each, one uninterrupted pass each.
            if (fill) {
                McRender.inCameraSpace(context,
                        throughWalls ? ThroughWalls.FILLED : com.killer560.hub.util.EspTypes.FILLED,
                        (pose, fillBuffer) -> {
                            for (Entry e : entries) {
                                com.killer560.hub.util.BoxVerts.filledBox(pose.pose(), fillBuffer, e.box(), e.r(), e.g(), e.b(),
                                        e.a() * fillAlphaScale);
                            }
                        });
            }
            if (outline) {
                McRender.inCameraSpace(context,
                        throughWalls ? ThroughWalls.LINES : com.killer560.hub.util.EspTypes.LINES,
                        (pose, lineBuffer) -> {
                            for (Entry e : entries) {
                                com.killer560.hub.util.BoxVerts.lineBox(pose, lineBuffer, e.box(), e.r(), e.g(), e.b(), 1f, 2f);
                            }
                        });
            }
        } catch (Throwable t) {
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("[Etherwarp] Box rendering failed - waypoints will not be drawn this frame", t);
            }
        }
    }

    private static boolean renderFailureLogged = false;
}
