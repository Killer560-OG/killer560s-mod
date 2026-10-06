package com.killer560.hub.autoroutes;

import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McRender;

/**
 * Node markers for the current room's route (QUOI Box / Filled box / Cylinder styles, per-type or uniform colours,
 * thickness + height sliders, the active node in its own colour), the chain line between consecutive nodes, each
 * node's 1-based number (AP3's numbering, behind Show Node Numbers), and in edit mode: the recorded path as a faint line, and a faint highlight on every block any dungeon
 * breaker node will break (air = red outline, present = white fill - QUOI's DB editor). Nothing is drawn while the
 * Interactive Map is open (the feature decides that; see {@link AutoRoutesFeature#isRenderHidden}).
 * <p>
 * Everything is depth-tested against the world like Secret Waypoints, and drawn through {@link WorldRenderUtils}
 * only. Any exception is caught by the feature's render hook, which disables the feature instead of taking the
 * frame down.
 */
public final class AutoRoutesRenderer {

    private static final int RING_SEGMENTS = 40;
    private static final double GROUND_OFFSET = 0.03;
    private static final double LABEL_DISTANCE = 40.0;
    private static final float PATH_ALPHA = 0.35f;
    /** AP3's label stacking: each later node of a tile's stack ({@link RouteNode#sameTile}) lifts its label this much. */
    private static final double STACK_STEP = 0.3;

    private AutoRoutesRenderer() {
    }

