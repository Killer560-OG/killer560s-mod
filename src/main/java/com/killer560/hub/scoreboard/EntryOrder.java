package com.killer560.hub.scoreboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One of the Custom Scoreboard's ordered lists (lines, events, chunked stats) in SkyHanni's model: the entries that
 * are ON the board, top to bottom, and an "available" pool of everything else (killer560, 2026-10-07: "draggable to be
 * in order and ... the add and trashcan system SkyHanni uses instead of an individual toggle system"). There is no
 * per-entry on/off flag any more - an entry is drawn exactly when it is in {@link #active()}, in that order; the
 * trash can moves it to the pool and Add puts it back at the end.
 *
 * <p>Saved as {@code {"active": [...], "available": [...]}}. An id in neither (a line added in a newer build) joins
 * the board right after the nearest entry before it in the default order if it is on by default, else the pool.
 *
 * <p>Before this, each list was {@code [{"id": ..., "enabled": ...}, ...]} under the old keys ({@code entries},
 * {@code events}, {@code chunkedStats}), drawn in saved order with the disabled ones skipped. {@link #load} reads that
 * when the new key is missing - with the old loader's own rules for ids the file did not name - and keeps exactly the
 * enabled ids in exactly their saved order, so an existing board draws the same lines in the same order after the
 * update. {@link #toLegacyJson} is still written beside the new key, so a jar from before this change (or a friend's,
 * receiving shared settings) reads the same board rather than its defaults.
 */
public final class EntryOrder<E extends Enum<E>> {

    private final Class<E> type;
    private final Predicate<E> onByDefault;
    private final List<E> active = new ArrayList<>();

    public EntryOrder(Class<E> type, Predicate<E> onByDefault) {
        this.type = type;
        this.onByDefault = onByDefault;
        reset();
    }

    /** The entries on the board, top to bottom. Read-only view; change it through this class. */
    public List<E> active() {
        return Collections.unmodifiableList(active);
    }

    public int size() {
        return active.size();
    }

    public E get(int index) {
        return active.get(index);
    }

    public int indexOf(E id) {
        return active.indexOf(id);
    }

    public boolean contains(E id) {
        return active.contains(id);
    }

    /** Everything not on the board, in default (enum) order. */
    public List<E> available() {
        List<E> out = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            if (!active.contains(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** Puts {@code id} at the bottom of the board (no-op if it is already on it). */
    public void add(E id) {
        if (id != null && !active.contains(id)) {
            active.add(id);
        }
    }

    /** Takes the entry at {@code index} off the board, into the pool. */
    public E removeAt(int index) {
        if (index < 0 || index >= active.size()) {
            return null;
        }
        return active.remove(index);
    }

    public boolean remove(E id) {
        return active.remove(id);
    }

    /**
     * Moves the entry at {@code from} so it ends up at index {@code to} of the resulting list (both clamped). This is
     * a drag-and-drop: {@code to} is the insertion slot counted with the dragged entry already lifted out.
     */
    public void move(int from, int to) {
        if (from < 0 || from >= active.size()) {
            return;
        }
        E e = active.remove(from);
        active.add(Math.max(0, Math.min(active.size(), to)), e);
    }

    /** Replaces the board with {@code ids} in that order (unknown/duplicate entries dropped). */
    public void setActive(List<E> ids) {
        active.clear();
        if (ids != null) {
            for (E e : ids) {
                add(e);
            }
        }
    }

    /** The default board: every entry that is on by default, in enum order. */
    public void reset() {
        active.clear();
        for (E e : type.getEnumConstants()) {
            if (onByDefault.test(e)) {
                active.add(e);
            }
        }
    }

    // ---- persistence ------------------------------------------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("active", ids(active));
        o.add("available", ids(available()));
        return o;
    }

    /** The pre-2026-10-07 shape: board entries enabled in order, then the pool disabled. */
    public JsonArray toLegacyJson() {
        JsonArray array = new JsonArray();
        for (E e : active) {
            array.add(legacyRow(e, true));
        }
        for (E e : available()) {
            array.add(legacyRow(e, false));
        }
        return array;
    }

    /**
     * Reads {@code current} (the new shape) if it is there, else migrates {@code legacy} (the old toggle list), else
     * leaves the defaults. Returns which one it used: "current", "legacy" or "default".
     */
    public String load(JsonElement current, JsonArray legacy) {
        if (current != null && current.isJsonObject() && current.getAsJsonObject().has("active")) {
            loadCurrent(current.getAsJsonObject());
            return "current";
        }
        if (legacy != null) {
            loadLegacy(legacy);
            return "legacy";
        }
        reset();
        return "default";
    }

    private void loadCurrent(JsonObject o) {
        List<E> loaded = new ArrayList<>();
        Set<E> known = EnumSet.noneOf(type);
        for (E e : parseIds(ConfigJson.getArray(o, "active"))) {
            if (known.add(e)) {
                loaded.add(e);
            }
        }
        known.addAll(parseIds(ConfigJson.getArray(o, "available")));
        E[] all = type.getEnumConstants();
        for (int d = 0; d < all.length; d++) {
            E e = all[d];
            if (known.contains(e)) {
                continue;
            }
            known.add(e);
            if (!onByDefault.test(e)) {
                continue;
            }
            int insertAt = 0;
            for (int p = d - 1; p >= 0; p--) {
                int idx = loaded.indexOf(all[p]);
                if (idx >= 0) {
                    insertAt = idx + 1;
                    break;
                }
            }
            loaded.add(insertAt, e);
        }
        active.clear();
        active.addAll(loaded);
    }

    /**
     * The old {@code loadRows} exactly (saved order, unknown ids dropped; an id the file does not name is inserted
     * right after the nearest id before it in the default order with its default state, or at the end), then the
     * board is the enabled rows in that order.
     */
    private void loadLegacy(JsonArray array) {
        List<E> ids = new ArrayList<>();
        List<Boolean> on = new ArrayList<>();
        Set<E> seen = EnumSet.noneOf(type);
        for (JsonElement el : array) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            E id = ConfigJson.getEnum(o, "id", type, null);
            if (id == null || !seen.add(id)) {
                continue;
            }
            ids.add(id);
            on.add(ConfigJson.getBool(o, "enabled", true));
        }
        E[] all = type.getEnumConstants();
        for (int d = 0; d < all.length; d++) {
            E def = all[d];
            if (seen.contains(def)) {
                continue;
            }
            int insertAt = ids.size();
            for (int p = d - 1; p >= 0; p--) {
                int idx = ids.indexOf(all[p]);
                if (idx >= 0) {
                    insertAt = idx + 1;
                    break;
                }
            }
            ids.add(insertAt, def);
            on.add(insertAt, onByDefault.test(def));
            seen.add(def);
        }
        active.clear();
        for (int i = 0; i < ids.size(); i++) {
            if (on.get(i)) {
                active.add(ids.get(i));
            }
        }
    }

    private List<E> parseIds(JsonArray array) {
        List<E> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (JsonElement el : array) {
            if (!el.isJsonPrimitive()) {
                continue;
            }
            try {
                out.add(Enum.valueOf(type, el.getAsString()));
            } catch (IllegalArgumentException ignored) {
                // an id from another build: dropped
            }
        }
        return out;
    }

    private static <E extends Enum<E>> JsonArray ids(List<E> list) {
        JsonArray array = new JsonArray();
        for (E e : list) {
            array.add(e.name());
        }
        return array;
    }

    private static JsonObject legacyRow(Enum<?> e, boolean enabled) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.name());
        o.addProperty("enabled", enabled);
        return o;
    }
}
