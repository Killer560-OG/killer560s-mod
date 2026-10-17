package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Levers that open a way through, in rooms that are not puzzles.
 *
 * <p>Some ordinary rooms have a secret behind something a lever takes away - a barred doorway in Mines, a
 * boarded-up wall in Pressure Plates. The capture holds the blocks, and it holds the lever, but nothing
 * connects them: Hypixel moves those blocks with a command, not with redstone a capture could record. So the
 * connection is written down here, once per room, and the sim does the rest.
 *
 * <h2>How each rule was found</h2>
 *
 * <p>Not by decoding and guessing - that cost two rounds of wrong answers on exactly these two rooms. Each one
 * is a {@code /simwhere} reading killer560 took standing in the room looking at the block, which is why the
 * coordinates here are CAPTURE-LOCAL: that is the system {@code /simwhere} prints and the system
 * {@link SimRoomPuzzles#capturedPos} takes.
 *
 * <ul>
 *   <li><b>Mines.</b> He looked at the door and said "it is this entire door": {@code dark_oak_log} at
 *       capture {@code (50, 79, 58)}. A flood fill from there gives an 18-block dark oak frame at
 *       {@code x=50, y=78..84, z=58..60} with three iron bars filling its opening at {@code z=59} - 21 cells
 *       exactly, so the box below is the door and nothing else. The lever at {@code (35, 79, 60)} is the one
 *       in line with it, and the trapped chest at {@code (57, 78, 59)} is seven blocks past it: lever, door,
 *       chest, which is how he first described it ("the dark oak door between it and the chest").</li>
 *   <li><b>Pressure Plates.</b> He looked at the wall: {@code oak_planks} at capture {@code (54, 94, 16)}. The
 *       flood fill gives 30 blocks of oak at {@code x=54..55, y=93..97, z=15..17} - again exactly the box -
 *       with a chest at {@code (60, 94, 16)} directly behind it. Of the room's two real levers,
 *       {@code (6, 71, 26)} and {@code (22, 83, 16)}, the second is the higher - "there are two levers [...]
 *       whenever I flick the top one" - and it is across the room and below the wall on the same {@code z},
 *       matching "the wooden wall across and up from it". (The sixteen levers banked at {@code y=97} are part
 *       of the room's own mechanism, not these two.)</li>
 * </ul>
 *
 * <p>Adding another is one line in {@link #RULES}: the room name, the lever he {@code /simwhere}'d, and the
 * two corners of what it takes away.
 *
 * <h2>2026-10-09: four wooden doors, two of them with no lever in the capture</h2>
 *
 * <p>From his screenshots, matched to the captures by decoding (each door is a flood fill of one wood type that fills
 * its bounding box exactly, the same check as above):
 * <ul>
 *   <li><b>Bridges.</b> The capture's only lever, {@code (55, 59, 29)} (floor), drops the 18-block dark oak door at
 *       {@code x=10, y=81..86, z=15..17} in front of the chest at {@code (4, 82, 16)} - his 02.05.13 / 02.05.45 shots,
 *       whose F3 heights put the lever and the door 22 blocks apart exactly as the capture does.</li>
 *   <li><b>Raccoon.</b> Its lever {@code (25, 70, 29)} (floor, on an acacia log) drops the 3x4 acacia log door at
 *       {@code x=24..26, y=69..72, z=19} (12 blocks, exactly the box).</li>
 *   <li><b>Logs.</b> "The wood block is missing a lever": the lone dark oak wood set in a 3x3 of polished andesite at
 *       {@code (29, 83, 6)} - the only wood block in the library ringed like that. The lever is PLACED on top of it,
 *       {@code (29, 84, 6)}, and drops the 32-block dark oak door at {@code x=20..21, y=69..72, z=23..26}.</li>
 *   <li><b>Arrow Trap.</b> "Missing lever": the lone oak wood block at floor level in a wall nook, {@code (16, 69, 24)}
 *       (02.31.13). The lever is PLACED on top of it, {@code (16, 70, 24)}, and drops the 5x5 oak log wall at
 *       {@code x=14..18, y=69..73, z=8} (02.31.27) with the room's chest behind it.</li>
 * </ul>
 * Where the two placed levers stand (on top, a floor lever) is an inference from Raccoon's and Bridges' own levers,
 * which are floor levers on a block; the captures do not say.
 *
 * <h2>2026-10-12: five more, every lever already in its capture</h2>
 *
 * <p>From his levers-1012 shots, matched by decoding (each door a flood fill that fills its box exactly):
 * <ul>
 *   <li><b>Redstone Warrior.</b> The wall lever at {@code (16, 62, 5)} (18) opens the 3x5 iron bars at
 *       {@code x=15..17, y=58..62, z=27} in front of the chest at {@code (16, 58, 25)} (19).</li>
 *   <li><b>Hallway.</b> The floor lever at {@code (11, 76, 93)}, hidden beside a leaf bush at a cobblestone pillar's foot
 *       (21), breaks the 2x4x2 oak log gate at {@code x=28..29, y=69..72, z=73..74} (22) that shuts the chest at
 *       {@code (26, 69, 55)} off from the hall.</li>
 *   <li><b>Knight.</b> The floor lever on spruce wood at {@code (4, 70, 10)} (23) breaks the 4x4x2 spruce gate at
 *       {@code x=24..27, y=69..72, z=21..22} (24) in front of the two chests at {@code z=30}.</li>
 *   <li><b>Atlas.</b> The floor lever on the lone polished granite block at {@code (54, 67, 32)} (25) opens the 3x4x2 oak
 *       log door at {@code x=7..9, y=80..83, z=25..26} (26), the one at the top of the spruce steps with a player head
 *       over it.</li>
 *   <li><b>Long Hall.</b> The floor lever on the oak log desk at {@code (21, 70, 28)} (27) opens the oak wood strip at
 *       {@code x=18, y=82, z=12..19} (28), the log floor of the channel between the dark stairs.</li>
 * </ul>
 *
 * <h2>2026-10-13: Deathmite and Doors</h2>
 *
 * <ul>
 *   <li><b>Deathmite.</b> The floor lever on the lone oak log ringed by polished diorite and chiseled quartz at
 *       {@code (54, 70, 16)} opens the spruce stair/slab and oak log door at {@code x=28, y=65..70, z=15..17} (18 blocks,
 *       every one wood, exactly the box) behind the stone brick arch.</li>
 *   <li><b>Doors.</b> One corridor at {@code z=15..17} shut by four one-wood doors, and four floor levers each standing
 *       on a log of one of those woods: the lever's log names its door. Oak {@code (62, 59, 16)} opens the oak log door
 *       {@code x=51, y=58..63} (18); acacia {@code (51, 59, 2)} the acacia log door {@code x=40, y=58..64} (21); the pair
 *       either side of the pillar he asked about, dark oak {@code (44, 59, 16)} and jungle {@code (46, 59, 16)}, the dark
 *       oak door {@code x=24} and the jungle door {@code x=20} (21 each, {@code y=58..64}), in front of the two chests at
 *       {@code (9, 58, 14)} and {@code (9, 58, 18)}. The pairing by wood is read off the capture, not seen on Hypixel.</li>
 * </ul>
 *
 * <h2>2026-10-14: Buttons and Dome</h2>
 *
 * <ul>
 *   <li><b>Buttons.</b> "make this lever in buttons drop this gate": the floor lever on the oak log at {@code (43, 81, 58)}
 *       drops the 3x9 oak log gate at {@code x=56..58, y=80..88, z=16} (27 blocks, exactly the box - the room's only
 *       solid wood wall).</li>
 *   <li><b>Dome.</b> "make it so the lever in dome drops this door": the wall lever at {@code (22, 75, 7)} drops the
 *       3x5x2 oak log door right above it at {@code x=21..22, y=77..81, z=4..6} (30 blocks, exactly the box).</li>
 * </ul>
 *
 * <h2>2026-10-15: Cobble Wall Pillar and Golden Oasis, both levers PLACED</h2>
 *
 * <p>Neither capture holds the lever; both positions are RSM's {@code LEVER} waypoints ({@code assets/rsm/secrets.json}
 * in killer560s-mod-logs/rsm-ref, Hypixel-derived), turned into capture coordinates by the turn that lands that
 * room's RSM chests on the capture's chests, and both match his Hypixel shots.
 * <ul>
 *   <li><b>Cobble Wall Pillar.</b> "there should be a lever here that drops this iron gate": a floor lever in the
 *       one-block nook at {@code (8, 69, 21)} beside the captured redstone wall torch (his shot shows the red torch
 *       beside it). It drops the gate in front of the chest at {@code (19, 60, 29)}: three iron bars wide and four
 *       high at {@code z=27} AND the three-by-four of BARRIER blocks right behind them at {@code z=28} - the capture
 *       recorded Hypixel's barriers, and dropping only the bars would leave an invisible wall. {@code x=18..20,
 *       y=60..63, z=27..28}, 24 blocks, exactly the box. The identical bars at {@code x=13..15} guard a skull, not a
 *       chest, have no barriers, and stay.</li>
 *   <li><b>Golden Oasis.</b> "there should be a lever on this block in golden oasis that drops that wood": the lone
 *       acacia wood at the end of the strip of three redstone blocks, {@code (30, 70, 26)}; the floor lever goes on top,
 *       {@code (30, 71, 26)}. It drops the room's only acacia log wall, {@code x=5..8, y=69..72, z=11..12} (32 blocks,
 *       exactly the box) - the lever's wood names its door, as in Doors. The redstone blocks in that strip are the
 *       Redstone Key's, see {@link SimRedstoneKey}.</li>
 * </ul>
 *
 * <p>Opening is one-way and once only, like the real thing - flicking the lever back does not put the blocks
 * back, because on Hypixel it does not either. A fresh floor rearms everything.
 */
