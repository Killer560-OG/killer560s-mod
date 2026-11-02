package com.killer560.hub.spotify;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.OverlayPalette;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Spotify HUD controls in chat (killer560, 2026-10-26: "for the spotify hud if i click on it while chat is open it should
 * have buttons there to pause and skip and all of that").
 *
 * <p>With the chat screen open (the mouse is free) and the mouse over the Spotify HUD, a row of buttons appears under it:
 * Previous, Rewind, Play/Pause, Skip Ahead, Next, and Volume Down / the volume / Volume Up. A click calls
 * {@link SpotifyControls#perform}, exactly as the tab's buttons and the keys do; a control that cannot work is dimmed and
 * the reason is written under the row. With chat closed nothing changes: the HUD is display-only.
 *
 * <p>Clicks reach this through Fabric's {@code ScreenMouseEvents.allowMouseClick} on the chat screen, the pattern Custom
 * Scoreboard and the Live Map's "open from HUD" use. Any left click on the HUD or its row is consumed, so it can never go
 * on to a chat link or move the text cursor; other buttons and clicks elsewhere pass to chat untouched. The HUD editor is
 * its own screen, so it never sees this.
 *
 * <p>Geometry is in the HUD's own unscaled units (the element is drawn translated to its position and scaled), so the row
 * scales and moves with the panel. Nothing is allocated per frame.
 */
public final class SpotifyHudControls {

    /** Row height and the gap between the panel and the row. */
    static final int ROW_H = 14;
    static final int GAP = 2;
    /** The reason line under the row. */
    static final int NOTE_H = 10;

    private static final SpotifyControls.Action[] ACTIONS = {
            SpotifyControls.Action.PREVIOUS, SpotifyControls.Action.REWIND, SpotifyControls.Action.PLAY_PAUSE,
            SpotifyControls.Action.SKIP_AHEAD, SpotifyControls.Action.NEXT, SpotifyControls.Action.VOLUME_DOWN,
            null, SpotifyControls.Action.VOLUME_UP};
    private static final String[] LABELS = {"|<", "<<", "", ">>", ">|", "-", "", "+"};
    /**
     * Cell x ranges inside the 200-wide panel: five transport cells (Play/Pause the wide one in the middle), a gap, then
     * - / volume / +. Play/Pause is its own style (killer560, 2026-11-01: "it is just a smaller skip so it can be
     * mistaken"): wider, filled with the accent, and a drawn triangle or two bars instead of text.
     */
    private static final int[] X0 = {0, 22, 44, 76, 98, 128, 146, 184};
    private static final int[] X1 = {20, 42, 74, 96, 118, 144, 182, 200};
    /** Index of the Play/Pause cell and of the volume cell. */
    static final int PLAY_CELL = 2;
    static final int VOLUME_CELL = 6;

    // Render thread only: where the HUD was drawn on its last frame, so a click can be placed.
    private static long drawnAtMs;
    private static int panelW;
    private static int panelH;
    // Test hooks (read by the testkit).
    private static volatile boolean rowShown;
    private static volatile long clicksTaken;
    private static volatile String lastClicked = "";

    private SpotifyHudControls() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof ChatScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                    try {
                        return com.killer560.hub.compat.McInput.button(event) != 0 || !click(event.x(), event.y());
                    } catch (RuntimeException e) {
                        return true;
                    }
                });
            }
        });
    }

    private static boolean chatOpen() {
        return McCompat.screen(Minecraft.getInstance()) instanceof ChatScreen;
    }

    /** The mouse in the HUD's own units, or null. */
    private static double[] mouseLocal(double guiX, double guiY) {
        int[] pos = HudElementRegistry.resolvePosition(SpotifyHud.INSTANCE);
        float scale = HudElementRegistry.resolveScale(SpotifyHud.INSTANCE);
        if (scale <= 0) {
            return null;
        }
        LOCAL[0] = (guiX - pos[0]) / scale;
        LOCAL[1] = (guiY - pos[1]) / scale;
        return LOCAL;
    }

    private static final double[] LOCAL = new double[2];

    private static boolean overPanelOrRow(double lx, double ly) {
        return lx >= 0 && lx < panelW && ly >= 0 && ly < panelH + GAP + ROW_H;
    }

    private static int cellAt(double lx, double ly) {
        if (ly < panelH + GAP || ly >= panelH + GAP + ROW_H) {
            return -1;
        }
        for (int i = 0; i < X0.length; i++) {
            if (lx >= X0[i] && lx < X1[i]) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Draws the row under the panel when chat is open and the mouse is over the panel or the row. Called by
     * {@link SpotifyHud#render} at the end of its frame, under the HUD's own pose, with the panel's size.
     */
    static void draw(GuiGraphicsExtractor graphics, Font font, OverlayPalette p, int w, int h, boolean paused) {
        drawnAtMs = System.currentTimeMillis();
        panelW = w;
        panelH = h;
        Minecraft mc = Minecraft.getInstance();
        boolean always = SpotifyHudConfig.getInstance().isAlwaysShowControls();
        double[] m = null;
        if (chatOpen() && mc.getWindow() != null) {
            m = mouseLocal(mc.mouseHandler.getScaledXPos(mc.getWindow()), mc.mouseHandler.getScaledYPos(mc.getWindow()));
            if (m != null && !overPanelOrRow(m[0], m[1])) {
                m = null;
            }
        }
        if (m == null && !always) {
            rowShown = false;
            return;
        }
        rowShown = true;
        drawRow(graphics, font, p, w, h, paused, m == null ? -1 : cellAt(m[0], m[1]));
    }

    /** Edit HUD with Always Show Controls on: the row as it will look, nothing hovered. */
    static void drawInEditor(GuiGraphicsExtractor graphics, Font font, OverlayPalette p, int w, int h, boolean paused) {
        drawRow(graphics, font, p, w, h, paused, -1);
    }

    private static void drawRow(GuiGraphicsExtractor graphics, Font font, OverlayPalette p, int w, int h, boolean paused,
                                int hover) {
        int y0 = h + GAP;
        GuiRects r = GuiRects.begin(graphics);
        String reason = null;
        boolean playOn = SpotifyControls.unavailableReason(SpotifyControls.Action.PLAY_PAUSE) == null;
        for (int i = 0; i < X0.length; i++) {
            SpotifyControls.Action a = ACTIONS[i];
            String why = a == null ? null : SpotifyControls.unavailableReason(a);
            boolean on = a != null && why == null;
            if (i == hover && why != null) {
                reason = why;
            }
            // The volume reading sits in a box like the buttons beside it (killer560, 2026-11-01).
            r.outline(X0[i], y0, X1[i] - X0[i], ROW_H, p.border());
            int fill;
            if (i == PLAY_CELL) {
                fill = !playOn ? OverlayPalette.alpha(p.accentDeep(), 0x90) : i == hover ? p.accentHover() : p.accent();
            } else {
                fill = i == hover && on ? OverlayPalette.alpha(p.accent(), 0x70) : OverlayPalette.alpha(p.panelDeep(), 0xE8);
            }
            r.fill(X0[i] + 1, y0 + 1, X1[i] - 1, y0 + ROW_H - 1, fill);
        }
        // Play/Pause: a drawn triangle (paused, so it plays) or two bars, in the panel's dark color on the accent.
        int glyph = OverlayPalette.alpha(p.panelDeep(), 0xFF);
        int cx = (X0[PLAY_CELL] + X1[PLAY_CELL]) / 2;
        int cy = y0 + ROW_H / 2;
        if (paused) {
            for (int k = 0; k < 9; k++) {
                int len = 1 + Math.min(k, 8 - k);
                r.fill(cx - 2, cy - 4 + k, cx - 2 + len, cy - 3 + k, glyph);
            }
        } else {
            r.fill(cx - 4, cy - 4, cx - 1, cy + 5, glyph);
            r.fill(cx + 1, cy - 4, cx + 4, cy + 5, glyph);
        }
        if (reason != null) {
            r.fill(0, y0 + ROW_H + 1, w, y0 + ROW_H + 1 + NOTE_H, OverlayPalette.alpha(p.panelDeep(), 0xE8));
        }
        r.submit();
        lastPlayGlyph = paused ? "play" : "pause";
        for (int i = 0; i < X0.length; i++) {
            if (i == PLAY_CELL) {
                continue;
            }
            SpotifyControls.Action a = ACTIONS[i];
            String label = LABELS[i];
            if (a == null) {
                int v = SpotifyControls.volume();
                label = v < 0 ? "?" : VOLUME_TEXT[v];
            }
            boolean on = a == null || SpotifyControls.unavailableReason(a) == null;
            int colour = a == null ? p.textHover() : !on ? p.faint() : i == hover ? p.textHover() : p.accentHover();
            graphics.text(font, label, (X0[i] + X1[i]) / 2 - font.width(label) / 2, y0 + 3, colour, true);
        }
        if (reason != null) {
            String fitted = font.width(reason) <= w - 4 ? reason : font.plainSubstrByWidth(reason, w - 10) + "...";
            graphics.text(font, fitted, 2, y0 + ROW_H + 2, p.faint(), true);
        }
    }

    // Test hook: the glyph the Play/Pause cell drew last ("play" or "pause").
    private static volatile String lastPlayGlyph = "";

    /** "0%".."100%", made once so a frame allocates no string for the volume. */
    private static final String[] VOLUME_TEXT = new String[101];

    static {
        for (int i = 0; i <= 100; i++) {
            VOLUME_TEXT[i] = i + "%";
        }
    }

    /** A left click in chat at GUI coordinates; true when it was on the HUD or its row (consumed). */
    static boolean click(double guiX, double guiY) {
        if (!chatOpen() || !SpotifyHudConfig.getInstance().isHudEnabled()
                || System.currentTimeMillis() - drawnAtMs > 500 || panelW <= 0) {
            return false;   // the HUD is not on screen: the click is chat's
        }
        double[] m = mouseLocal(guiX, guiY);
        if (m == null || !overPanelOrRow(m[0], m[1])) {
            return false;
        }
        clicksTaken++;
        int cell = cellAt(m[0], m[1]);
        SpotifyControls.Action a = cell < 0 ? null : ACTIONS[cell];
        if (a != null && SpotifyControls.unavailableReason(a) == null) {
            lastClicked = a.name();
            SpotifyControls.perform(a);
        }
        return true;
    }
}
