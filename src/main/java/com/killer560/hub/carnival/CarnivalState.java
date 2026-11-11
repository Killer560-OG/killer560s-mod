package com.killer560.hub.carnival;

import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.scoreboard.ScoreboardData;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the Carnival features stand (carnival-1110): whether the player is in the Hub's Carnival, which of its
 * three minigames he is playing, and the sidebar's Carnival lines (tokens, score, catch streak, fruits, accuracy, kills,
 * time left). The minigame comes from the sidebar's bold game line or its game-only stat line (SkyHanni's
 * {@code ScoreboardPattern} carnival lines), and from the NPC's start line (SkyHanni's "Good luck, matey!" and "Good
 * luck, pal!"), until the centered end-of-game title. Read four times a second, only while a Carnival feature is on.
 *
 * <p>Unverified in game (taken from SkyHanni): every sidebar line shape, the two start lines, and the end title being a
 * centered line holding only the game's name. Catch a Fish has no known start line, so it is read from the sidebar alone.
 */
public final class CarnivalState {

    /** The three Carnival minigames. */
    public enum Game {
        NONE("None"),
        FRUIT_DIGGING("Fruit Digging"),
        CATCH_A_FISH("Catch a Fish"),
        ZOMBIE_SHOOTOUT("Zombie Shootout");

        public final String label;

        Game(String label) {
            this.label = label;
        }

        static Game of(String label) {
            for (Game g : values()) {
                if (g != NONE && g.label.equalsIgnoreCase(label)) {
                    return g;
                }
            }
            return NONE;
        }
    }

    // Chat (anchored on the whole line: an [NPC] line or a centered title no player's chat line can take).
    static final Pattern START_FRUIT = Pattern.compile("^\\[NPC] Carnival Pirateman: Good luck, matey!$");
    static final Pattern START_ZOMBIE = Pattern.compile("^\\[NPC] Carnival Cowboy: Good luck, pal!$");
    static final Pattern END_TITLE = Pattern.compile("^ {10,40}(Fruit Digging|Catch a Fish|Zombie Shootout)$");

    // Sidebar, read with the color codes stripped.
    static final Pattern SB_HEADER = Pattern.compile("^Carnival \\d{1,4}(?::\\d{1,2}){0,3}$");
    static final Pattern SB_GAME = Pattern.compile("^(Catch a Fish|Fruit Digging|Zombie Shootout)$");
    static final Pattern SB_TOKENS = Pattern.compile("^Carnival Tokens: ([\\d,]{1,11})$");
    static final Pattern SB_SCORE = Pattern.compile("^Score: ([\\d,]{1,9})\\b.{0,30}$");
    static final Pattern SB_STREAK = Pattern.compile("^Catch Streak: (\\d{1,4})$");
    static final Pattern SB_FRUITS = Pattern.compile("^Fruits: (\\d{1,2})/(\\d{1,2})$");
    static final Pattern SB_ACCURACY = Pattern.compile("^Accuracy: (\\d{1,3}(?:\\.\\d{1,2})?)%$");
    static final Pattern SB_KILLS = Pattern.compile("^Kills: (\\d{1,4})$");
    static final Pattern SB_TIME = Pattern.compile("^Time Left: (.{1,16})$");

    /** How long a game started by its NPC line counts as running without any sidebar sign of it. */
    private static final long FRUIT_MAX_MS = 5 * 60_000L + 20_000L;
    private static final long ZOMBIE_MAX_MS = 100_000L + 20_000L;

    private static final List<BooleanSupplier> DEMANDS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Game>> STARTS = new CopyOnWriteArrayList<>();
    private static final List<GameEndListener> ENDS = new CopyOnWriteArrayList<>();
    private static boolean registered;
    private static int ticks;
    private static Object level;

