package com.killer560.hub.roomsim;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * Hypixel's movement abilities, reimplemented locally for the dungeon sim - as SERVER behaviour.
 *
 * <p>Etherwarp, Instant Transmission, Wither Impact and Tactical Insertion are all server behaviour on Hypixel:
 * the client sends a use packet ({@code ServerboundUseItemPacket}, or {@code ServerboundUseItemOnPacket} when the
 * crosshair is on a block), the server reads its own copy of the player - position, the rotation the packet
 * carried, the sneak state from {@code ServerboundPlayerInputPacket}, the held item - and moves him with a
 * position packet. This class answers those same packets on the integrated server, through Fabric's
 * {@code UseItemCallback} / {@code UseBlockCallback}, which fire inside {@code ServerPlayerGameMode.useItem} /
 * {@code useItemOn} for the {@link ServerPlayer}.
 *
 * <p><b>Why it is the server and not the client (2026-10-04).</b> It used to react to the CLIENT's
 * {@code gameMode.useItem} and ignore the server's copy of the player. That meant a raw use packet - which is what
 * Auto Routes and the Interactive Map send on Hypixel - did nothing in here, and both grew sim-only branches that
 * called into this class directly instead. killer560: "If I make an auto route on sim it will still work the exact
 * same on main, right? That is my entire reason for creating the sim." Those branches are gone; the features send
 * exactly what they send on Hypixel, and the sim answers it the way Hypixel does. Hand use is the same path: the
 * client's own right click sends the same packet to the integrated server. The client-side callbacks below do
 * nothing but stop vanilla's client prediction (a TNT placement, a bow draw) for the items the server handles.
 *
 * <p><b>Why writing positions is correct here and forbidden everywhere else.</b> The standing rule in this mod is
 * never to write position or velocity, because Hypixel reconstructs which key combination could have produced a
 * delta and lags you back when none can. That rule is about a server that is not ours. In the sim the integrated
 * server IS ours, and a teleport is performed on the SERVER's player with a proper teleport, so the client gets the
 * same {@code ClientboundPlayerPositionPacket} Hypixel would send.
 *
 * <p>Everything here is gated on {@link SimState#canAct}, which additionally requires a singleplayer world and no
 * connected server. That gate is the whole safety story: if it were ever wrong, this file would be writing
 * positions on Hypixel.
 */
public final class SimAbilities {

    /** Items whose sneak-right-click is an etherwarp on Hypixel. */
    private static final Set<String> ETHERWARP_ITEMS = Set.of(
            "ASPECT_OF_THE_VOID", "ASPECT_OF_THE_END", "ETHERWARP_CONDUIT");

    /** The wither blades. All four carry Wither Impact. */
    private static final Set<String> WITHER_BLADES = Set.of(
            "HYPERION", "VALKYRIE", "SCYLLA", "ASTRAEA");

    private static final String TACTICAL_INSERTION = "TACTICAL_INSERTION";

    /** Hypixel's etherwarp range, before Transmission Tuners. */
    private static final double ETHERWARP_RANGE = 57.0;

    /**
     * Where an etherwarp puts the feet: the top of the block landed on, plus this. Hypixel's figure (QUOI's, and
     * what the mod's planner predicts with), after which ordinary gravity settles him. The sim used to land at
     * exactly + 1.0, and the planner and executors carried sim branches using 1.0 to match it; one number now.
     */
    public static final double ETHERWARP_LANDING_OFFSET = 0.05;

    /** Wither Impact throws you this far along your look. */
    private static final double WITHER_IMPACT_RANGE = 10.0;

    /**
     * Instant Transmission: 8 blocks, plus one per Transmission Tuner.
     *
     * <p>Taken from the wiki (2026-09-28): 8 blocks base on Aspect of the End, Aspect of the Void and the
     * Etherwarp Conduit alike, and a tuner adds a block to any of them, four maximum. The tuner count is read off
     * the item's own {@code tuned_transmission} tag the same way {@code EtherwarpHopper} reads it.
     *
     * <p>killer560 (2026-09-28): "Treat the default as 12 nearly no one plays with less", so the sim's own
     * Aspect of the Void is handed out fully tuned and lands 12 blocks out unless he changes it.
     */
    private static final double INSTANT_TRANSMISSION_BASE = 8.0;
    private static final int MAX_TUNERS = 4;

    /** Where Tactical Insertion will put you back, or null when none is planted. Server thread only. */
    private static volatile Vec3 insertion;
    private static volatile java.util.UUID insertionOwner;

    private SimAbilities() {
    }

    public static void register() {
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (abilityGraceTicks > 0) {
                abilityGraceTicks--;
            }
        });
        // The insertion's return is the server's own timer, as on Hypixel.
        ServerTickEvents.END_SERVER_TICK.register(SimAbilities::serverTick);
        // UseItemCallback and UseBlockCallback are COMMON events: in singleplayer each click fires them once on
        // the client (MultiPlayerGameMode, before the packet is sent) and once on the integrated server
        // (ServerPlayerGameMode, when the packet arrives). The server copy is the ability; the client copy only
        // decides whether vanilla's client-side prediction runs.
        UseItemCallback.EVENT.register((player, level, hand) -> player instanceof ServerPlayer sp
                ? onServerUse(sp, hand, null)
                : onClientUse(player, hand, null));
        // RIGHT-CLICKING A BLOCK is a different packet (ServerboundUseItemOnPacket), and it is why Superboom once
        // placed TNT: every sim item is a reskinned vanilla one, so vanilla's place-block handling has to be kept
        // off it on BOTH sides. killer560 (2026-09-30): "For superboom if i right click it then it actually places
        // the block and it shouldnt".
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> player instanceof ServerPlayer sp
                ? onServerUse(sp, hand, hitResult)
                : onClientUse(player, hand, hitResult));
    }

    /** Whether this skyblock id is a right-click ability the sim's server implements. */
    private static boolean isAbilityItem(String id) {
        return ETHERWARP_ITEMS.contains(id) || WITHER_BLADES.contains(id) || TACTICAL_INSERTION.equals(id)
                || SimItems.consumesUse(id);
    }

    /**
     * A BUTTON, LEVER OR CHEST WINS OVER THE ITEM, as vanilla and Hypixel both do: the block's own use runs first
     * and the item is only used when you sneak. Consuming these meant an AOTV or a Hyperion in hand turned every Tic
     * Tac Toe button press into an Instant Transmission or a Wither Impact (2026-10-04, "auto tictactoe isn't
     * working on sim"). Asked identically on both sides so they cannot disagree about who owns the click.
     */
    private static boolean blockWins(Player player, Level level, BlockHitResult hit) {
        net.minecraft.world.level.block.Block clicked = level.getBlockState(hit.getBlockPos()).getBlock();
        return !player.isShiftKeyDown()
                && (clicked instanceof net.minecraft.world.level.block.ButtonBlock
                || clicked instanceof net.minecraft.world.level.block.LeverBlock
                || clicked instanceof net.minecraft.world.level.block.ChestBlock);
    }

    /**
     * The client half of a right click: never an ability, only "let vanilla predict this or not".
     *
     * <p>SUCCESS makes Fabric's client hook send the very packet vanilla would have sent (a use packet carrying
     * the player's real rotation, or a use-on-block packet) without running the item's vanilla client use first,
     * so a Superboom is not predicted as a TNT placement and a Terminator does not start drawing. PASS leaves
     * vanilla alone, which also sends the packet. Either way the server decides.
     */
    private static InteractionResult onClientUse(Player player, InteractionHand hand, BlockHitResult onBlock) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client) || player != client.player) {
            return InteractionResult.PASS;
        }
        String id = CheatUtils.skyblockId(player.getItemInHand(hand));
        if (id == null) {
            return InteractionResult.PASS;
        }
        if (onBlock != null) {
            if (!isAbilityItem(id) || blockWins(player, player.level(), onBlock)) {
                return InteractionResult.PASS;
            }
            markAbilityUsed();
            return sendHeldSlotFirst(client);
        }
        if (isAbilityItem(id)) {
            markAbilityUsed();
            return sendHeldSlotFirst(client);
        }
        // An item the server is about to refuse in a trap room (an ender pearl): no client prediction of a throw
        // the server will not make.
        if (trapLocked(id, SimState.currentRoomName())) {
            return sendHeldSlotFirst(client);
        }
        return InteractionResult.PASS;
    }

    /**
     * Tells the server which slot is held BEFORE the use packet goes, then answers SUCCESS.
     *
     * <p>Fabric's client {@code UseItemCallback} hook sits on {@code MultiPlayerGameMode.useItem} AT the call to
     * {@code ensureHasSentCarriedItem}, before it (fabric-events-interaction 5.2.2, javap), and a SUCCESS cancels the
     * method there. So vanilla's "send the selected slot first" never ran for any item this class answers: a swap
     * and a use in the same tick - which is every Auto Puzzles reposition made with sneak already held (Auto Routes
     * sends its slot itself, RouteExecutor.select) - reached the integrated server as a use of the PREVIOUS item, with the slot packet
     * following on the next tick. In the 93-solve runs of 2026-10-04 each reposition that came right after a shot
     * fired the Terminator along the etherwarp's aim and timed out (client "warp sent ... holding ASPECT_OF_THE_VOID",
     * server "use TERMINATOR slot 1"); in Ice Path those arrows landed on the silverfish's next cell and shoved it.
     * Hypixel never saw this - there the callback passes and vanilla sends the slot first - so the sim now does
     * exactly what vanilla would have done at that point.
     */
    private static InteractionResult sendHeldSlotFirst(Minecraft client) {
        if (client.gameMode instanceof com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker invoker) {
            invoker.killer560smod$invokeEnsureHasSentCarriedItem();
        }
        return InteractionResult.SUCCESS;
    }

    /** True on the integrated server of a live sim session. */
    static boolean simServer(ServerPlayer sp) {
        Minecraft client = Minecraft.getInstance();
        return SimState.canAct(client) && client.getSingleplayerServer() == sp.level().getServer();
    }

    /**
     * The server half: a use packet (or use-on-block packet) has arrived, and this is what Hypixel's server does
     * with it. Position, rotation and sneak are the SERVER player's - the rotation is the one the use packet
     * carried ({@code handleUseItem} snaps the server player to it before calling {@code useItem}).
     */
    private static InteractionResult onServerUse(ServerPlayer sp, InteractionHand hand, BlockHitResult onBlock) {
        if (!simServer(sp)) {
            return InteractionResult.PASS;
        }
        ItemStack held = sp.getItemInHand(hand);
        String id = CheatUtils.skyblockId(held);
        if (id == null) {
            return InteractionResult.PASS;
        }
        boolean ours = isAbilityItem(id);
        if (onBlock != null && (!ours || blockWins(sp, sp.level(), onBlock))) {
            return InteractionResult.PASS;
        }
        String room = SimState.roomNameAt(sp.getBlockX(), sp.getBlockZ());
        if (trapLocked(id, room)) {
            sayTrapLocked(room);
            // FAIL, not PASS: PASS would let the item's VANILLA behaviour through, and an ender pearl is vanilla
            // all the way down - it would still throw and still teleport him out of the trap.
            return InteractionResult.FAIL;
        }
        if (!ours) {
            return InteractionResult.PASS;
        }
        boolean sneaking = sp.isShiftKeyDown();
        if (ETHERWARP_ITEMS.contains(id) && sneaking && etherwarpLocked(room)) {
            sayEtherwarpLocked();
            return InteractionResult.FAIL;
        }
        markAbilityUsed();
        boolean fired;
        if (ETHERWARP_ITEMS.contains(id)) {
            fired = sneaking ? etherwarp(sp, held) : instantTransmission(sp, held);
        } else if (WITHER_BLADES.contains(id)) {
            fired = witherImpact(sp);
        } else if (TACTICAL_INSERTION.equals(id)) {
            fired = tacticalInsertion(sp);
        } else {
            // Anything this class does not handle gets one more chance: SimItems owns the Spirit Sceptre,
            // Superboom and the rest, and there is exactly one place a right-click is resolved.
            fired = SimItems.useOnServer(sp, id, onBlock);
        }
        // On a block the click is consumed even when the ability declines: a Superboom aimed at plain stone should
        // do nothing at all - not place a block of TNT.
        return fired || onBlock != null ? InteractionResult.SUCCESS : InteractionResult.PASS;
    }

    /**
     * Teleports onto the block being looked at, if it is a place you could stand - from the server's copy of him.
     *
     * <p>The ray is the PLANNER'S ({@code TeleportUtils.getLook} and {@code traverseVoxels}, sneaking eye height),
     * run against the server's own level, so a hop lands exactly where the Interactive Map's path search and Auto
     * Routes' recorded look say it will - the same model of Hypixel's etherwarp the mod uses everywhere else. A
     * chained hop resolves from where the previous one put him, because the server player is already there, which
     * is what lets hops chain one a tick (killer560, 2026-10-04: "the interactive map etherwarp on sim is really
     * slow"). The overlay's resolver is the same traversal with the same clearance rule.
     *
     * <p>The feet land at the top of the target's real collision box plus {@link #ETHERWARP_LANDING_OFFSET}, and
     * gravity does the rest, as on Hypixel.
     */
    private static boolean etherwarp(ServerPlayer sp, ItemStack held) {
        ServerLevel level = (ServerLevel) sp.level();
        double range = ETHERWARP_RANGE + tuners(held);
        Vec3 look = com.killer560.hub.livemap.autoclear.TeleportUtils.getLook(sp.getYRot(), sp.getXRot());
        Vec3 from = new Vec3(sp.getX(),
                sp.getY() + com.killer560.hub.livemap.autoclear.TeleportUtils.eyeHeight(true), sp.getZ());
        Vec3 to = from.add(look.scale(range));
        var hit = com.killer560.hub.livemap.autoclear.TeleportUtils.traverseVoxels(level,
                from.x, from.y, from.z, to.x, to.y, to.z, true);
        if (!hit.succeeded() || hit.pos() == null) {
            fail("no etherwarp target there");
            return false;
        }
        BlockPos target = hit.pos();
        double standY = com.killer560.hub.etherwarpoverlay.EtherwarpOverlayFeature.standYOn(level, target);
        teleport(sp, target.getX() + 0.5, standY + ETHERWARP_LANDING_OFFSET, target.getZ() + 0.5);
        return true;
    }

    /**
     * The plain right-click teleport: straight along your look, stopping at whatever is in the way.
     *
     * <p>Shares {@link #witherImpact}'s stop-at-the-wall handling rather than repeating it, because getting that
     * wrong in only one of the two would be worse than not having it.
     */
    private static boolean instantTransmission(ServerPlayer sp, ItemStack held) {
        return dash(sp, instantTransmissionRange(held));
    }

    /**
     * Kept for the testkit's sim scenario, which calls it reflectively by this signature: an Instant Transmission
     * for the singleplayer player, resolved on the integrated server exactly as a use packet would be.
     */
    @SuppressWarnings("unused")
    private static boolean instantTransmission(Minecraft client, ItemStack held) {
        var server = client.getSingleplayerServer();
        if (!SimState.canAct(client) || server == null) {
            return false;
        }
        java.util.UUID uuid = client.player.getUUID();
        ItemStack copy = held.copy();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp != null) {
                instantTransmission(sp, copy);
            }
        });
        return true;
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
     * Wither Impact: the dash, and then the blast it lands with.
     *
     * <p>killer560 (2026-10-01): "the hyperion does not do its aoe damage either." On Hypixel the ability's whole
     * point is that it puts you in the middle of a pack and then detonates there. The blast is a sphere at where he
     * LANDED, not where he aimed. Damage and radius are approximations, not Hypixel's real formula - sim mobs have
     * one health, so what matters is which of them are inside it.
     */
    private static boolean witherImpact(ServerPlayer sp) {
        if (!dash(sp, WITHER_IMPACT_RANGE)) {
            return false;
        }
        witherBlast(sp);
        return true;
    }

    /** Wither Impact's radius, in blocks. */
    private static final double WITHER_BLAST_RADIUS = 5.0;

    /** Enough to kill anything in the sim outright; sim mobs have one health anyway. */
    private static final float WITHER_BLAST_DAMAGE = 10_000f;

    /** Runs on the server thread, straight after the dash's teleport has moved the server player. */
    private static void witherBlast(ServerPlayer sp) {
        ServerLevel level = (ServerLevel) sp.level();
        Vec3 centre = sp.position().add(0, sp.getBbHeight() / 2.0, 0);
        var box = net.minecraft.world.phys.AABB.ofSize(centre,
                WITHER_BLAST_RADIUS * 2, WITHER_BLAST_RADIUS * 2, WITHER_BLAST_RADIUS * 2);
        var source = level.damageSources().playerAttack(sp);
        int hit = 0;
        for (net.minecraft.world.entity.LivingEntity target
                : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, box,
                        e -> e.isAlive() && e != sp)) {
            // The BOX is a box and the blast is a sphere - the corners of an axis-aligned box reach 1.7x further
            // than its faces. Same check, same reason as the Spirit Sceptre.
            if (target.getBoundingBox().distanceToSqr(centre) > WITHER_BLAST_RADIUS * WITHER_BLAST_RADIUS) {
                continue;
            }
            target.hurtServer(level, source, WITHER_BLAST_DAMAGE);
            hit++;
        }
        if (hit > 0) {
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(),
                    net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.6f, 1.2f);
        }
    }

    /** How finely {@link #dashTarget} walks the look vector. */
    private static final double STEP = 0.25;

    /**
     * Where a dash of {@code range} along {@code player}'s look would land, or null when the player cannot move.
     *
     * <p>Public and side-effect free so the teleport logger can ask the sim what it WOULD do and print that beside
     * what Hypixel actually did; a logger comparing Hypixel against a copy of the model would agree with the model
     * no matter how wrong the model was. Takes the level and player it runs against: the sim's server passes its
     * own level and the {@link ServerPlayer}.
     *
     * <p><b>This model was MEASURED, not reasoned about.</b> killer560 ran 52 Instant Transmissions on real
     * Hypixel on 2026-09-30 with the teleport logger. What the log shows Hypixel actually does:
     * <ul>
     *   <li><b>It lands you on a block.</b> 51 of 52 landings sat exactly on a block centre in x and z, and
     *       52 of 52 on a whole y - which is also what the mod's planner predicts for an AOTV hop
     *       ({@code ClearNode.AotvNode}, block + 1.0). Etherwarp is the one with the 1.05.</li>
     *   <li><b>It travels along the look vector, not along the ground.</b></li>
     *   <li><b>It never lifts you.</b> Looking down 35 degrees moved him 1.6 blocks with no change in height;
     *       looking straight up moved him straight up - it simply follows where you are pointing.</li>
     * </ul>
     */
    public static Vec3 dashTarget(Level level, Player player, double range) {
        if (player == null || level == null) {
            return null;
        }
        // THE BODY'S AIM, NOT THE CAMERA'S. getLookAngle reads getXRot/getYRot, which on the server is the
        // rotation the use packet carried - what Hypixel receives. (On the client, getViewVector would read
        // ViewFreeze's held view while an auto turns him; that bug is why "ice fill starts completing it then
        // freezes part way through", killer560 2026-10-04.)
        Vec3 look = player.getLookAngle();
        Vec3 from = player.position();
        net.minecraft.world.phys.AABB box = player.getBoundingBox();
        Vec3 best = null;
        // Once the look has driven the travel into something it stays at the height it settled at for the rest
        // of the walk. Null until that happens.
        Double lockedY = null;
        // HOW FAR A SETTLED WALK MAY RUN: to where the EYE's look meets a block, and no further. QUOI's Auto Ice
        // Fill hops one tile at a time by aiming from the eye at the next tile's feet position, which on Hypixel
        // lands exactly there (killer560, 2026-10-02: "auto ice fill tends to start working but then it'll stop
        // after only a few teleports").
        Vec3 eye = player.getEyePosition();
        BlockHitResult eyeHit = level.clip(new ClipContext(eye, eye.add(look.scale(range)),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double settledLimit = eyeHit != null && eyeHit.getType() == HitResult.Type.BLOCK
                ? eyeHit.getLocation().distanceTo(eye) : range;
        for (double d = STEP; d <= range + 1.0e-6; d += STEP) {
            if (lockedY != null && d > settledLimit + 1.0e-6) {
                break;
            }
            Vec3 full = from.add(look.scale(d));
            Vec3 candidate = snap(lockedY == null ? full : new Vec3(full.x, lockedY, full.z));
            if (candidate.equals(best)) {
                continue;   // the same block as the last step - nothing new to test
            }
            if (fits(level, player, box, from, candidate)) {
                best = candidate;
                continue;
            }
            if (lockedY != null) {
                // ONE BLOCK UP, but only onto something the eye's look passes over - Auto Ice Fill climbing from
                // one slab to the next. Bounded by the look itself, so it cannot become the old origin-raising
                // step-up that stacked into a six-block climb.
                Vec3 up = new Vec3(candidate.x, lockedY + 1.0, candidate.z);
                if (up.y <= full.y + player.getEyeHeight() && fits(level, player, box, from, up)) {
                    lockedY = up.y;
                    best = up;
                    continue;
                }
            }
            if (lockedY == null) {
                // A BLOCKED VERTICAL SETTLES AT THE NEAREST HEIGHT THAT FITS, between where the look wanted to put
                // him and the height he started at. Aiming down settles on the floor he stands on (his Hypixel
                // log: 35 degrees down moved him 1.6 blocks at the SAME height); aiming up settles on the last
                // block below the roof (2026-10-01: "teleport me as much up as it can without puting my head into
                // the roof").
                double fromY = Math.floor(from.y);
                double dir = candidate.y > fromY ? -1.0 : 1.0;
                Vec3 settled = null;
                for (double y = candidate.y + dir;
                        dir < 0 ? y >= fromY : y <= fromY;
                        y += dir) {
                    Vec3 lower = new Vec3(candidate.x, y, candidate.z);
                    if (fits(level, player, box, from, lower)) {
                        settled = lower;
                        break;
                    }
                }
                // Never a step BACKWARDS. Looking straight up at a low ceiling settles on his own block, which is
                // not a teleport - it is undoing the one he already has.
                if (settled != null
                        && (best == null || from.distanceToSqr(settled) > from.distanceToSqr(best))) {
                    lockedY = settled.y;
                    best = settled;
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
     * <p>{@code floor} rather than {@code round} on y because the landing is the block you stand ON.
     */
    private static Vec3 snap(Vec3 at) {
        return new Vec3(Math.floor(at.x) + 0.5, Math.floor(at.y), Math.floor(at.z) + 0.5);
    }

    private static boolean dash(ServerPlayer sp, double range) {
        Vec3 best = dashTarget(sp.level(), sp, range);
        if (best == null) {
            // Nothing along the look vector fits, so he stays put - which is what the server does too. Three of
            // the 54 logged clicks produced no movement at all.
            return false;
        }
        teleport(sp, best.x, best.y, best.z);
        return true;
    }

    /** Whether the player's own box fits at {@code to}. */
    private static boolean fits(Level level, Player player, net.minecraft.world.phys.AABB box, Vec3 from, Vec3 to) {
        return level.noCollision(player, box.move(to.subtract(from)));
    }

    /** Ticks left before a planted insertion pulls him back, or 0 when none is planted. Server thread. */
    private static int insertionTicks;

    /**
     * Ticks left in which a left click is treated as the tail of a right-click ability rather than a beam.
     *
     * <p>Using an ability makes the client swing the arm, and that swing reads as an attack press - so
     * teleporting with Hyperion also fired the mage beam. killer560 (2026-09-30): "Dont make hyperion left
     * click when i teleport as well." Set by both halves of a click (the client's, and the server's when a
     * packet arrives), counted down on the client tick.
     */
    private static volatile int abilityGraceTicks;

    /** How long that grace lasts. Two ticks is enough to cover the swing without swallowing a real click. */
    private static final int ABILITY_GRACE_TICKS = 2;

    /**
     * Abilities are off inside a trap room.
     *
     * <p>killer560 (2026-10-01): "Make it so in trap I cannot etherwarp or teleport or use any ability. I can
     * use abilities to go into it but not while in it. THis does not apply to dungeon breaker or super boom."
     *
     * <p>"While in it" is the whole rule, so it is checked at the moment the packet arrives against the room the
     * SERVER has him standing in - a warp that starts outside and lands inside is allowed, and that is the one he
     * named. The Dungeon Breaker is a LEFT-click tool (a START_DESTROY_BLOCK packet) and never reaches this path;
     * Superboom is in {@link #TRAP_ALLOWED}.
     *
     * <p>The room's own type decides, not its name: {@code RoomEntry.type} is what the map and the generator
     * already call a trap room. The name is only a fallback for a room the database does not know.
     */
    private static final java.util.Set<String> TRAP_ALLOWED = java.util.Set.of("SUPERBOOM_TNT");

    private static boolean trapLocked(String skyblockId, String room) {
        if (skyblockId == null || TRAP_ALLOWED.contains(skyblockId) || room == null) {
            return false;
        }
        // Teleport Maze too. killer560 (2026-10-01): "disable all abilites and dungeon breaker in teleprot maze."
        // The chambers are sealed by iron bars; an etherwarp or a teleport skips the whole puzzle.
        if (isTeleportMaze(room)) {
            return true;
        }
        var entry = com.killer560.hub.roomdatabase.RoomDatabase.lookupByName(room);
        String type = entry == null ? null : entry.type;
        return type == null
                ? room.toLowerCase(java.util.Locale.ROOT).contains("trap")
                : type.equalsIgnoreCase("trap");
    }

    /**
     * No etherwarp inside Boulder. killer560 (2026-10-04): "I should not be able to etherwarp in that room."
     *
     * <p>Etherwarp only - a sneaking use with an etherwarp item, whoever sends it - and only while he is STANDING
     * in the room. Instant Transmission is left alone: it stops at a box like any wall, while an etherwarp lands
     * on top of one, and the boxes' barrier ceiling is what keeps the far side of the room behind the puzzle.
     */
    private static boolean etherwarpLocked(String room) {
        return room != null && room.equalsIgnoreCase("Boulder");
    }

    private static void sayEtherwarpLocked() {
        long now = System.currentTimeMillis();
        if (now - lastTrapMessageMs < 1000L) {
            return;
        }
        lastTrapMessageMs = now;
        onClient(() -> ModChat.send("Sim", ModChat.bad("No etherwarp in Boulder"),
                ModChat.dim(" - solve it and walk.")));
    }

    static boolean isTeleportMaze(String room) {
        return room != null && room.equalsIgnoreCase("Teleport Maze");
    }

    /** Said at most once a second, or holding right-click fills chat with it. */
    private static volatile long lastTrapMessageMs = 0L;

    private static void sayTrapLocked(String room) {
        long now = System.currentTimeMillis();
        if (now - lastTrapMessageMs < 1000L) {
            return;
        }
        lastTrapMessageMs = now;
        boolean maze = isTeleportMaze(room);
        onClient(() -> ModChat.send("Sim",
                ModChat.bad(maze ? "No abilities in the Teleport Maze" : "No abilities in a trap room"),
                ModChat.dim(" - walk it.")));
    }

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

    private static boolean tacticalInsertion(ServerPlayer sp) {
        insertion = sp.position();
        insertionOwner = sp.getUUID();
        insertionTicks = INSERTION_DELAY_TICKS;
        // No chat line. killer560 (2026-09-30): "Remove the tactical insertion lines that appear in chat."
        return true;
    }

    /**
     * Counts a planted insertion down on the server and pulls him back when it expires. Dropped the moment the
     * sim is not live, so leaving the sim or dying cannot strand a pending teleport.
     */
    private static void serverTick(MinecraftServer server) {
        if (insertion == null) {
            return;
        }
        if (!SimState.canAct(Minecraft.getInstance())) {
            insertion = null;
            insertionTicks = 0;
            return;
        }
        if (--insertionTicks > 0) {
            return;
        }
        Vec3 back = insertion;
        java.util.UUID owner = insertionOwner;
        insertion = null;
        ServerPlayer sp = owner == null ? null : server.getPlayerList().getPlayer(owner);
        if (sp != null) {
            teleport(sp, back.x, back.y, back.z);
        }
    }

    /**
     * Moves the SERVER's player with a proper teleport, so the client receives the same position packet Hypixel
     * sends. Rotation is RELATIVE and zero: the client keeps exactly the camera it has, whatever it turned to
     * since the packet left - an ability that also span the camera would be unusable.
     */
    private static void teleport(ServerPlayer sp, double x, double y, double z) {
        // level() rather than serverLevel(): ServerPlayer has no serverLevel() accessor in 26.1.2, and a
        // ServerPlayer's level is always a ServerLevel.
        sp.teleportTo((ServerLevel) sp.level(), x, y, z, Set.of(Relative.Y_ROT, Relative.X_ROT), 0f, 0f, false);
    }

    /** Chat belongs on the client thread; the abilities run on the server's. */
    static void onClient(Runnable r) {
        Minecraft.getInstance().execute(r);
    }

    private static void fail(String why) {
        onClient(() -> ModChat.send("Sim", ModChat.dim(why)));
    }

    /** Dropped when a sim session ends, so a marker cannot survive into the next map. */
    public static void reset() {
        insertion = null;
        insertionTicks = 0;
    }

    /** Whether an insertion is planted. */
    public static boolean hasInsertion() {
        return insertion != null;
    }
}
