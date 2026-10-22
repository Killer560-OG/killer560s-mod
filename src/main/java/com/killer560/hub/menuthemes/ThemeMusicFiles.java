package com.killer560.hub.menuthemes;

import com.killer560.hub.clicksound.ClickSounds;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Theme Music you import yourself (killer560, 2026-10-21: "and their own sounds for the themes as well"). The same import
 * path as Click Sounds: copy the file's path (Shift + right click, Copy as path), press Import Music, and it is copied
 * into {@code killer560smod-menu-thememusic/} (Open Music Folder) and set as that theme's track.
 * <p>An Ogg Vorbis file is kept as it is. Anything else - an mp3, a wav, a video (its sound only) - is turned into .ogg by
 * ffmpeg (which must be installed), its first {@link #MAX_SECONDS} s. Unlike a click, a song is long, so it is never
 * decoded whole: the sound engine STREAMS it, through {@code SoundBufferLibraryThemeMusicMixin}, which answers the
 * engine's stream request for {@code killer560smod:sounds/thememusic/<key>.ogg} from the file (looping the way vanilla's
 * music loops, {@link LoopingAudioStream}). The import itself runs on its own thread.
 */
public final class ThemeMusicFiles {

    private static final Logger LOGGER = ModLog.get("killer560smod-thememusic");

    public static final String IMPORT_PREFIX = "import:";
    static final String NAMESPACE = "killer560smod";
    /** The longest part of a song kept by a conversion. */
    public static final int MAX_SECONDS = 600;
    /** The biggest .ogg kept as it is (a 10-minute song at a high quality is well under it). */
    public static final long MAX_OGG_BYTES = 64L * 1024 * 1024;

    /** Imported files by the key their sound path carries (an Identifier cannot hold spaces or capitals). */
    private static final Map<String, Path> KEYS = new ConcurrentHashMap<>();

