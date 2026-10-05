package com.killer560.hub.voicetotext;

import com.killer560.hub.util.BoundedDownload;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * The Vosk speech recognizer, fetched on first use instead of shipped inside the mod jar.
 * <p>
 * Vosk's own jar is 25.9 MB, almost all of it native libraries for three operating systems (libvosk.so,
 * libvosk.dll with its three MinGW runtime DLLs, libvosk.dylib), and until 2026-10-05 it was bundled Jar-in-Jar,
 * which made it 70% of this mod's distributed jar for a feature most players never turn on. It now follows the
 * speech model's pattern: the first time Voice To Text is really used, the exact {@code vosk-0.3.45.jar} is
 * downloaded from Maven Central into the voice config folder, checked against {@link #SHA256} (the hash of the
 * same artifact the build used to bundle), and loaded through a child {@link URLClassLoader}. A file that does not
 * match is never loaded: a bad download is deleted and the error reported; a cached copy that no longer matches is
 * deleted and fetched again.
 * <p>
 * Why reflection: classes defined by the child loader cannot be named from mod code, because Fabric's class
 * loader (which loads this class) would try to resolve {@code org.vosk.Model} itself and fail - Vosk is no longer
 * on its classpath. So the handful of Vosk calls the feature makes go through the {@link Method}s held here, and
 * {@code org.vosk} is deliberately NOT on the mod's compile classpath either, so a direct reference cannot creep
 * back in and compile only to throw {@code NoClassDefFoundError} in game.
 * <p>
 * JNA stays bundled with the mod. The child loader's parent is this class's loader, so Vosk's {@code com.sun.jna}
 * references resolve to the bundled JNA; Vosk's own native loading ({@code LibVosk}'s static initialiser calls
 * {@code Native.extractFromResourcePath(..., LibVosk.class.getClassLoader())} and {@code Native.register}) reads
 * the native libraries as resources of the loader that defined {@code LibVosk}, which is the child loader over the
 * downloaded jar - so they are found exactly as they were inside the bundled one.
 */
public final class VoskLibrary {

    private static final Logger LOGGER = ModLog.get("killer560smod-voicetotext");

    public static final String VERSION = "0.3.45";
    public static final String JAR_NAME = "vosk-" + VERSION + ".jar";
    /** SHA-256 of {@code com.alphacephei:vosk:0.3.45}'s jar, from the copy Gradle resolved for the bundled build. */
    public static final String SHA256 = "9e38e96e4448a41d889bb254b2f8424945554945c6e77e1cd97a3d8633fac2ba";
    public static final long SIZE_BYTES = 25_861_916L;
    public static final String DEFAULT_URL = "https://repo1.maven.org/maven2/com/alphacephei/vosk/" + VERSION + "/" + JAR_NAME;
    /** Routed like every other download, so the testkit's offline switch and overrides reach it. */
    public static final String DOWNLOAD_URL = ModNet.url("maven-central", DEFAULT_URL);

    /** Far above the real size; only a broken or hostile response ever reaches it. */
    private static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private static final String PART_PREFIX = JAR_NAME + ".";
    private static final String PART_SUFFIX = ".part";

    private static VoskLibrary loaded;

    private final URLClassLoader loader;
    private final Constructor<?> modelCtor;
    private final Constructor<?> recognizerCtor;
    private final Method acceptWaveForm;
    private final Method finalResult;
    private final Method setLogLevel;
    private final Class<?> logLevel;

    private VoskLibrary(URLClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        Class<?> model = Class.forName("org.vosk.Model", false, loader);
        Class<?> recognizer = Class.forName("org.vosk.Recognizer", false, loader);
        Class<?> libVosk = Class.forName("org.vosk.LibVosk", false, loader);
        this.logLevel = Class.forName("org.vosk.LogLevel", false, loader);
        this.modelCtor = model.getConstructor(String.class);
        this.recognizerCtor = recognizer.getConstructor(model, float.class);
        this.acceptWaveForm = recognizer.getMethod("acceptWaveForm", byte[].class, int.class);
        this.finalResult = recognizer.getMethod("getFinalResult");
        this.setLogLevel = libVosk.getMethod("setLogLevel", logLevel);
    }

