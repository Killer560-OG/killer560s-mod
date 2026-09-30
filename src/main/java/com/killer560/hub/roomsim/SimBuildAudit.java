package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModLog;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the floor back out of the world and checks each room really is the room it was meant to be.
 *
 * <h2>Why this exists</h2>
 *
 * <p>killer560 (2026-09-30): "I am standing in Crypt but the room generated isnt crypt", "this is a rare room
 * i do not remember whcih but it isnt waterfall", "it feels like almost every room was generated wrong."
 *
 * <p>Every check the build already ran said it was fine, and they all shared one blind spot: they checked the
 * build's own INPUTS against each other. The door audit compares measured door masks with the layout's door
 * cells; the secret audit compares database coordinates with the room's box; the map is published from the
 * same cell array the paste read. Not one of them looks at a block that was actually written, so a paste that
 * put the wrong blocks down, or put them in the wrong place, would pass all three in silence - and did.
 *
 * <p>This is the missing half: after the last block lands, sample the world inside each room's own footprint
 * and compare it against the capture that was supposed to be pasted there. It is the only check in the build
 * whose answer comes from the world rather than from the plan, which is exactly what makes it worth its cost.
 *
 * <h2>Reading the number it prints</h2>
 *
 * <p>A healthy room does NOT score 100%, and expecting it to would make this useless. Doorways are carved
 * after the paste, unused ones are bricked up, secret chests and markers are written in, and a wither door
 * becomes barriers - so a few per cent of any room is legitimately not what the capture holds. What cannot
 * happen legitimately is a room that mostly disagrees: two captures of different rooms share their walls and
 * floors and still only reach about ninety per cent (measured across his 134 captures on 2026-09-30), so a
 * room pasted from the wrong capture, or a quarter turn out, or half-written, falls far below anything the
 * post-paste edits can explain.
 *
 * <p>The threshold is therefore deliberately low. This is a smoke alarm, not a ruler: it should never fire on
 * a working build, and when it does fire the room is wrong in a way he can see from where he is standing.
 */
public final class SimBuildAudit {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /**
     * Below this share of sampled blocks matching, the room is reported.
     *
     * <p>Chosen under the noise floor rather than near it - see the class doc on why two different rooms can
     * still agree about ninety per cent of the time. A room at 70% is not a room with a few chests added.
     */
    private static final double REPORT_BELOW = 0.70;

    /** Roughly how many positions to look at per room. A floor of 24 rooms is then about 70,000 reads. */
    private static final int SAMPLES_PER_ROOM = 3000;

    /** A room as it was pasted - what the audit needs to find it again. */
    private record Pasted(RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
    }

    private static final List<Pasted> PASTED = new ArrayList<>();

    /**
     * The last floor's scores, as {@code "Room=87"} per room, newest build only.
     *
     * <p>Exposed so a gametest can assert on the audit rather than on the build's own inputs, which is the
     * whole reason this class exists. A test that only re-checked the plan would share the blind spot.
     */
    private static final List<String> LAST_SCORES = new ArrayList<>();
    private static int lastBelowThreshold = -1;

    private SimBuildAudit() {
    }

    /** Forgets the last floor. Called where the build clears its other per-map state. */
    public static synchronized void clear() {
        PASTED.clear();
        LAST_SCORES.clear();
        lastBelowThreshold = -1;
    }

    /** Rooms in the last audited floor that scored under the threshold, or -1 if no floor has been audited. */
    public static synchronized int lastBelowThreshold() {
        return lastBelowThreshold;
    }

    /** Every room of the last audited floor and its score, as {@code "Name=87"}. */
    public static synchronized List<String> lastScores() {
        return List.copyOf(LAST_SCORES);
    }

    /** Records a placement as it is queued, so the audit can look it up once the paste has finished. */
    public static synchronized void record(RoomLibrary.Room room, int gridX, int gridZ, int rotation) {
        PASTED.add(new Pasted(room, gridX, gridZ, rotation));
    }

