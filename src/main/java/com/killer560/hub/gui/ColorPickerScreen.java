package com.killer560.hub.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * A real HSV color picker (hue bar + saturation/value square + alpha slider) - killer560's "click on
 * it then it opens a color wheel" request. No existing color-picker UI existed anywhere in this
 * codebase before this, so this is a new, general-purpose, reusable screen any future feature's color
 * setting can open, not just Simon Says.
 * <p>
 * Live-previews as you drag (calls {@code onChange} immediately, same as every other live-updating
 * setting in this mod) rather than only applying on "Done". "Set Default" resets to whatever default
 * ARGB the caller passed in, independent of Done/Cancel.
 */
public class ColorPickerScreen extends Screen {

    private final Screen parent;
    private final int defaultArgb;
    private final IntConsumer onChange;

    private float hue;
    private float saturation;
    private float value;
    private float alpha;

    private int panelX, panelY, panelW, panelH;
    private int svX, svY, svSize;
    private int hueBarX, hueBarY, hueBarW, hueBarH;
    private boolean draggingSv = false;
    private boolean draggingHue = false;

    public ColorPickerScreen(Screen parent, String title, int initialArgb, int defaultArgb, IntConsumer onChange) {
        super(Component.literal(title));
        this.parent = parent;
        this.defaultArgb = defaultArgb;
        this.onChange = onChange;
        setFromArgb(initialArgb);
    }

    private void setFromArgb(int argb) {
        this.alpha = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8) & 0xFF) / 255f;
        float b = (argb & 0xFF) / 255f;
        float[] hsv = java.awt.Color.RGBtoHSB((int) (r * 255), (int) (g * 255), (int) (b * 255), null);
        this.hue = hsv[0] * 360f;
        this.saturation = hsv[1];
        this.value = hsv[2];
    }

    private int currentArgb() {
        int rgb = java.awt.Color.HSBtoRGB(hue / 360f, saturation, value);
        int a = Math.round(alpha * 255) << 24;
        return a | (rgb & 0x00FFFFFF);
    }

    private void applyLive() {
        onChange.accept(currentArgb());
    }

    @Override
    protected void init() {
        panelW = 200;
        panelH = 260;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        svSize = 150;
        svX = panelX + (panelW - svSize) / 2;
        svY = panelY + 30;

        hueBarX = svX;
        hueBarY = svY + svSize + 10;
        hueBarW = svSize;
        hueBarH = 16;

        int buttonY = hueBarY + hueBarH + 40;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Set Default"), btn -> {
                    setFromArgb(defaultArgb);
                    applyLive();
                }).bounds(panelX + 10, buttonY, (panelW - 30) / 2, 20).build());

        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(panelX + 20 + (panelW - 30) / 2, buttonY, (panelW - 30) / 2, 20).build());

        double alphaNorm = alpha;
        this.addRenderableWidget(new ThemedSliderButton(panelX + 10, buttonY - 24, panelW - 20, 18,
                Component.literal(String.format(java.util.Locale.US, "Alpha: %.0f%%", alpha * 100)), alphaNorm) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(String.format(java.util.Locale.US, "Alpha: %.0f%%", alpha * 100)));
            }

            @Override
            protected void applyValue() {
                alpha = (float) this.value;
                applyLive();
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF0D0D0D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF553311);
        graphics.centeredText(this.font, this.title, panelX + panelW / 2, panelY + 10, 0xFFCC6600);

        // Saturation/Value square for the current hue - top-left = white (sat 0, val 1), bottom-left =
        // black (val 0), right edge = fully saturated. Coarse 4px steps to keep fill-call count sane.
        int step = 4;
        for (int px = 0; px < svSize; px += step) {
            for (int py = 0; py < svSize; py += step) {
                float s = px / (float) svSize;
                float v = 1f - py / (float) svSize;
                int rgb = java.awt.Color.HSBtoRGB(hue / 360f, s, v);
                graphics.fill(svX + px, svY + py, svX + px + step, svY + py + step, 0xFF000000 | (rgb & 0xFFFFFF));
            }
        }
        graphics.outline(svX, svY, svSize, svSize, 0xFF000000);
        int cursorX = svX + Math.round(saturation * svSize);
        int cursorY = svY + Math.round((1f - value) * svSize);
        drawCursorRing(graphics, cursorX, cursorY);

        // Hue bar - one thin vertical strip per few pixels across the full 0-360 range.
        for (int px = 0; px < hueBarW; px += 2) {
            float h = px / (float) hueBarW;
            int rgb = java.awt.Color.HSBtoRGB(h, 1f, 1f);
            graphics.fill(hueBarX + px, hueBarY, hueBarX + px + 2, hueBarY + hueBarH, 0xFF000000 | (rgb & 0xFFFFFF));
        }
        graphics.outline(hueBarX, hueBarY, hueBarW, hueBarH, 0xFF000000);
        int hueCursorX = hueBarX + Math.round((hue / 360f) * hueBarW);
        graphics.fill(hueCursorX - 1, hueBarY - 2, hueCursorX + 1, hueBarY + hueBarH + 2, 0xFFFFFFFF);

        int previewSize = 20;
        int previewX = panelX + panelW - previewSize - 10;
        int previewY = panelY + 10;
        graphics.fill(previewX, previewY, previewX + previewSize, previewY + previewSize, currentArgb());
        graphics.outline(previewX, previewY, previewSize, previewSize, 0xFFFFFFFF);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void drawCursorRing(GuiGraphicsExtractor graphics, int x, int y) {
        int r = 4;
        graphics.fill(x - r, y - 1, x + r, y + 1, 0xFFFFFFFF);
        graphics.fill(x - 1, y - r, x + 1, y + r, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            if (isWithin(event.x(), event.y(), svX, svY, svSize, svSize)) {
                draggingSv = true;
                updateSv(event.x(), event.y());
                return true;
            }
            if (isWithin(event.x(), event.y(), hueBarX, hueBarY, hueBarW, hueBarH)) {
                draggingHue = true;
                updateHue(event.x());
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSv) {
            updateSv(event.x(), event.y());
            return true;
        }
        if (draggingHue) {
            updateHue(event.x());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingSv = false;
        draggingHue = false;
        return super.mouseReleased(event);
    }

    private boolean isWithin(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    private void updateSv(double mouseX, double mouseY) {
        saturation = clamp01((float) ((mouseX - svX) / svSize));
        value = 1f - clamp01((float) ((mouseY - svY) / svSize));
        applyLive();
    }

    private void updateHue(double mouseX) {
        hue = clamp01((float) ((mouseX - hueBarX) / hueBarW)) * 360f;
        applyLive();
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
