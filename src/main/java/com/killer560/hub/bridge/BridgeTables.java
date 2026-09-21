package com.killer560.hub.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every identifier translation the Cross-Mod Bridge does, in one place, each with where it came from.
 * Pure data - no Minecraft classes - so the codec test harness can load it outside the game.
 * <p>
 * Spec references are to {@code wave/BRIDGE-PROTOCOLS.md} (the source-cited protocol write-up) unless a line
 * says otherwise. Where that spec was wrong or unfinished and the jar/source on disk was re-read to settle it,
 * the line says so.
 */
public final class BridgeTables {

    private BridgeTables() {
    }

    // ================================================================== rooms

    /**
     * One dungeon room. {@code name} is the exact key NoammAddons' {@code roomsByName} uses (spec 2.4
     * "Field-format notes": case- and exact-string-sensitive) - and, because {@code roomdatabase.RoomDatabase}
     * downloads NoammAddons' own {@code rooms-modern.json}, it is also exactly the name our own map scan puts in
     * {@code DungeonLayout.name(...)} and {@code PartyInteropState}'s room keys. So NoammAddons needs no
     * translation at all; only Devonian does.
     * <p>
     * {@code devonianId} is Devonian's {@code RoomData.roomID}. <b>The spec (1.4 "Field-format notes") calls this
     * a run-local scan index; it is not.</b> Re-read 2026-09-21: {@code DungeonScanner.kt:23-34} declares
     * {@code RoomData(..., val roomID: Int, ...)} deserialised from the static
     * {@code /assets/devonian/dungeons/rooms.json}, and {@code DungeonRoom.kt:48} copies it
     * ({@code roomID = data.roomID}). It is a fixed per-room id (0..145, 140 rooms, all unique), the same for
     * every Devonian client in every run - which is what makes this table possible.
     * <p>
     * Built by joining Devonian's {@code rooms.json} (source checkout, commit 871f5d5) with NoammAddons'
     * {@code rooms-modern.json} (as cached by this mod, 2026-09-14) on name: 128 names match exactly; 7 more
     * match once the zero-width space (U+200B) Devonian puts inside the name is removed; 4 were matched by
     * identical type + shape + secret count + crypt count because the names differ ("Silvers Sword" vs "Silver
     * Sword", "Pillars" vs "Rare Pillars", "Mini Rail Track" vs "Rail Track"; Devonian's single "Blaze" covers
     * both of NoammAddons' "Higher Blaze"/"Lower Blaze"). Devonian's "Bomb Defuse" (id 20) has no NoammAddons
     * counterpart and is deliberately absent - a message about it is dropped. Room core hashes were NOT usable
     * as a join key: the two mods hash room cores differently and no single room shares a core value. Secret
     * counts agree for every one of the 140 rows (checked). -1 = no Devonian id.
     */
    public record Room(String name, int secrets, int devonianId) {
    }

    private static Room row(String name, int secrets, int devonianId) {
        return new Room(name, secrets, devonianId);
    }

    /** Devonian id that maps to more than one of our room names - {@link #roomsForDevonianId} returns both and
     *  the caller must disambiguate from its own map scan, or drop the message. */
    public static final int DEVONIAN_BLAZE_ID = 10;

