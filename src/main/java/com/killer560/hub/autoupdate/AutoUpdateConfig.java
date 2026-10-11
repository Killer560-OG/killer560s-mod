package com.killer560.hub.autoupdate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Auto Update's one setting, the channel (killer560, 2026-10-11): Off, Full Release (the default) or Beta. */
public final class AutoUpdateConfig {

    /** Which GitHub release the updater follows. */
    public enum Channel {
        OFF("Off"),
        /** The newest non-prerelease release ({@code /releases/latest}). */
        FULL_RELEASE("Full Release"),
        /** The rolling "Latest Dev Build" pre-release (tag {@code dev-latest}) that dev-build.yml republishes. */
        BETA("Beta");

        public final String label;

        Channel(String label) {
            this.label = label;
        }

        public Channel next() {
            Channel[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        /** Parses a stored or typed name; {@code null} when it is not one. */
        public static Channel parse(String s) {
            if (s == null) {
                return null;
            }
            String k = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
            for (Channel c : values()) {
                if (c.name().equals(k)) {
                    return c;
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-autoupdate.json");

    private static AutoUpdateConfig instance;

    private Channel channel = Channel.FULL_RELEASE;

    private AutoUpdateConfig() {
    }

    public static AutoUpdateConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        AutoUpdateConfig cfg = new AutoUpdateConfig();
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (obj.has("channel")) {
                    Channel c = Channel.parse(obj.get("channel").getAsString());
                    if (c != null) {
                        cfg.channel = c;
                    }
                }
            } catch (Exception ignored) {
                // A broken file reads as the defaults, like every other config.
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("channel", channel.name());
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel == null ? Channel.FULL_RELEASE : channel;
    }

    /** The staging folder: downloaded jars, staged.json / installed.json and the helper's log. */
    public static Path stagingDir() {
        return ModPaths.config("killer560smod-autoupdate-staging");
    }
}
