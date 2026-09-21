package com.killer560.hub.dungeonclass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.players.PlayerNames;
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
import java.util.UUID;

/**
 * MOD-WIDE manual class overrides: player -&gt; {@link DungeonClass}, persisted in
 * {@code config/killer560smod-classoverrides.json}.
 * <p>
 * killer560, 2026-09-16: "You could implement the class override system as something mod wide as it would be useful
 * during stuff like the leap menu being changed as well to fit classes whenever we have duplicates. I already have
 * the ability to do it [a] custom way through that, but not actually set their class." And on why it exists at all:
 * "there should also be a manual override in case I am in a party with say five mages but one mage is doing
 * berserk's term, one is doing archer's term, etc." - so the override is about the ROLE a person is playing, and
 * the same person may be assigned a class that differs from their real one.
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
 * <p>
 * 2026-09-21, killer560's standing rule ("anything that remembers a player keys on their UUID, never their IGN,
 * and shows the current IGN client-side by resolving it from the UUID - incase they ever change their name"):
 * every entry keys on a {@link PlayerNames} UUID once one resolves. An entry added while its UUID can't be
 * resolved yet (an offline friend typed in by name, Mojang down) is kept working under its typed IGN exactly as
 * before - see {@link #putEntry} / {@link #resolveAndUpgrade} - and is upgraded to the UUID form, never dropped,
 * the moment {@link PlayerNames} resolves one. {@link #all()} always shows the current name for a resolved entry.
 */
