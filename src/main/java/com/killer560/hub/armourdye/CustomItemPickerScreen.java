package com.killer560.hub.armourdye;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Custom Item page (2026-10-18): search every Minecraft item and every Hypixel catalog item by name, pick one, and
 * see it large. Apply gives the selected item that item's look (its model, and its head texture when it has one), so
 * the selected item shows as the picked one. Nothing is sent to the server.
 * <p>
 * killer560, 2026-11-01: Clear takes the applied item off the selection ("if i press clear it doesnt remove the custom item
 * for it"), and reopening the page shows the item applied last, picked and scrolled to (the entry keeps its key,
 * {@link ArmourDyeEntry#customItem}).
 */
public final class CustomItemPickerScreen extends ListScreenBase {

    /** One searchable item: a Minecraft item or a Hypixel catalog entry. */
    private record Choice(String name, String source, Item minecraft, SkyblockItemEntry hypixel) {

        /** {@code minecraft:<registry id>} or {@code hypixel:<Skyblock id>}: what {@link ArmourDyeEntry#customItem} keeps. */
        String key() {
            return hypixel != null ? "hypixel:" + hypixel.id() : "minecraft:" + BuiltInRegistries.ITEM.getKey(minecraft).getPath();
        }
    }

    private static final int MAX_RESULTS = 400;
    private static final int ROW_H = 18;

    private final CustomItemsScreen main;
    private final List<Choice> all = new ArrayList<>();
    private final List<Choice> shown = new ArrayList<>();
    private String query = "";
    private Choice picked;
    private ItemStack pickedStack = ItemStack.EMPTY;

    private int leftW;
    private int rightX;
    private int rightW;
    private int listY;
    private EditBox search;

    public CustomItemPickerScreen(CustomItemsScreen main) {
        super(Component.literal("Custom Item"), main);
        this.main = main;
    }

    @Override
    protected void init() {
        panelW = Math.min(CustomItemsScreen.MAX_W, this.width - 8);
        panelH = Math.min(CustomItemsScreen.MAX_H, this.height - 8);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        leftW = 220;
        rightX = panelX + leftW + 16;
        rightW = panelX + panelW - 8 - rightX;
        listY = panelY + 48;
        rows = Math.max(1, (panelH - 48 - 10) / ROW_H);

        boolean first = all.isEmpty();
        if (first) {
            loadChoices();
        }

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), b -> onClose())
                .bounds(panelX + panelW - 7 - 48, panelY + 3, 48, 14).build());
        // Clear: takes the applied item off the selection, and empties the search. It sits under Apply to Selected
        // (killer560: "the clear button should be at the bottom below apply to selected").
        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("Clear"), b -> {
                    main.clearCustomItem();
                    picked = null;
                    pickedStack = ItemStack.EMPTY;
                    query = "";
                    scroll = 0;
                    if (search != null) {
                        search.setValue("");
                    }
                    applyFilter();
                    rebuildWidgets();
                }).bounds(rightX, panelY + panelH - 24, rightW, 20).build();
        clear.active = main.selectedKey() != null;
        addRenderableWidget(clear);

        search = new ThemedEditBox(this.font, panelX + 8, panelY + 24, leftW, 18, Component.literal("Search"))
                .hint("search Minecraft and Hypixel items");
        search.setMaxLength(64);
        search.setResponder(text -> {
            query = text;
            scroll = 0;
            applyFilter();
        });
        if (first) {
            selectApplied();
        }
        search.setValue(query);
        if (first && picked != null) {
            int at = shown.indexOf(picked);
            scroll = at < 0 ? 0 : Math.max(0, Math.min(at, Math.max(0, shown.size() - rows)));
        }
        addRenderableWidget(search);

        // Apply: give the selected item the picked item's look.
        SettingsButtonWidget apply = SettingsButtonWidget.builder(Component.literal("Apply to Selected"), b -> {
                    main.copyLookFrom(pickedStack, picked.key());
                    onClose();
                }).bounds(rightX, panelY + panelH - 48, rightW, 20).build();
        apply.active = picked != null && main.selectedKey() != null;
        addRenderableWidget(apply);
    }

    /** Every Minecraft item with a name, then every Hypixel catalog item. Built once per open of the page. */
    private void loadChoices() {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == net.minecraft.world.item.Items.AIR) {
                continue;
            }
            String name = new ItemStack(item).getHoverName().getString();
            if (!name.isBlank()) {
                all.add(new Choice(name, "Minecraft item", item, null));
            }
        }
        for (SkyblockItemEntry entry : SkyblockItemRepository.getItems()) {
            if (entry.name() != null && !entry.name().isBlank()) {
                all.add(new Choice(entry.name(), "Hypixel item", null, entry));
            }
        }
        applyFilter();
    }

    /** The item the selection has applied (its saved key), picked and scrolled into view. */
    private void selectApplied() {
        ArmourDyeEntry e = main.entry();
        if (e == null || e.customItem == null || e.customItem.isBlank()) {
            return;
        }
        for (Choice c : all) {
            if (c.key().equals(e.customItem)) {
                picked = c;
                pickedStack = stackFor(c);
                if (!shown.contains(c)) {
                    query = c.name(); // past the first MAX_RESULTS rows: search for it, so it is on the list
                }
                return;
            }
        }
    }

    /** The item picked on this page now, or null. Public for the testkit. */
    public String pickedKey() {
        return picked == null ? null : picked.key();
    }

    private void applyFilter() {
        shown.clear();
        for (Choice c : all) {
            if (CustomItemsScreen.matchesQuery(c.name(), query)) {
                shown.add(c);
                if (shown.size() >= MAX_RESULTS) {
                    break;
                }
            }
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - rows)));
    }

    /** The stack a choice shows as. Built on pick, not per frame. */
    private static ItemStack stackFor(Choice c) {
        return c.hypixel() != null ? SkyblockItemStackFactory.build(c.hypixel()) : new ItemStack(c.minecraft());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        ScreenChrome.backdrop(graphics, this.width, this.height);
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.headerBar(graphics, panelX + 3, panelY + 2, panelW - 6, 16, "Custom Item");

        if (shown.isEmpty()) {
            MenuStyle.text(graphics, "No item matches that search.", panelX + 8, listY + 4, MenuStyle.DIM);
        }
        for (int row = 0; row < rows && scroll + row < shown.size(); row++) {
            Choice c = shown.get(scroll + row);
            int ry = listY + row * ROW_H;
            boolean sel = c == picked;
            boolean over = mouseX >= panelX + 8 && mouseX < panelX + 8 + leftW && mouseY >= ry && mouseY < ry + ROW_H;
            ScreenChrome.row(graphics, panelX + 8, ry, leftW, ROW_H, over, sel, row % 2 == 1);
            MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(c.name(), leftW - 12)),
                    panelX + 12, ry + 5, sel ? MenuStyle.OR_HOVER : MenuStyle.TXT);
        }

        // Right: the picked item, large, drawn without any saved look on it.
        String title = picked == null ? "Pick an item" : picked.name();
        MenuStyle.text(graphics, ScreenChrome.readable(this.font.plainSubstrByWidth(title, rightW)),
                rightX, panelY + 24, picked == null ? MenuStyle.DIM : MenuStyle.TXT);
        if (picked != null) {
            MenuStyle.text(graphics, picked.source(), rightX, panelY + 36, MenuStyle.DIM);
        }
        ScreenChrome.well(graphics, rightX, panelY + 50, 96, 96);
        if (!pickedStack.isEmpty()) {
            graphics.pose().pushMatrix();
            graphics.pose().translate(rightX + 16, panelY + 66);
            graphics.pose().scale(4.5f, 4.5f);
            ArmourDye.withoutOverrides(() -> graphics.item(pickedStack, 0, 0));
            graphics.pose().popMatrix();
        }
        if (main.selectedKey() == null) {
            MenuStyle.text(graphics, "Select an item in the grid first.", rightX, panelY + 152, MenuStyle.DIM);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x();
        double y = event.y();
        if (x >= panelX + 8 && x < panelX + 8 + leftW && y >= listY && y < listY + rows * ROW_H) {
            int idx = scroll + (int) ((y - listY) / ROW_H);
            if (idx >= 0 && idx < shown.size()) {
                picked = shown.get(idx);
                pickedStack = stackFor(picked);
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    protected int listSize() {
        return shown.size();
    }
}
