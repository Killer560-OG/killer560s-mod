package com.killer560.hub.petwheel;

import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * "Edit pets" (killer560's item 8.2): pick which of the pets {@link PetsMenuScanner} has ever seen go on the
 * wheel, and in what order. Left column is every known pet not currently on the wheel (paged, there can be a
 * lot); right column is the wheel's own order with Up/Down/Remove. Nothing here talks to Hypixel - it only
 * ever reads {@link PetWheelConfig}'s already-scanned lists, so it works even with no /pets screen open.
 */
public class PetPickerScreen extends Screen {

    private static final int ROWS_PER_PAGE = 9;
    private static final int ROW_H = 20;
    private static final int COL_W = 210;
    private static final int GAP = 16;
    private static final int TOP = 44;

    private final Screen parent;
    private int knownPage = 0;

    public PetPickerScreen(Screen parent) {
        super(Component.literal("Edit Pets"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        PetWheelConfig cfg = PetWheelConfig.getInstance();
        List<PetEntry> wheel = cfg.getWheelPets();
        List<PetEntry> addable = cfg.getKnownPets().stream().filter(p -> !cfg.isOnWheel(p.uuid())).toList();

        int leftX = width / 2 - COL_W - GAP / 2;
        int rightX = width / 2 + GAP / 2;

        int pages = Math.max(1, (int) Math.ceil(addable.size() / (double) ROWS_PER_PAGE));
        knownPage = Math.max(0, Math.min(knownPage, pages - 1));
        int from = knownPage * ROWS_PER_PAGE;
        int to = Math.min(from + ROWS_PER_PAGE, addable.size());
        int y = TOP;
        for (int i = from; i < to; i++) {
            PetEntry entry = addable.get(i);
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("+ " + entry.shortLabel()), btn -> {
                        cfg.addToWheel(entry.uuid());
                        cfg.save();
                        rebuild();
                    }).bounds(leftX, y, COL_W, 18).build());
            y += ROW_H;
        }
        if (pages > 1) {
            int navY = TOP + ROWS_PER_PAGE * ROW_H + 2;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("< Prev"), btn -> {
                        knownPage--;
                        rebuild();
                    }).bounds(leftX, navY, COL_W / 2 - 2, 16).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Next >"), btn -> {
                        knownPage++;
                        rebuild();
                    }).bounds(leftX + COL_W / 2 + 2, navY, COL_W / 2 - 2, 16).build());
        }

        y = TOP;
        int smallBtn = 18;
        for (int i = 0; i < wheel.size(); i++) {
            PetEntry entry = wheel.get(i);
            int index = i;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal((i + 1) + ". " + entry.shortLabel()), btn -> {
                    }).bounds(rightX, y, COL_W - smallBtn * 3 - 6, 18).build());
            int bx = rightX + COL_W - smallBtn * 3 - 4;
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("^"), btn -> {
                        cfg.moveInWheel(index, -1);
                        cfg.save();
                        rebuild();
                    }).bounds(bx, y, smallBtn, 18).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("v"), btn -> {
                        cfg.moveInWheel(index, 1);
                        cfg.save();
                        rebuild();
                    }).bounds(bx + smallBtn + 2, y, smallBtn, 18).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("x"), btn -> {
                        cfg.removeFromWheel(entry.uuid());
                        cfg.save();
                        rebuild();
                    }).bounds(bx + (smallBtn + 2) * 2, y, smallBtn, 18).build());
            y += ROW_H;
        }

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(width / 2 - 40, height - 30, 80, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xCC000000);
        var font = Minecraft.getInstance().font;
        graphics.centeredText(font, "Edit Pets", width / 2, 14, 0xFFCC6600);
        graphics.centeredText(font, "Known pets (tap to add)", width / 2 - COL_W - GAP / 2 + COL_W / 2, TOP - 12, 0xFFFFFFFF);
        graphics.centeredText(font, "On the wheel, in order", width / 2 + GAP / 2 + COL_W / 2, TOP - 12, 0xFFFFFFFF);
        if (PetWheelConfig.getInstance().getWheelPets().isEmpty()) {
            graphics.centeredText(font, "Nothing on the wheel yet", width / 2 + GAP / 2 + COL_W / 2, TOP + 6, 0xFF9A8C80);
        }
        if (PetWheelConfig.getInstance().getKnownPets().isEmpty()) {
            graphics.centeredText(font, "Open /pets once to read your pets", width / 2 - COL_W - GAP / 2 + COL_W / 2, TOP + 6, 0xFF9A8C80);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
