package com.killer560.hub.auction.ah;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.AuctionHouseConfig;
import com.killer560.hub.auction.AuctionListing;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.hud.AutoScale;
import com.killer560.hub.profileviewer.item.LegacyItems;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reskin Real Auction House: Hypixel's real AH menus drawn as pages of the unified Auction House (killer560,
 * 2026-10-07: "redo the auction house in a similar manner" to the Bazaar).
 * <p>
 * Same contract as the Bazaar's reskin. {@link AhPages} decides which menu is open; the chest is hidden (the
 * {@code auction/ah/mixin} hide mixins) and every item it mapped is drawn as a card, tab, filter or bar button that
 * remembers its slot. One press sends exactly ONE {@code handleContainerInput} on that slot (the button pressed;
 * QUICK_MOVE with shift) - what the same press on Hypixel's chest sends - and only when the slot still holds the very
 * item drawn and the menu has settled. Nothing else ever clicks: no tick, timer or chat line reaches {@link #sendClick}.
 * Bidding, buying, the confirm step, creating and managing auctions and claiming all happen in Hypixel's own menus;
 * the bid and price signs are sign screens, never touched.
 * <p>
 * <b>No flash between pages.</b> Hypixel opens a new container for each step. The previous page keeps being drawn (not
 * clickable, the header's bar pulsing) until the new one's items have settled; a listing opened from the API browser
 * is drawn from its API data until its Auction View arrives. So the chest never shows and the panel never blanks.
 * <p>
 * Falls back to Hypixel's own GUI for any menu {@link AhPages} cannot map, while the Hypixel Menu key (Left Alt by
 * default) is held, after the header's "Hypixel menu" button, and with Reskin Real Auction House off.
 */
public final class AhReskin {

    private static final Identifier INPUT_PHASE = Identifier.fromNamespaceAndPath("killer560smod", "ah_reskin_input");
    private static final Identifier DRAW_PHASE = Identifier.fromNamespaceAndPath("killer560smod", "ah_reskin_draw");

    /** Items must sit unchanged this long before a page is read (the Bazaar reskin's settle window). */
    private static final long SETTLE_MS = 110L;
    /** A page whose items never arrive goes back to Hypixel's GUI after this. */
    private static final long LOAD_TIMEOUT_MS = 2000L;
    /** The previous page is carried into a new menu opened within this long. */
    private static final long CARRY_MS = 3000L;

    private static final class State {
        final AbstractContainerScreen<?> screen;
        final int containerId;
        final long openedMs = System.currentTimeMillis();
        List<ItemStack> live = List.of();
        long stableSinceMs = System.currentTimeMillis();
        boolean pending = true;
        AhPages.Page page;
        /** What was settled when {@link #page} was read: drawn from here, so a half-updated menu never shows. */
        List<ItemStack> stacks = List.of();
        /** The previous page drawn until this one settles (not clickable). */
        AhPages.Page carriedPage;
        List<ItemStack> carriedStacks = List.of();
        AuctionListing opening;
        boolean vanilla;
        int scroll;
        int contentHeight;
        float factor = 1f;
        final List<AhUi.Hotspot> hotspots = new ArrayList<>();
        final List<String> layout = new ArrayList<>();
        int clicksSent;
        String lastClick = "";
        boolean recorded;

        State(AbstractContainerScreen<?> screen) {
            this.screen = screen;
            this.containerId = screen.getMenu().containerId;
        }
    }

    private static volatile State active;
    /** The last settled page and when it was last drawn, for carrying into the next menu. */
    private static volatile AhPages.Page lastPage;
    private static volatile List<ItemStack> lastStacks = List.of();
    private static volatile long lastDrawnMs;
    /** Frames drawn while a page was loading, and how many of them showed nothing of a page (testkit). */
    private static volatile int loadingFrames;
    private static volatile int blankFrames;
    /** Frames where a mapped-title AH menu showed Hypixel's chest although nothing asked for it (testkit). */
    private static volatile int vanillaFrames;

    private AhReskin() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.addPhaseOrdering(INPUT_PHASE, Event.DEFAULT_PHASE);
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, DRAW_PHASE);
        ScreenEvents.AFTER_INIT.register(INPUT_PHASE, AhReskin::onInitInput);
        ScreenEvents.AFTER_INIT.register(DRAW_PHASE, AhReskin::onInitDraw);
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    private static String plainTitle(Screen screen) {
        String s = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return s == null ? "" : s.trim();
    }

    private static State stateFor(Screen screen) {
        State st = active;
        return st != null && st.screen == screen ? st : null;
    }

    private static void onInitInput(Minecraft client, Screen screen, int w, int h) {
        if (!(screen instanceof ContainerScreen cs) || !AhPages.titleCandidate(plainTitle(cs))) {
            return;
        }
        State st = stateFor(screen);
        if (st == null) {
            st = new State(cs);
            // A listing he just opened from the API browser is drawn from its API data; otherwise the page he was on.
            st.opening = AhNav.pending();
            if (st.opening == null && System.currentTimeMillis() - lastDrawnMs < CARRY_MS && lastPage != null) {
                st.carriedPage = lastPage;
                st.carriedStacks = lastStacks;
            }
            active = st;
        }
        State state = st;
        // Fabric recreates every per-screen event (remove included) at each init, so a resize needs them all again.
        ScreenEvents.remove(screen).register(s -> {
            if (active == state) {
                active = null;
            }
        });
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> onPress(state, event.x(), event.y(),
                event.button(), event.hasShiftDown()));
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !hiding(state));
        ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, sx, sy) -> {
            if (!hiding(state)) {
                return true;
            }
            state.scroll = Math.max(0, state.scroll - (int) Math.signum(sy) * 41);
            return false;
        });
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            if (!hiding(state)) {
                return true;
            }
            // Escape and the inventory key still close the menu; any other key would act on a hidden slot.
            return event.key() == InputConstants.KEY_ESCAPE || client.options.keyInventory.matches(event);
        });
    }

    private static void onInitDraw(Minecraft client, Screen screen, int w, int h) {
        State st = stateFor(screen);
        if (st == null) {
            return;
        }
        ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> draw(st, g, mx, my));
    }

    /** True while {@code screen} is an AH menu drawn as the reskin (read by the hide mixins). */
    public static boolean isHiding(Object screen) {
        State st = active;
        return st != null && st.screen == screen && hiding(st);
    }

    private static boolean vanillaKeyHeld() {
        int code = AuctionHouseConfig.getInstance().getVanillaKeyCode();
        Minecraft mc = Minecraft.getInstance();
        return code >= 0 && mc.getWindow() != null && KeyUtil.isKeyDown(mc.getWindow(), code);
    }

    private static boolean hiding(State st) {
        if (!AuctionHouseConfig.getInstance().isReskinRealAh() || st.vanilla || vanillaKeyHeld()) {
            return false;
        }
        if (st.page != null) {
            return st.page.kind() != AhPages.Kind.UNKNOWN;
        }
        return System.currentTimeMillis() - st.openedMs < LOAD_TIMEOUT_MS;
    }

    /** Re-reads the menu; a page is (re)read only once its items have sat unchanged for {@link #SETTLE_MS}. */
    private static void poll(State st) {
        AbstractContainerMenu menu = st.screen.getMenu();
        int n = Math.max(0, menu.slots.size() - 36);
        List<ItemStack> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            items.add(menu.slots.get(i).getItem());
        }
        long now = System.currentTimeMillis();
        if (!ItemStack.listMatches(items, st.live)) {
            st.live = List.copyOf(items);
            st.stableSinceMs = now;
            st.pending = true;
            return;
        }
        if (!st.pending || now - st.stableSinceMs < SETTLE_MS || items.stream().allMatch(ItemStack::isEmpty)) {
            return;
        }
        st.pending = false;
        List<ItemStack> snapshot = new ArrayList<>(menu.slots.size());
        for (Slot slot : menu.slots) {
            snapshot.add(slot.getItem().copy());
        }
        AhPages.Page old = st.page;
        st.page = AhPages.classify(plainTitle(st.screen), n / 9, describe(menu));
        st.stacks = snapshot;
        if (old == null || old.kind() != st.page.kind()) {
            st.scroll = 0;
        }
        recordRecents(st);
    }

    private static List<AhPages.Item> describe(AbstractContainerMenu menu) {
        int n = Math.max(0, menu.slots.size() - 36);
        List<AhPages.Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack stack = menu.slots.get(i).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            name = name == null ? "" : name.trim();
            out.add(new AhPages.Item(i, name, DungeonChestValuer.cleanLore(stack),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(), name.isBlank()));
        }
        return out;
    }

    /** The auction he is looking at and the search he ran go into the recents rail (once per page). */
    private static void recordRecents(State st) {
        if (st.recorded || st.page == null) {
            return;
        }
        AuctionHouseConfig cfg = AuctionHouseConfig.getInstance();
        if (st.page.kind() == AhPages.Kind.VIEW && st.page.hero() != null) {
            ItemStack hero = stack(st.stacks, st.page.hero().slot());
            String id = LegacyItems.skyblockId(hero);
            AhLore.Listing l = AhLore.parse(st.page.hero().lore());
            cfg.addRecentViewed(new AuctionHouseConfig.Viewed(id, st.page.hero().name(), l.tier(), "",
                    System.currentTimeMillis()));
            cfg.save();
            st.recorded = true;
        } else if (st.page.kind() == AhPages.Kind.BROWSER && !st.page.filter().isEmpty()) {
            cfg.addRecentSearch(st.page.filter());
            cfg.save();
            st.recorded = true;
        }
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    private static boolean onPress(State st, double x, double y, int button, boolean shift) {
        poll(st);
        if (!hiding(st)) {
            return true;
        }
        if (button != 0 && button != 1) {
            return false;
        }
        double lx = x / st.factor;
        double ly = y / st.factor;
        for (AhUi.Hotspot h : st.hotspots) {
            if (!h.contains(lx, ly)) {
                continue;
            }
            if (h.action() != null) {
                Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                        .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                h.action().accept(button);
            } else if (h.slot() >= 0) {
                sendClick(st, h, button, shift);
            }
            break;
        }
        return false; // never let a press reach the hidden chest or inventory
    }

    /**
     * The only place the reskin talks to the server: one press, one container click on the slot that was drawn. Refused
     * (nothing sent) while the menu is changing or a carried page is shown, when the screen is not the one drawn, or
     * when the slot no longer holds the drawn item.
     */
    private static void sendClick(State st, AhUi.Hotspot h, int button, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (st.pending || st.page == null || mc.gameMode == null || mc.player == null || McCompat.screen(mc) != st.screen) {
            return;
        }
        AbstractContainerMenu menu = st.screen.getMenu();
        if (menu.containerId != st.containerId || h.slot() >= menu.slots.size()
                || !ItemStack.matches(menu.slots.get(h.slot()).getItem(), h.stack())) {
            return;
        }
        ContainerInput input = shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        mc.gameMode.handleContainerInput(menu.containerId, h.slot(), button, input, mc.player);
        st.clicksSent++;
        st.lastClick = h.slot() + " " + button + " " + input.name() + " " + h.label();
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    private static ItemStack stack(List<ItemStack> stacks, int slot) {
        return slot >= 0 && slot < stacks.size() ? stacks.get(slot) : ItemStack.EMPTY;
    }

    private static void draw(State st, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        poll(st);
        st.hotspots.clear();
        st.layout.clear();
        if (!hiding(st)) {
            if (AuctionHouseConfig.getInstance().isReskinRealAh() && !st.vanilla && !vanillaKeyHeld()
                    && (st.page == null || st.page.kind() != AhPages.Kind.UNKNOWN)) {
                vanillaFrames++;
            }
            return;
        }
        AhMarket.markInUse();
        Screen s = st.screen;
        float f = AutoScale.current();
        st.factor = f;
        int w = Math.max(1, (int) Math.floor(s.width / f));
        int h = Math.max(1, (int) Math.floor(s.height / f));
        int lmx = (int) Math.floor(mouseX / f);
        int lmy = (int) Math.floor(mouseY / f);
        boolean live = st.page != null;
        AhPages.Page page = live ? st.page : st.carriedPage;
        List<ItemStack> stacks = live ? st.stacks : st.carriedStacks;
        // A carried or loading page draws into a throwaway hotspot list: it is shown, never clickable.
        List<AhUi.Hotspot> hs = live ? st.hotspots : new ArrayList<>();
        AhUi ui = new AhUi(g, w, h, lmx, lmy, st.layout, hs);
        g.pose().pushMatrix();
        g.pose().scale(f, f);
        try {
            ui.layout.add("scale " + f + " " + s.width + " " + s.height);
            ui.layout.add("state " + (live ? "live" : page != null ? "carried" : st.opening != null ? "opening" : "loading"));
            ui.frame();
            drawPage(st, ui, page, stacks, live);
        } finally {
            g.pose().popMatrix();
        }
        if (live) {
            lastPage = st.page;
            lastStacks = st.stacks;
            AhNav.clearPending();
        } else {
            loadingFrames++;
            if (page == null && st.opening == null) {
                blankFrames++;
            }
        }
        lastDrawnMs = System.currentTimeMillis();
        if (!ui.hoverStack.isEmpty()) {
            List<Component> lines = new ArrayList<>(Screen.getTooltipFromItem(Minecraft.getInstance(), ui.hoverStack));
            lines.addAll(ui.hoverExtra);
            g.setTooltipForNextFrame(ui.font, lines, Optional.empty(), mouseX, mouseY);
        } else if (ui.hoverLines != null) {
            g.setTooltipForNextFrame(ui.font, ui.hoverLines, Optional.empty(), mouseX, mouseY);
        }
    }

    private static void drawPage(State st, AhUi ui, AhPages.Page page, List<ItemStack> stacks, boolean live) {
        AuctionConfig cfg = AuctionConfig.getInstance();
        boolean busy = !live || st.pending;
        // Header: the browser's real category slots on the Auctions Browser, the API browser's tabs elsewhere.
        List<AhUi.Tab> tabs = new ArrayList<>();
        if (page != null && page.kind() == AhPages.Kind.BROWSER) {
            for (AhPages.Item it : page.tabs()) {
                boolean sel = false;
                for (String l : it.lore()) {
                    if (l.toLowerCase(Locale.ROOT).contains("currently")) {
                        sel = true;
                    }
                }
                ItemStack stack = stack(stacks, it.slot());
                tabs.add(new AhUi.Tab(it.name(), stack, sel, it.slot(), stack.copy(), null));
            }
        } else {
            for (String[] tab : AhUi.API_TABS) {
                String cat = tab[1];
                tabs.add(new AhUi.Tab(tab[0], tabIcon(cat), false, -1, ItemStack.EMPTY,
                        b -> AhNav.toBrowser(cat, null, false)));
            }
        }
        ui.header(tabs, "Hypixel menu", b -> st.vanilla = true, "action", busy);

        // Toolbar.
        List<AhUi.Button> buttons = new ArrayList<>();
        if (page != null && page.kind() == AhPages.Kind.BROWSER) {
            addFilter(buttons, page.sort(), stacks, "Sort", "sort");
            addFilter(buttons, page.rarity(), stacks, "Rarity", "rarity");
            addFilter(buttons, page.type(), stacks, "Type", "type");
            if (page.reset() != null) {
                ItemStack r = stack(stacks, page.reset().slot());
                buttons.add(new AhUi.Button("Reset", "↺", null, false, page.reset().slot(), r.copy(), null, "reset"));
            }
            AhPages.Item search = page.search();
            ui.toolbar(page.filter(), "Search (Hypixel's sign)", false, null, null,
                    search == null ? -1 : search.slot(), search == null ? ItemStack.EMPTY : stack(stacks, search.slot()).copy(),
                    buttons, page.heading());
        } else {
            String heading = page != null ? page.heading() : st.opening != null ? "Opening auction..." : "Loading...";
            ui.toolbar("", "Search the Auction House", false, b -> AhNav.toBrowser(null, "", true), "search", -1,
                    ItemStack.EMPTY, buttons, heading);
        }

        ui.recentsRail(q -> AhNav.toBrowser("", q, false), v -> AhNav.toBrowser("", com.killer560.hub.auction.screen.AuctionHouseScreen.viewedQuery(v), false), () -> {
            AuctionHouseConfig c = AuctionHouseConfig.getInstance();
            c.clearRecents();
            c.save();
        });

        int x = ui.contentX;
        int y = ui.bodyY;
        int cw = ui.contentW;
        int bottom = ui.bodyBottom;
        ui.region("content", x, y, cw, bottom - y);
        if (page == null) {
            if (st.opening != null) {
                drawOpening(ui, st.opening, x, y, cw, bottom);
            } else {
                ui.text("content", "Reading Hypixel's Auction House...", x + 8, y + 8, ui.t.dim());
            }
            ui.bottomBar(List.of(), "", List.of());
            return;
        }
        int maxScroll;
        if (page.kind().grid()) {
            maxScroll = drawGrid(st, ui, page, stacks, x, y, cw, bottom);
        } else {
            maxScroll = drawCards(st, ui, page, stacks, x, y, cw, bottom);
        }
        st.scroll = Math.max(0, Math.min(st.scroll, maxScroll));

        // Bottom bar: the menu's own bottom-row buttons; the page arrows on the right.
        List<AhUi.Button> bar = new ArrayList<>();
        for (AhPages.Item it : page.nav()) {
            ItemStack stack = stack(stacks, it.slot());
            bar.add(new AhUi.Button(navLabel(it), navLabel(it), stack, false, it.slot(), stack.copy(), null, "nav"));
        }
        List<AhUi.Button> right = new ArrayList<>();
        if (page.prev() != null) {
            int[] p = AhPages.arrowPage(page.prev());
            ItemStack stack = stack(stacks, page.prev().slot());
            right.add(new AhUi.Button("‹ Prev" + (p[1] > 0 ? " (" + p[0] + "/" + p[1] + ")" : ""), "‹", null, false,
                    page.prev().slot(), stack.copy(), null, "prev"));
        }
        if (page.next() != null) {
            int[] p = AhPages.arrowPage(page.next());
            ItemStack stack = stack(stacks, page.next().slot());
            right.add(new AhUi.Button("Next" + (p[1] > 0 ? " (" + p[0] + "/" + p[1] + ")" : "") + " ›", "›", null, false,
                    page.next().slot(), stack.copy(), null, "next"));
        }
        String status = page.kind().grid() ? page.listings().size() + (page.listings().size() == 1 ? " listing" : " listings") : "";
        ui.bottomBar(bar, status, right);
    }

    private static void addFilter(List<AhUi.Button> out, AhPages.Item it, List<ItemStack> stacks, String name, String id) {
        if (it == null) {
            return;
        }
        String opt = AhPages.selectedOption(it);
        ItemStack stack = stack(stacks, it.slot());
        String label = opt.isEmpty() ? it.name() : name + ": " + opt;
        out.add(new AhUi.Button(label, opt.isEmpty() ? name : opt, null, false, it.slot(), stack.copy(), null, id));
    }

    /** "Go Back" says where it goes ("< Auction House" from its "To Auction House" lore line). */
    private static String navLabel(AhPages.Item it) {
        if (it.name().equals("Go Back")) {
            for (String line : it.lore()) {
                String l = line.trim();
                if (l.startsWith("To ") && l.length() <= 34) {
                    return "‹ " + l.substring(3);
                }
            }
            return "‹ Back";
        }
        return it.name();
    }

    static ItemStack tabIcon(String category) {
        net.minecraft.world.item.Item item = switch (category) {
            case "weapon" -> net.minecraft.world.item.Items.GOLDEN_SWORD;
            case "armor" -> net.minecraft.world.item.Items.DIAMOND_CHESTPLATE;
            case "accessories" -> net.minecraft.world.item.Items.EMERALD;
            case "consumables" -> net.minecraft.world.item.Items.APPLE;
            case "blocks" -> net.minecraft.world.item.Items.COBBLESTONE;
            case "*misc" -> net.minecraft.world.item.Items.STICK;
            default -> net.minecraft.world.item.Items.NETHER_STAR;
        };
        return new ItemStack(item);
    }

    /** Listing cards for Auctions Browser / Manage Auctions / Your Bids; returns the max scroll. */
    private static int drawGrid(State st, AhUi ui, AhPages.Page page, List<ItemStack> stacks, int x, int y, int cw,
            int bottom) {
        List<AhPages.Item> rows = page.listings();
        if (rows.isEmpty()) {
            String msg = switch (page.kind()) {
                case MANAGE -> "You have no auctions right now.";
                case BIDS -> "You have no active bids.";
                default -> "No listings on this page.";
            };
            ui.text("content", msg, x + 8, y + 8, ui.t.dim());
            return 0;
        }
        int[] grid = ui.gridColumns(cw - 6, 150);
        int cols = grid[0], cardW = grid[1], gap = grid[2];
        int cardH = 36;
        int total = ((rows.size() + cols - 1) / cols) * (cardH + gap) - gap;
        int top = y + 3;
        int maxScroll = Math.max(0, total - (bottom - top - 3));
        ui.beginClip(x, top, cw, bottom);
        try {
            for (int i = 0; i < rows.size(); i++) {
                AhPages.Item it = rows.get(i);
                int cx = x + 3 + (i % cols) * (cardW + gap);
                int cy = top + (i / cols) * (cardH + gap) - st.scroll;
                if (cy + cardH <= top || cy >= bottom) {
                    continue;
                }
                ItemStack stack = stack(stacks, it.slot());
                AhLore.Listing l = AhLore.parse(it.lore());
                String price = l.price() < 0 ? "" : l.isBin() ? AhUi.coins(l.price()) + " coins"
                        : l.priceLabel() + " " + AhUi.coins(l.price());
                String badge = "";
                int badgeColor = ui.t.dim();
                if (!l.status().isEmpty()) {
                    badge = l.status().replace("!", "");
                    badgeColor = l.status().startsWith("Sold") ? ui.t.green() : l.status().startsWith("Expired")
                            ? ui.t.red() : ui.t.aqua();
                } else if (l.isBin()) {
                    badge = "BIN";
                    badgeColor = ui.t.gold();
                } else if (l.price() >= 0) {
                    badge = l.bids() > 0 ? l.bids() + " bids" : "Auction";
                    badgeColor = ui.t.aqua();
                }
                List<String> sub = new ArrayList<>();
                if (!l.endsIn().isEmpty()) {
                    sub.add(l.endsIn());
                }
                if (!l.seller().isEmpty()) {
                    sub.add(l.seller());
                }
                String id = LegacyItems.skyblockId(stack);
                long lb = AhMarket.lowestBin(id);
                List<Component> extra = new ArrayList<>();
                if (lb > 0) {
                    extra.add(Component.literal("§8Lowest BIN now: §6" + AhUi.coins(lb)));
                }
                AhMarket.Stats market = AhMarket.stats(id);
                boolean cheapest = l.isBin() && lb > 0 && l.bin() <= lb && market != null && market.binCount() > 1;
                int edge = AhUi.tierColor(l.tier(), ui.t.border());
                ui.listingCard(cx, cy, cardW, cardH, stack, stack.getHoverName(), price, ui.t.gold(), badge, badgeColor,
                        String.join(" · ", sub), edge, cheapest, it.slot(), null, String.valueOf(it.slot()), true, extra);
            }
        } finally {
            ui.endClip();
        }
        scrollbar(ui, x + cw - 3, top, bottom, total, st.scroll, maxScroll);
        return maxScroll;
    }

    /** Hero + action cards for the other pages; returns the max scroll. */
    private static int drawCards(State st, AhUi ui, AhPages.Page page, List<ItemStack> stacks, int x, int y, int cw,
            int bottom) {
        int top = y + 4;
        int cy = top - st.scroll;
        ui.beginClip(x, top, cw, bottom);
        int contentEnd;
        try {
            if (page.hero() != null) {
                ItemStack hero = stack(stacks, page.hero().slot());
                AhLore.Listing l = AhLore.parse(page.hero().lore());
                List<String[]> info = new ArrayList<>();
                if (l.price() >= 0) {
                    info.add(new String[]{l.priceLabel(), AhUi.coins(l.price()) + " coins", "FFC040"});
                }
                if (!l.seller().isEmpty()) {
                    info.add(new String[]{"Seller", l.seller()});
                }
                if (!l.endsIn().isEmpty()) {
                    info.add(new String[]{"Ends in", l.endsIn()});
                }
                if (!l.graceLeft().isEmpty()) {
                    info.add(new String[]{"Can buy in", l.graceLeft()});
                }
                if (l.bids() >= 0) {
                    info.add(new String[]{"Bids", String.valueOf(l.bids())});
                }
                cy = ui.hero(x + 4, cy, cw - 8, hero, hero.getHoverName(), info, page.hero().slot()) + 4;
                if (page.kind() == AhPages.Kind.VIEW) {
                    int my = ui.marketPanel(x + 4, cy, cw - 8, 44, LegacyItems.skyblockId(hero));
                    cy = my > cy ? my + 5 : cy;
                }
            }
            List<AhPages.Item> actions = page.actions();
            int inner = cw - 8;
            if (page.playerInventory()) {
                cy = drawInventory(st, ui, stacks, x + 4, cy, inner) + 5;
            }
            int cols = page.kind() == AhPages.Kind.CONFIRM ? Math.min(2, Math.max(1, inner / 120))
                    : Math.max(1, Math.min(actions.size(), inner / 150));
            int gap = 5;
            int cardW = (inner - gap * (cols - 1)) / Math.max(1, cols);
            for (int r = 0; r * cols < actions.size(); r++) {
                int rowH = 30;
                for (int c = 0; c < cols && r * cols + c < actions.size(); c++) {
                    rowH = Math.max(rowH, Math.min(120, ui.actionCardHeight(stack(stacks, actions.get(r * cols + c).slot()), cardW)));
                }
                for (int c = 0; c < cols && r * cols + c < actions.size(); c++) {
                    AhPages.Item it = actions.get(r * cols + c);
                    ItemStack stack = stack(stacks, it.slot());
                    int tone = 0;
                    if (page.kind() == AhPages.Kind.CONFIRM) {
                        String n = it.name().toLowerCase(Locale.ROOT);
                        tone = n.startsWith("confirm") ? ui.t.green() : n.startsWith("cancel") ? ui.t.red() : 0;
                    }
                    ui.actionCard(x + 4 + c * (cardW + gap), cy, cardW, rowH, stack, it.slot(), tone, "card");
                }
                cy += rowH + gap;
            }
            contentEnd = cy + st.scroll;
        } finally {
            ui.endClip();
        }
        int total = contentEnd - top;
        int maxScroll = Math.max(0, total - (bottom - top));
        scrollbar(ui, x + cw - 3, top, bottom, total, st.scroll, maxScroll);
        return maxScroll;
    }

    /** His inventory on Create Auction pages: a click puts that item up, as on Hypixel's own screen. */
    private static int drawInventory(State st, AhUi ui, List<ItemStack> stacks, int x, int y, int cw) {
        int containerSlots = stacks.size() - 36;
        if (containerSlots < 0) {
            return y;
        }
        int cell = 18;
        int gridW = 9 * cell;
        int gx = x + Math.max(0, (cw - gridW) / 2);
        ui.text("content", "Pick the item to auction:", gx, y, ui.t.dim());
        int gy = y + 11;
        ui.region("inventory", gx, gy, gridW, 4 * cell + 4);
        for (int i = 0; i < 36; i++) {
            int row = i < 27 ? i / 9 : 3;
            int col = i % 9;
            int sx = gx + col * cell;
            int sy = gy + row * cell + (row == 3 ? 4 : 0);
            int slot = containerSlots + i;
            ItemStack stack = stack(stacks, slot);
            boolean hover = ui.in(sx, sy, cell, cell);
            ui.g.fill(sx, sy, sx + cell - 1, sy + cell - 1, hover ? ui.t.cardHover() : ui.t.card());
            if (!stack.isEmpty()) {
                ui.g.item(stack, sx + 1, sy + 1);
                ui.g.itemDecorations(ui.font, stack, sx + 1, sy + 1);
                ui.addHotspot(new AhUi.Hotspot(sx, sy, cell - 1, cell - 1, slot, stack.copy(), null,
                        stack.getHoverName().getString()));
                ui.hotspot(String.valueOf(slot), sx, sy, cell - 1, cell - 1, stack.getHoverName().getString());
                if (hover) {
                    ui.hoverStack = stack;
                }
            }
        }
        return gy + 4 * cell + 4;
    }

    /** The frame shown between clicking a listing in the API browser and Hypixel's Auction View arriving. */
    private static void drawOpening(AhUi ui, AuctionListing l, int x, int y, int cw, int bottom) {
        List<String[]> info = new ArrayList<>();
        info.add(new String[]{l.bin() ? "BIN" : l.highestBid() > 0 ? "Top bid" : "Starting bid",
                AhUi.coins(l.currentPrice()) + " coins", "FFC040"});
        info.add(new String[]{"Ends in", AhUi.timeLeft(l.end() - System.currentTimeMillis())});
        int cy = ui.hero(x + 4, y + 4, cw - 8, l.icon(), Component.literal(
                com.killer560.hub.itembrowser.SkyblockItemStackFactory.tierColorCode(l.tier()) + l.itemName()), info, -1) + 4;
        int my = ui.marketPanel(x + 4, cy, cw - 8, 44, l.skyblockId());
        cy = my > cy ? my + 6 : cy + 2;
        if (cy + 9 < bottom) {
            ui.text("content", "Opening on Hypixel...", x + 8, cy, ui.t.dim());
        }
    }

    private static void scrollbar(AhUi ui, int x, int top, int bottom, int total, int scroll, int maxScroll) {
        if (maxScroll <= 0) {
            return;
        }
        int hgt = bottom - top;
        ui.g.fill(x, top, x + 2, bottom, ui.t.surfaceAlt());
        int thumb = Math.max(12, (int) ((long) hgt * hgt / Math.max(1, total)));
        int ty = top + (int) ((long) (hgt - thumb) * scroll / Math.max(1, maxScroll));
        ui.g.fill(x, ty, x + 2, ty + thumb, ui.t.accent());
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /** The reskinned page's kind ("VIEW"...), "LOADING"/"CARRIED"/"OPENING" before it settles, "NONE" for Hypixel's GUI. */
    public static String kindForTest() {
        State st = active;
        if (st == null || !hiding(st)) {
            return "NONE";
        }
        if (st.page != null) {
            return st.page.kind().name();
        }
        return st.carriedPage != null ? "CARRIED" : st.opening != null ? "OPENING" : "LOADING";
    }

    /** Every region, text and hotspot of the last frame drawn, in layout units (see {@link AhUi}). */
    public static List<String> layoutReportForTest() {
        State st = active;
        return st == null ? List.of() : List.copyOf(st.layout);
    }

    /** "clicksSent|last click" for the open reskin. */
    public static String clicksForTest() {
        State st = active;
        return st == null ? "0|" : st.clicksSent + "|" + st.lastClick;
    }

    /** {frames drawn while loading, of those with no page at all, frames that showed the chest unasked}. */
    public static int[] loadingFramesForTest() {
        return new int[]{loadingFrames, blankFrames, vanillaFrames};
    }

    public static void resetFramesForTest() {
        loadingFrames = 0;
        blankFrames = 0;
        vanillaFrames = 0;
    }

    public static void scrollForTest(int px) {
        State st = active;
        if (st != null) {
            st.scroll = Math.max(0, px);
        }
    }

    public static float factorForTest() {
        State st = active;
        return st == null ? 1f : st.factor;
    }
}
