package com.killer560.hub.autopuzzles;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.QuizSolverConfig;
import com.killer560.hub.puzzlesolvers.QuizSolverFeature;
import com.killer560.hub.puzzlesolvers.WeirdosSolverFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import com.killer560.hub.compat.McCompat;

/**
 * Cheat-build-only "Auto Puzzles": Auto Quiz (Trivia) and Auto Three Weirdos. Pure consumers of the existing
 * visual solvers ({@link QuizSolverFeature}, {@link WeirdosSolverFeature}) - no detection of their own, so the
 * matching solver must be ON too.
 * <p>
 * Automation ported from QUOI ({@code puzzlesolvers/impl/Quiz.kt} + {@code ThreeWeirdos.kt} "Auto"; NoammAddons
 * 26.1.2 has no auto for either):
 * <ul>
 *   <li>Quiz: once the correct option is known AND the answer holograms are up (an ArmorStand named with "ⓒ"
 *   within 20 blocks), no-rotate interact the correct option's block (the solver's option pos, the same pos
 *   QUOI clicks) when eye-to-centre distance² &lt;= 36. QUOI re-clicks every 500ms; this clicks ONCE per
 *   question, re-armed only by Oruo's "answered Question #" line / the solver clearing the answer.</li>
 *   <li>Three Weirdos ({@link AutoWeirdos}): once the solver has the correct chest and both wrong chests (QUOI's
 *   {@code wrongPositions.size == 2} gate - all 3 NPCs have spoken), open the chest. Once per room. Optional (default
 *   OFF) QUOI "talk to NPCs": talk to each "CLICK" stand within entity reach, one a tick, moving into reach of the
 *   ones out of it.</li>
 * </ul>
 * The other puzzle autos (QUOI ports) live in their own classes and are ticked from here: {@link AutoBlaze},
 * {@link AutoBeams}, {@link AutoIcePath}, {@link AutoBoulder}, {@link AutoWater}, {@link AutoTicTacToe},
 * {@link AutoTeleportMaze}, {@link AutoIceFill} (shared: {@link AutoPuzzleUtil}, {@link AutoReposition}, {@link AutoGuard}).
 * <p>
 * Safety: only in a dungeon, never in boss, only while the live map says the player is standing in that
 * exact puzzle room, never with a screen open or while sneaking (a sneak-click would place the held item).
 */
