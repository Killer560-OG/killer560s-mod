package com.killer560.hub.luck;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.gui.profit.ProfitScope;
import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.relay.RelayListener;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Other mod users' luck (killer560: "If someone else uses my mod it would be nice to be able to see what all they have
 * gotten"). One relay {@code data} packet, key {@link #KEY}, to the mod users in your relay room (party or lobby, the
 * room Mod Chat is in): your overall luck and your 12 drops with the most expected. Nothing else.
 *
 * <p>Opt-in on both ends, on the consent the mod already has: SENT only while Share My Luck (this feature, default OFF)
 * and Show Me As A Mod User ({@link ModUserConfig#isShare}) are both on; RECEIVED only while See Other Mod Users
 * ({@link ModUserConfig#isSeeOthers}) is on. Sent on change, every 2 minutes, and soon after someone joins the room (the
 * relay does not replay data to late joiners); every inbound packet is checked field by field and bounded.
 */
public final class LuckShare {

    public static final String KEY = "luck.v1";
    static final int MAX_ROWS = 12;
    static final long HEARTBEAT_MS = 120_000;
    static final long FORGET_MS = 30 * 60_000;

    /** What another player shared. */
    public record Remote(String from, double luck, int drops, long got, double expected, List<RemoteRow> rows,
                         long receivedMs) {
    }

    public record RemoteRow(String name, long got, double expected) {
    }

    private static final Map<String, Remote> REMOTE = new LinkedHashMap<>();

    /** Test hook: where a packet goes instead of the relay (null = the relay). */
    static volatile BiPredicate<String, JsonElement> testSender;
    static volatile String lastSent = "";

    private static volatile boolean resend;
    private static String lastJson;
    private static long lastSendMs;
    private static int ticks;

    private LuckShare() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LuckShare", client -> tick()));
        RelayClient.addListener(new RelayListener() {
            @Override
            public void onConnected(String room, List<String> online) {
                synchronized (REMOTE) {
                    REMOTE.clear();
                }
                resend = true;
            }

            @Override
            public void onPresence(String event, String name, List<String> online) {
                if ("join".equals(event)) {
                    resend = true;
                } else if ("leave".equals(event)) {
                    synchronized (REMOTE) {
                        REMOTE.remove(name);
                    }
                }
            }

            @Override
            public void onData(String from, String key, JsonElement value) {
                if (KEY.equals(key)) {
                    receive(from, value);
                }
            }
        });
    }

    /** Why your luck is not being sent, or null when it is allowed. */
    public static String blockReason() {
        if (!LuckConfig.getInstance().isShareMyLuck()) {
            return "Share My Luck is off";
        }
        return !ModUserConfig.getInstance().isShare() ? "Show Me As A Mod User is off" : null;
    }

    private static void tick() {
        if (++ticks % 20 != 0) {
            return;
        }
        BiPredicate<String, JsonElement> sender = testSender;
        if (blockReason() != null || (sender == null && !RelayClient.isConnected())) {
            lastJson = null;
            return;
        }
        JsonObject payload = payload();
        String json = payload.toString();
        long now = System.currentTimeMillis();
        boolean due = !json.equals(lastJson) || resend || now - lastSendMs >= HEARTBEAT_MS;
        if (!due || now - lastSendMs < 5_000) {
            return;
        }
        boolean sent = sender != null ? sender.test(KEY, payload) : RelayClient.sendData(KEY, payload);
        if (sent) {
            lastSent = json;
            lastJson = json;
            lastSendMs = now;
            resend = false;
        }
    }

    /** Your summary over every profile of this account. */
    static JsonObject payload() {
        LuckConfig cfg = LuckConfig.getInstance();
        List<LuckStore.Row> rows = new ArrayList<>(LuckStore.rows(null, cfg.getChestOdds(), false));
        rows.removeIf(r -> r.expected() <= 0);
        LuckStore.Summary s = LuckStore.summarize(rows);
        rows.sort(Comparator.comparingDouble(LuckStore.Row::expected).reversed());
        JsonObject o = new JsonObject();
        o.addProperty("luck", Math.round(s.meanPercentile() * 1000) / 1000.0);
        o.addProperty("drops", s.drops());
        o.addProperty("got", s.got());
        o.addProperty("expected", Math.round(s.expected() * 100) / 100.0);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < rows.size() && i < MAX_ROWS; i++) {
            LuckStore.Row r = rows.get(i);
            JsonArray a = new JsonArray();
            a.add(r.name().length() > 40 ? r.name().substring(0, 40) : r.name());
            a.add(r.got());
            a.add(Math.round(r.expected() * 100) / 100.0);
            arr.add(a);
        }
        o.add("rows", arr);
        return o;
    }

    /** Takes a packet from {@code from}, if See Other Mod Users is on and every field is sane. Any thread. */
    static boolean receive(String from, JsonElement value) {
        if (!ModUserConfig.getInstance().isSeeOthers() || from == null || !from.matches("[A-Za-z0-9_]{1,16}")
                || value == null || !value.isJsonObject()) {
            return false;
        }
        try {
            JsonObject o = value.getAsJsonObject();
            double luck = o.get("luck").getAsDouble();
            int drops = o.get("drops").getAsInt();
            long got = o.get("got").getAsLong();
            double expected = o.get("expected").getAsDouble();
            if (!(luck >= 0 && luck <= 1) || drops < 0 || drops > 100_000 || got < 0 || got > 100_000_000L
                    || !(expected >= 0 && expected < 1e9)) {
                return false;
            }
            List<RemoteRow> rows = new ArrayList<>();
            JsonArray arr = o.getAsJsonArray("rows");
            for (int i = 0; i < arr.size() && i < MAX_ROWS; i++) {
                JsonArray a = arr.get(i).getAsJsonArray();
                String name = a.get(0).getAsString();
                long g = a.get(1).getAsLong();
                double e = a.get(2).getAsDouble();
                if (name.isBlank() || name.length() > 40 || !name.matches("[A-Za-z0-9'\\-:.,&!() ]+") || g < 0
                        || g > 100_000_000L || !(e >= 0 && e < 1e9)) {
                    return false;
                }
                rows.add(new RemoteRow(name, g, e));
            }
            synchronized (REMOTE) {
                REMOTE.put(from, new Remote(from, luck, drops, got, expected, List.copyOf(rows), System.currentTimeMillis()));
            }
            return true;
        } catch (RuntimeException bad) {
            return false;
        }
    }

    /** Everyone who shared in the last 30 minutes, luckiest first; never yourself. */
    public static List<Remote> others() {
        long now = System.currentTimeMillis();
        String self = ProfitScope.currentAccount();
        List<Remote> out = new ArrayList<>();
        synchronized (REMOTE) {
            REMOTE.values().removeIf(r -> now - r.receivedMs() > FORGET_MS);
            for (Remote r : REMOTE.values()) {
                if (!r.from().equalsIgnoreCase(self)) {
                    out.add(r);
                }
            }
        }
        out.sort(Comparator.comparingDouble(Remote::luck).reversed());
        return out;
    }

    static void clearForTest() {
        synchronized (REMOTE) {
            REMOTE.clear();
        }
        lastJson = null;
        lastSent = "";
        lastSendMs = 0;
    }
}
