package com.killer560.hub.bazaar;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.KeyUtil;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Hypixel's real Bazaar menus drawn as the Bazaar screen ({@link BazaarView}), the same screen /killer560bz opens.
 * <p>
 * It stays a real interaction with the real container. {@link BazaarPages} decides which menu is open from its title
 * and items; the chest is hidden ({@code bazaar/mixin}) and the view draws the menu's items as rows, cards and buttons
 * that remember their slot. A press on one sends exactly ONE {@code handleContainerInput} for that slot (the button
 * pressed, QUICK_MOVE with shift) - the packet the same press on Hypixel's chest sends - and only through
 * {@link #sendClick}, which refuses when the menu changed since it was read or the slot no longer holds what was drawn.
 * The one click that is not a press of his is {@link BazaarFollowUp}'s, the direct follow-up to one.
 * <p>
 * <b>No flash between pages</b> (killer560, 2026-10-07: "Swapping pages in it does this reloading thing"). Each page
 * Hypixel sends is a new container screen whose items arrive a moment later. While the new one is not yet read, the
 * view keeps drawing the page that was on screen - the same frame, inert to clicks - and swaps to the new page the frame
 * it is classified, with the header, tabs, recents and bottom bar never moving. Hypixel's chest is never shown in
 * between, and neither is an empty panel. A menu that turns out to be one the reskin does not know goes to Hypixel's GUI
 * after a short grace.
 * <p>
 * Falls back to Hypixel's own GUI, untouched, for any menu {@link BazaarPages} cannot map, while the Hypixel Menu key
 * (Left Alt by default) is held, after the "Hypixel menu" button in the header, with Reskin Real Bazaar off, and while
 * the cheat build's Bazaar flipper runs. Input listeners register in a phase BEFORE Fabric's default and drawing AFTER
 * it (see docs/LESSONS-GUI.md).
 */
public final class BazaarReskin {

    private static final Identifier INPUT_PHASE = Identifier.fromNamespaceAndPath("killer560smod", "bazaar_reskin_input");
    private static final Identifier DRAW_PHASE = Identifier.fromNamespaceAndPath("killer560smod", "bazaar_reskin_draw");

    /** Items must sit unchanged this long before a press (or the follow-up) may click on the page. */
    static final long SETTLE_MS = 110L;
    /** A hidden menu whose items have not arrived by then goes back to Hypixel's GUI. */
    private static final long LOAD_TIMEOUT_MS = 1500L;
    /** A menu the reskin cannot map keeps the previous page up this long after its items settle, then shows as Hypixel's. */
    private static final long UNKNOWN_GRACE_MS = 600L;
    /** A generic "A ➜ B" title hides Hypixel's GUI while loading only when the Bazaar was on screen this recently. */
    static final long RECENT_MS = 3000L;
    /** After the follow-up click, the previous view stays up until the next page arrives, at most this long. */
    private static final long HOLD_MS = 1500L;

    /** Everything about the one container screen being reskinned. Kept across a resize (init runs again). */
    static final class State {
        final AbstractContainerScreen<?> screen;
        final int containerId;
        final long openedMs = System.currentTimeMillis();
        final boolean hideWhileLoading;
        List<ItemStack> snapshot = List.of();
        long stableSinceMs = System.currentTimeMillis();
        boolean pending = true;
        BazaarPages.Page page;
        boolean vanilla;
        /** The follow-up is deciding on (or has clicked from) this page: keep the previous view up. */
        boolean hold;
        long followUpSentMs;
        String appliedKey = "";
        int clicksSent;
        String lastClick = "";
        /** What the last frame drew: this state, an earlier one (frozen), or null (API view, frozen or not). */
        State drawnWith;
        boolean frozen;

        State(AbstractContainerScreen<?> screen, boolean hideWhileLoading) {
            this.screen = screen;
            this.containerId = screen.getMenu().containerId;
            this.hideWhileLoading = hideWhileLoading;
        }

        ItemStack stackAt(int slot) {
            AbstractContainerMenu menu = screen.getMenu();
            return slot >= 0 && slot < menu.slots.size() ? menu.slots.get(slot).getItem() : ItemStack.EMPTY;
        }
    }

    private static volatile State active;
    /** The state whose own page was last drawn: what a newer, not yet read menu keeps on screen. */
    private static State lastDrawn;

    private BazaarReskin() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.addPhaseOrdering(INPUT_PHASE, Event.DEFAULT_PHASE);
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, DRAW_PHASE);
        ScreenEvents.AFTER_INIT.register(INPUT_PHASE, BazaarReskin::onInitInput);
        ScreenEvents.AFTER_INIT.register(DRAW_PHASE, BazaarReskin::onInitDraw);
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    static String plainTitle(Screen screen) {
        String s = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return s == null ? "" : s.trim();
    }

    private static State stateFor(Screen screen) {
        State st = active;
        return st != null && st.screen == screen ? st : null;
    }

    private static void onInitInput(Minecraft client, Screen screen, int w, int h) {
        if (!(screen instanceof ContainerScreen cs)) {
            return;
        }
        if (!BazaarPages.titleCandidate(plainTitle(cs))) {
            BazaarFollowUp.disarm("another menu opened");
            return;
        }
        State st = stateFor(screen);
        if (st == null) {
            boolean live = BazaarView.sessionLive(RECENT_MS);
            if (!live) {
                BazaarView.freshSession();
                lastDrawn = null;
            }
            st = new State(cs, BazaarPages.strongTitle(plainTitle(cs)) || live);
            active = st;
        }
        State state = st;
        // Fabric recreates every per-screen event (remove included) at each init, so a resize needs them all again
        // (fabric-screen-api ScreenMixin.beforeInit, javap 5.1.0).
        ScreenEvents.remove(screen).register(s -> {
            if (active == state) {
                active = null;
            }
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> onPress(state, event.x() / scale(),
                event.y() / scale(), event.button(), event.hasShiftDown()));
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !hiding(state));
        ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, sx, sy) -> {
            if (!hiding(state)) {
                return true;
            }
            BazaarView.scroll(mx / scale(), my / scale(), sy);
            return false;
        });
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            if (!hiding(state)) {
                return true;
            }
            if (BazaarView.key(event)) {
                return false;
            }
            // Escape and the inventory key still close the menu. Every other key (hotbar swaps, drop) would act on
            // whatever hidden slot sits under the mouse, so it is swallowed.
            return event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
                    || client.options.keyInventory.matches(event);
        });
        ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> {
            if (!hiding(state)) {
                return true;
            }
            BazaarView.charTyped(event);
            return false;
        });
    }

    private static void onInitDraw(Minecraft client, Screen screen, int w, int h) {
        State st = stateFor(screen);
        if (st == null) {
            return;
        }
        ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> draw(st, g, mx, my));
    }

    /** True while {@code screen} is a Bazaar menu drawn as the Bazaar screen (read by the hide mixins and HUD gate). */
    public static boolean isHiding(Object screen) {
        State st = active;
        return st != null && st.screen == screen && hiding(st);
    }

    private static boolean vanillaKeyHeld() {
        int code = AuctionConfig.getInstance().getBazaarVanillaKeyCode();
        Minecraft mc = Minecraft.getInstance();
        return code >= 0 && mc.getWindow() != null && KeyUtil.isKeyDown(mc.getWindow(), code);
    }

    private static boolean hiding(State st) {
        if (!AuctionConfig.getInstance().isReskinRealBazaar() || st.vanilla || vanillaKeyHeld()
                || com.killer560.hub.bazaarflip.BazaarFlipFeature.isRunning()) {
            return false;
        }
        if (st.page != null && st.page.kind() != BazaarPages.Kind.UNKNOWN) {
            return true;
        }
        if (!st.hideWhileLoading) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (st.page == null) {
            return now - st.openedMs < LOAD_TIMEOUT_MS;
        }
        // A page we cannot map: the previous page stays up a moment in case its items are still arriving.
        return st.pending || now - st.stableSinceMs < UNKNOWN_GRACE_MS;
    }

    /** Re-reads the menu: classified on every change, clickable once its items have sat still for {@link #SETTLE_MS}. */
    private static void poll(State st) {
        List<ItemStack> items = menuStacks(st.screen.getMenu());
        long now = System.currentTimeMillis();
        if (!ItemStack.listMatches(items, st.snapshot)) {
            st.snapshot = List.copyOf(items);
            st.stableSinceMs = now;
            st.pending = true;
            classify(st, items);
            return;
        }
        if (!st.pending || now - st.stableSinceMs < SETTLE_MS || items.stream().allMatch(ItemStack::isEmpty)) {
            return;
        }
        st.pending = false;
        onSettled(st);
    }

    private static void classify(State st, List<ItemStack> items) {
        if (items.stream().allMatch(ItemStack::isEmpty)) {
            return;
        }
        st.page = BazaarPages.classify(plainTitle(st.screen), rows(st.screen.getMenu()), describe(st.screen.getMenu()));
        if (BazaarFollowUp.isArmed() && st.followUpSentMs == 0 && waitsOn(st.page)) {
            st.hold = true; // the follow-up decides once the items settle; until then the previous view stays up
        }
        applyMode(st);
    }

    private static boolean waitsOn(BazaarPages.Page page) {
        BazaarFollowUp.Armed a = BazaarFollowUp.armed();
        return a != null && page != null && ((a.target() == BazaarFollowUp.Target.PRODUCT
                && page.kind() == BazaarPages.Kind.SEARCH) || (a.target() == BazaarFollowUp.Target.BUTTON
                && page.kind() == BazaarPages.Kind.CATEGORY));
    }

    /** The page's items have settled: the armed follow-up (if any) decides now. */
    private static void onSettled(State st) {
        if (!BazaarFollowUp.isArmed() || st.page == null || st.followUpSentMs != 0) {
            if (st.hold && st.followUpSentMs == 0) {
                st.hold = false;
                applyMode(st);
            }
            return;
        }
        int slot = BazaarFollowUp.decide(st.page);
        if (slot >= 0) {
            ItemStack stack = st.stackAt(slot).copy();
            String label = BazaarOrderParser.strip(stack.getHoverName().getString());
            if (sendClick(st, slot, stack, 0, false, "follow-up " + label)) {
                BazaarFollowUp.clicked(slot, label);
                st.hold = true;
                st.followUpSentMs = System.currentTimeMillis();
                return;
            }
            BazaarFollowUp.disarm("click refused");
        }
        st.hold = false;
        applyMode(st);
    }

    /** Tells the view a new page is on screen (once per page), unless the previous view is being held. */
    private static void applyMode(State st) {
        if (st.page == null || st.page.kind() == BazaarPages.Kind.UNKNOWN || st.hold) {
            return;
        }
        String key = st.page.kind() + "|" + st.page.title();
        if (key.equals(st.appliedKey)) {
            return;
        }
        st.appliedKey = key;
        BazaarView.onPageShown(st.page, st);
    }

    private static int rows(AbstractContainerMenu menu) {
        return Math.max(0, menu.slots.size() - 36) / 9;
    }

    /** The menu's own slots (never the 36 player-inventory slots appended after them). */
    private static List<ItemStack> menuStacks(AbstractContainerMenu menu) {
        int n = Math.max(0, menu.slots.size() - 36);
        List<ItemStack> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(menu.slots.get(i).getItem());
        }
        return out;
    }

    private static List<BazaarPages.Item> describe(AbstractContainerMenu menu) {
        int n = Math.max(0, menu.slots.size() - 36);
        List<BazaarPages.Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = BazaarOrderParser.strip(stack.getHoverName().getString());
            out.add(new BazaarPages.Item(slot.index, name, DungeonChestValuer.cleanLore(stack), name.isBlank()));
        }
        return out;
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    private static boolean onPress(State st, double x, double y, int button, boolean shift) {
        poll(st);
        if (!hiding(st)) {
            return true;
        }
        if (st.frozen) {
            return false; // the page on screen is not the menu that is open: nothing on it may be clicked
        }
        BazaarView.press(x, y, button, shift, st.drawnWith);
        return false; // never let a press reach the hidden chest or inventory
    }

    /**
     * The only place the Bazaar talks to the server through a menu: one container click on {@code slot}. Refused
     * (nothing sent, false) when the menu changed since it was read, the screen is not the one drawn, or the slot no
     * longer holds {@code expected}.
     */
    static boolean sendClick(State st, int slot, ItemStack expected, int button, boolean shift, String label) {
        Minecraft mc = Minecraft.getInstance();
        if (st == null || st != active || st.pending || mc.gameMode == null || mc.player == null
                || McCompat.screen(mc) != st.screen) {
            return false;
        }
        AbstractContainerMenu menu = st.screen.getMenu();
        if (menu.containerId != st.containerId || slot < 0 || slot >= menu.slots.size()
                || !ItemStack.matches(menu.slots.get(slot).getItem(), expected)) {
            return false;
        }
        ContainerInput input = shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, input, mc.player);
        st.clicksSent++;
        st.lastClick = slot + " " + button + " " + input.name() + " " + label;
        return true;
    }

    /** The reskinned menu that is open and drawn as itself (not frozen), or null. */
    static State liveState() {
        State st = active;
        return st != null && hiding(st) && !st.frozen && st.page != null ? st : null;
    }

    static void showHypixelMenu(State st) {
        if (st != null) {
            st.vanilla = true;
        }
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    private static void draw(State st, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        poll(st);
        if (st.hold && st.followUpSentMs > 0 && System.currentTimeMillis() - st.followUpSentMs > HOLD_MS) {
            st.hold = false; // the follow-up's answer never came: show this page after all
            applyMode(st);
        } else if (st.hold && st.followUpSentMs == 0 && !BazaarFollowUp.isArmed()) {
            st.hold = false; // the follow-up timed out before this page settled
            applyMode(st);
        }
        if (!hiding(st)) {
            st.drawnWith = null;
            st.frozen = false;
            return;
        }
        boolean own = st.page != null && st.page.kind() != BazaarPages.Kind.UNKNOWN && !st.hold;
        State with = own ? st : (lastDrawn != st ? lastDrawn : null);
        if (own) {
            lastDrawn = st;
        }
        st.drawnWith = with;
        st.frozen = !own;
        // Auto Scale, as the mod's own screens get it (hud/mixin/AutoScaleScreenMixin): the menu-less Bazaar screen is
        // one of those, so the reskin lays out and draws on the same canvas - switching between them moves nothing.
        float f = scale();
        g.pose().pushMatrix();
        try {
            g.pose().scale(f, f);
            BazaarView.tooltipScale = f;
            BazaarView.draw(g, com.killer560.hub.hud.AutoScale.layoutSize(st.screen.width, f),
                    com.killer560.hub.hud.AutoScale.layoutSize(st.screen.height, f), (int) (mouseX / f),
                    (int) (mouseY / f), with, !own, "reskin");
        } finally {
            BazaarView.tooltipScale = 1.0f;
            g.pose().popMatrix();
        }
    }

    /** The factor the mod's own screens are drawn with right now (Auto Scale), 1 when it is off. */
    private static float scale() {
        float f = com.killer560.hub.hud.AutoScale.current();
        return f > 0.05f ? f : 1.0f;
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /** The open menu's kind ("PRODUCT"...), "LOADING" while the previous page is still shown, or "NONE" when Hypixel's
     *  GUI shows (or no Bazaar menu is open). */
    public static String kindForTest() {
        State st = active;
        if (st == null || !hiding(st)) {
            return "NONE";
        }
        if (st.page == null || st.page.kind() == BazaarPages.Kind.UNKNOWN || st.hold) {
            return "LOADING";
        }
        return st.page.kind().name();
    }

    /** Every region, text and hotspot of the last frame drawn. */
    public static List<String> layoutReportForTest() {
        return BazaarView.layoutReportForTest();
    }

    /** "clicksSent|last click" for the open reskin (testkit). */
    public static String clicksForTest() {
        State st = active;
        return st == null ? "0|" : st.clicksSent + "|" + st.lastClick;
    }
}
