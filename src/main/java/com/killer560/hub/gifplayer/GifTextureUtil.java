package com.killer560.hub.gifplayer;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * The two small pieces of GIF-texture handling shared between {@link GifPlayerFeature} (one gif
 * pinned in a HUD corner) and {@code com.killer560.hub.dvd.DvdFeature} (a gif bouncing around the
 * screen) - split out so the hard-won v-flip fix below only has to exist once.
 */
public final class GifTextureUtil {

    /** Writes a decoded frame's pixels into {@code image}, already flipped for {@link #blit}. */
    public static void writeFrame(NativeImage image, int[] argb) {
        int w = image.getWidth();
        int h = image.getHeight();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            // Written into the NativeImage bottom-up (row h-1-y, not y): field-tested (2026-09-03)
            // with a real 3-point A/B/A comparison - v0=1,v1=0 in blit() below is REQUIRED on this
            // engine to get a non-blank render at all (v0=0,v1=1 reliably renders solid white, not
            // just "flipped" - confirmed by reverting it back and seeing the white box return with
            // otherwise-identical, correctly-logged pixel data). Since that mapping alone then
            // renders upside down, pixel rows are pre-flipped here instead of touching blit() again,
            // so the two effects cancel out into a right-side-up image.
            int dstY = h - 1 - y;
            for (int x = 0; x < w; x++) {
                int px = argb[row + x];
                int a = (px >>> 24) & 0xFF;
                int r = (px >>> 16) & 0xFF;
                int g = (px >>> 8) & 0xFF;
                int b = px & 0xFF;
                image.setPixelABGR(x, dstY, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
    }

    /** Draws {@code texture} (already frame-written via {@link #writeFrame}) at (x, y) sized
     *  width x height. */
    public static void blit(GuiGraphicsExtractor graphics, DynamicTexture texture, int x, int y, int width, int height) {
        // The 3rd/4th int params of this blit overload are the SECOND CORNER'S ABSOLUTE POSITION
        // (x1, y1), not a width/height offset - confirmed by re-examining DebugScreenOverlay's own
        // call (from earlier this session's javap work), which passes "x+64, y+64" rather than a
        // literal "64, 64". Field-tested bug (2026-09-05): passing plain width/height here (as if
        // they were an offset) rendered fine for GifPlayerFeature/the HUD editor ONLY because both
        // always draw at a pose-translated local origin (0,0), where "x0+width" and "width" are
        // numerically identical - completely masking the bug there. DvdFeature draws at the box's
        // real moving screen position, which exposed it for real: one corner (whatever literal
        // width/height value was passed) stayed pinned at that fixed screen point every frame while
        // the other corner (the actual moving x,y) swept around it, looking exactly like the box
        // resizing in place instead of translating - reported and confirmed by killer560 in the field.
        // v0=1, v1=0 (not the "obvious" 0,0,1,1) - see writeFrame().
        graphics.blit(texture.getTextureView(), texture.getSampler(), x, y, x + width, y + height, 0f, 1f, 1f, 0f);
    }

    private GifTextureUtil() {
    }
}
