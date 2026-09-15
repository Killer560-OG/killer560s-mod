package com.killer560.hub.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** A two-handled integer range slider in the mod's theme (2026-09-14, killer560: "a cps range between 1-15 that has
 *  both ends of a slider"). Same look as {@link ThemedSliderButton}: dark track, dim-orange border (bright on hover),
 *  the span between the two handles filled, both handles solid orange. Clicking grabs the nearest handle; dragging
 *  moves it, and a handle pushed past the other drags it along so low &lt;= high always holds. */
public abstract class RangeSliderWidget extends AbstractWidget {

    private static final int TRACK_BG = 0xFF1A1A1A;
    private static final int TRACK_BORDER = 0xFF663D1A;
    private static final int TRACK_BORDER_HOVER = 0xFFCC6600;
    private static final int FILLED = 0xFF3D2A14;
    private static final int HANDLE = 0xFFCC6600;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int HANDLE_WIDTH = 6;

    private final int minValue;
    private final int maxValue;
    private int low;
    private int high;
    private int dragging = -1; // 0 = low handle, 1 = high handle

    protected RangeSliderWidget(int x, int y, int width, int height, int minValue, int maxValue, int low, int high) {
        super(x, y, width, height, Component.empty());
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.low = clamp(Math.min(low, high));
        this.high = clamp(Math.max(low, high));
        setMessage(label(this.low, this.high));
    }

    /** Text drawn on the track for the current range. */
    protected abstract Component label(int low, int high);

    /** Called whenever either end changes. */
    protected abstract void onRangeChanged(int low, int high);

    private int clamp(int v) {
        return Math.max(minValue, Math.min(maxValue, v));
    }

    private int handleX(int value) {
        double t = maxValue == minValue ? 0 : (value - minValue) / (double) (maxValue - minValue);
        return getX() + (int) Math.round(t * (getWidth() - HANDLE_WIDTH));
    }

    private int valueAt(double mouseX) {
        double t = (mouseX - getX() - HANDLE_WIDTH / 2.0) / Math.max(1, getWidth() - HANDLE_WIDTH);
        return clamp((int) Math.round(minValue + Math.max(0, Math.min(1, t)) * (maxValue - minValue)));
    }

    private void setHandle(int which, int value) {
        int oldLow = low;
        int oldHigh = high;
        if (which == 0) {
            low = value;
            if (low > high) {
                high = low;
            }
        } else {
            high = value;
            if (high < low) {
                low = high;
            }
        }
        if (low != oldLow || high != oldHigh) {
            setMessage(label(low, high));
            onRangeChanged(low, high);
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = getX();
        int y0 = getY();
        int w = getWidth();
        int h = getHeight();
        graphics.fill(x0, y0, x0 + w, y0 + h, TRACK_BG);
        int lowX = handleX(low);
        int highX = handleX(high);
        graphics.fill(lowX + HANDLE_WIDTH, y0 + 1, highX, y0 + h - 1, FILLED);
        graphics.outline(x0, y0, w, h, isHovered || dragging >= 0 ? TRACK_BORDER_HOVER : TRACK_BORDER);
        graphics.fill(lowX, y0, lowX + HANDLE_WIDTH, y0 + h, HANDLE);
        graphics.fill(highX, y0, highX + HANDLE_WIDTH, y0 + h, HANDLE);
        graphics.centeredText(Minecraft.getInstance().font, getMessage(), x0 + w / 2, y0 + (h - 8) / 2, TEXT);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int value = valueAt(event.x());
        int lowDistance = Math.abs(handleX(low) - (int) event.x());
        int highDistance = Math.abs(handleX(high) - (int) event.x());
        if (lowDistance == highDistance) {
            dragging = value > low ? 1 : 0; // stacked handles: pull whichever way the click is
        } else {
            dragging = lowDistance < highDistance ? 0 : 1;
        }
        setHandle(dragging, value);
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging >= 0) {
            setHandle(dragging, valueAt(event.x()));
        }
    }

    @Override
    public void onRelease(MouseButtonEvent event) {
        dragging = -1;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
