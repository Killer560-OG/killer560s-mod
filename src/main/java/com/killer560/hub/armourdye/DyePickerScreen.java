package com.killer560.hub.armourdye;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The Dye list of the Custom Color page (killer560, 2026-10-19: "a dye option to custom color that pulls all of the
 * hypixel dyes"). The dyes come from Hypixel's own item list ({@link SkyblockItemRepository}): an item whose id starts
 * with {@code DYE_} or whose name ends in " Dye", and that carries a {@code color}. A dye without a colour in Hypixel's
 * data is left out - no colour is ever made up here. Picking a row hands its colour (opaque ARGB) to {@code onPick}.
 */
public final class DyePickerScreen extends Screen implements MenuThemedScreen {

    private static final int PANEL_MAX_W = 260;
    private static final int PANEL_MAX_H = 260;
    private static final int ROW_H = 18;

    private final Screen parent;
    private final IntConsumer onPick;
    private final List<SkyblockItemEntry> dyes = new ArrayList<>();
    private int scroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int rows;

    public DyePickerScreen(Screen parent, IntConsumer onPick) {
        super(Component.literal("Dyes"));
        this.parent = parent;
        this.onPick = onPick;
        for (SkyblockItemEntry item : SkyblockItemRepository.getItems()) {
            if (isDye(item)) {
                dyes.add(item);
            }
        }
        dyes.sort(Comparator.comparing(SkyblockItemEntry::name, String.CASE_INSENSITIVE_ORDER));
    }

    /** A Hypixel dye that has a colour to apply. */
    static boolean isDye(SkyblockItemEntry item) {
        if (item.dyeColor() == null) {
            return false;
        }
        return item.id().startsWith("DYE_") || item.name().endsWith(" Dye");
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_W, this.width - 8);
        panelH = Math.min(PANEL_MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listY = panelY + 28;
        rows = Math.max(1, (panelH - 28 - 8) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, dyes.size() - rows)));
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 7 - 48, panelY + 3, 48, 14).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "Dyes (" + dyes.size() + ")");

        for (int row = 0; row < rows && scroll + row < dyes.size(); row++) {
            SkyblockItemEntry dye = dyes.get(scroll + row);
            int ry = listY + row * ROW_H;
            boolean over = mouseX >= panelX + 8 && mouseX < panelX + panelW - 8 && mouseY >= ry && mouseY < ry + ROW_H;
            ScreenChrome.row(graphics, panelX + 8, ry, panelW - 16, ROW_H, over, false, row % 2 == 1);
            graphics.fill(panelX + 12, ry + 3, panelX + 24, ry + ROW_H - 3, 0xFF000000 | dye.dyeColor());
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(dye.name(), panelW - 48)),
                    panelX + 30, ry + 5, MenuStyle.TXT);
        }
        if (dyes.isEmpty()) {
            MenuStyle.text(graphics, this.font.plainSubstrByWidth(
                    SkyblockItemRepository.isRefreshing() ? "Loading Hypixel's item list..."
                            : "Hypixel's item list has no dyes with a colour.", panelW - 16),
                    panelX + 8, listY + 4, MenuStyle.DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (x >= panelX + 8 && x < panelX + panelW - 8 && y >= listY && y < listY + rows * ROW_H) {
            int idx = scroll + (int) ((y - listY) / ROW_H);
            if (idx >= 0 && idx < dyes.size()) {
                onPick.accept(0xFF000000 | dyes.get(idx).dyeColor());
                onClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, dyes.size() - rows)));
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
