package com.killer560.hub.chocolatefactory.egg;

import com.killer560.hub.compat.McRender;
import com.killer560.hub.util.SkyblockGate;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.Optional;

/**
 * Draws the Egglocator's guess on both builds, as SkyHanni's drawGuessLocations does: a filled waypoint on the egg's block,
 * seen through blocks, a "Guess" label, and a green line from the crosshair to the block's center (killer560, 2026-10-19:
 * "tracer lines should be drawn to my crosshair always").
 * <p>
 * The crosshair point is the camera plus 2 blocks along its forward vector, SkyHanni's exactPlayerCrosshairLocation; the
 * camera is this frame's interpolated one, so the line does not judder between ticks the way the player's tick look did.
 * <p>
 * Far eggs (killer560: "should even if the egg is out of render distance"): the line shader applies fog
 * (26.1.2's rendertype_lines.fsh calls apply_fog with the render-distance fog), so a line end or an
 * outline past the fog turns the fog's color and vanishes, and anything past the far plane (4 x render distance, javap
 * Camera.update) is clipped. So when the egg is further than {@link #FOG_SAFE} of the render distance the marker is drawn at
 * that distance along the true direction, larger, and the line ends there; it points exactly at the egg either way.
 */
final class LocatorGuessRenderer {

    /** Fraction of the render distance (blocks) inside which nothing is fogged; past it the marker is pulled in. */
    private static final double FOG_SAFE = 0.6;
    private static final double MIN_SAFE = 24.0;
    /** Crosshair point: this far along the camera's forward vector (SkyHanni: 2). */
    private static final double CROSSHAIR = 2.0;

    private LocatorGuessRenderer() {
    }

    /** Builds the render types now, on both builds, rather than in the middle of the first frame that needs them. */
    static void register() {
        RenderType unused = Types.LINES;
        RenderType unused2 = Types.FILLED;
    }

    static void draw(LevelRenderContext context, Vec3 spot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.options == null || !SkyblockGate.allows()) {
            return;
        }
        // Table points are block corners; a learned spot is a head point. Either way the egg is in this block.
        double bx = Math.floor(spot.x + 1e-4), by = Math.floor(spot.y + 1e-4), bz = Math.floor(spot.z + 1e-4);
        Vec3 centre = new Vec3(bx + 0.5, by + 0.5, bz + 0.5);
        Vec3 cam = McRender.cameraPos(context);
        Vector3fc f = McRender.cameraForward(context);
        Vec3 start = cam.add(f.x() * CROSSHAIR, f.y() * CROSSHAIR, f.z() * CROSSHAIR);

        double dist = centre.distanceTo(cam);
        double safe = Math.max(MIN_SAFE, client.options.getEffectiveRenderDistance() * 16.0 * FOG_SAFE);
        final Vec3 mark;
        final double half;
        if (dist <= safe) {
            mark = centre;
            half = 0.5;
        } else {
            mark = cam.add(centre.subtract(cam).scale(safe / dist));
            // Big enough to see at that distance, still small next to the line.
            half = Math.max(0.5, safe / 48.0);
        }
        AABB box = new AABB(mark.x - half, mark.y - half, mark.z - half, mark.x + half, mark.y + half, mark.z + half);
        float dx = (float) (mark.x - start.x), dy = (float) (mark.y - start.y), dz = (float) (mark.z - start.z);

        McRender.inCameraSpace(context, Types.FILLED, (pose, buffer) ->
                EggEsp.filledBox(pose.pose(), buffer, box, 1f, 0.85f, 0.2f, 0.35f));
        // Egglocator Tracer Line (killer560, 2026-10-19: "make a specific setting for toggling the line to hoppity eggs"):
        // only the line from the crosshair; the box and label still mark the guess.
        boolean tracer = com.killer560.hub.chocolatefactory.ChocolateFactoryConfig.getInstance().isEggLocatorLine();
        McRender.inCameraSpace(context, Types.LINES, (pose, buffer) -> {
            EggEsp.lineBox(pose, buffer, box, 1f, 0.85f, 0.2f, 1f, 2.5f);
            if (!tracer) {
                return;
            }
            buffer.addVertex(pose, (float) start.x, (float) start.y, (float) start.z)
                    .setColor(0.33f, 1f, 0.33f, 1f).setNormal(pose, dx, dy, dz).setLineWidth(2f);
            buffer.addVertex(pose, (float) mark.x, (float) mark.y, (float) mark.z)
                    .setColor(0.33f, 1f, 0.33f, 1f).setNormal(pose, dx, dy, dz).setLineWidth(2f);
        });
        label(context, client, cam, mark.x, mark.y + half + 0.6, mark.z, "§aGuess §7(" + Math.round(dist) + "m)");
    }

    /** Camera-facing text, the shape PosmsgRenderer.renderLabel uses. */
    private static void label(LevelRenderContext context, Minecraft client, Vec3 cam, double x, double y, double z, String text) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        Font font = client.font;
        double d = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * (float) Math.min(8.0, Math.max(1.0, d / 12.0));
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(McRender.cameraRotation(context));
            poseStack.scale(s, -s, s);
            McRender.drawText(context, font, text, -font.width(text) / 2f, -font.lineHeight / 2f, 0xFFFFFFFF, false,
                    poseStack, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
    }

    /** Holder idiom: built on {@link #register()}, both builds. The guess is shown through walls, as SkyHanni does. */
    private static final class Types {
        static final RenderType LINES = RenderType.create("killer560smod_egglocator_lines",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/egglocator_lines"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).createRenderSetup());

        static final RenderType FILLED = RenderType.create("killer560smod_egglocator_filled",
                RenderSetup.builder(RenderPipelines.register(RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                        .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/egglocator_filled"))
                        .withCull(false)
                        .withDepthStencilState(Optional.<DepthStencilState>empty())
                        .build())).sortOnUpload().createRenderSetup());
    }
}