public final class SimRoomLevers {

    private static final org.slf4j.Logger LOGGER = ModLog.get("killer560smod-roomsim");

    /**
     * One room's lever and what it clears, all in capture-local coordinates.
     *
     * @param room  the room name, matched case-insensitively
     * @param lever the lever block
     * @param from  one corner of the box to clear, inclusive
     * @param to    the opposite corner, inclusive
     */
    private record Rule(String room, int[] lever, int[] from, int[] to, boolean place) {
        Rule(String room, int[] lever, int[] from, int[] to) {
            this(room, lever, from, to, false);
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("Mines",
                    new int[]{35, 79, 60}, new int[]{50, 78, 58}, new int[]{50, 84, 60}),
            new Rule("Pressure Plates",
                    new int[]{22, 83, 16}, new int[]{54, 93, 15}, new int[]{55, 97, 17}),
            new Rule("Bridges",
                    new int[]{55, 59, 29}, new int[]{10, 81, 15}, new int[]{10, 86, 17}),
            new Rule("Raccoon",
                    new int[]{25, 70, 29}, new int[]{24, 69, 19}, new int[]{26, 72, 19}),
            new Rule("Logs",
                    new int[]{29, 84, 6}, new int[]{20, 69, 23}, new int[]{21, 72, 26}, true),
            new Rule("Arrow Trap",
                    new int[]{16, 70, 24}, new int[]{14, 69, 8}, new int[]{18, 73, 8}, true),
            // 2026-10-12, his levers-1012 shots (see the class comment).
            new Rule("Redstone Warrior",
                    new int[]{16, 62, 5}, new int[]{15, 58, 27}, new int[]{17, 62, 27}),
            new Rule("Hallway",
                    new int[]{11, 76, 93}, new int[]{28, 69, 73}, new int[]{29, 72, 74}),
            new Rule("Knight",
                    new int[]{4, 70, 10}, new int[]{24, 69, 21}, new int[]{27, 72, 22}),
            new Rule("Atlas",
                    new int[]{54, 67, 32}, new int[]{7, 80, 25}, new int[]{9, 83, 26}),
            new Rule("Long Hall",
                    new int[]{21, 70, 28}, new int[]{18, 82, 12}, new int[]{18, 82, 19}),
            // 2026-10-13 (see the class comment).
            new Rule("Deathmite",
                    new int[]{54, 70, 16}, new int[]{28, 65, 15}, new int[]{28, 70, 17}),
            new Rule("Doors",
                    new int[]{62, 59, 16}, new int[]{51, 58, 15}, new int[]{51, 63, 17}),
            new Rule("Doors",
                    new int[]{51, 59, 2}, new int[]{40, 58, 15}, new int[]{40, 64, 17}),
            new Rule("Doors",
                    new int[]{44, 59, 16}, new int[]{24, 58, 15}, new int[]{24, 64, 17}),
            new Rule("Doors",
                    new int[]{46, 59, 16}, new int[]{20, 58, 15}, new int[]{20, 64, 17}),
            // 2026-10-14 (see the class comment).
            new Rule("Buttons",
                    new int[]{43, 81, 58}, new int[]{56, 80, 16}, new int[]{58, 88, 16}),
            new Rule("Dome",
                    new int[]{22, 75, 7}, new int[]{21, 77, 4}, new int[]{22, 81, 6}),
            // 2026-10-15 (see the class comment).
            new Rule("Cobble Wall Pillar",
                    new int[]{8, 69, 21}, new int[]{18, 60, 27}, new int[]{20, 63, 28}, true),
            new Rule("Golden Oasis",
                    new int[]{30, 71, 26}, new int[]{5, 69, 11}, new int[]{8, 72, 12}, true));

