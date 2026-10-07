package com.killer560.hub.autopuzzles;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.QuizSolverConfig;
import com.killer560.hub.puzzlesolvers.QuizSolverFeature;
import com.killer560.hub.puzzlesolvers.WeirdosSolverConfig;
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
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.HashSet;
import java.util.Set;
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
 *   <li>Three Weirdos: once the solver has the correct chest and both wrong chests (QUOI's
 *   {@code wrongPositions.size == 2} gate - all 3 NPCs have spoken), no-rotate interact the chest when
 *   distance² &lt; 30. Once per room. Optional (default OFF) QUOI "talk to NPCs": right-click each "CLICK"
 *   stand within 10 blocks (distance² &lt;= 30), 200ms apart.</li>
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
    // QUOI ThreeWeirdos: player.eyePosition.distanceToSqr(pos.vec3) < 30.
    /** Measured block reach, squared - was 30.0 (5.48 blocks) to the centre. */
    private static final double WEIRDOS_REACH_SQ = AutoPuzzleUtil.BLOCK_REACH_SQ;
    // QUOI ThreeWeirdos NPC clicks: getEntities<ArmorStand>(10.0), entity.distanceToSqr(player) > 30 -> skip, 200ms gap.
    private static final double NPC_SCAN_RADIUS_SQ = 10.0 * 10.0;
    /**
     * Measured ENTITY reach, squared.
     *
     * <p>Was 30.0 - 5.48 blocks, and measured feet-to-feet at that. Talking to a Weirdos NPC is an entity
     * interaction, where the anticheat names the distance past 3.0 to the entity's BOX, so this was nearly
     * twice the real limit against a measure that is itself optimistic.
     */
    private static final double NPC_REACH_SQ =
            com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH
                    * com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
    private static final long NPC_CLICK_GAP_MS = 200L;

    // ---- Quiz state ----
    private static boolean quizActed = false;
    private static BlockPos quizPendingPos = null;
    private static long quizPendingSinceMs = 0L;
    private static boolean quizWaitLogged = false;
    private static boolean quizSolverOffWarned = false;

    // ---- Three Weirdos state ----
    private static BlockPos weirdosActedPos = null;
    private static BlockPos weirdosPendingPos = null;
    private static long weirdosPendingSinceMs = 0L;
    private static boolean weirdosWaitLogged = false;
    private static boolean weirdosSolverOffWarned = false;
    /** The Three Weirdos room the clicked-NPC set belongs to. */
    private static com.killer560.hub.roomdatabase.RoomEntry lastWeirdosRoom;

    private static final Set<Integer> clickedNpcIds = new HashSet<>();
    private static long lastNpcClickMs = 0L;

    private static ClientLevel lastLevel = null;
    // Review fix (2026-09-15): the solvers' state is static and only resets on their own conditions
    // (leave dungeon / boss / room change). Across a quick requeue into a new instance (new level) their
    // old answer/chest could still be set when a same-layout room is entered, so nothing is clicked in a
    // level until the solver has been observed EMPTY at least once in that level - i.e. the answer/chest
    // it later reports was really produced in this instance.
    private static boolean quizSolverClearedThisLevel = false;
    private static boolean weirdosSolverClearedThisLevel = false;

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
        String plain = ChatFormatting.stripFormatting(message.getString());
        String msg = (plain != null ? plain : message.getString()).trim();
        // Same line QuizSolverFeature uses to clear the previous question's answer - re-arm for the next one
        // even if the next answer arrives in the same tick (so the null gap is never observed).
        if (msg.startsWith("[STATUE] Oruo the Omniscient: ") && msg.endsWith("correctly!")
                && (msg.contains("answered Question #") || msg.contains("answered the final question"))) {
            resetQuiz();
        }
    }

    private static void onTick(Minecraft client) {
        AutoPuzzleUtil.turnTick(client); // first, always: a body turned for a click is given back the tick after
        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetQuiz();
            resetWeirdos();
            quizSolverOffWarned = false;
            weirdosSolverOffWarned = false;
            quizSolverClearedThisLevel = false;
            weirdosSolverClearedThisLevel = false;
            AutoBlaze.levelChanged(client);
            AutoBeams.levelChanged(client);
            AutoIcePath.levelChanged(client);
            AutoBoulder.levelChanged();
            AutoWater.levelChanged(client);
            AutoTicTacToe.levelChanged();
            AutoTeleportMaze.levelChanged(client);
            AutoIceFill.levelChanged(client);
        }
        if (QuizSolverFeature.getCorrectAnswerPos() == null) {
            quizSolverClearedThisLevel = true;
        }
        if (WeirdosSolverFeature.getCorrectChestPos() == null && WeirdosSolverFeature.getWrongChestCount() == 0) {
            weirdosSolverClearedThisLevel = true;
        }
        if (client.player == null || client.level == null || client.gameMode == null) {
            return;
        }
        boolean inDungeon = DungeonState.isInDungeon();
        boolean inBoss = LiveMapFeature.isInBoss();
        RoomEntry room = inDungeon && !inBoss ? LiveMapFeature.currentRoomEntry() : null;
        String roomName = room != null ? room.name : null;
        tickQuiz(client, QUIZ_ROOM.equals(roomName));
        tickWeirdos(client, WEIRDOS_ROOM.equals(roomName));
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

        BlockPos answer = QuizSolverFeature.getCorrectAnswerPos();
        if (answer == null) {
            // Solver cleared the answer (question answered / room reset) - next known answer is a new question.
            if (quizActed || quizPendingPos != null) {
                resetQuiz();
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
        String blocker = null;
        if (client.player.isShiftKeyDown()) {
            blocker = "sneaking";
        } else if (!answerHologramsUp(client)) {
            blocker = "answer holograms (ⓒ stand) not up yet";
        } else if (com.killer560.hub.util.BlockHits.boxDistanceSq(client.player.getEyePosition(), answer) > QUIZ_REACH_SQ) {
            // To the BOX - see the note in AutoWater. Pairing a box-measured limit with a centre-measured
            // distance refuses blocks the server would accept.
            blocker = String.format(java.util.Locale.US, "out of reach (%.2f blocks)",
                    Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(client.player.getEyePosition(), answer)));
        }
        if (blocker != null) {
            return;
        }
        // THE BUTTON - see below. Asked before the gate so a click the reported look misses turns the body first.
        BlockPos quizClick = QuizSolverFeature.getCorrectAnswerButton(client.level);
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
        ModChat.send(CHAT, ModChat.text("Quiz: clicked the "), ModChat.good("correct answer"), ModChat.text("."));
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
        quizActed = false;
        quizPendingPos = null;
        quizPendingSinceMs = 0L;
        quizWaitLogged = false;
    }

    // ------------------------------------------------------------------
    // Auto Three Weirdos
    // ------------------------------------------------------------------

    private static void tickWeirdos(Minecraft client, boolean inWeirdosRoom) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        if (!cfg.isAutoWeirdosEnabled() || !inWeirdosRoom) {
            weirdosPendingPos = null;
            weirdosWaitLogged = false;
            if (!inWeirdosRoom) {
                weirdosSolverOffWarned = false;
            }
            return;
        }
        if (!WeirdosSolverConfig.getInstance().isEnabled()) {
            if (!weirdosSolverOffWarned) {
                weirdosSolverOffWarned = true;
                ModChat.send(CHAT, ModChat.text("Auto Three Weirdos needs "), ModChat.value("Weirdos Solver"),
                        ModChat.text(" turned on."));
            }
            return;
        }

        BlockPos chest = WeirdosSolverFeature.getCorrectChestPos();
        if (chest == null) {
            // Solver reset (dungeon left / boss / world change) - a later correct chest is a new room.
            weirdosActedPos = null;
            weirdosPendingPos = null;
        }
        if (weirdosActedPos != null) {
            return; // once per room
        }
        long now = System.currentTimeMillis();
        boolean solved = chest != null && WeirdosSolverFeature.getWrongChestCount() >= 2;
        if (!solved && cfg.isWeirdosTalkToNpcs() && McCompat.screen(client) == null && !client.player.isShiftKeyDown()) {
            tryTalkToNpc(client, now);
        }
        if (!solved) {
            weirdosWait(chest == null
                    ? "the solver has no correct chest yet (" + WeirdosSolverFeature.getWrongChestCount()
                    + " wrong chest(s) known" + (cfg.isWeirdosTalkToNpcs() ? "" : ", talk to NPCs is off") + ")"
                    : "the solver has the chest but only " + WeirdosSolverFeature.getWrongChestCount()
                    + " of 2 wrong chests");
            return;
        }
        if (!weirdosSolverClearedThisLevel) {
            if (!weirdosWaitLogged) {
                weirdosWaitLogged = true;
                LOGGER.info("[AutoPuzzles] Weirdos: chest {} predates this world - not opening until the solver resets", chest);
            }
            return;
        }
        if (!chest.equals(weirdosPendingPos)) {
            weirdosPendingPos = chest;
            weirdosPendingSinceMs = now;
            weirdosWaitLogged = false;
        }
        if (now - weirdosPendingSinceMs < cfg.getWeirdosDelayMs() || McCompat.screen(client) != null) {
            return;
        }
        BlockState state = client.level.getBlockState(chest);
        // To the BOX - see the note in AutoWater.
        double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(client.player.getEyePosition(), chest);
        String blocker = null;
        if (client.player.isShiftKeyDown()) {
            blocker = "sneaking";
        } else if (!(state.getBlock() instanceof ChestBlock)) {
            blocker = "block there is not a chest (" + state + ")";
        } else if (distSq >= WEIRDOS_REACH_SQ) {
            blocker = String.format(java.util.Locale.US, "out of reach (%.2f blocks)", Math.sqrt(distSq));
        }
        if (blocker != null) {
            weirdosWait("not opening " + AutoPuzzleUtil.fmt(chest) + " - " + blocker);
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, chest)) {
            return; // gate held this tick back (or the body was turned to it) - not marked acted, opened later
        }
        weirdosActedPos = chest;
        if (!interactBlockNoRotate(client, chest)) {
            LOGGER.warn("[AutoPuzzles] Weirdos: no clickable shape at {} (state={}) - not opening this room", chest, state);
            return;
        }
        ModChat.send(CHAT, ModChat.text("Three Weirdos: opened the "), ModChat.good("correct chest"), ModChat.text("."));
    }

    private static void tryTalkToNpc(Minecraft client, long now) {
        // The NPCs already talked to belong to ONE room.
        //
        // clickedNpcIds was only cleared on a level change, so after three NPCs in the first Three Weirdos
        // the guard below was permanently satisfied and Auto Three Weirdos never spoke to the NPCs in a
        // second one at all. Entity ids are not reused across rooms, so clearing on a room change costs
        // nothing and is the correct scope.
        com.killer560.hub.roomdatabase.RoomEntry weirdosRoom =
                com.killer560.hub.livemap.LiveMapFeature.currentRoomEntry();
        if (weirdosRoom != lastWeirdosRoom) {
            lastWeirdosRoom = weirdosRoom;
            clickedNpcIds.clear();
        }
        if (clickedNpcIds.size() >= 3 || now - lastNpcClickMs < NPC_CLICK_GAP_MS) {
            return;
        }
        // One NPC per tick, nearest first (the entity iteration order is not stable enough to be a rule on its own,
        // and the remaining stands are simply talked to on later ticks).
        Entity best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) || clickedNpcIds.contains(entity.getId())) {
                continue;
            }
            // EYE to the stand's BOX, not origin to origin.
            //
            // The constant was updated to MEASURED_MAX_ENTITY_REACH and the MEASURE was left alone, so this
            // compared feet-to-feet against a limit the server applies eye-to-box. It sent interacts at
            // stands the server considers out of range, and skipped stands it would have accepted when the
            // vertical offset went the other way. Terminal Aura and Terminal Triggerbot already do it this
            // way; this was the one that did not.
            double distSq = com.killer560.hub.util.BlockHits.boxDistanceSq(
                    client.player.getEyePosition(), entity.getBoundingBox());
            if (distSq > NPC_SCAN_RADIUS_SQ || distSq > NPC_REACH_SQ || distSq >= bestDistSq) {
                continue;
            }
            String stripped = ChatFormatting.stripFormatting(entity.getName().getString());
            if (stripped == null || !stripped.contains("CLICK")) {
                continue;
            }
            best = entity;
            bestDistSq = distSq;
        }
        if (best == null) {
            weirdosWait("no un-talked CLICK stand within entity reach ("
                    + clickedNpcIds.size() + " of 3 talked to)");
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, best)) {
            return; // gate held this tick back (or the body was turned to it) - the NPC stays unmarked
        }
        // A point ON the stand's box, not the stand's own position.
        //
        // new EntityHitResult(entity) reports the entity's origin as the hit location - a point at its feet.
        // The server measures the reported hit, so a stand that is genuinely in range can be reported as a
        // hit half a block below where the box is. Clipping the eye ray against the box gives the point a
        // real click would have produced.
        net.minecraft.world.phys.Vec3 eye = client.player.getEyePosition();
        net.minecraft.world.phys.Vec3 aim = best.getBoundingBox().clip(eye,
                best.getBoundingBox().getCenter()).orElse(best.getBoundingBox().getCenter());
        client.gameMode.interact(client.player, best, new EntityHitResult(best, aim),
                InteractionHand.MAIN_HAND);
        client.player.swing(InteractionHand.MAIN_HAND);
        clickedNpcIds.add(best.getId());
        lastNpcClickMs = now;
        LOGGER.info("[AutoPuzzles] Weirdos: talked to '{}' at {} ({} of 3)",
                ChatFormatting.stripFormatting(best.getName().getString()), best.blockPosition().toShortString(),
                clickedNpcIds.size());
    }

    /** What Auto Three Weirdos last said it was waiting for, so each refusal is one INFO line, not one a tick. */
    private static String weirdosLoggedWait = null;

    private static void weirdosWait(String why) {
        if (!why.equals(weirdosLoggedWait)) {
            weirdosLoggedWait = why;
            LOGGER.info("[AutoPuzzles] Weirdos: waiting - {}", why);
        }
    }

    private static void resetWeirdos() {
        weirdosActedPos = null;
        weirdosPendingPos = null;
        weirdosPendingSinceMs = 0L;
        weirdosWaitLogged = false;
        clickedNpcIds.clear();
        lastNpcClickMs = 0L;
        weirdosLoggedWait = null;
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
