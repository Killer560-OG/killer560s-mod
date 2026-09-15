package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            onTick();
            logQuizStateIfChanged();
        });
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(QuizSolverFeature::onWorldRender);
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

    private static BlockPos realPos(int x, int y, int z, int[] clayAndRotation) {
        RoomEntry.Pos relative = new RoomEntry.Pos();
        relative.x = x;
        relative.y = y;
        relative.z = z;
        return RoomDatabase.toRealCoord(relative, clayAndRotation[0], clayAndRotation[1], clayAndRotation[2]);
    }

    private static void onMessage(Component message) {
        if (!QuizSolverConfig.getInstance().isEnabled() || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        String msg = plain != null ? plain : message.getString();
        logQuizChat(msg);

        if (msg.startsWith("[STATUE] Oruo the Omniscient: ") && msg.endsWith("correctly!")) {
            if (msg.contains("answered the final question")) {
                reset();
                return;
            }
            if (msg.contains("answered Question #")) {
                for (TriviaOption option : options) {
                    option.correct = false;
                }
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
        for (Map.Entry<String, List<String>> entry : ANSWERS.entrySet()) {
            if (msg.contains(entry.getKey())) {
                triviaAnswers = entry.getValue();
                return;
            }
        }
    }

    // [QuizSolver] diagnostics - logging only (chat-rate, only in a dungeon with the solver enabled).
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-puzzles");

    private static String lastLoggedState = null;

    private static void logQuizStateIfChanged() {
        RoomEntry current = QuizSolverConfig.getInstance().isEnabled() && DungeonState.isInDungeon()
                ? LiveMapFeature.currentRoomEntry() : null;
        String state = current == null || !"Quiz".equals(current.name)
                ? "notInRoom(enabled=" + QuizSolverConfig.getInstance().isEnabled() + " inBoss=" + LiveMapFeature.isInBoss() + ")"
                : "inRoom optionPositions=" + options[0].blockPos + "," + options[1].blockPos + "," + options[2].blockPos
                + " currentAnswers=" + triviaAnswers + " correct=[" + options[0].correct + "," + options[1].correct
                + "," + options[2].correct + "]";
        if (!state.equals(lastLoggedState)) {
            LOGGER.info("[QuizSolver] State: {}", state);
            lastLoggedState = state;
        }
    }

    private static void logQuizChat(String msg) {
        String trimmed = msg.trim();
        boolean relevant = msg.contains("Oruo") || startsWithAnswerLetter(trimmed) || trimmed.endsWith("?");
        if (!relevant) {
            return;
        }
        RoomEntry current = LiveMapFeature.currentRoomEntry();
        LOGGER.info("[QuizSolver] Chat: \"{}\" | room={} answersLoaded={} currentAnswers={} optionPositionsSet={} correct=[{},{},{}]",
                trimmed, current != null ? current.name : null, ANSWERS.size(), triviaAnswers,
                options[0].blockPos != null, options[0].correct, options[1].correct, options[2].correct);
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
            BlockPos below = option.blockPos.below();
            WorldRenderUtils.renderFilledBox(context, new AABB(below), 0.2f, 1.0f, 0.3f, 0.5f);
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

    private static void reset() {
        for (TriviaOption option : options) {
            option.blockPos = null;
            option.correct = false;
        }
        triviaAnswers = null;
    }
}
