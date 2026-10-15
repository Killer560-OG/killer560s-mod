package com.killer560.hub.gui;

import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.CategoryTab;
import com.killer560.hub.gui.tab.FolderTab;
import com.killer560.hub.gui.tab.HomeTab;
import com.killer560.hub.gui.tab.KeyCaptureTab;
import com.killer560.hub.gui.tab.MenuCategories;
import com.killer560.hub.hud.GuiRects;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.killer560.hub.compat.McCompat;

/**
 * The mod menu, "Option A" (killer560 approved the mockup on 2026-10-12): a header bar (the mod's name, version and
 * variant, a search box with a Ctrl F hint, and round Discord / GitHub / features-doc buttons), a sidebar of categories
 * with an icon and an on/total count, a grid of FEATURE CARDS (name, one line, the feature's own on/off switch; a
 * disabled feature's card is dimmed) and, when a card is clicked, a settings DRAWER on the right holding that feature's
 * tab exactly as it always was built. Depth everywhere: bevels, black outlines, recessed wells, drop shadows, the orange
 * glow on what is selected, the orange scrollbar ({@link MenuStyle}).
 *
 * <p>Nothing about a setting changed - only how the menu looks and how you reach a tab. A card's switch and the drawer's
 * switch press the tab's own master button ({@link FeatureCards}); the drawer's rows are the tab's own widgets, drawn as
 * rows by {@link SettingsButtonWidget}, {@link ThemedSliderButton} and {@link RangeSliderWidget} while this screen is
 * up. The categories are regrouped by {@link MenuCategories}; each leaf keeps its old category for tooltip lookups.
 *
 * <p>State that survives closing and reopening (static): the category, the open drawer, both scroll positions.
 */
public class ModScreen extends Screen {

    // ---- persistent state (static: survives closing and reopening the menu) ----------------------------------------
    private static List<BaseTab> tabs;
    private static int selectedTab = 0;
    /** True when the sidebar's Favourites entry is selected (it is not a category of {@link #tabs}). */
    private static boolean favouritesSelected = false;
    /** The feature whose drawer is open, or null. */
    private static BaseTab openFeature;
    /** The drawer's (or a page's) scroll, in GUI units. */
    private static int scrollOffset = 0;
    /** The card grid's scroll. */
    private static int gridScroll = 0;
    private static String searchQuery = "";
    /** A tab to select by NAME on the next init. */
    private static String pendingTabName;
    /** The scrolling content pane's top edge, for a widget that keeps itself in view (the Custom Crosshair preview). */
    private static int paneTop = 0;

    private final Screen parent;

    // ---- layout (recomputed by layout()) ---------------------------------------------------------------------------
    private int panelX, panelY, panelW, panelH;
    private int headerX, headerY, headerW, headerH;
    private int sideX, sideY, sideW, sideH;
    private int gridX, gridY, gridW, gridH;
    private int drawerX, drawerY, drawerW, drawerH;
    private boolean drawerOverlay;
    private int contentX, contentY, contentW;
    private int searchX, searchY, searchW, searchH;

    private EditBox searchField;
    private boolean paletteOpen;
    private int maxScroll = 0;
    private int visibleContentHeight = 0;
    private ScrollingContentPane contentPane;
    /** The open drawer's own master row (its switch in the header presses it), or null. */
    private MasterSwitch drawerMaster;
    /** The drawer's Basic / Advanced strip: the "▶ Advanced" header row it opens and closes, or null. */
    private SettingsButtonWidget advancedHeader;

    // ---- grid ------------------------------------------------------------------------------------------------------
    private enum ItemKind { CARD, HEADER, BLOCK }

    /** One thing in the centre grid, positioned in grid-content coordinates (before {@link #gridScroll}). */
    private static final class Item {
        final ItemKind kind;
        final BaseTab tab;
        final String text;
        final boolean red;
        final Block block;
        int x, y, w, h;

        Item(ItemKind kind, BaseTab tab, String text, boolean red, Block block) {
            this.kind = kind;
            this.tab = tab;
            this.text = text;
            this.red = red;
            this.block = block;
        }
    }

    /** A full-width piece of the Home page. */
    private interface Block {
        int height(int width);

        void render(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY);

        boolean click(int x, int y, int w, double mx, double my);
    }

    private final List<Item> items = new ArrayList<>();
    private int gridContentH;
    private int gridMax;
    private static final int CARD_H = 66;
    private static final int CARD_MIN_W = 104;
    private static final int GAP = 6;
    private static final int GRID_PAD = 6;

    // ---- sidebar ---------------------------------------------------------------------------------------------------
    private record SideEntry(String label, String icon, int tab, boolean favourites, int x, int y, int w, int h) {
    }

    private final List<SideEntry> side = new ArrayList<>();
    private final List<Integer> sideSeparators = new ArrayList<>();

    // ---- search palette --------------------------------------------------------------------------------------------
    /** One search hit: a feature (its name matched) or a setting inside one. */
    public record Result(BaseTab tab, String label, boolean feature, String where) {
    }

    private final List<Result> results = new ArrayList<>();
    private int paletteSel = 0;
    private int paletteScroll = 0;
    private int palX, palY, palW, palRowsY, palRows;
    private static final int PAL_ROW = 16;

    // ---- links (the ones the mod already publishes) -----------------------------------------------------------------
    static final String DISCORD_URL = "https://discord.gg/hkQMF5fE84";
    /** killer560's channel (2026-10-14: "Add a button to this that will take you to my youtube channel"). */
    static final String YOUTUBE_URL = "https://www.youtube.com/@Killer560_iscool";
    static final String GITHUB_URL = "https://github.com/" + com.killer560.hub.autoupdate.AutoUpdater.REPO;
    static final String FEATURES_URL = "https://docs.google.com/document/d/e/2PACX-1vQsLEy_hlz-Dv518bH7AgYjyoGgdNl2jSAF4T8vzQ"
            + "Hawmkg0f56q6Yrp67Zqncc8h9N-pdAvFLC5_D-/pub";

    /** A setting the drawer should scroll to and flash after the next build (a search hit). */
    private static String pendingFocusLabel;
    /** Set while a search hit's closed section was opened to look for it again (once). */
    private boolean focusRetried;
    private AbstractWidget flashWidget;
    private long flashUntil;

    public ModScreen(Screen parent) {
        this(parent, -1);
    }

    /** Top edge (GUI units) of the menu's scrolling content pane as last laid out. */
    public static int contentPaneTop() {
        return paneTop;
    }

    /** Opens the menu on the category, or the feature (its drawer open), called {@code name}. */
    public static ModScreen atTab(Screen parent, String name) {
        pendingTabName = name;
        return new ModScreen(parent, -1);
    }

    /** Throw the tab list away and rebuild it - and the open menu, if this is it (the testing build's marks). */
    public static void reloadTabs() {
        String open = openFeature == null ? null : openFeature.getClass().getName();
        tabs = null;
        openFeature = null;
        FeatureCards.clear();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (com.killer560.hub.compat.McCompat.screen(mc) instanceof ModScreen screen) {
            ensureTabs();
            if (open != null) {
                for (BaseTab leaf : allLeaves()) {
                    if (leaf.getClass().getName().equals(open)) {
                        screen.navigateTo(leaf);
                    }
                }
            }
            screen.init(screen.width, screen.height);
        }
    }

    /** @param initialTab tab index to jump to, or -1 to keep whichever tab was last selected. */
    public ModScreen(Screen parent, int initialTab) {
        super(Component.literal("Killer560's Mod"));
        this.parent = parent;
        if (initialTab >= 0) {
            selectedTab = initialTab;
            favouritesSelected = false;
        }
    }

    private static void ensureTabs() {
        if (tabs == null) {
            FeatureCards.clear();
            tabs = MenuCategories.build();
            if (com.killer560.hub.BuildVariant.TESTING) {
                tabs = com.killer560.hub.testing.TestingMenu.arrange(tabs);
            }
            MenuCategories.relink(tabs);
        }
    }

    @Override
    protected void init() {
        ensureTabs();
        if (pendingTabName != null) {
            String want = pendingTabName;
            pendingTabName = null;
            boolean found = false;
            for (int i = 0; i < tabs.size(); i++) {
                if (tabs.get(i).name.equals(want)) {
                    selectCategory(i);
                    found = true;
                    break;
                }
            }
            if (!found) {
                for (BaseTab leaf : allLeaves()) {
                    if (leaf.name.equals(want)) {
                        navigateTo(leaf);
                        break;
                    }
                }
            }
        }
        if (selectedTab >= tabs.size() || selectedTab < 0) {
            selectedTab = 0;
        }
        if (openFeature != null && !allLeaves().contains(openFeature)) {
            openFeature = null;
        }

        // Panel: most of the canvas, as the mockup (the canvas is ~853x480 at every Auto Scaled window).
        // Nearly the full width: the drawer needs ~340 units of content (the narrowest the tabs were ever laid out at,
        // the old menu's smallest panel) beside a three-column grid.
        panelW = Math.min(this.width - 12, 1000);
        panelH = Math.max(Math.min(this.height - 12, 300), Math.min((int) (this.height * 0.90), 600));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        headerX = panelX + 4;
        headerY = panelY + 4;
        headerW = panelW - 8;
        headerH = 26;
        int iconsW = 4 * 16 + 4;
        searchW = Math.max(110, Math.min(200, headerW / 3));
        searchH = 16;
        searchX = headerX + headerW - 8 - iconsW - 6 - searchW;
        searchY = headerY + (headerH - searchH) / 2;
        searchField = new EditBox(this.font, searchX + 14, searchY + 4, searchW - 14 - 34, 10, Component.literal("Search"));
        searchField.setBordered(false);
        searchField.setMaxLength(64);
        searchField.setTextColor(MenuStyle.TXT);
        searchField.setTextShadow(MenuStyle.TEXT_SHADOW);
        // The hint is painted by drawHeader: EditBox draws its hint with a text shadow, which smears dark text on the
        // Light theme (the Profiles name box had the same bug, 786297ce).
        // The search is not kept across a reopen: its results list would cover the page you came back to.
        searchQuery = "";
        searchField.setValue(searchQuery);
        searchField.setResponder(text -> {
            searchQuery = text;
            paletteOpen = !text.isBlank();
            paletteSel = 0;
            paletteScroll = 0;
            computeResults();
        });
        if (!searchQuery.isBlank()) {
            computeResults();
        }
        rebuild();
    }

    // ---- navigation ------------------------------------------------------------------------------------------------

    private void selectCategory(int index) {
        selectedTab = index;
        favouritesSelected = false;
        gridScroll = 0;
        if (openFeature != null && MenuCategories.category(openFeature) != tabs.get(index)) {
            openFeature = null;
            scrollOffset = 0;
        }
    }

    /** Opens {@code leaf}'s drawer in its own category (Favourites stays if it is starred there). */
    private void navigateTo(BaseTab leaf) {
        BaseTab cat = MenuCategories.category(leaf);
        boolean keepFav = favouritesSelected && MenuConfig.getInstance().isFavourite(id(leaf));
        if (!keepFav) {
            int idx = tabs.indexOf(cat);
            if (idx >= 0) {
                selectedTab = idx;
                favouritesSelected = false;
            }
        }
        if (openFeature != leaf) {
            scrollOffset = 0;
        }
        openFeature = leaf;
        if (cat == leaf) {
            openFeature = null; // a top-level page (Profiles)
        }
    }

    /** Opens {@code leaf}'s drawer (navigating to its category) and rebuilds. For the testkit and the search. */
    public void openFeature(BaseTab leaf) {
        navigateTo(leaf);
        rebuild();
        scrollGridTo(leaf);
    }

    /** The feature whose drawer is open, or null. */
    public BaseTab openFeature() {
        return openFeature;
    }

