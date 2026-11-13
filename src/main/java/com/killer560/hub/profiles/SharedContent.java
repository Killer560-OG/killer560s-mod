package com.killer560.hub.profiles;

import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One-time fold of every profile's saved copy of shared content ({@link ProfileData#SHARED_NAMES}: routes, waypoints,
 * layouts, imported files) into the live files (profshare-1112). Until then each profile saved its own copy and applying
 * one replaced the live files, so a route lived only in the profile that was active when he made it. A file missing live
 * is copied in; a live file of the same name always wins and nothing is written beside it (killer560 2026-11-13: only his
 * own files in those folders, no "<name> - <profile>" copies). Nothing is deleted, and the profiles' own copies stay
 * where they are, so a different older version is still in that profile's folder.
 */
public final class SharedContent {

    private static final Logger LOGGER = ModLog.get("killer560smod-profiles");

    /** Written once the fold ran, so it never runs twice. */
    public static final String MARKER = "shared-content-merged-v1.txt";

    private SharedContent() {
    }

    /** Folds every profile under {@code profilesDir} in, once. Returns the number of files copied into the live tree. */
    public static int mergeOnce(Path profilesDir) {
        Path marker = profilesDir.resolve(MARKER);
        if (!Files.isDirectory(profilesDir) || Files.exists(marker)) return 0;
        int copied = 0;
        List<Path> profiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesDir, Files::isDirectory)) {
            stream.forEach(profiles::add);
        } catch (IOException e) {
            LOGGER.warn("[Profiles] could not list profiles for the shared-content merge", e);
            return 0;
        }
        profiles.sort(null);
        for (Path profile : profiles) {
            String profileName = profile.getFileName().toString();
            for (String key : ProfileData.SHARED_NAMES) {
                String name = ModPaths.PREFIX + key;
                // AP3's pre-folder file goes where the store reads it, as its default chains file.
                Path live = key.equals("ap3.json")
                        ? ModPaths.config(ModPaths.PREFIX + "ap3").resolve(com.killer560.hub.ap3.Ap3Store.DEFAULT_CONFIG_NAME)
                        : ModPaths.config(name);
                // A folder sits in data/, a flat .json file at the profile's top level (a format-1 profile kept it there).
                for (Path saved : List.of(profile.resolve(ProfileData.DATA_DIR).resolve(name), profile.resolve(name))) {
                    copied += fold(saved, live, profileName);
                }
            }
        }
        try {
            Files.writeString(marker, "Shared content of every profile was folded into the live files; see the log.\n");
        } catch (IOException e) {
            LOGGER.warn("[Profiles] could not write {}", marker, e);
        }
        LOGGER.info("[Profiles] Shared content: folded {} file(s) from {} profile(s) into the live files", copied,
                profiles.size());
        return copied;
    }

    private static int fold(Path saved, Path live, String profile) {
        if (Files.isRegularFile(saved)) {
            return copyKeeping(saved, live, profile);
        }
        if (!Files.isDirectory(saved)) return 0;
        int copied = 0;
        try (var walk = Files.walk(saved)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String rel = saved.relativize(f).toString();
                if (ProfileData.isExcludedFileName(f.getFileName().toString())) continue;
                copied += copyKeeping(f, live.resolve(rel), profile);
            }
        } catch (IOException e) {
            LOGGER.warn("[Profiles] could not read {}", saved, e);
        }
        return copied;
    }

    /** Copies {@code from} to {@code to} when nothing is there yet; a live file is never touched. Returns 1 when copied. */
    private static int copyKeeping(Path from, Path to, String profile) {
        try {
            if (Files.exists(to)) {
                if (!sameBytes(from, to)) {
                    LOGGER.info("[Profiles] Shared content: kept the live {}; profile \"{}\"'s different copy stays in its folder",
                            to.getFileName(), profile);
                }
                return 0;
            }
            Files.createDirectories(to.getParent());
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            return 1;
        } catch (IOException e) {
            LOGGER.warn("[Profiles] could not fold {} into {}", from, to, e);
            return 0;
        }
    }

    private static boolean sameBytes(Path a, Path b) throws IOException {
        return Files.size(a) == Files.size(b) && Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
    }
}
