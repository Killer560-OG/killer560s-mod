package com.killer560.hub.mainmenu;

import com.killer560.hub.compat.McRender;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.Random;

/**
 * The main menu scenes' own shaders (killer560, 2026-10-16: "make that one super high quality and 120fps"): what a vertex
 * colour cannot do. A {@code GuiRects} gradient is interpolated in the GPU and then rounded to the 8-bit framebuffer once per
 * layer, so a faint slow gradient (a ray fading down a near-white sky, a glow's falloff) shows as contour bands, and nothing
 * drawn with vertex colours can be dithered. Three pipelines, all {@link RenderPipelines#GUI_TEXTURED_SNIPPET} (position, uv,
 * colour, {@code Sampler0}) with this mod's shaders in {@code assets/killer560smod/shaders/core/}:
 * <ul>
 *   <li>{@code scene_soft_light}: Light's whole Soft Light backdrop in one full-screen quad - the base gradient, the four
 *       slanted rays with smooth sides, the title glow and the stepless vignette, composited in float and dithered once with
 *       blue noise at the end, so the 8-bit result has no bands at all;</li>
 *   <li>{@code scene_soft_disc}: one drifting light, an analytically antialiased disc (exact coverage at any GUI scale and any
 *       sub-pixel position). The glow and vignette above it are an affine map of the colour, so the shader applies them to
 *       the disc's own colour, which is exactly the result of drawing the disc under them;</li>
 *   <li>{@code scene_cone_glow}: a radial glow (Viridian's slime glow and fog), the stepped glow's nested ellipses averaged
 *       into one smooth falloff, its alpha dithered with blue noise.</li>
 * </ul>
 * Parameters ride in a small data texture ({@link #data}): rows 0-63 are a 64x64 blue-noise tile (void-and-cluster,
 * generated once at startup), rows 64-71 four slots of 128 floats, each float stored bit-exact as one RGBA8 texel and read
 * back with {@code uintBitsToFloat}. A slot is written and uploaded once per scene draw; the vertex shader reads it into flat
 * varyings, so no fragment fetches parameters.
 * <p>
 * Registered at client init ({@link #register}) so {@code ShaderManager} precompiles them with vanilla's pipelines (a broken
 * shader fails the resource reload; see {@code ScoreboardBlur}). Off with VulkanMod, or when anything fails: {@link #available}
 * goes false and the scenes draw their {@code GuiRects} versions.
 */
public final class SceneShaders {

    private static final Logger LOGGER = ModLog.get("killer560smod-mainmenu");

    private SceneShaders() {
    }

    static RenderPipeline softLight;
    static RenderPipeline softDisc;
    static RenderPipeline coneGlow;
    private static boolean failed;

    /** Blue noise, 64x64, 0..255, generated at {@link #register}. */
    private static byte[] noise;

    static final int N = 64;
    static final int SLOTS = 4;
    static final int SLOT_FLOATS = 128;

    private static DynamicTexture data;
    private static TextureSetup setup;
    private static int nextSlot;

    /** Test hook (testkit 1684/1686): false draws the GuiRects versions, as if the shaders were unavailable. */
    public static boolean enabledForTest = true;

    /** Test probe: shader elements submitted since the last reset (the testkit's proof the shader path ran). */
    public static int elements;

    public static void register() {
        if (softLight != null || failed) {
            return;
        }
        if (FabricLoader.getInstance().isModLoaded("vulkanmod")) {
            failed = true;
            return;
        }
        try {
            noise = blueNoise(N, 560L);
            softLight = pipeline("scene_soft_light");
            softDisc = pipeline("scene_soft_disc");
            coneGlow = pipeline("scene_cone_glow");
        } catch (RuntimeException | LinkageError e) {
            fail("pipeline registration", e);
        }
    }

