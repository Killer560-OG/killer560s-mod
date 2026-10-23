package com.killer560.hub.leapmenu;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.players.PlayerNames;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Leap Order settings: the default order ({@link LeapOrderMode}), per class YOU are playing which teammates were
 *  moved by hand into which of the 4 leap menu spots (everyone else follows the default order), plus the class last
 *  picked in the editor. (The old sort/display/assign/customize/GUI-scale settings were removed
 *  2026-09-15 with the class-first Leap Order redo.)
 * <p>
 * 2026-09-21, killer560's standing rule ("anything that remembers a player keys on their UUID, never their IGN,
 * and shows the current IGN client-side by resolving it from the UUID - incase they ever change their name"):
 * each saved spot keys on a {@link PlayerNames} UUID once one resolves - see {@link SlotRef}. The Leap Order
 * editor ({@code LeapOrderScreen}) only ever deals in names (it swaps live party members between the 4 spots),
 * so {@link #getClassOrder}/{@link #setClassOrder} keep their {@code List<String>} shape unchanged and do the
 * UUID bookkeeping internally: a spot almost always resolves instantly (a live party member's UUID is already
 * known from the tab list), and one that can't yet (added while its owner was offline, or loaded from a
 * pre-2026-09-21 save) keeps working exactly as before under its typed name until {@link PlayerNames} resolves
 * it - never dropped. */
