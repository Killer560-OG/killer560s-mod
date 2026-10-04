package com.killer560.hub.bugreport;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.GsonBuilder;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ModChat;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "/killer560 bugreport" - bundles everything needed to diagnose a problem (log, configs, environment)
 * into one zip the user can drag into Discord. Written for the public release: today the only way to get
 * diagnostics out of a stranger is to talk them through finding {@code logs/latest.log} by hand.
 * <p>
 * REDACTION IS THE POINT OF THIS CLASS, not an afterthought. This mod talks to a private relay with
 * Mojang-keypair auth and stores a raw login token ({@code killer560smod-session-login.json}, see
 * {@code accounts.core.SessionLoginStore}) and proxy credentials ({@code killer560smod-account-proxies.json},
 * see {@code proxy.config.AccountProxyStore}) on disk. {@code profiles.ProfileManager} already treats those
 * two files as too sensitive to put in a *shared profile* zip and excludes them outright (its own
 * {@code EXCLUDED_FILES} / secret-field handling) - this class follows that same precedent and skips them
 * entirely rather than trying to redact them field-by-field, on top of the generic key-based redaction
 * below that would otherwise catch their "token"/"password" fields anyway. A bug report that leaks a
 * credential into a public Discord channel is a worse outcome than a bug report that never got filed, so
 * every decision below is written to err toward redacting too much rather than too little.
 */