    private static final ExecutorService IMPORTER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-thememusic-import");
        t.setDaemon(true);
        return t;
    });

    private static volatile boolean importing;
    private static volatile String lastImport;
    private static volatile int streamsOpened;

    private ThemeMusicFiles() {
    }

    public static Path folder() {
        Path dir = ModPaths.config("killer560smod-menu-thememusic");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("[ThemeMusic] could not create {}", dir, e);
        }
        return dir;
    }

    /** The .ogg files in the folder, by name. */
    public static List<String> imported() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder())) {
            files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
                    .forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException e) {
            // an unreadable folder lists nothing
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** The file an {@code import:} id names, or null when it is gone (or names something outside the folder). */
    static Path fileOf(String id) {
        if (id == null || !id.startsWith(IMPORT_PREFIX)) {
            return null;
        }
        Path dir = folder().normalize();
        Path f = dir.resolve(id.substring(IMPORT_PREFIX.length())).normalize();
        return dir.equals(f.getParent()) && Files.isRegularFile(f) ? f : null;
    }

    public static String label(String id) {
        String f = id.substring(IMPORT_PREFIX.length());
        int dot = f.lastIndexOf('.');
        return (fileOf(id) == null ? "§c(missing) §r" : "") + (dot > 0 ? f.substring(0, dot) : f);
    }

    /** The sound location an imported file plays under; its file is remembered for the stream request. */
    static Identifier keyFor(Path file) {
        long modified = 0;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
        }
        // The modification time is in the key, so a file imported again under the same name starts afresh.
        String key = "thememusic/m" + Integer.toHexString(file.getFileName().toString().toLowerCase(Locale.ROOT).hashCode())
                + "_" + Long.toHexString(modified);
        KEYS.put(key, file);
        return Identifier.fromNamespaceAndPath(NAMESPACE, key);
    }

    /** True for the stream path ({@code killer560smod:sounds/thememusic/<key>.ogg}) an imported track asks the engine for. */
    public static boolean isImported(Identifier id) {
        return id != null && NAMESPACE.equals(id.getNamespace()) && id.getPath().startsWith("sounds/thememusic/");
    }

    /** The engine's stream for an imported track, opened off the render thread like vanilla's. */
    public static CompletableFuture<AudioStream> stream(Identifier id, boolean looping) {
        String path = id.getPath();
        String key = path.substring("sounds/".length(), path.length() - (path.endsWith(".ogg") ? 4 : 0));
        Path file = KEYS.get(key);
        if (file == null) {
            return CompletableFuture.failedFuture(new IOException("unknown imported theme music " + key));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                InputStream in = new BufferedInputStream(Files.newInputStream(file));
                streamsOpened++;
                return looping ? new LoopingAudioStream(JOrbisAudioStream::new, in) : new JOrbisAudioStream(in);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    // ---------------------------------------------------------------- import

    public static boolean importing() {
        return importing;
    }

    public static String lastImport() {
        return lastImport;
    }

    /** Streams the engine opened for imported tracks (testkit evidence: the file really was handed to the engine). */
    public static int streamsOpened() {
        return streamsOpened;
    }

    /** Imports the clipboard's file on the import thread; {@code done} gets the new choice's id, or null on failure. */
    public static void importClipboardAsync(String clip, Consumer<String> done) {
        Path path;
        try {
            path = ClickSounds.clipboardPath(clip, "Copy a sound or video file's path first (Shift + right click it, Copy as path).");
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
                id = IMPORT_PREFIX + importFile(source);
                lastImport = "§aImported " + label(id) + " and set it as this theme's music.";
            } catch (IOException | RuntimeException e) {
                lastImport = "§c" + e.getMessage();
                LOGGER.warn("[ThemeMusic] import of {} failed: {}", source, e.getMessage());
            } finally {
                importing = false;
            }
            done.accept(id);
        });
    }

    /** Copies a readable .ogg in, or converts anything else with ffmpeg. Blocking: the import thread only. */
    static String importFile(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("No such file.");
        }
        String name = source.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".ogg") && Files.size(source) <= MAX_OGG_BYTES && readableOgg(source)) {
            Path target = folder().resolve(name);
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            return name;
        }
        return convert(source, name);
    }

    /** The engine's own Ogg reader opens it and gives some sound. */
    private static boolean readableOgg(Path file) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file));
             JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
            return stream.read(4096).hasRemaining();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String convert(Path source, String name) throws IOException {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^A-Za-z0-9 _-]", "_").strip();
        if (base.isEmpty()) {
            base = "music";
        }
        Path work = Files.createTempFile("killer560smod-thememusic-", ".ogg");
        Path log = Files.createTempFile("killer560smod-thememusic-", ".log");
        try {
            Process p;
            try {
                p = new ProcessBuilder("ffmpeg", "-y", "-hide_banner", "-v", "error",
                        "-i", source.toAbsolutePath().toString(), "-vn", "-t", String.valueOf(MAX_SECONDS),
                        "-ac", "2", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "4", work.toAbsolutePath().toString())
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            } catch (IOException e) {
                throw new IOException(name + " needs ffmpeg to import (only .ogg files import without it). ffmpeg was not"
                        + " found: install it, then restart Minecraft.");
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
            if (p.exitValue() != 0 || !Files.isRegularFile(work) || Files.size(work) == 0 || !readableOgg(work)) {
                String errors = Files.readString(log, StandardCharsets.UTF_8);
                if (errors.contains("matches no streams") || errors.contains("does not contain any stream")) {
                    throw new IOException(name + " has no audio track, so there is no music to import.");
                }
                throw new IOException(name + " is not a sound or video file that ffmpeg can read.");
            }
            Path target = folder().resolve(base + ".ogg");
            Files.move(work, target, StandardCopyOption.REPLACE_EXISTING);
            return target.getFileName().toString();
        } finally {
            Files.deleteIfExists(work);
            Files.deleteIfExists(log);
        }
    }
}
