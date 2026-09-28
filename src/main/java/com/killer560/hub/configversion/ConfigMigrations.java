package com.killer560.hub.configversion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Records which build last wrote this install's config, and runs one-off fixes when that changes.
 *
 * <p>Written because of a real failure on 2026-09-28. The align tolerance had been saved for days by a build
 * whose reader ignored it, so every stored value was a number that had never driven anything. Fixing the reader
 * would have silently activated those stale numbers and put real installs looser than the align nodes are
 * designed to hold. The only fix available was to rename the key by hand and abandon the old one - the second
 * time that exact trick had been used on that exact setting.
 *
 * <p>Renaming a key works, but it leaves no record, it cannot be undone, and it does not scale past one person's
 * machine. With the mod going public, a setting whose MEANING changes needs somewhere to say so once, keyed to
 * the version that changed it, and the config files themselves are the wrong place because each feature owns its
 * own. So: one small file holding the last version that ran, and a list of migrations that run in order when it
 * moves.
 *
 * <p>A migration must be safe to run on a config that has never seen it, and safe to skip on a fresh install -
 * a new install writes the current version and runs nothing, because defaults are already correct by definition.
 */
public final class ConfigMigrations {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STAMP =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-configversion.json");

    /** One fix: the version it belongs to, why it exists, and what it does. */
    public record Migration(String version, String reason, Runnable apply) {
    }

    private static final List<Migration> MIGRATIONS = new ArrayList<>();

    static {
        // The align tolerance is already handled by its own key rename (alignToleranceExact -> alignToleranceV3),
        // which shipped before this existed. It is recorded here as the worked example of what belongs in this
        // list, deliberately doing nothing, so the next person can see the shape of a real migration rather than
        // an invented one.
        MIGRATIONS.add(new Migration("1.1.0",
                "align tolerance stored under a key whose reader ignored it; handled by renaming the key",
                () -> { }));
    }

    private ConfigMigrations() {
    }

    /** Registers a fix to run when an install moves onto {@code version}. Call before {@link #run()}. */
    public static void add(Migration m) {
        MIGRATIONS.add(m);
    }

    /**
     * Runs anything this install has not run yet, then records the current version.
     *
     * <p>Never throws into startup: a broken migration must not stop the mod loading, because a user who cannot
     * launch cannot be told what went wrong or send a report about it.
     */
    public static void run() {
        String current = currentVersion();
        String previous = readStamp();
        try {
            if (previous == null) {
                // Fresh install: defaults are correct, so nothing to fix. Just stamp it, or every migration ever
                // written would run against a config that never had the problem.
                LOGGER.info("First run on {} - no config migrations needed", current);
            } else if (!previous.equals(current)) {
                LOGGER.info("Config was last written by {}, now on {} - checking migrations", previous, current);
                for (Migration m : MIGRATIONS) {
                    if (m.version().equals(previous)) {
                        continue; // already the version that wrote it
                    }
                    try {
                        m.apply().run();
                    } catch (Throwable t) {
                        LOGGER.error("Config migration for {} failed ({}) - continuing", m.version(), m.reason(), t);
                    }
                }
            }
            writeStamp(current);
        } catch (Throwable t) {
            LOGGER.error("Config version check failed - continuing without it", t);
        }
    }

    /** What the last run wrote, or null if this install has never recorded one. */
    public static String readStamp() {
        if (!Files.exists(STAMP)) {
            return null;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(STAMP, StandardCharsets.UTF_8)).getAsJsonObject();
            String v = ConfigJson.getString(o, "lastVersion", null);
            return v == null || v.isBlank() ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeStamp(String version) {
        try {
            Files.createDirectories(STAMP.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("lastVersion", version);
            o.addProperty("lastRunEpochMillis", System.currentTimeMillis());
            Files.writeString(STAMP, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("Could not record the config version", e);
        }
    }

    /** Read from the loader rather than hardcoded, so it cannot drift from gradle.properties. */
    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer("killer560smod")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
