package com.killer560.hub.chocolatefactory.egg;

import com.killer560.hub.shared.RecordCatalog;
import com.killer560.hub.shared.SharedRecords;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.chocolatefactory.ChocolateFactoryConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Hoppity egg tracker (killer560, 2026-10-19: "a tracker to hoppity eggs ... there is no toggle for it"). Counts every egg
 * found, every new rabbit and every duplicate with the chocolate it paid, per hunt and in total, and keeps them between
 * sessions. A hunt starts the first time the calendar says one is running after it was not. Off by default; the switch is
 * in the Chocolate Factory tab. Each count is only added while the switch is on.
 */
public final class EggTracker {

    /** One hunt's counts. */
    public static final class Hunt {
        public long startedMs;
        public int eggs;
        public int newRabbits;
        public int dupes;
        public long dupeChocolate;
    }

    private static final int KEEP_HUNTS = 50;

    private static final List<Hunt> HUNTS = new ArrayList<>();
    private static boolean loaded;

    static {
        // The in-game account switcher: the next read is the new account's hunts (each change is saved as it happens).
        SharedRecords.onAccountChange(() -> {
            synchronized (EggTracker.class) {
                EggTracker.loaded = false;
                HUNTS.clear();
            }
        });
    }
    private static boolean huntWasActive;

    private EggTracker() {
    }

    public static boolean enabled() {
        return ChocolateFactoryConfig.getInstance().isEggTracker();
    }

    /** Called on the client tick: starts a new hunt record when a hunt has just begun. */
    static void tick(long now) {
        if (!enabled()) {
            return;
        }
        ensureLoaded();
        boolean active = HoppityCalendar.huntActive(now);
        if (active && !huntWasActive) {
            Hunt h = new Hunt();
            h.startedMs = now;
            HUNTS.add(0, h);
            while (HUNTS.size() > KEEP_HUNTS) {
                HUNTS.remove(HUNTS.size() - 1);
            }
            save();
        }
        huntWasActive = active;
    }

    static void eggFound() {
        if (current() != null) {
            current().eggs++;
            save();
        }
    }

    static void newRabbit() {
        if (current() != null) {
            current().newRabbits++;
            save();
        }
    }

    static void duplicate(long chocolate) {
        Hunt h = current();
        if (h != null) {
            h.dupes++;
            h.dupeChocolate += chocolate;
            save();
        }
    }

    /** The hunt being played right now, or null when the tracker is off or no hunt has been seen. */
    public static Hunt current() {
        if (!enabled()) {
            return null;
        }
        ensureLoaded();
        return HUNTS.isEmpty() ? null : HUNTS.get(0);
    }

    /** Totals over every kept hunt: eggs, new rabbits, duplicates, chocolate from duplicates, hunts. */
    public static long[] totals() {
        ensureLoaded();
        long eggs = 0;
        long news = 0;
        long dupes = 0;
        long choc = 0;
        for (Hunt h : HUNTS) {
            eggs += h.eggs;
            news += h.newRabbits;
            dupes += h.dupes;
            choc += h.dupeChocolate;
        }
        return new long[]{eggs, news, dupes, choc, HUNTS.size()};
    }

    /** Clears every kept hunt and saves. */
    public static void reset() {
        ensureLoaded();
        HUNTS.clear();
        huntWasActive = false;
        save();
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            // This account's hunts from every instance (shared-stats-1101).
            JsonObject obj = SharedRecords.load(SharedRecords.file(RecordCatalog.EGG_TRACKER));
            JsonArray arr = obj.getAsJsonArray("hunts");
            if (arr == null) {
                return;
            }
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                Hunt h = new Hunt();
                h.startedMs = o.get("startedMs").getAsLong();
                h.eggs = o.get("eggs").getAsInt();
                h.newRabbits = o.get("newRabbits").getAsInt();
                h.dupes = o.get("dupes").getAsInt();
                h.dupeChocolate = o.get("dupeChocolate").getAsLong();
                HUNTS.add(h);
            }
        } catch (Exception ignored) {
            HUNTS.clear();
        }
    }

    private static synchronized void save() {
        try {
            JsonArray arr = new JsonArray();
            for (Hunt h : HUNTS) {
                JsonObject o = new JsonObject();
                o.addProperty("startedMs", h.startedMs);
                o.addProperty("eggs", h.eggs);
                o.addProperty("newRabbits", h.newRabbits);
                o.addProperty("dupes", h.dupes);
                o.addProperty("dupeChocolate", h.dupeChocolate);
                arr.add(o);
            }
            JsonObject root = new JsonObject();
            root.add("hunts", arr);
            SharedRecords.save(SharedRecords.file(RecordCatalog.EGG_TRACKER), root);
        } catch (Exception ignored) {
        }
    }
}
