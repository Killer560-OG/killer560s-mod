package com.killer560.hub.ap3;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * "Choose AP3 Config" - killer560 (2026-09-21): "I should be able to click into it and select any of the ap3's in
 * my folder, and create new ones through this." A small panel in the mod's own chrome (the same black / amber the
 * color picker and the main menu use, the title in the cheat red because AP3 only exists in the cheat jar): one
 * row per chains file in {@link Ap3Store#directory()} with the one in use marked, a name box + New Config, Done.
 * <p>
 * Every change goes through {@link Ap3Store#select} / {@link Ap3Store#createConfig} - the same code the store
 * itself uses - so picking a file here persists it ({@link Ap3Config#getChainsFile()}), stops a running chain and
 * loads the file exactly as {@code /ap3 reload} would; a name is checked by {@link Ap3Store#validateConfigName}
 * (plain file name, safe characters, never an existing file) and the reason is shown on the panel when it is not
 * accepted. Closing goes back to the mod menu, which rebuilds the AP3 tab so the button shows the new name.
 */
public class Ap3ConfigScreen extends Screen implements MenuThemedScreen {

    /** The Theme setting this screen follows (ScreenThemes; "Theme Default" = the menu's theme). */
    public static final String THEME_ID = "ap3";

    @Override
    public String themeId() {
        return THEME_ID;
    }

    /** {x, y, w, h} of the themed panel as last drawn (the testkit samples it). */
    private int[] themedBounds = new int[4];

    @Override
    public int[] themedPanelBounds() {
        return themedBounds.clone();
    }

    private static final int PANEL_W = 260;
    private static final int PAD = 10;
    private static final int ROW = 18;
    private static final int GAP = 4;
    /** Rows shown at once; the wheel scrolls the rest. */
    private static final int MAX_VISIBLE = 10;
    private static final int NEW_W = 76;

    private final Screen parent;
    private int panelX, panelY, panelW, panelH;
    private int statusY;
    private int scrollRow;
    private List<String> names = List.of();
    /** What is typed in the name box - kept across rebuilds (a failed create must not wipe it). */
    private String newName = "";
    /** The last outcome, drawn under the list: red for a refusal, green for a switch / create. */
    private String status = "";

    public Ap3ConfigScreen(Screen parent) {
        super(Component.literal("Choose AP3 Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        names = Ap3Store.listConfigNames();
        int maxScroll = Math.max(0, names.size() - MAX_VISIBLE);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow));
        int visible = Math.min(MAX_VISIBLE, names.size());

        panelW = PANEL_W;
        panelH = 30 + visible * (ROW + GAP) + 16 + (ROW + GAP) + 6 + 20 + PAD;
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        int x = panelX + PAD;
        int w = panelW - PAD * 2;
        int y = panelY + 30;

        String active = Ap3Config.getInstance().getChainsFile();
        for (int i = scrollRow; i < scrollRow + visible; i++) {
            final String name = names.get(i);
            boolean isActive = name.equalsIgnoreCase(active);
            SettingsButtonWidget row = SettingsButtonWidget.builder(
                    Component.literal(isActive ? "§a> §f" + name : "§7" + name), btn -> {
                        Ap3Store.select(name);
                        status = "§aUsing " + name;
                        rebuildWidgets();
                    }).bounds(x, y, w, ROW).build();
            row.active = !isActive;
            this.addRenderableWidget(row);
            y += ROW + GAP;
        }
        statusY = y;
        y += 16;

        EditBox nameBox = new ThemedEditBox(this.font, x, y, w - NEW_W - GAP, ROW, Component.literal("New config name"));
        nameBox.setMaxLength(Ap3Store.MAX_CONFIG_NAME + 5); // room for a typed ".json"
        ((ThemedEditBox) nameBox).hint("new config name");
        nameBox.setValue(newName);
        nameBox.setResponder(text -> newName = text == null ? "" : text);
        this.addRenderableWidget(nameBox);
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("New Config"), btn -> create())
                .bounds(x + w - NEW_W, y, NEW_W, ROW).build());
        y += ROW + GAP + 6;

        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(x, y, w, 20).build());
    }

    /** New Config: an EMPTY file with the typed name, selected at once; a refused name says why and keeps the text. */
    private void create() {
        String error = Ap3Store.createConfig(newName);
        if (error != null) {
            status = "§c" + error;
        } else {
            status = "§aCreated and using " + Ap3Config.getInstance().getChainsFile();
            newName = "";
            scrollRow = 0;
        }
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // The menu theme (screen sweep, 2026-10-13): panel and header bar, the title in the cheat red (a cheat-only
        // screen), the folder under it, the config rows as the menu's raised buttons.
        ScreenChrome.backdrop(graphics, this.width, this.height);
        themedBounds = new int[]{panelX, panelY, panelW, panelH};
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX, panelY, panelW);
        ScreenChrome.titleCentred(graphics, this.title.getString(), panelX + panelW / 2, panelY + 6, true);
        String where = names.size() > MAX_VISIBLE
                ? String.format(Locale.US, "%d files - scroll", names.size())
                : Ap3Store.directory().getFileName().toString() + "/";
        ScreenChrome.centered(graphics, where, panelX + panelW / 2, panelY + 17, MenuStyle.DIM);
        if (!status.isEmpty()) {
            ScreenChrome.text(graphics, status, panelX + PAD, statusY + 2, MenuStyle.TXT);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (names.size() > MAX_VISIBLE && mouseX >= panelX && mouseX <= panelX + panelW
                && mouseY >= panelY && mouseY <= panelY + panelH) {
            int before = scrollRow;
            scrollRow = Math.max(0, Math.min(names.size() - MAX_VISIBLE, scrollRow - (int) Math.signum(scrollY)));
            if (scrollRow != before) {
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
