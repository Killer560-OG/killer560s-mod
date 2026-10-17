package com.killer560.hub.bazaarflip;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.gui.profit.ProfitScope;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The Bazaar Flipper's profit tracker (killer560, 2026-10-16: "also include auto bazaar in the profit menu as well but
 * only have it show on cheat version"): one record per completed cycle, from the coins Hypixel itself reported - the
 * sum of the cycle's "[Bazaar] Bought Nx X for C coins!" lines is what was spent, the sum of its "You sold X xN for C
 * Coins!" lines is what came back. Neither the scan's estimate nor the order book enters it.
 *
 * <p>Net is received minus spent - exactly what the purse moved by - so units bought and then not sold (the NPC sell
 * limit, a refused sale) earn nothing here: their cost is in, no revenue for them is. Tax is its own column and is 0
 * today ({@link BazaarFlipScanner#INSTANT_BUY_TAX}: instant buys and NPC sales are untaxed). When the purse was read off
 * the scoreboard at both ends, the record also keeps that change, for comparison.
 *
 * <p>Every record carries a {@link ProfitScope.Key}, like the other trackers. The file
 * ({@code killer560smod-bazaarflip-profit.json}) is run history and never rides in a mod profile.
 */
public final class BazaarFlipProfitLog {

    private static final Logger LOGGER = ModLog.get("killer560smod-bazaarflip");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = ModPaths.config("killer560smod-bazaarflip-profit.json");
    /** Oldest records are dropped past this, so the file stays small. */
    private static final int MAX_RECORDS = 5000;

    /** One cycle. {@code purseDelta} is NaN when the purse was not readable at both ends. */
    public record Cycle(long atMs, String productId, String name, int bought, int sold, double spent, double received,
                        double tax, double purseDelta, ProfitScope.Key key) {
        public double net() {
            return received - spent - tax;
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("atMs", atMs);
            o.addProperty("productId", productId);
            o.addProperty("name", name);
            o.addProperty("bought", bought);
            o.addProperty("sold", sold);
            o.addProperty("spent", spent);
            o.addProperty("received", received);
            o.addProperty("tax", tax);
            if (!Double.isNaN(purseDelta)) {
                o.addProperty("purseDelta", purseDelta);
            }
            key.writeTo(o);
            return o;
        }

        static Cycle fromJson(JsonObject o) {
            return new Cycle(o.get("atMs").getAsLong(), o.get("productId").getAsString(), o.get("name").getAsString(),
                    o.get("bought").getAsInt(), o.get("sold").getAsInt(), o.get("spent").getAsDouble(),
                    o.get("received").getAsDouble(), o.has("tax") ? o.get("tax").getAsDouble() : 0,
                    o.has("purseDelta") ? o.get("purseDelta").getAsDouble() : Double.NaN, ProfitScope.Key.readFrom(o));
        }
    }

    /** Sums over the records a filter selects. */
    public record Totals(int cycles, long bought, long sold, double spent, double received, double tax) {
        public double net() {
            return received - spent - tax;
        }
    }

    private static final List<Cycle> CYCLES = new ArrayList<>();
    private static ProfitScope.Filter view = new ProfitScope.Filter();
    private static boolean loaded = false;
    private static boolean accountsMigrated = false;

    private BazaarFlipProfitLog() {
    }

    /** A cycle the flipper finished. */
    public static synchronized Cycle record(String productId, String name, int bought, int sold, double spent,
                                            double received, double tax, double purseDelta) {
        ensureLoaded();
        Cycle c = new Cycle(System.currentTimeMillis(), productId, name, bought, sold, spent, received, tax, purseDelta,
                ProfitScope.current());
        CYCLES.add(c);
        while (CYCLES.size() > MAX_RECORDS) {
            CYCLES.removeFirst();
        }
        save();
        return c;
    }

    public static synchronized List<Cycle> cycles(ProfitScope.Filter f) {
        ensureLoaded();
        List<Cycle> out = new ArrayList<>();
        for (Cycle c : CYCLES) {
            if (c.key().matches(f)) {
                out.add(c);
            }
        }
        return out;
    }

    public static Totals totals(ProfitScope.Filter f) {
        int n = 0;
        long bought = 0;
        long sold = 0;
        double spent = 0;
        double received = 0;
        double tax = 0;
        for (Cycle c : cycles(f)) {
            n++;
            bought += c.bought();
            sold += c.sold();
            spent += c.spent();
            received += c.received();
            tax += c.tax();
        }
        return new Totals(n, bought, sold, spent, received, tax);
    }

    /** What the /profit screen shows ({@link ProfitScope#resolve} of the saved switches). */
    public static ProfitScope.Filter scope() {
        return ProfitScope.resolve(view());
    }

    public static synchronized ProfitScope.Filter view() {
        ensureLoaded();
        return view;
    }

    public static synchronized void saveView() {
        ensureLoaded();
        save();
    }

    public static synchronized List<ProfitScope.Key> keys() {
        ensureLoaded();
        LinkedHashSet<ProfitScope.Key> out = new LinkedHashSet<>();
        CYCLES.forEach(c -> out.add(c.key()));
        return List.copyOf(out);
    }

    /** Recent cycles, newest first, as one line each. */
    public static List<String> recentLines(int max) {
        List<Cycle> all = cycles(scope());
        List<String> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            Cycle c = all.get(i);
            out.add(String.format(Locale.US, "%s x%,d: %s%,.0f%s", c.name(), c.sold(), c.net() >= 0 ? "+" : "",
                    c.net(), c.sold() < c.bought() ? String.format(Locale.US, " (%,d unsold)", c.bought() - c.sold()) : ""));
        }
        return out;
    }

    /** Net per cycle, oldest first, for the graph. */
    public static long[] netPerCycle() {
        List<Cycle> all = cycles(scope());
        long[] out = new long[all.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.round(all.get(i).net());
        }
        return out;
    }

    public static synchronized void reset(ProfitScope.Filter f) {
        ensureLoaded();
        CYCLES.removeIf(c -> c.key().matches(f));
        save();
    }

    /** A mod profile was renamed: its records follow the new name. */
    public static synchronized void renameModProfile(String oldName, String newName) {
        ensureLoaded();
        boolean changed = false;
        for (int i = 0; i < CYCLES.size(); i++) {
            Cycle c = CYCLES.get(i);
            if (c.key().modProfile().equals(oldName)) {
                CYCLES.set(i, new Cycle(c.atMs(), c.productId(), c.name(), c.bought(), c.sold(), c.spent(),
                        c.received(), c.tax(), c.purseDelta(), c.key().withModProfile(newName)));
                changed = true;
            }
        }
        if (oldName.equals(view.modProfile)) {
            view.modProfile = newName;
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    /** Reads the file again (testkit: save and reload). */
    public static synchronized void reload() {
        loaded = false;
        accountsMigrated = false;
        CYCLES.clear();
        view = new ProfitScope.Filter();
        ensureLoaded();
    }

    private static void ensureLoaded() {
        if (loaded) {
            migrate();
            return;
        }
        loaded = true;
        try {
            if (Files.exists(FILE)) {
                JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("cycles")) {
                    for (JsonElement e : root.getAsJsonArray("cycles")) {
                        try {
                            CYCLES.add(Cycle.fromJson(e.getAsJsonObject()));
                        } catch (RuntimeException bad) {
                            LOGGER.warn("[BazaarFlip] Skipped an unreadable profit record: {}", e);
                        }
                    }
                }
                if (root.has("view") && root.get("view").isJsonObject()) {
                    view = ProfitScope.Filter.fromJson(root.getAsJsonObject("view"));
                    view.account = null; // the account shown is Show Profit's (ProfitConfig), as in the other trackers
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[BazaarFlip] Couldn't read {} - starting an empty profit log", FILE, e);
        }
        migrate();
    }

    /** Records written before the account was known get one, as {@link ProfitScope#migrated} decides. */
    private static void migrate() {
        if (accountsMigrated || !ProfitScope.canMigrate()) {
            return;
        }
        accountsMigrated = true;
        boolean changed = false;
        for (int i = 0; i < CYCLES.size(); i++) {
            Cycle c = CYCLES.get(i);
            ProfitScope.Key to = ProfitScope.migrated(c.key());
            if (!to.equals(c.key())) {
                CYCLES.set(i, new Cycle(c.atMs(), c.productId(), c.name(), c.bought(), c.sold(), c.spent(),
                        c.received(), c.tax(), c.purseDelta(), to));
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            JsonArray arr = new JsonArray();
            CYCLES.forEach(c -> arr.add(c.toJson()));
            root.add("cycles", arr);
            root.add("view", view.toJson());
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[BazaarFlip] Couldn't save {}", FILE, e);
        }
    }
}
