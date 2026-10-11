package com.killer560.hub.heldoutline;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.killer560.hub.compat.McRender;
import com.killer560.hub.motionblur.mixin.PostChainAccessor;
import com.killer560.hub.motionblur.mixin.PostPassAccessor;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Held Item Outline: an outline, a fill, or both, around the FIRST-PERSON held item model - never the hotbar, never
 * third person.
 *
 * <p>The technique is the one every outline/glow mod uses (vanilla's own glowing-entity outline, Meteor Client's
 * shader ESP): draw the object again into an off-screen mask, then run an edge pass over the mask and composite onto
 * the frame. Here:
 * <ol>
 * <li>{@code ItemInHandRenderer.renderItem} is hooked at HEAD and records each first-person item it is asked to draw
 * (stack, display context, the full pose, light). Nothing is changed in the vanilla draw.</li>
 * <li>In {@code GameRenderer.renderLevel}, right before the screen effect is submitted (after the hands, before the
 * GUI), each recorded item is submitted again through vanilla's own {@code renderItem} and drawn with
 * {@code RenderSystem.outputColorTextureOverride}/{@code outputDepthTextureOverride} pointing at a cleared
 * window-sized mask target - every {@code RenderType} draw honours those overrides (javap: {@code RenderType.draw} on
 * 26.1.2, {@code PreparedRenderType.drawFromBuffer} on 26.2). It is drawn under the model-view vanilla drew the hands
 * with ({@code McRender.drawHandMask}). A mask texel that took depth or any colour is the item's silhouette (not every
 * item pipeline writes alpha).</li>
 * <li>A post chain ({@code assets/killer560smod/heldoutline/held_outline.json}) runs a horizontal pass storing the
 * distance to the nearest mask pixel in each row, then a vertical pass that turns those into the exact Euclidean
 * distance within the outline width (2 x (2w + 1) texel reads per pixel, not (2w + 1)^2), blends the outline
 * (solid out to the width, fading over the next pixel) outside the silhouette and the fill inside it, and blits back
 * onto the main target.</li>
 * </ol>
 * Because it composites before the GUI is drawn, the hotbar and every HUD element sit on top of it; with no item in
 * the hand, in third person, with F1, or with the feature off, nothing is recorded and nothing at all runs.
 * The chain is built and failure-handled like {@link com.killer560.hub.motionblur.MotionBlurRenderer}: any failure
 * logs once, says so in chat and switches the feature off.
 */
public final class HeldOutlineRenderer {

    private static final Logger LOGGER = ModLog.get("killer560smod-heldoutline");

    private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("killer560smod", "held_outline");
    private static final Identifier CHAIN_JSON =
            Identifier.fromNamespaceAndPath("killer560smod", "heldoutline/held_outline.json");
    static final Identifier MASK_ID = Identifier.fromNamespaceAndPath("killer560smod", "held_mask");
    static final Identifier NEAR_ID = Identifier.fromNamespaceAndPath("killer560smod", "held_near");
    static final Identifier FAR_ID = Identifier.fromNamespaceAndPath("killer560smod", "held_far");
    private static final String H_BLOCK = "HeldOutlineH";
    private static final int H_SIZE = 16;
    private static final String MAIN_BLOCK = "HeldOutlineConfig";
    /** Connector segments for Outline Side: Both (screen pixels), as many as the shader's {@code Seg[]} holds. */
    static final int MAX_SEGMENTS = 128;
    /** OutlineColor, FillColor, Params, SegInfo, SegBox, then Seg[MAX_SEGMENTS] - all std140 vec4. */
    private static final int MAIN_SIZE = (5 + MAX_SEGMENTS) * 16;
    private static final int MAX_CAPTURES = 4;

    private record Capture(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack.Pose pose,
                           int light) {
    }

    private static final List<Capture> captures = new ArrayList<>(2);
    private static final Matrix4f handView = new Matrix4f();
    private static boolean drawingMask;

