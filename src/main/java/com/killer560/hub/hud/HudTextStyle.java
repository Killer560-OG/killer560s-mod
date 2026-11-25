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

    // Read every frame by every HUD draw: volatile snapshots (replaced whole on a change), no lock on the read path.
    private static volatile Map<String, Boolean> shadows = Map.of();
    private static volatile Set<String> backgrounds = Set.of();
    private static volatile boolean loaded;

    private HudTextStyle() {
    }

    private static void ensureLoaded() {
        if (!loaded) {
            load();
        }
    }

    /** Puts the defaults back and reads the file (a profile apply calls this). */
    public static synchronized void load() {
        Map<String, Boolean> sh0 = new HashMap<>();
        Set<String> bg0 = new HashSet<>();
        JsonObject o = ConfigJson.read(PATH);
        if (o != null) {
            JsonObject sh = o.has("shadow") && o.get("shadow").isJsonObject() ? o.getAsJsonObject("shadow") : new JsonObject();
            sh.entrySet().forEach(e -> sh0.put(e.getKey(), e.getValue().getAsBoolean()));
            JsonObject bg = o.has("background") && o.get("background").isJsonObject() ? o.getAsJsonObject("background") : new JsonObject();
            bg.entrySet().forEach(e -> {
                if (e.getValue().getAsBoolean()) {
                    bg0.add(e.getKey());
                }
            });
        }
        shadows = sh0;
        backgrounds = bg0;
        loaded = true;
    }

    private static synchronized void save() {
        JsonObject o = new JsonObject();
        JsonObject sh = new JsonObject();
        shadows.forEach(sh::addProperty);
        JsonObject bg = new JsonObject();
        backgrounds.forEach(id -> bg.addProperty(id, true));
        o.add("shadow", sh);
        o.add("background", bg);
        ConfigJson.write(PATH, o);
    }

    /** Whether HUD {@code id} draws its text with a shadow; {@code def} is what the HUD drew before this setting existed. */
    public static boolean shadow(String id, boolean def) {
        ensureLoaded();
        return shadows.getOrDefault(id, def);
    }

    public static synchronized void setShadow(String id, boolean on) {
        ensureLoaded();
        Map<String, Boolean> m = new HashMap<>(shadows);
        m.put(id, on);
        shadows = m;
        save();
    }

    public static boolean background(String id) {
        ensureLoaded();
        return backgrounds.contains(id);
    }

    public static synchronized void setBackground(String id, boolean on) {
        ensureLoaded();
        Set<String> b = new HashSet<>(backgrounds);
        if (on) {
            b.add(id);
        } else {
            b.remove(id);
        }
        backgrounds = b;
        save();
    }

    /** Called from {@link HudElementRegistry#drawAt} inside the element's translated and scaled space. */
    static void drawBackground(net.minecraft.client.gui.GuiGraphicsExtractor graphics, HudElement element) {
        // Only while the HUD drew in the last 100 ms (its own HudSeen stamp), so an element showing nothing leaves no box.
        ensureLoaded();
        if (backgrounds.isEmpty() || !backgrounds.contains(element.id()) || HudSeen.msSince(element.id(), System.currentTimeMillis()) > 100L) {
            return;
        }
        int w = element.width();
        int h = element.height();
        if (w > 0 && h > 0) {
            graphics.fill(-PAD, -PAD, w + PAD, h + PAD, BACKGROUND);
        }
    }
}
