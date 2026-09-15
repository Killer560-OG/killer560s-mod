package com.killer560.hub.experiments;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.rngmeter.HypixelMarketPrices;
import com.killer560.hub.rngmeter.RngItem;
import com.killer560.hub.rngmeter.RngItemNames;
import com.killer560.hub.rngmeter.RngMeterEngine;
import com.killer560.hub.rngmeter.RngSource;
import com.killer560.hub.util.ModChat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Experimentation Table profit tracker (roadmap item, 2026-09-15): logs each finished experiment
 * (Superpairs / Chronomatron / Ultrasequencer) - which game and tier, rounds reached vs rounds needed
 * for max Superpairs clicks (Chronomatron/Ultrasequencer) or clicks used vs clicks available
 * (Superpairs), the rewards claimed, their live coin value, and Enchanting Exp gained - plus running
 * totals and the Bits spent on bonus charges. Persisted to {@code killer560smod-experiments-profit.json}
 * so it survives restarts; totals shown in the Experiments tab, a summary posted via {@link ModChat}
 * the moment a session's rewards are claimed.
 * <p>
 * Purely observational - never clicks, never sends anything. Every real text matched below is taken
 * from SkyHanni 7.48.0's own {@code ExperimentationTableApi} repo patterns (read out of the real jar's
 * constant pool with javap, 2026-09-15), not guessed:
 * <ul>
 *   <li>puzzle title {@code (?<type>Superpairs|Chronomatron|Ultrasequencer) \((?<tier>.*)\)}</li>
 *   <li>reward screen title {@code Experiment [Oo]ver|Superpairs Rewards}</li>
 *   <li>the claim item's lore: {@code §7Stakes: ...}, then {@code §7Rewards:}, one
 *   {@code §8 +<reward> (Stakes|Pairs)} line per reward, ending at {@code §eClick to claim rewards!}</li>
 *   <li>chat {@code You claimed the \S+ rewards!} - the session-end trigger</li>
 *   <li>chat drop lines {@code  +<reward>} (fallback if the reward screen was never seen)</li>
 *   <li>chat {@code You bought a bonus charge for the Experimentation Table! (N/3)} - SkyHanni prices
 *   charge 1/2/3 at 150/300/500 Bits ({@code ExperimentsProfitTracker.kt})</li>
 *   <li>{@code (?<amount>(?:\d+|\d+,\d+)[MBk]?) Enchanting Exp}</li>
 * </ul>
 * Superpairs' remaining clicks come from the board's slot-4 item name {@code Remaining Clicks: N}
 * (SkyHanni {@code SuperpairDataDisplay.kt}).
 */
