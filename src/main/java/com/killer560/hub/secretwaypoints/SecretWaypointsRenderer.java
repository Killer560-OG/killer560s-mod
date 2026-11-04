package com.killer560.hub.secretwaypoints;

import static com.killer560.hub.util.BoxVerts.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.List;
import java.util.Optional;
import com.killer560.hub.compat.McRender;

/**
 * Box drawing for Secret Waypoints. killer560 (change 62): "draw them through walls" - a waypoint for a secret
 * you have not found yet is by definition behind something, so the depth-tested boxes
 * {@code WorldRenderUtils} draws were invisible exactly when they mattered.
 * <p>
 * The no-depth pipelines are built the same way {@code mobesp/EspRenderer} and {@code p3nav/P3NavRenderer}
 * build theirs - copies of vanilla's LINES / DEBUG_FILLED snippets with
 * {@code withDepthStencilState(Optional.empty())}, which is how NoammAddons' 26.1.2
 * {@code NoammRenderPipelines} does it. The pipeline ids are secretwaypoints-specific so they cannot collide
 * with Dungeon ESP's or P3 Nav's registrations. Vertex layout is the same Odin-derived one the rest of the
 * repo's box renderers use.
 * <p>
 * Everything is drawn in one camera-relative {@code pushPose}/{@code popPose} for the whole batch rather than
 * one per box (2026-09-20 FPS pass): with ~150 waypoints on screen that was 300 matrix pushes and 300
 * {@code getMainCamera()} lookups per frame. Unlike those two renderers, which fetch a fresh
 * {@code VertexConsumer} for every single box, this one fetches one per render type and then fills it in a
 * single uninterrupted pass - see the crash note in {@link #draw} for why that distinction matters.
 */
public final class SecretWaypointsRenderer {

    private static final Logger LOGGER = ModLog.get("killer560smod-secretwaypoints");

    private SecretWaypointsRenderer() {
    }

    /** Called from {@link SecretWaypointsFeature#register()} so the pipelines exist before the renderer
     *  precompiles its pipeline list. */
    static void init() { RenderType unused = com.killer560.hub.util.EspTypes.LINES_THRU; }

