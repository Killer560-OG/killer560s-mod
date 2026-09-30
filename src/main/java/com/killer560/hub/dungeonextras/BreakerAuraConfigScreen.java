package com.killer560.hub.dungeonextras;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import com.killer560.hub.compat.McCompat;

/**
 * "Choose Breaker Aura Config" - killer560: "breaker aura also needs an option to swap between breaker auras just
 * like the auto routes can swap. with the same easy copy and whatnot." Same panel {@link com.killer560.hub.ap3.Ap3ConfigScreen}
 * uses for "Choose AP3 Config": one row per config file in {@link BreakerAuraStore#directory()} with the one in
 * use marked, a Delete button per row (refused on the active config and on the last config left -
 * {@link BreakerAuraStore#delete}), a name box + New Config, Done.
 * <p>
 * Every change goes through {@link BreakerAuraStore#select} / {@link BreakerAuraStore#createConfig} /
 * {@link BreakerAuraStore#delete} - the same code the store itself uses - so picking a file here persists it
 * ({@link DungeonExtrasConfig#getBreakerAuraConfigFile()}) and reloads it at once; a name is checked by
 * {@link BreakerAuraStore#validateConfigName} (plain file name, safe characters, never an existing file) and the
 * reason is shown on the panel when it is not accepted. Closing goes back to the mod menu, which rebuilds the
 * Breaker Aura tab so the button shows the new name.
 */
public class BreakerAuraConfigScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PAD = 10;
    private static final int ROW = 18;
    private static final int GAP = 4;
    /** Rows shown at once; the wheel scrolls the rest. */
    private static final int MAX_VISIBLE = 10;
    private static final int NEW_W = 76;
    private static final int DEL_W = 50;

    private final Screen parent;
    private int panelX, panelY, panelW, panelH;
    private int statusY;
    private int scrollRow;
    private List<String> names = List.of();
    /** What is typed in the name box - kept across rebuilds (a failed create must not wipe it). */
    private String newName = "";
    /** The last outcome, drawn under the list: red for a refusal, green for a switch / create / delete. */
    private String status = "";

    public BreakerAuraConfigScreen(Screen parent) {
        super(Component.literal("Choose Breaker Aura Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        names = BreakerAuraStore.listConfigNames();
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

        String active = DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile();
        int selW = w - DEL_W - GAP;
        for (int i = scrollRow; i < scrollRow + visible; i++) {
            final String name = names.get(i);
            boolean isActive = name.equalsIgnoreCase(active);
            SettingsButtonWidget row = SettingsButtonWidget.builder(
                    Component.literal(isActive ? "§a> §f" + name : "§7" + name), btn -> {
                        BreakerAuraStore.select(name);
                        status = "§aUsing " + name;
                        rebuildWidgets();
                    }).bounds(x, y, selW, ROW).build();
            row.active = !isActive;
            this.addRenderableWidget(row);

            SettingsButtonWidget del = SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        String error = BreakerAuraStore.delete(name);
                        status = error != null ? "§c" + error : "§aDeleted " + name;
                        // scrollRow is re-clamped to the fresh (shorter) list at the top of init(), so nothing
                        // extra is needed here even though one fewer row now exists.
                        rebuildWidgets();
                    }).bounds(x + selW + GAP, y, DEL_W, ROW).build();
            // Refused on the active config (switch away first) and when it is the only one left - same two rules
            // BreakerAuraStore.delete enforces, so a disabled button never surprises with a reason it never shows.
            del.active = !isActive && names.size() > 1;
            this.addRenderableWidget(del);
            y += ROW + GAP;
        }
        statusY = y;
        y += 16;

        EditBox nameBox = new EditBox(this.font, x, y, w - NEW_W - GAP, ROW, Component.literal("New config name"));
        nameBox.setMaxLength(BreakerAuraStore.MAX_CONFIG_NAME + 5); // room for a typed ".json"
        nameBox.setHint(Component.literal("§8new config name"));
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
        String error = BreakerAuraStore.createConfig(newName);
        if (error != null) {
            status = "§c" + error;
        } else {
            status = "§aCreated and using " + DungeonExtrasConfig.getInstance().getBreakerAuraConfigFile();
            newName = "";
            scrollRow = 0;
        }
        rebuildWidgets();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF0D0D0D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF553311);
        graphics.centeredText(this.font, this.title, panelX + panelW / 2, panelY + 8, SectionHeaders.color(true));
        String where = names.size() > MAX_VISIBLE
                ? String.format(Locale.US, "%d files - scroll", names.size())
                : BreakerAuraStore.directory().getFileName().toString() + "/";
        graphics.centeredText(this.font, where, panelX + panelW / 2, panelY + 19, 0xFF9A8C80);
        if (!status.isEmpty()) {
            graphics.text(this.font, status, panelX + PAD, statusY + 2, 0xFFFFFFFF, false);
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
