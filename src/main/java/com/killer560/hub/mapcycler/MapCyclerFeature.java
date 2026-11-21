package com.killer560.hub.mapcycler;

import com.killer560.hub.creationcooldown.CreationCooldownFeature;
import com.killer560.hub.doorkeys.DungeonKeys;
import com.killer560.hub.instaclearwarning.InstaClearWarningFeature;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.macrostop.MacroStop;
import com.killer560.hub.scorecalc.ScoreCalculatorFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ServerCommands;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

import java.util.regex.Pattern;

/**
 * Map Cycler (roomcycle-1118, killer560 2026-11-18: "the player can choose how many rooms they want the map cycler to leave
 * for rooms before blood rush ... it should run off of a keybind or /roomcycle in which case it toggles till it finds a room or
 * you press it again"). Cheat build only.
 *
 * <p>While it runs, each dungeon map is judged by {@link RushBounds}: rooms between the Entrance and Blood, not counting
 * either. A map whose most is at or under Max Rooms Before Blood is kept (sound, one line, the cycler turns off); one whose
 * least is over it is re-rolled; an unsure map waits for more of the map until Hypixel's 30 s creation cooldown ends and
 * then is re-rolled (mapcycler-keep-1121, mapcycler-unsure-1121: killer560 2026-11-21: "it should decide by the time that the reque timer
 * is done. at that point it then reques 1s after the timer ends no matter what"). A re-roll is the command he types
 * himself, {@code /joininstance <this floor>} from inside the run (seen 7 times 11-17, 32-50 s apart), sent 1 s after the
 * cooldown ends (fixed, nothing to set: killer560 11-21 "it should be a fixed 1s after the cooldown is off"), through {@link ActionGate}, and only by the player who made the
 * instance (the name in its "entered" line): anyone else gets "Only the instance creator can re-queue!". It acts only in
 * the Entrance room before the run starts ("it should only work in the green room while the run hasnt started yet"):
 * Mort's run-start line stops it for that dungeon, quietly. Evidence: killer560s-mod-logs/roomcycle-1118.md.
 */
