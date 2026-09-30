package com.killer560.hub.roomsim;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import com.killer560.hub.livemap.DungeonLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wither doors for the dungeon sim, in killer560's own words: "the last starred mob in rooms that have a
 * wither door need to drop the key to open the door, the blocks [for] doors specifically after clicked with
 * a key needs to change to barrier blocks, and then shortly there after the barrier blocks fall away".
 *
 * <p>This file owns exactly that: the door registry, the key item, and the open sequence (BARRIER, then
 * removed). It does NOT track which mob is "the last starred mob" or hook mob deaths - that belongs to
 * whichever file owns mob tracking (SimMobs, per the handoff), which should call {@link #dropKeyAt} once it
 * decides a key should fall.
 *
 * <p>Same safety story as the rest of {@code roomsim}: everything here is gated on {@link SimState}, and
 * every world write happens on the integrated server's own thread against the server's own level/player,
 * never the client entity - see {@link SimAbilities}' class doc for why that split is correct only here.
 */
public final class SimDoors {

    /**
     * A dungeon wither door's block footprint, in this sim: 3 wide, 4 tall, 1 thick.
     *
     * <p>ASSUMPTION, not a measured Hypixel fact (the task explicitly says not to verify this against real
     * Hypixel): the door spans X (width) and Y (height), one block thick along Z, and {@code centre} is the
     * bottom-middle block of that slab - i.e. the door occupies x in [centre.x-1, centre.x+1], y in
     * [centre.y, centre.y+3], z == centre.z. A door built the other way (thick along X instead of Z) would
     * be registered wrong by this class; whatever calls {@link #addDoor} is responsible for passing a centre
     * consistent with this orientation, or this class needs extending first.
     */
    private static final int DOOR_WIDTH = 3;
    private static final int DOOR_HEIGHT = 4;

    /** The item id {@link SimItems} and everything else in this mod reads back via CUSTOM_DATA "id". */
    private static final String WITHER_KEY_ID = "WITHER_KEY";

    /**
     * How long the door stays as BARRIER before the blocks are removed. killer560's spec only says "shortly
     * there after" - we have no measured Hypixel timing for this, so 1 second (20 server ticks) is a chosen
     * placeholder, not a fact. Change this constant if he reports the real feel is off.
     */
    private static final int OPEN_DELAY_TICKS = 20;

    /** Every door this sim session knows about. */
    /** Secret chests a doorway carve has removed. Reported by the sim's build so this cannot go unnoticed. */
    public static int CHESTS_CARVED_AWAY;

    /** How many blocks of floor the carve had to lay this build, because the doorway had none. */
    public static int FLOORED;

    /**
     * Every position a doorway carve opened, so nothing gets put back into a doorway afterwards.
     *
     * <p>The build deliberately carves doorways BEFORE placing secrets, because the reverse order had the
     * carve delete a secret chest that happened to sit in a doorway - about one chest on one floor in three.
     * Flipping it fixed that and created the mirror image: a secret chest placed INTO a carved opening, which
     * is a door you cannot walk through. Scenario 81 found one between Blood and Pedestal on 2026-09-30,
     * blocked by "stone_brick_stairs+stone_bricks+chest".
     *
     * <p>Neither order is right on its own; what is needed is for the secret placement to know where the
     * openings are. A missing chest is a far smaller problem than a sealed door, so a secret that lands in one
     * is skipped and said out loud rather than placed.
     */
    private static final java.util.Set<BlockPos> CARVED = new java.util.HashSet<>();

    /** Whether this position is inside an opening a doorway carve made. */
    public static boolean isCarvedDoorway(BlockPos at) {
        return CARVED.contains(at);
    }

    /** How many positions the carves opened on this floor. */
    public static int carvedCount() {
        return CARVED.size();
    }

    private static final List<Door> DOORS = new CopyOnWriteArrayList<>();

    /** Reverse lookup so a right-click on any one of a door's blocks finds the whole door in O(1). */
    private static final Map<BlockPos, Door> BLOCK_INDEX = new ConcurrentHashMap<>();

    /** Doors currently mid-open: door -> ticks remaining until the BARRIER blocks are removed. */
    private static final Map<Door, Integer> PENDING = new ConcurrentHashMap<>();

    private SimDoors() {
    }

    /**
     * One door's block positions, and which kind of door it is.
     *
     * <p>The type is carried so the entrance gate can be taken down by clearing the blocks this class wrote,
     * rather than hunting for infested chiseled stone brick across the grid - see {@link #openEntranceGate}.
     * A record so it works as a map key by structural equality.
     */
    private record Door(List<BlockPos> blocks, int type) {
    }

    /**
     * Cuts an actual doorway through the wall between two rooms.
     *
     * <p>killer560 (2026-09-28): "the map is not full nor is it possible to get to every room from the
     * starting room." The second half was this: NOTHING ever cut an opening. Every captured room is pasted
     * with all four of its walls intact, so a generated floor was a set of sealed boxes standing next to each
     * other. The map data said there were doors between them and the world disagreed.
     *
     * <p>The opening is carved through BOTH walls and the seam between them, because a room's own wall and its
     * neighbour's are two separate walls with a gap - punching through only one leaves a dead end that looks
     * like a doorway.
     *
     * <p>A normal door is left open. Blood and entrance doors are filled with barriers and registered, so the
     * existing key and countdown logic can open them exactly as it already does for wither doors.
     *
     * @param alongX true when the two rooms are side by side on the X axis
     */
    public static void carveDoorway(ServerLevel level, BlockPos centre, boolean alongX, int doorType) {
        int floorY = findFloor(level, centre);
        // Three wide and four high, the shape of a Catacombs doorway, and deep enough to pass through both
        // walls plus the seam.
        int halfWidth = 1;
        int depth = 3;
        // The blocks a shut door is actually MADE of on Hypixel, not a barrier.
        //
        // killer560 (2026-09-29): "Once the start finishes it needs to remove the blocks for the gate. Those
        // infested chizzledd blocks." The green room's gate is infested chiseled stone brick - it is what
        // DoorScanner and WitherDoorScanner both recognise an entrance door by on the real server - and a
        // wither door is a slab of coal blocks. Filling all three kinds with an invisible barrier meant the
        // sim's doors were holes you could not walk through with nothing to see, so there was no gate to
        // watch disappear when the run started.
        net.minecraft.world.level.block.state.BlockState fill = switch (doorType) {
            case DungeonLayout.DOOR_ENTRANCE -> Blocks.INFESTED_CHISELED_STONE_BRICKS.defaultBlockState();
            case DungeonLayout.DOOR_WITHER -> Blocks.COAL_BLOCK.defaultBlockState();
            case DungeonLayout.DOOR_BLOOD -> Blocks.RED_TERRACOTTA.defaultBlockState();
            default -> null;
        };
        java.util.List<BlockPos> filled = new java.util.ArrayList<>();
        for (int d = -depth; d <= depth; d++) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                for (int y = floorY; y < floorY + DOOR_HEIGHT; y++) {
                    BlockPos at = alongX
                            ? new BlockPos(centre.getX() + d, y, centre.getZ() + w)
                            : new BlockPos(centre.getX() + w, y, centre.getZ() + d);
                    // Recorded, so the next floor's clear reaches it - a carve wrote straight into the level
                    // and was outside the bounds the paste recorded.
                    SimBuildQueue.touched(at.getX(), at.getZ());
                    if (level.getBlockState(at).is(Blocks.CHEST)) {
                        CHESTS_CARVED_AWAY++;
                    }
                    level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                    CARVED.add(at.immutable());
                    if (fill != null && d == 0) {
                        level.setBlockAndUpdate(at, fill);
                        filled.add(at.immutable());
                    }
                }
            }
        }
        // LAY A FLOOR where the carve leaves none.
        //
        // The carve only ever removes the air ABOVE a floor it searched for; it never puts one down. So a
        // doorway can be completely clear and still impossible to walk through, because what is under it is a
        // hole. Measured 2026-09-30 by scenario 81, twice: between "Crypt" and "Mines" the floor was two
        // blocks lower for the three blocks on the approach side, and between "Balcony" and "Archway" the whole
        // 3x7 box was open from four below the doorway to nine above it - 21 of 21 columns air at every layer.
        // He walked in and fell. The report said "nothing solid across the seam", which was true and was the
        // point.
        //
        // The other two explanations were eliminated first rather than assumed away: every column of all 135
        // captures was read, so it is not missing capture data, and the build's own audit says every carved
        // door is backed by a measured doorway in both rooms, so it is not punching through a wall. What is
        // left is that the two rooms genuinely meet at different heights, and a real Catacombs connector is
        // always floored - you never walk out of a door into a void.
        //
        // Only where it is NOT already solid, and only the one layer directly under the doorway, so a room's
        // own floor is never overwritten and a pit deeper in the room is untouched apart from being capped at
        // the doorway's own level.
        for (int d = -depth; d <= depth; d++) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos under = alongX
                        ? new BlockPos(centre.getX() + d, floorY - 1, centre.getZ() + w)
                        : new BlockPos(centre.getX() + w, floorY - 1, centre.getZ() + d);
                if (!level.getBlockState(under).getCollisionShape(level, under).isEmpty()) {
                    continue;
                }
                level.setBlockAndUpdate(under, Blocks.STONE_BRICKS.defaultBlockState());
                // Recorded like the carve itself, or the next floor's clear does not reach it and the block is
                // left standing in the middle of the next map.
                SimBuildQueue.touched(under.getX(), under.getZ());
                CARVED.add(under.immutable());
                FLOORED++;
            }
        }
        if (fill != null && !filled.isEmpty()) {
            Door door = new Door(java.util.List.copyOf(filled), doorType);
            DOORS.add(door);
            for (BlockPos p : door.blocks()) {
                BLOCK_INDEX.put(p, door);
            }
        }
    }

    /**
     * Bricks up a doorway that leads nowhere.
     *
     * <p>A captured room carries its own doorways in its blocks, so a room placed with a doorway facing the
     * edge of the map - or facing a neighbour that has no doorway back - is a three-by-four hole into the
     * void. The layout keeps those to about five a floor and cannot always avoid them, so they are filled in.
     *
     * <p>Stone brick, which is what the wall around it is made of, and only the wall plane itself: filling the
     * gap between the two rooms as well would wall off a doorway on the OTHER side of the same seam.
     */
    public static void sealDoorway(ServerLevel level, BlockPos centre, boolean alongX) {
        int floorY = findFloor(level, centre);
        for (int d = -1; d <= 1; d++) {
            for (int w = -1; w <= 1; w++) {
                for (int y = floorY - 1; y < floorY + DOOR_HEIGHT + 1; y++) {
                    BlockPos at = alongX
                            ? new BlockPos(centre.getX() + d, y, centre.getZ() + w)
                            : new BlockPos(centre.getX() + w, y, centre.getZ() + d);
                    if (!level.getBlockState(at).isAir()) {
                        continue;   // never overwrite something that is already there
                    }
                    SimBuildQueue.touched(at.getX(), at.getZ());
                    level.setBlockAndUpdate(at, Blocks.STONE_BRICKS.defaultBlockState());
                }
            }
        }
    }

    /**
     * The floor level at a doorway.
     *
     * <p>Searched rather than assumed, for the same reason the player's landing spot is: rooms differ in floor
     * height, and a doorway cut at a fixed Y is a hole in a wall halfway up on half the floors.
     */
    /**
     * The walking floor at a doorway, searched DOWNWARD through the band a Catacombs doorway can be in.
     *
     * <p>This used to scan upward from the very bottom of the world and take the first solid block with two
     * air above it, which is the LOWEST surface in the column - a basement, a cave, the underside of the room.
     * The carve then happened down there and the real doorway stayed solid. It only ever worked because
     * captures used to start at y60, so the lowest surface was the walking floor by luck; once rooms were
     * captured at full height, with content down to y15 in places, scenario 81 found two of eight doorways
     * impassable on a single floor - blocked by stone bricks and by cobblestone with stairs, both at head
     * height, with a carved opening sitting uselessly below them.
     *
     * <p>Every doorway on a Catacombs floor is at the same level, on the dungeon floor around y68-70, with
     * roofs up near y99-107. So the search runs down from y90 - above a doorway's four-block opening, below
     * any roof - to y55, and takes the first surface it meets, which is the highest one in that band rather
     * than the deepest one in the world. {@link SimAltitude#toWorld} maps those to wherever this floor was
     * built. The y69 fallback is unchanged: it is the real dungeon floor.
     *
     * <p><b>The top of the band has to be INSIDE the opening, not above it.</b> Measured 2026-09-30 by
     * printing the carve volume layer by layer for every doorway the player could not walk through (scenario
     * 81's seam profile): the failing doorways all had a perfect 21-of-21 open 3x7 carve FIVE BLOCKS ABOVE the
     * walking floor, with the floor layer itself only 13 of 21 open. So the carve was happening exactly as
     * written - at a "floor" this search had found at capture y74. With the band starting at y75, the scan
     * begins above the doorway's lintel, and the first surface it meets coming down is the TOP OF THAT LINTEL
     * whenever the seam above the door is open (which it often is, up to the roof). Starting at y72 instead -
     * the top block of a doorway whose floor is y69 - the scan begins inside the opening, walks down through
     * air, and lands on the doorway's own floor. It still covers floors from y67 to y72, which is every
     * Catacombs doorway.
     */
    private static int findFloor(ServerLevel level, BlockPos near) {
        int top = SimAltitude.toWorld(DOORWAY_SEARCH_TOP);
        int bottom = SimAltitude.toWorld(DOORWAY_SEARCH_BOTTOM);
        for (int y = top; y >= bottom; y--) {
            BlockPos at = new BlockPos(near.getX(), y, near.getZ());
            if (level.getBlockState(at).isAir()) {
                continue;
            }
            // A CHEST is not a floor, and mistaking one for a floor is what sealed a doorway.
            //
            // Some rooms have a secret chest in their perimeter at the doorway height - Pedestal has one at
            // capture y70 on its z=0 edge, Archway one at y70 near the same edge, and y70 is exactly where a
            // doorway opening starts. Treating that chest as the surface returned y71, so the carve ran 71..74
            // and left the chest itself at 70, sitting in the middle of the opening. Scenario 81 reported the
            // doorway between Pedestal and Archway as "blocked by chest" with the player having walked 3.3
            // blocks into an opening that was carved one block too high. Stepping past it finds the real floor
            // underneath, and the carve then removes the chest as it always did.
            if (level.getBlockState(at).is(Blocks.CHEST)
                    || level.getBlockState(at).is(Blocks.TRAPPED_CHEST)) {
                continue;
            }
            if (level.getBlockState(at.above()).isAir()
                    && level.getBlockState(at.above(2)).isAir()) {
                return y + 1;
            }
        }
        return SimAltitude.toWorld(69);
    }

    /**
     * The band a doorway's floor can be in, in CAPTURE coordinates. See {@link #findFloor}.
     *
     * <p>Tight on purpose, and both ends were found by being wrong. Searching UP from the bottom of the world
     * found basements. Searching DOWN from y90 found a room's internal upper floor instead of its ground
     * floor, which is still a surface with air above it - scenario 81 went from two doorways impassable to
     * two different ones, both blocked by plain stone brick with the player having moved a fifth of a block.
     * Searching down from y75 found the top of the doorway's own LINTEL, because the seam above a door is
     * usually open: that put the carve five blocks above the floor, which is the measurement in
     * {@link #findFloor}'s doc. A Catacombs doorway is on the dungeon floor at y68-70 and is four blocks tall,
     * so y72 is the top block OF the opening rather than the first block above it, and y58 is below the floor
     * and above any basement.
     */
    private static final int DOORWAY_SEARCH_TOP = 72;
    private static final int DOORWAY_SEARCH_BOTTOM = 58;

    /**
     * Registers a wither door centred on {@code centre}. See {@link #DOOR_WIDTH}/{@link #DOOR_HEIGHT} for
     * the size assumption this bakes in.
     */
    public static void addDoor(BlockPos centre) {
        if (centre == null) {
            return;
        }
        List<BlockPos> blocks = new ArrayList<>(DOOR_WIDTH * DOOR_HEIGHT);
        BlockPos min = centre.offset(-(DOOR_WIDTH / 2), 0, 0);
        BlockPos max = centre.offset(DOOR_WIDTH / 2, DOOR_HEIGHT - 1, 0);
        for (BlockPos p : BlockPos.betweenClosed(min, max)) {
            blocks.add(p.immutable());
        }
        Door door = new Door(List.copyOf(blocks), DungeonLayout.DOOR_WITHER);
        DOORS.add(door);
        for (BlockPos p : door.blocks()) {
            BLOCK_INDEX.put(p, door);
        }
    }

    /**
     * Hooks right-click-with-a-wither-key on a registered door block, and the server-tick countdown that
     * turns a door's BARRIER blocks back to air once {@link #OPEN_DELAY_TICKS} has passed.
     */
    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // level.isClientSide(): this event also fires server-side; without this check the door would
            // try to open twice per real click, exactly the double-fire AbilityCooldownFeature's own
            // UseBlockCallback registration guards against the same way.
            if (!level.isClientSide() || !SimState.canAct(client) || player != client.player) {
                return InteractionResult.PASS;
            }
            Door door = BLOCK_INDEX.get(hitResult.getBlockPos());
            if (door == null || PENDING.containsKey(door)) {
                // Not a door block, or this door is already mid-open - do not consume a second key for a
                // barrier phase that is already running.
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            if (!WITHER_KEY_ID.equals(CheatUtils.skyblockId(held))) {
                return InteractionResult.PASS;
            }
            openDoor(client, door, hand);
            return InteractionResult.SUCCESS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SimState.isActive() || PENDING.isEmpty()) {
                return;
            }
            try {
                List<Door> ready = new ArrayList<>();
                for (Map.Entry<Door, Integer> entry : PENDING.entrySet()) {
                    int remaining = entry.getValue() - 1;
                    if (remaining <= 0) {
                        ready.add(entry.getKey());
                    } else {
                        entry.setValue(remaining);
                    }
                }
                if (ready.isEmpty()) {
                    return;
                }
                ServerLevel level = server.overworld();
                for (Door door : ready) {
                    PENDING.remove(door);
                    for (BlockPos p : door.blocks()) {
                        level.removeBlock(p, false);
                    }
                }
            } catch (RuntimeException e) {
                // One broken door must never stall the server tick or every other pending door with it.
            }
        });
    }

    /**
     * Turns a door's blocks to BARRIER immediately and schedules their removal. Consumes the key from the
     * SERVER's copy of the player's held item, not the client stack the callback handed us - the same
     * "server is authoritative" rule {@link SimAbilities#teleport} and {@link SimItems#giveOnServer} follow.
     */
    private static void openDoor(Minecraft client, Door door, InteractionHand hand) {
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        UUID uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null) {
                sp.getItemInHand(hand).shrink(1);
            }
            ServerLevel level = server.overworld();
            for (BlockPos p : door.blocks()) {
                level.setBlockAndUpdate(p, Blocks.BARRIER.defaultBlockState());
            }
        });
        // Counted down by the ServerTickEvents listener in register(), not a sleeping thread - a sleeping
        // thread would block whichever thread called this, and would not survive the sim world closing.
        PENDING.put(door, OPEN_DELAY_TICKS);
        ModChat.send("Sim", ModChat.text("Wither door opening..."));
    }

    /**
     * Opens the door containing {@code blockPos} without needing a key or a click.
     *
     * <p>For tests. The sequence this triggers - blocks to barriers, then barriers away a second later - is the
     * part most worth proving, and driving it through a real right-click means simulating an interaction the
     * gametest harness has no clean way to produce. Exposing the same path the key-click takes means the test
     * exercises the real code rather than a copy of it.
     *
     * @return whether a door was found at that position
     */
    public static boolean openForTest(Minecraft client, BlockPos blockPos) {
        Door door = BLOCK_INDEX.get(blockPos);
        if (door == null || PENDING.containsKey(door)) {
            return false;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (BlockPos p : door.blocks()) {
                level.setBlockAndUpdate(p, Blocks.BARRIER.defaultBlockState());
            }
        });
        PENDING.put(door, OPEN_DELAY_TICKS);
        return true;
    }

    /**
     * Takes the green room's gate down, the way it drops when a run starts.
     *
     * <p>killer560 (2026-09-29): "Once the start finishes it needs to remove the blocks for the gate."
     *
     * <p>Two things happen, because either alone leaves a gate standing. The registered entrance door goes
     * through the normal open sequence, so whatever {@code /start} was handed opens. And then every infested
     * chiseled stone brick left anywhere on the grid is removed - that is the block the gate is made of, and
     * sweeping for it catches gates that came in as part of a captured room's own geometry, which nothing
     * registered and nothing would ever have opened.
     *
     * @return how many gate blocks were removed
     */
    public static int openEntranceGate(Minecraft client, BlockPos registered) {
        if (!SimState.canAct(client)) {
            return 0;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return 0;
        }
        if (registered != null) {
            openForTest(client, registered);
        }
        int[] removed = {0};
        server.execute(() -> {
            ServerLevel level = server.overworld();
            // Clear the blocks this class WROTE, not every infested chiseled stone brick it can find.
            //
            // The sweep this replaces walked the whole grid section by section and skipped any chunk
            // hasChunk called unloaded, on the reasoning that "the gate is in the green room, which is where
            // he is standing when /start runs, so it is always loaded". That holds for one gate. Scenario 81
            // built a floor whose entrance room had TWO gated doorways, 32 blocks apart, and walked the
            // player face-first into both of them after the countdown had finished and said GO.
            //
            // Every gate block's position is already recorded at carve time, so there is nothing to search
            // for: a dozen positions per door, no chunk scan, and no assumption about what is loaded. It also
            // cannot delete a room's own decorative infested brick, which the sweep could.
            for (Door door : DOORS) {
                if (door.type() != DungeonLayout.DOOR_ENTRANCE) {
                    continue;
                }
                for (BlockPos at : door.blocks()) {
                    if (!level.getBlockState(at).isAir()) {
                        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                        removed[0]++;
                    }
                    BLOCK_INDEX.remove(at);
                }
            }
            DOORS.removeIf(d -> d.type() == DungeonLayout.DOOR_ENTRANCE);
        });
        return removed[0];
    }

    /** Ticks the pending countdown holds, so a test knows how long to wait rather than guessing. */
    public static int openDelayTicks() {
        return OPEN_DELAY_TICKS;
    }

    /**
     * A wither key stack, built the exact way {@link SimItems#build} builds every other sim item: a plain
     * vanilla stack carrying the real Skyblock id in CUSTOM_DATA "id". {@code Items.TRIPWIRE_HOOK} is a
     * placeholder base like SimItems' own choices (Items.BONE for Spirit Sceptre, Items.FEATHER for
     * Tactical Insertion) - a stand-in, not a claim about Hypixel's real item model.
     */
    public static ItemStack createWitherKey() {
        ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Wither Key"));
        CompoundTag tag = new CompoundTag();
        tag.putString("id", WITHER_KEY_ID);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        // The same Skyblock tooltip everything else the sim hands him gets.
        SimItems.applySkyblockTooltip(stack, WITHER_KEY_ID);
        return stack;
    }

    /**
     * Drops a wither key item entity at {@code pos}, on the server. Meant to be called by whichever file
     * tracks "the last starred mob died" - this class does not track mobs itself.
     */
    public static void dropKeyAt(Minecraft client, Vec3 pos) {
        if (!SimState.canAct(client) || pos == null) {
            return;
        }
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            ItemEntity entity = new ItemEntity(level, pos.x, pos.y, pos.z, createWitherKey());
            level.addFreshEntity(entity);
        });
    }

    /** Forgets every door and pending barrier removal - call when a run restarts or the sim session ends. */
    /** Doors still registered and shut, for the sim's sidebar. */
    public static int doorsRemaining() {
        return DOORS.size();
    }

    /** Wither keys in his inventory, counted the same way the real key tracker would. */
    public static int keysHeld() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            if (data != null && WITHER_KEY_ID.equals(data.copyTag().getStringOr("id", ""))) {
                n += stack.getCount();
            }
        }
        return n;
    }

    public static void clear() {
        DOORS.clear();
        BLOCK_INDEX.clear();
        CARVED.clear();
        PENDING.clear();
    }
}
