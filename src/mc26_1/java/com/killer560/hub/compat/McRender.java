package com.killer560.hub.compat;

import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4fc;
import org.joml.Matrix4fStack;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Quaternionf;
import org.joml.Vector3fc;

/**
 * World-space drawing, for Minecraft 26.1.2.
 *
 * <p>This is the part of the 26.2 port that looked like a rewrite and turned out not to be.
 * {@code MultiBufferSource} does not exist in 26.2 at all - zero occurrences in the jar - and
 * {@code LevelRenderer.render} now works through a frame graph and render-state objects instead of handing
 * out vertex buffers. But {@code OrderedSubmitNodeCollector.submitCustomGeometry} takes a callback that
 * receives a {@code VertexConsumer}, so the mod's own vertex maths is untouched and only the way it gets hold
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
     * Whether {@link #inCameraSpace} runs its callback LATER than the call (26.1.2 runs the callback inside the call.) A caller that hands the
     * callback a reused scratch buffer must copy it first when this is true.
     */
    public static final boolean CALLBACK_RUNS_LATER = false;

    /** What a caller draws once the pose is in camera space. */
    public interface Geometry {
        void draw(PoseStack.Pose pose, VertexConsumer buffer);
    }

    /** Sorted upload for a LINES render type where the version allows it: 26.1.2 sorts lines, 26.2 refuses. */
    public static RenderSetup.RenderSetupBuilder sortLinesOnUpload(RenderSetup.RenderSetupBuilder builder) {
        return builder.sortOnUpload();
    }

    /** Where the camera is this frame. */
    public static Vec3 cameraPos(LevelRenderContext context) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().position();
    }

    /** How the camera is turned this frame - the quaternion callers billboard with. */
    public static Quaternionf cameraRotation(LevelRenderContext context) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().rotation();
    }

    /** The camera's pitch, in degrees. */
    public static float cameraXRot(LevelRenderContext context) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().xRot();
    }

    /** The camera's yaw, in degrees. */
    public static float cameraYRot(LevelRenderContext context) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().yRot();
    }

    /** Unit vector the camera is looking along. */
    public static Vector3fc cameraForward(LevelRenderContext context) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().forwardVector();
    }

    /**
     * Runs {@code geometry} with the pose translated into camera space and a consumer for {@code type}.
     *
     * <p>Every world-space draw in the mod had the same seven lines around it - null-check the buffer source,
     * push, translate by the negated camera, take the pose, get the buffer, draw, pop. They live here now, so
     * a version that changes how any of that works changes one place.
     */
    public static void inCameraSpace(LevelRenderContext context, RenderType type, Geometry geometry) {
        MultiBufferSource.BufferSource bufferSource = batching ? batch : context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        if (batching) {
            batchedDraws++;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = cameraPos(context);
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        geometry.draw(poseStack.last(), bufferSource.getBuffer(type));
        poseStack.popPose();
    }
    // --------------------------------------------------------------------------- one draw call per type (FPS pass)
    //
    // 26.1.2 hands every render event the level's immediate BufferSource, and none of the types the mod draws with
    // (debugFilledBox, LINES_TRANSLUCENT, the features' no-depth copies) is one of its fixed buffers: they all share
    // ONE buffer, and getBuffer for a different type ENDS the one before it (javap, MultiBufferSource$BufferSource.
    // getBuffer: lastSharedType -> endBatch). So every switch between a box's fill and its outline, or between one
    // feature's lines and the next feature's fills, was a build + sort + upload + draw call: an ESP box with a fill was
    // two draw calls, a teammate with a label three. Inside the two events below, inCameraSpace writes into a source
    // of the mod's own instead, where every type is a fixed buffer (added the first time it is seen), so nothing ends
    // anything until the event's last listener has run; then each type is drawn once. Same vertices, same render
    // types, same moment of the frame (vanilla ends its own source right after both events, LevelRenderer
    // lambda$addMainPass$0); only the order BETWEEN types changes, which shows only where two translucent shapes
    // overlap. 26.2 needs none of this: its submit collector already groups custom geometry by type.

    private static final net.minecraft.resources.Identifier BATCH_OPEN =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "world_batch_open");
    private static final net.minecraft.resources.Identifier BATCH_FLUSH =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "world_batch_flush");
    /** Render thread only. */
    private static WorldBatch batch;
    private static boolean batching;
    private static long batchedDraws;
    private static long batchFlushes;

    /** The mod's own buffer source: every type a fixed buffer, so a type switch never ends a batch. */
    private static final class WorldBatch extends MultiBufferSource.BufferSource {
        WorldBatch() {
            super(new com.mojang.blaze3d.vertex.ByteBufferBuilder(256), new java.util.LinkedHashMap<>());
        }

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (!fixedBuffers.containsKey(type)) {
                fixedBuffers.put(type, new com.mojang.blaze3d.vertex.ByteBufferBuilder(type.bufferSize()));
            }
            return super.getBuffer(type);
        }

        boolean hasWork() {
            return !startedBuilders.isEmpty();
        }

        /**
         * Frees everything, for a batch that went wrong (a listener threw before the flush, or a builder could not
         * build): the next frame starts a fresh one rather than drawing or appending to half-written buffers.
         */
        void release() {
            startedBuilders.clear();
            for (com.mojang.blaze3d.vertex.ByteBufferBuilder b : fixedBuffers.values()) {
                try {
                    b.close();
                } catch (RuntimeException ignored) {
                    // freeing is best effort
                }
            }
            fixedBuffers.clear();
            try {
                sharedBuffer.close();
            } catch (RuntimeException ignored) {
                // freeing is best effort
            }
        }
    }

    /** Client init, once: opens the batch before the default phase of each event and draws it after. */
    public static void registerBatching() {
        var features = net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES;
        features.addPhaseOrdering(BATCH_OPEN, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        features.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, BATCH_FLUSH);
        features.register(BATCH_OPEN, context -> openBatch());
        features.register(BATCH_FLUSH, context -> flushBatch());
        var terrain = net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN;
        terrain.addPhaseOrdering(BATCH_OPEN, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        terrain.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, BATCH_FLUSH);
        terrain.register(BATCH_OPEN, context -> openBatch());
        terrain.register(BATCH_FLUSH, context -> flushBatch());
    }

    private static void openBatch() {
        if (batch != null && batching) {
            // The last event never reached its flush: a listener before it threw.
            batch.release();
            batch = null;
        }
        if (batch == null) {
            batch = new WorldBatch();
        }
        batching = true;
    }

    private static void flushBatch() {
        if (!batching) {
            return;
        }
        batching = false;
        if (batch.hasWork()) {
            batchFlushes++;
            try {
                batch.endBatch();
            } catch (RuntimeException e) {
                // A geometry callback that threw half way through a vertex leaves a builder that cannot build. Drop
                // this frame's batch rather than let the exception into LevelRenderer (and keep the next frame's).
                batch.release();
                batch = null;
                if (!batchFailureLogged) {
                    batchFailureLogged = true;
                    com.killer560.hub.util.ModLog.get("killer560smod-render")
                            .error("World render batch failed; this frame's mod geometry was dropped", e);
                }
            }
        }
    }

    private static boolean batchFailureLogged;

    /** Testkit: {geometry callbacks written into the batch, batches drawn} since launch (26.2: both 0). */
    public static long[] batchStats() {
        return new long[]{batchedDraws, batchFlushes};
    }

    /**
     * Draws text in the world.
     *
     * <p>26.2 removes {@code Font.drawInBatch} entirely - text is prepared and submitted now - so the twenty
     * call sites in this mod go through here.
     */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.network.chat.Component text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack, net.minecraft.client.gui.Font.DisplayMode mode,
                                int background, int light) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        font.drawInBatch(text, x, y, colour, shadow, poseStack.last().pose(), bufferSource, mode, background,
                light);
    }

    /** As {@link #drawText}, for a plain string. */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font, String text,
                                float x, float y, int colour, boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        font.drawInBatch(text, x, y, colour, shadow, poseStack.last().pose(), bufferSource, mode, background,
                light);
    }

    /** As {@link #drawText}, for an already-ordered sequence. */
    public static void drawText(LevelRenderContext context, net.minecraft.client.gui.Font font,
                                net.minecraft.util.FormattedCharSequence text, float x, float y, int colour,
                                boolean shadow, PoseStack poseStack,
                                net.minecraft.client.gui.Font.DisplayMode mode, int background, int light) {
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        font.drawInBatch(text, x, y, colour, shadow, poseStack.last().pose(), bufferSource, mode, background,
                light);
    }


    // --------------------------------------------------------------------------- render targets and GPU buffers
    //
    // The four differences the two cosmetic post-process features (Custom Scoreboard's background blur and
    // Motion Blur) ran into. All four are the same API doing the same thing under a different name, verified
    // with javap against both jars - none of them needed the features stubbing out, and neither feature's GLSL
    // changes at all: 26.2 still wants "#version 330", still ships
    // assets/minecraft/shaders/include/dynamictransforms.glsl and projection.glsl for #moj_import, and still
    // declares "uniform sampler2D Sampler0".

    /**
     * The framebuffer the world was drawn into this frame.
     *
     * <p>26.2 removes {@code Minecraft.getMainRenderTarget()}. It is NOT replaced by a GpuSurface model, as the
     * port notes claimed: {@code GameRenderer} keeps a private {@code mainRenderTarget} field and exposes it as
     * the public method {@code mainRenderTarget()} - the old name minus the {@code get}. Found by listing
     * {@code GameRenderer}, and it is still a real {@code MainTarget}, so everything the two blur features do
     * with it (width, height, {@code getColorTexture}, {@code getColorTextureView}) is unchanged.
     */
    public static RenderTarget mainRenderTarget(Minecraft client) {
        return client.getMainRenderTarget();
    }

    /**
     * Declares that a pipeline samples texture unit 0.
     *
     * <p>26.2 drops {@code RenderPipeline.Builder.withSampler(String)} for the bind-group model, where the same
     * declaration is {@code withBindGroupLayout(BindGroupLayouts.SAMPLER0)} - and {@code BindGroupLayouts.SAMPLER0}
     * is, in its own bytecode, exactly {@code BindGroupLayout.builder().withSampler("Sampler0").build()}. The
     * shader still writes {@code uniform sampler2D Sampler0}.
     */
    public static RenderPipeline.Builder withSampler0(RenderPipeline.Builder builder) {
        return builder.withSampler("Sampler0");
    }

    /** An off-screen colour target the size of the window, in the same format as the main one. */
    public static TextureTarget newTextureTarget(String label, int width, int height, boolean useDepth) {
        return new TextureTarget(label, width, height, useDepth);
    }

    /**
     * Clears a colour texture to transparent black - the {@code 0} that 26.1.2's {@code clearColorTexture} took
     * as a packed ARGB int, which 26.2 takes as a {@code Vector4fc}.
     */
    public static void clearToZero(GpuTexture texture) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, 0);
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
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(buffer, false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(x, y, z, w);
        }
    }

    // ---- first-person hand (Held Item Outline) ------------------------------------------------------------------

    /** The depth a cleared target holds where nothing was drawn. 26.1.2 clears depth to 1.0 (far) before the hands. */
    public static final double FAR_DEPTH = 1.0;

    /** Where an extra first-person item submit goes so that {@link #drawHandMask} draws it: 26.1.2 has one storage. */
    public static SubmitNodeCollector handMaskCollector(Minecraft client) {
        return client.gameRenderer.getFeatureRenderDispatcher().getSubmitNodeStorage();
    }

    /**
     * Draws what was submitted to {@link #handMaskCollector}, under the same model-view vanilla drew the hands with.
     * 26.1.2's {@code ItemInHandRenderer.renderHandsWithItems} flushes its own submits ({@code renderAllFeatures()} +
     * {@code bufferSource().endBatch()} at its end, javap) while {@code GameRenderer.renderItemInHand} has
     * {@code handView} multiplied onto the pushed model-view stack - so the hands are already drawn by the time anything
     * after {@code renderItemInHand} runs, and a second copy needs that push again. (Measured: the storage holds 0 item
     * submits right after {@code renderItemInHand}, and a copy drawn without the push never appeared on screen.)
     */
    public static void drawHandMask(Minecraft client, Matrix4fc handView) {
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        try {
            if (handView != null) {
                mv.mul(handView);
            }
            client.gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
            client.renderBuffers().bufferSource().endBatch();
        } finally {
            mv.popMatrix();
        }
    }

    /** Writes consecutive std140 vec4s (four floats each) into a mapped uniform buffer. */
    public static void writeUniformVec4s(GpuBuffer buffer, float[] values) {
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(buffer, false, true)) {
            Std140Builder b = Std140Builder.intoBuffer(view.data());
            for (int i = 0; i + 3 < values.length; i += 4) {
                b.putVec4(values[i], values[i + 1], values[i + 2], values[i + 3]);
            }
        }
    }

    /**
     * One extra nametag line, drawn exactly as vanilla draws {@code nameTag} and {@code scoreText} in
     * {@code EntityRenderer.submitNameDisplay}: 26.1.2's {@code submitNameTag} takes the camera distance, 26.2's does not
     * (javap, both). Song Tag (spotify/SongTagDraw) is the caller.
     */
    public static void submitNameTag(SubmitNodeCollector collector, PoseStack poseStack, Vec3 attachment, int yOffset,
                                     net.minecraft.network.chat.Component text, boolean seeThrough, int light,
                                     double distanceToCameraSq,
                                     net.minecraft.client.renderer.state.level.CameraRenderState camera) {
        collector.submitNameTag(poseStack, attachment, yOffset, text, seeThrough, light, distanceToCameraSq, camera);
    }

}
