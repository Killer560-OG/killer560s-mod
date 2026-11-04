package com.killer560.hub.banreport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.autoupdate.BuildInfo;
import com.killer560.hub.bugreport.BugReportFeature;
import com.killer560.hub.relay.RelayAuth;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SessionLogTap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Report Bans (killer560, 2026-11-01, to-do Doc "BAN LOGGER"): opt-in, OFF by default. When a player who switched it
 * on is banned on Hypixel, one report goes through the relay ({@code POST /ban-report}) to a private staff channel, so we
 * can tell whether the mod flagged, a spectator reported him, or something else: the ban screen's text (Ban ID hidden),
 * the mod's version and variant, which features were on and their settings, and that session's mod log (server
 * corrections included).
 *
 * <p><b>What never leaves:</b> account tokens and session IDs (the login and proxy files are never read, every remaining
 * setting key that looks like a secret is redacted, token shapes are scrubbed), other players' chat (chat loggers are never
 * tapped: {@link SessionLogTap#SKIP}), other players' names and UUIDs (replaced by {@code <player>} / {@code <uuid>}), IP
 * addresses, e-mail addresses, the Hypixel key and the home folder.
 *
 * <p><b>What starts it:</b> only a disconnect screen ({@code accounts.mixin.DisconnectedScreenMixin}) whose text is a
 * Hypixel ban ({@link BanDetector}), on a connection the player made to hypixel.net. Never chat, never a title, never a
 * kick from another server. Once per ban (hashed key kept in {@link BanReportConfig} and checked by the relay), at most
 * one send per 10 minutes and three a day from this client.
 */
public final class BanReportFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-banreport");
    static final String FEATURE = "Ban Reports";

    static final int MAX_MESSAGE = 1500;
    static final int MAX_SETTINGS = 190_000;
    static final int MAX_LOG = 340_000;
    static final int MAX_FEATURES = 300;
    static final long MIN_GAP_MS = 10 * 60_000L;
    static final int MAX_PER_DAY = 3;
    static final int MAX_NAMES = 4000;

    /** Setting files never sent: they hold other players (friends, party, names, notes), run history or caches. */
    private static final String[] SKIP_FILE_PARTS = {
            "session", "proxy", "account", "friend", "social", "party", "playernames", "players", "supporters",
            "namechanger", "nick", "lfg", "modusers", "croesus", "profit", "history", "cache", "log", "runs",
            "sharedrecords", "banreport", "bugreport", "rng-item", "notes", "ignore", "blacklist", "whitelist"};

    private static final Pattern NAME_TOKEN = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}");
    private static final Pattern SAFE_SHORT = Pattern.compile("[^A-Za-z0-9 ._+\\-()]");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-banreport");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(IO)
            .build();

    private static volatile String lastHost = "";
    private static volatile boolean joinedSinceConnect;
    /** Lower-case names of other players seen this session (tab list and world), for the scrub. */
    private static final Set<String> SEEN_NAMES = ConcurrentHashMap.newKeySet();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static int tick;

    // ---- test hooks --------------------------------------------------------------------------------------------------
    private static final List<String> ATTEMPTS = new ArrayList<>();
    private static final List<String> DECISIONS = new ArrayList<>();
    private static volatile String lastResult = "";

    private BanReportFeature() {
    }

    public static void register() {
        BanReportConfig.getInstance();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (isHypixel(lastHost)) {
                joinedSinceConnect = true;
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BanReportFeature.onTick", BanReportFeature::onTick));
    }

    /** A connection the player started (ConnectScreenMixin): which host, and nothing joined yet. */
    public static void noteConnect(String host) {
        lastHost = host == null ? "" : host;
        joinedSinceConnect = false;
    }

    static boolean isHypixel(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT).strip();
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h.equals("hypixel.net") || h.endsWith(".hypixel.net");
    }

    private static void onTick(Minecraft mc) {
        if (!BanReportConfig.getInstance().isEnabled() || ++tick % 100 != 0) {
            return;
        }
        if (SEEN_NAMES.size() >= MAX_NAMES) {
            return;
        }
        String self = mc.getUser() == null ? "" : mc.getUser().getName().toLowerCase(Locale.ROOT);
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                addName(info.getProfile().name(), self);
            }
        }
        if (mc.level != null) {
            for (net.minecraft.client.player.AbstractClientPlayer p : mc.level.players()) {
                addName(p.getGameProfile().name(), self);
            }
        }
    }

    private static void addName(String name, String self) {
        if (name != null && SEEN_NAMES.size() < MAX_NAMES && NAME_TOKEN.matcher(name).matches()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.equals(self)) {
                SEEN_NAMES.add(lower);
            }
        }
    }

    /** The last screen read: DisconnectedScreen's constructors chain into each other, so the mixin sees one screen
     *  up to three times (testkit 4101 found it); each screen is read once. */
    private static java.lang.ref.WeakReference<Object> lastScreen = new java.lang.ref.WeakReference<>(null);

    /** DisconnectedScreenMixin: every disconnect screen's reason, before anything else looks at it. */
    public static void onDisconnectScreen(Object screen, Component reason) {
        if (screen != null && lastScreen.get() == screen) {
            return;
        }
        lastScreen = new java.lang.ref.WeakReference<>(screen);
        try {
            onDisconnectText(reason == null ? null : reason.getString());
        } catch (RuntimeException e) {
            // A disconnect screen must always open.
            LOGGER.error("[BanReport] could not build a ban report", e);
        }
    }

    static void onDisconnectText(String text) {
        BanReportConfig cfg = BanReportConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        BanDetector.Ban ban = BanDetector.parse(text);
        if (ban == null) {
            return;
        }
        if (!isHypixel(lastHost)) {
            decide("not a Hypixel connection (" + (lastHost.isEmpty() ? "none" : "other host") + ")");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        User user = mc == null ? null : mc.getUser();
        if (user == null || user.getProfileId() == null) {
            decide("no signed-in account");
            return;
        }
        String uuid = user.getProfileId().toString();
        String key = ban.key(uuid);
        if (cfg.wasReported(key) || !IN_FLIGHT.add(key)) {
            decide("already reported");
            return;
        }
        long now = System.currentTimeMillis();
        List<Long> sent = cfg.sentAt();
        long recent = sent.stream().filter(t -> now - t < 24 * 3600_000L).count();
        if (!sent.isEmpty() && now - sent.get(sent.size() - 1) < MIN_GAP_MS) {
            IN_FLIGHT.remove(key);
            decide("rate limited: one report per 10 minutes");
            return;
        }
        if (recent >= MAX_PER_DAY) {
            IN_FLIGHT.remove(key);
            decide("rate limited: three reports a day");
            return;
        }
        JsonObject payload = buildPayload(ban, key, user.getName(), uuid, joinedSinceConnect ? "play" : "login");
        String body = payload.toString();
        synchronized (ATTEMPTS) {
            ATTEMPTS.add(body);
        }
        decide("sending");
        cfg.noteSent(now);
        cfg.save();
        send(key, body);
    }

    private static void decide(String what) {
        LOGGER.info("[BanReport] ban screen seen: {}", what);
        synchronized (DECISIONS) {
            DECISIONS.add(what);
        }
    }

    // ---- the report ------------------------------------------------------------------------------------------------

    static JsonObject buildPayload(BanDetector.Ban ban, String key, String selfName, String selfUuid, String kind) {
        Set<String> names = otherNames(selfName);
        JsonObject o = new JsonObject();
        o.addProperty("banKey", key);
        String message = ban.maskedMessage();
        message = scrub(message, selfName, selfUuid, names);
        o.addProperty("message", cap(message, MAX_MESSAGE));
        o.addProperty("kind", kind);
        o.addProperty("modVersion", safeShort(FabricLoader.getInstance().getModContainer("killer560smod")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown")));
        o.addProperty("minecraft", safeShort(BuildInfo.minecraft()));
        o.addProperty("variant", safeShort(BuildInfo.variant()));
        o.addProperty("commit", safeShort(BuildInfo.shortCommit()));
        JsonArray features = new JsonArray();
        StringBuilder settings = new StringBuilder();
        collectSettings(features, settings, selfName, selfUuid, names);
        o.add("features", features);
        o.addProperty("settings", settings.toString());
        o.addProperty("log", buildLog(selfName, selfUuid, names));
        return o;
    }

    private static String safeShort(String s) {
        String out = SAFE_SHORT.matcher(s == null ? "" : s).replaceAll("");
        return out.length() > 64 ? out.substring(0, 64) : out;
    }

    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Other players' names the scrub replaces: seen this session, plus every name in the mod's name cache. */
    private static Set<String> otherNames(String selfName) {
        Set<String> out = new HashSet<>(SEEN_NAMES);
        Path cache = ModPaths.config("killer560smod-playernames.json");
        try {
            if (Files.isRegularFile(cache) && Files.size(cache) < 8_000_000) {
                collectNames(JsonParser.parseString(Files.readString(cache, StandardCharsets.UTF_8)), out);
            }
        } catch (Exception e) {
            LOGGER.info("[BanReport] name cache unreadable, scrubbing with the session's names only");
        }
        if (selfName != null) {
            out.remove(selfName.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static void collectNames(JsonElement e, Set<String> out) {
        if (out.size() >= 50_000) {
            return;
        }
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                collectNames(en.getValue(), out);
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                collectNames(x, out);
            }
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            if (NAME_TOKEN.matcher(s).matches()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * Everything that leaves goes through here last: credential shapes and the home folder (the Bug Report's own pass),
     * other players' names and UUIDs, IP and e-mail addresses, the OS account name.
     */
    public static String scrub(String text, String selfName, String selfUuid, Set<String> otherNames) {
        String out = BugReportFeature.redactShapes(text);
        String selfDashless = selfUuid == null ? "" : selfUuid.replace("-", "").toLowerCase(Locale.ROOT);
        Matcher u = UUID_SHAPE.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (u.find()) {
            boolean self = u.group().replace("-", "").toLowerCase(Locale.ROOT).equals(selfDashless);
            u.appendReplacement(sb, self ? Matcher.quoteReplacement(u.group()) : "<uuid>");
        }
        u.appendTail(sb);
        out = sb.toString();
        out = IPV4.matcher(out).replaceAll("<ip>");
        out = IPV6.matcher(out).replaceAll("<ip>");
        out = EMAIL.matcher(out).replaceAll("<email>");
        String self = selfName == null ? "" : selfName.toLowerCase(Locale.ROOT);
        String osUser = System.getProperty("user.name", "").toLowerCase(Locale.ROOT);
        Matcher n = NAME_TOKEN.matcher(out);
        sb = new StringBuilder();
        while (n.find()) {
            // Whole tokens only: a name inside a longer word is a different word.
            int s = n.start();
            int e = n.end();
            boolean whole = (s == 0 || !isNameChar(out.charAt(s - 1))) && (e == out.length() || !isNameChar(out.charAt(e)));
            String lower = n.group().toLowerCase(Locale.ROOT);
            String rep = n.group();
            if (whole && !lower.equals(self)) {
                if (otherNames.contains(lower)) {
                    rep = "<player>";
                } else if (osUser.length() >= 3 && lower.equals(osUser)) {
                    rep = "<user>";
                }
            }
            n.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        n.appendTail(sb);
        return sb.toString();
    }

    private static boolean isNameChar(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static void collectSettings(JsonArray features, StringBuilder settings, String selfName, String selfUuid,
                                        Set<String> names) {
        List<Path> files = new ArrayList<>(BugReportFeature.listConfigFiles());
        files.sort(null);
        int skipped = 0;
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            String stem = fileName.replaceFirst("^killer560smod-", "").replaceFirst("\\.json$", "");
            String lower = stem.toLowerCase(Locale.ROOT);
            boolean skip = false;
            for (String part : SKIP_FILE_PARTS) {
                if (lower.contains(part)) {
                    skip = true;
                    break;
                }
            }
            if (skip) {
                skipped++;
                continue;
            }
            String redacted = BugReportFeature.redactConfigFile(file);
            if (redacted == null) {
                continue;
            }
            try {
                JsonElement root = JsonParser.parseString(redacted);
                if (root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                        JsonElement v = e.getValue();
                        boolean on = v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
                        if (!on || features.size() >= MAX_FEATURES) {
                            continue;
                        }
                        if (e.getKey().equals("enabled")) {
                            features.add(safeShort(stem));
                        } else if (e.getKey().endsWith("Enabled") || e.getKey().startsWith("enable")) {
                            features.add(safeShort(stem + "." + e.getKey()));
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // features are a summary; the file itself is still sent below
            }
            String block = "== " + fileName + " ==\n" + scrub(redacted, selfName, selfUuid, names) + "\n";
            if (settings.length() + block.length() > MAX_SETTINGS) {
                settings.append("== (more setting files left out: size limit) ==\n");
                break;
            }
            settings.append(block);
        }
        if (skipped > 0) {
            settings.append("== ").append(skipped).append(" file(s) with other players, history or account data left out ==\n");
        }
    }

    private static String buildLog(String selfName, String selfUuid, Set<String> names) {
        List<String> lines = SessionLogTap.snapshot();
        List<String> out = new ArrayList<>(lines.size());
        int total = 0;
        // Newest first, so the size cap drops the oldest lines, then put back in order.
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = scrub(BugReportFeature.redactLogLine(lines.get(i)), selfName, selfUuid, names);
            if (total + line.length() + 1 > MAX_LOG - 200) {
                out.add("[... " + (i + 1) + " older line(s) left out: size limit ...]");
                break;
            }
            out.add(line);
            total += line.length() + 1;
        }
        java.util.Collections.reverse(out);
        return String.join("\n", out);
    }

    // ---- sending ---------------------------------------------------------------------------------------------------

    private static void send(String key, String body) {
        String base = RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL);
        if (!RelayEndpoint.isUsable(base)) {
            finish(key, -1, "relay unavailable");
            return;
        }
        String url = ModNet.url("banreport-relay", base + "/ban-report");
        RelayAuth.authenticate(HTTP, base)
                .thenCompose(t -> ModNet.sendAsync(HTTP, HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(30))
                                .header("Authorization", "Bearer " + t.value())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .whenComplete((res, err) -> {
                    if (err != null) {
                        Throwable c = err.getCause() != null ? err.getCause() : err;
                        finish(key, -1, c instanceof RelayAuth.RelayAuthException ? c.getMessage() : "could not reach the relay");
                    } else {
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
                    }
                });
    }

    private static void finish(String key, int status, String why) {
        IN_FLIGHT.remove(key);
        lastResult = status + (why.isEmpty() ? "" : " " + why);
        Minecraft mc = Minecraft.getInstance();
        Runnable onClient = () -> {
            if (status == 200) {
                BanReportConfig cfg = BanReportConfig.getInstance();
                cfg.markReported(key);
                cfg.save();
                LOGGER.info("[BanReport] report sent{}", why.isEmpty() ? "" : " (" + why + ")");
                ModChat.send(FEATURE, ModChat.good("Ban report sent"), ModChat.text(" to killer560's staff."));
            } else {
                LOGGER.info("[BanReport] report not sent: HTTP {} {}", status, why);
                ModChat.send(FEATURE, ModChat.bad("Ban report not sent: "),
                        ModChat.text(why.isEmpty() ? "relay returned HTTP " + status : why));
            }
        };
        if (mc != null) {
            mc.execute(onClient);
        } else {
            onClient.run();
        }
        // A report that was not sent is not marked: it goes again the next time the ban screen shows, within the limits.
        LOGGER.info("[BanReport] relay answered {}", lastResult);
    }

    // ---- test hooks ------------------------------------------------------------------------------------------------

    /** Test hook: every report body built (before the network), oldest first. */
    public static List<String> attemptsForTest() {
        synchronized (ATTEMPTS) {
            return List.copyOf(ATTEMPTS);
        }
    }

    /** Test hook: what each recognized ban screen led to ("sending", "already reported", "rate limited: ..."). */
    public static List<String> decisionsForTest() {
        synchronized (DECISIONS) {
            return List.copyOf(DECISIONS);
        }
    }

    /** Test hook: forget attempts, decisions, history, names seen and the last connection. */
    public static void clearForTest() {
        synchronized (ATTEMPTS) {
            ATTEMPTS.clear();
        }
        synchronized (DECISIONS) {
            DECISIONS.clear();
        }
        IN_FLIGHT.clear();
        SEEN_NAMES.clear();
        lastResult = "";
        lastHost = "";
        joinedSinceConnect = false;
        BanReportConfig.getInstance().clearHistoryForTest();
    }

    /** Test hook: a name "seen" this session, as the tab list would add it. */
    public static void noteNameForTest(String name) {
        addName(name, "");
    }

    /** Test hook: mark the connection as joined (a play-state kick rather than a refused login). */
    public static void noteJoinedForTest() {
        joinedSinceConnect = true;
    }
}
