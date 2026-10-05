package com.killer560.hub.puzzlesolvers;

import com.killer560.hub.util.FeatureGuard;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
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
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("QuizSolverFeature", client -> {
            onTick();
        }));
        // After translucent TERRAIN, not features: water is drawn after the features pass, so a highlight
        // drawn there ended up painted over by any water behind/around it (killer560, 2026-09-21).
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(QuizSolverFeature::onWorldRender);
    }

    private static Map<String, List<String>> loadAnswers() {
        try (InputStream stream = QuizSolverFeature.class.getClassLoader()
                .getResourceAsStream("data/killer560smod/puzzles/quiz-answers.json")) {
            if (stream == null) {
                return Map.of();
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, List<String>>>() {
                }.getType();
                Map<String, List<String>> parsed = new Gson().fromJson(reader, type);
                return parsed != null ? parsed : Map.of();
            }
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void onTick() {
        // Boss check: NoammAddons e42d3316 "reset when entering boss" (2026-09-14 port).
        if (!QuizSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            if (lastRoomEntry != null || triviaAnswers != null || options[0].blockPos != null) {
                lastRoomEntry = null;
                reset();
            }
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        if (current != lastRoomEntry) {
            lastRoomEntry = current;
            reset();
        }
        if (current == null || !"Quiz".equals(current.name) || options[0].blockPos != null) {
            return;
        }
        int[] clayAndRotation = LiveMapFeature.currentRoomClayAndRotation();
        if (clayAndRotation == null) {
            return;
        }
        options[0].blockPos = realPos(20, 70, 6, clayAndRotation);
        options[1].blockPos = realPos(15, 70, 9, clayAndRotation);
        options[2].blockPos = realPos(10, 70, 6, clayAndRotation);
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
        String plain = ChatFormatting.stripFormatting(message.getString());
        String msg = plain != null ? plain : message.getString();

        if (msg.startsWith("[STATUE] Oruo the Omniscient: ") && msg.endsWith("correctly!")) {
            if (msg.contains("answered the final question")) {
                reset();
                return;
            }
            if (msg.contains("answered Question #")) {
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
            }
        }

        if (trimmed.equals("What SkyBlock year is it?")) {
            long skyblockYear = ((System.currentTimeMillis() / 1000L) - 1560276000L) / 446400L + 1;
            triviaAnswers = List.of("Year " + skyblockYear);
            return;
        }
        // Oruo's question is a server line of its own - centring spaces and the question, nothing in front -
        // which is why the year question above is compared on the trimmed line. A party/guild/all-chat/DM
        // line always starts with its channel, rank or "name: " prefix, so requiring the question at the very
        // start keeps anyone quoting a question from overwriting the answers. startsWith rather than equals:
        // some keys are only the first line of a question Hypixel wraps ("...who sells stained").
        for (Map.Entry<String, List<String>> entry : ANSWERS.entrySet()) {
            if (trimmed.startsWith(entry.getKey())) {
                triviaAnswers = entry.getValue();
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
            SolverEspRender.renderWaypoint(context, new AABB(option.blockPos), 0.2f, 1.0f, 0.3f, 2f);
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
     *         has none there (so a room that does not match this shape still gets the old behaviour rather
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
