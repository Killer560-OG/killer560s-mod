package com.killer560.hub.petwheel;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.KeyUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pet Wheel settings (killer560's item 8.2): a radial keybind menu of a chosen subset/order of his real pets,
 * off by default and living in the New tab until confirmed working. Not cheat-gated - killer560, 2026-09-21:
 * "it's just a reskin of the /pets menu, not real automation, so it ships identically in both builds."
 * <p>
 * Pages (killer560, 2026-10-18: "custom presets of different pages and also the ability to toggle between different pages"):
 * the wheel is a list of named PAGES, each its own ordered set of pets - a preset. One page is current (it is what the
 * wheel shows, what Edit Pets edits and what is saved as the page to open on); {@link #stepPage} / {@link #setPageIndex}
 * change it, from the tab and from the wheel itself (scroll, Left/Right). A file from before pages loads as one page.
 * <p>
 * Two lists are kept, both persisted:
 * <ul>
 *   <li>{@code wheelPets} - the CURRENT page's ordered subset actually shown on the wheel, picked/rearranged in
 *   {@link PetWheelScreen}'s edit mode (opened by {@code gui.tab.PetWheelTab}'s "Edit Pets" button).</li>
 *   <li>{@code knownPets} - every pet {@link PetsMenuScanner} has ever read off a real {@code /pets} screen,
 *   insertion-ordered, so {@link PetsMenuScanner}'s passive scan (and {@link PetWheelEditor}'s right-click
 *   pick) both have somewhere to record what a pet's icon/name/level/tier looked like last. Both lists are
 *   keyed by the pet's own item uuid (see {@link PetEntry}), never by name+rarity.</li>
 * </ul>
 */
public final class PetWheelConfig {

    public enum InteractionMode {
        /** Hold the bind, drag over a slice, release to summon it - killer560's "hold-the-key-and-release". */
        HOLD_RELEASE,
        /** Press the bind to open the wheel, then click a slice - killer560's "press-the-key-then-click". */
        PRESS_CLICK
    }

    public static final int MIN_SCALE_PCT = 60;
    /** Raised from 150 (killer560, 2026-09-27: "the scale should also be able to go a decent bit higher"). */
    public static final int MAX_SCALE_PCT = 400;
    public static final int DEFAULT_SCALE_PCT = 100;
    /** Icon Size: a second, independent multiplier on top of {@link #scalePercent} that only grows the pet
     *  picture itself (killer560: "make a way to have the pictures alot bigger as well") - the tile box and
     *  ring radius both grow to fit it (see {@code PetWheelScreen#layout}), so a huge icon setting can't make
     *  neighbouring slices overlap. */
    public static final int MIN_ICON_SCALE_PCT = 100;
    public static final int MAX_ICON_SCALE_PCT = 400;
    public static final int DEFAULT_ICON_SCALE_PCT = 100;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-petwheel.json");

    private static PetWheelConfig instance;

    private boolean enabled = false;
    private int scalePercent = DEFAULT_SCALE_PCT;
    private int iconScalePercent = DEFAULT_ICON_SCALE_PCT;
    private InteractionMode mode = InteractionMode.HOLD_RELEASE;
    private int keyCode = KeyUtil.NONE;
    /** killer560: "add an option to hide their level and an option to hide the pets name" - two separate
     *  toggles, independent of each other, affecting only the wheel's own slice label (never the Edit Pets
     *  lists, which still show the full "[Lvl N] Name" so picking the right pet stays unambiguous there). */
    private boolean hideLevel = false;
    private boolean hideName = false;
    /** killer560, 2026-10-04: "add an option to hide the pets menu while it is working" - the summon's /pets
     *  menu is opened headless (never shown) and closed again; see {@link PetSummoner}. */
    private boolean hideMenuWhileSummoning = false;
    /** killer560, 2026-10-18: custom keys (or mouse buttons) for the wheel's page changes, on top of the arrow keys
     *  and scroll that always work. {@link KeyUtil#NONE} = unbound. */
    private int prevPageKeyCode = KeyUtil.NONE;
    private int nextPageKeyCode = KeyUtil.NONE;

