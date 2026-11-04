package com.killer560.hub.mining.lobby;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.mining.MiningIsland;
import com.killer560.hub.mining.MiningTextHud;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import com.killer560.hub.util.TabText;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lobby Tracker (legit, both builds): remembers the mining lobbies you have been in, by the server id the tab list
 * shows ("Server: mini12AB"), and on joining one you have seen before says when and how often (Athen's Lobby Marker).
 * A small HUD shows this lobby's id, your visits and the world's day counter (its tick count / 24000, which is the
 * world's own clock, not a number Hypixel publishes). History is kept in {@code killer560smod-mining-lobbies.json}
 * (500 lobbies at most, left out of profiles as history). Reads only; never sends anything.
 */
public final class LobbyTrackerFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-mining-lobby");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATA_PATH = ModPaths.config("killer560smod-mining-lobbies.json");
    private static final Pattern SERVER = Pattern.compile("^Server:\\s*([A-Za-z0-9]{1,24})$");
    private static final int MAX_LOBBIES = 500;

    private record Visit(long firstSeen, long lastSeen, int visits) {
    }

    private static final Map<String, Visit> HISTORY = new LinkedHashMap<>();
    private static boolean loaded = false;
    private static String current = null;
    private static int tick = 0;
    private static List<String> hudLines = List.of();

    public static final MiningTextHud HUD = new MiningTextHud("mining_lobby", "Lobby Tracker", 10, 220,
            () -> LobbyTrackerConfig.getInstance().isEnabled() && LobbyTrackerConfig.getInstance().isHud(),
            () -> hudLines, List.of("§6Lobby §fmini12AB", "§7Visits: §f3", "§7World Day: §f4"));

    private LobbyTrackerFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("LobbyTrackerFeature", LobbyTrackerFeature::onTick));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            current = null;
            hudLines = List.of();
        }));
    }

    private static void onTick(Minecraft client) {
        if (tick++ % 20 != 0) {
            return;
        }
        LobbyTrackerConfig cfg = LobbyTrackerConfig.getInstance();
        if (!cfg.isEnabled() || client.level == null || client.getConnection() == null || !MiningIsland.onMiningIsland()) {
            hudLines = List.of();
            return;
        }
        String id = readServer(client);
        if (id == null) {
            return;
        }
        ensureLoaded();
        long now = System.currentTimeMillis();
        if (!id.equals(current)) {
            current = id;
            Visit before = HISTORY.get(id);
            if (before != null && cfg.isChatOnJoin()) {
                ModChat.send("Lobby Tracker", ModChat.text("You were in this lobby (" + id + ") "),
                        ModChat.value(ago(now - before.lastSeen())), ModChat.text(" ago, "),
                        ModChat.value(before.visits() + (before.visits() == 1 ? " time" : " times")),
                        ModChat.text(" before."));
            }
            HISTORY.remove(id);
            HISTORY.put(id, before == null ? new Visit(now, now, 1)
                    : new Visit(before.firstSeen(), now, before.visits() + 1));
            while (HISTORY.size() > MAX_LOBBIES) {
                HISTORY.remove(HISTORY.keySet().iterator().next());
            }
            save();
        } else {
            Visit v = HISTORY.get(id);
            if (v != null) {
                HISTORY.put(id, new Visit(v.firstSeen(), now, v.visits()));
            }
        }
        List<String> out = new ArrayList<>();
        Visit v = HISTORY.get(id);
        out.add("§6Lobby §f" + id);
        out.add("§7Visits: §f" + (v == null ? 1 : v.visits()));
        if (cfg.isShowWorldDay()) {
            out.add("§7World Day: §f" + (client.level.getGameTime() / 24000L));
        }
        hudLines = out;
    }

    private static String readServer(Minecraft client) {
        for (PlayerInfo info : client.getConnection().getListedOnlinePlayers()) {
            String plain = TabText.plain(info);
            if (plain == null || !plain.contains("Server:")) {
                continue;
            }
            Matcher m = SERVER.matcher(plain.trim());
            if (m.matches()) {
                return m.group(1);
            }
        }
        return null;
    }

    static String ago(long ms) {
        long m = Math.max(0, ms / 60_000L);
        if (m < 1) {
            return "under a minute";
        }
        if (m < 60) {
            return m + "m";
        }
        long h = m / 60;
        if (h < 48) {
            return h + "h " + (m % 60) + "m";
        }
        return (h / 24) + " days";
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(DATA_PATH)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(DATA_PATH, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                HISTORY.put(e.getKey(), new Visit(o.get("first").getAsLong(), o.get("last").getAsLong(),
                        o.get("visits").getAsInt()));
            }
        } catch (Exception e) {
            LOGGER.warn("[LobbyTracker] Couldn't read {} - starting a fresh history: {}", DATA_PATH.getFileName(),
                    e.toString());
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Visit> e : HISTORY.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("first", e.getValue().firstSeen());
                o.addProperty("last", e.getValue().lastSeen());
                o.addProperty("visits", e.getValue().visits());
                root.add(e.getKey(), o);
            }
            Files.createDirectories(DATA_PATH.getParent());
            Files.writeString(DATA_PATH, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOGGER.warn("[LobbyTracker] Couldn't save {}: {}", DATA_PATH.getFileName(), e.toString());
        }
    }

    /** For the tab. */
    public static String status() {
        ensureLoaded();
        return (current == null ? "Not in a mining lobby" : "This lobby: " + current) + "; " + HISTORY.size()
                + " lobbies remembered";
    }

    public static void clearHistory() {
        ensureLoaded();
        HISTORY.clear();
        current = null;
        save();
    }
}
