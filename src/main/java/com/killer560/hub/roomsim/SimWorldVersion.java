package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModPaths;

import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.killer560.hub.util.ModLog;

/**
 * Remembers which shape the sim world on disk was generated in.
 *
 * <p>A world's chunk generator is decided when it is created and never changes, so switching the sim to a void
 * world does nothing to the superflat one already saved. It has to be deleted once.
 *
 * <p>This exists so that deletion happens exactly once rather than on every open. Tracked with a flag rather
 * than by reading the world's generator back, because a flag is one line and reading the generator means
 * loading the level to ask it a question whose answer decides whether to delete the level.
 *
 * <p>Deleting it is safe in a way deleting a world usually is not: everything in the sim world is rebuilt from
 * the room library on demand, so nothing in it exists only there. The library is in the config folder and is
 * never touched by this.
 */
public final class SimWorldVersion {

    private static final Logger LOGGER = ModLog.get("killer560smod-roomsim");

    private static final Path FILE =
            ModPaths.config("killer560smod-sim-world.txt");

    /** Bumped when the world has to be regenerated for a reason a rebuild cannot fix. 1 = void world. */
    private static final int CURRENT = 1;

    private SimWorldVersion() {
    }

    public static boolean isVoidWorld() {
        try {
            if (!Files.exists(FILE)) {
                return false;
            }
            return Integer.parseInt(Files.readString(FILE, StandardCharsets.UTF_8).trim()) >= CURRENT;
        } catch (Exception e) {
            // Unreadable means unknown, and unknown means regenerate - a wasted rebuild costs seconds, while
            // wrongly assuming it is current leaves him in the superflat world he asked to be rid of.
            return false;
        }
    }

    public static void markVoidWorld() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, String.valueOf(CURRENT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not record the sim world version - it may be regenerated again next time", e);
        }
    }
}
