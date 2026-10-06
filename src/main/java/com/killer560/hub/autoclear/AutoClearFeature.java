package com.killer560.hub.autoclear;

import java.util.function.Consumer;

/**
 * STUB - TO BE REPLACED by the real Auto Clear feature (built in parallel by another agent). Only the agreed API, so
 * Auto Secret's "Auto Clear Rooms" option compiles; {@link #isAvailable()} is false, so Auto Secret never hands a
 * room to it and falls back to warping there and handing control back. When the real class is merged, take ITS
 * version of this file whole.
 */
public final class AutoClearFeature {

    private AutoClearFeature() {
    }

    /** STUB: Auto Clear is not built yet. */
    public static boolean isAvailable() {
        return false;
    }

    /** STUB: gives up at once. */
    public static void clearRoom(String roomName, Runnable onDone, Consumer<String> onGiveUp) {
        if (onGiveUp != null) {
            onGiveUp.accept("Auto Clear is not in this build");
        }
    }

    /** STUB. */
    public static boolean isBusy() {
        return false;
    }

    /** STUB. */
    public static void cancel() {
    }
}
