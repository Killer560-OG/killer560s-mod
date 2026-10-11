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
    private static final String H_BLOCK = "HeldOutlineH";
    private static final int H_SIZE = 16;
    private static final String MAIN_BLOCK = "HeldOutlineConfig";
    private static final int MAIN_SIZE = 48;
    private static final int MAX_CAPTURES = 4;

    private record Capture(LivingEntity entity, ItemStack stack, ItemDisplayContext context, PoseStack.Pose pose,
                           int light) {
    }

    private static final List<Capture> captures = new ArrayList<>(2);
    private static final Matrix4f handView = new Matrix4f();
    private static boolean drawingMask;

    private static TextureTarget mask;
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

    private HeldOutlineRenderer() {
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    public static int lastItemCount() {
        return lastItemCount;
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
            if (mask == null) {
                mask = McRender.newTextureTarget("killer560smod held outline mask", main.width, main.height, true);
            } else if (mask.width != main.width || mask.height != main.height) {
                mask.resize(main.width, main.height);
            }

            McRender.clearToZero(mask.getColorTexture());
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(mask.getDepthTexture(), McRender.FAR_DEPTH);

            GpuTextureView oldColor = RenderSystem.outputColorTextureOverride;
            GpuTextureView oldDepth = RenderSystem.outputDepthTextureOverride;
            RenderSystem.outputColorTextureOverride = mask.getColorTextureView();
            RenderSystem.outputDepthTextureOverride = mask.getDepthTextureView();
            drawingMask = true;
            int drawn = 0;
            try {
                SubmitNodeCollector collector = McRender.handMaskCollector(mc);
                for (Capture c : captures) {
                    PoseStack ps = new PoseStack();
                    ps.last().set(c.pose());
                    mc.gameRenderer.itemInHandRenderer.renderItem(c.entity(), c.stack(), c.context(), ps, collector,
                            c.light());
                    drawn++;
                }
                McRender.drawHandMask(mc, handView);
            } finally {
                drawingMask = false;
                RenderSystem.outputColorTextureOverride = oldColor;
                RenderSystem.outputDepthTextureOverride = oldDepth;
            }

            writeUniforms(cfg);
            RenderSystem.backupProjectionMatrix();
            try {
                FrameGraphBuilder frame = new FrameGraphBuilder();
                Map<Identifier, ResourceHandle<RenderTarget>> handles = new HashMap<>();
                handles.put(PostChain.MAIN_TARGET_ID, frame.importExternal("main", main));
                handles.put(MASK_ID, frame.importExternal("held_mask", mask));
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
        } catch (Throwable t) {
            fail("Held Item Outline render failed", t);
        } finally {
            captures.clear();
        }
    }

    private static void writeUniforms(HeldOutlineConfig cfg) {
        HeldOutlineConfig.Style style = cfg.getStyle();
        float w = cfg.getWidth();
        McRender.writeUniformVec4s(hBuffer, new float[]{style.outline ? w : 0f, (float) McRender.FAR_DEPTH, 0f, 0f});
        int o = cfg.getOutlineColor();
        int f = cfg.getFillColor();
        McRender.writeUniformVec4s(mainBuffer, new float[]{
                ((o >> 16) & 0xFF) / 255f, ((o >> 8) & 0xFF) / 255f, (o & 0xFF) / 255f, ((o >>> 24) & 0xFF) / 255f,
                ((f >> 16) & 0xFF) / 255f, ((f >> 8) & 0xFF) / 255f, (f & 0xFF) / 255f, ((f >>> 24) & 0xFF) / 255f,
                w, style.outline ? 1f : 0f, style.fill ? 1f : 0f, debugView});
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
                    Set.of(PostChain.MAIN_TARGET_ID, MASK_ID), CHAIN_ID, projection, projectionBuffer);
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
        if (mask != null) {
            try {
                mask.destroyBuffers();
            } catch (Throwable ignored) {
            }
            mask = null;
        }
    }
}
