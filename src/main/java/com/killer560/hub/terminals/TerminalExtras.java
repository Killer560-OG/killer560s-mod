package com.killer560.hub.terminals;

import com.killer560.hub.clicksound.ClickSounds;
import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;

/**
 * Terminal Sounds (Odin) and Terminal Click Path (Athen's Terminal Click), both legit and both in Terminal QoL
 * ({@link TerminalQolConfig}). One mouse-click hook ({@link #noteClick}, from the solver's container mixin) feeds both:
 * a correct click plays the chosen click sound, and every click is kept as a point of the path. The path shows for
 * {@link #SHOW_MS} after the terminal closes, drawn by a Fabric HUD layer that returns on its first line while nothing is
 * shown. A finished terminal ({@link #onOwnTerminalDone}, from Terminal Timers' own-line match) plays the complete sound.
 * Click tracking needs the Terminal Solver on (it is what knows a terminal is open and which slots are correct).
 */
public final class TerminalExtras {

    private static final long SHOW_MS = 4000L;
    private static final long FADE_MS = 600L;
    /** A terminal is at most a few dozen clicks; anything past this is not kept. */
    private static final int MAX_POINTS = 64;

    /** Window-normalized click points (0..1 of the GUI-scaled window), so the path draws right after a Terminal Scale change closes. */
    private static final float[] xs = new float[MAX_POINTS];
    private static final float[] ys = new float[MAX_POINTS];
    private static final boolean[] correct = new boolean[MAX_POINTS];
    private static int points;
    private static long shownUntilMs;
    /** Sounds played since the game started, per kind, for the testkit. */
    static int clickSoundsPlayed;
    static int completeSoundsPlayed;

    private TerminalExtras() {
    }

    public static void register() {
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("killer560smod", "terminal_click_path"), (graphics, deltaTracker) -> draw(graphics));
    }

    /** A new terminal opened: the last path is gone. */
    static void begin() {
        points = 0;
        shownUntilMs = 0L;
    }

    /** The terminal closed: show what was clicked, if the switch is on. */
    static void closed() {
        if (points > 0 && TerminalQolConfig.getInstance().isClickPath()) {
            shownUntilMs = System.currentTimeMillis() + SHOW_MS;
        }
    }

    /** One mouse press inside a tracked terminal, before the screen handles it. */
    public static void noteClick(AbstractContainerScreen<?> screen, double x, double y) {
        TerminalQolConfig q = TerminalQolConfig.getInstance();
        boolean sound = q.isTerminalSounds();
        boolean path = q.isClickPath();
        if ((!sound && !path) || !TerminalSolverFeature.isTracking()) return;
        int slot = HoverTerminalFeature.resolveHoveredSlot(screen);
        if (slot < 0) return;
        boolean ok = TerminalSolverFeature.isCorrectSlot(slot);
        if (path && points < MAX_POINTS) {
            var window = Minecraft.getInstance().getWindow();
            xs[points] = (float) (x / window.getGuiScaledWidth());
            ys[points] = (float) (y / window.getGuiScaledHeight());
            correct[points++] = ok;
        }
        if (sound && ok && play(q.getClickSound(), q)) clickSoundsPlayed++;
    }

    /** Your own "completed a terminal!" line arrived. */
    public static void onOwnTerminalDone() {
        TerminalQolConfig q = TerminalQolConfig.getInstance();
        if (q.isTerminalSounds() && play(q.getCompleteSound(), q)) completeSoundsPlayed++;
    }

    private static boolean play(String id, TerminalQolConfig q) {
        SoundInstance sound = ClickSounds.make(id, q.getSoundVolume() / 100f);
        if (sound == null) return false;
        Minecraft.getInstance().getSoundManager().play(sound);
        return true;
    }

    /** The click points as "x,y,ok" strings (testkit). */
    static java.util.List<String> pathForTest() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < points; i++) out.add(xs[i] + "," + ys[i] + "," + correct[i]);
        return out;
    }

    static boolean showingForTest() { return shownUntilMs > System.currentTimeMillis(); }

    private static void draw(GuiGraphicsExtractor g) {
        if (shownUntilMs == 0L) return;
        long left = shownUntilMs - System.currentTimeMillis();
        if (left <= 0L) {
            shownUntilMs = 0L;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int gw = mc.getWindow().getGuiScaledWidth();
        int gh = mc.getWindow().getGuiScaledHeight();
        int alpha = left >= FADE_MS ? 255 : (int) (255 * left / FADE_MS);
        int line = (alpha << 24) | (ThemeAccent.accent(0xFFCC6600) & 0xFFFFFF);
        for (int i = 1; i < points; i++) {
            int x0 = Math.round(xs[i - 1] * gw), y0 = Math.round(ys[i - 1] * gh);
            int x1 = Math.round(xs[i] * gw), y1 = Math.round(ys[i] * gh);
            int steps = Math.max(1, Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) / 3);
            for (int s = 0; s <= steps; s++) {
                int px = x0 + (x1 - x0) * s / steps, py = y0 + (y1 - y0) * s / steps;
                g.fill(px, py, px + 2, py + 2, line);
            }
        }
        for (int i = 0; i < points; i++) {
            int px = Math.round(xs[i] * gw), py = Math.round(ys[i] * gh);
            int dot = (alpha << 24) | (correct[i] ? ModChat.GOOD : ModChat.BAD);
            g.fill(px - 3, py - 3, px + 3, py + 3, dot);
            g.text(mc.font, String.valueOf(i + 1), px + 5, py - 4, (alpha << 24) | 0xFFFFFF, true);
        }
    }
}
