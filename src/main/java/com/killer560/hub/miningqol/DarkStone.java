package com.killer560.hub.miningqol;

import com.killer560.hub.miningqol.mixin.SpriteContentsMipsAccessor;
import com.killer560.hub.miningqol.mixin.TextureAtlasRedrawInvoker;
import com.killer560.hub.util.ModLog;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dark Stone (legit, both builds; killer560 2026-11-04: "add a stone dark mode option as well. a texture pack that makes
 * all stuff dark mode", then "ores must match the stone around them"). It re-skins the textures the player already has
 * loaded, so it follows whatever pack is active, and there is no per-vertex or per-frame work at all:
 * <ul>
 *   <li>Stone-type textures (stone, cobblestone, andesite, granite, diorite, deepslate, tuff, ice ...) get every pixel
 *   multiplied by the darkness factor.</li>
 *   <li>Ore textures (coal, iron, gold, lapis, redstone, emerald, diamond, copper, plain and deepslate) get only the pixels
 *   that belong to the stone behind the ore darkened, the same way as the stone itself, so the ore matches the stone around
 *   it: a pixel counts as stone when it is close to the host stone texture's pixel at the same place, or has low saturation
 *   and is close to one of the host's colors. Colored specks (and coal's near-black ones) stay as they are.</li>
 *   <li>Titanium (polished diorite), gemstones and every other texture are not touched.</li>
 * </ul>
 * How: the block atlas' sprites keep their images ({@code SpriteContents.byMipLevel}); the darkened copies are swapped in and
 * {@code TextureAtlas.uploadInitialContents} redraws the atlas from them (the same step the load does, no resource reload).
 * Switching off, or leaving the mining island, swaps the originals back. A resource reload builds a fresh atlas, which is
 * noticed (the sprite object changes) and re-skinned. Animated sprites are skipped: the game redraws their frames itself
 * every few ticks. While active the block-atlas stone also shows in inventory icons and held blocks, which use the same atlas.
 *
 * <p>Tried first and dropped: tinting vertex colors in the mesher (it cannot leave an ore's specks alone, and Fabric's
 * terrain renderer bypasses vanilla's quad output so it needed a hook on every vertex the game draws).
 */
public final class DarkStone {

    private static final Logger LOGGER = ModLog.get("killer560smod-darkstone");

    /** Stone-type textures (block atlas names without the "block/" prefix). Polished diorite is Titanium: not here. */
    private static final String[] STONE = {"stone", "cobblestone", "smooth_stone", "andesite", "polished_andesite", "diorite",
        "granite", "polished_granite", "deepslate", "deepslate_top", "cobbled_deepslate", "polished_deepslate",
        "deepslate_bricks", "tuff", "packed_ice", "blue_ice", "clay"};

    /** Mithril textures: only with Darken Mithril Too. */
    private static final String[] MITHRIL = {"gray_wool", "light_blue_wool", "prismarine_bricks", "dark_prismarine",
        "cyan_terracotta"};

    private static final String[] ORE_NAMES = {"coal", "iron", "gold", "lapis", "redstone", "emerald", "diamond", "copper"};

    private static volatile boolean on = false;
    private static String lastKey = "";
    private static volatile boolean forceIsland = false;
    private static int redraws = 0;
    private static long lastApplyNanos = 0;

    /** Sprites currently showing a darkened copy: {original images, darkened images}. */
    private static final Map<TextureAtlasSprite, NativeImage[][]> APPLIED = new LinkedHashMap<>();
    private static TextureAtlas appliedAtlas;
    private static TextureAtlasSprite guardSprite;

    private DarkStone() {
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        boolean en = cfg.isDarkStone() && (MiningState.onMiningIsland() || forceIsland);
        int lv = cfg.getDarkStoneLevel();
        boolean mi = cfg.isDarkStoneMithril();
        if (!APPLIED.isEmpty() && staleAtlas(client)) {
            forget();
            lastKey = "";
        }
        String key = en + "|" + lv + "|" + mi;
        if (key.equals(lastKey)) {
            return;
        }
        if (en && client.level == null) {
            return;
        }
        lastKey = key;
        try {
            long t0 = System.nanoTime();
            restore(client);
            if (en) {
                apply(client, 1.0f - lv / 100.0f, mi);
            }
            lastApplyNanos = System.nanoTime() - t0;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[DarkStone] re-skin failed: {}", e.toString());
            forget();
        }
        on = en && !APPLIED.isEmpty();
    }

    private static TextureAtlas blockAtlas(Minecraft client) {
        AbstractTexture t = client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        return t instanceof TextureAtlas a ? a : null;
    }

    private static boolean staleAtlas(Minecraft client) {
        TextureAtlas atlas = blockAtlas(client);
        return atlas == null || atlas != appliedAtlas
                || atlas.getSprite(Identifier.withDefaultNamespace("block/stone")) != guardSprite;
    }

    private static TextureAtlasSprite sprite(TextureAtlas atlas, String name) {
        TextureAtlasSprite s = atlas.getSprite(Identifier.withDefaultNamespace("block/" + name));
        return s == atlas.missingSprite() ? null : s;
    }

    private static void apply(Minecraft client, float factor, boolean mithril) {
        TextureAtlas atlas = blockAtlas(client);
        if (atlas == null) {
            return;
        }
        // every darkened set is built from the ORIGINAL images first, then all are swapped in together
        Map<TextureAtlasSprite, NativeImage[]> plan = new LinkedHashMap<>();
        List<String> stoneNames = new ArrayList<>(List.of(STONE));
        if (mithril) {
            stoneNames.addAll(List.of(MITHRIL));
        }
        for (String n : stoneNames) {
            TextureAtlasSprite s = sprite(atlas, n);
            if (s != null && !s.contents().isAnimated()) {
                plan.put(s, darkenedStone(mips(s), factor));
            }
        }
        String[] prefixes = {"", "deepslate_"};
        String[] hosts = {"stone", "deepslate"};
        for (int i = 0; i < prefixes.length; i++) {
            TextureAtlasSprite host = sprite(atlas, hosts[i]);
            if (host == null) {
                continue;
            }
            for (String ore : ORE_NAMES) {
                TextureAtlasSprite s = sprite(atlas, prefixes[i] + ore + "_ore");
                if (s != null && !s.contents().isAnimated()) {
                    plan.put(s, darkenedOre(mips(s), mips(host), factor));
                }
            }
        }
        if (plan.isEmpty()) {
            return;
        }
        for (Map.Entry<TextureAtlasSprite, NativeImage[]> e : plan.entrySet()) {
            SpriteContentsMipsAccessor acc = (SpriteContentsMipsAccessor) (Object) e.getKey().contents();
            APPLIED.put(e.getKey(), new NativeImage[][] {acc.killer560smod$getMips(), e.getValue()});
            acc.killer560smod$setMips(e.getValue());
        }
        appliedAtlas = atlas;
        guardSprite = atlas.getSprite(Identifier.withDefaultNamespace("block/stone"));
        redraw(atlas);
    }

    private static void restore(Minecraft client) {
        if (APPLIED.isEmpty()) {
            return;
        }
        boolean same = !staleAtlas(client);
        TextureAtlas atlas = appliedAtlas;
        for (Map.Entry<TextureAtlasSprite, NativeImage[][]> e : APPLIED.entrySet()) {
            if (same) {
                ((SpriteContentsMipsAccessor) (Object) e.getKey().contents()).killer560smod$setMips(e.getValue()[0]);
            }
            for (NativeImage img : e.getValue()[1]) {
                if (img != null && !img.isClosed()) {
                    img.close();
                }
            }
        }
        APPLIED.clear();
        if (same) {
            redraw(atlas);
        }
        appliedAtlas = null;
        guardSprite = null;
    }

    /** The atlas was rebuilt by a reload (its old sprites and our copies went with it): just drop the records. */
    private static void forget() {
        APPLIED.clear();
        appliedAtlas = null;
        guardSprite = null;
        on = false;
    }

    private static void redraw(TextureAtlas atlas) {
        ((TextureAtlasRedrawInvoker) (Object) atlas).killer560smod$redraw();
        redraws++;
    }

    private static NativeImage[] mips(TextureAtlasSprite s) {
        return ((SpriteContentsMipsAccessor) (Object) s.contents()).killer560smod$getMips();
    }

    private static int dark(int argb, float f) {
        int a = argb >>> 24;
        int r = Math.round(((argb >> 16) & 255) * f);
        int g = Math.round(((argb >> 8) & 255) * f);
        int b = Math.round((argb & 255) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static NativeImage[] darkenedStone(NativeImage[] src, float f) {
        NativeImage[] out = new NativeImage[src.length];
        for (int m = 0; m < src.length; m++) {
            NativeImage in = src[m];
            NativeImage o = new NativeImage(in.getWidth(), in.getHeight(), false);
            for (int y = 0; y < in.getHeight(); y++) {
                for (int x = 0; x < in.getWidth(); x++) {
                    o.setPixel(x, y, dark(in.getPixel(x, y), f));
                }
            }
            out[m] = o;
        }
        return out;
    }

    private static NativeImage[] darkenedOre(NativeImage[] ore, NativeImage[] host, float f) {
        NativeImage[] out = new NativeImage[ore.length];
        for (int m = 0; m < ore.length; m++) {
            NativeImage in = ore[m];
            NativeImage h = m < host.length ? host[m] : null;
            NativeImage o = new NativeImage(in.getWidth(), in.getHeight(), false);
            for (int y = 0; y < in.getHeight(); y++) {
                for (int x = 0; x < in.getWidth(); x++) {
                    int p = in.getPixel(x, y);
                    o.setPixel(x, y, isStone(p, h, x, y, in.getWidth(), in.getHeight()) ? dark(p, f) : p);
                }
            }
            out[m] = o;
        }
        return out;
    }

    /** Does this ore pixel belong to the stone behind the ore? */
    static boolean isStone(int p, NativeImage host, int x, int y, int w, int hgt) {
        if ((p >>> 24) < 8 || host == null) {
            return false;
        }
        if (host.getWidth() == w && host.getHeight() == hgt && dist(p, host.getPixel(x, y)) <= 14) {
            return true;
        }
        if (saturation(p) >= 0.15f) {
            return false;
        }
        int best = Integer.MAX_VALUE;
        for (int yy = 0; yy < host.getHeight(); yy++) {
            for (int xx = 0; xx < host.getWidth(); xx++) {
                best = Math.min(best, dist(p, host.getPixel(xx, yy)));
            }
        }
        return best <= 16;
    }

    private static int dist(int a, int b) {
        int dr = Math.abs(((a >> 16) & 255) - ((b >> 16) & 255));
        int dg = Math.abs(((a >> 8) & 255) - ((b >> 8) & 255));
        int db = Math.abs((a & 255) - (b & 255));
        return Math.max(dr, Math.max(dg, db));
    }

    private static float saturation(int p) {
        int r = (p >> 16) & 255;
        int g = (p >> 8) & 255;
        int b = p & 255;
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        return mx == 0 ? 0f : (mx - mn) / (float) mx;
    }

    /** Testkit: treat the world as a mining island (no Skyblock in the harness). */
    public static void forceIslandForTest(boolean v) {
        forceIsland = v;
    }

    public static boolean isOn() {
        return on;
    }

    /** Sprites showing a darkened copy right now. */
    public static long darkenedCount() {
        return APPLIED.size();
    }

    /** Atlas redraws done (one per switch, level change or island change). */
    public static int remeshCount() {
        return redraws;
    }

    /** Time of the last apply or restore (the atlas redraw), in microseconds. */
    public static long lastApplyMicros() {
        return lastApplyNanos / 1000L;
    }
}
