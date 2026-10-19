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
 * on (both are what killer560 asked for), Check Requirements on, Auto Accept Requests on. The browse page's sort and
 * filters are kept here too, so they ride in profiles with the rest. Per tab it remembers the last activity, party size,
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
    /** The leader's client invites a joiner who meets every checked requirement, without a click (killer560, 2026-10-18:
     *  "Have my client send them a request to join and theirs accept it"). Off: every request waits for Accept. */
    private boolean autoAcceptRequests = true;
    private LfgCategory lastTab = LfgCategory.DUNGEONS;
    /** The browse page's sort and filters (local to the menu; the relay never sees them). */
    private LfgFilter.Sort sort = LfgFilter.Sort.NEWEST;
    private boolean meetsOnly = false;
    private boolean hideFull = true;
    /** The activity filter inside a category ("" = any), e.g. "F7" on the Dungeons page. */
    private final Map<LfgCategory, String> activityFilter = new EnumMap<>(LfgCategory.class);
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
                cfg.autoAcceptRequests = ConfigJson.getBool(obj, "autoAcceptRequests", true);
                cfg.sort = LfgFilter.Sort.fromId(ConfigJson.getString(obj, "sort", "newest"));
                cfg.meetsOnly = ConfigJson.getBool(obj, "meetsOnly", false);
                cfg.hideFull = ConfigJson.getBool(obj, "hideFull", true);
                JsonObject af = ConfigJson.getObject(obj, "activityFilter");
                if (af != null) {
                    for (LfgCategory c : LfgCategory.values()) {
                        String a = ConfigJson.getString(af, c.id, "");
                        if (c.activities.size() > 1 && c.activities.contains(a)) {
                            cfg.activityFilter.put(c, a);
                        }
                    }
                }
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
                        if (c.allowsActivity(act)) {
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
            obj.addProperty("autoAcceptRequests", autoAcceptRequests);
            obj.addProperty("sort", sort.id);
            obj.addProperty("meetsOnly", meetsOnly);
            obj.addProperty("hideFull", hideFull);
            JsonObject af = new JsonObject();
            activityFilter.forEach((c, a) -> af.addProperty(c.id, a));
            obj.add("activityFilter", af);
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

    public boolean isAutoAcceptRequests() {
        return autoAcceptRequests;
    }

    public void setAutoAcceptRequests(boolean autoAcceptRequests) {
        this.autoAcceptRequests = autoAcceptRequests;
    }

    public LfgFilter.Sort getSort() {
        return sort;
    }

    public void setSort(LfgFilter.Sort sort) {
        if (sort != null) {
            this.sort = sort;
        }
    }

    public boolean isMeetsOnly() {
        return meetsOnly;
    }

    public void setMeetsOnly(boolean meetsOnly) {
        this.meetsOnly = meetsOnly;
    }

    public boolean isHideFull() {
        return hideFull;
    }

    public void setHideFull(boolean hideFull) {
        this.hideFull = hideFull;
    }

    /** The activity filter for one category, "" for any. */
    public String getActivityFilter(LfgCategory c) {
        return activityFilter.getOrDefault(c, "");
    }

    public void setActivityFilter(LfgCategory c, String activity) {
        if (activity == null || activity.isEmpty() || !c.activities.contains(activity)) {
            activityFilter.remove(c);
        } else {
            activityFilter.put(c, activity);
        }
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
