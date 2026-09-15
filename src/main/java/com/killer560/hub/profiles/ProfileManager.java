package com.killer560.hub.profiles;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Real config-file profile system - killer560's "custom mod profiles... clicking between them will
 * change which settings" request. A profile is a real, plain snapshot of every one of this mod's own
 * `killer560smod-*.json` setting files, copied into its own folder under
 * {@code config/killer560smod-profiles/<name>/}. Deliberately the simplest correct implementation
 * rather than a live-reloading one: every one of the ~50 existing {@code XyzConfig} classes caches its
 * settings in a private static field loaded once per game session, and none of them expose a way to
 * force a reload - rather than touch all ~50 of those classes (high real risk of a subtle mistake in at
 * least one), switching a profile just overwrites the live JSON files on disk and asks for a restart to
 * actually pick them up. That trade-off is stated plainly in the Profiles tab and every load/save chat
 * message, not hidden.
 * <p>
 * Deliberately EXCLUDES real credentials/caches that should never be silently overwritten or shared:
 * the direct-session-login token, per-account proxy assignments (tied to this user's own saved
 * accounts/proxy service, meaningless on another PC), and the RNG-meter/storage-overlay caches (real
 * run data, not a "setting").
 */
public final class ProfileManager {

    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path PROFILES_DIR = CONFIG_DIR.resolve("killer560smod-profiles");
    private static final Path ACTIVE_MARKER = CONFIG_DIR.resolve("killer560smod-active-profile.txt");

    private static final Set<String> EXCLUDED_FILES = Set.of(
            "killer560smod-session-login.json",
            "killer560smod-account-proxies.json",
            "killer560smod-rng-item-log.json",
            "killer560smod-storageoverlay-cache.json",
            // Croesus Profit Logger's claim history + totals - real run data, not a setting.
            "killer560smod-croesus-log.json",
            // Experimentation Table profit tracker's session log + totals - also real run data.
            "killer560smod-experiments-profit.json"
    );

    private ProfileManager() {
    }

    public record Result(boolean success, String message) {
    }

    /** Filesystem-safe folder name for a profile - anything outside letters/digits/space/underscore/
     *  hyphen becomes an underscore, matching the mod's other simple sanitizers (e.g. Posmsg names). */
    private static String sanitize(String name) {
        return name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
    }

    public static List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(PROFILES_DIR)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PROFILES_DIR)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static String getActiveProfile() {
        try {
            if (Files.exists(ACTIVE_MARKER)) {
                String name = Files.readString(ACTIVE_MARKER, StandardCharsets.UTF_8).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private static List<Path> liveConfigFiles() {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONFIG_DIR, "killer560smod-*.json")) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && !EXCLUDED_FILES.contains(entry.getFileName().toString())) {
                    files.add(entry);
                }
            }
        } catch (IOException ignored) {
        }
        return files;
    }

    /** Snapshots every current setting file into a new (or overwritten) profile folder. */
    public static Result saveCurrentAsProfile(String rawName) {
        String name = sanitize(rawName);
        if (name.isEmpty()) {
            return new Result(false, "§cProfile name can't be empty.");
        }
        try {
            Path dir = PROFILES_DIR.resolve(name);
            Files.createDirectories(dir);
            int count = 0;
            for (Path file : liveConfigFiles()) {
                Files.copy(file, dir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
            return new Result(true, "§a[Profiles] Saved " + count + " setting file(s) as profile \"" + name + "\".");
        } catch (IOException e) {
            return new Result(false, "§cFailed to save profile: " + e.getMessage());
        }
    }

    /** Overwrites every live setting file with the ones stored in the given profile, and marks it
     *  active. Does NOT reload any already-running feature's cached settings - see class doc. */
    public static Result applyProfile(String rawName) {
        String name = sanitize(rawName);
        Path dir = PROFILES_DIR.resolve(name);
        if (!Files.isDirectory(dir)) {
            return new Result(false, "§cNo profile named \"" + name + "\".");
        }
        try {
            int count = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
                for (Path file : stream) {
                    Files.copy(file, CONFIG_DIR.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
            Files.writeString(ACTIVE_MARKER, name, StandardCharsets.UTF_8);
            return new Result(true, "§a[Profiles] Applied " + count + " setting file(s) from \"" + name
                    + "\". Restart Minecraft for every feature to pick up the change.");
        } catch (IOException e) {
            return new Result(false, "§cFailed to apply profile: " + e.getMessage());
        }
    }

    public static Result deleteProfile(String rawName) {
        String name = sanitize(rawName);
        Path dir = PROFILES_DIR.resolve(name);
        if (!Files.isDirectory(dir)) {
            return new Result(false, "§cNo profile named \"" + name + "\".");
        }
        try {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path file : stream) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
            if (name.equals(getActiveProfile())) {
                Files.deleteIfExists(ACTIVE_MARKER);
            }
            return new Result(true, "§a[Profiles] Deleted \"" + name + "\".");
        } catch (IOException e) {
            return new Result(false, "§cFailed to delete profile: " + e.getMessage());
        }
    }

    /** Zips a profile's folder into {@code config/killer560smod-profiles/<name>.zip} - a single real
     *  file the user can hand to a friend (Discord attachment, etc.) with no path guessing needed. */
    public static Result exportProfile(String rawName) {
        String name = sanitize(rawName);
        Path dir = PROFILES_DIR.resolve(name);
        if (!Files.isDirectory(dir)) {
            return new Result(false, "§cNo profile named \"" + name + "\".");
        }
        Path zipPath = PROFILES_DIR.resolve(name + ".zip");
        try (OutputStream fos = Files.newOutputStream(zipPath);
             ZipOutputStream zos = new ZipOutputStream(fos);
             DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, zos);
                zos.closeEntry();
            }
            return new Result(true, "§a[Profiles] Exported to " + zipPath + " - send that file to share it.");
        } catch (IOException e) {
            return new Result(false, "§cFailed to export profile: " + e.getMessage());
        }
    }

    /** Imports a shared zip into a new profile. {@code source} may be an absolute path, or just a
     *  filename to look for directly inside {@code config/killer560smod-profiles/} (the simplest case -
     *  a friend drops the .zip they were sent into that folder and only types its name). */
    public static Result importProfile(String source, String rawNewName) {
        String name = sanitize(rawNewName);
        if (name.isEmpty()) {
            return new Result(false, "§cProfile name can't be empty.");
        }
        Path zipPath = Path.of(source);
        if (!Files.isRegularFile(zipPath)) {
            zipPath = PROFILES_DIR.resolve(source);
        }
        if (!Files.isRegularFile(zipPath)) {
            return new Result(false, "§cCouldn't find a file at \"" + source
                    + "\" (checked that path directly, and inside config/killer560smod-profiles/).");
        }
        Path dir = PROFILES_DIR.resolve(name);
        try {
            Files.createDirectories(dir);
            int count = 0;
            try (InputStream fis = Files.newInputStream(zipPath); ZipInputStream zis = new ZipInputStream(fis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = Path.of(entry.getName()).getFileName().toString();
                    if (entry.isDirectory() || !entryName.endsWith(".json")) {
                        continue;
                    }
                    Files.copy(zis, dir.resolve(entryName), StandardCopyOption.REPLACE_EXISTING);
                    count++;
                }
            }
            return new Result(true, "§a[Profiles] Imported " + count + " setting file(s) into new profile \""
                    + name + "\". Use /killer560 profile load " + name + " to switch to it.");
        } catch (IOException e) {
            return new Result(false, "§cFailed to import profile: " + e.getMessage());
        }
    }
}
