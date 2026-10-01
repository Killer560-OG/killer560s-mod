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
    private record Rule(String room, int[] lever, int[] from, int[] to) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("Mines",
                    new int[]{35, 79, 60}, new int[]{50, 78, 58}, new int[]{50, 84, 60}),
            new Rule("Pressure Plates",
                    new int[]{22, 83, 16}, new int[]{54, 93, 15}, new int[]{55, 97, 17}));

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
        ModChat.send("Sim", ModChat.good(room == null ? "Opened" : room),
                ModChat.text(" - the lever takes the way through down."));
    }

    /** Drops the floor's levers without touching the world, for leaving the sim. */
    public static void forget() {
        ARMED.clear();
        ROOM_OF.clear();
    }

    /** For tests and the build log. */
    public static int armedCount() {
        return ARMED.size();
    }
}
