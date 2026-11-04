package com.killer560.hub.mining.chmap;

/**
 * One structure found in the current lobby, and the box it has been seen to occupy so far.
 *
 * <p>The box GROWS. A structure is learned by standing in it, so the first observation is a point and every
 * later one widens what is known - walk round the Mines of Divan and its border creeps out to the edges you
 * actually reached. That is honest about what has been seen, which a fixed shape would not be: the map draws
 * the part of the structure the player has established is there, not a guess at the rest of it.
 *
 * <p>{@code source} is what makes the two maps distinguishable at a glance. A find from the cheat scanner is
 * marked as such, so a shared find can never silently pass off a scan as something someone walked into - see
 * {@link ChDiscovery} for why that matters when these are sent to other people.
 */
public final class ChFind {

    public enum Source {
        /** He stood in it. The only thing the legit map ever produces. */
        VISITED,
        /** The cheat map's scanner saw it from outside. */
        SCANNED,
        /** Another player running this mod sent it over the relay. */
        SHARED,
    }

    public final ChStructure structure;
    public Source source;

    public double minX;
    public double minY;
    public double minZ;
    public double maxX;
    public double maxY;
    public double maxZ;

    /** Who found it, for a shared find. Empty for his own. A UUID, never an IGN - names change. */
    public String fromUuid = "";

    public ChFind(ChStructure structure, Source source, double x, double y, double z) {
        this.structure = structure;
        this.source = source;
        this.minX = this.maxX = x;
        this.minY = this.maxY = y;
        this.minZ = this.maxZ = z;
    }

    /** Widens the known box to include this position. */
    public void include(double x, double y, double z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    /** Widens to include everything another find knows. Used when a shared find arrives for a known place. */
    public void include(ChFind other) {
        include(other.minX, other.minY, other.minZ);
        include(other.maxX, other.maxY, other.maxZ);
    }

    public double centreX() {
        return (minX + maxX) / 2;
    }

    public double centreY() {
        return (minY + maxY) / 2;
    }

    public double centreZ() {
        return (minZ + maxZ) / 2;
    }

    /**
     * Whether this is more than a single point yet.
     *
     * <p>A border drawn round one position is a dot, not a border, so the map draws a waypoint for those and
     * only outlines a find once it has some width to outline.
     */
    public boolean hasExtent() {
        return maxX - minX >= 1 || maxZ - minZ >= 1;
    }
}
