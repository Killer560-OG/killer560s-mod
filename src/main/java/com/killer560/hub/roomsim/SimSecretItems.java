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

    /**
     * Drops that have appeared and not yet been collected, each against the block it was placed on.
     *
     * <p>The anchor is kept because a sim secret must not DRIFT. Measured 2026-09-30 with the uncollected
     * report: a drop climbed 277 -> 280 -> 285 -> 289 -> 295 while he stood underneath it, so scenario 81
     * reported "the item secret appeared but standing on it did not collect it" - correctly. What pushed it
     * was NOT established; the report was extended to print its velocity and the fluid around it, and the
     * next run collected its drop instantly so there was nothing to read. The anchor therefore corrects drift
     * from whatever cause rather than from a named one - see {@link #tickCollected}.
     */
    private static final Map<UUID, BlockPos> LIVE = new ConcurrentHashMap<>();

    private static final org.slf4j.Logger LOGGER =
            com.killer560.hub.util.ModLog.get("killer560smod-roomsim");

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
        for (Map.Entry<UUID, BlockPos> entry : LIVE.entrySet()) {
            UUID id = entry.getKey();
            var entity = level.getEntity(id);
            if (entity == null || entity.isRemoved()) {
                LIVE.remove(id);
                SimScore.secretFound();
            } else {
                pin(entity, entry.getValue());
                reportWhyNotCollected(level, entity, entry.getValue());
            }
        }
    }

    /**
     * Puts a drop back on the block it was placed on.
     *
     * <p>A sim secret is a marker, not loot: it belongs on the block the room's capture says it is on, and it
     * has to stay there until he walks onto it. {@code setNoGravity} only stops it FALLING, and something in
     * at least one room lifted a drop 18 blocks over four seconds, which made a working pickup look broken.
     *
     * <p>Snapping back rather than making it immovable, because the drop still has to behave like an item for
     * the pickup itself: vanilla's collection test is a box overlap against the player, and an entity with its
     * physics disabled outright is easy to get subtly wrong in a way that stops that working.
     *
     * <p>This is a server-side entity in the local sim world. It is not the player, and none of the movement
     * rules that exist to keep Hypixel happy are in play here.
     */
    private static void pin(net.minecraft.world.entity.Entity drop, BlockPos anchor) {
        double x = anchor.getX() + 0.5;
        double y = anchor.getY() + 0.5;
        double z = anchor.getZ() + 0.5;
        // A quarter of a block, so the ordinary bob of a rendered item is left alone and only real drift is
        // corrected. Squared, to keep a square root out of a per-tick path.
        if (drop.distanceToSqr(x, y, z) > 0.0625) {
            drop.snapTo(x, y, z);
        }
        if (!drop.getDeltaMovement().equals(net.minecraft.world.phys.Vec3.ZERO)) {
            drop.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }
    }

    /** How often the uncollected report is written, in ticks. Once a second is plenty to read. */
    private static final int REPORT_EVERY_TICKS = 20;

    private static int reportTicks;

    /**
     * Says why a drop that is sitting there has not been picked up.
     *
     * <p>killer560 authorised this logger on 2026-09-30 after scenario 81 reported "the item secret appeared
     * but standing on it did not collect it" three times across five runs and two attempts to fix it from
     * reasoning alone - one of which was based on the drop having fallen, which it cannot, because it is
     * spawned with no gravity. Both attempts were guesses at a mechanism, and a guess had already been wrong
     * once.
     *
     * <p>So this prints the things that actually decide a vanilla pickup: where the drop is, where the player
     * is, the distance between them, the pickup delay still to run, and whether the player is in a state that
     * can pick anything up at all. One of those is the answer, and none of them was visible before.
     *
     * <p>Only while a drop is outstanding, only once a second, and only in a dev build - this is an
     * investigation aid, and {@link com.killer560.hub.util.ModLog} drops it in a release anyway.
     */
    private static void reportWhyNotCollected(ServerLevel level, net.minecraft.world.entity.Entity drop,
                                              BlockPos anchor) {
        if (!com.killer560.hub.BuildVariant.DEV_TOOLS || ++reportTicks % REPORT_EVERY_TICKS != 0) {
            return;
        }
        var players = level.players();
        if (players.isEmpty()) {
            LOGGER.info("[SimSecretItems] drop at {} is waiting - no player on the server",
                    drop.blockPosition().toShortString());
            return;
        }
        var player = players.get(0);
        int delay = drop instanceof net.minecraft.world.entity.item.ItemEntity item
                ? item.getAge() : -1;
        // WHY IT MOVES, as well as where it is. The first run of this logger showed the drop climbing away
        // from the player - 277, 280, 285, 289, 295 - which no-gravity and a zero initial velocity cannot
        // explain on their own. So the thing that would explain it is printed too: what it is standing in,
        // whether it is in a fluid, and what its velocity actually is.
        var at = drop.blockPosition();
        var state = level.getBlockState(at);
        LOGGER.info("[SimSecretItems] drop at {} ({}), player at {} ({}), {} blocks apart; "
                        + "player alive={} spectator={} gamemode={}; drop removed={} age={}; "
                        + "motion={} noGravity={} in={} fluid={} inWater={} inLava={}; "
                        + "anchor={} drift={}",
                at.toShortString(),
                String.format(java.util.Locale.US, "%.2f/%.2f/%.2f", drop.getX(), drop.getY(), drop.getZ()),
                player.blockPosition().toShortString(),
                String.format(java.util.Locale.US, "%.2f/%.2f/%.2f",
                        player.getX(), player.getY(), player.getZ()),
                String.format(java.util.Locale.US, "%.2f", drop.distanceTo(player)),
                player.isAlive(), player.isSpectator(), player.gameMode.getGameModeForPlayer(),
                drop.isRemoved(), delay,
                String.format(java.util.Locale.US, "%.4f/%.4f/%.4f",
                        drop.getDeltaMovement().x, drop.getDeltaMovement().y, drop.getDeltaMovement().z),
                drop.isNoGravity(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(),
                net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(
                        level.getFluidState(at).getType()).getPath(),
                drop.isInWater(), drop.isInLava(),
                // Drift is what the pin exists to keep at zero, so it is printed whether or not it is zero.
                // A report that only showed a problem would leave a working pin indistinguishable from a
                // drop that simply never moved.
                anchor.toShortString(),
                String.format(java.util.Locale.US, "%.3f", Math.sqrt(drop.distanceToSqr(
                        anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5))));
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
        LIVE.put(drop.getUUID(), at.immutable());
        SimBuildQueue.touched(at.getX(), at.getZ());
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> com.killer560.hub.util.ModChat.send("Sim",
                    com.killer560.hub.util.ModChat.dim("A secret item is here.")));
        }
    }
}
