package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Real Hypixel dungeon "Quiz" puzzle solver, ported from Odin's own {@code QuizSolver.kt}. The real
 * Oruo the Omniscient statue asks a real trivia question in chat with 3 real lettered answer options
 * (ⓐ/ⓑ/ⓒ), each standing on one of 3 fixed real relative floor positions; the correct option is looked up
 * from a bundled real question-to-answer(s) database ({@code data/killer560smod/puzzles/quiz-answers.json},
 * copied verbatim from Odin) and highlighted with a floor box. Never answers for you - only highlights.
 */
public final class QuizSolverFeature {

    private static final Map<String, List<String>> ANSWERS = loadAnswers();

    private static final class TriviaOption {
        BlockPos blockPos;
        boolean correct;
    }

    private static final TriviaOption[] options = {new TriviaOption(), new TriviaOption(), new TriviaOption()};
    private static List<String> triviaAnswers = null;
    private static RoomEntry lastRoomEntry = null;

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-puzzlesolvers");
    /** Logging only: last room state logged ("none" outside the Quiz room), so lines are written on CHANGE. */
    private static String roomLogKey = "none";

    private static void logRoomState(String key, String detail) {
        if (key.equals(roomLogKey)) {
            return;
        }
        roomLogKey = key;
        LOGGER.info("[QuizSolver] {}", detail);
    }

    private static String letterOf(int index) {
        return index == 0 ? "a" : index == 1 ? "b" : index == 2 ? "c" : "?";
    }

    private QuizSolverFeature() {
    }

