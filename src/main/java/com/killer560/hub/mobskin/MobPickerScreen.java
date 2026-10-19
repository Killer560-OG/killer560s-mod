package com.killer560.hub.mobskin;

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
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The "My Mob" picker (killer560, 2026-10-18: a custom menu with a search bar in place of the old cycling button). Lists
 * the mobs this version has, Player first, and narrows as you type: any word typed that appears in a mob's name or id
 * matches, case-insensitive. Click a row to pick it; the choice goes to {@code onPick} and the screen returns to its
 * parent (the Player Cosmetics tab) at once. Escape goes back without changing anything.
 * <p>
 * The stored value is a {@link MobSkins} id, or "" for the normal player, exactly as the cycling button stored it.
 */
public final class MobPickerScreen extends Screen implements MenuThemedScreen {

    /** The entry for the normal player: stored as "". */
    private static final String PLAYER = "";

    private static final int PANEL_MAX_W = 300;
    private static final int PANEL_MAX_H = 260;
    private static final int ROW_H = 20;

    private final Screen parent;
    private final String current;
    private final Consumer<String> onPick;
    private final List<String> all = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    private String query = "";
    private int scroll;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int listY;
    private int rows;
    private EditBox search;

    /**
     * @param parent  the screen to return to on pick or Escape (the Player Cosmetics tab's screen)
     * @param current the stored choice ({@link MobSkins} id, or "" for the player), shown as the selected row
     * @param onPick  receives the picked id ("" for the player)
     */
    public MobPickerScreen(Screen parent, String current, Consumer<String> onPick) {
        super(Component.literal("My Mob"));
        this.parent = parent;
        this.current = current == null ? PLAYER : current;
        this.onPick = onPick;
    }

    @Override
    protected void init() {
        panelW = Math.min(PANEL_MAX_W, this.width - 8);
        panelH = Math.min(PANEL_MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = Math.max(2, (this.height - panelH) / 2);
        listY = panelY + 46;
        rows = Math.max(1, (panelH - 46 - 8) / ROW_H);

        all.clear();
        all.add(PLAYER);
        all.addAll(MobSkins.available());
        all.addAll(MobSkins.petIds());
        applyFilter();

        search = new ThemedEditBox(this.font, panelX + 8, panelY + 24, panelW - 16, 18, Component.literal("Search"))
                .hint("search mobs by name");
        search.setMaxLength(64);
        search.setResponder(text -> {
            query = text;
            scroll = 0;
            applyFilter();
        });
        search.setValue(query);
        addRenderableWidget(search);
        setFocused(search);
        search.setFocused(true);

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 7 - 48, panelY + 3, 48, 14).build());
    }

    private void applyFilter() {
        shown.clear();
        for (String id : all) {
            if (matches(id, query)) {
                shown.add(id);
            }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rows)));
    }

    /** True when the query is blank or any of its words is in the mob's name or id (case-insensitive). */
    static boolean matches(String id, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String name = label(id).toLowerCase(Locale.ROOT);
        String wireId = id.replace('_', ' ');
        for (String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty() && (name.contains(word) || wireId.contains(word))) {
                return true;
            }
        }
        return false;
    }

    private static String label(String id) {
        return id.isEmpty() ? "Player" : MobSkins.displayName(id);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "My Mob");

        boolean scrollable = shown.size() > rows;
        int rowW = panelW - 16;
        for (int row = 0; row < rows && scroll + row < shown.size(); row++) {
            String id = shown.get(scroll + row);
            int ry = listY + row * ROW_H;
            boolean over = inRow(mouseX, mouseY, ry, rowW);
            boolean picked = id.equals(current);
            ScreenChrome.row(graphics, panelX + 8, ry, rowW, ROW_H, over, picked, row % 2 == 1);
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(label(id), rowW - 12 - 70)),
                    panelX + 12, ry + 6, over || picked ? MenuStyle.OR_HOVER : MenuStyle.TXT);
            if (!id.isEmpty() && !MobSkins.isPet(id)) {
                String wire = this.font.plainSubstrByWidth(id, 64);
                int wireRight = panelX + 8 + rowW - (scrollable ? 14 : 6);
                MenuStyle.text(graphics, wire, wireRight - this.font.width(wire), ry + 6, MenuStyle.DIM);
            }
        }
        if (scrollable) {
            ScreenChrome.scrollbar(graphics, panelX + panelW - 12, listY, rows * ROW_H, scroll, shown.size() - rows, rows);
        }
        if (shown.isEmpty()) {
            MenuStyle.text(graphics, "No mob matches that search.", panelX + 8, listY + 4, MenuStyle.DIM);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private boolean inRow(double x, double y, int ry, int rowW) {
        return x >= panelX + 8 && x < panelX + 8 + rowW && y >= ry && y < ry + ROW_H;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        int rowW = panelW - 16;
        if (x >= panelX + 8 && x < panelX + 8 + rowW && y >= listY && y < listY + rows * ROW_H) {
            int idx = scroll + (int) ((y - listY) / ROW_H);
            if (idx >= 0 && idx < shown.size()) {
                onPick.accept(shown.get(idx));
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
}
