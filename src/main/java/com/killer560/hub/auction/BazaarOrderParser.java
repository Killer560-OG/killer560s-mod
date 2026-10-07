package com.killer560.hub.auction;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads one of the player's Bazaar orders from the item Hypixel shows for it in the Manage Orders menu. Pure string
 * work on the item's colour-stripped name and lore, so it can be tested without a client; never throws.
 * <p>
 * The formats come from two mods that parse this same menu, since Hypixel publishes none:
 * <ul>
 *   <li>SkyHanni {@code features/inventory/bazaar/BazaarOrderApi.kt} (beta branch, read 2026-10-07): the name
 *       {@code §a§lBUY §fWheat} / {@code §6§lSELL ...}; lore {@code Order amount: 2,000x} / {@code Offer amount: 50x},
 *       {@code Filled: 26/50 (52%)} or {@code Filled: 1.9k/1.9k 100%!} (absent until something trades; large numbers
 *       are abbreviated), {@code You have 6 items to claim!}, {@code Price per unit: 2,082.3 coins} and, on a co-op,
 *       {@code By: [MVP+] hannibal2}. Menu titles {@code Your Bazaar Orders} / {@code Co-op Bazaar Orders}
 *       ({@code BazaarApi.kt}).</li>
 *   <li>Skyblocker {@code skyblock/bazaar/BazaarHelper.java} and {@code BazaarOrderTracker.java} (main, read
 *       2026-10-07): the same amount and price lines, {@code Filled: \S+ \(?(\d+)%\)?!?}, a last line
 *       {@code Click to claim!} when there is something to claim, and the lines {@code Expired!} /
 *       {@code Expires in ...}.</li>
 * </ul>
 * A sell offer's claim line ("You have N coins to claim!") is NOT in either source; it is accepted here because a sell
 * offer pays out coins, but that wording is unverified.
 * <p>
 * Every pattern is anchored and every quantifier bounded (CLAUDE.md: other players' text can reach item lore too).
 */
public final class BazaarOrderParser {

    public enum Type { BUY, SELL }

    /** One order as the menu showed it. {@code filled} is exact only when {@code full}; Hypixel abbreviates
     *  ("1.2k") below that. {@code claimableItems}/{@code claimableCoins} are -1 when the menu did not say. */
    public record Order(Type type, String productName, String productId, long amount, long filled, boolean full,
            double pricePerUnit, long claimableItems, double claimableCoins, boolean claimable, boolean expired,
            String expiresIn, String owner) {

        public double total() {
            return amount * pricePerUnit;
        }

        /** Stable identity for merging two reads of the same order. */
        public String key() {
            return type + "|" + productName.toLowerCase(Locale.ROOT) + "|" + pricePerUnit + "|" + amount;
        }
    }

    static final Pattern NAME = Pattern.compile("^(BUY|SELL) (\\S.{0,63})$");
    static final Pattern AMOUNT = Pattern.compile("^(?:Order|Offer) amount: ([\\d,]{1,15})x$");
    static final Pattern FILLED = Pattern.compile(
            "^Filled: ([\\d,.]{1,15}[kKmMbB]?)/([\\d,.]{1,15}[kKmMbB]?) (?:\\(([\\d.]{1,6})%\\)|(100%!))$");
    static final Pattern CLAIM_ITEMS = Pattern.compile("^You have ([\\d,]{1,15}) items? to claim!$");
    static final Pattern CLAIM_COINS = Pattern.compile("^You have ([\\d,.]{1,20}) coins? to claim!$");
    static final Pattern PRICE = Pattern.compile("^Price per unit: ([\\d,.]{1,20}) coins?$");
    static final Pattern OWNER = Pattern.compile("^By: (?:\\[[A-Za-z+]{1,10}\\] )?([A-Za-z0-9_]{1,16})$");
    static final Pattern EXPIRES = Pattern.compile("^Expires in (.{1,24})$");
    static final Pattern COLOR = Pattern.compile("§.");

    private BazaarOrderParser() {
    }

    public static String strip(String s) {
        return s == null ? "" : COLOR.matcher(s).replaceAll("").trim();
    }

    /** @return the order, or null when this item is not one (glass panes, the back arrow, a malformed line). */
    public static Order parse(String rawName, List<String> rawLore) {
        try {
            return parseUnsafe(strip(rawName), rawLore);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Order parseUnsafe(String name, List<String> rawLore) {
        Matcher n = NAME.matcher(name);
        if (!n.matches() || rawLore == null) {
            return null;
        }
        Type type = "BUY".equals(n.group(1)) ? Type.BUY : Type.SELL;
        String product = n.group(2).trim();
        long amount = -1;
        long filled = 0;
        boolean full = false;
        double price = -1;
        long claimItems = -1;
        double claimCoins = -1;
        boolean claimable = false;
        boolean expired = false;
        String expires = null;
        String owner = null;
        int lines = 0;
        for (String raw : rawLore) {
            if (++lines > 64) {
                break;
            }
            String line = strip(raw);
            Matcher m;
            if ((m = AMOUNT.matcher(line)).matches()) {
                amount = parseLong(m.group(1));
            } else if ((m = FILLED.matcher(line)).matches()) {
                full = m.group(4) != null;
                filled = abbreviated(m.group(1));
            } else if ((m = CLAIM_ITEMS.matcher(line)).matches()) {
                claimItems = parseLong(m.group(1));
            } else if ((m = CLAIM_COINS.matcher(line)).matches()) {
                claimCoins = parseDouble(m.group(1));
            } else if ((m = PRICE.matcher(line)).matches()) {
                price = parseDouble(m.group(1));
            } else if ((m = OWNER.matcher(line)).matches()) {
                owner = m.group(1);
            } else if ((m = EXPIRES.matcher(line)).matches()) {
                expires = m.group(1);
            } else if (line.equals("Expired!")) {
                expired = true;
            } else if (line.equals("Click to claim!")) {
                claimable = true;
            }
        }
        if (amount < 0 || price < 0) {
            return null;
        }
        if (full) {
            filled = amount;
        }
        filled = Math.max(0, Math.min(amount, filled));
        claimable = claimable || claimItems > 0 || claimCoins > 0;
        return new Order(type, product, BazaarCatalog.idForName(product), amount, filled, full, price, claimItems,
                claimCoins, claimable, expired, expires, owner);
    }

    static long parseLong(String s) {
        return Long.parseLong(s.replace(",", ""));
    }

    static double parseDouble(String s) {
        return Double.parseDouble(s.replace(",", ""));
    }

    /** "1.2k" -> 1200, "34" -> 34. Hypixel rounds these, so the result is approximate. */
    static long abbreviated(String s) {
        String t = s.replace(",", "");
        double mult = 1;
        char last = Character.toLowerCase(t.charAt(t.length() - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            t = t.substring(0, t.length() - 1);
        }
        return Math.round(Double.parseDouble(t) * mult);
    }
}
