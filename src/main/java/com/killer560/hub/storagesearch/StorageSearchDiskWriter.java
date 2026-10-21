package com.killer560.hub.storagesearch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/** Writes this feature's cache files off the render thread. The island-chest cache holds up to 400 chests as Base64 NBT
 *  and was rewritten whole, pretty-printed, on the render thread each time a chest screen closed with new contents - a
 *  multi-megabyte write mid-frame. A caller snapshots its (immutable) entries and hands over a supplier that builds the
 *  JSON from that snapshot; a newer write for the same file replaces one still waiting, and a JVM shutdown hook writes
 *  whatever is left so a quit right after closing a chest loses nothing. */
final class StorageSearchDiskWriter {

    private static final Map<Path, Supplier<String>> PENDING = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-storagesearch-writer");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(StorageSearchDiskWriter::flush, "killer560smod-storagesearch-flush"));
    }

    private StorageSearchDiskWriter() {
    }

    /** Queues {@code json} (called on the writer thread, so it may only read a snapshot) to be written to {@code path}. */
    static void write(Path path, Supplier<String> json) {
        PENDING.put(path, json);
        EXECUTOR.execute(StorageSearchDiskWriter::flush);
    }

    private static synchronized void flush() {
        for (Path path : PENDING.keySet()) {
            Supplier<String> json = PENDING.remove(path);
            if (json == null) {
                continue;
            }
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, json.get(), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
    }
}