    private static TextureTarget mask;
    /** Outline Side: the near face alone and the far face alone (colour only; they borrow {@link #mask}'s depth). */
    private static TextureTarget nearMask;
    private static TextureTarget farMask;
    /** 0 while vanilla submits; 1 / 2 while the near / far face mask is drawn (read by {@link #filterSubmit}). */
    private static int faceFilter;
    private static boolean inFilteredSubmit;
    /** Whether any item drawn this frame had a near and a far face (an extruded sprite or a cube). */
    private static boolean facesFound;
    private static final float[] segments = new float[MAX_SEGMENTS * 4];
    private static int segmentCount;
    private static final Matrix4f hudProjection = new Matrix4f();
    private static PostChain chain;
    private static GpuBuffer hBuffer;
    private static GpuBuffer mainBuffer;
    private static CrossFrameResourcePool resourcePool;
    private static Projection projection;
    private static ProjectionMatrixBuffer projectionBuffer;
    private static boolean failed;

    /** Test/diagnostic counters: frames composited, items drawn into the mask in the last composited frame. */
    private static long framesDrawn;
    /** Test view, set by the testkit only: 0 normal, 1 the composite pass paints the whole frame magenta, 2 it shows
     *  the mask's silhouette in white on black. Separates "the pass never wrote" from "the mask was empty". */
    private static int debugView;
    private static int lastItemCount;
    private static boolean lastFacesFound;
    private static int lastSegmentCount;

