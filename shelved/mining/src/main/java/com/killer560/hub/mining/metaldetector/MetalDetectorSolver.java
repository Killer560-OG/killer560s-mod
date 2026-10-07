package com.killer560.hub.mining.metaldetector;

import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrows the Mines of Divan treasure down to one block, from distance readings taken as you move.
 *
 * <p>Pure arithmetic, no Minecraft state, no side effects - which is the point: this is the half that can be
 * tested on its own, and the half both the legit waypoint and the macro use, so they can never disagree
 * about where the treasure is.
 *
 * <p><b>The method.</b> The mines' interior is fixed, so the chest is at one of 42 known offsets from the
 * centre ({@link MetalDetectorData}). A reading says "the treasure is 37.4m away" from where you stand, so
 * every candidate whose distance from that spot is not 37.4m (within {@link MetalDetectorData#TOLERANCE})
 * cannot be it. Take a second reading from somewhere else and intersect. Two or three readings from
 * well-separated spots usually leave exactly one.
 *
 * <p>This is elimination over a finite set, not trilateration in open space - which is why it can give an
 * exact block rather than an approximate region.
 */
public final class MetalDetectorSolver {

    /** Candidates still consistent with every reading so far. Null until the centre is known. */
    private List<Vec3i> possible;

    /** Where the mines' centre is, in world coordinates. */
    private Vec3i centre;

    private double lastDistance = -1;
    private Vec3 lastFrom;

    /**
     * Sets the mines' centre, from a Keeper armour stand.
     *
     * <p>Resets the search: a new centre means a different mines, and keeping candidates measured against
     * the old one would be worse than having none.
     */
    public void setCentre(Vec3i centre) {
        if (centre != null && centre.equals(this.centre)) {
            return;
        }
        this.centre = centre;
        reset();
    }

    public Vec3i centre() {
        return centre;
    }

    public boolean hasCentre() {
        return centre != null;
    }

    /** Starts the search over, keeping the centre. For a new treasure, or when the readings contradict. */
    public void reset() {
        possible = null;
        lastDistance = -1;
        lastFrom = null;
    }

    /**
     * Folds in one reading: the treasure is {@code distance} away from {@code from}.
     *
     * <p>A reading taken from where the last one was taken tells us nothing new, so it is ignored rather
     * than counted twice - standing still with the detector out would otherwise look like confirmation.
     *
     * @return how many candidates remain, or -1 when the centre is not known yet
     */
    public int addReading(double distance, Vec3 from) {
        if (centre == null || from == null || distance < 0) {
            return -1;
        }
        if (lastFrom != null && lastFrom.distanceToSqr(from) < 1.0 && Math.abs(distance - lastDistance) < 0.05) {
            return possible == null ? MetalDetectorData.CHEST_OFFSETS.size() : possible.size();
        }
        lastDistance = distance;
        lastFrom = from;

        List<Vec3i> from0 = possible == null ? worldCandidates() : possible;
        List<Vec3i> kept = new ArrayList<>();
        for (Vec3i candidate : from0) {
            if (matches(candidate, distance, from)) {
                kept.add(candidate);
            }
        }
        // Nothing left means the readings disagree - a new treasure spawned mid-search, or the centre was
        // wrong. Starting again from this one reading is better than reporting an empty answer, because the
        // reading itself is still good.
        if (kept.isEmpty()) {
            kept = new ArrayList<>();
            for (Vec3i candidate : worldCandidates()) {
                if (matches(candidate, distance, from)) {
                    kept.add(candidate);
                }
            }
        }
        possible = kept;
        return possible.size();
    }

    /**
     * Whether this candidate is the right distance from {@code from}.
     *
     * <p>Measured to the CENTRE of the block, not its corner - the readout is a distance to the treasure,
     * and the treasure is the block, so a half-block error here would be a quarter of the tolerance spent on
     * nothing.
     */
    private static boolean matches(Vec3i candidate, double distance, Vec3 from) {
        double dx = candidate.getX() + 0.5 - from.x;
        double dy = candidate.getY() + 0.5 - from.y;
        double dz = candidate.getZ() + 0.5 - from.z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return Math.abs(d - distance) < MetalDetectorData.TOLERANCE;
    }

    private List<Vec3i> worldCandidates() {
        List<Vec3i> out = new ArrayList<>(MetalDetectorData.CHEST_OFFSETS.size());
        for (Vec3i offset : MetalDetectorData.CHEST_OFFSETS) {
            out.add(new Vec3i(centre.getX() + offset.getX(),
                    centre.getY() + offset.getY(),
                    centre.getZ() + offset.getZ()));
        }
        return out;
    }

    /** Every position still consistent with the readings - one of these is the treasure. */
    public List<Vec3i> possible() {
        return possible == null ? List.of() : List.copyOf(possible);
    }

    /** The answer, or null while more than one candidate remains. */
    public Vec3i solved() {
        return possible != null && possible.size() == 1 ? possible.get(0) : null;
    }

    /**
     * The centre implied by seeing a named Keeper at this position.
     *
     * @return the mines' centre, or null for a name that is not one of the four
     */
    public static Vec3i centreFromKeeper(String keeperName, Vec3i keeperPos) {
        Vec3i offset = MetalDetectorData.KEEPER_OFFSETS.get(keeperName);
        if (offset == null || keeperPos == null) {
            return null;
        }
        return new Vec3i(keeperPos.getX() - offset.getX(),
                keeperPos.getY() - offset.getY(),
                keeperPos.getZ() - offset.getZ());
    }
}
