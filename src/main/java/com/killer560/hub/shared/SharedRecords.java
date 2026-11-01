package com.killer560.hub.shared;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The shared record store (killer560, 2026-11-01: "I want profit logs from other prism instances all combinable as the
 * mod updates"). Records ({@link RecordCatalog}) live outside every instance, in
 * {@code %APPDATA%\killer560smod\shared\} on Windows, so every instance and Minecraft version on the PC reads and adds to
 * the same profit, run history and stats. Settings stay in each instance's {@code config/killer560/}.
 *
 * <p>The first time an instance asks for a record, its old per-instance file is folded in ({@link SharedFile#importLegacy}:
 * no double counting of copies) and moved to {@code <game dir>/killer560smod-records-moved/}, out of the config folder so
 * profiles never carry it.
 *
 * <p>A testkit client ({@code testkit.*} system properties) keeps its store in its own game folder, so a test never
 * touches his real records; {@code -Dkiller560smod.sharedDir=<dir>} names any other folder.
 */
public final class SharedRecords {

    private static final Logger LOGGER = ModLog.get("killer560smod-sharedrecords");
    private static final Map<Path, SharedFile> FILES = new ConcurrentHashMap<>();
    private static final List<Runnable> ACCOUNT_LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Path root;

    private SharedRecords() {
    }

    /** The store's folder. */
    public static synchronized Path root() {
        Path r = root;
        if (r == null) {
            r = resolveRoot();
            root = r;
        }
        return r;
    }

    private static Path resolveRoot() {
        String override = System.getProperty("killer560smod.sharedDir");
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        if (System.getProperty("testkit.scenario") != null || System.getProperty("testkit.quiet") != null
                || System.getProperty("testkit.suite") != null) {
            // A test client's own sandbox, emptied when the client starts so one run's records never reach the next.
            Path dir = FabricLoader.getInstance().getGameDir().resolve("killer560smod-shared").toAbsolutePath().normalize();
            deleteTree(dir);
            return dir;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Paths.get(appData != null ? appData : home, "killer560smod", "shared");
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", "killer560smod", "shared");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return Paths.get(xdg != null && !xdg.isBlank() ? xdg : Paths.get(home, ".local", "share").toString(),
                "killer560smod", "shared");
    }

    private static void deleteTree(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            LOGGER.warn("[SharedRecords] Could not empty the test store {}", dir, e);
        }
    }

    /** The signed-in account's UUID without dashes, or "unknown" before the client has one. */
    public static String accountId() {
        try {
            Minecraft mc = Minecraft.getInstance();
            UUID id = mc == null || mc.getUser() == null ? null : mc.getUser().getProfileId();
            if (id != null) {
                lastAccount = id.toString().replace("-", "").toLowerCase(Locale.ROOT);
                return lastAccount;
            }
        } catch (RuntimeException notHere) {
            // Seen 2026-11-01: testkit 75 asked from its test thread and got no account (a run history landed under
            // accounts/unknown). Off the client thread, answer with the account last read on it.
        }
        return lastAccount != null ? lastAccount : "unknown";
    }

    private static volatile String lastAccount;

    /** Where {@code kind} lives for the signed-in account. */
    public static Path pathOf(RecordCatalog.Kind kind) {
        Path dir = kind.scope() == RecordCatalog.Scope.GLOBAL ? root() : root().resolve("accounts").resolve(accountId());
        return dir.resolve(kind.sharedName());
    }

    /** The shared file for {@code kind} (the signed-in account's, for an ACCOUNT record), with this instance's old file
     *  folded in on first use. */
    public static SharedFile file(RecordCatalog.Kind kind) {
        Path p = pathOf(kind).toAbsolutePath().normalize();
        SharedFile f = FILES.computeIfAbsent(p, path -> new SharedFile(path, kind.policy()));
        // Checked on every call, not only the first: an old file that turns up later (a config folder copied in from an
        // older install while the game runs) is folded in too. It is one exists() check when there is none.
        migrateLegacy(kind, f);
        return f;
    }

    /** Reads {@code kind}, never throwing: an empty object when there is nothing or it failed. */
    public static JsonObject load(SharedFile f) {
        try {
            return f.load();
        } catch (Exception e) {
            LOGGER.warn("[SharedRecords] Could not read {}", f.path(), e);
            return new JsonObject();
        }
    }

    /** Merges and writes, never throwing. Returns the merged file, or {@code mine} if the write failed. */
    public static JsonObject save(SharedFile f, JsonObject mine) {
        try {
            return f.save(mine);
        } catch (Exception e) {
            LOGGER.warn("[SharedRecords] Could not write {}", f.path(), e);
            return mine;
        }
    }

    private static synchronized void migrateLegacy(RecordCatalog.Kind kind, SharedFile f) {
        Path legacy;
        try {
            legacy = ModPaths.config(kind.legacyName());
            if (kind.legacyInner() != null) {
                legacy = legacy.resolve(kind.legacyInner());
            }
        } catch (RuntimeException e) {
            return;
        }
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(legacy);
            boolean added = f.importLegacy(bytes, legacy.toAbsolutePath().normalize().toString());
            Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            Path rel = gameDir.relativize(legacy.toAbsolutePath().normalize());
            Path moved = gameDir.resolve("killer560smod-records-moved").resolve(rel);
            Files.createDirectories(moved.getParent());
            if (Files.exists(moved)) {
                moved = moved.resolveSibling(moved.getFileName() + "." + System.currentTimeMillis());
            }
            Files.move(legacy, moved, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[SharedRecords] {} {} into {} (old file kept at {})",
                    added ? "Folded" : "Already had (a copy)", legacy.getFileName(), f.path(), moved);
        } catch (Exception e) {
            LOGGER.warn("[SharedRecords] Could not fold {} into {} - left it in place", legacy, f.path(), e);
        }
    }

    /** Runs {@code reload} after the account changes (the in-game account switcher), so ACCOUNT records follow it. */
    public static void onAccountChange(Runnable reload) {
        ACCOUNT_LISTENERS.add(reload);
    }

    /** Called by {@code AccountApplier} after it swapped the signed-in account. */
    public static void accountChanged() {
        for (Runnable r : ACCOUNT_LISTENERS) {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOGGER.warn("[SharedRecords] A record store failed to follow the account switch", e);
            }
        }
    }
}
