package com.killer560.hub.hud;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

public final class HudElementRegistry {

    private static final List<HudElement> ELEMENTS = new ArrayList<>();
    /** id -> the FIRST registered element with that id (what the old linear {@link #byId} scan returned). */
    private static final java.util.Map<String, HudElement> BY_ID = new java.util.HashMap<>();
    /** Bumped on every register/unregister, so a per-frame caller can cache what it derives from {@link #all()}. */
    private static int version;

    private HudElementRegistry() {
    }

    public static void register(HudElement element) {
        ELEMENTS.add(element);
        BY_ID.putIfAbsent(element.id(), element);
        version++;
    }

    /** Removes a previously registered element (e.g. a GIF that's been toggled off) so it stops
     *  showing up in the HUD editor and being rendered. */
    public static void unregister(String id) {
        ELEMENTS.removeIf(e -> e.id().equals(id));
        BY_ID.remove(id);
        version++;
        CLAMP_MEMOS.remove(id);
        HudSeen.forget(id);
    }

    public static List<HudElement> all() {
        return ELEMENTS;
    }

    /** Changes whenever the element list does. */
    public static int version() {
        return version;
    }

    /** @return the registered element with this id, or null. A map lookup: the per-frame HUD layers look their
     *  own element up every frame, and the linear scan over ~100 elements that replaced a Stream here on
     *  2026-09-20 was still 1.5% of the render thread in the 95-fps-bench JFR (2026-10-05). */
    public static HudElement byId(String id) {
        return BY_ID.get(id);
    }

    /**
     * Top-left position to draw {@code element} at.
     * <p>
     * killer560 (2026-09-16): "for all the random huds that you have make sure all of them are on screen the
     * first time they are rendered." Most elements' {@code defaultX/Y} are fixed pixel rows (x=10, y up to 500)
     * chosen for GUI scale 2 on 1080p; at scale 3/4 or on a small window those land below the bottom edge,
     * and a HUD you cannot see is one you cannot drag back. So a position the player has NOT saved is clamped
     * fully into the visible screen (element's scaled width/height taken into account) every time it is
     * resolved - not persisted, so it keeps tracking window-size / GUI-scale changes until the player drags it.
     * A position the player did save is returned untouched: if they parked something half off-screen on
     * purpose, that is theirs to keep. The one exception is a saved position whose box is 100% outside the
     * current screen (typically saved at a larger GUI size, then the window shrank) - that is pulled back into
     * view, again without persisting, so it can still be grabbed in the editor.
     */
    public static int[] resolvePosition(HudElement element) {
        int[] laid;
        try {
            laid = element.layoutPosition();
        } catch (RuntimeException e) {
            laid = null; // a broken layout falls back to the saved position rather than breaking the HUD
        }
        if (laid != null) {
            return laid;
        }
        return ownPosition(element);
    }

    /** {@link #resolvePosition} without the element's {@link HudElement#layoutPosition()}: its saved (or default)
     *  position, clamped as usual. For a layoutPosition() that starts from where the element would otherwise be. */
    public static int[] ownPosition(HudElement element) {
        HudConfig cfg = HudConfig.getInstance();
        boolean saved = cfg.hasPosition(element.id());
        int[] pos = cfg.getPosition(element.id(), element.defaultX(), element.defaultY());
        try {
            if (saved) {
                // Auto Scale (2026-10-05): a saved position is in BASELINE GUI pixels - the units of his 2560x1440 /
                // GUI 3 monitor, where it was dragged - and is drawn at that times the factor, so the layout keeps
                // its relative placement and every element stays the same fraction of the window. Where the factor
                // is not 1 the scaled box is clamped fully on screen (memoised, so no per-frame width() calls):
                // positions placed on a different screen shape must not end up hanging off an edge. At factor 1 the
                // old rule stands - a deliberate half-off-screen placement is his to keep.
                float f = AutoScale.current();
                if (f != 1.0f) {
                    return memoisedClamp(element, Math.round(pos[0] * f), Math.round(pos[1] * f), null);
                }
                return rescueIfInvisible(element, pos);
            }
            // Defaults are NOT multiplied: several are computed from the live screen size already (the Storage
            // Overlay centres itself), and they are clamped on screen below anyway.
            return memoisedClamp(element, pos);
        } catch (RuntimeException e) {
            // A window that isn't ready yet or a width()/height() that throws must never break rendering -
            // fall back to the raw position, exactly what this method returned before the clamp existed.
            return pos;
        }
    }

    /** Inverse of the Auto Scale step in {@link #resolvePosition} ({@code saved * factor}): what the HUD editor
     *  stores for a box it dropped at screen position {@code x,y}, so that drawing it back gives the same spot.
     *  Identity when Auto Scale is off or the factor is 1. */
    public static int[] toSaved(int x, int y) {
        float f = AutoScale.current();
        if (f == 1.0f) {
            return new int[]{x, y};
        }
        return new int[]{toSaved(x, f), toSaved(y, f)};
    }

