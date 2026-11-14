package com.killer560.hub.onlinecount;

import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayEndpoint;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModNet;
import com.killer560.hub.util.SkyblockGate;
import com.killer560.hub.util.TabText;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Online Count (killer560, 2026-11-02: "add a logger into the mod so you in discord can do /online and it shows how many
 * people using the mod are online, not specific player names just number, and what island they are on ... make it so
 * they share no matter what"; then "i want no specific player data from them just if they are on an island").
 * <p>
 * While the game is in any world or sits on the title screen, every {@link #INTERVAL_MS} (and soon after the island changes) one
 * {@code POST /presence} goes to the relay with EXACTLY two fields: {@code session}, a random id made when the game
 * starts (in memory only, never saved, never derived from the account), and {@code location}, the island or area
 * ("Hub", "Catacombs F7", "Hypixel (not Skyblock)"), or "Main Menu" with no world and no connection (killer560, 2026-11-04: "/online doesnt work
 * if someone is just on the start screen"; a singleplayer world (the sim) sends "Singleplayer", another server "Other Server", connecting sends nothing). Leaving Hypixel sends {@code location: "Offline"} once, so the
 * relay drops the session at once instead of after its TTL. Always on in both builds; there is no setting.
 * <p>
 * Never in a client gametest (the testkit) unless {@code -Dkiller560.test.presence=true}; {@code killer560.net.offline}
 * and the {@code presence-relay} service override (read on every send) apply as for every other call. Every failure is
 * silent: no chat, one WARN per session at most, nothing thrown into a tick or onto the netty thread.
 */
public final class OnlinePresence {

    private static final Logger LOGGER = ModLog.get("killer560smod-onlinecount");

    /** One heartbeat a minute. The relay counts a session online for 3 minutes after its last one. */
    public static final long INTERVAL_MS = 60_000L;
    /** First heartbeat this long after joining, so the tab list's Area line has arrived. */
    static final long FIRST_DELAY_MS = 8_000L;
    /** An island change is sent early, but never sooner than this after the last send (the relay refuses under 10 s). */
    static final long CHANGE_GAP_MS = 15_000L;
    static final int MAX_LOCATION = 32;
    public static final String OFFLINE = "Offline";
    public static final String NOT_SKYBLOCK = "Hypixel (not Skyblock)";
    public static final String SKYBLOCK = "Skyblock";
    /** The title screen or any menu with no connection and no world (the relay counts it and lists it on its own line). */
    public static final String MAIN_MENU = "Main Menu";
    private static final int MODE_NONE = 0;
    private static final int MODE_HYPIXEL = 1;
    private static final int MODE_MENU = 2;
    /** In a world that is not Hypixel: the singleplayer sim or any other server (onlinesim-1112). */
    private static final int MODE_LOCAL = 3;
    public static final String SINGLEPLAYER = "Singleplayer";
    public static final String OTHER_SERVER = "Other Server";

    private static final Pattern TAB_AREA = Pattern.compile("^(?:Area|Dungeon):\\s*(.{1,40})$");
    private static final Pattern NOT_SAFE = Pattern.compile("[^A-Za-z0-9 '.()\\-]");

    /** Made once per game start: 128 random bits as hex. Never written anywhere but the heartbeat. */
    private static final String SESSION = newSession();

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "killer560smod-onlinecount");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // Client thread only.
    private static int mode;
    private static long joinedAt;
    private static long lastSentAt;
    private static String lastLocation = "";
    private static int tick;

    // Any thread.
    private static volatile boolean live;
    private static volatile boolean warned;
    private static final AtomicInteger PENDING = new AtomicInteger();
    private static final List<String> SENT = new ArrayList<>();
    private static final List<String> RESULTS = new ArrayList<>();

    private OnlinePresence() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("OnlinePresence", OnlinePresence::onTick));
        // Netty thread (CLAUDE.md): read only the volatile flag and queue the send; no client or world state.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            try {
                if (live) {
                    live = false;
                    send(OFFLINE);
                }
            } catch (Throwable ignored) {
                // never onto the packet path
            }
        });
    }

    /** Off in a client gametest unless the harness turns it on. Read on every tick. */
    static boolean allowedHere() {
        return System.getProperty("fabric.client.gametest") == null || Boolean.getBoolean("killer560.test.presence");
    }

    static long interval() { return Long.getLong("killer560.test.presenceIntervalMs", INTERVAL_MS); }

    private static void onTick(Minecraft mc) {
        if (++tick % 20 != 0) {
            return;
        }
        int nextMode = MODE_NONE;
        if (allowedHere()) {
            if (mc.level != null) {
                nextMode = mc.player == null ? MODE_NONE : onHypixel(mc.getCurrentServer()) ? MODE_HYPIXEL : MODE_LOCAL;
            } else if (mc.getConnection() == null) {
                nextMode = MODE_MENU;
            }
        }
        long now = System.currentTimeMillis();
        if (nextMode != mode) {
            // A switch restarts the first-beat delay and drops the old row (Offline, if the DISCONNECT listener has not
            // already). lastSentAt is kept across Hypixel <-> title screen so the relay's 10 s floor holds.
            if (live) {
                live = false;
                send(OFFLINE);
            }
            if (mode == MODE_NONE) {
                lastSentAt = 0L;
            }
            mode = nextMode;
            joinedAt = now;
            lastLocation = "";
        }
        if (mode == MODE_NONE) {
            return;
        }
        long interval = interval();
        String location = mode == MODE_MENU ? MAIN_MENU
                : mode == MODE_LOCAL ? (mc.getSingleplayerServer() != null ? SINGLEPLAYER : OTHER_SERVER) : currentLocation(mc);
        boolean due = now - joinedAt >= Math.min(FIRST_DELAY_MS, interval)
                && (lastSentAt == 0L || now - lastSentAt >= interval
                || (!location.equals(lastLocation) && now - lastSentAt >= Math.min(CHANGE_GAP_MS, interval)));
        if (due) {
            lastSentAt = now;
            lastLocation = location;
            live = true;
            send(location);
        }
    }

    /** The address the player connected to is hypixel.net or one of its subdomains. */
    static boolean onHypixel(ServerData server) {
        if (server == null || server.ip == null) return false;
        String h = server.ip.toLowerCase(Locale.ROOT).strip();
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {
            h = h.substring(0, colon);
        }
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h.equals("hypixel.net") || h.endsWith(".hypixel.net");
    }

    /** Where the player is, from the mod's own detection. Client thread. */
    static String currentLocation(Minecraft mc) {
        String floor = DungeonState.getFloor();
        return locationFor(SkyblockGate.isOnSkyblock() || DungeonState.isInDungeon(), readTabArea(mc), floor);
    }

    /** The location string for a state: a dungeon floor first, then the tab's Area line on Skyblock, else not Skyblock. */
    public static String locationFor(boolean onSkyblock, String tabArea, String floor) {
        if (floor != null && !floor.isBlank()) {
            String f = clean(floor);
            return !f.isEmpty() && f.length() <= 3 ? "Catacombs " + f : "Catacombs";
        }
        if (!onSkyblock) return NOT_SKYBLOCK;
        String area = clean(tabArea);
        return area.isEmpty() ? SKYBLOCK : area;
    }

    /** Formatting codes and anything but letters, digits, space and ' . ( ) - removed; at most {@link #MAX_LOCATION}. */
    static String clean(String s) {
        if (s == null) return "";
        String plain = ChatObserver.stripCodes(s);
        if (plain == null) {
            plain = s;
        }
        plain = NOT_SAFE.matcher(plain.replace('’', '\'')).replaceAll("").replaceAll("\\s+", " ").strip();
        return plain.length() > MAX_LOCATION ? plain.substring(0, MAX_LOCATION).strip() : plain;
    }

    private static String readTabArea(Minecraft mc) {
        if (mc.getConnection() == null) return "";
        for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            String plain = TabText.plain(info);
            if (plain == null || !(plain.contains("Area:") || plain.contains("Dungeon:"))) continue;
            Matcher m = TAB_AREA.matcher(plain.strip());
            if (m.matches()) return m.group(1).strip();
        }
        return "";
    }

    /** The exact body sent: {@code {"session": ..., "location": ...}} and nothing else. */
    static String body(String location) {
        JsonObject o = new JsonObject();
        o.addProperty("session", SESSION);
        o.addProperty("location", location);
        return o.toString();
    }

    static String url() {
        return ModNet.url("presence-relay", RelayEndpoint.normalise(RelayEndpoint.DEFAULT_BASE_URL) + "/presence");
    }

    private static void send(String location) {
        String body = body(location);
        synchronized (SENT) {
            SENT.add(System.currentTimeMillis() + " " + body);
            if (SENT.size() > 100) {
                SENT.remove(0);
            }
        }
        // At most two queued: a relay that hangs never builds a backlog.
        if (PENDING.get() >= 2) return;
        PENDING.incrementAndGet();
        try {
            IO.execute(() -> {
                try {
                    post(body);
                } finally {
                    PENDING.decrementAndGet();
                }
            });
        } catch (RuntimeException e) {
            PENDING.decrementAndGet();
        }
    }

    private static void post(String body) {
        String result;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> res = ModNet.send(HTTP, req, HttpResponse.BodyHandlers.discarding());
            result = "HTTP " + res.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = "interrupted";
        } catch (Throwable e) {
            result = e.getClass().getSimpleName();
            if (!warned) {
                warned = true;
                LOGGER.warn("[OnlineCount] relay not reachable ({}); further failures are not logged", result);
            }
        }
        synchronized (RESULTS) {
            RESULTS.add(result);
            if (RESULTS.size() > 100) {
                RESULTS.remove(0);
            }
        }
    }

    private static String newSession() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    // ---- test hooks (docs/TESTING-HOOKS.md) ----------------------------------------------------------------------------

    /** Every body handed to the sender, "epochMillis json", recorded before the network (so offline reads the same). */
    public static List<String> sentForTest() {
        synchronized (SENT) {
            return new ArrayList<>(SENT);
        }
    }

    /** What each send ended in: "HTTP 204", or the exception's simple name. */
    public static List<String> resultsForTest() {
        synchronized (RESULTS) {
            return new ArrayList<>(RESULTS);
        }
    }

    public static void clearForTest() {
        synchronized (SENT) {
            SENT.clear();
        }
        synchronized (RESULTS) {
            RESULTS.clear();
        }
        mode = MODE_NONE;
        lastSentAt = 0L;
        lastLocation = "";
    }

    public static boolean warnedForTest() { return warned; }

    public static String sessionForTest() { return SESSION; }
}
