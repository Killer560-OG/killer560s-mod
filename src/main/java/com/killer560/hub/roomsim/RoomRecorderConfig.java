package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.util.ConfigJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings for {@link RoomRecorderFeature}. Persisted, per the project rule that no setting is lost on restart. */
public final class RoomRecorderConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            ModPaths.config("killer560smod-roomrecorder.json");

    private static RoomRecorderConfig instance;

    /**
     * Stop the loop on a five-puzzle dungeon and say so, loudly, instead of leaving after the scan window.
     *
     * <p>killer560 (2026-09-28): "These are far more likely to have rare rooms so I should manually search a
     * hair to get about 2 chunks worth of rooms loaded in. It is worth it in those instances." So this is not a
     * safety pause - it is the recorder handing over on the runs where a human walking around is worth more than
     * another lap.
     */
    private boolean pauseOnFivePuzzles = true;

    /**
     * Walk a few steps into the spawn room on entering, to pull an extra ring of rooms into range.
     *
     * <p>killer560 asked for it (2026-09-28) and it is the difference between scanning what you can see from
     * the doorway and what you can see from inside, so it defaults ON. It is also the only thing the recorder
     * does that moves the player, which is why it has its own switch rather than being unconditional.
     */
    private boolean walkInOnEntry = true;

    /**
     * Key that resumes a paused run, or starts the recorder when it is off.
     *
     * <p>A key rather than only the command because the pause exists while he is playing the run by hand, and
     * stopping to type is the moment the feature costs more than it saves. Defaults to apostrophe, beside the
     * semicolon Breaker Aura already uses, and is rebindable.
     */
    private int resumeKeyCode = org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;

    private RoomRecorderConfig() {
    }

    public static RoomRecorderConfig getInstance() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            instance = new RoomRecorderConfig();
            return;
        }
        try {
            JsonObject o = JsonParser.parseString(Files.readString(CONFIG_PATH, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            RoomRecorderConfig c = new RoomRecorderConfig();
            c.pauseOnFivePuzzles = ConfigJson.getBool(o, "pauseOnFivePuzzles", true);
            c.walkInOnEntry = ConfigJson.getBool(o, "walkInOnEntry", true);
            c.resumeKeyCode = com.killer560.hub.util.KeyUtil.sanitize(
                    ConfigJson.getInt(o, "resumeKeyCode", org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE));
            // A binding of Enter already saved before that was rejected at the rebind is repaired on load,
            // rather than left to toggle the recorder on every chat message he sends.
            if (c.resumeKeyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || c.resumeKeyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                c.resumeKeyCode = org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE;
            }
            instance = c;
        } catch (Exception e) {
            instance = new RoomRecorderConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("pauseOnFivePuzzles", pauseOnFivePuzzles);
            o.addProperty("walkInOnEntry", walkInOnEntry);
            o.addProperty("resumeKeyCode", resumeKeyCode);
            Files.writeString(CONFIG_PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public boolean isWalkInOnEntry() {
        return walkInOnEntry;
    }

    public void setWalkInOnEntry(boolean v) {
        walkInOnEntry = v;
    }

    public boolean isPauseOnFivePuzzles() {
        return pauseOnFivePuzzles;
    }

    public void setPauseOnFivePuzzles(boolean v) {
        pauseOnFivePuzzles = v;
    }

    public int getResumeKeyCode() {
        return resumeKeyCode;
    }

    public void setResumeKeyCode(int v) {
        resumeKeyCode = com.killer560.hub.util.KeyUtil.sanitize(v);
    }
}
