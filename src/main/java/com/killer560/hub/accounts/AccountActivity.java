package com.killer560.hub.accounts;

import com.google.gson.JsonObject;
import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedFile;
import com.killer560.hub.shared.SharedRecords;
import com.killer560.hub.shared.SharedWriter;
import com.killer560.hub.util.FeatureGuard;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * When each account was last in use and for how long it was in a world, for the Swap Accounts screen's Recently Played
 * and Most Playtime sorts (acctswap-1117). Only the account's UUID, a duration and a time are kept, in the shared
 * record store (never a token). The tick listener only adds 50 ms to a counter while in a world; the file is written
 * off the client thread when the account changes, the world is left, and every 5 minutes.
 */
public final class AccountActivity {

    /** Playtime and last-used time of one account; both 0 when nothing is recorded. */
    public record Entry(long playMs, long lastMs) {
    }

    private static final int FLUSH_TICKS = 6000;

    private static UUID pendingUuid;
    private static String pendingId;
    private static long pendingMs;
    private static int ticks;
    private static boolean wasInWorld;

    private AccountActivity() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("AccountActivity.onTick", AccountActivity::onTick));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> flush());
    }

    /** The key an account is stored under: its UUID without dashes, lower case. */
    public static String key(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static void onTick(Minecraft mc) {
        UUID id = mc.getUser() == null ? null : mc.getUser().getProfileId();
        if (id == null) {
            return;
        }
        boolean inWorld = mc.level != null;
        if (!id.equals(pendingUuid)) {
            flush();
            pendingUuid = id;
            pendingId = key(id.toString());
            flush();
        }
        if (inWorld) {
            pendingMs += 50;
            ticks++;
        }
        if (wasInWorld && !inWorld || ticks >= FLUSH_TICKS) {
            flush();
        }
        wasInWorld = inWorld;
    }

    /** Writes what has been counted for the account in use (and stamps it as used now), on the shared writer thread. */
    private static void flush() {
        String id = pendingId;
        long ms = pendingMs;
        pendingMs = 0;
        ticks = 0;
        if (id != null) {
            SharedWriter.submit(() -> record(id, ms, System.currentTimeMillis()));
        }
    }

    /** Adds {@code addPlayMs} to the account's playtime and raises its last-used time to {@code lastMs}. */
    public static synchronized void record(String uuid, long addPlayMs, long lastMs) {
        SharedFile f = SharedRecords.file(RecordCatalog.ACCOUNT_ACTIVITY);
        JsonObject o = SharedRecords.load(f);
        JsonObject all = o.has("accounts") && o.get("accounts").isJsonObject() ? o.getAsJsonObject("accounts") : new JsonObject();
        o.add("accounts", all);
        String k = key(uuid);
        JsonObject e = all.has(k) && all.get(k).isJsonObject() ? all.getAsJsonObject(k) : new JsonObject();
        all.add(k, e);
        e.addProperty("playMs", num(e, "playMs") + Math.max(0, addPlayMs));
        e.addProperty("lastMs", Math.max(num(e, "lastMs"), lastMs));
        SharedRecords.save(f, o);
    }

    /** Every recorded account by {@link #key}; read once when the screen opens. */
    public static Map<String, Entry> snapshot() {
        Map<String, Entry> out = new HashMap<>();
        JsonObject o = SharedRecords.load(SharedRecords.file(RecordCatalog.ACCOUNT_ACTIVITY));
        if (o.has("accounts") && o.get("accounts").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> en : o.getAsJsonObject("accounts").entrySet()) {
                if (en.getValue().isJsonObject()) {
                    JsonObject e = en.getValue().getAsJsonObject();
                    out.put(en.getKey(), new Entry(num(e, "playMs"), num(e, "lastMs")));
                }
            }
        }
        return out;
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
