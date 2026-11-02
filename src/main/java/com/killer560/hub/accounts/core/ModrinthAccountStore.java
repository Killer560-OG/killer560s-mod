package com.killer560.hub.accounts.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the Microsoft accounts the Modrinth App has signed in, from the {@code minecraft_users} table of its
 * {@code app.db} (columns uuid, active, username, access_token, refresh_token, expires; checked against a copy of a real
 * app.db on 2026-11-01). Strictly read-only ({@link SqliteReader}): nothing is ever written back to the app's files.
 * <p>
 * The app refreshes every signed-in account's Minecraft token when it starts (all five rows of that copy carried expiry
 * times within two minutes of each other, 24 hours after the app's launch), so while it is running the tokens are
 * current. The refresh token is not used: the app's is tied to its own Xbox device key.
 */
public final class ModrinthAccountStore {

    private ModrinthAccountStore() {
    }

    /** The Modrinth App's own data directory (dirs::data_dir()/ModrinthApp in the app's source). */
    public static Path defaultAppDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Paths.get(appData != null ? appData : System.getProperty("user.home"), "ModrinthApp");
        } else if (os.contains("mac")) {
            return Paths.get(System.getProperty("user.home"), "Library", "Application Support", "ModrinthApp");
        } else {
            return Paths.get(System.getProperty("user.home"), ".local", "share", "ModrinthApp");
        }
    }

    /**
     * The app.db for a game running from {@code gameDir}: the folder two levels up when the game dir is
     * {@code <app>/profiles/<name>} and holds an app.db, else the default app folder's. The system property
     * {@code killer560smod.modrinthAppDb} overrides both (tests).
     */
    public static Path appDb(Path gameDir) {
        String override = System.getProperty("killer560smod.modrinthAppDb");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        Path fromGameDir = appDbAbove(gameDir);
        return fromGameDir != null ? fromGameDir : defaultAppDir().resolve("app.db");
    }

    /** {@code <app>/app.db} when {@code gameDir} is {@code <app>/profiles/<name>} and that file exists, else null. */
    static Path appDbAbove(Path gameDir) {
        if (gameDir == null) {
            return null;
        }
        Path abs = gameDir.toAbsolutePath().normalize();
        Path profiles = abs.getParent();
        if (profiles == null || profiles.getFileName() == null
                || !"profiles".equalsIgnoreCase(profiles.getFileName().toString())) {
            return null;
        }
        Path app = profiles.getParent();
        if (app == null) {
            return null;
        }
        Path db = app.resolve("app.db");
        return Files.isRegularFile(db) ? db : null;
    }

    /** The accounts in {@code appDb}, the app's active account first; empty when the file does not exist. */
    public static List<PrismAccount> load(Path appDb) throws IOException {
        List<PrismAccount> result = new ArrayList<>();
        if (appDb == null || !Files.isRegularFile(appDb)) {
            return result;
        }
        List<Map<String, Object>> rows;
        try (SqliteReader reader = SqliteReader.open(appDb)) {
            rows = reader.readTable("minecraft_users");
        }
        for (Map<String, Object> row : rows) {
            String uuid = row.get("uuid") instanceof String s ? s : null;
            String name = row.get("username") instanceof String s ? s : null;
            String token = row.get("access_token") instanceof String s ? s : null;
            long expires = row.get("expires") instanceof Long l ? l : 0L;
            if (uuid == null || name == null || token == null || token.isBlank()) {
                continue;
            }
            PrismAccount account = PrismAccount.modrinth(uuid, name, token, expires);
            boolean active = row.get("active") instanceof Long l && l != 0;
            if (active) {
                result.add(0, account);
            } else {
                result.add(account);
            }
        }
        return result;
    }
}
