package com.killer560.hub.cape;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Builds the Make Your Own cape as a 64x32 texture at runtime and registers it with the texture manager. Regenerated only when
 * the colours or the pattern change. The back panel of a cape sheet is x 1..10, y 1..16; every other pixel of the sheet takes the
 * colour of the nearest back-panel pixel, so the sides and front match.
 */
public final class CustomCapeTexture {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("killer560smod", "textures/cape/custom.png");

    private static String builtFor = null;

    private CustomCapeTexture() {
    }

    /** Makes sure the texture for the current settings is registered, and returns its id. */
    public static Identifier ensure(CapeConfig cfg) {
        String key = cfg.getCustomPattern().name() + ":" + cfg.getCustomColourA() + ":" + cfg.getCustomColourB();
        if (key.equals(builtFor)) {
            return ID;
        }
        try {
            NativeImage image = new NativeImage(64, 32, false);
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 64; x++) {
                    int u = Math.max(0, Math.min(9, x - 1));
                    int v = Math.max(0, Math.min(15, y - 1));
                    int rgb = colourAt(cfg.getCustomPattern(), cfg.getCustomColourA(), cfg.getCustomColourB(), u, v);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    image.setPixelABGR(x, y, (0xFF << 24) | (b << 16) | (g << 8) | r);
                }
            }
            DynamicTexture texture = new DynamicTexture(() -> "killer560smod custom cape", image);
            Minecraft.getInstance().getTextureManager().register(ID, texture);
            builtFor = key;
        } catch (Exception ignored) {
            builtFor = key; // do not retry every frame
        }
        return ID;
    }

    /** The colour of one back-panel pixel (u 0..9, v 0..15) for a pattern. */
    static int colourAt(CapeConfig.Pattern pattern, int a, int b, int u, int v) {
        switch (pattern) {
            case STRIPES:
                return (v / 3) % 2 == 0 ? a : b;
            case GRADIENT:
                return mix(a, b, v / 15.0);
            case CENTRE_BAND:
                return u >= 3 && u <= 6 ? b : a;
            case SOLID:
            default:
                return a;
        }
    }

    private static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
