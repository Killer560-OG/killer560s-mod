package com.killer560.hub.roomsim;

import com.mojang.authlib.GameProfile;

import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A REAL Catacombs-style miniboss for the dungeon sim: a server-side player entity that Mob ESP recognises on
 * its own miniboss path, and that can be hit and killed like any other mob.
 *
 * <h2>The contract it has to satisfy</h2>
 * {@code mobesp/MobEspFeature} finds a miniboss with
 * <pre>entity instanceof Player p &amp;&amp; p != client.player &amp;&amp; p.getUUID().version() == 2
 *     &amp;&amp; MINIBOSS_NAMES.contains(p.getName().getString())</pre>
 * so the thing on the CLIENT has to be a {@code Player} instance, with a version-2 UUID, whose name is one of
 * Hypixel's five. Three of those are load-bearing and each one decided part of the design below:
 * <ul>
 * <li><b>A client {@code Player} can only come from an AddEntity packet of type {@code PLAYER}.</b>
 * {@code ClientPacketListener.createEntityFromPacket} (26.1.2 bytecode) builds a {@code RemotePlayer} from
 * {@code getPlayerInfo(uuid).getProfile()} for that type - and if the UUID is not already in the client's player
 * info map it logs "Server attempted to add player prior to sending player info" and drops the entity. So a
 * {@link ClientboundPlayerInfoUpdatePacket} with {@code ADD_PLAYER} has to reach the client BEFORE the entity is
 * added to the level. That is exactly what Hypixel does for its NPCs, and why the ESP's check is written around
 * v2 UUIDs in the first place.
 * <li><b>{@code Player.getName()} is the game profile's name and nothing else</b> - {@code getfield gameProfile;
 * GameProfile.name(); Component.literal} - so the name is not a custom name tag, it is the profile, and the
 * profile is what has to read "Shadow Assassin".
 * <li><b>A profile name is capped at 16 characters on the wire.</b> {@code ADD_PLAYER} encodes it with
 * {@code ByteBufCodecs.PLAYER_NAME}, which is {@code stringUtf8(16)}, and singleplayer does NOT skip
 * serialization: {@code Connection$3.initChannel} calls {@code configureInMemoryPipeline} ->
 * {@code configureSerialization}, which installs a real {@code PacketEncoder}/{@code PacketDecoder}. So
 * "Frozen Adventurer" (17 characters) cannot be sent at all - see {@link Name#FROZEN_ADVENTURER}.
 * </ul>
 *
 * <h2>Why it is a Fabric {@link FakePlayer} subclass</h2>
 * A bare {@code ServerPlayer} put through {@code addFreshEntity} crashes: {@code ChunkMap.addEntity} sees a
 * {@code ServerPlayer} and calls {@code updatePlayerStatus(player, true)}, which reaches the static
 * {@code ChunkMap.markChunkPendingToSend}, which reads {@code player.connection.chunkSender} with no null check.
 * {@code FakePlayer}'s constructor installs a {@code FakePlayerPacketListener} - a real
 * {@code ServerGamePacketListenerImpl} over a channel-less {@code Connection} whose {@code send} is a no-op - so
 * every one of those paths has something to talk to and nothing is ever transmitted. That is the whole reason to
 * inherit rather than write another one.
 * <p>
 * What {@code FakePlayer} does NOT do is get added to a level, and two of its overrides have to be undone here:
 * {@code isInvulnerableTo} returns a flat {@code true}, and {@code tick()} is empty (see {@link Miniboss#tick}).
 *
 * <h2>Safety</h2>
 * Nothing here is reachable without passing {@link SimState#canAct}: {@link #place} is package-private and its
 * only caller is {@link SimMobs}'s already-gated spawn path, which hands it a {@link ServerLevel} it can only
 * have got from a singleplayer server. This file adds no second door into the sim.
 */
public final class SimMiniboss {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("killer560smod-simminiboss");

    /**
     * The five names {@code MobEspFeature.MINIBOSS_NAMES} matches, and the only names worth placing: any other
     * string produces a player entity the ESP will not look at twice.
     */
    public enum Name {
        SHADOW_ASSASSIN("Shadow Assassin"),
        LOST_ADVENTURER("Lost Adventurer"),
        /**
         * 17 characters, which is one more than {@code ByteBufCodecs.PLAYER_NAME} ({@code stringUtf8(16)}) can
         * carry, and {@code Player.getName()} reads the profile name only - so there is no way to hand the
         * client an entity whose {@code getName()} is this. {@link #place} refuses it and says so rather than
         * placing something the ESP silently ignores.
         */
        FROZEN_ADVENTURER("Frozen Adventurer"),
        DIAMOND_GUY("Diamond Guy"),
        KING_MIDAS("King Midas");

        /** Longest name a game profile can carry over the wire - {@code ByteBufCodecs.PLAYER_NAME}. */
        static final int MAX_PROFILE_NAME = 16;

        private final String text;

        Name(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }

        boolean sendable() {
            return text.length() <= MAX_PROFILE_NAME;
        }
    }

    /** Placed minibosses, so their tab-list profile entry can be withdrawn once the entity is gone. */
    private static final List<UUID> PLACED = new CopyOnWriteArrayList<>();

    private SimMiniboss() {
    }

    /**
     * Places one miniboss and tells every real client about it. Server thread only.
     *
     * @return the placed entity, or null if it could not be placed - in which case the caller is expected to
     *         fall back to something that does work rather than leaving the room empty
     */
    static ServerPlayer place(ServerLevel level, BlockPos pos, Name name, double health) {
        if (!name.sendable()) {
            LOGGER.warn("Cannot place a \"{}\": a game profile name is capped at {} characters on the wire"
                            + " (ByteBufCodecs.PLAYER_NAME) and Player.getName() reads the profile only",
                    name.text(), Name.MAX_PROFILE_NAME);
            return null;
        }
        MinecraftServer server = level.getServer();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            // Nobody to send the ADD_PLAYER profile to. The entity would exist server-side and be dropped by
            // every client that later saw its AddEntity packet ("Server attempted to add player prior to sending
            // player info"), which is an invisible, unhittable miniboss - worse than none. Refuse, and let the
            // caller place a mob instead.
            LOGGER.warn("Not placing a miniboss at {}: no player is on the sim server yet to send its profile to",
                    pos);
            return null;
        }
        GameProfile profile = new GameProfile(npcUuid(), name.text());
        Miniboss boss;
        try {
            boss = new Miniboss(level, profile);
        } catch (RuntimeException | LinkageError e) {
            // A ServerPlayer constructor touches the player list, stats and advancements. If any of that ever
            // stops working, the sim must degrade to a mob rather than take the server tick down with it.
            LOGGER.warn("Could not build a miniboss player entity; the caller will fall back to a mob", e);
            return null;
        }
        boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        boss.setHealth((float) health);
        // Nothing moves it: Miniboss.tick() runs no physics at all, so the position it is placed at is the
        // position it keeps. killer560, 2026-09-28, on sim mobs generally: "I would rather just have them
        // never move".
        boss.setNoGravity(true);
        boss.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0.0f, 0.0f);

        // ADD_PLAYER *first*, and only ADD_PLAYER: with UPDATE_LISTED left out the client's PlayerInfo keeps its
        // default listed=false, so the miniboss never shows up in the tab list. Sent straight to each real
        // client's connection because this entity is not in the PlayerList and nothing will broadcast for it.
        ClientboundPlayerInfoUpdatePacket info = new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, boss);
        for (ServerPlayer real : server.getPlayerList().getPlayers()) {
            real.connection.send(info);
        }

        if (!level.addFreshEntity(boss)) {
            LOGGER.warn("Level refused the miniboss entity at {}", pos);
            forget(server, List.of(boss.getUUID()));
            return null;
        }
        PLACED.add(boss.getUUID());
        LOGGER.info("Placed miniboss \"{}\" uuid={} (v{}) id={} at {}",
                name.text(), boss.getUUID(), boss.getUUID().version(), boss.getId(), pos);
        return boss;
    }

    /**
     * Withdraws the client-side profile entry of any miniboss whose entity has gone - killed, or cleared by
     * {@link SimMobs#clear}. Server thread only, called once per server tick from {@link SimMobs}.
     *
     * <p>Left undone, the client's {@code playerInfoMap} would keep growing one dead NPC per kill for the whole
     * session.
     */
    static void tick(ServerLevel level) {
        if (PLACED.isEmpty()) {
            return;
        }
        List<UUID> gone = new ArrayList<>();
        for (UUID id : PLACED) {
            Entity entity = level.getEntity(id);
            if (entity == null || entity.isRemoved()) {
                gone.add(id);
            }
        }
        if (!gone.isEmpty()) {
            PLACED.removeAll(gone);
            forget(level.getServer(), gone);
        }
    }

    /** Drops the profile entries for these UUIDs on every real client. */
    private static void forget(MinecraftServer server, List<UUID> ids) {
        ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(ids);
        for (ServerPlayer real : server.getPlayerList().getPlayers()) {
            real.connection.send(packet);
        }
    }

    /**
     * True for a miniboss this class placed.
     *
     * <p>Needed because a {@code ServerPlayer} added to a level lands in {@code ServerLevel.players()}
     * ({@code ServerLevel$EntityCallbacks.onTrackingStart} adds every {@code ServerPlayer} to that list), so
     * anything in the sim that walks the player list now sees minibosses too. Whatever the list is being used
     * for - waking a Fel, finding where "the player" is - it means the real one.
     */
    static boolean isPlaced(Entity entity) {
        return entity instanceof Miniboss;
    }

    /**
     * A version-2 UUID, which is what {@code MobEspFeature.isMiniboss} tests for and what Hypixel's NPCs carry.
     *
     * <p>Not {@code UUID.nameUUIDFromBytes}, which produces version 3, and not {@code UUID.randomUUID}, which
     * produces version 4 - the version nibble has to be written deliberately. Random rather than derived from
     * the name, because two minibosses of the same name can stand in one floor and a duplicate UUID is silently
     * refused by the entity manager.
     */
    private static UUID npcUuid() {
        byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        bytes[6] = (byte) ((bytes[6] & 0x0F) | 0x20);
        bytes[8] = (byte) ((bytes[8] & 0x3F) | 0x80);
        long msb = 0L;
        long lsb = 0L;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFFL);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }

    /**
     * The entity. A {@link FakePlayer} for its fake packet listener (see this class's doc), with the three
     * {@code FakePlayer}/{@code ServerPlayer} behaviours that would make it unfightable taken back out.
     */
    private static final class Miniboss extends FakePlayer {

        Miniboss(ServerLevel level, GameProfile profile) {
            super(level, profile);
        }

        /**
         * <b>This is what makes it killable, and it is not obvious.</b> {@code FakePlayer.isInvulnerableTo}
         * returns a flat {@code true}; underneath it {@code ServerPlayer.isInvulnerableTo} returns true unless
         * {@code this.connection.hasClientLoaded()}, and a fake listener never receives the packet that sets
         * that. Either one alone refuses every hit.
         */
        @Override
        public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
            return false;
        }

        /**
         * {@code ServerPlayer.hurtServer} asks the victim {@code canHarmPlayer(attacker)} and
         * {@code ServerPlayer.canHarmPlayer} returns false whenever {@code isPvpAllowed()} is false. Whether an
         * integrated server has PvP on is not this file's business to depend on, so the answer is fixed here.
         */
        @Override
        public boolean canHarmPlayer(Player other) {
            return true;
        }

        /** {@code ChunkMap$TrackedEntity.updatePlayer} never sends a spectator to anyone, and
         *  {@code Player.isPickable()} is false for one - a spectating miniboss would be invisible and
         *  unclickable. Fixed rather than inherited from a game mode nothing here sets. */
        @Override
        public boolean isSpectator() {
            return false;
        }

        @Override
        public boolean isCreative() {
            return false;
        }

        /**
         * A deliberately tiny tick, and it must not be empty.
         *
         * <p>{@code FakePlayer.tick()} is empty, which is what keeps a fake player out of every
         * {@code ServerPlayer.tick} path that wants a real connection. But the damage cooldown counter is
         * decremented in a tick and <b>only</b> in {@code ServerPlayer.tick} for a player:
         * {@code LivingEntity.baseTick} guards its own decrement with {@code !(this instanceof ServerPlayer)}
         * (verified in the 26.1.2 bytecode). With nothing decrementing it, {@code invulnerableTime} sticks at 20
         * after the first hit and {@code LivingEntity.hurtServer}'s {@code invulnerableTime > 10} branch then
         * refuses every later hit of the same size ({@code amount <= lastHurt}) - so the miniboss would take
         * exactly one hit and become immortal. That bug is invisible in any test that only hits it once.
         *
         * <p>No physics, no AI, no movement: see {@link #place}.
         */
        @Override
        public void tick() {
            if (isDeadOrDying()) {
                // Removal is done here rather than inside die(), so nothing is torn down in the middle of the
                // damage path that is still using it.
                discard();
                return;
            }
            if (invulnerableTime > 0) {
                invulnerableTime--;
            }
            if (hurtTime > 0) {
                hurtTime--;
            }
        }

        /**
         * No vanilla player death. {@code ServerPlayer.die} broadcasts a death message to the real player
         * through {@code PlayerList.broadcastSystemMessage}, sends a combat-kill packet down a connection that
         * drops everything, and leaves the corpse lying there waiting for a respawn that can never come. The
         * miniboss just stops existing, which is what Hypixel's NPC minibosses do - {@link #tick} removes it on
         * the next server tick.
         */
        @Override
        public void die(DamageSource source) {
            this.dead = true;
        }
    }
}
