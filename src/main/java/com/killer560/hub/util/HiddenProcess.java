package com.killer560.hub.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Starts a console program (powershell, ffmpeg, reg) from the game without a command prompt window (modconwin-1119). The
 * game runs under javaw, which has no console, so Windows gives every console child a NEW console, and with Windows Terminal
 * as the default terminal that shows as a flashing window ({@code -WindowStyle Hidden} still flashes first). Java cannot set
 * CREATE_NO_WINDOW, so the child runs under {@code conhost.exe --headless}, which has no window but drops the child's stdout
 * and exit code: {@link #run} gets both back through a temp .cmd, {@link #headless} is for a caller that carries its own
 * channel (Spotify's named pipes). No conhost.exe (not Windows): the plain launch, unchanged.
 */
public final class HiddenProcess {
    private static final Path CONHOST = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "conhost.exe");
    private static final boolean AVAILABLE = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            && Files.isRegularFile(CONHOST);

    private HiddenProcess() { }

    /** Whether {@link #headless} and {@link #run} use the hidden console host here. */
    public static boolean available() { return AVAILABLE; }

    /** {@code cmd} prefixed with conhost's headless host when there is one, else {@code cmd} itself. */
    public static List<String> headless(List<String> cmd) {
        if (!AVAILABLE) return cmd;
        List<String> out = new ArrayList<>(cmd.size() + 2);
        out.add(CONHOST.toString());
        out.add("--headless");
        out.addAll(cmd);
        return out;
    }

    /**
     * Runs {@code cmd} with its stdout and stderr in {@code log}, waits up to {@code timeoutMs} and returns its exit code
     * (-2 on a timeout, the child killed). {@code env} is added to its environment. Throws IOException when the program
     * cannot be started (not found: Windows 9009). Blocks, so never on the render thread.
     */
    public static int run(List<String> cmd, Map<String, String> env, Path log, long timeoutMs) throws IOException, InterruptedException {
        if (!AVAILABLE) {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
            pb.environment().putAll(env);
            Process p = pb.start();
            if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return p.exitValue();
            p.destroyForcibly();
            return -2;
        }
        Path script = Files.createTempFile("killer560smod-hidden-", ".cmd");
        Path rc = Path.of(script + ".rc");
        StringBuilder sb = new StringBuilder("@echo off\r\n");
        for (String a : cmd) {
            sb.append('"').append(a.replace("%", "%%").replace("\"", "\\\"")).append("\" ");
        }
        sb.append(">\"").append(log).append("\" 2>&1\r\necho %errorlevel% >\"").append(rc).append("\"\r\n");
        Process p = null;
        try {
            Files.writeString(script, sb, StandardCharsets.UTF_8);
            ProcessBuilder pb = new ProcessBuilder(headless(List.of("cmd.exe", "/d", "/c", script.toString())));
            pb.environment().putAll(env);
            p = pb.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) return -2;
            int code = Files.exists(rc) ? Integer.parseInt(Files.readString(rc).trim()) : -1;
            if (code == 9009) throw new IOException(cmd.get(0) + " was not found");
            return code;
        } catch (NumberFormatException e) {
            return -1;
        } finally {
            if (p != null && p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
            Files.deleteIfExists(script);
            Files.deleteIfExists(rc);
        }
    }
}
