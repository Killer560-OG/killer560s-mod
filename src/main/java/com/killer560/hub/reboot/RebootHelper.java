package com.killer560.hub.reboot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * /reboot's relauncher: a separate tiny Java process, started just before the game closes, that waits for the game
 * to exit (and for Auto Update's installer, when one was started) and then asks the launcher to start the same
 * instance again.
 *
 * <p>STANDALONE ON PURPOSE, exactly like {@code autoupdate/SwapHelper}: {@link RebootFeature} copies only this one
 * class file out of the jar and runs it with the game's own {@code javaw}, so it may use the JDK and nothing else - no
 * other mod class, no Minecraft, no Fabric, and no nested, anonymous or inner classes. Lambdas are fine.
 *
 * <p>Arguments: {@code <game pid> <timeout seconds> <work dir> <installer pid file or -> <instance id> <launcher exe>
 * [launcher prefix args...]}. The launcher is run as {@code <exe> [prefix...] --launch <instance id>} (Prism, PolyMC
 * and MultiMC all take {@code --launch}); the prefix exists only so the testkit can stand a tiny script in for Prism.
 * Log: {@code reboot.log} in the work dir. Exit 0 = launcher started, 2 = the game outlived the timeout (nothing
 * launched), anything else = failed (nothing launched).
 */
public final class RebootHelper {

    /** After the game is gone, how long to let the launcher notice before asking it to start the instance again. */
    private static final long GRACE_MS = 3000;
    /** Auto Update's installer retries a busy jar for 30 s; never wait on it longer than this. */
    private static final long INSTALLER_WAIT_S = 120;

    private static Path log;

    private RebootHelper() {
    }

    public static void main(String[] args) {
        if (args.length < 6) {
            System.err.println("usage: RebootHelper <game pid> <timeout s> <work dir> <installer pid file|-> <instance id>"
                    + " <launcher> [prefix args...]");
            System.exit(64);
            return;
        }
        Path work = Path.of(args[2]).toAbsolutePath();
        log = work.resolve("reboot.log");
        int code;
        try {
            List<String> prefix = new ArrayList<>();
            for (int i = 6; i < args.length; i++) {
                prefix.add(args[i]);
            }
            code = run(Long.parseLong(args[0]), Long.parseLong(args[1]), work,
                    "-".equals(args[3]) ? null : Path.of(args[3]), args[4], args[5], prefix);
        } catch (Throwable t) {
            log("failed: " + t);
            code = 70;
        }
        log("exit " + code);
        System.exit(code);
    }

    static int run(long gamePid, long timeoutS, Path work, Path installerPidFile, String instanceId, String launcher,
                   List<String> prefix) throws Exception {
        long started = System.currentTimeMillis();
        log("start: game " + gamePid + ", instance \"" + instanceId + "\", launcher " + launcher);
        if (!waitForExit(gamePid, timeoutS, "game")) {
            log("game still running after " + timeoutS + " s - not relaunching");
            return 2;
        }
        log("game " + gamePid + " has exited");

        // Auto Update's installer is started from CLIENT_STOPPING, after this helper; it writes its PID to a file.
        // Wait for it, so the relaunched game loads the new jar rather than racing the swap.
        if (installerPidFile != null && Files.isRegularFile(installerPidFile)
                && Files.getLastModifiedTime(installerPidFile).toMillis() >= started - 2000) {
            try {
                long installer = Long.parseLong(Files.readString(installerPidFile, StandardCharsets.UTF_8).trim());
                log("waiting for the Auto Update installer (pid " + installer + ")");
                if (!waitForExit(installer, INSTALLER_WAIT_S, "installer")) {
                    log("installer still running after " + INSTALLER_WAIT_S + " s - relaunching anyway");
                }
            } catch (NumberFormatException e) {
                log("unreadable installer pid file - not waiting on it");
            }
        }

        Thread.sleep(GRACE_MS);
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher);
        cmd.addAll(prefix);
        cmd.add("--launch");
        cmd.add(instanceId);
        log("running " + cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path launcherDir = Path.of(launcher).toAbsolutePath().getParent();
        if (launcherDir != null && Files.isDirectory(launcherDir)) {
            pb.directory(launcherDir.toFile());
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(work.resolve("launcher-out.log").toFile()));
        long pid = pb.start().pid();
        log("launcher started (pid " + pid + ")");
        return 0;
    }

    /** Polled rather than onExit(), for the same reason SwapHelper polls. True once {@code pid} is gone. */
    private static boolean waitForExit(long pid, long timeoutS, String what) throws InterruptedException {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isEmpty() || !h.get().isAlive()) {
            return true;
        }
        log("waiting for " + what + " " + pid + " to exit (at most " + timeoutS + " s)");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutS);
        while (h.get().isAlive()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(250);
        }
        return true;
    }

    private static void log(String line) {
        String s = LocalDateTime.now() + " [RebootHelper] " + line + System.lineSeparator();
        System.out.print(s);
        try {
            if (log != null) {
                Files.createDirectories(log.getParent());
                Files.writeString(log, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException ignored) {
        }
    }
}
