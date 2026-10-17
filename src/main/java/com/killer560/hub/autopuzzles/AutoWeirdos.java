package com.killer560.hub.autopuzzles;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.puzzlesolvers.WeirdosSolverConfig;
import com.killer560.hub.puzzlesolvers.WeirdosSolverFeature;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Auto Three Weirdos: talks to each weirdo's "CLICK" stand (with Talk To NPCs on), then opens the chest the solver names.
 *
 * <p>QUOI's auto clicked every stand within 5.48 blocks feet to feet, which from the room's middle reaches all three.
 * Talking to an NPC is an ENTITY interaction, and the measured limit for one is 3.0 blocks eye to the stand's box
 * (docs/SIM.md), so from where a trip into the room stands him one weirdo is usually out of reach: killer560, 2026-10-15,
 * "on sim it cannot reach the right person for auto three weirdos" - two talked to, the third 3.26 off, and "waiting - no
 * un-talked CLICK stand within entity reach" every tick until he walked over himself (Autopilot: 28.9 s against 7.9).
 * Since then, when no weirdo left to talk to is in reach, the auto moves itself onto the standing spot that has as many of
 * them in reach as it can (all three in every room the sim builds), preferring one that reaches their chests too: one
 * etherwarp of its own ({@link AutoReposition}, next-tick mode - sneak and look this tick, the use the next) with Etherwarp
 * Reposition on and an AOTV in the hotbar, else a walk ({@link MazeWalk}, the forward key only) with auto-puzzle pathing
 * on. Talks go one a tick, the body turned onto the next weirdo in the same tick as each talk, so none needs a turn tick
 * of its own; and the chest likewise when the room's answer is out of block reach.
 */
final class AutoWeirdos {

    private static final Logger LOGGER = ModLog.get("killer560smod-autopuzzles");
    private static final String TAG = "Weirdos";

    /** Measured ENTITY reach (3.0), eye to the stand's box: the anticheat names the distance past it. */
    static final double NPC_REACH = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_ENTITY_REACH;
    /** A standing spot must have a weirdo this close, a little inside {@link #NPC_REACH}, so a walk's overshoot or a
     *  landing's hover still leaves it in reach. */
    static final double SPOT_NPC_REACH = 2.8;
    /** Standing eye to a chest's box from a spot, a little inside the 4.5 the server enforces. */
    static final double SPOT_CHEST_REACH = 4.3;
    /** How far (eye to box) a CLICK stand may be and still be one of this room's weirdos when the live map has no
     *  bounds for the room; with bounds, anywhere inside them counts (from the doorway the weirdos are ~21 blocks off). */
    private static final double NPC_SCAN = 16.0;
    private static final double NPC_SCAN_IN_ROOM = 48.0;
    /** Moves tried for one set of weirdos (or the chest) before the auto gives up and says so. */
    private static final int MAX_MOVES = 3;

    private static final FreeCam CAMERA = new FreeCam(TAG);
    private static final AutoReposition WARP = new AutoReposition(TAG, CAMERA, true);
    private static final MazeWalk WALK = new MazeWalk();

    private static BlockPos actedPos = null;
    private static BlockPos pendingPos = null;
    private static long pendingSinceMs = 0L;
    private static boolean predatesLogged = false;
    private static boolean solverOffWarned = false;
    /** The solver was seen empty in this world (see AutoPuzzlesFeature's review fix): its chest is this instance's. */
    private static boolean solverClearedThisLevel = false;
    private static com.killer560.hub.roomdatabase.RoomEntry lastRoom;
    private static final Set<Integer> talked = new HashSet<>();
    /** The solver held a chest or a wrong chest last tick (its going empty again is a new round). */
    private static boolean solverHadAnswer = false;

    /** Walking toward a spot: what it was for, when it started, how long it may take. */
    private static boolean walking = false;
    private static long walkStartMs = 0L;
    private static long walkTimeoutMs = 0L;
    /** The weirdos (entity ids) or the chest the current move is for; a different set is a new problem. */
    private static String moveFor = null;
    private static int moves = 0;
    /** How many weirdos the current walk must have in reach before it stops (as many as its spot reaches). */
    private static int walkGoal = 0;

    private static String loggedWait = null;

    private AutoWeirdos() {
    }

