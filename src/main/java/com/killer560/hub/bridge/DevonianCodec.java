package com.killer560.hub.bridge;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Devonian's party-socket wire format - plain bracketed text frames, not JSON (spec section 1.4). Pure: no
 * Minecraft classes, so the codec test harness runs it outside the game.
 */
public final class DevonianCodec {

    /** Spec 1.1: {@code WebsocketClient.kt:182} - plain {@code ws://}, no TLS, despite the host name. */
    public static final String URL = "ws://wss.docilelm.top/";
    /** Spec 1.2 step 3: {@code KEY_NAME = "devonianwebsocket"} ({@code WebsocketClient.kt:55}). */
    static final String KEY_NAME = "devonianwebsocket";

    /** Spec 1.3/1.4: {@code WebsocketClient.kt:166,169}. */
    public static final String PARTY_LEAVE = "PartyLeave";
    /** Spec 1.3/1.4: {@code WebsocketClient.kt:139} - sent before intentionally dropping the socket. */
    public static final String DISCONNECT = "Disconnect";
    /** Spec 1.4: the bare server text {@code "3002"} ({@code WebsocketClient.kt:153}) - "re-announce your party". */
    public static final String REANNOUNCE = "3002";

    /** Spec 1.4: {@code DungeonScanner.kt:61} - Devonian's own receive regex, byte-identical. */
    private static final Pattern ROOM_SECRETS = Pattern.compile("^RoomSecrets\\[(\\d+)/(\\d+), (\\d+)]$");
    /** Spec 1.4: {@code TeamSecretsStats.kt:29}. */
    private static final Pattern SECRET_TRACKER = Pattern.compile("^SecretTracker\\[(\\w{1,16}), (\\d+)]$");
    private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private static final int MAX_SECRETS = 999;
    private static final int MAX_ROOM_ID = 9_999;

    private DevonianCodec() {
    }

    // ------------------------------------------------------------------ auth

    /**
     * Spec 1.2 step 3 ({@code WebsocketClient.kt:208-214}): {@code BigInteger(1, SHA1(UTF-8("devonianwebsocket" +
     * r))).toString(16)} - UNSIGNED (the two-argument constructor), unlike vanilla's signed server hash, and not
     * zero-padded. This is the {@code serverId} passed to Mojang's {@code joinServer}; only Mojang sees it.
     */
    public static String serverIdHash(String r) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest((KEY_NAME + r).getBytes(StandardCharsets.UTF_8));
            return new BigInteger(1, digest).toString(16);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** Spec 1.2 step 5 ({@code WebsocketClient.kt:187}): the RAW random {@code r}, not its hash. {@code null}
     *  if the name is not a plain Minecraft name (we never send anything but the local account's own). */
    public static String authenticate(String ign, String r) {
        if (ign == null || !IGN.matcher(ign).matches() || r == null || r.isEmpty()) {
            return null;
        }
        return "Authenticate[" + ign + ", " + r + "]";
    }

    // ------------------------------------------------------------------ party

    /**
     * Spec 1.3: {@code Party.partyHash = members.keys.sumOf { it.hashCode() }} ({@code Party.kt:72}), members =
     * the Hypixel party's member UUIDs INCLUDING the local player. Plain int addition, so it wraps and is
     * order-independent - exactly {@link UUID#hashCode()} summed.
     */
    public static int partyHash(Collection<UUID> members) {
        int sum = 0;
        for (UUID id : members) {
            sum += id.hashCode();
        }
        return sum;
    }

    /** Spec 1.3 ({@code WebsocketClient.kt:170}). */
    public static String party(int partyHash) {
        return "Party[" + partyHash + "]";
    }

    // ------------------------------------------------------------------ data

    /** Spec 1.4 ({@code DungeonScanner.kt:238}). {@code null} if out of range - Devonian's own receiver would
     *  drop it anyway ({@code current !in 0..room.totalSecrets}). */
    public static String roomSecrets(int found, int total, int devonianRoomId) {
        if (found < 0 || total < 0 || found > total || total > MAX_SECRETS
                || devonianRoomId < 0 || devonianRoomId > MAX_ROOM_ID) {
            return null;
        }
        return "RoomSecrets[" + found + "/" + total + ", " + devonianRoomId + "]";
    }

    /** Spec 1.4 ({@code TeamSecretsStats.kt:44}). Encoded for completeness and the test harness; the bridge
     *  never sends it because this mod has no SELF "secrets I personally found" fact (see
     *  {@code PartyDataFeature}'s class doc). */
    public static String secretTracker(String ign, int secrets) {
        if (ign == null || !IGN.matcher(ign).matches() || secrets < 0 || secrets > MAX_SECRETS) {
            return null;
        }
        return "SecretTracker[" + ign + ", " + secrets + "]";
    }

    // ------------------------------------------------------------------ decode

    /** Everything Devonian's server can send that we understand. */
    public sealed interface Message permits Reannounce, RoomSecrets, SecretTracker {
    }

    public record Reannounce() implements Message {
    }

    public record RoomSecrets(int found, int total, int devonianRoomId) implements Message {
    }

    public record SecretTracker(String ign, int secrets) implements Message {
    }

    /** @return the parsed message, or {@code null} for anything unknown, malformed or out of range. */
    public static Message decode(String text) {
        if (text == null || text.length() > 256) {
            return null;
        }
        if (REANNOUNCE.equals(text)) {
            return new Reannounce();
        }
        Matcher m = ROOM_SECRETS.matcher(text);
        if (m.matches()) {
            int found = parse(m.group(1), MAX_SECRETS);
            int total = parse(m.group(2), MAX_SECRETS);
            int id = parse(m.group(3), MAX_ROOM_ID);
            if (found < 0 || total < 0 || id < 0 || found > total) {
                return null;
            }
            return new RoomSecrets(found, total, id);
        }
        m = SECRET_TRACKER.matcher(text);
        if (m.matches()) {
            int secrets = parse(m.group(2), MAX_SECRETS);
            if (secrets < 0 || !IGN.matcher(m.group(1)).matches()) {
                return null; // \w also matches non-ASCII letters; a real IGN never contains them
            }
            return new SecretTracker(m.group(1), secrets);
        }
        return null;
    }

    /** @return the value, or -1 if it has too many digits or exceeds {@code max}. */
    private static int parse(String digits, int max) {
        if (digits.length() > 6) {
            return -1;
        }
        int v = Integer.parseInt(digits);
        return v > max ? -1 : v;
    }
}
