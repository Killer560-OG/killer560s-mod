package com.killer560.hub.menuthemes;

import com.killer560.hub.clicksound.ClickSounds;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Theme Background (killer560, 2026-10-21: "Make sure there is a way for people to easily import their own videos or gifs
 * as main backgrounds"): the files a theme's main menu background can be, and their import.
 * <p>Imported backgrounds live in {@code killer560smod-menu-themebackgrounds/} beside the settings (Open Backgrounds
 * Folder). Two kinds:
 * <ul>
 *   <li>a GIF, copied as it is and decoded by GIF Player's own {@code GifDecoder} (id {@code gif:<file name>});</li>
 *   <li>a video (or a GIF too big to hold decoded), turned by ffmpeg - the same tool Click Sounds imports video with - into
 *       a folder of JPEG frames at {@link #FPS} fps, at most {@link #MAX_SECONDS} s and {@link #MAX_W}x{@link #MAX_H}
 *       (id {@code video:<folder name>}). Nothing in the mod decodes video itself (Video Browser drives a browser
 *       window), so the frames are read back one at a time with ImageIO by {@link ThemeBackgroundPlayer}.</li>
 * </ul>
 * Import is "Copy as path" on the file and Import From Clipboard, as Click Sounds does. It runs on its own thread
 * (ffmpeg can take a while on a long video), never on the render thread.
 */
public final class ThemeBackgrounds {

    private static final Logger LOGGER = ModLog.get("killer560smod-themebg");

    public static final String GIF_PREFIX = "gif:";
    public static final String VIDEO_PREFIX = "video:";
    /** The theme's own animated scene. */
    public static final String NONE = "none";

    public static final int FPS = 20;
    public static final int MAX_SECONDS = 60;
    public static final int MAX_W = 960;
    public static final int MAX_H = 540;
    /** The file in a video folder that marks it as one and says its frame rate. */
    static final String INFO_FILE = "frames.txt";
    /** A GIF over this many decoded bytes is converted like a video instead (GifDecoder keeps every frame whole). */
    static final long MAX_GIF_DECODED = 128L * 1024 * 1024;

    private static final ExecutorService IMPORTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-themebg-import");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean importing;
    private static volatile String lastImport;
    private static volatile int imports;

    private ThemeBackgrounds() {
    }

    /** The import folder, created if missing. */
    public static Path folder() {
        Path dir = ModPaths.config("killer560smod-menu-themebackgrounds");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[ThemeBackground] could not create {}", dir, e);
        }
        return dir;
    }

    /** Every imported background's id, GIFs then videos, each by name. */
    public static List<String> imported() {
        List<String> gifs = new ArrayList<>();
        List<String> videos = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder())) {
            files.forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && name.toLowerCase(Locale.ROOT).endsWith(".gif")) {
                    gifs.add(GIF_PREFIX + name);
                } else if (Files.isDirectory(p) && Files.isRegularFile(p.resolve(INFO_FILE))) {
                    videos.add(VIDEO_PREFIX + name);
                }
            });
        } catch (IOException e) {
            // an unreadable folder lists nothing
        }
        gifs.sort(String.CASE_INSENSITIVE_ORDER);
        videos.sort(String.CASE_INSENSITIVE_ORDER);
        gifs.addAll(videos);
        return gifs;
    }

    /** None, then every imported background. */
    public static List<String> choices() {
        List<String> out = new ArrayList<>();
        out.add(NONE);
        out.addAll(imported());
        return out;
    }

    public static String step(String id, int dir) {
        List<String> all = choices();
        int i = Math.max(0, all.indexOf(id));
        return all.get(Math.floorMod(i + dir, all.size()));
    }

    public static String label(String id) {
        if (id == null || NONE.equals(id)) {
            return "Theme Scene";
        }
        if (id.startsWith(GIF_PREFIX)) {
            String f = id.substring(GIF_PREFIX.length());
            return (fileOf(id) == null ? "§c(missing) §r" : "") + f.substring(0, Math.max(0, f.length() - 4)) + " (GIF)";
        }
        if (id.startsWith(VIDEO_PREFIX)) {
            return (fileOf(id) == null ? "§c(missing) §r" : "") + id.substring(VIDEO_PREFIX.length()) + " (video)";
        }
        return "Theme Scene";
    }

    /** The GIF file or video folder an id names, or null when it is gone (or names something outside the folder). */
    static Path fileOf(String id) {
        if (id == null) {
            return null;
        }
        Path dir = folder().normalize();
        if (id.startsWith(GIF_PREFIX)) {
            Path f = dir.resolve(id.substring(GIF_PREFIX.length())).normalize();
            return dir.equals(f.getParent()) && Files.isRegularFile(f) ? f : null;
        }
        if (id.startsWith(VIDEO_PREFIX)) {
            Path f = dir.resolve(id.substring(VIDEO_PREFIX.length())).normalize();
            return dir.equals(f.getParent()) && Files.isRegularFile(f.resolve(INFO_FILE)) ? f : null;
        }
        return null;
    }

    // ---------------------------------------------------------------- import

    public static boolean importing() {
        return importing;
    }

    /** The last import's message (green on success, red on refusal), or null before any. */
    public static String lastImport() {
        return lastImport;
    }

    public static int imports() {
        return imports;
    }

    /**
     * Imports the file whose path is on the clipboard, on the import thread. {@code done} gets the new background's id
     * (null on failure) on that thread; {@link #lastImport()} holds the message to show.
     */
    public static void importClipboardAsync(String clip, Consumer<String> done) {
        Path path;
        try {
            path = ClickSounds.clipboardPath(clip, "Copy a video or GIF file's path first (Shift + right click it, Copy as path).");
        } catch (IOException e) {
            lastImport = "§c" + e.getMessage();
            done.accept(null);
            return;
        }
        importAsync(path, done);
    }

    public static void importAsync(Path source, Consumer<String> done) {
        if (importing) {
            lastImport = "§cAn import is already running.";
            done.accept(null);
            return;
        }
        importing = true;
        lastImport = "§7Importing " + source.getFileName() + "...";
        IMPORTER.execute(() -> {
            String id = null;
            try {
                id = importFile(source);
                imports++;
                lastImport = "§aImported " + label(id) + " and set it as this theme's background.";
            } catch (IOException | RuntimeException e) {
                lastImport = "§c" + e.getMessage();
                LOGGER.warn("[ThemeBackground] import of {} failed: {}", source, e.getMessage());
            } finally {
                importing = false;
            }
            done.accept(id);
        });
    }

    /** Copies a GIF in, or turns a video (or a huge GIF) into a frames folder. Blocking: the import thread only. */
    static String importFile(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("No such file.");
        }
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^A-Za-z0-9 _-]", "_").strip();
        if (base.isEmpty()) {
            base = "background";
        }
        if (ext.equals("gif") && gifFits(source)) {
            Path target = folder().resolve(base + ".gif");
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return GIF_PREFIX + target.getFileName();
        }
        return convertVideo(source, name, base);
    }

    /** A GIF GifDecoder can hold whole (all frames decoded under {@link #MAX_GIF_DECODED}). */
    private static boolean gifFits(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false);
                long bytes = (long) reader.getWidth(0) * reader.getHeight(0) * 4L * reader.getNumImages(true);
                return bytes <= MAX_GIF_DECODED;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** ffmpeg: the first {@link #MAX_SECONDS} s as JPEG frames at {@link #FPS} fps, scaled down to fit {@link #MAX_W}x{@link #MAX_H}. */
    private static String convertVideo(Path source, String name, String base) throws IOException {
        Path dir = folder();
        String folderName = base;
        for (int n = 2; Files.exists(dir.resolve(folderName)); n++) {
            folderName = base + " " + n;
        }
        Path work = Files.createTempDirectory("killer560smod-themebg-");
        try {
            return convertVideo(source, name, dir, folderName, work);
        } finally {
            // The ffmpeg work folder is this import's own scratch: whatever a failed run left in it goes.
            try (Stream<Path> left = Files.list(work)) {
                for (Path f : left.toList()) {
                    Files.deleteIfExists(f);
                }
            } catch (IOException ignored) {
            }
            Files.deleteIfExists(work);
        }
    }

    private static String convertVideo(Path source, String name, Path dir, String folderName, Path work) throws IOException {
        Path log = work.resolve("ffmpeg.log");
        Process p;
        try {
            p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-v", "error", "-i", source.toAbsolutePath().toString(),
                    "-t", String.valueOf(MAX_SECONDS), "-an",
                    // Scaled DOWN to fit MAX_W x MAX_H, never up: a smaller video keeps its own size (less to read and upload).
                    "-vf", "fps=" + FPS + ",scale=w='min(" + MAX_W + ",iw)':h='min(" + MAX_H + ",ih)'"
                            + ":force_original_aspect_ratio=decrease",
                    "-q:v", "5", work.resolve("f%05d.jpg").toAbsolutePath().toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            throw new IOException(name + " needs ffmpeg to import (videos, and GIFs too big to play as they are). ffmpeg was"
                    + " not found: install it, then restart Minecraft. A GIF imports without it.");
        }
        try {
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IOException("ffmpeg took too long on " + name + ".");
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        }
        List<Path> frames;
        try (Stream<Path> s = Files.list(work)) {
            frames = s.filter(f -> f.getFileName().toString().endsWith(".jpg")).sorted().toList();
        }
        if (p.exitValue() != 0 || frames.isEmpty()) {
            String errors = Files.isRegularFile(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
            if (errors.contains("matches no streams") || errors.contains("does not contain any stream")) {
                throw new IOException(name + " has no picture to import.");
            }
            throw new IOException(name + " is not a video or GIF that ffmpeg can read.");
        }
        Path target = dir.resolve(folderName);
        Files.createDirectories(target);
        for (Path f : frames) {
            Files.move(f, target.resolve(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(target.resolve(INFO_FILE), "fps=" + FPS + "\nframes=" + frames.size() + "\nsource=" + name + "\n",
                StandardCharsets.UTF_8);
        return VIDEO_PREFIX + folderName;
    }
}
