package com.killer560.hub.autosell;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Auto Sell sell list as a menu (killer560, 2026-10-18: "/autosell list ... open a custom menu where you can unadd or
 * add items to the list"). Lists every identity on the sell list. Type an identity and press Add, Add Held for the item
 * in your main hand, or Add Defaults for his dungeon-junk list; each row's Remove button takes it off the list. Opened by {@code /autosell list} and the Auto Sell tab's button.
 */
public final class AutoSellListScreen extends Screen implements MenuThemedScreen {

    private static final String FEATURE = "AutoSell";
    private static final int PANEL_MAX_W = 320;
    private static final int PANEL_MAX_H = 280;
    private static final int ROW_H = 20;
    private static final int PAD = 8;
    private static final int GAP = 4;
    private static final int REMOVE_W = 54;
    private static final int SCROLLBAR_W = 8;
    /** Header, the input row, the Add Held / Add Defaults row, then the list (killer560, 2026-11-01: "the items are under
     *  the buttons already, and the buttons dont really fit the menu" - the list started 6 px above Add Held's bottom). */
    private static final int LIST_TOP = 70;
    private static final int FOOTER_H = 16;

    private final Screen parent;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int rows;
    private int scroll;
    private com.killer560.hub.gui.CommitEditBox input;

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
        listY = panelY + LIST_TOP;
        rows = Math.max(1, (panelH - LIST_TOP - FOOTER_H) / ROW_H);

        int inner = panelW - PAD * 2;
        int addW = 56;
        input = new ThemedEditBox(this.font, panelX + PAD, panelY + 24, Math.max(40, inner - addW - GAP), 18,
                Component.literal("Identity")).hint("item id, e.g. ENCHANTED_REDSTONE_BLOCK");
        input.setMaxLength(64);
        input.onEnter(this::addTyped);
        addRenderableWidget(input);
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Add"), b -> addTyped())
                .bounds(panelX + panelW - PAD - addW, panelY + 24, addW, 18).build());

        int half = (inner - GAP) / 2;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Add Held"), b -> addHeld())
                .bounds(panelX + PAD, panelY + 46, half, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Add Defaults"), b -> addDefaults())
                .bounds(panelX + PAD + half + GAP, panelY + 46, inner - half - GAP, 18).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 52, panelY + 3, 44, 14).build());
        setFocused(input);
        input.setFocused(true);
    }

    /** The list row's width, leaving room for the scrollbar. */
    private int rowW() {
        return panelW - PAD * 2 - SCROLLBAR_W - GAP;
    }

    private void addDefaults() {
        AutoSellConfig cfg = AutoSellConfig.getInstance();
        int added = cfg.addDefaults();
        cfg.save();
        ModChat.send(FEATURE, ModChat.good("Added "), ModChat.value(added + " default item(s)"),
                ModChat.text(" to the sell list" + (added < AutoSellConfig.DEFAULT_SELL_IDENTITIES.size()
                        ? " (the rest were already on it)." : ".")));
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
        String identity = held.isEmpty() ? null : AutoSellFeature.identityOf(held);
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
        int rowW = rowW();
        for (int row = 0; row < rows && scroll + row < all.size(); row++) {
            String id = all.get(scroll + row);
            int ry = listY + row * ROW_H;
            int rx = panelX + PAD;
            boolean overRow = mouseX >= rx && mouseX < rx + rowW && mouseY >= ry && mouseY < ry + ROW_H;
            ScreenChrome.row(graphics, rx, ry, rowW, ROW_H, false, false, row % 2 == 1);
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(id, rowW - REMOVE_W - 12)),
                    rx + 4, ry + 6, MenuStyle.TXT);
            // the Remove button at the right of each item
            int bx = rx + rowW - REMOVE_W - 2;
            int by = ry + 2;
            boolean overBtn = overRow && mouseX >= bx && mouseX < bx + REMOVE_W && mouseY >= by && mouseY < by + ROW_H - 4;
            graphics.fill(bx, by, bx + REMOVE_W, by + ROW_H - 4, overBtn ? 0xFF7A1F1F : 0xFF4A1414);
            graphics.outline(bx, by, REMOVE_W, ROW_H - 4, overBtn ? 0xFFFF7070 : 0xFF9A3A3A);
            graphics.centeredText(this.font, "Remove", bx + REMOVE_W / 2, by + 4, 0xFFFFFFFF);
        }
        if (scrollable) {
            ScreenChrome.scrollbar(graphics, panelX + panelW - PAD - SCROLLBAR_W, listY, rows * ROW_H, scroll,
                    all.size() - rows, rows);
        }
        if (all.isEmpty()) {
            MenuStyle.text(graphics, "The sell list is empty.", panelX + PAD, listY + 4, MenuStyle.DIM);
        }
        MenuStyle.text(graphics, all.size() + " item(s) on the list", panelX + PAD, panelY + panelH - 12, MenuStyle.DIM);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        List<String> all = identities();
        int rowW = rowW();
        int rx = panelX + PAD;
        int bx = rx + rowW - REMOVE_W - 2;
        if (x >= bx && x < bx + REMOVE_W && y >= listY && y < listY + rows * ROW_H) {
            int row = (int) ((y - listY) / ROW_H);
            int idx = scroll + row;
            double inRow = y - (listY + row * ROW_H);
            if (idx >= 0 && idx < all.size() && inRow >= 2 && inRow < ROW_H - 2) {
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
