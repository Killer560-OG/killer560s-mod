package com.killer560.hub.hud;

import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One pair of settings for the dungeon timer HUDs (gaps-sounds-hud-1124): Text Shadow and Background, kept per HUD id in
 * one file. A HUD that was never touched draws exactly as before (each draw site passes its old shadow value as the
 * default, and a background is off). The background is drawn by {@link HudElementRegistry#drawAt} behind the element's
 * own {@code width()} by {@code height()} box, so no HUD needs its own rectangle code.
 */
public final class HudTextStyle {

    private static final Path PATH = ModPaths.config("killer560smod-hudtextstyle.json");
    /** Translucent black behind the text. */
    private static final int BACKGROUND = 0x80000000;
    private static final int PAD = 2;

    private static final Map<String, Boolean> SHADOW = new HashMap<>();
    private static final Set<String> BACKGROUNDS = new HashSet<>();
    private static boolean loaded;

    private HudTextStyle() {
    }

    private static synchronized void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** Puts the defaults back and reads the file (a profile apply calls this). */
    public static synchronized void load() {
        SHADOW.clear();
        BACKGROUNDS.clear();
        JsonObject o = ConfigJson.read(PATH);
        if (o != null) {
            JsonObject sh = o.has("shadow") && o.get("shadow").isJsonObject() ? o.getAsJsonObject("shadow") : new JsonObject();
            sh.entrySet().forEach(e -> SHADOW.put(e.getKey(), e.getValue().getAsBoolean()));
            JsonObject bg = o.has("background") && o.get("background").isJsonObject() ? o.getAsJsonObject("background") : new JsonObject();
            bg.entrySet().forEach(e -> {
                if (e.getValue().getAsBoolean()) {
                    BACKGROUNDS.add(e.getKey());
                }
            });
        }
        loaded = true;
    }

    private static synchronized void save() {
        JsonObject o = new JsonObject();
        JsonObject sh = new JsonObject();
        SHADOW.forEach(sh::addProperty);
        JsonObject bg = new JsonObject();
        BACKGROUNDS.forEach(id -> bg.addProperty(id, true));
        o.add("shadow", sh);
        o.add("background", bg);
        ConfigJson.write(PATH, o);
    }

    /** Whether HUD {@code id} draws its text with a shadow; {@code def} is what the HUD drew before this setting existed. */
    public static synchronized boolean shadow(String id, boolean def) {
        ensureLoaded();
        return SHADOW.getOrDefault(id, def);
    }

    public static synchronized void setShadow(String id, boolean on) {
        ensureLoaded();
        SHADOW.put(id, on);
        save();
    }

    public static synchronized boolean background(String id) {
        ensureLoaded();
        return BACKGROUNDS.contains(id);
    }

    public static synchronized void setBackground(String id, boolean on) {
        ensureLoaded();
        if (on) {
            BACKGROUNDS.add(id);
        } else {
            BACKGROUNDS.remove(id);
        }
        save();
    }

    /** Called from {@link HudElementRegistry#drawAt} inside the element's translated and scaled space. */
    static void drawBackground(net.minecraft.client.gui.GuiGraphicsExtractor graphics, HudElement element) {
        // Only while the HUD drew in the last 100 ms (its own HudSeen stamp), so an element showing nothing leaves no box.
        if (!background(element.id()) || HudSeen.msSince(element.id(), System.currentTimeMillis()) > 100L) {
            return;
        }
        int w = element.width();
        int h = element.height();
        if (w > 0 && h > 0) {
            graphics.fill(-PAD, -PAD, w + PAD, h + PAD, BACKGROUND);
        }
    }
}