public final class MapCyclerFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-mapcycler");
    public static final String FEATURE = "Map Cycler";

    /** Not the instance creator, or not the party leader: stop, nothing else to try. */
    private static final Pattern NOT_LEADER = Pattern.compile(
            "^(?:Only the instance creator can re-queue!|You are not the party leader!?|You must be the party leader.*|Only the party leader can .*)$");
    /** The server would not take the command yet: back off and try again. */
    private static final Pattern THROTTLE = Pattern.compile(
            "^(?:Command Failed: This command is on cooldown!.*|You are already queued.*|Cannot join .* for a moment!.*|There was a problem joining.*|Couldn't find a server.*)$");
    /** A send is answered within this; later lines are about something else. */
    private static final long ANSWER_MS = 8_000L;
    /** No new run this long after a send: the send is treated as refused. */
    private static final long NO_RUN_MS = 45_000L;
    /** Out of a dungeon (or off SkyBlock) this long without a re-roll of ours: he left, stop. */
    private static final long LEFT_MS = 10_000L;
    private static final int MAX_FAILS = 5;

    enum Phase { WAITING, DECIDING, REROLL, SENT }

    private static boolean running;
    private static Phase phase = Phase.WAITING;
    private static int seenGen = -1;
    private static long genAtMs;
    private static int runGen = -1;
    private static long runStartMs;
    private static int bloodOpenGen = -1;
    private static int runStartedGen = -1;
    /** When it began judging this run: a cycler started after the cooldown ended still waits 1 s before a re-roll. */
    private static long judgedFromMs;
    private static DungeonLayout lastLayout;
    private static int[] bounds = {0, RushBounds.NO_MAX};
    private static String reason = "";
    private static int rerolls;
    private static int fails;
    private static long lastSendMs;
    private static long backoffUntilMs;
    private static long awayMs;
    private static String floor;
    /** Testkit: the last command sent, how many went, and the last line said. */
    static String lastCommand;
    static int sent;
    static String lastSaid;
    /** Testkit: bounds to judge instead of the live map's (a fake server has no dungeon map). */
    static volatile int[] boundsForTest;
    /** Testkit: whether he stands in the Entrance room (a fake server has no dungeon map); null reads the live map. */
    static volatile Boolean entranceForTest;

    private MapCyclerFeature() {
    }

    public static void register() {
        MapCyclerConfig.getInstance();
        ChatObserver.subscribe(m -> onChat(ChatObserver.strip(m)));
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("MapCyclerFeature", MapCyclerFeature::tick));
        MacroStop.register(FEATURE, MacroStop.Kind.EMERGENCY, () -> running, why -> stop("Stopped (you pressed " + why + ")."));
        ClientCommandRegistrationCallback.EVENT.register((d, a) -> d.register(ClientCommands.literal("roomcycle")
                .executes(ctx -> {
                    Minecraft.getInstance().execute(MapCyclerFeature::toggle);
                    return 1;
                })));
    }

    public static boolean isRunning() {
        return running;
    }

    /** The key and /roomcycle: start, or stop when running. */
    public static void toggle() {
        setRunning(!running);
    }

    public static void setRunning(boolean on) {
        if (on == running) {
            return;
        }
        if (!on) {
            stop("Stopped.");
            return;
        }
        if (!MapCyclerConfig.getInstance().isEnabled()) {
            say(ModChat.bad("Turn Map Cycler on in the menu first."));
            return;
        }
        running = true;
        phase = Phase.WAITING;
        rerolls = 0;
        fails = 0;
        backoffUntilMs = 0;
        awayMs = 0;
        lastLayout = null;
        runGen = -1;
        judgedFromMs = System.currentTimeMillis();
        LOGGER.info("[MapCycler] started: max {} room(s) before Blood, unsure -> {}", MapCyclerConfig.getInstance().getMaxRooms(),
                "re-roll");
        say(ModChat.text("Started: looking for a map with at most "), ModChat.value(String.valueOf(MapCyclerConfig.getInstance().getMaxRooms())),
                ModChat.text(" room(s) before Blood."));
    }

    /** Stops it; {@code why} is said in chat (null: quietly, e.g. the switch went off). */
    public static void stop(String why) {
        if (!running) {
            return;
        }
        running = false;
        LOGGER.info("[MapCycler] stopped after {} re-roll(s): {}", rerolls, why);
        if (why != null) {
            say(ModChat.text(why));
        }
    }

    private static void say(net.minecraft.network.chat.Component... parts) {
        lastSaid = ModChat.line(FEATURE, parts).getString();
        ModChat.send(FEATURE, parts);
    }

    static void onChat(String plain) {
        if (plain == null || !MapCyclerConfig.getInstance().isEnabled()) {
            return;
        }
        String line = plain.trim();
        if (DungeonKeys.BLOOD_DOOR_OPENED.matcher(line).matches()) {
            bloodOpenGen = LiveMapFeature.resetGeneration();
            return;
        }
        if (ScoreCalculatorFeature.RUN_START.matcher(line).matches()) {
            runStartedGen = LiveMapFeature.resetGeneration();
            return;
        }
        if (!running) {
            return;
        }
        if (NOT_LEADER.matcher(line).matches()) {
            stop("Only the player who started the run can re-roll it; stopped.");
        } else if (phase == Phase.SENT && System.currentTimeMillis() - lastSendMs < ANSWER_MS && THROTTLE.matcher(line).matches()) {
            refused("the server answered \"" + line + "\"");
        }
    }

    /** The re-roll did not go through: wait longer each time, give up after {@link #MAX_FAILS}. */
    private static void refused(String what) {
        fails++;
        LOGGER.info("[MapCycler] re-roll refused ({} of {}): {}", fails, MAX_FAILS, what);
        if (fails >= MAX_FAILS) {
            stop("The server refused the re-roll " + MAX_FAILS + " times; stopped.");
            return;
        }
        backoffUntilMs = System.currentTimeMillis() + Math.min(120_000L, 5_000L << fails);
        phase = Phase.REROLL;
        ModChat.log(FEATURE, ModChat.text("Re-roll refused (" + what + "), trying again in "
                + (backoffUntilMs - System.currentTimeMillis()) / 1000 + " s"));
    }

    private static void tick(Minecraft client) {
        MapCyclerConfig cfg = MapCyclerConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        int gen = LiveMapFeature.resetGeneration();
        long now = System.currentTimeMillis();
        if (gen != seenGen) {
            seenGen = gen;
            genAtMs = now;
        }
        if (!running || client.player == null) {
            return;
        }
        boolean inRun = DungeonState.isInDungeon() && !DungeonState.isBossPhaseActive();
        if (phase == Phase.SENT) {
            if (gen != runGen && inRun) {
                fails = 0;
                newRun(gen, genAtMs);
            } else {
                if (now - lastSendMs > NO_RUN_MS) {
                    refused("no new run " + NO_RUN_MS / 1000 + " s after it");
                }
                return;
            }
        }
        if (!inRun) {
            boolean left = phase != Phase.WAITING || !SkyblockGate.isOnSkyblock();
            if (!left) {
                awayMs = 0;
            } else if (awayMs == 0) {
                awayMs = now;
            } else if (now - awayMs > LEFT_MS) {
                stop(phase == Phase.WAITING ? "You left SkyBlock; stopped." : "You left the dungeon; stopped.");
            }
            return;
        }
        awayMs = 0;
        if (phase == Phase.WAITING || gen != runGen) {
            // Time from the run's arrival as seen while the switch was on (a run older than that is judged at once).
            newRun(gen, genAtMs);
        }
        if (bloodOpenGen == gen) {
            stop("The blood door is open; stopped.");
            return;
        }
        if (runStartedGen == gen) {
            LOGGER.info("[MapCycler] the run started: stopped for this dungeon");
            stop(null);
            return;
        }
        if (!(entranceForTest != null ? entranceForTest : CreationCooldownFeature.inEntrance())) {
            return;
        }
        // Recounted only when the layout object changes (the map learned something, or he moved rooms): 36 slots.
        DungeonLayout layout = DungeonLayout.current();
        if (layout != null && layout != lastLayout || boundsForTest != null) {
            lastLayout = layout;
            int[] b = boundsForTest != null ? boundsForTest : RushBounds.of(layout, floor);
            if (b[0] != bounds[0] || b[1] != bounds[1]) {
                LOGGER.info("[MapCycler] rooms before Blood: {}..{}", b[0], b[1] == RushBounds.NO_MAX ? "?" : b[1]);
            }
            bounds = b;
        }
        decide(cfg, now);
    }

    private static void newRun(int gen, long startMs) {
        runGen = gen;
        runStartMs = startMs;
        judgedFromMs = System.currentTimeMillis();
        floor = DungeonState.getFloor();
        lastLayout = null;
        bounds = new int[]{0, RushBounds.NO_MAX};
        phase = Phase.DECIDING;
        LOGGER.info("[MapCycler] judging a {} map (re-rolls so far {})", floor, rerolls);
    }

    /** When the map must be decided by: the end of this run's creation cooldown (30 s after its arrival when no "entered"
     *  line was seen for it), never before it began judging the run. */
    private static long decideByMs() {
        long end = CreationCooldownFeature.endsAtMs();
        if (end < runStartMs) {
            end = runStartMs + CreationCooldownFeature.COOLDOWN_MS;
        }
        return Math.max(end, judgedFromMs);
    }

    private static void decide(MapCyclerConfig cfg, long now) {
        int n = cfg.getMaxRooms();
        if (bounds[1] <= n) {
            // A most below the least's: proven from the map, not a guess (the path is not fully seen yet).
            if (bounds[0] == bounds[1]) {
                found("Found a map: ", bounds[1], " room(s) to Blood.");
            } else {
                found("Kept: Blood is at most ", bounds[1], " room(s) away.");
            }
            return;
        }
        long decideBy = decideByMs();
        if (phase == Phase.DECIDING) {
            if (bounds[0] > n) {
                reason = "at least " + bounds[0] + " room(s) before Blood";
            } else if (now < decideBy) {
                return;
            } else {
                // Not proven within the limit by the cooldown's end: always re-rolled (killer560 11-21, never kept on a guess).
                reason = "unsure (" + bounds[0] + "-" + (bounds[1] == RushBounds.NO_MAX ? "?" : bounds[1]) + " room(s))";
            }
            phase = Phase.REROLL;
            LOGGER.info("[MapCycler] re-roll wanted: {}", reason);
        }
        reroll(now, decideBy + 1_000L);
    }

    private static void found(String what, int rooms, String tail) {
        running = false;
        LOGGER.info("[MapCycler] {}{}{} after {} re-roll(s)", what, rooms, tail, rerolls);
        say(ModChat.good(what), ModChat.value(String.valueOf(rooms)), ModChat.text(tail));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f));
    }

    /** Sends the re-roll once {@code atMs} (1 s after the creation cooldown) has come. */
    private static void reroll(long now, long atMs) {
        MapCyclerConfig cfg = MapCyclerConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        String creator = CreationCooldownFeature.creator();
        if (creator != null && client.player != null && !creator.equalsIgnoreCase(client.player.getGameProfile().name())) {
            stop("Only " + creator + " (who started this run) can re-roll it; stopped.");
            return;
        }
        if (cfg.getMaxRerolls() > 0 && rerolls >= cfg.getMaxRerolls()) {
            stop("Max Re-rolls (" + cfg.getMaxRerolls() + ") reached; stopped.");
            return;
        }
        String id = InstaClearWarningFeature.instanceFor(floor);
        if (id == null) {
            stop("Cannot re-roll floor " + floor + "; stopped.");
            return;
        }
        if (now < atMs || now < backoffUntilMs
                || MacroStop.emergencyHeld() || !ActionGate.tryAct(ActionGate.Actor.MAP_CYCLER)) {
            return;
        }
        String cmd = "joininstance " + id;
        if (!ServerCommands.toServer(cmd)) {
            return;
        }
        lastSendMs = now;
        lastCommand = cmd;
        sent++;
        rerolls++;
        phase = Phase.SENT;
        LOGGER.info("[MapCycler] re-roll {}: /{} ({})", rerolls, cmd, reason);
        ModChat.log(FEATURE, ModChat.text("Re-roll " + rerolls + ": " + reason + " (limit " + cfg.getMaxRooms() + ")"));
    }
}
