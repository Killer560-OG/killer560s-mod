package com.killer560.hub.bazaar;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of Hypixel's real Bazaar menus is open, decided from the container's title AND its items - pure string work,
 * so the testkit can check it without a client, and it never throws.
 * <p>
 * A page is only reported when its title matches one of the formats below AND an item that page always carries is
 * there. Anything else is {@link Kind#UNKNOWN}, and {@code BazaarReskin} leaves that menu to Hypixel's own GUI: a menu
 * we cannot map is never drawn half-right. Sources (read 2026-10-07; all text colour-stripped):
 * <ul>
 *   <li>SkyHanni {@code features/inventory/bazaar/BazaarApi.kt} (beta), repo patterns {@code inventory.bazaar}:
 *       titles {@code Bazaar ➜ .*}, {@code How many do you want?}, {@code How much do you want to pay?},
 *       {@code Confirm Buy Order}, {@code Confirm Instant Buy}, {@code At what price are you selling?},
 *       {@code Confirm Sell Offer}, {@code Order options}, {@code Your Bazaar Orders}, {@code Co-op Bazaar Orders};
 *       product page slot 10 {@code Buy Instantly}; Order options slot 11 name contains {@code Cancel Order}.</li>
 *   <li>Skyblocker {@code skyblock/bazaar/BazaarHelper.java}, {@code BazaarOrderTracker.java} (product page {@code .* ➜ .*},
 *       product at slot 13), {@code ReorderHelper.java} ({@code ^Order options}).</li>
 *   <li>hypixelskyblock.minecraft.wiki {@code Bazaar/UI/General}, {@code /Farming}, {@code /Oddities}: the category page
 *       {@code Bazaar ➜ Farming} (categories Farming, Mining, Combat, Woods &amp; Fishes, Oddities down the left column,
 *       groups whose lore ends "Click to view products!", a bottom row of Search, Sell Inventory Now, Sell Sacks Now,
 *       Close, Manage Orders, Bazaar History, Bazaar Settings); a group page {@code Farming ➜ Wheat & Seeds} (products
 *       with {@code Buy price: # coins} / {@code Sell price: # coins}; also {@code (1/3) Oddities ➜ Reforge Stones}); the
 *       product page (Buy Instantly, Sell Instantly, the product, Create Buy Order, Create Sell Offer); {@code <Item> ➜
 *       Instant Buy} (Buy only one!, Buy a stack!, Fill my inventory!, Custom Amount); the buy order amount (Get me a
 *       stack!, A big stack!, One thousand!, Custom Amount) and price (Same as Top Order, Top Order +0.1, 5% of Spread,
 *       Custom Price) steps, Confirm Buy Order (Buy Order), the sell offer price step (Same as Best Offer, Best Offer
 *       -0.1, 10% of Spread, Custom Price), Confirm Sell Offer (Sell Offer), and Sell Inventory Now's {@code Are you
 *       sure?} (Selling whole inventory, Cancel).</li>
 * </ul>
 * Left to Hypixel's GUI on purpose, because no source shows their layout: Confirm Instant Buy, Bazaar Settings, Bazaar
 * History, and anything else. The sign screens (search, custom amount, custom price) are not containers and are never
 * touched.
 * <p>
 * The reskin depends on no slot NUMBER: every element it draws carries the slot it was read from, and a click goes back
 * to that slot. Roles only decide where an item is drawn (sidebar, list or card, bottom bar).
 */
public final class BazaarPages {

    public enum Kind {
        /** "Bazaar ➜ Farming" ...: categories down the left, product groups in the grid. */
        CATEGORY(true),
        /** Any other "Bazaar ➜ ..." page listing products with prices (search results). */
        SEARCH(true),
        /** "Farming ➜ Wheat & Seeds": one row per product, prices from the lore. */
        GROUP(true),
        /** One product: Buy Instantly / Sell Instantly / Create Buy Order / Create Sell Offer. */
        PRODUCT(false),
        /** "Wheat ➜ Instant Buy": how many to buy instantly. */
        INSTANT_BUY(false),
        /** "How many do you want?": buy order amount. */
        ORDER_AMOUNT(false),
        /** "How much do you want to pay?": buy order price. */
        ORDER_PRICE(false),
        /** "Confirm Buy Order". */
        CONFIRM_BUY(false),
        /** "At what price are you selling?": sell offer price. */
        OFFER_PRICE(false),
        /** "Confirm Sell Offer". */
        CONFIRM_SELL(false),
        /** "Are you sure?" after Sell Inventory Now. */
        CONFIRM_SELL_INVENTORY(false),
        /** "Your Bazaar Orders" / "Co-op Bazaar Orders" (Manage Orders). */
        ORDERS(true),
        /** "Order options": one order's cancel choice. */
        ORDER_OPTIONS(false),
        /** Not a Bazaar menu we know: Hypixel's own GUI is left alone. */
        UNKNOWN(false);

        /** Drawn as a scrolling list of rows (true) or as cards (false). */
        public final boolean list;

        Kind(boolean list) {
            this.list = list;
        }
    }

    /** One menu slot, as the classifier sees it. {@code slot} is the container slot index a click goes to. */
    public record Item(int slot, String name, List<String> lore, boolean filler) {
    }

    /** A classified page: the kind, a heading for the header, and every non-filler item in its place. */
    public record Page(Kind kind, String title, String heading, List<Item> sidebar, List<Item> content, List<Item> nav) {
    }

    public static final Set<String> CATEGORIES = Set.of("Farming", "Mining", "Combat", "Woods & Fishes", "Oddities");

    static final Pattern CATEGORY_TITLE = Pattern.compile("^Bazaar ➜ (Farming|Mining|Combat|Woods & Fishes|Oddities)$");
    static final Pattern BAZAAR_TITLE = Pattern.compile("^Bazaar ➜ (.{1,40})$");
    /** "A ➜ B": group and product pages. Both halves bounded; "(1/3) " page prefixes are inside the first. */
    static final Pattern ARROW_TITLE = Pattern.compile("^(.{1,48}) ➜ (.{1,48})$");
    static final Pattern INSTANT_BUY_TITLE = Pattern.compile("^(.{1,48}) ➜ Instant Buy$");
    static final Pattern ORDERS_TITLE = Pattern.compile("^(?:Your|Co-op) Bazaar Orders$");
    static final Pattern PRICE_LINE = Pattern.compile("^(?:Buy|Sell) price: [\\d,.]{1,20} coins?$");
    static final Pattern PAGE_PREFIX = Pattern.compile("^\\(\\d{1,3}/\\d{1,3}\\) ");

    private BazaarPages() {
    }

    /** True when the title could be a Bazaar menu at all, so its items are worth classifying. */
    public static boolean titleCandidate(String title) {
        if (title == null) {
            return false;
        }
        String t = title.trim();
        return strongTitle(t) || t.equals("Are you sure?") || (ARROW_TITLE.matcher(t).matches() && !t.contains("Museum"));
    }

    /** Titles only the Bazaar uses: the reskin may hide Hypixel's GUI for these while the items load. Generic ones
     *  ("A ➜ B", "Are you sure?") stay Hypixel's until their items prove them Bazaar pages. */
    public static boolean strongTitle(String title) {
        if (title == null) {
            return false;
        }
        String t = title.trim();
        return ORDERS_TITLE.matcher(t).matches() || BAZAAR_TITLE.matcher(t).matches()
                || INSTANT_BUY_TITLE.matcher(t).matches()
                || switch (t) {
                    case "Order options", "How many do you want?", "How much do you want to pay?",
                            "At what price are you selling?", "Confirm Buy Order", "Confirm Sell Offer" -> true;
                    default -> false;
                };
    }

    /**
     * @param rows   the container's row count (9 slots each); the bottom row is the button bar
     * @param items  every container slot that is not empty, in slot order (never the player's inventory)
     */
    public static Page classify(String title, int rows, List<Item> items) {
        try {
            return classifyUnsafe(title == null ? "" : title.trim(), rows, items);
        } catch (RuntimeException e) {
            return unknown(title);
        }
    }

    private static Page unknown(String title) {
        return new Page(Kind.UNKNOWN, title, "", List.of(), List.of(), List.of());
    }

    private static Page classifyUnsafe(String title, int rows, List<Item> items) {
        if (rows < 3 || rows > 6 || items == null || items.isEmpty()) {
            return unknown(title);
        }
        Kind kind = kindOf(title, items);
        if (kind == Kind.UNKNOWN) {
            return unknown(title);
        }
        int bottom = (rows - 1) * 9;
        List<Item> sidebar = new ArrayList<>();
        List<Item> content = new ArrayList<>();
        List<Item> nav = new ArrayList<>();
        for (Item it : items) {
            if (it.filler() || it.slot() < 0 || it.slot() >= rows * 9) {
                continue;
            }
            if (kind == Kind.CATEGORY && it.slot() % 9 == 0 && CATEGORIES.contains(it.name())) {
                sidebar.add(it);
            } else if (it.slot() >= bottom || isNavName(it.name())) {
                nav.add(it);
            } else {
                content.add(it);
            }
        }
        if (content.isEmpty() && kind != Kind.ORDERS) {
            return unknown(title);
        }
        if (kind == Kind.PRODUCT) {
            // The product itself (Skyblocker/SkyHanni read it from slot 13) leads, then the four actions.
            content.sort(java.util.Comparator.comparing(BazaarPages::isAction)); // stable: false (the product) first
        }
        return new Page(kind, title, heading(kind, title), List.copyOf(sidebar), List.copyOf(content), List.copyOf(nav));
    }

    /** The four trade buttons of a product page (and Sell Inventory Now's result). */
    public static boolean isAction(Item it) {
        return switch (it.name()) {
            case "Buy Instantly", "Sell Instantly", "Create Buy Order", "Create Sell Offer", "Inventory sold!" -> true;
            default -> false;
        };
    }

    /** The bar buttons Hypixel puts on Bazaar menus; drawn in our bottom bar wherever they sit. */
    public static boolean isNavName(String name) {
        return switch (name) {
            case "Go Back", "Close", "Manage Orders", "Search", "Sell Inventory Now", "Sell Sacks Now", "View Graphs",
                    "Bazaar Settings", "Bazaar History", "Direct Mode", "Advanced Mode", "Instasell Ignore",
                    "Next Page", "Previous Page" -> true;
            default -> false;
        };
    }

    private static boolean has(List<Item> items, String name) {
        for (Item it : items) {
            if (it.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAny(List<Item> items, String... names) {
        for (String n : names) {
            if (has(items, n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nameContains(List<Item> items, String part) {
        for (Item it : items) {
            if (it.name().contains(part)) {
                return true;
            }
        }
        return false;
    }

    /** Items with a "Buy price: # coins" or "Sell price: # coins" lore line (the products of a group page). */
    private static int priced(List<Item> items) {
        int n = 0;
        for (Item it : items) {
            for (String line : it.lore()) {
                if (PRICE_LINE.matcher(line).matches()) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    private static boolean loreEnds(List<Item> items, String last) {
        for (Item it : items) {
            List<String> l = it.lore();
            if (!l.isEmpty() && l.get(l.size() - 1).equals(last)) {
                return true;
            }
        }
        return false;
    }

    static Kind kindOf(String title, List<Item> items) {
        if (ORDERS_TITLE.matcher(title).matches()) {
            return Kind.ORDERS;
        }
        switch (title) {
            case "Order options":
                return nameContains(items, "Cancel Order") ? Kind.ORDER_OPTIONS : Kind.UNKNOWN;
            case "How many do you want?":
                return has(items, "Custom Amount") && hasAny(items, "Get me a stack!", "A big stack!", "One thousand!")
                        ? Kind.ORDER_AMOUNT : Kind.UNKNOWN;
            case "How much do you want to pay?":
                return has(items, "Custom Price") && hasAny(items, "Same as Top Order", "Top Order +0.1")
                        ? Kind.ORDER_PRICE : Kind.UNKNOWN;
            case "At what price are you selling?":
                return has(items, "Custom Price") && hasAny(items, "Same as Best Offer", "Best Offer -0.1")
                        ? Kind.OFFER_PRICE : Kind.UNKNOWN;
            case "Confirm Buy Order":
                return has(items, "Buy Order") ? Kind.CONFIRM_BUY : Kind.UNKNOWN;
            case "Confirm Sell Offer":
                return has(items, "Sell Offer") ? Kind.CONFIRM_SELL : Kind.UNKNOWN;
            case "Are you sure?":
                return has(items, "Selling whole inventory") && has(items, "Cancel")
                        ? Kind.CONFIRM_SELL_INVENTORY : Kind.UNKNOWN;
            default:
                break;
        }
        if (CATEGORY_TITLE.matcher(title).matches()) {
            return has(items, "Manage Orders") ? Kind.CATEGORY : Kind.UNKNOWN;
        }
        if (BAZAAR_TITLE.matcher(title).matches()) {
            // Settings and History are "Bazaar ➜ ..." too, but list no priced products.
            return priced(items) >= 1 ? Kind.SEARCH : Kind.UNKNOWN;
        }
        if (INSTANT_BUY_TITLE.matcher(title).matches()) {
            return has(items, "Custom Amount") && hasAny(items, "Buy only one!", "Buy a stack!", "Fill my inventory!")
                    ? Kind.INSTANT_BUY : Kind.UNKNOWN;
        }
        if (ARROW_TITLE.matcher(title).matches() && !title.contains("Museum")) {
            if (has(items, "Buy Instantly") && hasAny(items, "Create Buy Order", "Create Sell Offer")) {
                return Kind.PRODUCT;
            }
            if (priced(items) >= 1 || loreEnds(items, "Click to view products!")) {
                return Kind.GROUP;
            }
        }
        return Kind.UNKNOWN;
    }

    /** Header text: "Farming", "Wheat & Seeds", "Wheat", or the step's name. */
    static String heading(Kind kind, String title) {
        Matcher m;
        return switch (kind) {
            case CATEGORY -> (m = CATEGORY_TITLE.matcher(title)).matches() ? m.group(1) : title;
            case SEARCH -> (m = BAZAAR_TITLE.matcher(title)).matches() ? m.group(1) : title;
            case GROUP, PRODUCT -> (m = ARROW_TITLE.matcher(title)).matches()
                    ? PAGE_PREFIX.matcher(m.group(1)).replaceFirst("") + " › " + m.group(2) : title;
            case INSTANT_BUY -> (m = INSTANT_BUY_TITLE.matcher(title)).matches() ? m.group(1) + " › Instant Buy" : title;
            case ORDER_AMOUNT -> "Buy Order › How many?";
            case ORDER_PRICE -> "Buy Order › Price";
            case CONFIRM_BUY -> "Buy Order › Confirm";
            case OFFER_PRICE -> "Sell Offer › Price";
            case CONFIRM_SELL -> "Sell Offer › Confirm";
            case CONFIRM_SELL_INVENTORY -> "Sell Inventory › Confirm";
            case ORDERS -> title.startsWith("Co-op") ? "Manage Orders › Co-op" : "Manage Orders";
            case ORDER_OPTIONS -> "Manage Orders › Order Options";
            case UNKNOWN -> title;
        };
    }
}
