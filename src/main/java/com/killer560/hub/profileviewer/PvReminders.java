package com.killer560.hub.profileviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModPaths;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Profile Viewer reminders (the SkyBlockPV styles' forge slot click): a chat line at a set time, kept across restarts in
 * one small file, keyed by id so setting a slot's reminder again replaces it. The tick only compares the clock with the
 * next due time; the file is read once, off the client thread, and written off it on every change.
 */
public final class PvReminders {
    private static final Path FILE = ModPaths.config("killer560smod-profileviewer-reminders.json");
    private static final int MAX = 64;

    record Reminder(String id, String message, long at) {
    }

    private static final List<Reminder> LIST = new ArrayList<>();
    private static volatile long next = Long.MAX_VALUE;
    private static volatile boolean loaded;

    private PvReminders() {
    }

    /** From the Profile Viewer's client tick, with a player in the world (a line due earlier waits for one). */
    static void tick() {
        if (!loaded) {
            loaded = true;
            java.util.concurrent.CompletableFuture.runAsync(PvReminders::load);
            return;
        }
        if (System.currentTimeMillis() < next) return;
        List<Reminder> due = new ArrayList<>();
        synchronized (LIST) {
            long now = System.currentTimeMillis();
            while (!LIST.isEmpty() && LIST.get(0).at() <= now) due.add(LIST.remove(0));
            changed();
        }
        for (Reminder r : due) ModChat.send("Profile Viewer", Component.literal(r.message()));
    }

    /** Sets (or replaces) the reminder {@code id}: {@code message} in chat at {@code at} (epoch ms). */
    public static void add(String id, String message, long at) {
        synchronized (LIST) {
            LIST.removeIf(r -> r.id().equals(id));
            if (LIST.size() >= MAX) LIST.remove(LIST.size() - 1);
            int i = 0;
            while (i < LIST.size() && LIST.get(i).at() <= at) i++;
            LIST.add(i, new Reminder(id, message, at));
            changed();
        }
    }

    /** The reminder set under {@code id}, or null (testkit). */
    static Reminder get(String id) {
        synchronized (LIST) {
            for (Reminder r : LIST) if (r.id().equals(id)) return r;
        }
        return null;
    }

    private static void load() {
        JsonObject root = ConfigJson.read(FILE);
        JsonArray arr = root == null ? null : ConfigJson.getArray(root, "reminders");
        if (arr == null) return;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            String id = ConfigJson.getString(o, "id", null), msg = ConfigJson.getString(o, "message", null);
            long at = ConfigJson.getLong(o, "at", 0);
            if (id != null && msg != null && at > 0 && get(id) == null) add(id, msg, at);
        }
    }

    /** Under LIST's lock: the next due time, and the file written off the client thread. */
    private static void changed() {
        next = LIST.isEmpty() ? Long.MAX_VALUE : LIST.get(0).at();
        JsonArray arr = new JsonArray();
        for (Reminder r : LIST) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("message", r.message());
            o.addProperty("at", r.at());
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("reminders", arr);
        pendingSave = root;
        java.util.concurrent.CompletableFuture.runAsync(PvReminders::flush);
    }

    private static volatile JsonObject pendingSave;
    private static final Object SAVE_LOCK = new Object();

    /** Writes the newest snapshot only, so two saves never land out of order. */
    private static void flush() {
        synchronized (SAVE_LOCK) {
            JsonObject root = pendingSave;
            pendingSave = null;
            if (root != null) ConfigJson.write(FILE, root);
        }
    }
}