    /** Every tick, whatever the toggles, so the solver's empty state is seen (the predates-this-world guard). */
    static void observeSolver() {
        // A latch: once set it stays set until the next world, so the solver is only asked while it is not (fps-auto-1015).
        if (!solverClearedThisLevel && WeirdosSolverFeature.getCorrectChestPos() == null
                && WeirdosSolverFeature.getWrongChestCount() == 0) {
            solverClearedThisLevel = true;
        }
    }

    static void levelChanged(Minecraft client) {
        reset(client);
        solverOffWarned = false;
        solverClearedThisLevel = false;
        CAMERA.drop();
    }

    static void tick(Minecraft client, boolean inRoom) {
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        LocalPlayer player = client.player;
        if (!cfg.isAutoWeirdosEnabled() || !inRoom) {
            if (WARP.isActive() || walking || CAMERA.isEngaged()) {
                stopEverything(client, inRoom ? "Auto Three Weirdos switched off" : "left the room");
            }
            pendingPos = null;
            predatesLogged = false;
            if (!inRoom) {
                solverOffWarned = false;
            }
            return;
        }
        if (!WeirdosSolverConfig.getInstance().isEnabled()) {
            if (!solverOffWarned) {
                solverOffWarned = true;
                ModChat.send(AutoPuzzlesFeature.CHAT, ModChat.text("Auto Three Weirdos needs "),
                        ModChat.value("Weirdos Solver"), ModChat.text(" turned on."));
            }
            return;
        }
        com.killer560.hub.roomdatabase.RoomEntry room = LiveMapFeature.currentRoomEntry();
        if (room != lastRoom) {
            // The weirdos talked to belong to ONE room: entity ids are not reused across rooms, and a set kept from the
            // first Three Weirdos of a run left the second with nobody to talk to.
            lastRoom = room;
            talked.clear();
            moveFor = null;
            moves = 0;
        }
        CAMERA.keep(player);

        BlockPos chest = WeirdosSolverFeature.getCorrectChestPos();
        boolean solverEmpty = chest == null && WeirdosSolverFeature.getWrongChestCount() == 0;
        if (solverEmpty && solverHadAnswer) {
            // The solver forgot this room's answer: a new round (the sim deals one after a wrong chest, or a rebuilt
            // room). The weirdos speak again, so they are talked to again.
            talked.clear();
            moveFor = null;
            moves = 0;
        }
        solverHadAnswer = !solverEmpty;
        // A weirdo that is no longer in the world (the room was rebuilt, or he walked out of its range) is not one
        // talked to: the stands there now are new ones.
        talked.removeIf(id -> client.level.getEntity(id) == null);
        if (chest == null) {
            // Solver reset (dungeon left / boss / world change) - a later correct chest is a new room.
            actedPos = null;
            pendingPos = null;
        }
        if (actedPos != null) {
            return; // once per room
        }
        if (WARP.isActive()) {
            WARP.tick(client);
            if (WARP.isActive()) {
                AutoPuzzleActivity.note(TAG);
                return;
            }
            // Landed this tick: only get ready for what comes next (the sneak is let go in this tick) - the body turns
            // onto the next weirdo or the chest now, so the talk or click goes the very next tick with no turn tick.
            readyBody(client, player, chest);
            return;
        }
        boolean solved = chest != null && WeirdosSolverFeature.getWrongChestCount() >= 2;
        if (!solved) {
            if (!cfg.isWeirdosTalkToNpcs()) {
                stopWalking(client, "talk to NPCs is off");
                waitFor("the solver has no correct chest yet (" + WeirdosSolverFeature.getWrongChestCount()
                        + " wrong chest(s) known, talk to NPCs is off)");
                return;
            }
            if (McCompat.screen(client) != null) {
                stopWalking(client, "a screen opened");
                return;
            }
            talk(client, player);
            return;
        }
        if (!solverClearedThisLevel) {
            if (!predatesLogged) {
                predatesLogged = true;
                LOGGER.info("[AutoPuzzles] Weirdos: chest {} predates this world - not opening until the solver resets", chest);
            }
            return;
        }
        openChest(client, player, chest, cfg);
    }

    // ------------------------------------------------------------------ talking

