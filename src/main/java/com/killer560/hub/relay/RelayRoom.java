package com.killer560.hub.relay;

import com.killer560.hub.leapmenu.PartyTracker;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Which relay room this client belongs in.
 * <p>
 * A party's room name is derived, not assigned: SHA-256 over the sorted lower-case IGNs of everyone in the
 * party, hex, truncated. Every member computes the same string from their own {@link PartyTracker} view without
 * anyone coordinating, and the relay never learns who is in the party - all it ever sees is a hash. Outside a
 * party there is nothing to derive, so the client falls back to {@link #GLOBAL}.
 * <p>
 * Truncated to 32 hex characters purely to stay inside the relay's 64-character room-name limit
 * ({@code safeRoom} in the Worker's {@code index.ts}, which also restricts the charset to
 * {@code [a-z0-9:_-]} - {@code "party:" + hex} fits both).
 */
public final class RelayRoom {

    public static final String GLOBAL = "global";

    private static final int HEX_LENGTH = 32;

    private RelayRoom() {
    }

    /**
     * @return {@code party:<hash>} for the current party, or {@link #GLOBAL} when you are alone / the party
     *         isn't known yet. Reads {@link PartyTracker} only; call it from the client thread.
     */
    public static String current() {
        Minecraft client = Minecraft.getInstance();
        String self = client == null || client.player == null ? null : client.player.getGameProfile().name();
        List<String> teammates = PartyTracker.teammates();
        if (self == null || teammates.isEmpty()) {
            return GLOBAL;
        }
        // Sorted + lower-cased + de-duplicated so every member hashes byte-identical input regardless of the
        // order Hypixel listed them in or how anyone's client capitalised a name.
        TreeSet<String> names = new TreeSet<>();
        names.add(self.toLowerCase(Locale.ROOT));
        for (String teammate : teammates) {
            if (teammate != null && !teammate.isBlank()) {
                names.add(teammate.toLowerCase(Locale.ROOT));
            }
        }
        if (names.size() < 2) {
            return GLOBAL;
        }
        String hash = sha256Hex(String.join(",", names));
        return hash == null ? GLOBAL : "party:" + hash.substring(0, HEX_LENGTH);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** "Party (4)" / "Global" - what the settings tab shows instead of a meaningless hash. */
    public static String describe(String room) {
        if (room == null || room.equals(GLOBAL)) {
            return "Global";
        }
        return room.startsWith("party:") ? "Party (" + (PartyTracker.teammates().size() + 1) + ")" : room;
    }
}
