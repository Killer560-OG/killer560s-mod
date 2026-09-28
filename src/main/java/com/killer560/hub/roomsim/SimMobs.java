package com.killer560.hub.roomsim;

import com.killer560.hub.util.FeatureGuard;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Combat dummies for the dungeon sim - zombies, skeletons, and Fels.
 *
 * <p>killer560's own spec: "all mobs have one HP and all damage does some amount that will one tap every mob
 * they do not need to have the exact same HP mob specifically don't even really need names." So there is no
 * naming scheme here on purpose - a spawned dummy is identified by its {@link Kind}, nothing else, and every
 * one dies in one hit. Fels are the interesting case: "[Fel] need [Fel] logic where they are that skull that
 * doesn't move and then once you get close, they become the upside down Enderman" - see {@link #wake} for what
 * that becomes here and what it explicitly does not.
 *
 * <p>Everything below is gated on {@link SimState#canAct}, same as {@link SimAbilities}: this package is the one
 * place this mod is allowed to write positions and spawn entities, and that is only true inside the sim's own
 * integrated server. All spawning happens there, via {@code server.execute(...)}, never on the client.
 */
public final class SimMobs {

    /** Hypixel's Fel wakes once a player closes to about this range. */
    public static final double FEL_WAKE_RADIUS = 4.0;
    private static final double FEL_WAKE_RADIUS_SQ = FEL_WAKE_RADIUS * FEL_WAKE_RADIUS;

    /** "all mobs have one HP" - killer560. Applies to zombies, skeletons, and the enderman a Fel wakes into. */
    private static final double ONE_HP = 1.0;

    public enum Kind {
        ZOMBIE, SKELETON, FEL
    }

    /** Every entity this class has spawned (dummies and woken Fels), for {@link #clear}. */
    private static final List<UUID> SPAWNED = new CopyOnWriteArrayList<>();

    /** UUIDs of spawned mobs flagged "starred". Stale (dead) entries are harmless and are wiped by clear(). */
    private static final List<UUID> STARRED = new CopyOnWriteArrayList<>();

    /** Dormant Fel markers not yet woken into an enderman. */
    private static final List<FelMarker> FELS = new CopyOnWriteArrayList<>();

    /**
     * Published each server tick by {@link #refreshStarred}, read directly by {@link #starredAliveIn} and
     * {@link #anyStarredAlive}. A volatile reference to an immutable list is the whole thread-safety story: the
     * refresh runs on the server thread (inside {@code server.execute}), those two queries are meant to be
     * called from whichever thread the door/key logic runs on, and swapping an immutable reference needs no
     * lock in either direction.
     */
    private static volatile List<StarredEntry> starredSnapshot = List.of();

    /** True once any starred mob has been alive; lets {@link #refreshStarred} tell "just spawned, none yet" (0,
     *  never fire) apart from "the last one just died" (nonzero, then 0 - fire once). Server thread only. */
    private static boolean hadStarred;

    /** Set by whichever file owns the wither-key drop and the doors - not this one; see the class prompt. */
    private static volatile Runnable onLastStarredDeath;

    private SimMobs() {
    }

    /** One position snapshot of a starred mob still alive, taken on the server thread. */
    private record StarredEntry(UUID id, double x, double y, double z) {
    }

    /** A dormant Fel: an invisible armour stand wearing a skull. See {@link #spawnFel}. */
    private static final class FelMarker {
        final ArmorStand skull;
        final boolean starred;
        boolean woken;

        FelMarker(ArmorStand skull, boolean starred) {
            this.skull = skull;
            this.starred = starred;
        }
    }

    public static void register() {
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("SimMobs.tick", SimMobs::tick));
    }

    private static void tick(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        // Reading and mutating mob/armour-stand state has to happen on the server thread, same reasoning as
        // SimAbilities.teleport: the integrated server, not the client, owns these entities.
        server.execute(() -> {
            ServerLevel level = server.overworld();
            wakeFels(level);
            refreshStarred(level);
        });
    }

    public static void spawn(Minecraft client, BlockPos pos, Kind kind) {
        spawn(client, pos, kind, false);
    }

    public static void spawnStarred(Minecraft client, BlockPos pos, Kind kind) {
        spawn(client, pos, kind, true);
    }

    private static void spawn(Minecraft client, BlockPos pos, Kind kind, boolean starred) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerLevel level = server.overworld();
            switch (kind) {
                case ZOMBIE -> spawnDummy(level, new SimZombie(EntityType.ZOMBIE, level), pos, starred);
                case SKELETON -> spawnDummy(level, new SimSkeleton(EntityType.SKELETON, level), pos, starred);
                case FEL -> spawnFel(level, pos, starred);
            }
        });
    }

    /**
     * Places a one-HP zombie or skeleton. Server thread only - called from {@link #spawn}.
     *
     * <p>{@code setPersistenceRequired()} is the normal vanilla answer to "don't despawn", but it is not enough
     * here: {@code Mob.checkDespawn()} discards any hostile mob whose {@code EntityType.isAllowedInPeaceful()}
     * is false the moment the level's difficulty reads PEACEFUL, and it does that check BEFORE it ever looks at
     * persistence (confirmed with {@code javap -c} against {@code Mob.class} in the 26.1.2 jar). SimWorld opens
     * the sim on {@code Difficulty.PEACEFUL} on purpose, so a plain {@code Zombie}/{@code Skeleton} would vanish
     * on its very next tick. {@link #SimZombie} and {@link #SimSkeleton} fix that the one place this file can
     * reach without touching SimWorld: they override {@code checkDespawn()} to do nothing at all, which also
     * satisfies "not despawn" more completely than persistence alone would.
     */
    private static void spawnDummy(ServerLevel level, Mob mob, BlockPos pos, boolean starred) {
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(ONE_HP);
        mob.setHealth((float) ONE_HP);
        mob.setPersistenceRequired();
        mob.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        level.addFreshEntity(mob);
        SPAWNED.add(mob.getUUID());
        if (starred) {
            STARRED.add(mob.getUUID());
        }
    }

    /**
     * Places a dormant Fel: an invisible, no-base-plate armour stand wearing a skull, so only the skull shows -
     * an invisible armour stand still renders its equipped items, which is the whole trick. It is invulnerable
     * while dormant because a Fel is not a fightable mob yet on Hypixel either; it only becomes one once it
     * wakes, in {@link #wake}.
     */
    private static void spawnFel(ServerLevel level, BlockPos pos, boolean starred) {
        ArmorStand skull = new ArmorStand(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        skull.setInvisible(true);
        skull.setNoBasePlate(true);
        skull.setShowArms(false);
        skull.setInvulnerable(true);
        skull.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.SKELETON_SKULL));
        level.addFreshEntity(skull);
        FELS.add(new FelMarker(skull, starred));
    }

    /** Checks every dormant Fel against every player each tick and wakes the ones close enough. */
    private static void wakeFels(ServerLevel level) {
        if (FELS.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        for (FelMarker fel : FELS) {
            if (fel.woken || fel.skull.isRemoved()) {
                continue;
            }
            for (ServerPlayer player : players) {
                if (player.distanceToSqr(fel.skull) <= FEL_WAKE_RADIUS_SQ) {
                    wake(level, fel);
                    break;
                }
            }
        }
    }

    /**
     * Replaces a dormant Fel with an enderman where it stood.
     *
     * <p><b>What this does not do:</b> render it upside down. killer560's spec is explicit that the woken form
     * is "the upside down Enderman", and 26.1.2 has no clean way to do that from this package: there is no
     * upside-down {@code Pose}, and flipping how one specific entity renders needs a client-side renderer
     * registration (an {@code EntityRendererProvider} override or a matching mixin on the enderman renderer),
     * which is wiring that belongs with whichever file registers this mod's entity renderers - not a spawn-and-
     * track class, and out of scope for this one file. So the wake and the replace are real; the enderman just
     * renders upright until that renderer piece is added elsewhere. Faking it with, say, a scaled-and-flipped
     * armour stand instead of a real enderman would have hidden that gap instead of naming it.
     */
    private static void wake(ServerLevel level, FelMarker fel) {
        fel.woken = true;
        double x = fel.skull.getX();
        double y = fel.skull.getY();
        double z = fel.skull.getZ();
        fel.skull.discard();

        SimEnderman enderman = new SimEnderman(EntityType.ENDERMAN, level);
        enderman.getAttribute(Attributes.MAX_HEALTH).setBaseValue(ONE_HP);
        enderman.setHealth((float) ONE_HP);
        enderman.setPersistenceRequired();
        enderman.setPos(x, y, z);
        level.addFreshEntity(enderman);
        SPAWNED.add(enderman.getUUID());
        if (fel.starred) {
            STARRED.add(enderman.getUUID());
        }
    }

    /**
     * Rebuilds {@link #starredSnapshot} from whatever is still alive, and fires the last-death hook on the 0-to-
     * nonzero-to-0 transition. A dormant, un-woken starred Fel counts as alive: on Hypixel it has not died yet
     * either, it just has not been fought yet, and a room where it was never approached must not look like every
     * starred mob is already dead.
     */
    private static void refreshStarred(ServerLevel level) {
        List<StarredEntry> alive = new ArrayList<>();
        for (UUID id : STARRED) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.isAlive()) {
                alive.add(new StarredEntry(id, entity.getX(), entity.getY(), entity.getZ()));
            }
        }
        for (FelMarker fel : FELS) {
            if (fel.starred && !fel.woken && !fel.skull.isRemoved()) {
                alive.add(new StarredEntry(fel.skull.getUUID(), fel.skull.getX(), fel.skull.getY(), fel.skull.getZ()));
            }
        }
        starredSnapshot = List.copyOf(alive);

        if (!alive.isEmpty()) {
            hadStarred = true;
        } else if (hadStarred) {
            hadStarred = false;
            Runnable callback = onLastStarredDeath;
            if (callback != null) {
                callback.run();
            }
        }
    }

    /** How many starred mobs are still alive inside {@code area}. Door/key logic owns what happens at zero. */
    public static int starredAliveIn(AABB area) {
        int count = 0;
        for (StarredEntry entry : starredSnapshot) {
            if (area.contains(entry.x(), entry.y(), entry.z())) {
                count++;
            }
        }
        return count;
    }

    /** Whether any starred mob spawned by this class is still alive, anywhere in the sim. */
    public static boolean anyStarredAlive() {
        return !starredSnapshot.isEmpty();
    }

    /** Registers the "last starred mob just died" hook. Does not itself drop a key or open a door. */
    public static void setOnLastStarredDeath(Runnable callback) {
        onLastStarredDeath = callback;
    }

    /** Removes every mob and Fel marker this class spawned, for a run restart. Never touches anything else. */
    public static void clear(Minecraft client) {
        if (!SimState.canAct(client)) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        List<UUID> toDiscard = List.copyOf(SPAWNED);
        List<FelMarker> dormant = List.copyOf(FELS);
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (UUID id : toDiscard) {
                Entity entity = level.getEntity(id);
                if (entity != null) {
                    entity.discard();
                }
            }
            for (FelMarker fel : dormant) {
                if (!fel.skull.isRemoved()) {
                    fel.skull.discard();
                }
            }
        });
        SPAWNED.clear();
        STARRED.clear();
        FELS.clear();
        starredSnapshot = List.of();
        hadStarred = false;
    }

    /**
     * Plain {@link Zombie} minus the peaceful-discard half of {@code checkDespawn()} - see
     * {@link #spawnDummy}. {@code isSunSensitive()} is overridden too, belt-and-suspenders: dungeon rooms have
     * no sky exposure, but the spec asked for it explicitly ("there is no daylight, but persistence matters").
     */
    private static final class SimZombie extends Zombie {
        SimZombie(EntityType<? extends Zombie> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }

        @Override
        protected boolean isSunSensitive() {
            return false;
        }
    }

    /** Plain {@link Skeleton} minus {@code checkDespawn()} - see {@link #spawnDummy}. Skeleton has no separate
     *  sun-sensitivity override to disable in this version; checked with {@code javap} against Skeleton and
     *  AbstractSkeleton. */
    private static final class SimSkeleton extends Skeleton {
        SimSkeleton(EntityType<? extends Skeleton> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }
    }

    /** Plain {@link EnderMan} minus {@code checkDespawn()} - see {@link #wake}. */
    private static final class SimEnderman extends EnderMan {
        SimEnderman(EntityType<? extends EnderMan> type, Level level) {
            super(type, level);
        }

        @Override
        public void checkDespawn() {
        }
    }
}
