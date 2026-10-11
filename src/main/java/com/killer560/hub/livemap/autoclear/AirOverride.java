package com.killer560.hub.livemap.autoclear;

import java.util.Set;

/**
 * A grid that reads given blocks as air: the blocks Breaker Aura will have broken by the time an Auto Routes path
 * flies past them. killer560 (2026-10-11): "the path node specifically should look at all blocks added to breaker
 * aura and treat those as not existing when it plans its route as they should be broken whenever it goes by". Both
 * the line of sight (a ray passes them) and standing room (a landing under one has its head room) read them as air.
 *
 * <p>Only for a planner's own search: the floor graph is shared with the Interactive Map and caches what its rays saw,
 * so it is never handed one of these. No Minecraft imports (tools/bench compiles it).
 */
public final class AirOverride implements EtherSearch.Grid {

    private static final int AIR_FLAGS = EtherSearch.PASSABLE | EtherSearch.AIR;

    private final EtherSearch.Grid inner;
    private final Set<Long> air;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;

    /** {@code air}: positions packed with {@link EtherSearch#pack}; must not be empty. */
    public AirOverride(EtherSearch.Grid inner, Set<Long> air) {
        this.inner = inner;
        this.air = air;
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y0 = Integer.MAX_VALUE;
        int y1 = Integer.MIN_VALUE;
        int z0 = Integer.MAX_VALUE;
        int z1 = Integer.MIN_VALUE;
        for (long p : air) {
            int x = WarpGraph.unpackX(p);
            int y = WarpGraph.unpackY(p);
            int z = WarpGraph.unpackZ(p);
            x0 = Math.min(x0, x);
            x1 = Math.max(x1, x);
            y0 = Math.min(y0, y);
            y1 = Math.max(y1, y);
            z0 = Math.min(z0, z);
            z1 = Math.max(z1, z);
        }
        minX = x0;
        maxX = x1;
        minY = y0;
        maxY = y1;
        minZ = z0;
        maxZ = z1;
    }

    /** {@code inner} itself when {@code air} is null or empty. */
    public static EtherSearch.Grid wrap(EtherSearch.Grid inner, Set<Long> air) {
        return air == null || air.isEmpty() ? inner : new AirOverride(inner, air);
    }

    @Override
    public int flags(int x, int y, int z) {
        if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ
                && air.contains(EtherSearch.pack(x, y, z))) {
            return AIR_FLAGS;
        }
        return inner.flags(x, y, z);
    }
}
