package com.killer560.hub.lfg;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom Party Finder settings ({@link LfgFeature}). Defaults: the feature on (nothing reaches the relay until the menu
 * is opened, a listing is made or a Hypixel queue is dual-listed), Dual-List Hypixel Queues on and Auto Accept Invite
 * on (both are what killer560 asked for), Check Requirements on. Per tab it remembers the last activity, party size,
 * note and requirement values, which is what fills the Create form ("auto fill requirements").
 */
public final class LfgConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-partyfinder-custom.json");

    private static LfgConfig instance;

    /** What the Create form last held for one tab. */
    public static final class Draft {
        public String activity;
        public int max;
        public String note = "";
        public final Map<String, Integer> reqs = new LinkedHashMap<>();

        Draft(LfgCategory c) {
            activity = c.activities.get(0);
            max = c.maxParty;
        }
    }

    private boolean enabled = true;
    private boolean dualList = true;
    private boolean autoAcceptInvite = true;
    private boolean checkRequirements = true;
    private LfgCategory lastTab = LfgCategory.DUNGEONS;
    private final Map<LfgCategory, Draft> drafts = new EnumMap<>(LfgCategory.class);

    private LfgConfig() {
        for (LfgCategory c : LfgCategory.values()) {
            drafts.put(c, new Draft(c));
        }
    }

    public static LfgConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        LfgConfig cfg = new LfgConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.enabled = ConfigJson.getBool(obj, "enabled", true);
                cfg.dualList = ConfigJson.getBool(obj, "dualList", true);
                cfg.autoAcceptInvite = ConfigJson.getBool(obj, "autoAcceptInvite", true);
                cfg.checkRequirements = ConfigJson.getBool(obj, "checkRequirements", true);
                LfgCategory tab = LfgCategory.fromId(ConfigJson.getString(obj, "lastTab", "dungeons"));
                cfg.lastTab = tab == null ? LfgCategory.DUNGEONS : tab;
                JsonObject drafts = ConfigJson.getObject(obj, "drafts");
                if (drafts != null) {
                    for (LfgCategory c : LfgCategory.values()) {
                        JsonObject d = ConfigJson.getObject(drafts, c.id);
                        if (d == null) {
                            continue;
                        }
                        Draft draft = cfg.drafts.get(c);
                        String act = ConfigJson.getString(d, "activity", draft.activity);
                        if (c.activities.contains(act)) {
                            draft.activity = act;
                        }
                        draft.max = Math.max(2, Math.min(c.maxParty, ConfigJson.getInt(d, "max", c.maxParty)));
                        draft.note = clampNote(ConfigJson.getString(d, "note", ""));
                        JsonObject reqs = ConfigJson.getObject(d, "reqs");
                        if (reqs != null) {
                            for (LfgCategory.Field f : c.fields) {
                                JsonElement v = reqs.get(f.key());
                                if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                                    draft.reqs.put(f.key(), Math.max(0, Math.min(f.max(), v.getAsInt())));
                                }
                            }
                        }
                    }
                }
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
            obj.addProperty("dualList", dualList);
            obj.addProperty("autoAcceptInvite", autoAcceptInvite);
            obj.addProperty("checkRequirements", checkRequirements);
            obj.addProperty("lastTab", lastTab.id);
            JsonObject all = new JsonObject();
            for (Map.Entry<LfgCategory, Draft> e : drafts.entrySet()) {
                JsonObject d = new JsonObject();
                d.addProperty("activity", e.getValue().activity);
                d.addProperty("max", e.getValue().max);
                d.addProperty("note", e.getValue().note);
                JsonObject reqs = new JsonObject();
                e.getValue().reqs.forEach(reqs::addProperty);
                d.add("reqs", reqs);
                all.add(e.getKey().id, d);
            }
            obj.add("drafts", all);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    static String clampNote(String note) {
        String s = note == null ? "" : note.replace("§", "").replaceAll("\\p{Cntrl}", "");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    /** Raw toggle (the settings tab); the feature itself also needs Skyblock Only to allow it. */
    public boolean isEnabledRaw() {
        return enabled;
    }

    public boolean isEnabled() {
        return enabled && com.killer560.hub.util.SkyblockGate.allows();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDualList() {
        return dualList;
    }

    public void setDualList(boolean dualList) {
        this.dualList = dualList;
    }

    public boolean isAutoAcceptInvite() {
        return autoAcceptInvite;
    }

    public void setAutoAcceptInvite(boolean autoAcceptInvite) {
        this.autoAcceptInvite = autoAcceptInvite;
    }

    public boolean isCheckRequirements() {
        return checkRequirements;
    }

    public void setCheckRequirements(boolean checkRequirements) {
        this.checkRequirements = checkRequirements;
    }

    public LfgCategory getLastTab() {
        return lastTab;
    }

    public void setLastTab(LfgCategory tab) {
        if (tab != null) {
            this.lastTab = tab;
        }
    }

    public Draft draft(LfgCategory c) {
        return drafts.get(c);
    }
}
