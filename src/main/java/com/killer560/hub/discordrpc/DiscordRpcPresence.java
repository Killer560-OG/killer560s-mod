package com.killer560.hub.discordrpc;

import com.google.gson.JsonObject;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.pathfinding.IslandDetector;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.SkyblockGate;
import net.minecraft.client.Minecraft;

/**
 * Turns what the mod already knows about the session into the two Discord presence lines. Everything here
 * reads cached statics the mod updates each tick ({@link IslandDetector}, {@link DungeonState},
 * {@link Floor7Tracker}) so it is cheap, but it still MUST be called from the client thread - the worker
 * thread only ever sees the finished {@link Key}.
 * <p>
 * Skyblock wording is gated on {@link SkyblockGate#isOnSkyblock()}: off Hypixel (singleplayer, another
 * server) the presence says nothing about islands, areas or dungeons.
 */
public final class DiscordRpcPresence {

    private DiscordRpcPresence() {
    }

    /**
     * Everything that decides what Discord displays. Compared as a value so an update is only sent when
     * the DISPLAYED TEXT actually changed - Discord rate-limits SET_ACTIVITY hard.
     */
    public record Key(String details, String state, String largeImage, String largeText,
                      String smallImage, String smallText, boolean elapsed) {
    }

    /** @return the presence lines for right now. Client thread only. */
    public static Key current(DiscordRpcConfig cfg) {
        String details;
        String state = null;

        if (cfg.isHideDetails()) {
            // Privacy mode: Discord shows just "Playing <application name>" and nothing else.
            details = null;
        } else {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.level == null) {
                details = "In the menus";
            } else if (!SkyblockGate.isOnSkyblock()) {
                details = "Playing Minecraft";
            } else if (cfg.isShowDungeonInfo() && DungeonState.isInDungeon()) {
                String floor = DungeonState.getFloor();
                details = floor == null || floor.isBlank() ? "The Catacombs" : "The Catacombs - " + floor;
                state = dungeonState();
            } else if (cfg.isShowArea()) {
                String island = IslandDetector.islandName();
                String area = IslandDetector.scoreboardArea();
                details = island.isBlank() ? "Playing SkyBlock" : island;
                state = area.isBlank() || area.equalsIgnoreCase(island) ? null : area;
            } else {
                details = "Playing SkyBlock";
            }
        }

        return new Key(clean(details), clean(state),
                cfg.getLargeImageKey(), cfg.getLargeImageText(),
                cfg.getSmallImageKey(), cfg.getSmallImageText(),
                cfg.isShowElapsed());
    }

    /** F7/M7 boss phase when the tracker knows it, otherwise a generic line. */
    private static String dungeonState() {
        try {
            Floor7Tracker.Phase phase = Floor7Tracker.getPhase();
            if (phase != null && phase != Floor7Tracker.Phase.UNKNOWN) {
                return "Boss - " + phase.name();
            }
        } catch (Throwable ignored) {
            // Tracker not initialised / not on a floor it understands - fall through.
        }
        return DungeonState.isBossPhaseActive() ? "Boss fight" : "In a dungeon run";
    }

    /** Discord rejects details/state shorter than 2 characters and truncates past 128. */
    private static String clean(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 2) {
            return null;
        }
        return trimmed.length() > 128 ? trimmed.substring(0, 128) : trimmed;
    }

    /** Builds the JSON activity object sent to Discord. Safe to call from the worker thread. */
    static JsonObject toActivity(Key key, long startedAtMs) {
        JsonObject activity = new JsonObject();
        if (key.details() != null) {
            activity.addProperty("details", key.details());
        }
        if (key.state() != null) {
            activity.addProperty("state", key.state());
        }
        if (key.elapsed()) {
            JsonObject timestamps = new JsonObject();
            timestamps.addProperty("start", startedAtMs / 1000L);
            activity.add("timestamps", timestamps);
        }
        JsonObject assets = new JsonObject();
        putIfSet(assets, "large_image", key.largeImage());
        putIfSet(assets, "large_text", key.largeText());
        putIfSet(assets, "small_image", key.smallImage());
        putIfSet(assets, "small_text", key.smallText());
        if (assets.size() > 0) {
            activity.add("assets", assets);
        }
        return activity;
    }

    private static void putIfSet(JsonObject obj, String name, String value) {
        if (value != null && !value.isBlank()) {
            obj.addProperty(name, value.trim());
        }
    }

    /** One-line human summary of what Discord is being told, for the settings tab. */
    public static String describe(Key key) {
        if (key.details() == null && key.state() == null) {
            return "Killer560's Mod (details hidden)";
        }
        if (key.state() == null) {
            return key.details();
        }
        return key.details() + " - " + key.state();
    }
}
