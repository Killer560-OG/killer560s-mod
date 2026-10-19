package com.killer560.hub.modusers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.BuildVariant;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Mod User Tag settings (killer560, 2026-10-19). Everything is off by default: you do not see other mod users' symbols, and
 * you do not show yours. {@link #shareAs} is which variant you show as when sharing; the legit build can only show legit,
 * and the cheat build may show either.
 */
public final class ModUserConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-modusers.json");

    private static ModUserConfig instance;

    private boolean seeOthers = false;
    private boolean share = false;
    /** "legit" or "cheat"; the legit build always uses legit. */
    private String shareAs = "legit";

    private ModUserConfig() {
    }

    public static ModUserConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new ModUserConfig();
            return;
        }
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            ModUserConfig cfg = new ModUserConfig();
            cfg.seeOthers = ConfigJson.getBool(obj, "seeOthers", false);
            cfg.share = ConfigJson.getBool(obj, "share", false);
            cfg.shareAs = ConfigJson.getString(obj, "shareAs", "legit");
            instance = cfg;
        } catch (Exception e) {
            instance = new ModUserConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("seeOthers", seeOthers);
            obj.addProperty("share", share);
            obj.addProperty("shareAs", shareAs);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** This build's own variant name: "cheat" or "legit". */
    public static String ownVariant() {
        return BuildVariant.CHEAT_FEATURES_ENABLED ? "cheat" : "legit";
    }

    public boolean isSeeOthers() {
        return seeOthers;
    }

    public void setSeeOthers(boolean seeOthers) {
        this.seeOthers = seeOthers;
    }

    public boolean isShare() {
        return share;
    }

    public void setShare(boolean share) {
        this.share = share;
    }

    /** The variant shown when sharing. Always legit on the legit build. */
    public String getShareAs() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && "cheat".equals(shareAs) ? "cheat" : "legit";
    }

    public void setShareAs(String shareAs) {
        this.shareAs = "cheat".equals(shareAs) ? "cheat" : "legit";
    }
}