    private static boolean sidebarHeader;
    private static Game sidebarGame = Game.NONE;
    private static Game chatGame = Game.NONE;
    private static long chatGameAtMs;
    private static Game game = Game.NONE;
    private static long gameStartMs;
    private static long tokens = -1;
    private static int score = -1;
    private static int lastGameScore = -1;
    private static int streak = -1;
    private static int fruitsFound = -1;
    private static int fruitsTotal = -1;
    private static double accuracy = -1;
    private static int kills = -1;
    private static String timeLeft = "";
    /** The game the end title just closed: its sidebar lines can linger a few seconds and must not restart it. */
    private static Game endedGame = Game.NONE;
    private static long endedAtMs;
    /** Test hook: forces the "in the Carnival" answer (null = read it). */
    private static Boolean forcedInCarnival;

    /** Told when a game ends, with the last score the sidebar showed for it (-1 when it never showed one). */
    public interface GameEndListener {
        void ended(Game game, int score, long durationMs);
    }

    private CarnivalState() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        int bit = FeatureBits.register("Carnival State", CarnivalState::demanded);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CarnivalState.tick", bit, CarnivalState::tick));
        ChatObserver.subscribe(CarnivalState::onChat);
    }

    /** A Carnival feature registers its switch here; the reads run while any is on. */
    public static void addDemand(BooleanSupplier on) {
        DEMANDS.add(on);
        FeatureBits.changed();
    }

    public static void onGameStart(Consumer<Game> listener) {
        STARTS.add(listener);
    }

    public static void onGameEnd(GameEndListener listener) {
        ENDS.add(listener);
    }

    private static boolean demanded() {
        for (BooleanSupplier d : DEMANDS) {
            if (d.getAsBoolean()) {
                return true;
            }
        }
        return false;
    }

    private static void onChat(Component message) {
        if (!demanded()) {
            return;
        }
        String plain = ChatObserver.strip(message);
        if (plain == null || plain.length() > 80) {
            return;
        }
        if (START_FRUIT.matcher(plain).matches()) {
            chatStart(Game.FRUIT_DIGGING);
        } else if (START_ZOMBIE.matcher(plain).matches()) {
            chatStart(Game.ZOMBIE_SHOOTOUT);
        } else {
            Matcher m = END_TITLE.matcher(plain);
            if (m.matches()) {
                Game g = Game.of(m.group(1));
                if (chatGame == g) {
                    chatGame = Game.NONE;
                }
                if (game == g) {
                    endedGame = g;
                    endedAtMs = System.currentTimeMillis();
                    sidebarGame = Game.NONE;
                    setGame(Game.NONE);
                }
            }
        }
    }

    private static void chatStart(Game g) {
        chatGame = g;
        chatGameAtMs = System.currentTimeMillis();
        setGame(g);
    }

    private static void tick(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            chatGame = Game.NONE;
            sidebarGame = Game.NONE;
            setGame(Game.NONE);
            tokens = -1;
        }
        if (client.level == null || ticks++ % 5 != 0) {
            return;
        }
        readSidebar(client);
        if (chatGame != Game.NONE) {
            long max = chatGame == Game.FRUIT_DIGGING ? FRUIT_MAX_MS : ZOMBIE_MAX_MS;
            if (System.currentTimeMillis() - chatGameAtMs > max) {
                chatGame = Game.NONE;
            }
        }
        setGame(sidebarGame != Game.NONE ? sidebarGame : chatGame);
    }

    private static void readSidebar(Minecraft client) {
        List<String> lines = ScoreboardData.sidebarNow(client);
        boolean header = false;
        Game named = Game.NONE;
        Game byStat = Game.NONE;
        int sc = -1;
        int st = -1;
        int ff = -1;
        int ft = -1;
        double acc = -1;
        int kl = -1;
        String time = "";
        for (String raw : lines) {
            String line = ChatObserver.stripCodes(raw);
            if (line == null) {
                continue;
            }
            line = line.trim();
            if (line.isEmpty() || line.length() > 48) {
                continue;
            }
            Matcher m;
            if (SB_HEADER.matcher(line).matches()) {
                header = true;
            } else if ((m = SB_GAME.matcher(line)).matches()) {
                named = Game.of(m.group(1));
            } else if ((m = SB_TOKENS.matcher(line)).matches()) {
                tokens = parse(m.group(1));
            } else if ((m = SB_SCORE.matcher(line)).matches()) {
                sc = (int) parse(m.group(1));
            } else if ((m = SB_STREAK.matcher(line)).matches()) {
                st = Integer.parseInt(m.group(1));
                byStat = Game.CATCH_A_FISH;
            } else if ((m = SB_FRUITS.matcher(line)).matches()) {
                ff = Integer.parseInt(m.group(1));
                ft = Integer.parseInt(m.group(2));
                byStat = Game.FRUIT_DIGGING;
            } else if ((m = SB_ACCURACY.matcher(line)).matches()) {
                acc = Double.parseDouble(m.group(1));
                byStat = Game.ZOMBIE_SHOOTOUT;
            } else if ((m = SB_KILLS.matcher(line)).matches()) {
                kl = Integer.parseInt(m.group(1));
                byStat = Game.ZOMBIE_SHOOTOUT;
            } else if ((m = SB_TIME.matcher(line)).matches()) {
                time = m.group(1).trim();
            }
        }
        sidebarHeader = header;
        sidebarGame = named != Game.NONE ? named : byStat;
        if (sidebarGame != Game.NONE && sidebarGame == endedGame && System.currentTimeMillis() - endedAtMs < 8000L) {
            sidebarGame = Game.NONE;
        }
        score = sc;
        streak = st;
        fruitsFound = ff;
        fruitsTotal = ft;
        accuracy = acc;
        kills = kl;
        timeLeft = time;
    }

    private static long parse(String digits) {
        try {
            return Long.parseLong(digits.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void setGame(Game g) {
        if (g == game) {
            if (g != Game.NONE && score >= 0) {
                lastGameScore = score;
            }
            return;
        }
        Game old = game;
        long started = gameStartMs;
        int last = lastGameScore;
        game = g;
        gameStartMs = System.currentTimeMillis();
        lastGameScore = g != Game.NONE && score >= 0 ? score : -1;
        if (old != Game.NONE) {
            long dur = System.currentTimeMillis() - started;
            for (GameEndListener l : ENDS) {
                l.ended(old, last, dur);
            }
        }
        if (g != Game.NONE) {
            for (Consumer<Game> l : STARTS) {
                l.accept(g);
            }
        }
    }

    // ---- reads --------------------------------------------------------------------------------------------------

    /** The sidebar area reads "Carnival", or a Carnival game is running. */
    public static boolean inCarnival() {
        if (forcedInCarnival != null) {
            return forcedInCarnival;
        }
        return game != Game.NONE || "Carnival".equalsIgnoreCase(IslandDetector.scoreboardArea());
    }

    /** The minigame being played now, or {@link Game#NONE}. */
    public static Game game() {
        return game;
    }

    public static boolean playing(Game g) {
        return game == g;
    }

    /** Milliseconds since the current game started (0 with none). */
    public static long gameMillis() {
        return game == Game.NONE ? 0L : System.currentTimeMillis() - gameStartMs;
    }

    /** The sidebar showed the Carnival's own header (the event is on). */
    public static boolean eventOnSidebar() {
        return sidebarHeader;
    }

    /** Carnival Tokens from the sidebar, -1 when not shown since the last world change. */
    public static long tokens() {
        return tokens;
    }

    public static int score() {
        return score;
    }

    public static int streak() {
        return streak;
    }

    public static int fruitsFound() {
        return fruitsFound;
    }

    public static int fruitsTotal() {
        return fruitsTotal;
    }

    public static double accuracy() {
        return accuracy;
    }

    public static int kills() {
        return kills;
    }

    public static String timeLeft() {
        return timeLeft;
    }

    /** Harness: pretend to stand in the Carnival (null goes back to the real read). */
    public static void forceInCarnivalForTest(Boolean in) {
        forcedInCarnival = in;
    }

    /** Harness: start or end a game as its NPC line or end title would. */
    public static void forceGameForTest(String label) {
        Game g = label == null ? Game.NONE : Game.of(label);
        if (g == Game.NONE) {
            chatGame = Game.NONE;
            sidebarGame = Game.NONE;
            setGame(Game.NONE);
        } else {
            chatStart(g);
        }
    }
}
