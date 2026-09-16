package com.killer560.hub.pathfinding;

import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Collects every unfound Fairy Soul on the island by itself. CHEAT BUILD ONLY
 * ({@code BuildVariant.CHEAT_FEATURES_ENABLED}), behind its own default-off "Auto Fairy Souls" toggle.
 * <p>
 * Route order comes from {@link FairySoulsFeature} (nearest-neighbour + 2-opt/Or-opt over real graph distances).
 * Getting to each soul depends on the mode:
 * <ul>
 * <li>{@code WALK} - walk the graph path the whole way. An etherwarp is only used when walking cannot do it: the
 * walker is stuck, the graph cannot reach the soul at all, or the graph path ends more than 5 blocks from it (a soul
 * up on a ledge).</li>
 * <li>{@code ETHERWARP} - walk, but every half second look ahead along the path for an etherwarp that skips at least
 * {@link #SHORTCUT_MIN_SAVING} blocks of walking, and take it.</li>
 * <li>{@code FAST_ETHERWARP} - plan a whole etherwarp chain to each soul with the Interactive Map's A* and run it,
 * falling back to the ETHERWARP behaviour for that leg when no chain exists (unloaded chunks, nothing to land on).</li>
 * </ul>
 * Stops instantly on player movement, mouse movement, a click, a screen, damage, a world change, or when nothing has
 * improved for a few seconds and no etherwarp rescue is possible.
 */
public final class AutoSoulRunner {

    private static final String CHAT = "Auto Fairy Souls";
    private static final double COLLECT_DISTANCE = 4.5;
    private static final double SHORTCUT_MIN_SAVING = 15.0;
    private static final double SOUL_SEARCH_RADIUS = 6.0;
    private static final int HOP_TIMEOUT_TICKS = 120;
    private static final int COLLECT_TIMEOUT_TICKS = 80;
    private static final int MAX_ATTEMPTS_PER_SOUL = 3;

    private enum State {
        IDLE, WALKING, ETHER, COLLECTING
    }

    private static State state = State.IDLE;
    private static boolean active;
    private static int soulIndexMarker = -1;
    private static int attempts;
    private static int waitTicks;
    private static int shortcutCooldown;
    private static boolean chainTriedForSoul;
    private static boolean warnedNoItem;

    private AutoSoulRunner() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void start() {
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (!cfg.isAutoSouls()) {
            ModChat.send(CHAT, ModChat.bad("Auto Fairy Souls is off "),
                    ModChat.dim("(settings: New > Pathfinding, cheat build only)"));
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        active = true;
        state = State.IDLE;
        attempts = 0;
        waitTicks = 0;
        chainTriedForSoul = false;
        soulIndexMarker = -1;
        warnedNoItem = false;
        AutoWalker.startSession();
        if (!FairySoulsFeature.isGuiding()) {
            FairySoulsFeature.start(cfg.getSoulMode());
            if (!FairySoulsFeature.isGuiding()) {
                // The graph is still downloading - don't report the island finished (2026-09-16 review).
                active = false;
                AutoWalker.endSession("no soul route yet");
                return;
            }
        }
        ModChat.send(CHAT, ModChat.text("Started in mode "), ModChat.value(cfg.getAutoMode().label),
                ModChat.dim(" - any key press, click or mouse move stops it."));
    }

    public static void stop(String reason, boolean announce) {
        if (!active) {
            return;
        }
        active = false;
        state = State.IDLE;
        AutoWalker.endSession(reason);
        EtherwarpHopper.cancel();
        if (announce) {
            ModChat.send(CHAT, ModChat.bad("Stopped"), reason == null ? ModChat.text(".") : ModChat.dim(" - " + reason));
        }
    }

    /** Called by the walker when it shut its session down (player input, damage, screen, ...). */
    static void onWalkerStopped(String reason) {
        if (active) {
            active = false;
            state = State.IDLE;
            EtherwarpHopper.cancel();
            ModChat.send(CHAT, ModChat.bad("Stopped"), ModChat.dim(" - " + reason
                    + " (press the resume key or run /k560path souls auto)"));
        }
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(Minecraft client) {
        if (!active) {
            return;
        }
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        LocalPlayer player = client.player;
        if (player == null || !cfg.isAutoSouls()) {
            stop("auto fairy souls turned off", false);
            return;
        }
        if (!AutoWalker.isSessionActive()) {
            stop(AutoWalker.stopReason(), true);
            return;
        }
        if (IslandDetector.graphIsland() == null) {
            // Without a graph island NavigationManager stops its target every tick while tickTravel starts a new one:
            // that ping-pongs two chat lines per tick. Stop once instead.
            stop("no navigation graph for this area", true);
            return;
        }
        if (FairySoulsFeature.isRouting()) {
            AutoWalker.setWalking(false);
            return;
        }
        IslandGraph.Node soul = FairySoulsFeature.currentSoul();
        if (soul == null) {
            AutoWalker.setWalking(false);
            stop(null, false);
            ModChat.send(CHAT, ModChat.good("Done - every known soul on this island is collected."));
            return;
        }
        if (soul.index != soulIndexMarker) {
            soulIndexMarker = soul.index;
            attempts = 0;
            chainTriedForSoul = false;
            state = State.IDLE;
            waitTicks = 0;
        }
        Vec3 soulCentre = NavigationManager.centre(soul);
        double distance = player.position().distanceTo(soulCentre);

        switch (state) {
            case ETHER -> tickEther(client, player, soulCentre);
            case COLLECTING -> tickCollect(client, player, soul, soulCentre, distance);
            default -> tickTravel(client, player, cfg, soul, soulCentre, distance);
        }
    }

    private static void tickTravel(Minecraft client, LocalPlayer player, PathfindingConfig cfg, IslandGraph.Node soul,
                                   Vec3 soulCentre, double distance) {
        if (distance <= COLLECT_DISTANCE) {
            AutoWalker.setWalking(false);
            state = State.COLLECTING;
            waitTicks = 0;
            return;
        }
        if (!NavigationManager.isActive()) {
            FairySoulsFeature.navigateToCurrent();
        }
        state = State.WALKING;
        AutoWalker.setWalking(true);

        boolean hasItem = EtherwarpHopper.hotbarItem() != null;
        if (!hasItem && !warnedNoItem && cfg.getAutoMode() != PathfindingConfig.AutoMode.WALK) {
            warnedNoItem = true;
            EtherwarpHopper.warnNoItem();
        }
        if (!hasItem || EtherwarpHopper.isBusy()) {
            handleStuckWithoutEther(cfg);
            return;
        }

        if (cfg.getAutoMode() == PathfindingConfig.AutoMode.FAST_ETHERWARP && !chainTriedForSoul && player.onGround()) {
            chainTriedForSoul = true;
            BlockPos goal = chainGoal(player, soulCentre);
            if (goal != null) {
                AutoWalker.setWalking(false);
                state = State.ETHER;
                waitTicks = 0;
                EtherwarpHopper.chain(goal, () -> state = State.IDLE, () -> state = State.IDLE);
                return;
            }
        }

        if (AutoWalker.isStuck()) {
            AutoWalker.clearStuck();
            if (!rescueHop(player, soulCentre)) {
                attempts++;
                if (attempts >= MAX_ATTEMPTS_PER_SOUL) {
                    ModChat.send(CHAT, ModChat.bad("Could not reach a soul"), ModChat.dim(" - skipping it."));
                    FairySoulsFeature.skipCurrent();
                }
            }
            return;
        }

        boolean wantShortcuts = cfg.getAutoMode() == PathfindingConfig.AutoMode.ETHERWARP
                || cfg.getAutoMode() == PathfindingConfig.AutoMode.FAST_ETHERWARP;
        if (wantShortcuts && player.onGround() && --shortcutCooldown <= 0) {
            shortcutCooldown = 10;
            if (tryShortcut(player)) {
                AutoWalker.setWalking(false);
                state = State.ETHER;
                waitTicks = 0;
            }
        }
    }

    private static void handleStuckWithoutEther(PathfindingConfig cfg) {
        if (!AutoWalker.isStuck()) {
            return;
        }
        AutoWalker.clearStuck();
        attempts++;
        if (attempts >= MAX_ATTEMPTS_PER_SOUL) {
            ModChat.send(CHAT, ModChat.bad("Stuck on the way to a soul"), ModChat.dim(" - skipping it."));
            FairySoulsFeature.skipCurrent();
        }
    }

    private static void tickEther(Minecraft client, LocalPlayer player, Vec3 soulCentre) {
        AutoWalker.setWalking(false);
        if (EtherwarpHopper.isBusy()) {
            if (++waitTicks > HOP_TIMEOUT_TICKS) {
                EtherwarpHopper.cancel();
                state = State.IDLE;
                waitTicks = 0;
            }
            return;
        }
        AutoWalker.rebaseCamera();
        Vec3 landing = EtherwarpHopper.expectedLanding();
        if (landing != null && player.position().distanceTo(landing) > 4.0) {
            attempts++;
        }
        state = State.IDLE;
        waitTicks = 0;
        NavigationManager.recalculate(true);
    }

    private static void tickCollect(Minecraft client, LocalPlayer player, IslandGraph.Node soul, Vec3 soulCentre,
                                    double distance) {
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        AutoWalker.setWalking(false);
        if (distance > COLLECT_DISTANCE + 2.0) {
            state = State.IDLE;
            return;
        }
        if (!cfg.isAutoCollect()) {
            return; // the player clicks the soul themselves; the chat line moves the queue on
        }
        ArmorStand stand = findSoulEntity(client, soulCentre);
        if (stand == null) {
            if (++waitTicks > COLLECT_TIMEOUT_TICKS) {
                waitTicks = 0;
                ModChat.send(CHAT, ModChat.bad("No Fairy Soul entity here"), ModChat.dim(" - skipping it."));
                FairySoulsFeature.skipCurrent();
            }
            return;
        }
        Vec3 aim = stand.position().add(0, 1.0, 0);
        AutoWalker.lookAt(aim);
        if (AutoWalker.aimError() > 3f) {
            return;
        }
        if (waitTicks % 20 == 0) {
            client.gameMode.interact(player, stand, new EntityHitResult(stand, aim), InteractionHand.MAIN_HAND);
        }
        if (++waitTicks > COLLECT_TIMEOUT_TICKS) {
            waitTicks = 0;
            attempts++;
            if (attempts >= MAX_ATTEMPTS_PER_SOUL) {
                ModChat.send(CHAT, ModChat.bad("Clicking a soul did nothing"), ModChat.dim(" - skipping it."));
                FairySoulsFeature.skipCurrent();
            }
        }
    }

    // ------------------------------------------------------------------ etherwarp helpers

    /** The soul's own landing spot when it is within range, else the furthest reachable spot along the path. */
    private static BlockPos chainGoal(LocalPlayer player, Vec3 soulCentre) {
        BlockPos direct = EtherwarpHopper.landingSpotNear(soulCentre);
        Vec3 pos = player.position();
        if (direct != null && pos.distanceToSqr(Vec3.atCenterOf(direct)) < 110 * 110) {
            return direct;
        }
        List<Vec3> path = NavigationManager.path();
        BlockPos best = null;
        for (int i = path.size() - 1; i >= 0; i--) {
            Vec3 point = path.get(i);
            if (pos.distanceToSqr(point) > 100 * 100) {
                continue;
            }
            BlockPos spot = EtherwarpHopper.landingSpotNear(point);
            if (spot != null) {
                best = spot;
                break;
            }
        }
        return best != null ? best : direct;
    }

    /** ETHERWARP mode: the furthest point along the path this hop can reach, if it saves enough walking. */
    private static boolean tryShortcut(LocalPlayer player) {
        List<Vec3> path = NavigationManager.path();
        if (path.size() < 2) {
            return false;
        }
        Vec3 pos = player.position();
        int seg = NavigationManager.closestSegment(pos);
        double along = 0;
        Vec3 previous = NavigationManager.projectOnSegment(pos, path.get(seg), path.get(seg + 1));
        BlockPos bestSpot = null;
        double bestSaving = SHORTCUT_MIN_SAVING;
        for (int i = seg + 1; i < path.size(); i++) {
            Vec3 point = path.get(i);
            along += previous.distanceTo(point);
            previous = point;
            if (along < SHORTCUT_MIN_SAVING) {
                continue;
            }
            if (pos.distanceToSqr(point) > 60 * 60) {
                continue;
            }
            BlockPos spot = EtherwarpHopper.landingSpotNear(point);
            if (spot == null) {
                continue;
            }
            TeleportUtils.Rotation dir = EtherwarpHopper.directionTo(spot);
            if (dir == null) {
                continue;
            }
            double saving = along - dir.distance();
            if (saving > bestSaving) {
                bestSaving = saving;
                bestSpot = spot;
            }
        }
        return bestSpot != null && EtherwarpHopper.hop(bestSpot, () -> state = State.IDLE);
    }

    /** WALK mode's last resort: one hop as close to the soul as possible. */
    private static boolean rescueHop(LocalPlayer player, Vec3 soulCentre) {
        BlockPos spot = EtherwarpHopper.landingSpotNear(soulCentre);
        if (spot != null && EtherwarpHopper.directionTo(spot) != null) {
            AutoWalker.setWalking(false);
            state = State.ETHER;
            waitTicks = 0;
            return EtherwarpHopper.hop(spot, () -> state = State.IDLE);
        }
        if (tryShortcut(player)) {
            AutoWalker.setWalking(false);
            state = State.ETHER;
            waitTicks = 0;
            return true;
        }
        return false;
    }

    /** The soul itself: Hypixel renders it as a small armour stand wearing a head, right next to the graph node. */
    private static ArmorStand findSoulEntity(Minecraft client, Vec3 soulCentre) {
        if (client.level == null) {
            return null;
        }
        AABB box = new AABB(soulCentre.x - SOUL_SEARCH_RADIUS, soulCentre.y - SOUL_SEARCH_RADIUS,
                soulCentre.z - SOUL_SEARCH_RADIUS, soulCentre.x + SOUL_SEARCH_RADIUS,
                soulCentre.y + SOUL_SEARCH_RADIUS, soulCentre.z + SOUL_SEARCH_RADIUS);
        ArmorStand best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity entity : client.level.getEntities(null, box)) {
            if (!(entity instanceof ArmorStand stand)) {
                continue;
            }
            if (stand.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                continue;
            }
            double d = stand.position().distanceToSqr(soulCentre);
            if (d < bestDist) {
                bestDist = d;
                best = stand;
            }
        }
        return best;
    }
}
