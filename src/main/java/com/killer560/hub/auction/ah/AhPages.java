package com.killer560.hub.auction.ah;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of Hypixel's Auction House menus is open, read from its title and items, and what each item IS on it (a
 * listing, a category tab, a filter control, the pager, the hero item, an action, the bottom row's navigation).
 * {@link AhReskin} draws every item it was given here as one element that clicks that item's slot; a menu this
 * class cannot map is {@link Kind#UNKNOWN} and stays Hypixel's own GUI.
 * <p>
 * Sources (2026-10-07):
 * <ul>
 *   <li>Titles: Skyblocker {@code MenuScreensConstructorMixin} ("auctions browser", "auctions: ", ends with
 *       "auction view", "confirm purchase", "confirm bid"); SkyHanni {@code AuctionsHighlighter} ("Manage Auctions"),
 *       {@code AuctionHouseCopyUnderbidPrice} ("Create BIN Auction", {@code Auctions: "..."}); the hypixelskyblock wiki's
 *       Auction House page (menus "Auction House", "Your Bids", "Create Auction", "Create BIN Auction",
 *       "Auction Duration").</li>
 *   <li>Auctions Browser slots: Skyblocker {@code AuctionBrowserScreen} - categories in column 0 (one per row),
 *       listings in rows 1-4 x columns 2-7, 46 previous page, 47 reset, 48 search, 49 back, 50 sort, 51 rarity,
 *       52 BIN filter, 53 next page; the page arrows' first lore line reads "(n/total)"; the selected option of a
 *       filter is the lore line marked "▶"; the search sign's lore carries "Filtered: ...".</li>
 *   <li>Auction View: the item in slot 13 (Skyblocker {@code AuctionViewScreen}); Confirm Bid/Purchase: confirm 11,
 *       cancel 15 (Skyblocker's popup clicks exactly those).</li>
 * </ul>
 * "Auction Stats", "Bid History" and anything else are not mapped.
 */
public final class AhPages {

    public enum Kind {
        MAIN("Auction House"),
        BROWSER("Auctions Browser"),
        VIEW("Auction View"),
        CONFIRM("Confirm"),
        MANAGE("Manage Auctions"),
        BIDS("Your Bids"),
        CREATE("Create Auction"),
        DURATION("Auction Duration"),
        UNKNOWN("");

        public final String label;

        Kind(String label) {
            this.label = label;
        }

        /** Pages drawn as a grid of listing cards (the others are hero + action cards). */
        public boolean grid() {
            return this == BROWSER || this == MANAGE || this == BIDS;
        }
    }

    /** One non-empty menu slot: plain name, plain lore, the item's registry path ("arrow"), blank-named filler. */
    public record Item(int slot, String name, List<String> lore, String itemPath, boolean filler) {
    }

    /** What a menu is, for drawing. Every non-filler item of the menu is in exactly one of the lists/fields. */
    public record Page(Kind kind, String title, String heading, List<Item> tabs, List<Item> listings, Item search,
                       Item sort, Item rarity, Item type, Item reset, Item prev, Item next, String filter, Item hero, List<Item> actions, List<Item> nav, boolean playerInventory) {

        /** Every item with an element on screen. */
        public List<Item> all() {
            List<Item> out = new ArrayList<>(tabs);
            out.addAll(listings);
            for (Item i : new Item[]{search, sort, rarity, type, reset, prev, next, hero}) {
                if (i != null) {
                    out.add(i);
                }
            }
            out.addAll(actions);
            out.addAll(nav);
            return out;
        }
    }

    private static final Pattern SEARCH_TITLE = Pattern.compile("^Auctions: \".{0,40}\"?$");
    private static final Pattern PAGE_LORE = Pattern.compile("^\\(([\\d,]{1,6})/([\\d,]{1,6})\\)$");
    private static final Pattern FILTERED = Pattern.compile("^Filtered: (.{0,40})$");
    private static final Pattern SEARCH_QUERY = Pattern.compile("^Auctions: \"(.{0,40}?)\"?$");

    private AhPages() {
    }

    /** True for any title this class might map (decides whether the reskin even watches a screen). */
    public static boolean titleCandidate(String title) {
        return kindOf(title) != Kind.UNKNOWN;
    }

    /** The kind a title names, before the items are checked. */
    public static Kind kindOf(String title) {
        if (title == null) {
            return Kind.UNKNOWN;
        }
        return switch (title) {
            case "Auction House" -> Kind.MAIN;
            case "Auctions Browser" -> Kind.BROWSER;
            case "Auction View", "BIN Auction View" -> Kind.VIEW;
            case "Confirm Bid", "Confirm Purchase" -> Kind.CONFIRM;
            case "Manage Auctions" -> Kind.MANAGE;
            case "Your Bids" -> Kind.BIDS;
            case "Create Auction", "Create BIN Auction" -> Kind.CREATE;
            case "Auction Duration" -> Kind.DURATION;
            default -> SEARCH_TITLE.matcher(title).matches() ? Kind.BROWSER : Kind.UNKNOWN;
        };
    }

    /** A strong title opens hidden (the reskin's loading frame) instead of flashing Hypixel's chest first. */
    public static boolean strongTitle(String title) {
        Kind k = kindOf(title);
        return k == Kind.BROWSER || k == Kind.VIEW || k == Kind.MANAGE || k == Kind.BIDS || k == Kind.CREATE;
    }

    /** The search a browser title names ({@code Auctions: "hype"} -> hype), or "". */
    public static String searchOf(String title) {
        Matcher m = title == null ? null : SEARCH_QUERY.matcher(title);
        return m != null && m.matches() ? m.group(1) : "";
    }

    public static Page classify(String title, int rows, List<Item> items) {
        Kind kind = kindOf(title);
        if (kind == Kind.UNKNOWN || rows < 3 || rows > 6) {
            return unknown(title);
        }
        Item[] at = new Item[rows * 9];
        for (Item it : items) {
            if (it.slot() >= 0 && it.slot() < at.length && !it.filler()) {
                at[it.slot()] = it;
            }
        }
        return switch (kind) {
            case BROWSER -> browser(title, rows, at);
            case MANAGE, BIDS -> listingPage(kind, title, rows, at);
            case VIEW -> heroPage(kind, title, rows, at, true);
            case CREATE -> heroPage(kind, title, rows, at, false);
            case CONFIRM -> confirm(title, rows, at);
            default -> actionPage(kind, title, rows, at);
        };
    }

    private static Page unknown(String title) {
        return new Page(Kind.UNKNOWN, title, title, List.of(), List.of(), null, null, null, null, null, null, null,
                "", null, List.of(), List.of(), false);
    }

    private static Page browser(String title, int rows, Item[] at) {
        if (rows != 6 || at[49] == null) {
            return unknown(title);
        }
        List<Item> tabs = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            if (at[r * 9] != null) {
                tabs.add(at[r * 9]);
            }
        }
        if (tabs.size() < 3) {
            return unknown(title);
        }
        List<Item> listings = new ArrayList<>();
        for (int r = 1; r <= 4; r++) {
            for (int c = 2; c <= 7; c++) {
                if (at[r * 9 + c] != null) {
                    listings.add(at[r * 9 + c]);
                }
            }
        }
        Item prev = at[46];
        Item next = at[53];
        Item reset = at[47];
        Item search = at[48];
        Item sort = at[50];
        Item rarity = at[51];
        Item type = at[52];
        java.util.Set<Integer> used = new java.util.HashSet<>(List.of(46, 47, 48, 50, 51, 52, 53));
        for (Item t : tabs) {
            used.add(t.slot());
        }
        for (Item l : listings) {
            used.add(l.slot());
        }
        List<Item> nav = new ArrayList<>();
        for (int s = 0; s < at.length; s++) {
            if (at[s] != null && !used.contains(s)) {
                nav.add(at[s]);
            }
        }
        String filter = search == null ? "" : loreMatch(search, FILTERED);
        if (filter.isEmpty()) {
            filter = searchOf(title);
        }
        return new Page(Kind.BROWSER, title, title.startsWith("Auctions: ") ? "Search: " + searchOf(title) : "Browse",
                tabs, listings, search, sort, rarity, type, reset, prev, next, filter, null, List.of(),
                nav, false);
    }

    private static Page listingPage(Kind kind, String title, int rows, Item[] at) {
        List<Item> listings = new ArrayList<>();
        List<Item> nav = new ArrayList<>();
        Item prev = null;
        Item next = null;
        for (int s = 0; s < at.length; s++) {
            Item it = at[s];
            if (it == null) {
                continue;
            }
            if (isPrev(it)) {
                prev = it;
            } else if (isNext(it)) {
                next = it;
            } else if (s < (rows - 1) * 9 && AhLore.isListing(it.lore())) {
                listings.add(it);
            } else {
                nav.add(it);
            }
        }
        return new Page(kind, title, kind.label, List.of(), listings, null, null, null, null, null, prev, next,
                "", null, List.of(), nav, false);
    }

    private static Page heroPage(Kind kind, String title, int rows, Item[] at, boolean needHero) {
        Item hero = at[13];
        if (needHero && hero == null) {
            return unknown(title);
        }
        List<Item> actions = new ArrayList<>();
        List<Item> nav = new ArrayList<>();
        for (int s = 0; s < at.length; s++) {
            Item it = at[s];
            if (it == null || s == 13) {
                continue;
            }
            (s >= (rows - 1) * 9 ? nav : actions).add(it);
        }
        return new Page(kind, title, title, List.of(), List.of(), null, null, null, null, null, null, null, "",
                hero, actions, nav, kind == Kind.CREATE);
    }

    private static Page confirm(String title, int rows, Item[] at) {
        if (at[11] == null || at[15] == null) {
            return unknown(title);
        }
        List<Item> actions = new ArrayList<>();
        for (Item it : at) {
            if (it != null && it.slot() != 13) {
                actions.add(it);
            }
        }
        return new Page(Kind.CONFIRM, title, title, List.of(), List.of(), null, null, null, null, null, null, null,
                "", at[13], actions, List.of(), false);
    }

    private static Page actionPage(Kind kind, String title, int rows, Item[] at) {
        List<Item> actions = new ArrayList<>();
        List<Item> nav = new ArrayList<>();
        for (int s = 0; s < at.length; s++) {
            Item it = at[s];
            if (it != null) {
                (s >= (rows - 1) * 9 ? nav : actions).add(it);
            }
        }
        if (actions.isEmpty()) {
            return unknown(title);
        }
        return new Page(kind, title, kind == Kind.MAIN ? "Auction House" : title, List.of(), List.of(), null, null, null,
                null, null, null, null, "", null, actions, nav, false);
    }

    private static boolean isPrev(Item it) {
        return it.name().equals("Previous Page");
    }

    private static boolean isNext(Item it) {
        return it.name().equals("Next Page");
    }

    /** {n, total} of an arrow's own "(n/total)" lore line, as it reads; {0, 0} when it has none. Whether n is the page
     *  shown or the page the arrow leads to is not interpreted: the pager prints each arrow's own numbers. */
    public static int[] arrowPage(Item arrow) {
        if (arrow == null) {
            return new int[]{0, 0};
        }
        for (String line : arrow.lore()) {
            Matcher m = PAGE_LORE.matcher(line.trim());
            if (m.matches()) {
                try {
                    return new int[]{Integer.parseInt(m.group(1).replace(",", "")),
                            Integer.parseInt(m.group(2).replace(",", ""))};
                } catch (NumberFormatException e) {
                    return new int[]{0, 0};
                }
            }
        }
        return new int[]{0, 0};
    }

    /** The option of a filter button currently selected: its lore line marked "▶ ", or "". */
    public static String selectedOption(Item it) {
        if (it == null) {
            return "";
        }
        for (String line : it.lore()) {
            String t = line.trim();
            if (t.startsWith("▶ ") && t.length() <= 40) {
                return t.substring(2).trim();
            }
        }
        return "";
    }

    private static String loreMatch(Item it, Pattern p) {
        for (String line : it.lore()) {
            Matcher m = p.matcher(line.trim());
            if (m.matches()) {
                return m.group(1).trim();
            }
        }
        return "";
    }
}
