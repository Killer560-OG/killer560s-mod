package com.killer560.hub.dungeonclass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MOD-WIDE manual class overrides: IGN -> {@link DungeonClass}, persisted in
 * {@code config/killer560smod-classoverrides.json}.
 * <p>
 * killer560, 2026-09-16: "You could implement the class override system as something mod wide as it would be useful
 * during stuff like the leap menu being changed as well to fit classes whenever we have duplicates. I already have
 * the ability to do it [a] custom way through that, but not actually set their class." And on why it exists at all:
 * "there should also be a manual override in case I am in a party with say five mages but one mage is doing
 * berserk's term, one is doing archer's term, etc." - so the override is about the ROLE a person is playing, and
 * the same IGN may be assigned a class that differs from their real one.
 * <p>
 * One lookup, {@link #classOf(String, DungeonClass)}: the override when one exists, else whatever the caller
 * detected. Case-insensitive on the IGN. Every place in the mod that resolves a player to a class reads through it
 * ({@code leapmenu/PartyTracker.classOf} - which the Leap Menu, Custom Leap Menu, Live Map, Run Stats, Teammates,
 * Ability Cooldown, Run Summary, Core Entry Times and {@code witherdragons/P5State.selfClass} all sit on -
 * {@code fastleap/Teammates.firstAliveOfClass} - Fast Leap's class targeting and AP3's leap nodes - and
 * {@code dungeonalerts/ClassColors}), so setting someone's class once fixes all of them at the same time.
 * <p>
 * Deliberately NOT gated on the cheat build: it is a display / targeting preference, not automation.
 * {@link #set} and {@link #clear} save immediately (a GUI mutation that forgets {@link #save()} must still persist -
 * every setting in this mod survives a restart), and {@link #load} is registered in
 * {@code profiles/ProfileManager.reloadAllConfigs()}.
 */
public final class ClassOverrides {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-classoverrides");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-classoverrides.json");
    /** A friend's file, not a network input - but still bounded. */
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_IGN = 16;

    private static ClassOverrides instance;

    /** lowercase IGN -> (IGN as typed, class). Insertion order is the order the tab shows rows in. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private record Entry(String ign, DungeonClass clazz) {
    }

    private ClassOverrides() {
    }

    public static ClassOverrides getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ClassOverrides cfg = new ClassOverrides();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject overrides = ConfigJson.getObject(o, "overrides");
                if (overrides != null) {
                    for (String rawIgn : overrides.keySet()) {
                        if (cfg.entries.size() >= MAX_ENTRIES) {
                            break;
                        }
                        String ign = cleanIgn(rawIgn);
                        DungeonClass clazz = DungeonClass.byName(ConfigJson.getString(overrides, rawIgn, null));
                        if (ign != null && clazz != null) {
                            cfg.entries.put(ign.toLowerCase(Locale.ROOT), new Entry(ign, clazz));
                        }
                    }
                }
            } catch (Exception e) {
                // unreadable file (not just a bad key) - keep empty; the next explicit set()/save() rewrites it
                LOGGER.warn("[ClassOverrides] Could not read {}: {}", CONFIG_PATH.getFileName(), e.toString());
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("note", "Manual class overrides (IGN -> class). Wins over the tab list everywhere in the mod.");
            JsonObject overrides = new JsonObject();
            for (Entry e : entries.values()) {
                overrides.addProperty(e.ign(), e.clazz().name());
            }
            o.add("overrides", overrides);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[ClassOverrides] Failed to save {}", CONFIG_PATH.getFileName(), e);
        }
    }

    // ------------------------------------------------------------------------------------------- the lookup

    /**
     * The class to treat {@code ign} as: the override when one exists, else {@code detected}. Null-safe on both
     * arguments (a null IGN just returns {@code detected}).
     */
    public static DungeonClass classOf(String ign, DungeonClass detected) {
        if (ign == null) {
            return detected;
        }
        Entry e = getInstance().entries.get(ign.trim().toLowerCase(Locale.ROOT));
        return e == null ? detected : e.clazz();
    }

    /** True when {@code ign} has an override (the tab uses this to mark rows). */
    public static boolean has(String ign) {
        return ign != null && getInstance().entries.containsKey(ign.trim().toLowerCase(Locale.ROOT));
    }

    /** Sets (or replaces) the override for {@code ign} and saves. A null class clears it. @return true when stored. */
    public static boolean set(String ign, DungeonClass cls) {
        String clean = cleanIgn(ign);
        if (clean == null) {
            return false;
        }
        if (cls == null) {
            return clear(clean);
        }
        ClassOverrides cfg = getInstance();
        String key = clean.toLowerCase(Locale.ROOT);
        if (!cfg.entries.containsKey(key) && cfg.entries.size() >= MAX_ENTRIES) {
            return false;
        }
        cfg.entries.put(key, new Entry(clean, cls));
        cfg.save();
        return true;
    }

    /** Removes the override for {@code ign} and saves. @return true when one existed. */
    public static boolean clear(String ign) {
        if (ign == null) {
            return false;
        }
        ClassOverrides cfg = getInstance();
        boolean removed = cfg.entries.remove(ign.trim().toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            cfg.save();
        }
        return removed;
    }

    /** Removes every override and saves. */
    public static void clearAll() {
        ClassOverrides cfg = getInstance();
        if (!cfg.entries.isEmpty()) {
            cfg.entries.clear();
            cfg.save();
        }
    }

    /** Every override, IGN (as typed) -> class, in insertion order. A copy: mutate through {@link #set}/{@link #clear}. */
    public static Map<String, DungeonClass> all() {
        Map<String, DungeonClass> out = new LinkedHashMap<>();
        for (Entry e : getInstance().entries.values()) {
            out.put(e.ign(), e.clazz());
        }
        return Collections.unmodifiableMap(out);
    }

    /** A Minecraft IGN: 1-16 of {@code [A-Za-z0-9_]}, formatting and whitespace stripped. Null when it is not one. */
    static String cleanIgn(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("§.", "").strip();
        if (t.isEmpty() || t.length() > MAX_IGN || !t.matches("[A-Za-z0-9_]+")) {
            return null;
        }
        return t;
    }
}
