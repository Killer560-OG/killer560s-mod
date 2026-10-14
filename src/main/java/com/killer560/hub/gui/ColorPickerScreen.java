package com.killer560.hub.gui;

import com.killer560.hub.hud.GuiRects;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.IntConsumer;
import com.killer560.hub.compat.McCompat;

/**
 * A real HSV color picker (hue bar + saturation/value square + alpha slider) - killer560's "click on
 * it then it opens a color wheel" request. Every colour setting in the mod opens this one screen.
 * <p>
 * Live-previews as you drag (calls {@code onChange} immediately, same as every other live-updating
 * setting in this mod) rather than only applying on "Done". "Set Default" resets to whatever default
 * ARGB the caller passed in, independent of Done/Cancel.
 * <p>
 * Moving colours between menus (killer560, 2026-10-13): a hex box that shows the colour and takes a typed one, Copy
 * (the colour as {@code #RRGGBB}, or {@code #AARRGGBB} when it is translucent, onto the system clipboard) and Paste
 * (anything {@link ColourHex#parse} reads; anything else is refused with a red line and changes nothing), Ctrl+C /
 * Ctrl+V for both while the hex box is not focused, and a Recent row ({@link RecentColours}: the last eight colours
 * picked, copied or pasted in any picker) where a click applies that colour. A colour without alpha keeps this
 * setting's own alpha.
 * <p>
 * The exact ARGB is kept in {@link #argb}; the HSV fields only drive the square and the bar, so a pasted
 * {@code #123456} is saved as exactly that and not as its HSV round trip.
 */
public class ColorPickerScreen extends Screen {

    /** Tooltip scope for this screen's buttons in {@code SettingTooltipsData} ("colour picker/copy" ...). */
    static final String TOOLTIP_SCOPE = "color picker";
    private static final long STATUS_MS = 3000L;
    private static final int SWATCH = 16;
    private static final int SWATCH_GAP = 4;

    private final Screen parent;
    private final int defaultArgb;
    private final int initialArgb;
    private final IntConsumer onChange;

    private int argb;
    private float hue;
    private float saturation;
    private float value;
    private float alpha;

    private int panelX, panelY, panelW, panelH;
    private int svX, svY, svSize;
    private int hueBarX, hueBarY, hueBarW, hueBarH;
    private int statusY, recentX, recentY;
    private boolean draggingSv = false;
    private boolean draggingHue = false;

    private EditBox hexBox;
    private AbstractWidget copyButton;
    private AbstractWidget pasteButton;
    private AlphaSlider alphaSlider;
    /** True while the hex box is being written by the screen itself, so its responder does not answer it. */
    private boolean syncingHex = false;
    /** True while a typed hex is being applied, so the box is not rewritten under the cursor. */
    private boolean fromHexBox = false;

    private String status = "";
    private boolean statusBad = false;
    private long statusUntil = 0L;

    private AbstractWidget tooltipWidget;
    private int tooltipSwatch = -1;
    private long tooltipSinceMs;

    public ColorPickerScreen(Screen parent, String title, int initialArgb, int defaultArgb, IntConsumer onChange) {
        super(Component.literal(title));
        this.parent = parent;
        this.defaultArgb = defaultArgb;
        this.initialArgb = initialArgb;
        this.onChange = onChange;
        setFromArgb(initialArgb);
    }

    private void setFromArgb(int c) {
        this.argb = c;
        this.alpha = ((c >>> 24) & 0xFF) / 255f;
        float[] hsv = java.awt.Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
        this.hue = hsv[0] * 360f;
        this.saturation = hsv[1];
        this.value = hsv[2];
    }

    /** After the square, bar or alpha moved: the ARGB from HSV and alpha. */
    private void argbFromHsv() {
        int rgb = java.awt.Color.HSBtoRGB(hue / 360f, saturation, value);
        argb = (Math.round(alpha * 255) << 24) | (rgb & 0x00FFFFFF);
    }

    public int currentArgb() {
        return argb;
    }

    private void applyLive() {
        onChange.accept(argb);
        syncWidgets();
    }

    /** Puts a colour set from outside the square (paste, recent, default, typed hex) on every control and applies it. */
    private void applyArgb(int c) {
        setFromArgb(c);
        applyLive();
    }

    private void syncWidgets() {
        if (hexBox != null && !fromHexBox) {
            syncingHex = true;
            try {
                hexBox.setValue(ColourHex.format(argb));
            } finally {
                syncingHex = false;
            }
        }
        if (alphaSlider != null) {
            alphaSlider.sync(alpha);
        }
    }