public final class ClassOverrides {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-classoverrides");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-classoverrides.json");
    /** A friend's file, not a network input - but still bounded. */
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_IGN = 16;
    /** Key prefix for an entry that hasn't resolved a UUID yet - see class doc. */
    private static final String UNRESOLVED_PREFIX = "name:";

    private static ClassOverrides instance;

    /** Key -&gt; entry: {@code uuid.toString()} once {@link PlayerNames} has resolved one, else
     *  {@code "name:" + lowercase ign}. Insertion order is the order the tab shows rows in. */
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** @param ign the typed/last-known name (always present - it's what the tab shows until a resolve, and
     *  what a resolved entry falls back to if {@link PlayerNames} hasn't got a fresher name yet).
     *  @param uuid null until {@link PlayerNames} resolves one for {@code ign}. */
    private record Entry(String ign, UUID uuid, DungeonClass clazz) {
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
                JsonElement raw = o.has("overrides") ? o.get("overrides") : null;
                if (raw != null && raw.isJsonArray()) {
                    loadArrayFormat(cfg, raw.getAsJsonArray());
                } else if (raw != null && raw.isJsonObject()) {
                    // Pre-2026-09-21 format: ign -> class, no UUIDs. Loaded as unresolved entries, each of
                    // which kicks off a resolve below and rewrites itself to the UUID form once one arrives.
                    loadLegacyObjectFormat(cfg, raw.getAsJsonObject());
                }
            } catch (Exception e) {
                // unreadable file (not just a bad key) - keep empty; the next explicit set()/save() rewrites it
                LOGGER.warn("[ClassOverrides] Could not read {}: {}", CONFIG_PATH.getFileName(), e.toString());
            }
        }
        instance = cfg;
    }

    private static void loadArrayFormat(ClassOverrides cfg, JsonArray overrides) {
        for (JsonElement el : overrides) {
            if (cfg.entries.size() >= MAX_ENTRIES || !el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String ign = cleanIgn(ConfigJson.getString(o, "ign", null));
            DungeonClass clazz = DungeonClass.byName(ConfigJson.getString(o, "class", null));
            if (ign == null || clazz == null) {
                continue;
            }
            putEntry(cfg, new Entry(ign, parseUuid(ConfigJson.getString(o, "uuid", null)), clazz));
        }
    }

    private static void loadLegacyObjectFormat(ClassOverrides cfg, JsonObject overrides) {
        for (String rawIgn : overrides.keySet()) {
            if (cfg.entries.size() >= MAX_ENTRIES) {
                break;
            }
            String ign = cleanIgn(rawIgn);
            DungeonClass clazz = DungeonClass.byName(ConfigJson.getString(overrides, rawIgn, null));
            if (ign != null && clazz != null) {
                putEntry(cfg, new Entry(ign, null, clazz));
            }
        }
    }

    /** Stores {@code e} at the right key and, if it has no UUID yet, asks {@link PlayerNames} to resolve one. */
    private static void putEntry(ClassOverrides cfg, Entry e) {
        cfg.entries.put(keyOf(e), e);
        if (e.uuid() == null) {
            resolveAndUpgrade(cfg, e.ign());
        }
    }

    /** Asks {@link PlayerNames} to resolve {@code ign}, and upgrades the unresolved entry to the UUID form the
     *  moment it does. Mutates {@code cfg} directly, never {@link #getInstance()} - while {@link #load()} is
     *  still building a new instance, {@code getInstance()} may still return the OLD one (or null). Only
     *  upgrades if that IGN's entry is still exactly what it was when the lookup started, so a user who
     *  retyped or removed it in the meantime isn't clobbered by a slow lookup landing late. */
    private static void resolveAndUpgrade(ClassOverrides cfg, String ign) {
        PlayerNames.resolveAsync(ign, uuid -> {
            if (uuid == null) {
                return;
            }
            String oldKey = UNRESOLVED_PREFIX + ign.toLowerCase(Locale.ROOT);
            Entry old = cfg.entries.get(oldKey);
            if (old != null && ign.equalsIgnoreCase(old.ign())) {
                cfg.entries.remove(oldKey);
                cfg.entries.put(uuid.toString(), new Entry(old.ign(), uuid, old.clazz()));
                cfg.save();
            }
        });
    }

    private static String keyOf(Entry e) {
        return e.uuid() != null ? e.uuid().toString() : UNRESOLVED_PREFIX + e.ign().toLowerCase(Locale.ROOT);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("note", "Manual class overrides, keyed on UUID once resolved (kept under the typed "
                    + "IGN until then). Wins over the tab list everywhere in the mod.");
            JsonArray overrides = new JsonArray();
            for (Entry e : entries.values()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("ign", e.ign());
                if (e.uuid() != null) {
                    entry.addProperty("uuid", e.uuid().toString());
                }
                entry.addProperty("class", e.clazz().name());
                overrides.add(entry);
            }
            o.add("overrides", overrides);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[ClassOverrides] Failed to save {}", CONFIG_PATH.getFileName(), e);
        }
    }

    // ------------------------------------------------------------------------------------------- the lookup

    /** Finds the entry for {@code ign} the UUID-first way: by the live UUID {@link PlayerNames} already knows
     *  for that name when it knows one, else by the IGN itself for an entry that hasn't resolved a UUID yet -
     *  "matching at runtime compares UUIDs when both sides have one, falling back to name only for unmigrated
     *  entries." Package-visible for the tab. */
    private static Entry find(String ign) {
        ClassOverrides cfg = getInstance();
        String clean = ign.trim();
        UUID uuid = PlayerNames.uuidFor(clean);
        Entry e = uuid != null ? cfg.entries.get(uuid.toString()) : null;
        return e != null ? e : cfg.entries.get(UNRESOLVED_PREFIX + clean.toLowerCase(Locale.ROOT));
    }

    /**
     * The class to treat {@code ign} as: the override when one exists, else {@code detected}. Null-safe on both
     * arguments (a null IGN just returns {@code detected}).
     */
    public static DungeonClass classOf(String ign, DungeonClass detected) {
        if (ign == null) {
            return detected;
        }
        Entry e = find(ign);
        return e == null ? detected : e.clazz();
    }

    /** True when {@code ign} has an override (the tab uses this to mark rows). */
    public static boolean has(String ign) {
        return ign != null && find(ign) != null;
    }

    /** Sets (or replaces) the override for {@code ign} and saves. A null class clears it. Resolves {@code ign}
     *  to a UUID immediately when it's already known (e.g. a live party member - almost always instant, no
     *  network) or asynchronously otherwise, per killer560's UUID rule - see class doc. @return true when
     *  stored. */
    public static boolean set(String ign, DungeonClass cls) {
        String clean = cleanIgn(ign);
        if (clean == null) {
            return false;
        }
        if (cls == null) {
            return clear(clean);
        }
        ClassOverrides cfg = getInstance();
        Entry existing = find(clean);
        UUID uuid = existing != null ? existing.uuid() : PlayerNames.uuidFor(clean);
        if (existing == null && cfg.entries.size() >= MAX_ENTRIES) {
            return false;
        }
        if (existing != null) {
            cfg.entries.remove(keyOf(existing));
        }
        Entry fresh = new Entry(clean, uuid, cls);
        cfg.entries.put(keyOf(fresh), fresh);
        cfg.save();
        if (uuid == null) {
            resolveAndUpgrade(cfg, clean);
        }
        return true;
    }

    /** Removes the override for {@code ign} and saves. @return true when one existed. */
    public static boolean clear(String ign) {
        if (ign == null) {
            return false;
        }
        ClassOverrides cfg = getInstance();
        Entry existing = find(ign);
        boolean removed = existing != null && cfg.entries.remove(keyOf(existing)) != null;
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

    /** Every override, current display name (resolved live via {@link PlayerNames} for an entry that has a
     *  UUID, else the name it's still waiting to resolve) -&gt; class, in insertion order. A copy: mutate
     *  through {@link #set}/{@link #clear}. */
    public static Map<String, DungeonClass> all() {
        Map<String, DungeonClass> out = new LinkedHashMap<>();
        for (Entry e : getInstance().entries.values()) {
            String current = e.uuid() != null ? PlayerNames.nameFor(e.uuid()) : null;
            out.put(current != null ? current : e.ign(), e.clazz());
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
