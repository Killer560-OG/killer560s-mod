package com.killer560.hub.gui.profit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * One clickable tracker card on the {@link ProfitHubScreen}: the whole box is the button, so a click anywhere on it
 * opens the tracker. Name on top, a live one-line summary under it, and the {@code /profit <name>} form in the
 * corner so the command is learned from the screen that replaces it. Its message is the tracker's name.
 */
public final class ProfitCardWidget extends AbstractWidget {

    private final ProfitTracker tracker;
    private final Runnable onPress;

    public ProfitCardWidget(int x, int y, int w, int h, ProfitTracker tracker, Runnable onPress) {
        super(x, y, w, h, Component.literal(tracker.label));
        this.tracker = tracker;
        this.onPress = onPress;
    }

    public ProfitTracker tracker() {
        return tracker;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        var font = Minecraft.getInstance().font;
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        g.fill(x, y, x + w, y + h, isHovered ? 0xFF1C1208 : ProfitPanels.PANEL_BG);
        g.outline(x, y, w, h, isHovered ? ProfitPanels.ACCENT : ProfitPanels.BORDER);
        g.fill(x, y, x + 2, y + h, ProfitPanels.ACCENT);
        g.text(font, font.plainSubstrByWidth(tracker.label, w - 20), x + 10, y + 8, ProfitPanels.TEXT, false);
        g.text(font, font.plainSubstrByWidth(tracker.blurb, w - 20), x + 10, y + 20, ProfitPanels.DIM, false);
        ProfitTracker.Summary s = tracker.summary();
        g.text(font, font.plainSubstrByWidth(s.text(), w - 20), x + 10, y + 34, s.color(), false);
        String cmd = "/profit " + tracker.command();
        g.text(font, cmd, x + w - 8 - font.width(cmd), y + h - 12, ProfitPanels.ACCENT, false);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        onPress.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        output.add(NarratedElementType.TITLE, getMessage());
    }
}
