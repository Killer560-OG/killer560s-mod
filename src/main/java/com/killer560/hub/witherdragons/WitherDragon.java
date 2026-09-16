package com.killer560.hub.witherdragons;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * The five M7 Phase 5 dragons. Coordinates ported verbatim from Odin's {@code WitherDragonsEnum.kt}
 * (spawnPos, statuePos, aabbDimensions, particle xRange/zRange):
 * https://github.com/odtheking/Odin/blob/main/src/main/kotlin/com/odtheking/odin/features/impl/boss/WitherDragonsEnum.kt
 * (identical spawn/box/range values in NoammAddons' {@code WitherDragonEnum.kt}, local copy
 * C:\Users\Hunter\noammaddonsmod, except Blue's box minY 16 there vs 13 in Odin - Odin's kept). Type names
 * (Power/Flame/Apex/Ice/Soul) and {@code skipKillTime} (ticks after spawn an arrow hit still counts toward the
 * "arrows hit" stat) are from NoammAddons.
 */
public enum WitherDragon {
    RED("Red", "Power", 'c', 0xFFFF5555, new BlockPos(27, 14, 59), new BlockPos(32, 22, 59),
            new AABB(14.5, 13.0, 45.5, 39.5, 28.0, 70.5), 24.0, 30.0, 56.0, 62.0, 50),
    ORANGE("Orange", "Flame", '6', 0xFFFFAA00, new BlockPos(85, 14, 56), new BlockPos(80, 23, 56),
            new AABB(72.0, 8.0, 47.0, 102.0, 28.0, 77.0), 82.0, 88.0, 53.0, 59.0, 62),
    GREEN("Green", "Apex", 'a', 0xFF55FF55, new BlockPos(27, 14, 94), new BlockPos(32, 23, 94),
            new AABB(7.0, 8.0, 80.0, 37.0, 28.0, 110.0), 23.0, 29.0, 91.0, 97.0, 52),
    BLUE("Blue", "Ice", 'b', 0xFF55FFFF, new BlockPos(84, 14, 94), new BlockPos(79, 23, 94),
            new AABB(71.5, 13.0, 82.5, 96.5, 26.0, 107.5), 82.0, 88.0, 91.0, 97.0, 47),
    PURPLE("Purple", "Soul", '5', 0xFFAA00AA, new BlockPos(56, 14, 125), new BlockPos(56, 22, 120),
            new AABB(45.5, 13.0, 113.5, 68.5, 23.0, 136.5), 53.0, 59.0, 122.0, 128.0, 38);

    public enum State {
        SPAWNING, ALIVE, DEAD
    }

    public final String colourName;
    public final String typeName;
    public final char colorCode;
    public final int argb;
    public final BlockPos spawnPos;
    public final BlockPos statuePos;
    public final AABB box;
    final double minX;
    final double maxX;
    final double minZ;
    final double maxZ;
    public final int skipKillTicks;

    // runtime state (Odin WitherDragonsEnum vars + NoammAddons arrowsHit)
    int timeToSpawn = 100;
    State state = State.DEAD;
    int timesSpawned = 0;
    UUID entityUUID = null;
    boolean sprayed = false;
    long spawnedTick = 0L;
    long spawnedMs = 0L;
    int arrowsHit = 0;
    float health = 0f;
    boolean statueWasSolid = false;

    WitherDragon(String colourName, String typeName, char colorCode, int argb, BlockPos spawnPos, BlockPos statuePos,
                 AABB box, double minX, double maxX, double minZ, double maxZ, int skipKillTicks) {
        this.colourName = colourName;
        this.typeName = typeName;
        this.colorCode = colorCode;
        this.argb = argb;
        this.spawnPos = spawnPos;
        this.statuePos = statuePos;
        this.box = box;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.skipKillTicks = skipKillTicks;
    }

    /** Kotlin {@code x in a..b} (inclusive) for Odin's particle ranges. */
    boolean particleInRange(double x, double z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public State state() {
        return state;
    }

    public int timeToSpawn() {
        return timeToSpawn;
    }

    public int timesSpawned() {
        return timesSpawned;
    }

    public float health() {
        return health;
    }

    /** "Red" or "Power" per the Name Style setting. */
    public String displayName() {
        return WitherDragonsConfig.getInstance().getNameStyle() == WitherDragonsConfig.NameStyle.TYPE ? typeName : colourName;
    }

    public String colored() {
        return "§" + colorCode + displayName();
    }

    /** Odin {@code WitherDragonsEnum.reset()} (hard). */
    static void resetAll() {
        for (WitherDragon d : values()) {
            d.timeToSpawn = 0;
            d.timesSpawned = 0;
            d.state = State.DEAD;
            d.entityUUID = null;
            d.sprayed = false;
            d.spawnedTick = 0L;
            d.spawnedMs = 0L;
            d.arrowsHit = 0;
            d.health = 0f;
            d.statueWasSolid = false;
        }
    }
}
