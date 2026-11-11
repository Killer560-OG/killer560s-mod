package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.islands.IslandHud;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carnival Tracker (SkyHanni's Carnival Goals and daily ticket reminder, and its custom scoreboard's Carnival lines): in
 * the Carnival, a HUD with the game you are playing (score, fruits, catch streak, kills, accuracy, time left), your
 * Carnival Tokens and what this session added, games played this session, your best score in each game, the goals not
 * yet reached from Chantelle's "Carnival Goals" menu, and whether today's 25 Carnival Tickets are still to claim from
 * the Carnival Leader (with a chat reminder when you walk in). Display only.
 *
 * <p>Unverified in game (SkyHanni's and the wiki's text): "You earned N Carnival Tokens!", "You claimed Carnival Ticket
 * x25!", the Carnival Leader's "already claimed" line, the goal lore and its "COMPLETE" line, the goal chat lines.
 */
public final class CarnivalTracker {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final Pattern EARNED = Pattern.compile("^\\s{0,40}You earned ([\\d,]{1,7}) Carnival Tokens!$");
    static final Pattern CLAIMED = Pattern.compile("^You claimed Carnival Ticket x\\d{1,3}!$");
    static final Pattern ALREADY = Pattern.compile("^\\[NPC] Carnival Leader: You've already claimed your Carnival Tickets"
            + " for today, but I'm happy to answer any questions you might have\\.?$");
    static final String GOALS_TITLE = "Carnival Goals";

    private static long sessionStartTokens = -1;
    private static long sessionEarned;
    private static int sessionGames;
    private static final Map<CarnivalState.Game, Integer> SESSION_BEST = new EnumMap<>(CarnivalState.Game.class);
    private static boolean wasInCarnival;
    private static String remindedDay = "";
    private static int goalsReads;

    private CarnivalTracker() {
    }

    public static void register() {
        CarnivalState.register();
        CarnivalHistory.warm();
        com.killer560.hub.shared.SharedRecords.onAccountChange(CarnivalHistory::warm);
        CarnivalState.addDemand(() -> CarnivalTrackerConfig.getInstance().isEnabledRaw());
        CarnivalState.onGameEnd(CarnivalTracker::onGameEnd);
        ChatObserver.subscribe(CarnivalTracker::onChat);
        int bit = FeatureBits.register("Carnival Tracker", () -> CarnivalTrackerConfig.getInstance().isEnabledRaw());
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CarnivalTracker.tick", bit, CarnivalTracker::tick));
    }

    private static String today() {
        return LocalDate.now(ZoneOffset.UTC).toString();
    }

    static boolean ticketsReady() {
        return !today().equals(CarnivalHistory.get().ticketsClaimedDay);
    }

    private static void onGameEnd(CarnivalState.Game game, int score, long durationMs) {
        if (!CarnivalTrackerConfig.getInstance().isEnabled()) {
            return;
        }
        // A game seen for a few seconds only (the mod switched on mid-game, a sidebar flicker) is not a game played.
        if (durationMs < 5000L) {
            return;
        }
        sessionGames++;
        CarnivalHistory.Data d = CarnivalHistory.get();
        d.played.merge(game.name(), 1, Integer::sum);
        if (score > 0) {
            SESSION_BEST.merge(game, score, Math::max);
            d.best.merge(game.name(), score, Math::max);
        }
        CarnivalHistory.save();
        LOGGER.info("[Carnival] {} ended: score {}, {}s", game.label, score, durationMs / 1000);
    }

