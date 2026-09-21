package com.killer560.hub.partydata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.interop.PartyInteropState;
import com.killer560.hub.livemap.DungeonLayout;

import java.util.Locale;

/**
 * The wire format for {@code killer560s-mod-relay}'s {@code data} packet, spent on party dungeon facts -
 * documented in full, with worked examples, in {@code killer560s-mod-relay/PROTOCOL.md} so another mod's
 * developer (or a future cross-mod bridge) could implement a client against it without reading this file.
 * <p>
 * Every key is versioned ({@code dg.v1.*}) so a later, incompatible change ships as {@code dg.v2.*} instead
 * of breaking whoever already speaks v1. Values are small, flat JSON objects - no nesting, no arrays of
 * objects - so a strict validator ({@link #readFlag}, {@link #readCounter}, ...) can check every field's
 * type and range before anything touches {@link PartyInteropState}.
 * <p>
 * <b>What is deliberately NOT here:</b> player position, inventory, or chat. Only facts about the dungeon
 * layout and its score-relevant events - see {@link PartyDataFeature}'s class doc for the exact allow-list.
 */
final class PartyDataProtocol {

    // ------------------------------------------------------------------ keys

    static final String KEY_FLAG = "dg.v1.flag";
    static final String KEY_COUNTER = "dg.v1.counter";
    static final String KEY_ROOM_SECRETS = "dg.v1.roomSecrets";
    static final String KEY_ROOM = "dg.v1.room";
    static final String KEY_DOOR = "dg.v1.door";
    static final String KEY_DRAGON = "dg.v1.dragon";
    /** Per-player secret total (Devonian's {@code SecretTracker[name, n]}). Documented for forward
     *  compatibility; nothing in this build PUBLISHES it yet - see {@link PartyDataFeature}'s class doc for
     *  why (no existing SELF-derived "secrets I personally opened" signal to relay honestly). */
    static final String KEY_PLAYER_SECRETS = "dg.v1.playerSecrets";

    private static final int MAX_COUNT = 999;
    private static final int MAX_COORD = 20_000;

    private PartyDataProtocol() {
    }

    // ------------------------------------------------------------------ flag

    static JsonObject flagPayload(String flagWireName) {
        JsonObject o = new JsonObject();
        o.addProperty("flag", flagWireName);
        return o;
    }

    /** @return the matching {@link PartyInteropState.Flag}, or null if unknown/malformed. */
    static PartyInteropState.Flag readFlag(JsonElement value) {
        String flag = enumString(value, "flag");
        if (flag == null) {
            return null;
        }
        return switch (flag) {
            case "mimic" -> PartyInteropState.Flag.MIMIC_KILLED;
            case "prince" -> PartyInteropState.Flag.PRINCE_KILLED;
            case "bat" -> PartyInteropState.Flag.BAT_KILLED;
            case "blood_opened" -> PartyInteropState.Flag.BLOOD_OPENED;
            case "blood_done" -> PartyInteropState.Flag.BLOOD_DONE;
            default -> null;
        };
    }

    static String wireName(PartyInteropState.Flag flag) {
        return switch (flag) {
            case MIMIC_KILLED -> "mimic";
            case PRINCE_KILLED -> "prince";
            case BAT_KILLED -> "bat";
            case BLOOD_OPENED -> "blood_opened";
            case BLOOD_DONE -> "blood_done";
        };
    }

    // ------------------------------------------------------------------ counter

    static JsonObject counterPayload(String counterWireName, int value) {
        JsonObject o = new JsonObject();
        o.addProperty("counter", counterWireName);
        o.addProperty("value", value);
        return o;
    }

    static PartyInteropState.Counter readCounterName(JsonElement value) {
        String counter = enumString(value, "counter");
        if (counter == null) {
            return null;
        }
        return switch (counter) {
            case "secrets" -> PartyInteropState.Counter.SECRETS_FOUND;
            case "crypts" -> PartyInteropState.Counter.CRYPTS;
            case "deaths" -> PartyInteropState.Counter.DEATHS;
            case "terminals" -> PartyInteropState.Counter.TERMINALS_DONE;
            case "devices" -> PartyInteropState.Counter.DEVICES_DONE;
            case "levers" -> PartyInteropState.Counter.LEVERS_DONE;
            default -> null;
        };
    }

    /** @return the counter's value in [0, {@link #MAX_COUNT}], or -1 if missing/out of range. */
    static int readCounterValue(JsonElement value) {
        Integer v = intField(value, "value");
        return v != null && v >= 0 && v <= MAX_COUNT ? v : -1;
    }

    static String wireName(PartyInteropState.Counter counter) {
        return switch (counter) {
            case SECRETS_FOUND -> "secrets";
            case CRYPTS -> "crypts";
            case DEATHS -> "deaths";
            case TERMINALS_DONE -> "terminals";
            case DEVICES_DONE -> "devices";
            case LEVERS_DONE -> "levers";
        };
    }

    // ------------------------------------------------------------------ room secrets

    static JsonObject roomSecretsPayload(String room, int found, int total) {
        JsonObject o = new JsonObject();
        o.addProperty("room", room);
        o.addProperty("found", found);
        o.addProperty("total", total);
        return o;
    }