    /** Lever world position to the blocks it clears, for the floor currently standing. */
    private static final Map<BlockPos, List<BlockPos>> ARMED = new ConcurrentHashMap<>();
    /** Which room each armed lever belongs to, for the chat line. */
    private static final Map<BlockPos, String> ROOM_OF = new ConcurrentHashMap<>();

    private SimRoomLevers() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            Minecraft client = Minecraft.getInstance();
            // level.isClientSide(): this event fires on both sides in singleplayer, and without the check a
            // single click would be handled twice - the same guard SimDoors uses.
            if (!level.isClientSide() || !SimState.canAct(client) || player != client.player) {
                return InteractionResult.PASS;
            }
            List<BlockPos> blocks = ARMED.remove(hit.getBlockPos());
            if (blocks == null) {
                return InteractionResult.PASS;
            }
            String room = ROOM_OF.remove(hit.getBlockPos());
            open(client, blocks, room);
            // PASS, so vanilla still flips the lever: the click, the sound and the animation are what make it
            // read as a real lever rather than a trigger pad.
            return InteractionResult.PASS;
        });
    }

    /**
     * Arms every rule whose room is on the floor that has just been built.
     *
     * <p>Server thread only, alongside {@link SimRoomPuzzles#armFloor} - it reads the capture and nothing
     * else, so it needs no world access at all, but it must see the same placements that method does.
     *
     * @return how many levers were armed
     */
    public static int armFloor(ServerLevel level) {
        ARMED.clear();
        ROOM_OF.clear();
        if (level == null) {
            return 0;
        }
        int armed = 0;
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            for (Rule rule : RULES) {
                if (!rule.room().equalsIgnoreCase(placed.name())) {
                    continue;
                }
                RoomLibrary.Room room = RoomLibrary.get(placed.name());
                if (room == null) {
                    continue;
                }
                SimRoomPuzzles.Placement p = new SimRoomPuzzles.Placement(room,
                        placed.gridX(), placed.gridZ(), placed.pasteRotation(), placed.rotation());
                BlockPos lever = SimRoomPuzzles.capturedPos(p,
                        rule.lever()[0], rule.lever()[1], rule.lever()[2]);
                // A lever the capture never had is put there, on top of its block, as a floor lever - only into
                // air, so a wrong rule can never overwrite the room.
                if (rule.place() && level.getBlockState(lever).isAir()) {
                    level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                                    net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
                    SimBuildQueue.touched(lever.getX(), lever.getZ());
                }
                // SAID OUT LOUD when the lever is not where the capture says, because a rule that silently
                // arms on a stone block is a lever he flicks forever with nothing happening - which is
                // exactly how both of these looked before they existed.
                if (!level.getBlockState(lever).is(Blocks.LEVER)) {
                    LOGGER.warn("Sim room levers: {} has no lever at capture {},{},{} (world {}) - it is {}."
                                    + " Not armed; the capture or the rule is wrong.",
                            placed.name(), rule.lever()[0], rule.lever()[1], rule.lever()[2], lever,
                            level.getBlockState(lever).getBlock());
                    continue;
                }
                List<BlockPos> blocks = box(p, rule);
                ARMED.put(lever, blocks);
                ROOM_OF.put(lever, placed.name());
                armed++;
                LOGGER.info("Sim room levers: {}'s lever at {} will clear {} block(s)",
                        placed.name(), lever, blocks.size());
            }
        }
        return armed;
    }

    /** Every world position inside a rule's capture-local box, at this placement's rotation. */
    private static List<BlockPos> box(SimRoomPuzzles.Placement p, Rule rule) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = Math.min(rule.from()[0], rule.to()[0]); x <= Math.max(rule.from()[0], rule.to()[0]); x++) {
            for (int y = Math.min(rule.from()[1], rule.to()[1]); y <= Math.max(rule.from()[1], rule.to()[1]); y++) {
                for (int z = Math.min(rule.from()[2], rule.to()[2]); z <= Math.max(rule.from()[2], rule.to()[2]); z++) {
                    out.add(SimRoomPuzzles.capturedPos(p, x, y, z));
                }
            }
        }
        return out;
    }

    private static void open(Minecraft client, List<BlockPos> blocks, String room) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos pos : blocks) {
                // setBlockAndUpdate, not the bulk flags: this is a few dozen blocks at a moment he is looking
                // at them, and the neighbours around a removed frame (stairs, bars, torches) have to settle.
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        });
        ModChat.log("Sim", ModChat.good(room == null ? "Opened" : room), // Hypixel says nothing here (2026-10-16)
                ModChat.text(" - the lever takes the way through down."));
    }

    /** Drops the floor's levers without touching the world, for leaving the sim. */
    public static void forget() {
        ARMED.clear();
        ROOM_OF.clear();
    }

    /**
     * The world position of a capture-local block of the first placed room of this name, or null when none is
     * placed - the same transform the rules use. For tests.
     */
    public static BlockPos capturedWorld(String roomName, int cx, int cy, int cz) {
        for (SimRoomIndex.Placed placed : SimRoomIndex.placed()) {
            if (!placed.name().equalsIgnoreCase(roomName)) {
                continue;
            }
            RoomLibrary.Room room = RoomLibrary.get(placed.name());
            if (room == null) {
                return null;
            }
            return SimRoomPuzzles.capturedPos(new SimRoomPuzzles.Placement(room,
                    placed.gridX(), placed.gridZ(), placed.pasteRotation(), placed.rotation()), cx, cy, cz);
        }
        return null;
    }

    /** For tests and the build log. */
    public static int armedCount() {
        return ARMED.size();
    }
}