    static void render(LevelRenderContext ctx, Route route, RouteCoords.Frame frame, boolean editMode,
                       RouteNode activeNode) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || route == null || frame == null) {
            return;
        }
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        float thickness = cfg.getThickness();
        double height = cfg.getHeight();
        Vec3 playerPos = client.player.position();

        List<Vec3> chain = new ArrayList<>();
        List<RouteNode> ordered = route.nodesInPathOrder();
        for (RouteNode node : ordered) {
            Vec3 real = RouteCoords.toReal(frame, node.relativePos());
            chain.add(real.add(0, height / 2.0, 0));
            int argb = node == activeNode ? cfg.getActiveColorArgb() : cfg.colorFor(node);
            float[] c = WorldRenderUtils.argbToFloats(argb);
            float alpha = c.length > 3 ? c[3] : 1f;
            if (alpha <= 0.01f) {
                alpha = 1f;
            }
            AABB box = node.boundingBox(real, height);
            AutoRoutesConfig.RenderStyle style = cfg.getRenderStyle();
            double ringRadius = Math.max(0.1, node.radius) / 2.0;
            switch (style) {
                case FILLED -> {
                    // killer560, 2026-10-04: "shift all node waypoints down by one so they are actually inside of the
                    // block that I placed it on top of ... only for the filled setting". Drawn only - the trigger box
                    // (node.contains) and everything else about the node stay where they were.
                    box = filledDisplayBox(box);
                    WorldRenderUtils.renderFilledBox(ctx, box, c[0], c[1], c[2], alpha * 0.35f);
                    WorldRenderUtils.renderOutlineBox(ctx, box, c[0], c[1], c[2], alpha, thickness);
                }
                case CYLINDER -> {
                    WorldRenderUtils.renderLineStrip(ctx, ring(real, ringRadius, GROUND_OFFSET), c[0], c[1], c[2], alpha, thickness);
                    if (height > 0.15) {
                        WorldRenderUtils.renderLineStrip(ctx, ring(real, ringRadius, height), c[0], c[1], c[2], alpha * 0.6f,
                                Math.max(0.5f, thickness * 0.6f));
                    }
                }
                default -> WorldRenderUtils.renderOutlineBox(ctx, box, c[0], c[1], c[2], alpha, thickness);
            }
            if (node.start) {
                // killer560, 2026-10-04: "if something is a start node the outline is filled in so I know it is
                // special". Filled in every style; in Filled style, where every node already is, it is filled
                // twice as solid so it still stands out.
                if (style == AutoRoutesConfig.RenderStyle.CYLINDER) {
                    // The cylinder's own shape, between its two rings. A box fill put its corners outside the circle
                    // (killer560: "make sure the fill is only inside the cylinder").
                    WorldRenderUtils.renderFilledCylinder(ctx, real, ringRadius, real.y + GROUND_OFFSET,
                            real.y + Math.max(GROUND_OFFSET, height), RING_SEGMENTS, c[0], c[1], c[2], alpha * 0.45f);
                } else {
                    float fill = style == AutoRoutesConfig.RenderStyle.FILLED ? 0.7f : 0.45f;
                    WorldRenderUtils.renderFilledBox(ctx, box, c[0], c[1], c[2], alpha * fill);
                }
            }
            if ((editMode || cfg.isShowNodeNumbers()) && playerPos.distanceTo(real) <= LABEL_DISTANCE) {
                // AP3's numbering (killer560, 2026-10-04: "the same node numbering system per room"): 1-based
                // position in the room's own list, the same number /ar list prints and /ar delete|remove take, so
                // deleting #2 makes the old #3 read #2. Shown always while Show Node Numbers is on, as AP3's are;
                // edit mode shows the label even with it off. node.modifierTag() appends " [start]" /
                // " [await ...]" - the same tag /ar list shows.
                int index = route.indexOf(node) + 1;
                String text = (cfg.isShowNodeNumbers() ? "#" + index + " " : "") + node.type.label() + node.modifierTag();
                // Nodes on the same tile lift their labels a step each, AP3's stacking rule, so every number reads;
                // the lowest label fires first.
                double lift = stackIndex(route, node) * STACK_STEP;
                renderLabel(ctx, real.x, real.y + height + 0.35 + lift, real.z, text, argb | 0xFF000000);
            }
        }
        if (chain.size() >= 2) {
            float[] a = WorldRenderUtils.argbToFloats(cfg.getActiveColorArgb());
            WorldRenderUtils.renderLineStrip(ctx, chain, a[0], a[1], a[2], 0.4f, Math.max(0.5f, thickness / 2f));
        }

        if (editMode) {
            renderPath(ctx, route, frame, thickness);
            renderBreakerBlocks(ctx, client, route, frame);
        }
    }

    /** Lifts a face this far off any block face it lies on, so the fill draws in front of the block instead of
     *  z-fighting with it. */
    private static final double FACE_EPSILON = 0.002;

    /** Filled style's drawn box: the whole block the node was placed on top of (full height, the node's width),
     *  pushed out by {@link #FACE_EPSILON} on every side. Moving the node's own box down a block left a 0.1-high
     *  slab buried at the bottom of that block at the default height - invisible on a normal floor. */
    private static AABB filledDisplayBox(AABB box) {
        return new AABB(box.minX, box.minY - 1.0, box.minZ, box.maxX, box.minY, box.maxZ).inflate(FACE_EPSILON);
    }

    /** This node's place in its tile's stack - AP3's {@code Ap3Renderer.stackIndex} lift, but counted in FIRING
     *  order ({@link Route#stackOf}, the same tile test playback uses), so the labels read bottom-up in the order
     *  the stack goes off. A lone node is 0. */
    private static int stackIndex(Route route, RouteNode me) {
        return Math.max(0, route.stackOf(me).indexOf(me));
    }

    /** The recorded movement as a faint polyline - where playback will actually walk. */
    private static void renderPath(LevelRenderContext ctx, Route route, RouteCoords.Frame frame, float thickness) {
        RoutePath path = route.path();
        if (path.size() < 2) {
            return;
        }
        List<Vec3> points = new ArrayList<>();
        int stride = Math.max(1, path.size() / 400); // a 10-minute path still draws as ~400 segments
        for (int i = 0; i < path.size(); i += stride) {
            RoutePath.Sample s = path.get(i);
            Vec3 real = RouteCoords.toReal(frame, s.x(), s.y(), s.z());
            points.add(real.add(0, 0.05, 0));
        }
        RoutePath.Sample last = path.last();
        if (last != null) {
            points.add(RouteCoords.toReal(frame, last.x(), last.y(), last.z()).add(0, 0.05, 0));
        }
        WorldRenderUtils.renderLineStrip(ctx, points, 1f, 0.65f, 0.2f, PATH_ALPHA, Math.max(0.5f, thickness / 2f));
    }

    /** Which path the last edit-mode breaker draw took ("highlight/FILLED_OUTLINE 3 drawn"), for the testkit's 96-ar. */
    private static volatile String lastBreakerDraw = "";

    public static String lastBreakerDraw() {
        return lastBreakerDraw;
    }

    /**
     * killer560: "blocks that will be broken by any node get a faint highlight while in edit mode". Air (already broken)
     * is red, a standing block white - QUOI's DB editor. How they are drawn is the Auto Routes tab's Breaker Block
     * Display and Breaker Block Style (2026-10-06), through Breaker Aura's own draw ({@code BreakerAuraFeature
     * .drawPickBoxes}): Highlight is depth-tested, Waypoint draws through walls and is culled to the render distance
     * (horizontal, from the camera) before any world lookup, as Breaker Aura's is.
     */
    private static void renderBreakerBlocks(LevelRenderContext ctx, Minecraft client, Route route, RouteCoords.Frame frame) {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        boolean waypoint = cfg.getBreakerDisplay()
                == com.killer560.hub.dungeonextras.DungeonExtrasConfig.BreakerDisplay.WAYPOINT;
        com.killer560.hub.dungeonextras.DungeonExtrasConfig.BreakerStyle style = cfg.getBreakerStyle();
        int total = 0;
        for (RouteNode node : route.nodes()) {
            if (node.type == RouteNode.Type.DUNGEON_BREAKER) {
                total += node.breakerBlocks.size();
            }
        }
        if (total == 0) {
            lastBreakerDraw = (waypoint ? "waypoint/" : "highlight/") + style.name() + " 0 drawn";
            return;
        }
        Vec3 cam = McRender.cameraPos(ctx);
        double maxH = client.options.getEffectiveRenderDistance() * 16.0;
        double maxHSq = maxH * maxH;
        // Fresh arrays every frame: on 26.2 the draw callbacks run later in the frame (WorldRenderUtils.renderOutlineBoxes).
        AABB[] boxes = new AABB[total];
        float[] rgba = new float[total * 4];
        int count = 0;
        for (RouteNode node : route.nodes()) {
            if (node.type != RouteNode.Type.DUNGEON_BREAKER) {
                continue;
            }
            for (BlockPos rel : node.breakerBlocks) {
                BlockPos real = RouteCoords.toRealBlock(frame, rel);
                if (waypoint) {
                    double dx = real.getX() + 0.5 - cam.x;
                    double dz = real.getZ() + 0.5 - cam.z;
                    if (dx * dx + dz * dz > maxHSq) {
                        continue;
                    }
                }
                boolean air = client.level.getBlockState(real).isAir();
                boxes[count] = new AABB(real.getX(), real.getY(), real.getZ(), real.getX() + 1, real.getY() + 1, real.getZ() + 1);
                int c = count * 4;
                rgba[c] = 1f;
                rgba[c + 1] = air ? 0.2f : 1f;
                rgba[c + 2] = air ? 0.2f : 1f;
                rgba[c + 3] = air ? 0.5f : 0.6f;
                count++;
            }
        }
        lastBreakerDraw = (waypoint ? "waypoint/" : "highlight/") + style.name() + " " + count + " drawn";
        com.killer560.hub.dungeonextras.BreakerAuraFeature.drawPickBoxes(ctx, boxes, rgba, count, waypoint, style);
    }

    private static List<Vec3> ring(Vec3 centre, double radius, double yOffset) {
        List<Vec3> points = new ArrayList<>(RING_SEGMENTS + 1);
        double y = centre.y + yOffset;
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = (Math.PI * 2 * i) / RING_SEGMENTS;
            points.add(new Vec3(centre.x + Math.cos(angle) * radius, y, centre.z + Math.sin(angle) * radius));
        }
        return points;
    }

    /** Camera-facing text at a world position - {@code posmsg/PosmsgRenderer.renderLabel}. */
    private static void renderLabel(LevelRenderContext ctx, double x, double y, double z, String text,
                                    int color) {
        PoseStack poseStack = ctx.poseStack();
        if (poseStack == null || text == null || text.isBlank()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = McRender.cameraPos(ctx);
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * (float) Math.min(8.0, Math.max(1.0, dist / 12.0));
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            poseStack.mulPose(McRender.cameraRotation(ctx));
            poseStack.scale(s, -s, s);
            McRender.drawText(ctx, font, text, -font.width(text) / 2f, -font.lineHeight / 2f, color, false,
                    poseStack, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
    }
}
