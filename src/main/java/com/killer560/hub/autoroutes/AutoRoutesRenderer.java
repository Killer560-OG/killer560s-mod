package com.killer560.hub.autoroutes;

import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.rendertype.RenderTypes;
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
 * Everything is depth-tested against the world like Secret Waypoints, and drawn through {@link WorldRenderUtils}'s
 * vertex layout only. Any exception is caught by the feature's render hook, which disables the feature instead of
 * taking the frame down.
 * <p>
 * <b>Cached geometry (ar-render-1016).</b> Until then every frame rebuilt every node from scratch: a sorted copy of
 * the node list, a Vec3, an AABB and a float[] per node, a 41-point Vec3 list per cylinder ring, the label string and
 * its tile stack (a list, sorted), and one camera-space call (a pose push and a lambda) per shape - 1-2 MB a second for
 * a 30-node room standing still. Now the vertices are built ONCE into flat arrays ({@link Mesh}) and replayed every
 * frame, in the same order and with the same float arithmetic, in one camera-space call per render type. The mesh is
 * rebuilt when its {@link Mesh#matches key} changes: the route, every node's drawn fields (identity, position, radius,
 * type, start/await modifiers, path index, colour) compared field by field each frame, the room transform and the sim's
 * height shift, and the draw settings. A field-by-field check rather than a version counter bumped by writers: nodes
 * are edited in place through public fields from some forty places (recorder, editor, undo, planner, commands, the
 * testkit), and one missed bump would leave a stale drawing on screen, where a compare of ~30 x 12 fields cannot miss
 * and costs about a microsecond. Colours live in a small {@link Mesh#palette}, rebuilt when the active node or the
 * active colour changes, so playback's highlight moves on the very frame the active node does. Nodes whose whole
 * drawing lies outside the camera's culling frustum (inflated by a margin wider than the thickest line) are skipped;
 * the chain and the recorded path are never culled, since a segment between two off-screen nodes can cross the view.
 */
public final class AutoRoutesRenderer {

    private static final int RING_SEGMENTS = 40;
    private static final double GROUND_OFFSET = 0.03;
    private static final double LABEL_DISTANCE = 40.0;
    private static final float PATH_ALPHA = 0.35f;
    /** AP3's label stacking: each later node of a tile's stack ({@link RouteNode#sameTile}) lifts its label this much. */
    private static final double STACK_STEP = 0.3;
    /**
     * How far a node's drawing is grown before the frustum test, in blocks. A line is drawn {@code thickness} pixels
     * wide (at most {@link AutoRoutesConfig#MAX_THICKNESS}, 8), so up to 4 px of it can sit outside the shape's own
     * box; one block is more than 4 px out to ~130 blocks even at 1080p, and the room's nodes are within ~90.
     */
    private static final double CULL_MARGIN = 1.0;
    /** The largest label scale {@link #renderLabel} uses (0.025 x 8), for the label's cull box. */
    private static final double MAX_LABEL_SCALE = 0.2;

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
        AutoRoutesConfig.RenderStyle style = cfg.getRenderStyle();
        boolean numbers = cfg.isShowNodeNumbers();
        int shift = com.killer560.hub.livemap.DungeonLayout.simYOffset();

        Mesh m = mesh;
        if (m == null || !m.matches(route, frame, shift, cfg, thickness, height, style, numbers)) {
            m = Mesh.build(route, frame, shift, cfg, thickness, height, style, numbers, client.font);
            mesh = m;
            meshBuilds++;
        }
        int activeArgb = cfg.getActiveColorArgb();
        m.updatePalette(activeNode, activeArgb);

        // ---- which nodes are on screen ----
        Frustum frustum = cullFrustum(ctx);
        int n = m.count;
        int drawn = 0;
        for (int i = 0; i < n; i++) {
            boolean vis = frustum == null || frustum.isVisible(m.bounds[i]);
            m.visible[i] = vis;
            if (vis) {
                drawn++;
            }
        }
        nodesDrawn = drawn;
        nodesCulled = n - drawn;
        totalDrawn += drawn;
        totalCulled += n - drawn;
        framesDrawn++;
        drawnActive = m.paletteActive;

        // Edit mode's recorded path is the same render type as the chain and was drawn right after it.
        float[] path = editMode ? pathSegments(route, frame, shift) : null;
        if (PER_SHAPE) {
            // 26.2: the shapes' own submission sequence, each a cached range (see PER_SHAPE).
            for (int k = 0; k < n; k++) {
                int i = m.order[k];
                if (!m.visible[i]) {
                    continue;
                }
                if (m.preFill[i] != null) {
                    McRender.inCameraSpace(ctx, RenderTypes.debugFilledBox(), m.preFill[i]);
                }
                if (m.lines[i] != null) {
                    McRender.inCameraSpace(ctx, RenderTypes.LINES_TRANSLUCENT, m.lines[i]);
                }
                if (m.postFill[i] != null) {
                    McRender.inCameraSpace(ctx, RenderTypes.debugFilledBox(), m.postFill[i]);
                }
            }
            if (m.chainEnd > m.chainStart || path != null) {
                LINES.mesh = m;
                LINES.nodes = false;
                LINES.path = path;
                LINES.pathWidth = Math.max(0.5f, thickness / 2f);
                McRender.inCameraSpace(ctx, RenderTypes.LINES_TRANSLUCENT, LINES);
            }
        } else {
            // 26.1.2: one camera-space pass per render type, in the order the per-shape calls used to fill each buffer.
            if (m.lineSegCount() > 0 || path != null) {
                LINES.mesh = m;
                LINES.nodes = true;
                LINES.path = path;
                LINES.pathWidth = Math.max(0.5f, thickness / 2f);
                McRender.inCameraSpace(ctx, RenderTypes.LINES_TRANSLUCENT, LINES);
            }
            if (m.fillVertCount() > 0) {
                FILLS.mesh = m;
                McRender.inCameraSpace(ctx, RenderTypes.debugFilledBox(), FILLS);
            }
        }

        // ---- labels, after the shapes as before (a different buffer source, so the order between them is moot) ----
        int labels = 0;
        if (editMode || numbers) {
            Vec3 playerPos = client.player.position();
            for (int k = 0; k < n; k++) {
                int i = m.order[k];
                double dx = m.realX[i] - playerPos.x;
                double dy = m.realY[i] - playerPos.y;
                double dz = m.realZ[i] - playerPos.z;
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > LABEL_DISTANCE) {
                    continue;
                }
                if (frustum != null && !frustum.isVisible(m.labelBounds[i])) {
                    continue;
                }
                renderLabel(ctx, m.realX[i], m.labelY[i], m.realZ[i], m.labels[i], m.argb[i] | 0xFF000000);
                labels++;
            }
        }
        labelsDrawn = labels;
        totalLabels += labels;

        if (editMode) {
            renderBreakerBlocks(ctx, client, route, frame, shift);
        }
    }

    /** The frustum vanilla culled this frame's entities and chunks with (world coordinates), or null. */
    private static Frustum cullFrustum(LevelRenderContext ctx) {
        try {
            var state = ctx.levelState();
            return state == null || state.cameraRenderState == null ? null : state.cameraRenderState.cullFrustum;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------- the cached mesh

    /** Render thread only. */
    private static Mesh mesh;
    private static long meshBuilds;
    private static long paletteBuilds;
    private static int nodesDrawn;
    private static int nodesCulled;
    private static int labelsDrawn;
    private static long pathBuilds;
    private static long totalDrawn;
    private static long totalCulled;
    private static long framesDrawn;
    private static long totalLabels;
    private static RouteNode drawnActive;

    /**
     * Testkit: {mesh builds, palette builds, path builds, frames drawn, nodes drawn, nodes culled} since launch, then
     * {nodes drawn, nodes culled, labels drawn} on the last frame, then labels drawn since launch.
     */
    public static long[] renderStats() {
        return new long[]{meshBuilds, paletteBuilds, pathBuilds, framesDrawn, totalDrawn, totalCulled,
                nodesDrawn, nodesCulled, labelsDrawn, totalLabels};
    }

    /** Testkit: the node the last drawn frame coloured as active (null for none). */
    public static Object drawnActive() {
        return drawnActive;
    }

    /** Palette slots per node: the colour at alpha, x0.6 (cylinder top ring), x0.35 (Filled fill), x0.45, x0.7. */
    private static final int SLOTS = 5;
    private static final int S_FULL = 0;
    private static final int S_RING_TOP = 1;
    private static final int S_FILL = 2;
    private static final int S_START45 = 3;
    private static final int S_START70 = 4;

    /**
     * Everything the node markers draw for one route in one room under one set of draw settings: line segments
     * {@code [sx, sy, sz, ex, ey, ez]} with a palette slot and a width each, and fill vertices {@code [x, y, z]} with a
     * slot each, per node in path order, then the chain. Immutable once built except {@link #palette}/{@link #argb}
     * (replaced, never written in place, so a 26.2 callback that runs later in the frame reads a whole palette) and
     * {@link #visible} (rewritten each frame before that frame's draw).
     */
    private static final class Mesh {
        // ---- key ----
        Route route;
        int count;
        RouteNode[] nodes;          // route list order
        double[] key;               // per node: x, y, z, radius
        int[] keyInts;              // per node: type, flags, awaitCondition, awaitAmount, pathIndex, colour
        String roomName;
        int clayX;
        int clayZ;
        int rotation;
        int shift;
        float thickness;
        double height;
        AutoRoutesConfig.RenderStyle style;
        boolean numbers;

        // ---- geometry, indexed by list position ----
        int[] order;                // list positions in path order
        int[] baseArgb;             // colorFor(node)
        double[] realX;
        double[] realY;
        double[] realZ;
        double[] labelY;
        String[] labels;
        AABB[] bounds;
        AABB[] labelBounds;
        boolean[] visible;
        int[] lineStart;            // segment range per list position
        int[] lineEnd;
        int[] fillStart;            // vertex range per list position
        int[] fillEnd;
        int[] fillMid;              // fills before the node's lines end here (Filled style's box), the start fill after
        RangeReplay[] preFill;      // PER_SHAPE submissions per list position, null where the node has none
        RangeReplay[] lines;
        RangeReplay[] postFill;
        int chainStart;
        int chainEnd;
        float[] seg;                // 6 floats per segment
        int[] segSlot;
        float[] segWidth;
        float[] fill;               // 3 floats per vertex
        int[] fillSlot;

        // ---- palette ----
        RouteNode paletteActive;
        int paletteActiveArgb;
        boolean paletteBuilt;
        float[] palette;            // 4 floats per slot
        int[] packed;               // the same colours as BufferBuilder.setColor(int) takes them, one per slot
        int[] argb;                 // per list position: the colour drawn (active or base), for the label

        int lineSegCount() {
            return seg.length / 6;
        }

        int fillVertCount() {
            return fill.length / 3;
        }

        boolean matches(Route r, RouteCoords.Frame f, int sh, AutoRoutesConfig cfg, float th, double h,
                        AutoRoutesConfig.RenderStyle st, boolean num) {
            if (r != route || sh != shift || th != thickness || h != height || st != style || num != numbers
                    || f.clayX() != clayX || f.clayZ() != clayZ || f.rotation() != rotation
                    || !f.roomName().equals(roomName)) {
                return false;
            }
            List<RouteNode> list = r.nodes();
            if (list.size() != count) {
                return false;
            }
            for (int i = 0; i < count; i++) {
                RouteNode node = list.get(i);
                if (node != nodes[i]) {
                    return false;
                }
                int d = i * 4;
                if (node.x != key[d] || node.y != key[d + 1] || node.z != key[d + 2] || node.radius != key[d + 3]) {
                    return false;
                }
                int k = i * 6;
                if (node.type.ordinal() != keyInts[k] || flags(node) != keyInts[k + 1]
                        || node.awaitCondition.ordinal() != keyInts[k + 2] || node.awaitAmount != keyInts[k + 3]
                        || node.pathIndex != keyInts[k + 4] || cfg.colorFor(node) != keyInts[k + 5]) {
                    return false;
                }
            }
            return true;
        }

        private static int flags(RouteNode n) {
            return (n.start ? 1 : 0) | (n.awaitEnabled ? 2 : 0);
        }

        /** The colours, when the active node or a colour changed. Replaces the arrays rather than writing them. */
        void updatePalette(RouteNode active, int activeArgb) {
            if (paletteBuilt && active == paletteActive && activeArgb == paletteActiveArgb) {
                return;
            }
            paletteBuilt = true;
            paletteActive = active;
            paletteActiveArgb = activeArgb;
            paletteBuilds++;
            float[] p = new float[(count * SLOTS + 1) * 4];
            int[] a = new int[count];
            for (int i = 0; i < count; i++) {
                int c = nodes[i] == active ? activeArgb : baseArgb[i];
                a[i] = c;
                float[] f = WorldRenderUtils.argbToFloats(c);
                float alpha = f.length > 3 ? f[3] : 1f;
                if (alpha <= 0.01f) {
                    alpha = 1f;
                }
                int s = i * SLOTS * 4;
                put(p, s + S_FULL * 4, f, alpha);
                put(p, s + S_RING_TOP * 4, f, alpha * 0.6f);
                put(p, s + S_FILL * 4, f, alpha * 0.35f);
                put(p, s + S_START45 * 4, f, alpha * 0.45f);
                put(p, s + S_START70 * 4, f, alpha * 0.7f);
            }
            float[] chain = WorldRenderUtils.argbToFloats(activeArgb);
            put(p, count * SLOTS * 4, chain, 0.4f);
            palette = p;
            packed = pack(p);
            argb = a;
        }

        private static void put(float[] p, int at, float[] rgb, float alpha) {
            p[at] = rgb[0];
            p[at + 1] = rgb[1];
            p[at + 2] = rgb[2];
            p[at + 3] = alpha;
        }

        int chainSlot() {
            return count * SLOTS;
        }

        static Mesh build(Route route, RouteCoords.Frame frame, int shift, AutoRoutesConfig cfg, float thickness,
                          double height, AutoRoutesConfig.RenderStyle style, boolean numbers, Font font) {
            Mesh m = new Mesh();
            List<RouteNode> list = route.nodes();
            int n = list.size();
            m.route = route;
            m.count = n;
            m.nodes = list.toArray(new RouteNode[0]);
            m.key = new double[n * 4];
            m.keyInts = new int[n * 6];
            m.roomName = frame.roomName();
            m.clayX = frame.clayX();
            m.clayZ = frame.clayZ();
            m.rotation = frame.rotation();
            m.shift = shift;
            m.thickness = thickness;
            m.height = height;
            m.style = style;
            m.numbers = numbers;
            m.baseArgb = new int[n];
            m.realX = new double[n];
            m.realY = new double[n];
            m.realZ = new double[n];
            m.labelY = new double[n];
            m.labels = new String[n];
            m.bounds = new AABB[n];
            m.labelBounds = new AABB[n];
            m.visible = new boolean[n];
            m.lineStart = new int[n];
            m.lineEnd = new int[n];
            m.fillStart = new int[n];
            m.fillEnd = new int[n];
            m.fillMid = new int[n];
            for (int i = 0; i < n; i++) {
                RouteNode node = m.nodes[i];
                int d = i * 4;
                m.key[d] = node.x;
                m.key[d + 1] = node.y;
                m.key[d + 2] = node.z;
                m.key[d + 3] = node.radius;
                int k = i * 6;
                m.keyInts[k] = node.type.ordinal();
                m.keyInts[k + 1] = flags(node);
                m.keyInts[k + 2] = node.awaitCondition.ordinal();
                m.keyInts[k + 3] = node.awaitAmount;
                m.keyInts[k + 4] = node.pathIndex;
                m.keyInts[k + 5] = cfg.colorFor(node);
                m.baseArgb[i] = m.keyInts[k + 5];
            }
            // Path order: by pathIndex, ties keeping list order (Route.nodesInPathOrder's stable sort).
            List<RouteNode> ordered = route.nodesInPathOrder();
            m.order = new int[n];
            for (int k = 0; k < n; k++) {
                m.order[k] = indexOfIdentity(m.nodes, ordered.get(k));
            }

            Floats seg = new Floats(256);
            Ints segSlot = new Ints(64);
            Floats segWidth = new Floats(64);
            Floats fill = new Floats(256);
            Ints fillSlot = new Ints(64);
            List<Vec3> chain = new ArrayList<>(n);
            float ringTopWidth = Math.max(0.5f, thickness * 0.6f);
            for (int k = 0; k < n; k++) {
                int i = m.order[k];
                RouteNode node = m.nodes[i];
                Vec3 real = RouteCoords.toReal(frame, node.relativePos());
                m.realX[i] = real.x;
                m.realY[i] = real.y;
                m.realZ[i] = real.z;
                chain.add(real.add(0, height / 2.0, 0));
                int base = i * SLOTS;
                AABB box = node.boundingBox(real, height);
                double ringRadius = Math.max(0.1, node.radius) / 2.0;
                m.lineStart[i] = segSlot.size;
                m.fillStart[i] = fillSlot.size;
                switch (style) {
                    case FILLED -> {
                        // killer560, 2026-10-04: "shift all node waypoints down by one so they are actually inside of the
                        // block that I placed it on top of ... only for the filled setting". Drawn only - the trigger box
                        // (node.contains) and everything else about the node stay where they were.
                        box = filledDisplayBox(box);
                        filledBox(fill, fillSlot, box, base + S_FILL);
                        outlineBox(seg, segSlot, segWidth, box, base + S_FULL, thickness);
                    }
                    case CYLINDER -> {
                        ring(seg, segSlot, segWidth, real, ringRadius, GROUND_OFFSET, base + S_FULL, thickness);
                        if (height > 0.15) {
                            ring(seg, segSlot, segWidth, real, ringRadius, height, base + S_RING_TOP, ringTopWidth);
                        }
                    }
                    default -> outlineBox(seg, segSlot, segWidth, box, base + S_FULL, thickness);
                }
                m.fillMid[i] = fillSlot.size;
                if (node.start) {
                    // killer560, 2026-10-04: "if something is a start node the outline is filled in so I know it is
                    // special". Filled in every style; in Filled style, where every node already is, it is filled
                    // twice as solid so it still stands out.
                    if (style == AutoRoutesConfig.RenderStyle.CYLINDER) {
                        // The cylinder's own shape, between its two rings. A box fill put its corners outside the circle
                        // (killer560: "make sure the fill is only inside the cylinder").
                        filledCylinder(fill, fillSlot, real, ringRadius, real.y + GROUND_OFFSET,
                                real.y + Math.max(GROUND_OFFSET, height), RING_SEGMENTS, base + S_START45);
                    } else {
                        filledBox(fill, fillSlot, box,
                                base + (style == AutoRoutesConfig.RenderStyle.FILLED ? S_START70 : S_START45));
                    }
                }
                m.lineEnd[i] = segSlot.size;
                m.fillEnd[i] = fillSlot.size;
                m.bounds[i] = bounds(seg, m.lineStart[i], m.lineEnd[i], fill, m.fillStart[i], m.fillEnd[i], real)
                        .inflate(CULL_MARGIN);

                // AP3's numbering (killer560, 2026-10-04: "the same node numbering system per room"): 1-based
                // position in the room's own list, the same number /ar list prints and /ar delete|remove take, so
                // deleting #2 makes the old #3 read #2. Shown always while Show Node Numbers is on, as AP3's are;
                // edit mode shows the label even with it off. node.modifierTag() appends " [start]" /
                // " [await ...]" - the same tag /ar list shows.
                String text = (numbers ? "#" + (i + 1) + " " : "") + node.type.label() + node.modifierTag();
                m.labels[i] = text;
                // Nodes on the same tile lift their labels a step each, AP3's stacking rule, so every number reads;
                // the lowest label fires first.
                double lift = stackIndex(route, node) * STACK_STEP;
                m.labelY[i] = real.y + height + 0.35 + lift;
                double half = Math.max(font.width(text), font.lineHeight) * MAX_LABEL_SCALE + CULL_MARGIN;
                m.labelBounds[i] = new AABB(real.x - half, m.labelY[i] - half, real.z - half,
                        real.x + half, m.labelY[i] + half, real.z + half);
            }
            m.chainStart = segSlot.size;
            if (chain.size() >= 2) {
                float w = Math.max(0.5f, thickness / 2f);
                strip(seg, segSlot, segWidth, chain, m.chainSlot(), w);
            }
            m.chainEnd = segSlot.size;
            m.seg = seg.toArray();
            m.segSlot = segSlot.toArray();
            m.segWidth = segWidth.toArray();
            m.fill = fill.toArray();
            m.fillSlot = fillSlot.toArray();
            m.preFill = new RangeReplay[n];
            m.lines = new RangeReplay[n];
            m.postFill = new RangeReplay[n];
            for (int i = 0; PER_SHAPE && i < n; i++) {
                if (m.fillMid[i] > m.fillStart[i]) {
                    m.preFill[i] = new RangeReplay(m, false, m.fillStart[i], m.fillMid[i]);
                }
                if (m.lineEnd[i] > m.lineStart[i]) {
                    m.lines[i] = new RangeReplay(m, true, m.lineStart[i], m.lineEnd[i]);
                }
                if (m.fillEnd[i] > m.fillMid[i]) {
                    m.postFill[i] = new RangeReplay(m, false, m.fillMid[i], m.fillEnd[i]);
                }
            }
            return m;
        }

        private static int indexOfIdentity(RouteNode[] nodes, RouteNode n) {
            for (int i = 0; i < nodes.length; i++) {
                if (nodes[i] == n) {
                    return i;
                }
            }
            throw new IllegalStateException("path order holds a node the list does not");
        }

        /** The box round everything one node draws (and its position, for a node that draws nothing). */
        private static AABB bounds(Floats seg, int s0, int s1, Floats fill, int f0, int f1, Vec3 real) {
            double minX = real.x, minY = real.y, minZ = real.z;
            double maxX = real.x, maxY = real.y, maxZ = real.z;
            float[] a = seg.data;
            for (int s = s0 * 6; s < s1 * 6; s += 3) {
                minX = Math.min(minX, a[s]);
                minY = Math.min(minY, a[s + 1]);
                minZ = Math.min(minZ, a[s + 2]);
                maxX = Math.max(maxX, a[s]);
                maxY = Math.max(maxY, a[s + 1]);
                maxZ = Math.max(maxZ, a[s + 2]);
            }
            float[] b = fill.data;
            for (int v = f0 * 3; v < f1 * 3; v += 3) {
                minX = Math.min(minX, b[v]);
                minY = Math.min(minY, b[v + 1]);
                minZ = Math.min(minZ, b[v + 2]);
                maxX = Math.max(maxX, b[v]);
                maxY = Math.max(maxY, b[v + 1]);
                maxZ = Math.max(maxZ, b[v + 2]);
            }
            return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    // ---- the vertex layouts, exactly as WorldRenderUtils writes them (same corners, order and float casts) ----

    private static final int[] EDGES = {
            0, 1, 1, 5, 5, 4, 4, 0,
            3, 2, 2, 6, 6, 7, 7, 3,
            0, 3, 1, 2, 5, 6, 4, 7
    };

    /** WorldRenderUtils.renderLineBox's twelve edges. */
    private static void outlineBox(Floats seg, Ints slot, Floats width, AABB box, int s, float w) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        for (int i = 0; i < EDGES.length; i += 2) {
            int k0 = EDGES[i];
            int k1 = EDGES[i + 1];
            float sx = cornerX(k0, x0, x1), sy = cornerY(k0, y0, y1), sz = k0 >= 4 ? z1 : z0;
            float ex = cornerX(k1, x0, x1), ey = cornerY(k1, y0, y1), ez = k1 >= 4 ? z1 : z0;
            seg.add(sx, sy, sz);
            seg.add(ex, ey, ez);
            slot.add(s);
            width.add(w);
        }
    }

    private static float cornerX(int k, float x0, float x1) {
        int m = k & 3;
        return m == 1 || m == 2 ? x1 : x0;
    }

    private static float cornerY(int k, float y0, float y1) {
        return (k & 3) >= 2 ? y1 : y0;
    }

    /** WorldRenderUtils.renderLineStrip: one segment per consecutive pair. */
    private static void strip(Floats seg, Ints slot, Floats width, List<Vec3> pts, int s, float w) {
        for (int i = 0; i < pts.size() - 1; i++) {
            Vec3 start = pts.get(i);
            Vec3 end = pts.get(i + 1);
            seg.add((float) start.x, (float) start.y, (float) start.z);
            seg.add((float) end.x, (float) end.y, (float) end.z);
            slot.add(s);
            width.add(w);
        }
    }

    /** The old {@code ring()} point list fed through {@link #strip}. */
    private static void ring(Floats seg, Ints slot, Floats width, Vec3 centre, double radius, double yOffset, int s,
                             float w) {
        List<Vec3> points = new ArrayList<>(RING_SEGMENTS + 1);
        double y = centre.y + yOffset;
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = (Math.PI * 2 * i) / RING_SEGMENTS;
            points.add(new Vec3(centre.x + Math.cos(angle) * radius, y, centre.z + Math.sin(angle) * radius));
        }
        strip(seg, slot, width, points, s, w);
    }

    /** WorldRenderUtils.addChainedFilledBoxVertices' 24 corners. */
    private static void filledBox(Floats fill, Ints slot, AABB box, int s) {
        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;
        float[] v = {
                minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ,
                maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ,
                minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ,
                maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ,
                minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ,
                minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ,
        };
        for (int i = 0; i < v.length; i += 3) {
            fill.add(v[i], v[i + 1], v[i + 2]);
            slot.add(s);
        }
    }

    /** WorldRenderUtils.renderFilledCylinder: per segment a side and the top cap (and the bottom cap), two-sided. */
    private static void filledCylinder(Floats fill, Ints slot, Vec3 centre, double radius, double y0, double y1,
                                       int segments, int s) {
        int n = Math.max(3, segments);
        float cx = (float) centre.x, cz = (float) centre.z;
        float bottom = (float) y0, top = (float) y1;
        for (int i = 0; i < n; i++) {
            double t0 = (Math.PI * 2 * i) / n;
            double t1 = (Math.PI * 2 * (i + 1)) / n;
            float x0 = (float) (centre.x + Math.cos(t0) * radius), z0 = (float) (centre.z + Math.sin(t0) * radius);
            float x1 = (float) (centre.x + Math.cos(t1) * radius), z1 = (float) (centre.z + Math.sin(t1) * radius);
            twoSidedQuad(fill, slot, s, x0, bottom, z0, x1, bottom, z1, x1, top, z1, x0, top, z0);
            twoSidedQuad(fill, slot, s, cx, top, cz, x0, top, z0, x1, top, z1, x1, top, z1);
            if (top - bottom > 1.0e-4f) {
                twoSidedQuad(fill, slot, s, cx, bottom, cz, x0, bottom, z0, x1, bottom, z1, x1, bottom, z1);
            }
        }
    }

    private static void twoSidedQuad(Floats fill, Ints slot, int s,
                                     float ax, float ay, float az, float bx, float by, float bz,
                                     float cx, float cy, float cz, float dx, float dy, float dz) {
        fill.add(ax, ay, az);
        fill.add(bx, by, bz);
        fill.add(cx, cy, cz);
        fill.add(dx, dy, dz);
        fill.add(dx, dy, dz);
        fill.add(cx, cy, cz);
        fill.add(bx, by, bz);
        fill.add(ax, ay, az);
        for (int i = 0; i < 8; i++) {
            slot.add(s);
        }
    }

    // ---- the two replays ----

    /**
     * Each 4-float colour as the ARGB int whose {@code BufferBuilder.setColor(int)} writes the same four bytes as
     * {@code setColor(r, g, b, a)}: VertexConsumer's {@code setColor(float x4)} is {@code setColor((int) (r * 255f), ...)}
     * and BufferBuilder writes those as bytes R, G, B, A (four {@code memPutByte}, each an FFM segment on Java 25 -
     * the largest allocation left under the replay, JFR 2026-10-16); {@code setColor(int)} is {@code memPutInt} of
     * {@code toABGR(argb)}, the same bytes in the same order on a little-endian or a big-endian machine (javap, both
     * versions).
     */
    static int[] pack(float[] rgba) {
        int[] out = new int[rgba.length / 4];
        for (int i = 0; i < out.length; i++) {
            int r = (int) (rgba[i * 4] * 255.0f);
            int g = (int) (rgba[i * 4 + 1] * 255.0f);
            int b = (int) (rgba[i * 4 + 2] * 255.0f);
            int a = (int) (rgba[i * 4 + 3] * 255.0f);
            out[i] = (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
        }
        return out;
    }

    /**
     * Render-thread scratch for the replays. {@code VertexConsumer.addVertex(Matrix4fc, x, y, z)} is
     * {@code addVertex(matrix.transformPosition(x, y, z, new Vector3f()))} and {@code setNormal(Pose, x, y, z)} is
     * {@code setNormal(pose.transformNormal(x, y, z, new Vector3f()))} (javap, 26.1.2 and 26.2 alike): a Vector3f per
     * vertex and another per normal, 48 bytes a line vertex. {@code BufferBuilder} overrides neither, so for a plain
     * BufferBuilder the replay makes the same two JOML calls into these and hands over the same floats.
     */
    private static final org.joml.Vector3f POS = new org.joml.Vector3f();
    private static final org.joml.Vector3f NORMAL = new org.joml.Vector3f();

    /** True for a consumer whose Matrix/Pose vertex methods are VertexConsumer's own defaults. */
    private static boolean plain(VertexConsumer buffer) {
        return buffer.getClass() == com.mojang.blaze3d.vertex.BufferBuilder.class;
    }

    /**
     * True where shapes must keep their own submissions (26.2). 26.1.2's mod batch ({@code McRender}) keeps one buffer
     * per render type for the whole event, so one pass per type fills each buffer exactly as the per-shape calls did.
     * 26.2's custom-geometry renderer merges only CONSECUTIVE submissions of one type into a draw, and sorts each
     * translucent fill draw on its own (javap, {@code RenderTypeFeatureRenderer$Group.getVertexBuilder}): one pass per
     * type there blended overlapping fills in a different order (95-perf-ar-render shots, 18,151 px on Filled), so 26.2
     * keeps the old sequence - per node its fill, its lines, its start fill - replaying cached ranges.
     * {@code CALLBACK_RUNS_LATER} is exactly the 26.2 side of that difference.
     */
    private static final boolean PER_SHAPE = McRender.CALLBACK_RUNS_LATER;

    /** One node's lines or fills, a range of the mesh, for a PER_SHAPE submission. Built with the mesh. */
    private static final class RangeReplay implements McRender.Geometry {
        final Mesh mesh;
        final boolean lines;
        final int from;
        final int to;

        RangeReplay(Mesh mesh, boolean lines, int from, int to) {
            this.mesh = mesh;
            this.lines = lines;
            this.from = from;
            this.to = to;
        }

        @Override
        public void draw(PoseStack.Pose pose, VertexConsumer buffer) {
            Mesh m = mesh;
            if (lines) {
                LineReplay.segments(pose, buffer, m.seg, m.segSlot, m.segWidth, m.palette, m.packed, from, to, 0f);
            } else {
                FillReplay.vertices(pose, buffer, m, from, to);
            }
        }
    }

    private static final LineReplay LINES = new LineReplay();
    private static final FillReplay FILLS = new FillReplay();

    /** Every visible node's segments in path order, then the chain - each exactly as renderLineBox/renderLineStrip
     *  wrote it. */
    private static final class LineReplay implements McRender.Geometry {
        Mesh mesh;
        /** False: the chain and path only (PER_SHAPE draws the nodes' own lines). */
        boolean nodes = true;
        /** Edit mode's recorded path (6 floats a segment), or null. */
        float[] path;
        float pathWidth;

        @Override
        public void draw(PoseStack.Pose pose, VertexConsumer buffer) {
            Mesh m = mesh;
            float[] pal = m.palette;
            int[] pk = m.packed;
            for (int k = 0; nodes && k < m.count; k++) {
                int i = m.order[k];
                if (m.visible[i]) {
                    segments(pose, buffer, m.seg, m.segSlot, m.segWidth, pal, pk, m.lineStart[i], m.lineEnd[i], 0f);
                }
            }
            segments(pose, buffer, m.seg, m.segSlot, m.segWidth, pal, pk, m.chainStart, m.chainEnd, 0f);
            float[] p = path;
            if (p != null) {
                segments(pose, buffer, p, null, null, PATH_RGBA, PATH_PACKED, 0, p.length / 6, pathWidth);
            }
        }

        /** {@code slots}/{@code widths} null: every segment {@code pal[0..3]} at {@code width}. */
        private static void segments(PoseStack.Pose pose, VertexConsumer buffer, float[] seg, int[] slots, float[] widths,
                                     float[] pal, int[] packed, int from, int to, float width) {
            boolean fast = plain(buffer);
            org.joml.Matrix4f mat = pose.pose();
            org.joml.Vector3f v = POS;
            org.joml.Vector3f nv = NORMAL;
            for (int s = from; s < to; s++) {
                int o = s * 6;
                int c = slots == null ? 0 : slots[s] * 4;
                float r = pal[c], g = pal[c + 1], b = pal[c + 2], a = pal[c + 3];
                float w = widths == null ? width : widths[s];
                float sx = seg[o], sy = seg[o + 1], sz = seg[o + 2];
                float ex = seg[o + 3], ey = seg[o + 4], ez = seg[o + 5];
                float dx = ex - sx, dy = ey - sy, dz = ez - sz;
                if (fast) {
                    // VertexConsumer.addVertex(Pose, ...) / setColor(float x4) / setNormal(Pose, ...) exactly, minus
                    // their new Vector3f and the per-byte colour puts.
                    int col = packed[c >> 2];
                    pose.transformNormal(dx, dy, dz, nv);
                    mat.transformPosition(sx, sy, sz, v);
                    buffer.addVertex(v.x, v.y, v.z).setColor(col).setNormal(nv.x, nv.y, nv.z).setLineWidth(w);
                    mat.transformPosition(ex, ey, ez, v);
                    buffer.addVertex(v.x, v.y, v.z).setColor(col).setNormal(nv.x, nv.y, nv.z).setLineWidth(w);
                } else {
                    buffer.addVertex(pose, sx, sy, sz).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(w);
                    buffer.addVertex(pose, ex, ey, ez).setColor(r, g, b, a).setNormal(pose, dx, dy, dz).setLineWidth(w);
                }
            }
        }
    }

    /** Every visible node's fills in path order, as addChainedFilledBoxVertices/twoSidedQuad wrote them. */
    private static final class FillReplay implements McRender.Geometry {
        Mesh mesh;

        @Override
        public void draw(PoseStack.Pose pose, VertexConsumer buffer) {
            Mesh m = mesh;
            for (int k = 0; k < m.count; k++) {
                int i = m.order[k];
                if (m.visible[i]) {
                    vertices(pose, buffer, m, m.fillStart[i], m.fillEnd[i]);
                }
            }
        }

        static void vertices(PoseStack.Pose pose, VertexConsumer buffer, Mesh m, int from, int to) {
            float[] pal = m.palette;
            float[] f = m.fill;
            int[] pk = m.packed;
            var matrix = pose.pose();
            boolean fast = plain(buffer);
            org.joml.Vector3f p = POS;
            for (int v = from; v < to; v++) {
                int o = v * 3;
                int c = m.fillSlot[v] * 4;
                if (fast) {
                    matrix.transformPosition(f[o], f[o + 1], f[o + 2], p);
                    buffer.addVertex(p.x, p.y, p.z).setColor(pk[m.fillSlot[v]]);
                } else {
                    buffer.addVertex(matrix, f[o], f[o + 1], f[o + 2]).setColor(pal[c], pal[c + 1], pal[c + 2], pal[c + 3]);
                }
            }
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

    // ------------------------------------------------------------------------------------------- edit mode

    /** The recorded path's polyline for one path in one room; rebuilt when the path or the room changes. */
    private static RoutePath pathKey;
    private static int pathKeySize;
    private static RoutePath.Sample pathKeyFirst;
    private static RoutePath.Sample pathKeyLast;
    private static RouteCoords.Frame pathKeyFrame;
    private static int pathKeyShift;
    private static float[] pathSegs;

    /** The path's colour: WorldRenderUtils.renderLineStrip(..., 1f, 0.65f, 0.2f, PATH_ALPHA, ...) as before. */
    private static final float[] PATH_RGBA = {1f, 0.65f, 0.2f, PATH_ALPHA};
    private static final int[] PATH_PACKED = pack(PATH_RGBA);

    /**
     * The recorded movement as a faint polyline - where playback will actually walk - as line segments (renderLineStrip's
     * pairs of consecutive points), or null for a path under two samples.
     */
    private static float[] pathSegments(Route route, RouteCoords.Frame frame, int shift) {
        RoutePath path = route.path();
        if (path.size() < 2) {
            return null;
        }
        RoutePath.Sample first = path.get(0);
        RoutePath.Sample last = path.last();
        if (path != pathKey || path.size() != pathKeySize || first != pathKeyFirst || last != pathKeyLast
                || shift != pathKeyShift || !frame.sameRoom(pathKeyFrame) || pathSegs == null) {
            List<Vec3> points = new ArrayList<>();
            int stride = Math.max(1, path.size() / 400); // a 10-minute path still draws as ~400 segments
            for (int i = 0; i < path.size(); i += stride) {
                RoutePath.Sample s = path.get(i);
                Vec3 real = RouteCoords.toReal(frame, s.x(), s.y(), s.z());
                points.add(real.add(0, 0.05, 0));
            }
            if (last != null) {
                points.add(RouteCoords.toReal(frame, last.x(), last.y(), last.z()).add(0, 0.05, 0));
            }
            // A new array each rebuild, never written afterwards: on 26.2 the draw reads it later in the frame.
            float[] segs = new float[Math.max(0, points.size() - 1) * 6];
            for (int i = 0; i < points.size() - 1; i++) {
                Vec3 a = points.get(i);
                Vec3 b = points.get(i + 1);
                int o = i * 6;
                segs[o] = (float) a.x;
                segs[o + 1] = (float) a.y;
                segs[o + 2] = (float) a.z;
                segs[o + 3] = (float) b.x;
                segs[o + 4] = (float) b.y;
                segs[o + 5] = (float) b.z;
            }
            pathSegs = segs;
            pathKey = path;
            pathKeySize = path.size();
            pathKeyFirst = first;
            pathKeyLast = last;
            pathKeyFrame = frame;
            pathKeyShift = shift;
            pathBuilds++;
        }
        return pathSegs.length == 0 ? null : pathSegs;
    }

    /** Which path the last edit-mode breaker draw took ("highlight/FILLED_OUTLINE 3 drawn"), for the testkit's 96-ar. */
    private static volatile String lastBreakerDraw = "";
    private static boolean lastBreakerWaypoint;
    private static com.killer560.hub.dungeonextras.DungeonExtrasConfig.BreakerStyle lastBreakerStyle;
    private static int lastBreakerCount = -1;

    public static String lastBreakerDraw() {
        return lastBreakerDraw;
    }

    private static void noteBreakerDraw(boolean waypoint,
                                        com.killer560.hub.dungeonextras.DungeonExtrasConfig.BreakerStyle style, int count) {
        if (waypoint != lastBreakerWaypoint || style != lastBreakerStyle || count != lastBreakerCount) {
            lastBreakerWaypoint = waypoint;
            lastBreakerStyle = style;
            lastBreakerCount = count;
            lastBreakerDraw = (waypoint ? "waypoint/" : "highlight/") + style.name() + " " + count + " drawn";
        }
    }

    /** Breaker blocks in real coordinates for one route's breaker nodes in one room: rebuilt when either changes. */
    private static long[] breakerKey = new long[0];
    private static RouteCoords.Frame breakerKeyFrame;
    private static int breakerKeyShift;
    private static BlockPos[] breakerReal = new BlockPos[0];
    private static AABB[] breakerBoxes = new AABB[0];
    /** Reused per frame where the draw runs inside the call (26.1.2); fresh per frame where it runs later (26.2). */
    private static AABB[] breakerScratchBoxes = new AABB[0];
    private static float[] breakerScratchRgba = new float[0];

    /**
     * killer560: "blocks that will be broken by any node get a faint highlight while in edit mode". Air (already broken)
     * is red, a standing block white - QUOI's DB editor. How they are drawn is the Auto Routes tab's Breaker Block
     * Display and Breaker Block Style (2026-10-06), through Breaker Aura's own draw ({@code BreakerAuraFeature
     * .drawPickBoxes}): Highlight is depth-tested, Waypoint draws through walls and is culled to the render distance
     * (horizontal, from the camera) before any world lookup, as Breaker Aura's is.
     */
    private static void renderBreakerBlocks(LevelRenderContext ctx, Minecraft client, Route route, RouteCoords.Frame frame,
                                            int shift) {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        boolean waypoint = cfg.getBreakerDisplay()
                == com.killer560.hub.dungeonextras.DungeonExtrasConfig.BreakerDisplay.WAYPOINT;
        com.killer560.hub.dungeonextras.DungeonExtrasConfig.BreakerStyle style = cfg.getBreakerStyle();
        if (!breakerKeyMatches(route, frame, shift)) {
            rebuildBreakers(route, frame, shift);
        }
        int total = breakerReal.length;
        if (total == 0) {
            noteBreakerDraw(waypoint, style, 0);
            return;
        }
        Vec3 cam = McRender.cameraPos(ctx);
        double maxH = client.options.getEffectiveRenderDistance() * 16.0;
        double maxHSq = maxH * maxH;
        AABB[] boxes;
        float[] rgba;
        if (McRender.CALLBACK_RUNS_LATER) {
            // On 26.2 the draw callbacks run later in the frame (WorldRenderUtils.renderOutlineBoxes): fresh arrays.
            boxes = new AABB[total];
            rgba = new float[total * 4];
        } else {
            if (breakerScratchBoxes.length < total) {
                breakerScratchBoxes = new AABB[total];
                breakerScratchRgba = new float[total * 4];
            }
            boxes = breakerScratchBoxes;
            rgba = breakerScratchRgba;
        }
        int count = 0;
        for (int j = 0; j < total; j++) {
            BlockPos real = breakerReal[j];
            if (waypoint) {
                double dx = real.getX() + 0.5 - cam.x;
                double dz = real.getZ() + 0.5 - cam.z;
                if (dx * dx + dz * dz > maxHSq) {
                    continue;
                }
            }
            boolean air = client.level.getBlockState(real).isAir();
            boxes[count] = breakerBoxes[j];
            int c = count * 4;
            rgba[c] = 1f;
            rgba[c + 1] = air ? 0.2f : 1f;
            rgba[c + 2] = air ? 0.2f : 1f;
            rgba[c + 3] = air ? 0.5f : 0.6f;
            count++;
        }
        noteBreakerDraw(waypoint, style, count);
        com.killer560.hub.dungeonextras.BreakerAuraFeature.drawPickBoxes(ctx, boxes, rgba, count, waypoint, style);
    }

    /** True when the breaker nodes' blocks (in list order) and the room are what {@link #breakerReal} was built from. */
    private static boolean breakerKeyMatches(Route route, RouteCoords.Frame frame, int shift) {
        if (shift != breakerKeyShift || !frame.sameRoom(breakerKeyFrame)) {
            return false;
        }
        int j = 0;
        long[] key = breakerKey;
        for (RouteNode node : route.nodes()) {
            if (node.type != RouteNode.Type.DUNGEON_BREAKER) {
                continue;
            }
            for (BlockPos rel : node.breakerBlocks) {
                if (j >= key.length || key[j] != rel.asLong()) {
                    return false;
                }
                j++;
            }
        }
        return j == key.length;
    }

    private static void rebuildBreakers(Route route, RouteCoords.Frame frame, int shift) {
        int total = 0;
        for (RouteNode node : route.nodes()) {
            if (node.type == RouteNode.Type.DUNGEON_BREAKER) {
                total += node.breakerBlocks.size();
            }
        }
        long[] key = new long[total];
        BlockPos[] real = new BlockPos[total];
        AABB[] boxes = new AABB[total];
        int j = 0;
        for (RouteNode node : route.nodes()) {
            if (node.type != RouteNode.Type.DUNGEON_BREAKER) {
                continue;
            }
            for (BlockPos rel : node.breakerBlocks) {
                BlockPos r = RouteCoords.toRealBlock(frame, rel);
                key[j] = rel.asLong();
                real[j] = r;
                boxes[j] = new AABB(r.getX(), r.getY(), r.getZ(), r.getX() + 1, r.getY() + 1, r.getZ() + 1);
                j++;
            }
        }
        breakerKey = key;
        breakerReal = real;
        breakerBoxes = boxes;
        breakerKeyFrame = frame;
        breakerKeyShift = shift;
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

    // ------------------------------------------------------------------------------------------- growable arrays

    private static final class Floats {
        float[] data;
        int size;

        Floats(int cap) {
            data = new float[cap];
        }

        void add(float a) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = a;
        }

        void add(float a, float b, float c) {
            add(a);
            add(b);
            add(c);
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private static final class Ints {
        int[] data;
        int size;

        Ints(int cap) {
            data = new int[cap];
        }

        void add(int a) {
            if (size == data.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = a;
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
