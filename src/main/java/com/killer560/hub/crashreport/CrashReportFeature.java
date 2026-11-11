package com.killer560.hub.crashreport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.banreport.BanReportFeature;
import com.killer560.hub.relay.RelayAuth;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Crash Reports (killer560, 2026-11-04): opt-in, OFF by default, in both builds. After the game crashes, ONE report of
 * that crash goes through the relay ({@code POST /crash-report}) to the admins' private channel: the crash file (a
 * Minecraft crash report or a JVM {@code hs_err_pid*.log}), the mod's version, commit, variant and Minecraft version, the
 * other mods and the features that were on.
 *
 * <p><b>When:</b> as it crashes, from {@code mixin.CrashReportSaveMixin} once Minecraft has written its crash report (the
 * crashing thread waits up to {@link #LIVE_WAIT_S} s for the send); otherwise on the next start, about 5 s after the client
 * is up, for the NEWEST crash file only (a JVM crash never reaches Java code). Each crash file is sent once (its hashed
 * key is kept here and checked by the relay), never one from before the switch went on or older than a week, at most one
 * every 5 minutes and five a day.
 *
 * <p><b>Scrubbed before it leaves:</b> credential shapes and home folders ({@code BugReportFeature.redactShapes}, the
 * shapes of tools/verify-artifact.py), the launcher's {@code --accessToken}/{@code --xuid}/{@code --clientId} and server
 * arguments, IP addresses and {@code host:port} servers, other players' names and UUIDs, e-mail addresses, the OS account
 * name, and his own name and UUID unless "Include My Minecraft Name" is on (then "Include My Discord" lets the relay add
 * his linked Discord, discordlink-1105).
 */
public final class CrashReportFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-crashreport");
    static final String FEATURE = "Crash Reports";

    static final int MAX_READ = 1_500_000;
    static final int MAX_CRASH = 390_000;
    static final int MAX_SUMMARY = 1000;
    static final int MAX_LIST = 400;
    static final long MIN_GAP_MS = 5 * 60_000L;
    static final int MAX_PER_DAY = 5;
    static final long MAX_AGE_MS = 7 * 24 * 3600_000L;
    static final int LIVE_WAIT_S = 15;
    /** Client ticks after start before the boot scan: the client is up and the account is loaded. */
    private static final int BOOT_DELAY_TICKS = 100;

    /** Launcher arguments that are server addresses, and (without consent) his name and UUID. */
    private static final Pattern SERVER_ARG = Pattern.compile("(?i)(--(?:server|port|quickPlayMultiplayer|quickPlayRealms)[ =])\\S+");
    private static final Pattern SELF_ARG = Pattern.compile("(?i)(--(?:username|uuid)[ =])\\S+");
    /** A crash report's player list: {@code LocalPlayer['Name'/12, ...]}, {@code RemotePlayer['Name'/...]}. */
    private static final Pattern PLAYER_ENTRY = Pattern.compile("(Player\\[')([A-Za-z0-9_]{1,16})(')");
    /** A server as {@code host:port}; never a stack frame's {@code Foo.java:12}. */
    private static final Pattern HOST_PORT = Pattern.compile(
            "(?i)\\b(?:[a-z0-9-]{1,63}\\.)+(?!(?:java|kt|scala|groovy|class|cpp|hpp|c|h)\\b)[a-z]{2,24}:\\d{2,5}\\b");
    private static final Pattern CRASH_FILE = Pattern.compile("crash-.*\\.txt");
    private static final Pattern HS_ERR = Pattern.compile("hs_err_pid\\d+\\.log");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-crashreport");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static int ticks;
    private static boolean bootScanned;

    // ---- test hooks --------------------------------------------------------------------------------------------------
    private static final List<String> ATTEMPTS = new ArrayList<>();
    private static final List<String> DECISIONS = new ArrayList<>();
    private static volatile String lastResult = "";
    /** A gametest's bearer token instead of the Mojang chat-key login (only honored inside a client gametest). */
    private static volatile String testToken;

    private CrashReportFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("CrashReportFeature.onTick", CrashReportFeature::onTick));
    }

    /** Once per start: after {@link #BOOT_DELAY_TICKS}, the newest crash file, on the report thread. One int check a tick. */
    private static void onTick(Minecraft mc) {
        if (bootScanned || ++ticks < BOOT_DELAY_TICKS) {
            return;
        }
        bootScanned = true;
        if (CrashReportConfig.getInstance().isEnabled() && System.getProperty("fabric.client.gametest") == null) {
            IO.execute(() -> report(newestCrash(), "boot", false));
        }
    }

    /** CrashReportSaveMixin: Minecraft has just written a crash report to {@code file}. Never throws. */
    public static void onCrashSaved(Path file) {
        try {
            if (CrashReportConfig.getInstance().isEnabled()) {
                report(file, "live", true);
            }
        } catch (Throwable t) {
            // The crash must go on exactly as it would have.
            LOGGER.error("[CrashReport] could not send the crash as it happened", t);
        }
    }

    /** The newest crash file: {@code crash-reports/crash-*.txt} or {@code hs_err_pid*.log} in the game or working folder. */
    static Path newestCrash() {
        Path game = FabricLoader.getInstance().getGameDir();
        Path best = null;
        long bestTime = Long.MIN_VALUE;
        for (Path dir : List.of(game.resolve("crash-reports"), game, Path.of("").toAbsolutePath())) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString();
                    boolean crash = dir.endsWith("crash-reports") ? CRASH_FILE.matcher(n).matches() : HS_ERR.matcher(n).matches();
                    long t = crash && Files.isRegularFile(p) ? Files.getLastModifiedTime(p).toMillis() : Long.MIN_VALUE;
                    if (t > bestTime) {
                        best = p;
                        bestTime = t;
                    }
                }
            } catch (Exception e) {
                LOGGER.info("[CrashReport] could not list {}", dir.getFileName());
            }
        }
        return best;
    }

    /** Builds and sends one report of {@code file}; with {@code wait}, blocks until the relay answered (bounded). */
    static void report(Path file, String source, boolean wait) {
        CrashReportConfig cfg = CrashReportConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        if (file == null) {
            decide("no crash file");
            return;
        }
        String key;
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
            key = crashKey(file.getFileName().toString(), Files.size(file), modified);
        } catch (Exception e) {
            decide("crash file unreadable");
            return;
        }
        long now = System.currentTimeMillis();
        if (modified < cfg.enabledAt() || now - modified > MAX_AGE_MS) {
            decide("crash from before the switch was on or over a week old");
            return;
        }
        if (cfg.wasReported(key) || !IN_FLIGHT.add(key)) {
            decide("already reported");
            return;
        }
        List<Long> sent = cfg.sentAt();
        if (!sent.isEmpty() && now - sent.get(sent.size() - 1) < MIN_GAP_MS
                || sent.stream().filter(t -> now - t < 24 * 3600_000L).count() >= MAX_PER_DAY) {
            IN_FLIGHT.remove(key);
            decide("rate limited: one report per 5 minutes, five a day");
            return;
        }
        String body;
        try {
            JsonObject payload = buildPayload(file, key, source, cfg.isShareName());
            payload.addProperty("shareDiscord", cfg.isShareName() && cfg.isShareDiscord());
            body = payload.toString();
        } catch (Exception e) {
            IN_FLIGHT.remove(key);
            decide("could not build the report");
            LOGGER.info("[CrashReport] could not build the report: {}", e.toString());
            return;
        }
        synchronized (ATTEMPTS) {
            ATTEMPTS.add(body);
        }
        decide("sending (" + source + ")");
        cfg.noteSent(now);
        cfg.save();
        CompletableFuture<?> done = send(key, body);
        if (wait) {
            try {
                done.get(LIVE_WAIT_S, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.info("[CrashReport] the relay did not answer in {} s; the next start sends it", LIVE_WAIT_S);
            }
        }
    }

    static String crashKey(String name, long size, long modified) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256")
                .digest((name + "|" + size + "|" + modified).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h);
    }

    private static void decide(String what) {
        LOGGER.info("[CrashReport] {}", what);
        synchronized (DECISIONS) {
            DECISIONS.add(what);
        }
    }

    // ---- the report ------------------------------------------------------------------------------------------------

    static JsonObject buildPayload(Path file, String key, String source, boolean identify) throws Exception {
        boolean nativeCrash = HS_ERR.matcher(file.getFileName().toString()).matches();
        String text;
        try (InputStream in = Files.newInputStream(file)) {
            text = new String(in.readNBytes(MAX_READ), StandardCharsets.UTF_8);
        }
        Minecraft mc = Minecraft.getInstance();
        User user = mc == null ? null : mc.getUser();
        String self = user == null ? null : user.getName();
        String selfUuid = user == null || user.getProfileId() == null ? null : user.getProfileId().toString();
        String crash = scrub(text, self, selfUuid, identify, BanReportFeature.otherNames(self));
        if (crash.length() > MAX_CRASH) {
            crash = crash.substring(0, MAX_CRASH - 80) + "\n[... " + (crash.length() - MAX_CRASH + 80) + " characters left out: size limit ...]";
        }
        JsonObject o = new JsonObject();
        o.addProperty("crashKey", key);
        o.addProperty("kind", nativeCrash ? "native" : "java");
        o.addProperty("source", source);
        o.addProperty("identify", identify);
        o.addProperty("summary", summary(crash, nativeCrash));
        o.addProperty("crash", crash);
        BanReportFeature.addBuild(o);
        JsonArray mods = new JsonArray();
        List<String> names = new ArrayList<>();
        for (ModContainer m : FabricLoader.getInstance().getAllMods()) {
            String id = m.getMetadata().getId();
            if (m.getContainingMod().isEmpty() && !Set.of("killer560smod", "minecraft", "java", "fabricloader").contains(id)) {
                names.add(BanReportFeature.safeShort(id + " " + m.getMetadata().getVersion().getFriendlyString()));
            }
        }
        names.stream().sorted().limit(MAX_LIST).forEach(mods::add);
        o.add("mods", mods);
        JsonArray features = new JsonArray();
        BanReportFeature.featuresOn().stream().limit(MAX_LIST).forEach(features::add);
        o.add("features", features);
        return o;
    }

    /**
     * The crash text as it may leave: launcher credentials and server arguments, {@code host:port} servers, other
     * players in the crash's player list, then Auto Report Bans' scrub (shapes, home folder, UUIDs, IPs, e-mail, names,
     * the OS account). Without {@code identify} his own name and UUID go too.
     */
    static String scrub(String text, String self, String selfUuid, boolean identify, Set<String> otherNames) {
        String out = SERVER_ARG.matcher(text).replaceAll("$1<server>");
        if (!identify) {
            out = SELF_ARG.matcher(out).replaceAll("$1<hidden>");
        }
        out = HOST_PORT.matcher(out).replaceAll("<server>");
        String selfLower = self == null ? "" : self.toLowerCase(Locale.ROOT);
        // A name seen in a Player['X' entry is another player's: it goes everywhere else in the report too.
        Set<String> names = new java.util.HashSet<>(otherNames);
        Matcher m = PLAYER_ENTRY.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean keep = identify && m.group(2).toLowerCase(Locale.ROOT).equals(selfLower);
            if (!keep) {
                names.add(m.group(2).toLowerCase(Locale.ROOT));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + (keep ? m.group(2) : "<player>") + m.group(3)));
        }
        m.appendTail(sb);
        out = sb.toString();
        if (identify) {
            names.remove(selfLower);
            return BanReportFeature.scrub(out, self, selfUuid, names);
        }
        if (!selfLower.isEmpty()) {
            names.add(selfLower);
        }
        return BanReportFeature.scrub(out, null, null, names);
    }

    /** The first lines that say what happened: Description and the exception (Java), or the "#" header (hs_err). */
    static String summary(String crash, boolean nativeCrash) {
        String[] lines = crash.split("\\R", 400);
        StringBuilder sb = new StringBuilder();
        if (nativeCrash) {
            for (String l : lines) {
                if (l.startsWith("#") && l.length() > 2) {
                    sb.append(l).append('\n');
                }
                if (sb.length() > MAX_SUMMARY) {
                    break;
                }
            }
        } else {
            int at = -1;
            for (int i = 0; i < lines.length && at < 0; i++) {
                if (lines[i].startsWith("Description:")) {
                    at = i;
                }
            }
            for (int i = Math.max(at, 0), taken = 0; i < lines.length && taken < 8; i++) {
                if (!lines[i].isBlank()) {
                    sb.append(lines[i]).append('\n');
                    taken++;
                }
            }
        }
        String held = nativeCrash ? null : ShutdownGuard.heldBy(crash);
        String s = (held == null ? "" : held + "\n") + sb.toString().strip();
        return s.length() > MAX_SUMMARY ? s.substring(0, MAX_SUMMARY - 3) + "..." : s;
    }

    // ---- sending ---------------------------------------------------------------------------------------------------

    private static CompletableFuture<?> send(String key, String body) {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            finish(key, -1, "relay unavailable");
            return CompletableFuture.completedFuture(null);
        }
        String url = ModNet.url("crashreport-relay", base + "/crash-report");
        String forTest = System.getProperty("fabric.client.gametest") != null ? testToken : null;
        CompletableFuture<String> token = forTest != null
                ? CompletableFuture.completedFuture(forTest)
                : RelayAuth.authenticate(HTTP, base).thenApply(RelayAuth.Token::value);
        return token
                .thenCompose(t -> ModNet.sendAsync(HTTP, HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(20))
                                .header("Authorization", "Bearer " + t)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .whenComplete((res, err) -> {
                    if (err != null) {
                        Throwable c = err.getCause() != null ? err.getCause() : err;
                        finish(key, -1, c instanceof RelayAuth.RelayAuthException ? c.getMessage() : "could not reach the relay");
                        return;
                    }
                    String why = "";
                    try {
                        JsonObject j = JsonParser.parseString(res.body()).getAsJsonObject();
                        if (j.has("error")) {
                            why = j.get("error").getAsString();
                        } else if (j.has("duplicate") && j.get("duplicate").getAsBoolean()) {
                            why = "already reported";
                        }
                    } catch (RuntimeException ignored) {
                        // no JSON body
                    }
                    finish(key, res.statusCode(), why);
                });
    }

    /** Records the answer here and now (a crashing client thread never runs a queued task), then tells chat. */
    private static void finish(String key, int status, String why) {
        IN_FLIGHT.remove(key);
        lastResult = status + (why.isEmpty() ? "" : " " + why);
        if (status == 200) {
            CrashReportConfig cfg = CrashReportConfig.getInstance();
            cfg.markReported(key);
            cfg.save();
        }
        // A report that was not sent is not marked: the next start sends it again, within the limits.
        LOGGER.info("[CrashReport] relay answered {}", lastResult);
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                if (status == 200) {
                    ModChat.send(FEATURE, ModChat.good("Crash report sent"), ModChat.text(" to killer560's admins."));
                } else {
                    ModChat.send(FEATURE, ModChat.bad("Crash report not sent: "),
                            ModChat.text(why.isEmpty() ? "relay returned HTTP " + status : why));
                }
            });
        }
    }

    // ---- test hooks ------------------------------------------------------------------------------------------------

    /** Test hook: every report body built (before the network), oldest first. */
    public static List<String> attemptsForTest() {
        synchronized (ATTEMPTS) {
            return List.copyOf(ATTEMPTS);
        }
    }

    /** Test hook: what each look at a crash led to ("sending (boot)", "already reported", ...). */
    public static List<String> decisionsForTest() {
        synchronized (DECISIONS) {
            return List.copyOf(DECISIONS);
        }
    }

    public static String lastResultForTest() {
        return lastResult;
    }

    /** Test hook: the boot path (the newest crash file) on the calling thread, waiting for the relay's answer. */
    public static void bootScanForTest() {
        report(newestCrash(), "boot", true);
    }

    /** Test hook: the bearer token sent instead of the chat-key login; null for the real login. */
    public static void setTokenForTest(String token) {
        testToken = token;
    }

    /** Test hook: forget attempts, decisions and the history; the switch counts as on since {@code enabledAt}. */
    public static void clearForTest(long enabledAt) {
        synchronized (ATTEMPTS) {
            ATTEMPTS.clear();
        }
        synchronized (DECISIONS) {
            DECISIONS.clear();
        }
        IN_FLIGHT.clear();
        lastResult = "";
        CrashReportConfig.getInstance().clearHistoryForTest(enabledAt);
    }
}
