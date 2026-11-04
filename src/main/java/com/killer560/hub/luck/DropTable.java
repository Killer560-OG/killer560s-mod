package com.killer560.hub.luck;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The drop table How Lucky Are You ships with ({@code assets/killer560smod/rngtracker/drop-table.json}), built from
 * hypixelskyblock.minecraft.wiki by {@code tools/rng-table/build_table.py} (every chance read from the wiki's own text; the
 * file's {@code notes} say what each number means and which page it came from).
 *
 * <p>An ACTIVITY is one attempt the mod can count: {@code chest:F7:Bedrock} (one claimed F7 Bedrock Chest),
 * {@code slayer:revenant:5} (one Revenant Horror V kill), or {@code untracked} for a drop whose attempts are not counted
 * yet (its drops are still recorded). A DROP is an item of one activity with its chance per attempt; its key is
 * {@code activity + "|" + name}.
 */
public final class DropTable {

    private static final Logger LOGGER = ModLog.get("killer560smod-luck");
    static final String RESOURCE = "/assets/killer560smod/rngtracker/drop-table.json";

    public record Activity(String key, String label, String group, String url) {
    }

    /**
     * @param chance    base chance per attempt (chests: the wiki's "No Bonuses" average; slayers: every drop unlocked,
     *                  no Magic Find); NaN for an untracked drop
     * @param chanceMax chests: the wiki's "S+ Max Bonuses" average; NaN when the table has none
     * @param mf        Magic Find raises it (Slayer#Boss Loot: Main/Extra items under 5%; mobs: under 5% after Looting)
     * @param loot      a mob drop Looting raises (Looting page: x(1 + 0.15 x level); never slayers or pets)
     * @param mob       for a mob drop, the mob's name as its nametag shows it (the page title less any "(...)")
     * @param level     for a mob drop, the table's mob level (0 when the page gives none)
     */
    public record Drop(String name, String activity, double chance, double chanceMax, String pool, int req, boolean mf,
                       String category, String chanceText, boolean loot, String mob, int level) {
        public String key() {
            return activity + "|" + name;
        }

        public boolean tracked() {
            return !Double.isNaN(chance);
        }
    }

    private static volatile DropTable instance;

    private final Map<String, Activity> activities;
    private final List<Drop> drops;
    private final Map<String, Drop> byKey = new HashMap<>();
    private final Map<String, List<Drop>> byActivity = new HashMap<>();
    private final Map<String, List<Drop>> byName = new HashMap<>();
    private final List<String> notes;
    private final Map<String, List<String>> byMob = new HashMap<>();
    private final Map<String, Integer> mobLevel = new HashMap<>();

    private DropTable(Map<String, Activity> activities, List<Drop> drops, List<String> notes) {
        this.activities = Collections.unmodifiableMap(activities);
        this.drops = List.copyOf(drops);
        this.notes = List.copyOf(notes);
        for (Drop d : drops) {
            byKey.put(d.key(), d);
            byActivity.computeIfAbsent(d.activity(), k -> new ArrayList<>()).add(d);
            byName.computeIfAbsent(normalize(d.name()), k -> new ArrayList<>()).add(d);
            if (!d.mob().isEmpty()) {
                List<String> acts = byMob.computeIfAbsent(normalize(d.mob()), k -> new ArrayList<>());
                if (!acts.contains(d.activity())) {
                    acts.add(d.activity());
                    mobLevel.put(d.activity(), d.level());
                }
            }
        }
    }

    public static DropTable get() {
        DropTable t = instance;
        if (t == null) {
            synchronized (DropTable.class) {
                t = instance;
                if (t == null) {
                    t = load();
                    instance = t;
                }
            }
        }
        return t;
    }

    private static DropTable load() {
        try (InputStream in = DropTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.error("[Luck] {} is missing from the jar", RESOURCE);
                return new DropTable(Map.of(), List.of(), List.of());
            }
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            LOGGER.error("[Luck] Could not read {}", RESOURCE, e);
            return new DropTable(Map.of(), List.of(), List.of());
        }
    }

    static DropTable parse(JsonObject root) {
        Map<String, Activity> acts = new LinkedHashMap<>();
        JsonObject a = root.getAsJsonObject("activities");
        for (String key : a.keySet()) {
            JsonObject o = a.getAsJsonObject(key);
            acts.put(key, new Activity(key, str(o, "label", key), str(o, "group", "Other"), str(o, "url", "")));
        }
        List<Drop> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("drops")) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Drop(str(o, "name", "?"), str(o, "activity", "untracked"), num(o, "chance"), num(o, "chanceMax"),
                    str(o, "pool", ""), o.has("req") ? o.get("req").getAsInt() : 0,
                    o.has("mf") && o.get("mf").getAsBoolean(), str(o, "cat", "other"), str(o, "chanceText", ""),
                    o.has("loot") && o.get("loot").getAsBoolean(), str(o, "mob", ""), o.has("level") ? o.get("level").getAsInt() : 0));
        }
        List<String> notes = new ArrayList<>();
        if (root.has("notes")) {
            root.getAsJsonArray("notes").forEach(n -> notes.add(n.getAsString()));
        }
        return new DropTable(acts, out, notes);
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : Double.NaN;
    }

    public List<Drop> drops() {
        return drops;
    }

    public List<String> notes() {
        return notes;
    }

    public Activity activity(String key) {
        Activity a = activities.get(key);
        return a != null ? a : new Activity(key, key, "Other", "");
    }

    public Map<String, Activity> activities() {
        return activities;
    }

    public Drop byKey(String key) {
        return byKey.get(key);
    }

    public List<Drop> ofActivity(String activity) {
        return byActivity.getOrDefault(activity, List.of());
    }

    /** Every table drop with this name (any activity), by {@link #normalize}d name. */
    public List<Drop> named(String name) {
        return byName.getOrDefault(normalize(name), List.of());
    }

    /**
     * The activity of a mob with this nametag name ("Zealot") and level (0 = unknown): the table whose level matches, else
     * the page named exactly so ("Blaze", not "Blaze (Minion Mob)"), else the first. Null for a mob the table lacks.
     */
    public String mobActivity(String name, int level) {
        List<String> acts = byMob.get(normalize(name));
        if (acts == null || acts.isEmpty()) {
            return null;
        }
        if (level > 0) {
            for (String a : acts) {
                if (mobLevel.getOrDefault(a, 0) == level) {
                    return a;
                }
            }
        }
        String exact = "mob:" + name.trim();
        for (String a : acts) {
            if (a.equalsIgnoreCase(exact) || a.regionMatches(true, 0, exact + ":", 0, exact.length() + 1)) {
                return a;
            }
        }
        return acts.get(0);
    }

    /**
     * Whether an activity's attempts come only from a claim (a dungeon or Kuudra chest, a Nucleus bundle, an
     * experiment), so a drop line or a pickup is never its drop.
     */
    public static boolean claimOnly(String activity) {
        return activity.startsWith("chest:") || activity.startsWith("kuudra:") || activity.equals("nucleus")
                || activity.startsWith("experiment:");
    }

    /** The drop called {@code name} in {@code activity}, or null. */
    public Drop find(String activity, String name) {
        String n = normalize(name);
        for (Drop d : ofActivity(activity)) {
            if (normalize(d.name()).equals(n)) {
                return d;
            }
        }
        return null;
    }

    /**
     * The name as the table and chat both spell it: formatting codes, the rune diamond, a "Shiny " prefix and an
     * amount ("2x ") dropped, case and spacing folded. Item names hold apostrophes and digits, which are kept.
     */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = name.replaceAll("§.", "").replace('◆', ' ').trim();
        s = s.replaceFirst("^\\d{1,4}x ", "");
        s = s.replaceFirst("^(?i)shiny ", "");
        return s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
