package com.killer560.hub.bazaar;

import com.killer560.hub.auction.BazaarApi;
import com.killer560.hub.auction.BazaarCatalog;
import com.killer560.hub.auction.BazaarOrderParser;
import com.killer560.hub.auction.BazaarOrders;
import com.killer560.hub.auction.BazaarProduct;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
import com.killer560.hub.util.ChatColors;
import net.minecraft.ChatFormatting;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Numbers, names and live-product lookups shared by the Bazaar screen's views. */
final class BazaarFormat {

    private static List<BazaarProduct> indexedSource;
    private static Map<String, BazaarProduct> byId = Map.of();
    private static Map<String, BazaarProduct> byName = Map.of();

    private BazaarFormat() {
    }

    private static void index() {
        List<BazaarProduct> source = BazaarApi.getProducts();
        if (source == indexedSource) {
            return;
        }
        Map<String, BazaarProduct> ids = new HashMap<>();
        Map<String, BazaarProduct> names = new HashMap<>();
        for (BazaarProduct p : source) {
            ids.put(p.productId(), p);
            String key = p.displayName().toLowerCase(Locale.ROOT);
            BazaarProduct old = names.get(key);
            // Two names belong to two ids each (Enchanted Hay Bale, Enchanted Carrot on a Stick): keep the busier.
            if (old == null || p.weeklyVolume() > old.weeklyVolume()) {
                names.put(key, p);
            }
        }
        byId = ids;
        byName = names;
        indexedSource = source;
    }

    /** The live product with this id, or null. */
    static BazaarProduct byId(String productId) {
        if (productId == null) {
            return null;
        }
        index();
        return byId.get(productId);
    }

    /**
     * The live product a menu item names. By NAME over the live list rather than through {@link BazaarCatalog#idForName},
     * because two names belong to two ids each and the catalog's pick need not be the one the API lists.
     */
    static BazaarProduct byName(String name) {
        if (name == null) {
            return null;
        }
        index();
        return byName.get(BazaarOrderParser.strip(name).trim().toLowerCase(Locale.ROOT));
    }

    /** The product's display name: live, else the bundled table's, else the id. */
    static String displayName(String productId) {
        BazaarProduct p = byId(productId);
        if (p != null) {
            return p.displayName();
        }
        BazaarCatalog.Entry e = BazaarCatalog.isLoaded() ? BazaarCatalog.get(productId) : null;
        return e != null && e.name() != null ? e.name() : productId;
    }

    static int nameColor(BazaarProduct p) {
        if (p.ultimate()) {
            return 0xFFFF55FF;
        }
        String code = SkyblockItemStackFactory.tierColorCode(p.tier());
        char c = code.length() >= 2 ? code.charAt(1) : 'f';
        ChatFormatting cf = ChatFormatting.getByCode(c);
        Integer rgb = cf != null ? ChatColors.color(cf) : null;
        return 0xFF000000 | (rgb != null ? rgb : 0xFFFFFF);
    }

    /** Coins, compact enough for a column: 4.4, 1,234.5, 123.4k, 12.35M, 1.23B. */
    static String price(double v) {
        double a = Math.abs(v);
        if (a >= 1e9) {
            return String.format(Locale.US, "%.2fB", v / 1e9);
        }
        if (a >= 1e6) {
            return String.format(Locale.US, "%.2fM", v / 1e6);
        }
        if (a >= 1e5) {
            return String.format(Locale.US, "%.1fk", v / 1e3);
        }
        return String.format(Locale.US, "%,.1f", v);
    }

    static String shortNumber(long n) {
        if (n >= 1_000_000_000L) {
            return String.format(Locale.US, "%.2fB", n / 1e9);
        }
        if (n >= 1_000_000L) {
            return String.format(Locale.US, "%.2fM", n / 1e6);
        }
        if (n >= 10_000L) {
            return String.format(Locale.US, "%.1fk", n / 1e3);
        }
        return String.format(Locale.US, "%,d", n);
    }

    static String percent(double v) {
        return String.format(Locale.US, "%.1f%%", v);
    }

    static String standingText(BazaarOrderParser.Order o, BazaarProduct live) {
        if (o.expired()) {
            return "Expired";
        }
        if (o.claimable()) {
            return o.full() ? "Filled - claim" : "Claimable";
        }
        if (o.full()) {
            return "Filled";
        }
        return switch (BazaarOrders.standing(o, live)) {
            case TOP -> "Top order";
            case MATCHED -> "Matched top";
            case OUTBID -> (o.type() == BazaarOrderParser.Type.BUY ? "Outbid: " : "Undercut: ")
                    + price(o.type() == BazaarOrderParser.Type.BUY ? live.topBuyOrderPrice() : live.topSellOfferPrice());
            case UNKNOWN -> "No live price";
        };
    }

    static int statusColor(BazaarTheme t, BazaarOrderParser.Order o, BazaarProduct live) {
        if (o.expired()) {
            return t.red();
        }
        if (o.claimable() || o.full()) {
            return t.green();
        }
        return switch (BazaarOrders.standing(o, live)) {
            case TOP -> t.green();
            case MATCHED -> t.gold();
            case OUTBID -> t.red();
            case UNKNOWN -> t.faint();
        };
    }

    static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + "m ago";
        }
        if (s < 86400 * 2) {
            return (s / 3600) + "h ago";
        }
        return (s / 86400) + "d ago";
    }
}