    /**
     * Checks every room of the floor that was just built, and says so.
     *
     * <p>Server thread, after the last paste: it reads blocks, so it has to be, and it has to be after or it
     * would be measuring a half-built floor and reporting the build against itself.
     */
    public static void verify(ServerLevel level) {
        List<Pasted> rooms;
        synchronized (SimBuildAudit.class) {
            rooms = new ArrayList<>(PASTED);
        }
        if (rooms.isEmpty()) {
            return;
        }
        int worst = 100;
        String worstName = null;
        int reported = 0;
        List<String> scores = new ArrayList<>(rooms.size());
        for (Pasted p : rooms) {
            double share = matchShare(level, p);
            if (share < 0) {
                continue;   // nothing comparable in that room - say nothing rather than invent a score
            }
            int percent = (int) Math.round(share * 100);
            scores.add(p.room().name + "=" + percent);
            if (percent < worst) {
                worst = percent;
                worstName = p.room().name;
            }
            if (share < REPORT_BELOW) {
                reported++;
                LOGGER.warn("Sim build: the blocks at \"{}\" (cell {},{}, pasted at {}) match its capture only "
                        + "{}% of the time. That is far below what carving doorways and adding secrets can "
                        + "account for, so what is standing there is not that room as captured - a different "
                        + "capture, a different quarter turn, or a paste that did not finish.",
                        p.room().name, p.gridX(), p.gridZ(), p.rotation(), percent);
            }
        }
        synchronized (SimBuildAudit.class) {
            LAST_SCORES.clear();
            LAST_SCORES.addAll(scores);
            lastBelowThreshold = reported;
        }
        if (reported == 0) {
            LOGGER.info("Sim build: all {} room(s) match their captures (worst: {} at {}%)",
                    rooms.size(), worstName, worst);
        } else {
            LOGGER.warn("Sim build: {} of {} room(s) do not match their captures", reported, rooms.size());
        }
    }

    /**
     * What share of the sampled positions in this room hold the block the capture says.
     *
     * @return the share, or -1 when there was nothing worth comparing
     */
    private static double matchShare(ServerLevel level, Pasted p) {
        RoomLibrary.Room room = p.room();
        BlockPos origin = DungeonLayout.cellCenter(p.gridZ() * DungeonLayout.GRID + p.gridX());
        int worldX0 = origin.getX() - RoomLibrary.TILE / 2 - room.margin;
        int worldZ0 = origin.getZ() - RoomLibrary.TILE / 2 - room.margin;

        // The captured band is taller than the room, and the top of it is air in every room, which would
        // agree with a wrong room just as happily. Sampling the occupied part is what makes the number mean
        // something: it is the walls, floor and ceiling that differ between rooms.
        int minY = room.contentMinY();
        int maxY = room.contentMaxY();
        if (maxY < minY) {
            return -1;
        }
        long volume = (long) room.sizeX * room.sizeZ * (maxY - minY + 1);
        int stride = (int) Math.max(1, volume / SAMPLES_PER_ROOM);

        int checked = 0;
        int matched = 0;
        long index = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < room.sizeX; x++) {
                for (int z = 0; z < room.sizeZ; z++, index++) {
                    if (index % stride != 0) {
                        continue;
                    }
                    if (!room.seenColumn[z * room.sizeX + x]) {
                        continue;   // never captured, so the world holds the red marker and not this
                    }
                    short paletteIdx = room.at(x, y, z);
                    if (paletteIdx < 0 || paletteIdx >= room.palette.size()) {
                        continue;
                    }
                    int[] local = RoomPlacer.rotateLocal(x, z, room.sizeX, room.sizeZ, p.rotation());
                    BlockState state = level.getBlockState(new BlockPos(
                            worldX0 + local[0], SimAltitude.toWorld(y), worldZ0 + local[1]));
                    checked++;
                    // The BLOCK, not the state: the paste turns each block's facing with the room, and a
                    // stair that came out facing the wrong way is a different fault from a room that is not
                    // there at all. This one is about identity.
                    String want = room.palette.get(paletteIdx);
                    String got = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (want.equals(got) || sameAir(want, got)) {
                        matched++;
                    }
                }
            }
        }
        return checked == 0 ? -1 : (double) matched / checked;
    }

    /** Air is air. A capture holds void_air where the world holds air and neither is a fault. */
    private static boolean sameAir(String want, String got) {
        return want.endsWith("air") && got.endsWith("air");
    }
}
