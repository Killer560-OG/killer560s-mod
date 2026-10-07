package com.killer560.hub.hud;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Persisted positions for draggable HUD overlays, plus the keybind that opens the position editor. */
public final class HudConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-hud.json");

    private static HudConfig instance;

    private final Map<String, int[]> positions = new HashMap<>();
    private final Map<String, Float> scales = new HashMap<>();
    /** GLFW key code for opening the HUD editor, or -1 if unbound. */
    private int editKeyCode = -1;
    /** HUD editor "Show Unseen" toggle: make every element whose setting is ON draggable, not just the ones
     *  that were actually on screen within {@link HudSeen#GRACE_MS} of the editor opening. A switched-off
     *  element is never listed either way (see {@link HudElement#isEnabledInSettings()}). Persisted like
     *  every other setting so the editor reopens the way it was left; the key keeps its old
     *  {@code editorShowAll} name so existing configs still load. Default off - killer560 asked for the
     *  filtered view. */
    private boolean editorShowAll = false;
    /**
     * The global HUD scale on the Home tab (killer560, 2026-10-04: "a GUI resizer slider... just like SkyHanni... from
     * 5% to 300% in 5% increments. It should resize everything my mod has uniformly"). Every element is drawn at its
     * own scale TIMES this, and the per-element scale is stored on its own, so an element set to 2.0x with this at 0.5
     * draws at its original size and goes back to 2.0x when this returns to 100%. Lives in this file so a profile
     * carries it with the positions it multiplies.
     */
    public static final float MIN_GLOBAL_SCALE = 0.05f;
    public static final float MAX_GLOBAL_SCALE = 3.0f;
    private float globalScale = 1.0f;
    /** Auto Scale (monitor), ON by default for everyone (killer560, 2026-10-05). Multiplies the HUD (and the mod's
     *  screens) by {@link AutoScale#current()} so they keep the size they have on his 2560x1440 / GUI 3 monitor as a
     *  fraction of any window. The HUD Scale slider still multiplies on top. Lives here, beside {@link #globalScale},
     *  so profiles carry it with the positions it affects. */
    private boolean autoScale = true;
    /**
     * HUD editor snapping (2026-10-07, killer560: "Those should kind of do a snapping style where they snap to align with
     * things. You can choose what all they will align with."). The master switch and what a dragged or resized box
     * snaps to; all on by default. Hold Alt while dragging to place freely. See {@link HudSnap}.
     */
    private boolean editorSnap = true;
    private boolean snapElementEdges = true;
    private boolean snapElementCentres = true;
    private boolean snapScreenEdges = true;
    private boolean snapScreenCentre = true;
    private boolean snapEqualSpacing = true;

    /** Bumped by every load and every scale change, so a cached layout that depends on scales (the Health and Mana
     *  Bars Predefined layout) knows to recompute. */
    private static int version;

    private HudConfig() {
    }

    public static int version() {
        return version;
    }

    public static HudConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        version++;
        HudConfig cfg = new HudConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cfg.editKeyCode = com.killer560.hub.util.KeyUtil.sanitize(ConfigJson.getInt(obj, "editKeyCode", -1));
                cfg.editorShowAll = ConfigJson.getBool(obj, "editorShowAll", false);
                cfg.globalScale = clampGlobalScale(ConfigJson.getFloat(obj, "globalScale", 1.0f));
                cfg.autoScale = ConfigJson.getBool(obj, "autoScale", true);
                cfg.editorSnap = ConfigJson.getBool(obj, "editorSnap", true);
                cfg.snapElementEdges = ConfigJson.getBool(obj, "snapElementEdges", true);
                cfg.snapElementCentres = ConfigJson.getBool(obj, "snapElementCentres", true);
                cfg.snapScreenEdges = ConfigJson.getBool(obj, "snapScreenEdges", true);
                cfg.snapScreenCentre = ConfigJson.getBool(obj, "snapScreenCentre", true);
                cfg.snapEqualSpacing = ConfigJson.getBool(obj, "snapEqualSpacing", true);
                JsonObject positions = ConfigJson.getObject(obj, "positions");
                if (positions != null) {
                    for (String id : positions.keySet()) {
                        // One bad element is skipped on its own instead of dropping every HUD position.
                        JsonObject pos = ConfigJson.getObject(positions, id);
                        if (pos == null) {
                            continue;
                        }
                        if (pos.has("x") && pos.has("y")) {
                            int x = ConfigJson.getInt(pos, "x", Integer.MIN_VALUE);
                            int y = ConfigJson.getInt(pos, "y", Integer.MIN_VALUE);
                            if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE) {
                                cfg.positions.put(id, new int[]{x, y});
                            }
                        }
                        float scale = ConfigJson.getFloat(pos, "scale", Float.NaN);
                        if (!Float.isNaN(scale) && scale > 0f) {
                            cfg.scales.put(id, scale);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("editKeyCode", editKeyCode);
            obj.addProperty("editorShowAll", editorShowAll);
            obj.addProperty("globalScale", globalScale);
            obj.addProperty("autoScale", autoScale);
            obj.addProperty("editorSnap", editorSnap);
            obj.addProperty("snapElementEdges", snapElementEdges);
            obj.addProperty("snapElementCentres", snapElementCentres);
            obj.addProperty("snapScreenEdges", snapScreenEdges);
            obj.addProperty("snapScreenCentre", snapScreenCentre);
            obj.addProperty("snapEqualSpacing", snapEqualSpacing);
            JsonObject positions = new JsonObject();
            for (Map.Entry<String, int[]> entry : this.positions.entrySet()) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", entry.getValue()[0]);
                pos.addProperty("y", entry.getValue()[1]);
                pos.addProperty("scale", scales.getOrDefault(entry.getKey(), 1.0f));
                positions.add(entry.getKey(), pos);
            }
            // Bug fix (2026-09-15 persistence audit): an element resized with the scroll wheel in the HUD
            // editor but never dragged has a scale and no position - it used to be dropped here, so the
            // resize reset on restart. Write it scale-only; load() leaves its position on the default.
            for (Map.Entry<String, Float> entry : this.scales.entrySet()) {
                if (!this.positions.containsKey(entry.getKey())) {
                    JsonObject pos = new JsonObject();
                    pos.addProperty("scale", entry.getValue());
                    positions.add(entry.getKey(), pos);
                }
            }
            obj.add("positions", positions);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public int[] getPosition(String id, int defaultX, int defaultY) {
        // getOrDefault EVALUATES its default eagerly, so this allocated a fresh int[2] on every call even
        // when the element had a saved position - about 110 allocations a frame across the HUD, every frame,
        // almost all of them thrown away immediately. The lookup answers first now.
        int[] saved = positions.get(id);
        return saved != null ? saved : new int[]{defaultX, defaultY};
    }

    /** True once the player has moved this element themselves (a scale-only entry does not count). Used by
     *  {@link HudElementRegistry#resolvePosition} to clamp only defaults, never a deliberate placement. */
    public boolean hasPosition(String id) {
        return positions.containsKey(id);
    }

    public void setPosition(String id, int x, int y) {
        positions.put(id, new int[]{x, y});
    }

    public float getScale(String id, float defaultScale) {
        // Same trap as getPosition above, fourteen lines up: getOrDefault takes an Object, so defaultScale is
        // boxed BEFORE the call and thrown away again whenever the id has a saved scale. Float.valueOf has no
        // value cache the way Integer.valueOf does, so that box is a real allocation every time - and this is
        // read per element per frame by HudInGameRenderer plus a dozen feature render callbacks.
        Float saved = scales.get(id);
        return saved != null ? saved : defaultScale;
    }

    public void setScale(String id, float scale) {
        scales.put(id, scale);
        version++;
    }

    public int getEditKeyCode() {
        return editKeyCode;
    }

    public void setEditKeyCode(int editKeyCode) {
        this.editKeyCode = editKeyCode;
    }

    public float getGlobalScale() {
        return globalScale;
    }

    public void setGlobalScale(float globalScale) {
        this.globalScale = clampGlobalScale(globalScale);
        version++;
    }

    /** The multiplier every HUD element is drawn at on top of its own scale: the HUD Scale slider times the Auto
     *  Scale factor (1.0 when Auto Scale is off). {@link HudElementRegistry#resolveScale} and the HUD editor both
     *  read this, so the editor shows each element at the size it draws in game. */
    public float getEffectiveGlobalScale() {
        return globalScale * AutoScale.current();
    }

    public boolean isAutoScale() {
        return autoScale;
    }

    public void setAutoScale(boolean autoScale) {
        this.autoScale = autoScale;
        version++;
    }

    /** Snaps to the slider's 5% steps and keeps it inside 5%..300%; NaN (a hand-edited file) means 100%. */
    public static float clampGlobalScale(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 1.0f;
        }
        float stepped = Math.round(value * 20.0f) / 20.0f;
        return Math.max(MIN_GLOBAL_SCALE, Math.min(MAX_GLOBAL_SCALE, stepped));
    }

    public boolean isEditorShowAll() {
        return editorShowAll;
    }

    public void setEditorShowAll(boolean editorShowAll) {
        this.editorShowAll = editorShowAll;
    }

    public boolean isEditorSnap() {
        return editorSnap;
    }

    public void setEditorSnap(boolean v) {
        this.editorSnap = v;
    }

    public boolean isSnapElementEdges() {
        return snapElementEdges;
    }

    public void setSnapElementEdges(boolean v) {
        this.snapElementEdges = v;
    }

    public boolean isSnapElementCentres() {
        return snapElementCentres;
    }

    public void setSnapElementCentres(boolean v) {
        this.snapElementCentres = v;
    }

    public boolean isSnapScreenEdges() {
        return snapScreenEdges;
    }

    public void setSnapScreenEdges(boolean v) {
        this.snapScreenEdges = v;
    }

    public boolean isSnapScreenCentre() {
        return snapScreenCentre;
    }

    public void setSnapScreenCentre(boolean v) {
        this.snapScreenCentre = v;
    }

    public boolean isSnapEqualSpacing() {
        return snapEqualSpacing;
    }

    public void setSnapEqualSpacing(boolean v) {
        this.snapEqualSpacing = v;
    }
}
