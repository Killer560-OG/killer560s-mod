package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.witherdragons.ServerTickClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the dungeon Quiz (Oruo the Omniscient) is, for every client feature: started or not, which of its three
 * questions, and how many server ticks until that question can be answered (killer560, 2026-10-17: "the first time you
 * open it there is a long cooldown till you can answer then a shorter one after that for a total of 3 answers").
 *
 * <p>Driven only by Oruo's own chat lines, matched whole (equals, or an anchored pattern with a real name group), so it
 * reads the same on Hypixel and in the sim and no player's chat can move it. The timings are NoammAddons' and Odin's
 * (NoammAddons {@code features/impl/dungeon/solvers/puzzles/QuizSolver.kt}: {@code answerTime = currentTime + 220} on
 * "I am Oruo the Omniscient...", {@code + 100} on "2 questions left..." and on "One more question!", counted in server
 * ticks; Odin/QUOI {@code QuizSolver.kt} {@code START_DURATION = 220}, {@code QUESTION_DURATION = 100}). His own Hypixel
 * logs (276 Quiz runs, 26.1.2 (Dungeons), July-October 2026) agree: the first question answered 11 s after the intro in
 * the most runs and never sooner than 10 s, each later one 5 s after its line and never sooner than 4 s.
 *
 * <p>The ticks are counted with {@link ServerTickClock} (one per server ping, as NoammAddons counts them), so a server
 * that lags holds the answer back by exactly as long as the server does.
 */
public final class QuizState {

    private static final Logger LOGGER = ModLog.get("killer560smod-quiz");

    public static final String PREFIX = "[STATUE] Oruo the Omniscient: ";
    public static final String INTRO = PREFIX + "I am Oruo the Omniscient. I have lived many lives. I have learned all there is to know.";
    public static final String SECOND = PREFIX + "2 questions left... Then you will have proven your worth to me!";
    public static final String THIRD = PREFIX + "One more question!";
    /** Server ticks from {@link #INTRO} until question 1 can be answered. */
    public static final int FIRST_LOCK_TICKS = 220;
    /** Server ticks from {@link #SECOND} / {@link #THIRD} until that question can be answered. */
    public static final int LATER_LOCK_TICKS = 100;

    private static final Pattern ANSWERED = Pattern.compile(
            "^\\[STATUE] Oruo the Omniscient: ([A-Za-z0-9_]{1,16}) answered (?:Question #([12])|the final question) correctly!$");

    /** Oruo's wrong-answer line, as he reported it on Hypixel (ArchitectDraftFeature, 2026-09-27); the puzzle fails. */
    public static final Pattern WRONG = Pattern.compile(
            "^\\[STATUE] Oruo the Omniscient: (?:\\[[^\\]]{1,20}\\] )?([A-Za-z0-9_]{1,16}) chose the wrong answer!.*$");

    /** 0 not started, 1-3 the question being asked (or waited for), 4 every question answered. */
    private static int question;
    /** Whether the current question has been answered (Oruo's "answered Question #N" line), waiting for the next. */
    private static boolean answered;
    /** {@link ServerTickClock#now()} from which the current question can be answered; -1 before a lock line. */
    private static long answerAt = -1;
    /** {@link ServerTickClock#now()} when the last line moved it, for the testkit. */
    private static long lastLineAt = -1;
    /** {@link ServerTickClock#now()} of the last "answered" line. */
    private static long answeredAt = -1;
    private static Object level;

    private QuizState() {
    }

    public static void register() {
        ServerTickClock.register();
        ChatObserver.subscribe(QuizState::onMessage);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("QuizState", client -> {
            if (client.level != level) {
                level = client.level;
                reset("world changed");
            }
        }));
    }

    private static void onMessage(Component message) {
        String msg = ChatObserver.strip(message);
        if (msg == null || !msg.startsWith(PREFIX)) {
            return;
        }
        msg = msg.trim();
        long now = ServerTickClock.now();
        if (msg.equals(INTRO)) {
            set(1, now + FIRST_LOCK_TICKS, now, "Oruo began");
        } else if (msg.equals(SECOND)) {
            set(2, now + LATER_LOCK_TICKS, now, "question 2");
        } else if (msg.equals(THIRD)) {
            set(3, now + LATER_LOCK_TICKS, now, "question 3");
        } else if (WRONG.matcher(msg).matches()) {
            reset("a wrong answer");
        } else {
            Matcher m = ANSWERED.matcher(msg);
            if (!m.matches()) {
                return;
            }
            int n = m.group(2) == null ? 3 : Integer.parseInt(m.group(2));
            lastLineAt = now;
            answeredAt = now;
            if (n >= 3) {
                question = 4;
                answered = true;
                answerAt = -1;
                LOGGER.info("[QuizState] final question answered by {} (server tick {})", m.group(1), now);
            } else {
                question = n;
                answered = true;
                answerAt = -1;
                LOGGER.info("[QuizState] question {} answered by {} (server tick {})", n, m.group(1), now);
            }
        }
    }

    private static void set(int q, long at, long now, String why) {
        question = q;
        answered = false;
        answerAt = at;
        lastLineAt = now;
        LOGGER.info("[QuizState] {}: question {} of 3 answerable at server tick {} ({} ticks from now {})", why, q, at,
                at - now, now);
    }

    private static void reset(String why) {
        if (question != 0) {
            LOGGER.info("[QuizState] reset ({})", why);
        }
        question = 0;
        answered = false;
        answerAt = -1;
        lastLineAt = -1;
        answeredAt = -1;
    }

    /** Oruo has begun (his first line was seen), this world. */
    public static boolean started() {
        return question > 0;
    }

    /** Every question answered. */
    public static boolean done() {
        return question >= 4;
    }

    /** Started and not done. */
    public static boolean inProgress() {
        return question > 0 && question < 4;
    }

    /** 1-3: the question asked or about to be (after an answer, the one answered until the next line); 0/4 outside. */
    public static int question() {
        return question;
    }

    /** The question the next answer is for: the one asked, or after an answer the one coming (1-3; 0/4 outside). */
    public static int nextQuestion() {
        return inProgress() && answered ? question + 1 : question;
    }

    /** Questions answered so far (0-3). */
    public static int answeredCount() {
        if (question >= 4) {
            return 3;
        }
        return question <= 0 ? 0 : question - 1 + (answered ? 1 : 0);
    }

    /** The current question waits for its cooldown or is open: not yet answered, its lock line seen. */
    public static boolean awaitingAnswer() {
        return inProgress() && !answered && answerAt >= 0;
    }

    /** The current question can be answered now. */
    public static boolean answerable() {
        return awaitingAnswer() && ServerTickClock.now() >= answerAt;
    }

    /**
     * Server ticks until the current question can be answered: 0 when it can now; for a question just answered, until
     * the next is expected to open (the answer plus Oruo's two-second line plus {@link #LATER_LOCK_TICKS}); -1 when no
     * Quiz is in progress.
     */
    public static long ticksUntilAnswerable() {
        if (!inProgress()) {
            return -1;
        }
        if (answered) {
            // Oruo's next lock line is not here yet: the soonest the next question can open is his usual line gap plus the
            // lock, counted from the answer - so a planner deciding again a few seconds later sees the time go down.
            return Math.max(0L, answeredAt + NEXT_LINE_TICKS + LATER_LOCK_TICKS - ServerTickClock.now());
        }
        return Math.max(0L, answerAt - ServerTickClock.now());
    }

    /** Oruo's next lock line comes about this long after an answer (his logs: 2 s in 252 of 269 runs). */
    public static final int NEXT_LINE_TICKS = 40;

    /** {@link ServerTickClock#now()} the current question opens at (-1 when none waits). */
    public static long answerAtTick() {
        return answerAt;
    }

    /** {@link ServerTickClock#now()} of the last line that moved the state, for the testkit. */
    public static long lastLineTick() {
        return lastLineAt;
    }

    /** One line for HUDs and logs: "Quiz 2/3: 3.4s" or "Quiz 2/3: answer now". */
    public static String describe() {
        if (!inProgress()) {
            return done() ? "Quiz done" : "Quiz not started";
        }
        if (answered) {
            return "Quiz " + question + "/3 answered, next soon";
        }
        long t = ticksUntilAnswerable();
        return t <= 0 ? "Quiz " + question + "/3: answer now"
                : String.format(java.util.Locale.US, "Quiz %d/3: %.1fs", question, t / 20.0);
    }
}
