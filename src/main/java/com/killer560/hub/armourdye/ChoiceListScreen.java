package com.killer560.hub.armourdye;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * A dropdown list for the Custom Items menu (2026-10-18, killer560: "Trim and Pattern open a dropdown list when clicked,
 * the current one marked, picked by click"). Each value is one row; the blank value is "Off". The current value is
 * highlighted and marked with ">". Clicking a row hands the value to {@code onPick} and returns to the menu.
 */
public final class ChoiceListScreen extends Screen implements MenuThemedScreen {

    private static final int PANEL_MAX_W = 260;
    private static final int PANEL_MAX_H = 260;
    private static final int ROW_H = 18;

    private final Screen parent;
    private final String title;
    private final List<String> values;
    private final String current;
    private final Consumer<String> onPick;
    private int scroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int rows;

    public ChoiceListScreen(Screen parent, String title, List<String> values, String current, Consumer<String> onPick) {
        super(Component.literal(title));
        this.parent = parent;
        this.title = title;
        this.values = values;
        this.current = current == null ? "" : current;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_W, this.width - 8);
        panelH = Math.min(PANEL_MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listY = panelY + 28;
        rows = Math.max(1, (panelH - 28 - 8) / ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, values.size() - rows)));

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 7 - 48, panelY + 3, 48, 14).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, title);

        for (int row = 0; row < rows && scroll + row < values.size(); row++) {
            String value = values.get(scroll + row);
            int ry = listY + row * ROW_H;
            boolean selected = value.equals(current);
            boolean over = mouseX >= panelX + 8 && mouseX < panelX + panelW - 8 && mouseY >= ry && mouseY < ry + ROW_H;
            ScreenChrome.row(graphics, panelX + 8, ry, panelW - 16, ROW_H, over, selected, row % 2 == 1);
            String text = (selected ? "> " : "  ") + (value.isEmpty() ? "Off" : value);
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(text, panelW - 28)),
                    panelX + 12, ry + 5, selected ? MenuStyle.OR_HOVER : MenuStyle.TXT);
        }
        if (values.isEmpty()) {
            MenuStyle.text(graphics, "No choices yet (a world must be loaded).", panelX + 8, listY + 4, MenuStyle.DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (x >= panelX + 8 && x < panelX + panelW - 8 && y >= listY && y < listY + rows * ROW_H) {
            int idx = scroll + (int) ((y - listY) / ROW_H);
            if (idx >= 0 && idx < values.size()) {
                onPick.accept(values.get(idx));
                onClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(scrollY), Math.max(0, values.size() - rows)));
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
