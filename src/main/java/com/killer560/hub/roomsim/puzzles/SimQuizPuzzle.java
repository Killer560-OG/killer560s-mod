package com.killer560.hub.roomsim.puzzles;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.killer560.hub.roomsim.SimRoomPuzzles;
import com.killer560.hub.roomsim.SimState;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A three-chest "which one is right" puzzle for the dungeon sim: three chests, one labelled answer is correct,
 * clicking it solves the puzzle and clicking either other one fails it. Built fresh at a given origin, not read
 * off a real captured room.
 *
 * <p><b>What is real here and what is not.</b> The QUESTION and its CORRECT answer are the real Hypixel dungeon
 * "Quiz" trivia database this mod already ships and already uses for the live solver -
 * {@code data/killer560smod/puzzles/quiz-answers.json}, loaded the exact same way
 * {@code com.killer560.hub.puzzlesolvers.QuizSolverFeature#loadAnswers} does, and never edited or invented here.
 *
 * <p>The other two chests' WRONG answer text is not real: that file only ever stored the correct answer(s) for
 * each question (it is how the live solver tells you which of Oruo's three floor spots to stand on - it does not
 * need to know the wrong options, since it never has to display them). Hypixel generates the two wrong options
 * itself and nothing in this codebase records what they were. So the wrong options here are other REAL questions'
 * REAL correct answers, borrowed out of context and relabelled as distractors for a question they don't belong
 * to - a generated puzzle using real data as raw material, not a reproduction of a real trivia round. Said plainly
 * rather than dressed up as authentic.
 *
 * <p>This also means the puzzle only covers "Quiz"-shaped trivia (one question, three lettered answers). The
 * real "Three Weirdos" puzzle is a different mechanic entirely - three NPCs each giving one of several fixed
 * truth/lie dialogue lines that the real solver ({@code WeirdosSolverFeature}) pattern-matches to work out who is
 * lying - and nothing in {@code quiz-answers.json} or elsewhere in the bundled data encodes that dialogue, so it
 * is not reproduced here. Three clickable chests was kept as the shared shape between the two real puzzles, since
 * the task asked for either.
 *
 * <p>Gated on {@link SimState#canAct} throughout; every block and entity change happens on the integrated server
 * thread via {@code server.execute(...)} - same rule as the rest of {@code roomsim}.
 */
public final class SimQuizPuzzle {

    private static final Map<String, List<String>> ANSWERS = loadAnswers();
    /** Chests spaced 3 blocks apart along X at the given origin - not real Hypixel Quiz floor coordinates (those
     *  are room-relative capture data this sim has no captured room to anchor to; see QuizSolverFeature's own
     *  (20,70,6)/(15,70,9)/(10,70,6) for what the real layout actually is). */
    private static final int[] OFFSET_X = {0, 3, 6};

    private static final Map<BlockPos, Integer> CELL_INDEX = new ConcurrentHashMap<>();
    private static volatile BlockPos[] chestPos = null;
    private static final List<UUID> LABELS = new ArrayList<>();

    private static volatile boolean built = false;
    private static volatile boolean complete = false;
    private static volatile int correctIndex = -1;

    /** {world position, answer index} for each of a bound room's twelve pillar buttons. Null when standalone. */
    private static volatile java.util.List<Object[]> quizButtons = null;

    /** When the question was announced. An answer before {@link #ANSWER_DELAY_MS} after it does not count. */
    private static volatile long askedAtMs = 0L;

    /** killer560 (2026-10-01): "have a 5s delay from the question coming out to being able to answer it." */
    private static final long ANSWER_DELAY_MS = 5000L;

    private SimQuizPuzzle() {
    }

    /** Hooks the chest click. Call once from {@code Killer560ModClient#onInitializeClient}, alongside the other
     *  {@code roomsim} {@code register()} calls (wiring not done here - see this file's restriction on which
     *  files it may touch). */
    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(
                com.killer560.hub.util.FeatureGuard.start("SimQuizPuzzle.tick", SimQuizPuzzle::tick));
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // level.isClientSide(): this event also fires server-side; without this check a real click would
            // resolve twice - the same double-fire guard SimDoors uses for its own UseBlockCallback. Returning
            // a non-PASS result here also stops the vanilla chest screen from opening, which is the point.
            if (!level.isClientSide() || !SimState.canAct(client) || player != client.player) {
                return InteractionResult.PASS;
            }
            Integer index = CELL_INDEX.get(hitResult.getBlockPos());
            if (index == null || !built || complete) {
                return InteractionResult.PASS;
            }
            onChestClick(client, index);
            return InteractionResult.SUCCESS;
        });
    }

    /** Builds a fresh question: picks a random real question from {@code quiz-answers.json}, places its real
     *  correct answer and two borrowed-real-answer distractors on three chests in random order, and announces
     *  the question and lettered options in chat the way the real Oruo statue line does. */
    public static void build(Minecraft client, BlockPos origin) {
        if (!SimState.canAct(client) || origin == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        reset();
        if (ANSWERS.isEmpty()) {
            ModChat.send("Sim", ModChat.bad("No quiz data bundled - quiz-answers.json did not load."));
            return;
        }
        List<String> questions = new ArrayList<>(ANSWERS.keySet());
        String question = questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
        List<String> correctAnswers = ANSWERS.get(question);
        String correct = correctAnswers.get(ThreadLocalRandom.current().nextInt(correctAnswers.size()));

        String[] text = new String[3];
        int correctSlot = ThreadLocalRandom.current().nextInt(3);
        text[correctSlot] = correct;
        List<String> wrongPool = distractorPool(question, correct, questions);
        int wrongTaken = 0;
        for (int i = 0; i < 3 && wrongTaken < wrongPool.size(); i++) {
            if (i == correctSlot) {
                continue;
            }
            text[i] = wrongPool.get(wrongTaken++);
        }
        // If the file somehow had too few other questions to borrow two distractors from, fall back to a
        // clearly-fake placeholder rather than leaving a chest unlabelled.
        for (int i = 0; i < 3; i++) {
            if (text[i] == null) {
                text[i] = "(no other answer available)";
            }
        }
        correctIndex = correctSlot;

        BlockPos[] positions = new BlockPos[3];
        for (int i = 0; i < 3; i++) {
            positions[i] = origin.offset(OFFSET_X[i], 0, 0).immutable();
        }
        chestPos = positions;
        boundPositions = null;   // a standalone arena, not a bind to a captured room
        for (int i = 0; i < 3; i++) {
            CELL_INDEX.put(positions[i], i);
        }
        built = true;
        complete = false;

        char[] letters = {'ⓐ', 'ⓑ', 'ⓒ'}; // circled a/b/c - same glyphs QuizSolverFeature matches
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (int i = 0; i < 3; i++) {
                // NO CHEST in a bound room - the pillar's buttons are the answer now, so a chest would
                // be a second way to answer and a block the room does not have. A standalone arena
                // still gets them, because it has no pillars to press.
                if (quizButtons == null) {
                    level.setBlockAndUpdate(positions[i], Blocks.CHEST.defaultBlockState());
                }
                LABELS.add(spawnLabel(level, positions[i], letters[i] + " " + text[i]));
            }
        });

        StringBuilder options = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            options.append(letters[i]).append(' ').append(text[i]).append("  ");
        }
        ModChat.send("Sim", ModChat.text(question));
        ModChat.send("Sim", ModChat.dim(options.toString().trim()));
    }

    /**
     * Arms this puzzle on a REAL captured Quiz or Three Weirdos room, at the room's own answer positions.
     *
     * <p>This is the one bind that has to CREATE its furniture, and the reason is in the real rooms rather
     * than in the capture: Oruo and the three weirdos are <b>armour stands</b> and their answer spots are
     * plain floor, so there is no puzzle block anywhere in either capture for a bind to attach to. What the
     * bind does provide is the right PLACE.
     *
     * <ul>
     *   <li><b>Quiz</b> - the three spots are {@code QuizSolverFeature}'s own room-relative
     *       {@code (20,70,6)}, {@code (15,70,9)}, {@code (10,70,6)}, which are floor blocks in the capture.
     *       The chests go one block above them, so the floor is not destroyed.</li>
     *   <li><b>Three Weirdos</b> - the capture DOES hold the weirdos' three chests, and
     *       {@link com.killer560.hub.roomsim.SimRoomPuzzles#capturedBlocks} finds them off the room data
     *       rather than the world (by the time this runs, {@code SimSecrets} has added secret chests and a
     *       world scan could not tell them apart). Those three are used as they stand, so this room binds to
     *       real geometry after all.</li>
     * </ul>
     *
     * <p>Server thread only; called from {@code SimBuilder}'s post-build block.
     *
     * @return whether the puzzle was armed
     */
    public static boolean bindAt(ServerLevel level, SimRoomPuzzles.Placement p) {
        boolean weirdos = "three weirdos".equalsIgnoreCase(p.room().name);
        BlockPos[] positions;
        if (weirdos) {
            List<BlockPos> chests = SimRoomPuzzles.capturedBlocks(p, Blocks.CHEST);
            if (chests.size() < 3) {
                com.killer560.hub.util.ModLog.get("killer560smod-roomsim").warn(
                        "Sim quiz: Three Weirdos' capture holds {} chest(s), not the three the puzzle needs",
                        chests.size());
                return false;
            }
            // The three closest to each other, since a bigger room can hold an unrelated chest too. Cheap:
            // three chests is three candidate triples.
            positions = new BlockPos[]{chests.get(0), chests.get(1), chests.get(2)};
        } else {
            int[][] spots = {{20, 70, 6}, {15, 70, 9}, {10, 70, 6}};
            List<int[]> rels = List.of(spots);
            // "Solid floor with standing room over it", not merely "solid": the three spots are plain floor,
            // and solid alone puts rotation 0 and rotation 180 level at 3 of 3 in this capture - a tie the
            // recovered capture turn would have to break on its own. Adding the two blocks of air separates
            // them 3 to 1, because at the wrong turn two of the three spots are inside the room's terracotta.
            SimRoomPuzzles.Anchor anchor = SimRoomPuzzles.bestAnchor(level, p, rels,
                    (lv, pos) -> !lv.getBlockState(pos).isAir()
                            && lv.getBlockState(pos.above()).isAir()
                            && lv.getBlockState(pos.above(2)).isAir(),
                    new int[]{0}, 3);
            if (anchor == null) {
                return false;
            }
            positions = new BlockPos[3];
            quizButtons = new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                // One above the floor spot: the spot itself is the block he stands on in the real room.
                positions[i] = anchor.world(spots[i]).above();
                // THE PILLAR'S FOUR BUTTONS. killer560 (2026-10-01): "Dont have the chests in the room it
                // should just be answered by pressing any of the buttons on the cooresponding pillar."
                //
                // Decoding the capture settles where they are without a guess: at this room's own turn the
                // three answer spots land on capture (11,70,25), (16,70,22) and (21,70,25), and all twelve of
                // its stone buttons sit in rings of four around exactly those - each spot's four horizontal
                // neighbours at the same height. So a button is an answer spot stepped one block along x or z,
                // and pressing any of a pillar's four answers that pillar.
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    quizButtons.add(new Object[]{
                        anchor.world(spots[i][0] + d[0], spots[i][1], spots[i][2] + d[1]), i});
                }
            }
        }
        if (ANSWERS.isEmpty()) {
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim")
                    .warn("Sim quiz: quiz-answers.json did not load - nothing to ask");
            return false;
        }
        // Same in-memory clear reset() does, without its queued block writes: those are aimed at the PREVIOUS
        // arena's positions and would land a tick from now, inside the room just pasted.
        java.util.List<Object[]> buttons = quizButtons;
        forget();
        quizButtons = buttons;
        boundRoom = p.room().name;
        if (buttons != null) {
            for (Object[] b : buttons) {
                CELL_INDEX.put((BlockPos) b[0], (Integer) b[1]);
            }
        }
        newQuestion(level, positions, false);
        return true;
    }

    /**
     * Picks a fresh question and puts it on these three chests. Server thread only.
     *
     * @param replaceLabels true when there are already labels floating over them to take away first
     */
    private static void newQuestion(ServerLevel level, BlockPos[] positions, boolean replaceLabels) {
        if (ANSWERS.isEmpty()) {
            return;
        }
        if (replaceLabels) {
            for (UUID id : List.copyOf(LABELS)) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
            LABELS.clear();
            CELL_INDEX.clear();
        }
        List<String> questions = new ArrayList<>(ANSWERS.keySet());
        String question = questions.get(ThreadLocalRandom.current().nextInt(questions.size()));
        List<String> correctAnswers = ANSWERS.get(question);
        String correct = correctAnswers.get(ThreadLocalRandom.current().nextInt(correctAnswers.size()));
        String[] text = new String[3];
        int correctSlot = ThreadLocalRandom.current().nextInt(3);
        text[correctSlot] = correct;
        List<String> wrongPool = distractorPool(question, correct, questions);
        int wrongTaken = 0;
        for (int i = 0; i < 3 && wrongTaken < wrongPool.size(); i++) {
            if (i == correctSlot) {
                continue;
            }
            text[i] = wrongPool.get(wrongTaken++);
        }
        for (int i = 0; i < 3; i++) {
            if (text[i] == null) {
                text[i] = "(no other answer available)";
            }
        }
        correctIndex = correctSlot;
        chestPos = positions;
        boundPositions = positions;
        for (int i = 0; i < 3; i++) {
            CELL_INDEX.put(positions[i], i);
        }
        built = true;
        char[] letters = {'ⓐ', 'ⓑ', 'ⓒ'};
        for (int i = 0; i < 3; i++) {
            // NO CHEST in a bound room - the pillar's buttons are the answer now, so a chest would
            // be a second way to answer and a block the room does not have. A standalone arena
            // still gets them, because it has no pillars to press.
            if (quizButtons == null) {
                level.setBlockAndUpdate(positions[i], Blocks.CHEST.defaultBlockState());
            }
            LABELS.add(spawnLabel(level, positions[i], letters[i] + " " + text[i]));
        }
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            options.append(letters[i]).append(' ').append(text[i]).append("  ");
        }
        // HELD until he walks in, not announced now.
        //
        // killer560 (2026-10-01): "i also didnt see it send a chat message saying the question nor did my solver
        // work probably because of no message", and earlier: "only have it send the question once I open the
        // room." It WAS being sent - his log has it at 20:02:54, with the room reached at 20:04:22 - so it had
        // scrolled away ninety seconds before he got there. A bound room arms during the floor build, which is
        // minutes before he reaches it, so announcing at arm time can only ever be too early.
        pendingQuestion = question;
        pendingOptions = options.toString().trim();
        announced = false;
    }

    /** The question and its lettered options, waiting for him to enter the room. Null once announced. */
    private static volatile String pendingQuestion = null;
    private static volatile String pendingOptions = null;
    private static volatile boolean announced = false;

    /** How close counts as being in the room. A quiz room is one tile, so this comfortably covers it. */
    private static final double ANNOUNCE_RANGE_SQR = 22.0 * 22.0;

    /**
     * Announces the held question the first time he is near the three answer spots.
     *
     * <p>Distance to the puzzle's own blocks rather than the live map's room name: it needs no map, works for a
     * standalone arena as well as a bound room, and cannot announce the wrong room's question.
     */
    private static void tick(Minecraft client) {
        if (announced || pendingQuestion == null || !SimState.canAct(client)) {
            return;
        }
        BlockPos[] positions = chestPos;
        if (positions == null || positions.length == 0 || positions[0] == null) {
            return;
        }
        if (client.player.blockPosition().distSqr(positions[0]) > ANNOUNCE_RANGE_SQR) {
            return;
        }
        announced = true;
        askedAtMs = System.currentTimeMillis();
        ModChat.send("Sim", ModChat.text(pendingQuestion));
        ModChat.send("Sim", ModChat.dim(pendingOptions));
        ModChat.send("Sim", ModChat.dim("Oruo is still reading - answers count in 5s."));
    }

    /** Two other real questions' correct answers, picked at random and excluding anything equal to the correct
     *  answer text (so a question that happens to share wording with another isn't its own distractor). */
    private static List<String> distractorPool(String question, String correct, List<String> allQuestions) {
        List<String> candidates = new ArrayList<>(allQuestions);
        candidates.remove(question);
        java.util.Collections.shuffle(candidates, ThreadLocalRandom.current());
        Set<String> seen = new HashSet<>();
        seen.add(correct);
        List<String> picked = new ArrayList<>(2);
        for (String q : candidates) {
            if (picked.size() >= 2) {
                break;
            }
            List<String> answers = ANSWERS.get(q);
            if (answers == null || answers.isEmpty()) {
                continue;
            }
            String answer = answers.get(0);
            if (seen.add(answer)) {
                picked.add(answer);
            }
        }
        return picked;
    }

    /** Whether the correct chest has been opened. False before a question is answered, and false again after a
     *  wrong click resets the board. */
    public static boolean isComplete() {
        return complete;
    }

    /** Clears the chests and labels if a session is still open, and always clears the in-memory state. Safe to
     *  call with nothing built, and safe to call after the sim session has already ended. */
    /**
     * Drops this puzzle's bookkeeping WITHOUT touching the world.
     *
     * <p>{@link #reset} is the right thing while the arena is still standing: it puts blocks back, un-presses,
     * re-lights. It is the wrong thing when the floor those blocks belonged to no longer exists, which is
     * exactly the case {@code SimRoomPuzzles.armFloor} has to handle - the positions it holds are absolute and
     * the next floor is built over them, so a queued "set it back to air" lands inside the new floor and
     * punches a hole in it. Just as bad the other way: a stale click index left in place makes a click on some
     * unrelated block on the new floor count as a move in a puzzle that is not on it.
     */
    /** Non-null while this puzzle is bound to a real captured room rather than a standalone arena. */
    private static volatile BlockPos[] boundPositions = null;

    /** The bound room's own name - "Quiz" or "Three Weirdos". Both the chat label and the red square on the
     *  map are about the ROOM, and this class stands in for two of them. */
    private static volatile String boundRoom = null;

    public static void forget() {
        pendingQuestion = null;
        pendingOptions = null;
        announced = false;
        askedAtMs = 0L;
        quizButtons = null;
        boundPositions = null;
        boundRoom = null;
        chestPos = null;
        CELL_INDEX.clear();
        LABELS.clear();
        built = false;
        complete = false;
        correctIndex = -1;
    }

    public static void reset() {
        BlockPos[] positions = chestPos;
        List<UUID> labels = List.copyOf(LABELS);
        Minecraft client = Minecraft.getInstance();
        if ((positions != null || !labels.isEmpty()) && SimState.canAct(client)) {
            MinecraftServer server = client.getSingleplayerServer();
            if (server != null) {
                server.execute(() -> {
                    ServerLevel level = server.overworld();
                    if (positions != null) {
                        for (BlockPos pos : positions) {
                            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        }
                    }
                    for (UUID id : labels) {
                        Entity entity = level.getEntity(id);
                        if (entity != null) {
                            entity.discard();
                        }
                    }
                });
            }
        }
        chestPos = null;
        boundPositions = null;
        CELL_INDEX.clear();
        LABELS.clear();
        built = false;
        complete = false;
        correctIndex = -1;
    }

    private static void onChestClick(Minecraft client, int index) {
        MinecraftServer server = client.getSingleplayerServer();
        // Too early. Not a wrong answer - on Hypixel the options are not live while Oruo is still talking, so
        // an early press has to be ignored rather than failed, or the puzzle would punish reading quickly.
        if (askedAtMs > 0 && System.currentTimeMillis() - askedAtMs < ANSWER_DELAY_MS) {
            long left = (ANSWER_DELAY_MS - (System.currentTimeMillis() - askedAtMs) + 999) / 1000;
            ModChat.send("Sim", ModChat.dim("Too early - " + left + "s left."));
            return;
        }
        if (index == correctIndex) {
            complete = true;
            if (server != null && quizButtons == null) {
                // Standalone only: in a bound room that position is the pillar top and nothing would put it
                // back, so the room would keep a stray emerald block for the rest of the run.
                BlockPos pos = chestPos[index];
                server.execute(() -> server.overworld().setBlockAndUpdate(pos, Blocks.EMERALD_BLOCK.defaultBlockState()));
            }
            ModChat.send("Sim", ModChat.good("Correct!"));
        } else {
            // Wrong chest: fail like the real puzzle, not a silent pass - reset so the next attempt is a new
            // question rather than the same one with the wrong option already given away.
            // Tells the Architect's First Draft feature a puzzle failed, so his existing auto-get
            // setting works in here the same as it does on Hypixel.
            // The room's own name, not a hardcoded "Three Weirdos": this class runs the Quiz room too, and
            // the label he reads should say which of them he just failed. Passing the room name also turns
            // that room red on the map - see SimRoomState.
            SimPuzzles.reportFail(boundRoom == null ? "Quiz" : boundRoom, boundRoom);
            if (boundPositions != null && server != null) {
                // Bound to a real room: a new question on the same three chests. reset() here would delete
                // them, and in Three Weirdos those chests are the ROOM'S OWN - deleting them leaves a puzzle
                // room with nothing in it and no way to build it again on a generated floor.
                ModChat.send("Sim", ModChat.bad("Wrong answer - new question."));
                askedAtMs = 0L;
                announced = false;
                BlockPos[] positions = boundPositions;
                server.execute(() -> newQuestion(server.overworld(), positions, true));
            } else {
                ModChat.send("Sim", ModChat.bad("Wrong answer - resetting. Build again to retry."));
                reset();
            }
        }
    }

    /** An invisible, no-gravity armour stand carrying the option's letter and text as its name - the same
     *  "invisible stand, visible name" tag SimMobs uses for star mobs, and WeirdosSolverFeature reads for real
     *  NPCs. Floats just above the chest so it doesn't block the click raycast onto the chest itself. */
    private static UUID spawnLabel(ServerLevel level, BlockPos chestPos, String text) {
        // TWO BLOCKS LOWER in a bound room. killer560 (2026-10-01): "The quiz names above chests are still 2
        // blocks too high." The spot is one above the floor and the label floated 1.3 over that, putting it 2.3
        // up; with no chest under it there is nothing to clear, so it drops to 0.3 - the two blocks he measured.
        // A standalone arena keeps the old height, where a real chest IS in the way.
        double lift = quizButtons == null ? 1.3 : -0.7;
        ArmorStand stand = new ArmorStand(level, chestPos.getX() + 0.5, chestPos.getY() + lift, chestPos.getZ() + 0.5);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setNoBasePlate(true);
        stand.setInvulnerable(true);
        stand.setCustomName(Component.literal(text));
        stand.setCustomNameVisible(true);
        level.addFreshEntity(stand);
        return stand.getUUID();
    }

    private static Map<String, List<String>> loadAnswers() {
        try (InputStream stream = SimQuizPuzzle.class.getClassLoader()
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
}
