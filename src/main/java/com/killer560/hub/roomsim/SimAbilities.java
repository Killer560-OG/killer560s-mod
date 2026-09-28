package com.killer560.hub.roomsim;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Hypixel's movement abilities, reimplemented locally for the dungeon sim.
 *
 * <p>Etherwarp, Wither Impact and Tactical Insertion are all server behaviour on Hypixel - the real server moves
 * you and the client only predicts it. A local sim has no such server, so the sim has to be one.
 *
 * <p><b>Why writing positions is correct here and forbidden everywhere else.</b> The standing rule in this mod is
 * never to write position or velocity, because Hypixel reconstructs which key combination could have produced a
 * delta and lags you back when none can. That rule is about a server that is not ours. In the sim the integrated
 * server IS ours, so a teleport is simply a teleport - and it is performed on the SERVER's player through the
 * server thread, not by shoving the client entity somewhere and hoping, which would be corrected on the next tick
 * exactly the way Hypixel would correct it.
 *
 * <p>Everything here is gated on {@link SimState#canAct}, which additionally requires a singleplayer world and no
 * connected server. That gate is the whole safety story: if it were ever wrong, this file would be writing
 * positions on Hypixel.
 *
 * <p>Damage is deliberately absent for now. The sim has no mobs in it yet, and an ability that claims to hurt
 * things nothing has spawned is a feature that cannot be tested.
 */
public final class SimAbilities {

    /** Items whose sneak-right-click is an etherwarp on Hypixel. */
    private static final Set<String> ETHERWARP_ITEMS = Set.of(
            "ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END", "ETHERWARP_CONDUIT");

    /** The wither blades. All four carry Wither Impact. */
    private static final Set<String> WITHER_BLADES = Set.of(
            "HYPERION", "VALKYRIE", "SCYLLA", "ASTRAEA");

    private static final String TACTICAL_INSERTION = "TACTICAL_INSERTION";

    /** Hypixel's etherwarp range. */
    private static final double ETHERWARP_RANGE = 57.0;

    /** Wither Impact throws you this far along your look. */
    private static final double WITHER_IMPACT_RANGE = 10.0;

    /** Where Tactical Insertion will put you back, or null when none is planted. */
    private static Vec3 insertion;

    private SimAbilities() {
    }

    public static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            Minecraft client = Minecraft.getInstance();
            if (!SimState.canAct(client) || player != client.player) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getItemInHand(hand);
            String id = CheatUtils.skyblockId(held);
            if (id == null) {
                return InteractionResult.PASS;
            }
            if (ETHERWARP_ITEMS.contains(id) && player.isShiftKeyDown()) {
                return etherwarp(client) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (WITHER_BLADES.contains(id)) {
                return witherImpact(client) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (TACTICAL_INSERTION.equals(id)) {
                return tacticalInsertion(client) ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            // Anything this class does not handle gets one more chance: SimItems owns the Spirit Sceptre,
            // Superboom and the rest, and splitting them across two files is only tolerable if there is exactly
            // one place a right-click is resolved.
            if (SimItems.tryUse(client, id)) {
                return InteractionResult.SUCCESS;
            }
            return InteractionResult.PASS;
        });
    }

    /**
     * Teleports onto the block being looked at, if it is a place you could stand.
     *
     * <p>The two-air-above check is the rule that makes etherwarp feel like etherwarp: aiming at a wall or at a
     * block with a ceiling over it does nothing, and a sim where it always succeeds would teach the wrong habits
     * for the routes he is practising.
     */
    private static boolean etherwarp(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(ETHERWARP_RANGE));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos target = hit.getBlockPos();
        BlockPos stand = target.above();
        if (!client.level.getBlockState(stand).isAir() || !client.level.getBlockState(stand.above()).isAir()) {
            fail(client, "no room to stand there");
            return false;
        }
        teleport(client, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        return true;
    }

    /**
     * Throws you up to ten blocks along your look, stopping at whatever is in the way.
     *
     * <p>Stopping short rather than passing through: on Hypixel the blades do not put you inside a wall, and a
     * sim that let you phase through one would make every route practised in it wrong.
     */
    private static boolean witherImpact(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(WITHER_IMPACT_RANGE));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        Vec3 dest = hit != null && hit.getType() == HitResult.Type.BLOCK
                ? hit.getLocation().subtract(look.scale(0.4)) // back off the face so you do not land in it
                : end;
        // Feet, not eyes: the ray came from eye level and teleporting to it would gain you a block of height
        // on every cast.
        double drop = client.player.getEyePosition().y - client.player.getY();
        teleport(client, dest.x, dest.y - drop, dest.z);
        return true;
    }

    /** First use plants the marker, the next returns to it - the way it works on Hypixel. */
    private static boolean tacticalInsertion(Minecraft client) {
        if (insertion == null) {
            insertion = client.player.position();
            ModChat.send("Sim", ModChat.text("Tactical Insertion planted"));
            return true;
        }
        Vec3 back = insertion;
        insertion = null;
        teleport(client, back.x, back.y, back.z);
        ModChat.send("Sim", ModChat.text("Returned to your insertion"));
        return true;
    }

    /**
     * Moves the SERVER's copy of the player, on the server thread.
     *
     * <p>Not {@code client.player.setPos}. The integrated server holds the authoritative position, so moving only
     * the client entity is a desync the very next tick corrects - the same correction Hypixel would apply, for
     * the same reason. Relative set is empty so yaw and pitch are kept: an etherwarp that also span the camera
     * would be unusable.
     */
    private static void teleport(Minecraft client, double x, double y, double z) {
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        var uuid = client.player.getUUID();
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            // level() rather than serverLevel(): ServerPlayer has no serverLevel() accessor in 26.1.2, and a
            // ServerPlayer's level is always a ServerLevel.
            sp.teleportTo((net.minecraft.server.level.ServerLevel) sp.level(), x, y, z,
                    Set.<Relative>of(), yaw, pitch, false);
        });
    }

    private static void fail(Minecraft client, String why) {
        ModChat.send("Sim", ModChat.dim(why));
    }

    /** Dropped when a sim session ends, so a marker cannot survive into the next map. */
    public static void reset() {
        insertion = null;
    }

    /** Kept so callers do not have to reach into {@link Level} themselves. */
    public static boolean hasInsertion() {
        return insertion != null;
    }
}
