package com.killer560.hub.arrowalign;

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
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod/ArrowAlign");

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
    private static boolean active = false;
    private static ClientLevel lastLevel = null;

    // --- solve timer ---
    private static long firstClickMs = 0L;
    private static int clicksThisSolve = 0;
    private static boolean solvedAnnounced = false;

    // --- automation ---
    private static boolean syntheticClickInProgress = false;
    private static int triggerAimIndex = -1;
    private static long triggerAimSinceMs = 0L;
    private static long lastTriggerClickMs = 0L;
    private static long nextAuraClickAtMs = 0L;
    private static int lastAuraIndex = -1;

    static {
        Arrays.fill(observed, -1);
    }

    private ArrowAlignFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(ArrowAlignFeature::tick);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(ArrowAlignFeature::onWorldRender);
        // Per-frame automation so Trigger Bot / Aura delays are honoured to the millisecond, not the 50ms tick
        // (same reason SimonSaysFeature fires its Trigger Bot from this hook).
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> tickAutomation(Minecraft.getInstance()));
    }

    // ------------------------------------------------------------------
    // Tick: scan frames, confirm pending clicks, match layout
    // ------------------------------------------------------------------

    private static void tick(Minecraft client) {
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
                reset();
            }
            return;
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
                frames[i] = null;
                observed[i] = -1;
                pending[i] = 0;
                continue;
            }
            int rotation = frame.getRotation() & 7;
            if (frames[i] == null || frames[i].getId() != frame.getId() || observed[i] < 0) {
                frames[i] = frame;
                observed[i] = rotation;
                pending[i] = 0;
                continue;
            }
            frames[i] = frame;
            if (rotation != observed[i]) {
                int delta = (rotation - observed[i]) & 7;
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
            if (match != null) {
                LOGGER.info("[ArrowAlign] Layout matched ({} clicks needed).", totalServerClicksNeeded());
            }
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
                // Device reset / re-randomised after a solve - arm a fresh attempt.
                solvedAnnounced = false;
                firstClickMs = 0L;
                clicksThisSolve = 0;
            }
            return;
        }
        if (serverSolved && firstClickMs > 0L) {
            solvedAnnounced = true;
            long took = now - firstClickMs;
            LOGGER.info("[ArrowAlign] Solved in {}ms ({} clicks sent).", took, clicksThisSolve);
            if (cfg.isSolveTimeEnabled()) {
                ModChat.send("Arrow Align",
                        ModChat.text("Solved in "),
                        ModChat.value(String.format(Locale.US, "%.2fs", took / 1000.0)),
                        ModChat.dim(" (" + clicksThisSolve + " clicks)"));
            }
        }
    }

    private static void reset() {
        Arrays.fill(frames, null);
        Arrays.fill(observed, -1);
        Arrays.fill(pending, 0);
        Arrays.fill(lastClickMs, 0L);
        solution = null;
        active = false;
        firstClickMs = 0L;
        clicksThisSolve = 0;
        solvedAnnounced = false;
        triggerAimIndex = -1;
        lastAuraIndex = -1;
        nextAuraClickAtMs = 0L;
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
            return true;
        }
        noteClick(index, System.currentTimeMillis());
        return false;
    }

    /** Real interact on a frame through the same path a vanilla right-click takes
     *  ({@code gameMode.interact(player, entity, hitResult, hand)} - sends the interact packet with the hit location
     *  relative to the entity). Callers must already be behind a cheat-gated config getter. */
    private static boolean sendClick(Minecraft client, int index, long now) {
        ItemFrame frame = frames[index];
        if (frame == null || frame.isRemoved() || client.player == null || client.gameMode == null) {
            return false;
        }
        // Mod-wide one-interaction-per-tick gate (killer560, 2026-09-20: "make sure every type of aura has some
        // sort of coordination"). Trigger Bot and Aura are both allowed to run on the same tick above, so this is
        // also what stops THIS feature putting two interact packets in one tick. A denial must leave the caller's
        // delay/target bookkeeping untouched so the same frame is simply clicked a tick later.
        if (!com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.ARROW_ALIGN)) {
            return false;
        }
        AABB box = frame.getBoundingBox();
        // Centre of the east (+X, player-facing) face - where a real crosshair ray lands on the frame.
        Vec3 hit = new Vec3(box.maxX, (box.minY + box.maxY) / 2.0, (box.minZ + box.maxZ) / 2.0);
        syntheticClickInProgress = true;
        try {
            client.gameMode.interact(client.player, frame, new EntityHitResult(frame, hit), InteractionHand.MAIN_HAND);
        } finally {
            syntheticClickInProgress = false;
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        noteClick(index, now);
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
            triggerAimIndex = -1;
            return;
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
        if (index != triggerAimIndex) {
            triggerAimIndex = index;
            triggerAimSinceMs = now;
        }
        long delay = cfg.getTriggerBotDelayMs();
        if (now - triggerAimSinceMs < delay || now - lastTriggerClickMs < delay) {
            return;
        }
        if (!sendClick(client, index, now)) {
            return; // gate held this tick back - nothing sent, so the aim timer and delay stay where they are
        }
        lastTriggerClickMs = now;
    }

    private static void tickAura(Minecraft client, ArrowAlignConfig cfg, long now) {
        if (now < nextAuraClickAtMs) {
            return;
        }
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getAuraRange() * cfg.getAuraRange();
        int target = -1;
        if (lastAuraIndex >= 0 && isAuraClickable(lastAuraIndex, eye, rangeSq)) {
            target = lastAuraIndex; // finish the frame it's on before moving along
        } else {
            for (int i = 0; i < 25; i++) {
                if (isAuraClickable(i, eye, rangeSq)) {
                    target = i;
                    break;
                }
            }
        }
        if (target < 0) {
            lastAuraIndex = -1;
            return;
        }
        if (!sendClick(client, target, now)) {
            return; // gate held this tick back - the rolled delay must not be re-rolled for a click never sent
        }
        lastAuraIndex = target;
        int min = cfg.getAuraMinDelayMs();
        int max = Math.max(min, cfg.getAuraMaxDelayMs());
        nextAuraClickAtMs = now + (max > min ? ThreadLocalRandom.current().nextInt(min, max + 1) : min);
    }

    private static boolean isAuraClickable(int index, Vec3 eye, double rangeSq) {
        ItemFrame frame = frames[index];
        return frame != null && !frame.isRemoved() && clicksNeeded(index) > 0
                && eye.distanceToSqr(frame.getBoundingBox().getCenter()) <= rangeSq;
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

    /** Odin/NoammAddons dynamic colours: under 3 green, under 5 gold, else red. */
    private static int colorFor(int clicks) {
        return clicks < 3 ? COLOR_GREEN : clicks < 5 ? COLOR_GOLD : COLOR_RED;
    }

    /** Flat text on the frame face, facing +X (out of the wall toward the device stand). Same transform as
     *  SimonSaysFeature.renderNumber, mirrored: the camera orientation for a player looking due west (yaw 90) is
     *  rotationYXZ(PI - PI/2, 0, 0), then (+s, -s, +s) - see that method for why not (-s, -s, s). */
    private static void renderNumber(LevelRenderContext context, double worldX, double worldY, double worldZ,
                                     int number, int argb, float scaleMultiplier) {
        var bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        Vec3 cam = client.gameRenderer.getMainCamera().position();
        String text = String.valueOf(number);
        float scale = 0.03f * scaleMultiplier;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(new org.joml.Quaternionf().rotationYXZ((float) (Math.PI / 2.0), 0f, 0f));
        poseStack.scale(scale, -scale, scale);
        float width = font.width(text);
        font.drawInBatch(text, -width / 2f, -font.lineHeight / 2f, argb, false, poseStack.last().pose(),
                bufferSource, Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        poseStack.popPose();
    }
}
