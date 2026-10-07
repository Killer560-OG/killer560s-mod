package com.killer560.hub.scorecalc;

import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.FeatureGuard;
import com.google.gson.JsonArray;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.interop.InteropFeature;
import com.killer560.hub.interop.InteropSource;
import com.killer560.hub.interop.PartyInteropState;
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
import com.killer560.hub.util.ModLog;

import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.killer560.hub.compat.McCompat;

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

    private static final Logger LOGGER = ModLog.get("killer560smod-scorecalc");
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
    /** Public for Auto Routes' crypt node, which counts a prince kill of its own by the same line. */
    public static final Pattern PRINCE_KILLED = Pattern.compile("^A Prince falls\\. \\+1 Bonus Score$");
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

    private static final URI ELECTION_URI = ModNet.uri("hypixel", "https://api.hypixel.net/v2/resources/skyblock/election");
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
    /** Each named puzzle row of the tab list ("Ice Fill: [✔]"), lower-case name -> ✔ ✖ or ✦. Replaced whole each poll. */
    private static volatile Map<String, Character> puzzleStates = Map.of();

    // ---- Paul (shared across runs) ----
    private static volatile Boolean fetchedPaul = null;
    private static volatile long electionFetchedAtMs = 0L;
    private static volatile boolean electionInFlight = false;
    private static long electionAttemptAtMs = 0L;

    private ScoreCalculatorFeature() {
    }

    public static void register() {
        ChatObserver.subscribe(ScoreCalculatorFeature::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ScoreCalculatorFeature.tick", ScoreCalculatorFeature::tick));
    }

    // ------------------------------------------------------------------ tick

    /**
     * Whether anything wants this run's estimate: the Score Calculator itself, or a map's Extra Info section.
     *
     * <p>killer560 (2026-10-07): "I have the extra info overlay on for the map but the info isn't coming up below the
     * map." The tracking below used to run only with Score Calculator on - off in a fresh config - so Extra Info had
     * nothing to show unless a second, unrelated feature was switched on too. The TRACKING (tab list, sidebar, the
     * bonus chat lines, the formula) now runs for either consumer. Everything that DOES something - the 270/300
     * titles and party messages, the bonus-kill party alerts, the Party Interop flags, the election fetch and the
     * Score Calculator's own HUD - still needs Score Calculator on, so turning on Extra Info sends and draws nothing
     * new beyond the map's own lines.
     */
    public static boolean estimateWanted() {
        return ScoreCalculatorConfig.getInstance().isEnabled() || mapExtraInfoWanted();
    }

    /** Either map's Extra Info is on: the Dungeon Map HUD's (Map Extras) or the Interactive Map legend's. */
    public static boolean mapExtraInfoWanted() {
        com.killer560.hub.livemap.LiveMapConfig map = com.killer560.hub.livemap.LiveMapConfig.getInstance();
        return (map.isEnabled() && com.killer560.hub.mapping.MappingConfig.getInstance().isExtraInfoEnabled())
                || (map.isInteractiveMapEnabled() && map.isShowExtraInfo());
    }

    private static void tick(Minecraft client) {
        ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
        if (!estimateWanted() || client.level == null || client.player == null || !DungeonState.isInDungeon()) {
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
                // The election fetch is a network request; only the Score Calculator itself makes it.
                if (cfg.isEnabled() && cfg.getPaulMode() == ScoreCalculatorConfig.PaulMode.AUTO) {
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
        puzzleStates = Map.of();
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
                if (!ScoreCalculatorConfig.getInstance().isEnabled()) {
                    return; // tracking for a map's Extra Info only: nothing is offered or sent
                }
                // We saw it ourselves, so this is the most trustworthy version of the fact the party has.
                PartyInteropState.offerFlag(PartyInteropState.Flag.MIMIC_KILLED, InteropSource.SELF, null);
                maybeSendKillAlert("Mimic", ScoreCalculatorConfig.getInstance().isMimicAlertEnabled(),
                        ScoreCalculatorConfig.getInstance().getMimicAlertMessage(), PartyInteropState.Flag.MIMIC_KILLED);
                return;
            }
        }
    }

    /** Bonus-kill party alerts (moved from {@code DungeonInfoFeature} 2026-09-21 - see this class's doc).
     *  Only ever called from a self-detected kill (the real baby-zombie death / exact Hypixel bonus-score
     *  chat line) - a kill only known about because another mod announced it in party chat never reaches
     *  here, so this can't double up with that announcement. The interop check is still kept as a second
     *  guard for the case where our own detection and a party mate's mod both fire in the same tick. */
    private static void maybeSendKillAlert(String label, boolean enabled, String message, PartyInteropState.Flag flag) {
        if (!enabled) {
            return;
        }
        if (InteropFeature.alreadyAnnouncedInParty(flag)) {
            LOGGER.info("[ScoreCalc] {} kill alert suppressed - a party mate's mod already announced it", label);
            return;
        }
        TranslateFeature.sendGenerated(message, "pc");
    }

    /**
     * The tab list's "Crypts: N" right now, or -1 when there is no such line - read fresh, whatever this feature's own
     * settings are. For Auto Routes' crypt node, which watches it rise after its own weapon use; the same pattern the
     * score calculator reads, so the two can never disagree about what a crypt line looks like.
     */
    public static int tabCrypts(Minecraft client) {
        if (client.getConnection() == null) {
            return -1;
        }
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = com.killer560.hub.util.ChatObserver.stripCodes(display.getString());
            if (plain == null) {
                continue;
            }
            Matcher m = TAB_CRYPTS.matcher(plain);
            if (m.matches()) {
                return parseInt(m.group(1), -1);
            }
        }
        return -1;
    }

    private static void readTabList(Minecraft client) {
        if (client.getConnection() == null) {
            return;
        }
        // No sim branch: the dungeon sim publishes a Hypixel-shaped tab list (roomsim.SimTabList), so the
        // score HUD reads the same lines in the sim as on Hypixel.
        int completedPuzzles = 0;
        int failedPuzzles = 0;
        boolean sawPuzzleHeader = false;
        boolean matchedAny = false;
        Map<String, Character> states = new HashMap<>();
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) {
                continue;
            }
            String plain = com.killer560.hub.util.ChatObserver.stripCodes(display.getString());
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
                if (!"???".equals(m.group(1))) {
                    states.put(m.group(1).trim().toLowerCase(Locale.ROOT), m.group(2).charAt(0));
                }
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
            puzzleStates = Map.copyOf(states);
        }
        if (matchedAny) {
            // The tab list is server-sent and identical for everyone in the run, so these are SELF facts -
            // no other mod and no relay is needed for them. Shared so Party Interop has one place to read.
            PartyInteropState.offerCounter(PartyInteropState.Counter.SECRETS_FOUND, secretsFound, InteropSource.SELF, null);
            PartyInteropState.offerCounter(PartyInteropState.Counter.CRYPTS, crypts, InteropSource.SELF, null);
            PartyInteropState.offerCounter(PartyInteropState.Counter.DEATHS, deaths, InteropSource.SELF, null);
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
            String plain = com.killer560.hub.util.ChatObserver.stripCodes(text);
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
        if (!estimateWanted() || !DungeonState.isInDungeon()) {
            return;
        }
        // Map-only tracking records the kills but offers no Party Interop flag and sends no alert.
        boolean acts = cfg.isEnabled();
        String plain = ChatObserver.strip(message).trim();
        if (PRINCE_KILLED.matcher(plain).matches()) {
            if (!princeKilled) {
                princeKilled = true;
                if (!acts) {
                    return;
                }
                // The bonus-score line is public server chat identical for the whole party, so this is a
                // SELF fact for Party Interop - same as DungeonInfoFeature used to offer it.
                PartyInteropState.offerFlag(PartyInteropState.Flag.PRINCE_KILLED, InteropSource.SELF, null);
                maybeSendKillAlert("Prince", cfg.isPrinceAlertEnabled(), cfg.getPrinceAlertMessage(), PartyInteropState.Flag.PRINCE_KILLED);
            }
            return;
        }
        if (BAT_KILLED.matcher(plain).matches()) {
            if (!batKilled) {
                batKilled = true;
                if (!acts) {
                    return;
                }
                PartyInteropState.offerFlag(PartyInteropState.Flag.BAT_KILLED, InteropSource.SELF, null);
                maybeSendKillAlert("Bat", cfg.isBatAlertEnabled(), cfg.getBatAlertMessage(), PartyInteropState.Flag.BAT_KILLED);
            }
            return;
        }
        if (WATCHER_DONE.matcher(plain).find()) {
            expectingBloodUpdate = true;
            return;
        }
        Matcher teamScore = TEAM_SCORE.matcher(plain);
        if (teamScore.find()) {
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
        // A mimic killed in a room nobody from this client ever entered is the one bonus point a lone client
        // genuinely cannot see. Party Interop supplies it when someone else's mod announced it in party chat
        // (or when our own relay carried it); with Party Interop off, this is exactly as before.
        if (!mimicKilled && InteropFeature.mimicKilled()) {
            mimicKilled = true;
            LOGGER.info("[ScoreCalc] Mimic marked killed by Party Interop");
        }
        String floor = DungeonState.getFloor();
        int seconds = secondsElapsed >= 0 ? secondsElapsed : (int) ((System.currentTimeMillis() - runStartMs) / 1000L);
        ScoreCalculator.Inputs inputs = new ScoreCalculator.Inputs(floor, secretsPercent, secretsFound, crypts,
                completedRooms, clearedPercent, deaths, puzzleCount, puzzlesCompleted, puzzlesFailed, seconds,
                bloodDone, inBoss, mimicKilled, princeKilled, batKilled, isPaul(cfg), cfg.isAssumeSpiritPet());
        ScoreCalculator.Result result = ScoreCalculator.calculate(inputs);
        lastInputs = inputs;
        lastResult = result;
        if (!tabDataSeen || !cfg.isEnabled()) {
            return; // never alert off an empty tab list, nor while only a map's Extra Info is tracking
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
        Minecraft client = Minecraft.getInstance();
        boolean title = is300 ? cfg.isTitle300() : cfg.isTitle270();
        if (title) {
            String text = (is300 ? cfg.getTitle300Text() : cfg.getTitle270Text()).replace('&', '§');
            McCompat.setTimes(client, 5, 40, 10);
            McCompat.setTitle(client, Component.literal(text.contains("§") ? text : "§6§l" + text));
            McCompat.setSubtitle(client, Component.literal(""));
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
        McCompat.setTimes(client, 5, 40, 10);
        McCompat.setTitle(client, Component.literal(text.contains("§") ? text : "§6§l" + text));
        McCompat.setSubtitle(client, Component.literal(""));
        if (cfg.isAlertSound()) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
        }
    }

    /** Manual "Send Now" for the 270/300 party message - moved here from {@code DungeonInfoFeature}
     *  2026-09-21 (reuses this class's own {@code party270Message}/{@code party300Message} text instead of
     *  the separate, dead-toggle copies that used to live in {@code DungeonInfoConfig}, so there's exactly
     *  one 270/300 message to edit, not two). Sends immediately regardless of the automatic threshold /
     *  once-per-run gates below - killer560 asking for it explicitly overrides those. */
    public static void sendParty270Now() {
        TranslateFeature.sendGenerated(ScoreCalculatorConfig.getInstance().getParty270Message(), "pc");
    }

    public static void sendParty300Now() {
        TranslateFeature.sendGenerated(ScoreCalculatorConfig.getInstance().getParty300Message(), "pc");
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
        ModNet.sendAsync(HTTP, request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
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

    /** The inputs behind {@link #currentResult()}, or null. */
    public static ScoreCalculator.Inputs currentInputs() {
        return DungeonState.isInDungeon() ? lastInputs : null;
    }

    /**
     * A puzzle room's state from the tab list - '✔' done, '✖' failed, '✦' open - or 0 when the tab list does not name
     * it (not visited yet, Score Calculator off, outside a dungeon). Takes the room database's name: both blaze rooms
     * are "Higher Or Lower" on Hypixel's tab list.
     */
    public static char puzzleState(String roomName) {
        if (roomName == null || !DungeonState.isInDungeon()) {
            return 0;
        }
        String key = roomName.trim().toLowerCase(Locale.ROOT);
        if (key.equals("higher blaze") || key.equals("lower blaze")) {
            key = "higher or lower";
        }
        Character c = puzzleStates.get(key);
        return c == null ? 0 : c;
    }

    // ---- read-only accessors for the Dungeon Map's "Extra Info" panel (killer560, 2026-09-27) ----
    // The map never re-derives mimic/prince/bat state or the score formula itself - it only reads what this
    // class already tracks, exactly like this class's own HUD (buildLines above) does.

    public static boolean isMimicKilled() {
        return DungeonState.isInDungeon() && mimicKilled;
    }

    public static boolean isPrinceKilled() {
        return DungeonState.isInDungeon() && princeKilled;
    }

    public static boolean isBatKilled() {
        return DungeonState.isInDungeon() && batKilled;
    }

    public static int getCrypts() {
        return DungeonState.isInDungeon() ? crypts : 0;
    }

    /**
     * Same "S+ Secrets" readout {@link #buildLines} shows on this feature's own HUD, exposed for the Dungeon
     * Map's Extra Info panel so it reuses this run's live estimate instead of re-deriving the formula -
     * {@link ScoreCalculator#calculate} already assumes every room/puzzle ends up done (room score maxed,
     * skill only docked for deaths/failed puzzles already taken) while keeping the CURRENT bonus/speed as
     * they stand, which is exactly "assuming the current amount of crypts/status of the other things, not
     * that they are done but as is" (killer560, 2026-09-27).
     */
    public static String secretsNeededSummary() {
        ScoreCalculator.Result r = currentResult();
        if (r == null || r.secretsNeeded() < 0) {
            return "?";
        }
        if (r.secretsNeeded() == Integer.MAX_VALUE) {
            return "not reachable";
        }
        if (r.secretsRemaining() <= 0) {
            return "done (" + secretsFound + "/" + r.secretsNeeded() + ")";
        }
        return r.secretsRemaining() + " more (" + secretsFound + "/" + r.secretsNeeded() + ")";
    }

    /**
     * The Dungeon Map HUD's Extra Info lines, drawn under the map (killer560, 2026-10-07). One list for both the
     * drawing and the HUD box, so the box is always exactly these lines. Before the first estimate of a run the
     * numbers read "?" rather than the section vanishing; {@code demo} is the HUD editor's sample.
     */
    public static List<String> mapInfoLines(boolean demo) {
        ScoreCalculator.Result r = demo ? new ScoreCalculator.Result(302, 100, 100, 60, 40, 100, 2, 36, 60, 57, 3, "S+")
                : currentResult();
        List<String> out = new ArrayList<>(4);
        if (r == null) {
            out.add("§6Score §7?");
            out.add("§6Secrets §7?");
        } else {
            String sc = r.total() < 270 ? "§c" : r.total() < 300 ? "§e" : "§a";
            out.add("§6Score " + sc + r.total() + " §7(" + sc + r.rank() + "§7)");
            int found = demo ? 54 : secretsFound;
            if (r.secretsNeeded() < 0) {
                out.add("§6Secrets §f" + found + " §7/ ?");
            } else if (r.secretsNeeded() == Integer.MAX_VALUE) {
                out.add("§6Secrets §f" + found + " §cS+ out of reach");
            } else {
                out.add("§6Secrets " + (r.secretsRemaining() <= 0 ? "§a" : "§f") + found
                        + "§7/" + r.secretsNeeded());
            }
        }
        int c = demo ? 5 : getCrypts();
        int d = demo ? 0 : DungeonState.isInDungeon() ? deaths : 0;
        out.add("§6Crypts " + (c >= 5 ? "§a" : "§f") + c + "§7/5  §6Deaths "
                + (d > 0 ? "§c" : "§f") + d);
        boolean mimicFloor = demo || ScoreCalculator.floorNumber(DungeonState.getFloor()) >= 6;
        boolean m = demo || isMimicKilled();
        boolean p = !demo && isPrinceKilled();
        out.add((mimicFloor ? "§6Mimic " + (m ? "§a✔" : "§c✘") + "  " : "")
                + "§6Prince " + (p ? "§a✔" : "§c✘"));
        return out;
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
        return McCompat.screen(Minecraft.getInstance()) instanceof HudEditorScreen;
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
            // Rows 10 apart, the last one only a text row tall.
            return com.killer560.hub.hud.HudText.height(currentLines().size(), 10);
        }

        @Override
        public boolean isEnabledInSettings() {
            // Setting only. In a dungeon and not on p3sim (killer560, 2026-09-21: it is a boss-practice
            // server with no real run to score) are both still enforced in render(), which is where the draw
            // stamp is taken - so the HUD editor gets them from the drawing rather than from a copy here.
            return ScoreCalculatorConfig.getInstance().isEnabled();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
            Minecraft client = Minecraft.getInstance();
            boolean editor = inEditor();
            if (!cfg.isEnabled() || HudVisibility.hidesHud()) {
                return;
            }
            if (!editor && (!DungeonState.isInDungeon() || com.killer560.hub.cheatutils.CheatUtils.isOnP3Sim())) {
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
            if (lineY != y) {
                HudSeen.markDrawn(id());
            }
        }
    }
}