    /** Closes the drawer. */
    public void closeDrawer() {
        if (openFeature != null) {
            FeatureCards.refresh(openFeature);
        }
        openFeature = null;
        scrollOffset = 0;
        rebuild();
    }

    /** Selects the sidebar entry called {@code name} ("Favourites" included) and rebuilds; false if there is none. */
    public boolean selectCategory(String name) {
        if ("Favorites".equals(name)) {
            favouritesSelected = true;
            gridScroll = 0;
            rebuild();
            return true;
        }
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).name.equals(name)) {
                selectCategory(i);
                rebuild();
                return true;
            }
        }
        return false;
    }

    static String id(BaseTab tab) {
        return tab.getClass().getSimpleName();
    }

    /** Every feature tab of the menu (leaves of categories and groups, Home's sections, top-level pages). */
    public static List<BaseTab> allLeaves() {
        List<BaseTab> out = new ArrayList<>();
        ensureTabs();
        Set<BaseTab> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (BaseTab t : tabs) {
            collectLeaves(t, out, seen);
        }
        return out;
    }

    private static void collectLeaves(BaseTab t, List<BaseTab> out, Set<BaseTab> seen) {
        if (t instanceof FolderTab f) {
            for (BaseTab s : f.subTabs()) {
                collectLeaves(s, out, seen);
            }
        } else if (seen.add(t)) {
            out.add(t);
        }
    }

    private static List<BaseTab> leavesOf(BaseTab t) {
        List<BaseTab> out = new ArrayList<>();
        collectLeaves(t, out, java.util.Collections.newSetFromMap(new IdentityHashMap<>()));
        return out;
    }

    private BaseTab selected() {
        return tabs.get(selectedTab);
    }

    /** A top-level entry that is a single tab (Profiles) is shown as a page across the grid and drawer. */
    private boolean pageMode() {
        return !favouritesSelected && !(selected() instanceof FolderTab);
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    private void layout() {
        int bodyY = headerY + headerH + 5;
        int bodyH = panelY + panelH - 4 - bodyY;
        sideX = panelX + 4;
        sideY = bodyY;
        sideW = Math.max(110, Math.min(124, panelW * 13 / 100));
        sideH = bodyH;
        int restX = sideX + sideW + 5;
        int restW = panelX + panelW - 4 - restX;
        boolean drawer = openFeature != null && !pageMode();
        int wantDrawer = Math.max(DRAWER_MIN, Math.min(440, restW / 2));
        drawerOverlay = drawer && restW - wantDrawer - 5 < 2 * CARD_MIN_W + 2 * GRID_PAD;
        gridX = restX;
        gridY = bodyY;
        gridH = bodyH;
        if (drawer && !drawerOverlay) {
            drawerW = wantDrawer;
            gridW = restW - drawerW - 5;
            drawerX = gridX + gridW + 5;
        } else {
            gridW = restW;
            drawerW = drawerOverlay ? restW : wantDrawer;
            drawerX = drawerOverlay ? restX : restX + restW - drawerW;
        }
        drawerY = bodyY;
        drawerH = bodyH;

        side.clear();
        sideSeparators.clear();
        int y = sideY + 6;
        int rowH = 18;
        int x = sideX + 4;
        int w = sideW - 8;
        int home = indexOf(HomeTab.class);
        if (home >= 0) {
            side.add(new SideEntry(tabs.get(home).name, "⌂", home, false, x, y, w, rowH));
            y += rowH + 1;
        }
        side.add(new SideEntry("Favorites", "★", -1, true, x, y, w, rowH));
        y += rowH + 4;
        sideSeparators.add(y - 2);
        y += 4;
        int profiles = -1;
        for (int i = 0; i < tabs.size(); i++) {
            BaseTab t = tabs.get(i);
            if (i == home) {
                continue;
            }
            if (!(t instanceof FolderTab)) {
                profiles = profiles < 0 ? i : profiles;
                continue;
            }
            // Only the testing build's Untested category is a folder that is not a CategoryTab.
            String icon = t instanceof CategoryTab c ? c.icon : "⚑";
            side.add(new SideEntry(t.name, icon, i, false, x, y, w, rowH));
            y += rowH + 1;
        }
        y += 3;
        sideSeparators.add(y - 1);
        y += 4;
        for (int i = 0; i < tabs.size(); i++) {
            BaseTab t = tabs.get(i);
            if (i != home && !(t instanceof FolderTab)) {
                side.add(new SideEntry(t.name, "⇄", i, false, x, y, w, rowH));
                y += rowH + 1;
            }
        }
    }

    private int indexOf(Class<?> cls) {
        for (int i = 0; i < tabs.size(); i++) {
            if (cls.isInstance(tabs.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** How many times {@link #rebuild} ran (a press that rebuilds the tab itself is told apart by it). */
    private int rebuilds;

    private void rebuild() {
        rebuilds++;
        this.clearWidgets();
        SettingTooltips.clearScopes();
        layout();
        this.addRenderableWidget(searchField);
        drawerMaster = null;
        advancedHeader = null;
        contentPane = null;
        maxScroll = 0;
        visibleContentHeight = 0;

        buildItems();
        layoutGrid();

        if (pageMode()) {
            contentX = gridX + 8;
            contentY = gridY + 8;
            contentW = gridW - 22;
            int bottom = gridY + gridH - 6;
            buildContent(selected(), bottom);
        } else if (openFeature != null) {
            int top = drawerY + 26;
            if (advancedLabel(openFeature)) {
                top += 16;
            }
            contentX = drawerX + 8;
            contentY = top + 4;
            contentW = drawerW - 22;
            BaseTab built = openFeature;
            boolean reserved = advancedLabel(built);
            buildContent(built, drawerY + drawerH - 6);
            for (AbstractWidget w : contentPane == null ? List.<AbstractWidget>of() : contentPane.children()) {
                if (w instanceof SettingsButtonWidget b && b.parts().look() == SettingsButtonWidget.Look.HEADER
                        && b.parts().label().equalsIgnoreCase("Advanced")) {
                    advancedHeader = b;
                }
            }
            // The strip's room is known only once the real drawer has been built (no off-screen build for it: a tab
            // can consume a one-shot flag in buildWidgets, like Translate's picker for /language). Lay out again once
            // if it changed.
            if (openFeature == built && (advancedHeader != null) != reserved) {
                hasAdvanced.put(built, advancedHeader != null);
                rebuild();
                return;
            }
        }
        resumeLiveDrag();
    }

    /**
     * A slider dragged while the menu is laid out again (HUD Scale resizes this menu live, 2026-10-13) is rebuilt as a
     * new widget: give the drag to the new one, so the press that started it keeps moving it until the release.
     */
    private void resumeLiveDrag() {
        String key = ThemedSliderButton.liveDragKey();
        if (key == null || contentPane == null) {
            return;
        }
        for (AbstractWidget w : contentPane.children()) {
            if (w instanceof ThemedSliderButton s && key.equals(s.dragKey())) {
                this.setFocused(contentPane);
                this.setDragging(true);
                contentPane.setFocused(s);
                contentPane.setDragging(true);
                return;
            }
        }
    }

    /** Whether the tab has an Advanced section (cached): its drawer then gets the Basic / Advanced strip. */
    private static final Map<BaseTab, Boolean> hasAdvanced = new IdentityHashMap<>();

    private boolean advancedLabel(BaseTab tab) {
        return tab != null && hasAdvanced.getOrDefault(tab, false);
    }

    private void buildContent(BaseTab tab, int visibleBottom) {
        paneTop = contentY;
        List<AbstractWidget> widgets = new ArrayList<>();
        int startY = contentY;
        if (com.killer560.hub.BuildVariant.TESTING) {
            widgets.add(com.killer560.hub.testing.TestingMenu.markButton(tab, contentX, startY, contentW));
            startY += com.killer560.hub.testing.TestingMenu.MARK_ROW_HEIGHT;
        }
        List<AbstractWidget> built = tab.buildWidgets(contentX, startY, contentW, this::rebuild);
        for (AbstractWidget w : built) {
            SettingTooltips.scope(w, tab.name);
        }
        if (openFeature == tab && contentW < STACK_BELOW) {
            stackRows(built, contentX, contentW);
        }
        widgets.addAll(built);
        if (openFeature == tab) {
            drawerMaster = MasterSwitch.of(tab.masterToggles(built));
            if (drawerMaster != null) {
                FeatureCards.setKnownState(tab, drawerMaster.isOn());
            }
        }

        if (pendingFocusLabel != null) {
            String want = pendingFocusLabel.toLowerCase(Locale.ROOT);
            boolean found = false;
            for (AbstractWidget w : widgets) {
                if (label(w).toLowerCase(Locale.ROOT).contains(want)) {
                    scrollOffset = Math.max(0, w.getY() - contentY - 24);
                    flashWidget = w;
                    flashUntil = System.currentTimeMillis() + 1600;
                    found = true;
                    break;
                }
            }
            if (!found && !focusRetried) {
                // A setting found by search can sit in a closed Advanced section: open it (its own saved flag) and look
                // again once the tab has rebuilt.
                for (AbstractWidget w : widgets) {
                    if (w instanceof SettingsButtonWidget b && b.parts().look() == SettingsButtonWidget.Look.HEADER
                            && !b.parts().on()) {
                        focusRetried = true;
                        int before = rebuilds;
                        b.press();
                        if (rebuilds == before) {
                            rebuild();
                        }
                        return;
                    }
                }
            }
            pendingFocusLabel = null;
            focusRetried = false;
        }

        int naturalBottom = contentY;
        for (AbstractWidget w : widgets) {
            naturalBottom = Math.max(naturalBottom, w.getY() + w.getHeight());
        }
        visibleContentHeight = visibleBottom - contentY;
        maxScroll = Math.max(0, (naturalBottom + 6 - contentY) - visibleContentHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        for (AbstractWidget w : widgets) {
            w.setY(w.getY() - scrollOffset);
        }
        // The pane reaches 4 units above the first row, so a row scrolled to the very top is clear of the clip edge.
        contentPane = new ScrollingContentPane(contentX - 4, contentY - 4, contentW + 8, visibleContentHeight + 4, widgets);
        this.addRenderableWidget(contentPane);
    }

    /** A drawer narrower than this stacks a tab's side-by-side setting rows (see {@link #stackRows}). */
    private static final int STACK_BELOW = 380;
    /** The drawer's narrowest: 340 units of content (22 go to its padding and scrollbar). */
    private static final int DRAWER_MIN = 362;

    private static boolean simpleRow(AbstractWidget w) {
        return w instanceof SettingsButtonWidget || w instanceof ThemedSliderButton || w instanceof RangeSliderWidget;
    }

    /**
     * Puts side-by-side setting rows one under the other, each the drawer's full width, and moves everything below down
     * to make room (killer560 left side-by-side vs stacked to us: in the narrow drawer a half-width row cut most labels
     * short). Only a line made purely of plain setting rows (buttons and sliders sharing one y, nothing else overlapping
     * it) is stacked; a line holding anything else (a text box and its Set button, a preview, a list) keeps its layout and
     * only moves down. Every widget keeps its own behaviour - only its x, y and width change.
     */
    static void stackRows(List<AbstractWidget> widgets, int x, int width) {
        List<AbstractWidget> order = new ArrayList<>(widgets);
        order.sort(java.util.Comparator.comparingInt(AbstractWidget::getY).thenComparingInt(AbstractWidget::getX));
        Map<AbstractWidget, Integer> newY = new IdentityHashMap<>();
        // rows: simple widgets grouped by identical y
        java.util.TreeMap<Integer, List<AbstractWidget>> rows = new java.util.TreeMap<>();
        for (AbstractWidget w : order) {
            if (simpleRow(w) && w.visible) {
                rows.computeIfAbsent(w.getY(), k -> new ArrayList<>()).add(w);
            }
        }
        // the shift to apply to anything starting at or below a given y, built row by row
        java.util.TreeMap<Integer, Integer> shiftFrom = new java.util.TreeMap<>();
        int shift = 0;
        Set<AbstractWidget> stacked = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<Integer, List<AbstractWidget>> e : rows.entrySet()) {
            List<AbstractWidget> row = e.getValue();
            int y = e.getKey();
            if (row.size() < 2) {
                continue;
            }
            int h = 0;
            for (AbstractWidget w : row) {
                h = Math.max(h, w.getHeight());
            }
            boolean clean = true;
            for (AbstractWidget w : order) {
                if (!row.contains(w) && w.visible && w.getY() < y + h && w.getY() + w.getHeight() > y) {
                    clean = false;
                    break;
                }
            }
            if (!clean) {
                continue;
            }
            int pitch = h + 2;
            int base = y + shiftAt(shiftFrom, y);
            for (int i = 0; i < row.size(); i++) {
                AbstractWidget w = row.get(i);
                newY.put(w, base + i * pitch);
                w.setX(x);
                w.setWidth(width);
                stacked.add(w);
            }
            shift += (row.size() - 1) * pitch;
            shiftFrom.put(y + h, shift);
        }
        if (shiftFrom.isEmpty()) {
            return;
        }
        for (AbstractWidget w : order) {
            if (stacked.contains(w)) {
                w.setY(newY.get(w));
            } else {
                w.setY(w.getY() + shiftAt(shiftFrom, w.getY()));
            }
        }
    }

    private static int shiftAt(java.util.TreeMap<Integer, Integer> shiftFrom, int y) {
        Map.Entry<Integer, Integer> e = shiftFrom.floorEntry(y);
        return e == null ? 0 : e.getValue();
    }

    static String label(AbstractWidget w) {
        String s = w.getMessage().getString();
        String plain = net.minecraft.ChatFormatting.stripFormatting(s);
        return plain == null ? s : plain;
    }

    private void buildItems() {
        items.clear();
        if (pageMode()) {
            return;
        }
        if (favouritesSelected) {
            List<BaseTab> leaves = allLeaves();
            int added = 0;
            for (String fav : MenuConfig.getInstance().getFavourites()) {
                for (BaseTab leaf : leaves) {
                    if (id(leaf).equals(fav)) {
                        items.add(new Item(ItemKind.CARD, leaf, null, false, null));
                        added++;
                        break;
                    }
                }
            }
            if (added == 0) {
                items.add(new Item(ItemKind.BLOCK, null, null, false, new HintBlock(
                        "No favorites yet. Click the star on a feature card, or in a drawer's title bar, to pin it here.")));
            }
            return;
        }
        BaseTab cat = selected();
        if (cat instanceof HomeTab) {
            items.add(new Item(ItemKind.BLOCK, null, null, false, new HeroBlock()));
            items.add(new Item(ItemKind.BLOCK, null, null, false, new ThemeBlock()));
            items.add(new Item(ItemKind.HEADER, null, "Mod settings", false, null));
        }
        FolderTab folder = (FolderTab) cat;
        List<BaseTab> legit = new ArrayList<>();
        List<BaseTab> cheat = new ArrayList<>();
        for (BaseTab s : folder.subTabs()) {
            if (cat instanceof HomeTab && s instanceof com.killer560.hub.gui.tab.MenuThemeTab) {
                continue; // killer560, 2026-10-13: set from Home's Menu Theme row; Customise... still opens its drawer
            }
            (s.isCheatOnly() ? cheat : legit).add(s);
        }
        addSections(legit, false);
        if (!cheat.isEmpty()) {
            items.add(new Item(ItemKind.HEADER, null, "Cheat", true, null));
            addSections(cheat, true);
        }
    }

    private void addSections(List<BaseTab> sections, boolean cheatBlock) {
        List<FolderTab> groups = new ArrayList<>();
        for (BaseTab s : sections) {
            if (s instanceof FolderTab g) {
                groups.add(g);
            } else {
                items.add(new Item(ItemKind.CARD, s, null, false, null));
            }
        }
        // The groups killer560 asked to keep together (Secrets, Puzzle Solvers) follow as titled runs of cards.
        for (FolderTab g : groups) {
            items.add(new Item(ItemKind.HEADER, g, g.name, cheatBlock || g.isCheatOnly(), null));
            for (BaseTab s : g.subTabs()) {
                if (!(s instanceof FolderTab)) {
                    items.add(new Item(ItemKind.CARD, s, null, false, null));
                }
            }
        }
    }

    private int gridInnerX() {
        return gridX + GRID_PAD;
    }

    private int gridInnerW() {
        return gridW - 2 * GRID_PAD - 6;
    }

    private void layoutGrid() {
        int innerW = gridInnerW();
        int cols = Math.max(1, Math.min(5, (innerW + GAP) / (CARD_MIN_W + GAP)));
        int cardW = (innerW - (cols - 1) * GAP) / cols;
        int x0 = gridInnerX();
        int y = gridY + GRID_PAD;
        int col = 0;
        boolean first = true;
        for (Item it : items) {
            if (it.kind == ItemKind.CARD) {
                if (col == cols) {
                    col = 0;
                    y += CARD_H + GAP;
                }
                it.x = x0 + col * (cardW + GAP);
                it.y = y;
                it.w = cardW;
                it.h = CARD_H;
                col++;
                first = false;
                continue;
            }
            if (col > 0) {
                y += CARD_H + GAP;
                col = 0;
            }
            if (it.kind == ItemKind.HEADER) {
                if (!first) {
                    y += 2;
                }
                it.x = x0;
                it.y = y;
                it.w = innerW;
                it.h = 14;
                y += 14 + GAP;
            } else {
                it.x = x0;
                it.y = y;
                it.w = innerW;
                it.h = it.block.height(innerW);
                y += it.h + GAP;
            }
            first = false;
        }
        if (col > 0) {
            y += CARD_H + GAP;
        }
        gridContentH = y - (gridY + GRID_PAD) + GRID_PAD - GAP;
        gridMax = Math.max(0, gridContentH - (gridH - 2 * GRID_PAD) + GRID_PAD);
        gridScroll = Math.max(0, Math.min(gridScroll, gridMax));
    }

    private void scrollGridTo(BaseTab leaf) {
        for (Item it : items) {
            if (it.kind == ItemKind.CARD && it.tab == leaf) {
                int top = gridY + GRID_PAD;
                int bottom = gridY + gridH - GRID_PAD;
                if (it.y - gridScroll < top) {
                    gridScroll = it.y - top;
                } else if (it.y + it.h - gridScroll > bottom) {
                    gridScroll = it.y + it.h - bottom;
                }
                gridScroll = Math.max(0, Math.min(gridScroll, gridMax));
                return;
            }
        }
    }

    // ---- input -----------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (paletteOpen) {
            layoutPalette();
        }
        if (paletteOpen && inside(mouseX, mouseY, palX, palY, palW, palRowsY + palRows * PAL_ROW - palY + 4)) {
            int max = Math.max(0, results.size() - palRows);
            paletteScroll = Math.max(0, Math.min(max, paletteScroll - (int) Math.signum(scrollY)));
            return true;
        }
        // A list that scrolls itself gets the wheel first, and keeps it while the cursor is over it.
        if (contentPane != null && contentPane.hoveredChild((int) mouseX, (int) mouseY) instanceof WheelTarget target
                && target.wheel(mouseX, mouseY, scrollY)) {
            return true;
        }
        boolean overContent = pageMode()
                ? inside(mouseX, mouseY, gridX, gridY, gridW, gridH)
                : openFeature != null && inside(mouseX, mouseY, drawerX, drawerY, drawerW, drawerH);
        if (overContent) {
            if (maxScroll > 0) {
                scrollOffset -= (int) Math.round(scrollY * 16);
                rebuild();
            }
            return true;
        }
        if (inside(mouseX, mouseY, gridX, gridY, gridW, gridH) && gridMax > 0) {
            gridScroll = Math.max(0, Math.min(gridMax, gridScroll - (int) Math.round(scrollY * 20)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        KeyCaptureTab listening = findListeningKeyCaptureTab();
        if (listening != null) {
            listening.onKeyCaptured(keyEvent.key());
            rebuild();
            return true;
        }
        int key = keyEvent.key();
        // Ctrl F (killer560, 2026-10-13: "make the ctrl k become ctrl f to search"). A text box in the page or drawer
        // that has focus is asked first, so it keeps any Ctrl shortcut of its own; vanilla's EditBox has none on F.
        if (key == InputConstants.KEY_F && keyEvent.hasControlDown()) {
            GuiEventListener typing = contentPane == null ? null : contentPane.getFocused();
            if (typing instanceof EditBox && typing.keyPressed(keyEvent)) {
                return true;
            }
            focusSearch();
            return true;
        }
        boolean searching = searchField.isFocused() || paletteOpen;
        if (searching) {
            if (key == InputConstants.KEY_ESCAPE) {
                clearSearch();
                return true;
            }
            if (paletteOpen && !results.isEmpty()) {
                if (key == InputConstants.KEY_DOWN) {
                    paletteSel = Math.min(results.size() - 1, paletteSel + 1);
                    keepPaletteSelVisible();
                    return true;
                }
                if (key == InputConstants.KEY_UP) {
                    paletteSel = Math.max(0, paletteSel - 1);
                    keepPaletteSelVisible();
                    return true;
                }
                if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
                    activate(results.get(Math.max(0, Math.min(paletteSel, results.size() - 1))));
                    return true;
                }
            }
        }
        // Escape closes the menu with the drawer left open (killer560, 2026-10-13: reopening the menu reopens the same
        // drawer at the same scroll - openFeature and scrollOffset are static). The drawer's X closes the drawer.
        return super.keyPressed(keyEvent);
    }

    private void keepPaletteSelVisible() {
        if (paletteSel < paletteScroll) {
            paletteScroll = paletteSel;
        } else if (paletteRowsShown() > 0 && paletteSel >= paletteScroll + paletteRowsShown()) {
            paletteScroll = paletteSel - paletteRowsShown() + 1;
        }
    }

    /** Puts the cursor in the search box (Ctrl F). */
    public void focusSearch() {
        this.setFocused(searchField);
        searchField.setFocused(true);
        paletteOpen = !searchQuery.isBlank();
    }

    private void clearSearch() {
        searchField.setValue("");
        searchQuery = "";
        paletteOpen = false;
        results.clear();
        searchField.setFocused(false);
        if (this.getFocused() == searchField) {
            this.setFocused(null);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A tab waiting for a bind eats the next mouse press too, so a bind can be a mouse button (killer560,
        // 2026-09-20). Checked before anything else so the click doesn't also press what is under the cursor.
        KeyCaptureTab listening = findListeningKeyCaptureTab();
        if (listening != null && listening.supportsMouseCapture()) {
            listening.onMouseCaptured(event.button());
            rebuild();
            return true;
        }
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (paletteOpen) {
            if (paletteClick(mx, my, button)) {
                return true;
            }
            if (!inside(mx, my, searchX, searchY, searchW, searchH)) {
                clearSearch();
                return true;
            }
        }
        if (inside(mx, my, searchX, searchY, searchW, searchH)) {
            focusSearch();
            return super.mouseClicked(event, doubleClick);
        }
        if (headerClick(mx, my, button)) {
            unfocusSearch();
            return true;
        }
        if (button == 0 && sidebarClick(mx, my)) {
            unfocusSearch();
            return true;
        }
        if (openFeature != null && !pageMode() && inside(mx, my, drawerX, drawerY, drawerW, drawerH)) {
            if (button == 0 && drawerHeaderClick(mx, my)) {
                unfocusSearch();
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }
        if (!pageMode() && inside(mx, my, gridX, gridY, gridW, gridH)) {
            unfocusSearch();
            if (button == 0) {
                gridClick(mx, my);
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private void unfocusSearch() {
        if (searchField.isFocused()) {
            searchField.setFocused(false);
        }
        if (this.getFocused() == searchField) {
            this.setFocused(null);
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void clickSound() {
        Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    private boolean headerClick(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        // The fourth round icon was the theme switch; killer560 (2026-10-14) had it replaced by his YouTube channel.
        // Themes are picked in the Themes tab.
        String[] urls = {DISCORD_URL, GITHUB_URL, FEATURES_URL, YOUTUBE_URL};
        for (int i = 0; i < 4; i++) {
            int[] b = iconBounds(i);
            if (inside(mx, my, b[0], b[1], b[2], b[3])) {
                clickSound();
                com.killer560.hub.util.ExternalOpen.uri(urls[i]);
                return true;
            }
        }
        return false;
    }

    /** The header's round buttons {x, y, w, h}: 0 Discord, 1 GitHub, 2 features doc, 3 YouTube. */
    public int[] iconBounds(int i) {
        int size = 14;
        int x = headerX + headerW - 8 - (4 - i) * (size + 2) + 2;
        return new int[]{x, headerY + (headerH - size) / 2, size, size};
    }

    private boolean sidebarClick(double mx, double my) {
        for (SideEntry e : side) {
            if (inside(mx, my, e.x(), e.y(), e.w(), e.h())) {
                clickSound();
                if (e.favourites()) {
                    favouritesSelected = true;
                    gridScroll = 0;
                } else {
                    selectCategory(e.tab());
                    if (pageMode()) {
                        openFeature = null;
                        scrollOffset = 0;
                    }
                }
                rebuild();
                return true;
            }
        }
        return false;
    }

    // drawer header controls
    private int[] drawerCloseBounds() {
        return new int[]{drawerX + drawerW - 18, drawerY + 6, 12, 12};
    }

    private int[] drawerSwitchBounds() {
        return new int[]{drawerX + drawerW - 22 - MenuStyle.SWITCH_W - 4, drawerY + 7, MenuStyle.SWITCH_W,
                MenuStyle.SWITCH_H};
    }

    private int[] drawerStarBounds() {
        int sx = drawerMaster != null ? drawerSwitchBounds()[0] - 16 : drawerX + drawerW - 22 - 14;
        return new int[]{sx, drawerY + 5, 12, 14};
    }

    private int[] stripBounds(int i) {
        int y = drawerY + 26;
        int w = 52;
        return new int[]{drawerX + 8 + i * (w + 4), y, w, 14};
    }

    private boolean drawerHeaderClick(double mx, double my) {
        int[] c = drawerCloseBounds();
        if (inside(mx, my, c[0] - 2, c[1] - 2, c[2] + 4, c[3] + 4)) {
            clickSound();
            closeDrawer();
            return true;
        }
        if (drawerMaster != null) {
            int[] s = drawerSwitchBounds();
            if (inside(mx, my, s[0] - 2, s[1] - 2, s[2] + 4, s[3] + 4)) {
                clickSound();
                pressDrawerMaster();
                return true;
            }
        }
        int[] st = drawerStarBounds();
        if (inside(mx, my, st[0], st[1], st[2], st[3])) {
            clickSound();
            toggleFavourite(openFeature);
            return true;
        }
        if (advancedLabel(openFeature)) {
            for (int i = 0; i < 2; i++) {
                int[] b = stripBounds(i);
                if (inside(mx, my, b[0], b[1], b[2], b[3])) {
                    clickSound();
                    showAdvanced(i == 1);
                    return true;
                }
            }
        }
        return my < drawerY + 26 + (advancedLabel(openFeature) ? 16 : 0);
    }

    /** The drawer's Basic / Advanced strip: Advanced opens the tab's own Advanced section and scrolls to it, Basic closes
     *  it and goes back to the top. The section's own open flag (saved by the tab) stays the one source of truth. */
    private void showAdvanced(boolean advanced) {
        if (advancedHeader == null) {
            return;
        }
        boolean open = advancedHeader.parts().on();
        if (open != advanced) {
            advancedHeader.press();
        }
        if (advanced) {
            pendingFocusLabel = null;
            rebuild();
            if (advancedHeader != null) {
                scrollOffset += advancedHeader.getY() - contentY;
                rebuild();
            }
        } else {
            scrollOffset = 0;
            rebuild();
        }
    }

    /** The drawer title's switch: presses the open tab's own master row (exactly a click on it). */
    public void pressDrawerMaster() {
        if (drawerMaster == null || openFeature == null) {
            return;
        }
        BaseTab tab = openFeature;
        drawerMaster.press();
        FeatureCards.refresh(tab);
        rebuild();
    }

    private void toggleFavourite(BaseTab tab) {
        if (tab == null) {
            return;
        }
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.setFavourite(id(tab), !cfg.isFavourite(id(tab)));
        cfg.save();
        rebuild();
    }

    /** Presses a card's switch: the tab's own master button, built off-screen (see {@link FeatureCards}). */
    public void toggleCard(BaseTab tab) {
        if (openFeature == tab && drawerMaster != null) {
            pressDrawerMaster();
            return;
        }
        FeatureCards.toggle(tab, () -> { });
        rebuild();
    }

    private void gridClick(double mx, double my) {
        double gy = my + gridScroll;
        if (my < gridY + 2 || my > gridY + gridH - 2) {
            return;
        }
        for (Item it : items) {
            if (!inside(mx, gy, it.x, it.y, it.w, it.h)) {
                continue;
            }
            if (it.kind == ItemKind.BLOCK) {
                it.block.click(it.x, it.y - gridScroll, it.w, mx, my);
                return;
            }
            if (it.kind != ItemKind.CARD) {
                return;
            }
            clickSound();
            int[] sw = cardSwitch(it);
            if (FeatureCards.state(it.tab) != null && inside(mx, gy, sw[0] - 3, sw[1] - 3, sw[2] + 6, sw[3] + 6)) {
                toggleCard(it.tab);
                return;
            }
            int[] st = cardStar(it);
            if (inside(mx, gy, st[0] - 2, st[1] - 2, st[2] + 4, st[3] + 4)) {
                toggleFavourite(it.tab);
                return;
            }
            if (openFeature == it.tab) {
                closeDrawer();
            } else {
                if (openFeature != null) {
                    FeatureCards.refresh(openFeature);
                }
                openFeature = it.tab;
                scrollOffset = 0;
                rebuild();
                scrollGridTo(it.tab);
            }
            return;
        }
    }

    private static int[] cardSwitch(Item it) {
        return new int[]{it.x + it.w - 7 - MenuStyle.SWITCH_W, it.y + 7, MenuStyle.SWITCH_W, MenuStyle.SWITCH_H};
    }

    private static int[] cardStar(Item it) {
        return new int[]{it.x + 5, it.y + it.h - 13, 10, 10};
    }

    /** Screen bounds {x, y, w, h} of {@code tab}'s card, its switch and its star (each null when not on screen). */
    public int[][] cardBounds(BaseTab tab) {
        for (Item it : items) {
            if (it.kind == ItemKind.CARD && it.tab == tab) {
                int[] sw = cardSwitch(it);
                int[] st = cardStar(it);
                return new int[][]{{it.x, it.y - gridScroll, it.w, it.h}, {sw[0], sw[1] - gridScroll, sw[2], sw[3]},
                        {st[0], st[1] - gridScroll, st[2], st[3]}};
            }
        }
        return null;
    }

    /** Scrolls the grid so {@code tab}'s card is in view (no drawer change). */
    public void revealCard(BaseTab tab) {
        scrollGridTo(tab);
    }

    /** The features with a card on the current page, in order. */
    public List<BaseTab> cards() {
        List<BaseTab> out = new ArrayList<>();
        for (Item it : items) {
            if (it.kind == ItemKind.CARD) {
                out.add(it.tab);
            }
        }
        return out;
    }

    /** The grid's section headers on the current page as "text" or "!text" for a red one, in order. */
    public List<String> gridHeaders() {
        List<String> out = new ArrayList<>();
        for (Item it : items) {
            if (it.kind == ItemKind.HEADER) {
                out.add((it.red ? "!" : "") + it.text);
            }
        }
        return out;
    }

    /** True while a tab waits for a key ("Press any key..."): that key is his, not a shortcut (util/TypingDetector). */
    public boolean isCapturingKey() {
        return findListeningKeyCaptureTab() != null;
    }

    private KeyCaptureTab findListeningKeyCaptureTab() {
        if (tabs == null) {
            return null;
        }
        BaseTab active = pageMode() ? selected() : openFeature;
        if (active instanceof KeyCaptureTab captureTab && captureTab.isListeningForKey()) {
            return captureTab;
        }
        if (active instanceof FolderTab folder) {
            return folder.findListeningKeyCaptureTab();
        }
        // Home's sections are pinned (always shown in the old menu): one of them waiting counts while Home is up.
        if (!favouritesSelected && selected() instanceof HomeTab home) {
            return home.findListeningKeyCaptureTab();
        }
        return null;
    }

    // ---- search ----------------------------------------------------------------------------------------------------

    /** The search hits for the text in the box (features first, then settings). For the testkit too. */
    public List<Result> searchResults() {
        return new ArrayList<>(results);
    }

    /** Categories holding a search hit (the old menu's "visible tabs"), for the testkit. */
    private List<BaseTab> visibleTabs() {
        if (searchQuery.isBlank()) {
            return tabs;
        }
        Set<BaseTab> out = new LinkedHashSet<>();
        for (Result r : results) {
            out.add(MenuCategories.category(r.tab()));
        }
        return new ArrayList<>(out);
    }

    private void computeResults() {
        results.clear();
        if (searchQuery.isBlank()) {
            return;
        }
        List<String> variants = SearchAliases.variants(searchQuery);
        List<BaseTab> leaves = allLeaves();
        Set<BaseTab> named = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (BaseTab leaf : leaves) {
            for (String q : variants) {
                if (leaf.name.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT).trim())) {
                    results.add(new Result(leaf, leaf.name, true, where(leaf)));
                    named.add(leaf);
                    break;
                }
            }
        }
        List<Result> settings = new ArrayList<>();
        for (BaseTab leaf : leaves) {
            String hit = null;
            for (String q : variants) {
                if (leaf.matchesSearch(q)) {
                    hit = q;
                    break;
                }
            }
            if (hit == null) {
                continue;
            }
            int found = 0;
            try {
                for (String l : leaf.searchHits(hit)) {
                    if (named.contains(leaf) && l.equalsIgnoreCase(leaf.name)) {
                        continue;
                    }
                    settings.add(new Result(leaf, l, false, where(leaf) + " › " + leaf.name));
                    if (++found == 3) {
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
            }
            if (found == 0 && !named.contains(leaf)) {
                settings.add(new Result(leaf, "a setting in " + leaf.name, false, where(leaf) + " › " + leaf.name));
            }
        }
        results.addAll(settings);
    }

    private static String where(BaseTab leaf) {
        BaseTab parent = MenuCategories.parent(leaf);
        BaseTab cat = MenuCategories.category(leaf);
        if (parent != null && parent != cat) {
            return cat.name + " › " + parent.name;
        }
        return cat.name;
    }

    /** Opens what a hit names: the feature's drawer, scrolled to the setting for a setting hit. */
    public void activate(Result r) {
        clearSearch();
        if (!r.feature()) {
            pendingFocusLabel = r.label().startsWith("a setting in ") ? null : r.label();
        }
        navigateTo(r.tab());
        rebuild();
        scrollGridTo(r.tab());
    }

    private int paletteRowsShown() {
        return palRows;
    }

    private boolean paletteClick(double mx, double my, int button) {
        layoutPalette();
        int bottom = palRowsY + palRows * PAL_ROW;
        if (!inside(mx, my, palX, palY, palW, bottom - palY + 4)) {
            return false;
        }
        if (my < palRowsY) {
            return true;
        }
        int i = paletteScroll + (int) ((my - palRowsY) / PAL_ROW);
        if (i < 0 || i >= results.size()) {
            return true;
        }
        Result r = results.get(i);
        clickSound();
        if (r.feature() && FeatureCards.state(r.tab()) != null) {
            int sx = palX + palW - 8 - MenuStyle.SWITCH_W;
            if (mx >= sx - 3) {
                FeatureCards.toggle(r.tab(), () -> { });
                if (openFeature == r.tab()) {
                    rebuild();
                }
                return true;
            }
        }
        if (button == 0) {
            activate(r);
        }
        return true;
    }

    // ---- rendering -------------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (openFeature != null && drawerMaster != null) {
            FeatureCards.setKnownState(openFeature, drawerMaster.isOn());
        }
        MenuStyle.use();
        g.fill(0, 0, this.width, this.height, MenuStyle.BACKDROP);
        drawFrame(g);
        drawHeader(g, mouseX, mouseY);
        drawSidebar(g, mouseX, mouseY);
        if (pageMode()) {
            drawPageWell(g);
        } else {
            drawGrid(g, mouseX, mouseY);
            if (openFeature != null) {
                drawDrawer(g, mouseX, mouseY);
            }
        }
        boolean paletteUp = paletteOpen && !searchQuery.isBlank();
        super.extractRenderState(g, paletteUp ? -1 : mouseX, paletteUp ? -1 : mouseY, partialTick);
        drawContentDecor(g);
        if (paletteUp) {
            drawPalette(g, mouseX, mouseY);
        } else {
            drawSettingTooltip(g, mouseX, mouseY);
        }
    }

    private void drawFrame(GuiGraphicsExtractor g) {
        GuiRects r = GuiRects.begin(g);
        // drop shadow (0 18px 50px #000c): a few widening dark rings below and around the panel
        for (int i = 6; i >= 1; i--) {
            int a = 0x18 + (6 - i) * 0x10;
            r.fill(panelX - i, panelY - i + 4, panelX + panelW + i, panelY + panelH + i + 4, MenuStyle.shadow(a / 2));
        }
        r.outline(panelX - 1, panelY - 1, panelW + 2, panelH + 2, MenuStyle.FRAME_RING);
        MenuStyle.bevel(r, panelX, panelY, panelW, panelH, MenuStyle.PANEL_TOP, MenuStyle.PANEL_BOTTOM);
        r.outline(panelX, panelY, panelW, panelH, MenuStyle.BLACK);
        r.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 2, MenuStyle.HILITE_SOFT);
        r.submit();
    }

    private void drawHeader(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, headerX, headerY, headerW, headerH, 2);
        MenuStyle.bevel(r, headerX, headerY, headerW, headerH, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        // search well
        boolean focus = searchField.isFocused();
        MenuStyle.well(r, searchX, searchY, searchW, searchH, MenuStyle.WELL_DEEP);
        if (focus || inside(mouseX, mouseY, searchX, searchY, searchW, searchH)) {
            r.outline(searchX - 1, searchY - 1, searchW + 2, searchH + 2, focus ? MenuStyle.OR2 : MenuStyle.KEY_CHIP_EDGE);
        }
        // Ctrl F chip
        boolean showHint = searchQuery.isEmpty();
        int kw = font.width("Ctrl F") + 6;
        int kx = searchX + searchW - kw - 3;
        if (showHint) {
            r.outline(kx, searchY + 3, kw, searchH - 6, MenuStyle.KEY_CHIP_EDGE);
            r.fill(kx + 1, searchY + 4, kx + kw - 1, searchY + searchH - 4, MenuStyle.KEY_CHIP_BG);
        }
        // icons
        // Discord's, GitHub's and YouTube's own colours, the features doc in the menu's accent.
        int[] colours = {0xFF5865F2, 0xFF333333, MenuStyle.OR2, 0xFFFF0033};
        for (int i = 0; i < 4; i++) {
            int[] b = iconBounds(i);
            boolean hover = inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
            roundIcon(r, b[0], b[1], b[2], hover ? MenuStyle.mix(colours[i], 0xFFFFFFFF, 0.18f) : colours[i], hover);
        }
        r.submit();
        // brand: gradient title, a third larger, then the version and variant
        float scale = 4f / 3f;
        int brandY = headerY + (headerH - Math.round(8 * scale)) / 2;
        MenuStyle.gradientText(g, "Killer560's Mod", headerX + 8, brandY, scale, MenuStyle.BRAND_TOP, MenuStyle.BRAND_BOTTOM);
        int bw = Math.round(font.width("Killer560's Mod") * scale);
        String ver = modVersion() + " · " + com.killer560.hub.autoupdate.BuildInfo.variant()
                + (com.killer560.hub.BuildVariant.TESTING ? " · testing" : "");
        MenuStyle.text(g, ver, headerX + 8 + bw + 8, headerY + (headerH - 8) / 2 + 1, MenuStyle.DIM2);
        MenuStyle.flat(g, "⌕", searchX + 4, searchY + 4, MenuStyle.DIM2);
        // The theme can change with the menu open (the ◐ icon), so the box takes its colour and shadow every frame.
        searchField.setTextColor(MenuStyle.TXT);
        searchField.setTextShadow(MenuStyle.TEXT_SHADOW);
        if (showHint && !focus) {
            MenuStyle.flat(g, MenuStyle.ellipsize("Search features & settings", kx - 3 - searchField.getX()),
                    searchField.getX(), searchField.getY(), MenuStyle.DIM2);
        }
        if (showHint) {
            MenuStyle.flat(g, "Ctrl F", kx + 3, searchY + 4, MenuStyle.DIM2);
        }
        String[] glyphs = {"D", "G", "?", "▶"};
        for (int i = 0; i < 4; i++) {
            int[] b = iconBounds(i);
            g.text(font, glyphs[i], b[0] + (b[2] - font.width(glyphs[i])) / 2 + 1, b[1] + 3, 0xFFFFFFFF, true);
        }
    }

    private static void roundIcon(GuiRects r, int x, int y, int s, int colour, boolean hover) {
        r.fill(x + 3, y - 1 + 1, x + s - 3, y + s, MenuStyle.BLACK);
        r.fill(x + 1, y + 1, x + s - 1, y + s - 1, MenuStyle.BLACK);
        r.fill(x, y + 3, x + s, y + s - 3, MenuStyle.BLACK);
        r.fill(x + 3, y + 1, x + s - 3, y + s - 1, colour);
        r.fill(x + 2, y + 2, x + s - 2, y + s - 2, colour);
        r.fill(x + 1, y + 3, x + s - 1, y + s - 3, colour);
        r.fill(x + 3, y + 1, x + s - 3, y + 3, 0x40FFFFFF);
        r.fill(x + 3, y + s - 3, x + s - 3, y + s - 1, MenuStyle.shadow(0x50));
        if (hover) {
            MenuStyle.glow(r, x, y, s, s, colour, 0x40);
        }
    }

    private static String modVersion() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("killer560smod")
                    .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        } catch (RuntimeException e) {
            return "?";
        }
    }

    private void drawSidebar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, sideX, sideY, sideW, sideH, 3);
        MenuStyle.bevel(r, sideX, sideY, sideW, sideH, MenuStyle.SIDE_TOP, MenuStyle.SIDE_BOTTOM);
        for (int y : sideSeparators) {
            MenuStyle.separator(r, sideX + 6, sideX + sideW - 6, y);
        }
        for (SideEntry e : side) {
            boolean sel = e.favourites() ? favouritesSelected : (!favouritesSelected && e.tab() == selectedTab);
            boolean hover = inside(mouseX, mouseY, e.x(), e.y(), e.w(), e.h());
            if (sel) {
                MenuStyle.dropShadow(r, e.x(), e.y(), e.w(), e.h(), 2);
                MenuStyle.glow(r, e.x(), e.y(), e.w(), e.h(), MenuStyle.OR, 0x22);
                MenuStyle.hGradient(r, e.x() + 1, e.y() + 1, e.x() + e.w() - 1, e.y() + e.h() - 1, MenuStyle.SEL_TOP, MenuStyle.SEL_BOTTOM, 6);
                r.outline(e.x(), e.y(), e.w(), e.h(), MenuStyle.ACCENT_EDGE);
                r.fill(e.x() + 1, e.y() + 1, e.x() + e.w() - 1, e.y() + 2, MenuStyle.HILITE);
                r.fill(e.x() + 1, e.y() + 1, e.x() + 3, e.y() + e.h() - 1, MenuStyle.OR);
            } else if (hover) {
                r.fill(e.x(), e.y(), e.x() + e.w(), e.y() + e.h(), MenuStyle.alpha(MenuStyle.TXT, 0x14));
            }
        }
        r.submit();
        for (SideEntry e : side) {
            boolean sel = e.favourites() ? favouritesSelected : (!favouritesSelected && e.tab() == selectedTab);
            boolean hover = inside(mouseX, mouseY, e.x(), e.y(), e.w(), e.h());
            int ty = e.y() + (e.h() - 8) / 2;
            int tc = sel ? MenuStyle.OR : (hover ? MenuStyle.TXT_SOFT : MenuStyle.DIM);
            MenuStyle.text(g, e.icon(), e.x() + 7, ty, sel ? MenuStyle.OR : MenuStyle.DIM2);
            String count = sideCount(e);
            int cw = font.width(count);
            String label = MenuStyle.ellipsize(e.label(), e.w() - 26 - cw - 4);
            MenuStyle.text(g, label, e.x() + 20, ty, tc);
            if (!count.isEmpty()) {
                MenuStyle.flat(g, count, e.x() + e.w() - 5 - cw, ty, sel ? MenuStyle.OR2 : MenuStyle.DIM2);
            }
        }
    }

    /** The small number beside a sidebar row: only Favourites has one (how many are starred). The categories' on/total
     *  counts were taken out on killer560's request (2026-10-12: "HUD 7/12" became "HUD"). */
    private static String sideCount(SideEntry e) {
        if (e.favourites()) {
            int n = MenuConfig.getInstance().getFavourites().size();
            return n == 0 ? "" : String.valueOf(n);
        }
        return "";
    }

    /** Every sidebar row's text exactly as drawn (label, then its number if it has one). Read by the testkit. */
    public List<String> sideTexts() {
        List<String> out = new ArrayList<>();
        for (SideEntry e : side) {
            String count = sideCount(e);
            out.add(count.isEmpty() ? e.label() : e.label() + " " + count);
        }
        return out;
    }

    private void drawPageWell(GuiGraphicsExtractor g) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, gridX, gridY, gridW, gridH, MenuStyle.WELL);
        MenuStyle.scrollbar(r, gridX + gridW - 7, gridY + 5, gridH - 10, scrollOffset, maxScroll, visibleContentHeight);
        r.submit();
    }

    private void drawGrid(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, gridX, gridY, gridW, gridH, MenuStyle.WELL);
        r.submit();
        int clipTop = gridY + 2;
        int clipBottom = gridY + gridH - 2;
        boolean paletteUp = paletteOpen && !searchQuery.isBlank();
        boolean overGrid = !paletteUp && inside(mouseX, mouseY, gridX, clipTop, gridW, clipBottom - clipTop)
                && !(openFeature != null && drawerOverlay);
        g.enableScissor(gridX + 1, clipTop, gridX + gridW - 1, clipBottom);
        try {
            for (Item it : items) {
                int y = it.y - gridScroll;
                if (y + it.h + 8 < clipTop || y - 8 > clipBottom) {
                    continue;
                }
                switch (it.kind) {
                    case HEADER -> {
                        GuiRects hr = GuiRects.begin(g);
                        MenuStyle.sectionBand(hr, it.x, y, it.w, it.h, it.red);
                        hr.submit();
                        MenuStyle.text(g, it.text.toUpperCase(Locale.ROOT), it.x + 6, y + 4,
                                it.red ? MenuStyle.RED : MenuStyle.OR2);
                    }
                    case BLOCK -> it.block.render(g, it.x, y, it.w, overGrid ? mouseX : -1, overGrid ? mouseY : -1);
                    case CARD -> drawCard(g, it, y, overGrid && inside(mouseX, mouseY, it.x, y, it.w, it.h),
                            overGrid ? mouseX : -1, overGrid ? mouseY : -1);
                }
            }
        } finally {
            g.disableScissor();
        }
        if (!drawerOverlay || openFeature == null) {
            GuiRects sb = GuiRects.begin(g);
            MenuStyle.scrollbar(sb, gridX + gridW - 7, gridY + 5, gridH - 10, gridScroll, gridMax, gridH - 2 * GRID_PAD);
            sb.submit();
        }
    }

    private void drawCard(GuiGraphicsExtractor g, Item it, int y, boolean hover, int mouseX, int mouseY) {
        BaseTab tab = it.tab;
        Boolean state = FeatureCards.state(tab);
        boolean selected = openFeature == tab;
        boolean fav = MenuConfig.getInstance().isFavourite(id(tab));
        int x = it.x;
        int w = it.w;
        int h = it.h;
        GuiRects r = GuiRects.begin(g);
        MenuStyle.dropShadow(r, x, y, w, h, 3);
        if (selected) {
            MenuStyle.glow(r, x, y, w, h, MenuStyle.OR, 0x50);
        }
        MenuStyle.bevel(r, x, y, w, h, hover ? MenuStyle.CARD_HOVER_TOP : MenuStyle.CARD_TOP,
                hover ? MenuStyle.CARD_HOVER_BOTTOM : MenuStyle.CARD_BOTTOM);
        if (selected) {
            r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.OR);
        } else if (hover) {
            r.outline(x - 1, y - 1, w + 2, h + 2, MenuStyle.ACCENT_EDGE);
        }
        int[] sw = cardSwitch(it);
        if (state != null) {
            boolean swHover = mouseX >= 0 && inside(mouseX, mouseY, sw[0] - 3, sw[1] - 3 - (it.y - y), sw[2] + 6, sw[3] + 6);
            MenuStyle.toggle(r, sw[0], sw[1] - (it.y - y), state, swHover);
        }
        r.submit();
        int nameW = w - 14 - (state != null ? MenuStyle.SWITCH_W + 4 : 0);
        List<String> name = MenuStyle.wrap(tab.name, nameW, 2);
        int ty = y + 6;
        int nameColour = tab.isCheatOnly() ? MenuStyle.RED_TITLE : (hover ? MenuStyle.TXT_HOVER : MenuStyle.TXT);
        for (String line : name) {
            MenuStyle.text(g, line, x + 7, ty, nameColour);
            ty += 10;
        }
        ty += 2;
        int descLines = DescriptionConfig.getInstance().isShowMenu() ? Math.max(0, (y + h - 14 - ty) / 10) : 0;
        for (String line : MenuStyle.wrap(FeatureCards.description(tab), w - 14, descLines)) {
            MenuStyle.flat(g, line, x + 7, ty, MenuStyle.DIM);
            ty += 10;
        }
        int[] st = cardStar(it);
        int sy = st[1] - (it.y - y);
        boolean starHover = mouseX >= 0 && inside(mouseX, mouseY, st[0] - 2, sy - 2, st[2] + 4, st[3] + 4);
        if (fav || hover) {
            g.text(font, fav ? "★" : "☆", st[0] + 1, sy + 1, fav ? (starHover ? MenuStyle.OR_LIGHT : MenuStyle.OR)
                    : (starHover ? MenuStyle.OR : MenuStyle.DIM2), true);
        }
        g.text(font, "⚙", x + w - 13, y + h - 12, hover || selected ? MenuStyle.OR2 : MenuStyle.GEAR, false);
        if (Boolean.FALSE.equals(state) && !selected) {
            GuiRects o = GuiRects.begin(g);
            o.fill(x, y, x + w, y + h, hover ? MenuStyle.OFF_VEIL_HOVER : MenuStyle.OFF_VEIL);
            o.submit();
        }
    }

    private void drawDrawer(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        GuiRects r = GuiRects.begin(g);
        // -4px 0 12px #000a shadow on the grid side
        for (int i = 1; i <= 4; i++) {
            r.fill(drawerX - i, drawerY + 1, drawerX - i + 1, drawerY + drawerH - 1, MenuStyle.shadow(0x70 - i * 0x14));
        }
        MenuStyle.bevel(r, drawerX, drawerY, drawerW, drawerH, MenuStyle.DRAWER_TOP, MenuStyle.DRAWER_BOTTOM);
        // title bar
        r.gradient(drawerX + 1, drawerY + 1, drawerX + drawerW - 1, drawerY + 25, MenuStyle.TITLE_TOP, MenuStyle.TITLE_BOTTOM);
        r.fill(drawerX + 1, drawerY + 1, drawerX + drawerW - 1, drawerY + 2, MenuStyle.HILITE);
        MenuStyle.separator(r, drawerX + 1, drawerX + drawerW - 1, drawerY + 25);
        if (drawerMaster != null) {
            int[] s = drawerSwitchBounds();
            MenuStyle.toggle(r, s[0], s[1], drawerMaster.isOn(), inside(mouseX, mouseY, s[0] - 2, s[1] - 2, s[2] + 4, s[3] + 4));
        }
        boolean adv = advancedLabel(openFeature);
        boolean advOpen = advancedHeader != null && advancedHeader.parts().on();
        if (adv) {
            for (int i = 0; i < 2; i++) {
                int[] b = stripBounds(i);
                boolean active = (i == 1) == advOpen;
                if (active) {
                    r.fill(b[0] + 4, b[1] + b[3] - 2, b[0] + b[2] - 4, b[1] + b[3], MenuStyle.OR);
                    r.fill(b[0] + 3, b[1] + b[3] - 3, b[0] + b[2] - 3, b[1] + b[3] - 2, MenuStyle.alpha(MenuStyle.OR, 0x40));
                }
            }
            MenuStyle.separator(r, drawerX + 1, drawerX + drawerW - 1, drawerY + 26 + 16);
        }
        MenuStyle.scrollbar(r, drawerX + drawerW - 8, contentY, visibleContentHeight, scrollOffset, maxScroll,
                visibleContentHeight);
        r.submit();
        int[] c = drawerCloseBounds();
        boolean closeHover = inside(mouseX, mouseY, c[0] - 2, c[1] - 2, c[2] + 4, c[3] + 4);
        int[] st = drawerStarBounds();
        int titleMax = st[0] - drawerX - 14;
        String title = MenuStyle.ellipsize(openFeature.name, titleMax);
        int titleColour = openFeature.isCheatOnly() ? MenuStyle.RED_TITLE : MenuStyle.OR;
        MenuStyle.text(g, Component.literal(title).withStyle(net.minecraft.ChatFormatting.BOLD), drawerX + 9,
                drawerY + 9, titleColour);
        boolean fav = MenuConfig.getInstance().isFavourite(id(openFeature));
        boolean starHover = inside(mouseX, mouseY, st[0], st[1], st[2], st[3]);
        g.text(font, fav ? "★" : "☆", st[0] + 2, st[1] + 3, fav || starHover ? MenuStyle.OR : MenuStyle.DIM2, true);
        g.text(font, "✕", c[0] + 2, c[1] + 2, closeHover ? MenuStyle.OR : MenuStyle.DIM, true);
        if (adv) {
            String[] names = {"Basic", "Advanced"};
            for (int i = 0; i < 2; i++) {
                int[] b = stripBounds(i);
                boolean active = (i == 1) == advOpen;
                boolean hov = inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
                int col = active ? MenuStyle.OR : (hov ? MenuStyle.TXT_SOFT : MenuStyle.DIM);
                MenuStyle.text(g, names[i], b[0] + (b[2] - font.width(names[i])) / 2, b[1] + 3, col);
            }
        }
    }

    /** Section header bands behind the drawer's "§6§l" / "§c§l" headings, and the search flash. Drawn under nothing:
     *  the bands are translucent and sit below the heading text's own pixels only because the text is brighter. */
    private void drawContentDecor(GuiGraphicsExtractor g) {
        if (contentPane == null) {
            return;
        }
        if (flashWidget != null && System.currentTimeMillis() < flashUntil
                && contentPane.children().contains(flashWidget)) {
            GuiRects r = GuiRects.begin(g);
            int a = (int) (0x90 * Math.min(1.0, (flashUntil - System.currentTimeMillis()) / 600.0));
            g.enableScissor(contentPane.getX(), contentPane.getY(), contentPane.getX() + contentPane.getWidth(),
                    contentPane.getY() + contentPane.getHeight());
            MenuStyle.glow(r, flashWidget.getX(), flashWidget.getY(), flashWidget.getWidth(), flashWidget.getHeight(),
                    MenuStyle.OR, a);
            r.outline(flashWidget.getX() - 1, flashWidget.getY() - 1, flashWidget.getWidth() + 2,
                    flashWidget.getHeight() + 2, MenuStyle.alpha(MenuStyle.OR, a + 0x40));
            r.submit();
            g.disableScissor();
        }
    }

    /** Places the search palette (its rows are {@link #PAL_ROW} tall from {@code palRowsY}); drawn and hit-tested there. */
    public void layoutPalette() {
        palW = Math.min(panelW - 60, 440);
        palX = panelX + (panelW - palW) / 2;
        palY = headerY + headerH + 3;
        int maxRows = Math.max(3, (panelY + panelH - 20 - (palY + 20)) / PAL_ROW);
        palRows = Math.min(maxRows, Math.max(1, results.size()));
        palRowsY = palY + 18;
    }

    private int lastPalMouseX = Integer.MIN_VALUE, lastPalMouseY = Integer.MIN_VALUE;

    private void drawPalette(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        layoutPalette();
        // the mouse picks a row only when it moves, so it does not undo the arrow keys every frame
        boolean mouseMoved = mouseX != lastPalMouseX || mouseY != lastPalMouseY;
        lastPalMouseX = mouseX;
        lastPalMouseY = mouseY;
        int palH = palRowsY - palY + palRows * PAL_ROW + 4;
        GuiRects r = GuiRects.begin(g);
        r.fill(panelX, panelY, panelX + panelW, panelY + panelH, MenuStyle.shadow(0x60));
        for (int i = 8; i >= 1; i--) {
            r.fill(palX - i, palY - i + 6, palX + palW + i, palY + palH + i + 6, MenuStyle.shadow(0x14));
        }
        MenuStyle.glow(r, palX, palY, palW, palH, MenuStyle.OR, 0x40);
        r.outline(palX - 1, palY - 1, palW + 2, palH + 2, MenuStyle.OR);
        MenuStyle.bevel(r, palX, palY, palW, palH, MenuStyle.DRAWER_TOP, MenuStyle.DRAWER_BOTTOM);
        r.fill(palX + 1, palY + 1, palX + palW - 1, palY + 16, MenuStyle.WELL_DEEP);
        r.fill(palX + 1, palY + 16, palX + palW - 1, palY + 17, MenuStyle.BLACK);
        int shown = Math.min(palRows, results.size() - paletteScroll);
        for (int i = 0; i < shown; i++) {
            int idx = paletteScroll + i;
            int ry = palRowsY + i * PAL_ROW;
            boolean hover = inside(mouseX, mouseY, palX, ry, palW, PAL_ROW);
            if (hover && mouseMoved) {
                paletteSel = idx;
            }
            if (idx == paletteSel) {
                MenuStyle.hGradient(r, palX + 1, ry, palX + palW - 1, ry + PAL_ROW, MenuStyle.SEL_TOP, MenuStyle.SEL_BOTTOM, 8);
                r.fill(palX + 1, ry, palX + 3, ry + PAL_ROW, MenuStyle.OR);
            }
            Result res = results.get(idx);
            Boolean st = res.feature() ? FeatureCards.state(res.tab()) : null;
            if (st != null) {
                int sx = palX + palW - 8 - MenuStyle.SWITCH_W;
                MenuStyle.toggle(r, sx, ry + 3, st, hover && mouseX >= sx - 3);
            }
        }
        MenuStyle.scrollbar(r, palX + palW - 5, palRowsY, palRows * PAL_ROW, paletteScroll,
                Math.max(0, results.size() - palRows), palRows);
        r.submit();
        String head = results.isEmpty() ? "No features or settings match \"" + searchQuery + "\""
                : results.size() + " result" + (results.size() == 1 ? "" : "s") + " for \"" + searchQuery + "\"";
        MenuStyle.text(g, MenuStyle.ellipsize(head, palW - 120), palX + 8, palY + 5, MenuStyle.DIM);
        MenuStyle.flat(g, "↑↓  Enter opens", palX + palW - 8 - font.width("↑↓  Enter opens"), palY + 5, MenuStyle.DIM2);
        for (int i = 0; i < shown; i++) {
            int idx = paletteScroll + i;
            int ry = palRowsY + i * PAL_ROW;
            Result res = results.get(idx);
            boolean sel = idx == paletteSel;
            int right = palX + palW - 8 - (res.feature() && FeatureCards.state(res.tab()) != null ? MenuStyle.SWITCH_W + 8 : 0);
            String where = res.where();
            int whereW = Math.min(font.width(where), palW / 3);
            where = MenuStyle.ellipsize(where, whereW);
            MenuStyle.flat(g, where, right - font.width(where), ry + 4, MenuStyle.DIM2);
            MenuStyle.text(g, res.feature() ? "★" : "⚙", palX + 8, ry + 4, res.feature() ? MenuStyle.OR2 : MenuStyle.DIM2);
            int nameColour = res.tab().isCheatOnly() && res.feature() ? MenuStyle.RED_TITLE
                    : (sel ? MenuStyle.OR : MenuStyle.TXT);
            String label = MenuStyle.ellipsize(res.label(), right - font.width(where) - 10 - (palX + 22));
            MenuStyle.text(g, label, palX + 22, ry + 4, nameColour);
        }
    }

    private AbstractWidget tooltipWidget = null;
    private Object tooltipKey = null;
    private long tooltipHoverSinceMs = 0L;

    /** Hover description box for the setting under the mouse (see {@link SettingTooltips}), or a card's full
     *  description, after a short delay. */
    private void drawSettingTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        AbstractWidget hovered = contentPane == null ? null : contentPane.hoveredChild(mouseX, mouseY);
        String text = null;
        Object key = hovered;
        if (hovered != null) {
            try {
                text = SettingTooltips.describe(tooltipCategory(hovered), hovered, hovered.getMessage().getString());
            } catch (RuntimeException e) {
                text = null;
            }
        } else if (inside(mouseX, mouseY, iconBounds(3)[0], iconBounds(3)[1], iconBounds(3)[2], iconBounds(3)[3])) {
            key = "youtube";
            text = "Killer560's YouTube channel.";
        } else if (!pageMode() && inside(mouseX, mouseY, gridX, gridY, gridW, gridH)
                && !(openFeature != null && drawerOverlay)) {
            String homeLabel = homeButtonAt(mouseX, mouseY);
            if (homeLabel != null) {
                key = homeLabel; // a literal label, so the same object every frame (the hover delay compares by identity)
                text = SettingTooltips.describeScoped(null, "home button", homeLabel);
            }
            for (Item it : homeLabel != null ? List.<Item>of() : items) {
                if ((it.kind == ItemKind.CARD || (it.kind == ItemKind.HEADER && it.tab != null))
                        && inside(mouseX, mouseY + gridScroll, it.x, it.y, it.w, it.h)) {
                    key = it.tab;
                    text = cardTooltip(it.tab);
                    if (text == null && it.kind == ItemKind.CARD) {
                        text = FeatureCards.description(it.tab);
                        if (text != null && text.length() < 60) {
                            text = null; // short enough to read on the card itself
                        }
                    }
                    break;
                }
            }
        }
        long now = System.currentTimeMillis();
        if (key != tooltipKey) {
            tooltipKey = key;
            tooltipWidget = hovered;
            tooltipHoverSinceMs = now;
        }
        if (key == null || now - tooltipHoverSinceMs < 350L || text == null || text.isBlank()) {
            return;
        }
        int maxW = 220;
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(text), maxW);
        int w = 0;
        for (var line : lines) {
            w = Math.max(w, this.font.width(line));
        }
        int h = lines.size() * 10 - 2;
        int x = mouseX + 12;
        int y = mouseY + 12;
        if (x + w + 8 > this.width) {
            x = Math.max(4, mouseX - w - 16);
        }
        if (y + h + 8 > this.height) {
            y = Math.max(4, mouseY - h - 16);
        }
        GuiRects r = GuiRects.begin(graphics);
        MenuStyle.dropShadow(r, x - 4, y - 4, w + 8, h + 8, 3);
        r.outline(x - 5, y - 5, w + 10, h + 10, MenuStyle.OR2);
        MenuStyle.bevel(r, x - 4, y - 4, w + 8, h + 8, MenuStyle.TOOLTIP_TOP, MenuStyle.TOOLTIP_BOTTOM);
        r.submit();
        int ly = y;
        for (var line : lines) {
            graphics.text(this.font, line, x, ly, MenuStyle.TXT, MenuStyle.TEXT_SHADOW);
            ly += 10;
        }
    }

    @Override
    public void removed() {
        ThemedSliderButton.endLiveDrag();
        super.removed();
    }

    @Override
    public void onClose() {
        if (openFeature != null) {
            FeatureCards.refresh(openFeature);
        }
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Rebuilds the open content - for state that changes outside the menu while it is open (a profile zip dropped
     *  into the import folder, a profile applied). Client thread only. */
    public void refresh() {
        for (BaseTab leaf : allLeaves()) {
            FeatureCards.refresh(leaf);
        }
        rebuild();
    }

    /**
     * What hovering a card, or a group's title in the grid, shows: the description the tab's NAME had as an accordion
     * header row before the Option A menu (looked up under its old category), or null when it had none - the card's
     * own line then shows instead, in full, when the card cuts it short. Read by the testkit's tooltip cases.
     */
    public static String cardTooltip(BaseTab tab) {
        String header = SettingTooltips.describeScoped(null, MenuCategories.legacyCategory(tab), tab.name);
        return header == null || header.isBlank() ? null : header;
    }

    /**
     * The category name a row's tooltip is looked up under: the OLD category of the tab that built it
     * ({@link MenuCategories#legacyCategory}), so the category-scoped keys ("dungeon/mode") still answer after the
     * regroup. Read by the testkit's tooltip cases too, so they judge exactly what the menu shows.
     */
    public static String tooltipCategory(AbstractWidget w) {
        String scope = SettingTooltips.scopeOf(w);
        if (scope != null) {
            for (BaseTab leaf : allLeaves()) {
                if (leaf.name.equals(scope)) {
                    return MenuCategories.legacyCategory(leaf);
                }
            }
        }
        if (openFeature != null) {
            return MenuCategories.legacyCategory(openFeature);
        }
        return tabs == null || tabs.isEmpty() ? null : tabs.get(Math.max(0, Math.min(selectedTab, tabs.size() - 1))).name;
    }

    // ---- Home page blocks ------------------------------------------------------------------------------------------

    /** A plain line of text across the grid (Favourites with nothing starred). */
    private final class HintBlock implements Block {
        private final String text;

        HintBlock(String text) {
            this.text = text;
        }

        @Override
        public int height(int width) {
            return 12 + 10 * MenuStyle.wrap(text, width - 20, 4).size();
        }

        @Override
        public void render(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            int ty = y + 6;
            for (String line : MenuStyle.wrap(text, w - 20, 4)) {
                MenuStyle.text(g, line, x + 10, ty, MenuStyle.DIM);
                ty += 10;
            }
        }

        @Override
        public boolean click(int x, int y, int w, double mx, double my) {
            return false;
        }
    }

    /** A hand-drawn raised button on the Home page. */
    private record HomeButton(String label, Runnable action) {
    }

    private static final String HOME_PROFILES = "Profiles";
    private static final String HOME_REBOOT = "Reboot";
    private static final String HOME_SWEAT = "Sweat Mode";

    /** Home's quick button label under the mouse, or null. Its tooltip is SettingTooltipsData's "home button/<label>". */
    private String homeButtonAt(int mouseX, int mouseY) {
        for (Item it : items) {
            if (it.kind == ItemKind.BLOCK && it.block instanceof HeroBlock hero) {
                List<HomeButton> buttons = hero.buttons();
                for (int i = 0; i < buttons.size(); i++) {
                    int[] b = hero.buttonBox(i, it.x, it.y - gridScroll, it.w);
                    if (inside(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
                        return buttons.get(i).label();
                    }
                }
            }
        }
        return null;
    }

    /** Screen box {x, y, w, h} of Home's quick button {@code label} on the current page, or null. For the testkit. */
    public int[] homeButtonBounds(String label) {
        for (Item it : items) {
            if (it.kind == ItemKind.BLOCK && it.block instanceof HeroBlock hero) {
                List<HomeButton> buttons = hero.buttons();
                for (int i = 0; i < buttons.size(); i++) {
                    if (buttons.get(i).label().equals(label)) {
                        return hero.buttonBox(i, it.x, it.y - gridScroll, it.w);
                    }
                }
            }
        }
        return null;
    }

    /** Home's top box: the build, the update channel and its last result, and the quick buttons. */
    private final class HeroBlock implements Block {

        private List<HomeButton> buttons() {
            List<HomeButton> b = new ArrayList<>();
            // killer560 (2026-10-14): his channel at the top, and Sweat Mode right next to Check Updates - nine, a full 3x3.
            b.add(new HomeButton("YouTube", () -> com.killer560.hub.util.ExternalOpen.uri(YOUTUBE_URL)));
            b.add(new HomeButton("Edit HUD", () -> {
                Minecraft client = Minecraft.getInstance();
                McCompat.setScreen(client, new com.killer560.hub.hud.HudEditorScreen(McCompat.screen(client)));
            }));
            b.add(new HomeButton("Edit Bars", () -> {
                Minecraft client = Minecraft.getInstance();
                McCompat.setScreen(client, com.killer560.hub.hud.HudEditorScreen.forBars(McCompat.screen(client)));
            }));
            b.add(new HomeButton("Check Updates", () -> com.killer560.hub.gui.tab.AutoUpdateTab.checkNow(() -> { })));
            b.add(new HomeButton(HOME_SWEAT, com.killer560.hub.util.SweatMode::enter));
            b.add(new HomeButton("Discord", () -> com.killer560.hub.util.ExternalOpen.uri(DISCORD_URL)));
            b.add(new HomeButton("GitHub", () -> com.killer560.hub.util.ExternalOpen.uri(GITHUB_URL)));
            b.add(new HomeButton("Features Doc", () -> com.killer560.hub.util.ExternalOpen.uri(FEATURES_URL)));
            b.add(new HomeButton(HOME_REBOOT, com.killer560.hub.reboot.RebootFeature::pressButton));
            return b;
        }

        private int cols(int w) {
            return w >= 420 ? 3 : 2;
        }

        @Override
        public int height(int width) {
            int rows = (buttons().size() + cols(width) - 1) / cols(width);
            return 50 + rows * 20 + 6;
        }

        private int[] buttonBox(int i, int x, int y, int w) {
            int cols = cols(w);
            int bw = (w - 20 - (cols - 1) * 6) / cols;
            return new int[]{x + 10 + (i % cols) * (bw + 6), y + 50 + (i / cols) * 20, bw, 16};
        }

        @Override
        public void render(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            int h = height(w);
            GuiRects r = GuiRects.begin(g);
            MenuStyle.dropShadow(r, x, y, w, h, 3);
            MenuStyle.bevel(r, x, y, w, h, MenuStyle.BOX_TOP, MenuStyle.BOX_BOTTOM);
            r.gradient(x + 1, y + 1, x + w - 1, y + 15, MenuStyle.TITLE_TOP, MenuStyle.TITLE_BOTTOM);
            r.fill(x + 1, y + 15, x + w - 1, y + 16, MenuStyle.BLACK);
            List<HomeButton> buttons = buttons();
            for (int i = 0; i < buttons.size(); i++) {
                int[] b = buttonBox(i, x, y, w);
                MenuStyle.raisedButton(r, b[0], b[1], b[2], b[3], mouseX >= 0 && inside(mouseX, mouseY, b[0], b[1], b[2], b[3]), false);
            }
            r.submit();
            MenuStyle.text(g, "WELCOME BACK", x + 8, y + 4, MenuStyle.OR2);
            String mc = com.killer560.hub.autoupdate.BuildInfo.minecraft();
            String line1 = "Version " + modVersion() + " · " + com.killer560.hub.autoupdate.BuildInfo.variant()
                    + (mc == null ? "" : " · Minecraft " + mc) + " · build " + com.killer560.hub.autoupdate.BuildInfo.shortCommit();
            MenuStyle.text(g, MenuStyle.ellipsize(line1, w - 20), x + 10, y + 21, MenuStyle.TXT);
            String line2 = "Updates: " + com.killer560.hub.gui.tab.AutoUpdateTab.statusLine();
            MenuStyle.text(g, MenuStyle.ellipsize(line2, w - 20), x + 10, y + 33, MenuStyle.DIM);
            for (int i = 0; i < buttons.size(); i++) {
                int[] b = buttonBox(i, x, y, w);
                boolean hov = mouseX >= 0 && inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
                String l = MenuStyle.ellipsize(buttons.get(i).label(), b[2] - 6);
                MenuStyle.text(g, l, b[0] + (b[2] - font.width(l)) / 2, b[1] + 4, hov ? MenuStyle.OR_HOVER : MenuStyle.OR);
            }
        }

        @Override
        public boolean click(int x, int y, int w, double mx, double my) {
            List<HomeButton> buttons = buttons();
            for (int i = 0; i < buttons.size(); i++) {
                int[] b = buttonBox(i, x, y, w);
                if (inside(mx, my, b[0], b[1], b[2], b[3])) {
                    clickSound();
                    buttons.get(i).action().run();
                    return true;
                }
            }
            return false;
        }
    }

    /** Home's Menu Theme row: the four presets as chips (the active one lit) and Customise, which opens the Menu Theme
     *  drawer with Custom's colours. */
    private final class ThemeBlock implements Block {

        private static final String CUSTOMISE = "Customize...";

        @Override
        public int height(int width) {
            return 40;
        }

        private int[] chip(int i, int x, int y, int w) {
            int n = MenuTheme.values().length + 1;
            int cw = Math.min(90, (w - 20 - (n - 1) * 6) / n);
            return new int[]{x + 10 + i * (cw + 6), y + 20, cw, 14};
        }

        @Override
        public void render(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
            GuiRects r = GuiRects.begin(g);
            MenuStyle.dropShadow(r, x, y, w, 40, 3);
            MenuStyle.bevel(r, x, y, w, 40, MenuStyle.BOX_TOP, MenuStyle.BOX_BOTTOM);
            r.gradient(x + 1, y + 1, x + w - 1, y + 15, MenuStyle.TITLE_TOP, MenuStyle.TITLE_BOTTOM);
            r.fill(x + 1, y + 15, x + w - 1, y + 16, MenuStyle.BLACK);
            MenuTheme active = MenuConfig.getInstance().getTheme();
            MenuTheme[] all = MenuTheme.values();
            for (int i = 0; i <= all.length; i++) {
                int[] b = chip(i, x, y, w);
                boolean hov = mouseX >= 0 && inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
                if (i < all.length) {
                    MenuStyle.pill(r, b[0], b[1], b[2], b[3], all[i] == active, hov);
                } else {
                    MenuStyle.raisedButton(r, b[0], b[1], b[2], b[3], hov, false);
                }
            }
            r.submit();
            MenuStyle.text(g, "MENU THEME", x + 8, y + 4, MenuStyle.OR2);
            for (int i = 0; i <= all.length; i++) {
                int[] b = chip(i, x, y, w);
                boolean lit = i < all.length && all[i] == active;
                String l = MenuStyle.ellipsize(i < all.length ? all[i].label : CUSTOMISE, b[2] - 6);
                int colour = lit ? (MenuStyle.luminance(MenuStyle.OR) > 0.55f ? 0xFF160C02 : 0xFFFFFFFF) : MenuStyle.OR;
                g.text(font, l, b[0] + (b[2] - font.width(l)) / 2, b[1] + 3, colour, MenuStyle.TEXT_SHADOW && !lit);
            }
        }

        @Override
        public boolean click(int x, int y, int w, double mx, double my) {
            MenuTheme[] all = MenuTheme.values();
            for (int i = 0; i <= all.length; i++) {
                int[] b = chip(i, x, y, w);
                if (!inside(mx, my, b[0], b[1], b[2], b[3])) {
                    continue;
                }
                clickSound();
                if (i < all.length) {
                    MenuConfig cfg = MenuConfig.getInstance();
                    cfg.setTheme(all[i]);
                    cfg.save();
                    rebuild();
                } else {
                    for (BaseTab leaf : allLeaves()) {
                        if (leaf instanceof com.killer560.hub.gui.tab.MenuThemeTab) {
                            openFeature = leaf;
                            scrollOffset = 0;
                            rebuild();
                        }
                    }
                }
                return true;
            }
            return false;
        }
    }

    /** Wraps every widget the open drawer (or page) builds, so the content scrolls and is scissor-clipped as one pane.
     *  <p>Implements {@link ContainerEventHandler} so clicks, drags and typing route down into whichever real child
     *  (button, slider, edit box) they land on - the same routing {@code Screen} uses for its own children. A child's
     *  hovered flag is only set while {@code containsPointInScissor} is true (26.1.2 {@code AbstractWidget}), so a row's
     *  clipped half can neither hover nor take a click. */
    private static final class ScrollingContentPane extends AbstractWidget implements ContainerEventHandler {

        private final List<AbstractWidget> children;
        private GuiEventListener focused;
        private boolean dragging;

        ScrollingContentPane(int x, int y, int width, int height, List<AbstractWidget> children) {
            super(x, y, width, height, Component.empty());
            this.children = children;
        }

        @Override
        public List<AbstractWidget> children() {
            return children;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            int top = getY();
            int bottom = getY() + getHeight();
            graphics.enableScissor(getX(), top, getX() + getWidth(), bottom);
            try {
                // Section headings (SectionHeaders' "§6§l" / "§c§l" StringWidgets) get the menu's section band.
                GuiRects bands = GuiRects.begin(graphics);
                boolean any = false;
                for (AbstractWidget child : children) {
                    if (child instanceof StringWidget s && child.getY() + child.getHeight() > top && child.getY() < bottom) {
                        String raw = s.getMessage().getString();
                        boolean red = raw.startsWith(SectionHeaders.CHEAT_PREFIX);
                        if (red || raw.startsWith(SectionHeaders.LEGIT_PREFIX)) {
                            MenuStyle.sectionBand(bands, getX() + 2, child.getY() - 3, getWidth() - 10,
                                    child.getHeight() + 3, red);
                            any = true;
                        }
                    }
                }
                if (any) {
                    bands.submit();
                }
                for (AbstractWidget child : children) {
                    if (child.getY() + child.getHeight() <= top || child.getY() >= bottom) {
                        continue;
                    }
                    if (child instanceof StringWidget s && drawString(graphics, s)) {
                        continue;
                    }
                    child.extractRenderState(graphics, mouseX, mouseY, partialTick);
                }
            } finally {
                // Must run even if a child's render throws, or the rest of the frame inherits this clip.
                graphics.disableScissor();
            }
        }

        /**
         * A tab's text line in the theme: a section heading ("§6§l" / "§c§l") in the accent or the cheat red, and on a
         * light theme every other line with its washed-out colour codes darkened. False leaves it to the widget.
         */
        private static boolean drawString(GuiGraphicsExtractor g, StringWidget s) {
            String raw = s.getMessage().getString();
            boolean red = raw.startsWith(SectionHeaders.CHEAT_PREFIX);
            boolean heading = red || raw.startsWith(SectionHeaders.LEGIT_PREFIX);
            if (!heading && !MenuStyle.LIGHT) {
                return false;
            }
            var font = Minecraft.getInstance().font;
            int y = s.getY() + (s.getHeight() - 8) / 2;
            if (heading) {
                String plain = net.minecraft.ChatFormatting.stripFormatting(raw);
                g.text(font, Component.literal(MenuStyle.ellipsize(plain == null ? raw : plain, s.getWidth()))
                        .withStyle(net.minecraft.ChatFormatting.BOLD), s.getX(), y,
                        red ? MenuStyle.RED_TITLE : MenuStyle.OR, MenuStyle.TEXT_SHADOW);
                return true;
            }
            g.text(font, MenuStyle.ellipsize(MenuStyle.lightReadable(raw), s.getWidth()), s.getX(), y, MenuStyle.TXT, false);
            return true;
        }

        AbstractWidget hoveredChild(int mouseX, int mouseY) {
            if (!isMouseOver(mouseX, mouseY)) {
                return null;
            }
            // A painted card (PaintedPanel) answers only where no real widget sits on it.
            AbstractWidget panel = null;
            for (AbstractWidget child : children) {
                if (child.visible && mouseX >= child.getX() && mouseX < child.getX() + child.getWidth()
                        && mouseY >= child.getY() && mouseY < child.getY() + child.getHeight()) {
                    if (!(child instanceof PaintedPanel)) {
                        return child;
                    }
                    if (panel == null) {
                        panel = child;
                    }
                }
            }
            return panel;
        }

        // AbstractWidget implements every method below as a leaf; each override opts back into the container routing.
        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return ContainerEventHandler.super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            return ContainerEventHandler.super.mouseReleased(event);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            return ContainerEventHandler.super.mouseDragged(event, dragX, dragY);
        }

        @Override
        public ComponentPath nextFocusPath(FocusNavigationEvent event) {
            return ContainerEventHandler.super.nextFocusPath(event);
        }

        @Override
        public void setFocused(boolean value) {
            ContainerEventHandler.super.setFocused(value);
        }

        @Override
        public boolean isFocused() {
            return ContainerEventHandler.super.isFocused();
        }

        @Override
        public GuiEventListener getFocused() {
            return focused;
        }

        @Override
        public void setFocused(GuiEventListener guiEventListener) {
            // Tell the old and new widgets, like vanilla's AbstractContainerEventHandler does (fixed 2026-09-21: a
            // clicked EditBox never learned it was focused, so no text box in the menu took typing).
            if (this.focused == guiEventListener) {
                return;
            }
            if (this.focused != null) {
                this.focused.setFocused(false);
            }
            if (guiEventListener != null) {
                guiEventListener.setFocused(true);
            }
            this.focused = guiEventListener;
        }

        @Override
        public boolean isDragging() {
            return dragging;
        }

        @Override
        public void setDragging(boolean value) {
            this.dragging = value;
        }

        @Override
        public Collection<? extends NarratableEntry> getNarratables() {
            return children;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
