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
        // START, not END: this repo's rule is that anything which can send an interaction ticks before the
        // movement packet. The insertion's return is a teleport, so it belongs on the same side.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (abilityGraceTicks > 0) {
                abilityGraceTicks--;
            }
            if (SimState.canAct(client) && client.player != null) {
                tickInsertion(client);
            } else {
                insertion = null;
                insertionTicks = 0;
            }
        });
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
            markAbilityUsed();
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
        // RIGHT-CLICKING A BLOCK is a different event, and that is why Superboom placed TNT.
        //
        // killer560 (2026-09-30): "For superboom if i right click it then it actually places the block and it
        // shouldnt". UseItemCallback only fires for a right-click in AIR; aiming at a block goes through
        // UseBlockCallback and falls straight into vanilla's place-block handling. Every sim item is a
        // reskinned vanilla one, so Superboom is real TNT and gets really placed.
        //
        // Consuming the interaction here covers both halves: the ability runs, and vanilla never sees the
        // click. It returns SUCCESS even when the ability declines, because a Superboom aimed at plain stone
        // should do nothing at all - not place a block of TNT.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            Minecraft client = Minecraft.getInstance();
            // BOTH SIDES, or the block still gets placed.
            //
            // UseBlockCallback is a COMMON event: in singleplayer it fires once for the client player and
            // again for the integrated server's ServerPlayer. The first version compared `player` against
            // client.player, which is false for the server copy - so the server never saw a cancel and placed
            // the TNT anyway, which is why killer560 still had Superboom placing blocks after the first fix.
            // Matching on UUID catches both, and only the client side runs the ability so it cannot fire
            // twice - the same double-fire guard SimDoors and SimBoulderPuzzle already use.
            if (!SimState.canAct(client) || client.player == null
                    || !player.getUUID().equals(client.player.getUUID())) {
                return InteractionResult.PASS;
            }
            boolean clientSide = player == client.player;
            ItemStack held = player.getItemInHand(hand);
            String id = CheatUtils.skyblockId(held);
            if (id == null) {
                return InteractionResult.PASS;
            }
            markAbilityUsed();
            if (!clientSide) {
                // The server's job here is only to NOT place the block.
                return InteractionResult.SUCCESS;
            }
            if (ETHERWARP_ITEMS.contains(id)) {
                if (player.isShiftKeyDown()) {
                    etherwarp(client);
                } else {
                    instantTransmission(client, held);
                }
                return InteractionResult.SUCCESS;
            }
            if (WITHER_BLADES.contains(id)) {
                witherImpact(client);
                return InteractionResult.SUCCESS;
            }
            if (TACTICAL_INSERTION.equals(id)) {
                tacticalInsertion(client);
                return InteractionResult.SUCCESS;
            }
            SimItems.tryUse(client, id);
            return InteractionResult.SUCCESS;
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
        return dash(client, instantTransmissionRange(held));
    }

    /**
     * How far an Instant Transmission goes with this item, in blocks.
     *
     * <p>Public for the teleport logger, so the range it checks Hypixel against is the sim's own number and
     * not a second copy of the base-plus-tuners rule.
     */
    public static double instantTransmissionRange(ItemStack held) {
        return INSTANT_TRANSMISSION_BASE + tuners(held);
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

    /**
     * Where {@link #dash} would land, or null when nothing along the look fits.
     *
     * <p>Public and side-effect free so the teleport logger can ask the sim what it WOULD do and print that
     * beside what Hypixel actually did. The alternative was a second copy of this walk in the logger, and a
     * logger comparing Hypixel against a copy of the model would agree with the model no matter how wrong the
     * model was - it has to call the real thing or it measures nothing.
     */
    /**
     * Where {@link #dash} would land, or null when the player cannot move at all.
     *
     * <p><b>This model was MEASURED, not reasoned about.</b> killer560 ran 52 Instant Transmissions on real
     * Hypixel on 2026-09-30 with the teleport logger, and the previous model - walk the player's bounding box
     * along the look vector in quarter-block steps, lifting over anything in the way - agreed with the server
     * on exactly ONE of the 52. It was wrong in a way no amount of reasoning had caught over three rewrites.
     *
     * <p>What the log shows Hypixel actually does:
     * <ul>
     *   <li><b>It lands you on a block.</b> 51 of 52 landings sat exactly on a block centre in x and z, and
     *       52 of 52 on a whole y. The old model landed wherever the walk happened to stop, which is why
     *       almost every sample disagreed even when the direction and distance were close.</li>
     *   <li><b>It travels along the look vector, not along the ground.</b> For the shots that went the full
     *       distance, the horizontal travel tracks {@code range * cos(pitch)} to within half a block.</li>
     *   <li><b>It never lifts you.</b> Looking down 35 degrees moved him 1.6 blocks with no change in height;
     *       the old model wanted to put him SIX BLOCKS UP, because a blocked step would raise the walk's
     *       origin and the raises accumulated. Looking straight up moved him straight up (dy +4 to +7.8),
     *       which is the same rule seen from the other end - it simply follows where you are pointing.</li>
     * </ul>
     *
     * <p>So: step along the look vector, keep the furthest point the player still fits at, and snap that to
     * the block. No step-ups, no sliding along the floor, no last-resort lift.
     */
    public static Vec3 dashTarget(Minecraft client, double range) {
        var player = client.player;
        if (player == null || client.level == null) {
            return null;
        }
        Vec3 look = player.getViewVector(1.0f);
        Vec3 from = player.position();
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        Vec3 best = null;
        // Once the look has driven the travel into the floor it stays there for the rest of the walk, so the
        // remaining steps run along the ground rather than re-testing a descent that can only fail again.
        boolean verticalBlocked = false;
        for (double d = STEP; d <= range + 1.0e-6; d += STEP) {
            Vec3 full = from.add(look.scale(d));
            Vec3 candidate = snap(verticalBlocked ? new Vec3(full.x, from.y, full.z) : full);
            if (candidate.equals(best)) {
                continue;   // the same block as the last step - nothing new to test
            }
            if (fits(client, player, box, from, candidate)) {
                best = candidate;
                continue;
            }
            // AIMING DOWN MUST STILL MOVE YOU. killer560 (2026-09-30): "If i am looking down even just a
            // little bit or hitting a block at all it doesnt work even though on main it should."
            //
            // Standing on a floor and looking down even a degree puts the very first candidate inside that
            // floor, and breaking there meant the whole teleport was refused - which is exactly what he saw.
            // His own Hypixel log shows the real behaviour: aiming 35 degrees down moved him 1.6 blocks at
            // the SAME height. So a blocked descent keeps the horizontal part and carries on along the
            // ground. What is NOT restored is the old step-up, which raised the walk's origin and let the
            // raises stack into a six-block climb.
            if (!verticalBlocked) {
                Vec3 flat = snap(new Vec3(full.x, from.y, full.z));
                if (fits(client, player, box, from, flat)) {
                    verticalBlocked = true;
                    best = flat;
                    continue;
                }
            }
            break;
        }
        return best;
    }

    /**
     * Puts a position on the block Hypixel would have landed you on: centred in x and z, whole in y.
     *
     * <p>{@code floor} rather than {@code round} on y because the landing is the block you stand ON, and a
     * candidate part-way up a block belongs to that block's floor.
     */
    private static Vec3 snap(Vec3 at) {
        return new Vec3(Math.floor(at.x) + 0.5, Math.floor(at.y), Math.floor(at.z) + 0.5);
    }

    private static boolean dash(Minecraft client, double range) {
        Vec3 best = dashTarget(client, range);
        if (best == null) {
            // Nothing along the look vector fits, so he stays put - which is what the server does too. Three
            // of the 54 logged clicks produced no movement at all. The old code lifted him a block here; the
            // measurements show Hypixel never does that, so neither does this.
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
    /** Ticks left before a planted insertion pulls him back, or 0 when none is planted. */
    private static int insertionTicks;

    /**
     * Ticks left in which a left click is treated as the tail of a right-click ability rather than a beam.
     *
     * <p>Using an ability makes the client swing the arm, and that swing reads as an attack press - so
     * teleporting with Hyperion also fired the mage beam. killer560 (2026-09-30): "Dont make hyperion left
     * click when i teleport as well."
     */
    private static int abilityGraceTicks;

    /** How long that grace lasts. Two ticks is enough to cover the swing without swallowing a real click. */
    private static final int ABILITY_GRACE_TICKS = 2;

    /** Whether an ability has just been used, so the beam should stay quiet. */
    public static boolean usedAbilityRecently() {
        return abilityGraceTicks > 0;
    }

    /** Called by every ability entry point, so one place decides what counts as "just used". */
    private static void markAbilityUsed() {
        abilityGraceTicks = ABILITY_GRACE_TICKS;
    }

    /**
     * How long an insertion waits before returning you. killer560 (2026-09-30): "tactical insertion goes off
     * after 3 seconds atuomatically not after you click it again."
     */
    private static final int INSERTION_DELAY_TICKS = 60;

    private static boolean tacticalInsertion(Minecraft client) {
        insertion = client.player.position();
        insertionTicks = INSERTION_DELAY_TICKS;
        ModChat.send("Sim", ModChat.text("Tactical Insertion planted"));
        return true;
    }

    /**
     * Counts a planted insertion down and pulls him back when it expires.
     *
     * <p>Ticked from {@link #register}'s client tick rather than scheduled, so leaving the sim or dying
     * cannot strand a pending teleport - {@link #reset} clears it with everything else.
     */
    private static void tickInsertion(Minecraft client) {
        if (insertionTicks <= 0 || insertion == null) {
            return;
        }
        if (--insertionTicks > 0) {
            return;
        }
        Vec3 back = insertion;
        insertion = null;
        teleport(client, back.x, back.y, back.z);
        ModChat.send("Sim", ModChat.text("Returned to your insertion"));
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
