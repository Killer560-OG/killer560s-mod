package com.killer560.hub.util;

import com.killer560.hub.BuildVariant;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup timing (scanboot-1104). Dev builds only, and only with {@code -Dkiller560.boottime=true}: it records the
 * time between the mod's client entrypoint start and end, and (with {@code mark}) the time each step took. A release
 * jar has {@code DEV_TOOLS == false}, so every call folds away.
 */
public final class BootTimer {

    private static final boolean ON = BuildVariant.DEV_TOOLS && Boolean.getBoolean("killer560.boottime");
    private static final List<String> LABELS = new ArrayList<>();
    private static final List<Long> TIMES = new ArrayList<>();
    private static long start;
    private static long last;
    private static boolean titleSeen;

    private BootTimer() {
    }

    public static void begin() {
        if (ON) {
            start = System.nanoTime();
            last = start;
            LABELS.clear();
            TIMES.clear();
            long jvm = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            ModLog.get("killer560smod-boottime").info("[BootTimer] entrypoint begins {} ms after JVM start",
                    System.currentTimeMillis() - jvm);
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(c -> {
                if (!titleSeen && (com.killer560.hub.compat.McCompat.screen(c) != null || c.level != null)) {
                    titleSeen = true;
                    ModLog.get("killer560smod-boottime").info("[BootTimer] title screen reached {} ms after JVM start",
                            System.currentTimeMillis() - jvm);
                    if (Boolean.getBoolean("killer560.boottime.exit")) {
                        c.stop();
                    }
                }
            });
        }
    }

    public static void mark(String label) {
        if (ON) {
            long now = System.nanoTime();
            LABELS.add(label);
            TIMES.add(now - last);
            last = System.nanoTime();
        }
    }

    public static void end() {
        if (!ON) {
            return;
        }
        long total = System.nanoTime() - start;
        org.slf4j.Logger log = ModLog.get("killer560smod-boottime");
        log.info("[BootTimer] mod entrypoint total {} ms", total / 1_000_000.0);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < TIMES.size(); i++) {
            idx.add(i);
        }
        idx.sort((a, b) -> Long.compare(TIMES.get(b), TIMES.get(a)));
        for (int k = 0; k < Math.min(40, idx.size()); k++) {
            int i = idx.get(k);
            log.info("[BootTimer] step {} ms  {}", TIMES.get(i) / 1_000_000.0, LABELS.get(i));
        }
    }
}
