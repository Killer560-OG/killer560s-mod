package com.killer560.hub.splittimers;

/** Public read of the core box ({@link CoreEntryTimes#inCore}) for other packages; the bounds stay in one place. */
public final class CoreArea {
    private CoreArea() {
    }

    public static boolean contains(net.minecraft.world.phys.Vec3 p) {
        return CoreEntryTimes.inCore(p.x, p.y, p.z);
    }
}