public final class ExperimentsProfitTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-experiments-profit");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-experiments-profit.json");
    private static final String CHAT_FEATURE = "Experiments";
    private static final int MAX_LOGGED_SESSIONS = 200;
    /** A reward-screen snapshot older than this when the claim message arrives is treated as stale
     *  (left over from an earlier, unclaimed experiment) and ignored in favor of the chat fallback. */
    private static final long SNAPSHOT_MAX_AGE_MS = 60_000L;
    /** How long after "You claimed the X rewards!" to keep collecting "+reward" chat lines when there
     *  was no reward-screen snapshot to use instead. */
    private static final long CHAT_REWARD_WINDOW_MS = 2_000L;

    private static final Pattern PUZZLE_TITLE = Pattern.compile("^(Superpairs|Chronomatron|Ultrasequencer) \\((.+)\\)$");
    private static final Pattern REWARD_SCREEN_TITLE = Pattern.compile("Experiment [Oo]ver|Superpairs Rewards");
    private static final Pattern CLAIM_MESSAGE = Pattern.compile("You claimed the (\\S+) rewards!");
    private static final Pattern BONUS_CHARGE = Pattern.compile("You bought a bonus charge for the Experimentation Table! \\((\\d)/3\\)");
    private static final Pattern REMAINING_CLICKS = Pattern.compile("Remaining Clicks: (\\d+)");
    private static final Pattern STAKES_LORE = Pattern.compile("^\\s*Stakes: (.+?)\\s*$");
    private static final Pattern REWARD_LINE = Pattern.compile("^\\s*\\+\\s*(.+?)(?:\\s\\((?:Stakes|Pairs)\\))?\\s*$");
    private static final Pattern LEVEL_PREFIX = Pattern.compile("^\\[Lvl \\d+]\\s*");
    private static final Pattern COUNT_PREFIX = Pattern.compile("^(\\d+)x\\s+(.+)$");
    private static final Pattern COUNT_SUFFIX = Pattern.compile("^(.+?)\\s+x(\\d+)$");
    private static final Pattern ENCHANTING_EXP = Pattern.compile("(\\d+(?:,\\d{3})*(?:\\.\\d+)?)([kKmMbB]?) Enchanting Exp");
    private static final Pattern ROMAN_SUFFIX = Pattern.compile("^(.+?) ([IVXLC]+)$");
    private static final Pattern ENCHANTED_BOOK_WRAPPER = Pattern.compile("^Enchanted Book \\((.+)\\)$");

    /** One in-progress experiment, from the moment its puzzle screen is seen until its rewards are
     *  claimed. */
    private static final class Pending {
        final String game;
        String tier;
        int roundsReached = 0;
        int roundsNeeded = -1;
        int superpairsMaxClicks = -1;
        int superpairsRemaining = -1;

        Pending(String game, String tier) {
            this.game = game;
            this.tier = tier;
        }
    }

    private record Reward(String name, int count, long xp, String id, Double unitPrice) {
    }

    private static Pending pending;
    private static List<String> rewardSnapshot = List.of();
    private static long rewardSnapshotAtMs = 0L;
    private static String rewardSnapshotStakes = null;
    /** Non-null while collecting "+reward" chat lines after a claim with no usable screen snapshot. */
    private static String chatWindowGame = null;
    private static long chatWindowEndsAtMs = 0L;
    private static final List<String> chatWindowLines = new ArrayList<>();

    // Running totals (persisted).
    private static boolean loaded = false;
    private static long totalSessions;
    private static final Map<String, Long> sessionsPerGame = new LinkedHashMap<>();
    private static long totalXp;
    private static double totalValueCoins;
    private static long totalBitsSpent;
    private static long trackingSinceMs;
    private static String lastSummary = "";
    private static final Map<String, JsonObject> itemTotals = new LinkedHashMap<>();
    private static final List<JsonObject> sessionLog = new ArrayList<>();

    private ExperimentsProfitTracker() {
    }

    // ------------------------------------------------------------------------------------------
    // Hooks (registered from ExperimentsFeature.register())
    // ------------------------------------------------------------------------------------------

    static void tick(Minecraft client) {
        if (!ExperimentsConfig.getInstance().isProfitTrackerEnabled()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            maybeFinishChatWindow(now);
            if (!(client.screen instanceof ContainerScreen screen)) {
                return;
            }
            String title = screen.getTitle().getString();
            ChestMenu menu = screen.getMenu();
            Matcher puzzle = PUZZLE_TITLE.matcher(title);
            if (puzzle.matches()) {
                onPuzzleScreen(puzzle.group(1), puzzle.group(2), menu);
            } else if (REWARD_SCREEN_TITLE.matcher(title).find()) {
                snapshotRewardScreen(menu, now);
            }
        } catch (Exception e) {
            LOGGER.error("Experiments profit tracker tick failed", e);
        }
    }

    static void onGameMessage(Component message) {
        if (!ExperimentsConfig.getInstance().isProfitTrackerEnabled()) {
            return;
        }
        try {
            String text = message.getString();
            long now = System.currentTimeMillis();
            if (chatWindowGame != null && now <= chatWindowEndsAtMs) {
                Matcher line = REWARD_LINE.matcher(text);
                if (line.matches() && text.trim().startsWith("+")) {
                    chatWindowLines.add(text);
                    return;
                }
            }
            Matcher charge = BONUS_CHARGE.matcher(text);
            if (charge.find()) {
                int bits = switch (Integer.parseInt(charge.group(1))) {
                    case 1 -> 150;
                    case 2 -> 300;
                    case 3 -> 500;
                    default -> 0;
                };
                ensureLoaded();
                totalBitsSpent += bits;
                save();
                return;
            }
            Matcher claim = CLAIM_MESSAGE.matcher(text);
            if (claim.find()) {
                onClaim(claim.group(1), now);
            }
        } catch (Exception e) {
            LOGGER.error("Experiments profit tracker chat handling failed", e);
        }
    }

    /** Pushed from {@link ExperimentsFeature}'s tick (only runs while the solver itself is enabled) -
     *  the solver is the only thing that actually knows the Chronomatron/Ultrasequencer chain length. */
    static void noteRounds(ExperimentSolver.Mode mode, int chainLength, int roundsNeeded) {
        if (pending == null || !ExperimentsConfig.getInstance().isProfitTrackerEnabled()) {
            return;
        }
        String game = switch (mode) {
            case CHRONOMATRON -> "Chronomatron";
            case ULTRASEQUENCER -> "Ultrasequencer";
            default -> null;
        };
        if (game == null || !game.equals(pending.game)) {
            return;
        }
        pending.roundsReached = Math.max(pending.roundsReached, chainLength);
        if (roundsNeeded > 0) {
            pending.roundsNeeded = roundsNeeded;
        }
    }

    /** @return the "Remaining Clicks: N" count from a Superpairs item name, or -1 if it isn't one. */
    static int parseRemainingClicks(String name) {
        if (name == null) {
            return -1;
        }
        Matcher m = REMAINING_CLICKS.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    // ------------------------------------------------------------------------------------------
    // Session tracking
    // ------------------------------------------------------------------------------------------

    private static void onPuzzleScreen(String game, String tier, ChestMenu menu) {
        if (pending == null || !pending.game.equals(game)) {
            if (pending != null) {
                LOGGER.info("Discarding unclaimed {} session - a new {} session started", pending.game, game);
            }
            pending = new Pending(game, tier);
        }
        pending.tier = tier;
        if (game.equals("Superpairs")) {
            int remaining = readRemainingClicks(menu);
            if (remaining >= 0) {
                if (pending.superpairsRemaining < 0) {
                    pending.superpairsMaxClicks = remaining;
                } else if (remaining > pending.superpairsRemaining) {
                    // A "Gained +N Clicks" powerup raised the budget mid-board.
                    pending.superpairsMaxClicks += remaining - pending.superpairsRemaining;
                }
                pending.superpairsRemaining = remaining;
            }
        }
    }

    private static int readRemainingClicks(ChestMenu menu) {
        int containerSlotCount = menu.slots.size() - 36;
        if (containerSlotCount > 4) {
            ItemStack slot4 = menu.slots.get(4).getItem();
            if (slot4 != null && !slot4.isEmpty()) {
                int parsed = parseRemainingClicks(slot4.getHoverName().getString());
                if (parsed >= 0) {
                    return parsed;
                }
            }
        }
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            int parsed = parseRemainingClicks(stack.getHoverName().getString());
            if (parsed >= 0) {
                return parsed;
            }
        }
        return -1;
    }

    /** Reads the claim item's "Rewards:" ... "Click to claim rewards!" lore block. */
    private static void snapshotRewardScreen(ChestMenu menu, long now) {
        int containerSlotCount = menu.slots.size() - 36;
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) continue;
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            List<String> lines = new ArrayList<>();
            for (Component line : lore.lines()) {
                lines.add(line.getString());
            }
            if (lines.stream().noneMatch(l -> l.contains("Click to claim rewards!"))) continue;

            List<String> rewards = new ArrayList<>();
            List<String> plusLinesAnywhere = new ArrayList<>();
            String stakes = null;
            boolean sawHeader = false;
            boolean inRewards = false;
            for (String line : lines) {
                Matcher stakesMatch = STAKES_LORE.matcher(line);
                if (stakesMatch.matches()) {
                    stakes = stakesMatch.group(1);
                }
                String trimmed = line.trim();
                if (trimmed.equals("Rewards:")) {
                    sawHeader = true;
                    inRewards = true;
                    continue;
                }
                if (trimmed.contains("Click to claim rewards!")) {
                    break;
                }
                if (trimmed.startsWith("+")) {
                    plusLinesAnywhere.add(line);
                    if (inRewards) {
                        rewards.add(line);
                    }
                }
            }
            if (!sawHeader) {
                // Header text not seen (Hypixel wording drift) - fall back to every "+" line above
                // the claim prompt, which is still bounded to this one claim item's lore.
                rewards = plusLinesAnywhere;
            }
            if (!rewards.isEmpty()) {
                rewardSnapshot = rewards;
                rewardSnapshotAtMs = now;
                rewardSnapshotStakes = stakes;
            }
            return;
        }
    }

    private static void onClaim(String claimedGame, long now) {
        boolean snapshotFresh = !rewardSnapshot.isEmpty() && now - rewardSnapshotAtMs <= SNAPSHOT_MAX_AGE_MS;
        if (snapshotFresh) {
            commit(claimedGame, rewardSnapshot, "reward screen");
        } else {
            chatWindowGame = claimedGame;
            chatWindowEndsAtMs = now + CHAT_REWARD_WINDOW_MS;
            chatWindowLines.clear();
        }
    }

    private static void maybeFinishChatWindow(long now) {
        if (chatWindowGame == null || now <= chatWindowEndsAtMs) {
            return;
        }
        String game = chatWindowGame;
        List<String> lines = new ArrayList<>(chatWindowLines);
        chatWindowGame = null;
        chatWindowLines.clear();
        commit(game, lines, "chat");
    }

    private static void commit(String claimedGame, List<String> rewardLines, String source) {
        ensureLoaded();
        String game = normalizeGame(claimedGame);
        Pending session = pending != null && pending.game.equals(game) ? pending : new Pending(game, rewardSnapshotStakes);
        if (session.tier == null && rewardSnapshotStakes != null) {
            session.tier = rewardSnapshotStakes;
        }
        pending = null;
        rewardSnapshot = List.of();
        rewardSnapshotStakes = null;

        List<Reward> rewards = new ArrayList<>();
        for (String line : rewardLines) {
            Reward reward = parseReward(line);
            if (reward != null) {
                rewards.add(reward);
            }
        }

        long xp = 0;
        double value = 0;
        int unpriced = 0;
        JsonArray rewardsJson = new JsonArray();
        for (Reward reward : rewards) {
            xp += reward.xp();
            JsonObject r = new JsonObject();
            r.addProperty("name", reward.name());
            r.addProperty("count", reward.count());
            if (reward.xp() > 0) {
                r.addProperty("xp", reward.xp());
            } else {
                if (reward.id() != null) {
                    r.addProperty("id", reward.id());
                }
                if (reward.unitPrice() != null) {
                    r.addProperty("unitPrice", reward.unitPrice());
                    value += reward.unitPrice() * reward.count();
                } else {
                    unpriced++;
                }
                JsonObject item = itemTotals.computeIfAbsent(reward.name(), k -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("count", 0L);
                    o.addProperty("value", 0.0);
                    return o;
                });
                item.addProperty("count", item.get("count").getAsLong() + reward.count());
                item.addProperty("value", item.get("value").getAsDouble()
                        + (reward.unitPrice() != null ? reward.unitPrice() * reward.count() : 0.0));
            }
            rewardsJson.add(r);
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("endedAtMs", System.currentTimeMillis());
        entry.addProperty("game", game);
        if (session.tier != null) entry.addProperty("tier", session.tier);
        if (game.equals("Superpairs")) {
            if (session.superpairsMaxClicks >= 0) {
                entry.addProperty("clicksUsed", session.superpairsMaxClicks - Math.max(0, session.superpairsRemaining));
                entry.addProperty("maxClicks", session.superpairsMaxClicks);
            }
        } else {
            entry.addProperty("roundsReached", session.roundsReached);
            if (session.roundsNeeded > 0) entry.addProperty("roundsNeededForMaxClicks", session.roundsNeeded);
        }
        entry.addProperty("xpGained", xp);
        entry.addProperty("valueCoins", value);
        entry.addProperty("unpricedRewards", unpriced);
        entry.addProperty("source", source);
        entry.add("rewards", rewardsJson);

        totalSessions++;
        sessionsPerGame.merge(game, 1L, Long::sum);
        totalXp += xp;
        totalValueCoins += value;
        sessionLog.add(entry);
        while (sessionLog.size() > MAX_LOGGED_SESSIONS) {
            sessionLog.remove(0);
        }
        lastSummary = game + (session.tier != null ? " (" + session.tier + ")" : "")
                + ": +" + shortNumber(xp) + " XP, " + shortNumber(value) + " coins";
        save();
        LOGGER.info("Logged {} session from {}: {} rewards, xp={}, value={}, unpriced={}",
                game, source, rewards.size(), xp, value, unpriced);
        postSummary(game, session, rewards, xp, value, unpriced);
    }

    private static void postSummary(String game, Pending session, List<Reward> rewards, long xp, double value, int unpriced) {
        List<Component> parts = new ArrayList<>();
        parts.add(ModChat.value(game));
        if (session.tier != null) {
            parts.add(ModChat.dim(" (" + session.tier + ")"));
        }
        if (game.equals("Superpairs") && session.superpairsMaxClicks >= 0) {
            int used = session.superpairsMaxClicks - Math.max(0, session.superpairsRemaining);
            parts.add(ModChat.text(" - clicks "));
            parts.add(ModChat.value(used + "/" + session.superpairsMaxClicks));
        } else if (!game.equals("Superpairs") && session.roundsReached > 0) {
            parts.add(ModChat.text(" - rounds "));
            parts.add(ModChat.value(session.roundsReached + (session.roundsNeeded > 0 ? "/" + session.roundsNeeded : "")));
        }
        parts.add(ModChat.text(" | "));
        parts.add(ModChat.value("+" + shortNumber(xp)));
        parts.add(ModChat.text(" XP | "));
        parts.add(ModChat.value(shortNumber(value)));
        parts.add(ModChat.text(" coins"));
        if (unpriced > 0) {
            parts.add(ModChat.dim(" (" + unpriced + " unpriced)"));
        }
        ModChat.send(CHAT_FEATURE, parts.toArray(new Component[0]));

        List<String> named = new ArrayList<>();
        for (Reward reward : rewards) {
            if (reward.xp() > 0) continue;
            named.add((reward.count() > 1 ? reward.count() + "x " : "") + reward.name()
                    + (reward.unitPrice() != null ? " (" + shortNumber(reward.unitPrice() * reward.count()) + ")" : ""));
        }
        if (!named.isEmpty()) {
            ModChat.send(CHAT_FEATURE, ModChat.text("Rewards: "), ModChat.value(String.join(", ", named)));
        }
    }

    private static String normalizeGame(String claimed) {
        String lower = claimed == null ? "" : claimed.toLowerCase(Locale.US);
        if (lower.contains("superpair")) return "Superpairs";
        if (lower.contains("chronomatron")) return "Chronomatron";
        if (lower.contains("ultrasequencer")) return "Ultrasequencer";
        return pending != null ? pending.game : (claimed == null ? "Unknown" : claimed);
    }

    // ------------------------------------------------------------------------------------------
    // Reward parsing / pricing
    // ------------------------------------------------------------------------------------------

    private static Reward parseReward(String rawLine) {
        Matcher m = REWARD_LINE.matcher(rawLine);
        if (!m.matches()) {
            return null;
        }
        String name = LEVEL_PREFIX.matcher(m.group(1).trim()).replaceFirst("").trim();
        if (name.isEmpty()) {
            return null;
        }
        int count = 1;
        Matcher prefix = COUNT_PREFIX.matcher(name);
        Matcher suffix = COUNT_SUFFIX.matcher(name);
        if (prefix.matches()) {
            count = Integer.parseInt(prefix.group(1));
            name = prefix.group(2).trim();
        } else if (suffix.matches()) {
            count = Integer.parseInt(suffix.group(2));
            name = suffix.group(1).trim();
        }

        Matcher xp = ENCHANTING_EXP.matcher(name);
        if (xp.find()) {
            return new Reward(name, 1, parseAmount(xp.group(1), xp.group(2)), null, null);
        }

        String id = resolveId(name);
        Double price = id == null ? null : priceOf(name, id);
        return new Reward(name, count, 0L, id, price);
    }

    private static long parseAmount(String digits, String unit) {
        double base = Double.parseDouble(digits.replace(",", ""));
        double mult = switch (unit.toLowerCase(Locale.US)) {
            case "k" -> 1_000d;
            case "m" -> 1_000_000d;
            case "b" -> 1_000_000_000d;
            default -> 1d;
        };
        return Math.round(base * mult);
    }

    /** Best-effort display name -> Hypixel internal id. Unresolvable names just stay unpriced. */
    private static String resolveId(String name) {
        HypixelMarketPrices prices = RngMeterEngine.PRICES;
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith("experience bottle")) {
            if (lower.startsWith("colossal")) return "COLOSSAL_EXP_BOTTLE";
            if (lower.startsWith("titanic")) return "TITANIC_EXP_BOTTLE";
            if (lower.startsWith("grand")) return "GRAND_EXP_BOTTLE";
            return "EXP_BOTTLE";
        }
        if (lower.equals("guardian")) {
            // Pet auctions are keyed "PET_<TYPE>" by HypixelMarketPrices regardless of rarity, so this
            // is the lowest BIN across every Guardian rarity - an underestimate for a rarer drop.
            return "PET_GUARDIAN";
        }
        String mapped = RngItemNames.BY_NAME.get(name);
        if (mapped != null) {
            return mapped;
        }
        String bookName = name;
        Matcher wrapper = ENCHANTED_BOOK_WRAPPER.matcher(name);
        if (wrapper.matches()) {
            bookName = wrapper.group(1);
        }
        Matcher roman = ROMAN_SUFFIX.matcher(bookName);
        if (roman.matches()) {
            int level = romanToInt(roman.group(2));
            if (level > 0) {
                String base = toIdToken(roman.group(1));
                String candidate = "ENCHANTMENT_" + base + "_" + level;
                if (prices.hasBazaarProduct(candidate)) {
                    return candidate;
                }
                String ultimate = "ENCHANTMENT_ULTIMATE_" + base + "_" + level;
                if (prices.hasBazaarProduct(ultimate)) {
                    return ultimate;
                }
                if (wrapper.matches()) {
                    return candidate;
                }
            }
        }
        return toIdToken(name);
    }

    private static Double priceOf(String name, String id) {
        RngSource source = id.startsWith("PET_") ? RngSource.AH : RngSource.BAZAAR;
        Long price = RngMeterEngine.PRICES.getPrice(new RngItem("Experiments", name, id, source, 0L, false));
        return price == null ? null : price.doubleValue();
    }

    private static String toIdToken(String name) {
        return name.toUpperCase(Locale.US).replace("'", "").replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static int romanToInt(String roman) {
        int total = 0;
        int prev = 0;
        for (int i = roman.length() - 1; i >= 0; i--) {
            int v = switch (roman.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            if (v == 0) return -1;
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    static String shortNumber(double value) {
        double abs = Math.abs(value);
        if (abs >= 1_000_000_000d) return String.format(Locale.US, "%.2fB", value / 1_000_000_000d);
        if (abs >= 1_000_000d) return String.format(Locale.US, "%.2fM", value / 1_000_000d);
        if (abs >= 1_000d) return String.format(Locale.US, "%.1fk", value / 1_000d);
        return String.format(Locale.US, "%.0f", value);
    }

    // ------------------------------------------------------------------------------------------
    // Totals accessors (for ExperimentsTab) + persistence
    // ------------------------------------------------------------------------------------------

    public static long getTotalSessions() {
        ensureLoaded();
        return totalSessions;
    }

    public static long getSessionsFor(String game) {
        ensureLoaded();
        return sessionsPerGame.getOrDefault(game, 0L);
    }

    public static long getTotalXp() {
        ensureLoaded();
        return totalXp;
    }

    public static double getTotalValueCoins() {
        ensureLoaded();
        return totalValueCoins;
    }

    public static long getTotalBitsSpent() {
        ensureLoaded();
        return totalBitsSpent;
    }

    public static String getLastSummary() {
        ensureLoaded();
        return lastSummary;
    }

    public static String formatShort(double value) {
        return shortNumber(value);
    }

    public static void reset() {
        ensureLoaded();
        totalSessions = 0;
        sessionsPerGame.clear();
        totalXp = 0;
        totalValueCoins = 0;
        totalBitsSpent = 0;
        lastSummary = "";
        itemTotals.clear();
        sessionLog.clear();
        trackingSinceMs = System.currentTimeMillis();
        pending = null;
        rewardSnapshot = List.of();
        chatWindowGame = null;
        chatWindowLines.clear();
        save();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        trackingSinceMs = System.currentTimeMillis();
        if (!Files.exists(DATA_PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(DATA_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject totals = root.has("totals") ? root.getAsJsonObject("totals") : new JsonObject();
            totalSessions = totals.has("sessions") ? totals.get("sessions").getAsLong() : 0;
            totalXp = totals.has("xpGained") ? totals.get("xpGained").getAsLong() : 0;
            totalValueCoins = totals.has("rewardValueCoins") ? totals.get("rewardValueCoins").getAsDouble() : 0;
            totalBitsSpent = totals.has("bitsSpent") ? totals.get("bitsSpent").getAsLong() : 0;
            trackingSinceMs = totals.has("trackingSinceMs") ? totals.get("trackingSinceMs").getAsLong() : trackingSinceMs;
            lastSummary = totals.has("lastSummary") ? totals.get("lastSummary").getAsString() : "";
            if (totals.has("sessionsPerGame")) {
                for (Map.Entry<String, JsonElement> e : totals.getAsJsonObject("sessionsPerGame").entrySet()) {
                    sessionsPerGame.put(e.getKey(), e.getValue().getAsLong());
                }
            }
            if (totals.has("items")) {
                for (Map.Entry<String, JsonElement> e : totals.getAsJsonObject("items").entrySet()) {
                    itemTotals.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
            if (root.has("sessionLog")) {
                for (JsonElement e : root.getAsJsonArray("sessionLog")) {
                    sessionLog.add(e.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {} - starting fresh totals", DATA_PATH, e);
        }
    }

    private static void save() {
        try {
            JsonObject totals = new JsonObject();
            totals.addProperty("sessions", totalSessions);
            JsonObject perGame = new JsonObject();
            sessionsPerGame.forEach(perGame::addProperty);
            totals.add("sessionsPerGame", perGame);
            totals.addProperty("xpGained", totalXp);
            totals.addProperty("rewardValueCoins", totalValueCoins);
            totals.addProperty("bitsSpent", totalBitsSpent);
            totals.addProperty("trackingSinceMs", trackingSinceMs);
            totals.addProperty("lastSummary", lastSummary);
            JsonObject items = new JsonObject();
            itemTotals.forEach(items::add);
            totals.add("items", items);

            JsonArray log = new JsonArray();
            sessionLog.forEach(log::add);

            JsonObject root = new JsonObject();
            root.add("totals", totals);
            root.add("sessionLog", log);
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Couldn't save {}", DATA_PATH, e);
        }
    }
}
