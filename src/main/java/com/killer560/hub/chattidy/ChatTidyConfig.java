package com.killer560.hub.chattidy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.SkyblockGate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted Chat Tidy settings - see {@link ChatTidy}. Both features ship OFF, like every new feature; the two hider
 *  families default ON so turning on "Hide Damage Messages" hides both until one is switched off. */
public final class ChatTidyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-chattidy.json");

    private static volatile ChatTidyConfig instance;

    private boolean stackDuplicates = false;
    private boolean hideDamageMessages = false;
    private boolean hideAbilityDamage = true;
    private boolean hideIncomingHits = true;

    private ChatTidyConfig() {
    }

    public static ChatTidyConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        ChatTidyConfig cfg = new ChatTidyConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.stackDuplicates = ConfigJson.getBool(obj, "stackDuplicates", false);
                cfg.hideDamageMessages = ConfigJson.getBool(obj, "hideDamageMessages", false);
                cfg.hideAbilityDamage = ConfigJson.getBool(obj, "hideAbilityDamage", true);
                cfg.hideIncomingHits = ConfigJson.getBool(obj, "hideIncomingHits", true);
            } catch (Exception e) {
                cfg = new ChatTidyConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("stackDuplicates", stackDuplicates);
            obj.addProperty("hideDamageMessages", hideDamageMessages);
            obj.addProperty("hideAbilityDamage", hideAbilityDamage);
            obj.addProperty("hideIncomingHits", hideIncomingHits);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    // ---- gated (what the features read) ---------------------------------------------------------------------

    public boolean isStackDuplicates() {
        return stackDuplicates && SkyblockGate.allows();
    }

    public boolean isHideAbilityDamage() {
        return hideDamageMessages && hideAbilityDamage && SkyblockGate.allows();
    }

    public boolean isHideIncomingHits() {
        return hideDamageMessages && hideIncomingHits && SkyblockGate.allows();
    }

    // ---- raw (what the settings menu shows) -----------------------------------------------------------------

    public boolean getStackDuplicatesRaw() {
        return stackDuplicates;
    }

    public void setStackDuplicates(boolean v) {
        stackDuplicates = v;
    }

    public boolean getHideDamageMessagesRaw() {
        return hideDamageMessages;
    }

    public void setHideDamageMessages(boolean v) {
        hideDamageMessages = v;
    }

    public boolean getHideAbilityDamageRaw() {
        return hideAbilityDamage;
    }

    public void setHideAbilityDamage(boolean v) {
        hideAbilityDamage = v;
    }

    public boolean getHideIncomingHitsRaw() {
        return hideIncomingHits;
    }

    public void setHideIncomingHits(boolean v) {
        hideIncomingHits = v;
    }
}
