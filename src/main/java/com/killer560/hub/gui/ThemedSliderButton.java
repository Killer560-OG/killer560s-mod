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
 *  label and value sit above a full-width track. Outside the menu it keeps the old box.
 */
public abstract class ThemedSliderButton extends AbstractSliderButton {

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

    /** The message split at its first ':' into label and value ("Bar Width: 120" -> "Bar Width", "120"). */
    private String[] split() {
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
        if (!SettingsButtonWidget.inMenu()) {
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
        super.onClick(event, doubleClick);
        if (SettingsButtonWidget.inMenu() && this.active) {
            int[] t = trackRange();
            float f = screenFactor();
            liveDrag = new LiveDrag(dragKey(), t[0] * (double) f, t[1] * (double) f);
            setValue(valueAt(event.x()));
        }
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        if (SettingsButtonWidget.inMenu()) {
            setValue(dragValueAt(event.x()));
            return;
        }
        super.onDrag(event, dragX, dragY);
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        super.onRelease(event);
        liveDrag = null;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        int w = getWidth();
        int h = getHeight();
        if (SettingsButtonWidget.inMenu()) {
            var font = Minecraft.getInstance().font;
            String[] s = split();
            int[] t = trackRange();
            boolean hover = isHovered;
            com.killer560.hub.hud.GuiRects r = com.killer560.hub.hud.GuiRects.begin(graphics);
            if (hover) {
                r.fill(x0, y0, x0 + w, y0 + h - 1, MenuStyle.alpha(MenuStyle.TXT, 0x0E));
            }
            MenuStyle.separator(r, x0, x0 + w, y0 + h - 2);
            boolean row = rowLayout();
            int cy = row ? y0 + h / 2 - 1 : y0 + h - 6;
            MenuStyle.sliderTrack(r, t[0], t[1], cy, this.value, hover);
            r.submit();
            int textY = row ? y0 + (h - 8) / 2 : y0 + 1;
            int labelColor = this.active ? (hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT) : MenuStyle.DIM2;
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
