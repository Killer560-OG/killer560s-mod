package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <h2>2026-10-16: his room-by-room pass through All Rooms</h2>
 *
 * <p>Twenty-one more, and one correction, from his sim-grassruin-lever shots and descriptions. Each pairing comes
 * from the secret ROUTE first - the community route data numbers a secret's steps, so "5 - Lever" belongs with
 * "5 - Chest" - and the gate is then the body of gate material nearest that secret, preferring the one holding the
 * route's stonk/superboom/entrance point; {@code tools/lever_gates.py} does it for every room and prints the
 * proposals. Every pairing below was then held against his shot or description; where they disagreed, his shot won
 * and the javadoc says so.
 * <ul>
 *   <li><b>Grass Ruin.</b> The basement floor lever on polished granite, {@code (32, 53, 7)}, drops the 33 iron bars
 *       of the window at {@code x=37, y=52..58, z=8..12} beside it (his shot looks through it at the chest over the
 *       lava). Only the bars: the two polished andesite corners of its arch stay. The upper lever {@code (32, 82, 3)}
 *       is route step "Lever 1" of the same secret, but nothing in the capture is its gate; unbound.</li>
 *   <li><b>Layers.</b> The plank-floor pit lever {@code (6, 76, 3)} - captured ON, so it is turned off at arming -
 *       drops the oak-wood door frame with its bar at {@code x=5..7, y=69..73, z=10} in front of the chest at
 *       {@code (6, 69, 3)}. The second lever is missing from the capture; route and RSM both put it over the three
 *       smooth stone blocks by the torch, and it is PLACED on the middle one, {@code (9, 90, 33)} (the data say
 *       y 91, a block of air above it). It drops the 12 bars at {@code x=46, y=69..72, z=47..49} before the chest at
 *       {@code (48, 69, 48)}, red carpet in front.</li>
 *   <li><b>Long Hall</b> (corrected). The lever on the desk {@code (21, 70, 28)} was bound to the oak wood strip in
 *       the channel floor, which is not its gate: "this lever in Long Hall drops this door". The door is the 3 by 5
 *       oak wood and spruce stair panel at {@code x=20..22, y=84..88, z=11}, which holds the route's entrance.</li>
 *   <li><b>Mines.</b> The pit lever {@code (53, 79, 10)} drops the 20 bars at {@code x=47, y=80..83, z=11..15} with
 *       the chest at {@code (42, 80, 13)} behind; the pit lever {@code (15, 80, 4)} drops the oak log wall at
 *       {@code x=4..8, y=90..95, z=19} (his mines-door2 shot - the route data pair that lever with the bars at
 *       {@code x=9..11, y=82..84, z=21} instead, and his shot wins).</li>
 *   <li><b>Mossy</b> {@code (64, 70, 30)} drops the oak log wall {@code x=55..56, y=89..95, z=3..6};
 *       <b>Overgrown</b> {@code (4, 81, 16)} the oak log wall between the heads {@code x=25..28, y=79..82, z=11};
 *       <b>Pipes</b> {@code (53, 94, 16)} the 50 bars {@code x=62..66, y=54..63, z=24}; <b>Pirate</b>
 *       {@code (44, 82, 16)} the plank, log and stair wall {@code x=6..7, y=82..89, z=15..18}; <b>Pressure
 *       Plates</b>' wall lever {@code (6, 71, 26)} the 30 bars {@code x=12, y=70..75, z=22..26} with the item
 *       behind; <b>Redstone Crypt</b> {@code (16, 68, 16)} the oak logs of {@code x=5..6, y=69..74, z=15..17};
 *       <b>Redstone Key</b> {@code (6, 70, 28)} the acacia logs of {@code x=25..27, y=69..74, z=11}; <b>Water</b>'s
 *       wall lever {@code (16, 70, 8)} the oak log door {@code x=7..8, y=69..72, z=5..6}; <b>Withermancer</b>
 *       {@code (25, 61, 4)} the bars of {@code x=27, y=111..117, z=15..17} at the chest {@code (30, 111, 16)}.</li>
 *   <li><b>Purple Flags.</b> No lever in the capture; route and RSM agree on {@code (30, 55, 28)}, air with air
 *       under it and cobblestone over it, so it is PLACED there hanging from the ceiling. It drops the spruce door
 *       with its two bar windows, {@code x=25..27, y=49..53, z=13}.</li>
 *   <li><b>Supertall</b>, four levers, one floor of the shaft each: {@code (51, 77, 47)} drops the dark oak gate
 *       {@code x=43..44, y=73..81, z=30..34}; {@code (10, 98, 25)} the one above it, {@code y=95..103}; {@code (13,
 *       131, 8)} the gate {@code x=30..34, y=117..125, z=20..21}; and {@code (54, 142, 52)}, on its oak log, the
 *       3 by 3 oak log hatch {@code x=52..54, y=139, z=55..57}. Paired by height - his shots do not show which
 *       gate each lever faces.</li>
 * </ul>
 *
 * <h2>2026-10-17: Rails, Spider and Buttons' second lever</h2>
 *
 * <p>"for rails it is this style of door with the cobblestone fences. It is the same in spider." Both gates are
 * lattices of cobblestone WALL blocks in a cobblestone facade - not solid cobblestone, which is why the first pass
 * missed them. Rails: his /simwhere on one, capture {@code (56, 51, 42)}, is in the 21 walls of {@code x=54..58,
 * y=49..54, z=42}, the way through to the chest at {@code (61, 49, 55)} (the route's "7 - Lever" {@code (46, 71, 24)}).
 * Spider: the 16 walls of {@code x=21, y=83..87, z=31..35}, in front of the corridor to the chest at {@code (28, 84,
 * 33)} under the lever {@code (28, 93, 33)}. Only the walls go; the facade round them stays. Buttons' "lever behind
 * this lava", {@code (59, 70, 37)}, drops the 3 by 4 bars at {@code x=30, y=73..76, z=18..20}; its other lever was
 * already bound.
 *
 * <p>Then the rooms the sweep flagged, each gate confirmed by his shot and each lever the route's: End {@code (8, 84,
 * 6)} the 40 bars {@code x=14..18, y=69..76, z=11}; Gravel {@code (5, 69, 64)} the 15 bars {@code x=19..21, y=69..73,
 * z=34}; Dip {@code (5, 79, 20)} the oak logs {@code x=17..18, y=78..82, z=17..18}; Criss Cross {@code (16, 75, 2)} the
 * oak logs of {@code x=15..17, y=66..70, z=14}; Lava Ravine {@code (43, 84, 55)} the 5 by 5 log and stair door {@code
 * x=7..11, y=88..92, z=38}; Lots Of Floors {@code (19, 62, 17)} the dark oak logs {@code x=20..21, y=76..81,
 * z=25..27}; Museum {@code (54, 84, 27)} the wood panel {@code x=48, y=69..77, z=30..34} between the magenta braziers;
 * Rare Pillars {@code (7, 88, 28)} the acacia wood {@code x=11, y=69..75, z=11..13} over the chest's nook; Red Blue
 * {@code (55, 69, 6)} the 24 bars {@code x=57..60, y=69..74, z=12}; Red Green's lever, missing from the capture, is
 * PLACED "right between the skulls" on the stone brick at {@code (2, 60, 16)} and drops the bars {@code x=8,
 * y=74..77, z=15..17}. Atlas: the log wall he pointed at (his /simwhere: capture {@code (8, 81, 25)}) is the 2026-10-12
 * door; the route's other lever {@code (61, 90, 34)} drops it too.
 *
 * <p>A rule may name the blocks it takes ({@link Rule#only}); then only those inside its box go, read once when the
 * floor is armed. That is for gates that share their box with their frame (Grass Ruin's andesite corners) or with
 * something that must stay.
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
     * @param place where a lever the capture lacks is put: {@code FLOOR} on the block under it, {@code CEILING}
     *              hanging from the block over it, or null when the capture has the lever
     * @param only  the block ids (path, e.g. {@code iron_bars}) the lever takes; empty takes everything in the box
     */
    private record Rule(String room, int[] lever, int[] from, int[] to, AttachFace place, Set<String> only) {
        Rule(String room, int[] lever, int[] from, int[] to) {
            this(room, lever, from, to, null, Set.of());
        }

        Rule(String room, int[] lever, int[] from, int[] to, boolean place) {
            this(room, lever, from, to, place ? AttachFace.FLOOR : null, Set.of());
        }

        /** The same rule taking only these blocks. */
        Rule only(String... ids) {
            return new Rule(room, lever, from, to, place, Set.of(ids));
        }

        /** The same rule with its lever placed, hanging from the block over it. */
        Rule ceiling() {
            return new Rule(room, lever, from, to, AttachFace.CEILING, only);
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
            // 2026-10-16: the 3 x 5 panel holding the route's entrance, not the channel floor (see the class comment).
            new Rule("Long Hall",
                    new int[]{21, 70, 28}, new int[]{20, 84, 11}, new int[]{22, 88, 11}),
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
                    new int[]{30, 71, 26}, new int[]{5, 69, 11}, new int[]{8, 72, 12}, true),
            // 2026-10-16, his pass through All Rooms (see the class comment).
            new Rule("Grass Ruin",
                    new int[]{32, 53, 7}, new int[]{37, 52, 8}, new int[]{37, 58, 12}).only("iron_bars"),
            new Rule("Layers",
                    new int[]{6, 76, 3}, new int[]{5, 69, 10}, new int[]{7, 73, 10})
                    .only("oak_wood", "spruce_stairs", "iron_bars"),
            new Rule("Layers",
                    new int[]{9, 90, 33}, new int[]{46, 69, 47}, new int[]{46, 72, 49}, true),
            new Rule("Mines",
                    new int[]{53, 79, 10}, new int[]{47, 80, 11}, new int[]{47, 83, 15}),
            new Rule("Mines",
                    new int[]{15, 80, 4}, new int[]{4, 90, 19}, new int[]{8, 95, 19}),
            new Rule("Mossy",
                    new int[]{64, 70, 30}, new int[]{55, 89, 3}, new int[]{56, 95, 6}),
            new Rule("Overgrown",
                    new int[]{4, 81, 16}, new int[]{25, 79, 11}, new int[]{28, 82, 11}),
            new Rule("Pipes",
                    new int[]{53, 94, 16}, new int[]{62, 54, 24}, new int[]{66, 63, 24}),
            new Rule("Pirate",
                    new int[]{44, 82, 16}, new int[]{6, 82, 15}, new int[]{7, 89, 18}),
            new Rule("Pressure Plates",
                    new int[]{6, 71, 26}, new int[]{12, 70, 22}, new int[]{12, 75, 26}),
            new Rule("Purple Flags",
                    new int[]{30, 55, 28}, new int[]{25, 49, 13}, new int[]{27, 53, 13}, true).ceiling(),
            new Rule("Redstone Crypt",
                    new int[]{16, 68, 16}, new int[]{5, 69, 15}, new int[]{6, 74, 17}).only("oak_log"),
            new Rule("Redstone Key",
                    new int[]{6, 70, 28}, new int[]{25, 69, 11}, new int[]{27, 74, 11}).only("acacia_log"),
            new Rule("Supertall",
                    new int[]{51, 77, 47}, new int[]{43, 73, 30}, new int[]{44, 81, 34}),
            new Rule("Supertall",
                    new int[]{10, 98, 25}, new int[]{43, 95, 30}, new int[]{44, 103, 34}),
            new Rule("Supertall",
                    new int[]{13, 131, 8}, new int[]{30, 117, 20}, new int[]{34, 125, 21}),
            new Rule("Supertall",
                    new int[]{54, 142, 52}, new int[]{52, 139, 55}, new int[]{54, 139, 57}),
            new Rule("Water",
                    new int[]{16, 70, 8}, new int[]{7, 69, 5}, new int[]{8, 72, 6}),
            new Rule("Withermancer",
                    new int[]{25, 61, 4}, new int[]{27, 111, 15}, new int[]{27, 117, 17}).only("iron_bars"),
            // 2026-10-17: the cobblestone WALL lattices ("this style of door with the cobblestone fences"), and
            // Buttons' second lever (see the class comment).
            new Rule("Rails",
                    new int[]{46, 71, 24}, new int[]{54, 49, 42}, new int[]{58, 54, 42}).only("cobblestone_wall"),
            new Rule("Spider",
                    new int[]{28, 93, 33}, new int[]{21, 83, 31}, new int[]{21, 87, 35}).only("cobblestone_wall"),
            new Rule("Buttons",
                    new int[]{59, 70, 37}, new int[]{30, 73, 18}, new int[]{30, 76, 20}),
            // 2026-10-17, the sweep's flagged rooms as he confirmed each gate (see the class comment).
            new Rule("End",
                    new int[]{8, 84, 6}, new int[]{14, 69, 11}, new int[]{18, 76, 11}),
            new Rule("Gravel",
                    new int[]{5, 69, 64}, new int[]{19, 69, 34}, new int[]{21, 73, 34}),
            new Rule("Dip",
                    new int[]{5, 79, 20}, new int[]{17, 78, 17}, new int[]{18, 82, 18}),
            new Rule("Criss Cross",
                    new int[]{16, 75, 2}, new int[]{15, 66, 14}, new int[]{17, 70, 14}).only("oak_log"),
            new Rule("Lava Ravine",
                    new int[]{43, 84, 55}, new int[]{7, 88, 38}, new int[]{11, 92, 38}),
            new Rule("Lots Of Floors",
                    new int[]{19, 62, 17}, new int[]{20, 76, 25}, new int[]{21, 81, 27}),
            new Rule("Museum",
                    new int[]{54, 84, 27}, new int[]{48, 69, 30}, new int[]{48, 77, 34}),
            new Rule("Rare Pillars",
                    new int[]{7, 88, 28}, new int[]{11, 69, 11}, new int[]{11, 75, 13}),
            // Placed: "missing its lever ... right between the skulls", on the stone brick at (2, 60, 16).
            new Rule("Red Green",
                    new int[]{2, 61, 16}, new int[]{8, 74, 15}, new int[]{8, 77, 17}, true),
            // His Atlas log wall (his /simwhere point reads capture 8, 81, 25) is the door the 2026-10-12 rule already
            // gives the lever at (54, 67, 32); he flagged it again for the route's other lever, so that one drops it
            // too. The route itself pairs (61, 90, 34) with the bars at x=14, which stay until he says otherwise.
            new Rule("Atlas",
                    new int[]{61, 90, 34}, new int[]{7, 80, 25}, new int[]{9, 83, 26}),
            new Rule("Red Blue",
                    new int[]{55, 69, 6}, new int[]{57, 69, 12}, new int[]{60, 74, 12}));

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
            for (Rule written : RULES) {
                if (!written.room().equalsIgnoreCase(placed.name())) {
                    continue;
                }
                RoomLibrary.Room room = RoomLibrary.get(placed.name());
                if (room == null) {
                    continue;
                }
                Rule rule = fit(written, room);
                SimRoomPuzzles.Placement p = new SimRoomPuzzles.Placement(room,
                        placed.gridX(), placed.gridZ(), placed.pasteRotation(), placed.rotation());
                BlockPos lever = SimRoomPuzzles.capturedPos(p,
                        rule.lever()[0], rule.lever()[1], rule.lever()[2]);
                // A lever the capture never had is put there, on top of its block (or hanging from the one over
                // it) - only into air, so a wrong rule can never overwrite the room.
                if (rule.place() != null && level.getBlockState(lever).isAir()) {
                    level.setBlockAndUpdate(lever, Blocks.LEVER.defaultBlockState()
                            .setValue(LeverBlock.FACE, rule.place()));
                    SimBuildQueue.touched(lever.getX(), lever.getZ());
                }
                // OFF to begin with, as on Hypixel: Layers' capture caught its lever already thrown, and a click
                // on an "on" lever turns it off - the gate would open on the click that reads as closing it.
                if (level.getBlockState(lever).is(Blocks.LEVER) && level.getBlockState(lever).getValue(LeverBlock.POWERED)) {
                    level.setBlock(lever, level.getBlockState(lever).setValue(LeverBlock.POWERED, false), 2);
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
                List<BlockPos> blocks = box(p, rule, level);
                // Merged, not replaced: a lever may have more than one rule, and a second put() would quietly
                // drop the first one's gate.
                List<BlockPos> had = ARMED.get(lever);
                if (had == null) {
                    ARMED.put(lever, new ArrayList<>(blocks));
                    armed++;
                } else {
                    had.addAll(blocks);
                }
                ROOM_OF.put(lever, placed.name());
                LOGGER.info("Sim room levers: {}'s lever at {} will clear {} block(s)",
                        placed.name(), lever, blocks.size());
            }
        }
        return armed;
    }

    /**
     * The rule in THIS capture's frame. The table is written against the bundled captures, but a local capture of the
     * same room can be the same room taken a quarter or half turn round - the Mod Only Test instance's Overgrown is the
     * bundled one turned 180 degrees, its lever at {@code (28, 81, 16)} where the table says {@code (4, 81, 16)}, so
     * the rule armed on air there and took nothing. Every turn the room's shape allows is tried and the one that
     * lands the lever on a lever (air, for a lever the sim places) and the most gate blocks in the box wins; turn 0 on
     * a tie, so a capture that agrees with the table always reads the table as written.
     */
    static Rule fit(Rule rule, RoomLibrary.Room room) {
        Rule best = rule;
        int bestScore = Integer.MIN_VALUE;
        boolean square = room.sizeX == room.sizeZ;
        for (int k = 0; k < 4; k++) {
            if (!square && (k & 1) == 1) {
                continue;
            }
            Rule t = turned(rule, k, room.sizeX, room.sizeZ);
            String at = idAt(room, t.lever()[0], t.lever()[1], t.lever()[2]);
            boolean leverOk = t.place() != null ? at.equals("air") || at.equals("lever") : at.equals("lever");
            int score = (leverOk ? 100_000 : 0) + gateCount(room, t);
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best != rule) {
            LOGGER.info("Sim room levers: {}'s capture is turned from the table's - its lever {} reads as {}",
                    room.name, key(rule.lever()), key(best.lever()));
        }
        return best;
    }

    /** A rule with its capture coordinates turned k quarter turns inside a sizeX by sizeZ capture. */
    private static Rule turned(Rule rule, int k, int sizeX, int sizeZ) {
        if (k == 0) {
            return rule;
        }
        return new Rule(rule.room(), turn(rule.lever(), k, sizeX, sizeZ), turn(rule.from(), k, sizeX, sizeZ),
                turn(rule.to(), k, sizeX, sizeZ), rule.place(), rule.only());
    }

    private static int[] turn(int[] p, int k, int sizeX, int sizeZ) {
        int x = p[0];
        int z = p[2];
        for (int i = 0; i < k; i++) {
            // (x, z) -> (sizeZ-1-z, x): a quarter turn of a square; two of them are the half turn of any rectangle.
            int nx = sizeZ - 1 - z;
            int nz = x;
            int w = sizeX;
            x = nx;
            z = nz;
            sizeX = sizeZ;
            sizeZ = w;
        }
        return new int[]{x, p[1], z};
    }

    /** How many of the rule's gate blocks this capture holds in the rule's box. */
    private static int gateCount(RoomLibrary.Room room, Rule rule) {
        int n = 0;
        for (int x = Math.min(rule.from()[0], rule.to()[0]); x <= Math.max(rule.from()[0], rule.to()[0]); x++) {
            for (int y = Math.min(rule.from()[1], rule.to()[1]); y <= Math.max(rule.from()[1], rule.to()[1]); y++) {
                for (int z = Math.min(rule.from()[2], rule.to()[2]); z <= Math.max(rule.from()[2], rule.to()[2]); z++) {
                    String id = idAt(room, x, y, z);
                    if (!id.equals("air") && (rule.only().isEmpty() || rule.only().contains(id))) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * Every world position inside a rule's capture-local box, at this placement's rotation - or, for a rule that
     * names its blocks, only those of them standing there now.
     */
    private static List<BlockPos> box(SimRoomPuzzles.Placement p, Rule rule, ServerLevel level) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = Math.min(rule.from()[0], rule.to()[0]); x <= Math.max(rule.from()[0], rule.to()[0]); x++) {
            for (int y = Math.min(rule.from()[1], rule.to()[1]); y <= Math.max(rule.from()[1], rule.to()[1]); y++) {
                for (int z = Math.min(rule.from()[2], rule.to()[2]); z <= Math.max(rule.from()[2], rule.to()[2]); z++) {
                    BlockPos pos = SimRoomPuzzles.capturedPos(p, x, y, z);
                    if (rule.only().isEmpty() || rule.only().contains(
                            BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath())) {
                        out.add(pos);
                    }
                }
            }
        }
        return out;
    }

    /**
     * The library-wide sweep, from the captures alone (no floor needed): one line per rule - its lever block in the
     * capture (or that the sim places it), and how many of its box's captured blocks it takes - and one line per
     * captured lever no rule names. For the testkit and the build log; it changes nothing.
     */
    public static List<String> sweep() {
        List<String> out = new ArrayList<>();
        java.util.Set<String> bound = new java.util.HashSet<>();
        for (Rule written : RULES) {
            RoomLibrary.Room room = RoomLibrary.get(written.room());
            if (room == null) {
                out.add("RULE " + written.room() + " lever " + key(written.lever()) + ": no capture");
                continue;
            }
            Rule rule = fit(written, room);
            bound.add(room.name.toLowerCase(java.util.Locale.ROOT) + "|" + key(rule.lever()));
            String lever = idAt(room, rule.lever()[0], rule.lever()[1], rule.lever()[2]);
            int takes = 0;
            int box = 0;
            for (int x = Math.min(rule.from()[0], rule.to()[0]); x <= Math.max(rule.from()[0], rule.to()[0]); x++) {
                for (int y = Math.min(rule.from()[1], rule.to()[1]); y <= Math.max(rule.from()[1], rule.to()[1]); y++) {
                    for (int z = Math.min(rule.from()[2], rule.to()[2]); z <= Math.max(rule.from()[2], rule.to()[2]); z++) {
                        box++;
                        String id = idAt(room, x, y, z);
                        if (!id.equals("air") && (rule.only().isEmpty() || rule.only().contains(id))) {
                            takes++;
                        }
                    }
                }
            }
            out.add("RULE " + room.name + " lever " + key(rule.lever())
                    + (rule != written ? " (table " + key(written.lever()) + ", capture turned)" : "") + " is " + lever
                    + (rule.place() != null ? " (placed " + rule.place().getSerializedName() + ")" : "")
                    + ", takes " + takes + " of " + box);
        }
        for (String name : RoomLibrary.names()) {
            RoomLibrary.Room room = RoomLibrary.get(name);
            if (room == null) {
                continue;
            }
            for (int y = room.minY; y <= room.maxY; y++) {
                for (int z = 0; z < room.sizeZ; z++) {
                    for (int x = 0; x < room.sizeX; x++) {
                        if (idAt(room, x, y, z).equals("lever")
                                && !bound.contains(room.name.toLowerCase(java.util.Locale.ROOT) + "|" + x + "," + y + "," + z)) {
                            out.add("UNBOUND " + room.name + " lever " + x + "," + y + "," + z);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String key(int[] p) {
        return p[0] + "," + p[1] + "," + p[2];
    }

    /** The block id (path only) a capture holds here; "air" outside it or for a column never read. */
    private static String idAt(RoomLibrary.Room room, int x, int y, int z) {
        short i = room.at(x, y, z);
        if (i < 0 || i >= room.palette.size()) {
            return "air";
        }
        String s = room.palette.get(i);
        int bracket = s.indexOf('[');
        if (bracket >= 0) {
            s = s.substring(0, bracket);
        }
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
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
