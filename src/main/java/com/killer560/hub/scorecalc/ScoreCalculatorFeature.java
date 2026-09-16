package com.killer560.hub.scorecalc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.scoreboard.ScoreboardExtraData;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.translate.TranslateFeature;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeon Score Calculator: live estimate of the Catacombs score, S+ secrets needed, and 270/300 alerts.
 * <p>
 * Data (same sources Odin's {@code DungeonListener}, NoammAddons' {@code ScoreCalculation} and Skytils'
 * {@code ScoreCalculation} read, matched on formatting-stripped text):
 * <ul>
 * <li>Tab list: {@code Secrets Found: N}, {@code Secrets Found: X%}, {@code Crypts: N}, {@code Completed Rooms: N},
 * {@code Team Deaths: N}, {@code Puzzles: (N)} and each puzzle's {@code [✔]/[✖]/[✦]} line.</li>
 * <li>Sidebar: {@code Cleared: X% (N)} and {@code Time Elapsed: 1m 2s}.</li>
 * <li>Chat (via {@link ChatObserver}, dungeon-gated, exact lines): {@code A Prince falls. +1 Bonus Score},
 * {@code A Bat has been slain. +1 Bonus Score} (Odin {@code Mimic.kt}), the Watcher's "You have proven
 * yourself" line for blood-done (Odin), and other mods' party announcements ("Mimic Killed!" etc., Odin's list).</li>
 * <li>Mimic: a baby {@link Zombie} dying on F6/F7 outside the boss (Odin {@code Mimic.kt} / NoammAddons entity
 * event 3), polled from the tick loop the same way {@code DungeonInfoFeature} does it.</li>
 * <li>Boss room: Odin {@code DungeonListener.getBoss()} coordinates, OR {@link LiveMapFeature#isInBoss()}; latched.</li>
 * <li>Paul (EZPZ +10): Hypixel's keyless election endpoint (Odin {@code WebUtils.hasBonusPaulScore}; Skytils also
 * counts the minister's perk), falling back to {@link ScoreboardExtraData}'s cached election data.</li>
 * </ul>
 * The maths lives in {@link ScoreCalculator}. Everything here only runs while the master toggle is on and
 * {@link DungeonState#isInDungeon()}; state resets whenever a new dungeon world is entered.
 */
public final class ScoreCalculatorFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-scorecalc");
    public static final String ELEMENT_ID = "score_calculator";
    private static final String CHAT_NAME = "Score Calculator";

    // ---- tab list (Odin DungeonListener regexes, loosened to tolerate surrounding whitespace) ----
    private static final Pattern TAB_SECRETS_PERCENT = Pattern.compile("^\\s*Secrets Found: ([\\d.]+)%\\s*$");
    private static final Pattern TAB_SECRETS_COUNT = Pattern.compile("^\\s*Secrets Found: (\\d+)\\s*$");
    private static final Pattern TAB_CRYPTS = Pattern.compile("^\\s*Crypts: (\\d+)\\s*$");
    private static final Pattern TAB_COMPLETED_ROOMS = Pattern.compile("^\\s*Completed Rooms: (\\d+)\\s*$");
    // Odin "^Team Deaths: (\d+)$"; BetterMap strips "(n)" parentheses - accept both.
    private static final Pattern TAB_DEATHS = Pattern.compile("^\\s*(?:Team )?Deaths: \\(?(\\d+)\\)?\\s*$");
    private static final Pattern TAB_PUZZLE_COUNT = Pattern.compile("^\\s*Puzzles: \\((\\d+)\\)\\s*$");
    private static final Pattern TAB_PUZZLE = Pattern.compile("^\\s*(\\w+(?: \\w+)*|\\?\\?\\?): \\[([✖✔✦])] ?(?:\\((\\w+)\\))?\\s*$");

    // ---- sidebar (NoammAddons / Skytils) ----
    private static final Pattern SIDEBAR_CLEARED = Pattern.compile("Cleared: (\\d+)%");
    private static final Pattern SIDEBAR_ELAPSED = Pattern.compile("Time Elapsed: (?:(\\d+)h ?)?(?:(\\d+)m ?)?(?:(\\d+)s)?");

    // ---- chat ----
    private static final Pattern PRINCE_KILLED = Pattern.compile("^A Prince falls\\. \\+1 Bonus Score$");
    private static final Pattern BAT_KILLED = Pattern.compile("^A Bat has been slain\\. \\+1 Bonus Score$");
    private static final Pattern WATCHER_DONE = Pattern.compile("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.");
    private static final Pattern PARTY_MESSAGE = Pattern.compile("^Party > .*?: (.+)$");
    private static final Pattern TEAM_SCORE = Pattern.compile("^\\s*Team Score: (\\d+) \\(([A-DS+]+)\\)");
    // Odin DungeonListener party-assist lists (+ Skytils' marker strings).
    private static final Set<String> MIMIC_PARTY = Set.of("mimic killed", "mimic slain", "mimic killed!", "mimic dead",
            "mimic dead!", "$skytils-dungeon-score-mimic$");
    private static final Set<String> PRINCE_PARTY = Set.of("prince killed", "prince slain", "prince killed!", "prince dead",
            "prince dead!", "$skytils-dungeon-score-prince$");
    private static final Set<String> BAT_PARTY = Set.of("bat killed", "bat slain", "bat killed!", "bat dead", "bat dead!",
            "$skytils-dungeon-score-bat$");

    private static final URI ELECTION_URI = URI.create("https://api.hypixel.net/v2/resources/skyblock/election");
    private static final long ELECTION_CACHE_MS = 20 * 60 * 1000L;
    private static final long ELECTION_RETRY_MS = 60 * 1000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // ---- per-run state ----
    private static WeakReference<ClientLevel> runLevel = new WeakReference<>(null);
    private static long runStartMs = 0L;
    private static int runTicks = 0;
    private static int pollCounter = 0;
    private static double secretsPercent = 0;
    private static int secretsFound = 0;
    private static int crypts = 0;
    private static int completedRooms = 0;
    private static int clearedPercent = 0;
    private static int deaths = 0;
    private static int puzzleCount = 0;
    private static int puzzlesCompleted = 0;
    private static int puzzlesFailed = 0;
    private static int secondsElapsed = -1;
    private static boolean tabDataSeen = false;
    private static boolean expectingBloodUpdate = false;
    private static boolean bloodDone = false;
    private static boolean inBoss = false;
    private static boolean mimicKilled = false;
    private static boolean princeKilled = false;
    private static boolean batKilled = false;
    private static boolean said270 = false;
    private static boolean said300 = false;
    private static boolean loggedTabMiss = false;
    private static ScoreCalculator.Result lastResult = null;
    private static ScoreCalculator.Inputs lastInputs = null;

    // ---- Paul (shared across runs) ----
    private static volatile Boolean fetchedPaul = null;
    private static volatile long electionFetchedAtMs = 0L;
    private static volatile boolean electionInFlight = false;
    private static long electionAttemptAtMs = 0L;

    private ScoreCalculatorFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(ScoreCalculatorFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(ScoreCalculatorFeature::tick);
    }

    // ------------------------------------------------------------------ tick

    private static void tick(Minecraft client) {
        ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
        if (!cfg.isEnabled() || client.level == null || client.player == null || !DungeonState.isInDungeon()) {
            return;
        }
        if (client.level != runLevel.get()) {
            resetRun(client.level);
        }
        runTicks++;
        try {
            if (!inBoss) {
                updateBoss(client);
            }
            if (!mimicKilled && !inBoss) {
                checkMimic(client);
            }
            if (++pollCounter >= 10) {
                pollCounter = 0;
                readTabList(client);
                readSidebar(client);
                if (cfg.getPaulMode() == ScoreCalculatorConfig.PaulMode.AUTO) {
                    maybeFetchElection();
                }
                recalculate(cfg);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[ScoreCalc] Tick failed: {}", e.toString());
        }
    }

    private static void resetRun(ClientLevel level) {
        runLevel = new WeakReference<>(level);
        runStartMs = System.currentTimeMillis();
        runTicks = 0;
        pollCounter = 0;
        secretsPercent = 0;
        secretsFound = 0;
        crypts = 0;
        completedRooms = 0;
        clearedPercent = 0;
        deaths = 0;
        puzzleCount = 0;
        puzzlesCompleted = 0;
        puzzlesFailed = 0;
        secondsElapsed = -1;
        tabDataSeen = false;
        expectingBloodUpdate = false;
        bloodDone = false;
        inBoss = false;
        mimicKilled = false;
        princeKilled = false;
        batKilled = false;
        said270 = false;
        said300 = false;
        loggedTabMiss = false;
        lastResult = null;
        lastInputs = null;
        LOGGER.info("[ScoreCalc] New dungeon run tracked (floor={})", DungeonState.getFloor());
    }

    /** Odin {@code DungeonListener.getBoss()}: per-floor "past the room grid" coordinates, latched for the run. */
    private static void updateBoss(Minecraft client) {
        if (LiveMapFeature.isInBoss()) {
            setInBoss("livemap");
            return;
        }
        if (runTicks < 40) {
            return; // world-change placeholder position settles first
        }
        Vec3 pos = client.player.position();
        if (pos.x == 0.0 && pos.y == 100.0 && pos.z == 0.0) {
            return;
        }
        int floor = ScoreCalculator.floorNumber(DungeonState.getFloor());
        boolean boss = switch (floor) {
            case 1 -> pos.x > -71 && pos.z > -39;
            case 2, 3, 4 -> pos.x > -39 && pos.z > -39;
            case 5, 6 -> pos.x > -39 && pos.z > -7;
            case 7 -> pos.x > -7 && pos.z > -7;
            default -> false;
        };
        if (boss) {
            setInBoss("coords " + (int) pos.x + "," + (int) pos.z);
        }
    }

    private static void setInBoss(String why) {
        if (!inBoss) {
            inBoss = true;
            LOGGER.info("[ScoreCalc] Boss room entered ({})", why);
        }
    }

    private static void checkMimic(Minecraft client) {
        if (ScoreCalculator.floorNumber(DungeonState.getFloor()) < 6) {
            return;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof Zombie zombie && zombie.isBaby() && zombie.isDeadOrDying()) {
                mimicKilled = true;
                LOGGER.info("[ScoreCalc] Mimic killed (baby zombie id={} at {})", zombie.getId(), zombie.position());
                return;
            }
        }
    }

    private static void readTabList(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }
        int completedPuzzles = 0;
        int failedPuzzles = 0;
        boolean sawPuzzleHeader = false;
        boolean matchedAny = false;
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = ChatFormatting.stripFormatting(display.getString());
            if (plain == null || plain.isBlank()) {
                continue;
            }
            Matcher m;
            if ((m = TAB_SECRETS_PERCENT.matcher(plain)).matches()) {
                secretsPercent = parseDouble(m.group(1), secretsPercent);
                matchedAny = true;
            } else if ((m = TAB_SECRETS_COUNT.matcher(plain)).matches()) {
                secretsFound = parseInt(m.group(1), secretsFound);
                matchedAny = true;
            } else if ((m = TAB_CRYPTS.matcher(plain)).matches()) {
                crypts = parseInt(m.group(1), crypts);
                matchedAny = true;
            } else if ((m = TAB_COMPLETED_ROOMS.matcher(plain)).matches()) {
                completedRooms = parseInt(m.group(1), completedRooms);
                matchedAny = true;
            } else if ((m = TAB_DEATHS.matcher(plain)).matches()) {
                deaths = parseInt(m.group(1), deaths);
            } else if ((m = TAB_PUZZLE_COUNT.matcher(plain)).matches()) {
                puzzleCount = parseInt(m.group(1), puzzleCount);
                sawPuzzleHeader = true;
            } else if ((m = TAB_PUZZLE.matcher(plain)).matches()) {
                if ("✔".equals(m.group(2))) {
                    completedPuzzles++;
                } else if ("✖".equals(m.group(2))) {
                    failedPuzzles++;
                }
            }
        }
        if (sawPuzzleHeader || completedPuzzles > 0 || failedPuzzles > 0) {
            puzzlesCompleted = completedPuzzles;
            puzzlesFailed = failedPuzzles;
        }
        if (matchedAny && !tabDataSeen) {
            tabDataSeen = true;
            LOGGER.info("[ScoreCalc] Tab-list score data found (secrets={} {}%, crypts={}, rooms={})",
                    secretsFound, secretsPercent, crypts, completedRooms);
        } else if (!matchedAny && !tabDataSeen && !loggedTabMiss && runTicks > 400) {
            loggedTabMiss = true;
            LOGGER.warn("[ScoreCalc] No tab-list score lines matched after 20s this run (p3sim / changed format?) - score stays an estimate");
        }
    }

    private static void readSidebar(Minecraft client) {
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return;
        }
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            String text = sidebarLine(scoreboard, entry);
            String plain = ChatFormatting.stripFormatting(text);
            if (plain == null) {
                continue;
            }
            Matcher cleared = SIDEBAR_CLEARED.matcher(plain);
            if (cleared.find()) {
                int value = parseInt(cleared.group(1), clearedPercent);
                // Odin: the first "Cleared" change after the Watcher's line is blood's own clear.
                if (value != clearedPercent && expectingBloodUpdate && !bloodDone) {
                    bloodDone = true;
                    LOGGER.info("[ScoreCalc] Blood room counted as cleared ({}% -> {}%)", clearedPercent, value);
                }
                clearedPercent = value;
                continue;
            }
            if (plain.contains("Time Elapsed")) {
                Matcher elapsed = SIDEBAR_ELAPSED.matcher(plain);
                if (elapsed.find() && (elapsed.group(1) != null || elapsed.group(2) != null || elapsed.group(3) != null)) {
                    secondsElapsed = parseInt(elapsed.group(1), 0) * 3600 + parseInt(elapsed.group(2), 0) * 60
                            + parseInt(elapsed.group(3), 0);
                }
            }
        }
    }

    /** Hypixel's visible sidebar text lives in the team prefix/suffix (same approach as DungeonState). */
    private static String sidebarLine(Scoreboard scoreboard, PlayerScoreEntry entry) {
        PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
        if (team == null) {
            return entry.display() != null ? entry.display().getString() : entry.owner();
        }
        StringBuilder sb = new StringBuilder();
        if (team.getPlayerPrefix() != null) {
            sb.append(team.getPlayerPrefix().getString());
        }
        if (team.getPlayerSuffix() != null) {
            sb.append(team.getPlayerSuffix().getString());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ chat

    private static void onChat(Component message) {
        ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
        if (!cfg.isEnabled() || !DungeonState.isInDungeon()) {
            return;
        }
        String plain = ChatObserver.strip(message).trim();
        if (PRINCE_KILLED.matcher(plain).matches()) {
            if (!princeKilled) {
                LOGGER.info("[ScoreCalc] Prince killed");
            }
            princeKilled = true;
            return;
        }
        if (BAT_KILLED.matcher(plain).matches()) {
            if (!batKilled) {
                LOGGER.info("[ScoreCalc] Bat killed");
            }
            batKilled = true;
            return;
        }
        if (WATCHER_DONE.matcher(plain).find()) {
            expectingBloodUpdate = true;
            return;
        }
        Matcher teamScore = TEAM_SCORE.matcher(plain);
        if (teamScore.find()) {
            // Diagnostics only: Hypixel's real end-of-run score vs this estimate, for checking the formula in latest.log.
            ScoreCalculator.Result r = lastResult;
            LOGGER.info("[ScoreCalc] Hypixel final score {} ({}) vs estimate {} ({}) | inputs={} | parts skill={} explore={} speed={} bonus={}",
                    teamScore.group(1), teamScore.group(2), r == null ? "?" : r.total(), r == null ? "?" : r.rank(),
                    lastInputs, r == null ? "?" : r.skill(), r == null ? "?" : r.explore(),
                    r == null ? "?" : r.speed(), r == null ? "?" : r.bonus());
            return;
        }
        Matcher party = PARTY_MESSAGE.matcher(plain);
        if (party.matches()) {
            String body = party.group(1).trim().toLowerCase(Locale.ROOT);
            if (MIMIC_PARTY.contains(body) && ScoreCalculator.floorNumber(DungeonState.getFloor()) >= 6) {
                mimicKilled = true;
            } else if (PRINCE_PARTY.contains(body)) {
                princeKilled = true;
            } else if (BAT_PARTY.contains(body)) {
                batKilled = true;
            }
        }
    }

    // ------------------------------------------------------------------ score + alerts

    private static void recalculate(ScoreCalculatorConfig cfg) {
        String floor = DungeonState.getFloor();
        int seconds = secondsElapsed >= 0 ? secondsElapsed : (int) ((System.currentTimeMillis() - runStartMs) / 1000L);
        ScoreCalculator.Inputs inputs = new ScoreCalculator.Inputs(floor, secretsPercent, secretsFound, crypts,
                completedRooms, clearedPercent, deaths, puzzleCount, puzzlesCompleted, puzzlesFailed, seconds,
                bloodDone, inBoss, mimicKilled, princeKilled, batKilled, isPaul(cfg), cfg.isAssumeSpiritPet());
        ScoreCalculator.Result result = ScoreCalculator.calculate(inputs);
        ScoreCalculator.Result previous = lastResult;
        lastInputs = inputs;
        lastResult = result;
        if (previous == null || previous.total() != result.total()) {
            LOGGER.debug("[ScoreCalc] Score {} -> {} ({})", previous == null ? "-" : previous.total(), result.total(), inputs);
        }
        if (!tabDataSeen) {
            return; // never alert off an empty tab list
        }
        if (!said300 && result.total() >= 300) {
            said300 = true;
            said270 = true; // crossing both at once: announce only 300
            fireMilestone(cfg, 300, floor, seconds);
        } else if (!said270 && result.total() >= 270) {
            said270 = true;
            fireMilestone(cfg, 270, floor, seconds);
        }
    }

    private static void fireMilestone(ScoreCalculatorConfig cfg, int milestone, String floor, int seconds) {
        boolean is300 = milestone == 300;
        LOGGER.info("[ScoreCalc] {} score reached at {} on {} (estimate={}, inputs={})", milestone, formatTime(seconds),
                floor, lastResult == null ? "?" : lastResult.total(), lastInputs);
        Minecraft client = Minecraft.getInstance();
        boolean title = is300 ? cfg.isTitle300() : cfg.isTitle270();
        if (title) {
            String text = (is300 ? cfg.getTitle300Text() : cfg.getTitle270Text()).replace('&', '§');
            client.gui.setTimes(5, 40, 10);
            client.gui.setTitle(Component.literal(text.contains("§") ? text : "§6§l" + text));
            client.gui.setSubtitle(Component.literal(""));
            if (cfg.isAlertSound()) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
            }
        }
        boolean party = is300 ? cfg.isParty300() : cfg.isParty270();
        if (party) {
            // Once per run per milestone: guarded by said270/said300, reset only on a new dungeon world.
            TranslateFeature.sendGenerated(is300 ? cfg.getParty300Message() : cfg.getParty270Message(), "pc");
        }
        if (cfg.isChatNote()) {
            ModChat.send(CHAT_NAME, ModChat.value(String.valueOf(milestone)), ModChat.text(" score reached in "),
                    ModChat.value(formatTime(seconds)), ModChat.dim(" || "), ModChat.value(floor == null ? "?" : floor));
        }
    }

    /** Manual test from the settings tab: shows the configured title / client note without sending party chat. */
    public static void previewAlert(int milestone) {
        ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        String text = (milestone >= 300 ? cfg.getTitle300Text() : cfg.getTitle270Text()).replace('&', '§');
        client.gui.setTimes(5, 40, 10);
        client.gui.setTitle(Component.literal(text.contains("§") ? text : "§6§l" + text));
        client.gui.setSubtitle(Component.literal(""));
        if (cfg.isAlertSound()) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
        }
    }

    private static boolean isPaul(ScoreCalculatorConfig cfg) {
        return switch (cfg.getPaulMode()) {
            case FORCE_ON -> true;
            case FORCE_OFF -> false;
            case AUTO -> {
                Boolean fetched = fetchedPaul;
                if (fetched != null) {
                    yield fetched;
                }
                yield hasEzpz(ScoreboardExtraData.mayor()) || hasEzpz(ScoreboardExtraData.minister());
            }
        };
    }

    private static boolean hasEzpz(ScoreboardExtraData.Candidate candidate) {
        if (candidate == null || candidate.perks() == null) {
            return false;
        }
        for (ScoreboardExtraData.Perk perk : candidate.perks()) {
            if (perk != null && "EZPZ".equalsIgnoreCase(perk.name())) {
                return true;
            }
        }
        return false;
    }

    private static void maybeFetchElection() {
        long now = System.currentTimeMillis();
        if (electionInFlight || (fetchedPaul != null && now - electionFetchedAtMs < ELECTION_CACHE_MS)
                || now - electionAttemptAtMs < ELECTION_RETRY_MS) {
            return;
        }
        electionAttemptAtMs = now;
        electionInFlight = true;
        HttpRequest request = HttpRequest.newBuilder(ELECTION_URI)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "killer560smod")
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            try {
                if (error != null || response == null || response.statusCode() != 200) {
                    LOGGER.debug("[ScoreCalc] Election fetch failed: {}", error != null ? error.toString()
                            : response == null ? "no response" : "HTTP " + response.statusCode());
                    return;
                }
                Boolean paul = parseEzpz(response.body());
                if (paul != null) {
                    if (!paul.equals(fetchedPaul)) {
                        LOGGER.info("[ScoreCalc] Paul EZPZ active: {}", paul);
                    }
                    fetchedPaul = paul;
                    electionFetchedAtMs = System.currentTimeMillis();
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[ScoreCalc] Election parse failed: {}", e.toString());
            } finally {
                electionInFlight = false;
            }
        });
    }

    /** @return whether the current mayor (or minister) has the EZPZ perk, or null if the JSON isn't usable. */
    private static Boolean parseEzpz(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!ConfigJson.getBool(root, "success", false)) {
            return null;
        }
        JsonObject mayor = ConfigJson.getObject(root, "mayor");
        if (mayor == null) {
            return null;
        }
        JsonArray perks = ConfigJson.getArray(mayor, "perks");
        if (perks != null) {
            for (JsonElement el : perks) {
                if (el.isJsonObject() && "EZPZ".equalsIgnoreCase(ConfigJson.getString(el.getAsJsonObject(), "name", ""))) {
                    return true;
                }
            }
        }
        JsonObject minister = ConfigJson.getObject(mayor, "minister");
        JsonObject perk = minister == null ? null : ConfigJson.getObject(minister, "perk");
        return perk != null && "EZPZ".equalsIgnoreCase(ConfigJson.getString(perk, "name", ""));
    }

    // ------------------------------------------------------------------ helpers / API

    /** Latest estimate for the current run, or null before the first poll / outside a tracked run. */
    public static ScoreCalculator.Result currentResult() {
        return DungeonState.isInDungeon() ? lastResult : null;
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) ? v : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String formatTime(int seconds) {
        int s = Math.max(0, seconds);
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------ HUD

    private record Segment(String text, int color) {
    }

    private static final int SCORE_RED = 0xFF5555;
    private static final int SCORE_YELLOW = 0xFFFF55;

    private static int scoreColor(int score) {
        // Odin MapInfo.colorizeScore: red < 270, yellow < 300, green 300+.
        return score < 270 ? SCORE_RED : score < 300 ? SCORE_YELLOW : ModChat.GOOD;
    }

    private static List<List<Segment>> buildLines(ScoreCalculatorConfig cfg, boolean demo) {
        List<List<Segment>> lines = new ArrayList<>();
        ScoreCalculator.Result r = demo ? new ScoreCalculator.Result(302, 100, 100, 60, 40, 100, 2, 36, 60, 57, 3, "S+")
                : lastResult;
        if (r == null) {
            return lines;
        }
        int sc = scoreColor(r.total());
        lines.add(List.of(new Segment("Score: ", ModChat.ORANGE), new Segment(String.valueOf(r.total()), sc),
                new Segment(" (" + r.rank() + ")", sc)));
        boolean paul = demo ? false : lastInputs != null && lastInputs.paul();
        if (cfg.isShowBreakdown()) {
            lines.add(List.of(new Segment(" Skill: ", ModChat.LIGHT_ORANGE), new Segment(String.valueOf(r.skill()), ModChat.TEXT)));
            lines.add(List.of(new Segment(" Explore: ", ModChat.LIGHT_ORANGE), new Segment(String.valueOf(r.explore()), ModChat.TEXT),
                    new Segment(" (" + r.roomScore() + " rooms + " + r.secretScore() + " secrets)", ModChat.DIM)));
            lines.add(List.of(new Segment(" Speed: ", ModChat.LIGHT_ORANGE), new Segment(String.valueOf(r.speed()), ModChat.TEXT)));
            List<Segment> bonus = new ArrayList<>(List.of(new Segment(" Bonus: ", ModChat.LIGHT_ORANGE),
                    new Segment(String.valueOf(r.bonus()), ModChat.TEXT)));
            if (paul) {
                bonus.add(new Segment(" (+10 Paul)", ModChat.DIM));
            }
            lines.add(bonus);
        }
        if (cfg.isShowSecretsNeeded()) {
            List<Segment> line = new ArrayList<>();
            line.add(new Segment("S+ Secrets: ", ModChat.ORANGE));
            int found = demo ? 54 : secretsFound;
            if (r.secretsNeeded() < 0) {
                line.add(new Segment("?", ModChat.DIM));
            } else if (r.secretsNeeded() == Integer.MAX_VALUE) {
                line.add(new Segment("not reachable", ModChat.BAD));
            } else if (r.secretsRemaining() <= 0) {
                line.add(new Segment("done", ModChat.GOOD));
                line.add(new Segment(" (" + found + "/" + r.secretsNeeded() + ")", ModChat.DIM));
            } else {
                line.add(new Segment(r.secretsRemaining() + " more", ModChat.LIGHT_ORANGE));
                line.add(new Segment(" (" + found + "/" + r.secretsNeeded() + ")", ModChat.DIM));
            }
            lines.add(line);
        }
        if (cfg.isShowCryptsDeaths()) {
            int c = demo ? 5 : crypts;
            int d = demo ? 0 : deaths;
            lines.add(List.of(new Segment("Crypts: ", ModChat.ORANGE), new Segment(String.valueOf(c), c >= 5 ? ModChat.GOOD : ModChat.TEXT),
                    new Segment("/5", ModChat.DIM), new Segment("  Deaths: ", ModChat.ORANGE),
                    new Segment(String.valueOf(d), d > 0 ? ModChat.BAD : ModChat.TEXT)));
        }
        if (cfg.isShowMimicPrince()) {
            boolean mimicFloor = demo || ScoreCalculator.floorNumber(DungeonState.getFloor()) >= 6;
            List<Segment> line = new ArrayList<>();
            if (mimicFloor) {
                boolean m = demo || mimicKilled;
                line.add(new Segment("Mimic: ", ModChat.ORANGE));
                line.add(new Segment(m ? "✔" : "✘", m ? ModChat.GOOD : ModChat.BAD));
                line.add(new Segment("  ", ModChat.TEXT));
            }
            boolean p = !demo && princeKilled;
            line.add(new Segment("Prince: ", ModChat.ORANGE));
            line.add(new Segment(p ? "✔" : "✘", p ? ModChat.GOOD : ModChat.BAD));
            lines.add(line);
        }
        return lines;
    }

    private static boolean inEditor() {
        return Minecraft.getInstance().screen instanceof HudEditorScreen;
    }

    public static final class ScoreHudElement implements HudElement {

        public static final ScoreHudElement INSTANCE = new ScoreHudElement();

        private ScoreHudElement() {
        }

        @Override
        public String id() {
            return ELEMENT_ID;
        }

        @Override
        public String displayName() {
            return "Score Calculator";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            // Not 120: Posmsg / Spring Boots default there, and this element is up to 7 lines tall.
            return 160;
        }

        private static List<List<Segment>> currentLines() {
            ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
            boolean demo = inEditor() && (lastResult == null || !DungeonState.isInDungeon());
            return buildLines(cfg, demo);
        }

        @Override
        public int width() {
            Font font = Minecraft.getInstance().font;
            if (font == null) {
                return 100;
            }
            int max = 40;
            for (List<Segment> line : currentLines()) {
                int w = 0;
                for (Segment s : line) {
                    w += font.width(s.text());
                }
                max = Math.max(max, w);
            }
            return max + 1;
        }

        @Override
        public int height() {
            return Math.max(10, currentLines().size() * 10);
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            boolean editor = inEditor();
            if (!cfg.isEnabled() || (client.screen != null && !editor)) {
                return;
            }
            if (!editor && !DungeonState.isInDungeon()) {
                return;
            }
            Font font = client.font;
            int lineY = y;
            for (List<Segment> line : buildLines(cfg, editor && (lastResult == null || !DungeonState.isInDungeon()))) {
                int lineX = x;
                for (Segment s : line) {
                    graphics.text(font, s.text(), lineX, lineY, 0xFF000000 | s.color(), cfg.isTextShadow());
                    lineX += font.width(s.text());
                }
                lineY += 10;
            }
        }
    }
}
