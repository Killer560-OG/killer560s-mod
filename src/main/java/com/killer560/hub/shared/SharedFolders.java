package com.killer560.hub.shared;

import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * User-file folders shared by every instance and Minecraft version on the PC (killer560, 2026-11-04: imported menu
 * backgrounds and GIFs "universal"), under the shared store root ({@link SharedRecords#root}: a testkit client gets its
 * sandbox). Not records: no merge, just a folder. The first use per folder moves the instance's old config folder in.
 */
public final class SharedFolders {

    private static final Logger LOGGER = ModLog.get("killer560smod-sharedfolders");
    private static final Map<String, Path> READY = new ConcurrentHashMap<>();

    private SharedFolders() {
    }

    /** {@code <root>/<name>}, created; {@code legacy} (the old per-instance folder) is moved in the first time. */
    public static Path folder(String name, Path legacy) {
        return READY.computeIfAbsent(name, n -> {
            Path dir = SharedRecords.root().resolve(n);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.warn("could not create {}", dir, e);
            }
            moveInto(legacy, dir);
            return dir;
        });
    }

    /** Forgets which folders were set up, so the next {@link #folder} moves again (testkit). */
    public static void forgetForTest() {
        READY.clear();
    }

    /**
     * Moves every entry of {@code from} into {@code to}. A name already there with the same bytes is dropped from
     * {@code from}; with different bytes the mover keeps both under {@code name-2}, {@code name-3}...
     */
    public static void moveInto(Path from, Path to) {
        if (from == null || !Files.isDirectory(from) || from.toAbsolutePath().normalize().equals(to.toAbsolutePath().normalize())) {
            return;
        }
        int moved = 0;
        try (Stream<Path> entries = Files.list(from)) {
            for (Path src : entries.toList()) {
                try {
                    moved += moveOne(src, to);
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("could not move {} into {}", src, to, e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("could not move the files of {}", from, e);
        }
        try (Stream<Path> left = Files.list(from)) {
            if (left.findAny().isEmpty()) Files.delete(from);
        } catch (IOException e) {
            // a folder that stays is moved from again next time
        }
        if (moved > 0) LOGGER.info("moved {} item(s) from {} to {}", moved, from, to);
    }

    private static int moveOne(Path src, Path to) throws IOException {
        String name = src.getFileName().toString();
        Path dst = to.resolve(name);
        int dot = Files.isDirectory(src) ? -1 : name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; Files.exists(dst); n++) {
            if (same(src, dst)) {
                deleteTree(src);
                return 0;
            }
            dst = to.resolve(base + "-" + n + ext);
        }
        try {
            Files.move(src, dst);
        } catch (IOException e) {
            copyTree(src, dst);
            deleteTree(src);
        }
        return 1;
    }

    private static boolean same(Path a, Path b) throws IOException {
        if (Files.isRegularFile(a) && Files.isRegularFile(b)) return Files.mismatch(a, b) < 0;
        if (!Files.isDirectory(a) || !Files.isDirectory(b)) return false;
        try (Stream<Path> as = Files.list(a); Stream<Path> bs = Files.list(b)) {
            var la = as.toList();
            if (la.size() != bs.count()) return false;
            for (Path p : la) {
                Path q = b.resolve(p.getFileName().toString());
                if (!Files.exists(q) || !same(p, q)) return false;
            }
        }
        return true;
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        if (!Files.isDirectory(src)) {
            Files.copy(src, dst);
            return;
        }
        Files.createDirectories(dst);
        try (Stream<Path> kids = Files.list(src)) {
            for (Path k : kids.toList()) copyTree(k, dst.resolve(k.getFileName().toString()));
        }
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (Stream<Path> kids = Files.list(p)) {
                for (Path k : kids.toList()) deleteTree(k);
            }
        }
        Files.delete(p);
    }
}
