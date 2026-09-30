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

    /**
     * Instant Transmission: 8 blocks, plus one per Transmission Tuner.
     *
     * <p>The sim had etherwarp and Wither Impact but no plain right-click teleport at all, which left out the
     * one most routes are actually built on. Taken from the wiki (2026-09-28): 8 blocks base on Aspect of the
     * End, Aspect of the Void and the Etherwarp Conduit alike, and a tuner adds a block to any of them, four
     * maximum. The tuner count is read off the item's own {@code tuned_transmission} tag the same way
     * {@code EtherwarpHopper} reads it, rather than assumed - so a differently tuned item behaves differently
     * here, which is the point of practising in it.
     *
     * <p>killer560 (2026-09-28): "Treat the default as 12 nearly no one plays with less", so the sim's own
     * Aspect of the Void is handed out fully tuned and lands 12 blocks out unless he changes it.
     */
    private static final double INSTANT_TRANSMISSION_BASE = 8.0;
    private static final int MAX_TUNERS = 4;

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
            if (ETHERWARP_ITEMS.contains(id)) {
                return instantTransmission(client, held) ? InteractionResult.SUCCESS : InteractionResult.PASS;
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
        // The overlay's own resolver, not a second idea of what a legal target is. killer560 (2026-09-28):
        // "make sure etherwarp cannot put me into blocks it shoudl function the same as on main. Make sure
        // etherwarp overlay works as well."
        //
        // The old code here raycast to a block and required two air blocks above it. That is wrong for every
        // block whose collision box is not a full cube - a slab, a stair, a chest - because the feet do not go
        // at blockY+1, they go at the top of the real collision shape. It also meant the sim could land
        // somewhere the overlay had not highlighted, which is worse than either being wrong on its own: the
        // overlay is the thing he aims with, so the two disagreeing trains the aim and then punishes it.
        //
        // Sharing the resolver makes the highlight a promise. If the overlay does not draw it, the sim does
        // not go there, and neither does Hypixel.
        double range = ETHERWARP_RANGE + tunersOnHeldItem(client);
        BlockPos target = com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.resolveTarget(
                client.level, client.player.position(), client.player, range);
        if (target == null) {
            fail(client, "no etherwarp target there");
            return false;
        }
        double standY = com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.standYOn(client.level, target);
        teleport(client, target.getX() + 0.5, standY, target.getZ() + 0.5);
        return true;
    }

    /** Tuners on the item in hand, so the sim's reach matches the one he is actually carrying. */
    private static int tunersOnHeldItem(Minecraft client) {
        var stack = client.player.getMainHandItem();
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        return Math.max(0, Math.min(4, data.copyTag().getIntOr("tuned_transmission", 0)));
    }

    /**
     * The plain right-click teleport: straight along your look, stopping at whatever is in the way.
     *
     * <p>Shares {@link #witherImpact}'s stop-at-the-wall handling rather than repeating it, because getting that
     * wrong in only one of the two would be worse than not having it: a route practised against a teleport that
     * phases through a wall is a route that does not work on Hypixel.
     */
    private static boolean instantTransmission(Minecraft client, ItemStack held) {
        return dash(client, INSTANT_TRANSMISSION_BASE + tuners(held));
    }

    /** Tuners on this item, read off its own tag - capped, because Hypixel caps it at four. */
    private static int tuners(ItemStack held) {
        var data = held.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        return Math.min(MAX_TUNERS, Math.max(0, data.copyTag().getIntOr("tuned_transmission", 0)));
    }

    /**
     * Throws you up to ten blocks along your look, stopping at whatever is in the way.
     *
     * <p>Stopping short rather than passing through: on Hypixel the blades do not put you inside a wall, and a
     * sim that let you phase through one would make every route practised in it wrong.
     */
    private static boolean witherImpact(Minecraft client) {
        return dash(client, WITHER_IMPACT_RANGE);
    }

    /**
     * Moves you up to {@code range} along your look, stopping at the last place you actually fit.
     *
     * <p>killer560 (2026-09-29): "if i teleport and am looking down a little bit then it puts me into blocks
     * and it shouldnt."
     *
     * <p>Here is why it did. The old version cast a ray from the EYE, took where it hit, and then dropped that
     * point by the eye height to get the feet. Look down a few degrees and the ray meets the floor a couple of
     * blocks ahead - so the landing point was the floor's surface minus 1.62, which is a block and a half
     * inside the floor. The 0.4 back-off along the look vector was nearly horizontal and did nothing about it.
     *
     * <p>This walks the player's own bounding box along the look vector in quarter-block steps from where he
     * is standing and keeps the LAST position that does not collide with anything. It cannot end inside a
     * block, it cannot pass through a wall, and it needs no special case for looking down - the floor stops
     * the box the same way a wall does. A quarter block is fine enough that the stop is never visibly short
     * and coarse enough to be 48 checks at the longest range.
     *
     * <p><b>A blocked step SLIDES rather than cancelling the teleport.</b> killer560 (2026-09-29): "if it is a
     * regular teleport that wouldd bring me into a block then it shouldnt just not let me teleport but it
     * should get me as close as possible to the block. So if it is one block infront of me then i go 1 block
     * forward."
     *
     * <p>The version above refused outright whenever the FIRST quarter-block step collided, and standing on a
     * floor and looking down even a degree is exactly that case: the box rests with its bottom face on the
     * floor's top face, so any downward component at all puts it inside the floor. So nearly every teleport he
     * made was refused - fourteen "no room to teleport that way" in ten seconds in his 17:57 run on 2026-09-29.
     * When the vertical part of a step is what is blocked, the horizontal part is kept and the slide continues
     * at the height he started at, which is what the ability does on Hypixel: aiming at the ground ahead moves
     * you along the ground rather than doing nothing. Only a step that cannot move him horizontally either -
     * flush against a wall - ends the walk, and only a walk that never moved him at all reports a failure.
     */
    /** How far a blocked step may lift to clear what it hit: a slab, then a full block. */
    private static final double[] STEP_UPS = {0.5, 1.0};

    private static boolean dash(Minecraft client, double range) {
        var player = client.player;
        Vec3 look = player.getViewVector(1.0f);
        Vec3 from = player.position();   // reassigned when a step lifts over a block
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        Vec3 best = null;
        // Once the vertical part of the move is blocked it stays blocked for the rest of the walk: the next
        // step reaches further down into the same floor, so re-testing it every step would only ever fail.
        boolean verticalBlocked = false;
        for (double d = STEP; d <= range + 1.0e-6; d += STEP) {
            Vec3 full = from.add(look.scale(d));
            Vec3 candidate = verticalBlocked ? new Vec3(full.x, from.y, full.z) : full;
            if (fits(client, player, box, from, candidate)) {
                best = candidate;
                continue;
            }
            if (!verticalBlocked) {
                Vec3 flat = new Vec3(full.x, from.y, full.z);
                if (fits(client, player, box, from, flat)) {
                    verticalBlocked = true;
                    best = flat;
                    continue;
                }
            }
            // STEP UP rather than stop.
            //
            // killer560 (2026-09-29): "if i am looking at a block it should still teleport me towards it and
            // up a block if it is something i am touching already." Standing against a block, the very first
            // step collides with it and the walk ended there - so looking at the thing right in front of him
            // teleported him nowhere. Raising the step clears a block he is up against, the way walking into
            // one steps onto it, and the walk carries on from there.
            //
            // Tried in halves: 0.5 first for a slab or a stair, then a full block. A raised step has to clear
            // the same full box test as any other, so this cannot put him inside anything.
            boolean stepped = false;
            for (double lift : STEP_UPS) {
                Vec3 raised = new Vec3(full.x, from.y + lift, full.z);
                if (fits(client, player, box, from, raised)) {
                    best = raised;
                    from = new Vec3(from.x, from.y + lift, from.z);
                    verticalBlocked = true;
                    stepped = true;
                    break;
                }
            }
            if (stepped) {
                continue;
            }
            break;
        }
        if (best == null) {
            fail(client, "no room to teleport that way");
            return false;
        }
        teleport(client, best.x, best.y, best.z);
        return true;
    }

    /** Whether the player's own box fits at {@code to}. */
    private static boolean fits(Minecraft client, net.minecraft.world.entity.player.Player player,
                                net.minecraft.world.phys.AABB box, Vec3 from, Vec3 to) {
        return client.level.noCollision(player, box.move(to.subtract(from)));
    }

    /** How finely {@link #dash} walks the look vector. */
    private static final double STEP = 0.25;

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
