package com.killer560.hub.hud;

import com.killer560.hub.compat.McCompat;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;

/**
 * Keeps one HUD element off another that is on screen right now, in game (2026-10-07, killer560's dungeon screenshot:
 * the Split Timers' "Blood Rush" and "Boss Entry" rows were drawn straight over the Dungeon Map's Extra Info lines). The
 * map's box grew downward when Extra Info arrived (same day), so a Split Timers placed under the map was suddenly under
 * its info lines instead; nothing ever noticed two elements sharing pixels.
 * <p>
 * Used from an element's {@link HudElement#layoutPosition()}: when its own box ({@link HudElementRegistry#ownPosition},
 * its drawn scale) meets the other element's drawn box, it goes just below that box, else just above it, else just to
 * its right - keeping its own x (or y) - and otherwise stays exactly where it is. Only while the other element has
 * really drawn in the last {@link #LIVE_MS} ({@link HudSeen}), so leaving the dungeon puts it straight back. Never in
 * the HUD editor (the caller returns null there): what he drags is his own saved position, which this never writes.
 */
public final class HudAvoid {

    /** How recently the other element must have drawn to count as on screen. */
    static final long LIVE_MS = 500L;
    /** Room left between the two boxes. */
    public static final int GAP = 3;
    /** The answer is reused this long: the boxes' sizes come from width()/height(), which build line lists. */
    private static final long MEMO_MS = 100L;

    private HudAvoid() {
    }

    private static final java.util.Map<String, long[]> MEMO_AT = new java.util.HashMap<>();
    private static final java.util.Map<String, int[]> MEMO = new java.util.HashMap<>();

    /** Where {@code self} draws so it does not cover the element {@code otherId}, or null to use its own position. */
    public static int[] clear(HudElement self, String otherId) {
        long now = System.currentTimeMillis();
        String key = self.id() + "|" + otherId;
        long[] at = MEMO_AT.get(key);
        if (at != null && now - at[0] < MEMO_MS) {
            return MEMO.get(key);
        }
        int[] out;
        try {
            out = compute(self, otherId, now);
        } catch (RuntimeException e) {
            out = null; // never let a measuring problem move or break an element
        }
        MEMO_AT.put(key, new long[]{now});
        MEMO.put(key, out);
        return out;
    }

    private static int[] compute(HudElement self, String otherId, long now) {
        HudElement other = HudElementRegistry.byId(otherId);
        if (other == null || other == self || HudSeen.msSince(otherId, now) > LIVE_MS) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        Window window = mc == null ? null : mc.getWindow();
        if (window == null || McCompat.screen(mc) instanceof HudEditorScreen) {
            return null;
        }
        int[] o = HudElementRegistry.resolvePosition(other);
        float os = HudElementRegistry.resolveScale(other);
        int ox1 = o[0] + Math.round(other.width() * os);
        int oy1 = o[1] + Math.round(other.height() * os);
        int[] p = HudElementRegistry.ownPosition(self);
        float s = HudElementRegistry.resolveScale(self);
        int w = Math.round(self.width() * s);
        int h = Math.round(self.height() * s);
        boolean meets = p[0] < ox1 && o[0] < p[0] + w && p[1] < oy1 && o[1] < p[1] + h;
        if (!meets) {
            return null;
        }
        int screenW = window.getGuiScaledWidth();
        int screenH = window.getGuiScaledHeight();
        int below = oy1 + GAP;
        if (below + h <= screenH) {
            return new int[]{p[0], below};
        }
        int above = o[1] - GAP - h;
        if (above >= 0) {
            return new int[]{p[0], above};
        }
        int right = ox1 + GAP;
        if (right + w <= screenW) {
            return new int[]{right, Math.max(0, Math.min(p[1], screenH - h))};
        }
        return null; // no room anywhere: leave it where he put it
    }
}
