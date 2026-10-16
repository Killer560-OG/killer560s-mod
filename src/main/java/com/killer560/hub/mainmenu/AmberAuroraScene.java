package com.killer560.hub.mainmenu;

import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;

/**
 * Amber's "Aurora" main menu (killer560, 2026-10-16: his own picture of an orange aurora over a Minecraft valley at
 * sunset, "try taking that and editing the video for it ... make sure [the aurora lines] do line up for the render of
 * it"). Amber's Fancy scene in every instance since he approved it (2026-10-16, with the Fancy/Simple toggle); the old
 * embers stay reachable as {@code MainMenuScenes.AmberScene.EMBERS}.
 * <p>
 * The base is {@code textures/gui/amber_aurora.png}, built by {@code killer560s-mod-logs/aurora-picture/make_aurora.py}
 * from his picture with the baked-in title and buttons removed and every aurora band followed across the holes they left.
 * It is 2560x2880 (killer560, 2026-10-16: "make the high res version"; the softer, less streaky look he picked): the
 * picture on top at 2560x1440, its animation maps below at the same size, sampled linearly ({@code .mcmeta} blur, and the
 * pipeline's own LINEAR sampler). The shader and the overlays work in 1920x1080 picture units whatever the texture size. One full-screen quad draws it through the
 * {@code killer560smod:pipeline/amber_aurora} shader ({@code shaders/core/amber_aurora.fsh}), sampled LINEAR, so all of
 * the motion is smooth at full resolution: the curtains ripple (travelling waves move the sky a few pixels sideways and up
 * and down, nothing on the land), each ray flickers softly on its own phase, light travels slowly along the bands, the
 * stars twinkle in place, the low sun's glow breathes, glints drift on the river and the lava pulses. The view never sways.
 * The clock rides in the quad's vertex colour as a 24-bit fraction of a 600 s loop whose every motion is a whole number of
 * turns, so it wraps without a jump. A few soft embers drift up at the sides over it ({@link PictureScene} overlays).
 * <p>
 * If the pipeline is unavailable (VulkanMod, a failure) the picture is drawn still with the embers over it.
 */
final class AmberAuroraScene {

    private static final Logger LOGGER = ModLog.get("killer560smod-mainmenu");

    private AmberAuroraScene() {
    }

    /** The picture's units (positions in the shader and the overlays). */
    static final int TW = 1920;
    static final int TH = 1080;
    /** The texture's own size: the picture at 2560x1440 with its maps below. */
    static final int TEX_W = 2560;
    static final int TEX_H = 2880;
    /** The shader's loop, seconds; every motion in amber_aurora.fsh is a whole number of turns of it. */
    static final double LOOP = 600.0;

    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("killer560smod", "textures/gui/amber_aurora.png");

    private static RenderPipeline pipeline;
    private static boolean failed;
    private static GpuTextureView setupView;
    private static TextureSetup setup;

    /** Test probe (testkit 1695): frames drawn through the shader, and frames drawn still because it was unavailable. */
    public static long shaderFrames;
    public static long stillFrames;

    /** A few soft embers drifting up at the sides, over the land and the lower sky; never across the title. */
    private static final PictureScene OVERLAYS = PictureScene.of("textures/gui/amber_aurora.png", TW, TH)
            .overlay(new PictureScene.Motes(22, 30, 520, 1400, 500, 1060, 560, 0.018, 0.026, 3, 0xFFFFB45A, 1691, true));

    /** Registers the pipeline at client init, so it is in RenderPipelines' static list before the shaders precompile. */
    static void register() {
        if (pipeline != null || failed) {
            return;
        }
        if (FabricLoader.getInstance().isModLoaded("vulkanmod")) {
            failed = true;
            return;
        }
        try {
            Identifier shader = Identifier.fromNamespaceAndPath("killer560smod", "core/amber_aurora");
            pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/amber_aurora"))
                    .withVertexShader(shader)
                    .withFragmentShader(shader)
                    .build());
        } catch (RuntimeException | LinkageError e) {
            fail("pipeline registration", e);
        }
    }

    private static void fail(String what, Throwable e) {
        if (!failed) {
            failed = true;
            LOGGER.warn("[MainMenu] Amber's aurora shader disabled ({} failed): {}; the picture is drawn still", what, e.toString());
        }
    }

    /** The scene clock as the shader reads it: a 24-bit fraction of the loop in the colour's rgb, alpha full. */
    static int timeColour(double t) {
        return timeColour(t, LOOP);
    }

    /** The same for a shader whose loop is {@code loop} seconds (Ocean's falls). */
    static int timeColour(double t, double loop) {
        double phase = ((t % loop) + loop) % loop / loop;
        int code = (int) Math.min(0xFFFFFF, Math.floor(phase * 16_777_216.0));
        return 0xFF000000 | code;
    }

    /** The picture, before the frame's batch. */
    static void drawImage(GuiGraphicsExtractor g, int w, int h, double t) {
        if (pipeline != null && !failed) {
            try {
                AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(TEXTURE);
                GpuTextureView view = tex.getTextureView();
                if (setup == null || view != setupView) {
                    setupView = view;
                    setup = TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                }
                g.guiRenderState.addGuiElement(new BlitRenderState(pipeline, setup, new Matrix3x2f(g.pose()), 0, 0, w, h,
                        0f, 1f, 0f, 0.5f, timeColour(t), g.scissorStack.peek()));
                shaderFrames++;
                return;
            } catch (RuntimeException | LinkageError e) {
                fail("draw", e);
            }
        }
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.scale(w / (float) TW, h / (float) TH);
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, 0f, 0f, TW, TH, TEX_W, TEX_H / 2, TEX_W, TEX_H);
        pose.popMatrix();
        stillFrames++;
    }

    /** The embers, into the frame's batch. */
    static void drawOverlays(GuiRects r, int w, int h, double t, float density) {
        OVERLAYS.drawOverlays(r, w, h, t, density);
    }

    /** Whether the shader is in use (testkit 1695). */
    public static boolean shaderActive() {
        return pipeline != null && !failed;
    }
}
