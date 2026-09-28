package com.killer560.hub.roomsim;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
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
    private static final List<Door> DOORS = new CopyOnWriteArrayList<>();

    /** Reverse lookup so a right-click on any one of a door's blocks finds the whole door in O(1). */
    private static final Map<BlockPos, Door> BLOCK_INDEX = new ConcurrentHashMap<>();

    /** Doors currently mid-open: door -> ticks remaining until the BARRIER blocks are removed. */
    private static final Map<Door, Integer> PENDING = new ConcurrentHashMap<>();

    private SimDoors() {
    }

    /** One door's block positions. A record so it works as a map key by structural equality. */
    private record Door(List<BlockPos> blocks) {
    }

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
        Door door = new Door(List.copyOf(blocks));
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
    public static void clear() {
        DOORS.clear();
        BLOCK_INDEX.clear();
        PENDING.clear();
    }
}
