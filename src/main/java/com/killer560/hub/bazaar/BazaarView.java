package com.killer560.hub.bazaar;

import com.killer560.hub.auction.AuctionConfig;
import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarCatalog;
import com.killer560.hub.auction.BazaarIcons;
import com.killer560.hub.auction.BazaarOrderLevel;
import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.auction.BoosterCookie;
import com.killer560.hub.util.ServerCommands;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Bazaar screen: one layout for browsing and for Hypixel's real menus (killer560, 2026-10-07: "make it your own and
 * a bit better").
 * <pre>
 *  ┌ Bazaar  ‹ Back   Farming › Wheat &amp; Seeds › Wheat                    2,201 products · 1m ago [Hypixel menu] ┐
 *  │ [Farming] [Mining] [Combat] [Woods &amp; Fishes] [Oddities]                          [ Search products...   ] │
 *  │ Recent        │ content: the product list (API), a product with its four trade buttons, a step of an order,   │
 *  │  Wheat        │          Manage Orders ...                                                                    │
 *  │  Enchanted... │                                                                                               │
 *  │ [Sell Inventory Now] [Sell Sacks Now] [Manage Orders]                              Hold Left Alt: Hypixel's menu │
 *  └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
 * </pre>
 * Browsing (tabs, search, groups, prices, volumes, margins) is drawn from the public Bazaar API and needs no menu. A
 * product is opened on Hypixel: with a Booster Cookie the screen sends {@code /bz <name>} (and {@link BazaarFollowUp}
 * may click the one matching result as the follow-up to his click); at the Bazaar NPC without one, each of his clicks
 * makes the ONE menu click that takes him a step closer (category, group, product). The product page and every step
 * after it are Hypixel's own menu items ({@link BazaarReskin}); a press on one is one click on its slot.
 * <p>
 * All state that should survive a page change (tab, search, scroll, the product being opened) is static, so a new
 * container screen draws exactly the frame the old one did. Every frame records its regions, texts and hotspots
 * ({@link #layoutReportForTest}) for the testkit's overlap checks.
 */
public final class BazaarView {

    /** What the content area shows. */
    enum Mode { BROWSE, PREVIEW, CONTAINER }

    static final List<String> CATEGORIES = List.of("Farming", "Mining", "Combat", "Woods & Fishes", "Oddities");
    static final List<String> ACTIONS = List.of("Buy Instantly", "Sell Instantly", "Create Buy Order", "Create Sell Offer");
    static final List<String> BOTTOM = List.of("Sell Inventory Now", "Sell Sacks Now", "Manage Orders");
    /** Bottom-row buttons with a place of their own (header, bottom bar, list head) or left out on purpose (Escape
     *  closes, the search box searches, the rest are behind the Hypixel Menu key). Any OTHER bottom-row item (Cancel
     *  Buy Order...) is part of the page and drawn with its content. */
    static final java.util.Set<String> PLACED_NAV = java.util.Set.of("Go Back", "Close", "Search", "Bazaar History",
            "Bazaar Settings", "View Graphs", "Direct Mode", "Advanced Mode", "Instasell Ignore", "Next Page",
            "Previous Page", "Sell Inventory Now", "Sell Sacks Now", "Manage Orders");

    private static final Pattern TO_LINE = Pattern.compile("^To (.{1,32})$");
    private static final Pattern BUY_PRICE = Pattern.compile("^Buy price: ([\\d,.]{1,20}) coins?$");
    private static final Pattern SELL_PRICE = Pattern.compile("^Sell price: ([\\d,.]{1,20}) coins?$");
    private static final Pattern PRODUCT_LINE = Pattern.compile("^▶ (.{1,40}?)(?: [\\d,.]{1,20} \\| [\\d,.]{1,20})?$");

    private static final int ROW_H = 20;
    private static final int GROUP_H = 14;
    private static final int RAIL_ROW_H = 18;
    private static final int MAX_QUERY = 40;

    // ---- session (survives page changes) --------------------------------------------------------------------------

    static Mode mode = Mode.BROWSE;
    static String tab = "Farming";
    static String query = "";
    static boolean searchFocused;
    static int listScroll;
    static int railScroll;
    /** The product PREVIEW shows (and the product page, once it arrives). */
    static String previewId;
    /** The product he is heading for: highlighted in lists and recents. */
    static String targetId;
    static String status = "";
    static long statusUntilMs;
    static long lastFrameMs;

    /** Set by a host that scales the view itself: where its tooltips go. */
    static float tooltipScale = 1.0f;

    private static BoosterCookie.State cookie = BoosterCookie.State.UNKNOWN;
    private static long cookieReadMs;

    // ---- the last frame -------------------------------------------------------------------------------------------

    private record Hot(int x, int y, int w, int h, String key, int slot, ItemStack stack, Runnable action, String label) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private static final List<Hot> HOTS = new ArrayList<>();
    private static final List<String> LAYOUT = new ArrayList<>();
    private static String summary = "";
    private static int contentItems;
    private static int searchX, searchY, searchW, searchH;
    private static int railX, railY, railW, railH;
    private static int contentX, contentY, contentW, contentH;

    // per frame
    private static GuiGraphicsExtractor g;
    private static Font font;
    private static BazaarTheme t;
    private static int mouseX, mouseY;
    private static ItemStack hoveredStack;
    private static List<Component> hoveredLines;

    private BazaarView() {
    }

    static boolean sessionLive(long withinMs) {
        return System.currentTimeMillis() - lastFrameMs < withinMs;
    }

    /** A Bazaar visit starts (nothing Bazaar was on screen for a while): back to browsing. */
    static void freshSession() {
        mode = Mode.BROWSE;
        searchFocused = false;
        listScroll = 0;
        previewId = null;
        targetId = null;
        status = "";
        String saved = categoryOf(AuctionConfig.getInstance().getLastBazaarCategoryFilter());
        tab = saved != null && CATEGORIES.contains(saved) ? saved : tab;
        BazaarFollowUp.disarm("new visit");
    }

    private static String categoryOf(String sel) {
        if (sel == null || sel.isEmpty() || sel.startsWith("@")) {
            return null;
        }
        int slash = sel.indexOf('/');
        return slash < 0 ? sel : sel.substring(0, slash);
    }

    private static void say(String s) {
        status = s;
        statusUntilMs = System.currentTimeMillis() + 6000L;
    }

    private static BoosterCookie.State cookie() {
        long now = System.currentTimeMillis();
        if (now - cookieReadMs > 1000L) {
            cookie = BoosterCookie.state(Minecraft.getInstance());
            cookieReadMs = now;
        }
        return cookie;
    }

    /** A new Hypixel page is on screen (called once per page by the reskin). */
    static void onPageShown(BazaarPages.Page page, BazaarReskin.State st) {
        switch (page.kind()) {
            case CATEGORY -> {
                if (mode != Mode.BROWSE) {
                    mode = Mode.BROWSE;
                    listScroll = 0;
                }
                String cat = page.heading();
                if (targetId == null && query.isEmpty() && CATEGORIES.contains(cat)) {
                    tab = cat;
                }
            }
            case PRODUCT -> {
                mode = Mode.CONTAINER;
                listScroll = 0;
                BazaarPages.Item product = productItem(page);
                BazaarProduct p = product == null ? null : BazaarFormat.byName(product.name());
                String id = p != null ? p.productId() : product == null ? null : BazaarCatalog.idForName(product.name());
                if (id != null) {
                    previewId = id;
                    targetId = id;
                    BazaarRecents.add(id);
                }
            }
            default -> {
                mode = Mode.CONTAINER;
                listScroll = 0;
            }
        }
    }

    // ---- layout ---------------------------------------------------------------------------------------------------

    /**
     * Draws one frame. {@code cp} is the menu whose items the content shows (null: none), {@code frozen} says the
     * frame is a held one (the open menu is not read yet) - drawn the same, but nothing on it answers a press.
     */
    static void draw(GuiGraphicsExtractor graphics, int width, int height, int mx, int my, BazaarReskin.State cp,
            boolean frozen, String host) {
        g = graphics;
        font = Minecraft.getInstance().font;
        t = BazaarTheme.current();
        mouseX = frozen ? -1 : mx;
        mouseY = frozen ? -1 : my;
        hoveredStack = null;
        hoveredLines = null;
        HOTS.clear();
        LAYOUT.clear();
        contentItems = 0;
        lastFrameMs = System.currentTimeMillis();
        if (!status.isEmpty() && lastFrameMs > statusUntilMs) {
            status = "";
        }

        g.fill(0, 0, width, height, t.backdrop());
        int m = height < 300 ? 4 : 8;
        int panelW = Math.min(width - 2 * m, 760);
        int panelH = height - 2 * m;
        int px = (width - panelW) / 2;
        int py = m;
        int pad = 6;
        g.fill(px, py, px + panelW, py + panelH, t.panelBg());
        g.outline(px, py, panelW, panelH, t.border());
        region("panel", px, py, panelW, panelH);

        int headerY = py + pad;
        int tabsY = headerY + 16;
        int tabsH = 18;
        int bottomH = 20;
        int bottomY = py + panelH - pad - bottomH;
        int bodyY = tabsY + tabsH + 5;
        int bodyBottom = bottomY - 5;

        Mode shown = effectiveMode(cp);
        drawHeader(px + pad, headerY, panelW - 2 * pad, cp, shown);
        drawTabs(px + pad, tabsY, panelW - 2 * pad, tabsH, shown);

        railW = panelW >= 400 ? Math.max(100, Math.min(150, panelW / 5)) : 0;
        railX = px + pad;
        railY = bodyY;
        railH = bodyBottom - bodyY;
        if (railW > 0) {
            drawRail();
        }
        contentX = railW > 0 ? railX + railW + 5 : px + pad;
        contentY = bodyY;
        contentW = px + panelW - pad - contentX;
        contentH = bodyBottom - bodyY;
        g.fill(contentX, contentY, contentX + contentW, contentY + contentH, t.listBg());
        g.outline(contentX, contentY, contentW, contentH, t.border());
        region("content", contentX, contentY, contentW, contentH);
        String content = drawContent(cp, shown);
        drawBottom(px + pad, bottomY, panelW - 2 * pad, bottomH, cp);

        summary = shown + " " + content + (frozen ? " frozen" : "") + " items=" + contentItems;
        BazaarHud.recordFrame(host + " " + summary);
        if (!frozen) {
            // The tooltip is drawn after the host's pose is popped (the reskin scales its own drawing), so it is placed
            // in the host's unscaled coordinates.
            int tx = Math.round(mx * tooltipScale);
            int ty = Math.round(my * tooltipScale);
            if (hoveredStack != null && !hoveredStack.isEmpty()) {
                // The real item's own tooltip, every lore line Hypixel sent: nothing on the menu is hidden from him.
                g.setTooltipForNextFrame(font, hoveredStack, tx, ty);
            } else if (hoveredLines != null) {
                g.setTooltipForNextFrame(font, hoveredLines, Optional.empty(), tx, ty);
            }
        }
        g = null;
    }

    private static Mode effectiveMode(BazaarReskin.State cp) {
        if (mode == Mode.CONTAINER && (cp == null || cp.page == null || cp.page.kind() == BazaarPages.Kind.CATEGORY)) {
            return Mode.BROWSE;
        }
        if (mode == Mode.PREVIEW && previewId == null) {
            return Mode.BROWSE;
        }
        return mode;
    }

    // Header -------------------------------------------------------------------------------------------------------

    private static void drawHeader(int x, int y, int w, BazaarReskin.State cp, Mode shown) {
        region("header", x, y - 1, w, 13);
        String brand = "§lBazaar";
        text("header", brand, x, y + 2, t.accentBright());
        int bx = x + font.width(brand) + 8;

        // Back: Hypixel's own Go Back on a menu page; out of a preview, back to the list.
        // A product page has two ("To Wheat & Seeds", "To Bazaar"): one button each, nearest first.
        List<BazaarPages.Item> backs = new ArrayList<>();
        if (shown == Mode.CONTAINER && cp != null) {
            for (BazaarPages.Item it : cp.page.nav()) {
                if (it.name().equals("Go Back") && backs.size() < 2) {
                    backs.add(it);
                }
            }
        }
        if (backs.isEmpty() && (shown == Mode.PREVIEW || shown == Mode.CONTAINER)) {
            backs.add(null); // no Hypixel button: back to the list, locally
        }
        // Together never more than a third of the header, so the breadcrumb keeps its room on a small window.
        int labelMax = Math.max(30, Math.min(100, w / 3 / Math.max(1, backs.size()) - 14));
        for (BazaarPages.Item goBack : backs) {
            String label = fit(goBack != null ? backLabel(goBack) : "‹ Back", labelMax);
            int bw = font.width(label) + 10;
            boolean hover = in(bx, y, bw, 12);
            g.fill(bx, y, bx + bw, y + 12, hover ? t.controlHover() : t.control());
            g.outline(bx, y, bw, 12, hover ? t.accent() : t.border());
            text("header", label, bx + 5, y + 2, hover ? t.accentBright() : t.text());
            if (goBack != null) {
                slotHot("back", bx, y, bw, 12, goBack, cp.stackAt(goBack.slot()));
            } else {
                actionHot("back", bx, y, bw, 12, "Back", () -> {
                    mode = Mode.BROWSE;
                    listScroll = 0;
                });
            }
            bx += bw + 4;
        }
        bx += backs.isEmpty() ? 0 : 2;

        int right = x + w;
        if (cp != null) {
            String label = "Hypixel menu";
            int bw = font.width(label) + 10;
            int bxr = right - bw;
            boolean hover = in(bxr, y, bw, 12);
            g.fill(bxr, y, bxr + bw, y + 12, hover ? t.controlHover() : t.control());
            g.outline(bxr, y, bw, 12, hover ? t.accent() : t.border());
            text("header", label, bxr + 5, y + 2, hover ? t.accentBright() : t.dim());
            BazaarReskin.State host = cp;
            actionHot("hypixel-menu", bxr, y, bw, 12, label, () -> BazaarReskin.showHypixelMenu(host));
            right = bxr - 8;
        }
        String info = !status.isEmpty() ? status : infoLine();
        int infoColor = !status.isEmpty() ? t.accentBright() : t.faint();
        String crumb = breadcrumb(cp, shown);
        int crumbW = font.width(crumb);
        int infoMax = Math.max(0, right - bx - Math.min(crumbW, 120) - 12);
        String infoFit = fit(info, infoMax);
        int infoW = font.width(infoFit);
        if (infoW > 0) {
            text("header", infoFit, right - infoW, y + 2, infoColor);
        }
        text("header", fit(crumb, right - infoW - 10 - bx), bx, y + 2, t.text());
    }

    private static String infoLine() {
        int n = BazaarApi.getProducts().size();
        if (n == 0) {
            return BazaarApi.isRefreshing() ? "Fetching prices..." : "No price data yet";
        }
        String base = String.format(Locale.US, "%,d products", n);
        if (BazaarApi.isRefreshing()) {
            return base + " · refreshing...";
        }
        long fetched = BazaarApi.getLastFetchedMs();
        return fetched == 0 ? base : base + " · " + BazaarFormat.ago(fetched);
    }

    private static String breadcrumb(BazaarReskin.State cp, Mode shown) {
        return switch (shown) {
            case BROWSE -> query.isBlank() ? tab : "Search: \"" + query.trim() + "\"";
            case PREVIEW -> {
                BazaarProduct p = BazaarFormat.byId(previewId);
                yield p == null ? BazaarFormat.displayName(previewId) : p.category() + " › " + p.group() + " › " + p.displayName();
            }
            case CONTAINER -> {
                if (cp.page.kind() == BazaarPages.Kind.PRODUCT) {
                    BazaarPages.Item it = productItem(cp.page);
                    BazaarProduct p = it == null ? null : BazaarFormat.byName(it.name());
                    yield p != null ? p.category() + " › " + p.group() + " › " + p.displayName() : cp.page.heading();
                }
                yield cp.page.heading();
            }
        };
    }

    // Tabs and search ----------------------------------------------------------------------------------------------

    private static ItemStack categoryIcon(String cat) {
        return new ItemStack(switch (cat) {
            case "Mining" -> Items.DIAMOND_PICKAXE;
            case "Combat" -> Items.IRON_SWORD;
            case "Woods & Fishes" -> Items.FISHING_ROD;
            case "Oddities" -> Items.ENCHANTING_TABLE;
            default -> Items.GOLDEN_HOE;
        });
    }

    private static void drawTabs(int x, int y, int w, int h, Mode shown) {
        region("tabs", x, y, w, h);
        searchW = Math.max(80, Math.min(190, w * 30 / 100));
        searchH = h;
        searchX = x + w - searchW;
        searchY = y;
        int avail = searchX - 6 - x;
        int gap = 3;
        int[] widths = new int[CATEGORIES.size()];
        int total = gap * (CATEGORIES.size() - 1);
        boolean icons = true;
        for (int i = 0; i < CATEGORIES.size(); i++) {
            widths[i] = 20 + font.width(CATEGORIES.get(i)) + 8;
            total += widths[i];
        }
        if (total > avail) {
            icons = false;
            total = gap * (CATEGORIES.size() - 1);
            for (int i = 0; i < CATEGORIES.size(); i++) {
                widths[i] = font.width(CATEGORIES.get(i)) + 10;
                total += widths[i];
            }
        }
        if (total > avail) {
            int each = (avail - gap * (CATEGORIES.size() - 1)) / CATEGORIES.size();
            java.util.Arrays.fill(widths, Math.max(12, each));
        }
        boolean active = shown == Mode.BROWSE && query.isBlank();
        int tx = x;
        for (int i = 0; i < CATEGORIES.size(); i++) {
            String cat = CATEGORIES.get(i);
            int tw = widths[i];
            boolean sel = cat.equals(tab);
            boolean hover = in(tx, y, tw, h);
            int bg = sel ? (active ? t.selectedBg() : t.controlHover()) : hover ? t.controlHover() : t.control();
            g.fill(tx, y, tx + tw, y + h, bg);
            g.outline(tx, y, tw, h, sel && active ? t.accent() : hover ? t.accent() : t.border());
            if (sel && active) {
                g.fill(tx + 1, y + h - 2, tx + tw - 1, y + h - 1, t.accent());
            }
            int lx = tx + 5;
            if (icons) {
                g.item(categoryIcon(cat), tx + 2, y + 1);
                lx = tx + 20;
            }
            String label = fit(cat, tx + tw - 4 - lx);
            text("tabs", label, lx, y + 5, sel ? t.accentBright() : t.text());
            actionHot("tab:" + cat, tx, y, tw, h, cat, () -> selectTab(cat));
            tx += tw + gap;
        }
        drawSearch();
    }

    private static void selectTab(String cat) {
        tab = cat;
        query = "";
        searchFocused = false;
        mode = Mode.BROWSE;
        listScroll = 0;
        AuctionConfig cfg = AuctionConfig.getInstance();
        cfg.setLastBazaarCategoryFilter(cat);
        cfg.save();
    }

    private static void drawSearch() {
        boolean hover = in(searchX, searchY, searchW, searchH);
        g.fill(searchX, searchY, searchX + searchW, searchY + searchH, t.listBg());
        g.outline(searchX, searchY, searchW, searchH, searchFocused ? t.accent() : hover ? t.accent() : t.border());
        LAYOUT.add("widget search " + searchX + " " + searchY + " " + searchW + " " + searchH);
        int tx = searchX + 5;
        int room = searchW - 10;
        if (query.isEmpty() && !searchFocused) {
            g.text(font, fit("Search products...", room), tx, searchY + 5, t.faint(), false);
        } else {
            String shownText = query;
            while (font.width(shownText) > room - 4 && !shownText.isEmpty()) {
                shownText = shownText.substring(1); // keep the end (the caret) in view
            }
            g.text(font, shownText, tx, searchY + 5, t.text(), false);
            if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
                int cx = tx + font.width(shownText) + 1;
                g.fill(cx, searchY + 4, cx + 1, searchY + 13, t.text());
            }
        }
        actionHot("search", searchX, searchY, searchW, searchH, "Search", () -> searchFocused = true);
    }

    // Recents rail -------------------------------------------------------------------------------------------------

    private static void drawRail() {
        g.fill(railX, railY, railX + railW, railY + railH, t.railBg());
        g.outline(railX, railY, railW, railH, t.border());
        region("rail", railX, railY, railW, railH);
        text("rail", "Recent", railX + 6, railY + 5, t.dim());
        int top = railY + 16;
        int bottom = railY + railH - 3;
        g.fill(railX + 4, top - 2, railX + railW - 4, top - 1, t.border());
        List<String> ids = BazaarRecents.list();
        if (ids.isEmpty()) {
            String[] help = {"Products you open", "show up here."};
            int y = top + 4;
            for (String line : help) {
                if (y + 9 <= bottom) {
                    text("rail", fit(line, railW - 12), railX + 6, y, t.faint());
                }
                y += 10;
            }
            return;
        }
        int contentH = ids.size() * RAIL_ROW_H;
        int maxScroll = Math.max(0, contentH - (bottom - top));
        railScroll = Math.max(0, Math.min(railScroll, maxScroll));
        g.enableScissor(railX + 1, top, railX + railW - 1, bottom);
        try {
            for (int i = 0; i < ids.size(); i++) {
                int ry = top + i * RAIL_ROW_H - railScroll;
                if (ry + RAIL_ROW_H <= top) {
                    continue;
                }
                if (ry >= bottom) {
                    break;
                }
                String id = ids.get(i);
                boolean whole = ry >= top && ry + RAIL_ROW_H <= bottom;
                boolean sel = id.equals(targetId);
                boolean hover = in(railX, Math.max(ry, top), railW, Math.min(ry + RAIL_ROW_H, bottom) - Math.max(ry, top));
                if (sel) {
                    g.fill(railX + 1, ry, railX + railW - 1, ry + RAIL_ROW_H, t.selectedBg());
                    g.fill(railX + 1, ry, railX + 3, ry + RAIL_ROW_H, t.accent());
                } else if (hover) {
                    g.fill(railX + 1, ry, railX + railW - 1, ry + RAIL_ROW_H, t.rowHover());
                }
                g.item(BazaarIcons.icon(id), railX + 4, ry + 1);
                BazaarProduct p = BazaarFormat.byId(id);
                String name = fit(BazaarFormat.displayName(id), railW - 28);
                int color = p != null ? t.on(BazaarFormat.nameColor(p)) : t.text();
                maybeText(whole, "rail", name, railX + 23, ry + 5, color);
                int hy = Math.max(ry, top);
                int hh = Math.min(ry + RAIL_ROW_H, bottom) - hy;
                if (hh > 0) {
                    actionHot("recent:" + id, railX, hy, railW, hh, BazaarFormat.displayName(id), () -> openProduct(id));
                }
                if (hover && p != null) {
                    hoveredLines = productTooltip(p);
                }
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            scrollbar(railX + railW - 3, top, bottom - top, railScroll, maxScroll, contentH);
        }
    }

    // Bottom bar ---------------------------------------------------------------------------------------------------

    private static void drawBottom(int x, int y, int w, int h, BazaarReskin.State cp) {
        region("bottom", x, y, w, h);
        int gap = 4;
        int bw = Math.min(150, (w - 2 * gap) / 3);
        boolean live = cp != null && cp.page != null;
        boolean narrow = bw < 24 + font.width("Sell Inventory Now");
        int bx = x;
        for (String name : BOTTOM) {
            BazaarPages.Item item = live ? anyItem(cp.page, name) : null;
            boolean canCommand = cp == null || cookie() == BoosterCookie.State.ACTIVE;
            boolean available = item != null || canCommand;
            boolean hover = in(bx, y, bw, h);
            g.fill(bx, y, bx + bw, y + h, hover && available ? t.controlHover() : t.control());
            g.outline(bx, y, bw, h, hover && available ? t.accent() : t.border());
            ItemStack icon = item != null ? cp.stackAt(item.slot()) : new ItemStack(switch (name) {
                case "Sell Inventory Now" -> Items.CHEST;
                case "Sell Sacks Now" -> Items.CAULDRON;
                default -> Items.BOOK;
            });
            g.item(icon, bx + 2, y + 2);
            String label = narrow ? fit(name.replace(" Now", ""), bw - 24) : name;
            text("bottom", fit(label, bw - 24), bx + 20, y + 6, !available ? t.faint()
                    : hover ? t.accentBright() : t.text());
            if (item != null) {
                slotHot("bottom:" + name, bx, y, bw, h, item, icon);
                if (hover) {
                    hoveredStack = icon;
                }
            } else {
                actionHot("bottom:" + name, bx, y, bw, h, name, () -> bottomAction(name, cp));
                if (hover) {
                    hoveredLines = List.of(Component.literal(name), Component.literal(available
                            ? "§7Opens Hypixel's Bazaar and presses " + name + "." : "§7Go back to the Bazaar's main"
                            + " page to use this (or use a Booster Cookie)."));
                }
            }
            bx += bw + gap;
        }
        String hint = cp != null ? vanillaHint() : "";
        int hw = font.width(hint);
        if (!hint.isEmpty() && x + w - hw > bx + 6) {
            text("bottom", hint, x + w - hw, y + 6, t.faint());
        }
    }

    /** A bottom-bar button whose real button is not on the open page (or no page is open). */
    private static void bottomAction(String name, BazaarReskin.State cp) {
        if (cookie() == BoosterCookie.State.ACTIVE || cp == null) {
            BazaarFollowUp.armButton(name);
            ServerCommands.toServer("bz");
            say(BazaarConfig.getInstance().isFollowUpClick() ? "Opening " + name + "..."
                    : "Opening the Bazaar - press " + name + " there");
        } else {
            say("Go back to the Bazaar's main page for " + name);
        }
    }

    static void bottomActionFromSettings(String name) {
        BazaarFollowUp.armButton(name);
        ServerCommands.toServer("bz");
        say("Opening " + name + "...");
    }

    // Content ------------------------------------------------------------------------------------------------------

    private static String drawContent(BazaarReskin.State cp, Mode shown) {
        int x = contentX;
        int y = contentY;
        int w = contentW;
        int h = contentH;
        switch (shown) {
            case PREVIEW -> {
                drawProduct(null, previewId, x, y, w, h);
                return "PREVIEW " + previewId;
            }
            case CONTAINER -> {
                BazaarPages.Page page = cp.page;
                switch (page.kind()) {
                    case PRODUCT -> {
                        BazaarPages.Item it = productItem(page);
                        BazaarProduct p = it == null ? null : BazaarFormat.byName(it.name());
                        drawProduct(cp, p != null ? p.productId() : null, x, y, w, h);
                    }
                    case GROUP, SEARCH, ORDERS -> drawMenuList(cp, x, y, w, h);
                    default -> drawCards(cp, withExtraNav(page), x, y + 6, w, h - 6);
                }
                return "CONTAINER " + page.kind();
            }
            default -> {
                drawBrowse(x, y, w, h);
                return "BROWSE " + (query.isBlank() ? tab : "search");
            }
        }
    }

    // Browse (API) -------------------------------------------------------------------------------------------------

    private record Row(String group, int count, BazaarProduct product) {
    }

    private static List<BazaarProduct> rowsSource;
    private static String rowsKey = "";
    private static List<Row> rows = List.of();

    private static List<Row> browseRows() {
        List<BazaarProduct> source = BazaarApi.getProducts();
        AuctionConfig.BazaarSortMode sort = AuctionConfig.getInstance().getLastBazaarSort();
        String key = tab + "|" + query.trim().toLowerCase(Locale.ROOT) + "|" + sort;
        if (source == rowsSource && key.equals(rowsKey)) {
            return rows;
        }
        rowsSource = source;
        rowsKey = key;
        Comparator<BazaarProduct> cmp = comparator(sort);
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Row> out = new ArrayList<>();
        if (!q.isEmpty()) {
            List<BazaarProduct> hits = new ArrayList<>();
            for (BazaarProduct p : source) {
                if (!shown(p)) {
                    continue;
                }
                if (p.displayName().toLowerCase(Locale.ROOT).contains(q) || p.productId().toLowerCase(Locale.ROOT).contains(q)) {
                    hits.add(p);
                }
            }
            // Names that START with the search first, then the rest, each in the chosen order.
            hits.sort(Comparator.<BazaarProduct, Boolean>comparing(p -> !p.displayName().toLowerCase(Locale.ROOT)
                    .startsWith(q)).thenComparing(cmp));
            for (BazaarProduct p : hits) {
                out.add(new Row(null, 0, p));
            }
        } else {
            Map<String, List<BazaarProduct>> byGroup = new LinkedHashMap<>();
            if (BazaarCatalog.isLoaded()) {
                for (String grp : BazaarCatalog.groupsOf(tab).values()) {
                    byGroup.put(grp, new ArrayList<>());
                }
            }
            for (BazaarProduct p : source) {
                if (tab.equals(p.category()) && shown(p)) {
                    byGroup.computeIfAbsent(p.group() == null || p.group().isBlank() ? "Other" : p.group(),
                            k -> new ArrayList<>()).add(p);
                }
            }
            for (Map.Entry<String, List<BazaarProduct>> e : byGroup.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                e.getValue().sort(cmp);
                out.add(new Row(e.getKey(), e.getValue().size(), null));
                for (BazaarProduct p : e.getValue()) {
                    out.add(new Row(null, 0, p));
                }
            }
        }
        rows = out;
        return rows;
    }

    /**
     * Two names belong to two ids each (Enchanted Hay Bale: ENCHANTED_HAY_BALE and the dead ENCHANTED_HAY_BLOCK; Enchanted
     * Carrot on a Stick likewise). Hypixel's menu lists the name once, so the list keeps only the busier of the two.
     */
    private static boolean shown(BazaarProduct p) {
        BazaarProduct same = BazaarFormat.byName(p.displayName());
        return same == null || same == p;
    }

    private static Comparator<BazaarProduct> comparator(AuctionConfig.BazaarSortMode mode) {
        return switch (mode) {
            case BUY_PRICE_HIGH -> Comparator.comparingDouble(BazaarProduct::buyPrice).reversed();
            case SELL_PRICE_HIGH -> Comparator.comparingDouble(BazaarProduct::sellPrice).reversed();
            case SPREAD_HIGH -> Comparator.comparingDouble(BazaarProduct::marginPercent).reversed();
            case VOLUME_HIGH -> Comparator.comparingLong(BazaarProduct::weeklyVolume).reversed();
        };
    }

    private record Columns(int nameRight, int buyR, int sellR, int marginR, int volR, boolean margin, boolean vol) {
    }

    private static Columns columns(int x, int w) {
        int colW = Math.max(font.width("Margin") + 8, font.width("999.9M") + 10);
        int right = x + w - 8;
        boolean vol = w - 160 - colW * 4 >= 0;
        boolean margin = w - 140 - colW * (vol ? 4 : 3) >= 0;
        int volR = right;
        int marginR = vol ? volR - colW : right;
        int sellR = margin ? marginR - colW : marginR;
        int buyR = sellR - colW;
        return new Columns(buyR - colW - 4, buyR, sellR, marginR, volR, margin, vol);
    }

    private static void drawBrowse(int x, int y, int w, int h) {
        Columns c = columns(x, w);
        int headY = y + 5;
        region("list-head", x + 1, headY - 1, w - 2, 10);
        List<Row> list = browseRows();
        long products = list.stream().filter(r -> r.product() != null).count();
        String head = query.isBlank() ? tab + " · " + products + " products"
                : products + (products == 1 ? " result" : " results");
        text("list-head", fit(head, c.nameRight() - x - 8), x + 6, headY, t.dim());
        AuctionConfig.BazaarSortMode sort = AuctionConfig.getInstance().getLastBazaarSort();
        sortHead("Buy", c.buyR(), headY, AuctionConfig.BazaarSortMode.BUY_PRICE_HIGH, sort);
        sortHead("Sell", c.sellR(), headY, AuctionConfig.BazaarSortMode.SELL_PRICE_HIGH, sort);
        if (c.margin()) {
            sortHead("Margin", c.marginR(), headY, AuctionConfig.BazaarSortMode.SPREAD_HIGH, sort);
        }
        if (c.vol()) {
            sortHead("7d vol", c.volR(), headY, AuctionConfig.BazaarSortMode.VOLUME_HIGH, sort);
        }
        int top = headY + 12;
        g.fill(x + 4, top - 2, x + w - 4, top - 1, t.border());
        int bottom = y + h - 2;
        region("list", x + 1, top, w - 2, bottom - top);
        if (list.isEmpty()) {
            String msg = BazaarApi.getProducts().isEmpty()
                    ? (BazaarApi.isRefreshing() ? "Fetching Bazaar prices..." : "No price data yet - it refreshes on its own.")
                    : query.isBlank() ? "No products in " + tab + "." : "Nothing matches \"" + query.trim() + "\".";
            text("list", fit(msg, w - 16), x + 8, top + 6, t.dim());
            contentItems++;
            return;
        }
        int contentHeight = 0;
        for (Row r : list) {
            contentHeight += r.product() == null ? GROUP_H : ROW_H;
        }
        int maxScroll = Math.max(0, contentHeight - (bottom - top));
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));
        g.enableScissor(x + 1, top, x + w - 1, bottom);
        try {
            int ry = top - listScroll;
            int alt = 0;
            for (Row r : list) {
                int rh = r.product() == null ? GROUP_H : ROW_H;
                int rowY = ry;
                ry += rh;
                if (rowY + rh <= top) {
                    continue;
                }
                if (rowY >= bottom) {
                    break;
                }
                boolean whole = rowY >= top && rowY + rh <= bottom;
                if (r.product() == null) {
                    alt = 0;
                    maybeText(whole, "list", fit(r.group(), w - 60), x + 6, rowY + 4, t.accentBright());
                    String n = String.valueOf(r.count());
                    maybeText(whole, "list", n, x + w - 8 - font.width(n), rowY + 4, t.faint());
                    g.fill(x + 6 + Math.min(font.width(r.group()), w - 60) + 6, rowY + 8,
                            x + w - 14 - font.width(n), rowY + 9, t.border());
                    continue;
                }
                BazaarProduct p = r.product();
                boolean sel = p.productId().equals(targetId);
                int hy = Math.max(rowY, top);
                int hh = Math.min(rowY + rh, bottom) - hy;
                boolean hover = in(x, hy, w - 6, hh);
                if (sel) {
                    g.fill(x + 1, rowY, x + w - 1, rowY + rh, t.selectedBg());
                    g.fill(x + 1, rowY, x + 3, rowY + rh, t.accent());
                } else if (hover) {
                    g.fill(x + 1, rowY, x + w - 1, rowY + rh, t.rowHover());
                } else if (alt % 2 == 1) {
                    g.fill(x + 1, rowY, x + w - 1, rowY + rh, t.rowAlt());
                }
                alt++;
                productRow(p, x, rowY, c, whole, !query.isBlank());
                if (hh > 0) {
                    actionHot("product:" + p.productId(), x, hy, w - 6, hh, p.displayName(),
                            () -> openProduct(p.productId()));
                }
                if (hover) {
                    hoveredLines = productTooltip(p);
                }
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            scrollbar(x + w - 4, top, bottom - top, listScroll, maxScroll, contentHeight);
        }
    }

    private static void sortHead(String label, int right, int y, AuctionConfig.BazaarSortMode mode,
            AuctionConfig.BazaarSortMode current) {
        boolean on = mode == current;
        String s = on ? label + " ▾" : label;
        int sw = font.width(s);
        boolean hover = in(right - sw - 2, y - 2, sw + 4, 12);
        text("list-head", s, right - sw, y, on ? t.accentBright() : hover ? t.text() : t.faint());
        actionHot("sort:" + mode.name(), right - sw - 2, y - 1, sw + 4, 10, label, () -> {
            AuctionConfig cfg = AuctionConfig.getInstance();
            cfg.setLastBazaarSort(mode);
            cfg.save();
            listScroll = 0;
        });
    }

    private static void productRow(BazaarProduct p, int x, int y, Columns c, boolean record, boolean withGroup) {
        g.item(BazaarIcons.icon(p.productId()), x + 4, y + 2);
        contentItems++;
        int tx = x + 24;
        int nameW = c.nameRight() - tx;
        int ny = withGroup ? y + 2 : y + 6;
        maybeText(record, "list", fit(p.displayName(), nameW), tx, ny, t.on(BazaarFormat.nameColor(p)));
        if (withGroup) {
            maybeText(record, "list", fit(p.category() + " › " + p.group(), nameW), tx, y + 11, t.faint());
        }
        rightText(record, "list", BazaarFormat.price(p.buyPrice()), c.buyR(), y + 6, t.gold());
        rightText(record, "list", BazaarFormat.price(p.sellPrice()), c.sellR(), y + 6, t.gold());
        if (c.margin()) {
            rightText(record, "list", BazaarFormat.percent(p.marginPercent()), c.marginR(), y + 6,
                    p.spread() >= 0 ? t.green() : t.red());
        }
        if (c.vol()) {
            rightText(record, "list", BazaarFormat.shortNumber(p.weeklyVolume()), c.volR(), y + 6, t.dim());
        }
    }

    private static List<Component> productTooltip(BazaarProduct p) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(p.displayName()));
        lines.add(Component.literal("§7" + p.category() + " › " + p.group()));
        lines.add(Component.literal("§7Instant buy: §6" + BazaarFormat.price(p.buyPrice()) + " coins"));
        lines.add(Component.literal("§7Instant sell: §6" + BazaarFormat.price(p.sellPrice()) + " coins"));
        lines.add(Component.literal("§7Margin: " + (p.spread() >= 0 ? "§a" : "§c") + BazaarFormat.percent(p.marginPercent())));
        lines.add(Component.literal("§7Traded in 7 days: §f" + BazaarFormat.shortNumber(p.weeklyVolume())));
        lines.add(Component.literal("§eClick to open on Hypixel"));
        return lines;
    }

    // Opening a product --------------------------------------------------------------------------------------------

    /**
     * His click on a product (list, recents): open it on Hypixel. A product row on the open menu is clicked (one
     * click); with a Booster Cookie (or from the menu-less screen) {@code /bz <name>} is sent and the follow-up armed;
     * at the NPC without a Cookie the one menu click that leads towards it is made, and the next click goes on.
     */
    static void openProduct(String productId) {
        if (productId == null) {
            return;
        }
        BazaarRecents.add(productId);
        targetId = productId;
        String name = BazaarFormat.displayName(productId);
        BazaarReskin.State st = BazaarReskin.liveState();
        if (st != null) {
            BazaarPages.Page page = st.page;
            if (page.kind() == BazaarPages.Kind.GROUP || page.kind() == BazaarPages.Kind.SEARCH) {
                BazaarPages.Item row = named(page.content(), name);
                if (row != null && clickItem(st, row)) {
                    previewId = productId;
                    mode = Mode.PREVIEW;
                    return;
                }
            }
        }
        if (st == null || cookie() == BoosterCookie.State.ACTIVE) {
            previewId = productId;
            mode = Mode.PREVIEW;
            listScroll = 0;
            BazaarFollowUp.armProduct(name, productId);
            ServerCommands.toServer("bz " + name);
            say("Opening " + name + " on Hypixel...");
            return;
        }
        // At the NPC without a Booster Cookie: one menu click per click of his, each a step towards the product.
        BazaarProduct p = BazaarFormat.byId(productId);
        BazaarPages.Page page = st.page;
        if (page.kind() == BazaarPages.Kind.CATEGORY && p != null) {
            if (p.category().equals(page.heading())) {
                BazaarPages.Item group = named(page.content(), p.group());
                if (group == null) {
                    group = groupListing(page, name);
                }
                if (group != null && clickItem(st, group)) {
                    say("Opening " + group.name() + " - " + name + " is highlighted there");
                    return;
                }
            } else {
                BazaarPages.Item cat = named(page.sidebar(), p.category());
                if (cat != null && clickItem(st, cat)) {
                    tab = p.category();
                    say("Opening " + p.category() + " - click " + name + " again to go on");
                    return;
                }
            }
            say("Couldn't find " + name + " on this page of Hypixel's menu");
            return;
        }
        BazaarPages.Item back = navItem(page, "Go Back");
        if (back != null && clickItem(st, back)) {
            say("Going back - click " + name + " again to go on");
            return;
        }
        say("Without a Booster Cookie, open " + name + " from the Bazaar's main page");
    }

    private static boolean clickItem(BazaarReskin.State st, BazaarPages.Item it) {
        return BazaarReskin.sendClick(st, it.slot(), st.stackAt(it.slot()).copy(), 0, false, it.name());
    }

    private static BazaarPages.Item named(List<BazaarPages.Item> items, String name) {
        if (name == null) {
            return null;
        }
        BazaarPages.Item found = null;
        for (BazaarPages.Item it : items) {
            if (it.name().equalsIgnoreCase(name.trim())) {
                if (found != null) {
                    return null; // two of them: not sure which he means
                }
                found = it;
            }
        }
        return found;
    }

    /** The category page's group whose "▶ Name" lines list the product. */
    private static BazaarPages.Item groupListing(BazaarPages.Page page, String name) {
        for (BazaarPages.Item it : page.content()) {
            for (String line : it.lore()) {
                Matcher m = PRODUCT_LINE.matcher(line);
                if (m.matches() && m.group(1).equalsIgnoreCase(name)) {
                    return it;
                }
            }
        }
        return null;
    }

    // Product (preview and Hypixel's product page) -----------------------------------------------------------------

    static BazaarPages.Item productItem(BazaarPages.Page page) {
        for (BazaarPages.Item it : page.content()) {
            if (!BazaarPages.isAction(it)) {
                return it;
            }
        }
        return null;
    }

    /**
     * One product: a header from the live data, then the four trade buttons. From the menu-less screen (preview) the
     * buttons are placeholders in exactly the places Hypixel's own will be drawn when its product page arrives, so the
     * switch is the cards filling in, never a jump.
     */
    private static void drawProduct(BazaarReskin.State cp, String productId, int x, int y, int w, int h) {
        BazaarProduct p = BazaarFormat.byId(productId);
        BazaarPages.Item menuProduct = cp != null ? productItem(cp.page) : null;
        int hy = y + 5;
        int hdr = 38;
        region("product", x + 6, hy, w - 12, hdr);
        ItemStack icon = menuProduct != null ? cp.stackAt(menuProduct.slot()) : productId != null
                ? BazaarIcons.icon(productId) : ItemStack.EMPTY;
        g.pose().pushMatrix();
        g.pose().translate(x + 8, hy + 3);
        g.pose().scale(2f, 2f);
        g.item(icon, 0, 0);
        g.pose().popMatrix();
        contentItems++;
        int tx = x + 44;
        int tw = x + w - 8 - tx;
        String name = p != null ? p.displayName() : menuProduct != null ? menuProduct.name()
                : BazaarFormat.displayName(productId);
        text("product", fit(name, tw), tx, hy + 3, p != null ? t.on(BazaarFormat.nameColor(p)) : t.text());
        if (p != null) {
            text("product", fit(p.category() + " › " + p.group(), tw), tx, hy + 14, t.faint());
            String line = "Buy " + BazaarFormat.price(p.buyPrice()) + " · Sell " + BazaarFormat.price(p.sellPrice())
                    + " · Margin " + BazaarFormat.percent(p.marginPercent()) + " · 7d " + BazaarFormat.shortNumber(p.weeklyVolume());
            text("product", fit(line, tw), tx, hy + 25, t.gold());
        } else {
            text("product", fit(cp == null ? "Waiting for Hypixel..." : "No live price for this product", tw), tx,
                    hy + 14, t.faint());
        }
        if (menuProduct != null) {
            slotHot(String.valueOf(menuProduct.slot()), x + 6, hy, w - 12, hdr, menuProduct, icon);
            if (in(x + 6, hy, w - 12, hdr)) {
                hoveredStack = icon;
            }
        }
        g.fill(x + 6, hy + hdr, x + w - 6, hy + hdr + 1, t.border());

        // The four trade buttons, always in Hypixel's order and always in the same places.
        List<BazaarPages.Item> extra = new ArrayList<>();
        Map<String, BazaarPages.Item> byName = new HashMap<>();
        if (cp != null) {
            for (BazaarPages.Item it : cp.page.content()) {
                if (it == menuProduct) {
                    continue;
                }
                if (ACTIONS.contains(it.name())) {
                    byName.put(it.name(), it);
                } else {
                    extra.add(it);
                }
            }
        }
        int top = hy + hdr + 7;
        int bottom = y + h - 6;
        int avail = bottom - top;
        int bookH = avail >= 150 ? Math.min(78, avail * 35 / 100) : 0;
        int inner = w - 12;
        int cols = inner >= 560 ? 4 : 2;
        int n = ACTIONS.size() + extra.size();
        int gridRows = (n + cols - 1) / cols;
        int gap = 6;
        int cardW = (inner - gap * (cols - 1)) / cols;
        int cardH = Math.max(28, Math.min(96, (avail - bookH - gap * gridRows) / gridRows));
        region("cards", x + 6, top, inner, Math.max(0, Math.min(avail, gridRows * cardH + gap * (gridRows - 1))));
        for (int i = 0; i < n; i++) {
            int cx = x + 6 + (i % cols) * (cardW + gap);
            int cy = top + (i / cols) * (cardH + gap);
            if (cy + cardH > bottom) {
                break;
            }
            if (i < ACTIONS.size()) {
                BazaarPages.Item it = byName.get(ACTIONS.get(i));
                if (it != null) {
                    drawCard(cp, it, cx, cy, cardW, cardH);
                } else {
                    skeletonCard(ACTIONS.get(i), cx, cy, cardW, cardH);
                }
            } else {
                drawCard(cp, extra.get(i - ACTIONS.size()), cx, cy, cardW, cardH);
            }
        }
        if (bookH > 0 && p != null) {
            int by = top + gridRows * (cardH + gap) + 2;
            if (by + 20 <= bottom) {
                int colW = (inner - gap) / 2;
                region("book", x + 6, by, inner, bottom - by);
                book("Top buy orders", p.topBuyOrders(), x + 6, by, colW, bottom);
                book("Top sell offers", p.topSellOffers(), x + 6 + colW + gap, by, colW, bottom);
            }
        }
    }

    private static void skeletonCard(String name, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, t.skeleton());
        g.outline(x, y, w, h, t.border());
        region("card", x, y, w, h);
        text("card", fit(name, w - 12), x + 24, y + 8, t.dim());
        if (h >= 34) {
            text("card", fit("Opening...", w - 12), x + 6, y + 24, t.faint());
        }
        contentItems++;
    }

    private static void book(String title, List<BazaarOrderLevel> levels, int x, int y, int w, int bottom) {
        text("book", fit(title, w), x, y, t.accentBright());
        y += 11;
        if (levels.isEmpty()) {
            if (y + 9 <= bottom) {
                text("book", "None right now", x, y, t.faint());
            }
            return;
        }
        for (BazaarOrderLevel l : levels) {
            if (y + 9 > bottom) {
                break;
            }
            String priceText = BazaarFormat.price(l.pricePerUnit());
            String amount = BazaarFormat.shortNumber(l.amount()) + "x";
            String orders = l.orders() + (l.orders() == 1 ? " order" : " orders");
            boolean showOrders = font.width(priceText) + font.width(amount) + font.width(orders) + 16 <= w;
            text("book", fit(priceText, w / 2), x, y, t.gold());
            int amountRight = showOrders ? x + w - font.width(orders) - 8 : x + w;
            text("book", amount, amountRight - font.width(amount), y, t.text());
            if (showOrders) {
                text("book", orders, x + w - font.width(orders), y, t.faint());
            }
            y += 10;
        }
    }

    // Cards (amount, price and confirm steps, order options...) ----------------------------------------------------

    private static void drawCards(BazaarReskin.State cp, List<BazaarPages.Item> cards, int x, int y, int w, int h) {
        int n = cards.size();
        if (n == 0) {
            return;
        }
        int inner = w - 12;
        int cols = Math.max(1, Math.min(n, inner / 150));
        if (n == 4 && cols == 3) {
            cols = 2;
        }
        int gap = 6;
        int avail = y + h - 6 - y;
        // A short window: more columns rather than a card left off (every button of Hypixel's page must be reachable).
        int fitRows = Math.max(1, (avail + gap) / (28 + gap));
        cols = Math.min(n, Math.max(cols, (n + fitRows - 1) / fitRows));
        int gridRows = (n + cols - 1) / cols;
        int cardW = (inner - gap * (cols - 1)) / cols;
        int maxH = Math.max(28, (avail - gap * (gridRows - 1)) / gridRows);
        int[] rowH = new int[gridRows];
        for (int i = 0; i < n; i++) {
            rowH[i / cols] = Math.max(rowH[i / cols], Math.min(maxH, cardHeight(cp.stackAt(cards.get(i).slot()), cardW)));
        }
        int total = gap * (gridRows - 1);
        for (int r : rowH) {
            total += r;
        }
        region("cards", x + 6, y, inner, Math.max(0, Math.min(avail, total)));
        int cy = y;
        for (int r = 0; r < gridRows; r++) {
            if (cy + rowH[r] > y + h - 2) {
                break; // a very short window: never draw a card outside the panel
            }
            for (int c = 0; c < cols && r * cols + c < n; c++) {
                drawCard(cp, cards.get(r * cols + c), x + 6 + c * (cardW + gap), cy, cardW, rowH[r]);
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
                hgt += 10 * Math.max(1, font.split(line, w - 12).size());
            }
        }
        return Math.max(28, hgt + 4);
    }

    private static void drawCard(BazaarReskin.State cp, BazaarPages.Item it, int x, int y, int w, int h) {
        ItemStack stack = cp.stackAt(it.slot());
        boolean hover = in(x, y, w, h);
        g.fill(x, y, x + w, y + h, hover ? t.rowHover() : t.rowAlt());
        g.outline(x, y, w, h, hover ? t.accent() : t.border());
        if (hover) {
            hoveredStack = stack;
        }
        region("card", x, y, w, h);
        g.item(stack, x + 4, y + 4);
        g.itemDecorations(font, stack, x + 4, y + 4);
        contentItems++;
        componentText(true, "card", fitComponent(stack.getHoverName(), w - 28), x + 24, y + 8);
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
                        componentText(true, "card", seq, x + 6, ly);
                        ly += 10;
                    }
                }
            } finally {
                g.disableScissor();
            }
        }
        slotHot(String.valueOf(it.slot()), x, y, w, h, it, stack);
    }

    // Menu lists: a group's products, search results, Manage Orders -----------------------------------------------

    private static void drawMenuList(BazaarReskin.State cp, int x, int y, int w, int h) {
        BazaarPages.Page page = cp.page;
        List<BazaarPages.Item> list = withExtraNav(page);
        boolean orders = page.kind() == BazaarPages.Kind.ORDERS;
        boolean priced = !orders && list.stream().anyMatch(BazaarView::hasPrices);
        Columns c = columns(x, w);
        int headY = y + 5;
        region("list-head", x + 1, headY - 1, w - 2, 10);
        // Hypixel's page arrows for long groups ("(1/3) Oddities ➜ Reforge Stones"), at the head's right.
        int headRight = x + w - 8;
        BazaarPages.Item next = navItem(page, "Next Page");
        BazaarPages.Item prev = navItem(page, "Previous Page");
        if (next != null) {
            headRight = pageArrow(cp, next, "Next ›", headRight, headY);
        }
        if (prev != null) {
            headRight = pageArrow(cp, prev, "‹ Prev", headRight, headY);
        }
        String head = switch (page.kind()) {
            case ORDERS -> {
                int n = 0;
                for (BazaarPages.Item it : list) {
                    if (BazaarOrderParser.parse(it.name(), it.lore()) != null) {
                        n++;
                    }
                }
                yield n + (n == 1 ? " order" : " orders") + " · click one to claim or open it";
            }
            case SEARCH -> list.size() + (list.size() == 1 ? " result" : " results") + " on Hypixel";
            default -> list.size() + (list.size() == 1 ? " product" : " products");
        };
        boolean cols = priced && next == null && prev == null;
        text("list-head", fit(head, (cols ? c.nameRight() : headRight - 6) - x - 8), x + 6, headY, t.dim());
        if (cols) {
            text("list-head", "Buy", c.buyR() - font.width("Buy"), headY, t.faint());
            text("list-head", "Sell", c.sellR() - font.width("Sell"), headY, t.faint());
            if (c.margin()) {
                text("list-head", "Margin", c.marginR() - font.width("Margin"), headY, t.faint());
            }
            if (c.vol()) {
                text("list-head", "7d vol", c.volR() - font.width("7d vol"), headY, t.faint());
            }
        }
        int top = headY + 12;
        g.fill(x + 4, top - 2, x + w - 4, top - 1, t.border());
        int bottom = y + h - 2;
        region("list", x + 1, top, w - 2, bottom - top);
        if (list.isEmpty()) {
            text("list", orders ? "You have no Bazaar orders right now." : "Nothing here.", x + 8, top + 6, t.dim());
            contentItems++;
            return;
        }
        int rowH = orders ? 22 : ROW_H;
        int contentHeight = list.size() * rowH;
        int maxScroll = Math.max(0, contentHeight - (bottom - top));
        listScroll = Math.max(0, Math.min(listScroll, maxScroll));
        String target = targetId == null ? null : BazaarFormat.displayName(targetId);
        g.enableScissor(x + 1, top, x + w - 1, bottom);
        try {
            for (int i = 0; i < list.size(); i++) {
                int ry = top + i * rowH - listScroll;
                if (ry + rowH <= top) {
                    continue;
                }
                if (ry >= bottom) {
                    break;
                }
                BazaarPages.Item it = list.get(i);
                ItemStack stack = cp.stackAt(it.slot());
                boolean whole = ry >= top && ry + rowH <= bottom;
                int hy = Math.max(ry, top);
                int hh = Math.min(ry + rowH, bottom) - hy;
                boolean hover = in(x, hy, w - 6, hh);
                boolean sel = target != null && it.name().equalsIgnoreCase(target);
                if (sel) {
                    g.fill(x + 1, ry, x + w - 1, ry + rowH, t.selectedBg());
                    g.fill(x + 1, ry, x + 3, ry + rowH, t.accent());
                } else if (hover) {
                    g.fill(x + 1, ry, x + w - 1, ry + rowH, t.rowHover());
                } else if (i % 2 == 1) {
                    g.fill(x + 1, ry, x + w - 1, ry + rowH, t.rowAlt());
                }
                if (hover) {
                    hoveredStack = stack;
                }
                BazaarOrderParser.Order order = orders ? BazaarOrderParser.parse(stack.getHoverName().getString(),
                        it.lore()) : null;
                if (order != null) {
                    orderRow(order, stack, x + 6, ry, x + w - 8, whole);
                } else {
                    menuRow(it, stack, x, ry, w, c, cols, page.kind() == BazaarPages.Kind.GROUP && !cols, whole);
                }
                if (hh > 0) {
                    slotHot(String.valueOf(it.slot()), x, hy, w - 6, hh, it, stack);
                }
            }
        } finally {
            g.disableScissor();
        }
        if (maxScroll > 0) {
            scrollbar(x + w - 4, top, bottom - top, listScroll, maxScroll, contentHeight);
        }
    }

    private static int pageArrow(BazaarReskin.State cp, BazaarPages.Item it, String label, int right, int y) {
        int bw = font.width(label) + 8;
        int bx = right - bw;
        boolean hover = in(bx, y - 2, bw, 11);
        g.fill(bx, y - 2, bx + bw, y + 9, hover ? t.controlHover() : t.control());
        text("list-head", label, bx + 4, y, hover ? t.accentBright() : t.text());
        slotHot(String.valueOf(it.slot()), bx, y - 2, bw, 11, it, cp.stackAt(it.slot()));
        return bx - 4;
    }

    private static void menuRow(BazaarPages.Item it, ItemStack stack, int x, int ry, int w, Columns c, boolean cols,
            boolean groupPreview, boolean whole) {
        g.item(stack, x + 4, ry + 2);
        g.itemDecorations(font, stack, x + 4, ry + 2);
        contentItems++;
        int tx = x + 24;
        int nameRight = cols ? c.nameRight() : x + w - 8;
        BazaarProduct live = BazaarFormat.byName(it.name());
        String sub = subtitle(it);
        if (live != null) {
            maybeText(whole, "list", fit(live.displayName(), nameRight - tx), tx, ry + 2, t.on(BazaarFormat.nameColor(live)));
        } else {
            componentText(whole, "list", fitComponent(stack.getHoverName(), nameRight - tx), tx, ry + 2);
        }
        maybeText(whole, "list", fit(sub, nameRight - tx), tx, ry + 11, t.faint());
        if (cols && hasPrices(it)) {
            double buy = loreValue(it, BUY_PRICE);
            double sell = loreValue(it, SELL_PRICE);
            rightText(whole, "list", buy < 0 ? "-" : BazaarFormat.price(buy), c.buyR(), ry + 6, t.gold());
            rightText(whole, "list", sell < 0 ? "-" : BazaarFormat.price(sell), c.sellR(), ry + 6, t.gold());
            // Margin from the prices Hypixel's own menu shows; the weekly volume only the API has.
            if (c.margin() && buy >= 0 && sell > 0) {
                double pct = (buy - sell) / sell * 100.0;
                rightText(whole, "list", BazaarFormat.percent(pct), c.marginR(), ry + 6, pct >= 0 ? t.green() : t.red());
            }
            if (c.vol()) {
                rightText(whole, "list", live == null ? "-" : BazaarFormat.shortNumber(live.weeklyVolume()), c.volR(),
                        ry + 6, t.dim());
            }
        } else if (groupPreview) {
            int used = tx + Math.max(font.width(it.name()), font.width(sub)) + 16;
            String preview = fit(String.join(" · ", productNames(it)), x + w - 8 - used);
            int pw = font.width(preview);
            if (pw > 0) {
                maybeText(whole, "list", preview, x + w - 8 - pw, ry + 6, t.dim());
            }
        }
    }

    private static void orderRow(BazaarOrderParser.Order o, ItemStack stack, int x, int y, int right, boolean record) {
        g.item(stack, x - 3, y + 3);
        contentItems++;
        boolean buy = o.type() == BazaarOrderParser.Type.BUY;
        String tag = buy ? "BUY" : "SELL";
        int tagW = font.width("SELL") + 6;
        int tx = x + 17;
        g.fill(tx, y + 2, tx + tagW, y + 11, buy ? 0xFF1F4A1F : 0xFF4A3A10);
        BazaarProduct p = BazaarFormat.byName(o.productName());
        String st = BazaarFormat.standingText(o, p);
        int statusW = font.width(st);
        int nameX = tx + tagW + 4;
        String name = fit(o.productName(), right - statusW - 8 - nameX);
        String detail = fit(BazaarFormat.shortNumber(o.filled()) + "/" + BazaarFormat.shortNumber(o.amount())
                + " filled · " + BazaarFormat.price(o.pricePerUnit()) + " each · " + BazaarFormat.price(o.total())
                + " total" + (o.owner() != null ? " · " + o.owner() : ""), right - tx);
        maybeText(record, "list", tag, tx + (tagW - font.width(tag)) / 2, y + 3, buy ? 0xFF55DD55 : 0xFFFFC040);
        maybeText(record, "list", name, nameX, y + 3, t.text());
        maybeText(record, "list", st, right - statusW, y + 3, BazaarFormat.statusColor(t, o, p));
        maybeText(record, "list", detail, tx, y + 12, t.faint());
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    /** A press on the Bazaar (never on a frozen frame - the hosts do not call this then). */
    static void press(double x, double y, int button, boolean shift, BazaarReskin.State cp) {
        BazaarFollowUp.disarm("another press");
        boolean onSearch = x >= searchX && x < searchX + searchW && y >= searchY && y < searchY + searchH;
        if (!onSearch) {
            searchFocused = false;
        }
        if (button != 0 && button != 1) {
            return;
        }
        for (Hot h : HOTS) {
            if (!h.contains(x, y)) {
                continue;
            }
            if (h.action() != null) {
                if (button == 0) {
                    Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
                            .forUI(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    h.action().run();
                }
            } else if (cp != null) {
                BazaarReskin.sendClick(cp, h.slot(), h.stack(), button, shift, h.label());
            }
            return;
        }
    }

    static void scroll(double mx, double my, double sy) {
        int step = (int) Math.signum(sy) * ROW_H * 3;
        if (railW > 0 && mx >= railX && mx < railX + railW && my >= railY && my < railY + railH) {
            railScroll = Math.max(0, railScroll - step);
        } else {
            listScroll = Math.max(0, listScroll - step);
        }
    }

    /** A key while the Bazaar is open: true when the search field took it. */
    static boolean key(KeyEvent e) {
        if (!searchFocused) {
            if (e.key() == InputConstants.KEY_F && e.hasControlDown()) {
                searchFocused = true;
                return true;
            }
            return false;
        }
        BazaarFollowUp.disarm("typing");
        if (e.key() == InputConstants.KEY_ESCAPE || e.key() == InputConstants.KEY_RETURN) {
            searchFocused = false;
            return true;
        }
        if (e.key() == InputConstants.KEY_BACKSPACE && !query.isEmpty()) {
            setQuery(e.hasControlDown() ? "" : query.substring(0, query.length() - 1));
        }
        return true; // a focused search field takes every key (E types an e, it does not close the menu)
    }

    static void charTyped(CharacterEvent e) {
        if (!searchFocused || !e.isAllowedChatCharacter() || query.length() >= MAX_QUERY) {
            return;
        }
        BazaarFollowUp.disarm("typing");
        setQuery(query + e.codepointAsString());
    }

    private static void setQuery(String q) {
        query = q;
        mode = Mode.BROWSE;
        listScroll = 0;
    }

    // ---- small drawing helpers ------------------------------------------------------------------------------------

    private static boolean in(int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static void scrollbar(int x, int y, int h, int scroll, int maxScroll, int contentHeight) {
        g.fill(x, y, x + 2, y + h, t.control());
        int thumbH = Math.max(12, (int) ((long) h * h / Math.max(1, contentHeight)));
        int thumbY = y + (int) ((long) (h - thumbH) * scroll / Math.max(1, maxScroll));
        g.fill(x, thumbY, x + 2, thumbY + thumbH, t.accent());
    }

    /** The page's content plus the bottom-row items that have no place of their own (Cancel Buy Order...). */
    private static List<BazaarPages.Item> withExtraNav(BazaarPages.Page page) {
        List<BazaarPages.Item> out = new ArrayList<>(page.content());
        for (BazaarPages.Item it : page.nav()) {
            if (!PLACED_NAV.contains(it.name())) {
                out.add(it);
            }
        }
        return out;
    }

    private static BazaarPages.Item navItem(BazaarPages.Page page, String name) {
        if (page == null) {
            return null;
        }
        for (BazaarPages.Item it : page.nav()) {
            if (it.name().equals(name)) {
                return it;
            }
        }
        return null;
    }

    private static BazaarPages.Item anyItem(BazaarPages.Page page, String name) {
        for (List<BazaarPages.Item> part : List.of(page.nav(), page.content())) {
            for (BazaarPages.Item it : part) {
                if (it.name().equals(name)) {
                    return it;
                }
            }
        }
        return null;
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

    /** "Hold Left Alt: Hypixel's menu", or "" with no Hypixel Menu key set. */
    private static String vanillaHint() {
        int code = AuctionConfig.getInstance().getBazaarVanillaKeyCode();
        if (code < 0) {
            return "";
        }
        return "Hold " + InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString() + ": Hypixel's menu";
    }

    /** "Go Back" says where it goes ("‹ Bazaar", from its "To Bazaar" lore line). */
    static String backLabel(BazaarPages.Item it) {
        for (String line : it.lore()) {
            Matcher m = TO_LINE.matcher(line);
            if (m.matches()) {
                return "‹ " + m.group(1);
            }
        }
        return "‹ Back";
    }

    private static FormattedCharSequence fitComponent(Component c, int width) {
        if (width <= 0) {
            return FormattedCharSequence.EMPTY;
        }
        if (font.width(c) <= width) {
            return c.getVisualOrderText();
        }
        List<FormattedCharSequence> lines = font.split(c, width);
        return lines.isEmpty() ? FormattedCharSequence.EMPTY : lines.get(0);
    }

    private static void componentText(boolean record, String region, FormattedCharSequence seq, int x, int y) {
        g.text(font, t.seq(seq), x, y, 0xFFFFFFFF, false);
        if (record) {
            int w = font.width(seq);
            if (w > 0) {
                LAYOUT.add("text " + region + " " + x + " " + y + " " + w + " 8 " + plain(seq));
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

    private static void maybeText(boolean record, String region, String s, int x, int y, int color) {
        if (record) {
            text(region, s, x, y, color);
        } else if (!s.isEmpty()) {
            g.text(font, s, x, y, color, false); // partly scrolled out: drawn but clipped, not a layout box
        }
    }

    private static void rightText(boolean record, String region, String s, int right, int y, int color) {
        maybeText(record, region, s, right - font.width(s), y, color);
    }

    private static void text(String region, String s, int x, int y, int color) {
        if (s.isEmpty()) {
            return;
        }
        g.text(font, s, x, y, color, false);
        LAYOUT.add("text " + region + " " + x + " " + y + " " + font.width(s) + " 8 " + s);
    }

    private static void region(String name, int x, int y, int w, int h) {
        LAYOUT.add("region " + name + " " + x + " " + y + " " + w + " " + h);
    }

    private static void slotHot(String key, int x, int y, int w, int h, BazaarPages.Item it, ItemStack stack) {
        HOTS.add(new Hot(x, y, w, h, key, it.slot(), stack.copy(), null, it.name()));
        LAYOUT.add("hotspot " + it.slot() + " " + x + " " + y + " " + w + " " + h + " " + key.replace(' ', '_') + " "
                + it.name());
    }

    private static void actionHot(String key, int x, int y, int w, int h, String label, Runnable action) {
        HOTS.add(new Hot(x, y, w, h, key, -1, ItemStack.EMPTY, action, label));
        LAYOUT.add("hotspot " + key.replace(' ', '_') + " " + x + " " + y + " " + w + " " + h + " " + label);
    }

    private static String fit(String s, int width) {
        if (s == null || width <= 0) {
            return "";
        }
        if (font.width(s) <= width) {
            return s;
        }
        String cut = font.plainSubstrByWidth(s, Math.max(0, width - font.width("...")));
        return cut.isEmpty() ? "" : cut + "...";
    }

    // ---- testkit hooks --------------------------------------------------------------------------------------------

    /** What the last frame showed: "{@code <mode> <content> [frozen] items=<n>}". */
    static String frameSummary() {
        return summary;
    }

    /**
     * Every region, text, hotspot and widget of the LAST frame, one per line: {@code region <name> x y w h},
     * {@code text <region> x y w h <text>}, {@code hotspot <slot|action-key> x y w h <label>} (a slot hotspot's label
     * starts with its key, e.g. {@code back Go Back}), {@code widget search x y w h}.
     */
    public static List<String> layoutReportForTest() {
        return List.copyOf(LAYOUT);
    }

    public static String frameSummaryForTest() {
        return summary;
    }

    public static String modeForTest() {
        return mode + "|" + tab + "|" + query + "|" + (previewId == null ? "" : previewId) + "|"
                + (targetId == null ? "" : targetId) + "|" + status;
    }

    public static void selectTabForTest(String cat) {
        selectTab(cat);
    }

    public static void setQueryForTest(String q) {
        setQuery(q == null ? "" : q);
    }

    /** The same as his click on a product row or recent. */
    public static void openProductForTest(String productId) {
        openProduct(productId);
    }

    /** Shows the product view for {@code productId} without sending anything (render tests). */
    public static void previewForTest(String productId) {
        previewId = productId;
        targetId = productId;
        mode = Mode.PREVIEW;
        listScroll = 0;
    }

    /** Forget the cached Booster Cookie read (it is re-read at most once a second). */
    public static void forgetCookieForTest() {
        cookieReadMs = 0;
    }

    public static void resetForTest() {
        cookieReadMs = 0;
        freshSession();
        query = "";
        railScroll = 0;
        lastFrameMs = 0;
    }
}
