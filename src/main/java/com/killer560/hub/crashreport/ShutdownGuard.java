package com.killer560.hub.crashreport;

import com.killer560.hub.util.ModLog;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Another mod's non-daemon thread (crashguard-1110: SecretRoutes' HttpClient.send with no timeout) keeps the JVM alive
 * after Minecraft closed, so vanilla's ClientShutdownWatchdog (15 s after post-main) writes a "Client shutdown from
 * post-main" crash report on every close. {@link #arm} runs from a mixin at the start of the watchdog: a daemon thread
 * looks at the threads 11 s later and, only if non-daemon threads other than the main one still hold the JVM, logs one
 * WARN naming them and their mod and exits before the watchdog fires. A normal shutdown has no such thread and is never
 * touched. {@link #heldBy} puts the same answer in a report that slips through.
 */
public final class ShutdownGuard {
    private static final Logger LOGGER = ModLog.get("killer560smod-crashreport");
    static final long CHECK_MS = 11_000L;
    static final long HALT_MS = 1_500L;
    private static final String[] FRAMEWORK = {"java.", "jdk.", "sun.", "javax.", "com.sun.", "net.minecraft.", "com.mojang.", "io.netty."};
    private static final Pattern HEADER = Pattern.compile("^\"(.*)\" (daemon )?prio=");
    private static final Pattern FRAME = Pattern.compile("^\\tat (?:[\\w.@\\-]*/){0,2}([\\w.$]+)\\.[\\w$<>]+\\(");

    private ShutdownGuard() {
    }

    /** From the mixin on ClientShutdownWatchdog.startShutdownWatchdog; only vanilla's own end of main ("post-main"). */
    public static void arm(String reason, long mainId) {
        if (!"post-main".equals(reason)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(CHECK_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (check(mainId) != null) {
                // exit runs the shutdown hooks (log flush); the second thread halts if one of them hangs.
                Thread h = new Thread(() -> {
                    try {
                        Thread.sleep(HALT_MS);
                    } catch (InterruptedException ignored) {
                        // halt anyway
                    }
                    Runtime.getRuntime().halt(0);
                }, "killer560smod-shutdown-halt");
                h.setDaemon(true);
                h.start();
                System.exit(0);
            }
        }, "killer560smod-shutdown-guard");
        t.setDaemon(true);
        t.start();
    }

    /** Logs one WARN and returns it when non-daemon threads other than {@code mainId}'s still run; else null. */
    public static String check(long mainId) {
        List<String> held = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t.isAlive() && !t.isDaemon() && t.threadId() != mainId && t != Thread.currentThread()
                    && !t.getName().equals("DestroyJavaVM")) {
                held.add("\"" + t.getName() + "\" (" + owner(e.getValue()) + ")");
            }
        }
        if (held.isEmpty()) {
            return null;
        }
        String msg = "Minecraft closed but the JVM is held open by non-daemon thread(s) " + String.join(", ", held)
                + "; exiting before the shutdown watchdog writes a crash report";
        LOGGER.warn(msg);
        return msg;
    }

    private static boolean framework(String cls) {
        for (String p : FRAMEWORK) {
            if (cls.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** The mod id (else the package) of the top non-framework frame. */
    static String owner(StackTraceElement[] stack) {
        for (StackTraceElement f : stack) {
            String cls = f.getClassName();
            if (!framework(cls)) {
                String mod = modOf(cls);
                return mod != null ? mod : cls.substring(0, Math.max(0, cls.lastIndexOf('.')));
            }
        }
        return "no mod frames";
    }

    private static String modOf(String cls) {
        try {
            Path jar = Path.of(Class.forName(cls, false, ShutdownGuard.class.getClassLoader())
                    .getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
                if (m.getContainingMod().isEmpty()) {
                    for (Path o : m.getOrigin().getPaths()) {
                        if (o.toAbsolutePath().normalize().equals(jar)) {
                            return m.getMetadata().getId();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // the package says enough
        }
        return null;
    }

    /**
     * For a "Client shutdown from post-main" report text: a phrase naming each non-daemon thread of its thread dump
     * (other than the main ones) and its package, or null when it is not such a report or names none.
     */
    public static String heldBy(String crash) {
        if (!crash.contains("Client shutdown from post-main")) {
            return null;
        }
        List<String> held = new ArrayList<>();
        String name = null;
        boolean found = false;
        for (String line : crash.split("\\R")) {
            Matcher h = HEADER.matcher(line);
            if (h.find()) {
                name = h.group(2) != null || h.group(1).equals("DestroyJavaVM") || h.group(1).equals("main")
                        || h.group(1).equals("Render thread") ? null : h.group(1);
                found = false;
                continue;
            }
            Matcher f = name == null || found ? null : FRAME.matcher(line);
            if (f != null && f.find() && !framework(f.group(1))) {
                found = true;
                held.add("thread \"" + name + "\" (" + f.group(1).substring(0, Math.max(0, f.group(1).lastIndexOf('.'))) + ")");
            }
        }
        return held.isEmpty() ? null : "Held open by " + String.join(", ", held);
    }
}
