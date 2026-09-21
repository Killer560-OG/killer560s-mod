package com.killer560.hub.profileviewer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Persisted Profile Viewer settings - see {@link ProfileViewerFeature}. Every field survives a restart.
 * The Hypixel API key lives only in this file in the config dir; it is never logged, never put in a URL
 * (sent as the {@code API-Key} header) and only ever shown masked in the settings tab.
 */
public final class ProfileViewerConfig {

    /** Where profile data comes from. See {@code ProfileViewerApi} for the exact fallback order. */
    public enum Source {
        AUTO("Auto"),
        HYPIXEL("Hypixel API"),
        BACKEND("SkyBlockPV Backend");

        public final String label;

        Source(String label) {
            this.label = label;
        }

        public Source next() {
            Source[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /** One head in the recent-views list. Keyed by {@link #uuid} (killer560's rule: a rename must never
     *  break history) - {@link #name} is only the last-known IGN, shown until a live lookup refreshes it. */
    public record RecentView(UUID uuid, String name, long viewedAt) {
    }

    /** killer560 asked for "the last 5-10 people"; 10 is the top of that range. */
    public static final int MAX_RECENT_VIEWS = 10;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-profileviewer.json");

    private static ProfileViewerConfig instance;

    private String apiKey = "";
    // killer560: "set the source to auto by default" - Auto tries your own key first, then the keyless
    // backend, so it always works whether or not a key is set.
    private Source source = Source.AUTO;
    /** GLFW key code, -1 = unbound. Opens the player under the crosshair, or yourself if none. */
    private int openKeyCode = -1;
    private boolean rememberLastPage = true;
    private int lastPage = 0;
    private boolean showSkin = true;
    /** Most-recently-viewed first. */
    private final List<RecentView> recentViews = new ArrayList<>();

    private ProfileViewerConfig() {
    }

    public static synchronized ProfileViewerConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static synchronized void load() {
        ProfileViewerConfig cfg = new ProfileViewerConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
                cfg.apiKey = sanitizeKey(ConfigJson.getString(obj, "apiKey", ""));
                String src = ConfigJson.getString(obj, "source", Source.AUTO.name());
                try {
                    cfg.source = Source.valueOf(src);
                } catch (IllegalArgumentException ignored) {
                    cfg.source = Source.AUTO;
                }
                cfg.openKeyCode = ConfigJson.getInt(obj, "openKeyCode", -1);
                cfg.rememberLastPage = ConfigJson.getBool(obj, "rememberLastPage", true);
                cfg.lastPage = ConfigJson.getInt(obj, "lastPage", 0);
                cfg.showSkin = ConfigJson.getBool(obj, "showSkin", true);
                JsonArray recent = ConfigJson.getArray(obj, "recentViews");
                if (recent != null) {
                    for (JsonElement el : recent) {
                        // One malformed entry (bad/missing uuid) is skipped on its own.
                        if (el == null || !el.isJsonObject()) {
                            continue;
                        }
                        JsonObject o = el.getAsJsonObject();
                        UUID uuid = parseUuidQuiet(ConfigJson.getString(o, "uuid", ""));
                        if (uuid == null) {
                            continue;
                        }
                        cfg.recentViews.add(new RecentView(uuid, ConfigJson.getString(o, "name", ""),
                                ConfigJson.getLong(o, "viewedAt", 0)));
                        if (cfg.recentViews.size() >= MAX_RECENT_VIEWS) {
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Unreadable file: keep defaults for this session (never log the contents - it has the key).
            }
        }
        instance = cfg;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("apiKey", apiKey);
            obj.addProperty("source", source.name());
            obj.addProperty("openKeyCode", openKeyCode);
            obj.addProperty("rememberLastPage", rememberLastPage);
            obj.addProperty("lastPage", lastPage);
            obj.addProperty("showSkin", showSkin);
            JsonArray recent = new JsonArray();
            for (RecentView v : recentViews) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", v.uuid().toString());
                o.addProperty("name", v.name());
                o.addProperty("viewedAt", v.viewedAt());
                recent.add(o);
            }
            obj.add("recentViews", recent);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /** Dashed or dashless hex only - kept local (rather than calling into {@code ProfileViewerApi}) so
     *  loading this config never triggers that class's HTTP client / executor to spin up. */
    private static UUID parseUuidQuiet(String s) {
        if (s == null) {
            return null;
        }
        String hex = s.replace("-", "");
        if (hex.length() != 32 || !hex.matches("[0-9a-fA-F]{32}")) {
            return null;
        }
        try {
            return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Keeps only characters that can appear in a Hypixel key (UUID-ish) so a pasted newline/space
     *  can't break the request header. */
    public static String sanitizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '-') {
                sb.append(c);
            }
        }
        return sb.length() > 64 ? sb.substring(0, 64) : sb.toString();
    }

    public synchronized String getApiKey() {
        return apiKey;
    }

    public synchronized boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    public synchronized void setApiKey(String apiKey) {
        this.apiKey = sanitizeKey(apiKey);
    }

    public synchronized Source getSource() {
        return source;
    }

    public synchronized void setSource(Source source) {
        this.source = source == null ? Source.AUTO : source;
    }

    public synchronized int getOpenKeyCode() {
        return openKeyCode;
    }

    public synchronized void setOpenKeyCode(int openKeyCode) {
        this.openKeyCode = openKeyCode;
    }

    public synchronized boolean isRememberLastPage() {
        return rememberLastPage;
    }

    public synchronized void setRememberLastPage(boolean rememberLastPage) {
        this.rememberLastPage = rememberLastPage;
    }

    public synchronized int getLastPage() {
        return lastPage;
    }

    public synchronized void setLastPage(int lastPage) {
        this.lastPage = lastPage;
    }

    public synchronized boolean isShowSkin() {
        return showSkin;
    }

    public synchronized void setShowSkin(boolean showSkin) {
        this.showSkin = showSkin;
    }

    public synchronized List<RecentView> getRecentViews() {
        return List.copyOf(recentViews);
    }

    /** Bumps {@code uuid} to the front of the recent-views list (removing any older entry for the same
     *  uuid first) and saves. {@code name} is just the latest-known IGN for display before a live lookup
     *  can refresh it - the uuid is what identifies "who" was viewed. */
    public synchronized void recordRecentView(UUID uuid, String name) {
        if (uuid == null) {
            return;
        }
        recentViews.removeIf(v -> v.uuid().equals(uuid));
        recentViews.add(0, new RecentView(uuid, name == null ? "" : name, System.currentTimeMillis()));
        while (recentViews.size() > MAX_RECENT_VIEWS) {
            recentViews.remove(recentViews.size() - 1);
        }
        save();
    }
}