    private HeldOutlineRenderer() {
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    public static int lastItemCount() {
        return lastItemCount;
    }

    /** Whether the last composited frame found a near and a far face (false: Near/Both fell back to Far). */
    public static boolean lastFacesFound() {
        return lastFacesFound;
    }

    /** Connector lines drawn by Outline Side: Both in the last composited frame. */
    public static int lastSegmentCount() {
        return lastSegmentCount;
    }

    /** {@code GameRenderer.renderItemInHand} HEAD: a new set of hands, and the matrix vanilla pushes for them. */
    public static void beginHands(Matrix4fc view) {
        captures.clear();
        if ((failed || chain != null || mask != null) && !HeldOutlineConfig.getInstance().isEnabledRaw()) {
            failed = false; // re-enabling retries a previous failure
            closeChain(); // free the window-sized targets while off
        }
        if (view != null) {
            handView.set(view);
        } else {
            handView.identity();
        }
    }

    /** {@code ItemInHandRenderer.renderItem} HEAD. */
    public static void capture(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack poseStack,
                               int light) {
        if (drawingMask || failed || entity == null || stack == null || stack.isEmpty() || context == null
                || !context.firstPerson() || captures.size() >= MAX_CAPTURES) {
            return;
        }
        HeldOutlineConfig cfg = HeldOutlineConfig.getInstance();
        if (!cfg.isActive()) {
            return;
        }
        boolean rightContext = !context.leftHand();
        boolean mainRight = entity.getMainArm() == HumanoidArm.RIGHT;
        boolean mainHand = rightContext == mainRight;
        if (mainHand ? !cfg.isMainHand() : !cfg.isOffHand()) {
            return;
        }
        captures.add(new Capture(entity, stack, context, poseStack.last().copy(), light));
    }

    /** {@code GameRenderer.renderLevel}, just before the screen effect is submitted. */
    public static void afterHands() {
        if (captures.isEmpty()) {
            return;
        }
        HeldOutlineConfig cfg = HeldOutlineConfig.getInstance();
        if (failed || !cfg.isActive()) {
            captures.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            RenderTarget main = McRender.mainRenderTarget(mc);
            if (main == null || main.width <= 0 || main.height <= 0 || main.getColorTexture() == null) {
                return;
            }
            if (chain == null && !buildChain(mc)) {
                return;
            }
            mask = sized(mask, "killer560smod held outline mask", main, true);
            HeldOutlineConfig.Side side = cfg.getStyle().outline ? cfg.getSide() : HeldOutlineConfig.Side.FAR;
            boolean sides = side != HeldOutlineConfig.Side.FAR;
            if (sides) {
                nearMask = sized(nearMask, "killer560smod held outline near face", main, false);
                farMask = sized(farMask, "killer560smod held outline far face", main, false);
            }

            SubmitNodeCollector collector = McRender.handMaskCollector(mc);
            facesFound = false;
            segmentCount = 0;
            int drawn = captures.size();
            if (sides) {
                // The two faces alone, each drawn with only its own big quad (filterSubmit). They borrow the full mask's
                // depth, so they go first, and the full silhouette last leaves its own depth for the edge pass.
                ((com.killer560.hub.heldoutline.mixin.HeldOutlineProjectionAccessor) mc.gameRenderer)
                        .killer560smod$hudProjection().getMatrix(hudProjection);
                drawMask(mc, collector, nearMask, 1);
                drawMask(mc, collector, farMask, 2);
            }
            drawMask(mc, collector, mask, 0);

            writeUniforms(cfg, side);
            RenderSystem.backupProjectionMatrix();
            try {
                FrameGraphBuilder frame = new FrameGraphBuilder();
                Map<Identifier, ResourceHandle<RenderTarget>> handles = new HashMap<>();
                handles.put(PostChain.MAIN_TARGET_ID, frame.importExternal("main", main));
                handles.put(MASK_ID, frame.importExternal("held_mask", mask));
                // With Outline Side: Far the face masks are not drawn; the full mask stands in (the shader ignores it).
                handles.put(NEAR_ID, frame.importExternal("held_near", sides ? nearMask : mask));
                handles.put(FAR_ID, frame.importExternal("held_far", sides ? farMask : mask));
                chain.addToFrame(frame, main.width, main.height, new PostChain.TargetBundle() {
                    @Override
                    public void replace(Identifier id, ResourceHandle<RenderTarget> handle) {
                        handles.put(id, handle);
                    }

                    @Override
                    public ResourceHandle<RenderTarget> get(Identifier id) {
                        return handles.get(id);
                    }
                });
                frame.execute(resourcePool);
                resourcePool.endFrame();
            } finally {
                RenderSystem.restoreProjectionMatrix();
            }
            framesDrawn++;
            lastItemCount = drawn;
            lastFacesFound = sides && facesFound;
            lastSegmentCount = side == HeldOutlineConfig.Side.BOTH && facesFound ? segmentCount : 0;
        } catch (Throwable t) {
            fail("Held Item Outline render failed", t);
        } finally {
            captures.clear();
        }
    }

    private static void writeUniforms(HeldOutlineConfig cfg, HeldOutlineConfig.Side side) {
        HeldOutlineConfig.Style style = cfg.getStyle();
        float w = cfg.getWidth();
        // Mode: 0 Far (the whole silhouette, the original look), 1 Near, 2 Both. Near and Both need the two faces; a
        // model without them (a shield's special model) falls back to Far.
        float mode = side == HeldOutlineConfig.Side.FAR || !facesFound ? 0f
                : side == HeldOutlineConfig.Side.NEAR ? 1f : 2f;
        McRender.writeUniformVec4s(hBuffer, new float[]{style.outline ? w : 0f, (float) McRender.FAR_DEPTH, mode, 0f});
        int o = cfg.getOutlineColor();
        int f = cfg.getFillColor();
        int segs = mode == 2f ? segmentCount : 0;
        float[] v = new float[MAIN_SIZE / 4];
        float[] head = {
                ((o >> 16) & 0xFF) / 255f, ((o >> 8) & 0xFF) / 255f, (o & 0xFF) / 255f, ((o >>> 24) & 0xFF) / 255f,
                ((f >> 16) & 0xFF) / 255f, ((f >> 8) & 0xFF) / 255f, (f & 0xFF) / 255f, ((f >>> 24) & 0xFF) / 255f,
                w, style.outline ? 1f : 0f, style.fill ? 1f : 0f, debugView,
                segs, mode, 0f, 0f};
        System.arraycopy(head, 0, v, 0, head.length);
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < segs; i++) {
            for (int k = 0; k < 2; k++) {
                minX = Math.min(minX, segments[i * 4 + k * 2]);
                maxX = Math.max(maxX, segments[i * 4 + k * 2]);
                minY = Math.min(minY, segments[i * 4 + k * 2 + 1]);
                maxY = Math.max(maxY, segments[i * 4 + k * 2 + 1]);
            }
        }
        float pad = w + 2f;
        v[16] = segs == 0 ? 1f : minX - pad;
        v[17] = segs == 0 ? 1f : minY - pad;
        v[18] = segs == 0 ? 0f : maxX + pad;
        v[19] = segs == 0 ? 0f : maxY + pad;
        System.arraycopy(segments, 0, v, 20, segs * 4);
        McRender.writeUniformVec4s(mainBuffer, v);
    }

