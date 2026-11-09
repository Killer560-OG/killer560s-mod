package com.killer560.hub.intro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The first-launch tour's one setting: whether this instance has seen it. Kept out of profiles
 * ({@code ProfileData.EXCLUDED_NAMES}) and out of the shared stats store, so each instance shows it once.
 *
 * <p>killer560, 2026-11-01: it shows for everyone, fresh installs and players who just updated, "once and only once":
 * {@code seen} is saved the moment it opens on its own, so closing the game mid-tour does not bring it back. Home's
 * Replay Intro shows it again whenever wanted. The key is {@code seen}, not the first version's {@code done}: that
 * version wrote {@code done: true} without showing anything on every install that already had settings, and those
 * instances must still get the tour once.
 */
public final class IntroConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-intro.json");

    private static IntroConfig instance;

    private boolean done;

    private IntroConfig() {
    }

    public static IntroConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        IntroConfig cfg = new IntroConfig();
        JsonObject obj = ConfigJson.read(CONFIG_PATH);
        if (obj != null) {
            cfg.done = ConfigJson.getBool(obj, "seen", false);
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("seen", done);
            Files.writeString(CONFIG_PATH, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }
}
