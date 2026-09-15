package com.killer560.hub.profileviewer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("killer560smod-profileviewer.json");

    private static ProfileViewerConfig instance;

    private String apiKey = "";
    private Source source = Source.HYPIXEL; // default off the third-party backend; Auto/Backend are opt-in
    /** GLFW key code, -1 = unbound. Opens the player under the crosshair, or yourself if none. */
    private int openKeyCode = -1;
    private boolean rememberLastPage = true;
    private int lastPage = 0;
    private boolean showSkin = true;

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
                String src = ConfigJson.getString(obj, "source", Source.HYPIXEL.name());
                try {
                    cfg.source = Source.valueOf(src);
                } catch (IllegalArgumentException ignored) {
                    cfg.source = Source.HYPIXEL;
                }
                cfg.openKeyCode = ConfigJson.getInt(obj, "openKeyCode", -1);
                cfg.rememberLastPage = ConfigJson.getBool(obj, "rememberLastPage", true);
                cfg.lastPage = ConfigJson.getInt(obj, "lastPage", 0);
                cfg.showSkin = ConfigJson.getBool(obj, "showSkin", true);
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
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
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
        this.source = source == null ? Source.HYPIXEL : source;
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
}
