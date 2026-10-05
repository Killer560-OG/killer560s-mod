package com.killer560.hub.compat;

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
        MultiBufferSource.BufferSource bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        PoseStack poseStack = context.poseStack();
        Vec3 cam = cameraPos(context);
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        geometry.draw(poseStack.last(), bufferSource.getBuffer(type));
        poseStack.popPose();
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

}
