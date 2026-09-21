package com.killer560.hub.relay;

import com.killer560.hub.leapmenu.PartyTracker;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Which relay room this client belongs in, for whichever of Mod Chat's two room choices -
 * {@link Mode#PARTY} or {@link Mode#LOBBY} - killer560 picked. Both are derived, never assigned: every
 * member computes the same string from their own client state without anyone coordinating, and the relay
 * never learns who is in the party or which Hypixel instance a hash came from - all it ever sees is a hash.
 * <p>
 * <b>Rebuilt 2026-09-20.</b> killer560: <i>"Poor the global mod chat. Do not have a global option only have
 * a lobby option or a party option."</i> There is deliberately no third "everyone" choice and no fallback
 * between the two: {@link #current} returns {@code null} when the chosen mode's room can't be computed yet
 * (alone, for Party; Hypixel's instance id not known yet, for Lobby) and callers must stay disconnected
 * rather than guess something wider - see {@link RelayClient.State#NO_ROOM}. Silently widening the room is
 * the exact bug this feature replaced (Mod Chat used to leak into real, public party chat).
 * <p>
 * Truncated to 32 hex characters purely to stay inside the relay's 64-character room-name limit
 * ({@code safeRoom} in the Worker's {@code index.ts}, which also restricts the charset to
 * {@code [a-z0-9:_-]} - {@code "party:"}/{@code "lobby:"} + hex fits both).
 */
public final class RelayRoom {

    static final int HEX_LENGTH = 32;

    private RelayRoom() {
    }

    /** The two room choices left once the global option was removed - see the class doc. */
    public enum Mode {
        LOBBY("Lobby"),
        PARTY("Party");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /**
     * @return the room for {@code mode}, or {@code null} if it can't be computed right now - never a wider
     *         fallback room (see the class doc). Reads {@link PartyTracker} / {@link HypixelLocation} only;
     *         call it from the client thread.
     */
    public static String current(Mode mode) {
        return mode == Mode.PARTY ? partyRoom() : HypixelLocation.lobbyRoom();
    }

    /** {@code party:<hash>} for the current party, or {@code null} when you are alone / it isn't known yet. */
    private static String partyRoom() {
        Minecraft client = Minecraft.getInstance();
        String self = client == null || client.player == null ? null : client.player.getGameProfile().name();
        List<String> teammates = PartyTracker.teammates();
        if (self == null || teammates.isEmpty()) {
            return null;
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
            return null;
        }
        String hash = sha256Hex(String.join(",", names));
        return hash == null ? null : "party:" + hash.substring(0, HEX_LENGTH);
    }

    /** Package-visible so {@link HypixelLocation} hashes the Hypixel instance id the same way. */
    static String sha256Hex(String input) {
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

    /** "Party (4)" / "Lobby" - what the settings tab shows instead of a meaningless hash. Only meant to be
     *  called with a non-null room; the tab explains a null one itself (not in a party / instance not known
     *  yet / p3sim), since only it knows which mode is selected. */
    public static String describe(String room) {
        if (room == null || room.isEmpty()) {
            return "-";
        }
        if (room.startsWith("party:")) {
            return "Party (" + (PartyTracker.teammates().size() + 1) + ")";
        }
        if (room.startsWith("lobby:")) {
            return "Lobby";
        }
        return room;
    }
}
