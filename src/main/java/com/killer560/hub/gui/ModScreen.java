package com.killer560.hub.gui;

import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.ChatTab;
import com.killer560.hub.gui.tab.DisplayTab;
import com.killer560.hub.gui.tab.DungeonTab;
import com.killer560.hub.gui.tab.FolderTab;
import com.killer560.hub.gui.tab.HelpersTab;
import com.killer560.hub.gui.tab.HomeTab;
import com.killer560.hub.gui.tab.HudElementsTab;
import com.killer560.hub.gui.tab.KeyCaptureTab;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class ModScreen extends Screen {

    /** Shown top-right of the menu header (2026-09-07), per killer560's plan for versioning this mod once
     *  it starts getting pushed to GitHub: three numbers, incremented independently depending on how
     *  significant a change is. RIGHTMOST number ("beta" counter) bumps by 1 on every single release
     *  pushed to GitHub, no matter how small. MIDDLE number bumps whenever a release is significant
     *  enough that killer560 thinks everyone should update to it. LEFTMOST number bumps only for something
     *  absolutely massive. Bump this string by hand at release time - there's no build-time versioning
     *  wired up (yet). */
    private static final String MOD_VERSION = "Pre-Release Beta 1.0.1";

    // Built once and reused across every open/close (rather than rebuilt per screen instance) so
    // both the selected top-level tab AND each FolderTab's own remembered sub-tab survive closing
    // and reopening the mod menu, not just within a single open.
    private static List<BaseTab> tabs;
    private static int selectedTab = 0;

    private final Screen parent;

    private final int sidebarW = 110;
    private int panelX, panelY, panelW, panelH;
    private int contentX, contentY, contentW;

    private EditBox searchField;
    private String searchQuery = "";

    // Real bug found and fixed (2026-09-07), per killer560's screenshot showing accordion content
    // spilling straight past the bottom of the panel into the game world behind it: the content area
    // never had any scrolling or clipping at all - every widget just rendered wherever its fixed Y
    // coordinate said, with nothing stopping that from exceeding the panel once enough accordion
    // sections were expanded at once. scrollOffset shifts content up by this many pixels; a widget is
    // only actually added to the screen if it FULLY fits within the visible window after that shift
    // (simpler and just as effective as a real scissor clip here, since nothing partially spills past
    // the boundary either way - it just doesn't render until scrolled fully into view).
    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int visibleContentHeight = 0;

    public ModScreen(Screen parent) {
        this(parent, -1);
    }

    /** @param initialTab tab index to jump to, or -1 to keep whichever tab was last selected (the
     *  normal "reopen where I left off" case - only commands like /language pass a real index, to
     *  force-navigate to a specific tab). */
    public ModScreen(Screen parent, int initialTab) {
        super(Component.literal("Killer560's Mod"));
        this.parent = parent;
        if (initialTab >= 0) {
            selectedTab = initialTab;
        }
    }

    @Override
    protected void init() {
        if (tabs == null) {
            tabs = new ArrayList<>();
            tabs.add(new HomeTab());
            tabs.add(new DisplayTab());
            tabs.add(new ChatTab());
            tabs.add(new HudElementsTab());
            tabs.add(new HelpersTab());
            tabs.add(new DungeonTab());
        }
        if (selectedTab >= tabs.size() || selectedTab < 0) {
            selectedTab = 0;
        }

        // Percentage-of-screen sizing (with min/max floors/caps), not a flat pixel box: the old
        // fixed 560x400 cap left huge, comfortable side margins but almost none top/bottom on a
        // real reported case (this.width/height ~855x480 GUI-scaled units - width had 560 to work
        // with vs. only 400 of height, so the panel ended up hugging the top/bottom edges while
        // wasting space on the sides). Sizing both dimensions as a fraction of what's actually
        // available keeps margins visually comfortable on both axes across different resolutions/
        // GUI scales, while the min/max floors and caps keep it from getting absurdly cramped or
        // stretched at very small or very large window sizes.
        int minPanelW = Math.min(this.width - 20, 480);
        int minPanelH = Math.min(this.height - 20, 260);
        panelW = Math.max(minPanelW, Math.min((int) (this.width * 0.82), 620));
        panelH = Math.max(minPanelH, Math.min((int) (this.height * 0.78), 420));
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        contentX = panelX + sidebarW + 10;
        contentY = panelY + 40;
        contentW = panelW - sidebarW - 20;

        // Per killer560's "move that search bar up just a hair so its not on that orange line but
        // instead just above it" (2026-09-07) - the header's amber underline sits at panelY+29..30,
        // and the field's old y (panelY+10) with height 20 made its bottom edge land right on top of
        // it. panelY+5 leaves a small gap above the line instead.
        int searchWidth = 130;
        searchField = new EditBox(this.font, panelX + panelW - searchWidth - 6, panelY + 5,
                searchWidth, 20, Component.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search..."));
        searchField.setValue(searchQuery);
        searchField.setResponder(text -> {
            searchQuery = text;
            List<BaseTab> visible = visibleTabs();
            if (!visible.isEmpty() && !visible.contains(tabs.get(selectedTab))) {
                selectedTab = tabs.indexOf(visible.get(0));
                scrollOffset = 0;
            }
            // Per killer560's request (2026-09-08): search only narrows which folders/sub-tabs show up -
            // it no longer force-expands a collapsed accordion section just because the match came from
            // inside it. The folder still surfaces in the sidebar (matchesSearch still finds it), but
            // expanding it to see what matched is a manual click, same as always.
            rebuild();
        });

        rebuild();
    }

    private List<BaseTab> visibleTabs() {
        if (searchQuery.isBlank()) {
            return tabs;
        }
        List<BaseTab> result = new ArrayList<>();
        for (BaseTab tab : tabs) {
            if (tab.matchesSearch(searchQuery)) {
                result.add(tab);
            }
        }
        return result;
    }

    private void rebuild() {
        this.clearWidgets();
        this.addRenderableWidget(searchField);

        List<BaseTab> visible = visibleTabs();
        int tabY = panelY + 40;
        for (BaseTab tab : visible) {
            int index = tabs.indexOf(tab);
            boolean selected = index == selectedTab;
            this.addRenderableWidget(new MenuRowWidget(panelX + 8, tabY, sidebarW - 16, 20, tab.name, selected, false, () -> {
                selectedTab = index;
                scrollOffset = 0;
                rebuild();
            }));
            tabY += 24;
        }

        if (visible.isEmpty()) {
            this.addRenderableWidget(new StringWidget(contentX, contentY, contentW, 12,
                    Component.literal("§7No tabs match \"" + searchQuery + "\"."), this.font));
            maxScroll = 0;
            return;
        }

        List<AbstractWidget> contentWidgets = tabs.get(selectedTab).buildWidgets(contentX, contentY, contentW, this::rebuild);

        int naturalBottom = contentY;
        for (AbstractWidget w : contentWidgets) {
            naturalBottom = Math.max(naturalBottom, w.getY() + w.getHeight());
        }
        int visibleBottom = panelY + panelH - 10;
        visibleContentHeight = visibleBottom - contentY;
        maxScroll = Math.max(0, (naturalBottom - contentY) - visibleContentHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        for (AbstractWidget w : contentWidgets) {
            int newY = w.getY() - scrollOffset;
            w.setY(newY);
            // Only add widgets that fully fit the visible window - anything else simply doesn't
            // render (or receive clicks) until scrolled the rest of the way into view, which is what
            // actually stops content from spilling past the panel in the first place.
            if (newY >= contentY && newY + w.getHeight() <= visibleBottom) {
                this.addRenderableWidget(w);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseX >= contentX && mouseX <= panelX + panelW
                && mouseY >= contentY && mouseY <= panelY + panelH) {
            scrollOffset -= (int) Math.round(scrollY * 16);
            rebuild();
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
        return super.keyPressed(keyEvent);
    }

    /** Checks the top-level selected tab itself (e.g. {@code HomeTab}'s HUD-edit keybind), and - since
     *  a top-level tab can now be a {@link FolderTab} whose actual {@link KeyCaptureTab} lives inside
     *  one of its expanded accordion sections (e.g. {@code ExperimentsTab} inside {@code HelpersTab}) -
     *  falls back to asking the folder directly, rather than only ever checking the top level. */
    private KeyCaptureTab findListeningKeyCaptureTab() {
        BaseTab active = tabs.get(selectedTab);
        if (active instanceof KeyCaptureTab captureTab && captureTab.isListeningForKey()) {
            return captureTab;
        }
        if (active instanceof FolderTab folder) {
            KeyCaptureTab nested = folder.findListeningKeyCaptureTab();
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Black + amber theme (2026-09-07), per killer560's "dark themed mod style" with "black with
        // orange accents" - replaces the old dark-navy/blue-divider/cyan-title look.
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xFF0D0D0D);
        graphics.outline(panelX, panelY, panelW, panelH, 0xFF553311);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + 30, 0xFF000000);
        // Per killer560's "put some type of border around the top black part to more show the black
        // parts outline" (2026-09-07) - the header used to have no outline of its own, only the
        // bright amber underline below it, so its own boundary barely read against the panel's
        // already-near-black background. This dim outline (same shade as the panel's own border,
        // for consistency) frames it properly; the bright underline still draws on top of its bottom
        // edge right after, so that accent stays prominent.
        graphics.outline(panelX, panelY, panelW, 30, 0xFF553311);
        graphics.fill(panelX, panelY + 29, panelX + panelW, panelY + 30, 0xFFCC6600);
        graphics.fill(panelX + sidebarW, panelY + 30, panelX + sidebarW + 1, panelY + panelH, 0xFF553311);
        graphics.text(this.font, "Killer560's Mod", panelX + 10, panelY + 10, 0xFFCC6600, false);
        int versionX = searchField.getX() - 8 - this.font.width(MOD_VERSION);
        graphics.text(this.font, MOD_VERSION, versionX, panelY + 11, 0xFF888888, false);

        if (maxScroll > 0) {
            int trackX = panelX + panelW - 6;
            int trackTop = contentY;
            int trackBottom = trackTop + visibleContentHeight;
            graphics.fill(trackX, trackTop, trackX + 3, trackBottom, 0xFF1A1A1A);
            int contentHeight = visibleContentHeight + maxScroll;
            int thumbHeight = Math.max(10, visibleContentHeight * visibleContentHeight / contentHeight);
            int thumbY = trackTop + (visibleContentHeight - thumbHeight) * scrollOffset / maxScroll;
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFCC6600);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
