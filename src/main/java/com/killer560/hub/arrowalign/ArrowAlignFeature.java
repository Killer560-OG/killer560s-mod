package com.killer560.hub.arrowalign;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import com.killer560.hub.compat.McRender;

/**
 * Arrow Align - the F7/M7 P3 third device: a 5x5 wall of item frames holding arrows at x=-2, y 120-124, z 75-79
 * (Goldor arena, player side is +X, device stand at 0,120,77). Each right-click rotates a frame by one of its 8
 * steps; the device completes once every arrow points along the path.
 * <p>
 * Solution data: the 9 known layouts, identical across Odin ({@code features/impl/boss/ArrowAlign.kt}), NoammAddons
 * ({@code features/impl/floor7/devices/ArrowAlign.kt}), QUOI ({@code module/impl/floor7/ArrowAlign.kt}, same 9 in a
 * different order) and Devonian ({@code ArrowAlignSolver.kt}, same 9 re-indexed as {@code (dy shl 3) or dz}). Index
 * is {@code dy + dz * 5}; {@code -1} = no arrow frame there. A layout matches when arrow-frame presence matches its
 * non-{@code -1} cells exactly; the value is the target rotation, and clicks needed = {@code (target - current) & 7}.
 * <p>
 * Rotation tracking (the Devonian/QUOI idea, made explicit): the client never predicts an item-frame rotation
 * ({@code ItemFrame#interact} returns SUCCESS client-side without touching it - checked in the 26.1.2 jar), so the
 * real value only changes when the server's entity-data update arrives, which lags on Hypixel. Every click that goes
 * out (real, via {@code ArrowAlignInteractMixin}, or this feature's own) books a pending click on that frame; the
 * frame's "effective" rotation is {@code observed + pending}. When the observed rotation moves by d, d pending clicks
 * are confirmed. Pending clicks nobody confirms within {@link #PENDING_TIMEOUT_MS} are dropped (resync to the
 * server). Everything that decides whether to click (Prevent Misclicks, Trigger Bot, Aura) uses the effective value,
 * so spam or fast automation can't overshoot while updates are in flight.
 */
