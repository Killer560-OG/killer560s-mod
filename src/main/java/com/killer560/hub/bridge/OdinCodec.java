package com.killer560.hub.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Odin's only socket message - Melody terminal progress (spec section 3.4) - plus the shared "Hypixel server
 * code" scrape both Odin and NoammAddons group by. Pure: no Minecraft classes.
 */
public final class OdinCodec {

    /** Spec 3.1: the hidden {@code StringSetting} default in {@code ClickGUIModule.<clinit>}; the full URL is
     *  {@code getWebSocketUrl() + lobbyId} with no separator of its own. */
    public static final String WS_BASE = "wss://ws.odtheking.com/";

    /** Spec 3.3: {@code p3StartRegex} (constant pool #690) - Odin connects on this line. */
    public static final Pattern P3_START = Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");
    /** Spec 3.3: {@code coreRegex} (constant pool #686) - Odin disconnects on this line. (The spec calls it
     *  "the start of a fresh Catacombs run"; on Hypixel it is the line when the P3 terminals are done and the
     *  Goldor core opens - either way it is where Odin closes, and where we close too.) */
    public static final Pattern CORE_OPEN = Pattern.compile("^The Core entrance is opening!$");

    /**
     * Spec 2.3 / 3.1: {@code \d\d/\d\d/\d\d (\w{0,6}) *}, byte-identical in NoammAddons
     * ({@code LocationUtils.kt:140}, applied to each scoreboard team's prefix + suffix, colour codes stripped,
     * {@code LocationUtils.kt:54-56}) and Odin ({@code LocationUtils} constant pool #93). The group is the
     * Hypixel server code shown on the sidebar date line, e.g. {@code m104BN}.
     */
    private static final Pattern SERVER_CODE = Pattern.compile("\\d\\d/\\d\\d/\\d\\d (\\w{0,6}) *");
    private static final Pattern IGN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern LOBBY = Pattern.compile("^\\w{1,6}$");

    private OdinCodec() {
    }

    /** @return the server code in this (colour-stripped) sidebar text, or {@code null} if absent/blank. */
    public static String serverCode(String strippedTeamText) {
        if (strippedTeamText == null) {
            return null;
        }
        Matcher m = SERVER_CODE.matcher(strippedTeamText);
        if (!m.find()) {
            return null;
        }
        String code = m.group(1);
        return code == null || code.isEmpty() ? null : code;
    }

    /** Spec 3.1: {@code wss://ws.odtheking.com/<lobbyId>}; {@code null} for a lobby id that is not a plain
     *  server code (never put arbitrary text in a URL path). */
    public static URI socketUri(String lobbyId) {
        if (lobbyId == null || !LOBBY.matcher(lobbyId).matches()) {
            return null;
        }
        return URI.create(WS_BASE + lobbyId);
    }

    /** Spec 3.4: {@code {"user": <ign>, "type": <int>, "slot": <int>}}, Gson field order user/type/slot.
     *  Encoded for the test harness; the bridge sends nothing to Odin (see {@code OdinAdapter}'s class doc). */
    public static String update(String ign, int type, int slot) {
        if (ign == null || !IGN.matcher(ign).matches() || !validPayload(type, slot)) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("user", ign);
        o.addProperty("type", type);
        o.addProperty("slot", slot);
        return o.toString();
    }

    /** A validated melody update. For {@link BridgeTables#MELODY_TYPE_REMOVE} {@code slot} is meaningless. */
    public record Update(String ign, int type, int slot) {
    }

    /** @return the update, or {@code null} for malformed JSON, a non-IGN {@code user}, types 3/4 (no-ops in
     *  Odin itself), unknown types, or a slot outside the range {@link BridgeTables} documents for its type. */
    public static Update decode(String text) {
        if (text == null || text.length() > 512) {
            return null;
        }
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (el == null || !el.isJsonObject()) {
                return null;
            }
            o = el.getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
        String user = str(o, "user");
        Integer type = integer(o, "type");
        Integer slot = integer(o, "slot");
        if (user == null || !IGN.matcher(user).matches() || type == null) {
            return null;
        }
        if (type == BridgeTables.MELODY_TYPE_REMOVE) {
            return new Update(user, type, 0);
        }
        if (slot == null || !validPayload(type, slot)) {
            return null;
        }
        return new Update(user, type, slot);
    }

    private static boolean validPayload(int type, int slot) {
        return switch (type) {
            case BridgeTables.MELODY_TYPE_REMOVE -> true;
            case BridgeTables.MELODY_TYPE_CLAY -> slot >= BridgeTables.MELODY_MIN_CLAY_ROW && slot <= BridgeTables.MELODY_MAX_CLAY_ROW;
            case BridgeTables.MELODY_TYPE_PURPLE, BridgeTables.MELODY_TYPE_PANE ->
                    slot >= BridgeTables.MELODY_MIN_COLUMN && slot <= BridgeTables.MELODY_MAX_COLUMN;
            default -> false;
        };
    }

    private static String str(JsonObject o, String field) {
        JsonElement el = o.get(field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    private static Integer integer(JsonObject o, String field) {
        JsonElement el = o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double d = el.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) <= 1_000 ? (int) d : null;
        } catch (Exception e) {
            return null;
        }
    }
}
