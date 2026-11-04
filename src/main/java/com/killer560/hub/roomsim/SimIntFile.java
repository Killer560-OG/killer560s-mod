package com.killer560.hub.roomsim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A one-number preference file: read once on demand, written on change. */
final class SimIntFile {

    private SimIntFile() {
    }

    /** The number in {@code file}, or {@code fallback} when it is missing or unreadable. */
    static int read(Path file, int fallback) {
        try {
            if (Files.exists(file)) {
                return Integer.parseInt(Files.readString(file, StandardCharsets.UTF_8).trim());
            }
        } catch (Exception ignored) {
            // Unreadable means unknown, and the default is a safe unknown.
        }
        return fallback;
    }

    /** Writes {@code value}; losing the preference is not worth failing the click or command that made it. */
    static void write(Path file, int value) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.valueOf(value), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // Losing the preference is not worth failing the click he just made.
        }
    }
}
