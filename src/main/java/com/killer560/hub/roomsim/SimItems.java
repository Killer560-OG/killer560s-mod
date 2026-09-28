package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Gives the sim's own copies of Hypixel items ({@code /simitem}) and reimplements the abilities of the ones
 * {@link SimAbilities} does not cover.
 *
 * <p>Every item handed out is a plain {@link ItemStack} carrying the real Skyblock id in {@code CUSTOM_DATA}
 * "id" - the exact inverse of how {@link com.killer560.hub.cheatutils.CheatUtils#skyblockId} (and everything
 * else in this mod) reads that field. There is no NEU-repo lookup and no skin/model dressing: the rest of the
 * mod only ever cares about the id, and a plain stack is one fewer thing that can silently fail to build
 * offline in a sim with no Hypixel resource pack loaded.
 *
 * <p>Same gating and same "server, not client" rule as {@link SimAbilities}: nothing here is reachable unless
 * {@link SimState#canAct} is true, and every change to the world - giving an item, breaking a block, hurting
 * an entity - happens on the integrated server's own thread against the server's own player, never against
 * the client entity. That is what makes it safe for this file to do things (spawn items, insta-break blocks)
 * that would be unthinkable anywhere else in this codebase.
 */
public final class SimItems {

    /** Reach for "the block you're looking at" - superboom and the dungeon breaker both use it. Matches the
     *  block interaction range elsewhere in the mod closely enough for a sim; there is no server to enforce
     *  Hypixel's own measured 4.5-block limit here, so this is just "close enough to be aiming at it". */
    private static final double LOOK_RANGE = 5.0;

    /** How many blocks out from the targeted block Superboom TNT also clears - a real Superboom breaks a
     *  small area of crypt wall, not just the one block it lands on. */
    private static final int SUPERBOOM_RADIUS = 1;

    /** Approximation only (see {@link #spiritSceptre}) - not Hypixel's real bat-projectile numbers. */
    private static final float SCEPTER_DAMAGE = 30.0f;
    private static final double SCEPTER_KNOCKBACK = 1.0;
    private static final double SCEPTRE_RANGE = 6.0;
    private static final double SCEPTRE_WIDTH = 4.0;
    private static final double SCEPTRE_HEIGHT = 3.0;

    private SimItems() {
    }

    /** One entry per {@code /simitem <name>} subcommand. */
    private enum GiveItem {
        ASPECT_OF_THE_VOID("ASPECT_OF_THE_VOID", Items.GOLDEN_SWORD, "Aspect of the Void", 1),
        HYPERION("HYPERION", Items.NETHERITE_SWORD, "Hyperion", 1),
        SPIRIT_SCEPTRE("SPIRIT_SCEPTRE", Items.BONE, "Spirit Sceptre", 1),
        TERMINATOR("TERMINATOR", Items.BOW, "Terminator", 1),
        ARCHITECT_FIRST_DRAFT("ARCHITECT_FIRST_DRAFT", Items.PAPER,
                "Architect's First Draft", 1),
        SUPERBOOM_TNT("SUPERBOOM_TNT", Items.TNT, "Superboom TNT", 8),
        ENDER_PEARL("ENDER_PEARL", Items.ENDER_PEARL, "Ender Pearl", 16),
        TACTICAL_INSERTION("TACTICAL_INSERTION", Items.FEATHER, "Tactical Insertion", 1),
        DUNGEON_BREAKER("DUNGEONBREAKER", Items.DIAMOND_PICKAXE, "Dungeon Breaker", 1);

        final String skyblockId;
        final Item base;
        final String displayName;
        final int count;

        GiveItem(String skyblockId, Item base, String displayName, int count) {
            this.skyblockId = skyblockId;
            this.base = base;
            this.displayName = displayName;
            this.count = count;
        }
    }

    /** Call once from {@code Killer560ModClient#onInitializeClient}, alongside {@code SimAbilities.register()}. */
    public static void register() {
        // The Dungeon Breaker is a LEFT-click mining tool on Hypixel, so UseItemCallback - which is the
        // right-click path everything else here goes through - never fires for it. AttackBlockCallback is the
        // left-click equivalent and needs no mixin, which matters: a mixin on the break path is a thing that
        // breaks quietly on the next Minecraft version.
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register(
                (player, level, hand, pos, direction) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!SimState.canAct(client) || player != client.player) {
                        return net.minecraft.world.InteractionResult.PASS;
                    }
                    String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(player.getItemInHand(hand));
                    if (!"DUNGEONBREAKER".equals(id)) {
                        return net.minecraft.world.InteractionResult.PASS;
                    }
                    dungeonBreak(client);
                    return net.minecraft.world.InteractionResult.SUCCESS;
                });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = ClientCommands.literal("simitem").executes(ctx -> help());
            for (GiveItem item : GiveItem.values()) {
                root = root.then(ClientCommands.literal(literalName(item))
                        .executes(ctx -> give(item)));
            }
            root = root.then(ClientCommands.literal("all").executes(ctx -> giveAll()));
            dispatcher.register(root);
        });
    }

    private static String literalName(GiveItem item) {
        return item.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static int help() {
        ModChat.send("Sim", ModChat.text("Usage: /simitem <"
                + "aspect_of_the_void|hyperion|spirit_sceptre|superboom_tnt|ender_pearl|tactical_insertion|"
                + "dungeon_breaker|all>"));
        return 1;
    }

    /**
     * Gives one item. Named {@code /simitem <item>}, not {@code /item} - {@code /item} may collide with a
     * real server command, and this must never be mistaken for one.
     */
    private static int give(GiveItem item) {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            fail(client, "sim items only work inside a sim session");
            return 0;
        }
        giveOnServer(client, item);
        ModChat.send("Sim", ModChat.text("Gave you "), ModChat.value(item.displayName));
        return 1;
    }

    private static int giveAll() {
        Minecraft client = Minecraft.getInstance();
        if (!SimState.canAct(client)) {
            fail(client, "sim items only work inside a sim session");
            return 0;
        }
        for (GiveItem item : GiveItem.values()) {
            giveOnServer(client, item);
        }
        ModChat.send("Sim", ModChat.text("Gave you the full sim item set"));
        return 1;
    }

    /**
     * Builds the stack on the client thread (cheap, no world access needed) and hands it to the SERVER's
     * player on the server thread - same reasoning as {@link SimAbilities#teleport}: only a change made to
     * the server's own inventory persists, since the integrated server is the authority even though it is
     * ours in the sim.
     */
    private static void giveOnServer(Minecraft client, GiveItem item) {
        var server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        ItemStack stack = build(item);
        var uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            if (!sp.getInventory().add(stack)) {
                sp.drop(stack, false);
            }
        });
    }

    /** The exact inverse of {@code CheatUtils.skyblockId}: a plain stack with "id" set in CUSTOM_DATA. */
    private static ItemStack build(GiveItem item) {
        ItemStack stack = new ItemStack(item.base, item.count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(item.displayName));
        CompoundTag tag = new CompoundTag();
        tag.putString("id", item.skyblockId);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    // ---------------------------------------------------------------------------------------------------
    // Ability behaviour for the items SimAbilities does not cover. SimAbilities' own UseItemCallback should
    // delegate any skyblock id it does not itself recognise to tryUse (wiring not done here - see the class
    // doc of the caller). Etherwarp items, the wither blades and Tactical Insertion stay in SimAbilities;
    // only Spirit Sceptre, Superboom TNT and the Dungeon Breaker are implemented here, plus an explicit
    // pass-through for ender pearls.
    // ---------------------------------------------------------------------------------------------------

    /**
     * @return true if {@code skyblockId} was one of this file's abilities and it fired (or deliberately did
     * nothing, for ender pearls). False means "not mine" - the caller should keep treating the item as a
     * normal vanilla item.
     */
    public static boolean tryUse(Minecraft client, String skyblockId) {
        if (!SimState.canAct(client) || skyblockId == null) {
            return false;
        }
        return switch (skyblockId) {
            case "ENDER_PEARL" ->
                    // Vanilla ender pearls already throw, travel and teleport you through the integrated
                    // server exactly like any other singleplayer world - there is nothing Hypixel-specific
                    // to reimplement, so this is a deliberate no-op rather than a missing handler.
                    false;
            case "SPIRIT_SCEPTRE" -> spiritSceptre(client);
            // Terminator owns its own file: three arrows, and Salvation after three hits.
            case "TERMINATOR" -> SimTerminator.use(client);
            case "ARCHITECT_FIRST_DRAFT" -> architectDraft(client);
            case "SUPERBOOM_TNT" -> superboomTnt(client);
            case "DUNGEONBREAKER" -> dungeonBreak(client);
            default -> false;
        };
    }

    /**
     * Spirit Sceptre's real ability fires a spread of bats that explode on contact. This is deliberately a
     * simple approximation - one instant hit of damage and knockback to whatever is in a box in front of the
     * player - not Hypixel's real bat projectile count, spread or timing. Good enough to practise "hit the
     * mob in front of you", not a damage-number replica.
     */
    private static boolean spiritSceptre(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0f);
        Vec3 center = eye.add(look.scale(SCEPTRE_RANGE / 2.0));
        var uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            ServerLevel level = (ServerLevel) sp.level();
            AABB box = AABB.ofSize(center, SCEPTRE_RANGE, SCEPTRE_HEIGHT, SCEPTRE_WIDTH);
            List<Entity> hit = level.getEntities(sp, box, e -> e instanceof LivingEntity && e != sp);
            DamageSource source = level.damageSources().playerAttack(sp);
            for (Entity e : hit) {
                LivingEntity target = (LivingEntity) e;
                target.hurt(source, SCEPTER_DAMAGE);
                // Vanilla's own convention: pass the vector FROM the target TO the attacker: knockback()
                // normalises it and pushes the target the other way, away from the player.
                target.knockback(SCEPTER_KNOCKBACK, sp.getX() - target.getX(), sp.getZ() - target.getZ());
            }
        });
        ModChat.send("Sim", ModChat.text("Spirit Sceptre bats fired"));
        return true;
    }

    /** Breaks a small cube of breakable blocks centred on whatever block you're looking at, the way a real
     *  Superboom clears a chunk of crypt/reinforced wall rather than a single block. Simplified timing: the
     *  real item is thrown/placed and detonates after a short fuse, but the sim has no need to model the
     *  fuse itself, so this fires the moment {@code tryUse} is called for it - whatever event wires that up
     *  decides what "using" a Superboom means in the sim. */
    /**
     * Superboom.
     *
     * <p>A superboom that opens a CRYPT counts toward the score: five crypts are worth the bonus five points,
     * and that is most of the gap between a 299 and a 300. Recognised by the blocks it breaks rather than by
     * where it was used - a crypt is a cracked-stone-brick wall, and reading the wall is the only thing the sim
     * can know for certain.
     */
    private static boolean superboomTnt(Minecraft client) {
        BlockHitResult hit = lookedAtBlock(client);
        if (hit == null) {
            fail(client, "not looking at a block");
            return false;
        }
        BlockPos center = hit.getBlockPos().immutable();
        var server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        var uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            ServerLevel level = (ServerLevel) sp.level();
            BlockPos min = center.offset(-SUPERBOOM_RADIUS, -SUPERBOOM_RADIUS, -SUPERBOOM_RADIUS);
            BlockPos max = center.offset(SUPERBOOM_RADIUS, SUPERBOOM_RADIUS, SUPERBOOM_RADIUS);
            // A crypt is a cracked-stone-brick wall, so a superboom that removes one is a crypt opened. Counted
            // ONCE per detonation rather than per block: a crypt wall is several blocks and counting each of
            // them would hand out the bonus five points from a single charge.
            boolean openedCrypt = false;
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                BlockPos here = pos.immutable();
                if (level.getBlockState(here).is(net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS)) {
                    openedCrypt = true;
                }
                breakIfBreakable(level, here, sp);
            }
            if (openedCrypt) {
                SimScore.cryptBlown();
            }
        });
        ModChat.send("Sim", ModChat.text("Superboom TNT detonated"));
        return true;
    }

    /**
     * Architect's First Draft: resets the puzzle in the room you are in, and is consumed.
     *
     * <p>Verified against the wiki (2026-09-28): it "can be used to reset a failed Dungeon Puzzle in the room
     * the player is in" and is used up. The sim has no concept of which room a puzzle belongs to yet, so it
     * resets the puzzles that are built - which is the same thing while only one is ever up, and is stated here
     * rather than left to be discovered.
     *
     * <p>The point of having it at all is that a failed puzzle otherwise ends a practice run: without a reset
     * he would have to rebuild the map to try the same puzzle twice, which is the opposite of drilling it.
     */
    private static boolean architectDraft(Minecraft client) {
        if (!SimState.canAct(client)) {
            return false;
        }
        com.killer560.hub.roomsim.puzzles.SimPuzzles.resetAll();
        var server = client.getSingleplayerServer();
        if (server != null) {
            var uuid = client.player.getUUID();
            server.execute(() -> {
                ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
                if (sp != null) {
                    // Consumed, like the real one - a reset that costs nothing is not the same decision.
                    sp.getInventory().getSelectedItem().shrink(1);
                }
            });
        }
        ModChat.send("Sim", ModChat.text("Puzzle reset"));
        return true;
    }

    /**
     * Builds a sim item from its Skyblock id, or an empty stack when the id is not one of ours.
     *
     * <p>Public so {@link SimLoadout} can rebuild a saved hotbar without duplicating how items are made - two
     * places constructing the same item is two places to keep in step.
     */
    public static ItemStack build(String skyblockId) {
        for (GiveItem item : GiveItem.values()) {
            if (item.skyblockId.equals(skyblockId)) {
                return build(item);
            }
        }
        return ItemStack.EMPTY;
    }

    /** Instantly breaks the exact block you're looking at - the Dungeon Breaker's whole point ("0 ping",
     *  see {@code DungeonBreakerFeature}), except here there is no real server ping to hide because the
     *  sim's server IS the client's own integrated server. */
    private static boolean dungeonBreak(Minecraft client) {
        BlockHitResult hit = lookedAtBlock(client);
        if (hit == null) {
            fail(client, "not looking at a block");
            return false;
        }
        BlockPos target = hit.getBlockPos().immutable();
        var server = client.getSingleplayerServer();
        if (server == null) {
            return false;
        }
        var uuid = client.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
            if (sp == null) {
                return;
            }
            breakIfBreakable((ServerLevel) sp.level(), target, sp);
        });
        return true;
    }

    /** Skips air and anything with a negative destroy speed (bedrock, barriers, ...) - the same "obstruction
     *  only, never actually clears" boundary the rest of the mod already respects for indestructible blocks. */
    private static void breakIfBreakable(ServerLevel level, BlockPos pos, ServerPlayer breaker) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0) {
            return;
        }
        level.destroyBlock(pos, false, breaker, 512);
    }

    /** Raycast on the CLIENT's level - purely to find what the player is aiming at. Nothing here writes to
     *  the world; only {@link #breakIfBreakable} does that, on the server thread. */
    private static BlockHitResult lookedAtBlock(Minecraft client) {
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getViewVector(1.0f);
        Vec3 end = eye.add(look.scale(LOOK_RANGE));
        BlockHitResult hit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static void fail(Minecraft client, String why) {
        ModChat.send("Sim", ModChat.dim(why));
    }
}