    /** A window-sized target, made or resized to match {@code main}. */
    private static TextureTarget sized(TextureTarget t, String label, RenderTarget main, boolean depth) {
        if (t == null) {
            return McRender.newTextureTarget(label, main.width, main.height, depth);
        }
        if (t.width != main.width || t.height != main.height) {
            t.resize(main.width, main.height);
        }
        return t;
    }

    /**
     * Draws every recorded item again into {@code target}'s colour (and {@link #mask}'s depth), under the model-view
     * vanilla drew the hands with. {@code filter} 0 draws the whole item; 1 / 2 only its near / far face.
     */
    private static void drawMask(Minecraft mc, SubmitNodeCollector collector, TextureTarget target, int filter) {
        McRender.clearToZero(target.getColorTexture());
        RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(mask.getDepthTexture(), McRender.FAR_DEPTH);
        GpuTextureView oldColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView oldDepth = RenderSystem.outputDepthTextureOverride;
        RenderSystem.outputColorTextureOverride = target.getColorTextureView();
        RenderSystem.outputDepthTextureOverride = mask.getDepthTextureView();
        drawingMask = true;
        faceFilter = filter;
        try {
            for (Capture c : captures) {
                PoseStack ps = new PoseStack();
                ps.last().set(c.pose());
                mc.gameRenderer.itemInHandRenderer.renderItem(c.entity(), c.stack(), c.context(), ps, collector,
                        c.light());
            }
            McRender.drawHandMask(mc, handView);
        } finally {
            drawingMask = false;
            faceFilter = 0;
            RenderSystem.outputColorTextureOverride = oldColor;
            RenderSystem.outputDepthTextureOverride = oldDepth;
        }
    }

