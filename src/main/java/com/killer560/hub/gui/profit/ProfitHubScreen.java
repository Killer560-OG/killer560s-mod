package com.killer560.hub.gui.profit;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * {@code /profit}: one card per profit tracker, each showing a live one-line summary, clicking a card opens that
 * tracker's own screen with this hub as its parent (so Back / Escape lands here again).
 *
 * <p>killer560, 2026-10-05: "make it so the command /profit is what shows the custom gui's for things like croesus
 * profit and whatnot. It should load a general screen where i select which tracker I want. Or i can do /profit
 * etable or whatnot to open the menu specifically." Laid out like the Croesus screen (same panel, header and
 * {@link ProfitPanels} colours) so the two read as one family; Auto Scale is the screen mixin's job, nothing here.
 */
public class ProfitHubScreen extends Screen implements ThemedScreen {

    private static final int CARD_H = 54;
    private static final int GAP = 6;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public ProfitHubScreen(Screen parent) {
        super(Component.literal("Profit Trackers"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ProfitTracker[] all = ProfitTracker.values();
        int rows = (all.length + 1) / 2;
        panelW = Math.min(this.width - 20, Math.max(360, Math.min((int) (this.width * 0.7), 560)));
        panelH = Math.min(this.height - 20, 30 + 26 + rows * (CARD_H + GAP) + 28);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        int cardW = (panelW - 12 - GAP) / 2;
        int top = panelY + 30 + 26;
        for (int i = 0; i < all.length; i++) {
            ProfitTracker t = all[i];
            int cx = panelX + 6 + (i % 2) * (cardW + GAP);
            int cy = top + (i / 2) * (CARD_H + GAP);
            addRenderableWidget(new ProfitCardWidget(cx, cy, cardW, CARD_H, t,
                    () -> McCompat.setScreen(this.minecraft, t.open(this))));
        }
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close"), btn -> onClose())
                .bounds(panelX + panelW - 66, panelY + panelH - 24, 60, 18).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.begin("profit");
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX + 4, panelY + 4, panelW - 8, ScreenChrome.HEADER_H, "Profit Trackers");
        String hint = "/profit <name> opens one directly";
        MenuStyle.text(graphics, hint, panelX + panelW - 12 - this.font.width(hint), panelY + 12, MenuStyle.DIM2);
        MenuStyle.text(graphics, "PICK A TRACKER", panelX + 8, panelY + 40, MenuStyle.DIM);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
