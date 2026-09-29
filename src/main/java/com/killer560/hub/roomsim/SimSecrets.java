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
import org.slf4j.LoggerFactory;

import java.util.List;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-roomsim");

    /** Stand-ins for the two secret kinds that are not worth modelling exactly. */
    private static final net.minecraft.world.level.block.state.BlockState WITHER_MARKER =
            Blocks.SOUL_LANTERN.defaultBlockState();
    private static final net.minecraft.world.level.block.state.BlockState KEY_MARKER =
            Blocks.LEVER.defaultBlockState();

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

        // The corner the database's relative coordinates are measured from. On Hypixel that is the clay marker
        // at the room's corner; here the room is placed at a known cell, so it is computed from the same
        // origin the paste used - one block inside the captured window, which carries the wall margin.
        BlockPos centre = DungeonLayout.cellCenter(gridZ * DungeonLayout.GRID + gridX);
        int clayX = centre.getX() - RoomLibrary.TILE / 2;
        int clayZ = centre.getZ() - RoomLibrary.TILE / 2;

        int placed = 0;
        placed += chests(level, entry.secretCoords.chest, clayX, clayZ, rotation);
        placed += bats(level, entry.secretCoords.bat, clayX, clayZ, rotation);
        placed += items(level, entry.secretCoords.item, clayX, clayZ, rotation);
        placed += markers(level, entry.secretCoords.wither, clayX, clayZ, rotation, WITHER_MARKER);
        placed += markers(level, entry.secretCoords.redstoneKey, clayX, clayZ, rotation, KEY_MARKER);
        LOGGER.info("Sim secrets for {}: {} placed", room.name, placed);
        return placed;
    }

    private static int chests(ServerLevel level, List<RoomEntry.Pos> list, int clayX, int clayZ, int rotation) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (RoomEntry.Pos p : list) {
            BlockPos at = RoomDatabase.toRealCoord(p, clayX, clayZ, rotation);
            level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
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
            BlockPos at = RoomDatabase.toRealCoord(p, clayX, clayZ, rotation);
            var bat = EntityType.BAT.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
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

    private static int items(ServerLevel level, List<RoomEntry.Pos> list, int clayX, int clayZ, int rotation) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (RoomEntry.Pos p : list) {
            BlockPos at = RoomDatabase.toRealCoord(p, clayX, clayZ, rotation);
            var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
            var drop = new net.minecraft.world.entity.item.ItemEntity(
                    level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, stack);
            drop.setNeverPickUp();
            // No despawn timer and no drift: a secret that vanishes after five minutes makes the room
            // different on the second run through it, which is the one thing a practice room must not be.
            drop.setUnlimitedLifetime();
            drop.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            level.addFreshEntity(drop);
            n++;
        }
        return n;
    }

    private static int markers(ServerLevel level, List<RoomEntry.Pos> list, int clayX, int clayZ, int rotation,
                               net.minecraft.world.level.block.state.BlockState marker) {
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (RoomEntry.Pos p : list) {
            BlockPos at = RoomDatabase.toRealCoord(p, clayX, clayZ, rotation);
            if (level.getBlockState(at).isAir()) {
                level.setBlockAndUpdate(at, marker);
                n++;
            }
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
