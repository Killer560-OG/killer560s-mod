package com.killer560.hub.shared;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * One background thread for shared-store saves whose callers sit on the client thread (a chat line, a tick). A
 * {@link SharedRecords#save} takes an OS file lock (up to 10 s when another game holds it) and reads, merges and
 * writes the file, which is a stall if the client thread does it. The caller builds its JSON snapshot on its own thread
 * and hands the save here; saves run in submit order, and {@link #drain} (also run at JVM exit) waits for them.
 */
public final class SharedWriter {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-shared-writer");
        t.setDaemon(true);
        return t;
    });

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> drain(4000), "killer560smod-shared-writer-exit"));
    }

    private SharedWriter() {
    }

    public static void submit(Runnable save) {
        try {
            EXEC.execute(save);
        } catch (RuntimeException e) {
            save.run();
        }
    }

    /** Waits (at most the given milliseconds) until every save queued so far has been written. */
    public static void drain(long ms) {
        try {
            EXEC.submit(() -> { }).get(ms, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        }
    }
}