    private static RenderPipeline pipeline(String name) {
        return RenderPipelines.register(RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("killer560smod", "pipeline/" + name))
                .withVertexShader(Identifier.fromNamespaceAndPath("killer560smod", "core/" + name))
                .withFragmentShader(Identifier.fromNamespaceAndPath("killer560smod", "core/" + name))
                .build());
    }

    static boolean available() {
        return enabledForTest && softLight != null && !failed;
    }

    static void fail(String what, Throwable e) {
        if (!failed) {
            failed = true;
            LOGGER.warn("[MainMenu] scene shaders off ({} failed): {}", what, e.toString());
        }
    }

    // ---- the data texture ---------------------------------------------------------------------------------------------

    private static final float[] SLOT = new float[SLOT_FLOATS];

    /** The scratch slot a draw fills (then {@link #upload}). Render thread only. */
    static float[] slot() {
        Arrays.fill(SLOT, 0f);
        return SLOT;
    }

    /** Writes the scratch slot into the next of the four and uploads the texture; returns that slot's index, or -1. */
    static int upload() {
        if (!ensure()) {
            return -1;
        }
        try {
            int s = nextSlot;
            nextSlot = (nextSlot + 1) % SLOTS;
            NativeImage img = data.getPixels();
            for (int i = 0; i < SLOT_FLOATS; i++) {
                // bit-exact: the shader reassembles r | g << 8 | b << 16 | a << 24 (setPixelABGR puts the low byte in red)
                img.setPixelABGR(i % N, N + s * 2 + i / N, Float.floatToRawIntBits(SLOT[i]));
            }
            data.upload();
            return s;
        } catch (RuntimeException | LinkageError e) {
            fail("data texture", e);
            return -1;
        }
    }

    /** Creates the data texture (the blue noise in it) on first use; false when that failed. */
    static boolean ensure() {
        if (data != null) {
            return true;
        }
        try {
            {
                NativeImage img = new NativeImage(N, N + SLOTS * 2, false);
                for (int y = 0; y < N; y++) {
                    for (int x = 0; x < N; x++) {
                        int v = noise[y * N + x] & 0xFF;
                        img.setPixelABGR(x, y, 0xFF000000 | (v << 16) | (v << 8) | v);
                    }
                }
                data = new DynamicTexture(() -> "killer560smod scene data", img);
                setup = TextureSetup.singleTexture(data.getTextureView(),
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            fail("data texture", e);
            return false;
        }
    }

    static TextureSetup textureSetup() {
        return setup;
    }

    // ---- elements -----------------------------------------------------------------------------------------------------

    /** A run of textured quads (x0 y0 x1 y1 u0 v0 u1 v1 colour) in one pose, submitted as one element. */
    static final class Quads implements GuiElementRenderState {
        private final RenderPipeline pipeline;
        private final TextureSetup tex;
        private final Matrix3x2fc pose;
        private float[] q = new float[9 * 8];
        private int n;
        private final ScreenRectangle scissor;
        private float minX = Float.MAX_VALUE;
        private float minY = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;

        Quads(RenderPipeline pipeline, GuiGraphicsExtractor g) {
            this.pipeline = pipeline;
            this.tex = setup;
            this.pose = new Matrix3x2f(g.pose());
            this.scissor = g.scissorStack.peek();
        }

        Quads add(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int colour) {
            if (n * 9 + 9 > q.length) {
                q = Arrays.copyOf(q, q.length * 2);
            }
            int o = n * 9;
            q[o] = x0;
            q[o + 1] = y0;
            q[o + 2] = x1;
            q[o + 3] = y1;
            q[o + 4] = u0;
            q[o + 5] = v0;
            q[o + 6] = u1;
            q[o + 7] = v1;
            q[o + 8] = Float.intBitsToFloat(colour);
            n++;
            minX = Math.min(minX, Math.min(x0, x1));
            minY = Math.min(minY, Math.min(y0, y1));
            maxX = Math.max(maxX, Math.max(x0, x1));
            maxY = Math.max(maxY, Math.max(y0, y1));
            return this;
        }

        /** Hands the quads to the frame's render state (nothing when empty or scissored away). */
        void submit(GuiGraphicsExtractor g) {
            if (n == 0 || tex == null) {
                return;
            }
            ScreenRectangle b = bounds();
            if (b == null) {
                return;
            }
            g.guiRenderState.addGuiElement(this);
            elements++;
        }

        @Override
        public void buildVertices(VertexConsumer c) {
            for (int i = 0; i < n; i++) {
                int o = i * 9;
                float x0 = q[o];
                float y0 = q[o + 1];
                float x1 = q[o + 2];
                float y1 = q[o + 3];
                float u0 = q[o + 4];
                float v0 = q[o + 5];
                float u1 = q[o + 6];
                float v1 = q[o + 7];
                int col = Float.floatToRawIntBits(q[o + 8]);
                // BlitRenderState's order (javap 26.1.2 and 26.2)
                c.addVertexWith2DPose(pose, x0, y0).setUv(u0, v0).setColor(col);
                c.addVertexWith2DPose(pose, x0, y1).setUv(u0, v1).setColor(col);
                c.addVertexWith2DPose(pose, x1, y1).setUv(u1, v1).setColor(col);
                c.addVertexWith2DPose(pose, x1, y0).setUv(u1, v0).setColor(col);
            }
        }

        private ScreenRectangle bounds;

        @Override
        public ScreenRectangle bounds() {
            if (bounds == null) {
                int l = (int) Math.floor(minX);
                int t = (int) Math.floor(minY);
                ScreenRectangle b = new ScreenRectangle(l, t, Math.max(1, (int) Math.ceil(maxX) - l),
                        Math.max(1, (int) Math.ceil(maxY) - t)).transformMaxBounds(pose);
                bounds = scissor != null ? scissor.intersection(b) : b;
            }
            return bounds;
        }

        @Override
        public RenderPipeline pipeline() {
            return pipeline;
        }

        @Override
        public TextureSetup textureSetup() {
            return tex;
        }

        @Override
        public ScreenRectangle scissorArea() {
            return scissor;
        }
    }

    // ---- blue noise ---------------------------------------------------------------------------------------------------

    /**
     * A {@code n} x {@code n} tileable blue-noise threshold map by Ulichney's void-and-cluster (Gaussian energy, sigma 1.5,
     * toroidal): every value 0..255 equally often, neighbours as different as possible, so dithering with it adds no visible
     * pattern. About 30 ms once.
     */
    static byte[] blueNoise(int n, long seed) {
        int total = n * n;
        float[] lut = new float[total];
        double sigma = 1.5;
        for (int dy = 0; dy < n; dy++) {
            for (int dx = 0; dx < n; dx++) {
                int ddx = Math.min(dx, n - dx);
                int ddy = Math.min(dy, n - dy);
                lut[dy * n + dx] = (float) Math.exp(-(ddx * ddx + ddy * ddy) / (2 * sigma * sigma));
            }
        }
        boolean[] init = new boolean[total];
        float[] e0 = new float[total];
        Random rnd = new Random(seed);
        int ones = total / 10;
        for (int placed = 0; placed < ones; ) {
            int p = rnd.nextInt(total);
            if (!init[p]) {
                init[p] = true;
                splat(e0, lut, n, p, 1);
                placed++;
            }
        }
        // relax the initial pattern: move the tightest cluster into the largest void until they coincide
        for (int guard = 0; guard < total * 4; guard++) {
            int c = extreme(e0, init, true, true);
            init[c] = false;
            splat(e0, lut, n, c, -1);
            int v = extreme(e0, init, false, false);
            init[v] = true;
            splat(e0, lut, n, v, 1);
            if (v == c) {
                break;
            }
        }
        int[] rank = new int[total];
        boolean[] b = init.clone();
        float[] e = e0.clone();
        for (int r = ones - 1; r >= 0; r--) {
            int c = extreme(e, b, true, true);
            b[c] = false;
            splat(e, lut, n, c, -1);
            rank[c] = r;
        }
        b = init.clone();
        e = e0.clone();
        for (int r = ones; r < total; r++) {
            int v = extreme(e, b, false, false);
            b[v] = true;
            splat(e, lut, n, v, 1);
            rank[v] = r;
        }
        byte[] out = new byte[total];
        for (int i = 0; i < total; i++) {
            out[i] = (byte) (rank[i] * 256 / total);
        }
        return out;
    }

    private static void splat(float[] e, float[] lut, int n, int p, int sign) {
        int px = p % n;
        int py = p / n;
        for (int y = 0; y < n; y++) {
            int row = ((y - py) & (n - 1)) * n;
            int o = y * n;
            for (int x = 0; x < n; x++) {
                e[o + x] += sign * lut[row + ((x - px) & (n - 1))];
            }
        }
    }

    /** The index with the largest ({@code max}) or smallest energy among pixels whose state is {@code set}. */
    private static int extreme(float[] e, boolean[] b, boolean set, boolean max) {
        int best = -1;
        float bv = max ? -Float.MAX_VALUE : Float.MAX_VALUE;
        for (int i = 0; i < e.length; i++) {
            if (b[i] != set) {
                continue;
            }
            if (max ? e[i] > bv : e[i] < bv) {
                bv = e[i];
                best = i;
            }
        }
        return best;
    }
}