    private static final List<Room> ROOMS = List.of(
            row("Admin", 0, 105),
            row("Altar", 6, 134),
            row("Andesite", 2, 93),
            row("Archway", 3, 82),
            row("Arrow Trap", 1, 97),
            row("Atlas", 6, 34),
            row("Balcony", 4, 27),
            row("Banners", 1, 7),
            row("Basement", 1, 64),
            row("Beams", 2, 62),
            row("Big Red Flag", 2, 26),
            row("Black Flag", 3, 119),
            row("Blood", 0, 15),
            row("Blue Skulls", 1, 38),
            row("Boulder", 0, 83),
            row("Bridges", 6, 50),
            row("Buttons", 5, 88),
            row("Cage", 1, 32),
            row("Cages", 2, 25),
            row("Carpets", 1, 142),
            row("Cathedral", 8, 76),
            row("Catwalk", 6, 8),
            row("Cell", 1, 69),
            row("Chains", 2, 84),
            row("Chambers", 5, 121),
            row("Cobble Wall Pillar", 2, 87),
            row("Creeper Beams", 0, 51),
            row("Criss Cross", 1, 140),
            row("Crypt", 5, 78),
            row("Deathmite", 6, 37), // Devonian name has a zero-width space
            row("Default", 0, 68),
            row("Diagonal", 4, 58),
            row("Dino Site", 4, 16),
            row("Dip", 2, 63),
            row("Dome", 2, 30),
            row("Doors", 5, 122),
            row("Double Diamond", 3, 81),
            row("Dragon", 0, 35),
            row("Drop", 2, 115),
            row("Dueces", 3, 28),
            row("Duncan", 1, 9),
            row("End", 2, 99),
            row("Entrance", 0, 12),
            row("Fairy", 0, 5),
            row("Flags", 7, 23),
            row("Gold", 1, 3),
            row("Golden Oasis", 1, 118),
            row("Grand Library", 4, 91),
            row("Granite", 2, 111),
            row("Grass Ruin", 3, 110),
            row("Gravel", 6, 77),
            row("Hall", 0, 19),
            row("Hallway", 3, 61),
            row("Higher Blaze", 1, 10), // Devonian has ONE "Blaze" (id 10, both variants' cores) - see ambiguousDevonianId
            row("Ice Fill", 0, 47),
            row("Ice Path", 0, 39),
            row("Jumping Skulls", 1, 56),
            row("King Midas", 0, 57),
            row("Knight", 3, 90),
            row("Lava Pit", 3, 125),
            row("Lava Ravine", 6, 36),
            row("Layers", 8, 103),
            row("Leaves", 1, 113),
            row("Locked Away", 1, 94),
            row("Logs", 4, 107),
            row("Long Hall", 3, 41),
            row("Lots Of Floors", 3, 71),
            row("Lower Blaze", 1, 10), // Devonian has ONE "Blaze" (id 10, both variants' cores) - see ambiguousDevonianId
            row("Mage", 4, 112),
            row("Market", 5, 60),
            row("Melon", 7, 98),
            row("Mines", 10, 73),
            row("Mirror", 1, 70),
            row("Mossy", 4, 6),
            row("Multicolored", 1, 101), // Devonian name has a zero-width space
            row("Mural", 1, 59),
            row("Museum", 5, 53),
            row("Mushroom", 1, 48),
            row("New Trap", 3, 13),
            row("Old Trap", 4, 44),
            row("Overgrown", 3, 89), // Devonian name has a zero-width space
            row("Overgrown Chains", 2, 43),
            row("Painting", 2, 102),
            row("Pedestal", 5, 108),
            row("Perch", 2, 100),
            row("Pipes", 7, 131),
            row("Pirate", 6, 133),
            row("Pit", 5, 22),
            row("Pressure Plates", 6, 67),
            row("Prison Cell", 1, 109),
            row("Purple Flags", 5, 40),
            row("Quad Lava", 2, 54),
            row("Quartz Knight", 7, 66),
            row("Quiz", 0, 18),
            row("Raccoon", 4, 1),
            row("Rail Track", 3, 129), // matched by type+shape+secrets+crypts, Devonian name "Mini Rail Track"
            row("Rails", 9, 46),
            row("Rare Carpets", 1, 144),
            row("Rare Overgrown", 3, 49),
            row("Rare Pillars", 1, 116), // matched by type+shape+secrets+crypts, Devonian name "Pillars"
            row("Red Blue", 4, 42),
            row("Red Green", 3, 11),
            row("Redstone Crypt", 3, 143),
            row("Redstone Key", 3, 114),
            row("Redstone Warrior", 3, 21),
            row("Ritual", 3, 138),
            row("Sand Dragon", 1, 120),
            row("Sarcophagus", 3, 95), // Devonian name has a zero-width space
            row("Scaffolding", 2, 86), // Devonian name has a zero-width space
            row("Shadow Assassin", 0, 4),
            row("Silver Sword", 1, 106), // matched by type+shape+secrets+crypts, Devonian name "Silvers Sword"
            row("Skull", 2, 85),
            row("Slabs", 2, 79),
            row("Slime", 5, 135),
            row("Sloth", 1, 74),
            row("Small Stairs", 2, 55),
            row("Small Waterfall", 2, 31),
            row("Spider", 9, 2),
            row("Spikes", 3, 17),
            row("Staircase", 3, 130), // Devonian name has a zero-width space
            row("Stairs", 4, 29),
            row("Steps", 1, 33),
            row("Stone Window", 2, 123),
            row("Supertall", 6, 104),
            row("Teleport Maze", 0, 72),
            row("Temple", 3, 80),
            row("Three Floors", 1, 128),
            row("Three Weirdos", 0, 52),
            row("Tic Tac Toe", 1, 24),
            row("Tombstone", 2, 124),
            row("Tomioka", 0, 96),
            row("Trinity", 4, 126),
            row("Vinny 8 Ball", 1, 117),
            row("Water", 2, 45),
            row("Water Board", 0, 65),
            row("Waterfall", 8, 0),
            row("Well", 7, 92),
            row("Withermancer", 4, 75), // Devonian name has a zero-width space
            row("Wizard", 4, 14),
            row("Zodd", 1, 145)
    );