    /**
     * The saved value that draws back at exactly {@code screen} ({@code Math.round(saved * f) == screen}) when one
     * exists, else the nearest. Below factor 1 plain {@code round(screen / f)} always round-trips; above it not every
     * screen pixel is reachable and plain rounding could land one pixel off a neighbour it was snapped to in the HUD
     * editor (2026-10-07), so the neighbours of the rounded value are tried too.
     */
    static int toSaved(int screen, float f) {
        int s = Math.round(screen / f);
        int best = s;
        int bestErr = Math.abs(Math.round(s * f) - screen);
        for (int c = s - 1; c <= s + 1 && bestErr > 0; c++) {
            int err = Math.abs(Math.round(c * f) - screen);
            if (err < bestErr) {
                best = c;
                bestErr = err;
            }
        }
        return best;
    }

    /**
     * Memo for the never-dragged (unsaved-position) clamp path.
     * <p>
     * 2026-09-20 FPS pass: {@code HudInGameRenderer.draw} resolves the position of all 16 in-game-drawn
     * elements every frame, <em>before</em> any of them gets to check whether its feature is even switched
     * on, and for an element the player has never dragged that lands in {@link #clampIntoScreen}, which
     * calls {@code element.width()} AND {@code element.height()}. For Score Calculator those each run
     * {@code currentLines() -> buildLines()}, and Split Timers' each run {@code p5Lines()} +
     * {@code displayRows()} + {@code coreLines()}: two full line-list rebuilds per frame, per element,
     * <em>while the feature is off</em>. ({@link #rescueIfInvisible} already had a cheap short-circuit for
     * the saved-position path; this is its missing counterpart.)
     * <p>
     * The clamp result can only change when the raw position, the scale, the screen size or the element's
     * own measured size changes. The first three are cheap and are compared exactly; the fourth is covered
     * by re-measuring at most once per {@link #CLAMP_REFRESH_MS} - and not at all while the element has not
     * drawn recently ({@link HudSeen}), because an element that is not drawing cannot be growing either.
     * (That test used to be {@code isRelevantNow()}, a predicate that guessed at the same thing; the draw
     * stamp is the thing itself.) So a switched-off element measures itself once and then costs a map lookup and four
     * int compares per frame, and a live one re-clamps within {@link #CLAMP_REFRESH_MS} of changing size.
     */
    private static final class ClampMemo {
        int rawX;
        int rawY;
        float scale;
        int screenW;
        int screenH;
        int[] result;
        long measuredAtMs;
    }

    private static final long CLAMP_REFRESH_MS = 250L;

    private static final java.util.Map<String, ClampMemo> CLAMP_MEMOS = new java.util.HashMap<>();

    private static int[] memoisedClamp(HudElement element, int[] pos) {
        return memoisedClamp(element, pos[0], pos[1], pos);
    }

    /** {@code pos} may be null: the Auto Scale path passes the scaled x/y as ints and only allocates the array when
     *  the memo misses, so a scaled, unchanged element costs no allocation per frame (same rule as getPosition). */
    private static int[] memoisedClamp(HudElement element, int rawX, int rawY, int[] pos) {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        if (window == null) {
            return pos != null ? pos : new int[]{rawX, rawY};
        }
        // Read straight off the window: screenSize() allocated an array for every element every frame just to compare.
        int screenW = window.getGuiScaledWidth();
        int screenH = window.getGuiScaledHeight();
        float scale = resolveScale(element);
        ClampMemo memo = CLAMP_MEMOS.get(element.id());
        if (memo != null && memo.rawX == rawX && memo.rawY == rawY && memo.scale == scale
                && memo.screenW == screenW && memo.screenH == screenH
                // Within the TTL nothing needs re-measuring; beyond it, only an element that is actually
                // live (or being previewed in the HUD editor, where a disabled element draws demo content
                // and so does have a size) can have changed size.
                && (System.currentTimeMillis() - memo.measuredAtMs < CLAMP_REFRESH_MS
                    || (!editorOpen() && !HudSeen.drawnRecently(element.id())))) {
            return memo.result;
        }
        if (memo == null) {
            memo = new ClampMemo();
            CLAMP_MEMOS.put(element.id(), memo);
        }
        if (pos == null) {
            pos = new int[]{rawX, rawY};
        }
        memo.rawX = rawX;
        memo.rawY = rawY;
        memo.scale = scale;
        memo.screenW = screenW;
        memo.screenH = screenH;
        memo.measuredAtMs = System.currentTimeMillis();
        memo.result = clampIntoScreen(element, pos, new int[]{screenW, screenH});
        return memo.result;
    }

