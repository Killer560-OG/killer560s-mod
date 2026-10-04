package com.killer560.hub.dungeonclass;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persisted settings for {@link ClassSelectionOverlay} - killer560's "once I click on one of the 5 classes
 * on that main page" request: a party roster bar plus drag/click assignment drawn over the real Catacombs
 * class-selection screen. Purely a display/planning aid on top of a menu (like {@link ClassOverrides}, which
 * this feature reads and writes rather than keeping a second, separate store) - not gated on the cheat build,
 * and defaults ON the same way {@code ClassOverrides} does, since there is nothing automated here to be
 * careful about switching on by default.
 */
public final class ClassSelectionOverlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-classselectionoverlay.json");

    private static ClassSelectionOverlayConfig instance;

    private boolean enabled = true;

    private ClassSelectionOverlayConfig() {
    }

    public static ClassSelectionOverlayConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        ClassSelectionOverlayConfig cfg = new ClassSelectionOverlayConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
            } catch (Exception ignored) {
                // unreadable file - defaults
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("enabled", enabled);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    /** Raw toggle state for the settings tab (ignores the Skyblock gate) - see {@code PartyFinderOverlayConfig}'s
     *  own copy of this split for why: a settings-tab button that reads/writes {@link #isEnabled()} directly
     *  would appear stuck off (and silently re-enable itself on click) while outside Skyblock. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