public final class LeapMenuConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-leapmenu.json");

    private static LeapMenuConfig instance;

    /** A saved leap-menu spot: the name it was typed/last resolved under (always present - it's what shows
     *  until a resolve, and what a resolved slot falls back to if {@link PlayerNames} doesn't have anything
     *  fresher yet), and its UUID once one resolves. {@code null} in the slot list = left for auto-fill. */
    private record SlotRef(String ign, UUID uuid) {
    }

    /** Class name -&gt; the teammate moved by hand into each of the 4 spots (top-left, top-right, bottom-left,
     *  bottom-right); {@code null} = spot follows the default order. */
    private final Map<String, List<SlotRef>> classOrders = new LinkedHashMap<>();
    private String lastEditedClass = null;
    /** Where every teammate NOT moved by hand goes (2026-10-09). The saved spots above are only the hand-moved ones. */
    private LeapOrderMode defaultOrder = LeapOrderMode.ODIN;
    /** Spots saved before 2026-10-09, when the editor saved all 4 spots on every swap (so every one read as "moved by
     *  hand" and no default order could ever apply). Kept verbatim in the file, never read, so nothing is lost. */
    private JsonObject legacyClassOrders = null;
    /** 2: {@code classOrders} holds only hand-moved spots. Missing = the pre-2026-10-09 format. */
    private static final int FORMAT = 2;
    /** The teammate order Hypixel's own Spirit Leap menu last showed (container slot order) - the order the Leap
     *  Order editor lays its spots out in, so the editor starts from the real menu's order instead of the party
     *  listing order. Persisted so it survives a restart (the first edit after an update is otherwise blind).
     *  Deliberately left as plain names, not UUIDs: it's a pure ordering hint matched against
     *  {@code PartyTracker}'s live (name-only) tab-list listing, not an identity a feature is set to remember. */
    private final List<String> lastLeapOrder = new ArrayList<>();
    /** The teammates of the last dungeon run that started, in leap-menu order ({@link LeapRunMemory}); persisted so the
     *  next run is compared with it across a restart. Empty until a run has started. */
    private final List<String> lastRunParty = new ArrayList<>();
    /** Lower-case names given a class pick or a pin by hand outside a run (in the Hub, or in the dungeon before Mort's
     *  line) since the last run start: that is a choice made FOR the coming run, so its start keeps them for anyone in it
     *  ({@link LeapRunMemory}; killer560, 2026-10-22: picks set in the Hub were reset when the run started). Persisted,
     *  so a restart in the Hub keeps them; emptied at every run start. */
    private final List<String> setBeforeRun = new ArrayList<>();

    // ---- class picks (formerly the separate Class Overrides file; one store with the Leap Order, 2026-10-15) ------------

    /** The legacy file the picks used to live in ({@code killer560smod-classoverrides.json}): read once into
     *  {@link #classPicks} and removed. A profile saved before 2026-10-15 can put it back; the next load migrates it again. */
    private static final Path LEGACY_PICKS_PATH = ModPaths.config("killer560smod-classoverrides.json");
    /** A friend's file, not a network input - but still bounded. */
    public static final int MAX_PICKS = 64;
    /** Key prefix for a pick that has not resolved a UUID yet. */
    private static final String UNRESOLVED_PREFIX = "name:";

    /** One class pick: the class a player is treated as playing everywhere in the mod (it wins over the tab list).
     *  {@code ign} is the typed / last-known name, {@code uuid} null until {@link PlayerNames} resolves one. */
    private record Pick(String ign, UUID uuid, DungeonClass clazz) {
    }

    /** Key ({@code uuid.toString()} once resolved, else {@code "name:" + lowercase ign}) -&gt; pick, in insertion order. */
    private final Map<String, Pick> classPicks = new LinkedHashMap<>();

    private LeapMenuConfig() {
    }

    public static LeapMenuConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LeapMenuConfig cfg = new LeapMenuConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                boolean current = root.has("format") && root.get("format").isJsonPrimitive()
                        && root.get("format").getAsInt() >= FORMAT;
                if (root.has("legacyClassOrders") && root.get("legacyClassOrders").isJsonObject()) {
                    cfg.legacyClassOrders = root.getAsJsonObject("legacyClassOrders");
                }
                if (!current && root.has("classOrders") && root.get("classOrders").isJsonObject()) {
                    // Old full layouts: every spot was saved, moved or not. Read as pins they would freeze each class
                    // exactly as it was and the new default order would never show, so they start unpinned.
                    cfg.legacyClassOrders = root.getAsJsonObject("classOrders");
                } else if (root.has("classOrders") && root.get("classOrders").isJsonObject()) {
                    JsonObject orders = root.getAsJsonObject("classOrders");
                    for (String key : orders.keySet()) {
                        try {
                            List<SlotRef> slots = new ArrayList<>();
                            for (JsonElement el : orders.getAsJsonArray(key)) {
                                slots.add(parseSlot(el));
                            }
                            List<SlotRef> normalized = normalizeSlots(slots);
                            cfg.classOrders.put(key, normalized);
                            for (int i = 0; i < normalized.size(); i++) {
                                SlotRef slot = normalized.get(i);
                                if (slot != null && slot.uuid() == null) {
                                    resolveSlotAsync(cfg, key, i, slot.ign());
                                }
                            }
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
                if (root.has("lastRunParty") && root.get("lastRunParty").isJsonArray()) {
                    for (var el : root.getAsJsonArray("lastRunParty")) {
                        if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                            cfg.lastRunParty.add(el.getAsString());
                        }
                    }
                }
                if (root.has("setBeforeRun") && root.get("setBeforeRun").isJsonArray()) {
                    for (var el : root.getAsJsonArray("setBeforeRun")) {
                        String n = el.isJsonPrimitive() ? cleanIgn(el.getAsString()) : null;
                        if (n != null && cfg.setBeforeRun.size() < MAX_PICKS) {
                            cfg.setBeforeRun.add(n.toLowerCase(Locale.ROOT));
                        }
                    }
                }
                if (root.has("lastLeapOrder") && root.get("lastLeapOrder").isJsonArray()) {
                    for (var el : root.getAsJsonArray("lastLeapOrder")) {
                        if (el.isJsonPrimitive() && !el.getAsString().isBlank()) {
                            cfg.lastLeapOrder.add(el.getAsString());
                        }
                    }
                }
                if (root.has("defaultOrder") && root.get("defaultOrder").isJsonPrimitive()) {
                    cfg.defaultOrder = LeapOrderMode.byName(root.get("defaultOrder").getAsString());
                }
                if (root.has("lastEditedClass") && root.get("lastEditedClass").isJsonPrimitive()) {
                    cfg.lastEditedClass = root.get("lastEditedClass").getAsString();
                }
                if (root.has("classPicks") && root.get("classPicks").isJsonArray()) {
                    readPicks(cfg, root.getAsJsonArray("classPicks"));
                }
            } catch (Exception ignored) {
            }
        }
        migrateLegacyPicks(cfg);
        instance = cfg;
    }

    private static void readPicks(LeapMenuConfig cfg, JsonArray picks) {
        for (JsonElement el : picks) {
            if (cfg.classPicks.size() >= MAX_PICKS || !el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String ign = cleanIgn(ConfigJson.getString(o, "ign", null));
            DungeonClass clazz = DungeonClass.byName(ConfigJson.getString(o, "class", null));
            if (ign != null && clazz != null) {
                putPick(cfg, new Pick(ign, parseUuid(ConfigJson.getString(o, "uuid", null)), clazz));
            }
        }
    }

    /** Moves the old Class Overrides file into this config: its picks are added (a pick already here wins), the result
     *  is saved, and only then is the old file deleted - so a failed save loses nothing. Both file formats the old
     *  store ever wrote are read (an array of {ign, uuid?, class}, and the pre-2026-09-21 ign -> class object). */
    private static void migrateLegacyPicks(LeapMenuConfig cfg) {
        if (!Files.exists(LEGACY_PICKS_PATH)) {
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(LEGACY_PICKS_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement raw = o.get("overrides");
            if (raw != null && raw.isJsonArray()) {
                for (JsonElement el : raw.getAsJsonArray()) {
                    if (!el.isJsonObject()) {
                        continue;
                    }
                    JsonObject e = el.getAsJsonObject();
                    addMigrated(cfg, ConfigJson.getString(e, "ign", null), parseUuid(ConfigJson.getString(e, "uuid", null)),
                            ConfigJson.getString(e, "class", null));
                }
            } else if (raw != null && raw.isJsonObject()) {
                for (String ign : raw.getAsJsonObject().keySet()) {
                    addMigrated(cfg, ign, null, ConfigJson.getString(raw.getAsJsonObject(), ign, null));
                }
            }
            cfg.save();
            if (Files.readString(CONFIG_PATH, StandardCharsets.UTF_8).contains("classPicks")) {
                Files.delete(LEGACY_PICKS_PATH);
            }
        } catch (Exception e) {
            // unreadable or unwritable: leave the old file where it is, the next load tries again
        }
    }

    private static void addMigrated(LeapMenuConfig cfg, String rawIgn, UUID uuid, String className) {
        String ign = cleanIgn(rawIgn);
        DungeonClass clazz = DungeonClass.byName(className);
        if (ign == null || clazz == null || cfg.classPicks.size() >= MAX_PICKS) {
            return;
        }
        Pick pick = new Pick(ign, uuid, clazz);
        if (!cfg.classPicks.containsKey(keyOf(pick)) && cfg.findPick(ign) == null) {
            putPick(cfg, pick);
        }
    }

    /** {@code null}/blank = empty slot. A plain string is the pre-2026-09-21 format (an IGN, "" = empty); an
     *  object is the current {@code {"ign", "uuid"?}} form. */
    private static SlotRef parseSlot(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            String ign = ConfigJson.getString(o, "ign", null);
            return ign == null || ign.isBlank() ? null : new SlotRef(ign, parseUuid(ConfigJson.getString(o, "uuid", null)));
        }
        if (el.isJsonPrimitive()) {
            String ign = el.getAsString();
            return ign == null || ign.isBlank() ? null : new SlotRef(ign, null);
        }
        return null;
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

    /** Asks {@link PlayerNames} to resolve {@code ign} and, if it does, upgrades the slot at
     *  {@code (classKey, index)} to the UUID form - but only if that slot is still exactly what it was when the
     *  lookup started (still unresolved, still the same name), so a user who re-edited that spot in the
     *  meantime isn't clobbered by a slow lookup landing late. Mutates {@code cfg} directly, never
     *  {@link #getInstance()} - while {@link #load()} builds a new instance, {@code getInstance()} may still be
     *  the OLD one (or null). */
    private static void resolveSlotAsync(LeapMenuConfig cfg, String classKey, int index, String ign) {
        PlayerNames.resolveAsync(ign, uuid -> {
            if (uuid == null) {
                return;
            }
            List<SlotRef> current = cfg.classOrders.get(classKey);
            if (current == null || index >= current.size()) {
                return;
            }
            SlotRef now = current.get(index);
            if (now != null && now.uuid() == null && ign.equalsIgnoreCase(now.ign())) {
                List<SlotRef> updated = new ArrayList<>(current);
                updated.set(index, new SlotRef(now.ign(), uuid));
                cfg.classOrders.put(classKey, updated);
                cfg.save();
            }
        });
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("format", FORMAT);
            root.addProperty("defaultOrder", defaultOrder.name());
            JsonObject orders = new JsonObject();
            classOrders.forEach((key, slots) -> {
                JsonArray a = new JsonArray();
                for (SlotRef slot : slots) {
                    if (slot == null) {
                        a.add(JsonNull.INSTANCE);
                        continue;
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("ign", slot.ign());
                    if (slot.uuid() != null) {
                        o.addProperty("uuid", slot.uuid().toString());
                    }
                    a.add(o);
                }
                orders.add(key, a);
            });
            root.add("classOrders", orders);
            if (legacyClassOrders != null) {
                root.add("legacyClassOrders", legacyClassOrders);
            }
            JsonArray leapOrder = new JsonArray();
            lastLeapOrder.forEach(leapOrder::add);
            root.add("lastLeapOrder", leapOrder);
            JsonArray runParty = new JsonArray();
            lastRunParty.forEach(runParty::add);
            root.add("lastRunParty", runParty);
            JsonArray beforeRun = new JsonArray();
            setBeforeRun.forEach(beforeRun::add);
            root.add("setBeforeRun", beforeRun);
            if (lastEditedClass != null) {
                root.addProperty("lastEditedClass", lastEditedClass);
            }
            JsonArray picks = new JsonArray();
            for (Pick p : classPicks.values()) {
                JsonObject o = new JsonObject();
                o.addProperty("ign", p.ign());
                if (p.uuid() != null) {
                    o.addProperty("uuid", p.uuid().toString());
                }
                o.addProperty("class", p.clazz().name());
                picks.add(o);
            }
            root.add("classPicks", picks);
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static List<SlotRef> normalizeSlots(List<SlotRef> slots) {
        List<SlotRef> out = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            out.add(i < slots.size() ? slots.get(i) : null);
        }
        return out;
    }

    public LeapOrderMode getDefaultOrder() {
        return defaultOrder;
    }

    public void setDefaultOrder(LeapOrderMode mode) {
        this.defaultOrder = mode == null ? LeapOrderMode.ODIN : mode;
    }

    /** True when {@code name} was moved by hand into a spot of this class's Leap Order (it stays there until Reset). */
    public boolean isPinned(DungeonClass playing, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (String n : getClassOrder(playing)) {
            if (n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    /** Un-pins one teammate: they go back to where the default order puts them. @return true when it changed. */
    public boolean unpin(DungeonClass playing, String name) {
        List<String> slots = getClassOrder(playing);
        boolean changed = false;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).equalsIgnoreCase(name)) {
                slots.set(i, "");
                changed = true;
            }
        }
        if (changed) {
            setClassOrder(playing, slots);
        }
        return changed;
    }

    /** A hand swap of the spots {@code a} and {@code b} in the layout shown: both teammates are pinned where they now
     *  are (what the editor shows after a swap is what stays), every other pin is kept, and an empty spot pins
     *  nobody. */
    public void swap(DungeonClass playing, String[] layout, int a, int b) {
        String nameA = layout[a];
        String nameB = layout[b];
        List<String> slots = getClassOrder(playing);
        for (int i = 0; i < slots.size(); i++) {
            String n = slots.get(i);
            if (!n.isEmpty() && (n.equalsIgnoreCase(nameA == null ? "" : nameA)
                    || n.equalsIgnoreCase(nameB == null ? "" : nameB))) {
                slots.set(i, "");
            }
        }
        slots.set(b, nameA == null ? "" : nameA);
        slots.set(a, nameB == null ? "" : nameB);
        setClassOrder(playing, slots);
        noteSetByHand(nameA);
        noteSetByHand(nameB);
    }

    /** Remembers that {@code name} was given a pick or a pin by hand, when that happens outside a run (see
     *  {@link #setBeforeRun}). */
    private void noteSetByHand(String name) {
        String clean = cleanIgn(name);
        if (clean == null || LeapRunMemory.inRun()) {
            return;
        }
        String key = clean.toLowerCase(Locale.ROOT);
        if (!setBeforeRun.contains(key) && setBeforeRun.size() < MAX_PICKS) {
            setBeforeRun.add(key);
        }
    }

    /** True when {@code name} was given a pick or a pin by hand outside a run since the last run start. */
    public boolean wasSetBeforeRun(String name) {
        String clean = cleanIgn(name);
        return clean != null && setBeforeRun.contains(clean.toLowerCase(Locale.ROOT));
    }

    /** Lower-case names set by hand outside a run since the last run start (a copy). */
    public List<String> getSetBeforeRun() {
        return new ArrayList<>(setBeforeRun);
    }

    public void clearSetBeforeRun() {
        setBeforeRun.clear();
    }

    /** @return the 4 saved spots for this class, as names ("" = auto-fill; never null) - the CURRENT name for
     *  a resolved spot (via {@link PlayerNames}), else the name it's still waiting to resolve. */
    public List<String> getClassOrder(DungeonClass playing) {
        List<SlotRef> slots = playing == null ? null : classOrders.get(playing.name());
        if (slots == null) {
            slots = normalizeSlots(List.of());
        }
        List<String> out = new ArrayList<>(4);
        for (SlotRef slot : slots) {
            out.add(displayName(slot));
        }
        return out;
    }

    private static String displayName(SlotRef slot) {
        if (slot == null) {
            return "";
        }
        String current = slot.uuid() != null ? PlayerNames.nameFor(slot.uuid()) : null;
        return current != null ? current : slot.ign();
    }

    /** Saves the 4 spots for this class, given by name - the Leap Order editor only ever hands over live party
     *  members, so each incoming name is resolved to a UUID right here: immediately if {@link PlayerNames}
     *  already knows it (true for almost every live party member, straight from the tab list), or
     *  asynchronously otherwise, without ever blocking the editor. */
    public void setClassOrder(DungeonClass playing, List<String> slots) {
        if (playing == null) {
            return;
        }
        String key = playing.name();
        List<SlotRef> refs = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            refs.add(toSlotRef(i < slots.size() ? slots.get(i) : null));
        }
        classOrders.put(key, refs);
        for (int i = 0; i < refs.size(); i++) {
            SlotRef ref = refs.get(i);
            if (ref != null && ref.uuid() == null) {
                resolveSlotAsync(this, key, i, ref.ign());
            }
        }
    }

    private static SlotRef toSlotRef(String name) {
        return name == null || name.isEmpty() ? null : new SlotRef(name, PlayerNames.uuidFor(name));
    }

    /** @return the last started run's teammates, in leap-menu order (never null; empty before the first run). */
    public List<String> getLastRunParty() {
        return new ArrayList<>(lastRunParty);
    }

    public void setLastRunParty(List<String> party) {
        lastRunParty.clear();
        if (party != null) {
            lastRunParty.addAll(party);
        }
    }

    /** Removes {@code name}'s pin from every class's layout. @return true when one was removed. */
    public boolean unpinEverywhere(String name) {
        boolean changed = false;
        for (String key : new ArrayList<>(classOrders.keySet())) {
            DungeonClass cls = DungeonClass.byName(key);
            if (cls != null && unpin(cls, name)) {
                changed = true;
            }
        }
        return changed;
    }

    /** Every name pinned in any class's layout (as shown, lower case not applied). */
    public List<String> allPinnedNames() {
        List<String> out = new ArrayList<>();
        for (String key : classOrders.keySet()) {
            DungeonClass cls = DungeonClass.byName(key);
            if (cls == null) {
                continue;
            }
            for (String n : getClassOrder(cls)) {
                if (!n.isEmpty() && out.stream().noneMatch(n::equalsIgnoreCase)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /** Pins {@code name} into {@code spot} of this class's layout (replacing whoever was pinned there, and any other pin
     *  of the same name). */
    public void pinAt(DungeonClass playing, String name, int spot) {
        if (playing == null || name == null || spot < 0 || spot > 3) {
            return;
        }
        List<String> slots = getClassOrder(playing);
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).equalsIgnoreCase(name)) {
                slots.set(i, "");
            }
        }
        slots.set(spot, name);
        setClassOrder(playing, slots);
    }

    /** Drops every pin of every class's layout (the Default Order applies again everywhere). */
    public void clearAllPins() {
        classOrders.clear();
    }

    public void clearClassOrder(DungeonClass playing) {
        if (playing != null) {
            classOrders.remove(playing.name());
        }
    }

    /** @return the last real leap-menu order (never null); empty until a leap menu has been seen. */
    public List<String> getLastLeapOrder() {
        return new ArrayList<>(lastLeapOrder);
    }

    /** @return true when this changed the stored order (the caller then saves). */
    public boolean setLastLeapOrder(List<String> namesInSlotOrder) {
        if (namesInSlotOrder == null || namesInSlotOrder.isEmpty() || lastLeapOrder.equals(namesInSlotOrder)) {
            return false;
        }
        lastLeapOrder.clear();
        lastLeapOrder.addAll(namesInSlotOrder);
        return true;
    }

    public DungeonClass getLastEditedClass() {
        return lastEditedClass == null ? null : DungeonClass.byName(lastEditedClass);
    }

    public void setLastEditedClass(DungeonClass playing) {
        this.lastEditedClass = playing == null ? null : playing.name();
    }

    // ---- class picks API (ClassOverrides is the static front for it) --------------------------------------------------

    private static String keyOf(Pick p) {
        return p.uuid() != null ? p.uuid().toString() : UNRESOLVED_PREFIX + p.ign().toLowerCase(Locale.ROOT);
    }

    /** Stores {@code p} and, with no UUID yet, asks {@link PlayerNames} to resolve one and upgrade it. */
    private static void putPick(LeapMenuConfig cfg, Pick p) {
        cfg.classPicks.put(keyOf(p), p);
        if (p.uuid() == null) {
            resolvePickAsync(cfg, p.ign());
        }
    }

    /** Upgrades the unresolved pick for {@code ign} to the UUID form once {@link PlayerNames} resolves it, if it is still
     *  exactly what it was when the lookup started. Mutates {@code cfg} directly (never {@link #getInstance()}, which
     *  may be the OLD instance while {@link #load()} builds a new one). */
    private static void resolvePickAsync(LeapMenuConfig cfg, String ign) {
        PlayerNames.resolveAsync(ign, uuid -> {
            if (uuid == null) {
                return;
            }
            String oldKey = UNRESOLVED_PREFIX + ign.toLowerCase(Locale.ROOT);
            Pick old = cfg.classPicks.get(oldKey);
            if (old != null && ign.equalsIgnoreCase(old.ign())) {
                cfg.classPicks.remove(oldKey);
                cfg.classPicks.put(uuid.toString(), new Pick(old.ign(), uuid, old.clazz()));
                cfg.save();
            }
        });
    }

    /** UUID first (when {@link PlayerNames} knows one for the name), then the typed name for a pick still unresolved. */
    private Pick findPick(String ign) {
        String clean = ign.trim();
        UUID uuid = PlayerNames.uuidFor(clean);
        Pick p = uuid != null ? classPicks.get(uuid.toString()) : null;
        return p != null ? p : classPicks.get(UNRESOLVED_PREFIX + clean.toLowerCase(Locale.ROOT));
    }

    /** The class picked for {@code ign}, or null when none. */
    public DungeonClass pickOf(String ign) {
        Pick p = ign == null ? null : findPick(ign);
        return p == null ? null : p.clazz();
    }

    public boolean hasPick(String ign) {
        return ign != null && findPick(ign) != null;
    }

    /** Sets (or replaces) the pick for {@code ign} and saves; a null class clears it. @return true when stored. */
    public boolean setPick(String ign, DungeonClass cls) {
        String clean = cleanIgn(ign);
        if (clean == null) {
            return false;
        }
        if (cls == null) {
            return clearPick(clean);
        }
        Pick existing = findPick(clean);
        if (existing == null && classPicks.size() >= MAX_PICKS) {
            return false;
        }
        UUID uuid = existing != null ? existing.uuid() : PlayerNames.uuidFor(clean);
        if (existing != null) {
            classPicks.remove(keyOf(existing));
        }
        Pick fresh = new Pick(clean, uuid, cls);
        classPicks.put(keyOf(fresh), fresh);
        noteSetByHand(clean);
        save();
        if (uuid == null) {
            resolvePickAsync(this, clean);
        }
        return true;
    }

    /** Removes the pick for {@code ign} and saves. @return true when one existed. */
    public boolean clearPick(String ign) {
        if (ign == null) {
            return false;
        }
        Pick existing = findPick(ign);
        boolean removed = existing != null && classPicks.remove(keyOf(existing)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    public void clearAllPicks() {
        if (!classPicks.isEmpty()) {
            classPicks.clear();
            save();
        }
    }

    /** Every pick, current display name -&gt; class, in insertion order (a copy). */
    public Map<String, DungeonClass> allPicks() {
        Map<String, DungeonClass> out = new LinkedHashMap<>();
        for (Pick p : classPicks.values()) {
            String current = p.uuid() != null ? PlayerNames.nameFor(p.uuid()) : null;
            out.put(current != null ? current : p.ign(), p.clazz());
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** A Minecraft IGN: 1-16 of {@code [A-Za-z0-9_]}, formatting and whitespace stripped. Null when it is not one. */
    public static String cleanIgn(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.replaceAll("\u00a7.", "").strip();
        if (t.isEmpty() || t.length() > 16 || !t.matches("[A-Za-z0-9_]+")) {
            return null;
        }
        return t;
    }

    /** A teammate's class from the dungeon tab list (read by the leap menu colours and the Live Map). */
    public DungeonClass getAssignedClass(String playerName) {
        return PartyTracker.classOf(playerName);
    }
}
