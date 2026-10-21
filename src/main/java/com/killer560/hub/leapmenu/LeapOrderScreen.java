package com.killer560.hub.leapmenu;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import com.mojang.blaze3d.platform.InputConstants;
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
 * <p>
 * 2026-10-21, the chat panel (killer560: players type their class in chat, and he reads it and answers himself - no
 * auto-fill from chat, because of misspellings): a Chat button in the header's top-right corner on every stage opens
 * {@link LeapChatPanel} down the right side, from under the header to just above the bottom row. The stage's own content
 * (class cards, wizard rows, the four spots) shrinks into the space left of it; the bottom rows keep the full width.
 * While its input has the keyboard every key goes to it (Enter sends, Escape leaves the box, Up/Down recall).
 */
public class LeapOrderScreen extends Screen implements MenuThemedScreen {

    /** The Theme setting this screen follows (ScreenThemes; "Theme Default" = the menu's theme). */
    public static final String THEME_ID = "leaporder";

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

    private static final int CARD_GAP = 6;
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
    /** The chat panel (killer560, 2026-10-21), on every stage, closed when the editor opens. */
    private final LeapChatPanel chat = new LeapChatPanel();
    /** Set by the Chat button when it opens the panel during a mouse press (see {@link #addChat}). */
    private boolean focusChatAfterClick = false;

    public LeapOrderScreen(Screen parent) {
        super(Component.literal("Leap Order"));
        this.parent = parent;
    }