    private static final Map<String, Room> BY_NAME = new HashMap<>();
    private static final Map<Integer, List<Room>> BY_DEVONIAN_ID = new HashMap<>();

    static {
        for (Room room : ROOMS) {
            BY_NAME.put(room.name(), room);
            if (room.devonianId() >= 0) {
                BY_DEVONIAN_ID.computeIfAbsent(room.devonianId(), k -> new ArrayList<>()).add(room);
            }
        }
    }

    /** @return the room with this exact name (NoammAddons/our own naming), or null if unknown. */
    public static Room room(String exactName) {
        return exactName == null ? null : BY_NAME.get(exactName);
    }

    /** @return our room name(s) for a Devonian {@code roomID} - empty if unmapped, two entries for
     *  {@link #DEVONIAN_BLAZE_ID}. */
    public static List<Room> roomsForDevonianId(int devonianId) {
        List<Room> rooms = BY_DEVONIAN_ID.get(devonianId);
        return rooms == null ? List.of() : Collections.unmodifiableList(rooms);
    }

    public static int roomCount() {
        return ROOMS.size();
    }

    // ================================================================== doors

    /**
     * NoammAddons' {@code DoorType} enum (spec 2.4 "Enum wire values": {@code DoorType.kt:6-10} -
     * {@code BLOOD, WITHER, NORMAL, ENTRANCE}, upper-case, Gson enum-name serialisation) against the lower-case
     * door names our own {@code dg.v1.door} uses ({@code killer560s-mod-relay/PROTOCOL.md}, "dg.v1.door":
     * {@code normal, wither, blood, entrance}). Devonian and Odin carry no door data.
     * @return the NoammAddons wire value, or null for anything else.
     */
    public static String noammDoorType(String ourType) {
        if (ourType == null) {
            return null;
        }
        return switch (ourType) {
            case "normal" -> "NORMAL";
            case "wither" -> "WITHER";
            case "blood" -> "BLOOD";
            case "entrance" -> "ENTRANCE";
            default -> null;
        };
    }

    /** Inverse of {@link #noammDoorType}. Case-sensitive on purpose: NoammAddons' own receiver calls
     *  {@code DoorType.valueOf}, so anything not spelled exactly like this is not a real NoammAddons message. */
    public static String ourDoorType(String noammType) {
        if (noammType == null) {
            return null;
        }
        return switch (noammType) {
            case "NORMAL" -> "normal";
            case "WITHER" -> "wither";
            case "BLOOD" -> "blood";
            case "ENTRANCE" -> "entrance";
            default -> null;
        };
    }

    // ================================================================== M7 dragons