    /** True when a verified-looking copy is already on disk (by name and size only - {@link #load} still hashes it).
     *  Cheap enough for the main thread; used to tell the player whether a download is coming. */
    public static boolean isCached(Path dir) {
        try {
            Path jar = dir.resolve(JAR_NAME);
            return Files.isRegularFile(jar) && Files.size(jar) == SIZE_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Makes sure a verified {@code vosk-0.3.45.jar} is in {@code dir} (downloading it from {@code url} if not) and
     * returns Vosk loaded from it. Later calls in the same session return the same instance. Blocking - call from a
     * background thread.
     *
     * @param onDownload told once, before a download starts, so the caller can say so to the player
     */
    public static synchronized VoskLibrary load(Path dir, String url, Consumer<String> onDownload) throws IOException {
        if (loaded != null) {
            return loaded;
        }
        Files.createDirectories(dir);
        deleteLeftoverParts(dir);
        Path jar = dir.resolve(JAR_NAME);
        if (Files.exists(jar)) {
            String have = sha256(jar);
            if (SHA256.equals(have)) {
                LOGGER.info("[VoiceToText] Using cached {} (SHA-256 verified)", jar);
            } else {
                LOGGER.warn("[VoiceToText] Cached {} has SHA-256 {}, expected {} - deleting it and downloading again",
                        jar, have, SHA256);
                Files.delete(jar);
            }
        }
        if (!Files.exists(jar)) {
            if (onDownload != null) {
                onDownload.accept(url);
            }
            download(url, dir, jar);
        }
        URLClassLoader child = new URLClassLoader("killer560smod-vosk", new URL[]{jar.toUri().toURL()},
                VoskLibrary.class.getClassLoader());
        try {
            VoskLibrary lib = new VoskLibrary(child);
            // Runs LibVosk's static initialiser now - native extraction and JNA registration - so a native problem
            // fails here, before the 40MB model download, rather than after it.
            Class.forName("org.vosk.LibVosk", true, child);
            loaded = lib;
            return lib;
        } catch (ReflectiveOperationException | LinkageError e) {
            child.close();
            throw new IOException("the speech engine could not be loaded: " + e, e);
        }
    }

    /** Downloads to a temporary file beside {@code jar}, verifies it, and only then moves it into place, so a cut-off
     *  or corrupt download never leaves anything at {@code jar}. */
    private static void download(String url, Path dir, Path jar) throws IOException {
        LOGGER.info("[VoiceToText] Downloading the speech engine from {}", url);
        Path part = Files.createTempFile(dir, PART_PREFIX, PART_SUFFIX);
        try {
            MessageDigest digest = newDigest();
            try {
                URLConnection conn = ModNet.open(url);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(120_000);
                if (conn instanceof HttpURLConnection http && http.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + http.getResponseCode() + " from " + url);
                }
                try (InputStream in = new DigestInputStream(conn.getInputStream(), digest)) {
                    BoundedDownload.toFile(in, part, MAX_DOWNLOAD_BYTES, "speech engine download");
                }
            } catch (IOException e) {
                throw new IOException("could not download the speech engine (" + e.getMessage()
                        + ") - check your connection and try again", e);
            }
            long size = Files.size(part);
            String got = HexFormat.of().formatHex(digest.digest());
            if (size != SIZE_BYTES || !SHA256.equals(got)) {
                LOGGER.warn("[VoiceToText] Downloaded speech engine is {} bytes with SHA-256 {}; expected {} bytes, {}",
                        size, got, SIZE_BYTES, SHA256);
                throw new IOException("the downloaded speech engine failed its integrity check - not loading it");
            }
            try {
                Files.move(part, jar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            }
            LOGGER.info("[VoiceToText] Speech engine saved to {}", jar);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /** A game killed mid-download leaves its temporary file behind; nothing else ever reads one. */
    private static void deleteLeftoverParts(Path dir) {
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(dir, PART_PREFIX + "*" + PART_SUFFIX)) {
            for (Path p : parts) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            LOGGER.warn("[VoiceToText] Could not clear old partial downloads in {}", dir, e);
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", e);
        }
    }

    // ------------------------------------------------------------------ the Vosk calls the feature makes

    /** {@code new org.vosk.Model(path)}. Close it with {@link AutoCloseable#close()}. */
    public AutoCloseable newModel(String path) throws Exception {
        return (AutoCloseable) invoke(() -> modelCtor.newInstance(path));
    }

    /** One-shot recognition of 16-bit mono PCM: a fresh {@code Recognizer}, the whole buffer, its final result JSON. */
    public String recognize(Object model, float sampleRate, byte[] audio) throws Exception {
        try (AutoCloseable recognizer = (AutoCloseable) invoke(() -> recognizerCtor.newInstance(model, sampleRate))) {
            invoke(() -> acceptWaveForm.invoke(recognizer, audio, audio.length));
            return (String) invoke(() -> finalResult.invoke(recognizer));
        }
    }

    /** {@code LibVosk.setLogLevel(LogLevel.valueOf(name))} - WARNINGS, INFO or DEBUG. */
    public void setLogLevel(String name) throws Exception {
        Object level = logLevel.getMethod("valueOf", String.class).invoke(null, name);
        invoke(() -> setLogLevel.invoke(null, level));
    }

    /** The loader Vosk was defined by - for checks, not for use. */
    public ClassLoader classLoader() {
        return loader;
    }

    private interface Call {
        Object run() throws ReflectiveOperationException;
    }

    /** Unwraps reflection's wrapper so callers (and the player's error message) see Vosk's own exception. */
    private static Object invoke(Call call) throws Exception {
        try {
            return call.run();
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }
}
