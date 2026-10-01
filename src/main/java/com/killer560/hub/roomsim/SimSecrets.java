package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.roomdatabase.RoomDatabase;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.util.ModChat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;

import org.slf4j.Logger;

import java.util.List;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McEntities;

/**
 * Puts a room's secrets in it.
 *
 * <p>killer560 (2026-09-28): "it is not generating any secrets like main does."
 *
 * <p>These are NOT captured by the room recorder, and they could not sensibly be: a secret's chest is a block
 * like any other and would come along with the geometry, but a bat or a dropped item is an entity that only
 * exists while somebody is standing near it, and the recorder reads rooms it can see from across the map.
 *
 * <p>They do not need capturing, because this mod already ships them. {@link RoomDatabase} holds 140 rooms'
 * worth of secret positions - chests, bats, items, wither essence and redstone keys - together with the
 * relative-to-absolute transform the secret waypoints feature already uses. Using that same transform is the
 * point rather than a shortcut: if the sim placed secrets by its own maths, a waypoint could sit somewhere the
 * secret is not, and the waypoints are what he is practising against.
 *
 * <p><b>What is approximated.</b> Chests and bats are the real thing. An item secret is a dropped item, which
 * is faithful. Wither essence and redstone keys are marked with a distinctive block rather than modelled,
 * because what matters for a route is where you have to go and what you have to touch, and neither of those
 * needs the item to be genuine. Said plainly here rather than quietly, so a route practised around one is not
 * mistaken for a route practised around the real thing.
 */
public final class SimSecrets {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /**
     * A wither essence, as a pure black skull. Clickable - see {@link #register}.
     *
     * <p>killer560 (2026-10-01): "For the wither essences, make it so if i click it then it dissapears and make
     * the skull pure black. Also alll wither essences were recorded with this glowing lantern above them that
     * shouldnt exist."
     *
     * <p><b>The lantern was this file's own doing, and it was a workaround for a bug that is now fixed.</b>
     * Searching all 134 captures settles it: {@code soul_lantern} appears in NONE of them, and
     * {@code player_head} appears in 98 - which is what Hypixel renders a wither essence as. So the essence was
     * always in the room, and the reason it read as "wither essences are not loading in" on 2026-09-30 is the
     * same reason the chests were invisible: a {@code SkullBlock} is an {@code EntityBlock} whose model draws
     * nothing, and {@code RoomPlacer}'s fast path was not giving pasted blocks their block entity. A lantern was
     * added above the essence to stand in for something that was there all along and could not be seen.
     *
     * <p>With that fixed the lantern is both redundant and wrong, so it is gone. What goes in its place is a
     * {@code wither_skeleton_skull} written AT the essence's own database position, replacing whatever head the
     * capture holds there - the closest vanilla block to "pure black", and thematically the right one. A
     * captured {@code player_head} carries no profile through a capture (the palette stores a block state, not a
     * skin), so leaving it would have rendered a default Steve head instead.
     *
     * <p>Only the heads at the database's own wither coordinates are touched. 98 captures contain player heads
     * and most of them are decoration or some other secret entirely.
     */
    private static final net.minecraft.world.level.block.state.BlockState WITHER_MARKER =
            Blocks.WITHER_SKELETON_SKULL.defaultBlockState();
    private static final net.minecraft.world.level.block.state.BlockState KEY_MARKER =
            Blocks.LEVER.defaultBlockState();