    /**
     * NoammAddons' {@code WitherDragonEnum} constants (spec 2.4: {@code WitherDragonEnum.kt:38-43} -
     * {@code Red, Orange, Green, Blue, Purple, None}, <b>first letter capitalised only</b>) against the
     * lower-case colours our {@code dg.v1.dragon} / {@code PartyInteropState.offerDragonSpawn} use. {@code None}
     * is never sent and never accepted.
     */
    public static String noammDragon(String ourColour) {
        if (ourColour == null) {
            return null;
        }
        return switch (ourColour.toLowerCase(Locale.ROOT)) {
            case "red" -> "Red";
            case "orange" -> "Orange";
            case "green" -> "Green";
            case "blue" -> "Blue";
            case "purple" -> "Purple";
            default -> null;
        };
    }

    public static String ourDragon(String noammDragon) {
        if (noammDragon == null) {
            return null;
        }
        return switch (noammDragon) {
            case "Red" -> "red";
            case "Orange" -> "orange";
            case "Green" -> "green";
            case "Blue" -> "blue";
            case "Purple" -> "purple";
            default -> null; // includes "None"
        };
    }

    /** NoammAddons' {@code S2CPacketM7Dragon.DragonEvent} (spec 2.4: {@code SPAWN}, {@code DEATH}). */
    public static final String NOAMM_DRAGON_SPAWN = "SPAWN";
    public static final String NOAMM_DRAGON_DEATH = "DEATH";

    // ================================================================== Odin melody

    /**
     * Odin {@code MelodyMessage} {@code UpdateMessage.type} values (spec 3.4 table, re-verified against the
     * jar's tableswitch in {@code MelodyMessage$melodyWebSocket$1$1.invoke}): 0 = forget that user, 1 = clay
     * (button row), 2 = purple (target column), 5 = lime pane (moving column); 3 and 4 do nothing.
     */
    public static final int MELODY_TYPE_REMOVE = 0;
    public static final int MELODY_TYPE_CLAY = 1;
    public static final int MELODY_TYPE_PURPLE = 2;
    public static final int MELODY_TYPE_PANE = 5;

    /**
     * <b>The spec left this unresolved (3.4, 3.8 item 4); it is resolved here</b> from
     * {@code Odin-0.3.1.jar} with {@code javap -c -p -constants} on {@code MelodyMessage.class}, 2026-09-21:
     * <ul>
     * <li>{@code <clinit>} offsets 798-875 build {@code ranges = listOf(1..5, 10..14, 19..23, 28..32, 37..41)}.</li>
     * <li>{@code mapToRange(slot)} (offsets 0-73) finds the range containing {@code slot} and returns
     * {@code (slot - range.first) % 5}, else null - i.e. <b>the column, 0-4, within whichever row the pane is
     * in</b>. So type 2 (magenta/purple pane - the target) and type 5 (lime pane - the moving marker) are both
     * a plain column 0-4, directly comparable: the player is "on time" when pane == purple.</li>
     * <li>Type 1 (clay) is {@code packet.slot / 9} of the {@code LIME_TERRACOTTA} slot (offsets 80-90), no
     * range mapping. The four buttons are the terracotta at slots 16, 25, 34, 43 (same slots as our own
     * {@code TerminalSolverFeature.MELODY_CLAY_SLOTS}), so clay is the button ROW, 1-4.</li>
     * <li>{@code clayProgress = hashMapOf(2 to "Melody 25%", 3 to "Melody 50%", 4 to "Melody 75%")}
     * ({@code <clinit>} offsets 747-795): Odin itself reads clay 1 as 0% done, 2 as 25%, 3 as 50%, 4 as 75%.</li>
     * </ul>
     */
    public static final int MELODY_MIN_COLUMN = 0;
    public static final int MELODY_MAX_COLUMN = 4;
    public static final int MELODY_MIN_CLAY_ROW = 1;
    public static final int MELODY_MAX_CLAY_ROW = 4;

    /** Percent of the Melody terminal a player has finished, from their clay row, exactly as Odin labels it
     *  ({@code clayProgress}); -1 if out of range. */
    public static int melodyPercentForClayRow(int clayRow) {
        if (clayRow < MELODY_MIN_CLAY_ROW || clayRow > MELODY_MAX_CLAY_ROW) {
            return -1;
        }
        return (clayRow - 1) * 25;
    }
}
