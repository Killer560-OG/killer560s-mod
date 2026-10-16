package com.killer560.hub.experiments;

import com.killer560.hub.util.ModPaths;
import com.killer560.hub.gui.profit.ProfitScope;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

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
import com.killer560.hub.compat.McCompat;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-experiments-profit");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH =
            ModPaths.config("killer560smod-experiments-profit.json");
    private static final String CHAT_FEATURE = "Experiments";
    private static final int MAX_LOGGED_SESSIONS = 1000;
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

    /** Running totals for one Minecraft account + Skyblock profile + mod profile ({@link ProfitScope.Key}).
     *  killer560, 2026-10-11: each of the three is "tracked individually", and the /profit screen sums the
     *  buckets its switches select. */
    private static final class Bucket {
        long sessions;
        final Map<String, Long> sessionsPerGame = new LinkedHashMap<>();
        long xp;
        double valueCoins;
        long bitsSpent;
        final Map<String, JsonObject> items = new LinkedHashMap<>();

        JsonObject toJson(ProfitScope.Key key) {
            JsonObject o = new JsonObject();
            key.writeTo(o);
            o.addProperty("sessions", sessions);
            JsonObject perGame = new JsonObject();
            sessionsPerGame.forEach(perGame::addProperty);
            o.add("sessionsPerGame", perGame);
            o.addProperty("xpGained", xp);
            o.addProperty("rewardValueCoins", valueCoins);
            o.addProperty("bitsSpent", bitsSpent);
            JsonObject it = new JsonObject();
            items.forEach(it::add);
            o.add("items", it);
            return o;
        }

        static Bucket fromJson(JsonObject o) {
            Bucket b = new Bucket();
            b.sessions = o.has("sessions") ? o.get("sessions").getAsLong() : 0;
            b.xp = o.has("xpGained") ? o.get("xpGained").getAsLong() : 0;
            b.valueCoins = o.has("rewardValueCoins") ? o.get("rewardValueCoins").getAsDouble() : 0;
            b.bitsSpent = o.has("bitsSpent") ? o.get("bitsSpent").getAsLong() : 0;
            if (o.has("sessionsPerGame")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("sessionsPerGame").entrySet()) {
                    b.sessionsPerGame.put(e.getKey(), e.getValue().getAsLong());
                }
            }
            if (o.has("items")) {
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("items").entrySet()) {
                    b.items.put(e.getKey(), e.getValue().getAsJsonObject());
                }
            }
            return b;
        }

        void mergeFrom(Bucket o) {
            sessions += o.sessions;
            o.sessionsPerGame.forEach((g, n) -> sessionsPerGame.merge(g, n, Long::sum));
            xp += o.xp;
            valueCoins += o.valueCoins;
            bitsSpent += o.bitsSpent;
            for (Map.Entry<String, JsonObject> e : o.items.entrySet()) {
                JsonObject mine = items.get(e.getKey());
                if (mine == null) {
                    items.put(e.getKey(), e.getValue().deepCopy());
                } else {
                    mine.addProperty("count", mine.get("count").getAsLong() + e.getValue().get("count").getAsLong());
                    mine.addProperty("value", mine.get("value").getAsDouble() + e.getValue().get("value").getAsDouble());
                }
            }
        }

        boolean isEmpty() {
            return sessions == 0 && xp == 0 && valueCoins == 0 && bitsSpent == 0 && items.isEmpty();
        }
    }

    // Running totals (persisted).
    private static boolean loaded = false;
    /** Whether {@link #migrateAccounts} has run on what was loaded. */
    private static boolean accountsMigrated = false;
    private static final Map<ProfitScope.Key, Bucket> buckets = new LinkedHashMap<>();
    /** What the /profit screen shows (persisted with the data, so it is not a profile setting). */
    private static ProfitScope.Filter view = new ProfitScope.Filter();
    private static long trackingSinceMs;
    private static String lastSummary = "";
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
            if (!(McCompat.screen(client) instanceof ContainerScreen screen)) {
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
            String text = com.killer560.hub.util.ChatObserver.raw(message);
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
                bucket(ProfitScope.current()).bitsSpent += bits;
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
        ProfitScope.Key key = ProfitScope.current();
        Bucket bucket = bucket(key);
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
                JsonObject item = bucket.items.computeIfAbsent(reward.name(), k -> {
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
        key.writeTo(entry);
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

        bucket.sessions++;
        bucket.sessionsPerGame.merge(game, 1L, Long::sum);
        bucket.xp += xp;
        bucket.valueCoins += value;
        sessionLog.add(entry);
        while (sessionLog.size() > MAX_LOGGED_SESSIONS) {
            sessionLog.remove(0);
        }
        lastSummary = game + (session.tier != null ? " (" + session.tier + ")" : "")
                + ": +" + shortNumber(xp) + " XP, " + shortNumber(value) + " coins";
        save();
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
                String base = enchantIdToken(roman.group(1));
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

    /** Real bug found (2026-09-21) from killer560's "sometimes the autoetable misses some of the
     *  enchants": for a handful of enchants Hypixel's lore prints under a DIFFERENT name than the
     *  enchant's own internal id, {@code toIdToken} alone builds the wrong bazaar product id from the
     *  printed name - e.g. "Gravity VI" (the id is really {@code dragon_hunter}) built
     *  "ENCHANTMENT_GRAVITY_6", which is never a real product, so {@link #priceOf} always came back null
     *  for that one specific enchant while every other book on the same board priced fine. That made the
     *  session summary look like it "didn't recognize" Gravity/Drain/Woodsplitter/Pyroclasm books as
     *  enchants at all, next to correctly-priced books of every other kind - the exact same root cause
     *  (a name GUESSED from the id instead of matched against Hypixel's real one) already found and
     *  fixed for tooltip colouring in {@code EnchantColorsFeature#ID_TO_LORE_NAME} (id -&gt; printed name).
     *  This is that table's inverse (printed name -&gt; id), rebuilt here rather than reused because
     *  {@code ID_TO_LORE_NAME} is package-private to {@code enchantcolors} and out of scope for this file
     *  to touch - same source list (SkyHanni's repo constants), so the two stay in sync by construction,
     *  not by import. */
    private static final Map<String, String> ENCHANT_LORE_NAME_TO_ID = buildEnchantLoreNameToId();

    private static Map<String, String> buildEnchantLoreNameToId() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("counter-strike", "COUNTER_STRIKE");
        m.put("dragon tracer", "AIMING");
        m.put("drain", "SYPHON");
        m.put("gravity", "DRAGON_HUNTER");
        m.put("hardened vitality", "HARDENED_MANA");
        m.put("prismatic", "PRISTINE");
        m.put("pyroclasm", "MAGMARIZER");
        m.put("strong vitality", "STRONG_MANA");
        m.put("triple-strike", "TRIPLE_STRIKE");
        m.put("turbo-cacti", "TURBO_CACTUS");
        m.put("turbo-cocoa", "TURBO_COCO");
        m.put("vampiric vitality", "MANA_VAMPIRE");
        m.put("vivacious vitality", "FEROCIOUS_MANA");
        m.put("woodsplitter", "ARCANE");
        return m;
    }

    /** Like {@link #toIdToken} but checks the real id-alias table first, for enchants whose printed
     *  name doesn't match their internal id (see {@link #ENCHANT_LORE_NAME_TO_ID}'s doc). */
    private static String enchantIdToken(String loreName) {
        String alias = ENCHANT_LORE_NAME_TO_ID.get(loreName.trim().toLowerCase(Locale.US));
        return alias != null ? alias : toIdToken(loreName);
    }

    static int romanToInt(String roman) {
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

    /** The buckets a filter selects. */
    private static List<Bucket> selected(ProfitScope.Filter f) {
        ensureLoaded();
        List<Bucket> out = new ArrayList<>();
        for (Map.Entry<ProfitScope.Key, Bucket> e : buckets.entrySet()) {
            if (e.getKey().matches(f)) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    private static Bucket bucket(ProfitScope.Key key) {
        return buckets.computeIfAbsent(key, k -> new Bucket());
    }

    /** What the /profit screen currently shows; change its fields and call {@link #saveView()}. */
    public static ProfitScope.Filter view() {
        ensureLoaded();
        return view;
    }

    /** What the /profit screen shows: {@link #view()} with Show Profit and Profit Data applied
     *  ({@link ProfitScope#resolve}). The no-argument getters all read this. */
    public static ProfitScope.Filter scope() {
        return ProfitScope.resolve(view());
    }

    public static void saveView() {
        ensureLoaded();
        save();
    }

    /** Every account / profile / mod profile there is data for, for the screen's switches. */
    public static List<ProfitScope.Key> keys() {
        ensureLoaded();
        java.util.LinkedHashSet<ProfitScope.Key> out = new java.util.LinkedHashSet<>(buckets.keySet());
        for (JsonObject o : sessionLog) {
            out.add(ProfitScope.Key.readFrom(o));
        }
        return List.copyOf(out);
    }

    /** The no-argument getters read what the /profit screen's switches select ({@link #view()}). */
    public static long getTotalSessions() {
        return getTotalSessions(scope());
    }

    public static long getTotalSessions(ProfitScope.Filter f) {
        long n = 0;
        for (Bucket b : selected(f)) {
            n += b.sessions;
        }
        return n;
    }

    public static long getSessionsFor(String game) {
        return getSessionsFor(game, scope());
    }

    public static long getSessionsFor(String game, ProfitScope.Filter f) {
        long n = 0;
        for (Bucket b : selected(f)) {
            n += b.sessionsPerGame.getOrDefault(game, 0L);
        }
        return n;
    }

    public static long getTotalXp() {
        return getTotalXp(scope());
    }

    public static long getTotalXp(ProfitScope.Filter f) {
        long n = 0;
        for (Bucket b : selected(f)) {
            n += b.xp;
        }
        return n;
    }

    public static double getTotalValueCoins() {
        return getTotalValueCoins(scope());
    }

    public static double getTotalValueCoins(ProfitScope.Filter f) {
        double n = 0;
        for (Bucket b : selected(f)) {
            n += b.valueCoins;
        }
        return n;
    }

    public static long getTotalBitsSpent() {
        return getTotalBitsSpent(scope());
    }

    public static long getTotalBitsSpent(ProfitScope.Filter f) {
        long n = 0;
        for (Bucket b : selected(f)) {
            n += b.bitsSpent;
        }
        return n;
    }

    public static String getLastSummary() {
        ensureLoaded();
        return lastSummary;
    }

    public static String formatShort(double value) {
        return shortNumber(value);
    }

    /** Up to {@code max} highest-value rewards claimed in the selected buckets, highest first, as "name xN (value)". */
    public static List<String> topItemLines(int max) {
        Map<String, double[]> sum = new LinkedHashMap<>();
        for (Bucket b : selected(scope())) {
            for (Map.Entry<String, JsonObject> e : b.items.entrySet()) {
                double[] cv = sum.computeIfAbsent(e.getKey(), k -> new double[2]);
                cv[0] += e.getValue().get("count").getAsLong();
                cv[1] += e.getValue().get("value").getAsDouble();
            }
        }
        return sum.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                .limit(max)
                .map(e -> e.getKey() + " x" + (long) e.getValue()[0] + " (" + shortNumber(e.getValue()[1]) + ")")
                .toList();
    }

    /** Coin value of every logged session the switches select, oldest first, for the /profit graph. */
    public static long[] sessionValues() {
        ensureLoaded();
        List<Long> vals = new ArrayList<>();
        ProfitScope.Filter scope = scope();
        for (JsonObject o : sessionLog) {
            if (ProfitScope.Key.readFrom(o).matches(scope)) {
                vals.add(o.has("valueCoins") ? Math.round(o.get("valueCoins").getAsDouble()) : 0L);
            }
        }
        long[] out = new long[vals.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = vals.get(i);
        }
        return out;
    }

    /** Clears everything, every account and profile. */
    public static void reset() {
        reset(new ProfitScope.Filter());
    }

    /** Clears the totals and logged sessions the filter selects and nothing else; an all-null filter is everything. */
    public static void reset(ProfitScope.Filter f) {
        ensureLoaded();
        buckets.keySet().removeIf(k -> k.matches(f));
        sessionLog.removeIf(o -> ProfitScope.Key.readFrom(o).matches(f));
        if (f.isAll()) {
            lastSummary = "";
            trackingSinceMs = System.currentTimeMillis();
        }
        pending = null;
        rewardSnapshot = List.of();
        chatWindowGame = null;
        chatWindowLines.clear();
        save();
    }

    /** Reads the file again (the testkit's migration case; nothing in the mod needs it). */
    public static synchronized void reload() {
        loaded = false;
        accountsMigrated = false;
        buckets.clear();
        sessionLog.clear();
        view = new ProfitScope.Filter();
        lastSummary = "";
        ensureLoaded();
    }

    private static void ensureLoaded() {
        if (loaded) {
            migrateAccounts();
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
            trackingSinceMs = totals.has("trackingSinceMs") ? totals.get("trackingSinceMs").getAsLong() : trackingSinceMs;
            lastSummary = totals.has("lastSummary") ? totals.get("lastSummary").getAsString() : "";
            if (root.has("buckets")) {
                for (JsonElement e : root.getAsJsonArray("buckets")) {
                    JsonObject o = e.getAsJsonObject();
                    buckets.put(ProfitScope.Key.readFrom(o), Bucket.fromJson(o));
                }
            } else {
                // A file from before the account / profile switches: its totals become one bucket with no names.
                Bucket legacy = Bucket.fromJson(totals);
                if (!legacy.isEmpty()) {
                    buckets.put(new ProfitScope.Key(null, null, null), legacy);
                }
            }
            if (root.has("view") && root.get("view").isJsonObject()) {
                view = ProfitScope.Filter.fromJson(root.getAsJsonObject("view"));
                view.account = null; // the account shown is Show Profit's (ProfitConfig), not this file's
            }
            if (root.has("sessionLog")) {
                for (JsonElement e : root.getAsJsonArray("sessionLog")) {
                    sessionLog.add(e.getAsJsonObject());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Couldn't read {} - starting fresh totals", DATA_PATH, e);
        }
        migrateAccounts();
    }

    /** Gives every bucket and logged session without an account UUID (or mod profile) one, as
     *  {@link ProfitScope#migrated} decides, once the current account is known - the single pot from before accounts
     *  were kept becomes the current account's. Saves when anything changed. */
    private static void migrateAccounts() {
        if (accountsMigrated || !ProfitScope.canMigrate()) {
            return;
        }
        accountsMigrated = true;
        int changed = rekey(ProfitScope::migrated);
        if (changed > 0) {
            LOGGER.info("Gave {} older experiment record(s) an account", changed);
            save();
        }
    }

    /** Moves every bucket and logged session to {@code map(key)}, merging buckets that land on one key. */
    private static int rekey(java.util.function.UnaryOperator<ProfitScope.Key> map) {
        int changed = 0;
        Map<ProfitScope.Key, Bucket> moved = new LinkedHashMap<>();
        for (Map.Entry<ProfitScope.Key, Bucket> e : buckets.entrySet()) {
            ProfitScope.Key to = map.apply(e.getKey());
            if (!to.equals(e.getKey())) {
                changed++;
            }
            Bucket into = moved.get(to);
            if (into == null) {
                moved.put(to, e.getValue());
            } else {
                into.mergeFrom(e.getValue());
            }
        }
        buckets.clear();
        buckets.putAll(moved);
        for (JsonObject o : sessionLog) {
            ProfitScope.Key k = ProfitScope.Key.readFrom(o);
            ProfitScope.Key to = map.apply(k);
            if (!to.equals(k)) {
                to.writeTo(o);
                changed++;
            }
        }
        return changed;
    }

    /** A mod profile was renamed: its totals, logged sessions and the screen's switch follow the new name. */
    public static synchronized void renameModProfile(String oldName, String newName) {
        ensureLoaded();
        int changed = rekey(k -> k.modProfile().equals(oldName) ? k.withModProfile(newName) : k);
        if (oldName.equals(view.modProfile)) {
            view.modProfile = newName;
            changed++;
        }
        if (changed > 0) {
            save();
        }
    }

    private static void save() {
        try {
            JsonObject totals = new JsonObject();
            totals.addProperty("trackingSinceMs", trackingSinceMs);
            totals.addProperty("lastSummary", lastSummary);

            JsonArray bucketsJson = new JsonArray();
            buckets.forEach((k, b) -> bucketsJson.add(b.toJson(k)));

            JsonArray log = new JsonArray();
            sessionLog.forEach(log::add);

            JsonObject root = new JsonObject();
            root.addProperty("version", 2);
            root.add("totals", totals);
            root.add("buckets", bucketsJson);
            root.add("view", view.toJson());
            root.add("sessionLog", log);
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Couldn't save {}", DATA_PATH, e);
        }
    }
}
