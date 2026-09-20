package com.killer560.hub.hud;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.Window;

import java.util.ArrayList;
import java.util.List;

public final class HudElementRegistry {

    private static final List<HudElement> ELEMENTS = new ArrayList<>();

    private HudElementRegistry() {
    }

    public static void register(HudElement element) {
        ELEMENTS.add(element);
    }

    /** Removes a previously registered element (e.g. a GIF that's been toggled off) so it stops
     *  showing up in the HUD editor and being rendered. */
    public static void unregister(String id) {
        ELEMENTS.removeIf(e -> e.id().equals(id));
        CLAMP_MEMOS.remove(id);
    }

    public static List<HudElement> all() {
        return ELEMENTS;
    }

    /** @return the registered element with this id, or null. Indexed loop rather than a Stream because the
     *  per-frame Gui hooks look their own element up on every single frame (2026-09-20, FPS pass) - a
     *  filter/findFirst pipeline there allocates a Stream, an Optional and a capturing lambda each time. */
    public static HudElement byId(String id) {
        for (int i = 0; i < ELEMENTS.size(); i++) {
            HudElement e = ELEMENTS.get(i);
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
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
        HudConfig cfg = HudConfig.getInstance();
        boolean saved = cfg.hasPosition(element.id());
        int[] pos = cfg.getPosition(element.id(), element.defaultX(), element.defaultY());
        try {
            return saved ? rescueIfInvisible(element, pos) : memoisedClamp(element, pos);
        } catch (RuntimeException e) {
            // A window that isn't ready yet or a width()/height() that throws must never break rendering -
            // fall back to the raw position, exactly what this method returned before the clamp existed.
            return pos;
        }
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
     * by re-measuring at most once per {@link #CLAMP_REFRESH_MS} - and not at all while the element is not
     * relevant right now (its feature off / wrong floor), because an element that is not drawing cannot be
     * growing either. So a switched-off element measures itself once and then costs a map lookup and four
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
        int[] screen = screenSize();
        if (screen == null) {
            return pos;
        }
        float scale = resolveScale(element);
        ClampMemo memo = CLAMP_MEMOS.get(element.id());
        if (memo != null && memo.rawX == pos[0] && memo.rawY == pos[1] && memo.scale == scale
                && memo.screenW == screen[0] && memo.screenH == screen[1]
                // Within the TTL nothing needs re-measuring; beyond it, only an element that is actually
                // live (or being previewed in the HUD editor, where a disabled element draws demo content
                // and so does have a size) can have changed size.
                && (System.currentTimeMillis() - memo.measuredAtMs < CLAMP_REFRESH_MS
                    || (!editorOpen() && !isRelevantNow(element)))) {
            return memo.result;
        }
        if (memo == null) {
            memo = new ClampMemo();
            CLAMP_MEMOS.put(element.id(), memo);
        }
        memo.rawX = pos[0];
        memo.rawY = pos[1];
        memo.scale = scale;
        memo.screenW = screen[0];
        memo.screenH = screen[1];
        memo.measuredAtMs = System.currentTimeMillis();
        memo.result = clampIntoScreen(element, pos, screen);
        return memo.result;
    }

    public static float resolveScale(HudElement element) {
        return HudConfig.getInstance().getScale(element.id(), 1.0f);
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
        return client != null && client.screen instanceof HudEditorScreen;
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
        int[] screen = screenSize();
        if (screen == null) {
            return pos;
        }
        // Top-left corner on screen means at least that pixel is visible - skip width()/height() (which for some
        // elements builds their whole line list) on this per-frame path unless the corner is actually outside.
        if (pos[0] >= 0 && pos[0] < screen[0] && pos[1] >= 0 && pos[1] < screen[1]) {
            return pos;
        }
        int[] size = scaledSize(element);
        boolean visible = pos[0] < screen[0] && pos[0] + size[0] > 0 && pos[1] < screen[1] && pos[1] + size[1] > 0;
        return visible ? pos : clampIntoScreen(element, pos);
    }

    /** Whether {@code element} should be listed in the HUD editor right now; an exception counts as yes so a
     *  broken check can never hide an element from the editor (see {@link HudElement#isRelevantNow()}). */
    public static boolean isRelevantNow(HudElement element) {
        try {
            return element.isRelevantNow();
        } catch (RuntimeException e) {
            return true;
        }
    }
}
