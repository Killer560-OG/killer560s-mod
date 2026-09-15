package com.killer560.hub.motionblur;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.killer560.hub.motionblur.mixin.PostChainAccessor;
import com.killer560.hub.motionblur.mixin.PostPassAccessor;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Frame-accumulation motion blur: every frame the finished image is blended with a persistent "previous"
 * target ({@code out = mix(current, previous, blend)}), the result is stored back as the new "previous" and
 * copied onto the main target. Same technique as Noryea/motionblur-fabric, 7avyy/motionblur-fabric-1.21.11 and
 * IseyitThe/MotionBlurX, rebuilt against the javap-verified 26.1.2 Blaze3D API.
 * <p>
 * Robustness choices (all verified against the 26.1.2 jar):
 * <ul>
 * <li>The chain JSON lives at {@code assets/killer560smod/motionblur/motion_blur.json}, NOT under
 *     {@code post_effect/}: vanilla's ShaderManager parses every {@code post_effect/*.json} on each resource
 *     reload, and both a codec failure there and a failed {@code ShaderManager.getPostChain} call end in
 *     {@code Minecraft.triggerResourcePackRecovery} (clears the user's resource packs, or crashes when it can't).
 *     We parse it with {@link PostChainConfig#CODEC} ourselves and build it with {@link PostChain#load}, so
 *     every failure stays inside this class.</li>
 * <li>The fragment shader is under {@code shaders/post/} so vanilla's ShaderManager serves its source (only
 *     the raw text is loaded at reload - compiling happens here).</li>
 * <li>Each pass pipeline is precompiled with {@link GpuDevice#precompilePipeline} and checked with
 *     {@link CompiledRenderPipeline#isValid()} before the first frame; an invalid program would otherwise throw
 *     "Pipeline contains invalid shader program" mid frame-graph.</li>
 * <li>The JSON-declared {@code MotionBlurConfig} uniform buffer is USAGE_UNIFORM only (not writable), so it is
 *     swapped for our own UNIFORM|MAP_WRITE buffer of the same std140 size (vec4 = 16 bytes).</li>
 * <li>Own {@link CrossFrameResourcePool} (vanilla uses 3 frames), so no shadow into GameRenderer is needed.</li>
 * </ul>
 * Any failure: log once, chat notice, feature switched off and saved. Re-enabling from the tab retries.
 */
public final class MotionBlurRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-motionblur");

    private static final Identifier CHAIN_ID = Identifier.fromNamespaceAndPath("killer560smod", "motion_blur");
    private static final Identifier CHAIN_JSON =
            Identifier.fromNamespaceAndPath("killer560smod", "motionblur/motion_blur.json");
    private static final String UNIFORM_BLOCK = "MotionBlurConfig";
    private static final int UNIFORM_SIZE = 16;

    /** Blend factors are authored for this frame rate and rescaled for the real one. */
    private static final double REFERENCE_FPS = 60.0;
    /** Strength 100 % = this much of the previous frame kept per 1/60 s. */
    private static final double MAX_REFERENCE_BLEND = 0.9;
    private static final float MAX_BLEND = 0.985f;

    private static PostChain chain;
    private static GpuBuffer uniformBuffer;
    private static CrossFrameResourcePool resourcePool;
    private static Projection projection;
    private static ProjectionMatrixBuffer projectionBuffer;
    private static boolean failed;
    private static boolean mixinWarned;

    private static boolean primed;
    private static float lastWrittenBlend = Float.NaN;
    private static long lastFrameNanos;
    private static double smoothedFrameSeconds = 1.0 / REFERENCE_FPS;
    private static ClientLevel lastLevel;
    private static int lastWidth = -1;
    private static int lastHeight = -1;
    private static boolean lastBlurGui;

    private MotionBlurRenderer() {
    }

    /** Called from both GameRenderer hooks; {@code afterGui} says which one. */
    public static void onFrame(boolean afterGui, boolean renderLevelArg) {
        try {
            MotionBlurConfig cfg = MotionBlurConfig.getInstance();
            if (!cfg.isEnabled()) {
                failed = false; // re-enabling retries a previous failure
                if (chain != null) {
                    closeChain(); // free the two full-screen targets while off
                }
                resetHistory();
                return;
            }
            if (failed || cfg.isBlurGui() != afterGui) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            // Same condition GameRenderer.render uses to decide whether the level was drawn this frame.
            boolean levelDrawn = renderLevelArg && mc.isGameLoadFinished() && mc.level != null;
            if (!levelDrawn || cfg.getStrength() <= 0) {
                resetHistory();
                return;
            }

            RenderTarget main = mc.getMainRenderTarget();
            if (main == null || main.width <= 0 || main.height <= 0) {
                resetHistory();
                return;
            }
            if (mc.level != lastLevel || main.width != lastWidth || main.height != lastHeight
                    || cfg.isBlurGui() != lastBlurGui) {
                lastLevel = mc.level;
                lastWidth = main.width;
                lastHeight = main.height;
                lastBlurGui = cfg.isBlurGui();
                primed = false;
            }

            if (chain == null && !buildChain(mc)) {
                return;
            }

            float blend = primed ? computeBlend(cfg.getStrength()) : 0.0f;
            if (!primed) {
                lastFrameNanos = 0L;
            }
            writeBlend(blend);

            chain.process(main, resourcePool);
            resourcePool.endFrame();
            primed = true;
        } catch (Throwable t) {
            fail("Motion blur render failed", t);
        }
    }

    private static float computeBlend(int strengthPercent) {
        long now = System.nanoTime();
        if (lastFrameNanos != 0L) {
            double dt = (now - lastFrameNanos) / 1.0e9;
            dt = Math.max(1.0 / 1000.0, Math.min(0.25, dt));
            // Light smoothing so frame-pacing jitter at high FPS doesn't make the trail length flicker.
            smoothedFrameSeconds += (dt - smoothedFrameSeconds) * 0.2;
        }
        lastFrameNanos = now;

        double referenceBlend = (strengthPercent / 100.0) * MAX_REFERENCE_BLEND;
        // Keep the per-second decay constant: blend_fps = blend_ref ^ (refFps / fps).
        double blend = Math.pow(referenceBlend, smoothedFrameSeconds * REFERENCE_FPS);
        if (!Double.isFinite(blend)) {
            return 0.0f;
        }
        return (float) Math.max(0.0, Math.min(MAX_BLEND, blend));
    }

    private static void writeBlend(float blend) {
        if (blend == lastWrittenBlend) {
            return;
        }
        try (GpuBuffer.MappedView view = RenderSystem.getDevice().createCommandEncoder()
                .mapBuffer(uniformBuffer, false, true)) {
            Std140Builder.intoBuffer(view.data()).putVec4(blend, 0.0f, 0.0f, 0.0f);
        }
        lastWrittenBlend = blend;
    }

    private static boolean buildChain(Minecraft mc) {
        PostChain built = null;
        try {
            PostChainConfig config = readConfig(mc);
            if (config == null) {
                return false;
            }
            if (projection == null) {
                projection = new Projection();
                projectionBuffer = new ProjectionMatrixBuffer("killer560smod motion blur");
            }
            built = PostChain.load(config, mc.getTextureManager(), LevelTargetBundle.MAIN_TARGETS, CHAIN_ID,
                    projection, projectionBuffer);

            List<PostPass> passes;
            try {
                passes = ((PostChainAccessor) built).killer560smod$getPasses();
            } catch (ClassCastException e) {
                built.close();
                warnMixinMissing();
                return false;
            }

            GpuDevice device = RenderSystem.getDevice();
            for (PostPass pass : passes) {
                RenderPipeline pipeline = ((PostPassAccessor) pass).killer560smod$getPipeline();
                CompiledRenderPipeline compiled = device.precompilePipeline(pipeline);
                if (compiled == null || !compiled.isValid()) {
                    built.close();
                    fail("Motion blur shader failed to compile (" + pipeline.getLocation() + ")", null);
                    return false;
                }
            }

            GpuBuffer buffer = device.createBuffer(() -> "killer560smod motion blur config",
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_SIZE);
            boolean installed = false;
            for (PostPass pass : passes) {
                Map<String, GpuBuffer> uniforms = ((PostPassAccessor) pass).killer560smod$getCustomUniforms();
                if (!uniforms.containsKey(UNIFORM_BLOCK)) {
                    continue;
                }
                GpuBuffer old = uniforms.put(UNIFORM_BLOCK, buffer);
                if (old != null && old != buffer) {
                    old.close();
                }
                installed = true;
                break; // only the first pass declares the block; one owner keeps close() single
            }
            if (!installed) {
                buffer.close();
                built.close();
                fail("Motion blur uniform block missing from the effect", null);
                return false;
            }

            if (resourcePool == null) {
                resourcePool = new CrossFrameResourcePool(3);
            }
            chain = built;
            uniformBuffer = buffer; // closed by chain.close() via PostPass.close()
            lastWrittenBlend = Float.NaN;
            primed = false;
            return true;
        } catch (Throwable t) {
            if (built != null) {
                try {
                    built.close();
                } catch (Throwable ignored) {
                }
            }
            if (t instanceof ClassCastException) {
                warnMixinMissing();
            } else {
                fail("Motion blur effect failed to load", t);
            }
            return false;
        }
    }

    private static PostChainConfig readConfig(Minecraft mc) throws Exception {
        Optional<Resource> resource = mc.getResourceManager().getResource(CHAIN_JSON);
        if (resource.isEmpty()) {
            fail("Motion blur effect file not found (" + CHAIN_JSON + ")", null);
            return null;
        }
        JsonElement json;
        try (Reader reader = resource.get().openAsReader()) {
            json = JsonParser.parseReader(reader);
        }
        DataResult<PostChainConfig> parsed = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, json);
        Optional<PostChainConfig> result = parsed.result();
        if (result.isEmpty()) {
            String msg = parsed.error().map(DataResult.Error::message).orElse("unknown error");
            fail("Motion blur effect file is invalid: " + msg, null);
            return null;
        }
        return result.get();
    }

    private static void warnMixinMissing() {
        if (!mixinWarned) {
            mixinWarned = true;
            LOGGER.warn("Motion blur accessors did not apply; feature disabled");
        }
        disableWithNotice();
    }

    private static void fail(String message, Throwable t) {
        if (t != null) {
            LOGGER.error(message, t);
        } else {
            LOGGER.error(message);
        }
        disableWithNotice();
    }

    private static void disableWithNotice() {
        failed = true;
        closeChain();
        MotionBlurConfig cfg = MotionBlurConfig.getInstance();
        if (cfg.isEnabled()) {
            cfg.setEnabled(false);
            cfg.save();
        }
        try {
            ModChat.send("Motion Blur", ModChat.bad("Failed to load, disabled."));
        } catch (Throwable ignored) {
        }
    }

    private static void resetHistory() {
        primed = false;
        lastFrameNanos = 0L;
    }

    private static void closeChain() {
        PostChain old = chain;
        chain = null;
        uniformBuffer = null;
        lastWrittenBlend = Float.NaN;
        primed = false;
        if (old != null) {
            try {
                old.close();
            } catch (Throwable ignored) {
            }
        }
        // The pool's cached "swap" target (full-screen colour + depth) is only aged out by endFrame(), which
        // never runs again while the feature is off - free it now instead of holding it until re-enable.
        if (resourcePool != null) {
            try {
                resourcePool.clear();
            } catch (Throwable ignored) {
            }
        }
    }
}
