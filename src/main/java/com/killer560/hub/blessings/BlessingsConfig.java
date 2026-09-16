package com.killer560.hub.blessings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.SkyblockGate;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted Blessings settings - {@code killer560smod-blessings.json}. Everything ships OFF. Per-key
 * {@link ConfigJson} readers, {@link #save()} on every GUI change, reloaded by
 * {@code ProfileManager.reloadAllConfigs()}. Feature getters AND {@link SkyblockGate#allows()}; the tab reads the
 * {@code *Raw} getters.
 */
public final class BlessingsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("killer560smod-blessings.json");

    private static BlessingsConfig instance;

    private boolean hud = false;
    private boolean romanNumerals = false;
    private boolean announceChat = false;
    private boolean announceParty = false;
    /** Which blessings appear on the HUD / in announcements. Power and Time default on (NoammAddons'
     *  {@code BlessingDisplay.kt} defaults: Power true, Time true, the other three false) - the whole HUD is
     *  still OFF until "Blessings HUD" is switched on. */
    private final Map<Blessing, Boolean> shown = new EnumMap<>(Blessing.class);
    private final Map<Blessing, Integer> colors = new EnumMap<>(Blessing.class);

    private BlessingsConfig() {
        for (Blessing blessing : Blessing.values()) {
            shown.put(blessing, blessing == Blessing.POWER || blessing == Blessing.TIME);
            colors.put(blessing, blessing.defaultColor());
        }
    }

    public static BlessingsConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        BlessingsConfig cfg = new BlessingsConfig();
        JsonObject obj = null;
        if (Files.exists(CONFIG_PATH)) {
            try {
                obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                obj = null;
            }
        }
        if (obj != null) {
            cfg.hud = ConfigJson.getBool(obj, "hud", false);
            cfg.romanNumerals = ConfigJson.getBool(obj, "romanNumerals", false);
            cfg.announceChat = ConfigJson.getBool(obj, "announceChat", false);
            cfg.announceParty = ConfigJson.getBool(obj, "announceParty", false);
            for (Blessing blessing : Blessing.values()) {
                cfg.shown.put(blessing, ConfigJson.getBool(obj, showKey(blessing), cfg.isShownRaw(blessing)));
                cfg.colors.put(blessing, ConfigJson.getInt(obj, colorKey(blessing), blessing.defaultColor()));
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("hud", hud);
            obj.addProperty("romanNumerals", romanNumerals);
            obj.addProperty("announceChat", announceChat);
            obj.addProperty("announceParty", announceParty);
            for (Blessing blessing : Blessing.values()) {
                obj.addProperty(showKey(blessing), isShownRaw(blessing));
                obj.addProperty(colorKey(blessing), getColor(blessing));
            }
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static String showKey(Blessing blessing) {
        return "show" + blessing.displayName();
    }

    private static String colorKey(Blessing blessing) {
        return blessing.displayName().toLowerCase(Locale.ROOT) + "Color";
    }

    public boolean isHudEnabled() {
        return hud && SkyblockGate.allows();
    }

    public boolean getHudRaw() {
        return hud;
    }

    public void setHud(boolean v) {
        hud = v;
    }

    public boolean isRomanNumerals() {
        return romanNumerals;
    }

    public void setRomanNumerals(boolean v) {
        romanNumerals = v;
    }

    public boolean isAnnounceChatEnabled() {
        return announceChat && SkyblockGate.allows();
    }

    public boolean getAnnounceChatRaw() {
        return announceChat;
    }

    public void setAnnounceChat(boolean v) {
        announceChat = v;
    }

    public boolean isAnnouncePartyEnabled() {
        return announceParty && SkyblockGate.allows();
    }

    public boolean getAnnouncePartyRaw() {
        return announceParty;
    }

    public void setAnnounceParty(boolean v) {
        announceParty = v;
    }

    /** Whether this blessing is shown at all (HUD line + announcements). Not Skyblock-gated on its own - the
     *  callers ({@link #isHudEnabled()} / {@link #isAnnounceChatEnabled()}) already are. */
    public boolean isShown(Blessing blessing) {
        return isShownRaw(blessing);
    }

    public boolean isShownRaw(Blessing blessing) {
        Boolean value = shown.get(blessing);
        return value != null && value;
    }

    public void setShown(Blessing blessing, boolean v) {
        shown.put(blessing, v);
    }

    public int getColor(Blessing blessing) {
        Integer value = colors.get(blessing);
        return value == null ? blessing.defaultColor() : value;
    }

    public void setColor(Blessing blessing, int argb) {
        colors.put(blessing, argb);
    }
}
