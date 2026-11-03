package com.killer560.hub.compat;

import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4fc;
import org.joml.Matrix4fStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import org.joml.Vector4f;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * World-space drawing, for Minecraft 26.2.
 *
 * <p>This is the part of the 26.2 port that looked like a rewrite and turned out not to be.
 * {@code MultiBufferSource} does not exist in 26.2 at all - zero occurrences in the jar - and
 * {@code LevelRenderer.render} now works through a frame graph and render-state objects instead of handing
 * out vertex buffers. But {@code OrderedSubmitNodeCollector.submitCustomGeometry} takes a callback that
 * receives a {@code VertexConsumer}, so the mod's own vertex math is untouched and only the way it gets hold
 * of a consumer differs. {@code RenderType} and {@code RenderTypes} sit at the same package in both versions,
 * so the signatures here are genuinely identical rather than merely similar.
 *
 * <p><b>The camera.</b> 26.2 makes {@code GameRenderer.mainCamera} private and Fabric does not widen it, so
 * it cannot be read the old way - checked with a compile probe, not assumed. What does work is
 * {@code levelState().cameraRenderState.pos}, which Fabric's transitive access wideners open. That is only
 * reachable from inside a render event, so {@link #cameraPos} takes the context; every one of the mod's 29
 * camera call sites is already inside one, which was verified before this signature was settled.
 */
public final class McRender {

    private McRender() {
    }

    /**
     * Whether {@link #inCameraSpace} runs its callback LATER than the call (26.2 queues it and builds it later in the frame.) A caller that hands the
     * callback a reused scratch buffer must copy it first when this is true.
     */
    public static final boolean CALLBACK_RUNS_LATER = true;

    /** What a caller draws once the pose is in camera space. */
    public interface Geometry {
        void draw(PoseStack.Pose pose, VertexConsumer buffer);
    }

    /** Sorted upload for a LINES render type where the version allows it: 26.1.2 sorts lines, 26.2 refuses. */
    public static RenderSetup.RenderSetupBuilder sortLinesOnUpload(RenderSetup.RenderSetupBuilder builder) {
        // 26.2 sorts QUADS only: StagedVertexBuffer.appendDraw throws "Cannot sort draw with LINES" (javap, 26.2),
        // which crashed the first frame a through-walls solver line was drawn (testkit 93-solve, 2026-10-05).
        return builder;
    }

    /**
     * Gives a no-depth-test render type its Improved Transparency (OIT) pipelines. 26.3 draws every blended custom geometry
     * through them when Improved Transparency is on and throws "does not have OIT pipelines set up" for a type without
     * (javap 26.3, PreparedRenderType.drawFromBufferOit); {@code lines} picks vanilla's OIT lines or debug-filled snippet as the base (the plain snippets carry their own blend, which the OIT stages
     * reject: "Blend functions must currently be the same for all color targets").
     * 26.2 has no such thing, so there it returns the builder unchanged.
     */
    public static RenderSetup.RenderSetupBuilder withOitNoDepth(RenderSetup.RenderSetupBuilder builder, String name,
                                                                boolean lines) {
        return builder.setOitPipelines(net.minecraft.client.renderer.RenderPipelines.register(
                net.minecraft.client.renderer.oit.OitPipelineSet.builder(name, RenderPipeline.builder(lines
                        ? net.minecraft.client.renderer.RenderPipelines.OIT_LINES_SNIPPET
                        : net.minecraft.client.renderer.RenderPipelines.OIT_DEBUG_FILLED_SNIPPET).withCull(false))
                        .withoutDepthTest().build()));
    }

    /**
     * Gives a depth-tested, depth-writing render type vanilla's own OIT pipelines for lines or debug-filled quads (26.3 only;
     * 26.2 has none and returns the builder unchanged). Depth testing and writing happen in the OIT depth-bounds stage there.
     */
    public static RenderSetup.RenderSetupBuilder withVanillaOit(RenderSetup.RenderSetupBuilder builder, boolean lines) {
        return builder.setOitPipelines(lines ? net.minecraft.client.renderer.RenderPipelines.OIT_LINES_TRANSLUCENT
                : net.minecraft.client.renderer.RenderPipelines.OIT_DEBUG_FILLED_BOX);
    }

    /** Where the camera is this frame. */
    public static Vec3 cameraPos(LevelRenderContext context) {
        return context.levelState().cameraRenderState.pos;
    }

    /**
     * How the camera is turned this frame - {@code Camera.rotation()}'s quaternion, for billboarding.
     *
     * <p>{@code CameraRenderState.orientation} is not merely similar to it: 26.2's own
     * {@code Camera.extractRenderState} fills that field with {@code orientation.set(rotation())}, read off the
     * bytecode. The instance is the render state's own and vanilla reuses it between frames, so a caller must
     * not keep it - every caller in this mod feeds it straight to {@code PoseStack.mulPose}.
     */
    public static Quaternionf cameraRotation(LevelRenderContext context) {
        return context.levelState().cameraRenderState.orientation;
    }

    /** The camera's pitch, in degrees - {@code Camera.xRot()}. */
    public static float cameraXRot(LevelRenderContext context) {
        return context.levelState().cameraRenderState.xRot;
    }

    /** The camera's yaw, in degrees - {@code Camera.yRot()}. */
    public static float cameraYRot(LevelRenderContext context) {
        return context.levelState().cameraRenderState.yRot;
    }

    /**
     * Unit vector the camera is looking along - {@code Camera.forwardVector()}.
     *
     * <p>The render state does NOT carry this one, so it is rebuilt the way {@code Camera} builds it:
     * {@code Camera.setRotation} does {@code FORWARDS.rotate(rotation, forwards)} with the class's private
     * {@code FORWARDS} constant, which the static initializer sets to {@code (0, 0, -1)} - both facts read off
     * {@code javap -c} on 26.2's own {@code Camera}, not assumed from the 26.1.2 source. Minus Z, not plus:
     * getting that sign wrong would have compiled and pointed every tracer backward.
     *
     * <p>A fresh vector each call, because {@code rotate} writes into its destination and the camera's own
     * {@code forwards} field is not reachable here.
     */
    public static Vector3fc cameraForward(LevelRenderContext context) {
        return new Vector3f(0.0F, 0.0F, -1.0F).rotate(cameraRotation(context));
    }

    /**
     * Runs {@code geometry} with the pose translated into camera space and a consumer for {@code type}.
     *
     * <p>Every world-space draw in the mod had the same seven lines around it - null-check the buffer source,
     * push, translate by the negated camera, take the pose, get the buffer, draw, pop. They live here now, so
     * a version that changes how any of that works changes one place.
     */
    /**
     * Client init. 26.1.2 batches each world render event's geometry into one draw call per type here; 26.2 has
     * nothing to do, because its submit collector already groups custom geometry by render type.
     */
    public static void registerBatching() {
    }

    /** Testkit: {geometry callbacks batched, batches drawn}; always 0 on 26.2 (see {@link #registerBatching}). */
    public static long[] batchStats() {
        return new long[]{0L, 0L};
    }

    // ---- text measured from a world render event ------------------------------------------------------------------
    //
    // 26.3 runs the solid and classic-translucent stages inside ONE open render pass (LevelRenderer's main-pass lambda
    // opens it, executeSolid and executeClassicTransparency take it; javap 26.3), and Fabric's AFTER_TRANSLUCENT_FEATURES
    // and AFTER_TRANSLUCENT_TERRAIN fire inside executeClassicTransparency. Font.width bakes a glyph the first time it
    // sees a code point (FontSet$DelayedBake -> BitmapProvider$Glyph.upload -> copyBufferToTexture), and
    // FrontendCommandEncoder.copyBufferToTexture throws "Close the existing render pass before performing additional
    // commands" while isInRenderPass. That disabled Auto Routes and AP3 on their first frame (tkport263 U-2, U-3).
    // So a world renderer never calls the font inside a pass: it asks here. Outside a pass this measures (and bakes)
    // as normal; inside one it answers from widths measured earlier, and a string not seen yet is queued, measured at
    // the next safe point (the next frame's COLLECT_SUBMITS, or the client tick) and estimated for that one frame.
    // Drawing is unaffected: submitText only records the sequence, and its glyphs are prepared in prepareFrame,
    // before any pass opens.

    private static final java.util.Map<Object, Integer> TEXT_WIDTHS = new java.util.HashMap<>();
    private static final java.util.LinkedHashSet<Object> TEXT_PENDING = new java.util.LinkedHashSet<>();
    private static final int TEXT_WIDTHS_MAX = 4096;
    private static long textWidthEstimates;
    private static long textWidthsWarmed;
    private static final org.slf4j.Logger TEXT_LOG = com.killer560.hub.util.ModLog.get("killer560smod-render");

    /** Client init: measures queued strings at points where no render pass is open. A no-op on 26.2. */
    public static void registerTextWidths() {
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS
                .register(ctx -> warmPendingText());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK
                .register(client -> warmPendingText());
    }

    /** Whether a render pass is open on the device's (single) command encoder right now. Always false on 26.2. */
    public static boolean inRenderPass() {
        Object encoder = RenderSystem.getDevice().createCommandEncoder();
        if (encoder instanceof com.mojang.renderpearl.frontend.FrontendCommandEncoder frontend) {
            return frontend.isInRenderPass();
        }
        return true; // an encoder we do not know: assume the worst and never bake
    }

    /** {@code font.width(text)}, safe to call inside an open render pass (see above). */
    public static int textWidth(net.minecraft.client.gui.Font font, String text) {
        if (text == null) {
            return 0;
        }
        return textWidthOf(font, text);
    }

    /** {@code font.width(text)} for a component, safe to call inside an open render pass (see above). */
    public static int textWidth(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText text) {
        if (text == null) {
            return 0;
        }
        return textWidthOf(font, text);
    }

    /**
     * How many {@link #textWidth} answers were estimates (a string not yet measured, asked inside a pass). A caller that
     * caches a width compares this before and after and measures again next frame when it moved. Always 0 on 26.2.
     */
    public static long textWidthEstimates() {
        return textWidthEstimates;
    }

    /** Testkit: {estimates answered, strings measured at a safe point}. Both 0 on 26.2. */
    public static long[] textWidthStats() {
        return new long[]{textWidthEstimates, textWidthsWarmed};
    }

    private static int textWidthOf(net.minecraft.client.gui.Font font, Object text) {
        if (!inRenderPass()) {
            int w = measure(font, text);
            remember(text, w);
            return w;
        }
        Integer known = TEXT_WIDTHS.get(text);
        if (known != null) {
            return known;
        }
        TEXT_PENDING.add(text);
        if (textWidthEstimates++ == 0) {
            TEXT_LOG.info("[McRender] first text width asked inside an open render pass: estimated, measured at the next safe point");
        }
        return estimate(text instanceof String s ? s : ((net.minecraft.network.chat.FormattedText) text).getString());
    }

    private static int measure(net.minecraft.client.gui.Font font, Object text) {
        return text instanceof String s ? font.width(s) : font.width((net.minecraft.network.chat.FormattedText) text);
    }

    private static void remember(Object text, int width) {
        if (TEXT_WIDTHS.size() >= TEXT_WIDTHS_MAX && !TEXT_WIDTHS.containsKey(text)) {
            TEXT_WIDTHS.clear();
        }
        TEXT_WIDTHS.put(text, width);
    }

    private static void warmPendingText() {
        if (TEXT_PENDING.isEmpty() || inRenderPass()) {
            return;
        }
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        for (Object text : TEXT_PENDING) {
            remember(text, measure(font, text));
            if (textWidthsWarmed++ == 0) {
                TEXT_LOG.info("[McRender] first queued text width measured outside the render pass");
            }
        }
        TEXT_PENDING.clear();
    }

    /** About what the default font gives: 6 px a character (5 + 1 spacing), section-sign codes skipped. */
    private static int estimate(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '§' && i + 1 < s.length()) {
                i++;
                continue;
            }
            w += 6;
        }
        return w;
    }

    public static void inCameraSpace(LevelRenderContext context, RenderType type, Geometry geometry) {
        PoseStack poseStack = context.poseStack();
        Vec3 cam = cameraPos(context);
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        // order(0) is the default ordering bucket. submitCustomGeometry captures the pose at submit time and
        // hands it back to the callback, so popping immediately afterward is correct even though the frame
        // graph runs the callback later.
        context.submitNodeCollector().order(0).submitCustomGeometry(poseStack, type, geometry::draw);
        poseStack.popPose();
    }
    /**
     * Draws text in the world.
     *
     * <p>26.2 removes {@code Font.drawInBatch} entirely and replaces it with a submit. The argument order below
     * is NOT a guess: {@code submitText} takes four trailing ints where {@code drawInBatch} took three, and the
     * order was read off the components of {@code TextFeatureRenderer.Submit}, the record it builds -
     * {@code lightCoords, color, backgroundColor, outlineColor}. Getting that wrong would have compiled and
     * drawn text in the wrong color.
     *
     * <p>{@code font} is unused here - 26.2 needs only the sequence - but stays in the signature so both
     * versions' copies match, which is the rule for this package.
     */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.network.chat.Component text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack, net.minecraft.client.gui.Font.DisplayMode mode,
                                int background, int light) {
        drawText(context, font, text.getVisualOrderText(), x, y, colour, shadow, poseStack, mode, background,
                light);
    }

    /**
     * As {@link #drawText}, for a plain string.
     *
     * <p>26.2's submit path speaks only {@code FormattedCharSequence}, so the wrap happens here rather than at
     * eighteen call sites. The string's section-sign color codes are APPLIED, from {@code Style.EMPTY}, exactly as
     * {@code Font.drawInBatch(String, ...)} did on 26.1.2 and as {@code Font.width(String)} measures it.
     * {@code FormattedCharSequence.forward} does not read them: it drew "Emissary §7(186m)" with the code
     * printed (commissions-1103; HudTextCache had the same fault in 95-perf-fixes).
     */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font, String text,
                                float x, float y, int colour, boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        net.minecraft.util.FormattedCharSequence seq = sink -> net.minecraft.util.StringDecomposer.iterateFormatted(
                text, net.minecraft.network.chat.Style.EMPTY, sink);
        drawText(context, font, seq, x, y, colour, shadow, poseStack, mode, background, light);
    }

    /** As {@link #drawText}, for an already-ordered sequence - what 26.2 submits natively. */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.util.FormattedCharSequence text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        context.submitNodeCollector().order(0).submitText(poseStack, x, y, text, shadow, mode, light, colour,
                background, 0);
    }


    // --------------------------------------------------------------------------- render targets and GPU buffers
    //
    // The four differences the two cosmetic post-process features (Custom Scoreboard's background blur and
    // Motion Blur) ran into. All four are the same API doing the same thing under a different name, verified
    // with javap against both jars - none of them needed the features stubbing out, and neither feature's GLSL
    // changes at all: 26.2 still wants "#version 330", still ships
    // assets/minecraft/shaders/include/dynamictransforms.glsl and projection.glsl for #moj_import, and still
    // declares "uniform sampler2D Sampler0" (see core/text.fsh).

    /**
     * The framebuffer the world was drawn into this frame.
     *
     * <p>26.2 removes {@code Minecraft.getMainRenderTarget()}. It is NOT replaced by a GpuSurface model, as the
     * port notes claimed: {@code GameRenderer} keeps a private {@code mainRenderTarget} field and exposes it as
     * the public method {@code mainRenderTarget()} - the old name minus the {@code get}. Found by listing
     * {@code GameRenderer}, and it is still a real {@code MainTarget}, so everything the two blur features do
     * with it (width, height, {@code getColorTexture}, {@code getColorTextureView}) is unchanged.
     * {@code Minecraft.windowSurface()} is a different thing - the swapchain the finished frame is presented to -
     * and is NOT what either feature wants to sample.
     */
    public static RenderTarget mainRenderTarget(Minecraft client) {
        return client.gameRenderer.mainRenderTarget();
    }

    /**
     * Declares that a pipeline samples texture unit 0.
     *
     * <p>26.2 drops {@code RenderPipeline.Builder.withSampler(String)} for the bind-group model, where the same
     * declaration is {@code withBindGroupLayout(BindGroupLayouts.SAMPLER0)} - and {@code BindGroupLayouts.SAMPLER0}
     * is, in its own bytecode, exactly {@code BindGroupLayout.builder().withSampler("Sampler0").build()}, which is
     * why this is a rename and not a shader change.
     */
    /**
     * Gives a pipeline built from {@code RenderPipelines.LINES_SNIPPET} the color target 26.2's snippet carried. 26.3's
     * LINES_SNIPPET has NO color target state (vanilla adds {@code ColorTargetState.DEFAULT} or a translucent one per
     * pipeline; javap 26.3 RenderPipelines), and a pipeline with none crashes the game on its first draw in the main pass:
     * "Render pass color attachment count must match pipeline color target state count" (FrontendRenderPass.setPipeline),
     * which Secret Waypoints' through-walls lines hit on 2026-11-01 (testkit 96-ar). 26.2's snippet carries
     * {@code new ColorTargetState(BlendFunction.TRANSLUCENT)} (javap 26.2), so that is what is added here.
     */
    public static RenderPipeline.Builder linesColorTarget(RenderPipeline.Builder builder) {
        return builder.withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(
                com.mojang.renderpearl.api.pipeline.BlendFunction.TRANSLUCENT));
    }

    public static RenderPipeline.Builder withSampler0(RenderPipeline.Builder builder) {
        return builder.withBindGroupLayout(BindGroupLayouts.SAMPLER0);
    }

    /**
     * An off-screen color target the size of the window, in the same format as the main one.
     *
     * <p>26.2's {@code TextureTarget} constructor gained a {@code GpuFormat}. {@code RGBA8_UNORM} is not a guess:
     * it is what {@code MainTarget}'s own constructor passes up to {@code RenderTarget}, read off its bytecode -
     * and it has to match, because the whole point of this target is to be a {@code copyTextureToTexture}
     * destination for the main one.
     */
    public static TextureTarget newTextureTarget(String label, int width, int height, boolean useDepth) {
        // 26.3: TextureTarget(String, int, int, GpuFormat color, GpuFormat depth); a null depth format makes no depth
        // texture (RenderTarget.createBuffers checks it). MainTarget passes RGBA8_UNORM and D32_FLOAT (javap, 26.3).
        return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, useDepth ? GpuFormat.D32_FLOAT : null);
    }

    /**
     * Clears a color texture to transparent black - the {@code 0} that 26.1.2's {@code clearColorTexture} took
     * as a packed ARGB int, which 26.2 takes as a {@code Vector4fc}.
     */
    public static void clearToZero(GpuTexture texture) {
        RenderSystem.getDevice().createCommandEncoder()
                .clearColorTexture(texture, new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
    }

    /**
     * Writes one std140 vec4 into a mapped uniform buffer.
     *
     * <p>26.2 moves mapping off the command encoder and onto the buffer: {@code CommandEncoder.mapBuffer(buffer,
     * read, write)} returning a {@code GpuBuffer.MappedView} became {@code GpuBuffer.map(read, write)} returning
     * a {@code GpuBufferSlice.MappedView}. Both are {@code AutoCloseable} with a {@code data()} of
     * {@code ByteBuffer} and {@code Std140Builder} is unchanged, but the view TYPE differs, so the
     * try-with-resources itself has to live on this side of the facade rather than at the call site.
     */
    public static void writeUniformVec4(GpuBuffer buffer, float x, float y, float z, float w) {
        try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(x, y, z, w);
        }
    }

    /** Multiplies a rotation onto the pose. 26.3 renamed {@code PoseStack.mulPose(Quaternionfc)} to
     *  {@code rotate(Quaternionfc)} (javap); {@code mulPose} now takes only a matrix or a Transformation. */
    public static void rotate(com.mojang.blaze3d.vertex.PoseStack poseStack, org.joml.Quaternionfc rotation) {
        poseStack.rotate(rotation);
    }

    // ---- first-person hand (Held Item Outline) ------------------------------------------------------------------

    private static net.minecraft.client.renderer.SubmitNodeStorage handMaskStorage;

    /** The depth a cleared target holds where nothing was drawn. 26.2 is reverse-Z: {@code GameRenderer.renderLevel} clears depth to 0.0 before the hands and LevelRenderer's always-on-top pass clears its target to 0.0 (javap); a target cleared to 1.0 rejects every fragment. */
    public static final double FAR_DEPTH = 0.0;

    /** Where an extra first-person item submit goes so that {@link #drawHandMask} draws it: a storage of our own. */
    public static SubmitNodeCollector handMaskCollector(Minecraft client) {
        if (handMaskStorage == null) {
            handMaskStorage = new net.minecraft.client.renderer.SubmitNodeStorage();
        }
        return handMaskStorage;
    }

    /**
     * Draws what was submitted to {@link #handMaskCollector} into {@code colorTarget}'s color and {@code depthTarget}'s
     * depth, under the same model-view vanilla drew the hands with.
     *
     * <p>26.3 removed {@code RenderSystem.outputColorTextureOverride}/{@code outputDepthTextureOverride}, and
     * {@code FeatureRenderDispatcher.renderAllFeatures} became static {@code (RenderPass, PreparedFrame)}. This is
     * {@code GameRenderer.renderItemInHand}'s own sequence (javap, 26.3): push the model-view stack and multiply the view
     * matrix on, {@code prepareFrame(storage)} (which drains the storage), open a render pass on the targets with no
     * clear, {@code RenderSystem.bindDefaultUniforms}, {@code renderAllFeatures}, close the pass, then the frame. The
     * dispatcher reuses ONE PreparedFrame, so this must not run while vanilla holds it open; Held Item Outline calls it
     * from {@code render3dHud} between the hands' frame closing and the screen effect's opening.
     */
    public static void drawHandMask(Minecraft client, Matrix4fc handView, RenderTarget colorTarget,
                                    RenderTarget depthTarget) {
        if (handMaskStorage == null) {
            return;
        }
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        try {
            if (handView != null) {
                mv.mul(handView);
            }
            try (net.minecraft.client.renderer.feature.FeatureRenderDispatcher.PreparedFrame frame =
                         client.gameRenderer.featureRenderDispatcher().prepareFrame(handMaskStorage);
                 com.mojang.renderpearl.api.commands.RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                         .createRenderPass(() -> "killer560smod held outline mask", colorTarget.getColorTextureView(),
                                 java.util.Optional.empty(), depthTarget.getDepthTextureView(),
                                 java.util.OptionalDouble.empty())) {
                RenderSystem.bindDefaultUniforms(pass);
                net.minecraft.client.renderer.feature.FeatureRenderDispatcher.renderAllFeatures(pass, frame);
            }
        } finally {
            mv.popMatrix();
        }
    }

    /**
     * Compiles {@code pipeline} now and says whether it compiled. 26.3 removed {@code GpuDevice.precompilePipeline} and
     * {@code CompiledRenderPipeline.isValid}; its {@code RenderSystem.getCompiledPipelineNullable} looks the pipeline up
     * in the current (or fallback) {@code PipelineCache}, whose {@code get} compiles a miss synchronously
     * ({@code compilePipeline(...).join().finishCompile()}) and returns null when it fails (javap, 26.3).
     */
    public static boolean pipelineCompiles(RenderPipeline pipeline) {
        return RenderSystem.getCompiledPipelineNullable(pipeline) != null;
    }

    /** The entity's arm-swing progress this frame, 0 at rest - {@code LivingEntity.getAttackAnim} on 26.2,
     *  {@code getSwingAnimation} on 26.3 (javap, both). */
    public static float swingProgress(net.minecraft.world.entity.LivingEntity entity, float partialTick) {
        return entity.getSwingAnimation(partialTick);
    }

    /** Copies the arm-swing animation between two armed render states: 26.2's {@code attackTime} + {@code attackArm};
     *  26.3 replaced them with {@code swingAnimation} + {@code currentSwing} (javap, both). */
    public static void copySwing(net.minecraft.client.renderer.entity.state.ArmedEntityRenderState to,
                                 net.minecraft.client.renderer.entity.state.ArmedEntityRenderState from) {
        to.swingAnimation = from.swingAnimation;
        to.currentSwing = from.currentSwing;
    }

    /** Writes consecutive std140 vec4s (four floats each) into a mapped uniform buffer. */
    public static void writeUniformVec4s(GpuBuffer buffer, float[] values) {
        try (GpuBufferSlice.MappedView view = buffer.map(false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            for (int i = 0; i + 3 < values.length; i += 4) {
                b.putVec4(values[i], values[i + 1], values[i + 2], values[i + 3]);
            }
        }
    }

    /**
     * One extra nametag line, drawn exactly as vanilla draws {@code nameTag} and {@code scoreText} in
     * {@code EntityRenderer.submitNameDisplay}: 26.1.2's {@code submitNameTag} takes the camera distance, 26.2's does not
     * (javap, both), so it is dropped here. Song Tag (spotify/SongTagDraw) is the caller.
     */
    public static void submitNameTag(SubmitNodeCollector collector, PoseStack poseStack, Vec3 attachment, int yOffset,
                                     net.minecraft.network.chat.Component text, boolean seeThrough, int light,
                                     double distanceToCameraSq,
                                     net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        collector.submitNameTag(poseStack, attachment, yOffset, text, seeThrough, light, camera);
    }

}
