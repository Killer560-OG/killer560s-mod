package com.killer560.hub.shorts;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Finds Microsoft Edge (preferred) or Google Chrome, prepares the dedicated Shorts profile, and builds the
 * app-mode command line. Everything here blocks (file IO, reg.exe, process start) - background thread only.
 */
final class BrowserLauncher {

    static final String SHORTS_URL = "https://www.youtube.com/shorts";

    private BrowserLauncher() {
    }

    static Path profileDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("killer560smod-shorts-profile");
    }

    /** @return absolute path to msedge.exe/chrome.exe, or null if neither is installed. */
    static Path findBrowser(Logger log) {
        String pf86 = envOr("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String pf = envOr("ProgramFiles", "C:\\Program Files");
        String local = System.getenv("LOCALAPPDATA");

        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of(pf86, "Microsoft", "Edge", "Application", "msedge.exe"));
        candidates.add(Path.of(pf, "Microsoft", "Edge", "Application", "msedge.exe"));
        if (local != null) {
            candidates.add(Path.of(local, "Microsoft", "Edge", "Application", "msedge.exe"));
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        Path reg = registryAppPath("msedge.exe", log);
        if (reg != null) {
            return reg;
        }

        candidates.clear();
        candidates.add(Path.of(pf, "Google", "Chrome", "Application", "chrome.exe"));
        candidates.add(Path.of(pf86, "Google", "Chrome", "Application", "chrome.exe"));
        if (local != null) {
            candidates.add(Path.of(local, "Google", "Chrome", "Application", "chrome.exe"));
        }
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return registryAppPath("chrome.exe", log);
    }

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    /** Reads HKLM/HKCU "App Paths\<exe>" default value via reg.exe (no jna-platform Advapi32 available). */
    private static Path registryAppPath(String exe, Logger log) {
        for (String hive : new String[]{"HKLM", "HKCU"}) {
            String key = hive + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exe;
            try {
                Process p = new ProcessBuilder("reg.exe", "query", key, "/ve").redirectErrorStream(true).start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.append(line).append('\n');
                    }
                }
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    continue;
                }
                for (String line : out.toString().split("\n")) {
                    int idx = line.indexOf("REG_SZ");
                    if (idx >= 0) {
                        String value = line.substring(idx + "REG_SZ".length()).trim().replace("\"", "");
                        if (!value.isEmpty() && Files.isRegularFile(Path.of(value))) {
                            log.info("[Shorts] Found {} via registry {}.", exe, key);
                            return Path.of(value);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static List<String> buildCommand(Path exe, Path profile, int x, int y, int w, int h, ShortsConfig.Theme theme) {
        List<String> cmd = new ArrayList<>();
        cmd.add(exe.toString());
        cmd.add("--app=" + SHORTS_URL);
        // Dark/light mode (2026-09-16, killer560). The switch sets the browser's native theme before the first
        // paint, so YouTube (in its default "Device theme" appearance) loads already dark/light instead of
        // flashing the Windows theme first. --force-dark-mode is a long-standing Chromium switch; the light
        // twin is documented by third parties only, and Chromium ignores switches it doesn't know, so
        // neither can break a launch. The CDP prefers-color-scheme emulation ShortsFeature applies after
        // connecting is what actually guarantees the result (and is what makes a live change work without
        // a relaunch); this just removes the initial flash. SYSTEM adds nothing = the pre-option behaviour.
        if (theme == ShortsConfig.Theme.DARK) {
            cmd.add("--force-dark-mode");
        } else if (theme == ShortsConfig.Theme.LIGHT) {
            cmd.add("--force-light-mode");
        }
        cmd.add("--user-data-dir=" + profile.toAbsolutePath());
        // 0 = let the browser pick a free port and write it to <profile>/DevToolsActivePort (no port race).
        cmd.add("--remote-debugging-port=0");
        cmd.add("--window-size=" + Math.max(200, w) + "," + Math.max(200, h));
        cmd.add("--window-position=" + x + "," + y);
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-session-crashed-bubble");
        cmd.add("--hide-crash-restore-bubble");
        cmd.add("--autoplay-policy=no-user-gesture-required");
        cmd.add("--disable-backgrounding-occluded-windows");
        // Edge-only (Chrome ignores unknown switches): don't relaunch through the compat layer, which would
        // change the browser process id out from under us.
        cmd.add("--edge-skip-compat-layer-relaunch");
        return cmd;
    }

    /** Marks the profile as cleanly exited so no "Restore pages?" bubble appears after a forced kill. */
    static void markProfileExitedCleanly(Path profile, Logger log) {
        Path prefs = profile.resolve("Default").resolve("Preferences");
        if (!Files.isRegularFile(prefs)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(prefs, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject profileObj = root.has("profile") && root.get("profile").isJsonObject()
                    ? root.getAsJsonObject("profile") : new JsonObject();
            boolean changed = !profileObj.has("exit_type") || !"Normal".equals(profileObj.get("exit_type").getAsString())
                    || !profileObj.has("exited_cleanly") || !profileObj.get("exited_cleanly").getAsBoolean();
            if (!changed) {
                return;
            }
            profileObj.addProperty("exit_type", "Normal");
            profileObj.addProperty("exited_cleanly", true);
            root.add("profile", profileObj);
            Gson gson = new GsonBuilder().disableHtmlEscaping().create();
            Files.writeString(prefs, gson.toJson(root), StandardCharsets.UTF_8);
            log.info("[Shorts] Marked Shorts profile as cleanly exited (suppresses the restore-pages bubble).");
        } catch (Exception e) {
            log.warn("[Shorts] Couldn't patch profile Preferences ({}), continuing.", e.toString());
        }
    }

    /** @return the port from &lt;profile&gt;/DevToolsActivePort, or -1. */
    static int readDevToolsPort(Path profile) {
        Path f = profile.resolve("DevToolsActivePort");
        try {
            if (!Files.isRegularFile(f)) {
                return -1;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return -1;
            }
            int port = Integer.parseInt(lines.get(0).trim());
            return port > 0 && port < 65536 ? port : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** @return line 2 of &lt;profile&gt;/DevToolsActivePort ("/devtools/browser/&lt;id&gt;"), or null. */
    static String readDevToolsBrowserPath(Path profile) {
        try {
            List<String> lines = Files.readAllLines(profile.resolve("DevToolsActivePort"), StandardCharsets.UTF_8);
            return lines.size() >= 2 && !lines.get(1).isBlank() ? lines.get(1).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static void deleteDevToolsPortFile(Path profile) {
        try {
            Files.deleteIfExists(profile.resolve("DevToolsActivePort"));
        } catch (Exception ignored) {
        }
    }
}