    static String readRoomName(JsonElement value) {
        String room = string(value, "room");
        return room == null || room.isBlank() || room.length() > 64 ? null : room;
    }

    static int readFound(JsonElement value) {
        Integer v = intField(value, "found");
        return v != null && v >= 0 && v <= MAX_COUNT ? v : -1;
    }

    /** @return the room's max secrets, -1 for "unknown" (a valid, expected value here - see
     *  {@link PartyInteropState#offerRoomSecrets}), or -2 if the field itself is malformed. */
    static int readTotal(JsonElement value) {
        Integer v = intField(value, "total");
        if (v == null) {
            return -2;
        }
        return v == -1 || (v >= 0 && v <= MAX_COUNT) ? v : -2;
    }

    // ------------------------------------------------------------------ room / door (map intel)

    static JsonObject roomPayload(String name, int x, int z, int col, int row) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("col", col);
        o.addProperty("row", row);
        return o;
    }

    static JsonObject doorPayload(int x, int z, int col, int row, String type) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("z", z);
        o.addProperty("col", col);
        o.addProperty("row", row);
        o.addProperty("type", type);
        return o;
    }

    static String doorTypeWireName(int dungeonLayoutDoorType) {
        return switch (dungeonLayoutDoorType) {
            case DungeonLayout.DOOR_NORMAL -> "normal";
            case DungeonLayout.DOOR_WITHER -> "wither";
            case DungeonLayout.DOOR_BLOOD -> "blood";
            case DungeonLayout.DOOR_ENTRANCE -> "entrance";
            default -> null;
        };
    }

    /** Parsed, validated room/door fields, or null fields on any failure - checked individually by the
     *  caller so one bad field doesn't discard an otherwise-fine packet's other fields silently. */
    record RoomFields(String name, int x, int z, int col, int row) {
    }

    record DoorFields(int x, int z, int col, int row, String type) {
    }

    static RoomFields readRoom(JsonElement value) {
        String name = string(value, "name");
        Integer x = intField(value, "x");
        Integer z = intField(value, "z");
        Integer col = intField(value, "col");
        Integer row = intField(value, "row");
        if (name == null || name.isBlank() || name.length() > 64 || x == null || z == null || col == null
                || row == null || Math.abs(x) > MAX_COORD || Math.abs(z) > MAX_COORD
                || col < 0 || col >= DungeonLayout.GRID || row < 0 || row >= DungeonLayout.GRID) {
            return null;
        }
        return new RoomFields(name, x, z, col, row);
    }

    static DoorFields readDoor(JsonElement value) {
        Integer x = intField(value, "x");
        Integer z = intField(value, "z");
        Integer col = intField(value, "col");
        Integer row = intField(value, "row");
        String type = enumString(value, "type");
        if (x == null || z == null || col == null || row == null || type == null
                || Math.abs(x) > MAX_COORD || Math.abs(z) > MAX_COORD
                || col < 0 || col >= DungeonLayout.GRID || row < 0 || row >= DungeonLayout.GRID
                || !(type.equals("normal") || type.equals("wither") || type.equals("blood") || type.equals("entrance"))) {
            return null;
        }
        return new DoorFields(x, z, col, row, type);
    }

    // ------------------------------------------------------------------ dragon

    private static final String[] DRAGON_COLOURS = {"red", "orange", "green", "blue", "purple"};

    static JsonObject dragonPayload(String colourLower, String event) {
        JsonObject o = new JsonObject();
        o.addProperty("colour", colourLower);
        o.addProperty("event", event);
        return o;
    }

    static String readDragonColour(JsonElement value) {
        String colour = enumString(value, "colour");
        if (colour == null) {
            return null;
        }
        for (String known : DRAGON_COLOURS) {
            if (known.equals(colour)) {
                return known;
            }
        }
        return null;
    }

    static String readDragonEvent(JsonElement value) {
        String event = enumString(value, "event");
        return "spawning".equals(event) || "alive".equals(event) ? event : null;
    }

    // ------------------------------------------------------------------ player secrets

    static JsonObject playerSecretsPayload(int secrets) {
        JsonObject o = new JsonObject();
        o.addProperty("secrets", secrets);
        return o;
    }

    static int readPlayerSecrets(JsonElement value) {
        Integer v = intField(value, "secrets");
        return v != null && v >= 0 && v <= MAX_COUNT ? v : -1;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /** Enum-like fields (flag, counter, door type, colour, event) compare case-insensitively, so lower-case them. */
    private static String enumString(JsonElement value, String field) {
        String s = string(value, field);
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    /** Free text (room names) is returned as sent: lower-casing it made "Water Board" from a teammate a different
     *  room from our own "Water Board" and produced duplicate entries. */
    private static String string(JsonElement value, String field) {
        JsonObject obj = object(value);
        if (obj == null || !obj.has(field)) {
            return null;
        }
        JsonElement el = obj.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            return null;
        }
        try {
            return el.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intField(JsonElement value, String field) {
        JsonObject obj = object(value);
        if (obj == null || !obj.has(field)) {
            return null;
        }
        JsonElement el = obj.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double d = el.getAsDouble();
            int i = (int) d;
            return d == i ? i : null; // reject "2.5" - every field here is a whole number
        } catch (Exception e) {
            return null;
        }
    }
}
