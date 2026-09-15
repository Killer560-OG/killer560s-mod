package com.killer560.hub.i4sensors;

import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sharp Shooter (i4) automation - killer560's "Next lets work on the autoi4 portion" request (2026-09-14),
 * with the same Rotate / No Rotate split Simon Says has ("for i4 again it needs a rotate and no rotate
 * setting"). Cheat build only (see {@link I4SensorsConfig#isAutoI4Enabled}).
 * <p>
 * Device logic ported from NoammAddons' own confirmed {@code AutoI4.kt}/{@code I4Helper.kt} (cloned at
 * C:\Users\Hunter\noammaddonsmod), not guessed: while standing on the device (Noamm's isOnDev) holding a bow,
 * whenever one of the 9 wall targets ({@link I4SensorsFeature#DEV_BLOCKS}) turns {@code EMERALD_BLOCK},
 * aim at Noamm's own per-column aim point (x 67.5 / 65.5 - between columns, so a Terminator's side arrow
 * catches the neighbour - y = 131 - 2*row, z 50) and shoot. With Predictions on, immediately follows up with
 * a shot at a likely next target (Noamm's getPredictionTarget - prefers horizontally adjacent pairs of still-
 * unhit blocks) so the arrow is already in the air when it lights. A target that stays lit 1s after the last
 * shot gets re-shot (Noamm's watchdog). A target turning {@code BLUE_TERRACOTTA} is done. Stops on the
 * player's own "completed a device!" line or an armor stand near the wall renamed "Active".
 * <p>
 * Rotate: eases the real camera to the aim point over Rotation Time (Noamm's easeInOutCubic) every render
 * frame, then fires. No Rotate: sends the aim rotation to the server with the shot only - the camera
 * never moves. Per this mod's standing rotation rule, yaw is never wrapped/clamped: every target yaw is
 * the current running yaw plus the wrapped difference.
 * <p>
 * Not ported yet (Noamm has them, not asked for): timed rod swap / mask swap / leap.
 * Every decision logs under [AutoI4] (alongside [I4Sensors]' wall/arrow/hit data) for sim testing.
 */
public final class AutoI4Feature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-autoi4");
    private static final String TAG = "[AutoI4]";

    private static final Pattern DEVICE_DONE = Pattern.compile("^(\\w{3,16}) completed a device! \\(\\d/\\d\\)");
    private static final AABB STAND_BOX = new AABB(56, 118, 40, 76, 140, 58);
    private static final long RESHOOT_AFTER_MS = 1000L;
    private static final float ALREADY_AIMED_TOLERANCE_DEG = 1f;
    // Never two shots closer than this - Noamm's own effective cadence (it forces 170ms of rotation per shot
    // with Predictions on), and stops No Rotate from firing a target + its prediction in the same tick.
    private static final long MIN_SHOT_GAP_MS = 170L;

    private static final Set<BlockPos> doneTargets = new HashSet<>();
    private static final Map<BlockPos, BlockState> lastWall = new HashMap<>();
    private static final Map<BlockPos, Integer> predictionCounts = new HashMap<>();
    private static BlockPos activeTarget = null;
    private static long lastShotAtActiveMs = 0L;
    private static boolean completed = false;
    private static boolean wasRunning = false;
    private static String lastGateReason = "";

    // Shot queue: the current shot (aiming in progress in Rotate mode) plus at most one queued prediction.
    private static final List<BlockPos> shotQueue = new ArrayList<>();
    private static Shot currentShot = null;
    private static int shotsFired = 0;
    private static long lastFireAtMs = 0L;
    // Armor stand names seen near the wall - completion only counts on a real RENAME to "Active", so a stand
    // still reading "Active" from a previous attempt (p3sim restarts) can't instantly re-complete a new one.
    private static final Map<Integer, String> standNames = new HashMap<>();

    private static final class Shot {
        final BlockPos target;
        final boolean prediction;
        final Vec3 aimPoint;
        final float startYaw;
        final float startPitch;
        final float targetYaw;
        final float targetPitch;
        final long startedAtMs;
        final long durationMs;

        Shot(BlockPos target, boolean prediction, Vec3 aimPoint, float startYaw, float startPitch,
             float targetYaw, float targetPitch, long durationMs) {
            this.target = target;
            this.prediction = prediction;
            this.aimPoint = aimPoint;
            this.startYaw = startYaw;
            this.startPitch = startPitch;
            this.targetYaw = targetYaw;
            this.targetPitch = targetPitch;
            this.startedAtMs = System.currentTimeMillis();
            this.durationMs = durationMs;
        }
    }

    private AutoI4Feature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> frame());
        // Real bug found and fixed (2026-09-14, real Hypixel F7 log): Fabric's CHAT/GAME events never fired for
        // the real "completed a device!" line with Odin's Terminal Splits installed (it cancels the line via
        // ALLOW_GAME and re-adds its own copy straight to ChatComponent), so completion-by-chat never worked.
        // ChatObserver sees both paths, de-duplicated. (DEVICE_DONE has no end-anchor, so Odin's suffix is fine.)
        ChatObserver.subscribe(AutoI4Feature::onChat);
    }

    private static void onChat(Component message) {
        if (!wasRunning || completed) {
            return;
        }
        String plain = ChatFormatting.stripFormatting(message.getString());
        if (plain == null) {
            return;
        }
        Matcher m = DEVICE_DONE.matcher(plain);
        Minecraft client = Minecraft.getInstance();
        if (m.find() && client.player != null && m.group(1).equals(client.player.getGameProfile().name())) {
            markCompleted("own \"completed a device\" line: \"" + plain + "\"");
        }
    }

    // ------------------------------------------------------------------
    // Tick - gating, wall tracking, shot planning, No Rotate firing
    // ------------------------------------------------------------------

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();
        LocalPlayer player = client.player;
        String gate = gateReason(client, cfg, player);
        boolean running = gate.isEmpty();
        if (!gate.equals(lastGateReason)) {
            LOGGER.info("{} {} {}", TAG, I4SensorsFeature.clock(), running
                    ? "RUNNING (on device, holding bow) mode=" + (cfg.isAutoI4Rotate() ? "Rotate" : "No Rotate")
                    + " rotationTime=" + cfg.getAutoI4RotationTimeMs() + "ms predictions=" + cfg.isAutoI4Predictions()
                    : "idle: " + gate);
            lastGateReason = gate;
        }
        boolean pauseOnly = !running && (gate.startsWith("not holding a bow") || gate.equals("a screen is open"));
        if (!running && !pauseOnly) {
            if (wasRunning) {
                // Left the device, leapt, disconnected, or turned it off - the next attempt starts clean.
                resetDevice(gate);
            }
            currentShot = null;
            shotQueue.clear();
            wasRunning = false;
            return;
        }
        // Real bug found and fixed (2026-09-14 review pass): swapping off the bow (e.g. to a rod) or opening a menu
        // used to stop wall tracking and re-snapshot on return, so a hit + new light during the pause were never
        // seen and the device stalled on a dead target. A pause now keeps tracking, only shooting stops; the wall
        // is only freshly snapshotted after a real reset.
        if (lastWall.isEmpty()) {
            snapshotWall(client);
            snapshotStands(client);
        }
        wasRunning = true;

        trackWall(client);
        checkActiveStand(client);
        if (completed) {
            return;
        }
        if (pauseOnly) {
            currentShot = null;
            return;
        }

        long now = System.currentTimeMillis();
        // Re-shoot a target that's still lit well after the last shot at it (Noamm's watchdog).
        if (activeTarget != null && currentShot == null && shotQueue.isEmpty()
                && now - lastShotAtActiveMs >= RESHOOT_AFTER_MS && isLit(client, activeTarget)) {
            LOGGER.info("{} {} Watchdog: target #{} still lit {}ms after last shot - re-shooting.", TAG,
                    I4SensorsFeature.clock(), indexOf(activeTarget), now - lastShotAtActiveMs);
            shotQueue.add(activeTarget);
        }
        if (currentShot == null && !shotQueue.isEmpty()) {
            startShot(client, cfg, player, shotQueue.remove(0));
        }
        if (currentShot != null && !cfg.isAutoI4Rotate() && now - lastFireAtMs >= MIN_SHOT_GAP_MS) {
            fireNoRotate(client, player, currentShot);
            currentShot = null;
        }
    }

    private static String gateReason(Minecraft client, I4SensorsConfig cfg, LocalPlayer player) {
        if (!cfg.isAutoI4Enabled()) {
            return "Auto i4 off";
        }
        if (player == null || client.level == null || client.gameMode == null) {
            return "no player/level";
        }
        if (!I4SensorsFeature.isOnDungeonServer(client)) {
            return "not on hypixel.net/p3sim.net";
        }
        if (client.screen != null) {
            return "a screen is open";
        }
        Vec3 p = player.position();
        boolean onDev = Math.abs(p.y - 127.0) < 0.5 && p.x >= 62.0 && p.x <= 65.0 && p.z >= 34.0 && p.z <= 37.0;
        if (!onDev) {
            return "not on device";
        }
        if (!player.getMainHandItem().is(Items.BOW)) {
            return "not holding a bow (held " + I4SensorsFeature.itemDesc(player.getMainHandItem()) + ")";
        }
        return "";
    }

    private static void snapshotWall(Minecraft client) {
        lastWall.clear();
        for (BlockPos pos : I4SensorsFeature.DEV_BLOCKS) {
            BlockState state = client.level.getBlockState(pos);
            lastWall.put(pos, state);
            String id = I4SensorsFeature.blockId(state);
            if (id.equals("emerald_block") && activeTarget == null) {
                setActiveTarget(pos, "already lit when Auto i4 started");
            }
        }
    }

    private static void trackWall(Minecraft client) {
        // Two passes (2026-09-14 review pass): record every hit first, THEN handle new lights - otherwise a block
        // lit earlier in the loop than a same-tick hit could pick that just-hit block as its prediction.
        List<BlockPos> lit = new ArrayList<>();
        Map<BlockPos, String> litFrom = new HashMap<>();
        for (BlockPos pos : I4SensorsFeature.DEV_BLOCKS) {
            BlockState state = client.level.getBlockState(pos);
            BlockState old = lastWall.put(pos, state);
            if (old == null || old == state) {
                continue;
            }
            String from = I4SensorsFeature.blockId(old);
            String to = I4SensorsFeature.blockId(state);
            if (from.equals("emerald_block") && to.equals("blue_terracotta")) {
                doneTargets.add(pos);
                LOGGER.info("{} {} Target #{} HIT ({} of 9 done).", TAG, I4SensorsFeature.clock(), indexOf(pos), doneTargets.size());
                if (pos.equals(activeTarget)) {
                    activeTarget = null;
                }
                shotQueue.remove(pos);
                // Already hit (usually by an earlier prediction arrow) - don't finish aiming at / firing on it.
                if (currentShot != null && currentShot.target.equals(pos)) {
                    currentShot = null;
                }
            } else if (to.equals("emerald_block")) {
                lit.add(pos);
                litFrom.put(pos, from);
            }
        }
        for (BlockPos pos : lit) {
            if (completed || doneTargets.contains(pos)) {
                // A finished device lighting up again (p3sim restart without leaving the device).
                resetDevice("target #" + indexOf(pos) + " lit again after "
                        + (completed ? "completion" : "being hit") + " - new attempt");
                snapshotWall(client);
                snapshotStands(client);
            }
            setActiveTarget(pos, "lit (" + litFrom.get(pos) + " -> emerald_block)");
        }
    }

    private static void setActiveTarget(BlockPos pos, String why) {
        activeTarget = pos;
        lastShotAtActiveMs = 0L;
        LOGGER.info("{} {} New target #{} {} - {}. Interrupting {}.", TAG, I4SensorsFeature.clock(), indexOf(pos), pos, why,
                currentShot == null ? "nothing" : "shot at #" + indexOf(currentShot.target) + (currentShot.prediction ? " (prediction)" : ""));
        // A freshly lit target always takes priority over whatever was being aimed at (Noamm's getEmerald
        // retarget), and gets a fresh prediction after it.
        currentShot = null;
        shotQueue.clear();
        shotQueue.add(pos);
        if (I4SensorsConfig.getInstance().isAutoI4Predictions()) {
            BlockPos prediction = predictNext(pos);
            if (prediction != null) {
                shotQueue.add(prediction);
            }
        }
    }

    private static String standName(Entity entity) {
        Component name = entity.getCustomName();
        String plain = name == null ? null : ChatFormatting.stripFormatting(name.getString());
        return plain == null ? "" : plain.trim();
    }

    private static void snapshotStands(Minecraft client) {
        standNames.clear();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ArmorStand && STAND_BOX.contains(entity.position())) {
                standNames.put(entity.getId(), standName(entity));
            }
        }
    }

    private static void checkActiveStand(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) || !STAND_BOX.contains(entity.position())) {
                continue;
            }
            String name = standName(entity);
            String old = standNames.put(entity.getId(), name);
            if (old != null && !old.equals("Active") && name.equals("Active") && !completed) {
                markCompleted("armor stand at " + I4SensorsFeature.fmt(entity.position()) + " renamed \"" + old + "\" -> \"Active\"");
            }
        }
    }

    private static void markCompleted(String why) {
        if (completed) {
            return;
        }
        completed = true;
        currentShot = null;
        shotQueue.clear();
        LOGGER.info("{} {} Device COMPLETED ({}) - {} shots fired, {} of 9 targets seen hit. Stopping.", TAG,
                I4SensorsFeature.clock(), why, shotsFired, doneTargets.size());
    }

    private static void resetDevice(String why) {
        if (!doneTargets.isEmpty() || shotsFired > 0 || completed) {
            LOGGER.info("{} {} Reset ({}) - had {} shots, {} hits, completed={}.", TAG, I4SensorsFeature.clock(), why,
                    shotsFired, doneTargets.size(), completed);
        }
        doneTargets.clear();
        lastWall.clear();
        predictionCounts.clear();
        activeTarget = null;
        lastShotAtActiveMs = 0L;
        completed = false;
        shotsFired = 0;
        shotQueue.clear();
        currentShot = null;
    }

    // ------------------------------------------------------------------
    // Aiming and firing
    // ------------------------------------------------------------------

    /** Noamm's getTargetVector: aim between columns so a Terminator's spread covers the neighbour too. */
    private static Vec3 aimPointFor(BlockPos pos) {
        int i = Math.max(0, indexOf(pos));
        int col = i % 3;
        int row = i / 3;
        List<BlockPos> dev = I4SensorsFeature.DEV_BLOCKS;
        boolean leftDone = col < 2 && doneTargets.contains(dev.get(i + 1));
        boolean rightDone = col > 0 && doneTargets.contains(dev.get(i - 1));
        double x;
        if (col == 0) {
            x = 67.5;
        } else if (col == 2) {
            x = 65.5;
        } else if (rightDone && !leftDone) {
            x = 65.5;
        } else if (leftDone && !rightDone) {
            x = 67.5;
        } else {
            x = Math.random() < 0.5 ? 65.5 : 67.5;
        }
        return new Vec3(x, 131 - 2.0 * row, 50);
    }

    private static void startShot(Minecraft client, I4SensorsConfig cfg, LocalPlayer player, BlockPos target) {
        boolean prediction = !target.equals(activeTarget);
        if (!prediction && !isLit(client, target)) {
            LOGGER.info("{} {} Skipping shot at #{} - no longer lit.", TAG, I4SensorsFeature.clock(), indexOf(target));
            return;
        }
        Vec3 aim = aimPointFor(target);
        Vec3 eye = player.getEyePosition();
        Vec3 diff = aim.subtract(eye);
        double horizontal = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float rawYaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float rawPitch = (float) -(Mth.atan2(diff.y, horizontal) * (180.0 / Math.PI));
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        // Never wrap/clamp the running yaw (this mod's standing rotation rule) - add the wrapped difference.
        float targetYaw = currentYaw + Mth.wrapDegrees(rawYaw - currentYaw);
        float targetPitch = Mth.clamp(rawPitch, -90f, 90f);
        boolean alreadyAimed = Math.abs(targetYaw - currentYaw) <= ALREADY_AIMED_TOLERANCE_DEG
                && Math.abs(targetPitch - currentPitch) <= ALREADY_AIMED_TOLERANCE_DEG;
        long duration = cfg.isAutoI4Rotate() && !alreadyAimed
                ? (cfg.isAutoI4Predictions() ? 170L : cfg.getAutoI4RotationTimeMs()) : 0L;
        currentShot = new Shot(target, prediction, aim, currentYaw, currentPitch, targetYaw, targetPitch, duration);
        LOGGER.info("{} {} Aiming at #{}{} aimPoint={} eye={} yaw {} -> {} pitch {} -> {} ({}, {}ms).", TAG,
                I4SensorsFeature.clock(), indexOf(target), prediction ? " (PREDICTION)" : "", I4SensorsFeature.fmt(aim),
                I4SensorsFeature.fmt(eye), fmt2(currentYaw), fmt2(targetYaw), fmt2(currentPitch), fmt2(targetPitch),
                cfg.isAutoI4Rotate() ? "Rotate" : "No Rotate", duration);
    }

    /** Rotate mode - runs every render frame so the turn is as smooth as real mouse look. */
    private static void frame() {
        Shot shot = currentShot;
        I4SensorsConfig cfg = I4SensorsConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        if (shot == null || !cfg.isAutoI4Rotate() || !wasRunning || completed || client.player == null
                || client.gameMode == null) {
            return;
        }
        LocalPlayer player = client.player;
        long elapsed = System.currentTimeMillis() - shot.startedAtMs;
        double progress = shot.durationMs <= 0 ? 1.0 : Math.min(1.0, elapsed / (double) shot.durationMs);
        float eased = (float) easeInOutCubic(progress);
        player.setYRot(shot.startYaw + (shot.targetYaw - shot.startYaw) * eased);
        player.setXRot(shot.startPitch + (shot.targetPitch - shot.startPitch) * eased);
        if (progress < 1.0) {
            return;
        }
        player.setYRot(shot.targetYaw);
        player.setXRot(shot.targetPitch);
        if (System.currentTimeMillis() - lastFireAtMs < MIN_SHOT_GAP_MS) {
            return; // on target - hold until the minimum gap since the previous shot has passed
        }
        InteractionResult result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        onFired(client, shot, "Rotate", result, elapsed);
        currentShot = null;
        if (!shotQueue.isEmpty()) {
            startShot(client, cfg, player, shotQueue.remove(0));
        }
    }

    /** No Rotate - the camera never moves: the aim rotation goes to the server with the shot, then the
     *  local view is put straight back. */
    private static void fireNoRotate(Minecraft client, LocalPlayer player, Shot shot) {
        float realYaw = player.getYRot();
        float realPitch = player.getXRot();
        player.connection.send(new ServerboundMovePlayerPacket.Rot(shot.targetYaw, shot.targetPitch,
                player.onGround(), player.horizontalCollision));
        // useItem's own packet carries the player's CURRENT rotation, so it must be the aim rotation for the
        // duration of this one call.
        player.setYRot(shot.targetYaw);
        player.setXRot(shot.targetPitch);
        InteractionResult result;
        try {
            result = client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        } finally {
            player.setYRot(realYaw);
            player.setXRot(realPitch);
        }
        onFired(client, shot, "No Rotate", result, 0L);
    }

    private static void onFired(Minecraft client, Shot shot, String mode, InteractionResult result, long aimMs) {
        long now = System.currentTimeMillis();
        I4SensorsFeature.noteAutoShot(now);
        long sincePrevious = lastFireAtMs > 0 ? now - lastFireAtMs : -1;
        lastFireAtMs = now;
        shotsFired++;
        if (shot.target.equals(activeTarget)) {
            lastShotAtActiveMs = System.currentTimeMillis();
        }
        LOGGER.info("{} {} FIRED shot #{} at #{}{} ({}) after {}ms aim, {}ms since previous shot - yaw={} pitch={} useItem={} targetNow={}", TAG,
                I4SensorsFeature.clock(), shotsFired, indexOf(shot.target), shot.prediction ? " (PREDICTION)" : "",
                mode, aimMs, sincePrevious, fmt2(shot.targetYaw), fmt2(shot.targetPitch), result,
                I4SensorsFeature.blockId(client.level.getBlockState(shot.target)));
    }

    /** Noamm's getPredictionTarget: prefer one of a horizontally adjacent pair of still-unhit blocks, avoid
     *  predicting the same block more than twice. */
    private static BlockPos predictNext(BlockPos lastLit) {
        Minecraft client = Minecraft.getInstance();
        List<BlockPos> valid = new ArrayList<>();
        for (BlockPos pos : I4SensorsFeature.DEV_BLOCKS) {
            if (!doneTargets.contains(pos) && !pos.equals(lastLit)
                    && I4SensorsFeature.blockId(client.level.getBlockState(pos)).equals("blue_terracotta")) {
                valid.add(pos);
            }
        }
        if (valid.isEmpty()) {
            LOGGER.info("{} {} No prediction - no unhit blue_terracotta targets left.", TAG, I4SensorsFeature.clock());
            return null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : valid) {
            if (predictionCounts.getOrDefault(pos, 0) < 2) {
                candidates.add(pos);
            }
        }
        if (candidates.isEmpty()) {
            candidates = valid;
        }
        List<BlockPos> paired = new ArrayList<>();
        for (BlockPos a : candidates) {
            for (BlockPos b : candidates) {
                if (a.getY() == b.getY() && Math.abs(a.getX() - b.getX()) == 2) {
                    paired.add(a);
                    break;
                }
            }
        }
        List<BlockPos> pool = paired.isEmpty() ? candidates : paired;
        BlockPos chosen = pool.get((int) (Math.random() * pool.size()));
        predictionCounts.merge(chosen, 1, Integer::sum);
        LOGGER.info("{} {} Prediction after #{}: #{} (from {} {} candidates).", TAG, I4SensorsFeature.clock(),
                indexOf(lastLit), indexOf(chosen), pool.size(), paired.isEmpty() ? "unpaired" : "paired");
        return chosen;
    }

    private static boolean isLit(Minecraft client, BlockPos pos) {
        return I4SensorsFeature.blockId(client.level.getBlockState(pos)).equals("emerald_block");
    }

    private static int indexOf(BlockPos pos) {
        return pos == null ? -1 : I4SensorsFeature.DEV_BLOCKS.indexOf(pos);
    }

    private static double easeInOutCubic(double t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    private static String fmt2(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