    /** What {@code /leaporder}, {@code /class} and {@code /killer560 leaporder|class} run: opens this editor over whatever
     *  is showing, one tick later (never from inside command execution). */
    public static int openFromCommand() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.setScreenAndShow(new LeapOrderScreen(McCompat.screen(client))));
        return 1;
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
        boolean typing = chat.input != null && getFocused() == chat.input;
        clearWidgets();
        selectedSpot = -1;
        addChat(typing);
        int cx = width / 2;
        if (editing == null) {
            // Page 1 (redesigned 2026-10-15, killer560: "make this menu use a better amount of its space"): the five
            // classes are tall cards side by side across the whole panel, each listing the party members who are on
            // that class right now, instead of a short list in the middle of an empty screen.
            DungeonClass[] classes = DungeonClass.values();
            int[] area = classArea();
            int cardGap = CARD_GAP;
            int cardW = (area[2] - cardGap * (classes.length - 1)) / classes.length;
            int x = area[0] + (area[2] - (cardW * classes.length + cardGap * (classes.length - 1))) / 2;
            for (DungeonClass c : classes) {
                addRenderableWidget(new ClassCard(x, area[1], cardW, area[3], c, () -> {
                    editing = c;
                    LeapMenuConfig cfg = LeapMenuConfig.getInstance();
                    cfg.setLastEditedClass(c);
                    cfg.save();
                    if (!startWizard(false)) {
                        wizardWhenPartyArrives = PartyTracker.teammates().isEmpty();
                        rebuild();
                    }
                }));
                x += cardW + cardGap;
            }
            // Reset All (killer560, 2026-10-21): every class pick on the party and every saved class layout, in one go.
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset All"), btn -> {
                LeapMenuConfig cfg = LeapMenuConfig.getInstance();
                ClassOverrides.clearAll();
                cfg.clearAllPins();
                cfg.save();
                rebuild();
            }).bounds(cx - 186, height - 30, 120, 20).build());
            addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                    .bounds(cx - 60, height - 30, 120, 20).build());
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

    /** {x, y, w, h} of the row of class cards on page 1: from under the header's subtitle to above the Done button, and
     *  from edge to edge of the panel (inside its 12 unit margin). */
    private int[] classArea() {
        int x = 18;
        int y = 76;
        int w = Math.max(50, contentRight() - 2 * x);
        int room = Math.max(40, height - 40 - y);
        // Tall enough to fill a normal window, but not a long empty strip on a big one: past 280 units the row is centred.
        int h = Math.min(room, 280);
        return new int[]{x, y + (room - h) / 2, w, h};
    }

    /** The party as the dungeon tab list and the picks know it: you first, then the teammates in leap order. */
    private static java.util.List<String> partyNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            String self = client.player.getGameProfile().name();
            if (self != null && !self.isBlank()) {
                out.add(self);
            }
        }
        for (String n : PartyTracker.teammatesInLeapOrder()) {
            if (out.stream().noneMatch(n::equalsIgnoreCase)) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Class picks, bundled into Leap Order (killer560, 2026-09-21: "I would like this to be bundled into the
     * leaporder in a sense"; the separate Class Overrides tab was removed 2026-10-15 and this is now the only editor):
     * every spot with a player gets a button at the bottom of its box that cycles that player's mod-wide class pick
     * (None -> Mage -> Tank -> Healer -> Archer -> Berserker -> None), stored with the Leap Order itself. Right-clicking a spot does the same when the boxes are too small for it.
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
        String cls = shown == null ? "\u00a77?" : com.killer560.hub.dungeonclass.DungeonClass.colourCode(shown) + shown.displayName();
        return Component.literal("Class: " + cls + (override != null ? " \u00a78(override)" : ""));
    }

    private static void cycleOverride(String name, int dir) {
        DungeonClass override = ClassOverrides.has(name) ? ClassOverrides.classOf(name, null) : null;
        DungeonClass next = com.killer560.hub.dungeonclass.DungeonClass.step(override, dir);
        if (next == null) {
            ClassOverrides.clear(name);
        } else {
            ClassOverrides.set(name, next);
        }
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
    }

    /** The order the wizard's Class button steps through: unknown, then every class. */
    private static final DungeonClass[] WIZARD_CYCLE = {null, DungeonClass.MAGE, DungeonClass.TANK, DungeonClass.HEALER,
            DungeonClass.ARCHER, DungeonClass.BERSERKER};

    public static Component wizardClassText(DungeonClass cls) {
        return Component.literal("Class: " + (cls == null ? "\u00a77?" : com.killer560.hub.dungeonclass.DungeonClass.colourCode(cls)
                + cls.displayName()));
    }

    private void buildWizard(int cx) {
        String name = wizard.get(wizardIndex);
        DungeonClass now = PartyTracker.classOf(name);
        // The page's own rows sit in the part of the panel the chat leaves; the bottom row stays across the whole width.
        int ccx = contentRight() / 2;
        addRenderableWidget(SettingsButtonWidget.cycling(wizardClassText(now), (btn, dir) -> {
            int at = 0;
            for (int i = 0; i < WIZARD_CYCLE.length; i++) {
                if (WIZARD_CYCLE[i] == PartyTracker.classOf(name)) {
                    at = i;
                }
            }
            pickClass(name, WIZARD_CYCLE[SettingsButtonWidget.step(at, dir, WIZARD_CYCLE.length)]);
            rebuild();
        }).bounds(ccx - 80, wizardY() + 64, 160, 18).build());
        DungeonClass[] classes = DungeonClass.values();
        int bw = Math.min(70, (contentRight() - 20 - (classes.length - 1) * 4) / classes.length);
        int x = ccx - (classes.length * (bw + 4) - 4) / 2;
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
        int cx = contentRight() / 2;
        int y = wizardY();
        ScreenChrome.titleCentred(graphics, "Teammate " + (wizardIndex + 1) + " of " + wizard.size()
                + " - what class are they?", chat.open ? cx : width / 2, 16, false);
        int face = 32;
        GuiRects r = GuiRects.begin(graphics);
        ScreenChrome.card(r, cx - face / 2 - 4, y - 4, face + 8, face + 8, false, false);
        r.fill(cx - face / 2 - 1, y - 1, cx + face / 2 + 1, y + face + 1, MenuStyle.BLACK);
        r.submit();
        com.killer560.hub.livemap.LeapMapView.drawFace(graphics, name, cx - face / 2, y, face);
        DungeonClass now = PartyTracker.classOf(name);
        ScreenChrome.centered(graphics, name, cx, y + 40, now != null ? ScreenChrome.readable(now.color()) : MenuStyle.TXT);
        DungeonClass detected = PartyTracker.detectedClassOf(name);
        String source = detected != null ? "The tab list says " + detected.displayName()
                + (now != detected ? " (you picked " + (now == null ? "?" : now.displayName()) + ")" : "")
                : now != null ? "Picked by you" : "Not detected yet - pick their class";
        ScreenChrome.centered(graphics, source, cx, y + 51, MenuStyle.DIM);
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


    /** The four spots keep the leap menu's proportions but grow to fill the panel (2026-10-15; before, they stopped at the
     *  Custom Leap Menu's own scale and sat as a small block in the middle of a big screen). */
    private float boxScale() {
        float fitW = (contentRight() - 40) / (float) (BOX_W * 2 + GAP * 2);
        float fitH = (height - 90) / (float) (BOX_H * 2 + GAP * 2);
        return Math.max(0.4f, Math.min(2.5f, Math.min(fitW, fitH)));
    }

    /** @return {x0, y0, w, h} of a spot, laid out exactly like the custom leap menu. */
    private int[] spotBounds(int spot) {
        float s = boxScale();
        int w = (int) (BOX_W * s);
        int h = (int) (BOX_H * s);
        int cx = contentRight() / 2;
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
        // The menu theme (screen sweep, 2026-10-13): one panel over the whole window with a header band for the title
        // (and the Default Order pill), the spots as the menu's cards with their class colour as the edge, the class
        // buttons as cards too. Every position is the one it had: the spots still sit exactly where the leap menu puts them.
        ScreenChrome.backdrop(graphics, this.width, this.height);
        themedBounds = new int[]{6, 6, width - 12, height - 12};
        ScreenChrome.frame(graphics, 6, 6, width - 12, height - 12);
        GuiRects head = GuiRects.begin(graphics);
        MenuStyle.bevel(head, 9, 9, width - 18, editing != null && wizard == null ? 40 : editing == null ? 58 : 18,
                MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        head.submit();
        if (editing == null) {
            ScreenChrome.titleCentred(graphics, "Leap Order", width / 2, 16, false);
            drawLarge(graphics, "Select the class you are playing", width / 2, 34, 2f, MenuStyle.TXT_SOFT);
        } else if (wizard != null) {
            renderWizard(graphics);
        } else {
            ScreenChrome.titleCentred(graphics, "Leap Order - " + editing.displayName(), width / 2, 16, false);
            String[] layout = currentLayout();
            LeapMenuConfig cfg = LeapMenuConfig.getInstance();
            boolean any = false;
            for (int spot = 0; spot < 4; spot++) {
                int[] b = spotBounds(spot);
                boolean hovered = mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3];
                String name = layout[spot];
                any |= name != null;
                DungeonClass c = name == null ? null : PartyTracker.classOf(name);
                int color = c != null ? ScreenChrome.readable(c.color()) : MenuStyle.OR;
                GuiRects card = GuiRects.begin(graphics);
                ScreenChrome.card(card, b[0], b[1], b[2], b[3], hovered, spot == selectedSpot);
                // The class colour as the card's edge and a strip down its left side.
                card.outline(b[0] + 1, b[1] + 1, b[2] - 2, b[3] - 2, name == null ? MenuStyle.ACCENT_EDGE : color);
                if (name != null) {
                    card.fill(b[0] + 2, b[1] + 2, b[0] + 4, b[1] + b[3] - 2, color);
                }
                card.submit();
                if (name != null) {
                    // Above the class button when the box has one.
                    boolean withButton = b[3] >= 34;
                    int bodyH = withButton ? b[3] - 17 : b[3];
                    int textY = b[1] + bodyH / 2 - 4;
                    int face = Math.max(12, Math.min(40, bodyH - 8));
                    int textCx = b[0] + b[2] / 2;
                    if (b[2] >= face + 70) {
                        // The teammate's face on the left (the custom leap menu draws it too), the name in the rest.
                        int fx = b[0] + 8;
                        int fy = b[1] + (bodyH - face) / 2;
                        GuiRects fr = GuiRects.begin(graphics);
                        fr.fill(fx - 1, fy - 1, fx + face + 1, fy + face + 1, MenuStyle.BLACK);
                        fr.submit();
                        com.killer560.hub.livemap.LeapMapView.drawFace(graphics, name, fx, fy, face);
                        textCx = fx + face + (b[0] + b[2] - fx - face) / 2;
                    }
                    ScreenChrome.centered(graphics, name, textCx, textY, color);
                    if (cfg.isPinned(editing, name)) {
                        // Subtle: moved by hand, so the default order leaves this one alone until Reset.
                        MenuStyle.text(graphics, "pinned", b[0] + b[2] - font.width("pinned") - 4, b[1] + 4, MenuStyle.DIM2);
                    }
                }
            }
            if (!any) {
                ScreenChrome.centered(graphics, "No party members found", contentRight() / 2, height / 2 - 4, MenuStyle.DIM);
            }
        }
        chat.draw(graphics, font, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    // ---- the chat panel ------------------------------------------------------------------------------------------------

    /** The right edge of what the stage may use: the whole width, or up to the chat panel when it is open. */
    private int contentRight() {
        return chat.open ? chat.left(width) - 6 : width;
    }

    /** The Chat toggle in the header's top-right corner (clear of every stage's bottom row and of the Default Order pill),
     *  and, when open, the panel down the right from under the header to just above the bottom row - so it never covers
     *  a bottom button; a narrow window shrinks the panel, not the buttons. */
    private void addChat(boolean typing) {
        addRenderableWidget(SettingsButtonWidget.builder(Component.literal(chat.open ? "Hide Chat" : "Chat"), btn -> {
            chat.open = !chat.open;
            chat.scroll = 0;
            rebuild();
            if (chat.open && chat.input != null) {
                // Opening it is for typing: no extra click. A mouse press re-focuses the pressed button after this runs
                // (ContainerEventHandler.mouseClicked), so mouseClicked focuses the input again afterwards.
                setFocused(chat.input);
                focusChatAfterClick = true;
            }
        }).bounds(width - 12 - 60, 11, 60, 14).build());
        if (!chat.open) {
            return;
        }
        int top = editing == null ? 72 : wizard != null ? 32 : 54;
        addRenderableWidget(chat.layout(font, width, height, top));
        if (typing) {
            setFocused(chat.input);
        }
    }

    /** True while the chat input has the keyboard: every key goes to it, none to the editor or its buttons. */
    private boolean chatTyping() {
        return chat.open && chat.input != null && getFocused() == chat.input && chat.input.isFocused();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!chatTyping()) {
            return super.keyPressed(event);
        }
        int key = event.key();
        if (key == InputConstants.KEY_ESCAPE) {
            setFocused(null); // Escape leaves the box; a second one closes the editor as before
        } else if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            chat.send();
        } else if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
            chat.history(key == InputConstants.KEY_UP ? 1 : -1);
        } else if (key == InputConstants.KEY_PAGEUP || key == InputConstants.KEY_PAGEDOWN) {
            chat.page(key == InputConstants.KEY_PAGEUP ? 1 : -1);
        } else {
            chat.input.keyPressed(event);
        }
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (chatTyping()) {
            chat.input.charTyped(event);
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (chat.scroll(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Testkit: whether the chat panel is open, the lines it shows (plain text, top to bottom), and lines it has sent. */
    public boolean chatPanelOpen() {
        return chat.open;
    }

    public java.util.List<String> chatPanelLines() {
        return chat.shown();
    }

    public boolean chatPanelTyping() {
        return chatTyping();
    }

    public static int chatPanelSent() {
        return LeapChatPanel.sent;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (chat.input != null && getFocused() == chat.input && !chat.input.isMouseOver(event.x(), event.y())) {
            setFocused(null); // a click anywhere else gives the keyboard back to the editor
        }
        focusChatAfterClick = false;
        if (super.mouseClicked(event, doubleClick)) {
            if (focusChatAfterClick && chat.input != null) {
                setFocused(chat.input);
            }
            focusChatAfterClick = false;
            return true;
        }
        if (chat.over(event.x(), event.y())) {
            return true; // the panel's own area: nothing under it to click
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

    /** Text drawn {@code scale} times its size, centred on {@code cx} with its top at {@code y}. */
    private static void drawLarge(GuiGraphicsExtractor g, String s, int cx, int y, float scale, int colour) {
        int w = MenuStyle.font().width(s);
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(scale, scale);
        ScreenChrome.text(g, s, -w / 2, 0, colour);
        g.pose().popMatrix();
    }

    /** One class on page 1: a tall card showing just the class name, large. Its message is the class name, so it is
     *  found like the old button. */
    private static final class ClassCard extends AbstractWidget {
        private final DungeonClass cls;
        private final Runnable onClick;

        ClassCard(int x, int y, int w, int h, DungeonClass cls, Runnable onClick) {
            super(x, y, w, h, Component.literal(cls.displayName()));
            this.cls = cls;
            this.onClick = onClick;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int c = ScreenChrome.readable(cls.color());
            boolean last = LeapMenuConfig.getInstance().getLastEditedClass() == cls;
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            // Just the class, large (killer560, 2026-10-18: "select what class you are playing much larger so people
            // cant mistake what they need to do"). No names: who is on which class is the list on the tab list, not here.
            GuiRects r = GuiRects.begin(graphics);
            ScreenChrome.card(r, x, y, w, h, isHovered, last);
            r.outline(x + 1, y + 1, w - 2, h - 2, c);
            r.fill(x + 2, y + 2, x + w - 2, y + h - 2, MenuStyle.alpha(cls.color(), 0x30));
            r.submit();
            // Three times size, smaller only when the card is too narrow for the name (the chat panel narrows them).
            float scale = Math.max(1f, Math.min(3f, (w - 8) / (float) Math.max(1, MenuStyle.font().width(cls.displayName()))));
            drawLarge(graphics, cls.displayName(), x + w / 2, y + h / 2 - (int) (4 * scale), scale, c);
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
            // A card in the menu theme with the class colour as its edge (screen sweep, 2026-10-13).
            int c = ScreenChrome.readable(color);
            GuiRects r = GuiRects.begin(graphics);
            ScreenChrome.card(r, getX(), getY(), getWidth(), getHeight(), isHovered, false);
            r.outline(getX() + 1, getY() + 1, getWidth() - 2, getHeight() - 2, c);
            r.submit();
            ScreenChrome.centered(graphics, label, getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, c);
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
