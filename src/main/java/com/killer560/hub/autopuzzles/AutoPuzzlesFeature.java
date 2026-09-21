package com.killer560.hub.autopuzzles;

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
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autopuzzles");
    static final String CHAT = "AutoPuzzles";

    private static final String QUIZ_ROOM = "Quiz";
    private static final String WEIRDOS_ROOM = "Three Weirdos";

    // QUOI Quiz: player.eyePosition.distanceToSqr(answerPos.vec3) > 36 -> skip.
    private static final double QUIZ_REACH_SQ = 36.0;
    // QUOI Quiz: getEntities<ArmorStand>(20.0) { "ⓒ" in name }.
    private static final double QUIZ_HOLOGRAM_RADIUS_SQ = 20.0 * 20.0;
    // QUOI ThreeWeirdos: player.eyePosition.distanceToSqr(pos.vec3) < 30.
    private static final double WEIRDOS_REACH_SQ = 30.0;
    // QUOI ThreeWeirdos NPC clicks: getEntities<ArmorStand>(10.0), entity.distanceToSqr(player) > 30 -> skip, 200ms gap.
    private static final double NPC_SCAN_RADIUS_SQ = 10.0 * 10.0;
    private static final double NPC_REACH_SQ = 30.0;
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

    public static void register() {
        // ChatObserver, same as QuizSolverFeature (whose reset this re-arm mirrors): Odin/NoammAddons/Skyblocker can
        // cancel a server line via ALLOW_GAME and re-add their own copy straight to ChatComponent, which Fabric
        // listeners never see - on Fabric CHAT/GAME the solver would reset but this would never re-arm. The trigger
        // is anchored to Oruo's "[STATUE] ..." server format, so this mod's own client messages can't match.
        ChatObserver.subscribe(AutoPuzzlesFeature::onMessage);
        ClientTickEvents.END_CLIENT_TICK.register(AutoPuzzlesFeature::onTick);
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
            if (quizActed || quizPendingPos != null) {
                LOGGER.info("[AutoPuzzles] Quiz: question answered (\"{}\") - re-armed", msg);
            }
            resetQuiz();
        }
    }

    private static void onTick(Minecraft client) {
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
            if (quizPendingPos != null) {
                LOGGER.info("[AutoPuzzles] Quiz: pending click on {} cancelled (enabled={} inQuizRoom={})",
                        quizPendingPos, cfg.isAutoQuizEnabled(), inQuizRoom);
            }
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
                LOGGER.info("[AutoPuzzles] Quiz: in Quiz room but Quiz Solver is OFF - Auto Quiz needs it");
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
            LOGGER.info("[AutoPuzzles] Quiz: correct answer at {} - clicking after {}ms", answer, cfg.getQuizDelayMs());
        }
        if (now - quizPendingSinceMs < cfg.getQuizDelayMs() || client.screen != null) {
            return;
        }
        String blocker = null;
        if (client.player.isShiftKeyDown()) {
            blocker = "sneaking";
        } else if (!answerHologramsUp(client)) {
            blocker = "answer holograms (ⓒ stand) not up yet";
        } else if (client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(answer)) > QUIZ_REACH_SQ) {
            blocker = String.format(java.util.Locale.US, "out of reach (%.2f blocks)",
                    Math.sqrt(client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(answer))));
        }
        if (blocker != null) {
            if (!quizWaitLogged) {
                quizWaitLogged = true;
                LOGGER.info("[AutoPuzzles] Quiz: waiting to click {} - {}", answer, blocker);
            }
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - the question is not marked acted, we just click on a later tick
        }
        quizActed = true; // once per question, even if the click itself can't be built
        if (!interactBlockNoRotate(client, answer)) {
            LOGGER.warn("[AutoPuzzles] Quiz: no clickable shape at {} (state={}) - not clicking this question",
                    answer, client.level.getBlockState(answer));
            return;
        }
        LOGGER.info("[AutoPuzzles] Quiz: clicked answer block {} (delay={}ms)", answer, cfg.getQuizDelayMs());
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
            if (weirdosPendingPos != null) {
                LOGGER.info("[AutoPuzzles] Weirdos: pending chest {} cancelled (enabled={} inWeirdosRoom={})",
                        weirdosPendingPos, cfg.isAutoWeirdosEnabled(), inWeirdosRoom);
            }
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
                LOGGER.info("[AutoPuzzles] Weirdos: in Three Weirdos room but Weirdos Solver is OFF - Auto Three Weirdos needs it");
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
        if (!solved && cfg.isWeirdosTalkToNpcs() && client.screen == null && !client.player.isShiftKeyDown()) {
            tryTalkToNpc(client, now);
        }
        if (!solved) {
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
            LOGGER.info("[AutoPuzzles] Weirdos: correct chest {} (wrong={}) - opening after {}ms",
                    chest, WeirdosSolverFeature.getWrongChestCount(), cfg.getWeirdosDelayMs());
        }
        if (now - weirdosPendingSinceMs < cfg.getWeirdosDelayMs() || client.screen != null) {
            return;
        }
        BlockState state = client.level.getBlockState(chest);
        double distSq = client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(chest));
        String blocker = null;
        if (client.player.isShiftKeyDown()) {
            blocker = "sneaking";
        } else if (!(state.getBlock() instanceof ChestBlock)) {
            blocker = "block there is not a chest (" + state + ")";
        } else if (distSq >= WEIRDOS_REACH_SQ) {
            blocker = String.format(java.util.Locale.US, "out of reach (%.2f blocks)", Math.sqrt(distSq));
        }
        if (blocker != null) {
            if (!weirdosWaitLogged) {
                weirdosWaitLogged = true;
                LOGGER.info("[AutoPuzzles] Weirdos: waiting to open {} - {}", chest, blocker);
            }
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - the room is not marked acted, we just open on a later tick
        }
        weirdosActedPos = chest;
        if (!interactBlockNoRotate(client, chest)) {
            LOGGER.warn("[AutoPuzzles] Weirdos: no clickable shape at {} (state={}) - not opening this room", chest, state);
            return;
        }
        LOGGER.info("[AutoPuzzles] Weirdos: opened correct chest {} (delay={}ms)", chest, cfg.getWeirdosDelayMs());
        ModChat.send(CHAT, ModChat.text("Three Weirdos: opened the "), ModChat.good("correct chest"), ModChat.text("."));
    }

    private static void tryTalkToNpc(Minecraft client, long now) {
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
            double distSq = entity.distanceToSqr(client.player);
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
        if (best == null || !AutoPuzzleUtil.gateWorldClick()) {
            return; // gate held this tick back - nothing clicked, so the NPC stays unmarked and the gap untouched
        }
        client.gameMode.interact(client.player, best, new EntityHitResult(best), InteractionHand.MAIN_HAND);
        client.player.swing(InteractionHand.MAIN_HAND);
        clickedNpcIds.add(best.getId());
        lastNpcClickMs = now;
        LOGGER.info("[AutoPuzzles] Weirdos: talked to NPC stand id={} at {} ({}/3)",
                best.getId(), best.blockPosition(), clickedNpcIds.size());
    }

    private static void resetWeirdos() {
        weirdosActedPos = null;
        weirdosPendingPos = null;
        weirdosPendingSinceMs = 0L;
        weirdosWaitLogged = false;
        clickedNpcIds.clear();
        lastNpcClickMs = 0L;
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
            hit = new BlockHitResult(centre, Direction.getApproximateNearest(eyes.subtract(centre)), pos, false);
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        client.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }
}
