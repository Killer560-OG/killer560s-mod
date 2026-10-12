package com.killer560.hub.leapmenu;

import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.spiritleap.SpiritLeapOverlayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import com.killer560.hub.compat.McCompat;

/**
 * Leap Order editor (2026-09-15 redo, killer560: "There should first be a menu where you select what class you
 * currently are playing, then itll open a new menu with the players names where they normally are on the leap
 * menu, and you can swap the names from there").
 * <p>
 * Page 1 picks the class you are playing. Page 2 shows your party in the same 4 spots the custom leap menu uses;
 * click one name, then another spot, to swap them. Each class keeps its own layout, and the leap menu uses the
 * layout for the class the dungeon tab list says you are playing.
 * <p>
 * 2026-10-09 (killer560: "once I set everyone's class then it should automatically move them where they would normally
 * be; I should still be able to manually move them"): the Default Order button picks a {@link LeapOrderMode}, and every
 * teammate not moved by hand sits where it puts their class - so setting a class moves that teammate at once. A swap
 * pins both names it moved (drawn with a small grey "pinned"); shift-click unpins one, Reset to Default unpins all.
 */
public class LeapOrderScreen extends Screen {

    private static final int BOX_W = 180;
    private static final int BOX_H = 50;
    private static final int GAP = 6;

    private final Screen parent;
    private DungeonClass editing = null;
    private int selectedSpot = -1;
    private boolean requestedPartyList = false;
    /** The names the spot buttons were built for - a party change rebuilds them (auto-detects new members). */
    private String builtFor = "";

