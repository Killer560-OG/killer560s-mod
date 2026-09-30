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

    /**
     * One entry per {@code /simitem <name>} subcommand.
     *
     * <p>killer560 (2026-09-28): "make sure items are the same minecraft item as the one on main server, so
     * make hype an iron sword etherwarp a diamond shovel [...] and tac insertion is a blaze rod."
     *
     * <p>Every base item below is the {@code material} Hypixel's own item list gives (api.hypixel.net, checked
     * 2026-09-28), not a lookalike picked here. It matters for more than looks: an item's model decides its
     * held pose and its icon, so a Hyperion that is a netherite sword in the sim and an iron sword on Hypixel
     * looks different in the hotbar and in the hand, and a hotbar you read by shape is the thing being
     * practised.
     */
    private enum GiveItem {
        ASPECT_OF_THE_VOID("ASPECT_OF_THE_VOID", Items.DIAMOND_SHOVEL, "Aspect of the Void", 1),
        HYPERION("HYPERION", Items.IRON_SWORD, "Hyperion", 1),
        // BAT_WAND, not SPIRIT_SCEPTRE. That is the id Hypixel actually puts in the item's
        // ExtraAttributes (checked against api.hypixel.net's item list, 2026-09-28 - the starred one is
        // STARRED_BAT_WAND). It matters beyond being tidy: a route or an item node recorded on Hypixel
        // stores BAT_WAND, so a sim sceptre carrying the wrong id would not have matched it and the
        // route would have stopped with "SPIRIT_SCEPTRE is not in the hotbar".
        // A LOOKALIKE, not Hypixel's own material, for the two items whose material is paper.
        //
        // killer560 (2026-09-29): "superboom tnt and the spirit sceptre are both paper instead of their
        // propper item." Hypixel really does send PAPER for both, and on Hypixel his resource pack draws the
        // real item over it - but a pack tells them apart by the model id Hypixel attaches, and the sim has no
        // such id to give it, so in here they were simply paper. Setting a Hypixel model id instead would draw
        // a missing texture for anyone without that exact pack, which is worse than paper.
        //
        // So these two use a vanilla item that already looks like the thing. The Skyblock id in CUSTOM_DATA is
        // untouched, which is what everything else in the mod actually reads.
        SPIRIT_SCEPTRE("BAT_WAND", Items.BLAZE_ROD, "Spirit Sceptre", 1),
        TERMINATOR("TERMINATOR", Items.BOW, "Terminator", 1),
        // Paper is right for this one - it IS a blueprint.
        ARCHITECT_FIRST_DRAFT("ARCHITECT_FIRST_DRAFT", Items.PAPER,
                "Architect's First Draft", 1),
        SUPERBOOM_TNT("SUPERBOOM_TNT", Items.TNT, "Superboom TNT", 8),
        ENDER_PEARL("ENDER_PEARL", Items.ENDER_PEARL, "Ender Pearl", 16),
        // Moved off the blaze rod so it is not the same item in the hand as the Spirit Sceptre above.
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
                    if ("DUNGEONBREAKER".equals(id)) {
                        dungeonBreak(client);
                        return net.minecraft.world.InteractionResult.SUCCESS;
                    }
                    // killer560 (2026-09-29): "Superboom should be able to be activated on left click as
                    // well." Right click still works - SimAbilities routes it here - and this is the other
                    // half, because in a real run it is whichever button your thumb reaches first.
                    if ("SUPERBOOM_TNT".equals(id)) {
                        superboomTnt(client);
                        return net.minecraft.world.InteractionResult.SUCCESS;
                    }
                    // Everything else: nothing happens.
                    //
                    // killer560 (2026-09-29): "Make sure I cannot break blocks with anything besides the
                    // dungeon breaker." The sim world is survival, so a sword or a bare hand could mine the
                    // floor out of a room, and nothing remembered those blocks so they never came back - one
                    // mis-click permanently changed the room he was practising in. SUCCESS consumes the click
                    // so the mining animation does not even start.
                    return net.minecraft.world.InteractionResult.SUCCESS;
                });
        // And the same rule on the break itself.
        //
        // AttackBlockCallback alone is not enough: it stops the click that STARTS a break, but creative mode,
        // an instant-break block and anything that reaches PlayerBlockBreakEvents by another route would
        // still go through. This is the one that actually decides whether a block may be destroyed, so the
        // rule lives here as well - two gates on the same rule, because losing a room to a stray click is not
        // recoverable.
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register(
                (level, player, pos, state, entity) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!SimState.isActive() || client == null || player != client.player) {
                        return true;
                    }
                    return "DUNGEONBREAKER".equals(
                            com.killer560.hub.cheatutils.CheatUtils.skyblockId(player.getMainHandItem()));
                });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // killer560 (2026-09-28): "make /simitem open a menu [...] All of it should be through a gui."
            // The named subcommands stay - they cost nothing and a keybind can still fire one - but the bare
            // command now opens the picker rather than printing a list of words to type back.
            var root = ClientCommands.literal("simitem").executes(ctx -> {
                Minecraft mc = Minecraft.getInstance();
                if (!SimState.canAct(mc)) {
                    ModChat.send("Sim", ModChat.text("Sim items only work inside the sim."));
                    return 1;
                }
                mc.execute(() -> mc.setScreenAndShow(new SimItemsScreen(mc.screen)));
                return 1;
            });
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

    /** One item as the picker needs to know it - id and label, nothing about how it is built. */
    public record Entry(String skyblockId, String displayName) {
    }

    /**
     * Every item the sim can hand out, for {@link SimItemsScreen}.
     *
     * <p>Derived from the same enum the commands use rather than copied, so a new item appears in the picker by
     * existing - there is no second list to forget to update.
     */
    public static java.util.List<Entry> entries() {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        for (GiveItem item : GiveItem.values()) {
            out.add(new Entry(item.skyblockId, item.displayName));
        }
        return out;
    }

    /** Everything at once, for the picker's one button. */
    public static void giveEverything(Minecraft client) {
        giveAll();
    }

    /** The Architect's First Draft, named once so the sim's Architect handler cannot drift from the enum. */
    public static final String ARCHITECT_DRAFT_ID = "ARCHITECT_FIRST_DRAFT";

    /**
     * Gives one item by its Skyblock id, for code rather than for a command.
     *
     * <p>Matched against the {@link GiveItem} table rather than built from the id directly, so an id nothing
     * knows about is a quiet no rather than a blank paper item that looks real and does nothing.
     *
     * @return whether the id was one the sim can give
     */
    public static boolean give(Minecraft client, String skyblockId) {
        if (!SimState.canAct(client) || client.player == null || skyblockId == null) {
            return false;
        }
        for (GiveItem item : GiveItem.values()) {
            if (item.skyblockId.equalsIgnoreCase(skyblockId)) {
                giveOnServer(client, item);
                return true;
            }
        }
        return false;
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

    /**
     * Tuners the sim's teleport items come with.
     *
     * <p>killer560 (2026-09-28): "Treat the default as 12 nearly no one plays with less." Instant Transmission
     * is 8 blocks plus a block per Transmission Tuner, four maximum, so four tuners is 12 - what he plays with,
     * and therefore what a route practised in here has to be built against. A sim item that teleported 8 would
     * teach a route that lands four blocks short of where it does on Hypixel.
     */
    private static final int DEFAULT_TUNERS = 4;

    /** Items where a tuner count means anything - the three that carry Instant Transmission. */
    private static final java.util.Set<String> TUNABLE = java.util.Set.of(
            "ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID", "ETHERWARP_CONDUIT");

    /** The exact inverse of {@code CheatUtils.skyblockId}: a plain stack with "id" set in CUSTOM_DATA. */
    private static ItemStack build(GiveItem item) {
        ItemStack stack = new ItemStack(item.base, item.count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(item.displayName));
        CompoundTag tag = new CompoundTag();
        tag.putString("id", item.skyblockId);
        if (TUNABLE.contains(item.skyblockId)) {
            // The same tag Hypixel uses and EtherwarpHopper already reads, so the sim's own range maths and the
            // mod's real one agree without either knowing about the other.
            tag.putInt("tuned_transmission", DEFAULT_TUNERS);
        }
        if (TUNABLE.contains(item.skyblockId)) {
            // The tag the etherwarp OVERLAY looks for. Without it the overlay refuses to draw for the sim's own
            // Aspect of the Void - it accepts an item either with ethermerge set or with the conduit's id - so
            // he was aiming an etherwarp in here with no highlight at all.
            tag.putInt("ethermerge", 1);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        applySkyblockTooltip(stack, item.skyblockId);
        return stack;
    }

    /**
     * Gives a stack the tooltip it has on Hypixel.
     *
     * <p>Public because the sim hands out items from more than one place - the picker, loadouts, wither keys
     * and secret item drops - and killer560 asked for "the same tooltip as main [...] for all items the player
     * gets", which means all of them, not just the ones this class builds.
     */
    public static void applySkyblockTooltip(ItemStack stack, String skyblockId) {
        SimItemLore.apply(stack, skyblockId);
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
            // The flight, the particles and the blast live in SimSpiritSceptre - see its class doc for why the
            // old one-line hit box here read as "the sceptre does nothing".
            case "BAT_WAND" -> SimSpiritSceptre.fire(client);
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
            // A crypt is a cracked-stone-brick wall, so a superboom that removes one is a crypt opened.
            // Counted ONCE per detonation rather than per block: a crypt wall is several blocks and counting
            // each of them would hand out the bonus five points from a single charge.
            boolean openedCrypt = false;
            BlockPos cryptAt = null;
            BlockPos princeAt = null;
            int broken = 0;
            for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
                BlockPos here = pos.immutable();
                BlockState state = level.getBlockState(here);
                if (!isFragile(state) && !SimPrince.isPrince(here)) {
                    continue;
                }
                if ((state.is(net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS)
                        || state.is(net.minecraft.world.level.block.Blocks.INFESTED_CRACKED_STONE_BRICKS))
                        && sealsAChamber(level, here)) {
                    openedCrypt = true;
                    cryptAt = here;
                }
                // The prince: the golden crypt that was already in the room. Blowing one opens it and drops
                // its zombie; only the FIRST prince of the run scores, on his word "multiple princes still
                // the first one only gives 1 score".
                if (SimPrince.isPrince(here) && SimPrince.blow(here)) {
                    princeAt = here;
                }
                // NOT remembered, so it never comes back. killer560 (2026-09-29): "If it breaks something it
                // does not regenerate." That is how a crypt behaves on Hypixel - a wall you have blown open
                // stays open for the rest of the run - and it is the opposite of the Dungeon Breaker, whose
                // blocks DO come back. Sharing SimBreakerState between the two was what made a blown crypt
                // seal itself ten seconds later.
                level.destroyBlock(here, false, sp, 512);
                broken++;
            }
            if (broken == 0) {
                client.execute(() -> fail(client, "nothing fragile there - "
                        + "Superboom only breaks crypt walls"));
                return;
            }
            if (openedCrypt) {
                SimScore.cryptBlown();
                // The zombie that is standing in the crypt. killer560 (2026-09-29): "It should break crypts
                // and have the zombie spawn."
                SimMobs.spawnStarred(client, cryptAt.above(), SimMobs.Kind.ZOMBIE);
            }
            if (princeAt != null) {
                boolean scored = SimPrince.takeScore();
                if (scored) {
                    SimScore.cryptBlown();
                }
                SimMobs.spawnStarred(client, princeAt.above(), SimMobs.Kind.ZOMBIE);
                client.execute(() -> ModChat.send("Sim", ModChat.text("You opened the "),
                        ModChat.value("prince"), scored ? ModChat.text("") : ModChat.dim(" (no score - "
                                + "the run has already had its prince point)")));
            }
            // One charge per detonation. It was free before, so a single Superboom cleared a whole floor.
            sp.getInventory().getNonEquipmentItems().stream()
                    .filter(st -> "SUPERBOOM_TNT".equals(
                            com.killer560.hub.cheatutils.CheatUtils.skyblockId(st)))
                    .findFirst().ifPresent(st -> st.shrink(1));
        });
        ModChat.send("Sim", ModChat.text("Superboom TNT detonated"));
        return true;
    }

    /** How big a sealed pocket may be and still count as a crypt chamber. */
    private static final int CRYPT_CHAMBER_MAX = 48;

    /**
     * Whether this cracked brick seals an enclosed chamber, rather than being ordinary decoration.
     *
     * <p>killer560 (2026-09-30): "if i superboom regular cracked brick that isnt a crypt dont have it spawn a
     * zombie." Every cracked stone brick counted as a crypt, so blowing a decorative one scored the bonus and
     * spawned a starred zombie out of a solid wall.
     *
     * <p>What tells them apart is what is BEHIND the block. A crypt is a wall across a small sealed pocket;
     * decorative cracked brick is set into solid stone with nothing behind it, or faces the open room. So this
     * flood-fills the air on the far side, bounded at {@value #CRYPT_CHAMBER_MAX} blocks: a fill that stays
     * inside that bound is a chamber, and one that runs out of it has escaped into the room and is not.
     *
     * <p>Run BEFORE anything is destroyed, deliberately - the blast opens the wall and joins the chamber to
     * the room, so asking afterwards would find every chamber connected to everything and identify none.
     */
    private static boolean sealsAChamber(ServerLevel level, BlockPos wall) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos start = wall.relative(dir).immutable();
            if (!level.getBlockState(start).isAir()) {
                continue;
            }
            java.util.Set<BlockPos> seen = new java.util.HashSet<>();
            java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
            seen.add(start);
            queue.add(start);
            boolean escaped = false;
            while (!queue.isEmpty() && !escaped) {
                BlockPos at = queue.poll();
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    BlockPos next = at.relative(d).immutable();
                    if (seen.contains(next) || !level.getBlockState(next).isAir()) {
                        continue;
                    }
                    if (seen.size() >= CRYPT_CHAMBER_MAX) {
                        escaped = true;   // too big to be a crypt - this is the room
                        break;
                    }
                    seen.add(next);
                    queue.add(next);
                }
            }
            if (!escaped) {
                return true;
            }
        }
        return false;
    }

    /**
     * Blocks a Superboom is allowed to break.
     *
     * <p>killer560 (2026-09-29): "it shouldnt just break blocks in its way. It should break crypts [...] and
     * cracked stone bricks."
     *
     * <p>It used to clear every breakable block in a 3x3x3 cube, which meant a Superboom aimed anywhere took
     * a bite out of the room - floor, pillars, decoration, all of it. On Hypixel it only opens the fragile
     * walls: the cracked brick of a crypt and the chiseled brick that seals a passage, plus the cobwebs that
     * sometimes stand in for them. Everything else it is pointed at is simply not affected.
     */
    private static boolean isFragile(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS)
                || state.is(net.minecraft.world.level.block.Blocks.INFESTED_CRACKED_STONE_BRICKS)
                || state.is(net.minecraft.world.level.block.Blocks.CHISELED_STONE_BRICKS)
                || state.is(net.minecraft.world.level.block.Blocks.INFESTED_CHISELED_STONE_BRICKS)
                || state.is(net.minecraft.world.level.block.Blocks.INFESTED_STONE_BRICKS)
                || state.is(net.minecraft.world.level.block.Blocks.COBWEB);
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
        if (!SimBreakerState.trySpend()) {
            fail(client, "no Dungeonbreaker charges left");
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
        // Remembered BEFORE it goes, so it can come back - killer560 (2026-09-28): "make sure dungeon breaker
        // blocks come back after broken just like on main". Without it, the second run through a room is
        // through a room he already demolished, and every run after that is a different room.
        if (!state.isAir()) {
            SimBreakerState.remember(level, pos, state);
        }
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
