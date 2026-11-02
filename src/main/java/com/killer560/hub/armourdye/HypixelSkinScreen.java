package com.killer560.hub.armourdye;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Hypixel item skins picker (2026-10-18, killer560: "custom hypixel item skins if they exist"; rebuilt 2026-11-01 on
 * {@link ItemSkins}). Lists the skins that can be applied to the selected item - matched by its Skyblock id, so a starred,
 * recombed or fragged item finds its skins too - with the current one marked. Pick one and it becomes the item's Skin;
 * search narrows the list, any word typed matching. Returns to the Custom Color page on pick or Escape.
 */
public final class HypixelSkinScreen extends Screen implements MenuThemedScreen {

    private static final int PANEL_MAX_W = 340;
    private static final int PANEL_MAX_H = 280;
    private static final int ROW_H = 20;

    private final Screen parent;
    private final Consumer<ItemSkins.Skin> onPick;
    private final List<ItemSkins.Skin> all;
    private final String current;
    private final List<ItemSkins.Skin> shown = new ArrayList<>();
    private String query = "";
    private int scroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int rows;
    private EditBox search;

    public HypixelSkinScreen(Screen parent, List<ItemSkins.Skin> skins, String current, Consumer<ItemSkins.Skin> onPick) {
        super(Component.literal("Hypixel Skins"));
        this.parent = parent;
        this.all = skins == null ? List.of() : skins;
        this.current = current == null ? "" : current;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_W, this.width - 8);
        panelH = Math.min(PANEL_MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        listY = panelY + 48;
        rows = Math.max(1, (panelH - 48 - 22) / ROW_H);
        applyFilter();

        search = new ThemedEditBox(this.font, panelX + 8, panelY + 24, panelW - 16, 18, Component.literal("Search"))
                .hint("search skins by name");
        search.setMaxLength(64);
        search.setResponder(text -> {
            query = text;
            scroll = 0;
            applyFilter();
        });
        search.setValue(query);
        addRenderableWidget(search);

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 7 - 48, panelY + 3, 48, 14).build());
    }

    private void applyFilter() {
        shown.clear();
        for (ItemSkins.Skin skin : all) {
            if (CustomItemsScreen.matchesQuery(skin.name(), query)) {
                shown.add(skin);
            }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rows)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "Hypixel Skins");
        if (all.isEmpty()) {
            MenuStyle.text(graphics, "No skins for this item.", panelX + 8, panelY + 44, MenuStyle.DIM);
        }

        for (int row = 0; row < rows && scroll + row < shown.size(); row++) {
            ItemSkins.Skin skin = shown.get(scroll + row);
            int ry = listY + row * ROW_H;
            boolean selected = skin.name().equals(current);
            boolean over = mouseX >= panelX + 8 && mouseX < panelX + panelW - 8 && mouseY >= ry && mouseY < ry + ROW_H;
            ScreenChrome.row(graphics, panelX + 8, ry, panelW - 16, ROW_H, over, selected, row % 2 == 1);
            String text = (selected ? "> " : "  ") + skin.name();
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(text, panelW - 24)),
                    panelX + 12, ry + 6, over || selected ? MenuStyle.OR_HOVER : MenuStyle.TXT);
        }
        if (shown.isEmpty() && !all.isEmpty()) {
            MenuStyle.text(graphics, "No skin matches that search.", panelX + 8, listY + 4, MenuStyle.DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (x >= panelX + 8 && x < panelX + panelW - 8 && y >= listY && y < listY + rows * ROW_H) {
            int idx = scroll + (int) ((y - listY) / ROW_H);
            if (idx >= 0 && idx < shown.size()) {
                ItemSkins.Skin picked = shown.get(idx);
                onPick.accept(picked);
                onClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, shown.size() - rows)));
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

    @Override
    public String themeId() {
        return "custom_items";
    }
}
