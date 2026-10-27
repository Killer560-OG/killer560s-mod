package com.killer560.hub.blink;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.OverlayPalette;
import com.killer560.hub.gui.OverlayThemes;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Blink's HUD (killer560, 2026-10-27: "make the charge bar for blink more unique and have a growing bar with it"). HUD Style
 * picks what it shows:
 * <ul>
 *   <li><b>Bar</b>: a slim bar per part. Charge fills in the menu theme's accent as ticks bank, eased so it grows smoothly
 *       instead of jumping a tick at a time; full, it pulses; armed (a shot waiting for a movement key), it turns gold and
 *       blinks; a shot drains it fast with a white flash where the bank was. Hold has its own red bar counting toward Hold
 *       Ticks.</li>
 *   <li><b>Text</b>: "Charge 12/20  Hold 5/20", as before.</li>
 *   <li><b>Both</b> (default): "Charge", the bar, then "12/20".</li>
 * </ul>
 * Movable and resizable in the HUD editor (the registry scales it); the editor shows a sample (Charge 12/20, Hold 5/20). It
 * marks itself drawn ({@link HudSeen}) so the editor lists it without Show Unseen. Per frame it allocates nothing: the batch
 * is reused, count strings are cached, the text line is rebuilt only when what it says changes.
 */
final class BlinkHud implements HudElement {

    private static final Component EXAMPLE = Component.literal("§cCharge §f12§7/20  §cHold §f5§7/20");

    /** Sizes in GUI units at scale 1. */
    private static final int ROW = 10;
    private static final int BAR_H = 5;
    private static final int BAR_ONLY_W = 80;
    private static final int BOTH_W = 122;
    private static final int LABEL_W = 36;
    private static final int COUNT_W = 28;

    private static final int HOLD_TOP = 0xFFFF7A7A;
    private static final int HOLD_BOTTOM = 0xFFC62828;
    private static final int ARMED_TOP = 0xFFFFE27A;
    private static final int ARMED_BOTTOM = 0xFFE0A800;

    private static final GuiRects RECTS = GuiRects.reusable();
    /** "n/cap" for every bank and cap (0-20), made once. */
    private static final String[][] COUNTS = new String[21][21];

    // Animation state (render thread).
    private static float chargeShown;
    private static float holdShown;
    private static long lastNanos;
    private static long lastShots = -1;
    private static long flashStartNanos;
    private static float flashFrom;

    // Text line cache.
    private static long textKey = Long.MIN_VALUE;
    private static Component textLine;

    /** For the testkit: what the bars aim at and show (0-1), the charge bar's state, and frames a bar was drawn. */
    static volatile float chargeTarget;
    static volatile float chargeFill;
    static volatile float holdTarget;
    static volatile float holdFill;
    static volatile String chargeState = "none";
    static volatile long barFrames;

    @Override
    public String id() {
        return "blink";
    }

    @Override
    public String displayName() {
        return "Blink";
    }

    @Override
    public int defaultX() {
        return 10;
    }

    @Override
    public int defaultY() {
        return 400;
    }

    private static boolean editor() {
        return McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
    }

    private static BlinkConfig.HudStyle style() {
        return BlinkConfig.getInstance().getHudStyle();
    }

    private static int rows() {
        if (editor()) {
            return 2;
        }
        int n = (BlinkCharge.isActive() ? 1 : 0) + (BlinkFeature.isHolding() ? 1 : 0);
        return Math.max(1, n);
    }

    @Override
    public int width() {
        BlinkConfig.HudStyle s = style();
        if (s == BlinkConfig.HudStyle.TEXT) {
            return Math.max(1, Minecraft.getInstance().font.width(text()));
        }
        return s == BlinkConfig.HudStyle.BAR ? BAR_ONLY_W : BOTH_W;
    }

    @Override
    public int height() {
        BlinkConfig.HudStyle s = style();
        if (s == BlinkConfig.HudStyle.TEXT) {
            return 9;
        }
        int row = s == BlinkConfig.HudStyle.BAR ? BAR_H + 3 : ROW;
        return rows() * row - (s == BlinkConfig.HudStyle.BAR ? 3 : 1);
    }

