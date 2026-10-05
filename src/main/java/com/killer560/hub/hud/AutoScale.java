package com.killer560.hub.hud;

import com.killer560.hub.compat.McCompat;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Auto Scale (monitor): makes everything the mod draws take up the same FRACTION of the window on any monitor and at
 * any Minecraft GUI Scale, using killer560's own setup as the reference.
 * <p>
 * killer560 (2026-10-05): "make it detect the size of the monitor and auto scale based off of the monitor size as
 * well", "everything related to the mod", Auto ON by default, and "I do most of my testing on my middle monitor and I
 * like the size of everything for that monitor so make sure you kind of use that as the baseline." That monitor is
 * 2560x1440 and every one of his instances runs GUI Scale 3, so at 2560x1440 / GUI 3 the factor is exactly 1.0 and
 * nothing changes for him, pixel for pixel.
 * <p>
 * <b>Why the GUI scale is divided out.</b> Every HUD element and every screen is laid out in GUI pixels, and one GUI
 * pixel is already {@code guiScale} framebuffer pixels - Minecraft's own GUI Scale is applied before this mod draws
 * anything. So a box {@code w} GUI pixels wide is {@code w * guiScale} real pixels, which is
 * {@code w * guiScale / windowHeight} of the window. On the baseline that is {@code w * 3 / 1440}. To keep that
 * fraction on a window of height {@code H} at GUI scale {@code g} the box must be drawn {@code F} times larger, where
 * {@code w * F * g / H = w * 3 / 1440}, i.e. {@code F = 3 * (H / 1440) / g}. Multiplying by the window ratio alone
 * would double-scale: with Minecraft's "Auto" GUI scale, a 4K window already gets GUI 9 instead of 6, and a ratio on
 * top of that would make the mod 1.5x too big again. Dividing by the EFFECTIVE scale ({@link Window#getGuiScale()},
 * the number Minecraft actually renders with, not the option value, which is 0 for Auto) cancels exactly what
 * Minecraft already did, whatever the option says.
 * <p>
 * <b>Width or height.</b> The window ratio is {@code min(W / 2560, H / 1440)}. On a normal 16:9 window the two are
 * equal. On an ultrawide (3440x1440) height wins and the factor stays 1.0 - the extra width is just more room, the
 * way it is for vanilla. On a narrow or tall window (1280x1024, a window snapped to half a screen) width wins, so the
 * baseline layout - 853x480 GUI pixels - always fits inside the window: {@code (2560 / 3) * F <= W / g} follows
 * directly from taking the min. Height alone would push right-anchored HUDs and wide menus off the side there.
 * <p>
 * <b>Dead band and floor.</b> A factor within {@link #DEAD_BAND} of 1.0 is 1.0: a maximised (not fullscreen) window
 * on his 1440p monitor is about 2560x1361, a 5% difference nobody can see, and a non-integer scale softens Minecraft's
 * bitmap font for no visible gain. The factor is also never allowed below {@code 1 / guiScale}, so a font pixel never
 * gets smaller than a screen pixel on a tiny window.
 * <p>
 * The window's real framebuffer size is used, not the monitor's: a windowed game scales to its window. It is the same
 * {@code getWidth()/getHeight()} pair the rest of the mod reads.
 */
public final class AutoScale {

    /** The baseline: killer560's middle monitor at GUI Scale 3. Factor 1.0 there by definition. */
    public static final int REFERENCE_WIDTH = 2560;
    public static final int REFERENCE_HEIGHT = 1440;
    public static final int REFERENCE_GUI_SCALE = 3;
    /** |factor - 1| below this snaps to exactly 1.0 (see the class comment). */
    public static final float DEAD_BAND = 0.06f;

    /** The factor each open mod screen was laid out with. The mouse and the render pose must use THAT number, not a
     *  freshly computed one, or a click lands on a layout the screen does not have. Weak so closed screens go. */
    private static final Map<Screen, Float> SCREEN_FACTORS = new WeakHashMap<>();

    private AutoScale() {
    }

    /**
     * The pure scale math, with no Minecraft state: framebuffer size and the effective GUI scale in, factor out.
     * Unit-tested by the testkit ({@code 2560x1440 @3 = 1.0}, {@code 1920x1080 @3 = 0.75}, {@code 3840x2160 @3 = 1.5},
     * {@code 2560x1440 @2 = 1.5}, {@code 2560x1440 @4 = 0.75}).
     */
    public static float factor(int framebufferWidth, int framebufferHeight, int guiScale) {
        if (framebufferWidth <= 0 || framebufferHeight <= 0 || guiScale <= 0) {
            return 1.0f;
        }
        double ratio = Math.min(framebufferWidth / (double) REFERENCE_WIDTH,
                framebufferHeight / (double) REFERENCE_HEIGHT);
        double f = REFERENCE_GUI_SCALE * ratio / guiScale;
        if (Math.abs(f - 1.0) < DEAD_BAND) {
            return 1.0f;
        }
        return (float) Math.max(f, 1.0 / guiScale);
    }

    /** The factor right now, or 1.0 when Auto Scale is off or there is no window yet. Cheap: four int reads. */
    public static float current() {
        if (!HudConfig.getInstance().isAutoScale()) {
            return 1.0f;
        }
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        if (window == null) {
            return 1.0f;
        }
        return factor(window.getWidth(), window.getHeight(), window.getGuiScale());
    }

    /**
     * Which screens are scaled: the mod's own, meaning every screen class under {@code com.killer560.hub}. Vanilla
     * screens the mod only decorates (container overlays) are not - their mod parts are HUD elements and scale through
     * {@link HudElementRegistry#resolveScale}. The HUD editor is the one exception: its boxes ARE the in-game HUD, in
     * the game's own GUI coordinates, so it must stay unscaled to show and drag elements exactly where they draw.
     */
    public static boolean scalesScreen(Screen screen) {
        return screen != null && !(screen instanceof HudEditorScreen)
                && screen.getClass().getName().startsWith("com.killer560.hub.");
    }

    /** Called by the Screen mixin as it lays a screen out: decides and remembers that screen's factor. */
    public static float layoutFactor(Screen screen) {
        float f = scalesScreen(screen) ? current() : 1.0f;
        synchronized (SCREEN_FACTORS) {
            SCREEN_FACTORS.put(screen, f);
        }
        return f;
    }

    /** The factor {@code screen} was last laid out with; 1.0 if it never was (or is not one of ours). */
    public static float appliedFactor(Screen screen) {
        if (screen == null) {
            return 1.0f;
        }
        Float f;
        synchronized (SCREEN_FACTORS) {
            f = SCREEN_FACTORS.get(screen);
        }
        return f == null ? 1.0f : f;
    }

    /** True when {@code screen}'s layout is stale - Auto Scale was toggled while it was open, typically from the
     *  very menu that holds the toggle - so it must be laid out again before it draws. */
    public static boolean needsRelayout(Screen screen) {
        float want = scalesScreen(screen) ? current() : 1.0f;
        return want != appliedFactor(screen);
    }

    /** The factor of whatever screen is open now, for converting the mouse into that screen's coordinates. */
    public static float openScreenFactor() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? 1.0f : appliedFactor(McCompat.screen(client));
    }

    /** Screen-space size in GUI pixels divided by the factor, rounded UP so the scaled layout still reaches the far
     *  edge (rounding down left a one-pixel strip uncovered on the right/bottom). */
    public static int layoutSize(int guiPixels, float factor) {
        if (factor == 1.0f || factor <= 0f) {
            return guiPixels;
        }
        return (int) Math.ceil(guiPixels / factor);
    }
}