    private void setStatus(String text, boolean bad) {
        status = text;
        statusBad = bad;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    /** Copy: the colour onto the system clipboard and into Recent. */
    public void copyColour() {
        String hex = ColourHex.format(argb);
        try {
            this.minecraft.keyboardHandler.setClipboard(hex);
        } catch (RuntimeException e) {
            setStatus("Could not reach the clipboard", true);
            return;
        }
        RecentColours.getInstance().add(argb);
        setStatus("Copied " + hex, false);
    }

    /** Paste: the clipboard's colour, if it is one; otherwise a red line and nothing changes. Never throws. */
    public boolean pasteColour() {
        String clip;
        try {
            clip = this.minecraft.keyboardHandler.getClipboard();
        } catch (RuntimeException e) {
            clip = null;
        }
        ColourHex.Parsed p = ColourHex.parse(clip);
        if (p == null) {
            setStatus("Clipboard is not a color", true);
            return false;
        }
        applyArgb(ColourHex.applyTo(argb, p));
        RecentColours.getInstance().add(argb);
        setStatus("Pasted " + ColourHex.format(argb), false);
        return true;
    }

    /** Applies Recent colour {@code index} (0 = newest); false when there is none there. */
    public boolean useRecent(int index) {
        List<Integer> recent = RecentColours.getInstance().getRecent();
        if (index < 0 || index >= recent.size()) {
            return false;
        }
        int c = recent.get(index);
        applyArgb(ColourHex.applyTo(argb, new ColourHex.Parsed(c, ((c >>> 24) & 0xFF) != 0xFF)));
        setStatus("Using " + ColourHex.format(argb), false);
        return true;
    }

    /** Recent swatch {@code index}'s box {x, y, w, h} in this screen's layout units. */
    public int[] recentBounds(int index) {
        return new int[]{recentX + index * (SWATCH + SWATCH_GAP), recentY, SWATCH, SWATCH};
    }

    @Override
    protected void init() {
        panelW = 220;
        panelH = 324;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(4, (this.height - panelH) / 2);
        int inX = panelX + 10;
        int inW = panelW - 20;

        svSize = 150;
        svX = panelX + (panelW - svSize) / 2;
        svY = panelY + 30;

        hueBarX = svX;
        hueBarY = svY + svSize + 8;
        hueBarW = svSize;
        hueBarH = 12;

        int alphaY = hueBarY + hueBarH + 8;
        alphaSlider = new AlphaSlider(inX, alphaY, inW, 18, alpha);
        this.addRenderableWidget(alphaSlider);

        int hexY = alphaY + 24;
        int hexW = 84;
        int btnW = (inW - hexW - 8) / 2;
        hexBox = new EditBox(this.font, inX, hexY, hexW, 18, Component.literal("Hex"));
        hexBox.setMaxLength(16);
        syncingHex = true;
        hexBox.setValue(ColourHex.format(argb));
        syncingHex = false;
        hexBox.setResponder(this::onHexTyped);
        this.addRenderableWidget(hexBox);
        copyButton = this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Copy"), btn -> copyColour())
                .bounds(inX + hexW + 4, hexY, btnW, 18).build());
        pasteButton = this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Paste"), btn -> pasteColour())
                .bounds(inX + hexW + 8 + btnW, hexY, inW - hexW - 8 - btnW, 18).build());

        statusY = hexY + 23;
        recentY = statusY + 14;
        recentX = panelX + panelW - 10 - (RecentColours.MAX * SWATCH + (RecentColours.MAX - 1) * SWATCH_GAP);