    /**
     * Draws every waypoint in {@code waypoints} whose center is within {@code maxDistance} blocks of the camera.
     *
     * @return how many boxes were actually drawn (the rest were distance-culled).
     */
    static int draw(LevelRenderContext context, List<SecretWaypointsFeature.Waypoint> waypoints,
                    SecretWaypointsConfig.Style style, boolean throughWalls, double maxDistance) {
        if (waypoints.isEmpty()) return 0;
        boolean fill = style != SecretWaypointsConfig.Style.OUTLINE;
        boolean outline = style != SecretWaypointsConfig.Style.FILL;
        float fillAlphaScale = style == SecretWaypointsConfig.Style.FILL_OUTLINE ? 0.5f : 1.0f;

        Vec3 cam = McRender.cameraPos(context);
        double maxSq = maxDistance * maxDistance;

        // Distance cull once, into a reusable scratch array - the two passes below must walk exactly the
        // same set, and re-running the test per pass would give back the work the batching buys.
        if (visible.length < waypoints.size()) {
            visible = new int[waypoints.size()];
        }
        int[] vis = visible;
        int drawn = 0;
        for (int i = 0; i < waypoints.size(); i++) {
            SecretWaypointsFeature.Waypoint wp = waypoints.get(i);
            double dx = wp.centerX() - cam.x;
            double dy = wp.centerY() - cam.y;
            double dz = wp.centerZ() - cam.z;
            if (dx * dx + dy * dy + dz * dz <= maxSq) {
                vis[drawn++] = i;
            }
        }
        // Per frame: logged only when the draw decision changes (style, through-walls, list size, or none/some visible).
        if (style != lastStyle || throughWalls != lastThroughWalls || waypoints.size() != lastSize
                || (drawn == 0) != lastNone) {
            lastStyle = style;
            lastThroughWalls = throughWalls;
            lastSize = waypoints.size();
            lastNone = drawn == 0;
            LOGGER.info("[SecretWaypoints] drawing: waypoints={} visible={} style={} throughWalls={} maxDistance={}{}",
                    waypoints.size(), drawn, style, throughWalls, maxDistance,
                    drawn == 0 ? " - every waypoint is beyond the render distance" : "");
        }
        if (drawn == 0) return 0;

        // Captured for the lambdas below: `drawn` is reassigned in the catch, so it is not effectively final
        // and cannot be captured directly.
        final int count = drawn;
        // A COPY of what passed the cull, made now, for the lambdas below.
        //
        // On 26.2 inCameraSpace does not run its callback here: it is queued as a custom feature and built later
        // in the frame (CustomFeatureRenderer.buildGroup, from FeatureRenderDispatcher.prepareFrame). The tick
        // rebuilds the live waypoint list in between, so reading that list (and the shared `visible` scratch) from
        // the callback read a list that had already changed size - "Index 6 out of bounds for length 6", a crash,
        // the moment Show Crypts was switched off in scenario 98 on 26.2 (2026-10-05). Collecting a secret shrinks
        // the list the same way, so this was waiting for him on 26.2 regardless of crypts.
        final SecretWaypointsFeature.Waypoint[] snap = new SecretWaypointsFeature.Waypoint[count];
        for (int i = 0; i < count; i++) {
            snap[i] = waypoints.get(vis[i]);
        }
        try {
            // One full pass PER RENDER TYPE, never interleaved per box. Crash fixed
            // 2026-09-20 (killer560's log, "java.lang.IllegalStateException: Not building!"): neither of
            // these two types is one of the level BufferSource's fixed buffers (javap-confirmed: only the
            // glint/waterMask types are), so both draw out of its single SHARED buffer, and
            // MultiBufferSource$BufferSource#getBuffer ends - i.e. build()s - whatever shared type was
            // started before handing out a different one. The FPS pass had hoisted both getBuffer calls
            // out of the loop but kept the loop alternating fill/outline per box, so box 1's outline
            // ended the fill builder and box 2's fill then wrote into an already-built BufferBuilder.
            // Two flat passes keep the hoist (2 draw calls for the whole batch instead of 2 per box)
            // while making a type switch mid-pass impossible.
            if (fill) {
                McRender.inCameraSpace(context,
                        com.killer560.hub.util.EspTypes.fill(throughWalls),
                        (pose, fillBuffer) -> {
                            for (SecretWaypointsFeature.Waypoint wp : snap) {
                                filledBox(pose.pose(), fillBuffer, wp.box(), wp.r(), wp.g(), wp.b(),
                                        wp.a() * fillAlphaScale);
                            }
                        });
            }
            if (outline) {
                McRender.inCameraSpace(context,
                        com.killer560.hub.util.EspTypes.lines(throughWalls),
                        (pose, lineBuffer) -> {
                            for (SecretWaypointsFeature.Waypoint wp : snap) {
                                lineBox(pose, lineBuffer, wp.box(), wp.r(), wp.g(), wp.b(), 1f, 2f);
                            }
                        });
            }
        } catch (Throwable t) {
            // Never let a draw failure escape into LevelRenderer: the frame graph pass that calls this
            // event also owns the bufferSource.endBatch() that closes every builder started above, so an
            // exception thrown through it leaves a half-filled builder alive into the NEXT frame - which
            // is how one bad frame used to become a crash instead of a dropped frame of waypoints.
            if (!renderFailureLogged) {
                renderFailureLogged = true;
                LOGGER.error("[SecretWaypoints] Box rendering failed - waypoints will not be drawn this frame", t);
            }
            drawn = 0;
        }
        return drawn;
    }

    /** Test hook (util/EspDepthTestHook): one through-walls fill drawn exactly as a waypoint fill is. */
    public static void drawForTest(LevelRenderContext context, net.minecraft.world.phys.AABB box) {
        McRender.inCameraSpace(context, com.killer560.hub.util.EspTypes.FILLED_THRU, (pose, buffer) -> filledBox(pose.pose(), buffer, box, 0f, 1f, 0f, 0.5f));
    }

    /** Indices of the waypoints that passed this frame's distance cull. Render thread only; reused
     *  rather than allocated per frame (see the FPS note in the class doc). */
    private static int[] visible = new int[256];

    /** One report per session - a broken frame repeats at the frame rate, and the stack is identical. */
    private static boolean renderFailureLogged = false;

    /** The draw decision last logged (logging only). */
    private static SecretWaypointsConfig.Style lastStyle;
    private static boolean lastThroughWalls;
    private static int lastSize = -1;
    private static boolean lastNone;

}
