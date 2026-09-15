package com.killer560.hub.posmsg;

import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.secrets.DungeonState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Position-message waypoints: send a tagged chat line ({@link #TAG}) carrying a name/coords/radius,
 *  which every party member's own copy of this mod parses back out of the chat line they receive and
 *  renders as a marker in their own Posmsg HUD list ({@link PosmsgHudElement}) - no server, no shared
 *  backend, just chat both sides already have to see anyway. Sent to Party Chat directly via
 *  {@code sendCommand} (NOT through {@code TranslateFeature.sendGenerated}) so Translate/Auto
 *  Correct/Chat Emotes can never mangle the machine-readable payload.
 * <p>
 * "Once per run" ({@link PosmsgEntry#onceOnlyPerRun}) is tracked here, reset whenever
 * {@link DungeonState#isInDungeon()} transitions false -&gt; true (a fresh run), the same edge every
 * other per-run reset in this mod watches for. */
public final class PosmsgFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-posmsg");
    private static final String TAG = "[PM]";
    // Real bug found and fixed (2026-09-14): send() formatted the payload with the default locale, so a
    // comma-decimal locale (de_DE, fr_FR, ...) sent "12,50", which the old [\d.]+ groups never matched.
    // send() now always formats with Locale.US; receiving also accepts a comma decimal (fields are
    // pipe-separated, so it's unambiguous) and normalizes it, so lines from older builds still parse.
    private static final Pattern RECEIVE_PATTERN =
            Pattern.compile("\\[PM]([^|]+)\\|(-?[\\d.,]+)\\|(-?[\\d.,]+)\\|(-?[\\d.,]+)\\|(-?[\\d.,]+)");

    private static final Map<String, ReceivedMarker> receivedMarkers = new HashMap<>();
    private static final Set<String> usedThisRun = new HashSet<>();
    private static boolean wasInDungeon = false;

    private PosmsgFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
        boolean inDungeonNow = DungeonState.isInDungeon();
        if (inDungeonNow && !wasInDungeon) {
            usedThisRun.clear();
            receivedMarkers.clear();
            LOGGER.info("[Posmsg] New dungeon run detected - cleared once-per-run usage and received markers");
        }
        wasInDungeon = inDungeonNow;
    }

    private static void onChatMessage(Component message) {
        String raw = message.getString();
        if (!raw.contains(TAG)) {
            return;
        }
        Matcher m = RECEIVE_PATTERN.matcher(raw);
        if (!m.find()) {
            LOGGER.info("[Posmsg] Line contained {} but didn't match RECEIVE_PATTERN: \"{}\"", TAG, raw);
            return;
        }
        try {
            String name = m.group(1);
            double x = parseNumber(m.group(2));
            double y = parseNumber(m.group(3));
            double z = parseNumber(m.group(4));
            double radius = parseNumber(m.group(5));
            // If this exact name is one of my own configured entries, my own copy of it (with my own
            // toggles/color) already renders locally - no need for a second, differently-styled marker.
            if (PosmsgConfig.getInstance().byName(name) != null) {
                LOGGER.info("[Posmsg] Received \"{}\" but it matches a local entry name - not adding a marker", name);
                return;
            }
            receivedMarkers.put(name, new ReceivedMarker(name, x, y, z, radius, System.currentTimeMillis()));
            LOGGER.info("[Posmsg] Received marker \"{}\" at ({}, {}, {}) r={} (inDungeon={})",
                    name, x, y, z, radius, DungeonState.isInDungeon());
        } catch (NumberFormatException e) {
            LOGGER.info("[Posmsg] Failed to parse numbers in \"{}\": {}", raw, e.getMessage());
        }
    }

    /** Locale-independent number parse (Double.parseDouble always expects '.'); also accepts a comma
     *  decimal separator from older comma-locale senders. */
    private static double parseNumber(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    /** Sends the given preset/entry to Party Chat, honoring the once-per-run gate. Silently no-ops
     *  (with a HUD warning) if the entry has never been given real coordinates. */
    public static void send(PosmsgEntry entry) {
        if (!entry.configured) {
            ModOverlayMessage.show("§c[Posmsg] \"" + entry.name + "\" has no coordinates set yet - use \"Set to my position\" first.", 3000);
            return;
        }
        if (entry.onceOnlyPerRun && usedThisRun.contains(entry.id)) {
            ModOverlayMessage.show("§e[Posmsg] \"" + entry.name + "\" already sent this run.", 2500);
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        String payload = String.format(Locale.US, "%s%s|%.2f|%.2f|%.2f|%.2f",
                TAG, entry.name, entry.x, entry.y, entry.z, entry.radius);
        client.player.connection.sendCommand("pc " + payload);
        usedThisRun.add(entry.id);
        LOGGER.info("[Posmsg] Sent: {}", payload);
    }

    /** For {@code /killer560 posmsg add <message> <x> <y> <z> <radius>} - creates and immediately
     *  sends a brand-new, always-reusable custom waypoint. */
    public static PosmsgEntry addAndSend(String message, double x, double y, double z, double radius) {
        PosmsgConfig cfg = PosmsgConfig.getInstance();
        PosmsgEntry e = cfg.addNew();
        e.name = message;
        e.x = x;
        e.y = y;
        e.z = z;
        e.radius = radius;
        e.configured = true;
        cfg.save();
        send(e);
        return e;
    }

    public static Map<String, ReceivedMarker> receivedMarkers() {
        return receivedMarkers;
    }

    /** A waypoint received from someone else's chat message this run - always shown with default
     *  styling (the sender's own toggles/color aren't part of the wire format), expires only when a
     *  new run starts (see {@link #tick()}). */
    public record ReceivedMarker(String name, double x, double y, double z, double radius, long receivedAtMs) {
    }
}
