package com.killer560.hub.slotbinds;

import com.killer560.hub.util.ModPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persisted Slot Binds settings - see {@link SlotBindsFeature}'s class doc for the real
 *  swap mechanic this is built on. Ships disabled by default, same as every other new feature in this
 *  mod. */
public final class SlotBindsConfig {

    /** How bound-slot borders/link lines are drawn in the real inventory screen (killer560, 2026-09-21:
     *  "do not have the hud popup, instead show a border around each spot and a line for where it goes.
     *  Make a toggle to only show this on hover or always."). */
    public enum OverlayMode {
        ALWAYS("Always"),
        ON_HOVER("On Hover");

        public final String label;

        OverlayMode(String label) {
            this.label = label;
        }

        public OverlayMode next() { return values()[(ordinal() + 1) % values().length]; }

        public OverlayMode previous() { return values()[(values().length + ordinal() - 1) % values().length]; }
    }

    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-slotbinds.json");

    private static SlotBindsConfig instance;

    private boolean enabled = false;
    private int bindKey = -1;
    /** Every bind as {slotA, slotB} (container slots 5-44, one end in the hotbar 36-44), in the order made. A slot
     *  may be in several binds (slotbinds-multi-1128, killer560 2026-11-28: "slot binds should allow me to set
     *  multiple slots to one slot"), a pair of slots in only one. */
    private final List<int[]> pairs = new ArrayList<>();
    private final List<int[]> pairsView = Collections.unmodifiableList(pairs);
    /** Container slot -&gt; the slot it last swapped with through a bind (0 = none yet). Not saved. */
    private final int[] lastPartner = new int[45];
    private OverlayMode overlayMode = OverlayMode.ALWAYS;
    // Amber, matching this mod's own GUI theme color (SectionHeaders.LEGIT_COLOR) rather than inventing
    // a new default palette for a marker most people will never need to change.
    private int overlayColor = 0xFFFFAA00;

    private SlotBindsConfig() {
    }

