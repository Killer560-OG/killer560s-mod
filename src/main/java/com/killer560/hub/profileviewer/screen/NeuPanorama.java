package com.killer560.hub.profileviewer.screen;

import com.killer560.hub.chocolatefactory.egg.HoppityCalendar;
import com.killer560.hub.mainmenu.ThemeAssets;
import com.killer560.hub.mainmenu.WebpPictures;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * NEU Classic's Basic page background (pv-styles-exact-1124k): the player stands in front of a panorama of the island
 * their status shows (day or night by the SkyBlock hour, as the original picks it), turning slowly and dragged round by
 * the mouse. The pictures are not in the jar: they are the {@code pv-panorama} group of {@link ThemeAssets}, and only the
 * shown set's six faces are asked for. Until all six are in the cache nothing is drawn (the page's own plain background
 * shows). Decoding runs off the render thread; a frame does one 4x4 transform of 726 precomputed points and reuses its
 * six elements. The textures go when the screen closes ({@link #release}).
 */
final class NeuPanorama {

    private static final Logger LOGGER = ModLog.get("killer560smod-profileviewer");
    private static final int GRID = 10; // each face is a 10 x 10 grid of quads on the sphere, as the original splits it
    private static final int PTS = (GRID + 1) * (GRID + 1);
    /** The sets that exist (the original's 24), by name; a mode without its own set shows "unknown". */
    private static final java.util.Set<String> SETS = java.util.Set.of("combat_1_day", "combat_1_night", "combat_3_day",
            "combat_3_night", "crimson_isle", "crystal_hollows", "dark_auction", "dungeon", "dungeon_hub", "dynamic_day",
            "dynamic_night", "farming_1_day", "farming_1_night", "farming_2_day", "farming_2_night", "foraging_1_day",
            "foraging_1_night", "hub_day", "hub_night", "mining_1_day", "mining_1_night", "mining_2", "mining_3", "unknown");
    /** The sphere's points per face, already turned to that face: x y z u v. */
    private static final float[][] FACE_PTS = new float[6][];

    static {
        Matrix4f r = new Matrix4f();
        org.joml.Vector3f v = new org.joml.Vector3f();
        for (int k = 0; k < 6; k++) {
            r.identity();
            switch (k) {
                case 1 -> r.rotateY((float) Math.toRadians(90));
                case 2 -> r.rotateY((float) Math.toRadians(180));
                case 3 -> r.rotateY((float) Math.toRadians(-90));
                case 4 -> r.rotateX((float) Math.toRadians(90));
                case 5 -> r.rotateX((float) Math.toRadians(-90));
                default -> { }
            }
            float[] f = FACE_PTS[k] = new float[PTS * 5];
            for (int j = 0; j <= GRID; j++) {
                for (int i = 0; i <= GRID; i++) {
                    float u = i / (float) GRID, w = j / (float) GRID;
                    float xr = u * 2 - 1, yr = w * 2 - 1;
                    float s = (float) Math.sqrt(3 / (xr * xr + yr * yr + 1)); // on a sphere of radius sqrt(3)
                    r.transformPosition(v.set(xr * s, yr * s, s));
                    int o = (j * (GRID + 1) + i) * 5;
                    f[o] = v.x;
                    f[o + 1] = v.y;
                    f[o + 2] = v.z;
                    f[o + 3] = u;
                    f[o + 4] = w;
                }
            }
        }
    }

    private String shownSet;
    private String[] names; // the six asset names of shownSet
    private CompletableFuture<NativeImage[]> decoding;
    private DynamicTexture[] textures;
    private final Face[] faces = new Face[6];
    private final Map<String, String[]> namesBySet = new HashMap<>();
    private final Matrix4f cam = new Matrix4f();
    private final float[] proj = new float[PTS * 3]; // screen x, y and w of one face's points
    private String lastMode = "";
    private String lastKey = "unknown";
    private boolean lastNight;
    private float rotation;
    private long lastMs = -1;
    private int clickedX = -1;
    private int bx, by, bw, bh = -1;
    private int drawn; // frames drawn (testkit)

    /** Draws the panorama into (x, y, w, h) for the status mode {@code mode} ("" = unknown). Render thread. */
    void draw(GuiGraphicsExtractor g, int x, int y, int w, int h, String mode, int mx, int my) {
        long now = net.minecraft.util.Util.getMillis();
        bx = x;
        by = y;
        bw = w;
        bh = h;
        // BasicPage: it turns time/400 degrees on its own, and follows the mouse while a button held down over it drags it.
        int extra = 0;
        if (clickedX == -1) {
            if (lastMs >= 0) rotation += (now - lastMs) / 400f;
        } else {
            extra = mx - clickedX;
        }
        rotation %= 360;
        lastMs = now;

        String set = setFor(mode);
        if (!set.equals(shownSet)) {
            release();
            shownSet = set;
            names = namesBySet.computeIfAbsent(set, s -> {
                String[] n = new String[6];
                for (int i = 0; i < 6; i++) n[i] = "pvpano_" + s + "_" + i + ".webp";
                return n;
            });
        }
        if (textures == null && !load()) return;
        float angle = -rotation - extra;
        // The original's view: a 97 degree perspective drawn turned a quarter (its viewport is the box on its side), then
        // three turns, the eye moved (0, 0.37, 0.8), and the sphere spun by the angle about its up axis.
        cam.identity().perspective((float) Math.toRadians(97), h / (float) w, 0.05f, 10f)
                .rotateX((float) Math.PI).rotateZ((float) Math.toRadians(90)).rotateX((float) Math.toRadians(19))
                .translate(0, 0.37f, 0.8f).rotateY((float) Math.toRadians(angle));
        g.enableScissor(x, y, x + w, y + h);
        ScreenRectangle scissor = g.scissorStack.peek();
        for (int k = 0; k < 6; k++) {
            Face f = faces[k];
            float[] pts = FACE_PTS[k];
            for (int p = 0; p < PTS; p++) {
                int o = p * 5;
                float px = pts[o], py = pts[o + 1], pz = pts[o + 2];
                float cx = cam.m00() * px + cam.m10() * py + cam.m20() * pz + cam.m30();
                float cy = cam.m01() * px + cam.m11() * py + cam.m21() * pz + cam.m31();
                float cw = cam.m03() * px + cam.m13() * py + cam.m23() * pz + cam.m33();
                // the box shows the viewport transposed: screen x from its y, screen y from its x
                proj[p * 3] = x + w * (1 - cy / cw) / 2;
                proj[p * 3 + 1] = y + h * (1 - cx / cw) / 2;
                proj[p * 3 + 2] = cw;
            }
            f.fill(g, proj, pts, x, y, w, h, scissor);
            if (f.n > 0) g.guiRenderState.addGuiElement(f);
        }
        g.disableScissor();
        drawn++;
    }

    /** A left or right press: starts a drag when it is inside the box last drawn. */
    void pressed(int mx, int my) {
        if (clickedX == -1 && mx > bx && mx < bx + bw && my > by && my < by + bh) clickedX = mx;
    }

    /** The button let go: the drag is kept. */
    void released(int mx) {
        if (clickedX == -1) return;
        rotation = (rotation + mx - clickedX) % 360;
        clickedX = -1;
    }

    /** Asks for the six faces, decodes them in the background once all are cached, and uploads them; true when ready. */
    private boolean load() {
        if (decoding == null) {
            Path[] files = new Path[6];
            for (int i = 0; i < 6; i++) {
                ThemeAssets.request(names[i]); // a map lookup once known (unpublished and missing: looked again in 2 min)
                if ((files[i] = ThemeAssets.ready(names[i])) == null) return false;
            }
            decoding = CompletableFuture.supplyAsync(() -> {
                NativeImage[] out = new NativeImage[6];
                try {
                    for (int i = 0; i < 6; i++) out[i] = WebpPictures.decode(Files.readAllBytes(files[i]));
                    return out;
                } catch (Exception e) {
                    for (NativeImage im : out) if (im != null) im.close();
                    LOGGER.warn("[ProfileViewer] could not read a panorama of {}: {}", shownSet, e.toString());
                    return null;
                }
            });
            return false;
        }
        if (!decoding.isDone()) return false;
        NativeImage[] images = decoding.getNow(null);
        if (images == null) return false; // a file that will not decode leaves the plain background for this screen
        textures = new DynamicTexture[6];
        for (int i = 0; i < 6; i++) {
            DynamicTexture t = textures[i] = new DynamicTexture(() -> "killer560smod pv panorama", images[i]);
            t.upload();
            faces[i] = new Face(TextureSetup.singleTexture(t.getTextureView(),
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)));
        }
        return true;
    }

    /** The set the original shows for {@code mode}: "<mode>_day"/"_night" (night from 8 PM to 6 AM SkyBlock time), then
     *  "<mode>", then "unknown". Cached while the mode and the half of the day stay the same. */
    private String setFor(String mode) {
        long hour = Math.floorMod(System.currentTimeMillis() - HoppityCalendar.EPOCH_MS, HoppityCalendar.DAY_MS) / HoppityCalendar.HOUR_MS;
        boolean night = hour <= 6 || hour >= 20;
        if (mode == null) mode = "";
        if (!mode.equals(lastMode) || night != lastNight) {
            String timed = mode + (night ? "_night" : "_day");
            lastKey = SETS.contains(timed) ? timed : SETS.contains(mode) ? mode : "unknown";
            lastMode = mode;
            lastNight = night;
        }
        return lastKey;
    }

    /** Frees the textures (screen closed, or another set shown). */
    void release() {
        if (decoding != null && textures == null) {
            decoding.thenAccept(images -> {
                if (images != null) for (NativeImage im : images) im.close();
            });
        }
        if (textures != null) {
            for (DynamicTexture t : textures) t.close();
        }
        decoding = null;
        textures = null;
        shownSet = null;
    }

    /** Testkit: the set shown, whether its textures are up, and the frames drawn with them. */
    String stateForTest() {
        return shownSet + (textures != null ? " ready" : " waiting") + " drawn=" + drawn + " rotation=" + rotation;
    }

    /** One face's visible quads, reused every frame (filled, then handed to the frame's render state). */
    private static final class Face implements GuiElementRenderState {
        private final TextureSetup tex;
        private final Matrix3x2f pose = new Matrix3x2f();
        private float[] q = new float[GRID * GRID * 16];
        private int n;
        private ScreenRectangle scissor;
        private ScreenRectangle bounds;

        Face(TextureSetup tex) {
            this.tex = tex;
        }

        void fill(GuiGraphicsExtractor g, float[] proj, float[] pts, int bx, int by, int bw, int bh, ScreenRectangle scissor) {
            pose.set(g.pose());
            this.scissor = scissor;
            n = 0;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int j = 0; j < GRID; j++) {
                for (int i = 0; i < GRID; i++) {
                    int a = j * (GRID + 1) + i, b = a + 1, c = a + GRID + 2, d = a + GRID + 1;
                    if (proj[a * 3 + 2] < 0.05f || proj[b * 3 + 2] < 0.05f || proj[c * 3 + 2] < 0.05f || proj[d * 3 + 2] < 0.05f) {
                        continue; // behind the eye
                    }
                    float l = Math.min(Math.min(proj[a * 3], proj[b * 3]), Math.min(proj[c * 3], proj[d * 3]));
                    float r = Math.max(Math.max(proj[a * 3], proj[b * 3]), Math.max(proj[c * 3], proj[d * 3]));
                    float t = Math.min(Math.min(proj[a * 3 + 1], proj[b * 3 + 1]), Math.min(proj[c * 3 + 1], proj[d * 3 + 1]));
                    float bo = Math.max(Math.max(proj[a * 3 + 1], proj[b * 3 + 1]), Math.max(proj[c * 3 + 1], proj[d * 3 + 1]));
                    if (r < bx || l > bx + bw || bo < by || t > by + bh) continue; // outside the box
                    // one winding on screen whichever way the face is seen
                    float cross = (proj[b * 3] - proj[a * 3]) * (proj[d * 3 + 1] - proj[a * 3 + 1])
                            - (proj[b * 3 + 1] - proj[a * 3 + 1]) * (proj[d * 3] - proj[a * 3]);
                    int o = n * 16;
                    put(o, proj, pts, a);
                    put(o + 4, proj, pts, cross > 0 ? d : b);
                    put(o + 8, proj, pts, c);
                    put(o + 12, proj, pts, cross > 0 ? b : d);
                    n++;
                    minX = Math.min(minX, l);
                    maxX = Math.max(maxX, r);
                    minY = Math.min(minY, t);
                    maxY = Math.max(maxY, bo);
                }
            }
            if (n == 0) return;
            int l = (int) Math.floor(minX), t = (int) Math.floor(minY);
            ScreenRectangle b = new ScreenRectangle(l, t, Math.max(1, (int) Math.ceil(maxX) - l), Math.max(1, (int) Math.ceil(maxY) - t))
                    .transformMaxBounds(pose);
            bounds = scissor != null ? scissor.intersection(b) : b;
            if (bounds == null) n = 0;
        }

        private void put(int o, float[] proj, float[] pts, int p) {
            q[o] = proj[p * 3];
            q[o + 1] = proj[p * 3 + 1];
            q[o + 2] = pts[p * 5 + 3];
            q[o + 3] = pts[p * 5 + 4];
        }

        @Override
        public void buildVertices(VertexConsumer c) {
            for (int i = 0; i < n * 4; i++) {
                int o = i * 4;
                c.addVertexWith2DPose(pose, q[o], q[o + 1]).setUv(q[o + 2], q[o + 3]).setColor(0xFFFFFFFF);
            }
        }

        @Override
        public ScreenRectangle bounds() { return bounds; }

        @Override
        public RenderPipeline pipeline() { return RenderPipelines.GUI_TEXTURED; }

        @Override
        public TextureSetup textureSetup() { return tex; }

        @Override
        public ScreenRectangle scissorArea() { return scissor; }
    }
}
