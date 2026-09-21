package com.killer560.hub.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * NoammAddons' party-socket wire format - JSON text frames with a {@code "type"} discriminator (spec section
 * 2.4) - plus its HTTP token request (spec 2.2). Pure: no Minecraft classes.
 */
public final class NoammCodec {

    /** Spec 2.1: {@code WebSocket.kt:49}. */
    public static final String WS_BASE = "wss://ws.noamm.org";
    /** Spec 2.2 step 7: {@code AUTH_URL = "$BASE_URL/hypixel/auth"} ({@code ApiAuth.kt:29}, {@code NoammAPI.kt:12}). */
    public static final String AUTH_URL = "https://api.noamm.org/hypixel/auth";

    /** Spec 2.4 {@code PacketRegistry.kt:10-19} type strings, and the three ad-hoc map packets. */
    static final String T_DUNGEON_START = "dungeon_start";
    static final String T_DUNGEON_END = "dungeon_end";
    static final String T_RESET = "reset";
    static final String T_CHAT = "chat";
    static final String T_DOOR = "dungeondoor";
    static final String T_MIMIC = "dungeonmimic";
    static final String T_PRINCE = "dungeonprince";
    static final String T_BAT = "dungeonbat";
    static final String T_ROOM = "dungeonroom";
    static final String T_DRAGON = "m7dragon";
    static final String T_ROOM_SECRETS = "dungeonroomsecrets";
    static final String T_SOCKET_INFO = "socket_info";

    /**
     * NoammAddons' scan grid: {@code startX = -185}, {@code roomSize = 32} ({@code DungeonScanner.kt:29,32}),
     * cell world position {@code startX + col * (roomSize shr 1)} ({@code DungeonScanner.kt:74-75}) - the same
     * numbers as our own {@code LiveMapFeature.START_X/START_Z/HALF_ROOM} (-185, -185, 16), so a NoammAddons
     * col/row IS our col/row and x/z must agree with it exactly. Used to reject a malformed or mirrored cell.
     */
    static final int GRID_START = -185;
    static final int HALF_ROOM = 16;
    static final int GRID = 11;

    private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    /** Same shape as the server code NoammAddons itself sends ({@code \w{0,6}} from its lobby regex, never
     *  blank here). */
    private static final Pattern SERVER_CODE = Pattern.compile("^\\w{1,16}$");
    private static final Pattern FLOOR = Pattern.compile("^(?:E|[FM][1-7])$");
    private static final int MAX_SECRETS = 999;
    private static final int MAX_TEXT = 8_192;

    private NoammCodec() {
    }

    // ------------------------------------------------------------------ auth (HTTP, before the socket)

    /** Spec 2.2 step 4 ({@code ApiAuth.kt:101-104}): a fresh random UUID packed as 16 big-endian bytes. */
    public static byte[] nonceBytes(UUID nonce) {
        return ByteBuffer.allocate(16)
                .putLong(nonce.getMostSignificantBits())
                .putLong(nonce.getLeastSignificantBits())
                .array();
    }

    /**
     * Spec 2.2 step 7 body, key names exactly as NoammAddons' Gson data classes ({@code ApiAuth.kt:118-121}).
     * Everything here is public (the account UUID, the Mojang-issued PUBLIC key and Mojang's signature over it,
     * and our signature over a random nonce) - no access token, no private key, no password.
     * <p>
     * {@code mod}/{@code modVersion}: NoammAddons fills these from its own build constants
     * ({@code com.github.noamm9:NoammAddons_cheat} in the shipped jar, spec 2.6). We identify honestly as this
     * mod; see the staging notes for what happens if the server only accepts its own id.
     */
    public static String authBody(String playerUuid, String publicKeyB64, String publicKeySignatureB64,
                                  long expiresAtMs, byte[] nonce, byte[] signature,
                                  String mod, String minecraftVersion, String modVersion) {
        JsonObject keyPair = new JsonObject();
        keyPair.addProperty("uuid", playerUuid);
        keyPair.addProperty("publicKey", publicKeyB64);
        keyPair.addProperty("publicKeySignature", publicKeySignatureB64);
        keyPair.addProperty("expiresAt", expiresAtMs);
        JsonObject signedData = new JsonObject();
        signedData.addProperty("original", Base64.getEncoder().encodeToString(nonce));
        signedData.addProperty("signed", Base64.getEncoder().encodeToString(signature));
        JsonObject body = new JsonObject();
        body.add("keyPair", keyPair);
        body.add("signedData", signedData);
        body.addProperty("mod", mod);
        body.addProperty("minecraftVersion", minecraftVersion);
        body.addProperty("modVersion", modVersion);
        return body.toString();
    }

    /** Spec 2.2 step 8: {@code TokenResponse(token, issuedAt, expiresAt)} ({@code ApiAuth.kt:85-86,121}). */
    public record Token(String token, long issuedAtMs, long expiresAtMs) {
        /** Spec 2.2 step 8: NoammAddons refreshes {@code (expiresAt - issuedAt) - 5 min} after issue. */
        public boolean needsRefresh(long nowMs) {
            return nowMs >= expiresAtMs - 5 * 60_000L;
        }
    }