public final class ArrowAlignFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod/ArrowAlign");

    private static final int GRID_X = -2;
    private static final int GRID_Y = 120;
    private static final int GRID_Z = 75;
    private static final Vec3 DEVICE_STAND = Vec3.atCenterOf(new BlockPos(0, 120, 77));
    // Odin/NoammAddons use distSqr 200 around the device; a bit wider so the solver is already up when walking in.
    private static final double ACTIVE_RANGE_SQ = 15.0 * 15.0;
    private static final AABB SCAN_BOX = new AABB(GRID_X - 1, GRID_Y - 1, GRID_Z - 1, GRID_X + 2, GRID_Y + 6, GRID_Z + 6);
    // Same 1000ms "recent click" window Odin/NoammAddons/QUOI hold their local rotation guess for.
    private static final long PENDING_TIMEOUT_MS = 1000L;

    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_GOLD = 0xFFFFAA00;
    private static final int COLOR_RED = 0xFFFF5555;

    private static final int[][] SOLUTIONS = {
            {7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, -1, -1, 7, 1},
            {-1, -1, 7, 7, 5, -1, 7, 1, -1, 5, -1, -1, -1, -1, -1, -1, 7, 5, -1, 1, -1, -1, 7, 7, 1},
            {7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, -1, 7, 5, -1, -1, -1, -1, 5, -1, -1, -1, 3, 3},
            {5, 3, 3, 3, -1, 5, -1, -1, -1, -1, 7, 7, -1, -1, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, -1},
            {5, 3, 3, 3, 3, 5, -1, -1, -1, 1, 7, 7, -1, -1, 1, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
            {7, 7, 7, 7, -1, 1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, -1, 7, 7, 7, 1},
            {-1, -1, -1, -1, -1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1},
            {-1, -1, -1, -1, -1, 1, 3, 3, 3, 3, -1, -1, -1, -1, 1, 7, 7, 7, 7, 1, -1, -1, -1, -1, -1},
            {-1, -1, -1, -1, -1, -1, 1, -1, 1, -1, 7, 1, 7, 1, 3, 1, -1, 1, -1, 1, -1, -1, -1, -1, -1}
    };

    // --- per-frame state, index = dy + dz * 5 ---
    private static final ItemFrame[] frames = new ItemFrame[25];
    private static final int[] observed = new int[25];
    private static final int[] pending = new int[25];
    private static final long[] lastClickMs = new long[25];
    private static int[] solution = null;
    /** Grid index the Aura leaves undone before S3 (-1 none, -2 not chosen yet for this solution). */
    private static int heldIndex = -2;
    private static boolean active = false;
    private static ClientLevel lastLevel = null;

    // --- solve timer ---
    private static long firstClickMs = 0L;
    private static int clicksThisSolve = 0;
    private static boolean solvedAnnounced = false;

    // --- automation ---
    private static boolean syntheticClickInProgress = false;
    // Client ticks (ActionGate.currentTick), not ms: the clicks go out at the start of a tick.
    private static int triggerAimIndex = -1;
    private static long triggerAimSinceTick = 0L;
    /** Fractional tick the Trigger Bot may next click: one rolled gap after the aim landed, then after each click. */
    private static double nextTriggerClickDueTick = 0.0;
    /** Fractional, so the Aura's rolled 15-17 CPS stays 15-17 on average instead of rounding to 10. */
    private static double nextAuraClickDueTick = 0.0;
    /** The tick a due Aura click first went to turning the body (TurnFirst), or -1: the click is scheduled from there. */
    private static long auraTurnStartTick = -1L;
    /** Set by {@link #sendClick} when it returned false because it turned the body this tick. */
    private static boolean sendClickTurned = false;
    private static int lastAuraIndex = -1;

    static {
        Arrays.fill(observed, -1);
    }

    private ArrowAlignFeature() {
    }

    // ---- logging only ----
    private static String lastHeldKey;
    private static long lastHeldTick;
    private static int lastAuraTargetLogged = -1;
    private static boolean lastTriggerCrouchLogged;
    private static boolean lastAuraCrouchLogged;
    private static String lastAutomationGate = "init";

    /** A held click, once per key per 20 ticks (BloodCampFeature.logRefusal model). */
    private static void logHeld(String key, String detail) {
        long now = com.killer560.hub.util.ActionGate.currentTick();
        if (key.equals(lastHeldKey) && now - lastHeldTick < 20) {
            return;
        }
        lastHeldKey = key;
        lastHeldTick = now;
        LOGGER.info("[ArrowAlign] click held: {} tick={}", detail, now);
    }

    private static int solutionIndex(int[] sol) {
        for (int i = 0; i < SOLUTIONS.length; i++) {
            if (SOLUTIONS[i] == sol) {
                return i;
            }
        }
        return -1;
    }

    /** Clicks needed per frame, as "index:needed" for the frames that need any (pure). */
    private static String neededSummary() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            int n = clicksNeeded(i);
            if (n > 0) {
                sb.append(sb.length() == 0 ? "" : " ").append(i).append(':').append(n);
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("ArrowAlignFeature.tick", ArrowAlignFeature::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(com.killer560.hub.util.FeatureGuard.afterFeatures(
                com.killer560.hub.util.FeatureBits.register("Arrow Align render", () -> ArrowAlignConfig.getInstance().isSolverEnabledRaw()), ArrowAlignFeature::onWorldRender));
        // Trigger Bot and Aura click only from tick() (START_CLIENT_TICK). They also ran from the world-render hook for
        // millisecond delays until 2026-10-11, and a click sent from a frame follows the tick's movement packet: GrimAC
        // Post on every one (testkit 788). Delays are whole ticks now - see tickTriggerBot / tickAura.
    }

    // ------------------------------------------------------------------
    // Tick: scan frames, confirm pending clicks, match layout
    // ------------------------------------------------------------------

    /** The body turn for a frame the reported look does not reach (util/TurnFirst); the camera never moves. */
    private static final com.killer560.hub.util.TurnFirst TURN = new com.killer560.hub.util.TurnFirst();

    private static void tick(Minecraft client) {
        TURN.tick(client.player); // first, always: a body turned for a click is given back the tick after
        if (client.level != lastLevel) {
            reset();
            lastLevel = client.level;
        }
        ArrowAlignConfig cfg = ArrowAlignConfig.getInstance();
        boolean anyOn = cfg.isSolverEnabled() || cfg.isPreventMisclicksEnabled() || cfg.isSolveTimeEnabled()
                || cfg.isTriggerBotEnabled() || cfg.isAuraEnabled();
        if (!anyOn || client.player == null || client.level == null || !DungeonState.isF7OrM7()
                || client.player.distanceToSqr(DEVICE_STAND) > ACTIVE_RANGE_SQ) {
            if (active) {
                LOGGER.info("[ArrowAlign] inactive: anyOn={} f7={} distToDevice={} - state reset", anyOn,
                        DungeonState.isF7OrM7(), client.player == null ? "n/a"
                                : String.format(Locale.ROOT, "%.1f", Math.sqrt(client.player.distanceToSqr(DEVICE_STAND))));
                reset();
            }
            return;
        }
        if (!active) {
            LOGGER.info("[ArrowAlign] active: near the device (dist={}) solver={} preventMisclicks={} triggerBot={} aura={} tick={}",
                    String.format(Locale.ROOT, "%.1f", Math.sqrt(client.player.distanceToSqr(DEVICE_STAND))),
                    cfg.isSolverEnabled(), cfg.isPreventMisclicksEnabled(), cfg.isTriggerBotEnabled(), cfg.isAuraEnabled(),
                    com.killer560.hub.util.ActionGate.currentTick());
        }
        active = true;
        long now = System.currentTimeMillis();
        scanFrames(client.level, now);
        updateSolveTimer(cfg, now);
        tickAutomation(client);
    }

    private static void scanFrames(ClientLevel level, long now) {
        ItemFrame[] found = new ItemFrame[25];
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, SCAN_BOX, f -> f.getItem().is(Items.ARROW))) {
            int index = indexOf(frame.blockPosition());
            if (index >= 0) {
                found[index] = frame;
            }
        }
        for (int i = 0; i < 25; i++) {
            ItemFrame frame = found[i];
            if (frame == null) {
                if (frames[i] != null) {
                    LOGGER.info("[ArrowAlign] frame {} gone (entity #{}, pending {} dropped)", i, frames[i].getId(), pending[i]);
                }
                frames[i] = null;
                observed[i] = -1;
                pending[i] = 0;
                continue;
            }
            int rotation = frame.getRotation() & 7;
            if (frames[i] == null || frames[i].getId() != frame.getId() || observed[i] < 0) {
                LOGGER.debug("[ArrowAlign] frame {} tracked: entity #{} rotation {}", i, frame.getId(), rotation);
                frames[i] = frame;
                observed[i] = rotation;
                pending[i] = 0;
                continue;
            }
            frames[i] = frame;
            if (rotation != observed[i]) {
                int delta = (rotation - observed[i]) & 7;
                LOGGER.info("[ArrowAlign] frame {} rotation {} -> {} (server): confirms {} of {} pending, {} ms after the last click",
                        i, observed[i], rotation, Math.min(delta, pending[i]), pending[i],
                        lastClickMs[i] > 0 ? now - lastClickMs[i] : -1L);
                pending[i] = Math.max(0, pending[i] - delta);
                observed[i] = rotation;
            }
            if (pending[i] > 0 && now - lastClickMs[i] > PENDING_TIMEOUT_MS) {
                LOGGER.info("[ArrowAlign] {} pending click(s) on frame {} unconfirmed after {}ms - resyncing to server rotation {}.",
                        pending[i], i, now - lastClickMs[i], rotation);
                pending[i] = 0;
            }
        }
        int[] match = findSolution();
        if (match != solution) {
            solution = match;
            heldIndex = -2;
            int present = 0;
            for (ItemFrame f : frames) {
                if (f != null) {
                    present++;
                }
            }
            LOGGER.info("[ArrowAlign] layout {}: frames={} needed {}", match == null ? "none matched" : "#" + solutionIndex(match),
                    present, match == null ? "-" : neededSummary());
        }
    }

    private static int[] findSolution() {
        outer:
        for (int[] candidate : SOLUTIONS) {
            for (int i = 0; i < 25; i++) {
                if ((candidate[i] < 0) != (frames[i] == null)) {
                    continue outer;
                }
            }
            return candidate;
        }
        return null;
    }

    private static void updateSolveTimer(ArrowAlignConfig cfg, long now) {
        if (solution == null) {
            return;
        }
        boolean serverSolved = totalServerClicksNeeded() == 0;
        if (solvedAnnounced) {
            if (!serverSolved) {
                // Device reset / re-randomized after a solve - arm a fresh attempt.
                LOGGER.info("[ArrowAlign] device unsolved again after a solve: new attempt armed (needed {})", neededSummary());
                solvedAnnounced = false;
                heldIndex = -2; // the same layout rolled again: the Leave Last Arrow frame is chosen afresh
                firstClickMs = 0L;
                clicksThisSolve = 0;
            }
            return;
        }
        if (serverSolved && firstClickMs > 0L) {
            solvedAnnounced = true;
            long took = now - firstClickMs;
            LOGGER.info("[ArrowAlign] solved (server rotations): {} ms, {} clicks tick={}", took, clicksThisSolve,
                    com.killer560.hub.util.ActionGate.currentTick());
            if (cfg.isSolveTimeEnabled()) {
                ModChat.send("Arrow Align",
                        ModChat.text("Solved in "),
                        ModChat.value(String.format(Locale.US, "%.2fs", took / 1000.0)),
                        ModChat.dim(" (" + clicksThisSolve + " clicks)"));
            }
        }
    }

    private static void reset() {
        if (active || solution != null) {
            LOGGER.info("[ArrowAlign] reset: solution={} clicksThisSolve={} solved={}", solution == null ? "none"
                    : "#" + solutionIndex(solution), clicksThisSolve, solvedAnnounced);
        }
        lastAuraTargetLogged = -1;
        lastHeldKey = null;
        Arrays.fill(frames, null);
        Arrays.fill(observed, -1);
        Arrays.fill(pending, 0);
        Arrays.fill(lastClickMs, 0L);
        solution = null;
        heldIndex = -2;
        active = false;
        firstClickMs = 0L;
        clicksThisSolve = 0;
        solvedAnnounced = false;
        triggerAimIndex = -1;
        nextTriggerClickDueTick = 0.0;
        lastAuraIndex = -1;
        nextAuraClickDueTick = 0.0;
        auraTurnStartTick = -1L;
    }

    // ------------------------------------------------------------------
    // Click math
    // ------------------------------------------------------------------

    private static int indexOf(BlockPos pos) {
        int dy = pos.getY() - GRID_Y;
        int dz = pos.getZ() - GRID_Z;
        if (pos.getX() != GRID_X || dy < 0 || dy > 4 || dz < 0 || dz > 4) {
            return -1;
        }
        return dy + dz * 5;
    }

    /** @return the grid index of a tracked arrow frame, or -1. */
    private static int indexOfTracked(Entity entity) {
        if (!(entity instanceof ItemFrame frame) || !frame.getItem().is(Items.ARROW)) {
            return -1;
        }
        int index = indexOf(frame.blockPosition());
        if (index < 0 || frames[index] == null || frames[index].getId() != frame.getId()) {
            return -1;
        }
        return index;
    }

    /** Clicks still needed on top of every click already sent (observed + pending). 0 when unknown. */
    private static int clicksNeeded(int index) {
        int[] sol = solution;
        if (sol == null || sol[index] < 0 || observed[index] < 0) {
            return 0;
        }
        return (sol[index] - observed[index] - pending[index]) & 7;
    }

    /** Clicks needed by the server's last confirmed rotation alone. */
    private static int serverClicksNeeded(int index) {
        int[] sol = solution;
        if (sol == null || sol[index] < 0 || observed[index] < 0) {
            return 0;
        }
        return (sol[index] - observed[index]) & 7;
    }

    private static int totalServerClicksNeeded() {
        int total = 0;
        for (int i = 0; i < 25; i++) {
            total += serverClicksNeeded(i);
        }
        return total;
    }

    private static void noteClick(int index, long now) {
        pending[index]++;
        lastClickMs[index] = now;
        if (firstClickMs == 0L && !solvedAnnounced) {
            firstClickMs = now;
        }
        clicksThisSolve++;
    }

    /**
     * For {@code ArrowAlignInteractMixin} - every {@code MultiPlayerGameMode#interact} attempt. Blocks (returns true)
     * a real click on a tracked arrow frame that is already at its target counting in-flight clicks (i.e. the click
     * would overshoot), unless Crouch To Override is on and the player is sneaking. Any click that goes through is
     * booked as pending. This feature's own clicks book themselves in {@link #sendClick} and are ignored here, so
     * Trigger Bot/Aura stay overshoot-safe even if the mixin failed to apply.
     */
    public static boolean onInteractAttempt(Entity entity, boolean shiftDown) {
        if (syntheticClickInProgress || !active || solution == null) {
            return false;
        }
        int index = indexOfTracked(entity);
        if (index < 0) {
            return false;
        }
        ArrowAlignConfig cfg = ArrowAlignConfig.getInstance();
        if (clicksNeeded(index) == 0 && cfg.isPreventMisclicksEnabled() && !(cfg.isCrouchOverride() && shiftDown)) {
            LOGGER.info("[ArrowAlign] misclick blocked: frame {} already at target (observed {} + pending {}, target {}) tick={}",
                    index, observed[index], pending[index], solution[index], com.killer560.hub.util.ActionGate.currentTick());
            return true;
        }
        noteClick(index, System.currentTimeMillis());
        LOGGER.info("[ArrowAlign] player click booked: frame {} observed {} pending {} needed now {} sneak={} tick={}", index,
                observed[index], pending[index], clicksNeeded(index), shiftDown, com.killer560.hub.util.ActionGate.currentTick());
        return false;
    }

    /** Real interact on a frame through the same path a vanilla right-click takes
     *  ({@code gameMode.interact(player, entity, hitResult, hand)} - sends the interact packet with the hit location
     *  relative to the entity). Callers must already be behind a cheat-gated config getter. */
    private static boolean sendClick(Minecraft client, int index, long now) {
        return sendClick(client, index, now, true, false);
    }

    /** {@code claimGate} false is the Aura's extra clicks in the same tick (Clicks Per Tick above 1): the first click of
     *  the tick already took the ActionGate claim. {@code sameTickTurn}: a frame off the look is turned to AND clicked
     *  this tick (the Aura's Turn And Click Same Tick), instead of turned now and clicked next tick. */
    private static boolean sendClick(Minecraft client, int index, long now, boolean claimGate, boolean sameTickTurn) {
        sendClickTurned = false;
        ItemFrame frame = frames[index];
        if (frame == null || frame.isRemoved() || client.player == null || client.gameMode == null) {
            return false;
        }
        AABB box = frame.getBoundingBox();
        // The reach check belongs HERE, at the one place the interact packet leaves, not in each caller. Aura
        // checked its own range and Trigger Bot did not - it was safe only because it reads client.hitResult,
        // which is a coincidence of how it picks a target rather than a guarantee about what gets sent. This is
        // the server's hard entity limit, not the user's aura range: a tighter aura range is a preference, this
        // is the line that draws a Reach flag. It sits BEFORE the ActionGate claim so an out-of-range frame
        // does not burn this tick's one interaction on a packet that is never sent.
        double reach = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
        if (com.killer560.hub.util.BlockHits.boxDistanceSq(client.player.getEyePosition(), box) > reach * reach) {
            logHeld("reach" + index, "frame " + index + " out of reach: distToBox="
                    + String.format(Locale.ROOT, "%.2f",
                    Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(client.player.getEyePosition(), box)))
                    + " max=" + String.format(Locale.ROOT, "%.2f", reach));
            return false;
        }
        // Mod-wide one-interaction-per-tick gate (killer560, 2026-09-20: "make sure every type of aura has some
        // sort of coordination"). Trigger Bot and Aura are both allowed to run on the same tick above, so this is
        // also what stops THIS feature putting two interact packets in one tick. A denial must leave the caller's
        // delay/target bookkeeping untouched so the same frame is simply clicked a tick later.
        if (claimGate && !com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.ARROW_ALIGN)) {
            logHeld("gate", "ActionGate refused ARROW_ALIGN for frame " + index);
            return false;
        }
        // Where the line from the eye meets the frame's box - the point a real click reports. (It was the center of
        // the east face, right only for a player standing east of the frames.) Falls back to that face's center.
        Vec3 eye = client.player.getEyePosition();
        Vec3 hit = box.clip(eye, box.getCenter())
                .orElse(new Vec3(box.maxX, (box.minY + box.maxY) / 2.0, (box.minZ + box.maxZ) / 2.0));
        // Aura picks frames by range in any direction: one the reported look does not reach gets the BODY turned to it
        // this tick and is clicked on the next (or this one, Turn And Click Same Tick), the camera held (util/TurnFirst),
        // so the movement packets always show him looking at the frame he clicks. GrimAC 2.3.74 itself never judges the
        // look on an item frame (Reach returns NONE for a non-living entity, javap aafast-1027; the Hitboxes flags of
        // testkit 415/418 were armor stands); the turn is for a server that does. False is the callers' "retry".
        double turnReach = eye.distanceTo(hit) + 1.0;
        if (!TURN.readyBox(client.player, box, hit, turnReach)
                // Turn And Click Same Tick (aafast-1027): the turn just written goes out in THIS tick's movement packet,
                // right after this interact. GrimAC 2.3.74 judges a (living) entity interact at the next tick packet against
                // that packet's look (Reach.tickBetterReachCheckWithAngle, javap), and an item frame not at all; testkit
                // 1027-aafast-same* are clean. Asking again clears TurnFirst's pending wait (the look hits now).
                && !(sameTickTurn && TURN.isTurned() && TURN.readyBox(client.player, box, hit, turnReach))) {
            sendClickTurned = true;
            LOGGER.info("[ArrowAlign] turning body to frame {} first (reported look does not reach it) tick={}", index,
                    com.killer560.hub.util.ActionGate.currentTick());
            return false;
        }
        syntheticClickInProgress = true;
        try {
            client.gameMode.interact(client.player, frame, new EntityHitResult(frame, hit), InteractionHand.MAIN_HAND);
        } finally {
            syntheticClickInProgress = false;
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        noteClick(index, now);
        LOGGER.info("[ArrowAlign] click sent: frame {} entity #{} hit={} dist={} observed {} pending {} target {} needed {}{} tick={}",
                index, frame.getId(), String.format(Locale.ROOT, "%.2f,%.2f,%.2f", hit.x, hit.y, hit.z),
                String.format(Locale.ROOT, "%.2f", eye.distanceTo(hit)), observed[index], pending[index],
                solution == null ? -1 : solution[index], clicksNeeded(index), claimGate ? "" : " (extra click this tick)",
                com.killer560.hub.util.ActionGate.currentTick());
        return true;
    }

    // ------------------------------------------------------------------
    // Trigger Bot + Aura (cheat build)
    // ------------------------------------------------------------------

    private static void tickAutomation(Minecraft client) {
        ArrowAlignConfig cfg = ArrowAlignConfig.getInstance();
        boolean trigger = cfg.isTriggerBotEnabled();
        boolean aura = cfg.isAuraEnabled();
        if (!trigger && !aura) {
            triggerAimIndex = -1;
            return;
        }
        if (!active || solution == null || client.player == null || client.level == null
                || com.killer560.hub.util.ActionGate.containerScreenOpen(client) || client.gameMode == null || client.level != lastLevel) {
            String why = !active ? "inactive" : solution == null ? "no layout matched"
                    : com.killer560.hub.util.ActionGate.containerScreenOpen(client) ? "container screen open" : "no player/level";
            if (!why.equals(lastAutomationGate)) {
                lastAutomationGate = why;
                LOGGER.info("[ArrowAlign] automation held: {}", why);
            }
            triggerAimIndex = -1;
            return;
        }
        if (lastAutomationGate != null) {
            lastAutomationGate = null;
            LOGGER.info("[ArrowAlign] automation running: triggerBot={} aura={} needed {}", trigger, aura, neededSummary());
        }
        long now = System.currentTimeMillis();
        if (trigger) {
            tickTriggerBot(client, cfg, now);
        } else {
            triggerAimIndex = -1;
        }
        if (aura) {
            tickAura(client, cfg, now);
        }
    }

    private static void tickTriggerBot(Minecraft client, ArrowAlignConfig cfg, long now) {
        int index = client.hitResult instanceof EntityHitResult hit ? indexOfTracked(hit.getEntity()) : -1;
        if (index < 0 || clicksNeeded(index) == 0) {
            triggerAimIndex = -1;
            return;
        }
        // killer560, 2026-10-18: the Trigger Bot does nothing while the crouch key is held. Dropping the aim too means
        // the first click after the release waits a fresh gap, like a click on a newly aimed frame.
        boolean crouchHeld = cfg.isTriggerBotDisableWhileCrouching() && client.player.isShiftKeyDown();
        if (crouchHeld != lastTriggerCrouchLogged) {
            lastTriggerCrouchLogged = crouchHeld;
            LOGGER.info("[ArrowAlign] Trigger Bot {} (Disable While Crouching)", crouchHeld ? "paused: crouching" : "resumed");
        }
        if (cfg.isTriggerBotDisableWhileCrouching() && client.player.isShiftKeyDown()) {
            triggerAimIndex = -1;
            return;
        }
        long tick = com.killer560.hub.util.ActionGate.currentTick();
        if (index != triggerAimIndex) {
            triggerAimIndex = index;
            nextTriggerClickDueTick = tick + rollTriggerGapTicks(cfg);
            LOGGER.info("[ArrowAlign] Trigger Bot aimed at frame {}: needed {} first click due at tick {} (now {}) cps {}-{}",
                    index, clicksNeeded(index), String.format(Locale.ROOT, "%.2f", nextTriggerClickDueTick), tick,
                    cfg.getTriggerBotCpsMin(), cfg.getTriggerBotCpsMax());
        }
        if (tick < nextTriggerClickDueTick) {
            return;
        }
        if (!sendClick(client, index, now)) {
            return; // gate held this tick back - nothing sent, so the due tick stays where it is
        }
        // From the due tick, not from now, so the band's average survives tick rounding; a click the gate held back
        // carries at most a tick of backlog.
        nextTriggerClickDueTick = Math.max(nextTriggerClickDueTick, tick - 1) + rollTriggerGapTicks(cfg);
    }

    /** A fresh gap for every click: a CPS uniformly inside the Trigger Bot's range, as client ticks (fractional). */
    static double rollTriggerGapTicks(ArrowAlignConfig cfg) {
        double min = cfg.getTriggerBotCpsMin();
        double max = Math.max(min, cfg.getTriggerBotCpsMax());
        double cps = max > min ? ThreadLocalRandom.current().nextDouble(min, max) : min;
        return 1000.0 / Math.max(1.0, cps) / 50.0;
    }

    private static void tickAura(Minecraft client, ArrowAlignConfig cfg, long now) {
        long tick = com.killer560.hub.util.ActionGate.currentTick();
        // killer560, 2026-10-18: Disable While Crouching (per feature, on by default).
        boolean crouchHeld = cfg.isAuraDisableWhileCrouching() && client.player.isShiftKeyDown();
        if (crouchHeld != lastAuraCrouchLogged) {
            lastAuraCrouchLogged = crouchHeld;
            LOGGER.info("[ArrowAlign] Aura {} (Disable While Crouching)", crouchHeld ? "paused: crouching" : "resumed");
        }
        if (cfg.isAuraDisableWhileCrouching() && client.player.isShiftKeyDown()) {
            lastAuraIndex = -1;
            auraTurnStartTick = -1L;
            return;
        }
        if (tick < nextAuraClickDueTick) {
            return;
        }
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getAuraRange() * cfg.getAuraRange();
        int target = pickAuraTarget(client.player, eye, rangeSq);
        if (target != lastAuraTargetLogged) {
            lastAuraTargetLogged = target;
            if (target < 0) {
                LOGGER.info("[ArrowAlign] Aura: no frame to click in range {} (needed {}, held frame {}) tick={}",
                        String.format(Locale.ROOT, "%.2f", cfg.getAuraRange()), neededSummary(), heldIndex, tick);
            } else {
                LOGGER.info("[ArrowAlign] Aura target: frame {} needed {} (aura may click {}) distToBox={} onLook={} tick={}",
                        target, clicksNeeded(target), auraClicksLeft(target),
                        String.format(Locale.ROOT, "%.2f", Math.sqrt(com.killer560.hub.util.BlockHits.boxDistanceSq(eye,
                                frames[target].getBoundingBox()))),
                        frames[target].getBoundingBox().clip(eye, eye.add(com.killer560.hub.util.TurnFirst.look(client.player)
                                .scale(Math.sqrt(rangeSq) + 1.0))).isPresent(), tick);
            }
        }
        if (target < 0) {
            lastAuraIndex = -1;
            auraTurnStartTick = -1L;
            return;
        }
        if (!sendClick(client, target, now, true, cfg.isAuraSameTickTurn())) {
            // Gate held this tick back - the rolled delay must not be re-rolled for a click never sent. A tick spent
            // turning the body to the frame is remembered: the click is scheduled from it, below.
            if (sendClickTurned && auraTurnStartTick < 0) {
                auraTurnStartTick = tick;
            }
            return;
        }
        lastAuraIndex = target;
        for (int extra = 1; extra < cfg.getAuraClicksPerTick() && auraClicksLeft(target) > 0; extra++) {
            if (!sendClick(client, target, now, false, false)) {
                break;
            }
        }
        // A click that needed a turn first counts from the turn's tick, not the tick after: otherwise every turn tick was
        // added to the gap (Lever Aura's identical schedule measured 7.89 at 10 CPS, testkit 846; the sweep here, 832).
        long base = auraTurnStartTick >= 0 ? auraTurnStartTick : tick;
        auraTurnStartTick = -1L;
        // Aura CPS: each gap is 1000 / CPS ms with +-10% jitter so no two gaps are alike (never a metronome), and is
        // scheduled from the due tick, not this one, so the average is the CPS set even where the gap is a fraction of a
        // tick (16 CPS = 1.25 ticks: gaps of 1 and 2). One click a tick at most, so 20 is the ceiling. A click the gate
        // held back carries at most a tick of backlog.
        double gapMs = 1000.0 / cfg.getAuraCps() * (0.9 + ThreadLocalRandom.current().nextDouble() * 0.2);
        nextAuraClickDueTick = Math.max(nextAuraClickDueTick, base - 1) + gapMs / 50.0;
    }

    /**
     * Which frame the Aura clicks next (killer560, 2026-10-11, option A: "clicks frames already on the reported look
     * first, so it turns less often"). A frame the movement packets' look already passes through is clicked with no turn
     * at all, and a turn costs a tick (turn, then click once a movement packet has carried it - GrimAC Hitboxes
     * otherwise). So, in order: the frame it is on, if the look still reaches it (a frame needing several clicks); any
     * frame on the look, nearest along the ray (the body is given back to his view the tick after a click, so whatever
     * he is looking at is free); then the clickable frame the smallest turn away from the look, so each turn is short
     * and the next frame is beside the last.
     */
    private static int pickAuraTarget(net.minecraft.client.player.LocalPlayer player, Vec3 eye, double rangeSq) {
        Vec3 look = com.killer560.hub.util.TurnFirst.look(player);
        Vec3 end = eye.add(look.scale(Math.sqrt(rangeSq) + 1.0));
        if (lastAuraIndex >= 0 && isAuraClickable(lastAuraIndex, eye, rangeSq)
                && frames[lastAuraIndex].getBoundingBox().clip(eye, end).isPresent()) {
            return lastAuraIndex;
        }
        int onLook = -1;
        double onLookDist = Double.MAX_VALUE;
        int nearest = -1;
        double nearestDot = -2.0;
        for (int i = 0; i < 25; i++) {
            if (!isAuraClickable(i, eye, rangeSq)) {
                continue;
            }
            AABB box = frames[i].getBoundingBox();
            java.util.Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < onLookDist) {
                    onLookDist = d;
                    onLook = i;
                }
                continue;
            }
            double dot = box.getCenter().subtract(eye).normalize().dot(look);
            if (dot > nearestDot) {
                nearestDot = dot;
                nearest = i;
            }
        }
        return onLook >= 0 ? onLook : nearest;
    }

    /**
     * The frame the Aura must leave undone (killer560, 2026-10-18: "before S3 starts, during the PD phase, the aura
     * should always leave the lowest, leftmost arrow not done, so the last arrow is only completed once S3 starts"), or
     * -1 when nothing is held: the setting is off, Disable While Crouching applies, or S3 (or later) has started.
     * Chosen once per solution from the frames that need clicks: lowest row first, then the leftmost as the player sees
     * the wall (the frames face east, the player looks west, so left is the larger z).
     */
    private static int heldIndexNow() {
        ArrowAlignConfig cfg = ArrowAlignConfig.getInstance();
        if (!cfg.isAuraLeaveLastArrow() || !beforeS3()) {
            return -1;
        }
        Minecraft mc = Minecraft.getInstance();
        if (cfg.isLeaveLastArrowDisableWhileCrouching() && mc.player != null && mc.player.isShiftKeyDown()) {
            return -1;
        }
        if (heldIndex == -2) {
            int best = -1;
            for (int i = 0; i < 25; i++) {
                if (clicksNeeded(i) == 0) {
                    continue;
                }
                if (best < 0 || i % 5 < best % 5 || (i % 5 == best % 5 && i / 5 > best / 5)) {
                    best = i;
                }
            }
            heldIndex = best;
            LOGGER.info("[ArrowAlign] Leave Last Arrow: frame {} kept one click from done until S3 (needed {})", best,
                    best < 0 ? 0 : clicksNeeded(best));
        }
        return heldIndex;
    }

    /** True in a Floor 7 fight until the P3 stage tracker reaches S3 (the Predev stretch). */
    static boolean beforeS3() {
        return com.killer560.hub.fastleap.Floor7Tracker.inF7Boss()
                && com.killer560.hub.fastleap.Floor7Tracker.getStage().number < 3;
    }

    private static boolean isAuraClickable(int index, Vec3 eye, double rangeSq) {
        ItemFrame frame = frames[index];
        // To the frame's BOX, not its center. A frame is about 0.75 wide and 1/16 deep, so the center reads up
        // to ~0.53 further and the effective aura range was nearer 2.5 than the 3.0 the config clamps to -
        // frames the server would have accepted looked out of range.
        return frame != null && !frame.isRemoved() && auraClicksLeft(index) > 0
                && com.killer560.hub.util.BlockHits.boxDistanceSq(eye, frame.getBoundingBox()) <= rangeSq;
    }

    /**
     * Clicks the Aura may still send to a frame: all it needs, except the Leave Last Arrow frame, which is taken to ONE
     * click from done and left there (killer560, 2026-10-19: "leave the last arrow one click from being done"), so the
     * device completes on a single click once S3 starts. Until 2026-10-19 that frame got no clicks at all.
     */
    private static int auraClicksLeft(int index) {
        int needed = clicksNeeded(index);
        return index == heldIndexNow() ? Math.max(0, needed - 1) : needed;
    }

    // ------------------------------------------------------------------
    // Rendering (solver)
    // ------------------------------------------------------------------

    private static void onWorldRender(LevelRenderContext context) {
        ArrowAlignConfig cfg = ArrowAlignConfig.getInstance();
        if (!active || solution == null || !cfg.isSolverEnabled()) {
            return;
        }
        boolean highlight = cfg.isHighlightFrames();
        float[] highlightRgba = highlight ? WorldRenderUtils.argbToFloats(cfg.getHighlightColor()) : null;
        for (int i = 0; i < 25; i++) {
            ItemFrame frame = frames[i];
            if (frame == null) {
                continue;
            }
            int needed = clicksNeeded(i);
            AABB box = frame.getBoundingBox();
            // killer560, 2026-09-21: "have the highlight frames only highlight ones that aren't complete and make
            // that color of highlight customizable." A finished frame draws nothing at all now (it used to keep a
            // faint green box), so what is left to click is the only thing lit up. "Complete" counts clicks already
            // in flight, the same value the number uses, so box and number disappear on the same click rather than
            // the box lingering until the server's entity-data update lands.
            if (highlight && needed > 0) {
                WorldRenderUtils.renderFilledBox(context, box.inflate(0.01),
                        highlightRgba[0], highlightRgba[1], highlightRgba[2], highlightRgba[3]);
            }
            if (needed > 0) {
                renderNumber(context, box.maxX + 0.02, (box.minY + box.maxY) / 2.0, (box.minZ + box.maxZ) / 2.0,
                        needed, colorFor(needed), cfg.getNumberScale());
            }
        }
    }

    /** Odin/NoammAddons dynamic colors: under 3 green, under 5 gold, else red. */
    private static int colorFor(int clicks) {
        return clicks < 3 ? COLOR_GREEN : clicks < 5 ? COLOR_GOLD : COLOR_RED;
    }

    /** Flat text on the frame face, facing +X (out of the wall toward the device stand). Same transform as
     *  SimonSaysFeature.renderNumber, mirrored: the camera orientation for a player looking due west (yaw 90) is
     *  rotationYXZ(PI - PI/2, 0, 0), then (+s, -s, +s) - see that method for why not (-s, -s, s). */
    private static void renderNumber(LevelRenderContext context, double worldX, double worldY, double worldZ,
                                     int number, int argb, float scaleMultiplier) {
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        Vec3 cam = McRender.cameraPos(context);
        String text = String.valueOf(number);
        float scale = 0.03f * scaleMultiplier;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(new org.joml.Quaternionf().rotationYXZ((float) (Math.PI / 2.0), 0f, 0f));
        poseStack.scale(scale, -scale, scale);
        float width = font.width(text);
        McRender.drawText(context, font, text, -width / 2f, -font.lineHeight / 2f, argb, false, poseStack, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        poseStack.popPose();
    }
}
