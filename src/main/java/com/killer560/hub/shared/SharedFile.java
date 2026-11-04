package com.killer560.hub.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One record file in the shared store (shared-stats-1101): every game instance on the PC reads and writes the same file,
 * so profit and stats add up across instances and Minecraft versions.
 *
 * <p>Safe with two instances at once: every read and write holds an OS file lock on {@code <file>.lock} (plus a
 * per-file lock inside this JVM, because the OS lock is per process), and a save merges this instance's changes onto
 * what is on disk now with {@link JsonMerge} instead of overwriting it. The file is replaced through a temp file, so a
 * crash mid-write leaves the old one.
 *
 * <p>Depends on Gson and the JDK only (the standalone test and the one-time combine tool run it without Minecraft).
 */
public final class SharedFile {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    /** How long a save waits for another instance's lock before writing anyway (a hung game must not stop this one). */
    static long lockWaitMs = 10_000;

    private final Path file;
    private final JsonMerge.Policy policy;
    /** The JSON this instance last read or wrote: what its in-memory state was built from. */
    private JsonObject base;

    public SharedFile(Path file, JsonMerge.Policy policy) {
        this.file = file.toAbsolutePath().normalize();
        this.policy = policy;
    }

    public Path path() {
        return file;
    }

    /** Reads the file (an empty object when there is none or it is unreadable) and makes it the merge base. */
    public JsonObject load() throws IOException {
        try (Locked ignored = lock()) {
            JsonObject disk = read();
            synchronized (this) {
                base = disk.deepCopy();
            }
            return disk;
        }
    }

    /**
     * Merges {@code mine} (this instance's whole state) onto the file and writes it. Returns the merged JSON, which
     * holds what other instances added too. {@code mine} becomes the base: this instance's memory is built from it, and
     * whatever else the merge kept stays "added elsewhere" until a {@link #load}.
     */
    public JsonObject save(JsonObject mine) throws IOException {
        try (Locked ignored = lock()) {
            JsonObject disk = read();
            JsonObject b;
            synchronized (this) {
                b = base;
            }
            JsonObject merged = JsonMerge.merge(disk, b, mine, policy);
            write(merged);
            synchronized (this) {
                base = mine.deepCopy();
            }
            return merged;
        }
    }

    /**
     * Folds one instance's old record file into the shared one. {@code sourceId} names where it came from: importing
     * the same source again adds only what grew since the last import, and a file whose bytes were already imported from
     * anywhere (an instance folder copied from another) adds nothing. Returns false when it was skipped as a copy.
     */
    public boolean importLegacy(byte[] legacyBytes, String sourceId) throws IOException {
        JsonObject legacy = JsonParser.parseString(new String(legacyBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        String hash = sha256(legacyBytes);
        try (Locked ignored = lock()) {
            Path ledgerFile = ledgerPath();
            JsonObject ledger = readJson(ledgerFile);
            JsonObject hashes = obj(ledger, "hashes");
            JsonObject sources = obj(ledger, "sources");
            if (hashes.has(hash)) {
                return false;
            }
            JsonObject prior = null;
            if (sources.has(sourceId)) {
                Path priorFile = ledgerDir().resolve(sources.get(sourceId).getAsString() + ".json");
                if (Files.exists(priorFile)) {
                    prior = readJson(priorFile);
                }
            }
            // A first import is a union in which the store's own values win a conflict of state (the store is the
            // living copy; an old instance file is older). A re-import of the same source applies what changed in it.
            JsonObject merged = prior == null ? JsonMerge.merge(legacy, null, read(), policy)
                    : JsonMerge.merge(read(), prior, legacy, policy);
            write(merged);
            Files.createDirectories(ledgerDir());
            writeAtomically(ledgerDir().resolve(hash + ".json"), GSON.toJson((JsonElement) legacy));
            hashes.addProperty(hash, sourceId);
            sources.addProperty(sourceId, hash);
            writeAtomically(ledgerFile, GSON.toJson((JsonElement) ledger));
            return true;
        }
    }

    private Path ledgerPath() {
        return file.resolveSibling(".imported-" + file.getFileName());
    }

    private Path ledgerDir() {
        return file.resolveSibling(".imported");
    }

    private JsonObject read() {
        return readJson(file);
    }

    private static JsonObject readJson(Path p) {
        try {
            if (!Files.exists(p)) {
                return new JsonObject();
            }
            JsonElement e = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception unreadable) {
            // Keep the bytes before the next write replaces them (same rule as the old per-instance files).
            try {
                Files.copy(p, p.resolveSibling(p.getFileName() + ".corrupt-" + System.currentTimeMillis()));
            } catch (IOException ignored) {
            }
            return new JsonObject();
        }
    }

    private void write(JsonObject merged) throws IOException {
        writeAtomically(file, GSON.toJson((JsonElement) merged));
    }

    static void writeAtomically(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException refused) {
            // Windows refuses the replace while something else (a virus scanner, an editor) has the file open; writing
            // in place is not blocked by that (CroesusRunMemory.replace found this, 2026-10-22).
            Files.writeString(target, json, StandardCharsets.UTF_8);
            Files.deleteIfExists(tmp);
        }
    }

    private static JsonObject obj(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonObject()) {
            o.add(k, new JsonObject());
        }
        return o.getAsJsonObject(k);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    private Locked lock() throws IOException {
        ReentrantLock jvm = JVM_LOCKS.computeIfAbsent(file, k -> new ReentrantLock());
        long deadline = System.currentTimeMillis() + lockWaitMs;
        try {
            if (!jvm.tryLock(lockWaitMs, TimeUnit.MILLISECONDS)) {
                return new Locked(null, null, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Locked(null, null, null);
        }
        Files.createDirectories(file.getParent());
        FileChannel ch = null;
        try {
            ch = FileChannel.open(file.resolveSibling(file.getFileName() + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            while (true) {
                FileLock fl;
                try {
                    fl = ch.tryLock();
                } catch (OverlappingFileLockException same) {
                    fl = null;
                }
                if (fl != null) {
                    return new Locked(jvm, ch, fl);
                }
                if (System.currentTimeMillis() > deadline) {
                    // Another instance has held it for 10 s: carry on without it rather than lose this save.
                    return new Locked(jvm, ch, null);
                }
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Locked(jvm, ch, null);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (ch != null) {
                ch.close();
            }
            jvm.unlock();
            throw e;
        }
    }

    private record Locked(ReentrantLock jvm, FileChannel ch, FileLock fl) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                if (fl != null) {
                    fl.release();
                }
                if (ch != null) {
                    ch.close();
                }
            } finally {
                if (jvm != null) {
                    jvm.unlock();
                }
            }
        }
    }
}