    public static SlotBindsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        SlotBindsConfig cfg = new SlotBindsConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.enabled = ConfigJson.getBool(obj, "enabled", false);
            cfg.bindKey = ConfigJson.getInt(obj, "bindKey", -1);
            cfg.overlayMode = ConfigJson.getEnum(obj, "overlayMode", OverlayMode.class, OverlayMode.ALWAYS);
            cfg.overlayColor = ConfigJson.getInt(obj, "overlayColor", 0xFFFFAA00);
            JsonArray saved = ConfigJson.getArray(obj, "pairs");
            JsonObject old = ConfigJson.getObject(obj, "binds");
            // Per-element: one malformed entry is skipped instead of dropping every bind.
            if (saved != null) {
                for (JsonElement e : saved) {
                    try {
                        cfg.addBind(e.getAsJsonArray().get(0).getAsInt(), e.getAsJsonArray().get(1).getAsInt());
                    } catch (Exception ignored) {
                    }
                }
            } else if (old != null) {
                // Before slotbinds-multi-1128: "binds" held both directions of every pair (a: b and b: a).
                for (String key : old.keySet()) {
                    try {
                        cfg.addBind(Integer.parseInt(key.trim()), old.get(key).getAsInt());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        instance = cfg;
    }

    public void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.addProperty("bindKey", bindKey);
        obj.addProperty("overlayMode", overlayMode.name());
        obj.addProperty("overlayColor", overlayColor);
        JsonArray saved = new JsonArray();
        for (int[] pair : pairs) {
            JsonArray one = new JsonArray();
            one.add(pair[0]);
            one.add(pair[1]);
            saved.add(one);
        }
        obj.add("pairs", saved);
        ConfigJson.write(CONFIG_PATH, obj);
    }

    public boolean isEnabled() { return enabled && com.killer560.hub.util.SkyblockGate.allows(); }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getBindKey() { return bindKey; }

    public void setBindKey(int bindKey) { this.bindKey = bindKey; }

    /** Every bind as {slotA, slotB}, read-only. */
    public List<int[]> pairs() { return pairsView; }

    public OverlayMode getOverlayMode() { return overlayMode; }

    public void setOverlayMode(OverlayMode mode) { this.overlayMode = mode == null ? OverlayMode.ALWAYS : mode; }

    public int getOverlayColor() { return overlayColor; }

    public void setOverlayColor(int argb) { this.overlayColor = argb; }

    public static boolean isHotbar(int slot) { return slot >= 36 && slot < 45; }

    /** A pair a bind can hold: two different inventory slots (5-44), one of them in the hotbar - the vanilla SWAP
     *  click the bind sends takes a hotbar index. */
    public static boolean legal(int slotA, int slotB) {
        return slotA != slotB && slotA >= 5 && slotA < 45 && slotB >= 5 && slotB < 45
                && (isHotbar(slotA) || isHotbar(slotB));
    }

    public boolean hasBind(int slotA, int slotB) {
        for (int[] pair : pairs) {
            if ((pair[0] == slotA && pair[1] == slotB) || (pair[0] == slotB && pair[1] == slotA)) return true;
        }
        return false;
    }

    public boolean isBound(int slot) {
        for (int[] pair : pairs) {
            if (pair[0] == slot || pair[1] == slot) return true;
        }
        return false;
    }

    /** Links the two slots, keeping every other bind either of them has. False when the pair is not
     *  {@link #legal} or is bound already. */
    public boolean addBind(int slotA, int slotB) {
        if (!legal(slotA, slotB) || hasBind(slotA, slotB)) return false;
        pairs.add(new int[]{slotA, slotB});
        return true;
    }

    /** Removes the one bind between the two slots. */
    public boolean removeBind(int slotA, int slotB) {
        return pairs.removeIf(pair -> (pair[0] == slotA && pair[1] == slotB) || (pair[0] == slotB && pair[1] == slotA));
    }

    /** Removes every bind this slot is in. */
    public boolean removeBind(int slot) {
        return pairs.removeIf(pair -> pair[0] == slot || pair[1] == slot);
    }

    public void clearBinds() { pairs.clear(); }

    /** The slot a shift-click on {@code slot} swaps with, or -1 when it has no bind. A slot with several binds goes
     *  back to the one it swapped with last (so a second click undoes the first), else to its first bind. */
    public int partnerOf(int slot) {
        int first = -1;
        int last = slot >= 0 && slot < lastPartner.length ? lastPartner[slot] : 0;
        for (int[] pair : pairs) {
            if (pair[0] != slot && pair[1] != slot) continue;
            int other = pair[0] == slot ? pair[1] : pair[0];
            if (other == last) return other;
            if (first == -1) first = other;
        }
        return first;
    }

    /** Remembers a bind swap for {@link #partnerOf}. */
    public void noteSwap(int slotA, int slotB) {
        lastPartner[slotA] = slotB;
        lastPartner[slotB] = slotA;
    }

    /**
     * One setup click on {@code clicked} with {@code selected} (-1 = none) picked before it; returns the slot
     * selected afterwards. The first click selects. A click on another slot links it with the selected one, or
     * unlinks it when they are linked already, saves, and leaves the hotbar end selected so more slots can be
     * linked to it. A click on the selected slot lets go of it; a slot that cannot pair with the selected one
     * (neither is in the hotbar) becomes the selected one instead. The inventory screen and the Inventory Sorter's
     * Slot Binds editor both go through here.
     */
    public int setupClick(int selected, int clicked) {
        if (clicked < 5 || clicked >= 45) return selected;
        if (selected == -1) return clicked;
        if (clicked == selected) return -1;
        if (!legal(selected, clicked)) return clicked;
        if (!removeBind(selected, clicked)) addBind(selected, clicked);
        save();
        return isHotbar(clicked) && !isHotbar(selected) ? clicked : selected;
    }
}
