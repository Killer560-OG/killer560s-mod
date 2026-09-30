package com.killer560.hub.roomsim;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Item secrets: held back until he is standing on them, then real and collectable.
 *
 * <p>killer560 (2026-09-29): "I cannot pick up items in rooms for the item secrets. Make the item secrets only
 * spawn in if i am within 3 blocks of them for longer than 5 ticks."
 *
 * <p>Both halves of that were broken. The drop was created with {@code setNeverPickUp()} - not a delay that
 * runs out, a permanent refusal - so it could be walked through forever, and nothing anywhere counted an item
 * secret as found even if it could have been. And all of them existed from the moment the floor was built,
 * which is a floor full of items rather than a floor with secrets in it.
 *
 * <p>So a position is registered here at build time and nothing is spawned. Once the player has been inside
 * {@link #TRIGGER_RANGE} of it for more than {@link #TRIGGER_TICKS}, the drop appears where it belongs, with a
 * normal pickup delay, and collecting it counts a secret the same way opening a chest does.
 *
 * <p>Everything runs on the integrated server's own thread against the server's own player - the same split
 * the rest of {@code roomsim} uses, and for the same reason: the server owns the entities.
 */
public final class SimSecretItems {

    /** Blocks he has to be within. His number. */
    private static final double TRIGGER_RANGE = 3.0;

    /** And for how many ticks. "longer than 5", so the sixth tick inside the range is the one that spawns it. */
    private static final int TRIGGER_TICKS = 5;

    /**
     * What an item secret turns out to be.
     *
     * <p>Real Catacombs secret items, chosen per position from the position itself so a room gives the same
     * item every time it is built - a secret that is a different item on the second run is a detail that
     * teaches the wrong thing. The base item is PAPER for all of them, which is what Hypixel itself sends.
     */
    private static final String[][] ITEMS = {
        {"DECOY", "Decoy"},
        {"TRAP", "Trap"},
        {"DEFUSE_KIT", "Defuse Kit"},
        {"INFLATABLE_JERRY", "Inflatable Jerry"},
        {"TRAINING_WEIGHTS", "Training Weights"},
        {"SPIRIT_LEAP", "Spirit Leap"},
    };

    /** Positions waiting for him to come close, and how many ticks he has been close for. */
    private static final Map<BlockPos, Integer> PENDING = new ConcurrentHashMap<>();

    /** Drops that have appeared and not yet been collected. */
    private static final List<UUID> LIVE = new CopyOnWriteArrayList<>();

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("killer560smod-roomsim");

    /** So a throw in the tick handler is reported once rather than twenty times a second, or never. */
    private static boolean warnedOnce;

    private SimSecretItems() {
    }

    /** Remembers a secret item's position. Nothing is spawned until he walks up to it. */
    public static void register(BlockPos at) {
        if (at != null) {
            PENDING.put(at.immutable(), 0);
        }
    }

    /** Forgets every pending and live item - call when a floor is rebuilt or the sim session ends. */
    public static void reset() {
        PENDING.clear();
        LIVE.clear();
        warnedOnce = false;
    }

    /** How many item secrets are still waiting to be found, for anything that wants to report progress. */
    public static int remaining() {
        return PENDING.size() + LIVE.size();
    }

    /**
     * How many have not appeared yet, separately from how many are lying around uncollected.
     *
     * <p>{@link #remaining()} adds the two together, so it reads the same whether a secret never spawned or
     * spawned and was never picked up - and those are different bugs. Scenario 81 could not tell them apart
     * until this existed.
     */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** How many drops are on the floor waiting to be collected. */
    public static int liveCount() {
        return LIVE.size();
    }

    /**
     * Where the item secrets that have not appeared yet are.
     *
     * <p>For scenario 81, which walks the player onto one to prove the proximity spawn and the pickup work.
     * A test cannot guess these - they come from the room database through two rotations - and a test that
     * asserted on a position it made up would prove nothing about the real ones.
     */
    public static List<BlockPos> pendingPositions() {
        return List.copyOf(PENDING.keySet());
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!SimState.isActive() || (PENDING.isEmpty() && LIVE.isEmpty())) {
                return;
            }
            try {
                ServerLevel level = server.overworld();
                // Not players().get(0): a placed miniboss is a ServerPlayer and so is in this list too
                // (ServerLevel$EntityCallbacks.onTrackingStart adds every one), and a secret must appear near
                // the real player, not near an NPC.
                var player = level.players().stream()
                        .filter(p -> !SimMiniboss.isPlaced(p))
                        .findFirst()
                        .orElse(null);
                if (player != null) {
                    tickPending(level, player.getX(), player.getY(), player.getZ());
                }
                tickCollected(level);
            } catch (RuntimeException e) {
                // One bad secret must never stall the server tick - but it must not vanish either. This was an
                // empty catch, and an empty catch on a per-tick handler is a feature that can stop working with
                // nothing anywhere to say so. Logged ONCE, because a throw here would repeat twenty times a
                // second and bury the log it is meant to explain.
                if (!warnedOnce) {
                    warnedOnce = true;
                    LOGGER.warn("Item secrets stopped ticking after an error; none will appear this run", e);
                }
            }
        });
    }

    /**
     * Counts up the ticks he has spent near each pending secret, and spawns the ones that are ready.
     *
     * <p>The counter RESETS when he steps out of range rather than accumulating, because "within 3 blocks for
     * longer than 5 ticks" means five ticks together - a secret that appears because he has walked past it six
     * times is not what was asked for and would make a room behave differently on a second pass.
     */
    private static void tickPending(ServerLevel level, double px, double py, double pz) {
        List<BlockPos> ready = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : PENDING.entrySet()) {
            BlockPos at = entry.getKey();
            double dx = at.getX() + 0.5 - px;
            double dy = at.getY() + 0.5 - py;
            double dz = at.getZ() + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz <= TRIGGER_RANGE * TRIGGER_RANGE) {
                int ticks = entry.getValue() + 1;
                if (ticks > TRIGGER_TICKS) {
                    ready.add(at);
                } else {
                    entry.setValue(ticks);
                }
            } else if (entry.getValue() != 0) {
                entry.setValue(0);
            }
        }
        for (BlockPos at : ready) {
            PENDING.remove(at);
            spawn(level, at);
        }
    }

    /**
     * Notices a drop that has gone, and counts it.
     *
     * <p>The only thing that can remove one of these is him picking it up: they have no despawn timer, they
     * cannot burn or fall out of the world where they sit, and a floor rebuild calls {@link #reset} before it
     * clears the level. If that ever stops being true this over-counts rather than under-counts, which is the
     * right way round for a practice score - a secret he did collect never goes unrecorded.
     */
    private static void tickCollected(ServerLevel level) {
        for (UUID id : LIVE) {
            var entity = level.getEntity(id);
            if (entity == null || entity.isRemoved()) {
                LIVE.remove(id);
                SimScore.secretFound();
            }
        }
    }

    private static void spawn(ServerLevel level, BlockPos at) {
        String[] kind = ITEMS[Math.floorMod(at.hashCode(), ITEMS.length)];
        ItemStack stack = new ItemStack(Items.PAPER, 1);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(kind[1]));
        CompoundTag tag = new CompoundTag();
        tag.putString("id", kind[0]);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        SimItems.applySkyblockTooltip(stack, kind[0]);

        ItemEntity drop = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, stack);
        // A SHORT delay, not setNeverPickUp(). Ten ticks is vanilla's own "just dropped" delay and stops the
        // item being swallowed in the same tick it appears, which would look like nothing happened at all.
        drop.setPickUpDelay(10);
        drop.setUnlimitedLifetime();
        drop.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        drop.setNoGravity(true);
        level.addFreshEntity(drop);
        LIVE.add(drop.getUUID());
        SimBuildQueue.touched(at.getX(), at.getZ());
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> com.killer560.hub.util.ModChat.send("Sim",
                    com.killer560.hub.util.ModChat.dim("A secret item is here.")));
        }
    }
}