    /** Every wither essence skull this floor placed, so a click on one can be told from a click on scenery. */
    public static final java.util.Set<BlockPos> PLACED_WITHER =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Makes a wither essence collectable.
     *
     * <p>Right-clicking one takes it away and counts a secret, which is what it does on Hypixel. Client side
     * only - {@code UseBlockCallback} fires on the integrated server too in singleplayer, and counting there as
     * well would score every essence twice.
     */
    public static void register() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide()) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (!SimState.canAct(client) || player != client.player) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            BlockPos at = hit.getBlockPos().immutable();
            if (!PLACED_WITHER.remove(at)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            SimScore.secretFound();
            ModChat.send("Sim", ModChat.good("Wither essence collected"));
            var server = client.getSingleplayerServer();
            if (server != null) {
                server.execute(() ->
                        server.overworld().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState()));
            }
            return net.minecraft.world.InteractionResult.SUCCESS;
        });
    }

    private SimSecrets() {
    }

    /**
     * Places every secret the database knows for this room.
     *
     * @return how many were placed, or -1 when the room is not in the database
     */
    public static int place(ServerLevel level, RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
        RoomEntry entry = RoomDatabase.lookupByName(room.name);
        if (entry == null || entry.secretCoords == null) {
            // Not every captured room is in the database - and a room with no entry is not an error, it is a
            // room nobody has documented. Silence would be wrong though: a floor with no secrets in it looks
            // broken, and he should know which it is.
            return -1;
        }

        // The corner the database's relative coordinates are measured from, WHICH CORNER DEPENDS ON THE
        // ROTATION.
        //
        // A secret's relative coordinates are non-negative offsets running into the room from the clay marker,
        // and {@code toRealCoord} rotates them about that marker. At rotation 0 they run south-east, so the
        // marker is the room's north-west corner; a quarter turn sends them south-west, so the marker has to
        // be the north-east corner, and so on round. This used the north-west corner whatever the rotation,
        // so every room not at 0 threw its chests, bats and markers out of the room entirely - which is the
        // "alot of random floating chests" killer560 reported on 2026-09-29. It survived because the
        // generator refused to rotate anything; now that it does, this had to be right first.
        //
        // The corner is the corner of the room's TILE area, not of the captured window - the window takes in
        // one column of wall on each side (RoomLibrary.WALL_MARGIN) and the clay marker stands inside the
        // room, so the margin is deliberately not subtracted here.
        // The rotation the DATABASE has to be turned by is not the rotation the room was PASTED at. A capture
        // carries whatever quarter turn the room happened to be at when he walked through it, and only 34 of
        // his 135 are canonical - see RoomCaptureRotation. The paste rotation decides the room's footprint;
        // the sum of the two decides where the database's coordinates land and which corner they start from.
        int dbRotation = Math.floorMod(rotation + RoomCaptureRotation.of(room), 360);
        int[] clay = clayCorner(room, gridX, gridZ, rotation, dbRotation);
        int clayX = clay[0];
        int clayZ = clay[1];

        // The corner and rotation this WOULD have used before the capture's own turn was accounted for, kept
        // only so the audit can score both and the difference between them can be measured rather than
        // asserted. A fix with no control is a fix you are taking on trust.
        int[] oldClay = clayCorner(room, gridX, gridZ, rotation, rotation);
        uncorrectedClayX = oldClay[0];
        uncorrectedClayZ = oldClay[1];
        uncorrectedRotation = rotation;

        int placed = 0;
        // The room's own tile box, so a secret that lands outside it is reported rather than left in the
        // void. Cheap, and it is the check that would have caught the rotated-corner bug the moment rooms
        // started being turned instead of three scenarios later.
        int tilesX = (rotation == 90 || rotation == 270) ? tiles(room.sizeZ) : tiles(room.sizeX);
        int tilesZ = (rotation == 90 || rotation == 270) ? tiles(room.sizeX) : tiles(room.sizeZ);
        BlockPos boxCentre = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        boxMinX = boxCentre.getX() - RoomLibrary.TILE / 2;
        boxMinZ = boxCentre.getZ() - RoomLibrary.TILE / 2;
        boxMaxX = boxMinX + tilesX * (RoomLibrary.TILE + 1) - 2;
        boxMaxZ = boxMinZ + tilesZ * (RoomLibrary.TILE + 1) - 2;
        outsideBox = 0;
        placed += chests(level, entry.secretCoords.chest, clayX, clayZ, dbRotation);
        placed += bats(level, entry.secretCoords.bat, clayX, clayZ, dbRotation);
        placed += items(level, entry.secretCoords.item, clayX, clayZ, dbRotation);
        placed += markers(level, entry.secretCoords.wither, clayX, clayZ, dbRotation, WITHER_MARKER,
                PLACED_WITHER);
        placed += markers(level, entry.secretCoords.redstoneKey, clayX, clayZ, dbRotation, KEY_MARKER, null);
        // Loud only when something is wrong. A handful of real secrets do sit in a room's wall, so a couple
        // outside the tile box is normal; a whole room's worth means the clay corner for that rotation is
        // wrong, which is exactly the bug rotation introduced on 2026-09-29.
        if (outsideBox > 0) {
            LOGGER.warn("Sim secrets for {} (pasted at {}, capture turn {}, database rotation {}): {} "
                    + "secret(s) skipped, outside the room's own box x[{}..{}] z[{}..{}]",
                    room.name, rotation, RoomCaptureRotation.of(room), dbRotation, outsideBox,
                    boxMinX, boxMaxX, boxMinZ, boxMaxZ);
        }
        LOGGER.info("Sim secrets for {}: {} placed", room.name, placed);
        return placed;
    }

    /**
     * The corner a room's database coordinates are measured from, for a room placed at this cell and rotation.
     *
     * <p>Public and shared, because {@link SimRoomIndex} hands the same corner to Secret Waypoints. If the
     * waypoints computed their own, a waypoint could be drawn where the secret is not - and the waypoints are
     * the thing being practised against.
     *
     * @param gridX the 11x11 grid coordinates of the room's top-left cell
     * @return {@code {clayX, clayZ}}
     */
    /** A database coordinate in the world the floor was built into - the floor's shift applies to y. */
    private static BlockPos world(BlockPos at) {
        return new BlockPos(at.getX(), SimAltitude.toWorld(at.getY()), at.getZ());
    }

    public static int[] clayCorner(RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
        return clayCorner(room, gridX, gridZ, rotation, Math.floorMod(rotation + RoomCaptureRotation.of(room), 360));
    }

    /**
     * The clay corner, with the paste rotation and the database rotation given separately.
     *
     * <p>They are different numbers and they are used for different things. The room's FOOTPRINT - how many
     * tiles it covers along x and along z - follows the rotation it was physically pasted at. WHICH CORNER the
     * marker stands in follows the database rotation, because that is the corner {@code toRealCoord} measures
     * from. Collapsing the two into one value is what put whole rooms' secrets in the wrong corner for every
     * capture that was not canonical, which is 88 of his 122 identifiable rooms.
     */
    public static int[] clayCorner(RoomLibrary.Room room, int gridX, int gridZ, int rotation, int dbRotation) {
        int tilesX = (rotation == 90 || rotation == 270) ? tiles(room.sizeZ) : tiles(room.sizeX);
        int tilesZ = (rotation == 90 || rotation == 270) ? tiles(room.sizeX) : tiles(room.sizeZ);
        BlockPos centre = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int minX = centre.getX() - RoomLibrary.TILE / 2;
        int minZ = centre.getZ() - RoomLibrary.TILE / 2;
        int maxX = minX + tilesX * (RoomLibrary.TILE + 1) - 2;
        int maxZ = minZ + tilesZ * (RoomLibrary.TILE + 1) - 2;
        return new int[]{
            (dbRotation == 90 || dbRotation == 180) ? maxX : minX,
            (dbRotation == 180 || dbRotation == 270) ? maxZ : minZ,
        };
    }

    /**
     * How many chest secrets landed on a chest the capture had already pasted, and how many were tried.
     *
     * <p>The floor's own mark for whether database coordinates are being translated correctly. Reset per
     * build by {@link #resetAudit()} and read by scenario 82.
     */
    private static int chestsOnCapturedChest;
    private static int chestsChecked;
    private static int chestsUncorrected;
    private static int chestsSkippedOutside;
    private static int uncorrectedClayX;
    private static int uncorrectedClayZ;
    private static int uncorrectedRotation;

    /** @return {@code {corrected, checked, uncorrected, skippedOutsideTheirRoom}} for the floor just built. */
    public static int[] chestAudit() {
        return new int[]{chestsOnCapturedChest, chestsChecked, chestsUncorrected, chestsSkippedOutside};
    }

    /** Starts a new floor's audit. */
    public static void resetAudit() {
        chestsOnCapturedChest = 0;
        chestsChecked = 0;
        chestsUncorrected = 0;
        chestsSkippedOutside = 0;
        chestsInDoorways = 0;
        // The last floor's essences are gone with it, and a stale position would make a click on some unrelated
        // block on the new floor count a secret.
        PLACED_WITHER.clear();
    }

    /**
     * How many placed chests share a block with another placed chest.
     *
     * <p>Two secrets on one block is one secret he can never find: opening the chest counts once. It is not a
     * translation fault - the database has no duplicate coordinates - it is two rooms' secrets meeting in a
     * shared wall, which got likelier the moment floors started filling all 36 cells.
     */
    public static int collidingChests() {
        return PLACED_CHESTS.size() - new java.util.HashSet<>(PLACED_CHESTS).size();
    }

    /** Every chest this floor placed, so the build can check afterwards that they are all still there. */
    /** Secret chests skipped because they landed in a carved doorway. Reported by the build. */
    public static int chestsInDoorways;

    public static final java.util.List<BlockPos> PLACED_CHESTS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The room being placed right now, for the outside-the-box check. Single-threaded: place() is one call. */
    private static int boxMinX;
    private static int boxMinZ;
    private static int boxMaxX;
    private static int boxMaxZ;
    private static int outsideBox;

    /**
     * How far outside its own room a secret may sit before it is dropped.
     *
     * <p>Not zero: a few real secrets are embedded in a room's wall, and the wall is one column outside the
     * tile box. Two blocks covers those. Beyond that the coordinate is simply wrong - one of his floors put a
     * chest at x=-222, twenty-two blocks off the west edge of the map, in the void - and a secret in the void
     * is worse than a missing one, because the room's count says it is findable.
     */
    private static final int OUTSIDE_TOLERANCE = 2;

    /**
     * Whether a secret is close enough to its own room to be worth placing. Counts the ones that are not.
     *
     * <p>Y COUNTS TOO. This checked x and z only, so a secret whose database y falls outside the captured band
     * ({@link RoomLibrary#MIN_Y}..{@link RoomLibrary#MAX_Y}) was written into the void above or below the
     * floor. Three of Supertall's chests sit at y 142, two above the ceiling of the capture, and a build
     * reported "only 30 of 33 secret chest(s) survived - the rest were replaced by void_air" at exactly the
     * floor's top plus two. Sixty-four more secrets across the library sit below y 60 and were going the same
     * way, downwards.
     *
     * <p>There is nothing to place them against: the geometry around those positions was never captured. A
     * secret floating in the void is worse than a missing one, because the room's count still promises it.
     */
    private static boolean checkInside(BlockPos at) {
        boolean inside = at.getX() >= boxMinX - OUTSIDE_TOLERANCE && at.getX() <= boxMaxX + OUTSIDE_TOLERANCE
                && at.getZ() >= boxMinZ - OUTSIDE_TOLERANCE && at.getZ() <= boxMaxZ + OUTSIDE_TOLERANCE
                && at.getY() >= SimAltitude.minWorldY() && at.getY() <= SimAltitude.maxWorldY();
        if (!inside) {
            outsideBox++;
        }
        return inside;
    }

    /** Tiles across a captured dimension - {@code size = tiles * 32 + 1}, so this is its inverse. */
    private static int tiles(int size) {
        return Math.max(1, (size - 1) / (RoomLibrary.TILE + 1));
    }

    private static int chests(ServerLevel level, List<RoomEntry.Pos> list, int clayX, int clayZ, int rotation) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (RoomEntry.Pos p : list) {
            BlockPos at = world(RoomDatabase.toRealCoord(p, clayX, clayZ, rotation));
            // Recorded, so the NEXT build's clear knows this block exists. A secret written straight into the
            // level was outside the bounds the paste recorded, so it survived the wipe and the floor collected
            // one more chest every time a map was generated.
            if (!checkInside(at)) {
                // Counted by TYPE as well, because a floor holding fewer chests than the database lists is
                // either a translation fault or a room whose capture is the wrong size, and only a count of
                // the ones deliberately dropped can tell those apart. Deathmite is two tiles where the
                // database says three, so four of its secrets have nowhere to go and never will until it is
                // re-captured.
                chestsSkippedOutside++;
                continue;
            }
            // Does this secret land where the room's own capture already has a chest?
            //
            // This is the check that decides whether the database rotation is right, and it needs no Hypixel
            // and no eyeballing: the room is pasted BEFORE its secrets are placed, and a captured room still
            // contains the very chests these coordinates describe. Land on one and the translation agrees with
            // the geometry; land on air and it does not. Before the capture rotation was accounted for this
            // sat near zero on most floors, which is what "secrets in the wrong corner" looks like from inside.
            chestsChecked++;
            if (level.getBlockState(at).is(Blocks.CHEST)) {
                chestsOnCapturedChest++;
            }
            // The same question asked of the old, uncorrected translation, at the same moment against the same
            // world. This is the control: if both score the same the capture rotation is doing nothing, and if
            // the corrected one is not clearly ahead then it is not the fix it claims to be.
            BlockPos before = world(RoomDatabase.toRealCoord(
                    p, uncorrectedClayX, uncorrectedClayZ, uncorrectedRotation));
            if (level.getBlockState(before).is(Blocks.CHEST)) {
                chestsUncorrected++;
            }
            // Never into a carved doorway. A chest there is a door nobody can walk through, which is a much
            // worse outcome than one missing secret - see SimDoors.CARVED for why the two orders trade off.
            if (SimDoors.isCarvedDoorway(at)) {
                chestsInDoorways++;
                continue;
            }
            SimBuildQueue.touched(at.getX(), at.getZ());
            level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
            PLACED_CHESTS.add(at);
            // Offered to the mimic picker the same way a pasted room's chests are, so a sim floor can have a
            // mimic in a secret chest exactly as a real one does.
            SimMimic.addCandidate(at);
            n++;
        }
        return n;
    }

    private static int bats(ServerLevel level, List<RoomEntry.Pos> list, int clayX, int clayZ, int rotation) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (RoomEntry.Pos p : list) {
            BlockPos at = world(RoomDatabase.toRealCoord(p, clayX, clayZ, rotation));
            if (!checkInside(at)) {
                continue;
            }
            var bat = McEntities.BAT.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (bat != null) {
                bat.setPos(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
                // Still, like every other sim mob: killer560 asked that nothing wanders, so a bat cannot fly
                // off and turn a fixed secret into a moving target.
                bat.setNoAi(true);
                bat.setPersistenceRequired();
                level.addFreshEntity(bat);
                n++;
            }
        }
        return n;
    }

    /**
     * Item secrets are REGISTERED here, not spawned.
     *
     * <p>killer560 (2026-09-29): "I cannot pick up items in rooms for the item secrets. Make the item secrets
     * only spawn in if i am within 3 blocks of them for longer than 5 ticks."
     *
     * <p>Two separate things were wrong. The drop was created with {@code setNeverPickUp()}, which is not a
     * delay that expires - it is 32767 ticks, forever - so the item could be walked through and never
     * collected, and nothing counted it as a secret either way. And every item secret on the floor existed
     * from the moment the floor was built, which is not how Hypixel behaves: the item appears when you are
     * standing on it.
     *
     * <p>So the position is remembered and {@link SimSecretItems} spawns a real, collectable drop once he has
     * been within three blocks of it for more than five ticks - and picking it up counts a secret.
     */
    private static int items(ServerLevel level, List<RoomEntry.Pos> list, int clayX, int clayZ, int rotation) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (RoomEntry.Pos p : list) {
            BlockPos at = world(RoomDatabase.toRealCoord(p, clayX, clayZ, rotation));
            if (!checkInside(at)) {
                continue;
            }
            SimSecretItems.register(at);
            n++;
        }
        return n;
    }

    /**
     * @param record positions are added here when non-null, so a click can be told from a click on scenery
     */
    private static int markers(ServerLevel level, List<RoomEntry.Pos> list, int clayX, int clayZ, int rotation,
                               net.minecraft.world.level.block.state.BlockState marker,
                               java.util.Set<BlockPos> record) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        int blocked = 0;
        for (RoomEntry.Pos p : list) {
            BlockPos at = world(RoomDatabase.toRealCoord(p, clayX, clayZ, rotation));
            if (!checkInside(at)) {
                continue;
            }
            // THE DATABASE POSITION FIRST, and a head there is something to REPLACE rather than avoid.
            //
            // The old rule was "the first air at or within two blocks above", which is how a glowing lantern
            // ended up hovering above every essence - killer560 (2026-10-01). It was written when
            // "wither essences are not loading in" looked like the coordinate being buried in geometry. It was
            // not: 98 of the 134 captures hold a player_head, which is what Hypixel draws an essence as, and
            // those heads were simply invisible for want of a block entity - see WITHER_MARKER and RoomPlacer.
            //
            // So a head or air at the essence's own coordinate is the right spot, and the upward walk is kept
            // only as a fallback for a room whose coordinate really is inside something solid.
            BlockPos spot = null;
            net.minecraft.world.level.block.state.BlockState there = level.getBlockState(at);
            if (there.isAir()
                    || there.getBlock() instanceof net.minecraft.world.level.block.AbstractSkullBlock) {
                spot = at;
            } else {
                for (int up = 1; up <= 2; up++) {
                    BlockPos candidate = at.above(up);
                    if (level.getBlockState(candidate).isAir()) {
                        spot = candidate;
                        break;
                    }
                }
            }
            if (spot == null) {
                blocked++;
                continue;
            }
            SimBuildQueue.touched(spot.getX(), spot.getZ());
            level.setBlockAndUpdate(spot, marker);
            if (record != null) {
                record.add(spot);
            }
            n++;
        }
        if (blocked > 0) {
            LOGGER.warn("Sim secrets: {} marker(s) had no air within two blocks of the database position and "
                    + "were skipped", blocked);
        }
        return n;
    }

    /** Says what happened, including when the answer is "that room is not in the database". */
    public static void report(String roomName, int placed) {
        if (placed < 0) {
            ModChat.send("Sim", ModChat.dim(roomName + " is not in the room database, so it has no secrets - "
                    + "the geometry is still real."));
        } else if (placed > 0) {
            ModChat.send("Sim", ModChat.text("Placed "), ModChat.value(String.valueOf(placed)),
                    ModChat.text(" secret(s)"));
        }
    }
}
