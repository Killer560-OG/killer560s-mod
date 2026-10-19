package com.killer560.hub.bazaaradvisor;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The {@code /bzflip} setup menu (killer560, 2026-10-19: "it opens an option for npc or for bazaar ... then asks total profit
 * or best percentage ... insta buy or buy order"). Four steps, one question each, two buttons per question. Choosing an
 * answer stores it at once; the last step closes the menu and prints the summary in chat.
 */
public final class BazaarAdvisorSetupScreen extends Screen implements MenuThemedScreen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 110;

    private int step;
    private int panelX;
    private int panelY;

    public BazaarAdvisorSetupScreen() {
        super(Component.literal("Bazaar Advisor Setup"));
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = Math.max(2, (this.height - PANEL_H) / 2);
        buildButtons();
    }

    private void buildButtons() {
        this.clearWidgets();
        int bx = panelX + 12;
        int bw = (PANEL_W - 36) / 2;
        int by = panelY + 60;
        switch (step) {
            case 0 -> {
                addButton(bx, by, bw, "NPC", () -> answer(c -> c.setMode(BazaarAdvisorConfig.Mode.NPC), "NPC"));
                addButton(bx + bw + 12, by, bw, "Bazaar", () -> answer(c -> c.setMode(BazaarAdvisorConfig.Mode.BAZAAR), "Bazaar"));
            }
            case 1 -> {
                addButton(bx, by, bw, "Total profit",
                        () -> answer(c -> c.setGoal(BazaarAdvisorConfig.Goal.TOTAL_PROFIT), "Total profit"));
                addButton(bx + bw + 12, by, bw, "Best profit %",
                        () -> answer(c -> c.setGoal(BazaarAdvisorConfig.Goal.PROFIT_PERCENT), "Best profit %"));
            }
            case 2 -> {
                addButton(bx, by, bw, "Insta Buy", () -> answer(c -> c.setBuyMethod(BazaarAdvisorConfig.BuyMethod.INSTA_BUY), "Insta Buy"));
                addButton(bx + bw + 12, by, bw, "Buy Order", () -> answer(c -> c.setBuyMethod(BazaarAdvisorConfig.BuyMethod.BUY_ORDER), "Buy Order"));
            }
            default -> {
                addButton(bx, by, bw, "Insta Sell", () -> answer(c -> c.setSellMethod(BazaarAdvisorConfig.SellMethod.INSTA_SELL), "Insta Sell"));
                addButton(bx + bw + 12, by, bw, "Sell Offer", () -> answer(c -> c.setSellMethod(BazaarAdvisorConfig.SellMethod.SELL_OFFER), "Sell Offer"));
            }
        }
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Close"), b -> onClose())
                .bounds(panelX + PANEL_W - 52, panelY + 3, 44, 16).build());
        // Use last (killer560, 2026-10-19: "on every turn on have it make me select ... or have a button for use last"):
        // keeps the saved answers and carries on without asking again.
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Use last"), b -> useLast())
                .bounds(panelX + 12, panelY + PANEL_H - 26, PANEL_W - 24, 20).build());
    }

    /** Keeps the saved answers. The Flipper's switch uses this screen every time it turns on. */
    private void useLast() {
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        ModChat.send("Bazaar Advisor", ModChat.text("Using last answers: "), ModChat.value(cfg.summary()));
        onClose();
    }

    private void addButton(int x, int y, int w, String label, Runnable action) {
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, w, 20).build());
    }

    /** Stores one answer and moves on. The step after the last one finishes the setup. */
    private void answer(java.util.function.Consumer<BazaarAdvisorConfig> change, String label) {
        BazaarAdvisorConfig cfg = BazaarAdvisorConfig.getInstance();
        change.accept(cfg);
        cfg.save();
        if (step < 3) {
            step++;
            buildButtons();
            return;
        }
        ModChat.send("Bazaar Advisor", ModChat.text("Setup saved: "), ModChat.value(cfg.summary()));
        if (!cfg.isEnabled()) {
            ModChat.send("Bazaar Advisor", ModChat.dim("The advisor is off - turn it on in Items > Bazaar Advisor."));
        }
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, PANEL_W, PANEL_H);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, PANEL_W - 6, 16, "Bazaar Advisor - question " + (step + 1) + " of 4");
        String[] questions = {
                "Where do you sell?",
                "What should it optimise?",
                "How do you buy?",
                "How do you sell on the Bazaar?"
        };
        MenuStyle.text(graphics, questions[step], panelX + 12, panelY + 28, MenuStyle.TXT);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        com.killer560.hub.compat.McCompat.setScreen(Minecraft.getInstance(), null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, PANEL_W, PANEL_H};
    }
}
