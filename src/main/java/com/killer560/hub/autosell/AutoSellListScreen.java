package com.killer560.hub.autosell;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Auto Sell sell list as a menu (killer560, 2026-10-18: "/autosell list ... open a custom menu where you can unadd or
 * add items to the list"). Lists every identity on the sell list. Type an identity and press Add, or Add Held for the item
 * in your main hand; click a row to take it off the list. Opened by {@code /autosell list} and the Auto Sell tab's button.
 */
public final class AutoSellListScreen extends Screen implements MenuThemedScreen {

    private static final String FEATURE = "AutoSell";
    private static final int PANEL_MAX_W = 300;
    private static final int PANEL_MAX_H = 260;
    private static final int ROW_H = 20;

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int rows;
    private int scroll;
    private EditBox input;

    public AutoSellListScreen(Screen parent) {
        super(Component.literal("Auto Sell List"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_W, this.width - 8);
        panelH = Math.min(PANEL_MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        listY = panelY + 50;
        rows = Math.max(1, (panelH - 50 - 22) / ROW_H);

        input = new ThemedEditBox(this.font, panelX + 8, panelY + 24, panelW - 16 - 120, 18, Component.literal("Identity"))
                .hint("item identity, e.g. ENCHANTED_REDSTONE_BLOCK");
        input.setMaxLength(64);
        addRenderableWidget(input);

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Add"), b -> addTyped())
                .bounds(panelX + panelW - 8 - 56, panelY + 24, 56, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Add Held"), b -> addHeld())
                .bounds(panelX + 8, panelY + 26 + 18, 90, 16).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 52, panelY + 3, 44, 16).build());
        setFocused(input);
        input.setFocused(true);
    }

    private List<String> identities() {
        return new ArrayList<>(AutoSellConfig.getInstance().getSellIdentities());
    }

    private void addTyped() {
        String raw = input.getValue() == null ? "" : input.getValue().trim();
        if (raw.isEmpty()) {
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        boolean added = cfg.addSellIdentity(raw);
        cfg.save();
        ModChat.send(FEATURE, added ? ModChat.good("Added ") : ModChat.text("Already on the list: "), ModChat.value(raw.toUpperCase()));
        input.setValue("");
    }

    private void addHeld() {
        ItemStack held = Minecraft.getInstance().player == null ? ItemStack.EMPTY
                : Minecraft.getInstance().player.getMainHandItem();
        String identity = held.isEmpty() ? null : ItemIdentity.of(held);
        if (identity == null) {
            ModChat.send(FEATURE, ModChat.bad("Hold an item in your main hand first."));
            return;
        }
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        boolean added = cfg.addSellIdentity(identity);
        cfg.save();
        ModChat.send(FEATURE, added ? ModChat.good("Added ") : ModChat.text("Already on the list: "), ModChat.value(identity));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "Auto Sell List");

        List<String> all = identities();
        boolean scrollable = all.size() > rows;
        int rowW = panelW - 16;
        for (int row = 0; row < rows && scroll + row < all.size(); row++) {
            String id = all.get(scroll + row);
            int ry = listY + row * ROW_H;
            boolean over = mouseX >= panelX + 8 && mouseX < panelX + 8 + rowW && mouseY >= ry && mouseY < ry + ROW_H;
            ScreenChrome.row(graphics, panelX + 8, ry, rowW, ROW_H, over, false, row % 2 == 1);
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(id, rowW - 12)),
                    panelX + 12, ry + 6, over ? MenuStyle.OR_HOVER : MenuStyle.TXT);
        }
        if (scrollable) {
            ScreenChrome.scrollbar(graphics, panelX + panelW - 12, listY, rows * ROW_H, scroll, all.size() - rows, rows);
        }
        if (all.isEmpty()) {
            MenuStyle.text(graphics, "The sell list is empty.", panelX + 8, listY + 4, MenuStyle.DIM);
        }
        MenuStyle.text(graphics, "Click an item to take it off the list.", panelX + 8, panelY + panelH - 12, MenuStyle.DIM);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        List<String> all = identities();
        int rowW = panelW - 16;
        if (x >= panelX + 8 && x < panelX + 8 + rowW && y >= listY && y < listY + rows * ROW_H) {
            int idx = scroll + (int) ((y - listY) / ROW_H);
            if (idx >= 0 && idx < all.size()) {
                String id = all.get(idx);
                AutoSellConfig cfg = AutoSellConfig.getInstance();
                cfg.removeSellIdentity(id);
                cfg.save();
                ModChat.send(FEATURE, ModChat.text("Removed "), ModChat.value(id), ModChat.text(" from the sell list."));
                scroll = Math.max(0, Math.min(scroll, Math.max(0, all.size() - 1 - rows)));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = Math.max(0, identities().size() - rows);
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), max));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }
}