    /** @return the token, or {@code null} if the body is not a well-formed token response. */
    public static Token parseToken(String body) {
        JsonObject obj = object(body);
        if (obj == null) {
            return null;
        }
        String token = string(obj, "token");
        Long issued = longField(obj, "issuedAt");
        Long expires = longField(obj, "expiresAt");
        if (token == null || token.isBlank() || token.length() > 4096 || issued == null || expires == null
                || expires <= issued) {
            return null;
        }
        return new Token(token, issued, expires);
    }

    /** Spec 2.1/2.7 item 3: {@code wss://ws.noamm.org?name=<ign>&token=<token>} (Ktor {@code parameter(...)}),
     *  written with an explicit "/" path, which is the request-target Ktor sends for an empty path anyway. */
    public static URI socketUri(String ign, String token) {
        return URI.create(WS_BASE + "/?name=" + URLEncoder.encode(ign, StandardCharsets.UTF_8)
                + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ encode (client -> server)

    /**
     * Spec 2.3 "Join" message ({@code C2SPacketDungeonStart.kt:5-9}, sent {@code FEAT_WebSocket.kt:74-82}):
     * {@code members} is every dungeon teammate INCLUDING the sender ({@code dungeonTeammates}, not
     * {@code ...NoSelf}); {@code entrance} is the entrance room's first grid cell as {@code (column, row)},
     * serialised by Gson as {@code {"first": column, "second": row}}.
     */
    public static String dungeonStart(String serverId, String floor, List<String> members, int entranceCol, int entranceRow) {
        if (serverId == null || !SERVER_CODE.matcher(serverId).matches() || floor == null
                || !FLOOR.matcher(floor).matches() || members == null || members.isEmpty() || members.size() > 5
                || !validCell(entranceCol, entranceRow)) {
            return null;
        }
        JsonArray arr = new JsonArray();
        for (String m : members) {
            if (m == null || !IGN.matcher(m).matches()) {
                return null;
            }
            arr.add(m);
        }
        JsonObject entrance = new JsonObject();
        entrance.addProperty("first", entranceCol);
        entrance.addProperty("second", entranceRow);
        JsonObject o = new JsonObject();
        o.addProperty("serverId", serverId);
        o.addProperty("floor", floor);
        o.add("members", arr);
        o.add("entrance", entrance);
        o.addProperty("type", T_DUNGEON_START);
        return o.toString();
    }

    /** Spec 2.4 ad-hoc packets: bare {@code {"type":"dungeon_end"}} / {@code {"type":"reset"}}. */
    public static String bare(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o.toString();
    }

    public static String dungeonEnd() {
        return bare(T_DUNGEON_END);
    }

    public static String reset() {
        return bare(T_RESET);
    }

    /** Spec 2.4 {@code dungeondoor}: {@code x, z, col, row, doorType}. {@code ourType} is our lower-case name. */
    public static String door(int x, int z, int col, int row, String ourType) {
        String doorType = BridgeTables.noammDoorType(ourType);
        if (doorType == null || !cellMatchesWorld(col, row, x, z)) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("col", col);
        o.addProperty("row", row);
        o.addProperty("doorType", doorType);
        o.addProperty("type", T_DOOR);
        return o.toString();
    }

    /** Spec 2.4 {@code dungeonroom}: one message PER CELL of a room (NoammAddons' scanner sends each scanned
     *  tile, {@code DungeonScanner.kt:93}), {@code isSeparator} for the connector cells inside a big room. */
    public static String room(String name, int x, int z, int col, int row, boolean isSeparator) {
        if (BridgeTables.room(name) == null || !cellMatchesWorld(col, row, x, z)) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("col", col);
        o.addProperty("row", row);
        o.addProperty("isSeparator", isSeparator);
        o.addProperty("type", T_ROOM);
        return o.toString();
    }

    /** Spec 2.4: the three field-less Kotlin {@code object} packets. */
    public static String mimic() {
        return bare(T_MIMIC);
    }

    public static String prince() {
        return bare(T_PRINCE);
    }

    public static String bat() {
        return bare(T_BAT);
    }

    /** Spec 2.4 {@code m7dragon}: {@code event} SPAWN|DEATH, {@code dragon} Red|Orange|Green|Blue|Purple. */
    public static String dragon(String event, String ourColour) {
        String dragon = BridgeTables.noammDragon(ourColour);
        if (dragon == null || !(BridgeTables.NOAMM_DRAGON_SPAWN.equals(event) || BridgeTables.NOAMM_DRAGON_DEATH.equals(event))) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("event", event);
        o.addProperty("dragon", dragon);
        o.addProperty("type", T_DRAGON);
        return o.toString();
    }

    /** Spec 2.4 {@code dungeonroomsecrets}: {@code room} (exact room name), {@code secrets}. */
    public static String roomSecrets(String room, int secrets) {
        BridgeTables.Room known = BridgeTables.room(room);
        if (known == null || secrets < 0 || secrets > known.secrets()) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("room", room);
        o.addProperty("secrets", secrets);
        o.addProperty("type", T_ROOM_SECRETS);
        return o.toString();
    }

    // ------------------------------------------------------------------ decode (server -> client)

    public sealed interface Message permits Door, Room, Flag, Dragon, RoomSecrets, SocketInfo, Chat {
    }

    public record Door(int x, int z, int col, int row, String ourType) implements Message {
    }

    public record Room(String name, int x, int z, int col, int row, boolean isSeparator) implements Message {
    }

    /** {@code which} is one of {@code mimic}, {@code prince}, {@code bat}. */
    public record Flag(String which) implements Message {
    }

    /** {@code spawn} true for SPAWN, false for DEATH; {@code ourColour} lower-case. */
    public record Dragon(boolean spawn, String ourColour) implements Message {
    }

    public record RoomSecrets(String room, int secrets) implements Message {
    }

    public record SocketInfo(int connectedUsers, int usersInLobby, String lobby) implements Message {
    }

    /** Decoded so the harness can prove it parses, but never shown: it is free text from anyone on that
     *  server, with no dungeon meaning. */
    public record Chat(String message) implements Message {
    }

    /** @return the validated message, or {@code null} for anything unknown, malformed or out of range. */
    public static Message decode(String text) {
        if (text == null || text.length() > MAX_TEXT) {
            return null;
        }
        JsonObject o = object(text);
        if (o == null) {
            return null;
        }
        String type = string(o, "type");
        if (type == null) {
            return null;
        }
        switch (type) {
            case T_DOOR -> {
                Integer x = intField(o, "x"), z = intField(o, "z"), col = intField(o, "col"), row = intField(o, "row");
                String ourType = BridgeTables.ourDoorType(string(o, "doorType"));
                if (x == null || z == null || col == null || row == null || ourType == null
                        || !cellMatchesWorld(col, row, x, z)) {
                    return null;
                }
                return new Door(x, z, col, row, ourType);
            }
            case T_ROOM -> {
                String name = string(o, "name");
                Integer x = intField(o, "x"), z = intField(o, "z"), col = intField(o, "col"), row = intField(o, "row");
                Boolean sep = boolField(o, "isSeparator");
                if (name == null || BridgeTables.room(name) == null || x == null || z == null || col == null
                        || row == null || sep == null || !cellMatchesWorld(col, row, x, z)) {
                    return null;
                }
                return new Room(name, x, z, col, row, sep);
            }
            case T_MIMIC -> {
                return new Flag("mimic");
            }
            case T_PRINCE -> {
                return new Flag("prince");
            }
            case T_BAT -> {
                return new Flag("bat");
            }
            case T_DRAGON -> {
                String event = string(o, "event");
                String colour = BridgeTables.ourDragon(string(o, "dragon"));
                if (colour == null || event == null) {
                    return null;
                }
                if (BridgeTables.NOAMM_DRAGON_SPAWN.equals(event)) {
                    return new Dragon(true, colour);
                }
                if (BridgeTables.NOAMM_DRAGON_DEATH.equals(event)) {
                    return new Dragon(false, colour);
                }
                return null;
            }
            case T_ROOM_SECRETS -> {
                String room = string(o, "room");
                Integer secrets = intField(o, "secrets");
                BridgeTables.Room known = BridgeTables.room(room);
                if (known == null || secrets == null || secrets < 0 || secrets > known.secrets()) {
                    return null;
                }
                return new RoomSecrets(room, secrets);
            }
            case T_SOCKET_INFO -> {
                Integer users = intField(o, "connectedUsers");
                Integer lobby = intField(o, "usersInLobby");
                String hash = o.has("lobby") ? string(o, "lobby") : "";
                if (users == null || lobby == null || users < 0 || lobby < 0 || hash == null || hash.length() > 128) {
                    return null;
                }
                return new SocketInfo(users, lobby, hash);
            }
            case T_CHAT -> {
                String message = string(o, "message");
                return message == null || message.length() > 512 ? null : new Chat(message);
            }
            default -> {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    static boolean validCell(int col, int row) {
        return col >= 0 && col < GRID && row >= 0 && row < GRID;
    }

    /** x/z must be exactly the cell's NoammAddons world position - see {@link #GRID_START}. */
    static boolean cellMatchesWorld(int col, int row, int x, int z) {
        return validCell(col, row) && x == GRID_START + col * HALF_ROOM && z == GRID_START + row * HALF_ROOM;
    }

    private static JsonObject object(String text) {
        try {
            JsonElement el = JsonParser.parseString(text);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(JsonObject o, String field) {
        JsonElement el = o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            return null;
        }
        return el.getAsString();
    }

    private static Integer intField(JsonObject o, String field) {
        JsonElement el = o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double d = el.getAsDouble();
            if (d != Math.rint(d) || Math.abs(d) > 1_000_000) {
                return null;
            }
            return (int) d;
        } catch (Exception e) {
            return null;
        }
    }

    private static Long longField(JsonObject o, String field) {
        JsonElement el = o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return el.getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private static Boolean boolField(JsonObject o, String field) {
        JsonElement el = o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return el.getAsBoolean();
    }
}
