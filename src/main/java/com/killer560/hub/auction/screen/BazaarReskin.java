package com.killer560.hub.auction.screen;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarCatalog;
import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.auction.BazaarOrders;
import com.killer560.hub.auction.BazaarPages;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reskin Real Bazaar: Hypixel's real Bazaar menus drawn in the Bazaar browser's look (killer560, 2026-10-07: "The
 * custom bazaar menu should essentially fully replace the bazaar, so the bazaar orders should be a reskin of the true
 * bazaar").
 * <p>
 * It stays a real interaction with the real container. {@link BazaarPages} decides which menu is open from its title
 * and items; this class hides the chest (the {@code auction/mixin} hide mixins, as Spirit Leap's custom menu does) and
 * draws every item of that menu as a row, card, sidebar entry or bottom-bar button that remembers the slot it came
 * from. A mouse press on one sends exactly ONE {@code handleContainerInput} for that slot, with the button pressed and
 * QUICK_MOVE when shift is held - the packet the same press on Hypixel's chest sends - and nothing is ever clicked
 * any other way: no tick, timer or chat line reaches {@link #sendClick}. Before sending, the slot must still hold the
 * very item that was drawn there (same item and components) and the menu must not have changed since the page was read;
 * otherwise the press does nothing. So buying, selling, orders, claiming, cancelling, the search sign and every other
 * step keep working the way Hypixel's own GUI does - the sign screens are not containers and are never touched.
 * <p>
 * Falls back to Hypixel's own GUI, untouched, for any menu {@link BazaarPages} cannot map, while the Hypixel Menu key
 * (Left Alt by default) is held, after the "Hypixel menu" button in the header, with Reskin Real Bazaar off, and while
 * the cheat build's Bazaar flipper runs.
 * <p>
 * Input listeners register in a phase BEFORE Fabric's default, so a press on the reskin never reaches another feature's
 * handler for a slot that is hidden under it; drawing registers AFTER the default, so the reskin paints over the other
 * features' container overlays.
 */
public final class BazaarReskin {

    private static final Identifier INPUT_PHASE = Identifier.fromNamespaceAndPath("killer560smod", "bazaar_reskin_input");
    private static final Identifier DRAW_PHASE = Identifier.fromNamespaceAndPath("killer560smod", "bazaar_reskin_draw");

    /** Items must sit unchanged this long before a page is read (Spirit Leap's settle window). */
    private static final long SETTLE_MS = 110L;
    /** A Bazaar-only title whose items have not arrived by then goes back to Hypixel's GUI. */
    private static final long LOAD_TIMEOUT_MS = 1500L;
    /** A generic "A ➜ B" title hides Hypixel's GUI while loading only when a reskinned page was shown this recently
     *  (he is clicking through the Bazaar), never when one is opened from anywhere else. */
    private static final long RECENT_MS = 3000L;

    private static final int ROW_H = 20;
    private static final int NAV_H = 20;

    private static final Pattern BUY_PRICE = Pattern.compile("^Buy price: ([\\d,.]{1,20}) coins?$");
    private static final Pattern SELL_PRICE = Pattern.compile("^Sell price: ([\\d,.]{1,20}) coins?$");
    private static final Pattern TO_LINE = Pattern.compile("^To (.{1,32})$");
    /** A product line in a category page group's lore: "▶ Wheat" (Advanced mode appends " 4.8 | 4.3"). */
    private static final Pattern PRODUCT_LINE = Pattern.compile("^▶ (.{1,40}?)(?: [\\d,.]{1,20} \\| [\\d,.]{1,20})?$");

    /** One clickable element of the last frame: a menu slot (with the stack drawn for it) or a UI action. */
    private record Hotspot(int x, int y, int w, int h, int slot, ItemStack stack, Runnable action, String label) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /** Everything about the one container screen being reskinned. Kept across a resize (init runs again). */
    private static final class State {
        final AbstractContainerScreen<?> screen;
        final int containerId;
        final long openedMs = System.currentTimeMillis();
        final boolean hideWhileLoading;
        List<ItemStack> snapshot = List.of();
        long stableSinceMs = System.currentTimeMillis();
        boolean pending = true;
        BazaarPages.Page page;
        boolean vanilla;
        int listScroll;
        final List<Hotspot> hotspots = new ArrayList<>();
        final List<String> layout = new ArrayList<>();
        int clicksSent;
        String lastClick = "";

        State(AbstractContainerScreen<?> screen, boolean hideWhileLoading) {
            this.screen = screen;
            this.containerId = screen.getMenu().containerId;
            this.hideWhileLoading = hideWhileLoading;
        }
    }

    private static volatile State active;
    private static volatile long lastReskinMs;

    private static List<BazaarProduct> productsSource;
    private static Map<String, BazaarProduct> productsByName = Map.of();

    private BazaarReskin() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.addPhaseOrdering(INPUT_PHASE, Event.DEFAULT_PHASE);
        ScreenEvents.AFTER_INIT.addPhaseOrdering(Event.DEFAULT_PHASE, DRAW_PHASE);
        ScreenEvents.AFTER_INIT.register(INPUT_PHASE, BazaarReskin::onInitInput);
        ScreenEvents.AFTER_INIT.register(DRAW_PHASE, BazaarReskin::onInitDraw);
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
        if (!(screen instanceof ContainerScreen cs) || !BazaarPages.titleCandidate(plainTitle(cs))) {
            return;
        }
        State st = stateFor(screen);
        if (st == null) {
            String title = plainTitle(cs);
            boolean hide = BazaarPages.strongTitle(title) || System.currentTimeMillis() - lastReskinMs < RECENT_MS;
            st = new State(cs, hide);
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
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> onPress(state, event.x(), event.y(),
                event.button(), event.hasShiftDown()));
        ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !hiding(state));
        ScreenMouseEvents.allowMouseScroll(screen).register((s, mx, my, sx, sy) -> {
            if (!hiding(state)) {
                return true;
            }
            state.listScroll = Math.max(0, state.listScroll - (int) Math.signum(sy) * ROW_H * 3);
            return false;
        });
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            if (!hiding(state)) {
                return true;
            }
            // Escape and the inventory key still close the menu. Every other key (hotbar swaps, drop) would act on
            // whatever hidden slot sits under the mouse, so it is swallowed.
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

    /** True while {@code screen} is a Bazaar menu drawn as the reskin (read by the hide mixins). */
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
        if (st.page != null) {
            return st.page.kind() != BazaarPages.Kind.UNKNOWN;
        }
        return st.hideWhileLoading && System.currentTimeMillis() - st.openedMs < LOAD_TIMEOUT_MS;
    }

    /** Re-reads the menu; a page is (re)read only once its items have sat unchanged for {@link #SETTLE_MS}. */
    private static void poll(State st) {
        List<ItemStack> items = menuStacks(st.screen.getMenu());
        long now = System.currentTimeMillis();
        if (!ItemStack.listMatches(items, st.snapshot)) {
            st.snapshot = List.copyOf(items);
            st.stableSinceMs = now;
            st.pending = true;
            return;
        }
        if (!st.pending || now - st.stableSinceMs < SETTLE_MS || items.stream().allMatch(ItemStack::isEmpty)) {
            return;
        }
        st.pending = false;
        st.page = BazaarPages.classify(plainTitle(st.screen), rows(st.screen.getMenu()), describe(st.screen.getMenu()));
        st.listScroll = 0;
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
        if (button != 0 && button != 1) {
            return false;
        }
        for (Hotspot h : st.hotspots) {
            if (!h.contains(x, y)) {
                continue;
            }
            if (h.action() != null) {
                if (button == 0) {
                    Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                            .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    h.action().run();
                }
            } else {
                sendClick(st, h, button, shift);
            }
            break;
        }
        return false; // never let a press reach the hidden chest or inventory
    }

    /**
     * The only place the reskin talks to the server: one press, one container click on the slot that was drawn. Refused
     * (nothing sent) when the menu changed since the page was read, the screen is not the one drawn, or the slot no
     * longer holds the drawn item.
     */
    private static void sendClick(State st, Hotspot h, int button, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (st.pending || mc.gameMode == null || mc.player == null || McCompat.screen(mc) != st.screen) {
            return;
        }
        AbstractContainerMenu menu = st.screen.getMenu();
        if (menu.containerId != st.containerId || h.slot() < 0 || h.slot() >= menu.slots.size()
                || !ItemStack.matches(menu.slots.get(h.slot()).getItem(), h.stack())) {
            return;
        }
        ContainerInput input = shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
        mc.gameMode.handleContainerInput(menu.containerId, h.slot(), button, input, mc.player);
        st.clicksSent++;
        st.lastClick = h.slot() + " " + button + " " + input.name() + " " + h.label();
    }

    // ---- drawing --------------------------------------------------------------------------------------------------

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    private static void draw(State st, GuiGraphicsExtractor g, int mouseX, int mouseY) {
        poll(st);
        st.hotspots.clear();
        st.layout.clear();
        if (!hiding(st)) {
            return;
        }
        lastReskinMs = System.currentTimeMillis();
        Screen s = st.screen;
        Font font = font();
        int width = s.width;
        int height = s.height;
        g.fill(0, 0, width, height, 0xB0000000);
        int margin = height < 300 ? 4 : 8;
        int panelW = Math.min(width - 2 * margin, 700);
        int panelH = height - 2 * margin;
        int panelX = (width - panelW) / 2;
        int panelY = margin;
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, BazaarScreen.PANEL_BG);
        g.outline(panelX, panelY, panelW, panelH, BazaarScreen.BORDER);
        region(st, "panel", panelX, panelY, panelW, panelH);

        // Header: "Bazaar  <page>" left; the way back to Hypixel's own menu right.
        int headerY = panelY + 6;
        region(st, "header", panelX + 6, headerY - 2, panelW - 12, 12);
        String brand = "§lBazaar";
        text(st, g, "header", brand, panelX + 6, headerY, BazaarScreen.ACCENT_BRIGHT);
        int bx = panelX + 6 + font.width(brand) + 8;
        String btnLabel = "Hypixel menu";
        int btnW = font.width(btnLabel) + 10;
        int btnX = panelX + panelW - 6 - btnW;
        String hint = vanillaHint();
        int hintW = font.width(hint);
        boolean showHint = btnX - 6 - hintW > bx + 60;
        int headingRight = showHint ? btnX - 10 - hintW : btnX - 6;
        BazaarPages.Page page = st.page;
        String heading = page == null ? "Loading..." : page.heading();
        text(st, g, "header", fit(heading, headingRight - bx), bx, headerY, BazaarScreen.TEXT);
        if (showHint) {
            text(st, g, "header", hint, btnX - 6 - hintW, headerY, BazaarScreen.FAINT);
        }
        boolean btnHover = mouseX >= btnX && mouseX < btnX + btnW && mouseY >= headerY - 2 && mouseY < headerY + 10;
        g.fill(btnX, headerY - 2, btnX + btnW, headerY + 10, btnHover ? BazaarScreen.ROW_HOVER : BazaarScreen.ROW_ALT);
        g.outline(btnX, headerY - 2, btnW, 12, BazaarScreen.BORDER);
        text(st, g, "header", btnLabel, btnX + 5, headerY, BazaarScreen.DIM);
        st.hotspots.add(new Hotspot(btnX, headerY - 2, btnW, 12, -1, ItemStack.EMPTY, () -> st.vanilla = true, btnLabel));
        hotspot(st, "action", btnX, headerY - 2, btnW, 12, btnLabel);

        int bodyY = headerY + 14;
        int navY = panelY + panelH - 6 - NAV_H;
        int bodyBottom = navY - 4;
        if (page == null) {
            text(st, g, "main", "Reading Hypixel's Bazaar menu...", panelX + 12, bodyY + 8, BazaarScreen.DIM);
            return;
        }
        int mainX = panelX + 6;
        int mainW = panelW - 12;
        if (!page.sidebar().isEmpty()) {
            int sideW = Math.max(84, Math.min(130, panelW / 5));
            drawSidebar(st, g, page, panelX + 6, bodyY, sideW, bodyBottom - bodyY, mouseX, mouseY);
            mainX += sideW + 6;
            mainW -= sideW + 6;
        }
        g.fill(mainX, bodyY, mainX + mainW, bodyBottom, BazaarScreen.LIST_BG);
        g.outline(mainX, bodyY, mainW, bodyBottom - bodyY, BazaarScreen.BORDER);
        region(st, "main", mainX, bodyY, mainW, bodyBottom - bodyY);
        ItemStack[] hovered = new ItemStack[1];
        if (page.kind().list) {
            drawList(st, g, page, mainX, bodyY, mainW, bodyBottom - bodyY, mouseX, mouseY, hovered);
        } else {
            drawCards(st, g, page, mainX, bodyY, mainW, bodyBottom - bodyY, mouseX, mouseY, hovered);
        }
        drawNav(st, g, page, panelX + 6, navY, panelW - 12, mouseX, mouseY, hovered);
        if (hovered[0] != null && !hovered[0].isEmpty()) {
            // The real item's own tooltip, every lore line Hypixel sent: nothing on the menu is ever hidden from him.
            g.setTooltipForNextFrame(font, hovered[0], mouseX, mouseY);
        }
    }

    private static String vanillaHint() {
        int code = AuctionConfig.getInstance().getBazaarVanillaKeyCode();
        if (code < 0) {
            return "";
        }
        return "Hold " + InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString() + ":";
    }

    // Sidebar (category pages): Hypixel's five category buttons ---------------------------------------------------

    private static void drawSidebar(State st, GuiGraphicsExtractor g, BazaarPages.Page page, int x, int y, int w, int h,
            int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + h, BazaarScreen.SIDEBAR_BG);
        g.outline(x, y, w, h, BazaarScreen.BORDER);
        region(st, "sidebar", x, y, w, h);
        int rowH = 20;
        int ry = y + 3;
        for (BazaarPages.Item it : page.sidebar()) {
            if (ry + rowH > y + h - 3) {
                break;
            }
            ItemStack stack = stackAt(st, it.slot());
            boolean selected = it.name().equals(page.heading());
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowH;
            if (selected) {
                g.fill(x + 1, ry, x + w - 1, ry + rowH, BazaarScreen.SELECTED_BG);
                g.fill(x + 1, ry, x + 3, ry + rowH, BazaarScreen.ACCENT);
            } else if (hover) {
                g.fill(x + 1, ry, x + w - 1, ry + rowH, BazaarScreen.ROW_HOVER);
            }
            g.item(stack, x + 5, ry + 2);
            text(st, g, "sidebar", fit(it.name(), w - 30), x + 24, ry + 6,
                    selected ? BazaarScreen.ACCENT_BRIGHT : BazaarScreen.TEXT);
            slotHotspot(st, x + 1, ry, w - 2, rowH, it, stack);
            ry += rowH;
        }
    }

    // List pages: groups, products, orders -------------------------------------------------------------------------

    private static void drawList(State st, GuiGraphicsExtractor g, BazaarPages.Page page, int x, int y, int w, int h,
            int mouseX, int mouseY, ItemStack[] hovered) {
        Font font = font();
        List<BazaarPages.Item> rows = page.content();
        boolean orders = page.kind() == BazaarPages.Kind.ORDERS;
        boolean products = !orders && rows.stream().anyMatch(BazaarReskin::hasPrices);
        int headY = y + 4;
        region(st, "list-head", x + 1, headY, w - 2, 10);
        int colW = Math.max(font.width("Sell offer") + 6, font.width("999.9M") + 10);
        int right = x + w - 8;
        boolean vol = products && w - 22 - colW * 4 >= 110;
        boolean margin = products && w - 22 - colW * (vol ? 4 : 3) >= 90;
        int volX = vol ? right - colW : right;
        int marginX = margin ? volX - colW : volX;
        int sellX = marginX - colW;
        int buyX = sellX - colW;
        int nameRight = products ? buyX - 4 : right;
        String head = listHead(page, rows);
        text(st, g, "list-head", fit(head, (products ? buyX : right) - x - 10), x + 6, headY, BazaarScreen.DIM);
        if (products) {
            rightText(st, g, "list-head", "Buy", buyX + colW, headY, BazaarScreen.FAINT);
            rightText(st, g, "list-head", "Sell", sellX + colW, headY, BazaarScreen.FAINT);
            if (margin) {
                rightText(st, g, "list-head", "Margin", marginX + colW, headY, BazaarScreen.FAINT);
            }
            if (vol) {
                rightText(st, g, "list-head", "7d vol", volX + colW, headY, BazaarScreen.FAINT);
            }
        }
        int top = headY + 12;
        g.fill(x + 4, top - 2, x + w - 4, top - 1, BazaarScreen.BORDER);
        int bottom = y + h - 2;
        region(st, "list", x + 1, top, w - 2, bottom - top);
        if (rows.isEmpty()) {
            text(st, g, "list", orders ? "You have no Bazaar orders right now." : "Nothing here.", x + 8, top + 6,
                    BazaarScreen.DIM);
            return;
        }
        int rowH = orders ? 22 : ROW_H;
        int contentH = rows.size() * rowH;
        int maxScroll = Math.max(0, contentH - (bottom - top));
        st.listScroll = Math.min(st.listScroll, maxScroll);
        g.enableScissor(x + 1, top, x + w - 1, bottom);
        try {
            for (int i = 0; i < rows.size(); i++) {
                int ry = top + i * rowH - st.listScroll;
                if (ry + rowH <= top) {
                    continue;
                }
                if (ry >= bottom) {
                    break;
                }
                BazaarPages.Item it = rows.get(i);
                ItemStack stack = stackAt(st, it.slot());
                boolean whole = ry >= top && ry + rowH <= bottom;
                boolean hover = mouseX >= x && mouseX < x + w - 6 && mouseY >= Math.max(top, ry)
                        && mouseY < Math.min(bottom, ry + rowH);
                if (hover) {
                    hovered[0] = stack;
                    g.fill(x + 1, ry, x + w - 1, ry + rowH, BazaarScreen.ROW_HOVER);
                } else if (i % 2 == 1) {
                    g.fill(x + 1, ry, x + w - 1, ry + rowH, BazaarScreen.ROW_ALT);
                }
                BazaarOrderParser.Order order = orders ? BazaarOrderParser.parse(stack.getHoverName().getString(),
                        it.lore()) : null;
                if (order != null) {
                    drawOrderRow(st, g, order, stack, x + 6, ry, right, whole);
                } else {
                    g.item(stack, x + 3, ry + (rowH - 16) / 2);
                    g.itemDecorations(font, stack, x + 3, ry + (rowH - 16) / 2);
                    int tx = x + 23;
                    String sub = subtitle(it);
                    FormattedCharSequence name = fitComponent(stack.getHoverName(), nameRight - tx);
                    componentText(st, g, whole, "list", name, tx, ry + 2);
                    plainText(st, g, whole, "list", fit(sub, nameRight - tx), tx, ry + 11, BazaarScreen.FAINT);
                    if (products && hasPrices(it)) {
                        double buy = loreValue(it, BUY_PRICE);
                        double sell = loreValue(it, SELL_PRICE);
                        plainRight(st, g, whole, "list", buy < 0 ? "-" : BazaarScreen.price(buy), buyX + colW, ry + 6,
                                BazaarScreen.GOLD);
                        plainRight(st, g, whole, "list", sell < 0 ? "-" : BazaarScreen.price(sell), sellX + colW, ry + 6,
                                BazaarScreen.GOLD);
                        // Margin from the prices Hypixel's own menu shows; the weekly volume only the API has.
                        if (margin && buy >= 0 && sell > 0) {
                            double pct = (buy - sell) / sell * 100.0;
                            plainRight(st, g, whole, "list", String.format(Locale.US, "%.1f%%", pct), marginX + colW,
                                    ry + 6, pct >= 0 ? BazaarScreen.GREEN : BazaarScreen.RED);
                        }
                        if (vol) {
                            BazaarProduct p = liveProduct(it.name());
                            plainRight(st, g, whole, "list", p == null ? "-" : BazaarScreen.shortNumber(p.weeklyVolume()),
                                    volX + colW, ry + 6, BazaarScreen.DIM);
                        }
                    } else if (page.kind() == BazaarPages.Kind.CATEGORY) {
                        // A group's products, from its "▶ Wheat" lore lines, in the room the price columns use elsewhere.
                        int used = tx + Math.max(font.width(name), font.width(fit(sub, nameRight - tx))) + 16;
                        String preview = fit(String.join(" · ", productNames(it)), right - used);
                        int pw = font.width(preview);
                        int px = right - pw;
                        if (pw > 0 && px >= used) {
                            plainText(st, g, whole, "list", preview, px, ry + 6, BazaarScreen.DIM);
                        }
                    }
                }
                int hy = Math.max(ry, top);
                int hh = Math.min(ry + rowH, bottom) - hy;
                if (hh > 0) {
                    slotHotspot(st, x, hy, w - 6, hh, it, stack);
                }
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            int sx = x + w - 4;
            g.fill(sx, top, sx + 2, bottom, 0xFF1E1E1E);
            int thumbH = Math.max(12, (int) ((long) (bottom - top) * (bottom - top) / Math.max(1, contentH)));
            int thumbY = top + (int) ((long) (bottom - top - thumbH) * st.listScroll / Math.max(1, maxScroll));
            g.fill(sx, thumbY, sx + 2, thumbY + thumbH, BazaarScreen.ACCENT);
        }
    }

    private static String listHead(BazaarPages.Page page, List<BazaarPages.Item> rows) {
        return switch (page.kind()) {
            case CATEGORY -> rows.size() + " groups";
            case ORDERS -> {
                int n = 0;
                for (BazaarPages.Item it : rows) {
                    if (BazaarOrderParser.parse(it.name(), it.lore()) != null) {
                        n++;
                    }
                }
                yield n + (n == 1 ? " order" : " orders") + " · live from Manage Orders · click one to claim or open it";
            }
            default -> rows.size() + (rows.size() == 1 ? " product" : " products");
        };
    }

    private static void drawOrderRow(State st, GuiGraphicsExtractor g, BazaarOrderParser.Order o, ItemStack stack,
            int x, int y, int right, boolean record) {
        Font font = font();
        g.item(stack, x - 3, y + 3);
        boolean buy = o.type() == BazaarOrderParser.Type.BUY;
        String tag = buy ? "BUY" : "SELL";
        int tagW = font.width("SELL") + 6;
        int tx = x + 17;
        g.fill(tx, y + 2, tx + tagW, y + 11, buy ? 0xFF1F4A1F : 0xFF4A3A10);
        BazaarProduct p = liveProduct(o.productName());
        String status = BazaarScreen.standingText(o, p);
        int statusW = font.width(status);
        int nameX = tx + tagW + 4;
        String name = fit(o.productName(), right - statusW - 8 - nameX);
        String detailLine = fit(BazaarScreen.shortNumber(o.filled()) + "/" + BazaarScreen.shortNumber(o.amount())
                + " filled · " + BazaarScreen.price(o.pricePerUnit()) + " each · " + BazaarScreen.price(o.total())
                + " total" + (o.owner() != null ? " · " + o.owner() : ""), right - tx);
        plainText(st, g, record, "list", tag, tx + (tagW - font.width(tag)) / 2, y + 3,
                buy ? BazaarScreen.GREEN : BazaarScreen.GOLD);
        plainText(st, g, record, "list", name, nameX, y + 3, BazaarScreen.TEXT);
        plainText(st, g, record, "list", status, right - statusW, y + 3, BazaarScreen.statusColor(o, p));
        plainText(st, g, record, "list", detailLine, tx, y + 12, BazaarScreen.FAINT);
    }

    // Card pages: product, amounts, prices, confirmations, order options -------------------------------------------

    private static void drawCards(State st, GuiGraphicsExtractor g, BazaarPages.Page page, int x, int y, int w, int h,
            int mouseX, int mouseY, ItemStack[] hovered) {
        List<BazaarPages.Item> cards = new ArrayList<>(page.content());
        int top = y + 6;
        if (page.kind() == BazaarPages.Kind.PRODUCT && !cards.isEmpty() && !isAction(cards.get(0))) {
            top = drawProductHeader(st, g, cards.remove(0), x, top, w, mouseX, mouseY, hovered) + 6;
        }
        int n = cards.size();
        if (n == 0) {
            return;
        }
        int inner = w - 12;
        int cols = Math.max(1, Math.min(n, inner / 150));
        if (n == 4 && cols == 3) {
            cols = 2;
        }
        int gridRows = (n + cols - 1) / cols;
        int gap = 6;
        int cardW = (inner - gap * (cols - 1)) / cols;
        int avail = y + h - 6 - top;
        // Each row of cards as tall as its tallest card's lore needs, but never more than an even share of the room.
        int maxH = Math.max(28, (avail - gap * (gridRows - 1)) / gridRows);
        int[] rowH = new int[gridRows];
        for (int i = 0; i < n; i++) {
            rowH[i / cols] = Math.max(rowH[i / cols], Math.min(maxH, cardHeight(stackAt(st, cards.get(i).slot()), cardW)));
        }
        int total = gap * (gridRows - 1);
        for (int r : rowH) {
            total += r;
        }
        region(st, "cards", x + 6, top, inner, Math.max(0, Math.min(avail, total)));
        int cy = top;
        for (int r = 0; r < gridRows; r++) {
            if (cy + rowH[r] > y + h - 2) {
                break; // a very short window: never draw a card outside the panel
            }
            for (int c = 0; c < cols && r * cols + c < n; c++) {
                drawCard(st, g, cards.get(r * cols + c), x + 6 + c * (cardW + gap), cy, cardW, rowH[r], mouseX, mouseY,
                        hovered);
            }
            cy += rowH[r] + gap;
        }
    }

    /** What {@link #drawCard} needs to show the whole lore (same spacing rules), at least 28. */
    private static int cardHeight(ItemStack stack, int w) {
        int hgt = 24;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            boolean lastBlank = true;
            for (Component line : lore.lines()) {
                if (line.getString().isBlank()) {
                    if (!lastBlank) {
                        hgt += 4;
                    }
                    lastBlank = true;
                    continue;
                }
                lastBlank = false;
                hgt += 10 * Math.max(1, font().split(line, w - 12).size());
            }
        }
        return Math.max(28, hgt + 4);
    }

    private static int drawProductHeader(State st, GuiGraphicsExtractor g, BazaarPages.Item it, int x, int y, int w,
            int mouseX, int mouseY, ItemStack[] hovered) {
        ItemStack stack = stackAt(st, it.slot());
        int hgt = 38;
        region(st, "product", x + 6, y, w - 12, hgt);
        boolean hover = mouseX >= x + 6 && mouseX < x + w - 6 && mouseY >= y && mouseY < y + hgt;
        if (hover) {
            hovered[0] = stack;
        }
        g.pose().pushMatrix();
        g.pose().translate(x + 8, y + 3);
        g.pose().scale(2f, 2f);
        g.item(stack, 0, 0);
        g.pose().popMatrix();
        int tx = x + 44;
        int tw = x + w - 8 - tx;
        componentText(st, g, true, "product", fitComponent(stack.getHoverName(), tw), tx, y + 4);
        BazaarProduct p = liveProduct(it.name());
        String line = p == null ? subtitle(it)
                : "Instant buy " + BazaarScreen.price(p.buyPrice()) + " · Instant sell " + BazaarScreen.price(p.sellPrice())
                + " · Spread " + String.format(Locale.US, "%.1f%%", p.marginPercent());
        text(st, g, "product", fit(line, tw), tx, y + 15, p == null ? BazaarScreen.FAINT : BazaarScreen.GOLD);
        if (p != null) {
            String l2 = "7d: " + BazaarScreen.shortNumber(p.buyMovingWeek()) + " bought · "
                    + BazaarScreen.shortNumber(p.sellMovingWeek()) + " sold";
            text(st, g, "product", fit(l2, tw), tx, y + 26, BazaarScreen.FAINT);
        }
        g.fill(x + 6, y + hgt, x + w - 6, y + hgt + 1, BazaarScreen.BORDER);
        // The product item is a slot like any other: a press on it is the same press on Hypixel's slot.
        slotHotspot(st, x + 6, y, w - 12, hgt, it, stack);
        return y + hgt + 1;
    }

    private static void drawCard(State st, GuiGraphicsExtractor g, BazaarPages.Item it, int x, int y, int w, int h,
            int mouseX, int mouseY, ItemStack[] hovered) {
        Font font = font();
        ItemStack stack = stackAt(st, it.slot());
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? BazaarScreen.ROW_HOVER : BazaarScreen.ROW_ALT);
        g.outline(x, y, w, h, hover ? BazaarScreen.ACCENT : BazaarScreen.BORDER);
        if (hover) {
            hovered[0] = stack;
        }
        region(st, "card", x, y, w, h);
        g.item(stack, x + 4, y + 4);
        g.itemDecorations(font, stack, x + 4, y + 4);
        componentText(st, g, true, "card", fitComponent(stack.getHoverName(), w - 28), x + 24, y + 8);
        // The lore Hypixel sent, in its own colours, wrapped to the card; what does not fit is in the tooltip.
        int ly = y + 24;
        int bottom = y + h - 3;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
            try {
                boolean lastBlank = true;
                for (Component line : lore.lines()) {
                    if (ly + 9 > bottom) {
                        break;
                    }
                    if (line.getString().isBlank()) {
                        if (!lastBlank) {
                            ly += 4;
                        }
                        lastBlank = true;
                        continue;
                    }
                    lastBlank = false;
                    for (FormattedCharSequence seq : font.split(line, w - 12)) {
                        if (ly + 9 > bottom) {
                            break;
                        }
                        componentText(st, g, true, "card", seq, x + 6, ly);
                        ly += 10;
                    }
                }
            } finally {
                g.disableScissor();
            }
        }
        slotHotspot(st, x, y, w, h, it, stack);
    }

    // Bottom bar: Go Back, Close, Search, Manage Orders, Sell Inventory Now ... -------------------------------------

    private static void drawNav(State st, GuiGraphicsExtractor g, BazaarPages.Page page, int x, int y, int w,
            int mouseX, int mouseY, ItemStack[] hovered) {
        Font font = font();
        region(st, "nav", x, y, w, NAV_H);
        List<BazaarPages.Item> nav = page.nav();
        if (nav.isEmpty()) {
            return;
        }
        List<String> labels = new ArrayList<>();
        int total = 0;
        for (BazaarPages.Item it : nav) {
            String label = navLabel(it);
            labels.add(label);
            total += 24 + font.width(label) + 4;
        }
        int gap = 4;
        total += gap * (nav.size() - 1);
        boolean iconsOnly = total > w;
        int bx = x;
        for (int i = 0; i < nav.size(); i++) {
            BazaarPages.Item it = nav.get(i);
            ItemStack stack = stackAt(st, it.slot());
            String label = labels.get(i);
            int bw = iconsOnly ? Math.max(20, Math.min(22, (w - gap * (nav.size() - 1)) / nav.size()))
                    : 24 + font.width(label) + 4;
            if (bx + bw > x + w) {
                break;
            }
            boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + NAV_H;
            g.fill(bx, y, bx + bw, y + NAV_H, hover ? BazaarScreen.ROW_HOVER : BazaarScreen.ROW_ALT);
            g.outline(bx, y, bw, NAV_H, hover ? BazaarScreen.ACCENT : BazaarScreen.BORDER);
            g.item(stack, bx + 2, y + 2);
            if (!iconsOnly) {
                text(st, g, "nav", label, bx + 22, y + 6, hover ? BazaarScreen.ACCENT_BRIGHT : BazaarScreen.TEXT);
            }
            if (hover) {
                hovered[0] = stack;
            }
            slotHotspot(st, bx, y, bw, NAV_H, it, stack);
            bx += bw + gap;
        }
    }

    /** "Go Back" says where it goes ("< Bazaar", from its "To Bazaar" lore line); everything else keeps its name. */
    private static String navLabel(BazaarPages.Item it) {
        if (it.name().equals("Go Back")) {
            for (String line : it.lore()) {
                Matcher m = TO_LINE.matcher(line);
                if (m.matches()) {
                    return "< " + m.group(1);
                }
            }
            return "< Back";
        }
        return it.name();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static ItemStack stackAt(State st, int slot) {
        AbstractContainerMenu menu = st.screen.getMenu();
        return slot >= 0 && slot < menu.slots.size() ? menu.slots.get(slot).getItem() : ItemStack.EMPTY;
    }

    private static void slotHotspot(State st, int x, int y, int w, int h, BazaarPages.Item it, ItemStack stack) {
        st.hotspots.add(new Hotspot(x, y, w, h, it.slot(), stack.copy(), null, it.name()));
        hotspot(st, String.valueOf(it.slot()), x, y, w, h, it.name());
    }

    private static boolean isAction(BazaarPages.Item it) {
        return switch (it.name()) {
            case "Buy Instantly", "Sell Instantly", "Create Buy Order", "Create Sell Offer", "Inventory sold!" -> true;
            default -> false;
        };
    }

    private static boolean hasPrices(BazaarPages.Item it) {
        for (String line : it.lore()) {
            if (BUY_PRICE.matcher(line).matches() || SELL_PRICE.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The number a "Buy price: 4.8 coins" style lore line carries, or -1. */
    private static double loreValue(BazaarPages.Item it, Pattern p) {
        for (String line : it.lore()) {
            Matcher m = p.matcher(line);
            if (m.matches()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** A category page group's products: its "▶ Wheat" lore lines (wiki Bazaar/UI/Farming). */
    private static List<String> productNames(BazaarPages.Item it) {
        List<String> out = new ArrayList<>();
        for (String line : it.lore()) {
            Matcher m = PRODUCT_LINE.matcher(line);
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** The first lore line with text ("Common commodity", "7 products"), or "". */
    private static String subtitle(BazaarPages.Item it) {
        for (String line : it.lore()) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return "";
    }

    /**
     * The live product a menu item names. By NAME over the live list rather than through {@link BazaarCatalog#idForName},
     * because two names belong to two ids each (Enchanted Hay Bale: ENCHANTED_HAY_BALE and ENCHANTED_HAY_BLOCK; Enchanted
     * Carrot on a Stick likewise) and the catalog's pick need not be the one the API lists; of two live ones, the busier.
     */
    private static BazaarProduct liveProduct(String name) {
        if (name == null) {
            return null;
        }
        List<BazaarProduct> source = BazaarApi.getProducts();
        if (source != productsSource) {
            Map<String, BazaarProduct> m = new HashMap<>();
            for (BazaarProduct p : source) {
                String key = p.displayName().toLowerCase(Locale.ROOT);
                BazaarProduct old = m.get(key);
                if (old == null || p.weeklyVolume() > old.weeklyVolume()) {
                    m.put(key, p);
                }
            }
            productsByName = m;
            productsSource = source;
        }
        return productsByName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    private static FormattedCharSequence fitComponent(Component c, int width) {
        Font font = font();
        if (width <= 0) {
            return FormattedCharSequence.EMPTY;
        }
        if (font.width(c) <= width) {
            return c.getVisualOrderText();
        }
        List<FormattedCharSequence> lines = font.split(c, width);
        return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
    }

    private static void componentText(State st, GuiGraphicsExtractor g, boolean record, String region,
            FormattedCharSequence seq, int x, int y) {
        g.text(font(), seq, x, y, 0xFFFFFFFF, false);
        if (record) {
            int w = font().width(seq);
            if (w > 0) {
                st.layout.add("text " + region + " " + x + " " + y + " " + w + " 8 " + plain(seq));
            }
        }
    }

    private static String plain(FormattedCharSequence seq) {
        StringBuilder sb = new StringBuilder();
        seq.accept((index, style, cp) -> {
            sb.appendCodePoint(cp);
            return true;
        });
        return sb.toString();
    }

    private static void plainText(State st, GuiGraphicsExtractor g, boolean record, String region, String s, int x, int y,
            int color) {
        if (record) {
            text(st, g, region, s, x, y, color);
        } else if (!s.isEmpty()) {
            g.text(font(), s, x, y, color, false);
        }
    }

    private static void plainRight(State st, GuiGraphicsExtractor g, boolean record, String region, String s, int right,
            int y, int color) {
        plainText(st, g, record, region, s, right - font().width(s), y, color);
    }

    private static void text(State st, GuiGraphicsExtractor g, String region, String s, int x, int y, int color) {
        if (s.isEmpty()) {
            return;
        }
        g.text(font(), s, x, y, color, false);
        st.layout.add("text " + region + " " + x + " " + y + " " + font().width(s) + " 8 " + s);
    }

    private static void rightText(State st, GuiGraphicsExtractor g, String region, String s, int right, int y, int color) {
        text(st, g, region, s, right - font().width(s), y, color);
    }

    private static void region(State st, String name, int x, int y, int w, int h) {
        st.layout.add("region " + name + " " + x + " " + y + " " + w + " " + h);
    }

    private static void hotspot(State st, String slot, int x, int y, int w, int h, String label) {
        st.layout.add("hotspot " + slot + " " + x + " " + y + " " + w + " " + h + " " + label);
    }

    private static String fit(String s, int width) {
        Font font = font();
        if (width <= 0) {
            return "";
        }
        if (font.width(s) <= width) {
            return s;
        }
        String cut = font.plainSubstrByWidth(s, Math.max(0, width - font.width("...")));
        return cut.isEmpty() ? "" : cut + "...";
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /** The reskinned page's kind ("PRODUCT"...), "LOADING" before its items settle, or "NONE" when Hypixel's GUI shows. */
    public static String kindForTest() {
        State st = active;
        if (st == null || !hiding(st)) {
            return "NONE";
        }
        return st.page == null ? "LOADING" : st.page.kind().name();
    }

    /** Every region, text and hotspot of the last frame drawn ({@code hotspot <slot|action> x y w h <label>}). */
    public static List<String> layoutReportForTest() {
        State st = active;
        return st == null ? List.of() : List.copyOf(st.layout);
    }

    /** "clicksSent|last click" for the open reskin (testkit). */
    public static String clicksForTest() {
        State st = active;
        return st == null ? "0|" : st.clicksSent + "|" + st.lastClick;
    }
}