public final class AutoPuzzlesFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    static final String CHAT = "AutoPuzzles";

    private static final String QUIZ_ROOM = "Quiz";
    private static final String WEIRDOS_ROOM = "Three Weirdos";

    // QUOI Quiz: player.eyePosition.distanceToSqr(answerPos.vec3) > 36 -> skip.
    /** Measured block reach, squared - was 36.0 (6.0 blocks) to the centre. */
    private static final double QUIZ_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    // QUOI Quiz: getEntities<ArmorStand>(20.0) { "ⓒ" in name }.
    private static final double QUIZ_HOLOGRAM_RADIUS_SQ = 20.0 * 20.0;

    // ---- Quiz state ----
    private static boolean quizActed = false;
    private static BlockPos quizPendingPos = null;
    private static long quizPendingSinceMs = 0L;
    private static boolean quizWaitLogged = false;
    private static boolean quizSolverOffWarned = false;

    private static ClientLevel lastLevel = null;
    // Review fix (2026-09-15): the solvers' state is static and only resets on their own conditions
    // (leave dungeon / boss / room change). Across a quick requeue into a new instance (new level) their
    // old answer/chest could still be set when a same-layout room is entered, so nothing is clicked in a
    // level until the solver has been observed EMPTY at least once in that level - i.e. the answer/chest
    // it later reports was really produced in this instance.
    private static boolean quizSolverClearedThisLevel = false;

    private AutoPuzzlesFeature() {
    }

    /** Auto Ice Fill's own "done" (the finish tile is packed ice), for Auto Secret waiting on it in the room. */
    public static boolean isIceFillDone() {
        return AutoIceFill.isDone();
    }

    public static void register() {
        // ChatObserver, same as QuizSolverFeature (whose reset this re-arm mirrors): Odin/NoammAddons/Skyblocker can
        // cancel a server line via ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric
        // listeners never see - on Fabric CHAT/GAME the solver would reset but this would never re-arm. The trigger
        // is anchored to Oruo's "[STATUE] ..." server format, so this mod's own client messages can't match.
        ChatObserver.subscribe(AutoPuzzlesFeature::onMessage);
        ChatObserver.subscribe(AutoPuzzleActivity::onChat);
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoPuzzlesFeature.onTick", AutoPuzzlesFeature::onTick));
        // The free camera's hand sway (see ViewFreeze.followHandSway), at the end of the tick so it lands after
        // aiStep's own update. AP3 does its own while it is the one holding the view.
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AutoPuzzlesFeature.handSway", client -> {
            if (!com.killer560.hub.ap3.Ap3FreezeState.isFrozen()
                    && Float.isNaN(com.killer560.hub.ap3.Ap3Executor.frozenViewYaw())) {
                com.killer560.hub.util.ViewFreeze.followHandSway(client.player);
            }
        }));
        // Auto Boulder draws each tick's turn across the frames in between (no choppy 20 Hz camera).
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ctx -> {
            try {
                AutoBoulder.frame();
            } catch (RuntimeException e) {
                LOGGER.error("[AutoPuzzles] Boulder frame step threw", e);
            }
        });
        LOGGER.info("[AutoPuzzles] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    private static void onMessage(Component message) {
        if (!AutoPuzzlesConfig.getInstance().isAutoQuizEnabled()) {
            return;
        }
        String msg = ChatObserver.strip(message).trim();
        // Same line QuizSolverFeature uses to clear the previous question's answer - re-arm for the next one
        // even if the next answer arrives in the same tick (so the null gap is never observed).
        if (msg.startsWith("[STATUE] Oruo the Omniscient: ") && msg.endsWith("correctly!")
                && (msg.contains("answered Question #") || msg.contains("answered the final question"))) {
            resetQuiz();
            // The answer counted: Auto Secret and Autopilot carry on now, not 40 ticks later - between questions it goes
            // off to other work (2026-10-17), and after the last one the room is solved.
            AutoPuzzleActivity.ended();
            releaseQuizCamera();
        } else if (msg.equals(com.killer560.hub.puzzlesolvers.QuizState.INTRO)) {
            // Oruo began: talking to him was the whole step, and the first question is 11 s off.
            AutoPuzzleActivity.ended();
            oruoTalkedTick = -1;
        }
    }

    private static void onTick(Minecraft client) {
        AutoPuzzleUtil.turnTick(client); // first, always: a body turned for a click is given back the tick after
        AutoPuzzleActivity.tick(client);
        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetQuiz();
            QUIZ_CAMERA.drop();
            AutoWeirdos.levelChanged(client);
            quizSolverOffWarned = false;
            quizSolverClearedThisLevel = false;
            AutoBlaze.levelChanged(client);
            AutoBeams.levelChanged(client);
            AutoIcePath.levelChanged(client);
            AutoBoulder.levelChanged();
            AutoWater.levelChanged(client);
            AutoTicTacToe.levelChanged();
            AutoTeleportMaze.levelChanged(client);
            AutoIceFill.levelChanged(client);
        }
        // Latches: once set they stay set until the next world, so the solver is only asked while they are not.
        if (!quizSolverClearedThisLevel && QuizSolverFeature.getCorrectAnswerPos() == null) {
            quizSolverClearedThisLevel = true;
        }
        AutoWeirdos.observeSolver();
        if (client.player == null || client.level == null || client.gameMode == null) {
            return;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        boolean inBoss = LiveMapFeature.isInBoss();
        RoomEntry room = inDungeon && !inBoss ? LiveMapFeature.currentRoomEntry() : null;
        String roomName = room != null ? room.name : null;
        tickQuiz(client, QUIZ_ROOM.equals(roomName));
        AutoWeirdos.tick(client, WEIRDOS_ROOM.equals(roomName));
        // QUOI puzzle autos (each gates on its own toggle + room name and never acts on pre-world solver data).
        AutoBlaze.tick(client, roomName);
        AutoBeams.tick(client, roomName);
        AutoIcePath.tick(client, roomName);
        AutoBoulder.tick(client, roomName);
        AutoWater.tick(client, roomName);
        AutoTicTacToe.tick(client, roomName);
        AutoTeleportMaze.tick(client, roomName);
        AutoIceFill.tick(client, roomName);
    }

    // ------------------------------------------------------------------
    // Auto Quiz
    // ------------------------------------------------------------------

    private static void tickQuiz(Minecraft client, boolean inQuizRoom) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoQuizEnabled() || !inQuizRoom) {
            quizPendingPos = null;
            quizWaitLogged = false;
            releaseQuizCamera();
            if (!inQuizRoom) {
                quizSolverOffWarned = false;
            }
            return;
        }
        if (!QuizSolverConfig.getInstance().isEnabled()) {
            if (!quizSolverOffWarned) {
                quizSolverOffWarned = true;
                ModChat.send(CHAT, ModChat.text("Auto Quiz needs "), ModChat.value("Quiz Solver"),
                        ModChat.text(" turned on."));
            }
            return;
        }

        QUIZ_CAMERA.keep(client.player);
        noteAnswerSpot(client);
        BlockPos answer = QuizSolverFeature.getCorrectAnswerPos();
        if (answer == null) {
            // Solver cleared the answer (question answered / room reset) - next known answer is a new question.
            if (quizActed || quizPendingPos != null) {
                resetQuiz();
            }
            if (!com.killer560.hub.puzzlesolvers.QuizState.started()) {
                talkToOruo(client);   // the Quiz only begins when someone talks to Oruo
            }
            return;
        }
        if (quizActed) {
            return;
        }
        if (!quizSolverClearedThisLevel) {
            if (!quizWaitLogged) {
                quizWaitLogged = true;
                LOGGER.info("[AutoPuzzles] Quiz: answer {} predates this world - not clicking until the solver resets", answer);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!answer.equals(quizPendingPos)) {
            quizPendingPos = answer;
            quizPendingSinceMs = now;
            quizWaitLogged = false;
        }
        if (now - quizPendingSinceMs < cfg.getQuizDelayMs() || McCompat.screen(client) != null) {
            return;
        }
        // THE BUTTON - see below. Asked before the gate so a click the reported look misses turns the body first. The
        // one nearest his eye: a pillar has four, and the answer spot reaches the one on each pillar's inner side.
        BlockPos quizClick = QuizSolverFeature.getCorrectAnswerButton(client.level, client.player.getEyePosition());
        String blocker = null;
        if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
            blocker = "an Interactive Map trip is moving him";
        } else if (client.player.isShiftKeyDown()) {
            blocker = "sneaking";
        } else if (!answerHologramsUp(client)) {
            blocker = "answer holograms (ⓒ stand) not up yet";
        } else if (com.killer560.hub.util.BlockHits.boxDistanceSq(client.player.getEyePosition(), quizClick) > QUIZ_REACH_SQ) {
            // To the BOX of the block the click goes to (the button) - see the note in AutoWater. Pairing a
            // box-measured limit with a centre-measured distance refuses blocks the server would accept.
            blocker = String.format(java.util.Locale.US, "out of reach (%.2f blocks)",
                    Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(client.player.getEyePosition(), quizClick)));
            moveIntoQuizReach(client, quizClick);
        } else if (com.killer560.hub.puzzlesolvers.QuizState.started()
                && !com.killer560.hub.puzzlesolvers.QuizState.awaitingAnswer()) {
            blocker = "this question is answered - waiting for Oruo's next";
        } else if (com.killer560.hub.puzzlesolvers.QuizState.awaitingAnswer()
                && !com.killer560.hub.puzzlesolvers.QuizState.answerable()) {
            // Oruo's cooldown (QuizState): an answer before it ends does not count. The click goes on the first tick the
            // server can take it; on the tick before, the body turns onto the button so that click needs no turn tick.
            long left = com.killer560.hub.puzzlesolvers.QuizState.ticksUntilAnswerable();
            if (left <= 1) {
                AutoPuzzleUtil.aimBodyAt(client.player, quizClick, QUIZ_CAMERA);
            }
            blocker = "Oruo's cooldown (question " + com.killer560.hub.puzzlesolvers.QuizState.question()
                    + " answerable at server tick " + com.killer560.hub.puzzlesolvers.QuizState.answerAtTick() + ")";
        }
        if (blocker != null) {
            if (!blocker.equals(quizBlockerLogged)) {
                quizBlockerLogged = blocker;
                LOGGER.info("[AutoPuzzles] Quiz: answer {} known, waiting - {}", quizClick.toShortString(), blocker);
            }
            return;
        }
        quizBlockerLogged = null;
        if (!AutoPuzzleUtil.gateWorldClick(client, quizClick)) {
            return; // gate held this tick back (or the body was turned to it) - not marked acted, clicked later
        }
        quizActed = true; // once per question, even if the click itself can't be built
        // THE BUTTON, not the pillar. killer560 (2026-10-01): "auto quiz isnt workign on sim."
        //
        // The solver's three answer coordinates are the little smooth_stone pillars Oruo's buttons hang off -
        // decoding Quiz.json puts four of the room's twelve wall buttons around each one, which is all twelve.
        // Right-clicking the pillar itself does nothing in either the sim or a real dungeon, so this asks the
        // solver which button to press and falls back to the pillar only if the room has none.
        BlockPos click = quizClick;
        if (!interactBlockNoRotate(client, click)) {
            LOGGER.warn("[AutoPuzzles] Quiz: no clickable shape at {} (state={}) - not clicking this question",
                    click, client.level.getBlockState(click));
            return;
        }
        LOGGER.info("[AutoPuzzles] Quiz: answered question {} at server tick {} (answerable from {})",
                com.killer560.hub.puzzlesolvers.QuizState.question(),
                com.killer560.hub.witherdragons.ServerTickClock.now(),
                com.killer560.hub.puzzlesolvers.QuizState.answerAtTick());
        ModChat.log(CHAT, ModChat.text("Quiz: clicked the "), ModChat.good("correct answer"), ModChat.text("."));
        releaseQuizCamera();
    }

    /** Auto Quiz's free camera: held only for the tick it turns the body onto a button ahead of the cooldown's end. */
    private static final FreeCam QUIZ_CAMERA = new FreeCam("Quiz");

    private static void releaseQuizCamera() {
        Minecraft client = Minecraft.getInstance();
        if (QUIZ_CAMERA.isEngaged()) {
            QUIZ_CAMERA.release(client.player);
        }
    }

    // ---- talking to Oruo (killer560, 2026-10-17: the Quiz begins when he is talked to) ----

    /** Client tick of the last talk to Oruo that has not been answered by his first line yet; -1 when none waits. */
    private static long oruoTalkedTick = -1;
    private static long oruoTalkLag;
    /** The Oruo stand the last move into reach was for: one trip per stand until a talk goes. */
    private static java.util.UUID oruoMovedFor = null;
    private static String oruoWaitLogged = null;
    /** Ticks to wait for Oruo's first line after a talk before talking again (his line comes in the same tick). */
    private static final int ORUO_ANSWER_TICKS = 20;

    /**
     * Talks to Oruo when the Quiz has not begun: the "CLICK" stand by his name, as every Hypixel NPC carries one (else
     * the named stand itself), from within entity reach; out of reach, one Interactive Map trip onto a block in reach.
     */
    private static void talkToOruo(Minecraft client) {
        net.minecraft.client.player.LocalPlayer player = client.player;
        long tick = com.killer560.hub.livemap.autoclear.ClearExecutor.clientTick();
        if (oruoTalkedTick >= 0 && tick - oruoTalkedTick <= ORUO_ANSWER_TICKS
                + com.killer560.hub.util.ServerLag.ticksSince(oruoTalkLag)) {
            return;   // a talk is on its way; his line answers it
        }
        Entity oruo = oruo(client);
        if (oruo == null) {
            oruoWait("no Oruo stand in the room yet");
            return;
        }
        Vec3 eye = player.getEyePosition();
        double d = Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye, oruo.getBoundingBox()));
        if (d > AutoWeirdos.NPC_REACH) {
            if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
                oruoWait("an Interactive Map trip is moving him");
                return;
            }
            if (oruo.getUUID().equals(oruoMovedFor) || !AutoPuzzleUtil.gateWorldClick()) {
                return;
            }
            java.util.List<BlockPos> spots = AutoPuzzleUtil.reachSpots(client,
                    java.util.List.of(BlockPos.containing(oruo.getBoundingBox().getCenter())),
                    AutoWeirdos.SPOT_NPC_REACH - 0.3, false, player.position());
            oruoMovedFor = oruo.getUUID();
            if (spots.isEmpty()) {
                LOGGER.info("[AutoPuzzles] Quiz: Oruo is {} blocks off and no standable block reaches him",
                        String.format(java.util.Locale.US, "%.2f", d));
                return;
            }
            if (AutoPuzzleUtil.pathIfMapOn(spots.get(0), null, true)) {
                LOGGER.info("[AutoPuzzles] Quiz: Oruo is {} blocks off - moving onto {} to talk to him",
                        String.format(java.util.Locale.US, "%.2f", d), spots.get(0).toShortString());
            }
            return;
        }
        if (player.isShiftKeyDown() || McCompat.screen(client) != null) {
            oruoWait(player.isShiftKeyDown() ? "sneaking" : "a screen is open");
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, oruo)) {
            return;   // gate held this tick (or the body turned to him) - talked to on a later tick
        }
        net.minecraft.world.phys.AABB box = oruo.getBoundingBox();
        Vec3 aim = box.clip(eye, box.getCenter()).orElse(box.getCenter());
        client.gameMode.interact(player, oruo, new net.minecraft.world.phys.EntityHitResult(oruo, aim),
                InteractionHand.MAIN_HAND);
        player.swing(InteractionHand.MAIN_HAND);
        oruoTalkedTick = tick;
        oruoTalkLag = com.killer560.hub.util.ServerLag.mark();
        oruoMovedFor = null;
        oruoWaitLogged = null;
        LOGGER.info("[AutoPuzzles] Quiz: talked to Oruo ({} blocks eye to box)", String.format(java.util.Locale.US, "%.2f", d));
        ModChat.log(CHAT, ModChat.text("Quiz: talked to "), ModChat.value("Oruo"), ModChat.text("."));
    }

    private static void oruoWait(String why) {
        if (!why.equals(oruoWaitLogged)) {
            oruoWaitLogged = why;
            LOGGER.info("[AutoPuzzles] Quiz: not started, waiting to talk to Oruo - {}", why);
        }
    }

    /** Oruo's stand in this room: the "CLICK" stand nearest a stand named "Oruo the Omniscient", else that stand. */
    private static Entity oruo(Minecraft client) {
        Entity named = null;
        double bestNamed = 32.0 * 32.0;
        for (Entity e : client.level.entitiesForRendering()) {
            if (e.hasCustomName() && ChatObserver.strip(e.getName().getString()).contains("Oruo the Omniscient")) {
                double d = e.distanceToSqr(client.player);
                if (d < bestNamed) {
                    bestNamed = d;
                    named = e;
                }
            }
        }
        if (named == null) {
            return null;
        }
        Entity click = null;
        double bestClick = 4.0 * 4.0;
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof ArmorStand && e != named && "CLICK".equals(ChatObserver.strip(e.getName().getString()))) {
                double d = e.distanceToSqr(named);
                if (d < bestClick) {
                    bestClick = d;
                    click = e;
                }
            }
        }
        return click != null ? click : named;
    }

    // ---- where to stand for the answer ----

    /** The block, this world, from which one button of each of the three pillars is in reach (null: not found yet). */
    private static BlockPos quizAnswerSpot = null;
    private static Object quizSpotLevel = null;
    private static boolean quizSpotTried = false;

    /**
     * Where Auto Secret should bring him for an answer: a standable block from which BOTH eyes reach a button of every
     * pillar, so whichever answer is right is clicked from there with no move. Worked out once a world, the first time he
     * stands in the Quiz room (his trip there to talk to Oruo). Null until then, or when the room has no such block.
     */
    public static BlockPos quizAnswerSpot() {
        Minecraft client = Minecraft.getInstance();
        return client.level == quizSpotLevel ? quizAnswerSpot : null;
    }

    private static void noteAnswerSpot(Minecraft client) {
        if (client.level != quizSpotLevel) {
            quizSpotLevel = client.level;
            quizAnswerSpot = null;
            quizSpotTried = false;
        }
        if (quizSpotTried) {
            return;
        }
        java.util.List<BlockPos> pillars = QuizSolverFeature.answerPillars();
        if (pillars == null) {
            return;
        }
        quizSpotTried = true;
        Vec3 mid = Vec3.ZERO;
        for (BlockPos p : pillars) {
            mid = mid.add(Vec3.atCenterOf(p).scale(1.0 / pillars.size()));
        }
        java.util.List<BlockPos> buttons = new java.util.ArrayList<>();
        for (BlockPos p : pillars) {
            BlockPos b = QuizSolverFeature.buttonOf(client.level, p, mid);
            if (b == null) {
                LOGGER.info("[AutoPuzzles] Quiz: pillar {} has no button - no answer spot", p.toShortString());
                return;
            }
            buttons.add(b);
        }
        java.util.List<BlockPos> spots = AutoPuzzleUtil.reachSpots(client, buttons, Math.sqrt(QUIZ_REACH_SQ) - 0.1, false,
                mid);
        quizAnswerSpot = spots.isEmpty() ? null : spots.get(0);
        LOGGER.info("[AutoPuzzles] Quiz: answer spot {} (reaches a button of each pillar: {})",
                quizAnswerSpot == null ? "none" : quizAnswerSpot.toShortString(), buttons);
    }

    /** The last "waiting - why" Auto Quiz logged, so each reason is said once. */
    private static String quizBlockerLogged = null;
    /** The button the last move into reach was for: one trip per answer, not one a tick. */
    private static BlockPos quizMovedFor = null;

    /**
     * The answer's button is out of reach from where he stands: an Interactive Map trip onto the nearest block from which
     * both eyes reach it (fullclear-1016 run 002: Auto Secret's trip into Quiz ends at the room's spot, relative 15, 68, 5,
     * which reaches only the middle pillar's buttons; the answer was ⓒ and Auto Quiz waited out the 30 s Puzzle Wait,
     * saying nothing). Once per answer; the click goes when he stands in reach.
     */
    private static void moveIntoQuizReach(Minecraft client, BlockPos button) {
        if (button.equals(quizMovedFor) || !AutoPuzzleUtil.gateWorldClick()) {
            return;
        }
        java.util.List<BlockPos> spots = AutoPuzzleUtil.reachSpots(client, java.util.List.of(button),
                Math.sqrt(QUIZ_REACH_SQ) - 0.1, false, client.player.position());
        if (spots.isEmpty()) {
            quizMovedFor = button;
            LOGGER.info("[AutoPuzzles] Quiz: no standable block reaches the answer's button {}", button.toShortString());
            return;
        }
        if (AutoPuzzleUtil.pathIfMapOn(spots.get(0), null, true)) {
            quizMovedFor = button;
            LOGGER.info("[AutoPuzzles] Quiz: the answer's button {} is out of reach - moving onto {}",
                    button.toShortString(), spots.get(0).toShortString());
        }
    }

    private static boolean answerHologramsUp(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand && entity.distanceToSqr(client.player) <= QUIZ_HOLOGRAM_RADIUS_SQ
                    && entity.getName().getString().contains("ⓒ")) {
                return true;
            }
        }
        return false;
    }

    private static void resetQuiz() {
        oruoTalkedTick = -1;
        oruoMovedFor = null;
        oruoWaitLogged = null;
        quizActed = false;
        quizPendingPos = null;
        quizPendingSinceMs = 0L;
        quizWaitLogged = false;
        quizMovedFor = null;
        quizBlockerLogged = null;
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    /**
     * No-rotate block interact: the {@code SimonSaysFeature#sendNoRotateInteract} precedent
     * ({@code gameMode.useItemOn} with a synthetic {@link BlockHitResult}, camera untouched), but with QUOI's
     * {@code BlockPos.getHitResult()} face/hit-vector - the ray from the eyes to the block shape's centre
     * clipped against the real shape, so the face and hit point are ones a real click from here could produce.
     * Callers are already behind the cheat-build-gated config getters.
     *
     * @return false (nothing sent) if the block has no shape at all (QUOI skips those too)
     */
    private static boolean interactBlockNoRotate(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        VoxelShape shape = state.getShape(client.level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        Vec3 eyes = client.player.getEyePosition();
        Vec3 centre = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 dir = centre.subtract(eyes).normalize();
        Vec3 end = eyes.add(dir.scale(eyes.distanceTo(centre) + 1.5));
        BlockHitResult hit = shape.clip(eyes, end, pos);
        if (hit == null) {
            // Only reached when the ray misses the outline entirely. These two paths ALREADY clip from the eye
            // above, which is the thing that matters (see BlockHits and the PositionPlace measurement of
            // 2026-09-28) - so this last-resort centre is fine where it is and was deliberately left alone.
            hit = new BlockHitResult(centre, Direction.getApproximateNearest(eyes.subtract(centre)), pos, false);
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
