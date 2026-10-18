package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.ButtonBlock;
import org.slf4j.Logger;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Quiz logger (killer560, 2026-10-18: "quiz starts once you walk in and it starts its countdown timer. If you want,
 * put a quiz logger in my dungeon instance and I will go test it"). Always on, log only - no chat, no setting. Every line
 * starts with {@code [QuizLog]} and carries the server tick ({@link ServerTickClock}, one per server ping - the clock
 * QuizState counts its locks on) and the wall time, so a real run says exactly when he walked in, when each of Oruo's
 * lines came, when QuizState thought each question opened, and what each press got back.
 *
 * <p>INFO through {@link ModLog}: written in a dev or cheat build (the build he plays), dropped by a release.
 */
public final class QuizLog {

    private static final Logger LOGGER = ModLog.get("killer560smod-quizlog");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Pattern QUESTION = Pattern.compile("^Question #([123])$");
    /** A press waits this long for a line from Oruo before it is logged as answered by nothing. */
    private static final int RESULT_WAIT_TICKS = 40;
    private static final net.minecraft.resources.Identifier PHASE =
            net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "quizlog");

    private static Object level;
    private static long clientTick;
    private static boolean inQuiz;
    /** {@link ServerTickClock#now()} he last walked into the Quiz room this world; -1 before. */
    private static long enteredAt = -1;
    private static int entries;
    /** {@link ServerTickClock#now()} of Oruo's last intro this world; -1 before. */
    private static long introAt = -1;
    /** The "Question #N" line just came: the next non-blank line is its text. */
    private static int awaitingText;
    private static int optionsLeft;
    /** The press waiting for Oruo's answer: server tick, client tick, where. */
    private static long pressAt = -1;
    private static long pressClientTick = -1;
    private static BlockPos pressPos;
    /** Server tick of the last press, kept after its result is logged (pressAt is cleared then). */
    private static long lastPressTick = -1;
    /** How long after a press every chat line is logged, in server ticks (about 5 seconds). */
    private static final int CHAT_WINDOW_TICKS = 100;

    private QuizLog() {
    }

    public static void register() {
        ChatObserver.subscribe(QuizLog::onMessage);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("QuizLog", QuizLog::tick));
        // Ahead of every other listener and always PASS: it only watches. A click another listener answers first would
        // otherwise never be seen here.
        UseBlockCallback.EVENT.addPhaseOrdering(PHASE, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
        UseBlockCallback.EVENT.register(PHASE, (player, lv, hand, hit) -> {
            try {
                Minecraft client = Minecraft.getInstance();
                if (lv != null && lv.isClientSide() && player == client.player && hit != null && inQuiz
                        && lv.getBlockState(hit.getBlockPos()).getBlock() instanceof ButtonBlock) {
                    notePress(hit.getBlockPos(), "click");
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[QuizLog] press hook threw", e);
            }
            return InteractionResult.PASS;
        });
    }

    private static String now() {
        return "server tick " + ServerTickClock.now() + ", " + LocalTime.now().format(TIME);
    }

    private static void tick(Minecraft client) {
        clientTick++;
        if (client.level != level) {
            level = client.level;
            inQuiz = false;
            enteredAt = -1;
            entries = 0;
            introAt = -1;
            awaitingText = 0;
            optionsLeft = 0;
            pressAt = -1;
            return;
        }
        if (client.player == null || !DungeonState.isInDungeon()) {
            inQuiz = false;
            return;
        }
        RoomEntry room = LiveMapFeature.isInBoss() ? null : LiveMapFeature.currentRoomEntry();
        boolean quiz = room != null && "Quiz".equals(room.name);
        if (quiz && !inQuiz) {
            enteredAt = ServerTickClock.now();
            entries++;
            LOGGER.info("[QuizLog] entered the Quiz room (entry {} this run) at {} (client tick {}), at {} {} {}; {}", entries,
                    now(), clientTick, fmt(client.player.getX()), fmt(client.player.getY()), fmt(client.player.getZ()),
                    QuizState.describe());
            if (introAt >= 0 && enteredAt - introAt <= 40) {
                LOGGER.info("[QuizLog] intro {} server tick(s) after he walked in (it came before the map named the room)",
                        introAt - enteredAt);
            }
        } else if (!quiz && inQuiz) {
            LOGGER.info("[QuizLog] left the Quiz room at {} (client tick {}); {}", now(), clientTick, QuizState.describe());
        }
        inQuiz = quiz;
        if (pressAt >= 0 && ServerTickClock.now() - pressAt > RESULT_WAIT_TICKS) {
            LOGGER.info("[QuizLog] press on {} at server tick {}: no line from Oruo within {} server ticks",
                    pressPos.toShortString(), pressAt, RESULT_WAIT_TICKS);
            pressAt = -1;
        }
    }

    private static void onMessage(Component message) {
        if (!DungeonState.isInDungeon()) {
            return;
        }
        String raw = ChatObserver.strip(message);
        if (raw == null) {
            return;
        }
        String msg = raw.trim();
        // Every other line, unfiltered, while he is in the Quiz room or within a few seconds of a press. The Oruo lines
        // are logged below; this catches whatever Hypixel says under another name (killer560, 2026-10-18: a wrong answer
        // gave no "chose the wrong answer" line, so the log could not say what it did send).
        boolean recentPress = lastPressTick >= 0 && ServerTickClock.now() - lastPressTick <= CHAT_WINDOW_TICKS;
        if (!msg.startsWith(QuizState.PREFIX) && (inQuiz || recentPress) && !msg.isEmpty()) {
            LOGGER.info("[QuizLog] chat at {}: {}", now(), msg);
        }
        if (msg.startsWith(QuizState.PREFIX)) {
            LOGGER.info("[QuizLog] Oruo at {}: {}", now(), msg.substring(QuizState.PREFIX.length()));
            if (msg.equals(QuizState.INTRO)) {
                introAt = ServerTickClock.now();
                if (inQuiz && enteredAt >= 0) {
                    LOGGER.info("[QuizLog] intro {} server tick(s) after he walked in", introAt - enteredAt);
                } else {
                    // The server can see his feet cross into the room a tick or two before the map names it here; the
                    // entry line says how far apart they were when it comes. Otherwise a teammate walked in.
                    LOGGER.info("[QuizLog] intro while the map does not have him in the Quiz room ({})", enteredAt < 0
                            ? "not entered yet this run" : "he has left it");
                }
            }
            if (QuizState.WRONG.matcher(msg).matches()) {
                LOGGER.info("[QuizLog] WRONG ANSWER line at {}", now());
            }
            if (pressAt >= 0) {
                LOGGER.info("[QuizLog] press on {} at server tick {} -> {} server tick(s) later: {}", pressPos.toShortString(),
                        pressAt, ServerTickClock.now() - pressAt, msg.substring(QuizState.PREFIX.length()));
                pressAt = -1;
            }
            return;
        }
        Matcher q = QUESTION.matcher(msg);
        if (q.matches()) {
            awaitingText = Integer.parseInt(q.group(1));
            optionsLeft = 0;
            return;
        }
        if (awaitingText > 0 && !msg.isEmpty()) {
            int n = awaitingText;
            awaitingText = 0;
            optionsLeft = 3;
            long at = QuizState.answerAtTick();
            LOGGER.info("[QuizLog] question {}: \"{}\" at {}; QuizState: question {}, answerable at server tick {} ({})", n,
                    msg, now(), QuizState.question(), at, at < 0 ? "no lock line seen"
                            : QuizState.ticksUntilAnswerable() + " tick(s) from now");
            return;
        }
        if (optionsLeft > 0 && !msg.isEmpty() && (msg.startsWith("ⓐ") || msg.startsWith("ⓑ") || msg.startsWith("ⓒ"))) {
            optionsLeft--;
            LOGGER.info("[QuizLog] option {}", msg);
        }
    }

    /**
     * A press on a button in the Quiz room - his click (the hook above) or Auto Quiz's ({@code AutoPuzzlesFeature}). One
     * line per press, however many paths report it in the same tick.
     */
    public static void notePress(BlockPos pos, String who) {
        if (pos == null || pos.equals(pressPos) && pressClientTick == clientTick) {
            return;
        }
        long t = ServerTickClock.now();
        if (pressAt >= 0) {
            LOGGER.info("[QuizLog] press on {} at server tick {}: no line from Oruo before the next press, {} tick(s) later",
                    pressPos.toShortString(), pressAt, t - pressAt);
        }
        long open = QuizState.answerAtTick();
        String timing;
        if (!QuizState.started()) {
            timing = "before Oruo began";
        } else if (!QuizState.awaitingAnswer()) {
            timing = "between questions (question " + QuizState.question() + " answered, the next not announced)";
        } else if (t < open) {
            timing = "EARLY, " + (open - t) + " server tick(s) before QuizState's open at " + open;
        } else {
            timing = "on time, " + (t - open) + " server tick(s) after QuizState's open at " + open;
        }
        LOGGER.info("[QuizLog] press ({}) on {} (answer {}) at {} (client tick {}), question {}: {}", who, pos.toShortString(),
                pillarOf(pos), now(), clientTick, QuizState.nextQuestion(), timing);
        pressAt = t;
        lastPressTick = t;
        pressClientTick = clientTick;
        pressPos = pos.immutable();
    }

    private static String pillarOf(BlockPos button) {
        List<BlockPos> pillars = QuizSolverFeature.answerPillars();
        if (pillars == null) {
            return "?";
        }
        for (int i = 0; i < pillars.size(); i++) {
            if (pillars.get(i).distManhattan(button) == 1) {
                return String.valueOf((char) ('a' + i));
            }
        }
        return "none";
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
