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

    /**
     * Three Weirdos' three chests, capture-local.
     *
     * <p>The capture's own are at z 12, 14 and 17. All three move two blocks so the middle one lines up with
     * the chamber's cauldron at z=16 (killer560: "the middle most should be in line with the cauldron"), and
     * then the far one comes in one more (2026-10-01: "move the rightmost chest in 1, the one furthest away
     * from the skull in that room") - which leaves 14, 16, 18, evenly spaced two apart.
     */
    private static final int[][] WEIRDO_SPOTS = {{25, 69, 14}, {26, 69, 16}, {25, 69, 18}};

    /**
     * The NPC names the sim's three weirdos answer to.
     *
     * <p>Invented, not Hypixel's. {@code WeirdosSolverFeature} matches the speaker with {@code ^\[NPC] (.+):}
     * and then looks for an ArmorStand of that name, so WHICH name it is does not matter to the solver - only
     * that the chat line and the stand agree, which is the one thing this list guarantees.
     */
    private static final String[] WEIRDO_NAMES = {"Aldous", "Berta", "Cadmus"};

    /**
     * One line from {@code WeirdosSolverFeature}'s own SOLUTIONS list, and two from its WRONG list.
     *
     * <p>The solver's rule is simply "whoever speaks a SOLUTION line is standing at the right chest", so the
     * sim has the weirdo at the correct chest say the first and the other two say the others. Taken verbatim
     * from the solver's patterns rather than written fresh, because a line that does not match one of them
     * exactly tells it nothing.
     */
    private static final String WEIRDO_SOLUTION = "My chest has the reward and I'm telling the truth!";
    private static final String[] WEIRDO_WRONG = {
        "One of us is telling the truth!",
        "My chest doesn't have the reward. At least one of the others is telling the truth!",
    };

    /** Set while the bound room is Three Weirdos rather than the Quiz. */
    private static volatile boolean weirdosRoom = false;

    /**
     * The weirdos themselves: stand UUID -> which of the three it is. Both the visible NPC stand and the
     * "CLICK" stand under it are in here, so clicking either one talks to that weirdo.
     *
     * <p>killer560 (2026-10-02): "put armor stands where the NPCs would normally be and the solver/auto puzzle
     * isn't working for three weirdos either." Both halves had one cause. The stands used to be invisible name
     * tags sunk 0.7 into the floor, so there was nothing standing there to see; and they were spawned by
     * {@code server.execute} in the same call that sent all three {@code [NPC]} lines on the client - so when
     * {@code WeirdosSolverFeature} read each line and went looking for an ArmorStand of that name, the stand had
     * not reached the client yet. Its own log line for that case is "No ArmorStand named ...", and it gives up,
     * so nothing was ever highlighted and Auto Three Weirdos, which acts only on the solver, never had a chest.
     *
     * <p>Now the three are real visible stands placed when the room is armed, each with a "CLICK" stand under
     * its name the way Hypixel's NPCs carry one, and a weirdo speaks when he (or Auto Three Weirdos, which looks
     * for exactly that "CLICK" name within reach) talks to it - which is also how the real room works. A stand
     * is on the client long before anyone can click it, so the solver always finds it.
     */
    private static final Map<UUID, Integer> NPC_STANDS = new ConcurrentHashMap<>();


    /** When the question was announced. Kept for the chat line and for tests; nothing gates on it. */
    private static volatile long askedAtMs = 0L;


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
            // A BUTTON GETS TO BE A BUTTON. SUCCESS cancels the interaction, which is exactly right for a chest
            // (it stops the empty chest screen opening) and wrong for the Quiz room's pillar buttons: it threw
            // away vanilla's own press, so the button never depressed, never clicked, and the only sign anything
            // had happened was a chat line. Same reasoning SimWaterPuzzle's levers and SimBoulderPuzzle's
            // buttons already use - observe the click, never consume it.
            return level.getBlockState(hitResult.getBlockPos())
                    .getBlock() instanceof net.minecraft.world.level.block.ButtonBlock
                    ? InteractionResult.PASS : InteractionResult.SUCCESS;
        });
        // TALKING TO A WEIRDO - answered by the SERVER, from the interact packet, like Hypixel's NPCs.
        //
        // Until 2026-10-04 the weirdo spoke from the CLIENT copy of this callback. Fabric fires that copy only
        // from Minecraft.startUseItem - a real right click - so Auto Three Weirdos' gameMode.interact (which
        // sends the same ServerboundInteractPacket a click sends) never made anyone speak. Fabric's server copy
        // fires inside ServerGamePacketListenerImpl.handleInteract for the ServerPlayer (javap, fabric-events-
        // interaction 5.2.8), so a real click and a programmatic interact both reach it, exactly as both reach
        // Hypixel. The client copy returns SUCCESS for a real click, which makes Fabric send the packet and skip
        // the client-side interaction; the server copy speaks and returns SUCCESS so the armour stand's own
        // interaction (hanging his held item on it) never runs.
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register(
                (player, level, hand, entity, hitResult) -> {
                    Integer who = entity == null ? null : NPC_STANDS.get(entity.getUUID());
                    if (who == null) {
                        return InteractionResult.PASS;
                    }
                    if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) {
                        speak(sp, who, "talked to");
                    }
                    return InteractionResult.SUCCESS;
                });
        // A left click talks too. The client copy must PASS so the attack packet reaches the server; the server
        // copy speaks and returns FAIL, which cancels the hit on the stand.
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register(
                (player, level, hand, entity, hitResult) -> {
                    Integer who = entity == null ? null : NPC_STANDS.get(entity.getUUID());
                    if (who == null) {
                        return InteractionResult.PASS;
                    }
                    if (level.isClientSide()) {
                        return InteractionResult.PASS;
                    }
                    if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                        speak(sp, who, "hit");
                    }
                    return InteractionResult.FAIL;
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
        // reset() keeps the bind's own fields on purpose (it is also the mid-session "put it back" path), so a
        // standalone arena built after a bound room has to say it is one. Without this it would believe it had
        // pillar buttons and place no chests at all.
        quizButtons = null;
        weirdosRoom = false;
        boundRoom = null;
        if (ANSWERS.isEmpty()) {
            ModChat.send("Sim", ModChat.bad("No quiz data bundled - quiz-answers.json did not load."));
            return;
        }
        BlockPos[] positions = new BlockPos[3];
        for (int i = 0; i < 3; i++) {
            positions[i] = origin.offset(OFFSET_X[i], 0, 0).immutable();
        }
        complete = false;
        // ONE question path, shared with a bound room. This method used to pick its own question and announce
        // it with ModChat, options squashed onto a single "[Sim] ⓐ … ⓑ … ⓒ …" row - which is exactly the shape
        // QuizSolverFeature cannot read, so the solver never worked in a /simpuzzle arena even when it worked
        // in a generated floor. newQuestion holds the question for tick() to announce in Oruo's own format.
        server.execute(() -> newQuestion(server.overworld(), positions, false, false));
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
            // THE CHESTS MOVE. killer560 (2026-10-01): "For three weirdos the chests need to be shifted, the
            // middle most should be in line with the cauldron in the room."
            //
            // The capture holds its three chests at local (25,69,12), (26,69,14) and (25,69,17), and the
            // chamber's cauldron at (29,69,16) - so the middle chest sits two short of the cauldron's line.
            // All three move the same two blocks rather than being re-spaced, because their arrangement is the
            // room's own and only the alignment was wrong: the middle one lands on z=16 with the cauldron, and
            // the other two keep their offsets from it.
            List<BlockPos> captured = SimRoomPuzzles.capturedBlocks(p, Blocks.CHEST);
            positions = new BlockPos[WEIRDO_SPOTS.length];
            for (int i = 0; i < WEIRDO_SPOTS.length; i++) {
                positions[i] = SimRoomPuzzles.capturedPos(p,
                        WEIRDO_SPOTS[i][0], WEIRDO_SPOTS[i][1], WEIRDO_SPOTS[i][2]);
            }
            // The room's own three go, or the puzzle would have six chests and three of them dead.
            for (BlockPos old : captured) {
                boolean kept = false;
                for (BlockPos now : positions) {
                    kept |= now.equals(old);
                }
                if (!kept) {
                    level.setBlockAndUpdate(old, Blocks.AIR.defaultBlockState());
                }
            }
            for (BlockPos now : positions) {
                level.setBlockAndUpdate(now, Blocks.CHEST.defaultBlockState());
            }
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
        weirdosRoom = weirdos;
        if (buttons != null) {
            for (Object[] b : buttons) {
                CELL_INDEX.put((BlockPos) b[0], (Integer) b[1]);
            }
        }
        newQuestion(level, positions, false, true);
        if (weirdos) {
            spawnNpcs(level, positions, p.anchor());
        }
        return true;
    }

    /**
     * The three weirdos, standing where the solver looks for them: one database block of -x from each chest,
     * which is the exact step {@code WeirdosSolverFeature.findChestPos} takes back ({@code relative.x += 1}).
     * Through the room's recorded anchor, which is the same clay corner and rotation {@code SimRoomIndex}
     * publishes to the live map - see "one room must not have two answers" in CLAUDE.md. {@link #spawnWeirdos}
     * re-checks it against the live map once he walks in and moves them if the two ever disagree.
     */
    private static void spawnNpcs(ServerLevel level, BlockPos[] chests, SimRoomPuzzles.Anchor a) {
        NPC_STANDS.clear();
        NPC_IDS.clear();
        for (int i = 0; i < 3 && i < chests.length; i++) {
            com.killer560.hub.roomdatabase.RoomEntry.Pos rel =
                    com.killer560.hub.roomdatabase.RoomDatabase.toRelativeCoord(
                            chests[i], a.clayX(), a.clayZ(), a.rotation());
            rel.x -= 1;
            BlockPos spot = com.killer560.hub.roomdatabase.RoomDatabase.toRealCoord(
                    rel, a.clayX(), a.clayZ(), a.rotation());
            spot = new BlockPos(spot.getX(), chests[i].getY(), spot.getZ());
            placeNpc(level, i, spot, chests[i]);
        }
        com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                "Sim three weirdos: {} stand(s) placed for {} NPC(s), correct chest is #{}",
                NPC_STANDS.size(), NPC_IDS.size(), correctIndex);
    }

    /** One weirdo: a visible named stand facing away from its chest, and a "CLICK" stand under its name. */
    private static void placeNpc(ServerLevel level, int index, BlockPos spot, BlockPos chest) {
        double x = spot.getX() + 0.5;
        double y = spot.getY();
        double z = spot.getZ() + 0.5;
        double dx = x - (chest.getX() + 0.5);
        double dz = z - (chest.getZ() + 0.5);
        float yaw = (dx == 0 && dz == 0) ? 0f : (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
        ArmorStand npc = new ArmorStand(level, x, y, z);
        npc.snapTo(x, y, z, yaw, 0f);
        npc.setNoGravity(true);
        npc.setInvulnerable(true);
        npc.setShowArms(true);
        npc.setCustomName(Component.literal(WEIRDO_NAMES[index]));
        npc.setCustomNameVisible(true);
        if (level.addFreshEntity(npc)) {
            NPC_STANDS.put(npc.getUUID(), index);
            NPC_IDS.add(new UUID[]{npc.getUUID(), null});
        }
        // Hypixel's NPCs carry a second line reading CLICK under the name, on its own stand - and that word is
        // what Auto Three Weirdos looks for. A third of a block lower so the two names do not sit on each other.
        ArmorStand click = new ArmorStand(level, x, y - 0.3, z);
        click.snapTo(x, y - 0.3, z, yaw, 0f);
        click.setInvisible(true);
        click.setNoGravity(true);
        click.setInvulnerable(true);
        click.setNoBasePlate(true);
        click.setCustomName(Component.literal("CLICK"));
        click.setCustomNameVisible(true);
        if (level.addFreshEntity(click)) {
            NPC_STANDS.put(click.getUUID(), index);
            if (!NPC_IDS.isEmpty() && NPC_IDS.get(NPC_IDS.size() - 1)[1] == null) {
                NPC_IDS.get(NPC_IDS.size() - 1)[1] = click.getUUID();
            } else {
                NPC_IDS.add(new UUID[]{null, click.getUUID()});
            }
        }
    }

    /** {npc stand, click stand} per weirdo, in index order, for moving and removing them. */
    private static final List<UUID[]> NPC_IDS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * A weirdo's line, in the server's own {@code [NPC] Name: line} shape, when he talks to it. The one at the
     * correct chest says a SOLUTION line and the other two WRONG lines - the rule the solver implements.
     */
    private static void speak(net.minecraft.server.level.ServerPlayer player, int index, String how) {
        if (!weirdosRoom || correctIndex < 0 || complete) {
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info(
                    "Sim three weirdos: {} {} - not speaking (weirdos room {}, round {}, complete {})", how,
                    WEIRDO_NAMES[Math.max(0, Math.min(index, WEIRDO_NAMES.length - 1))], weirdosRoom,
                    correctIndex, complete);
            return;
        }
        String line;
        if (index == correctIndex) {
            line = WEIRDO_SOLUTION;
        } else {
            int rank = 0;
            for (int i = 0; i < index; i++) {
                if (i != correctIndex) {
                    rank++;
                }
            }
            line = WEIRDO_WRONG[Math.min(rank, WEIRDO_WRONG.length - 1)];
        }
        // A server system message, which reaches his chat the way Hypixel's NPC lines do.
        player.sendSystemMessage(Component.literal("[NPC] " + WEIRDO_NAMES[index] + ": " + line));
        com.killer560.hub.util.ModLog.get("killer560smod-roomsim").info("Sim three weirdos: {} {} - said its line",
                how, WEIRDO_NAMES[index]);
    }

    /**
     * Picks a fresh question and puts it on these three chests. Server thread only.
     *
     * @param replaceLabels true when there are already labels floating over them to take away first
     * @param bound true for a captured room, false for a standalone {@code /simpuzzle} arena - decides whether
     *              a wrong answer asks a new question on the same furniture or tears the arena down
     */
    private static void newQuestion(ServerLevel level, BlockPos[] positions, boolean replaceLabels,
                                    boolean bound) {
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
        String question = pickQuestion(questions);
        List<String> correctAnswers = ANSWERS.get(question);
        String correct = correctAnswers.get(ThreadLocalRandom.current().nextInt(correctAnswers.size()));
        String[] text = new String[3];
        int correctSlot = ThreadLocalRandom.current().nextInt(3);
        text[correctSlot] = correct;
        List<String> wrongPool = distractorPool(question, correctAnswers, questions);
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
        boundPositions = bound ? positions : null;
        for (int i = 0; i < 3; i++) {
            CELL_INDEX.put(positions[i], i);
        }
        // THE PILLAR BUTTONS GO BACK IN. The replaceLabels branch above clears CELL_INDEX wholesale, and in a
        // bound Quiz room the twelve buttons are the ONLY way to answer - so a second question (which is what a
        // wrong answer produces) left a room whose buttons did nothing at all.
        java.util.List<Object[]> buttons = quizButtons;
        if (buttons != null) {
            for (Object[] b : buttons) {
                CELL_INDEX.put((BlockPos) b[0], (Integer) b[1]);
            }
        }
        built = true;
        char[] letters = {'ⓐ', 'ⓑ', 'ⓒ'};
        for (int i = 0; i < 3; i++) {
            // NO CHEST in a bound QUIZ room - the pillar's buttons are the answer now, so a chest would
            // be a second way to answer and a block the room does not have. A standalone arena
            // still gets them, because it has no pillars to press. Three Weirdos keeps its chests: they
            // ARE the puzzle there, and bindAt has already put them where they belong.
            if (quizButtons == null && !weirdosRoom) {
                level.setBlockAndUpdate(positions[i], Blocks.CHEST.defaultBlockState());
            }
            if (weirdosRoom) {
                // The weirdos are NOT spawned here - see sayWeirdos. Their position has to be the one the
                // solver's own transform maps back onto the chest, and that transform needs the live map's
                // clay/rotation, which does not exist yet while the floor is still being built.
                continue;
            }
            LABELS.add(spawnLabel(level, positions[i], letters[i] + " " + text[i]));
        }
        // ONE STRING PER LINE, not one string split on double spaces later. Oruo sends the three options as
        // three separate chat lines and the solver matches each one whole; rebuilding them by splitting a joined
        // string would come apart on any answer that happens to contain two spaces in a row.
        String[] lines = new String[3];
        for (int i = 0; i < 3; i++) {
            lines[i] = letters[i] + " " + text[i];
        }
        // HELD until he walks in, not announced now.
        //
        // killer560 (2026-10-01): "i also didnt see it send a chat message saying the question nor did my solver
        // work probably because of no message", and earlier: "only have it send the question once I open the
        // room." It WAS being sent - his log has it at 20:02:54, with the room reached at 20:04:22 - so it had
        // scrolled away ninety seconds before he got there. A bound room arms during the floor build, which is
        // minutes before he reaches it, so announcing at arm time can only ever be too early.
        pendingQuestion = question;
        pendingOptions = lines;
        announced = false;
        if (weirdosRoom) {
            // A new round asks the same three again with a new answer, so the last round's highlights go.
            Minecraft.getInstance().execute(
                    com.killer560.hub.puzzlesolvers.WeirdosSolverFeature::clearForNewRound);
        }
    }

    /** The question and its lettered options, waiting for him to enter the room. Null once announced. */
    private static volatile String pendingQuestion = null;
    private static volatile String[] pendingOptions = null;
    private static volatile boolean announced = false;

    /** How close counts as being in the room. A quiz room is one tile, so this comfortably covers it. */
    private static final double ANNOUNCE_RANGE_SQR = 22.0 * 22.0;

    /**
     * How many consecutive ticks the live map must already have agreed on the room before the question goes
     * out. See {@link #tick} - two would do, three is margin.
     */
    private static final int ROOM_SETTLE_TICKS = 3;

    /** Consecutive ticks the live map has reported the bound room with a resolved rotation. */
    private static int settledTicks = 0;

    /** Ticks spent in announce range with the live map unable to name the room. See {@link #tick}. */
    private static int unmappedTicks = 0;

    /** How long to wait for the live map before announcing anyway. Three seconds. */
    private static final int MAP_GRACE_TICKS = 60;

    /**
     * Announces the held question once he is in the room AND the solvers have settled on it.
     *
     * <p><b>Why this is not just a distance check.</b> killer560 (2026-10-01): "my quiz solver and auto quiz
     * are still broken." They were, and this method was the reason. {@code QuizSolverFeature.onTick} and
     * {@code WeirdosSolverFeature} both do the same thing on a room change:
     *
     * <pre>  if (current != lastRoomEntry) { lastRoomEntry = current; reset(); }</pre>
     *
     * and that {@code reset()} clears {@code triviaAnswers} and every {@code options[].correct}. The announce
     * range is 22 blocks, which reaches well outside a one-tile room, so the question and its three ⓐ/ⓑ/ⓒ
     * lines were going out while he was still in the CORRIDOR. The solver read them, armed correctly - and
     * then he stepped through the door, the room changed, and it wiped everything it had just learned. Nothing
     * ever sends those lines again, so the solver sat empty for the rest of the room and Auto Quiz, which only
     * ever acts on what the solver knows, had nothing to act on.
     *
     * <p>So the gate is now the live map's own answer, held steady: the room it names must be the bound room,
     * with a resolved rotation, for {@link #ROOM_SETTLE_TICKS} ticks running. Two of those ticks is already
     * enough to guarantee the solvers have seen the room change and done their reset BEFORE the first line
     * arrives, because they run on END_CLIENT_TICK and this runs on START. The rotation has to be resolved for
     * a second reason as well - {@link #spawnWeirdos} inverts the solver's transform with it, and without it
     * the three stands were silently never spawned.
     *
     * <p>Distance is still required, and it is still the only gate for a standalone {@code /simpuzzle} arena,
     * which has no room on any map. A bound room whose name the live map cannot resolve at all falls back to
     * distance after {@link #MAP_GRACE_TICKS}, with a line in the log saying so - a question he can still play
     * beats a room that stays silent, and the log says which it was.
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
            settledTicks = 0;
            unmappedTicks = 0;
            return;
        }
        String room = boundRoom;
        if (room != null) {
            com.killer560.hub.roomdatabase.RoomEntry current =
                    com.killer560.hub.livemap.LiveMapFeature.currentRoomEntry();
            boolean onIt = current != null && room.equalsIgnoreCase(current.name)
                    && com.killer560.hub.livemap.LiveMapFeature.currentRoomClayAndRotation() != null;
            if (onIt) {
                unmappedTicks = 0;
                if (++settledTicks < ROOM_SETTLE_TICKS) {
                    return;
                }
            } else if (++unmappedTicks < MAP_GRACE_TICKS) {
                settledTicks = 0;
                return;
            } else {
                com.killer560.hub.util.ModLog.get("killer560smod-roomsim").warn(
                        "Sim quiz: in range of {} for {} ticks and the live map still cannot name the room with"
                                + " a rotation (it says {}) - asking the question anyway, but its solver will"
                                + " have nothing to measure against",
                        room, unmappedTicks, current == null ? "nothing" : current.name);
            }
        }
        announced = true;
        settledTicks = 0;
        unmappedTicks = 0;
        askedAtMs = System.currentTimeMillis();
        if (weirdosRoom) {
            // No clearForNewRound here any more: newQuestion does it when a round starts, and the weirdos only
            // speak when talked to, so clearing at walk-in could only throw away a line he had already heard.
            sayWeirdos(client);
            return;
        }
        // Same for Oruo: Hypixel always announces the previous question as answered before asking the next, and
        // the sim does not, so the previous question's correct flag has to come off by hand.
        com.killer560.hub.puzzlesolvers.QuizSolverFeature.clearForNewQuestion();
        // THE SERVER'S OWN WORDING, so QuizSolverFeature can read it. It listens for a line CONTAINING a
        // question it knows and then for lines starting with the circled letters whose text ends with the
        // answer - both of which are Hypixel's exact chat shape, and neither of which a "[Sim] ..." line with
        // the options squashed onto one row satisfies. The options go out one per line now, raw, which is how
        // Oruo sends them; the "[Sim]" header above them is still there to say where they came from.
        ModChat.send("Sim", ModChat.text(pendingQuestion));
        raw(client, "[STATUE] Oruo the Omniscient: " + pendingQuestion);
        String[] lines = pendingOptions;
        // The QUESTION FIRST, then the options, and never the other way round: the solver only marks an option
        // correct when it already knows the question's answers, so an option line that arrives first is read
        // against the previous question's answers or against nothing at all.
        for (String option : lines == null ? new String[0] : lines) {
            raw(client, option);
        }
    }

    /**
     * The three weirdos speak, in the server's own format, and the solver listens.
     *
     * <p>Whoever stands at the correct chest says a line out of {@code WeirdosSolverFeature}'s SOLUTIONS list
     * and the other two say lines out of its WRONG list - which is exactly the rule that solver implements
     * ("the speaker of a solution line is standing at the right chest"). Sent raw rather than through
     * {@link ModChat}, because its "[feature] " prefix would break the solver's anchored {@code ^\[NPC] }.
     */
    private static void sayWeirdos(Minecraft client) {
        spawnWeirdos(client);
        // They no longer all speak at once from here - each one speaks when talked to (see speak). Sending the
        // lines from here is what raced the stands to the client and left the solver with nobody to point at.
        ModChat.send("Sim", ModChat.dim("Three Weirdos - talk to each of them, then open the chest of whoever"
                + " is telling the truth."));
    }

    /** A line with no mod prefix at all, so an anchored solver pattern matches it. It still goes through
     *  ChatObserver, which is what every solver in this mod subscribes to. */
    private static void raw(Minecraft client, String line) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(line));
        }
    }

    /**
     * Puts the three weirdos where the SOLVER will look for them.
     *
     * <p>killer560 (2026-10-01): "the solver is one diagonally back and to the right from the actual chest."
     * The stands were placed at bind time from the puzzle's own anchor, one database block of -x off each
     * chest - which is the right RULE and the wrong transform: the anchor's clay corner and the one the live
     * map publishes for the same room do not have to agree to the block, and a constant disagreement in both
     * x and z is exactly a diagonal miss.
     *
     * <p>So the position is worked out by inverting {@code WeirdosSolverFeature}'s own calculation, with the
     * same {@code currentRoomClayAndRotation} it will use: take the chest to relative space, step one back in
     * x, and come out again. Whatever those numbers are, the solver's {@code relative.x += 1} then lands on
     * the chest by construction rather than by agreement. That needs the live map, which is why this happens
     * when he walks in rather than while the floor is being built.
     *
     * <p>The y is 69 plus the sim's floor shift, which is the literal the solver uses for an NPC - so the
     * round trip is exact in all three axes.
     */
    private static void spawnWeirdos(Minecraft client) {
        BlockPos[] positions = boundPositions;
        MinecraftServer server = client.getSingleplayerServer();
        int[] cr = com.killer560.hub.livemap.LiveMapFeature.currentRoomClayAndRotation();
        if (positions == null || server == null || cr == null) {
            return;
        }
        int y = 69 + com.killer560.hub.livemap.DungeonLayout.simYOffset();
        BlockPos[] spots = new BlockPos[3];
        for (int i = 0; i < 3 && i < positions.length; i++) {
            com.killer560.hub.roomdatabase.RoomEntry.Pos rel =
                    com.killer560.hub.roomdatabase.RoomDatabase.toRelativeCoord(
                            new BlockPos(positions[i].getX(), y, positions[i].getZ()), cr[0], cr[1], cr[2]);
            rel.x -= 1;
            spots[i] = com.killer560.hub.roomdatabase.RoomDatabase.toRealCoord(rel, cr[0], cr[1], cr[2]);
        }
        List<UUID[]> ids = List.copyOf(NPC_IDS);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            int moved = 0;
            for (int i = 0; i < 3 && i < ids.size(); i++) {
                if (spots[i] == null) {
                    continue;
                }
                double x = spots[i].getX() + 0.5;
                double z = spots[i].getZ() + 0.5;
                double feet = positions[i].getY();
                UUID[] pair = ids.get(i);
                Entity npc = pair[0] == null ? null : level.getEntity(pair[0]);
                if (npc != null && (Math.abs(npc.getX() - x) > 1.0e-3 || Math.abs(npc.getZ() - z) > 1.0e-3)) {
                    npc.snapTo(x, feet, z, npc.getYRot(), 0f);
                    moved++;
                }
                Entity click = pair[1] == null ? null : level.getEntity(pair[1]);
                if (click != null) {
                    click.snapTo(x, feet - 0.3, z, click.getYRot(), 0f);
                }
            }
            if (moved > 0) {
                com.killer560.hub.util.ModLog.get("killer560smod-roomsim").warn(
                        "Sim three weirdos: {} NPC(s) moved to where the solver's live-map transform puts them -"
                                + " the bind anchor and the published room disagree", moved);
            }
        });
    }

    /**
     * A question {@code QuizSolverFeature} will resolve to the right answer list.
     *
     * <p>That solver finds a question by taking the FIRST key in {@code quiz-answers.json} that the chat line
     * contains:
     *
     * <pre>  for (entry : ANSWERS) if (msg.contains(entry.getKey())) { triviaAnswers = entry.getValue(); break; }</pre>
     *
     * <p>So if one question's text is a substring of another's, asking the longer one hands the solver the
     * SHORTER one's answers, and nothing on any of the three chests matches them - the solver goes quiet with
     * no way to tell that from a solver that is simply broken. The real statue can of course ask whichever it
     * likes and that is the live solver's problem; the sim picks the questions, so it picks ones that resolve.
     *
     * <p>Falls back to a plain random pick if somehow none qualifies, so this can never fail to return.
     */
    private static String pickQuestion(List<String> allQuestions) {
        List<String> candidates = new ArrayList<>(allQuestions);
        java.util.Collections.shuffle(candidates, ThreadLocalRandom.current());
        for (String q : candidates) {
            if (solverResolves(q)) {
                return q;
            }
        }
        return allQuestions.get(ThreadLocalRandom.current().nextInt(allQuestions.size()));
    }

    /** Whether {@code QuizSolverFeature}'s first-contained-key scan over {@code ANSWERS} lands on this
     *  question itself. Written as that scan rather than as a substring test, so the two cannot drift. */
    private static boolean solverResolves(String question) {
        for (String key : ANSWERS.keySet()) {
            if (question.contains(key)) {
                return key.equals(question);
            }
        }
        return false;
    }

    /**
     * Two other real questions' correct answers to use as distractors.
     *
     * <p>Excludes any candidate the solver would mistake for the right answer. Its option test is
     * {@code triviaAnswers.stream().anyMatch(trimmed::endsWith)} over the WHOLE answer list, not just the one
     * answer this question happens to be showing - so a distractor that merely ENDS WITH any of the question's
     * correct answers ("Bonzo" vs "Super Bonzo") would light up as correct alongside the real one, and
     * {@code getCorrectAnswerPos} would hand Auto Quiz whichever came first in the array. Equality was the only
     * thing checked before, which catches the obvious case and not that one.
     */
    private static List<String> distractorPool(String question, List<String> correctAnswers,
                                               List<String> allQuestions) {
        List<String> candidates = new ArrayList<>(allQuestions);
        candidates.remove(question);
        java.util.Collections.shuffle(candidates, ThreadLocalRandom.current());
        Set<String> seen = new HashSet<>();
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
            boolean shadows = false;
            for (String right : correctAnswers) {
                shadows |= answer.endsWith(right) || right.endsWith(answer);
            }
            if (!shadows && seen.add(answer)) {
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

    /** Solved, and the room this class is bound to is Three Weirdos - for tests that must not mistake a Quiz. */
    public static boolean isWeirdosComplete() {
        return complete && weirdosRoom;
    }

    /** Solved, and the room this class is bound to is the Quiz (or a standalone arena). */
    public static boolean isQuizComplete() {
        return complete && !weirdosRoom;
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
        settledTicks = 0;
        unmappedTicks = 0;
        askedAtMs = 0L;
        quizButtons = null;
        boundPositions = null;
        boundRoom = null;
        weirdosRoom = false;
        chestPos = null;
        CELL_INDEX.clear();
        LABELS.clear();
        NPC_STANDS.clear();
        NPC_IDS.clear();
        built = false;
        complete = false;
        correctIndex = -1;
    }

    public static void reset() {
        BlockPos[] positions = chestPos;
        List<UUID> labels = new ArrayList<>(LABELS);
        for (UUID[] pair : NPC_IDS) {
            for (UUID id : pair) {
                if (id != null) {
                    labels.add(id);
                }
            }
        }
        NPC_STANDS.clear();
        NPC_IDS.clear();
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
        // NO ANSWER DELAY. killer560 asked for one ("have a 5s delay from the question coming out to being
        // able to answer it") and then asked for it back out ("remove the quiz delay I dont like it"), so an
        // answer counts the moment the question is up. The reading-time rule is Hypixel's; this is a drill.
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
                server.execute(() -> newQuestion(server.overworld(), positions, true, true));
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
        // ONE height now, for every label this puzzle makes. killer560 measured the Quiz's as two blocks too
        // high and then the Three Weirdos' as two blocks too high in the same way, which is the tell that it
        // was never about the chest: an ArmorStand renders its name about 2.3 blocks above its own position,
        // so a stand lifted 1.3 puts the text 3.6 over the spot. -0.7 puts it just above head height, where
        // both of them should have been. The stand itself ends up inside the floor, which is fine - it is
        // invisible and has no collision.
        double lift = -0.7;
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