    /**
     * {@code SubmitNodeStorage.submitItem} HEAD while a face mask is drawn: re-submits only the item's near face
     * ({@link #faceFilter} 1) or far face (2), and returns true so the original call is cancelled.
     *
     * <p>A flat item's model (ItemModelGenerator) is two full-size quads facing NORTH and SOUTH - the sprite's front and
     * back, its shape coming from the texture's alpha - plus thin wall quads along the texel edges; a cube is six full
     * quads. The near face is the one whose centre is nearer the camera. The far face points away, so back-face culling
     * would drop it; it is re-submitted with its vertex order reversed, which covers the same pixels facing the camera.
     * A layer without such a pair draws nothing here (a shield's special model never comes here at all), and the frame
     * falls back to Far.
     *
     * <p>While the near face is drawn, the "lines between the two at points" for Outline Side: Both are collected: a wall
     * edge running from the near face to the far face that two walls of different directions share is a corner of the
     * slab, and its two ends are projected to window pixels.
     */
    public static boolean filterSubmit(net.minecraft.client.renderer.SubmitNodeStorage self, PoseStack pose,
                                       ItemDisplayContext context, int light, int overlay, int outline, int[] tints,
                                       List<net.minecraft.client.resources.model.geometry.BakedQuad> quads,
                                       net.minecraft.client.renderer.item.ItemStackRenderState.FoilType foil) {
        if (faceFilter == 0 || inFilteredSubmit || quads == null) {
            return false;
        }
        net.minecraft.client.resources.model.geometry.BakedQuad north = null;
        net.minecraft.client.resources.model.geometry.BakedQuad south = null;
        int norths = 0;
        int souths = 0;
        for (net.minecraft.client.resources.model.geometry.BakedQuad q : quads) {
            if (q.direction() != net.minecraft.core.Direction.NORTH && q.direction() != net.minecraft.core.Direction.SOUTH) {
                continue;
            }
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                org.joml.Vector3fc p = q.position(i);
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y());
                maxY = Math.max(maxY, p.y());
            }
            if (maxX - minX < 0.99f || maxY - minY < 0.99f) {
                continue;
            }
            if (q.direction() == net.minecraft.core.Direction.NORTH) {
                north = q;
                norths++;
            } else {
                south = q;
                souths++;
            }
        }
        if (norths != 1 || souths != 1) {
            return true; // not a two-faced model: nothing in the face masks
        }
        Matrix4f toView = new Matrix4f(RenderSystem.getModelViewStack()).mul(handView).mul(pose.last().pose());
        boolean northNear = centreDistance(toView, north) < centreDistance(toView, south);
        net.minecraft.client.resources.model.geometry.BakedQuad near = northNear ? north : south;
        net.minecraft.client.resources.model.geometry.BakedQuad far = northNear ? south : north;
        List<net.minecraft.client.resources.model.geometry.BakedQuad> only;
        if (faceFilter == 1) {
            only = List.of(near);
            collectConnectors(toView, quads, near, far);
        } else {
            only = List.of(new net.minecraft.client.resources.model.geometry.BakedQuad(far.position3(), far.position2(), far.position1(), far.position0(),
                    far.packedUV3(), far.packedUV2(), far.packedUV1(), far.packedUV0(),
                    far.direction().getOpposite(), far.materialInfo()));
        }
        inFilteredSubmit = true;
        try {
            self.submitItem(pose, context, light, overlay, outline, tints, only, foil);
        } finally {
            inFilteredSubmit = false;
        }
        facesFound = true;
        return true;
    }

    private static float centreDistance(Matrix4f toView, net.minecraft.client.resources.model.geometry.BakedQuad q) {
        org.joml.Vector3f c = new org.joml.Vector3f();
        for (int i = 0; i < 4; i++) {
            c.add(q.position(i));
        }
        c.mul(0.25f);
        toView.transformPosition(c);
        return c.length();
    }

    private static void collectConnectors(Matrix4f toView, List<net.minecraft.client.resources.model.geometry.BakedQuad> quads, net.minecraft.client.resources.model.geometry.BakedQuad near, net.minecraft.client.resources.model.geometry.BakedQuad far) {
        float zNear = near.position0().z();
        float zFar = far.position0().z();
        float depth = Math.abs(zNear - zFar);
        if (depth < 1.0e-4f) {
            return;
        }
        // Wall edge (x, y) -> the directions of the walls with an edge running from the near to the far face there.
        Map<Long, java.util.EnumSet<net.minecraft.core.Direction>> edges = new HashMap<>();
        for (net.minecraft.client.resources.model.geometry.BakedQuad q : quads) {
            if (q == near || q == far) {
                continue;
            }
            for (int i = 0; i < 4; i++) {
                org.joml.Vector3fc a = q.position(i);
                org.joml.Vector3fc b = q.position((i + 1) % 4);
                boolean depthEdge = Math.abs(a.x() - b.x()) < 1.0e-4f && Math.abs(a.y() - b.y()) < 1.0e-4f
                        && Math.abs(Math.abs(a.z() - b.z()) - depth) < 1.0e-4f;
                if (!depthEdge) {
                    continue;
                }
                long key = ((long) Math.round(a.x() * 4096f) << 32) | (Math.round(a.y() * 4096f) & 0xFFFFFFFFL);
                edges.computeIfAbsent(key, k -> java.util.EnumSet.noneOf(net.minecraft.core.Direction.class))
                        .add(q.direction());
            }
        }
        Matrix4f clip = new Matrix4f(hudProjection).mul(toView);
        RenderTarget main = McRender.mainRenderTarget(Minecraft.getInstance());
        for (Map.Entry<Long, java.util.EnumSet<net.minecraft.core.Direction>> e : edges.entrySet()) {
            if (e.getValue().size() < 2 || segmentCount >= MAX_SEGMENTS) {
                continue;
            }
            float x = (int) (e.getKey() >> 32) / 4096f;
            float y = (int) (e.getKey() & 0xFFFFFFFFL) / 4096f;
            float[] a = screen(clip, x, y, zNear, main);
            float[] b = screen(clip, x, y, zFar, main);
            if (a == null || b == null) {
                continue;
            }
            int o = segmentCount * 4;
            segments[o] = a[0];
            segments[o + 1] = a[1];
            segments[o + 2] = b[0];
            segments[o + 3] = b[1];
            segmentCount++;
        }
    }

    /** Model point -> window pixels, origin bottom-left as gl_FragCoord; null behind the camera. */
    private static float[] screen(Matrix4f clip, float x, float y, float z, RenderTarget main) {
        org.joml.Vector4f v = clip.transform(new org.joml.Vector4f(x, y, z, 1f));
        if (v.w <= 1.0e-6f) {
            return null;
        }
        return new float[]{(v.x / v.w * 0.5f + 0.5f) * main.width, (v.y / v.w * 0.5f + 0.5f) * main.height};
    }

    private static boolean buildChain(Minecraft mc) {
        PostChain built = null;
        try {
            Optional<Resource> resource = mc.getResourceManager().getResource(CHAIN_JSON);
            if (resource.isEmpty()) {
                fail("Held Item Outline effect file not found (" + CHAIN_JSON + ")", null);
                return false;
            }
            JsonElement json;
            try (Reader reader = resource.get().openAsReader()) {
                json = JsonParser.parseReader(reader);
            }
            DataResult<PostChainConfig> parsed = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json);
            if (parsed.result().isEmpty()) {
                fail("Held Item Outline effect file is invalid: "
                        + parsed.error().map(DataResult.Error::message).orElse("unknown error"), null);
                return false;
            }
            if (projection == null) {
                projection = new Projection();
                projectionBuffer = new ProjectionMatrixBuffer("killer560smod held outline");
            }
            built = PostChain.load(parsed.result().get(), mc.getTextureManager(),
                    Set.of(PostChain.MAIN_TARGET_ID, MASK_ID, NEAR_ID, FAR_ID), CHAIN_ID, projection, projectionBuffer);
            List<PostPass> passes = ((PostChainAccessor) built).killer560smod$getPasses();
            GpuDevice device = RenderSystem.getDevice();
            for (PostPass pass : passes) {
                RenderPipeline pipeline = ((PostPassAccessor) pass).killer560smod$getPipeline();
                CompiledRenderPipeline compiled = device.precompilePipeline(pipeline);
                if (compiled == null || !compiled.isValid()) {
                    built.close();
                    fail("Held Item Outline shader failed to compile (" + pipeline.getLocation() + ")", null);
                    return false;
                }
            }
            GpuBuffer h = installUniform(device, passes, H_BLOCK, H_SIZE);
            GpuBuffer m = installUniform(device, passes, MAIN_BLOCK, MAIN_SIZE);
            if (h == null || m == null) {
                built.close();
                fail("Held Item Outline uniform block missing from the effect", null);
                return false;
            }
            if (resourcePool == null) {
                resourcePool = new CrossFrameResourcePool(3);
            }
            chain = built;
            hBuffer = h; // both closed by chain.close() via PostPass.close()
            mainBuffer = m;
            return true;
        } catch (Throwable t) {
            if (built != null) {
                try {
                    built.close();
                } catch (Throwable ignored) {
                }
            }
            fail("Held Item Outline effect failed to load", t);
            return false;
        }
    }

    /** Swaps the JSON-declared (USAGE_UNIFORM only, not writable) block for a mappable one of the same size. */
    private static GpuBuffer installUniform(GpuDevice device, List<PostPass> passes, String block, int size) {
        for (PostPass pass : passes) {
            Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).killer560smod$getCustomUniforms();
            if (!uniforms.containsKey(block)) {
                continue;
            }
            GpuBuffer buffer = device.createBuffer(() -> "killer560smod held outline " + block,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, size);
            GpuBuffer old = uniforms.put(block, buffer);
            if (old != null && old != buffer) {
                old.close();
            }
            return buffer;
        }
        return null;
    }

    private static void fail(String message, Throwable t) {
        if (t != null) {
            LOGGER.error(message, t);
        } else {
            LOGGER.error(message);
        }
        failed = true;
        closeChain();
        HeldOutlineConfig cfg = HeldOutlineConfig.getInstance();
        if (cfg.isEnabledRaw()) {
            cfg.setEnabled(false);
            cfg.save();
        }
        try {
            ModChat.send("Held Item Outline", ModChat.bad("Failed to load, disabled."));
        } catch (Throwable ignored) {
        }
    }

    private static void closeChain() {
        PostChain old = chain;
        chain = null;
        hBuffer = null;
        mainBuffer = null;
        if (old != null) {
            try {
                old.close();
            } catch (Throwable ignored) {
            }
        }
        if (resourcePool != null) {
            try {
                resourcePool.clear();
            } catch (Throwable ignored) {
            }
        }
        for (TextureTarget t : new TextureTarget[]{mask, nearMask, farMask}) {
            if (t != null) {
                try {
                    t.destroyBuffers();
                } catch (Throwable ignored) {
                }
            }
        }
        mask = null;
        nearMask = null;
        farMask = null;
    }
}