    private static void talk(Minecraft client, LocalPlayer player) {
        if (talked.size() >= 3) {
            stopWalking(client, "all three talked to");
            waitFor("all three talked to - the solver has " + WeirdosSolverFeature.getWrongChestCount()
                    + " of 2 wrong chests" + (WeirdosSolverFeature.getCorrectChestPos() == null ? " and no correct chest yet" : ""));
            return;
        }
        List<Entity> left = untalkedStands(client, player);
        if (left.isEmpty()) {
            stopWalking(client, "no weirdo left to talk to");
            waitFor("no un-talked CLICK stand in the room (" + talked.size() + " of 3 talked to)");
            return;
        }
        Vec3 eye = player.getEyePosition();
        List<Entity> inReach = new ArrayList<>();
        for (Entity e : left) {
            if (BlockHits.boxDistanceSq(eye, e.getBoundingBox()) <= NPC_REACH * NPC_REACH) {
                inReach.add(e);
            }
        }
        if (walking) {
            // Walk until as many weirdos as the spot was chosen for are in reach, then talk from there, one a tick.
            if (inReach.size() < walkGoal && !inReach.containsAll(left)) {
                tickWalk(client, player, "talking to the weirdos");
                return;
            }
            stopWalking(client, inReach.size() + " weirdo(s) in reach");
        }
        if (!inReach.isEmpty()) {
            if (player.isShiftKeyDown()) {
                waitFor("sneaking (a landing's sneak is let go this tick)");
                return;
            }
            Entity best = nearest(eye, inReach);
            if (!AutoPuzzleUtil.gateWorldClick(client, best)) {
                return; // gate held this tick back (or the body was turned to it) - the weirdo stays un-talked
            }
            // A point ON the stand's box, not the stand's own position: the server measures the reported hit, and the
            // entity's origin is a point at its feet half a block below the box a real click meets.
            AABB box = best.getBoundingBox();
            Vec3 aim = box.clip(eye, box.getCenter()).orElse(box.getCenter());
            client.gameMode.interact(player, best, new EntityHitResult(best, aim), InteractionHand.MAIN_HAND);
            player.swing(InteractionHand.MAIN_HAND);
            talked.add(best.getId());
            loggedWait = null;
            LOGGER.info("[AutoPuzzles] Weirdos: talked to '{}' at {} ({} of 3, {} blocks eye to box)",
                    ChatFormatting.stripFormatting(best.getName().getString()), best.blockPosition().toShortString(),
                    talked.size(), String.format(java.util.Locale.ROOT, "%.2f",
                            Math.sqrt(BlockHits.boxDistanceSq(eye, box))));
            // No gap: in this same tick the body turns onto the next weirdo in reach (its talk goes next tick with no
            // turn of its own), or the move toward the ones out of reach starts (sneak and look now, the use next tick).
            inReach.remove(best);
            left.remove(best);
            if (!inReach.isEmpty()) {
                AutoPuzzleUtil.aimBodyAt(player, nearest(eye, inReach).getBoundingBox(), CAMERA);
            } else if (!left.isEmpty()) {
                moveToWeirdos(client, player, left);
            }
            return;
        }
        moveToWeirdos(client, player, left);
    }

