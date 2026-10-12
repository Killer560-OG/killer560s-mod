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
 * pins both names it moved (drawn with a small grey "pinned"); shift-click unpins one, Reset Positions unpins all.
 * <p>
 * 2026-10-11, the class wizard (killer560: "it goes though player by player asking you their class, then after you get
 * a player on the right class you press next on a button on the bottom of your screen. Then it goes through all 4 of the
 * other players then once it gets to the end it shows the current menu thing"). After page 1, each teammate gets a page
 * of their own: their face and name, what the dungeon tab list already says they are (pre-filled), a Class button
 * (left click forward, right click back) and the five classes to pick from, and Back / Skip / Next at the bottom. A pick
 * is the same mod-wide class override the spot buttons set (picking what the tab list already says just clears the
 * override). After the last one the editor shows, laid out by the Default Order from those classes. The wizard runs on
 * its own only when some teammate's class is still unknown (so inside a dungeon, where the tab list knows everyone, it
 * goes straight to the layout). 2026-10-12 (killer560: "remove the set classes button up top I should never
 * need that"): that automatic start is the only way in. The bottom row is Back, Reset Classes (the party's class picks),
 * Reset Positions (this class's pins) and Done; opening the editor asks for the party list itself (/pl, at most every
 * 10 s, not in a dungeon), so there is no Refresh Party button and no hint line.
 */
public class LeapOrderScreen extends Screen {

    private static final int BOX_W = 180;
    private static final int BOX_H = 50;
    private static final int GAP = 6;

    private final Screen parent;
    private DungeonClass editing = null;
    private int selectedSpot = -1;
    /** The names the spot buttons were built for - a party change rebuilds them (auto-detects new members). */
    private String builtFor = "";
    /** The class wizard: the teammates it walks through (null = not running) and which one is showing. */
    private java.util.List<String> wizard = null;
    private int wizardIndex = 0;
    /** The editor opened with nobody in the party yet: start the wizard once names arrive (a /pl answer). */
    private boolean wizardWhenPartyArrives = false;

    public LeapOrderScreen(Screen parent) {
        super(Component.literal("Leap Order"));
        this.parent = parent;
    }

    /** When the editor last asked Hypixel for the party list (any instance): opening it again within
     *  {@link #REFRESH_EVERY_MS} sends nothing. */
    private static long lastPartyRefreshMs = 0L;
    private static final long REFRESH_EVERY_MS = 10_000L;
    /** /pl requests this editor has sent (testkit). */
    private static int partyRefreshes = 0;
    private boolean refreshedOnOpen = false;

    public static int partyRefreshes() {
        return partyRefreshes;
    }

    @Override
    protected void init() {
        if (!refreshedOnOpen) {
            // killer560 (2026-10-12): "have it auto refresh party every time i open this menu". init() also runs on a
            // resize, so once per opening; and at most once per REFRESH_EVERY_MS however often it is reopened.
            refreshedOnOpen = true;
            refreshParty();
        }
        rebuild();
    }

    /** Asks Hypixel for the party list (/pl), as the old Refresh Party button did - not inside a dungeon, where the tab
     *  list already names the party, and not twice within {@link #REFRESH_EVERY_MS}. */
    private static void refreshParty() {
        Minecraft client = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (client.player == null || DungeonState.isInDungeon() || now - lastPartyRefreshMs < REFRESH_EVERY_MS) {
            return;
        }
        lastPartyRefreshMs = now;
        partyRefreshes++;
        client.player.connection.sendCommand("pl");
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
                    if (!startWizard(false)) {
                        wizardWhenPartyArrives = PartyTracker.teammates().isEmpty();
                        rebuild();
                    }
                }));
                y += bh + 4;
            }
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                    .bounds(cx - 40, height - 30, 80, 20).build());
            return;
        }

        if (wizard != null) {
            buildWizard(cx);
            return;
        }
        // Bottom row, evenly spaced (killer560, 2026-10-12): Back, Reset Classes, Reset Positions, Done. Refresh Party
        // went: opening the editor refreshes the party, and joins/leaves after that arrive by themselves.
        int by = height - 30;
        int bw = Math.min(90, (width - 20 - 3 * 6) / 4);
        int bx = cx - (4 * bw + 3 * 6) / 2;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), btn -> {
            editing = null;
            rebuild();
        }).bounds(bx, by, bw, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset Classes"), btn -> {
            // Every class pick (the wizard's or a Class button's) on the current party: classes come from the tab list.
            for (String name : PartyTracker.teammatesInLeapOrder()) {
                ClassOverrides.clear(name);
            }
            ClassOverrides.getInstance().save();
            rebuild();
        }).bounds(bx + (bw + 6), by, bw, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset Positions"), btn -> {
            // Every pin of this class's layout: the Default Order places everyone again.
            LeapMenuConfig cfg = LeapMenuConfig.getInstance();
            cfg.clearClassOrder(editing);
            cfg.save();
            rebuild(); // the class buttons follow the names
        }).bounds(bx + 2 * (bw + 6), by, bw, 20).build());
        // The default order (2026-10-09): where every teammate not moved by hand goes, by their class.
        addRenderableWidget(SettingsButtonWidget.cycling(defaultOrderText(), (btn, dir) -> {
            LeapMenuConfig cfg = LeapMenuConfig.getInstance();
            cfg.setDefaultOrder(SettingsButtonWidget.step(cfg.getDefaultOrder(), dir));
            cfg.save();
            rebuild();
        }).bounds(cx - 80, 28, 160, 16).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(bx + 3 * (bw + 6), by, bw, 20).build());
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

    // ---- the class wizard ----------------------------------------------------------------------------------------------

    /** Starts the wizard over the current teammates (at most 4). Unless {@code always}, only when one of them has no
     *  known class. @return true when it started. */
    private boolean startWizard(boolean always) {
        java.util.List<String> names = new java.util.ArrayList<>(PartyTracker.teammatesInLeapOrder());
        if (names.size() > 4) {
            names = new java.util.ArrayList<>(names.subList(0, 4));
        }
        if (names.isEmpty() || !always && names.stream().allMatch(n -> PartyTracker.classOf(n) != null)) {
            return false;
        }
        wizard = names;
        wizardIndex = 0;
        wizardWhenPartyArrives = false;
        rebuild();
        return true;
    }

    private void endWizard() {
        wizard = null;
        rebuild();
    }

    /** Sets a teammate's class from the wizard: what the tab list already says clears the override, anything else
     *  sets it, nothing (?) clears it. */
    private static void pickClass(String name, DungeonClass cls) {
        DungeonClass detected = PartyTracker.detectedClassOf(name);
        if (cls == null || cls == detected) {
            ClassOverrides.clear(name);
        } else {
            ClassOverrides.set(name, cls);
        }
        ClassOverrides.getInstance().save();
    }

    /** The order the wizard's Class button steps through: unknown, then every class. */
    private static final DungeonClass[] WIZARD_CYCLE = {null, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER,
            DungeonClass.ARCHER, DungeonClass.BERSERKER};

    public static Component wizardClassText(DungeonClass cls) {
        return Component.literal("Class: " + (cls == null ? "\u00a77?" : com.killer560.hub.gui.tab.ClassOverridesTab.colourCode(cls)
                + cls.displayName()));
    }

    private void buildWizard(int cx) {
        String name = wizard.get(wizardIndex);
        DungeonClass now = PartyTracker.classOf(name);
        addRenderableWidget(SettingsButtonWidget.cycling(wizardClassText(now), (btn, dir) -> {
            int at = 0;
            for (int i = 0; i < WIZARD_CYCLE.length; i++) {
                if (WIZARD_CYCLE[i] == PartyTracker.classOf(name)) {
                    at = i;
                }
            }
            pickClass(name, WIZARD_CYCLE[SettingsButtonWidget.step(at, dir, WIZARD_CYCLE.length)]);
            rebuild();
        }).bounds(cx - 80, wizardY() + 64, 160, 18).build());
        DungeonClass[] classes = DungeonClass.values();
        int bw = Math.min(70, (width - 20 - (classes.length - 1) * 4) / classes.length);
        int x = cx - (classes.length * (bw + 4) - 4) / 2;
        for (DungeonClass c : classes) {
            String label = c == now ? "> " + c.displayName() + " <" : c.displayName();
            addRenderableWidget(new ColoredButton(x, wizardY() + 88, bw, 18, label, c.color(), () -> {
                pickClass(name, c);
                rebuild();
            }));
            x += bw + 4;
        }
        int by = height - 30;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), btn -> {
            if (wizardIndex == 0) {
                wizard = null;
                editing = null;
                rebuild();
            } else {
                wizardIndex--;
                rebuild();
            }
        }).bounds(cx - 150, by, 90, 20).build());
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Skip"), btn -> endWizard())
                .bounds(cx - 45, by, 90, 20).build());
        boolean last = wizardIndex >= wizard.size() - 1;
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(last ? "Finish" : "Next"), btn -> {
            if (wizardIndex >= wizard.size() - 1) {
                endWizard();
            } else {
                wizardIndex++;
                rebuild();
            }
        }).bounds(cx + 60, by, 90, 20).build());
    }

    private int wizardY() {
        return Math.max(30, height / 2 - 70);
    }

    private void renderWizard(GuiGraphicsExtractor graphics) {
        String name = wizard.get(wizardIndex);
        int cx = width / 2;
        int y = wizardY();
        graphics.centeredText(font, "Teammate " + (wizardIndex + 1) + " of " + wizard.size() + " - what class are they?",
                cx, 16, 0xFFCC6600);
        int face = 32;
        graphics.fill(cx - face / 2 - 1, y - 1, cx + face / 2 + 1, y + face + 1, 0xFF000000);
        com.killer560.hub.livemap.LeapMapView.drawFace(graphics, name, cx - face / 2, y, face);
        DungeonClass now = PartyTracker.classOf(name);
        graphics.centeredText(font, name, cx, y + 38, now != null ? now.color() : 0xFFFFFFFF);
        DungeonClass detected = PartyTracker.detectedClassOf(name);
        String source = detected != null ? "The tab list says " + detected.displayName()
                + (now != detected ? " (you picked " + (now == null ? "?" : now.displayName()) + ")" : "")
                : now != null ? "Picked by you" : "Not detected yet - pick their class";
        graphics.centeredText(font, source, cx, y + 50, 0xFF9A8C80);
    }

    @Override
    public void tick() {
        super.tick();
        if (editing == null) {
            return;
        }
        if (wizard != null) {
            return; // the wizard's names are fixed while it runs
        }
        if (wizardWhenPartyArrives && !PartyTracker.teammates().isEmpty()) {
            wizardWhenPartyArrives = false;
            if (startWizard(false)) {
                return;
            }
        }
        String now = String.join(",", Arrays.asList(currentLayout()).stream().map(n -> n == null ? "" : n).toList());
        if (!now.equals(builtFor)) {
            rebuild(); // someone joined / left: new spot buttons
        }
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
        } else if (wizard != null) {
            renderWizard(graphics);
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
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (editing == null || wizard != null) {
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
