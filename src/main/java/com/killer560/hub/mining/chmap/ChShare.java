package com.killer560.hub.mining.chmap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.relay.RelayClient;
import com.killer560.hub.relay.RelayListener;
import com.killer560.hub.util.ModLog;
import org.slf4j.Logger;

/**
 * Sends the structures found in this lobby to everyone else running this mod in it, and folds in theirs.
 *
 * <p>killer560 (2026-09-30): "it should be tied in so that way anyone else using our mod can also see way
 * points that I acquire there should be an option for sharing the waypoints under the map specifically so if
 * I don't want to share way points than it won't."
 *
 * <p><b>Its own switch, under the map.</b> The mod-wide sharing toggle is not the one that governs this -
 * that is the point of what he asked for - so {@link CrystalHollowsMapConfig} carries a share flag and a
 * receive flag of its own. They default ON, which is this project's rule for sharing settings, and turning
 * the share one off stops this sending anything while still letting him receive.
 *
 * <p><b>Keyed on the lobby, not the party.</b> A Crystal Hollows find is only meaningful to someone standing
 * in the same lobby - the layout differs on every server - so this only publishes while the relay is in a
 * {@code lobby:} room, which {@code HypixelLocation} already maintains from Hypixel's own server id. Sending
 * a find into a party room would be telling people about terrain that does not exist where they are.
 *
 * <p><b>What is NOT sent:</b> his position, anything about his inventory, and anything at all when he is not
 * in the Crystal Hollows. A find is a structure name and the box it was seen to occupy, nothing else.
 */
public final class ChShare {

    private static final Logger LOGGER = ModLog.get("killer560smod-chmap");

    /** Versioned like every other relay key, so an incompatible change ships as v2 rather than breaking v1. */
    private static final String KEY_FIND = "ch.v1.find";

    /** Re-sending everything occasionally is what lets someone who joined later catch up. */
    private static final long RESEND_EVERY_MS = 30_000;

    private static long lastSentAtMs;
    private static int lastSentCount = -1;

    private ChShare() {
    }

    /**
     * Whether the relay should be in a LOBBY room for us right now.
     *
     * <p>Read by {@code ModChatFeature}, which is the single place that decides the room. A Crystal Hollows
     * find travels in the lobby room or not at all, and the room mode defaults to Party - so without this
     * the whole sharing feature would compile, run every tick, and never send a single packet.
     *
     * <p>Only while he is actually in the Crystal Hollows, so this never takes the relay off the party room
     * anywhere it matters.
     */
    public static boolean wantsLobbyRoom() {
        CrystalHollowsMapConfig cfg = CrystalHollowsMapConfig.getInstance();
        return cfg.isEnabled()
                && (cfg.isShareWaypoints() || cfg.isReceiveWaypoints())
                && "CRYSTAL_HOLLOWS".equals(com.killer560.hub.pathfinding.IslandDetector.graphIsland());
    }

    /** One line for the settings tab: is sharing actually able to work right now, and if not, why not. */
    public static String status() {
        CrystalHollowsMapConfig cfg = CrystalHollowsMapConfig.getInstance();
        if (!cfg.isShareWaypoints() && !cfg.isReceiveWaypoints()) {
            return "off";
        }
        if (!"CRYSTAL_HOLLOWS".equals(com.killer560.hub.pathfinding.IslandDetector.graphIsland())) {
            return "waiting - not in the Crystal Hollows";
        }
        if (!RelayClient.isConnected()) {
            return "waiting - the relay is not connected";
        }
        String room = RelayClient.room();
        if (room == null || !room.startsWith("lobby:")) {
            return "waiting - Hypixel has not said which lobby this is yet";
        }
        return "connected to this lobby";
    }

    public static void register() {
        RelayClient.addListener(new Listener());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(
                com.killer560.hub.util.FeatureGuard.end("ChShare", client -> {
                    try {
                        tick();
                    } catch (Exception e) {
                        LOGGER.error("Crystal Hollows share tick failed", e);
                    }
                }));
    }

    private static void tick() {
        CrystalHollowsMapConfig cfg = CrystalHollowsMapConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isShareWaypoints()) {
            return;
        }
        if (!RelayClient.isConnected() || !RelayClient.room().startsWith("lobby:")) {
            return;
        }
        var finds = ChDiscovery.all();
        if (finds.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        // On a change, or occasionally regardless so a late joiner catches up. Counting is enough to notice
        // a new structure; a box that merely grew will go out on the next periodic send, which is soon and
        // costs nothing to wait for.
        boolean changed = finds.size() != lastSentCount;
        if (!changed && now - lastSentAtMs < RESEND_EVERY_MS) {
            return;
        }
        lastSentAtMs = now;
        lastSentCount = finds.size();
        for (ChFind f : finds) {
            // Only what he actually established himself. Relaying a find that came from someone else would
            // turn one person's guess into everybody's consensus with no way back to who saw it.
            if (f.source == ChFind.Source.SHARED) {
                continue;
            }
            RelayClient.sendData(KEY_FIND, encode(f));
        }
    }

    private static JsonObject encode(ChFind f) {
        JsonObject o = new JsonObject();
        o.addProperty("s", f.structure.name());
        o.addProperty("src", f.source.name());
        o.addProperty("x0", Math.round(f.minX));
        o.addProperty("y0", Math.round(f.minY));
        o.addProperty("z0", Math.round(f.minZ));
        o.addProperty("x1", Math.round(f.maxX));
        o.addProperty("y1", Math.round(f.maxY));
        o.addProperty("z1", Math.round(f.maxZ));
        return o;
    }

    /** Validates hard before anything reaches the map - the sender is another client and may be anything. */
    private static final class Listener implements RelayListener {
        @Override
        public void onData(String from, String key, JsonElement value) {
            if (!KEY_FIND.equals(key) || value == null || !value.isJsonObject()) {
                return;
            }
            CrystalHollowsMapConfig cfg = CrystalHollowsMapConfig.getInstance();
            if (!cfg.isEnabled() || !cfg.isReceiveWaypoints()) {
                return;
            }
            try {
                JsonObject o = value.getAsJsonObject();
                ChStructure structure = ChStructure.byName(readString(o, "s"));
                if (structure == null) {
                    return;
                }
                double x0 = readCoord(o, "x0");
                double y0 = readCoord(o, "y0");
                double z0 = readCoord(o, "z0");
                double x1 = readCoord(o, "x1");
                double y1 = readCoord(o, "y1");
                double z1 = readCoord(o, "z1");
                if (Double.isNaN(x0) || Double.isNaN(y0) || Double.isNaN(z0)
                        || Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(z1)) {
                    return;
                }
                ChFind f = new ChFind(structure, ChFind.Source.SHARED, x0, y0, z0);
                f.include(x1, y1, z1);
                f.fromUuid = from == null ? "" : from;
                ChDiscovery.addShared(f);
            } catch (RuntimeException e) {
                // A malformed packet is the sender's problem, not a reason to take anything down.
                LOGGER.warn("[CHMap] ignored a malformed shared find from {}", from);
            }
        }
    }

    private static String readString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /**
     * One coordinate, or NaN when it is not a number in range.
     *
     * <p>Bounded to the Crystal Hollows' own extent rather than merely "is a number": the cave is a fixed
     * 621x157x621 box, so anything outside that could not be a real find and would only serve to stretch a
     * border across the whole map. This is another client's data, and the project has already been bitten by
     * trusting another player's text - see the chat-pattern rules in CLAUDE.md.
     */
    private static double readCoord(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return Double.NaN;
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v) || v < -1000 || v > 1500) {
            return Double.NaN;
        }
        return v;
    }
}