    @Override
    public boolean isEnabledInSettings() {
        BlinkConfig cfg = BlinkConfig.getInstance();
        return com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && cfg.isEnabled() && cfg.isShowHud();
    }

    /** The Text style's line, rebuilt only when what it says changes. */
    private static Component text() {
        if (editor()) {
            return EXAMPLE;
        }
        long key = (BlinkCharge.isActive() ? 1L : 0L) | (long) BlinkCharge.banked() << 1 | (long) BlinkCharge.cap() << 6
                | (BlinkCharge.isArmed() ? 1L << 11 : 0L) | (BlinkFeature.isHolding() ? 1L << 12 : 0L)
                | (long) BlinkFeature.heldTicks() << 13 | (long) BlinkConfig.getInstance().getHoldTicks() << 19;
        if (key != textKey) {
            textKey = key;
            textLine = BlinkFeature.statusLine();
        }
        return textLine == null ? EXAMPLE : textLine;
    }

    private static String count(int n, int cap) {
        n = Math.max(0, Math.min(20, n));
        cap = Math.max(0, Math.min(20, cap));
        String s = COUNTS[n][cap];
        if (s == null) {
            s = n + "/" + cap;
            COUNTS[n][cap] = s;
        }
        return s;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        boolean editor = editor();
        BlinkConfig.HudStyle s = style();
        if (s == BlinkConfig.HudStyle.TEXT) {
            Component line = editor ? EXAMPLE : BlinkCharge.isActive() || BlinkFeature.isHolding() ? text() : null;
            if (line == null) {
                return;
            }
            graphics.text(Minecraft.getInstance().font, line, x, y, 0xFFFFFFFF, true);
            markDrawn(editor);
            return;
        }
        boolean charging = editor || BlinkCharge.isActive();
        boolean holding = editor || BlinkFeature.isHolding();
        if (!charging && !holding) {
            chargeShown = 0f;
            holdShown = 0f;
            chargeState = "none";
            return;
        }
        // Targets.
        int bank = editor ? 12 : BlinkCharge.banked();
        int cap = editor ? 20 : Math.max(1, BlinkCharge.cap());
        boolean armed = !editor && BlinkCharge.isArmed();
        int held = editor ? 5 : BlinkFeature.heldTicks();
        int holdCap = Math.max(1, BlinkConfig.getInstance().getHoldTicks());
        float cTarget = charging ? Math.min(1f, bank / (float) cap) : 0f;
        float hTarget = holding ? Math.min(1f, held / (float) holdCap) : 0f;

        // Easing: grows over ~80 ms, drains over ~40 ms; a shot (shots went up) flashes where the bank was.
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.05f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        long shots = BlinkCharge.shots();
        if (lastShots >= 0 && shots > lastShots && !editor) {
            flashStartNanos = now;
            flashFrom = chargeShown;
        }
        lastShots = shots;
        if (editor) {
            chargeShown = cTarget;
            holdShown = hTarget;
        } else {
            chargeShown += (cTarget - chargeShown) * (1f - (float) Math.exp(-dt / (cTarget < chargeShown ? 0.04f : 0.08f)));
            holdShown += (hTarget - holdShown) * (1f - (float) Math.exp(-dt / (hTarget < holdShown ? 0.04f : 0.06f)));
        }
        chargeTarget = cTarget;
        chargeFill = chargeShown;
        holdTarget = hTarget;
        holdFill = holdShown;
        boolean full = charging && bank >= cap;
        chargeState = !charging ? "none" : armed ? "armed" : full ? "full" : "charging";

        OverlayPalette p = OverlayThemes.menu();
        Font font = Minecraft.getInstance().font;
        boolean both = s == BlinkConfig.HudStyle.BOTH;
        int rowH = both ? ROW : BAR_H + 3;
        int barX0 = both ? x + LABEL_W : x;
        int barX1 = both ? x + BOTH_W - COUNT_W : x + BAR_ONLY_W;
        double t = (now / 1e9) % 1000.0;
        float pulse = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2 * 1.3));
        float blink = (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2 * 3.0));

        GuiRects r = RECTS.start(graphics);
        int row = 0;
        int chargeRowY = 0;
        int holdRowY = 0;
        if (charging) {
            chargeRowY = y + row * rowH;
            int by = chargeRowY + (both ? (ROW - BAR_H) / 2 - 1 : 0);
            int top = armed ? ARMED_TOP : full ? p.accentHover() : p.accentHover();
            int bottom = armed ? ARMED_BOTTOM : p.accent();
            if (full && !armed) {
                // Full: a soft glow that breathes around the bar.
                int a = 0x28 + (int) (pulse * 0x70);
                r.outline(barX0 - 2, by - 2, barX1 - barX0 + 4, BAR_H + 4, OverlayPalette.alpha(p.accent(), a));
            }
            if (armed) {
                int a = 0x40 + (int) (blink * 0x90);
                r.outline(barX0 - 2, by - 2, barX1 - barX0 + 4, BAR_H + 4, OverlayPalette.alpha(ARMED_TOP, a));
            }
            bar(r, p, barX0, by, barX1, chargeShown, top, bottom);
            // The drain flash: a white ghost of the bank that was, fading over 250 ms.
            float flashAge = (now - flashStartNanos) / 1e9f;
            if (flashStartNanos != 0 && flashAge < 0.25f && flashFrom > chargeShown) {
                int inner = barX1 - barX0 - 2;
                int fx0 = barX0 + 1 + Math.round(inner * chargeShown);
                int fx1 = barX0 + 1 + Math.round(inner * flashFrom);
                int a = (int) (0xC0 * (1f - flashAge / 0.25f));
                if (fx1 > fx0) {
                    r.fill(fx0, by + 1, fx1, by + BAR_H - 1, OverlayPalette.alpha(0xFFFFFFFF, a));
                }
            }
            row++;
        }
        if (holding) {
            holdRowY = y + row * rowH;
            int by = holdRowY + (both ? (ROW - BAR_H) / 2 - 1 : 0);
            bar(r, p, barX0, by, barX1, holdShown, HOLD_TOP, HOLD_BOTTOM);
        }
        r.submit();

        if (both) {
            if (charging) {
                int c = armed ? ARMED_TOP : p.accentHover();
                graphics.text(font, armed ? "Armed" : "Charge", x, chargeRowY, c, true);
                graphics.text(font, count(bank, cap), barX1 + 3, chargeRowY, full ? 0xFFFFFFFF : p.text(), true);
            }
            if (holding) {
                graphics.text(font, "Hold", x, holdRowY, HOLD_TOP, true);
                graphics.text(font, count(held, holdCap), barX1 + 3, holdRowY, p.text(), true);
            }
        }
        markDrawn(editor);
        if (!editor) {
            barFrames++;
        }
    }

    /** A slim recessed track with a rounded look (corners left out) and the filled part as a gradient. */
    private static void bar(GuiRects r, OverlayPalette p, int x0, int y, int x1, float fill, int top, int bottom) {
        int w = x1 - x0;
        // Track: rounded by leaving the four corner pixels out.
        r.fill(x0 + 1, y, x1 - 1, y + 1, p.outline());
        r.fill(x0 + 1, y + BAR_H - 1, x1 - 1, y + BAR_H, p.outline());
        r.fill(x0, y + 1, x0 + 1, y + BAR_H - 1, p.outline());
        r.fill(x1 - 1, y + 1, x1, y + BAR_H - 1, p.outline());
        r.fill(x0 + 1, y + 1, x1 - 1, y + BAR_H - 1, OverlayPalette.alpha(p.well(), 0xD0));
        int inner = w - 2;
        int filled = Math.round(inner * Math.max(0f, Math.min(1f, fill)));
        if (filled > 0) {
            r.gradient(x0 + 1, y + 1, x0 + 1 + filled, y + BAR_H - 1, top, bottom);
            if (filled < inner) {
                // The growing edge: a bright 1 px lip.
                r.fill(x0 + filled, y + 1, x0 + 1 + filled, y + BAR_H - 1, 0xE0FFFFFF);
            }
        }
    }

    private void markDrawn(boolean editor) {
        if (!editor) {
            HudSeen.markDrawn(id());
            BlinkFeature.hudFrames++;
        }
    }
}
