package com.killer560.hub.puzzlesolvers;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/** Reads a solver's JSON database out of the jar. */
final class SolverData {

    private SolverData() {
    }

    /** The parsed file, or {@code fallback} when it is missing, empty or unreadable (each logged with the solver's tag).
     *  Loggers are fetched by name because solvers call this from a static initializer, before their own LOGGER is set. */
    static <T> T load(String file, Type type, T fallback, String loggerName, String tag, String missing, String failed) {
        try (InputStream stream = SolverData.class.getClassLoader().getResourceAsStream("data/killer560smod/puzzles/" + file)) {
            if (stream == null) {
                com.killer560.hub.util.ModLog.get(loggerName).warn("{} {}", tag, missing);
                return fallback;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                T parsed = new Gson().fromJson(reader, type);
                return parsed != null ? parsed : fallback;
            }
        } catch (Exception e) {
            com.killer560.hub.util.ModLog.get(loggerName).warn(tag + " Failed to load " + failed, e);
            return fallback;
        }
    }
}
