package com.killer560.hub.mining.profit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.pathfinding.IslandDetector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mining "Profit Per Hour" tracker (killer560, verbatim: "Profit per hour tracker").
 * <p>
 * Purely observational, same rule as {@code experiments.ExperimentsProfitTracker} and
 * {@code runsummary.RunSummaryFeature}: never clicks, never moves, never sends anything. Every tick it
 * scans the player's real inventory (main + hotbar + armor + off-hand, {@link Inventory#getContainerSize()}),
 * identifies each stack with {@link ItemIdentity#of} (the same id resolver {@code autoroutes} already uses
 * for matching held items) and diffs it against the previous scan. Any item whose count went UP is a
 * "gain" - dropping, depositing to storage, selling, or crafting something away are simply negative deltas
 * and are ignored, so this can only under-count profit (from an unpriced or unrecognised drop), never
 * fabricate it.
 * <p>
 * Each gain is priced through {@link MiningItemPricer} (Bazaar first, then the AH/Bazaar cache the RNG Meter
 * already runs) and added to a running total, alongside an "active" clock that only advances while
 * {@link MiningProfitConfig#isEnabled()} is true AND (if "Mining Islands Only" is on) {@link #isMiningIsland}
 * says so - so leaving the mines, logging out, or opening a menu (which freezes visible inventory, not
 * real gains) simply pauses the clock instead of diluting the coins/hour figure. Both the clock and the
 * totals are persisted ({@code killer560smod-mining-profit.json}) so Coins/Hour survives a restart exactly
 * like {@code experiments.ExperimentsProfitTracker}'s totals do.
 */
public final class MiningProfitTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-mining-profit");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-mining-profit.json");

    /** Hypixel islands where "Mining Islands Only" lets the clock run - {@link IslandDetector}'s own graph
     *  island names, the same ones {@code pathfinding} features already gate on. */
    private static final Set<String> MINING_ISLANDS = Set.of(
            "DWARVEN_MINES", "GLACITE_TUNNELS", "CRYSTAL_HOLLOWS", "GOLD_MINES", "DEEP_CAVERNS");

    /** Real time between scans - the 41-slot identity scan is cheap, but there's no reason to run it at
     *  20Hz when items don't change that fast. */
    private static final int SCAN_EVERY_TICKS = 4;
    /** A gap longer than this between two ticks (relog, huge lag spike, world change) is NOT counted
     *  towards the active clock - same reasoning {@code ActionGate}'s teleport settle window documents for
     *  "don't trust a reading that spans a discontinuity". */
    private static final long MAX_TICK_GAP_MS = 2_000L;

    private static boolean loaded = false;
    private static int tickCounter = 0;
    private static long lastTickAtMs = 0L;
    private static boolean haveBaseline = false;
    private static final Map<String, Integer> lastCounts = new LinkedHashMap<>();
    private static final Map<String, String> lastNames = new LinkedHashMap<>();

    // Persisted running totals.
    private static long activeMs;
    private static double totalValueCoins;
    private static long unpricedGains;
    private static final Map<String, ItemTotal> itemTotals = new LinkedHashMap<>();

    private record ItemTotal(String name, long count, double value) {
    }

    private MiningProfitTracker() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(MiningProfitTracker::tick);
    }

    private static void tick(Minecraft client) {
        try {
            ensureLoaded();
            MiningProfitConfig cfg = MiningProfitConfig.getInstance();
            boolean active = cfg.isEnabled() && client.player != null && client.level != null
                    && (!cfg.isMiningIslandsOnly() || isMiningIsland());
            long now = System.currentTimeMillis();
            if (active && lastTickAtMs != 0L) {
                long gap = now - lastTickAtMs;
                if (gap > 0 && gap <= MAX_TICK_GAP_MS) {
                    activeMs += gap;
                }
            }
            lastTickAtMs = now;

            if (!cfg.isEnabled() || client.player == null) {
                haveBaseline = false;
                return;
            }
            if (++tickCounter < SCAN_EVERY_TICKS) {
                return;
            }
            tickCounter = 0;
            MiningItemPricer.ensurePricesLoading();
            scan(client.player);
        } catch (Exception e) {
            LOGGER.error("Mining profit tracker tick failed", e);
        }
    }

    /** Whether the player is currently on one of {@link #MINING_ISLANDS} - {@link IslandDetector} is ticked
     *  unconditionally by {@code pathfinding.PathfindingFeature}, so this never needs its own registration. */
    public static boolean isMiningIsland() {
        return MINING_ISLANDS.contains(IslandDetector.graphIsland());
    }

    private static void scan(LocalPlayer player) {
        Inventory inv = player.getInventory();
        Map<String, Integer> current = new LinkedHashMap<>();
        Map<String, String> currentNames = new LinkedHashMap<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String id = ItemIdentity.of(stack);
            if (id == null) {
                continue;
            }
            current.merge(id, stack.getCount(), Integer::sum);
            String plain = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            currentNames.putIfAbsent(id, plain == null ? id : plain);
        }

        if (!haveBaseline) {
            // First scan since enabling/joining: nothing to diff against yet, so nothing is "gained" -
            // otherwise every item already in your inventory when you turn this on would be counted as
            // profit, which is exactly the kind of fabricated number the brief says not to ship.
            haveBaseline = true;
            lastCounts.clear();
            lastCounts.putAll(current);
            lastNames.clear();
            lastNames.putAll(currentNames);
            return;
        }

        boolean changed = false;
        for (Map.Entry<String, Integer> e : current.entrySet()) {
            String id = e.getKey();
            int delta = e.getValue() - lastCounts.getOrDefault(id, 0);
            if (delta <= 0) {
                continue;
            }
            changed = true;
            String name = currentNames.getOrDefault(id, id);
            Double unitPrice = MiningItemPricer.priceOf(id);
            double value = unitPrice != null ? unitPrice * delta : 0.0;
            if (unitPrice == null) {
                unpricedGains++;
            } else {
                totalValueCoins += value;
            }
            ItemTotal prior = itemTotals.get(id);
            itemTotals.put(id, new ItemTotal(name,
                    (prior == null ? 0 : prior.count()) + delta,
                    (prior == null ? 0 : prior.value()) + value));
        }
        lastCounts.clear();
        lastCounts.putAll(current);
        lastNames.clear();
        lastNames.putAll(currentNames);
        if (changed) {
            save();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Accessors for MiningProfitTab
    // ------------------------------------------------------------------------------------------

    public static double getTotalValueCoins() {
        ensureLoaded();
        return totalValueCoins;
    }

    public static long getActiveMs() {
        ensureLoaded();
        return activeMs;
    }

    public static long getUnpricedGains() {
        ensureLoaded();
        return unpricedGains;
    }

    /** Coins/hour over the ACTIVE clock only (paused time never dilutes it), or 0 before anything's tracked. */
    public static double getCoinsPerHour() {
        ensureLoaded();
        return activeMs <= 0 ? 0.0 : totalValueCoins / (activeMs / 3_600_000.0);
    }

    /** Up to {@code max} highest-value items gained so far, highest first. */
    public static java.util.List<String> topItemLines(int max) {
        ensureLoaded();
        return itemTotals.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().value(), a.getValue().value()))
                .limit(max)
                .map(e -> e.getValue().name() + " x" + e.getValue().count()
                        + " (" + MiningItemPricer.shortNumber(e.getValue().value()) + ")")
                .toList();
    }

    public static void reset() {
        ensureLoaded();
        activeMs = 0;
        totalValueCoins = 0;
        unpricedGains = 0;
        itemTotals.clear();
        haveBaseline = false;
        lastCounts.clear();
        lastNames.clear();
        save();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(DATA_PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(DATA_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            activeMs = root.has("activeMs") ? root.get("activeMs").getAsLong() : 0L;
            totalValueCoins = root.has("totalValueCoins") ? root.get("totalValueCoins").getAsDouble() : 0.0;
            unpricedGains = root.has("unpricedGains") ? root.get("unpricedGains").getAsLong() : 0L;
            if (root.has("items")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("items").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    itemTotals.put(e.getKey(), new ItemTotal(
                            o.has("name") ? o.get("name").getAsString() : e.getKey(),
                            o.has("count") ? o.get("count").getAsLong() : 0L,
                            o.has("value") ? o.get("value").getAsDouble() : 0.0));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {} - starting fresh totals", DATA_PATH, e);
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("activeMs", activeMs);
            root.addProperty("totalValueCoins", totalValueCoins);
            root.addProperty("unpricedGains", unpricedGains);
            JsonObject items = new JsonObject();
            for (Map.Entry<String, ItemTotal> e : itemTotals.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("name", e.getValue().name());
                o.addProperty("count", e.getValue().count());
                o.addProperty("value", e.getValue().value());
                items.add(e.getKey(), o);
            }
            root.add("items", items);
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Couldn't save {}", DATA_PATH, e);
        }
    }

    /** "1h 23m" / "42m" / "<1m" - active-clock display for the tab. */
    public static String formatDuration(long ms) {
        if (ms < 60_000L) {
            return "<1m";
        }
        long totalMinutes = ms / 60_000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }
}