        int buttonY = recentY + SWATCH + 8;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Set Default"), btn -> applyArgb(defaultArgb))
                .bounds(inX, buttonY, (inW - 10) / 2, 20).build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(inX + (inW - 10) / 2 + 10, buttonY, inW - (inW - 10) / 2 - 10, 20).build());
        panelH = buttonY + 20 + 10 - panelY;
    }

    private void onHexTyped(String text) {
        if (syncingHex) {
            return;
        }
        ColourHex.Parsed p = ColourHex.parse(text);
        if (p == null) {
            if (text != null && !text.isBlank()) {
                setStatus("Not a color: use #RRGGBB", true);
            }
            return;
        }
        fromHexBox = true;
        try {
            applyArgb(ColourHex.applyTo(argb, p));
        } finally {
            fromHexBox = false;
        }
        status = "";
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        MenuStyle.use();
        GuiRects r = GuiRects.begin(graphics);
        r.fill(0, 0, this.width, this.height, MenuStyle.BACKDROP);
        MenuStyle.dropShadow(r, panelX, panelY, panelW, panelH, 3);
        r.outline(panelX - 1, panelY - 1, panelW + 2, panelH + 2, MenuStyle.FRAME_RING);
        MenuStyle.bevel(r, panelX, panelY, panelW, panelH, MenuStyle.PANEL_TOP, MenuStyle.PANEL_BOTTOM);

        // Saturation/Value square for the current hue - top-left = white (sat 0, val 1), bottom-left =
        // black (val 0), right edge = fully saturated. Coarse 4px steps, submitted as one element.
        int step = 4;
        for (int px = 0; px < svSize; px += step) {
            for (int py = 0; py < svSize; py += step) {
                float s = px / (float) svSize;
                float v = 1f - py / (float) svSize;
                int rgb = java.awt.Color.HSBtoRGB(hue / 360f, s, v);
                r.fill(svX + px, svY + py, svX + px + step, svY + py + step, 0xFF000000 | (rgb & 0xFFFFFF));
            }
        }
        r.outline(svX - 1, svY - 1, svSize + 2, svSize + 2, MenuStyle.BLACK);
        int cursorX = svX + Math.round(saturation * svSize);
        int cursorY = svY + Math.round((1f - value) * svSize);
        r.fill(cursorX - 4, cursorY - 1, cursorX + 4, cursorY + 1, 0xFFFFFFFF);
        r.fill(cursorX - 1, cursorY - 4, cursorX + 1, cursorY + 4, 0xFFFFFFFF);

        // Hue bar - one thin vertical strip per few pixels across the full 0-360 range.
        for (int px = 0; px < hueBarW; px += 2) {
            float h = px / (float) hueBarW;
            int rgb = java.awt.Color.HSBtoRGB(h, 1f, 1f);
            r.fill(hueBarX + px, hueBarY, hueBarX + px + 2, hueBarY + hueBarH, 0xFF000000 | (rgb & 0xFFFFFF));
        }
        r.outline(hueBarX - 1, hueBarY - 1, hueBarW + 2, hueBarH + 2, MenuStyle.BLACK);
        int hueCursorX = hueBarX + Math.round((hue / 360f) * hueBarW);
        r.fill(hueCursorX - 1, hueBarY - 2, hueCursorX + 1, hueBarY + hueBarH + 2, 0xFFFFFFFF);

        int previewSize = 20;
        int previewX = panelX + panelW - previewSize - 10;
        int previewY = panelY + 6;
        MenuStyle.well(r, previewX - 1, previewY - 1, previewSize + 2, previewSize + 2, MenuStyle.WELL);
        r.fill(previewX, previewY, previewX + previewSize, previewY + previewSize, argb);
        r.outline(previewX, previewY, previewSize, previewSize, MenuStyle.TXT);

        // Recent: filled swatches newest first, empty wells after them.
        List<Integer> recent = RecentColours.getInstance().getRecent();
        int hovered = recentAt(mouseX, mouseY);
        for (int i = 0; i < RecentColours.MAX; i++) {
            int[] b = recentBounds(i);
            if (i < recent.size()) {
                r.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], 0xFF000000 | recent.get(i));
                r.outline(b[0], b[1], b[2], b[3], i == hovered ? MenuStyle.OR : MenuStyle.BLACK);
                if (i == hovered) {
                    r.outline(b[0] - 1, b[1] - 1, b[2] + 2, b[3] + 2, MenuStyle.OR);
                }
            } else {
                MenuStyle.well(r, b[0], b[1], b[2], b[3], MenuStyle.WELL);
            }
        }
        r.submit();

        MenuStyle.text(graphics, this.title.getString(), panelX + 10, panelY + 12, MenuStyle.OR);
        MenuStyle.text(graphics, "Recent", panelX + 10, recentY + 4, MenuStyle.DIM);
        if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
            int w = this.font.width(status);
            MenuStyle.text(graphics, status, panelX + (panelW - w) / 2, statusY, statusBad ? MenuStyle.RED : MenuStyle.OR);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        drawTooltip(graphics, mouseX, mouseY, hovered, recent);
    }

    private int recentAt(double x, double y) {
        for (int i = 0; i < RecentColours.MAX; i++) {
            int[] b = recentBounds(i);
            if (isWithin(x, y, b[0], b[1], b[2] - 1, b[3] - 1)) {
                return i;
            }
        }
        return -1;
    }

    /** The menu's hover descriptions for this screen's buttons; a Recent swatch names its colour. */
    private void drawTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int swatch, List<Integer> recent) {
        AbstractWidget hovered = null;
        if (swatch < 0) {
            for (var child : this.children()) {
                if (child instanceof AbstractWidget w && w.visible && !(w instanceof EditBox)
                        && mouseX >= w.getX() && mouseX < w.getX() + w.getWidth()
                        && mouseY >= w.getY() && mouseY < w.getY() + w.getHeight()) {
                    hovered = w;
                    break;
                }
            }
        }
        long now = System.currentTimeMillis();
        if (hovered != tooltipWidget || swatch != tooltipSwatch) {
            tooltipWidget = hovered;
            tooltipSwatch = swatch;
            tooltipSinceMs = now;
        }
        String text;
        if (swatch >= 0) {
            text = swatch < recent.size() ? ColourHex.format(recent.get(swatch)) + " - click to use this color" : null;
        } else {
            text = hovered == null ? null : SettingTooltips.describe(TOOLTIP_SCOPE, hovered.getMessage().getString());
        }
        if (text == null || text.isBlank() || now - tooltipSinceMs < 350L) {
            return;
        }
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(text), 200);
        int w = 0;
        for (var line : lines) {
            w = Math.max(w, this.font.width(line));
        }
        int h = lines.size() * 10 - 2;
        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + w + 8 > this.width) {
            x = Math.max(4, mouseX - w - 16);
        }
        if (y + h + 8 > this.height) {
            y = Math.max(4, mouseY - h - 16);
        }
        GuiRects r = GuiRects.begin(graphics);
        r.gradient(x - 4, y - 4, x + w + 4, y + h + 4, MenuStyle.TOOLTIP_TOP, MenuStyle.TOOLTIP_BOTTOM);
        r.outline(x - 4, y - 4, w + 8, h + 8, MenuStyle.OR);
        r.submit();
        int ly = y;
        for (var line : lines) {
            MenuStyle.text(graphics, line, x, ly, MenuStyle.TXT);
            ly += 10;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            if (isWithin(event.x(), event.y(), svX, svY, svSize, svSize)) {
                setFocused(null);
                draggingSv = true;
                updateSv(event.x(), event.y());
                return true;
            }
            if (isWithin(event.x(), event.y(), hueBarX, hueBarY, hueBarW, hueBarH)) {
                setFocused(null);
                draggingHue = true;
                updateHue(event.x());
                return true;
            }
            int swatch = recentAt(event.x(), event.y());
            if (swatch >= 0) {
                setFocused(null);
                useRecent(swatch);
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

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && (hexBox == null || !hexBox.isFocused())) {
            if (event.key() == InputConstants.KEY_C) {
                copyColour();
                return true;
            }
            if (event.key() == InputConstants.KEY_V) {
                pasteColour();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private boolean isWithin(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    private void updateSv(double mouseX, double mouseY) {
        saturation = clamp01((float) ((mouseX - svX) / svSize));
        value = 1f - clamp01((float) ((mouseY - svY) / svSize));
        argbFromHsv();
        applyLive();
    }

    private void updateHue(double mouseX) {
        hue = clamp01((float) ((mouseX - hueBarX) / hueBarW)) * 360f;
        argbFromHsv();
        applyLive();
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    public void onClose() {
        if (argb != initialArgb) {
            RecentColours.getInstance().add(argb);
        }
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The alpha slider; {@link #sync} moves it when the colour changed elsewhere (a paste, a Recent swatch). */
    private final class AlphaSlider extends ThemedSliderButton {
        AlphaSlider(int x, int y, int w, int h, double v) {
            super(x, y, w, h, Component.literal(label((float) v)), v);
        }

        private static String label(float a) {
            return String.format(java.util.Locale.US, "Alpha: %.0f%%", a * 100);
        }

        void sync(float a) {
            this.value = a;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label((float) this.value)));
        }

        @Override
        protected void applyValue() {
            alpha = (float) this.value;
            argb = (Math.round(alpha * 255) << 24) | (argb & 0x00FFFFFF);
            onChange.accept(argb);
            if (hexBox != null) {
                syncingHex = true;
                try {
                    hexBox.setValue(ColourHex.format(argb));
                } finally {
                    syncingHex = false;
                }
            }
        }
    }
}
