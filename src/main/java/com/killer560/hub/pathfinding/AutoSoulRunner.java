package com.killer560.hub.pathfinding;

import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ViewFreeze;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collects every unfound Fairy Soul on the island by itself. CHEAT BUILD ONLY
 * ({@code BuildVariant.CHEAT_FEATURES_ENABLED}), behind the Auto Fairy Souls switch, and independent of the Fairy Souls
 * tab's own switch (killer560, 2026-10-17): it loads the island graph and runs the soul route itself.
 * <p>
 * Route order comes from {@link FairySoulsFeature} (nearest-neighbour + 2-opt/Or-opt over real graph distances), always
 * the whole island. Getting to each soul depends on the mode:
 * <ul>
 * <li>{@code WALK} - walking only, never an etherwarp.</li>
 * <li>{@code ETHERWARP} - walk, but every half second look ahead along the path for an etherwarp that skips at least
 * {@link #SHORTCUT_MIN_SAVING} blocks of walking, and take it.</li>
 * <li>{@code FAST_ETHERWARP} - plan a whole etherwarp chain to each soul with the Interactive Map's A* and run it,
 * falling back to the ETHERWARP behaviour for that leg when no chain exists.</li>
 * </ul>
 * When the walker has no way back onto the route on foot ({@link AutoWalker#needsEscape}: an enclosed pit, a hole with
 * no climb out), the cheapest allowed escape is taken (killer560, 2026-10-17: "it should be able to get out with things
 * like pearls or maybe etherwarping if their setting is on"): an etherwarp in an etherwarp mode with an etherwarp item
 * in the hotbar, else an Ender Pearl in Walk With Pearls mode and a pearl in the hotbar, aimed at the route or the
 * soul. With neither it says so once and moves on to the next soul.
 * <p>
 * Stops on any key or mouse button he presses ({@link AutoWalker}), a screen, or a failsafe (a world change or
 * disconnect). Damage carries on, knockback and death re-path; a server correction or a server-set rotation never
 * stops it.
 */
public final class AutoSoulRunner {

    private static final String CHAT = "Auto Fairy Souls";
    /**
     * How close to stand before clicking a soul: the entity reach the anticheat enforces, eye to the box
     * ({@code CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH}, 3.0). The walk aims to stop {@link AutoWalker#STOP_SHORT}
     * from it, well inside.
     */
    private static final double COLLECT_DISTANCE = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
    private static final double REACH = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
    private static final double SHORTCUT_MIN_SAVING = 15.0;
    private static final double SOUL_SEARCH_RADIUS = 6.0;
    private static final int MAX_CLICKS_PER_SOUL = 3;
    /** The longest wait for the island graph to download before the run gives up. */
    private static final long GRAPH_WAIT_MS = 30_000L;

    private enum State {
        IDLE, WAIT_GRAPH, WALKING, ETHER, PEARL, COLLECTING
    }

    private static State state = State.IDLE;
    private static boolean active;
    private static int soulIndexMarker = -1;
    private static int shortcutCooldown;
    private static boolean chainTriedForSoul;
    private static boolean warnedNoItem;
    private static long graphWaitStartMs;
    /** Ticks with no soul stand found in reach. */
    private static int noStandTicks;
    /** The soul clicks sent for the current soul, and when the last one went (ActionGate tick). */
    private static int clicksForSoul;
    private static long lastClickTick = Long.MIN_VALUE / 4;
    /** Ticks the body has been held still on the soul (see {@link #tickCollect}). */
    private static int lockedTicks;
    /** The run holds the camera for the click itself (Auto Walk off: no session-long free camera). */
    private static boolean clickViewHeld;
    /** An escape (etherwarp or pearl) is under way; when it ends the walker re-plans from the landing. */
    private static boolean escaping;
    private static boolean saidNoWayOut;
    /** Soul clicks sent this session (the testkit reads it). */
    private static int clicksSent;
    /** Escapes taken (testkit): etherwarps, pearls, "no way out" skips. */
    private static int escapesEther;
    private static int escapesPearl;
    private static int noWayOuts;
    /** Souls given up on this run (killer560, 2026-09-21: never call a run with skipped souls "Done"). */
    private static int skippedThisRun;

    private AutoSoulRunner() {
    }

    public static int clicksSent() {
        return clicksSent;
    }

    public static int escapesEther() {
        return escapesEther;
    }

    public static int escapesPearl() {
        return escapesPearl;
    }

    public static int noWayOuts() {
        return noWayOuts;
    }

    public static boolean isActive() {
        return active;
    }

    public static void start() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        if (!cfg.isAutoSouls()) {
            ModChat.send(CHAT, ModChat.bad("Auto Fairy Souls is off "),
                    ModChat.dim("(/killer560 > Misc > Auto Fairy Souls, cheat build only)"));
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || active) {
            return; // already running: a second start would drop the held click view without releasing it
        }
        active = true;
        state = State.WAIT_GRAPH;
        graphWaitStartMs = System.currentTimeMillis();
        chainTriedForSoul = false;
        soulIndexMarker = -1;
        warnedNoItem = false;
        skippedThisRun = 0;
        saidNoWayOut = false;
        escaping = false;
        clickViewHeld = false;
        AutoWalker.startSession();
        ModChat.send(CHAT, ModChat.text("Started in mode "), ModChat.value(cfg.getAutoMode().label),
                ModChat.dim(" (" + AutoWalker.jumpSummary(client.player) + ") - any key or click stops it."));
        tickWaitGraph(client);
    }

    public static void stop(String reason, boolean announce) {
        if (!active) {
            return;
        }
        active = false;
        state = State.IDLE;
        releaseClickView(Minecraft.getInstance().player);
        AutoWalker.endSession(reason);
        EtherwarpHopper.cancel();
        EnderPearlHopper.cancel();
        stopGuideUnlessWanted();
        if (announce) {
            ModChat.send(CHAT, ModChat.bad("Stopped"), reason == null ? ModChat.text(".") : ModChat.dim(" - " + reason));
        }
    }

    /** The guide this run started goes with it, unless the Fairy Souls feature itself is on (then it keeps guiding). */
    private static void stopGuideUnlessWanted() {
        if (!PathfindingConfig.getInstance().isFairySouls()) {
            FairySoulsFeature.stopGuide(false);
        }
    }

    /** Called by the walker when it shut its session down (his key, a screen, a failsafe). */
    static void onWalkerStopped(String reason) {
        if (active) {
            active = false;
            state = State.IDLE;
            releaseClickView(Minecraft.getInstance().player);
            EtherwarpHopper.cancel();
            EnderPearlHopper.cancel();
            stopGuideUnlessWanted();
            ModChat.send(CHAT, ModChat.bad("Stopped"), ModChat.dim(" - " + reason));
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
            stop("Auto Fairy Souls turned off", true);
            return;
        }
        if (!AutoWalker.isSessionActive()) {
            stop(AutoWalker.stopReason(), true);
            return;
        }
        if (AutoWalker.isRespawning()) {
            return; // dead: nothing to walk to or click until the respawn (AutoWalker re-plans then)
        }
        if (state == State.WAIT_GRAPH) {
            tickWaitGraph(client);
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
            int skipped = skippedThisRun;
            stop(null, false);
            if (skipped > 0) {
                // Not the same as "done": these are still logged as unfound and will be re-queued next run.
                ModChat.send(CHAT, ModChat.text("Stopped - "), ModChat.value(String.valueOf(skipped)),
                        ModChat.text(" soul" + (skipped == 1 ? "" : "s") + " could not be reached "),
                        ModChat.dim("(still logged as unfound - everything else on this island is collected)."));
            } else {
                ModChat.send(CHAT, ModChat.good("Done - every known soul on this island is collected."));
            }
            return;
        }
        if (soul.index != soulIndexMarker) {
            soulIndexMarker = soul.index;
            chainTriedForSoul = false;
            clicksForSoul = 0;
            noStandTicks = 0;
            lockedTicks = 0;
            if (!escaping) {
                state = State.IDLE;
            }
            AutoWalker.newTarget();
        }
        Vec3 soulCentre = NavigationManager.centre(soul);
        double distance = player.position().distanceTo(soulCentre);

        switch (state) {
            case ETHER -> tickEther(player);
            case PEARL -> tickPearl();
            case COLLECTING -> tickCollect(client, player, soulCentre, distance);
            default -> tickTravel(player, cfg, soulCentre, distance);
        }
    }

    /** Waits for the island graph (it may never have been loaded with Fairy Souls off), then starts the route. */
    private static void tickWaitGraph(Minecraft client) {
        String island = IslandDetector.graphIsland();
        if (island == null) {
            if (System.currentTimeMillis() - graphWaitStartMs > 3_000L) {
                stop("no navigation graph for this area", true);
            }
            return;
        }
        GraphRepository.ensureLoading(island, false);
        if (GraphRepository.get(island) == null) {
            if (System.currentTimeMillis() - graphWaitStartMs > GRAPH_WAIT_MS) {
                String error = GraphRepository.lastError(island);
                stop("the " + island + " graph did not load" + (error == null ? "" : " (" + error + ")"), true);
            }
            return;
        }
        if (!FairySoulsFeature.isGuiding()) {
            FairySoulsFeature.start(PathfindingConfig.SoulMode.ROUTE);
            if (!FairySoulsFeature.isGuiding()) {
                stop("nothing to collect here", false);
                return;
            }
        }
        state = State.IDLE;
    }

    private static void tickTravel(LocalPlayer player, PathfindingConfig cfg, Vec3 soulCentre, double distance) {
        if (distance <= COLLECT_DISTANCE) {
            AutoWalker.setWalking(false);
            state = State.COLLECTING;
            noStandTicks = 0;
            lockedTicks = 0;
            return;
        }
        if (!NavigationManager.isActive()) {
            FairySoulsFeature.navigateToCurrent();
        }
        state = State.WALKING;
        AutoWalker.setWalking(true);

        if (AutoWalker.needsEscape()) {
            escape(player, cfg, soulCentre);
            return;
        }
        boolean etherMode = cfg.getAutoMode().etherwarps();
        boolean hasItem = EtherwarpHopper.hotbarItem() != null;
        if (etherMode && !hasItem && !warnedNoItem) {
            warnedNoItem = true;
            EtherwarpHopper.warnNoItem();
        }
        if (!etherMode || !hasItem || EtherwarpHopper.isBusy() || AutoWalker.isRescuing()) {
            return;
        }
        if (cfg.getAutoMode() == PathfindingConfig.AutoMode.FAST_ETHERWARP && !chainTriedForSoul && player.onGround()) {
            chainTriedForSoul = true;
            BlockPos goal = chainGoal(player, soulCentre);
            if (goal != null) {
                AutoWalker.setWalking(false);
                state = State.ETHER;
                EtherwarpHopper.chain(goal, () -> state = State.IDLE, () -> state = State.IDLE);
                return;
            }
        }
        if (player.onGround() && --shortcutCooldown <= 0) {
            shortcutCooldown = 10;
            if (tryShortcut(player)) {
                AutoWalker.setWalking(false);
                state = State.ETHER;
            }
        }
    }

    /**
     * No way back on foot: an etherwarp if the mode allows one and the item is there, else an Ender Pearl if allowed and
     * in the hotbar, aimed at the route ahead or the soul; else say so (once a run) and move on to the next soul.
     */
    private static void escape(LocalPlayer player, PathfindingConfig cfg, Vec3 soulCentre) {
        if (EtherwarpHopper.isBusy() || EnderPearlHopper.isBusy()) {
            return;
        }
        List<Vec3> targets = escapeTargets(soulCentre);
        if (cfg.getAutoMode().etherwarps() && EtherwarpHopper.hotbarItem() != null) {
            for (Vec3 t : targets) {
                BlockPos spot = EtherwarpHopper.landingSpotNear(t);
                if (spot != null && EtherwarpHopper.directionTo(spot) != null) {
                    AutoWalker.setWalking(false);
                    if (EtherwarpHopper.hop(spot, () -> state = State.IDLE)) {
                        escaping = true;
                        escapesEther++;
                        state = State.ETHER;
                        ModChat.log(CHAT, ModChat.text("Escaping by etherwarp "), ModChat.dim("(" + AutoWalker.escapeWhy() + ")"));
                        return;
                    }
                }
            }
            // Nothing on the route is in sight (a pit's own rim hides it): a block in sight that the route can be
            // walked to from - an etherwarp lands on top of whatever block it hits, so the rim of a pit is one.
            BlockPos ring = ringLanding(player);
            if (ring != null && EtherwarpHopper.hop(ring, () -> state = State.IDLE)) {
                AutoWalker.setWalking(false);
                escaping = true;
                escapesEther++;
                state = State.ETHER;
                ModChat.log(CHAT, ModChat.text("Escaping by etherwarp "), ModChat.dim("(" + AutoWalker.escapeWhy()
                        + ", onto " + ring.toShortString() + ")"));
                return;
            }
        }
        if (cfg.isAllowPearls() && EnderPearlHopper.hasPearl()) {
            for (Vec3 t : targets) {
                if (EnderPearlHopper.hop(t, () -> state = State.IDLE)) {
                    AutoWalker.setWalking(false);
                    escaping = true;
                    escapesPearl++;
                    state = State.PEARL;
                    ModChat.log(CHAT, ModChat.text("Escaping by Ender Pearl "), ModChat.dim("(" + AutoWalker.escapeWhy() + ")"));
                    return;
                }
            }
        }
        noWayOuts++;
        String tools = cfg.getAutoMode().etherwarps() || cfg.isAllowPearls()
                ? "no etherwarp or pearl lands anywhere useful" : "etherwarp and pearls are not allowed";
        if (!saidNoWayOut) {
            saidNoWayOut = true;
            ModChat.send(CHAT, ModChat.bad("No way out "), ModChat.dim("(" + AutoWalker.escapeWhy() + "; " + tools
                    + ") - skipping this soul."));
        } else {
            ModChat.log(CHAT, ModChat.bad("No way out again"), ModChat.dim(" - skipping this soul."));
        }
        skippedThisRun++;
        AutoWalker.escaped();
        FairySoulsFeature.skipCurrent();
    }

    /**
     * The nearest block around him (up to 6 out, up to 8 above his feet) that an etherwarp lands on, that he can see,
     * and from which the route is walkable ({@link LocalPlanner}); or null. At most {@link #RING_PLANS} plans.
     */
    private static BlockPos ringLanding(LocalPlayer player) {
        net.minecraft.world.level.Level level = player.level();
        BlockPos feet = player.blockPosition();
        List<BlockPos> goals = AutoWalker.routeGoals(level, player.position(), feet);
        if (goals.isEmpty()) {
            return null;
        }
        double jumpH = WalkTerrain.jumpHeight(player);
        int plans = 0;
        for (int r = 1; r <= 6; r++) {
            for (int dy = 8; dy >= 0; dy--) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        BlockPos b = feet.offset(dx, dy - 1, dz);
                        if (!TeleportUtils.etherwarpable(b) || !LocalPlanner.cell(level, b.above())
                                || b.above().getY() < feet.getY() + 2) {
                            continue;
                        }
                        if (EtherwarpHopper.directionTo(b) == null) {
                            continue;
                        }
                        if (plans++ >= RING_PLANS) {
                            return null;
                        }
                        if (LocalPlanner.plan(level, b.above(), goals, jumpH).size() >= 2
                                || LocalPlanner.isGoal(b.above(), goals)) {
                            return b;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static final int RING_PLANS = 12;

    /** Where an escape may land: the route from its end (the soul) backward, nearest the soul first. */
    private static List<Vec3> escapeTargets(Vec3 soulCentre) {
        List<Vec3> out = new ArrayList<>();
        out.add(new Vec3(soulCentre.x, soulCentre.y - 0.5, soulCentre.z));
        List<Vec3> path = NavigationManager.path();
        for (int i = path.size() - 1; i >= 0; i--) {
            Vec3 p = path.get(i);
            out.add(new Vec3(p.x, p.y - 0.5, p.z));
        }
        return out;
    }

    private static void tickEther(LocalPlayer player) {
        AutoWalker.setWalking(false);
        if (EtherwarpHopper.isBusy()) {
            return; // ClearExecutor times out a hop it never sees land, and its server-lag-aware wait ends this
        }
        AutoWalker.rebaseCamera();
        Vec3 landing = EtherwarpHopper.expectedLanding();
        if (landing != null && player.position().distanceTo(landing) > 4.0) {
            ModChat.log(CHAT, ModChat.bad("Etherwarp landed off its spot"), ModChat.dim(" - walking on from here."));
        }
        endEscape();
        state = State.IDLE;
        NavigationManager.recalculate(true);
    }

    /** Waits on {@link EnderPearlHopper} (ticked from {@link PathfindingFeature}) to land the pearl or give up. */
    private static void tickPearl() {
        AutoWalker.setWalking(false);
        if (EnderPearlHopper.isBusy()) {
            return;
        }
        AutoWalker.rebaseCamera();
        endEscape();
        state = State.IDLE;
        NavigationManager.recalculate(true);
    }

    private static void endEscape() {
        if (escaping) {
            escaping = false;
            AutoWalker.escaped();
        }
    }

    /**
     * Clicks the soul: the body turns onto a point on its box (the camera stays held), holds still, and the click goes
     * once a movement packet has reported that look - through ActionGate, within the 3.0 entity reach measured eye to
     * box, and only when the held look's ray really meets the box. Clicked again only if the server has not answered
     * in about a round trip and a half.
     */
    private static void tickCollect(Minecraft client, LocalPlayer player, Vec3 soulCentre, double distance) {
        PathfindingConfig cfg = PathfindingConfig.getInstance();
        AutoWalker.setWalking(false);
        if (distance > COLLECT_DISTANCE + 2.0) {
            releaseClickView(player);
            state = State.IDLE;
            return;
        }
        if (!cfg.isAutoCollect()) {
            return; // he clicks the soul himself; the chat line moves the queue on
        }
        ArmorStand stand = findSoulEntity(client, soulCentre);
        if (stand == null) {
            if (++noStandTicks > 40) {
                noStandTicks = 0;
                releaseClickView(player);
                ModChat.log(CHAT, ModChat.bad("No Fairy Soul entity here"), ModChat.dim(" - skipping it."));
                skippedThisRun++;
                FairySoulsFeature.skipCurrent();
            }
            return;
        }
        noStandTicks = 0;
        if (!AutoWalker.holdsView()) {
            // Auto Walk is off: hold the camera just for this click, so only the body turns to the soul.
            clickViewHeld = true;
            ViewFreeze.hold(player.getYRot(), player.getXRot());
        }
        Vec3 eye = player.getEyePosition();
        AABB box = stand.getBoundingBox();
        if (com.killer560.hub.util.BlockHits.boxDistanceSq(eye, box) > REACH * REACH) {
            // Out of reach from here (the soul sits up on something): a step closer.
            if (cfg.isAutoWalk()) {
                AutoWalker.setWalking(true);
                state = State.WALKING;
            }
            return;
        }
        // The look the server last heard, if he held still: does its ray meet the box?
        Vec3 look = lookOf(player);
        Optional<Vec3> held = rayHit(eye, look, box);
        if (WalkCamera.isLocked() && held.isPresent()) {
            lockedTicks++;
        } else {
            lockedTicks = 0;
            Vec3 centre = box.getCenter();
            Optional<Vec3> aim = box.contains(eye) ? Optional.of(centre) : box.clip(eye, eye.add(centre.subtract(eye).normalize().scale(eye.distanceTo(centre) + 1.0)));
            if (aim.isEmpty()) {
                return;
            }
            AutoWalker.lookAt(aim.get());
            if (box.contains(eye) || AutoWalker.aimError() <= 1.0f) {
                Vec3 nowLook = lookOf(player);
                if (rayHit(eye, nowLook, box).isPresent()) {
                    WalkCamera.lock(); // this tick's movement packet carries the look; the click goes next tick
                }
            }
            return;
        }
        if (lockedTicks < 1) {
            return;
        }
        long now = ActionGate.currentTick();
        long retryTicks = Math.max(10, (long) Math.ceil(com.killer560.hub.util.PingTracker.automationMs() * 1.5 / 50.0) + 6);
        if (clicksForSoul > 0 && now - lastClickTick < retryTicks) {
            return;
        }
        if (clicksForSoul >= MAX_CLICKS_PER_SOUL) {
            ModChat.log(CHAT, ModChat.bad("Clicking a soul did nothing"), ModChat.dim(" - skipping it."));
            releaseClickView(player);
            skippedThisRun++;
            FairySoulsFeature.skipCurrent();
            return;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.FAIRY_SOUL)) {
            return;
        }
        client.gameMode.interact(player, stand, new EntityHitResult(stand, held.get()), InteractionHand.MAIN_HAND);
        clicksSent++;
        clicksForSoul++;
        lastClickTick = now;
    }

    /** Where a ray from the eye along {@code look} meets the box within reach - the eye itself when it is inside the
     *  box (standing right on a soul: its 2-block-tall stand contains a 1.62 eye; testkit 1017-fairy-overshoot). */
    private static Optional<Vec3> rayHit(Vec3 eye, Vec3 look, AABB box) {
        if (box.contains(eye)) {
            return Optional.of(eye);
        }
        return box.clip(eye, eye.add(look.scale(REACH + 1.0)));
    }

    /** The unit look of his BODY's rotation (what the movement packets report), vanilla's {@code calculateViewVector}. */
    private static Vec3 lookOf(LocalPlayer player) {
        double pitch = Math.toRadians(player.getXRot());
        double yaw = Math.toRadians(-player.getYRot());
        double cp = Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * cp, -Math.sin(pitch), Math.cos(yaw) * cp);
    }

    /** Auto Walk off: the camera held for a click goes back, body turned under the view first. */
    private static void releaseClickView(LocalPlayer player) {
        if (!clickViewHeld) {
            return;
        }
        clickViewHeld = false;
        float viewYaw = ViewFreeze.viewYaw();
        float viewPitch = ViewFreeze.viewPitch();
        if (player != null && !Float.isNaN(viewYaw)) {
            float yaw = player.getYRot() + Mth.wrapDegrees(viewYaw - player.getYRot());
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(Mth.clamp(viewPitch, -90f, 90f));
        }
        ViewFreeze.release();
        WalkCamera.clear();
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

    /** The soul itself: Hypixel renders it as an armour stand wearing a head, right next to the graph node. */
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
