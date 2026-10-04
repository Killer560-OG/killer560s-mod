package com.killer560.hub.roomsim;

/** Offline stand-in for RotationAudit: rotateLocal copied verbatim from the real RoomPlacer. */
final class RoomPlacer {
    static int[] rotateLocal(int x, int z, int sizeX, int sizeZ, int degrees) {
        return switch (degrees) {
            case 0 -> new int[]{x, z};
            case 90 -> new int[]{sizeZ - 1 - z, x};
            case 180 -> new int[]{sizeX - 1 - x, sizeZ - 1 - z};
            case 270 -> new int[]{z, sizeX - 1 - x};
            default -> throw new IllegalArgumentException(
                    "rotation must be 0, 90, 180 or 270 degrees, got " + degrees);
        };
    }
}