public final class BugReportFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bugreport");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Same two files {@code profiles.ProfileManager.EXCLUDED_FILES} keeps out of a shared profile zip for
     *  the exact same reason - a raw login token and proxy username/password. Skipped outright rather than
     *  redacted-in-place so a bug in the redaction logic below can never leak them. */
    private static final Set<String> FULLY_EXCLUDED_CONFIG_FILES = Set.of(
            "killer560smod-session-login.json",
            "killer560smod-account-proxies.json"
    );

    /** JSON-key substrings (case-insensitive) that mark a value as secret - exactly the list the feature
     *  request specifies. "key" is handled separately below because it also matches keybind settings. */
    private static final String[] SECRET_KEY_MARKERS = {
            "token", "secret", "password", "auth", "session", "cookie"
    };

    /** "key" alone would redact every keybind in the mod (abilityKeyCode, editKeyCode, Ap3's keybinds map,
     *  keybindsSectionOpen, ...) into uselessness - none of those are secrets, they're key codes. Anything
     *  containing "key" is still treated as secret UNLESS it also matches one of these keybind markers. */
    private static final String[] KEYBIND_ALLOWLIST_MARKERS = {
            "keycode", "keybind"
    };

    // Log-line redaction. Conservative on purpose (see class doc): a JWT-shaped token is redacted outright
    // wherever it appears, a "word: value" / "word=value" pair is redacted by value only (keeps the log
    // readable), and as a last resort a whole line is dropped if it contains one of the bare keywords in
    // a shape neither of those patterns caught - better an unreadable line than a leaked token.
    private static final Pattern JWT_PATTERN =
            Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)((?:access|refresh|session|api|bearer)?[_-]?"
                    + "(?:token|secret|password|passwd|api[_-]?key|cookie|session[_-]?id))"
                    + "(\"?\\s*[:=]\\s*\"?)([^\",\\s]+)");
    private static final Pattern BARE_KEYWORD_PATTERN =
            Pattern.compile("(?i)\\b(token|secret|password|passwd|cookie|session|auth)\\b");

    private BugReportFeature() {
    }

    /** Wire this up as the {@code /killer560 bugreport} executor - see the class doc / handoff note for
     *  exactly where. Never throws: every failure is reported in chat instead (requirement: must never
     *  crash the game). Returns 1 (brigadier "success") either way since the command itself always ran. */
    public static int generate() {
        try {
            Path zipPath = buildZip();
            ModChat.send("Bug Report",
                    ModChat.good("Saved: "), ModChat.value(zipPath.toString()),
                    ModChat.text(" - drag that file into Discord."));
            tryOpenContainingFolder(zipPath);
        } catch (Exception e) {
            LOGGER.warn("Bug report generation failed", e);
            ModChat.send("Bug Report", ModChat.bad("Couldn't generate a bug report: " + e.getMessage()));
        }
        return 1;
    }

    private static Path buildZip() throws Exception {
        Path bugReportsDir = ModPaths.config("bug-reports");
        Files.createDirectories(bugReportsDir);
        String timestamp = TIMESTAMP_FORMAT.format(LocalDateTime.now());
        Path zipPath = bugReportsDir.resolve("killer560smod-bugreport-" + timestamp + ".zip");

        try (OutputStream fos = Files.newOutputStream(zipPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            writeEntry(zos, "report.txt", buildReportText());

            for (Path configFile : listConfigFiles()) {
                String redacted = redactConfigFile(configFile);
                if (redacted != null) {
                    writeEntry(zos, "configs/" + configFile.getFileName(), redacted);
                }
            }

            if (BugReportConfig.getInstance().isIncludeLog()) {
                String log = readRedactedLog();
                if (log != null) {
                    writeEntry(zos, "logs/latest.log", log);
                }
            }
        }
        return zipPath;
    }

    private static void writeEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ------------------------------------------------------------------ report.txt

    private static String buildReportText() {
        StringBuilder sb = new StringBuilder();
        sb.append("killer560's Mod - Bug Report\n");
        sb.append("Generated: ").append(LocalDateTime.now()).append('\n');
        sb.append('\n');
        sb.append("Mod version: ").append(modVersion()).append('\n');
        sb.append("Minecraft version: ").append(FabricLoader.getInstance().getRawGameVersion()).append('\n');
        sb.append("Fabric Loader version: ").append(loaderVersion()).append('\n');
        sb.append("Build variant: ").append(BuildVariant.CHEAT_FEATURES_ENABLED ? "cheat" : "legit").append('\n');
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append('\n');
        sb.append("Java version: ").append(System.getProperty("java.version")).append('\n');
        Runtime rt = Runtime.getRuntime();
        sb.append(String.format(Locale.US, "Memory: %.1f MB free / %.1f MB total%n",
                rt.freeMemory() / 1024.0 / 1024.0, rt.totalMemory() / 1024.0 / 1024.0));
        sb.append('\n');
        sb.append("Loaded mods:\n");
        List<ModContainer> mods = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        mods.sort(Comparator.comparing(m -> m.getMetadata().getId(), String.CASE_INSENSITIVE_ORDER));
        for (ModContainer mod : mods) {
            sb.append("  ").append(mod.getMetadata().getId()).append(' ')
                    .append(mod.getMetadata().getVersion().getFriendlyString()).append('\n');
        }
        return sb.toString();
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("killer560smod")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    private static String loaderVersion() {
        // "fabricloader" is Fabric Loader's own built-in mod id - it always registers itself, so this is
        // real loader version rather than a hardcoded gradle.properties copy that would drift.
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    // ------------------------------------------------------------------ configs

    private static List<Path> listConfigFiles() {
        List<Path> files = new ArrayList<>();
        try {
            // Every killer560smod-*.json across the config/killer560 tree - ModPaths keeps the names unchanged.
            for (Path entry : ModPaths.settingFiles()) {
                if (!FULLY_EXCLUDED_CONFIG_FILES.contains(entry.getFileName().toString())) {
                    files.add(entry);
                }
            }
        } catch (Exception ignored) {
            // No config dir yet, or can't be listed - the report just ships with fewer configs, not a crash.
        }
        return files;
    }

    /** @return the pretty-printed, redacted JSON text for {@code file}, or {@code null} if it couldn't be
     *  parsed as JSON - a file that doesn't parse as an object is skipped rather than shipped raw, since an
     *  unparseable file can't be redacted with any confidence. */
    private static String redactConfigFile(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (root.isJsonObject()) {
                redactObject(root.getAsJsonObject());
            } else if (root.isJsonArray()) {
                redactArray(root.getAsJsonArray());
            } else {
                return null;
            }
            return new GsonBuilder().setPrettyPrinting().create().toJson(root);
        } catch (Exception e) {
            LOGGER.warn("Bug report: couldn't parse/redact {}, leaving it out", file.getFileName(), e);
            return null;
        }
    }

    private static void redactObject(JsonObject obj) {
        for (String key : new ArrayList<>(obj.keySet())) {
            if (isSecretKey(key)) {
                obj.addProperty(key, "[redacted]");
                continue;
            }
            JsonElement value = obj.get(key);
            if (value.isJsonObject()) {
                redactObject(value.getAsJsonObject());
            } else if (value.isJsonArray()) {
                redactArray(value.getAsJsonArray());
            }
        }
    }

    private static void redactArray(JsonArray array) {
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                redactObject(element.getAsJsonObject());
            } else if (element.isJsonArray()) {
                redactArray(element.getAsJsonArray());
            }
        }
    }

    /** See {@link #SECRET_KEY_MARKERS} / {@link #KEYBIND_ALLOWLIST_MARKERS} for why "key" is special-cased:
     *  redact anything containing "key" UNLESS it's shaped like a keybind field (keyCode/editKeyCode/the
     *  keybind(s) map), which would otherwise get wiped every single time this runs. */
    private static boolean isSecretKey(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String marker : SECRET_KEY_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        if (lower.contains("key")) {
            for (String safe : KEYBIND_ALLOWLIST_MARKERS) {
                if (lower.contains(safe)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ log

    /** @return the redacted contents of {@code logs/latest.log} from the run directory, or {@code null}
     *  if it doesn't exist / can't be read - never throws. */
    private static String readRedactedLog() {
        Path logPath = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(logPath)) {
            return null;
        }
        try {
            // Files.readString's UTF-8 decoder replaces malformed sequences rather than throwing, so a log
            // with a stray non-UTF-8 byte (e.g. from a foreign-language server MOTD) still comes through.
            String content = Files.readString(logPath, StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder(content.length());
            for (String line : content.split("\n", -1)) {
                out.append(redactLogLine(line)).append('\n');
            }
            // split(..., -1) + trailing append('\n') adds one extra blank line at the end - harmless in a
            // log file, not worth a special case.
            return out.toString();
        } catch (Exception e) {
            LOGGER.warn("Bug report: couldn't read latest.log", e);
            return null;
        }
    }

    private static String redactLogLine(String line) {
        String redacted = JWT_PATTERN.matcher(line).replaceAll("[redacted]");

        Matcher kv = KEY_VALUE_PATTERN.matcher(redacted);
        StringBuilder sb = new StringBuilder();
        while (kv.find()) {
            kv.appendReplacement(sb, Matcher.quoteReplacement(kv.group(1) + kv.group(2) + "[redacted]"));
        }
        kv.appendTail(sb);
        redacted = sb.toString();

        // Last-resort fallback: the line still mentions one of the bare keywords (so the key=value regex
        // above didn't match its shape) - drop the whole line rather than risk shipping whatever is next to
        // that word. Costs some log readability; per the feature request, that's the correct trade.
        if (BARE_KEYWORD_PATTERN.matcher(redacted).find() && !redacted.contains("[redacted]")) {
            return "[redacted - line contained a sensitive keyword]";
        }
        return redacted;
    }

    // ------------------------------------------------------------------ open folder

    private static void tryOpenContainingFolder(Path zipPath) {
        try {
            // Same real API every other "open folder" button in this mod uses (CringeTab, GifPlayerTab,
            // SpotifyTab, ...) - java.awt.Desktop is headless under Minecraft and just silently fails.
            Util.getPlatform().openPath(zipPath.getParent());
        } catch (Exception e) {
            LOGGER.warn("Bug report: couldn't open the bug-reports folder", e);
            // Not reported in chat - the "Saved: <path>" message already told them where it is.
        }
    }
}