    private static void onChat(Component message) {
        if (!CarnivalTrackerConfig.getInstance().isEnabled()) {
            return;
        }
        String plain = ChatObserver.strip(message);
        if (plain == null || plain.length() > 140) {
            return;
        }
        Matcher m = EARNED.matcher(plain);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1).replace(",", ""));
            sessionEarned += n;
            CarnivalHistory.get().tokensEarned += n;
            CarnivalHistory.save();
            return;
        }
        if (CLAIMED.matcher(plain).matches() || ALREADY.matcher(plain).matches()) {
            CarnivalHistory.get().ticketsClaimedDay = today();
            CarnivalHistory.save();
            return;
        }
        CarnivalGoal g = CarnivalGoal.fromChat(plain);
        if (g != null) {
            CarnivalHistory.get().goals.put(g.name(), true);
            CarnivalHistory.save();
        }
    }

    private static void tick(Minecraft client) {
        CarnivalTrackerConfig cfg = CarnivalTrackerConfig.getInstance();
        if (!cfg.isEnabled() || client.player == null) {
            cached = List.of();
            return;
        }
        long tokens = CarnivalState.tokens();
        if (tokens >= 0 && sessionStartTokens < 0) {
            sessionStartTokens = tokens;
        }
        boolean in = CarnivalState.inCarnival();
        if (in && !wasInCarnival && cfg.isTicketReminder() && ticketsReady() && !today().equals(remindedDay)) {
            remindedDay = today();
            ModChat.send("Carnival Tracker", ModChat.text("Today's Carnival Tickets are ready at the Carnival Leader."));
        }
        wasInCarnival = in;
        Screen screen = McCompat.screen(client);
        if (screen instanceof AbstractContainerScreen<?> container
                && GOALS_TITLE.equals(ChatObserver.stripCodes(container.getTitle().getString()).trim())) {
            readGoals(container);
        }
        cached = build();
    }

    /** Reads each goal item's state from Chantelle's open "Carnival Goals" menu. */
    static void readGoals(AbstractContainerScreen<?> container) {
        int found = 0;
        Map<String, Boolean> goals = CarnivalHistory.get().goals;
        boolean changed = false;
        for (Slot slot : container.getMenu().slots) {
            if (slot.container instanceof Inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            CarnivalState.Game game = CarnivalGoal.gameOfItem(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            if (game == CarnivalState.Game.NONE) {
                continue;
            }
            List<String> lore = DungeonChestValuer.cleanLore(stack);
            if (lore.isEmpty()) {
                continue;
            }
            StringBuilder joined = new StringBuilder();
            for (String line : lore) {
                if (line.isEmpty()) {
                    break;
                }
                if (joined.length() > 0) {
                    joined.append(' ');
                }
                joined.append(line);
            }
            CarnivalGoal g = CarnivalGoal.fromLore(game, joined.toString());
            if (g == null) {
                continue;
            }
            boolean done = "COMPLETE".equals(lore.get(lore.size() - 1));
            found++;
            if (!Boolean.valueOf(done).equals(goals.get(g.name()))) {
                goals.put(g.name(), done);
                changed = true;
            }
        }
        if (found > 0) {
            CarnivalHistory.Data d = CarnivalHistory.get();
            if (changed || System.currentTimeMillis() - d.goalsReadAtMs > 60_000L) {
                d.goalsReadAtMs = System.currentTimeMillis();
                CarnivalHistory.save();
                goalsReads++;
            }
        }
    }

    /** Goals not reached, per game, from the last read (empty when no read in the last six days: a new Carnival). */
    static Map<CarnivalState.Game, List<CarnivalGoal>> goalsLeft() {
        Map<CarnivalState.Game, List<CarnivalGoal>> out = new EnumMap<>(CarnivalState.Game.class);
        CarnivalHistory.Data d = CarnivalHistory.get();
        if (d.goals.isEmpty() || System.currentTimeMillis() - d.goalsReadAtMs > 6L * 24 * 3600_000L) {
            return out;
        }
        for (CarnivalGoal g : CarnivalGoal.values()) {
            Boolean done = d.goals.get(g.name());
            if (done != null && !done) {
                out.computeIfAbsent(g.game, k -> new ArrayList<>()).add(g);
            }
        }
        return out;
    }

    private static String num(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /** The HUD's lines, rebuilt once a tick (the HUD draws every frame). */
    private static List<String> cached = List.of();

    static List<String> lines() {
        return cached;
    }

    static List<String> build() {
        CarnivalTrackerConfig cfg = CarnivalTrackerConfig.getInstance();
        if (!cfg.isEnabled() || !CarnivalState.inCarnival()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("§6§lCarnival");
        CarnivalState.Game game = CarnivalState.game();
        if (cfg.isShowGame() && game != CarnivalState.Game.NONE) {
            StringBuilder s = new StringBuilder("§e" + game.label);
            if (CarnivalState.score() >= 0) {
                s.append(" §7Score: §f").append(num(CarnivalState.score()));
            }
            out.add(s.toString());
            switch (game) {
                case FRUIT_DIGGING -> {
                    if (CarnivalState.fruitsFound() >= 0) {
                        out.add(" §7Fruits: §f" + CarnivalState.fruitsFound() + "/" + CarnivalState.fruitsTotal());
                    }
                }
                case CATCH_A_FISH -> {
                    if (CarnivalState.streak() >= 0) {
                        out.add(" §7Catch Streak: §f" + CarnivalState.streak());
                    }
                }
                case ZOMBIE_SHOOTOUT -> {
                    if (CarnivalState.kills() >= 0) {
                        out.add(" §7Kills: §f" + CarnivalState.kills()
                                + (CarnivalState.accuracy() >= 0 ? " §7Accuracy: §f" + CarnivalState.accuracy() + "%" : ""));
                    }
                }
                default -> {
                }
            }
            if (!CarnivalState.timeLeft().isEmpty()) {
                out.add(" §7Time Left: §f" + CarnivalState.timeLeft());
            }
        }
        long tokens = CarnivalState.tokens();
        if (tokens >= 0) {
            long gained = sessionStartTokens >= 0 ? tokens - sessionStartTokens : 0;
            out.add("§7Tokens: §e" + num(tokens) + (gained > 0 ? " §a(+" + num(gained) + ")" : ""));
        }
        if (cfg.isShowSession()) {
            out.add("§7Session: §f" + sessionGames + " game" + (sessionGames == 1 ? "" : "s")
                    + (sessionEarned > 0 ? " §7earned §e" + num(sessionEarned) + " tokens" : ""));
        }
        if (cfg.isShowBest()) {
            Map<String, Integer> best = CarnivalHistory.get().best;
            for (CarnivalState.Game g : new CarnivalState.Game[]{CarnivalState.Game.FRUIT_DIGGING,
                    CarnivalState.Game.CATCH_A_FISH, CarnivalState.Game.ZOMBIE_SHOOTOUT}) {
                Integer b = best.get(g.name());
                if (b != null) {
                    Integer sb = SESSION_BEST.get(g);
                    out.add("§7Best " + g.label + ": §f" + num(b) + (sb != null ? " §7(session " + num(sb) + ")" : ""));
                }
            }
        }
        if (cfg.isShowGoals()) {
            for (Map.Entry<CarnivalState.Game, List<CarnivalGoal>> e : goalsLeft().entrySet()) {
                out.add("§b" + e.getKey().label + " goals:");
                for (CarnivalGoal g : e.getValue()) {
                    out.add(" §7- §f" + g.shortText);
                }
            }
        }
        if (cfg.isTicketReminder() && ticketsReady()) {
            out.add("§aDaily Carnival Tickets ready");
        }
        return out;
    }

    /** Harness: games counted this session. */
    public static int sessionGames() {
        return sessionGames;
    }

    /** Harness: tokens earned this session from the chat line. */
    public static long sessionEarned() {
        return sessionEarned;
    }

    /** Harness: menu reads that found goals. */
    public static int goalsReads() {
        return goalsReads;
    }

    /** Harness: the HUD's lines now. */
    public static List<String> hudLines() {
        return build();
    }

    public static final class Hud extends IslandHud {

        private static final List<String> SAMPLE = List.of("§6§lCarnival", "§eZombie Shootout §7Score: §f1,240",
                " §7Kills: §f18 §7Accuracy: §f82.5%", "§7Tokens: §e1,031 §a(+57)", "§7Session: §f3 games",
                "§7Best Fruit Digging: §f3,150");

        @Override
        public String id() {
            return "carnival_tracker";
        }

        @Override
        public String displayName() {
            return "Carnival Tracker";
        }

        @Override
        public int defaultY() {
            return 150;
        }

        @Override
        public boolean isEnabledInSettings() {
            return CarnivalTrackerConfig.getInstance().isEnabled();
        }

        @Override
        protected List<String> lines() {
            return CarnivalTracker.lines();
        }

        @Override
        protected List<String> sample() {
            return SAMPLE;
        }
    }
}
