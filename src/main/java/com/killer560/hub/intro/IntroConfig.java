package com.killer560.hub.intro;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;
import com.killer560.hub.util.ModPaths;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * The first-launch tour's one setting (killer560, 2026-11-01): whether it has run. Kept out of profiles
 * ({@code ProfileData.EXCLUDED_NAMES}): it is about this install, not a setup to share.
 *
 * <p>"Once, on the first install": an install that already had the mod's settings before this boot is not a first
 * install, so the tour is marked done for it without showing ({@link #captureFirstInstall}, called before anything
 * writes a setting). Home's Replay Intro shows it again whenever wanted.
 */
public final class IntroConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = ModPaths.config("killer560smod-intro.json");

    private static IntroConfig instance;
    /** Set by {@link #captureFirstInstall}: true when no setting of the mod existed when the game started. */
    private static boolean freshInstall;

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
        if (Files.exists(CONFIG_PATH)) {
            try {
                JsonObject obj = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                cfg.done = ConfigJson.getBool(obj, "done", false);
            } catch (Exception e) {
                cfg = new IntroConfig();
            }
        }
        instance = cfg;
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("done", done);
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

    /**
     * Called first thing in the client entrypoint, before any feature loads or saves: was there any setting of this
     * mod on disk (config/killer560/, or an old killer560smod-* file in the config root)? If there was, this is an
     * update rather than a first install, and the tour is marked done without showing.
     */
    public static void captureFirstInstall() {
        boolean existing = false;
        try {
            Path root = ModPaths.root();
            if (Files.isDirectory(root)) {
                try (Stream<Path> s = Files.walk(root, 4)) {
                    existing = s.anyMatch(Files::isRegularFile);
                }
            }
            if (!existing) {
                Path cfgDir = FabricLoader.getInstance().getConfigDir();
                if (Files.isDirectory(cfgDir)) {
                    try (Stream<Path> s = Files.list(cfgDir)) {
                        existing = s.anyMatch(p -> p.getFileName().toString().startsWith(ModPaths.PREFIX));
                    }
                }
            }
        } catch (Exception e) {
            existing = true; // unsure: never pop a tour up on someone who has used the mod for months
        }
        freshInstall = !existing;
        if (!freshInstall && !Files.exists(CONFIG_PATH)) {
            IntroConfig cfg = getInstance();
            cfg.setDone(true);
            cfg.save();
        }
    }

    /** True when no setting of the mod existed when the game started (read by the testkit). */
    public static boolean wasFreshInstall() {
        return freshInstall;
    }
}