    /**
     * Draws {@code element} in game at its HUD-editor position and scale ({@link #resolvePosition},
     * {@link #resolveScale}): push, translate, scale, {@code render(graphics, 0, 0)}, pop. A throw from the element is
     * swallowed so one broken element never takes the rest of the HUD frame down, and the pose is always popped.
     * The one shared copy of what a dozen draw sites each spelled out by hand until 2026-10-05.
     */
    public static void drawAt(net.minecraft.client.gui.GuiGraphicsExtractor graphics, HudElement element) {
        if (element == null) {
            return;
        }
        int[] pos = resolvePosition(element);
        float scale = resolveScale(element);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            element.render(graphics, 0, 0);
        } catch (RuntimeException e) {
            // One broken element must never take down the whole HUD frame.
        } finally {
            graphics.pose().popMatrix();
        }
    }

    /** The scale {@code element} is DRAWN at: its own scale times the global HUD scale times Auto Scale. Every draw site, the clamp
     *  and the editor's box use this, so they all measure the same box (see the width() lesson in docs/LESSONS.md). */
    public static float resolveScale(HudElement element) {
        return elementScale(element) * HudConfig.getInstance().getEffectiveGlobalScale();
    }

    /** The element's own stored scale, without the global multiplier - what the HUD editor scrolls and saves. */
    public static float elementScale(HudElement element) {
        try {
            float laid = element.layoutScale();
            if (laid > 0f) {
                return laid; // a layout's shared scale (see HudElement#layoutScale)
            }
        } catch (RuntimeException e) {
            // a broken layout falls back to the saved scale, as resolvePosition does for its position
        }
        float fallback;
        try {
            fallback = element.defaultScale();
        } catch (RuntimeException e) {
            fallback = 1.0f;
        }
        return HudConfig.getInstance().getScale(element.id(), fallback);
    }

    /** Scaled on-screen size of {@code element}: {width, height}. */
    private static int[] scaledSize(HudElement element) {
        float scale = resolveScale(element);
        return new int[]{
                Math.max(1, Math.round(element.width() * scale)),
                Math.max(1, Math.round(element.height() * scale))};
    }

    private static boolean editorOpen() {
        Minecraft client = Minecraft.getInstance();
        return client != null && McCompat.screen(client) instanceof HudEditorScreen;
    }

    private static int[] screenSize() {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        if (window == null) {
            return null;
        }
        return new int[]{window.getGuiScaledWidth(), window.getGuiScaledHeight()};
    }

    /** Moves the box so the whole of it is inside the screen; a box larger than the screen pins to the top/left. */
    private static int[] clampIntoScreen(HudElement element, int[] pos) {
        int[] screen = screenSize();
        if (screen == null) {
            return pos;
        }
        return clampIntoScreen(element, pos, screen);
    }

    private static int[] clampIntoScreen(HudElement element, int[] pos, int[] screen) {
        int[] size = scaledSize(element);
        int x = Math.max(0, Math.min(pos[0], screen[0] - size[0]));
        int y = Math.max(0, Math.min(pos[1], screen[1] - size[1]));
        return x == pos[0] && y == pos[1] ? pos : new int[]{x, y};
    }

    /** Leaves a saved position alone unless not one pixel of the box is on screen any more. */
    private static int[] rescueIfInvisible(HudElement element, int[] pos) {
        Minecraft client = Minecraft.getInstance();
        Window window = client == null ? null : client.getWindow();
        if (window == null) {
            return pos;
        }
        // Top-left corner on screen means at least that pixel is visible - skip width()/height() (which for some
        // elements builds their whole line list) on this per-frame path unless the corner is actually outside.
        // (And no screen array for that test: this runs for every saved element every frame at Auto Scale 1.)
        if (pos[0] >= 0 && pos[0] < window.getGuiScaledWidth() && pos[1] >= 0 && pos[1] < window.getGuiScaledHeight()) {
            return pos;
        }
        int[] screen = screenSize();
        int[] size = scaledSize(element);
        boolean visible = pos[0] < screen[0] && pos[0] + size[0] > 0 && pos[1] < screen[1] && pos[1] + size[1] > 0;
        return visible ? pos : clampIntoScreen(element, pos);
    }

    /** Whether {@code element}'s own setting is on; an exception counts as yes so a broken check can never
     *  make an element uneditable (see {@link HudElement#isEnabledInSettings()}). This is only half of what
     *  the HUD editor asks - the other half is {@link HudSeen}, which says whether it has actually drawn. */
    public static boolean isEnabledInSettings(HudElement element) {
        try {
            return element.isEnabledInSettings();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