    /** Every un-talked "CLICK" stand of this room: inside the live map's bounds for it, or within {@link #NPC_SCAN}. */
    private static List<Entity> untalkedStands(Minecraft client, LocalPlayer player) {
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        Vec3 eye = player.getEyePosition();
        List<Entity> out = new ArrayList<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand) || talked.contains(entity.getId())) {
                continue;
            }
            double scan = bounds == null ? NPC_SCAN : NPC_SCAN_IN_ROOM;
            if (BlockHits.boxDistanceSq(eye, entity.getBoundingBox()) > scan * scan) {
                continue;
            }
            if (bounds != null && (entity.getX() < bounds[0] || entity.getX() > bounds[2] + 1
                    || entity.getZ() < bounds[1] || entity.getZ() > bounds[3] + 1)) {
                continue;
            }
            String stripped = ChatFormatting.stripFormatting(entity.getName().getString());
            if (stripped != null && stripped.contains("CLICK")) {
                out.add(entity);
            }
        }
        return out;
    }

    private static Entity nearest(Vec3 eye, List<Entity> list) {
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : list) {
            double d = BlockHits.boxDistanceSq(eye, e.getBoundingBox());
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ moving into reach

    /**
     * None of {@code left} is in reach: onto the spot that reaches the most of them (and their chests), by one etherwarp
     * or a walk. Asked every tick while that holds; a running walk is carried on by {@link #talk}.
     */
    private static void moveToWeirdos(Minecraft client, LocalPlayer player, List<Entity> left) {
        StringBuilder key = new StringBuilder("npc");
        List<Integer> ids = new ArrayList<>();
        for (Entity e : left) {
            ids.add(e.getId());
        }
        java.util.Collections.sort(ids);
        for (int id : ids) {
            key.append(':').append(id);
        }
        List<AABB> boxes = new ArrayList<>();
        for (Entity e : left) {
            boxes.add(e.getBoundingBox());
        }
        List<BlockPos> chests = chestsBeside(client, left);
        String what = left.size() + " weirdo(s) left to talk to, none within " + NPC_REACH + " blocks";
        List<Spot> found = spots(client, player, boxes, chests, SPOT_NPC_REACH);
        Spot spot = found.isEmpty() ? null : found.get(0);
        if (spot == null) {
            waitFor(what + " - and no standable spot in the room has any of them within " + SPOT_NPC_REACH);
            return;
        }
        walkGoal = spot.reached;
        move(client, player, key.toString(), found, what, left.size(), chests.size());
    }

    /** The chests standing next to these weirdos (one block over, any side, at the stand's height or one either way):
     *  the solver's answer is one of them, so a spot that reaches them too saves a second move for the chest. */
    private static List<BlockPos> chestsBeside(Minecraft client, List<Entity> stands) {
        List<BlockPos> out = new ArrayList<>();
        for (Entity e : stands) {
            BlockPos at = e.blockPosition();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos p = at.offset(dx, dy, dz);
                        BlockState s = client.level.getBlockState(p);
                        if ((s.is(Blocks.CHEST) || s.is(Blocks.TRAPPED_CHEST)) && !out.contains(p)) {
                            out.add(p.immutable());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** A standing spot: the block stood on, his standing eye there, and how many targets it reaches. */
    private record Spot(BlockPos floor, Vec3 eye, int reached, int chests, double distSq) {
    }

    /**
     * Blocks to stand on that have at least one of {@code boxes} within {@code reach} of the standing eye, best first: the
     * most of {@code boxes}, then the most of {@code chests} within {@link #SPOT_CHEST_REACH}, then the nearest to him;
     * inside the live map's room, never inside a weirdo. Read off the world, so the same answer on Hypixel and in the sim.
     */
    private static List<Spot> spots(Minecraft client, LocalPlayer player, List<AABB> boxes, List<BlockPos> chests,
                                    double reach) {
        List<Spot> out = new ArrayList<>();
        if (boxes.isEmpty()) {
            return out;
        }
        int idx = LiveMapFeature.currentRoomIndex();
        int[] bounds = idx < 0 ? null : LiveMapFeature.roomWorldBounds(idx);
        AABB all = boxes.get(0);
        for (AABB b : boxes) {
            all = all.minmax(b);
        }
        int r = (int) Math.ceil(reach) + 1;
        int minX = (int) Math.floor(all.minX) - r;
        int maxX = (int) Math.floor(all.maxX) + r;
        int minZ = (int) Math.floor(all.minZ) - r;
        int maxZ = (int) Math.floor(all.maxZ) + r;
        int minY = (int) Math.floor(all.minY) - 4;
        int maxY = (int) Math.floor(all.maxY) + 1;
        double reachSq = reach * reach;
        double chestSq = SPOT_CHEST_REACH * SPOT_CHEST_REACH;
        Vec3 me = player.position();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (bounds != null && (x < bounds[0] || x > bounds[2] || z < bounds[1] || z > bounds[3])) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    double stand = MazeWalk.standHeight(client.level, x, y + 1, z);
                    if (Double.isNaN(stand) || stand < y + 1 - 1.0e-6 || stand > y + 1.5) {
                        continue; // his feet in the block over this one: a floor, a slab or carpet on it, nothing taller
                    }
                    AABB body = new AABB(x + 0.2, stand, z + 0.2, x + 0.8, stand + 1.8, z + 0.8);
                    boolean inStand = false;
                    for (AABB b : boxes) {
                        inStand |= b.intersects(body);
                    }
                    if (inStand) {
                        continue; // not standing inside a weirdo
                    }
                    Vec3 eye = new Vec3(x + 0.5, stand + AutoPuzzleUtil.EYE_STANDING, z + 0.5);
                    int reached = 0;
                    for (AABB b : boxes) {
                        if (BlockHits.boxDistanceSq(eye, b) <= reachSq) {
                            reached++;
                        }
                    }
                    if (reached == 0) {
                        continue;
                    }
                    int chestCount = 0;
                    for (BlockPos c : chests) {
                        if (BlockHits.boxDistanceSq(eye, c) <= chestSq) {
                            chestCount++;
                        }
                    }
                    out.add(new Spot(new BlockPos(x, y, z), eye, reached, chestCount,
                            me.distanceToSqr(x + 0.5, stand, z + 0.5)));
                }
            }
        }
        out.sort((a, b) -> a.reached != b.reached ? Integer.compare(b.reached, a.reached)
                : a.chests != b.chests ? Integer.compare(b.chests, a.chests) : Double.compare(a.distSq, b.distSq));
        return out;
    }

    /**
     * Starts the move onto the best of {@code found}: an etherwarp of the auto's own when Etherwarp Reposition is on, an
     * AOTV is in the hotbar and a warp can land on a spot as good as the best (as many targets, as many chests); else a
     * walk to the best when auto-puzzle pathing is on. At most {@link #MAX_MOVES} for one problem ({@code key}).
     * {@code targets}/{@code chestCount}: how many there are, for the log line.
     */
    private static void move(Minecraft client, LocalPlayer player, String key, List<Spot> found, String what,
                             int targets, int chestCount) {
        Spot spot = found.get(0);
        if (!key.equals(moveFor)) {
            moveFor = key;
            moves = 0;
        }
        if (com.killer560.hub.livemap.autoclear.ClearExecutor.isBusy()) {
            waitFor(what + " - the Interactive Map is still moving him");
            return;
        }
        if (moves >= MAX_MOVES) {
            waitFor(what + " - gave up after " + MAX_MOVES + " moves");
            return;
        }
        if (!player.onGround()) {
            waitFor(what + " - not on the ground yet");
            return;
        }
        AutoPuzzlesConfig cfg = AutoPuzzlesConfig.getInstance();
        boolean canWarp = cfg.isEtherwarpReposition() && hasAotv(player);
        if (canWarp) {
            // The best spot a warp can land on: one as good as the best of all, else the best that can be warped onto
            // at all (it reaches fewer - the next look from there finds the rest, a step closer).
            Spot target = null;
            Spot fallback = null;
            for (Spot s : found) {
                if (canWarpOnto(client, player, s.floor)) {
                    if (s.reached >= spot.reached && s.chests >= spot.chests) {
                        target = s;
                    } else if (fallback == null) {
                        fallback = s;
                    }
                    if (target != null) {
                        break;
                    }
                }
            }
            if (target == null) {
                target = fallback;
            }
            if (target != null && WARP.start(client, target.floor, false, false, false)) {
                moves++;
                loggedWait = null;
                LOGGER.info("[AutoPuzzles] Weirdos: {} - etherwarping onto {} (reaches {}, move {}/{})", what,
                        AutoPuzzleUtil.fmt(target.floor), reaches(target, targets, chestCount), moves, MAX_MOVES);
                return;
            }
            // No line onto any of them from here (from the doorway a pillar or the room's own step can be in the way):
            // a first warp onto a block from which one can be aimed at (AutoPuzzleUtil.hopToward, as Tic Tac Toe does).
            List<BlockPos> goals = new ArrayList<>();
            for (Spot s : found) {
                if (goals.size() >= 6) {
                    break;
                }
                goals.add(s.floor);
            }
            BlockPos hop = AutoPuzzleUtil.hopToward(client, player, goals);
            if (hop != null && !AutoPuzzleUtil.at(player, hop) && WARP.start(client, hop, false, false, false)) {
                moves++;
                loggedWait = null;
                LOGGER.info("[AutoPuzzles] Weirdos: {} - no warp line onto a spot in reach; etherwarping onto {} first "
                        + "(move {}/{})", what, AutoPuzzleUtil.fmt(hop), moves, MAX_MOVES);
                return;
            }
        }
        if (!cfg.isAutoPuzzlePathingEnabled()) {
            waitFor(what + " - no warp onto a spot in reach (Etherwarp Reposition " + (cfg.isEtherwarpReposition()
                    ? "on" : "off") + ", AOTV " + (hasAotv(player) ? "in the hotbar" : "not in the hotbar")
                    + ") and auto-puzzle pathing is off");
            return;
        }
        // A walk: straight to the best spot when the floor between is one level, else the nearest spot BY WALKING whose
        // standing eye has as many in reach (MazeWalk.planToSpot), else the Interactive Map, asked for the best spot.
        BlockPos planned = null;
        if (WALK.plan(client.level, player.position(), spot.floor.above())) {
            planned = spot.floor;
        } else {
            final Spot best = spot;
            final List<Spot> all = found;
            BlockPos around = spot.floor;
            BlockPos feet = MazeWalk.planToSpot(client.level, player.position(), around, 8, eye -> {
                for (Spot s : all) {
                    if (s.reached < best.reached) {
                        break;
                    }
                    if (s.eye.distanceToSqr(eye) < 0.36) {
                        return true;
                    }
                }
                return false;
            }, WALK);
            planned = feet == null ? null : feet.below();
        }
        if (planned == null) {
            if (AutoPuzzleUtil.pathIfMapOn(spot.floor, null, true)) {
                moves++;
                loggedWait = null;
                LOGGER.info("[AutoPuzzles] Weirdos: {} - no walk and no warp line; Interactive Map to {} (move {}/{})",
                        what, AutoPuzzleUtil.fmt(spot.floor), moves, MAX_MOVES);
                return;
            }
            waitFor(what + " - no warp line, no walkable way and no Interactive Map trip to " + AutoPuzzleUtil.fmt(spot.floor));
            return;
        }
        moves++;
        walking = true;
        walkStartMs = System.currentTimeMillis();
        walkTimeoutMs = 2000L + (long) (WALK.length(player.position()) / 3.5 * 1000.0);
        loggedWait = null;
        CAMERA.engage(player);
        LOGGER.info("[AutoPuzzles] Weirdos: {} - walking to {} ({} leg(s), {} blocks, move {}/{})", what,
                AutoPuzzleUtil.fmt(planned), WALK.legs(),
                String.format(java.util.Locale.ROOT, "%.1f", WALK.length(player.position())), moves, MAX_MOVES);
        tickWalk(client, player, what);
    }

    private static String reaches(Spot s, int targets, int chestCount) {
        return s.reached + " of " + targets + (chestCount == 0 ? "" : " and " + s.chests + " of " + chestCount
                + " chest(s)");
    }

    private static boolean canWarpOnto(Minecraft client, LocalPlayer player, BlockPos floor) {
        return !AutoPuzzleUtil.at(player, floor)
                && com.killer560.hub.livemap.autoclear.EtherwarpPathfinder.isEtherwarpable(floor)
                && AutoPuzzleUtil.etherwarpAim(client.level, player, floor) != null;
    }

    private static boolean hasAotv(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (AutoPuzzleUtil.isAotv(player.getInventory().getItem(i))) {
                return true;
            }
        }
        return false;
    }

    private static void tickWalk(Minecraft client, LocalPlayer player, String what) {
        if (System.currentTimeMillis() - walkStartMs > walkTimeoutMs) {
            stopWalking(client, "timed out after " + walkTimeoutMs + " ms");
            return;
        }
        AutoPuzzleActivity.note(TAG);
        if (!WALK.tick(client)) {
            stopWalking(client, "walk finished");
        }
    }

    private static void stopWalking(Minecraft client, String why) {
        if (!walking) {
            return;
        }
        walking = false;
        walkGoal = 0;
        client.options.keyUp.setDown(false);
        LOGGER.info("[AutoPuzzles] Weirdos: stopped walking - {}", why);
    }

    /** On a landing: the body onto the next weirdo in reach, or onto the chest once all three have spoken. */
    private static void readyBody(Minecraft client, LocalPlayer player, BlockPos chest) {
        Vec3 eye = AutoPuzzleUtil.settledEye(player);
        if (talked.size() < 3) {
            List<Entity> left = untalkedStands(client, player);
            Entity best = null;
            double bestSq = NPC_REACH * NPC_REACH;
            for (Entity e : left) {
                double d = BlockHits.boxDistanceSq(eye, e.getBoundingBox());
                if (d <= bestSq) {
                    bestSq = d;
                    best = e;
                }
            }
            if (best != null) {
                AutoPuzzleUtil.aimBodyAt(player, best.getBoundingBox(), CAMERA);
            }
        } else if (chest != null && BlockHits.boxDistanceSq(eye, chest) <= AutoPuzzleUtil.BLOCK_REACH_SQ) {
            AutoPuzzleUtil.aimBodyAt(player, chest, CAMERA);
        }
    }

    // ------------------------------------------------------------------ the chest

    private static void openChest(Minecraft client, LocalPlayer player, BlockPos chest, AutoPuzzlesConfig cfg) {
        long now = System.currentTimeMillis();
        if (!chest.equals(pendingPos)) {
            pendingPos = chest;
            pendingSinceMs = now;
        }
        if (McCompat.screen(client) != null) {
            stopWalking(client, "a screen opened");
            return;
        }
        BlockState state = client.level.getBlockState(chest);
        if (!(state.getBlock() instanceof ChestBlock)) {
            stopWalking(client, "no chest");
            waitFor("not opening " + AutoPuzzleUtil.fmt(chest) + " - block there is not a chest (" + state + ")");
            return;
        }
        // To the BOX - see the note in AutoWater.
        double distSq = BlockHits.boxDistanceSq(player.getEyePosition(), chest);
        if (walking) {
            if (distSq > AutoPuzzleUtil.BLOCK_REACH_SQ) {
                tickWalk(client, player, "the chest");
                return;
            }
            stopWalking(client, "the chest is in reach");
        }
        if (distSq > AutoPuzzleUtil.BLOCK_REACH_SQ) {
            String what = String.format(java.util.Locale.US, "the chest %s is out of reach (%.2f blocks)",
                    AutoPuzzleUtil.fmt(chest), Math.sqrt(distSq));
            List<Spot> found = spots(client, player, List.of(new AABB(chest)), List.of(), SPOT_CHEST_REACH);
            if (found.isEmpty()) {
                waitFor(what + " - and no standable spot in the room has it within " + SPOT_CHEST_REACH);
                return;
            }
            move(client, player, "chest:" + chest.asLong(), found, what, 1, 0);
            return;
        }
        if (player.isShiftKeyDown()) {
            waitFor("not opening " + AutoPuzzleUtil.fmt(chest) + " - sneaking");
            return;
        }
        if (now - pendingSinceMs < cfg.getWeirdosDelayMs()) {
            // His own Chest Open Delay. The body goes onto the chest meanwhile, so the click needs no turn tick after it.
            AutoPuzzleUtil.aimBodyAt(player, chest, CAMERA);
            return;
        }
        if (!AutoPuzzleUtil.gateWorldClick(client, chest)) {
            return; // gate held this tick back (or the body was turned to it) - not marked acted, opened later
        }
        actedPos = chest;
        if (!AutoPuzzleUtil.interactBlock(client, chest)) {
            LOGGER.warn("[AutoPuzzles] Weirdos: no clickable shape at {} (state={}) - not opening this room", chest, state);
            CAMERA.release(player);
            return;
        }
        LOGGER.info("[AutoPuzzles] Weirdos: opened the correct chest {}", AutoPuzzleUtil.fmt(chest));
        CAMERA.release(player);
        AutoPuzzleActivity.ended(); // the right chest is the solve: nothing follows
    }

    // ------------------------------------------------------------------ bookkeeping

    /** One INFO line per change of what the auto waits for, never one a tick. */
    private static void waitFor(String why) {
        if (!why.equals(loggedWait)) {
            loggedWait = why;
            LOGGER.info("[AutoPuzzles] Weirdos: waiting - {}", why);
        }
    }

    private static void stopEverything(Minecraft client, String why) {
        stopWalking(client, why);
        WARP.cancel(client);
        AutoReposition.releaseSneak(client);
        CAMERA.release(client.player);
    }

    static void reset(Minecraft client) {
        if (client != null) {
            stopWalking(client, "reset");
            WARP.cancel(client);
        }
        actedPos = null;
        pendingPos = null;
        pendingSinceMs = 0L;
        predatesLogged = false;
        talked.clear();
        loggedWait = null;
        moveFor = null;
        moves = 0;
        lastRoom = null;
        solverHadAnswer = false;
    }

    /** Test aid: weirdos talked to in this room. */
    public static int talkedCount() {
        return talked.size();
    }
}
