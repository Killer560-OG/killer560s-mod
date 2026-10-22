package com.killer560.hub.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Drop-in replacement for vanilla {@link AbstractSliderButton} that only overrides its rendering
 *  (vanilla draws its own fixed grey sprite track/handle with no way to recolor it) - every other bit
 *  of behavior (drag-to-set-value, click-to-jump, keyboard nudging, {@code updateMessage()}/
 *  {@code applyValue()}) is fully inherited unchanged. Existing anonymous subclasses just need
 *  {@code AbstractSliderButton} swapped for {@code ThemedSliderButton} at their `new` site - same
 *  constructor shape, same abstract methods to implement.
 *  <p>
 *  Built (2026-09-07), part of the black+amber GUI overhaul, per killer560's "make those sliding bars fit
 *  the theme as well... make the handle itself orange."
 *  <p>
 *  Option A menu (2026-10-12): inside the mod menu a slider is a setting ROW - its label on the left, a recessed track
 *  with an orange fill and a white knob in the middle, the value on the right. A click or drag maps onto that drawn
 *  track ({@link #trackRange()}), not the whole row, so the knob lands where the mouse is. Too narrow for that, the
 *  label and value sit above a full-width track. A MenuThemedScreen (screen sweep C) gets the same row; elsewhere it keeps the old box.
 */
public abstract class ThemedSliderButton extends AbstractSliderButton implements MenuAnim.Animated {

    private static final int TRACK_BG = 0xFF1A1A1A;
    private static final int TRACK_BORDER = 0xFF663D1A;
    private static final int TRACK_BORDER_HOVER = 0xFFCC6600;
    private static final int FILLED = 0xFF3D2A14;
    private static final int HANDLE = 0xFFCC6600;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int HANDLE_WIDTH = 6;

    protected ThemedSliderButton(int x, int y, int width, int height, Component message, double value) {
        super(x, y, width, height, message, value);
    }

    /** Its animation in the mod menu (hover, the knob gliding to a clicked spot); handed to its rebuilt copy. */
    private final MenuAnim.RowAnim anim = new MenuAnim.RowAnim();
    /** Set by a click on the track: the knob glides to the new value. A drag (or a key) moves it with the mouse. */
    private boolean clickJump;

    // ---- steps (killer560, 2026-10-15: "for all sliders that snap to values make it so the slider cannot be halfway
    // between values, it should snap to its proper position") ------------------------------------------------------
    private int steps;
    /** True once this slider has been seen to snap (its knob then glides from step to step instead of following the
     *  mouse between them). */
    private boolean stepped;

    /** Snaps this slider to {@code n} equal steps ({@code n + 1} stops). Returns this, for a builder-style call after
     *  an anonymous subclass. Inside the mod menu a slider needs no count: it is snapped to wherever its own label puts
     *  it (see {@link #setValue}). */
    public ThemedSliderButton steps(int n) {
        this.steps = Math.max(0, n);
        if (steps > 0) {
            this.value = snap(this.value, steps);
            this.stepped = true;
        }
        return this;
    }

    /** The knob position (0..1). */
    public double sliderValue() {
        return this.value;
    }

    /** The number of equal steps, or 0 when not known here. */
    protected int stepCount() {
        return steps;
    }

    /** Whether this slider snaps (a known step count, or seen snapping). Read by the testkit. */
    public boolean isStepped() {
        return stepped || stepCount() > 0;
    }

    private static double snap(double v, int n) {
        return Math.max(0.0, Math.min(1.0, Math.round(v * n) / (double) n));
    }

    /**
     * Every change of value - a click, a drag, a key - comes through here (vanilla's own mouse and key paths call it).
     * A slider with a known step count is snapped before it is applied. One in the mod menu is then put exactly where
     * its label says: the tab is built again off-screen and this row's twin, constructed from the value just saved,
     * holds the knob position for that label ({@link ModScreen#twinSliderValue}). So the knob always sits on a step and
     * the number shown always matches it, for every slider in the menu without each one declaring its steps.
     */
    @Override
    protected void setValue(double v) {
        boolean outer = stepSaves == null;
        if (outer) {
            stepSaves = new java.util.ArrayList<>();
        }
        try {
            setValueSnapped(v);
        } finally {
            if (outer) {
                java.util.List<Runnable> saves = stepSaves;
                stepSaves = null;
                if (!saves.isEmpty()) {
                    if (pressHeld) {
                        pendingSaves = saves; // the latest step's saves; the release writes them once
                    } else {
                        runAll(saves);
                    }
                }
            }
        }
    }

    // ---- save on release (sweep 2026-10-21: every drag step wrote the whole config file) ----------------------------

    /** Saves asked for by the value change running right now, or null outside one. */
    private static java.util.List<Runnable> stepSaves;
    /** The last drag step's saves, written when the press ends. */
    private static java.util.List<Runnable> pendingSaves;
    /** A mouse press on a slider has not been released yet. Static: a menu relayout hands the drag to a rebuilt row. */
    private static boolean pressHeld;
    /** The screen the press started in; if it goes away without a release, the next client tick writes the saves. */
    private static Object pressScreen;
    private static boolean flushTickRegistered;

    /**
     * Saves a slider's setting: once when the press that moves it is released, or at once for a key or a programmatic
     * change. A slider's {@code applyValue} (and any setter it calls) uses this instead of calling {@code save()} itself,
     * so a drag still applies every step live but writes the file only once. Outside a slider change it just runs.
     */
    public static void saveOnRelease(Runnable save) {
        if (stepSaves != null) {
            stepSaves.add(save);
        } else {
            save.run();
        }
    }

    /** Writes any saves a drag left waiting. Safe to call at any time. */
    public static void flushPendingSaves() {
        pressHeld = false;
        pressScreen = null;
        java.util.List<Runnable> saves = pendingSaves;
        pendingSaves = null;
        if (saves != null) {
            runAll(saves);
        }
    }

    private static void runAll(java.util.List<Runnable> saves) {
        for (Runnable r : saves) {
            try {
                r.run();
            } catch (RuntimeException e) {
                // One config's failed write must not lose the others.
            }
        }
    }

    private static void beginPress() {
        flushPendingSaves(); // a press whose release never came (its screen closed under it)
        pressHeld = true;
        Minecraft mc = Minecraft.getInstance();
        pressScreen = com.killer560.hub.compat.McCompat.screen(mc);
        if (!flushTickRegistered) {
            flushTickRegistered = true;
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (pressHeld && com.killer560.hub.compat.McCompat.screen(client) != pressScreen) {
                    flushPendingSaves();
                }
            });
        }
    }

    private void setValueSnapped(double v) {
        int n = stepCount();
        if (n > 0) {
            v = snap(v, n);
        }
        super.setValue(v);
        if (n > 0 || !SettingsButtonWidget.inMenu()) {
            return;
        }
        Double twin = ModScreen.twinSliderValue(this);
        if (twin != null && Math.abs(twin - this.value) > 1e-9) {
            this.value = Math.max(0.0, Math.min(1.0, twin));
            this.stepped = true;
        }
    }

    /** Arrow keys on a slider that snaps move it a whole step: vanilla's nudge is one pixel, which a snapping slider
     *  would put straight back on the step it left. */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        boolean left = key == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT;
        boolean right = key == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT;
        if (!(left || right) || !isStepped()) {
            return super.keyPressed(event);
        }
        int n = stepCount();
        if (n > 0) {
            setValue(this.value + (right ? 1.0 : -1.0) / n);
            return true;
        }
        String before = getMessage().getString();
        double nudge = 1.0 / Math.max(1, getWidth() - 8);
        double target = this.value;
        for (int i = 0; i < 4000 && target > 0.0 && target < 1.0; i++) {
            target += right ? nudge : -nudge;
            setValue(Math.max(0.0, Math.min(1.0, target)));
            if (!getMessage().getString().equals(before)) {
                break;
            }
        }
        return true;
    }

    @Override
    public MenuAnim.RowAnim anim() {
        return anim;
    }

    @Override
    public String animKey() {
        return "S|" + dragKey();
    }

    /** The message split at its first ':' into label and value ("Bar Width: 120" -> "Bar Width", "120"). */
    private net.minecraft.network.chat.Component splitFor;
    private String[] splitParts;

    /** {@link #computeSplit} of the current message, kept while it is the same object (a slider replaces its message
     *  with {@code setMessage} on every change; it was split three times a frame per slider, fps-gui-1015). */
    private String[] split() {
        net.minecraft.network.chat.Component msg = getMessage();
        if (splitParts == null || msg != splitFor) {
            splitParts = computeSplit();
            splitFor = msg;
        }
        return splitParts;
    }

    private String[] computeSplit() {
        String plain = net.minecraft.ChatFormatting.stripFormatting(getMessage().getString());
        if (plain == null) {
            plain = getMessage().getString();
        }
        int colon = plain.indexOf(':');
        if (colon <= 0) {
            return new String[]{"", plain.trim()};
        }
        return new String[]{plain.substring(0, colon).trim(), plain.substring(colon + 1).trim()};
    }

    /** Whether the menu row layout (label | track | value) fits; otherwise the text sits above a full-width track. */
    private boolean rowLayout() {
        var font = Minecraft.getInstance().font;
        String[] s = split();
        return getWidth() - font.width(s[0]) - Math.max(24, font.width(s[1])) - 24 >= 36;
    }

    /** The drawn track's ends in screen units {x0, x1}, the range a click or drag maps onto. */
    public int[] trackRange() {
        int x0 = getX();
        int w = getWidth();
        if (!(SettingsButtonWidget.inMenu() || SettingsButtonWidget.inThemedScreen())) {
            return new int[]{x0 + 4, x0 + w - 4};
        }
        if (!rowLayout()) {
            return new int[]{x0 + 6, x0 + w - 6};
        }
        var font = Minecraft.getInstance().font;
        String[] s = split();
        int labelW = font.width(s[0]);
        int valueW = Math.max(24, font.width(s[1]));
        int left = x0 + 4 + Math.min(labelW, w / 2) + 12;
        int right = x0 + w - 4 - valueW - 8;
        return new int[]{left, Math.max(left + 20, right)};
    }

    private double valueAt(double mouseX) {
        int[] t = trackRange();
        return Math.max(0, Math.min(1, (mouseX - t[0]) / Math.max(1.0, t[1] - t[0])));
    }

    /**
     * A drag in the mod menu, held by its row's label and with the track's ends in WINDOW GUI units (layout units times
     * the screen's Auto Scale factor), taken at the press. HUD Scale resizes the very menu it sits in, and since
     * 2026-10-13 it does so live while it is dragged (killer560: "the HUD must update live while dragging, not only on
     * release"): the relayout rebuilds this row, and ModScreen hands the drag to the new one ({@link #liveDragKey}).
     * Measuring against the track where it was pressed, in window units, keeps the value under the cursor monotonic
     * however the menu grows or shrinks under it.
     */
    private record LiveDrag(String key, double x0, double x1) {
    }

    private static LiveDrag liveDrag;

    /** The label of the menu slider being dragged right now, or null. */
    public static String liveDragKey() {
        return liveDrag == null ? null : liveDrag.key();
    }

    /** Forgets a drag whose release never arrived (the menu closed under it). */
    public static void endLiveDrag() {
        liveDrag = null;
        flushPendingSaves();
    }

    /** This row's label, the key a drag is handed over by. */
    public String dragKey() {
        return split()[0];
    }

    private static float screenFactor() {
        return com.killer560.hub.hud.AutoScale.openScreenFactor();
    }

    private double dragValueAt(double mouseX) {
        LiveDrag d = liveDrag;
        if (d == null || !d.key().equals(dragKey())) {
            return valueAt(mouseX);
        }
        double windowX = mouseX * screenFactor();
        return Math.max(0, Math.min(1, (windowX - d.x0()) / Math.max(1.0, d.x1() - d.x0())));
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        beginPress();
        super.onClick(event, doubleClick);
        if ((SettingsButtonWidget.inMenu() || SettingsButtonWidget.inThemedScreen()) && this.active) {
            int[] t = trackRange();
            float f = screenFactor();
            liveDrag = new LiveDrag(dragKey(), t[0] * (double) f, t[1] * (double) f);
            clickJump = true;
            setValue(valueAt(event.x()));
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        clickJump = false;
        if ((SettingsButtonWidget.inMenu() || SettingsButtonWidget.inThemedScreen())) {
            setValue(dragValueAt(event.x()));
            return;
        }
        super.onDrag(event, dragX, dragY);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        liveDrag = null;
        flushPendingSaves();
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        int w = getWidth();
        int h = getHeight();
        if ((SettingsButtonWidget.inMenu() || SettingsButtonWidget.inThemedScreen())) {
            var font = Minecraft.getInstance().font;
            String[] s = split();
            int[] t = trackRange();
            boolean hover = isHovered;
            anim.hover.set(hover ? 1f : 0f);
            float hv = anim.hover.get();
            // A click on the track glides the knob there; dragging follows the mouse exactly.
            float v = (float) this.value;
            if (v != anim.value.target()) {
                if ((clickJump || stepped || stepCount() > 0) && anim.value.placed()) {
                    anim.value.set(v);
                } else {
                    anim.value.snap(v);
                }
            }
            clickJump = false;
            com.killer560.hub.hud.GuiRects r = com.killer560.hub.hud.GuiRects.begin(graphics);
            if (hv > 0f) {
                r.fill(x0, y0, x0 + w, y0 + h - 1, MenuStyle.alpha(MenuStyle.TXT, Math.round(0x0E * hv)));
            }
            MenuStyle.separator(r, x0, x0 + w, y0 + h - 2);
            boolean row = rowLayout();
            int cy = row ? y0 + h / 2 - 1 : y0 + h - 6;
            MenuStyle.sliderTrack(r, t[0], t[1], cy, anim.value.get(), hv);
            r.submit();
            int textY = row ? y0 + (h - 8) / 2 : y0 + 1;
            int labelColor = this.active ? MenuStyle.ease(MenuStyle.TXT, MenuStyle.TXT_HOVER, hv) : MenuStyle.DIM2;
            if (row) {
                graphics.text(font, MenuStyle.ellipsize(s[0], t[0] - x0 - 14), x0 + 4, textY, labelColor, MenuStyle.TEXT_SHADOW);
                graphics.text(font, s[1], x0 + w - 4 - font.width(s[1]), textY, MenuStyle.DIM, MenuStyle.TEXT_SHADOW);
            } else {
                String l = MenuStyle.ellipsize(s[0], w - 12 - font.width(s[1]));
                graphics.text(font, l, x0 + 4, textY, labelColor, MenuStyle.TEXT_SHADOW);
                graphics.text(font, s[1], x0 + w - 4 - font.width(s[1]), textY, MenuStyle.DIM, MenuStyle.TEXT_SHADOW);
            }
            return;
        }
        graphics.fill(x0, y0, x0 + w, y0 + h, TRACK_BG);
        int handleX = x0 + (int) Math.round(this.value * (w - HANDLE_WIDTH));
        // A subtle "filled up to here" strip behind the handle, same idea as vanilla's own slider
        // (the portion already dragged past reads visually distinct from the empty remainder).
        graphics.fill(x0 + 1, y0 + 1, handleX, y0 + h - 1, FILLED);
        graphics.outline(x0, y0, w, h, isHovered ? TRACK_BORDER_HOVER : TRACK_BORDER);
        graphics.fill(handleX, y0, handleX + HANDLE_WIDTH, y0 + h, HANDLE);
        graphics.centeredText(Minecraft.getInstance().font, getMessage(), x0 + w / 2, y0 + (h - 8) / 2, TEXT);
    }
}
