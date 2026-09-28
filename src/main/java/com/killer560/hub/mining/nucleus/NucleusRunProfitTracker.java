package com.killer560.hub.mining.nucleus;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.mining.profit.MiningItemPricer;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.rngmeter.RngItemNames;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crystal Nucleus Run Profit tracker (killer560, verbatim: "Nuc run profit tracker").
 * <p>
 * Scoped exactly the way the brief asks: "detect start/end from the CH location and the run's own
 * chat/scoreboard cues if the mod already tracks location". This mod already tracks location via
 * {@link IslandDetector} (ticked unconditionally by {@code pathfinding.PathfindingFeature}), so the active
 * clock only runs while {@link IslandDetector#graphIsland()} is {@code CRYSTAL_HOLLOWS} - same "pause, don't
 * reset" clock {@code mining.profit.MiningProfitTracker} uses.
 * <p>
 * "One completed run" is Hypixel's own real chat cue, not a guess: breaking the Crystal Nucleus posts a
 * loot block starting with a line containing {@code CRYSTAL NUCLEUS LOOT BUNDLE} and ending with a long row
 * of {@code ▬} characters, with one 4-space-indented "item xN" line per reward in between. Verified against
 * the public SkyHanni mod's real, working detector (github.com/hannibal002/SkyHanni, beta branch,
 * {@code features/mining/crystalhollows/CrystalNucleusApi.kt} - {@code startPattern =
 * " \s*§r§5§lCRYSTAL NUCLEUS LOOT BUNDLE.*"}, {@code endPattern = "§3§l▬{64}"}), the same kind of
 * "confirmed against a real, working detector rather than guessed" sourcing {@code IslandDetector} and
 * {@code experiments.ExperimentsProfitTracker} already use for their own patterns. Matched here on
 * COLOR-STRIPPED text rather than the exact literal codes, since {@link Component#getString()} on a real
 * server line was not something this pass could verify byte-for-byte - stripped text keeps the same very
 * low false-positive rate ("CRYSTAL NUCLEUS LOOT BUNDLE" / a run of 20+ {@code ▬}) while not depending on
 * exact color-code placement.
 * <p>
 * <b>Per-item pricing is best-effort</b>: SkyHanni's own {@code ItemUtils.readItemAmount} line format was
 * not available to copy, so the "itemName xN" / "N x itemName" line is parsed with the same two-shape
 * regex {@code experiments.ExperimentsProfitTracker} already uses for its own reward lines. A line that
 * doesn't parse, or an item this mod can't price (see {@link MiningItemPricer}), is skipped rather than
 * guessed - so {@link #getRunsCompleted()} (driven purely by the exact loot-block boundary) is exact, while
 * {@link #getTotalValueCoins()} is a lower bound. Mithril/Gemstone Powder lines are excluded on purpose,
 * same as SkyHanni: powder has no stable per-unit sale value on its own.
 */
public final class NucleusRunProfitTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-nucleus-profit");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-nucleus-profit.json");

    private static final Pattern LOOT_START = Pattern.compile("CRYSTAL NUCLEUS LOOT BUNDLE");
    private static final Pattern LOOT_END = Pattern.compile("▬{20,}");
    private static final Pattern COUNT_PREFIX = Pattern.compile("^(\\d+)x\\s+(.+)$");
    private static final Pattern COUNT_SUFFIX = Pattern.compile("^(.+?)\\s+x(\\d+)$");

    private static boolean loaded = false;
    private static boolean inLootBlock = false;
    private static long lastTickAtMs = 0L;

    // Persisted running totals.
    private static long activeMs;
    private static long runsCompleted;
    private static double totalValueCoins;

    private NucleusRunProfitTracker() {
    }

    public static void register() {
        ChatObserver.subscribe(NucleusRunProfitTracker::onChat);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("NucleusRunProfitTracker", client -> {
            try {
                tickClock();
            } catch (Exception e) {
                LOGGER.error("Nucleus run profit clock tick failed", e);
            }
        }));
    }

    private static void tickClock() {
        ensureLoaded();
        NucleusRunProfitConfig cfg = NucleusRunProfitConfig.getInstance();
        boolean active = cfg.isEnabled() && "CRYSTAL_HOLLOWS".equals(IslandDetector.graphIsland());
        long now = System.currentTimeMillis();
        if (active && lastTickAtMs != 0L) {
            long gap = now - lastTickAtMs;
            if (gap > 0 && gap <= 2_000L) {
                activeMs += gap;
            }
        }
        lastTickAtMs = now;
    }

    private static void onChat(Component message) {
        try {
            if (!NucleusRunProfitConfig.getInstance().isEnabled()
                    || !"CRYSTAL_HOLLOWS".equals(IslandDetector.graphIsland())) {
                return;
            }
            String raw = message.getString();
            String plain = ChatFormatting.stripFormatting(raw);
            if (plain == null) {
                return;
            }
            if (!inLootBlock) {
                if (LOOT_START.matcher(plain).find()) {
                    inLootBlock = true;
                }
                return;
            }
            if (LOOT_END.matcher(plain).find()) {
                inLootBlock = false;
                ensureLoaded();
                runsCompleted++;
                save();
                return;
            }
            parseLootLine(plain);
        } catch (Exception e) {
            LOGGER.error("Nucleus run profit chat handling failed", e);
        }
    }

    private static void parseLootLine(String plain) {
        if (!plain.startsWith("    ")) {
            return;
        }
        String line = plain.substring(4).trim();
        if (line.isEmpty() || line.contains(" Powder")) {
            return;
        }
        int count = 1;
        String name = line;
        Matcher prefix = COUNT_PREFIX.matcher(line);
        Matcher suffix = COUNT_SUFFIX.matcher(line);
        if (prefix.matches()) {
            count = Integer.parseInt(prefix.group(1));
            name = prefix.group(2).trim();
        } else if (suffix.matches()) {
            name = suffix.group(1).trim();
            count = Integer.parseInt(suffix.group(2));
        }
        if (name.isBlank()) {
            return;
        }
        String id = RngItemNames.BY_NAME.getOrDefault(name, toIdToken(name));
        Double unitPrice = MiningItemPricer.priceOf(id);
        if (unitPrice == null) {
            return;
        }
        ensureLoaded();
        totalValueCoins += unitPrice * count;
        save();
    }

    private static String toIdToken(String name) {
        return name.toUpperCase(Locale.US).replace("'", "").replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    // ------------------------------------------------------------------------------------------
    // Accessors for NucleusRunProfitTab
    // ------------------------------------------------------------------------------------------

    public static long getRunsCompleted() {
        ensureLoaded();
        return runsCompleted;
    }

    public static double getTotalValueCoins() {
        ensureLoaded();
        return totalValueCoins;
    }

    public static long getActiveMs() {
        ensureLoaded();
        return activeMs;
    }

    public static double getCoinsPerRun() {
        ensureLoaded();
        return runsCompleted <= 0 ? 0.0 : totalValueCoins / runsCompleted;
    }

    public static double getCoinsPerHour() {
        ensureLoaded();
        return activeMs <= 0 ? 0.0 : totalValueCoins / (activeMs / 3_600_000.0);
    }

    public static void reset() {
        ensureLoaded();
        activeMs = 0;
        runsCompleted = 0;
        totalValueCoins = 0;
        inLootBlock = false;
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
            runsCompleted = root.has("runsCompleted") ? root.get("runsCompleted").getAsLong() : 0L;
            totalValueCoins = root.has("totalValueCoins") ? root.get("totalValueCoins").getAsDouble() : 0.0;
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {} - starting fresh totals", DATA_PATH, e);
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("activeMs", activeMs);
            root.addProperty("runsCompleted", runsCompleted);
            root.addProperty("totalValueCoins", totalValueCoins);
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Couldn't save {}", DATA_PATH, e);
        }
    }
}
