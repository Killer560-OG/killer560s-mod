package com.killer560.hub.scoreboard;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real background blur behind the Custom Scoreboard - the approach of SkyBlock Custom Scoreboard's
 * {@code BlurredBackground} (meowdding), simplified so it needs no custom uniform block:
 * <ol>
 * <li>While the HUD is extracted, {@link #submit} adds one {@link ColoredRectangleRenderState} per row of the rounded
 * box with the {@code killer560smod:pipeline/scoreboard_blur} pipeline. The blur radius (framebuffer pixels) rides in
 * the vertex colour's red channel, so the stock {@code DynamicTransforms}/{@code Projection} uniforms are all it
 * uses. Those elements are added before the background fill, so {@code GuiRenderState}'s overlap layering keeps the
 * fill and text above them.</li>
 * <li>{@code CustomScoreboardBlurMixin} copies the main render target's colour texture (the world, before any GUI is
 * drawn) into {@link #target} at the head of {@code GuiRenderer#draw}, before its first render pass opens - only on
 * frames that submitted a blur.</li>
 * <li>{@code shaders/core/scoreboard_blur.fsh} samples that copy around {@code gl_FragCoord} with a small gaussian
 * kernel. Only the scoreboard rectangle is ever touched, so the rest of the HUD is unaffected.</li>
 * </ol>
 * A runtime failure (texture copy rejected, resize rejected, the {@code GuiRenderer} hook not applying, VulkanMod)
 * turns the blur off for the session and logs once; the scoreboard then just draws without it.
 * <p>
 * <b>Not</b> covered: because {@link RenderPipelines#register} puts the pipeline in
 * {@code RenderPipelines.getStaticPipelines()}, {@code ShaderManager#apply} precompiles it with every vanilla pipeline
 * during the resource reload and throws {@code RuntimeException("Failed to compile pipelines: ...")} if it fails. A
 * broken/missing {@code scoreboard_blur.vsh}/{@code .fsh} therefore fails the whole resource reload instead of just
 * disabling the blur - keep the two shaders valid GLSL 330 that matches vanilla's core-shader format.
 */
public final class ScoreboardBlur {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-customscoreboard");

    private static RenderPipeline pipeline;
    private static TextureTarget target;
    private static TextureSetup setup = null;
    private static volatile boolean needsCopy = false;
    /** Set once a copy really happened; until then the quads are submitted with alpha 0 (the shader discards them). */
    private static volatile boolean copyConfirmed = false;
    private static int missedCopies = 0;
    private static boolean failed = false;

    private ScoreboardBlur() {
    }

    /** Registers the pipeline at client init so it's in {@code RenderPipelines}' static list before precompiling. */
    static void register() {
        if (pipeline != null || failed) {
            return;
        }
        if (FabricLoader.getInstance().isModLoaded("vulkanmod")) {
            failed = true;
            LOGGER.info("[CustomScoreboard] VulkanMod detected, background blur disabled.");
            return;
        }
        try {
            Identifier shader = Identifier.fromNamespaceAndPath("killer560smod", "core/scoreboard_blur");
            pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/scoreboard_blur"))
                    .withVertexShader(shader)
                    .withFragmentShader(shader)
                    .withSampler("Sampler0")
                    .build());
        } catch (RuntimeException | LinkageError e) {
            fail("pipeline registration", e);
        }
    }

    public static boolean available() {
        return pipeline != null && !failed;
    }

    private static void fail(String what, Throwable e) {
        if (!failed) {
            failed = true;
            LOGGER.warn("[CustomScoreboard] Background blur disabled ({} failed): {}", what, e.toString());
        }
    }

    /**
     * Queues the blur for a rounded rectangle in the current pose (GUI units). {@code strength} 1-20 is scaled by the
     * GUI scale into framebuffer pixels.
     */
    static void submit(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int strength) {
        if (!available() || w <= 0 || h <= 0) {
            return;
        }
        if (needsCopy) {
            // The previous frame's request was never consumed: the GuiRenderer hook isn't running.
            if (++missedCopies >= 3) {
                fail("GuiRenderer hook", new IllegalStateException("framebuffer copy never ran"));
                return;
            }
        } else {
            missedCopies = 0;
        }
        try {
            Minecraft client = Minecraft.getInstance();
            RenderTarget main = client.getMainRenderTarget();
            if (target == null || target.width != main.width || target.height != main.height) {
                // Resized here (extraction), never in GuiRenderer#draw, so no queued element still holds an old view.
                if (target == null) {
                    target = new TextureTarget("killer560smod scoreboard blur", main.width, main.height, false);
                } else {
                    target.resize(main.width, main.height);
                }
                RenderSystem.getDevice().createCommandEncoder().clearColorTexture(target.getColorTexture(), 0);
                setup = null;
                // The copy for the new size hasn't happened yet: hide the quads for this frame rather than risk
                // drawing the cleared (opaque black) texture if the copy below doesn't run.
                copyConfirmed = false;
            }
            if (setup == null) {
                setup = TextureSetup.singleTexture(target.getColorTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
            double guiScale = client.getWindow().getGuiScale();
            Matrix3x2f pose = new Matrix3x2f(g.pose());
            float poseScale = (float) Math.sqrt(Math.abs(pose.determinant()));
            int radiusPx = (int) Math.round(Math.max(1, Math.min(20, strength)) * guiScale * Math.max(0.25f, poseScale));
            // MainTarget#allocateColorAttachment returns null when the GPU is out of memory, and copyIfNeeded then
            // skips the copy - so check here, while the quads can still be submitted invisible, not after.
            int alpha = copyConfirmed && main.getColorTexture() != null ? 0xFF000000 : 0;
            int color = alpha | (Math.max(1, Math.min(255, radiusPx)) << 16);
            for (int row = 0; row < h; row++) {
                int in = CustomScoreboardFeature.roundedInset(row, h, r);
                int rowEnd = row + 1;
                if (in == 0) {
                    // Merge the straight middle section into one quad.
                    while (rowEnd < h && CustomScoreboardFeature.roundedInset(rowEnd, h, r) == 0) {
                        rowEnd++;
                    }
                }
                g.guiRenderState.addGuiElement(new ColoredRectangleRenderState(pipeline, setup, pose,
                        x + in, y + row, x + w - in, y + rowEnd, color, color, g.scissorStack.peek()));
                row = rowEnd - 1;
            }
            needsCopy = true;
        } catch (RuntimeException | LinkageError e) {
            fail("submit", e);
        }
    }

    /** Called at the head of {@code GuiRenderer#draw}, before its render passes open. */
    public static void copyIfNeeded() {
        if (!needsCopy) {
            return;
        }
        needsCopy = false;
        if (failed || target == null) {
            return;
        }
        try {
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            if (main.width != target.width || main.height != target.height
                    || main.getColorTexture() == null || target.getColorTexture() == null) {
                // Nothing was copied: keep the quads invisible until one really lands, so the next frames can't
                // sample a stale or cleared (opaque black) texture.
                copyConfirmed = false;
                return;
            }
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(main.getColorTexture(),
                    target.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            copyConfirmed = true;
        } catch (RuntimeException | LinkageError e) {
            fail("framebuffer copy", e);
        }
    }
}
