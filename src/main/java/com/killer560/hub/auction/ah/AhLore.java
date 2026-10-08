package com.killer560.hub.auction.ah;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the auction lines Hypixel appends to an item's lore in its AH menus (plain text, colour codes stripped).
 * Line shapes from SkyHanni (2026-10-07): {@code AuctionsHighlighter} "§7Buy it now: §6<coins> coins",
 * "§7(?:Starting bid|Top bid): §6<coins> coins", "§7Status: §aSold!" / "§7Status: §cExpired!";
 * {@code AuctionHouseCopyUnderbidPrice} "(?:Buy it now|Starting bid|Top bid): <coins> coins"; Skyblocker
 * {@code AuctionViewScreen} "Can buy in: ..." (a new BIN's grace period). "Seller:", "Ends in:" and "Bids:" are read
 * only to show them and are optional: a listing without them still shows its price. Every quantifier is bounded.
 */
public final class AhLore {

    private static final Pattern BIN = Pattern.compile("^Buy it now: ([\\d,]{1,20}) coins?$");
    private static final Pattern STARTING = Pattern.compile("^Starting bid: ([\\d,]{1,20}) coins?$");
    private static final Pattern TOP = Pattern.compile("^Top bid: ([\\d,]{1,20}) coins?$");
    private static final Pattern STATUS = Pattern.compile("^Status: (.{1,24})$");
    private static final Pattern SELLER = Pattern.compile("^Seller: (.{1,48})$");
    private static final Pattern ENDS = Pattern.compile("^Ends in: (.{1,24})$");
    private static final Pattern BIDS = Pattern.compile("^Bids: ([\\d,]{1,9}) bids?$");
    private static final Pattern GRACE = Pattern.compile("^Can buy in: (.{1,24})$");
    private static final String[] TIERS = {"VERY SPECIAL", "SPECIAL", "DIVINE", "MYTHIC", "LEGENDARY", "EPIC", "RARE",
            "UNCOMMON", "COMMON", "ULTIMATE", "ADMIN"};

    /** What the lore says about one listing. Prices are -1 when absent. */
    public record Listing(long bin, long startingBid, long topBid, String status, String seller, String endsIn,
                          int bids, String graceLeft, String tier) {
        public boolean isBin() {
            return bin >= 0;
        }

        /** The number that matters: the BIN price, else the top bid, else the starting bid. */
        public long price() {
            return bin >= 0 ? bin : topBid >= 0 ? topBid : startingBid;
        }

        public String priceLabel() {
            return bin >= 0 ? "BIN" : topBid >= 0 ? "Top bid" : startingBid >= 0 ? "Starting bid" : "";
        }
    }

    private AhLore() {
    }

    /** True when the lore carries an auction price or status line (a listing, not a button). */
    public static boolean isListing(List<String> lore) {
        for (String raw : lore) {
            String l = raw.trim();
            if (BIN.matcher(l).matches() || STARTING.matcher(l).matches() || TOP.matcher(l).matches()
                    || STATUS.matcher(l).matches()) {
                return true;
            }
        }
        return false;
    }

    public static Listing parse(List<String> lore) {
        long bin = -1, starting = -1, top = -1;
        String status = "", seller = "", ends = "", grace = "", tier = "";
        int bids = -1;
        for (String raw : lore) {
            String l = raw.trim();
            Matcher m;
            if ((m = BIN.matcher(l)).matches()) {
                bin = coins(m.group(1));
            } else if ((m = STARTING.matcher(l)).matches()) {
                starting = coins(m.group(1));
            } else if ((m = TOP.matcher(l)).matches()) {
                top = coins(m.group(1));
            } else if ((m = STATUS.matcher(l)).matches()) {
                status = m.group(1);
            } else if ((m = SELLER.matcher(l)).matches()) {
                seller = m.group(1);
            } else if ((m = ENDS.matcher(l)).matches()) {
                ends = m.group(1);
            } else if ((m = BIDS.matcher(l)).matches()) {
                bids = (int) Math.min(Integer.MAX_VALUE, coins(m.group(1)));
            } else if ((m = GRACE.matcher(l)).matches()) {
                grace = m.group(1);
            }
        }
        // The rarity line is the item's own last lore line before the auction block, e.g. "MYTHIC DUNGEON CHESTPLATE".
        for (String raw : lore) {
            String l = raw.trim().toUpperCase(Locale.ROOT);
            for (String t : TIERS) {
                if (l.startsWith(t + " ") || l.equals(t)) {
                    tier = t;
                }
            }
        }
        return new Listing(bin, starting, top, status, seller, ends, bids, grace, tier);
    }

    private static long coins(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