    public static void register() {
        // Shared solver highlight pipelines must exist before the level renderer precompiles them.
        SolverEspRender.init();
        // ChatObserver, not Fabric CHAT/GAME: Odin/NoammAddons/Skyblocker can cancel a server line via
        // ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric listeners never see.
        // Triggers here are exact/anchored server-format lines, so this mod's own client-side messages (which
        // ChatObserver also delivers) can't match. Overlay (action bar) lines are not delivered - none needed.
        ChatObserver.subscribe(QuizSolverFeature::onMessage);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("QuizSolverFeature", com.killer560.hub.util.FeatureBits.register("Quiz Solver tick", () -> QuizSolverConfig.getInstance().isEnabledRaw()), client -> {
            onTick();
        }));
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(com.killer560.hub.util.FeatureGuard.afterTerrain(
                com.killer560.hub.util.FeatureBits.register("Quiz Solver render", () -> QuizSolverConfig.getInstance().isEnabledRaw()), QuizSolverFeature::onWorldRender));
    }

    private static Map<String, List<String>> loadAnswers() {
        try (InputStream stream = QuizSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/quiz-answers.json")) {
            if (stream == null) {
                // Inline logger: this runs in the static initializer, before LOGGER is assigned.
                com.killer560.hub.util.ModLog.get("killer560smod-puzzlesolvers").warn(
                        "[QuizSolver] answers resource missing - no question will ever be recognized");
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, List<String>>>() {
                }.getType();
                Map<String, List<String>> parsed = new Gson().fromJson(reader, type);
                return parsed != null ? parsed : Map.of();
            }
        } catch (Exception e) {
            com.killer560.hub.util.ModLog.get("killer560smod-puzzlesolvers").warn("[QuizSolver] Failed to load answers", e);
            return Map.of();
        }
    }

    private static void onTick() {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!QuizSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            if (lastRoomEntry != null || triviaAnswers != null || options[0].blockPos != null) {
                LOGGER.info("[QuizSolver] solver idle: enabled={} inDungeon={} inBoss={} - question and positions cleared",
                        QuizSolverConfig.getInstance().isEnabled(), DungeonState.isInDungeon(), LiveMapFeature.isInBoss());
                roomLogKey = "none";
                lastRoomEntry = null;
                reset();
            }
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            if (QuizState.inProgress()) {
                // Oruo asks the next question wherever he is (his lines reach the whole dungeon), and Auto Secret leaves
                // the room between questions (2026-10-17): a question asked while he was in another room must still be
                // known when he walks back in. Only the room's positions are worked out again.
                for (TriviaOption option : options) {
                    option.blockPos = null;
                }
                if (!"none".equals(roomLogKey) || (current != null && "Quiz".equals(current.name))) {
                    LOGGER.info("[QuizSolver] room changed to {} while the quiz is in progress ({}): keeping the question"
                                    + " (answers known={}), recomputing pillar positions",
                            current == null ? "no known room" : current.name, QuizState.describe(), triviaAnswers != null);
                }
            } else {
                reset();
            }
        }
        if ((current == null || !"Quiz".equals(current.name)) && !"none".equals(roomLogKey)) {
            logRoomState("none", "left Quiz: now in " + (current == null ? "no known room" : current.name));
        }
        if (current == null || !"Quiz".equals(current.name) || options[0].blockPos != null) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (clayAndRotation == null) {
            logRoomState("wait", "in Quiz but clay/rotation not known yet - answer pillars not placed, waiting for the map");
            return;
        }
        options[0].blockPos = realPos(20, 70, 6, clayAndRotation);
        options[1].blockPos = realPos(15, 70, 9, clayAndRotation);
        options[2].blockPos = realPos(10, 70, 6, clayAndRotation);
        roomLogKey = "room " + clayAndRotation[0] + "," + clayAndRotation[1] + " rot " + clayAndRotation[2];
        LOGGER.info("[QuizSolver] Quiz detected: clay={},{} rotation={} pillars a={} b={} c={} ({}, answers known={})",
                clayAndRotation[0], clayAndRotation[1], clayAndRotation[2], options[0].blockPos.toShortString(),
                options[1].blockPos.toShortString(), options[2].blockPos.toShortString(), QuizState.describe(),
                triviaAnswers != null);
    }

    /** Its own copy of {@link PuzzleCoords#real}, now delegating: the shared one carries the sim's floor
     *  shift, and four private copies of the same three lines is how three of them came to be missing it. */
    private static BlockPos realPos(int x, int y, int z, int[] clayAndRotation) {
        return PuzzleCoords.real(x, y, z, clayAndRotation);
    }

    private static void onMessage(Component message) {
        if (!QuizSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            return;
        }
        String msg = ChatObserver.strip(message);

        // Oruo's three lock lines open a new question (NoammAddons QuizSolver.kt clears the options on each): nothing
        // known about the last one applies to the next.
        String whole = msg.trim();
        if (whole.equals(QuizState.INTRO) || whole.equals(QuizState.SECOND) || whole.equals(QuizState.THIRD)) {
            LOGGER.info("[QuizSolver] new question line from Oruo - previous answers and highlight cleared ({})",
                    whole.equals(QuizState.INTRO) ? "intro" : whole.equals(QuizState.SECOND) ? "question 2" : "question 3");
            clearForNewQuestion();
            return;
        }

        if (msg.startsWith("[STATUE] Oruo the Omniscient: ") && msg.endsWith("correctly!")) {
            if (msg.contains("answered the final question")) {
                LOGGER.info("[QuizSolver] final question answered - solver reset, puzzle done");
                reset();
                return;
            }
            if (msg.contains("answered Question #")) {
                LOGGER.info("[QuizSolver] question answered - highlight and answers cleared until the next question");
                for (TriviaOption option : options) {
                    option.correct = false;
                }
                // The next question's answers aren't known yet - don't match its options against the old answers.
                triviaAnswers = null;
            }
        }

        String trimmed = msg.trim();
        if (startsWithAnswerLetter(trimmed) && triviaAnswers != null
                && triviaAnswers.stream().anyMatch(trimmed::endsWith)) {
            char letter = Character.toLowerCase(trimmed.charAt(0));
            int index = switch (letter) {
                case 'ⓐ' -> 0;
                case 'ⓑ' -> 1;
                case 'ⓒ' -> 2;
                default -> -1;
            };
            if (index >= 0) {
                options[index].correct = true;
                BlockPos pillar = options[index].blockPos;
                LOGGER.info("[QuizSolver] solution: option {} \"{}\" is correct - pillar {}", letterOf(index), trimmed,
                        pillar == null ? "not placed yet (not in the Quiz room / clay unknown)" : pillar.toShortString());
            } else {
                LOGGER.info("[QuizSolver] answer line \"{}\" matched an answer but its letter is not a/b/c - not marked",
                        trimmed);
            }
        } else if (startsWithAnswerLetter(trimmed)) {
            LOGGER.info("[QuizSolver] option line \"{}\" is not correct (answers known={})", trimmed, triviaAnswers != null);
        }

        if (trimmed.equals("What SkyBlock year is it?")) {
            long skyblockYear = ((System.currentTimeMillis() / 1000L) - 1560276000L) / 446400L + 1;
            triviaAnswers = List.of("Year " + skyblockYear);
            LOGGER.info("[QuizSolver] question recognized: SkyBlock year -> answer \"Year {}\"", skyblockYear);
            return;
        }
        // Oruo's question is a server line of its own - centering spaces and the question, nothing in front -
        // which is why the year question above is compared on the trimmed line. A party/guild/all-chat/DM
        // line always starts with its channel, rank or "name: " prefix, so requiring the question at the very
        // start keeps anyone quoting a question from overwriting the answers. startsWith rather than equals:
        // some keys are only the first line of a question Hypixel wraps ("...who sells stained").
        for (Map.Entry<String, List<String>> entry : ANSWERS.entrySet()) {
            if (trimmed.startsWith(entry.getKey())) {
                triviaAnswers = entry.getValue();
                LOGGER.info("[QuizSolver] question recognized: \"{}\" -> {} accepted answer(s) {}", entry.getKey(),
                        entry.getValue() == null ? 0 : entry.getValue().size(), entry.getValue());
                return;
            }
        }
    }

    private static boolean startsWithAnswerLetter(String trimmed) {
        return !trimmed.isEmpty()
                && (trimmed.startsWith("ⓐ") || trimmed.startsWith("ⓑ") || trimmed.startsWith("ⓒ")
                || trimmed.startsWith("Ⓐ") || trimmed.startsWith("Ⓑ") || trimmed.startsWith("Ⓒ"));
    }

    private static void onWorldRender(LevelRenderContext context) {
        if (!QuizSolverConfig.getInstance().isEnabled() || triviaAnswers == null) {
            return;
        }
        for (TriviaOption option : options) {
            if (!option.correct || option.blockPos == null) {
                continue;
            }
            // THE BLOCK ITSELF, not the one under it.
            //
            // Decoding Quiz.json settles what these three coordinates actually name. At the capture's
            // database rotation (180) they land on capture-local (11,70,25), (16,70,22) and (21,70,25) -
            // each a smooth_stone block with air above it, and each ringed by FOUR of the room's twelve
            // wall-mounted stone buttons, which is all twelve of them. So (20,70,6) and its two siblings are
            // the little pillars Oruo's answer buttons hang off, not floor to stand on - and highlighting one
            // block lower put the box inside the pillar's own stonework.
            SolverEspRender.renderWaypoint(com.killer560.hub.puzzlesolvers.SolverEspConfig.Solver.QUIZ, context, new AABB(option.blockPos), 0.2f, 1.0f, 0.3f, 2f);
        }
    }

    /** The correct answer's block, once the question's answers are known and the answer holograms were found. */
    public static BlockPos getCorrectAnswerPos() {
        if (triviaAnswers == null) {
            return null;
        }
        for (TriviaOption o : options) {
            if (o.correct && o.blockPos != null) {
                return o.blockPos;
            }
        }
        return null;
    }

    /**
     * The BUTTON to right-click for the correct answer, which is not the same block as the answer pillar.
     *
     * <p>{@link #getCorrectAnswerPos} names the pillar (see the render note above): a plain smooth_stone block
     * that a right-click does nothing to. The answer is given by pressing any of the four stone buttons on its
     * sides, which is exactly what the capture holds - four wall buttons around each of the three pillars, at
     * the pillar's own height. Auto Quiz was clicking the pillar.
     *
     * @return the first button found on the correct pillar's four sides, or the pillar itself when the room
     *         has none there (so a room that does not match this shape still gets the old behavior rather
     *         than nothing at all)
     */
    public static BlockPos getCorrectAnswerButton(net.minecraft.world.level.Level level) {
        BlockPos pillar = getCorrectAnswerPos();
        if (pillar == null || level == null) {
            return pillar;
        }
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = pillar.relative(d);
            if (level.getBlockState(side).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock) {
                return side;
            }
        }
        return pillar;
    }

    /** {@link #getCorrectAnswerButton(net.minecraft.world.level.Level)}, taking of the pillar's buttons the one nearest
     *  {@code eye} (a pillar has four; the one on the side he stands is the one in reach). */
    public static BlockPos getCorrectAnswerButton(net.minecraft.world.level.Level level, net.minecraft.world.phys.Vec3 eye) {
        BlockPos pillar = getCorrectAnswerPos();
        if (pillar == null || level == null || eye == null) {
            return getCorrectAnswerButton(level);
        }
        BlockPos b = buttonOf(level, pillar, eye);
        return b != null ? b : pillar;
    }

    /** The button on one of {@code pillar}'s four sides nearest {@code toward}, or null when it has none. */
    public static BlockPos buttonOf(net.minecraft.world.level.Level level, BlockPos pillar, net.minecraft.world.phys.Vec3 toward) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            BlockPos side = pillar.relative(d);
            if (level.getBlockState(side).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock) {
                double dist = net.minecraft.world.phys.Vec3.atCenterOf(side).distanceToSqr(toward);
                if (dist < bestD) {
                    bestD = dist;
                    best = side;
                }
            }
        }
        return best;
    }

    /** The three answer pillars (ⓐ, ⓑ, ⓒ) while he is in the Quiz room and they are worked out, else null. */
    public static List<BlockPos> answerPillars() {
        if (options[0].blockPos == null || options[1].blockPos == null || options[2].blockPos == null) {
            return null;
        }
        return List.of(options[0].blockPos, options[1].blockPos, options[2].blockPos);
    }

    /**
     * Forgets the question currently being shown, keeping the three answer positions.
     *
     * <p>Exactly what the real {@code "answered Question #N correctly!"} line does here, exposed so the dungeon
     * sim can ask a second question in the same room without faking that line. Hypixel always announces the
     * previous question as answered before asking the next one, so the solver has never had to cope with two
     * questions arriving back to back - and when the sim's Quiz hands out a new question after a wrong answer,
     * the previous question's {@code correct} flag was still set and TWO options lit up, with
     * {@link #getCorrectAnswerPos} returning whichever came first in the array rather than the right one.
     */
    public static void clearForNewQuestion() {
        for (TriviaOption option : options) {
            option.correct = false;
        }
        triviaAnswers = null;
    }

    private static void reset() {
        for (TriviaOption option : options) {
            option.blockPos = null;
            option.correct = false;
        }
        triviaAnswers = null;
    }
}
