package com.killer560.hub.gui;

import com.killer560.hub.gui.tab.BaseTab;
import com.killer560.hub.gui.tab.ChatTab;
import com.killer560.hub.gui.tab.DisplayTab;
import com.killer560.hub.gui.tab.MiningWipTab;
import com.killer560.hub.gui.tab.DungeonTab;
import com.killer560.hub.gui.tab.FolderTab;
import com.killer560.hub.gui.tab.GeneralTab;
import com.killer560.hub.gui.tab.HelpersTab;
import com.killer560.hub.gui.tab.HomeTab;
import com.killer560.hub.gui.tab.HudElementsTab;
import com.killer560.hub.gui.tab.KeyCaptureTab;
import com.killer560.hub.gui.tab.NewTab;
import com.killer560.hub.gui.tab.ProfilesTab;
import net.minecraft.client.gui.ComponentPath;
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
    // Static for the same reason as tabs/selectedTab above (2026-09-14, per killer560's "reopening
    // should keep my scroll position, tab, and search text" request) - an instance field would reset
    // to "" the moment a fresh ModScreen is constructed on reopen, silently undoing whatever was typed
    // last time even though the selected tab itself already survived.
    private static String searchQuery = "";

    // Real bug found and fixed (2026-09-07), per killer560's screenshot showing accordion content
    // spilling straight past the bottom of the panel into the game world behind it: the content area
    // never had any scrolling or clipping at all - every widget just rendered wherever its fixed Y
    // coordinate said, with nothing stopping that from exceeding the panel once enough accordion
    // sections were expanded at once. scrollOffset shifts content up by this many pixels.
    // Originally every widget was only actually added to the screen if it FULLY fit within the visible
    // window after that shift - simpler than a real scissor clip, and looked fine, until killer560's
    // 2026-09-17 report (2026-09-17_14.35.42.png) that scrolling could leave "weird bottom space"
    // whenever the remaining room wasn't enough for a whole row: that row just didn't render at all
    // instead of showing however much of it fit. Content widgets are now wrapped in one
    // ScrollingContentPane (below) that scissor-clips them properly instead of dropping the partial one.
    // Static for the same reason as searchQuery above - survives closing and reopening the menu, not
    // just switching tabs within one open.
    private static int scrollOffset = 0;
    private int maxScroll = 0;
    private int visibleContentHeight = 0;
    private ScrollingContentPane contentPane;

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
            tabs.add(new ProfilesTab());
            tabs.add(new NewTab());
            tabs.add(new MiningWipTab());
            tabs.add(new GeneralTab());
            tabs.add(new DisplayTab());
            tabs.add(new ChatTab());
            tabs.add(new HudElementsTab());
            tabs.add(new HelpersTab());
            tabs.add(new DungeonTab());
            // Puzzle Solvers is no longer a top-level category (killer560, 2026-09-20: "Puzzle solvers
            // also shouldn't be a tab it should be in dungeons"). The solvers themselves sit in New for
            // this testing round and move to Dungeon once he confirms them; Auto Puzzles, the cheat half,
            // is already in the Dungeon folder.
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
        // The widgets themselves are discarded here, so their tooltip scopes go with them.
        SettingTooltips.clearScopes();
        this.addRenderableWidget(searchField);

        List<BaseTab> visible = visibleTabs();
        int tabY = panelY + 40;
        for (BaseTab tab : visible) {
            int index = tabs.indexOf(tab);
            boolean selected = index == selectedTab;
            this.addRenderableWidget(new MenuRowWidget(panelX + 8, tabY, sidebarW - 16, 20, tab.name, selected, false, tab.isCheatOnly(), () -> {
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
            contentPane = null;
            return;
        }

        BaseTab selected = tabs.get(selectedTab);
        if (selected instanceof FolderTab folderTab) {
            folderTab.setSearchQuery(searchQuery);
        }
        List<AbstractWidget> contentWidgets = selected.buildWidgets(contentX, contentY, contentW, this::rebuild);

        int naturalBottom = contentY;
        for (AbstractWidget w : contentWidgets) {
            naturalBottom = Math.max(naturalBottom, w.getY() + w.getHeight());
        }
        int visibleBottom = panelY + panelH - 10;
        visibleContentHeight = visibleBottom - contentY;
        maxScroll = Math.max(0, (naturalBottom - contentY) - visibleContentHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        for (AbstractWidget w : contentWidgets) {
            w.setY(w.getY() - scrollOffset);
        }
        // One wrapper widget instead of adding each row straight to the screen - see the ScrollingContentPane
        // class comment for why the clip has to happen at this level rather than per-row.
        contentPane = new ScrollingContentPane(contentX, contentY, contentW, visibleContentHeight, contentWidgets);
        this.addRenderableWidget(contentPane);
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

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // A tab waiting for a bind eats the next mouse press too, so a bind can be a mouse button
        // (killer560, 2026-09-20). Checked before super so the click doesn't also press the widget
        // under the cursor. Tabs that only take keys opt out via supportsMouseCapture().
        KeyCaptureTab listening = findListeningKeyCaptureTab();
        if (listening != null && listening.supportsMouseCapture()) {
            listening.onMouseCaptured(event.button());
            rebuild();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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
        drawSettingTooltip(graphics, mouseX, mouseY);
    }

    private net.minecraft.client.gui.components.AbstractWidget tooltipWidget = null;
    private long tooltipHoverSinceMs = 0L;

    /** Hover description box for the setting under the mouse (see {@link SettingTooltips}), after a short delay. */
    private void drawSettingTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        net.minecraft.client.gui.components.AbstractWidget hovered = null;
        for (var child : this.children()) {
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible
                    && mouseX >= w.getX() && mouseX < w.getX() + w.getWidth()
                    && mouseY >= w.getY() && mouseY < w.getY() + w.getHeight()) {
                hovered = w;
                break;
            }
        }
        // Every content-area row now lives inside one ScrollingContentPane wrapper (see its class
        // comment), so the loop above only ever finds that wrapper here - drill into its real children
        // for the specific setting under the mouse, same hit-test the loop above already does.
        if (hovered instanceof ScrollingContentPane pane) {
            hovered = pane.hoveredChild(mouseX, mouseY);
        }
        long now = System.currentTimeMillis();
        if (hovered != tooltipWidget) {
            tooltipWidget = hovered;
            tooltipHoverSinceMs = now;
        }
        if (hovered == null || now - tooltipHoverSinceMs < 350L || tabs.isEmpty()) {
            return;
        }
        String text;
        try {
            text = SettingTooltips.describe(tabs.get(selectedTab).name, hovered, hovered.getMessage().getString());
        } catch (RuntimeException e) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        int maxW = 220;
        java.util.List<net.minecraft.util.FormattedCharSequence> lines =
                this.font.split(net.minecraft.network.chat.Component.literal(text), maxW);
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
        graphics.fill(x - 4, y - 4, x + w + 4, y + h + 4, 0xF00D0D0D);
        graphics.outline(x - 4, y - 4, w + 8, h + 8, 0xFFCC6600);
        int ly = y;
        for (var line : lines) {
            graphics.text(this.font, line, x, ly, 0xFFF0E6DC, false);
            ly += 10;
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Wraps every widget the active tab/folder builds for the scrollable content area, so the panel
     *  can scissor-clip a row that's only half inside the visible window instead of refusing to add it
     *  at all - killer560's report (2026-09-17, screenshot 2026-09-17_14.35.42.png) that scrolling left
     *  "weird bottom space" whenever the remaining room wasn't enough to fit a whole row. {@code Screen}'s
     *  own render loop has no idea where the content area's own boundary is (it just walks every
     *  renderable in whatever order they were added), so a clip that only covers SOME of the screen's
     *  renderables isn't something a per-widget fix could do - this one wrapper widget is the actual
     *  scissor boundary instead, matching what the "consider the container instead of each widget"
     *  approach in a real scrolling list amounts to.
     *  <p>Implements {@link ContainerEventHandler} (rather than relying on {@link AbstractWidget}'s own
     *  leaf mouseClicked/mouseDragged/keyPressed/nextFocusPath behaviour - a superclass's own concrete
     *  method always beats a same-signature interface default, so those have to be explicitly
     *  overridden here to opt back into the container's own default routing) so clicks, drags and
     *  typing route down into whichever real child (button, slider, edit box) they land on - the exact
     *  same generic routing {@code Screen} itself already uses for its own top-level children, so
     *  existing focus/typing/dragging behaviour for every setting is unchanged.
     *  <p>Verified against the real 26.1.2 jar (decompiled {@code AbstractWidget.extractRenderState}
     *  bytecode) that a widget's hovered flag is only ever set when {@code GuiGraphicsExtractor
     *  .containsPointInScissor(mouseX, mouseY)} is also true for whatever scissor rect is active at the
     *  time - so passing the real mouse position straight through to a clipped child below needs no
     *  extra gating: a row's hidden half can never register as hovered (or, since a click routes through
     *  this same isMouseOver-based hit-test, as clicked) while this pane's scissor is the active one. */
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
                for (AbstractWidget child : children) {
                    // A row with zero overlap with the visible band would just get scissored away
                    // completely anyway - skip the extract call for it rather than pay for it.
                    if (child.getY() + child.getHeight() <= top || child.getY() >= bottom) {
                        continue;
                    }
                    child.extractRenderState(graphics, mouseX, mouseY, partialTick);
                }
            } finally {
                // Must run even if a child's own render call throws, or every draw for the rest of this
                // frame (sidebar, header, tooltip) inherits this pane's clip rect - the scissor stack
                // has no other way to recover (see GuiGraphicsExtractor$ScissorStack, a plain push/pop
                // deque with no reset).
                graphics.disableScissor();
            }
        }

        /** Hit-test used by {@code drawSettingTooltip} to find the specific setting under the mouse,
         *  now that every content row lives inside this one wrapper instead of being a direct child of
         *  the screen. Gated by this pane's own visible rectangle first so a row peeking only slightly
         *  into the clipped edge can't be picked from its hidden portion. */
        AbstractWidget hoveredChild(int mouseX, int mouseY) {
            if (!isMouseOver(mouseX, mouseY)) {
                return null;
            }
            for (AbstractWidget child : children) {
                if (child.visible && mouseX >= child.getX() && mouseX < child.getX() + child.getWidth()
                        && mouseY >= child.getY() && mouseY < child.getY() + child.getHeight()) {
                    return child;
                }
            }
            return null;
        }

        // AbstractWidget already implements GuiEventListener concretely with its own leaf-button
        // semantics for every method below, which would otherwise silently shadow ContainerEventHandler's
        // own default routing (a class's own method always wins over a same-signature interface default,
        // regardless of implements order) - each override just opts back into the container behaviour.
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
            // Tell the old and new widgets, like vanilla's AbstractContainerEventHandler does. Only storing the
            // reference meant a clicked EditBox never learned it was focused, so NO text box inside the mod menu
            // accepted typing (tolerance box, Profiles name, Class Overrides, ...). Fixed 2026-09-21.
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
            // Report each real row individually instead of this wrapper as one opaque entry, so the
            // narrator still reads out settings the same way it did before they all lived directly on
            // the screen.
            return children;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            // Nothing of its own to narrate - getNarratables() above hands the narrator the real rows.
        }
    }
}
