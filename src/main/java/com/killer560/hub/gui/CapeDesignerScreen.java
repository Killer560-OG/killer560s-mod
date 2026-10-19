package com.killer560.hub.gui;

import com.killer560.hub.cape.CapeConfig;
import com.killer560.hub.cape.CustomCapeTexture;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.GuiRects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The Make Your Own cape designer (killer560, 2026-10-19: "make your own should be its own button ... it should open its own menu
 * for you to custom place each pixel"). The grid is the cape's back panel, one square per pixel of the 10 x 16 sheet. Left click
 * (or drag) paints with the brush; right click (or drag) clears a pixel back to the pattern colour. Changes are saved when you
 * leave the screen and reach the cape at once.
 * <p>
 * Copy and Paste move a design as text: 16 rows of 10 entries, each a {@code RRGGBB} colour or {@code -} for a pixel that takes
 * the pattern colour, so a design can be shared or kept in a message.
 */
public class CapeDesignerScreen extends Screen {

    private static final int[] PALETTE = {
            0xFFFFFF, 0x000000, 0xFF3333, 0xFF8800, 0xFFFF33, 0x33CC33, 0x2D6CDF, 0xAA33FF};
    private static final int SWATCH = 16;
    private static final int SWATCH_GAP = 2;
    private static final long STATUS_MS = 3000L;

    private final Screen parent;
    private final CapeConfig cfg = CapeConfig.getInstance();
    private int brush = PALETTE[6];
    private boolean painting;
    private boolean erasing;

    private int cell;
    private int panelX, panelY, panelW, panelH;
    private int gridX, gridY;
    private int paletteY, pickX;
    private String status = "";
    private boolean statusBad;
    private long statusUntil;

    public CapeDesignerScreen(Screen parent) {
        super(Component.literal("Make Your Own"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        cell = Math.max(6, Math.min(14, (this.height - 200) / CapeConfig.PANEL_H));
        int gridW = CapeConfig.PANEL_W * cell;
        int gridH = CapeConfig.PANEL_H * cell;
        panelW = Math.max(gridW + 40, 8 * (SWATCH + SWATCH_GAP) + 20);
        panelH = 22 + gridH + 10 + SWATCH + 10 + 20 + 8 + 20 + 8 + 20 + 8 + 20 + 8 + 20 + 8 + 20 + 12;
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(4, (this.height - panelH) / 2);
        gridX = panelX + (panelW - gridW) / 2;
        gridY = panelY + 22;
        paletteY = gridY + gridH + 10;
        pickX = panelX + 10 + PALETTE.length * (SWATCH + SWATCH_GAP);

        int btnY = paletteY + SWATCH + 10;
        int half = (panelW - 30) / 2;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Clear Design"), btn -> {
                    cfg.clearPixels();
                    cfg.save();
                }).bounds(panelX + 10, btnY, half, 20).build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(panelX + 20 + half, btnY, half, 20).build());
        int copyY = btnY + 28;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Copy"), btn -> copyDesign())
                .bounds(panelX + 10, copyY, half, 20).build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Paste"), btn -> pasteDesign())
                .bounds(panelX + 20 + half, copyY, half, 20).build());
        // The pattern fills every pixel nobody has painted (Make Your Own's layout, killer560 2026-10-19).
        this.addRenderableWidget(SettingsButtonWidget.cycling(patternText(), (btn, dir) -> {
                    CapeConfig.Pattern[] all = CapeConfig.Pattern.values();
                    cfg.setCustomPattern(all[SettingsButtonWidget.step(cfg.getCustomPattern().ordinal(), dir, all.length)]);
                    cfg.save();
                    btn.setMessage(patternText());
                }).bounds(panelX + 10, copyY + 28, panelW - 20, 20).build());

        // Name it and save it; Load steps through the saved designs (killer560, 2026-10-19).
        nameBox = new EditBox(this.font, panelX + 10, copyY + 56, panelW - 20 - 60, 20, Component.literal("Design name"));
        nameBox.setMaxLength(24);
        nameBox.setHint(Component.literal("Name this design"));
        this.addRenderableWidget(nameBox);
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Save"), btn -> saveNamed())
                .bounds(panelX + panelW - 10 - 56, copyY + 56, 56, 20).build());
        this.addRenderableWidget(SettingsButtonWidget.cycling(loadText(), (btn, dir) -> {
                    int n = cfg.getSaved().size();
                    if (n > 0) {
                        loadIndex = Math.floorMod(loadIndex + dir, n);
                        cfg.loadDesign(loadIndex);
                        cfg.save();
                    }
                    btn.setMessage(loadText());
                }).bounds(panelX + 10, copyY + 84, panelW - 20, 20).build());
    }

    private EditBox nameBox;
    private int loadIndex = -1;

    private Component loadText() {
        int n = cfg.getSaved().size();
        if (n == 0) {
            return Component.literal("Load: §7no saved designs");
        }
        int i = Math.max(0, loadIndex);
        return Component.literal("Load: §6" + cfg.getSaved().get(i).name());
    }

    private void saveNamed() {
        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            setStatus("Type a name first", true);
            return;
        }
        cfg.saveDesign(name);
        cfg.save();
        setStatus("Saved \"" + name + "\"", false);
    }

    private Component patternText() {
        return Component.literal("Pattern: §6" + cfg.getCustomPattern().label);
    }

    /** Puts the design on the clipboard as 16 rows of 10 entries. */
    private void copyDesign() {
        StringBuilder b = new StringBuilder();
        for (int y = 0; y < CapeConfig.PANEL_H; y++) {
            for (int x = 0; x < CapeConfig.PANEL_W; x++) {
                int p = cfg.getPixel(x, y);
                b.append(p == CapeConfig.UNSET ? "-" : String.format("%06X", p));
                b.append(x == CapeConfig.PANEL_W - 1 ? "\n" : " ");
            }
        }
        try {
            this.minecraft.keyboardHandler.setClipboard(b.toString().trim());
            setStatus("Design copied", false);
        } catch (RuntimeException e) {
            setStatus("Could not reach the clipboard", true);
        }
    }

    /** Loads a design from the clipboard: exactly 160 entries, each a colour or {@code -}. Anything else changes nothing. */
    private void pasteDesign() {
        String clip;
        try {
            clip = this.minecraft.keyboardHandler.getClipboard();
        } catch (RuntimeException e) {
            clip = null;
        }
        String[] parts = clip == null ? new String[0] : clip.trim().split("\\s+");
        if (parts.length != CapeConfig.PANEL_W * CapeConfig.PANEL_H) {
            setStatus("Clipboard is not a cape design", true);
            return;
        }
        int[] pixels = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i];
            if (t.equals("-")) {
                pixels[i] = CapeConfig.UNSET;
            } else if (t.matches("[0-9A-Fa-f]{6}")) {
                pixels[i] = Integer.parseInt(t, 16);
            } else {
                setStatus("Clipboard is not a cape design", true);
                return;
            }
        }
        for (int i = 0; i < pixels.length; i++) {
            cfg.setPixel(i % CapeConfig.PANEL_W, i / CapeConfig.PANEL_W, pixels[i]);
        }
        cfg.save();
        setStatus("Design pasted", false);
    }

    private void setStatus(String text, boolean bad) {
        status = text;
        statusBad = bad;
        statusUntil = System.currentTimeMillis() + STATUS_MS;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        MenuStyle.use();
        GuiRects r = GuiRects.begin(graphics);
        r.fill(0, 0, this.width, this.height, MenuStyle.BACKDROP);
        MenuStyle.dropShadow(r, panelX, panelY, panelW, panelH, 3);
        r.outline(panelX - 1, panelY - 1, panelW + 2, panelH + 2, MenuStyle.FRAME_RING);
        MenuStyle.panel(r, panelX, panelY, panelW, panelH);

        // The back panel: each square is the colour that pixel will wear (the paint, or the pattern underneath).
        int hovered = pixelAt(mouseX, mouseY);
        for (int y = 0; y < CapeConfig.PANEL_H; y++) {
            for (int x = 0; x < CapeConfig.PANEL_W; x++) {
                int painted = cfg.getPixel(x, y);
                int rgb = painted != CapeConfig.UNSET ? painted : CustomCapeTexture.patternColour(cfg, x, y);
                int px = gridX + x * cell;
                int py = gridY + y * cell;
                r.fill(px, py, px + cell, py + cell, 0xFF000000 | rgb);
            }
        }
        r.outline(gridX - 1, gridY - 1, cell * CapeConfig.PANEL_W + 2, cell * CapeConfig.PANEL_H + 2, MenuStyle.BLACK);
        if (hovered >= 0) {
            int hx = gridX + (hovered % CapeConfig.PANEL_W) * cell;
            int hy = gridY + (hovered / CapeConfig.PANEL_W) * cell;
            r.outline(hx, hy, cell, cell, MenuStyle.OR);
        }

        // Palette, then the brush swatch and a Pick button for any other colour.
        for (int i = 0; i < PALETTE.length; i++) {
            int sx = panelX + 10 + i * (SWATCH + SWATCH_GAP);
            r.fill(sx, paletteY, sx + SWATCH, paletteY + SWATCH, 0xFF000000 | PALETTE[i]);
            r.outline(sx, paletteY, SWATCH, SWATCH, (PALETTE[i] == brush) ? MenuStyle.OR : MenuStyle.BLACK);
        }
        r.fill(pickX, paletteY, pickX + SWATCH, paletteY + SWATCH, 0xFF000000 | brush);
        r.outline(pickX, paletteY, SWATCH, SWATCH, MenuStyle.TXT);
        r.submit();

        // Title on one line, the hint on the line under it, so the two never overlap.
        MenuStyle.text(graphics, this.title.getString(), panelX + 10, panelY + 4, MenuStyle.OR);
        MenuStyle.text(graphics, "Left paints, right clears", panelX + 10, panelY + 13, MenuStyle.DIM);
        if (!status.isEmpty() && System.currentTimeMillis() < statusUntil) {
            int w = this.font.width(status);
            MenuStyle.text(graphics, status, panelX + (panelW - w) / 2, panelY + panelH - 12,
                    statusBad ? MenuStyle.RED : MenuStyle.OR);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** The pixel under the mouse as {@code y * PANEL_W + x}, or -1 when it is off the grid. */
    private int pixelAt(double mouseX, double mouseY) {
        int x = (int) Math.floor((mouseX - gridX) / cell);
        int y = (int) Math.floor((mouseY - gridY) / cell);
        if (x < 0 || y < 0 || x >= CapeConfig.PANEL_W || y >= CapeConfig.PANEL_H) {
            return -1;
        }
        return y * CapeConfig.PANEL_W + x;
    }

    private void applyAt(double mouseX, double mouseY) {
        int p = pixelAt(mouseX, mouseY);
        if (p < 0) {
            return;
        }
        int x = p % CapeConfig.PANEL_W;
        int y = p / CapeConfig.PANEL_W;
        cfg.setPixel(x, y, erasing ? CapeConfig.UNSET : brush);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 || event.button() == 1) {
            if (pixelAt(event.x(), event.y()) >= 0) {
                painting = true;
                erasing = event.button() == 1;
                applyAt(event.x(), event.y());
                return true;
            }
            for (int i = 0; i < PALETTE.length; i++) {
                int sx = panelX + 10 + i * (SWATCH + SWATCH_GAP);
                if (event.button() == 0 && within(event.x(), event.y(), sx, paletteY, SWATCH, SWATCH)) {
                    brush = PALETTE[i];
                    return true;
                }
            }
            if (event.button() == 0 && within(event.x(), event.y(), pickX, paletteY, SWATCH, SWATCH)) {
                McCompat.setScreen(this.minecraft, new ColorPickerScreen(this, "Brush", 0xFF000000 | brush, 0xFF000000 | brush,
                        argb -> brush = argb & 0xFFFFFF));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (painting) {
            applyAt(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (painting) {
            painting = false;
            cfg.save();
            return true;
        }
        return super.mouseReleased(event);
    }

    private static boolean within(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    @Override
    public void onClose() {
        cfg.save();
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
