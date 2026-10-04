package com.killer560.hub.roomsim;

import com.killer560.hub.livemap.DungeonLayout;
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
import com.killer560.hub.compat.McCompat;

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

    private static final org.slf4j.Logger LOGGER = com.killer560.hub.util.ModLog.get("killer560smod-roomsim");

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
        SPIRIT_SCEPTRE("BAT_WAND", Items.ALLIUM, "Spirit Sceptre", 1),
        TERMINATOR("TERMINATOR", Items.BOW, "Terminator", 1),
        // Paper is right for this one - it IS a blueprint.
        ARCHITECT_FIRST_DRAFT("ARCHITECT_FIRST_DRAFT", Items.PAPER,
                "Architect's First Draft", 1),
        SUPERBOOM_TNT("SUPERBOOM_TNT", Items.TNT, "Superboom TNT", 8),
        ENDER_PEARL("ENDER_PEARL", Items.ENDER_PEARL, "Ender Pearl", 16),
        // Moved off the blaze rod so it is not the same item in the hand as the Spirit Sceptre above.
        TACTICAL_INSERTION("TACTICAL_INSERTION", Items.BLAZE_ROD, "Tactical Insertion", 1),
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
        // START, not END: the house rule for anything on the interaction path.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(
                SimItems::clientTick);
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
                    // PUZZLES GET THE CLICK FIRST, whatever is in hand.
                    //
                    // This callback returns SUCCESS for every left click in the sim so a stray swing cannot mine
                    // a room, and SUCCESS consumes the event - so a puzzle that registered its own
                    // AttackBlockCallback afterwards would never see one. Any puzzle that wants left clicks has
                    // to be dispatched from here. Creeper Beams is the first: killer560 (2026-10-01) "creeper
                    // beams does nothing when i shoot the lanterns", and shooting a lantern - Mage beam, arrow,
                    // bare swing - is a left click, while the puzzle only had a right-click hook.
                    //
                    // Ahead of the held-item checks on purpose: a lantern should connect whether or not he
                    // happens to be holding the Dungeonbreaker.
                    if (com.killer560.hub.roomsim.puzzles.SimCreeperPuzzle.tryConnectAt(pos.immutable())) {
                        return net.minecraft.world.InteractionResult.SUCCESS;
                    }
                    String id = com.killer560.hub.cheatutils.CheatUtils.skyblockId(player.getItemInHand(hand));
                    if ("DUNGEONBREAKER".equals(id)) {
                        // ONE charge per block per press, which is what the real item costs.
                        //
                        // This callback fires every TICK while the button is held, not once per click.
                        // Cancelling the break means MultiPlayerGameMode never latches isDestroying, so its
                        // continueDestroyBlock falls straight back into startDestroyBlock - and that is where
                        // AttackBlockCallback lives. Twenty ticks of holding the button was twenty charges,
                        // the whole bar in one second: killer560 (2026-09-30) "Dungeonbreaker still loses
                        // charges before i do /start."
                        //
                        // Keyed on the BLOCK, not on a cooldown: sweeping the crosshair along a wall while
                        // holding the button should break each block it crosses and pay for each one, the way
                        // it does on Hypixel. Only re-breaking the same block without letting go is refused,
                        // and lastBreakPos is cleared the moment the button comes up - see clientTick.
                        BlockPos aimed = aimedBlock(client);
                        if (aimed != null && aimed.equals(lastBreakPos)) {
                            return net.minecraft.world.InteractionResult.SUCCESS;
                        }
                        if (dungeonBreak(client)) {
                            lastBreakPos = aimed;
                        }
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
                    // NOTHING breaks through vanilla in the sim, not even the Dungeon Breaker.
                    //
                    // It used to allow the break when the breaker was held, which meant two things destroyed
                    // the same block: dungeonBreak, which REMEMBERS it and puts it back, and vanilla, which
                    // does not. The vanilla one is why a mined block could stay gone. It also meant simply
                    // holding left-click mined the room while dungeonBreak spent a charge for each one -
                    // killer560 (2026-09-30): "before i do /start make it so mining doesnt use charges from
                    // the breaker."
                    //
                    // Every break in the sim now goes through dungeonBreak, which is the only path that
                    // records the block for restoring and the only one that spends a charge.
                    return false;
                });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // killer560 (2026-09-28): "make /simitem open a menu [...] All of it should be through a gui."
            // The named subcommands stay - they cost nothing and a keybind can still fire one - but the bare
            // command now opens the picker rather than printing a list of words to type back.
            // Registered twice, as /simitem and /item. killer560 (2026-09-30): "change the
            // command for the items to be /simitem or /item." Both are gated on being in the
            // sim, so /item cannot shadow a server command on Hypixel - the same reasoning that
            // kept it off /item in the first place, now satisfied by requires() instead.
            for (String name : new String[]{"simitem", "item"}) {
                // /item is gated with requires(), so outside the sim the command does not exist at all: it
                // does not tab-complete and Hypixel's own /item, if it ever has one, is untouched. That gate
                // is what makes the short name safe - the old comment here refused /item outright for want of
                // it.
                var root = ClientCommands.literal(name)
                        .requires(src -> SimState.canAct(Minecraft.getInstance()))
                        .executes(ctx -> {
                            Minecraft mc = Minecraft.getInstance();
                            if (!SimState.canAct(mc)) {
                                ModChat.send("Sim", ModChat.text("Sim items only work inside the sim."));
                                return 1;
                            }
                            mc.execute(() -> mc.setScreenAndShow(new SimItemsScreen(McCompat.screen(mc))));
                            return 1;
                        });
                for (GiveItem item : GiveItem.values()) {
                    root = root.then(ClientCommands.literal(literalName(item))
                            .executes(ctx -> give(item)));
                }
                root = root.then(ClientCommands.literal("all").executes(ctx -> giveAll()));
                dispatcher.register(root);
            }
        });
    }

    private static String literalName(GiveItem item) {
        return item.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static int help() {
        ModChat.send("Sim", ModChat.text("Usage: /simitem or /item <"
                + "aspect_of_the_void|hyperion|spirit_sceptre|superboom_tnt|ender_pearl|tactical_insertion|"
                + "dungeon_breaker|all>"));
        return 1;
    }

    /**
     * Gives one item. Registered as both {@code /simitem <item>} and {@code /item <item>}; the short
     * name is safe because both are gated on being inside the sim, so neither exists on a server.
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
            // The same fire rate as the left click - it is one weapon, not two.
            // Always returns true, so the caller consumes the click and vanilla never starts drawing the
            // bow. killer560 (2026-09-30): "The terminator still doesnt insta shoot nor does it work without
            // an arrow." Both were the same cause - the click fell through to vanilla, which wants arrows and
            // a draw time. The shot spawns real arrows on the server and consumes no ammunition at all;
            // the fire rate just decides whether this particular click produces one.
            case "TERMINATOR" -> {
                if (SimTerminator.readyToFire()) {
                    SimTerminator.use(client);
                }
                yield true;
            }
            case "ARCHITECT_FIRST_DRAFT" -> architectDraft(client);
            case "SUPERBOOM_TNT" -> superboomTnt(client);
            // NO DUNGEONBREAKER HERE. It is a left-click tool and it is handled on AttackBlockCallback.
            //
            // It used to be on this right-click path as well, which meant right-clicking ANYTHING while
            // holding the breaker broke the block in front of him instead - killer560 (2026-09-30): "if i
            // right click a chest it tries to mine it instead. It does that for everything." The breaker is
            // the item in his hand most of the time, so "everything" is right.
            default -> false;
        };
    }

    /**
     * Whether a right-click with this item is one of ours, and so must not reach vanilla.
     *
     * <p>Separate from {@link #tryUse} because the answer is needed on the SERVER side too, where the ability
     * itself must not run - {@code UseBlockCallback} is a common event and fires once per side. Asking
     * tryUse there would fire every ability twice.
     *
     * <p>An id that is NOT in here falls through to vanilla, which is what makes a chest open while the
     * Dungeon Breaker is in his hand.
     */
    public static boolean consumesUse(String skyblockId) {
        if (skyblockId == null) {
            return false;
        }
        return switch (skyblockId) {
            case "BAT_WAND", "TERMINATOR", "ARCHITECT_FIRST_DRAFT", "SUPERBOOM_TNT" -> true;
            // Ender pearls and the Dungeon Breaker are deliberately absent: the pearl is plain vanilla and
            // the breaker is a left-click tool.
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
                com.killer560.hub.compat.McEntities.knockback(
                        target, SCEPTER_KNOCKBACK, sp.getX() - target.getX(), sp.getZ() - target.getZ());
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
        // Which way he is looking at it. getDirection() points OUT of the block, towards him, so the
        // chamber is behind it and the wall runs across it.
        final net.minecraft.core.Direction face = hit.getDirection();
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
            // THE WHOLE WALL, not a 3x3 cube. killer560 (2026-09-30): "it shouldnt have this 3x3 range
            // instead it should bloww up any cracked bricks". A crypt wall is bigger than three blocks in at
            // least one direction, so a cube either left part of it standing or chewed into the room around
            // it. This walks the connected run of fragile blocks outward from the one he is looking at, which
            // is exactly the wall and nothing else.
            // ONE crypt test, on the block he AIMED at, and BEFORE anything is destroyed.
            //
            // Asking afterwards cannot work: the blast is what joins the chamber to the room, so by the time
            // the loop runs nothing is sealed any more. Counted once per detonation rather than per block, or
            // a single charge would hand out the bonus five points several times over.
            BlockState aimed = level.getBlockState(center);
            // A SECTION OF SMOOTH STONE SLABS IS A CRYPT. killer560 (2026-10-01): "For crypts try having it scan
            // for a sectiion of smooth stone slabs and those are crypts."
            //
            // His log is what makes this safe to act on. Every attempt he made aimed at smooth_stone_slab and
            // broke nothing, because a slab is not in the fragile set so connectedFragile had nothing to walk.
            // The same log also shows sealsAChamber returning false on EVERY detonation, including the
            // cracked_stone_bricks one that broke 34 blocks - so the old crypt test could never fire at all, and
            // "I still cannot explode crypts" was two faults stacked: nothing to break, and nothing to score.
            //
            // A SECTION, not a slab: a lone decorative slab is everywhere, so the run has to be at least
            // MIN_CRYPT_SLABS connected before it counts. The cracked-brick path is left exactly as it was,
            // sealsAChamber included - widening THAT would make every decorative cracked wall on the floor pay
            // out the crypt bonus, and cracked brick is decoration almost everywhere (see docs/SIM.md).
            boolean slabRun = aimed.is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB);
            java.util.List<BlockPos> slabSection = slabRun
                    ? connectedSlabs(level, center) : java.util.List.of();
            boolean slabCrypt = slabRun && slabSection.size() >= MIN_CRYPT_SLABS;
            // CLOSE ENOUGH IS A HIT. killer560 (2026-10-01): "if i use a superboom anywhere close to a crypt then it
            // blows it up. So i can hit the ground with 1 block gap between where i place it and the crypt and
            // itll still blow up the crypt." Only when he did not aim at cracked brick, so a decorative cracked
            // wall next to a crypt still behaves as it did.
            boolean aimedCracked = aimed.is(net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS)
                    || aimed.is(net.minecraft.world.level.block.Blocks.INFESTED_CRACKED_STONE_BRICKS);
            BlockPos nearCrypt = null;
            if (!slabCrypt && !aimedCracked) {
                nearCrypt = nearbySlabCrypt(level, center);
                if (nearCrypt != null) {
                    slabSection = connectedSlabs(level, nearCrypt);
                    slabCrypt = true;
                }
            }
            boolean openedCrypt = slabCrypt
                    || ((aimed.is(net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS)
                    || aimed.is(net.minecraft.world.level.block.Blocks.INFESTED_CRACKED_STONE_BRICKS))
                    && sealsAChamber(level, center));
            BlockPos cryptAt = openedCrypt ? (nearCrypt != null ? nearCrypt : center) : null;
            // A crypt gets the SLAB; anything else keeps the fragile-only fill. See docs/SIM.md for the
            // census behind that - cracked brick is decoration almost everywhere, and a crypt wall is cracked
            // brick interleaved with plain and mossy brick that a fragile-only fill cannot cross.
            java.util.List<BlockPos> targets = slabCrypt
                    ? withStairsAndFloor(level, slabSection)
                    : openedCrypt
                            ? wallSlab(level, center, face)
                            : connectedFragile(level, center);
            BlockPos princeAt = null;
            int broken = 0;
            for (BlockPos here : targets) {
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
            // SAYS WHAT IT DID, in the log, every detonation.
            //
            // killer560 (2026-10-01): "I still cannot blow up crypts." His run's log contains nothing at all
            // from this method - no line for a successful boom and none for a dud - so there is no way to tell
            // which of three things happened: he never used a Superboom, the aimed block was not cracked brick,
            // or sealsAChamber refused and the fragile-only fallback found nothing to break. One line answers
            // that outright.
            //
            // Deliberately NOT chat. killer560 (2026-09-30): "Remove the nothing to blow up line" - a Superboom
            // aimed at ordinary stone should do nothing visible, the way it does on Hypixel. A log line is not
            // something he has to read, and it is the thing that was missing.
            LOGGER.info("Sim superboom at {} face {}: aimed block {}, crypt={} (cracked={}, sealsAChamber={}),"
                            + " {} block(s) broken",
                    center, face,
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(aimed.getBlock()),
                    openedCrypt + (slabCrypt ? " (slab section of " + slabSection.size() + ")" : ""),
                    aimed.is(net.minecraft.world.level.block.Blocks.CRACKED_STONE_BRICKS)
                            || aimed.is(net.minecraft.world.level.block.Blocks.INFESTED_CRACKED_STONE_BRICKS),
                    sealsAChamber(level, center), broken);
            if (broken == 0) {
                return;
            }
            if (openedCrypt) {
                SimScore.cryptBlown();
                // The zombie that is standing in the crypt. killer560 (2026-09-29): "It should break crypts
                // and have the zombie spawn."
                // BEHIND the wall, in the chamber. above() put it inside the stonework, where it either
                // suffocated or never appeared - getDirection() points out towards him, so the opposite
                // of it is the air the crypt was sealing.
                // A slab crypt's chamber is under its lid, whichever way he was facing when he threw it.
                SimMobs.spawnStarred(client, slabCrypt ? cryptAt.below() : cryptAt.relative(face.getOpposite()),
                        SimMobs.Kind.ZOMBIE);
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
        // No chat line. killer560 (2026-09-30): "Also dont have ti send the chat message." On Hypixel a
        // Superboom is silent; the explosion is the feedback.
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
    /**
     * Whether a Dungeonbreaker swing at {@code target} is happening in a puzzle room.
     *
     * <p>True when EITHER the player or the block is in one, which is two rules in one test: you cannot use it
     * while standing in a puzzle room, and you cannot stand outside one and reach into it through the wall.
     * The wiki forbids both.
     *
     * <p>The room is found by nearest grid cell rather than by inverting {@link DungeonLayout#cellCenter}.
     * Cells sit {@code HALF_ROOM} apart, so an inversion is an off-by-one waiting to happen at every boundary,
     * and 121 squared distances on a single left-click costs nothing measurable.
     */
    private static boolean inPuzzleRoom(Minecraft client, BlockPos target) {
        if (client.player == null) {
            return false;
        }
        return isPuzzleCell(nearestCell(target)) || isPuzzleCell(nearestCell(client.player.blockPosition()));
    }

    private static boolean isPuzzleCell(int cell) {
        if (cell < 0) {
            return false;
        }
        String name = SimRoomIndex.nameAtCell(cell);
        return name != null && "PUZZLE".equals(SimFloorGen.typeOf(name));
    }

    /** The 11x11 grid cell whose centre is closest to {@code at}, or -1 when the grid is empty. */
    private static int nearestCell(BlockPos at) {
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        int cells = DungeonLayout.GRID * DungeonLayout.GRID;
        for (int i = 0; i < cells; i++) {
            BlockPos centre = DungeonLayout.cellCenter(i);
            long dx = centre.getX() - at.getX();
            long dz = centre.getZ() - at.getZ();
            long dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /**
     * Whether this block is one of the floor's secrets.
     *
     * <p>Tested by BLOCK rather than by position alone, because a captured room's own chests are secret chests
     * too - {@code SimSecrets.PLACED_CHESTS} holds only the ones the database added on top, so a position test
     * by itself would protect the added ones and leave the captured ones minable. Bat and item secrets are
     * entities and cannot be mined in the first place.
     *
     * <p>The two marker blocks are {@code SimSecrets}' own stand-ins: a soul lantern for wither essence and a
     * lever for a redstone key. A lever is also Water Board's control, which this protects as a side effect,
     * and that is correct for the same reason - it is not scenery.
     */
    private static boolean isSecretBlock(Minecraft client, BlockPos target) {
        if (client.level == null) {
            return false;
        }
        var state = client.level.getBlockState(target);
        return state.is(net.minecraft.world.level.block.Blocks.CHEST)
                || state.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)
                || state.is(net.minecraft.world.level.block.Blocks.SOUL_LANTERN)
                || state.is(net.minecraft.world.level.block.Blocks.LEVER)
                || SimSecrets.PLACED_CHESTS.contains(target);
    }

    private static boolean dungeonBreak(Minecraft client) {
        // NOT on a generated floor before the run has started.
        //
        // killer560 (2026-09-30): "before the countdown make it so breaker doesnt work on the generated map.
        // If i only choose one room thought then the breaker should work." A generated floor is a clear he is
        // about to practise, and breaking its walls while locked in the entrance would let him cut the route
        // before the timer even starts. A single loaded room is a sandbox, so it stays free.
        // hasStarted, not isRunning: the lock means "this floor has not been started yet", and it must not
        // come back once it has. isRunning goes false again whenever a run is stopped or re-armed, which put
        // the breaker back in its locked state mid-session - killer560 (2026-09-30): "the breaker doesnt
        // become a normal breaker again after /start finishes."
        if (SimState.isGeneratedFloor() && !SimRun.hasStarted()) {
            // Silent. killer560 (2026-09-30): "Remove the chat line about it being locked until start."
            // Nothing breaking is already the answer, and holding the button printed it twenty times a
            // second before the per-press guard landed.
            return false;
        }
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
        // NOT IN A PUZZLE ROOM, and NOT ON A SECRET. Both refusals happen BEFORE trySpend below, so a refused
        // break costs no charge - which is the whole reason they are here and not inside breakIfBreakable.
        //
        // killer560 (2026-10-01): "make it so in puzzle rooms I cannot use dungeon breaker" and "make it so i
        // cant dungeon breaker secrets". The first is also the real item's own rule: the wiki says the
        // Dungeonbreaker cannot be used in puzzle rooms, on doors, or to pass through a wall into another room.
        if (inPuzzleRoom(client, target) || SimAbilities.isTeleportMaze(SimState.currentRoomName())) {
            fail(client, "the Dungeonbreaker does not work in puzzle rooms");
            return false;
        }
        if (isSecretBlock(client, target)) {
            // Silent. killer560 (2026-10-04) asked for this chat line to go; the refusal itself stays, and the
            // block not breaking is the answer.
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

    /**
     * The block the attack button is currently being held against, or null.
     *
     * <p>Cleared as soon as the button is released, so a second press on the same block breaks it again. See
     * the comment at the AttackBlockCallback registration for why this exists.
     */
    private static BlockPos lastBreakPos;

    /** Clears {@link #lastBreakPos} when the attack button comes up. Registered in {@link #register}. */
    private static void clientTick(Minecraft client) {
        if (client.options == null || !client.options.keyAttack.isDown()) {
            lastBreakPos = null;
        }
    }

    /** What the player is aiming at, as a position, or null when it is not a block. */
    private static BlockPos aimedBlock(Minecraft client) {
        if (client.player == null || client.level == null) {
            return null;
        }
        BlockHitResult hit = lookedAtBlock(client);
        return hit == null ? null : hit.getBlockPos().immutable();
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
    /**
     * Every fragile block joined to {@code start}, so one charge opens a whole crypt wall.
     *
     * <p>killer560 (2026-09-30) asked for "any cracked bricks" rather than the old fixed 3x3x3 cube, which
     * both left large walls half standing and, on a thin wall, reached past it into the room behind.
     *
     * <p>Bounded at {@link #SUPERBOOM_MAX_BLOCKS}: connected cracked brick could in principle run a long way
     * through a floor's stonework, and an unbounded flood fill on the server thread is how a click becomes a
     * freeze. Orthogonal neighbours only - a wall that meets another only at a corner is a different wall.
     */
    /** How many connected smooth stone slabs make a crypt rather than a decorative slab. */
    private static final int MIN_CRYPT_SLABS = 4;

    /** How far from where a Superboom lands a crypt still counts as hit: a one-block gap is two blocks away. */
    private static final int SUPERBOOM_CRYPT_REACH = 3;

    /**
     * The nearest smooth stone slab within {@link #SUPERBOOM_CRYPT_REACH} of {@code at} whose connected run is a
     * crypt (at least {@link #MIN_CRYPT_SLABS}), or null. Searched in rings of growing distance so the nearest
     * crypt wins when two are in reach, and two layers up and down so a charge on the floor beside a raised or
     * sunken lid still finds it.
     */
    private static BlockPos nearbySlabCrypt(ServerLevel level, BlockPos at) {
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -SUPERBOOM_CRYPT_REACH; dx <= SUPERBOOM_CRYPT_REACH; dx++) {
            for (int dz = -SUPERBOOM_CRYPT_REACH; dz <= SUPERBOOM_CRYPT_REACH; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int dist = dx * dx + dz * dz + dy * dy;
                    if (dist >= bestDist) {
                        continue;
                    }
                    BlockPos p = at.offset(dx, dy, dz);
                    if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB)
                            && connectedSlabs(level, p).size() >= MIN_CRYPT_SLABS) {
                        best = p.immutable();
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    /**
     * The connected run of {@code smooth_stone_slab} containing {@code start}.
     *
     * <p>Same shape as {@link #connectedFragile}, over one block type, and capped the same way so a floor made
     * of slabs cannot turn one Superboom into a thousand-block hole.
     */
    private static java.util.List<BlockPos> connectedSlabs(ServerLevel level, BlockPos start) {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        if (!level.getBlockState(start).is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB)) {
            return found;
        }
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        while (!queue.isEmpty() && found.size() < 256) {
            BlockPos here = queue.removeFirst();
            found.add(here);
            // HORIZONTAL ONLY, so the run stays on the layer he aimed at. killer560 (2026-10-01): "make sure
            // crypts can only break the layer with the slabs and the one directly below it nothing lower than
            // that." A six-way fill climbs any slab stack it meets, and a dungeon has plenty - one charge
            // could open a shaft through several floors of them.
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos next = here.relative(dir).immutable();
                if (seen.add(next)
                        && level.getBlockState(next)
                        .is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE_SLAB)) {
                    queue.add(next);
                }
            }
        }
        return found;
    }

    /**
     * A slab crypt's slabs, plus the stairs around them and the block each stair stands on.
     *
     * <p>killer560 (2026-10-01): "The crypt thing worked perfectly but also needs to break the stairs and that
     * layer below it. THey are the stone brick stairs and the full block right below them." The slab run is the
     * crypt's lid; the stone brick stairs are its rim and the course under them is its floor, and leaving
     * either standing leaves a lip he cannot walk over into a crypt he has just opened.
     *
     * <p>Only stairs TOUCHING the slab run, and only one block under each - not a flood fill of its own. A
     * dungeon floor is full of stone brick stairs, and a fill that wandered off along them would chew a
     * trench across the room from a single charge.
     */
    private static java.util.List<BlockPos> withStairsAndFloor(ServerLevel level,
                                                               java.util.List<BlockPos> slabs) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>(slabs);
        java.util.List<BlockPos> stairs = new java.util.ArrayList<>();
        for (BlockPos slab : slabs) {
            // Horizontal neighbours only, for the same reason the slab run is: the rim is on the slab layer,
            // and a stair above or below it belongs to a different one.
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos next = slab.relative(dir).immutable();
                if (level.getBlockState(next).is(net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS)
                        && out.add(next)) {
                    stairs.add(next);
                }
            }
        }
        // And the course directly under the SLABS as well as under the stairs - that is "the one directly
        // below it", one layer, never two.
        for (BlockPos slab : slabs) {
            addFloorCourse(level, out, slab.below().immutable());
        }
        for (BlockPos stair : stairs) {
            addFloorCourse(level, out, stair.below().immutable());
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * Adds the block directly under a removed slab or stair - one layer, never two.
     *
     * <p>killer560 (2026-10-01): "it does a great job right now of removing the top layer of slabs only, however
     * everywhere it removes a slab have it remove one y layer below it as well." This used to name the stonework
     * it would accept (stone bricks, cobble, andesite...) and whatever his crypts actually sit on was not on the
     * list, so only the lid went. Now it is whatever is there, minus the things a blast must never take: air,
     * anything with a block entity (a chest, a skull, a secret), and the world's own floor.
     */
    private static void addFloorCourse(ServerLevel level, java.util.Set<BlockPos> out, BlockPos under) {
        BlockState below = level.getBlockState(under);
        if (below.isAir() || below.hasBlockEntity()
                || below.is(net.minecraft.world.level.block.Blocks.BEDROCK)
                || below.is(net.minecraft.world.level.block.Blocks.BARRIER)
                || below.is(net.minecraft.world.level.block.Blocks.LEVER)
                || below.is(net.minecraft.world.level.block.Blocks.SOUL_LANTERN)) {
            return;
        }
        out.add(under);
    }

    private static java.util.List<BlockPos> connectedFragile(ServerLevel level, BlockPos start) {
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        if (!isFragile(level.getBlockState(start)) && !SimPrince.isPrince(start)) {
            return found;
        }
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        while (!queue.isEmpty() && found.size() < SUPERBOOM_MAX_BLOCKS) {
            BlockPos here = queue.poll();
            found.add(here);
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = here.relative(dir).immutable();
                if (!seen.add(next)) {
                    continue;
                }
                if (isFragile(level.getBlockState(next)) || SimPrince.isPrince(next)) {
                    queue.add(next);
                }
            }
        }
        return found;
    }

    /** A ceiling on one detonation, so connected stonework cannot turn a click into a server stall. */
    private static final int SUPERBOOM_MAX_BLOCKS = 256;

    /** How wide across the wall one charge reaches, from the block he aimed at. */
    private static final int SUPERBOOM_WALL_PLANE = 2;

    /** How deep INTO the wall one charge reaches. Two is a wall; more is a tunnel. */
    private static final int SUPERBOOM_WALL_DEPTH = 2;

    /**
     * The stone-brick family a crypt wall is actually built from.
     *
     * <p>Plain {@code STONE} is deliberately out. Including it lifts the open rate from 88% to 95% across
     * the library, but a cracked brick set into a stone wall then takes a bite out of the room - which is
     * killer560's "it shouldnt just break blocks in its way", the complaint the whole change started from.
     */
    private static boolean isWallMaterial(BlockState state) {
        return isFragile(state)
                || state.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS)
                || state.is(net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICKS)
                || state.is(net.minecraft.world.level.block.Blocks.STONE_BRICK_STAIRS)
                || state.is(net.minecraft.world.level.block.Blocks.STONE_BRICK_SLAB)
                || state.is(net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICK_STAIRS)
                || state.is(net.minecraft.world.level.block.Blocks.MOSSY_STONE_BRICK_SLAB);
    }

    /**
     * The wall around {@code start}: a slab thin along the face he is looking at and wide across it.
     *
     * <p>Measured over the 411 cracked bricks in the library that actually seal a chamber (2026-09-30): this
     * opens 362 of them, a median of 34 blocks each, against 205 and a median of 2 for the fragile-only fill.
     * The bound is what keeps it a wall rather than a tunnel into the room behind.
     */
    private static java.util.List<BlockPos> wallSlab(ServerLevel level, BlockPos start,
                                                     net.minecraft.core.Direction face) {
        net.minecraft.core.Direction.Axis normal = face.getAxis();
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        while (!queue.isEmpty() && found.size() < SUPERBOOM_MAX_BLOCKS) {
            BlockPos here = queue.poll();
            found.add(here);
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                BlockPos next = here.relative(dir).immutable();
                if (!seen.add(next) || !withinSlab(start, next, normal)) {
                    continue;
                }
                if (isWallMaterial(level.getBlockState(next)) || SimPrince.isPrince(next)) {
                    queue.add(next);
                }
            }
        }
        return found;
    }

    /** Whether this position is still inside the slab: near the aimed face, and shallow into it. */
    private static boolean withinSlab(BlockPos start, BlockPos at, net.minecraft.core.Direction.Axis normal) {
        int dx = Math.abs(at.getX() - start.getX());
        int dy = Math.abs(at.getY() - start.getY());
        int dz = Math.abs(at.getZ() - start.getZ());
        int alongNormal = normal.choose(dx, dy, dz);
        int acrossA = normal == net.minecraft.core.Direction.Axis.X ? dy : dx;
        int acrossB = normal == net.minecraft.core.Direction.Axis.Z ? dy : dz;
        return alongNormal < SUPERBOOM_WALL_DEPTH
                && acrossA <= SUPERBOOM_WALL_PLANE && acrossB <= SUPERBOOM_WALL_PLANE;
    }

}