    /** One wheel page: a name and its ordered pets. */
    private static final class WheelPage {
        String name;
        final List<PetEntry> pets = new ArrayList<>();

        WheelPage(String name) {
            this.name = name;
        }
    }

    public static final int MAX_PAGES = 12;
    public static final int MAX_PAGE_NAME = 24;

    private final List<WheelPage> pages = new ArrayList<>(List.of(new WheelPage("Page 1")));
    private int pageIndex = 0;
    /** Ordered subset shown on the wheel, keyed by uuid so identity survives a rename/relevel. This IS the current
     *  page's list (reassigned whenever the page changes), so every edit below lands on the current page. */
    private List<PetEntry> wheelPets = pages.get(0).pets;
    /** Every pet ever scanned off a real /pets screen, insertion-ordered, keyed by uuid. */
    private final Map<String, PetEntry> knownPets = new LinkedHashMap<>();

    private PetWheelConfig() {
    }

    public static PetWheelConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    /** A boolean that tolerates a key of the wrong type, like {@code getInt} beside it. */
    private static boolean getBool(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A string that tolerates a key of the wrong type. */
    private static String getString(JsonObject o, String key, String fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Loads, then tells FeatureBits a gated setting may have changed (profile apply reloads every config). */
    public static void load() {
        load0();
        com.killer560.hub.util.FeatureBits.changed();
    }

    private static void load0() {
        PetWheelConfig cfg = new PetWheelConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                // Read through the same helpers the rest of the mod uses, so one key of the wrong type
                // cannot abandon the rest. This block used raw getAsBoolean/getAsString and the catch below
                // still published the half-filled object, so a single hand-edited key left everything after it
                // - including wheelPets and knownPets, which load last - silently at defaults, and the next
                // save wrote that loss to disk.
                cfg.enabled = getBool(root, "enabled", false);
                cfg.scalePercent = clampScale(getInt(root, "scalePercent", DEFAULT_SCALE_PCT));
                cfg.iconScalePercent = clampIconScale(getInt(root, "iconScalePercent", DEFAULT_ICON_SCALE_PCT));
                cfg.mode = parseMode(getString(root, "mode", null));
                cfg.keyCode = KeyUtil.sanitizeBind(getInt(root, "keyCode", KeyUtil.NONE));
                cfg.hideLevel = getBool(root, "hideLevel", false);
                cfg.hideName = getBool(root, "hideName", false);
                cfg.hideMenuWhileSummoning = getBool(root, "hideMenuWhileSummoning", false);
                cfg.prevPageKeyCode = KeyUtil.sanitizeBind(getInt(root, "prevPageKeyCode", KeyUtil.NONE));
                cfg.nextPageKeyCode = KeyUtil.sanitizeBind(getInt(root, "nextPageKeyCode", KeyUtil.NONE));
                readPages(root, cfg);
                List<PetEntry> known = new ArrayList<>();
                readPetList(root, "knownPets", known);
                for (PetEntry p : known) {
                    cfg.knownPets.put(p.uuid(), p);
                }
            } catch (Exception ignored) {
                // Corrupt file: defaults WHOLESALE, not whatever was filled in before the throw. Publishing a
                // half-populated object is worse than defaults, because the next save persists the loss.
                cfg = new PetWheelConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("enabled", enabled);
            root.addProperty("scalePercent", scalePercent);
            root.addProperty("iconScalePercent", iconScalePercent);
            root.addProperty("mode", mode.name());
            root.addProperty("keyCode", keyCode);
            root.addProperty("hideLevel", hideLevel);
            root.addProperty("hideName", hideName);
            root.addProperty("hideMenuWhileSummoning", hideMenuWhileSummoning);
            root.addProperty("prevPageKeyCode", prevPageKeyCode);
            root.addProperty("nextPageKeyCode", nextPageKeyCode);
            root.add("wheelPets", petListToJson(wheelPets)); // the current page, for a jar from before pages
            JsonArray pageArr = new JsonArray();
            for (WheelPage p : pages) {
                JsonObject po = new JsonObject();
                po.addProperty("name", p.name);
                po.add("pets", petListToJson(p.pets));
                pageArr.add(po);
            }
            root.add("pages", pageArr);
            root.addProperty("pageIndex", pageIndex);
            root.add("knownPets", petListToJson(new ArrayList<>(knownPets.values())));
            Files.writeString(CONFIG_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ simple settings

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled; com.killer560.hub.util.FeatureBits.changed();
    }

    /** Slices on the wheel at once: every pet on it (the Slices setting was removed 2026-10-11 - the wheel lays
     *  out however many pets you add). */
    public int getSliceCount() {
        return Math.max(1, wheelPets.size());
    }

    public int getScalePercent() {
        return scalePercent;
    }

    public void setScalePercent(int scalePercent) {
        this.scalePercent = clampScale(scalePercent);
    }

    public int getIconScalePercent() {
        return iconScalePercent;
    }

    public void setIconScalePercent(int iconScalePercent) {
        this.iconScalePercent = clampIconScale(iconScalePercent);
    }

    public boolean isHideLevel() {
        return hideLevel;
    }

    public void setHideLevel(boolean hideLevel) {
        this.hideLevel = hideLevel;
    }

    public boolean isHideName() {
        return hideName;
    }

    public void setHideName(boolean hideName) {
        this.hideName = hideName;
    }

    public boolean isHideMenuWhileSummoning() {
        return hideMenuWhileSummoning;
    }

    public void setHideMenuWhileSummoning(boolean hideMenuWhileSummoning) {
        this.hideMenuWhileSummoning = hideMenuWhileSummoning;
    }

    public InteractionMode getMode() {
        return mode;
    }

    public void setMode(InteractionMode mode) {
        this.mode = mode == null ? InteractionMode.HOLD_RELEASE : mode;
    }

    public void cycleMode() {
        cycleMode(1);
    }

    public void cycleMode(int dir) {
        mode = mode == InteractionMode.HOLD_RELEASE ? InteractionMode.PRESS_CLICK : InteractionMode.HOLD_RELEASE;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = KeyUtil.sanitizeBind(keyCode);
    }

    public int getPrevPageKeyCode() {
        return prevPageKeyCode;
    }

    public void setPrevPageKeyCode(int code) {
        this.prevPageKeyCode = KeyUtil.sanitizeBind(code);
    }

    public int getNextPageKeyCode() {
        return nextPageKeyCode;
    }

    public void setNextPageKeyCode(int code) {
        this.nextPageKeyCode = KeyUtil.sanitizeBind(code);
    }

    // ------------------------------------------------------------------ pages

    private static void readPages(JsonObject root, PetWheelConfig cfg) {
        cfg.pages.clear();
        if (root.has("pages") && root.get("pages").isJsonArray()) {
            for (var el : root.getAsJsonArray("pages")) {
                if (cfg.pages.size() >= MAX_PAGES) {
                    break;
                }
                try {
                    JsonObject po = el.getAsJsonObject();
                    WheelPage page = new WheelPage(cleanName(getString(po, "name", null), cfg.pages.size()));
                    readPetList(po, "pets", page.pets);
                    cfg.pages.add(page);
                } catch (RuntimeException ignored) {
                    // One bad page never blocks the rest.
                }
            }
        }
        if (cfg.pages.isEmpty()) {
            // A file from before pages: its one list is page 1.
            WheelPage only = new WheelPage("Page 1");
            readPetList(root, "wheelPets", only.pets);
            cfg.pages.add(only);
        }
        cfg.pageIndex = Math.max(0, Math.min(cfg.pages.size() - 1, getInt(root, "pageIndex", 0)));
        cfg.wheelPets = cfg.pages.get(cfg.pageIndex).pets;
    }

    private static String cleanName(String raw, int index) {
        String n = raw == null ? "" : raw.strip();
        if (n.length() > MAX_PAGE_NAME) {
            n = n.substring(0, MAX_PAGE_NAME).strip();
        }
        return n.isEmpty() ? "Page " + (index + 1) : n;
    }

    public int getPageCount() {
        return pages.size();
    }

    /** The current page, 0-based. */
    public int getPageIndex() {
        return pageIndex;
    }

    public String getPageName(int index) {
        return index >= 0 && index < pages.size() ? pages.get(index).name : "";
    }

    public String getCurrentPageName() {
        return pages.get(pageIndex).name;
    }

    /** Renames a page; a blank name goes back to "Page N". */
    public void setPageName(int index, String name) {
        if (index >= 0 && index < pages.size()) {
            pages.get(index).name = cleanName(name, index);
        }
    }

    /** Makes {@code index} (wrapping around the page count) the current page. */
    public void setPageIndex(int index) {
        pageIndex = Math.floorMod(index, pages.size());
        wheelPets = pages.get(pageIndex).pets;
    }

    /** Moves to the next ({@code dir} 1) or previous (-1) page, wrapping. */
    public void stepPage(int dir) {
        setPageIndex(pageIndex + dir);
    }

    /** Adds an empty page after the last one and makes it current. @return false at {@link #MAX_PAGES}. */
    public boolean addPage() {
        if (pages.size() >= MAX_PAGES) {
            return false;
        }
        pages.add(new WheelPage("Page " + (pages.size() + 1)));
        setPageIndex(pages.size() - 1);
        return true;
    }

    /** Deletes the current page (never the last one); the page before it becomes current. @return whether it did. */
    public boolean removeCurrentPage() {
        if (pages.size() <= 1) {
            return false;
        }
        pages.remove(pageIndex);
        setPageIndex(Math.max(0, pageIndex - 1));
        return true;
    }

    // ------------------------------------------------------------------ pet lists

    /** @return an unmodifiable, ordered snapshot of the wheel's pets. */
    public List<PetEntry> getWheelPets() {
        return List.copyOf(wheelPets);
    }

    /** @return an unmodifiable, insertion-ordered snapshot of every pet ever seen in a real /pets screen. */
    public List<PetEntry> getKnownPets() {
        return List.copyOf(knownPets.values());
    }

    public boolean isOnWheel(String uuid) {
        return wheelPets.stream().anyMatch(p -> p.uuid().equals(uuid));
    }

    /** Appends a known pet to the end of the wheel (no-op if already on it or unknown). */
    public void addToWheel(String uuid) {
        if (isOnWheel(uuid)) {
            return;
        }
        PetEntry known = knownPets.get(uuid);
        if (known != null) {
            wheelPets.add(known);
        }
    }

    public void removeFromWheel(String uuid) {
        wheelPets.removeIf(p -> p.uuid().equals(uuid));
    }

    /** @return true if the swap happened (both indices were valid neighbours-or-not within range). */
    public boolean moveInWheel(int index, int delta) {
        int target = index + delta;
        if (index < 0 || index >= wheelPets.size() || target < 0 || target >= wheelPets.size()) {
            return false;
        }
        PetEntry moved = wheelPets.remove(index);
        wheelPets.add(target, moved);
        return true;
    }

    /** killer560's reworked wheel-edit flow: left-click one slice then another (or drag one onto another)
     *  swaps their pets in place. Both indices must already hold a real pet - the wheel's one trailing empty
     *  "appendable" slot (see {@link PetWheelScreen}) is never a valid swap partner. @return true if it swapped. */
    public boolean swapInWheel(int indexA, int indexB) {
        if (indexA == indexB || indexA < 0 || indexB < 0 || indexA >= wheelPets.size() || indexB >= wheelPets.size()) {
            return false;
        }
        PetEntry a = wheelPets.get(indexA);
        wheelPets.set(indexA, wheelPets.get(indexB));
        wheelPets.set(indexB, a);
        return true;
    }

    /**
     * killer560's reworked wheel-edit flow: right-clicking a slot opens the real {@code /pets} menu and
     * whatever pet is clicked there replaces the pet in that slot. {@code index == wheelPets.size()} is the
     * wheel's one trailing empty slot (see {@link PetWheelScreen}'s edit-mode layout), which this appends to
     * rather than replacing; anything past that is out of range and ignored rather than leaving a gap.
     */
    public void replaceOrAppendWheelSlot(int index, PetEntry entry) {
        if (entry == null || index < 0 || index > wheelPets.size()) {
            return;
        }
        if (index == wheelPets.size()) {
            wheelPets.add(entry);
        } else {
            wheelPets.set(index, entry);
        }
    }

    /**
     * Merges a fresh scan of one pet into {@code knownPets} (see {@link PetEntry#mergedWith}), and refreshes
     * the matching wheel entry too so the wheel's cached name/level/tier/head stay current without needing
     * the wheel to be reopened after a level-up.
     *
     * @return true if anything actually changed (caller only needs to {@link #save()} then).
     */
    public boolean recordSeenPet(PetEntry fresh) {
        PetEntry existing = knownPets.get(fresh.uuid());
        PetEntry merged = existing == null ? fresh : existing.mergedWith(fresh);
        boolean changed = existing == null || !existing.equals(merged);
        knownPets.put(fresh.uuid(), merged);
        if (changed) {
            for (WheelPage page : pages) {
                for (int i = 0; i < page.pets.size(); i++) {
                    if (page.pets.get(i).uuid().equals(fresh.uuid())) {
                        page.pets.set(i, merged);
                        break;
                    }
                }
            }
        }
        return changed;
    }

    // ------------------------------------------------------------------ helpers

    private static int clampScale(int v) {
        return Math.max(MIN_SCALE_PCT, Math.min(MAX_SCALE_PCT, v));
    }

    private static int clampIconScale(int v) {
        return Math.max(MIN_ICON_SCALE_PCT, Math.min(MAX_ICON_SCALE_PCT, v));
    }

    private static InteractionMode parseMode(String raw) {
        if (raw == null) {
            return InteractionMode.HOLD_RELEASE;
        }
        try {
            return InteractionMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return InteractionMode.HOLD_RELEASE;
        }
    }

    private static int getInt(JsonObject obj, String key, int fallback) {
        try {
            return obj.has(key) ? obj.get(key).getAsInt() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void readPetList(JsonObject root, String key, List<PetEntry> out) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return;
        }
        for (var el : root.getAsJsonArray(key)) {
            try {
                JsonObject o = el.getAsJsonObject();
                String uuid = o.has("uuid") ? o.get("uuid").getAsString() : null;
                if (uuid == null || uuid.isBlank()) {
                    continue;
                }
                String name = o.has("name") ? o.get("name").getAsString() : null;
                String tier = o.has("tier") && !o.get("tier").isJsonNull() ? o.get("tier").getAsString() : null;
                int level = getInt(o, "level", -1);
                String skin = o.has("skinValue") && !o.get("skinValue").isJsonNull() ? o.get("skinValue").getAsString() : null;
                out.add(new PetEntry(uuid, name, tier, level, skin));
            } catch (RuntimeException ignored) {
                // One bad entry (hand-edited file) never blocks the rest of the list.
            }
        }
    }

    private static JsonArray petListToJson(List<PetEntry> pets) {
        JsonArray arr = new JsonArray();
        for (PetEntry p : pets) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", p.uuid());
            o.addProperty("name", p.name());
            if (p.tier() != null) {
                o.addProperty("tier", p.tier());
            }
            o.addProperty("level", p.level());
            if (p.skinValue() != null) {
                o.addProperty("skinValue", p.skinValue());
            }
            arr.add(o);
        }
        return arr;
    }
}