    public LeapOrderScreen(Screen parent) {
        super(Component.literal("Leap Order"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        selectedSpot = -1;
        int cx = width / 2;
        if (editing == null) {
            int bw = 110;
            int bh = 22;
            DungeonClass[] classes = DungeonClass.values();
            int y = height / 2 - (classes.length * (bh + 4)) / 2;
            for (DungeonClass c : classes) {
                addRenderableWidget(new ColoredButton(cx - bw / 2, y, bw, bh, c.displayName(), c.color(), () -> {
                    editing = c;
                    LeapMenuConfig cfg = LeapMenuConfig.getInstance();
                    cfg.setLastEditedClass(c);
                    cfg.save();
                    maybeRequestPartyList();
                    rebuild();
                }));
                y += bh + 4;
            }
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                    .bounds(cx - 40, height - 30, 80, 20).build());
            return;
        }

        int by = height - 30;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), btn -> {
            editing = null;
            rebuild();
        }).bounds(cx - 177, by, 70, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset to Default"), btn -> {
            LeapMenuConfig cfg = LeapMenuConfig.getInstance();
            cfg.clearClassOrder(editing);
            cfg.save();
            rebuild(); // the class buttons follow the names
        }).bounds(cx - 99, by, 100, 20).build());
        // The default order (2026-10-09): where every teammate not moved by hand goes, by their class.
        addRenderableWidget(SettingsButtonWidget.cycling(defaultOrderText(), (btn, dir) -> {
            LeapMenuConfig cfg = LeapMenuConfig.getInstance();
            cfg.setDefaultOrder(SettingsButtonWidget.step(cfg.getDefaultOrder(), dir));
            cfg.save();
            rebuild();
        }).bounds(cx - 80, 28, 160, 16).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Refresh Party"), btn -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.connection.sendCommand("pl");
            }
        }).bounds(cx + 9, by, 90, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(cx + 107, by, 70, 20).build());
        addClassButtons();
    }

    /**
     * Class Overrides, bundled into Leap Order (killer560, 2026-09-21: "I would like this to be bundled into the
     * leaporder in a sense"): every spot with a player gets a button at the bottom of its box that cycles that
     * player's mod-wide class override (None -> Mage -> Tank -> Healer -> Archer -> Berserker -> None), the same
     * store the Class Overrides tab edits. Right-clicking a spot does the same when the boxes are too small for it.
     */
    private void addClassButtons() {
        String[] layout = currentLayout();
        builtFor = String.join(",", Arrays.asList(layout).stream().map(n -> n == null ? "" : n).toList());
        for (int spot = 0; spot < 4; spot++) {
            String name = layout[spot];
            int[] b = spotBounds(spot);
            if (name == null || b[3] < 34) {
                continue;
            }
            addRenderableWidget(SettingsButtonWidget.cycling(classButtonText(name), (btn, dir) -> {
                        cycleOverride(name, dir);
                        rebuild();
                    }).bounds(b[0] + 4, b[1] + b[3] - 17, b[2] - 8, 14).build());
        }
    }

    /** "Default Order: Odin" - the label is also the tooltip key "default order". */
    public static Component defaultOrderText() {
        return Component.literal("Default Order: §6" + LeapMenuConfig.getInstance().getDefaultOrder().label());
    }

    private static Component classButtonText(String name) {
        DungeonClass override = ClassOverrides.has(name) ? ClassOverrides.classOf(name, null) : null;
        DungeonClass shown = override != null ? override : PartyTracker.classOf(name);
        String cls = shown == null ? "\u00a77?" : com.killer560.hub.gui.tab.ClassOverridesTab.colourCode(shown) + shown.displayName();
        return Component.literal("Class: " + cls + (override != null ? " \u00a78(override)" : ""));
    }

    private static void cycleOverride(String name, int dir) {
        DungeonClass override = ClassOverrides.has(name) ? ClassOverrides.classOf(name, null) : null;
        DungeonClass next = com.killer560.hub.gui.tab.ClassOverridesTab.step(override, dir);
        if (next == null) {
            ClassOverrides.clear(name);
        } else {
            ClassOverrides.set(name, next);
        }
        ClassOverrides.getInstance().save();
    }

    @Override
    public void tick() {
        super.tick();
        if (editing == null) {
            return;
        }
        String now = String.join(",", Arrays.asList(currentLayout()).stream().map(n -> n == null ? "" : n).toList());
        if (!now.equals(builtFor)) {
            rebuild(); // someone joined / left: new spot buttons
        }
    }

    /** Outside a dungeon with no party known yet, ask Hypixel for the party list once so names show up. */
    private void maybeRequestPartyList() {
        Minecraft client = Minecraft.getInstance();
        if (requestedPartyList || client.player == null || DungeonState.isInDungeon()
                || !PartyTracker.teammates().isEmpty()) {
            return;
        }
        requestedPartyList = true;
        client.player.connection.sendCommand("pl");
    }

    private float boxScale() {
        float scale = SpiritLeapOverlayConfig.getInstance().getScale();
        float fitW = (width - 20) / (float) (BOX_W * 2 + GAP * 2);
        float fitH = (height - 90) / (float) (BOX_H * 2 + GAP * 2);
        return Math.max(0.4f, Math.min(scale, Math.min(fitW, fitH)));
    }

    /** @return {x0, y0, w, h} of a spot, laid out exactly like the custom leap menu. */
    private int[] spotBounds(int spot) {
        float s = boxScale();
        int w = (int) (BOX_W * s);
        int h = (int) (BOX_H * s);
        int cx = width / 2;
        int cy = height / 2;
        int x0 = spot % 2 == 0 ? cx - GAP - w : cx + GAP;
        int y0 = spot < 2 ? cy - GAP - h : cy + GAP;
        return new int[]{x0, y0, w, h};
    }

    private String[] currentLayout() {
        // teammatesInLeapOrder(), not teammates(): spots nobody has been placed in yet must auto-fill in the order
        // the real Spirit Leap menu shows (its container slot order), not the party listing order - otherwise the
        // saved layout is off by a position until something is swapped.
        // Hand-moved teammates keep their spot; everyone else goes where the default order puts their class - the
        // same call the custom leap menu lays out with.
        return LeapMenuFeature.layout(PartyTracker.teammatesInLeapOrder(), editing);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xCC000000);
        if (editing == null) {
            graphics.centeredText(font, "Select your class", width / 2, height / 2 - 90, 0xFFCC6600);
        } else {
            graphics.centeredText(font, "Leap Order - " + editing.displayName(), width / 2, 16, editing.color());
            String[] layout = currentLayout();
            LeapMenuConfig cfg = LeapMenuConfig.getInstance();
            boolean any = false;
            for (int spot = 0; spot < 4; spot++) {
                int[] b = spotBounds(spot);
                boolean hovered = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
                String name = layout[spot];
                any |= name != null;
                DungeonClass c = name == null ? null : PartyTracker.classOf(name);
                int color = c != null ? c.color() : 0xFFCC6600;
                graphics.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hovered ? 0xE0202020 : 0xCC0D0D0D);
                graphics.outline(b[0], b[1], b[2], b[3], spot == selectedSpot ? 0xFFFFFFFF : color);
                if (spot == selectedSpot) {
                    graphics.outline(b[0] + 1, b[1] + 1, b[2] - 2, b[3] - 2, 0xFFFFFFFF);
                }
                if (name != null) {
                    // Above the class button when the box has one.
                    int textY = b[3] >= 34 ? b[1] + (b[3] - 17) / 2 - 4 : b[1] + b[3] / 2 - 4;
                    graphics.centeredText(font, name, b[0] + b[2] / 2, textY, color);
                    if (cfg.isPinned(editing, name)) {
                        // Subtle: moved by hand, so the default order leaves this one alone until Reset.
                        graphics.text(font, "pinned", b[0] + b[2] - font.width("pinned") - 3, b[1] + 3, 0xFF7A7A7A, false);
                    }
                }
            }
            if (!any) {
                graphics.centeredText(font, "No party members found", width / 2, height / 2 - 4, 0xFF9A8C80);
            }
            graphics.centeredText(font, "Click a name, then a spot, to move it. Shift-click to unpin.",
                    width / 2, height - 42, 0xFF7A7A7A);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (editing == null) {
            return false;
        }
        for (int spot = 0; spot < 4; spot++) {
            int[] b = spotBounds(spot);
            if (event.x() < b[0] || event.x() >= b[0] + b[2] || event.y() < b[1] || event.y() >= b[1] + b[3]) {
                continue;
            }
            String[] layout = currentLayout();
            if (event.button() == 1) {
                if (layout[spot] != null) {
                    cycleOverride(layout[spot], 1);
                    rebuild();
                }
                return true;
            }
            if (event.hasShiftDown()) {
                LeapMenuConfig cfg = LeapMenuConfig.getInstance();
                if (layout[spot] != null && cfg.unpin(editing, layout[spot])) {
                    cfg.save();
                    rebuild();
                }
                selectedSpot = -1;
                return true;
            }
            if (selectedSpot < 0) {
                if (layout[spot] != null) {
                    selectedSpot = spot;
                }
            } else if (selectedSpot == spot) {
                selectedSpot = -1;
            } else {
                // Both teammates are pinned where they land (a manual move sticks until Reset).
                LeapMenuConfig cfg = LeapMenuConfig.getInstance();
                cfg.swap(editing, layout, selectedSpot, spot);
                cfg.save();
                rebuild(); // the class buttons follow the names
            }
            return true;
        }
        selectedSpot = -1;
        return false;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A class-colored button for the class picker. */
    private static final class ColoredButton extends AbstractWidget {
        private final String label;
        private final int color;
        private final Runnable onClick;

        ColoredButton(int x, int y, int w, int h, String label, int color, Runnable onClick) {
            super(x, y, w, h, Component.literal(label));
            this.label = label;
            this.color = color;
            this.onClick = onClick;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), isHovered ? 0xFF262626 : 0xFF1A1A1A);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), color);
            graphics.centeredText(Minecraft.getInstance().font, label, getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onClick.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            output.add(NarratedElementType.TITLE, getMessage());
        }
    }
}
