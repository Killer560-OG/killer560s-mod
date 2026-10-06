package com.killer560.hub.autoroutes;

import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an Auto Routes {@code await:<n>} counts - only things the LOCAL player did (killer560, 2026-10-05: "it shouldn't
 * judge based off of if the secret count rises at all it should just be once it detects I actually click something",
 * and "make sure that it can't be triggered by someone else in the room getting a secret or flicking a lever"). The
 * room's "x/y Secrets" action bar is not read: a teammate's secret raises it too, and levers never touch it.
 * <p>
 * Two separate counters, and neither ever sees the other's events ("The awaits for things besides crypts are not
 * supposed to detect crypts"):
 * <ul>
 *   <li><b>secrets</b> (an await on any node but a crypt node): a right click WE sent on a chest, lever or player head
 *   (a wither-essence skull), seen at {@code MultiPlayerGameMode.useItemOn} - by hand or a route node, each block once;
 *   an item WE picked up ({@code ClientboundTakeItemEntityPacket} collector = our id); a secret bat (Hypixel's Skyblock
 *   health, {@link #isSecretBat}) first appearing within {@link #BAT_SPAWN_RANGE} blocks of us; and the MIMIC dying
 *   after we clicked its trapped chest - the trapped chest's click itself never counts. A button, or any other block,
 *   is not a secret click.</li>
 *   <li><b>crypts</b> (only a crypt node's): the tab list's "Crypts: N" rising, or "A Prince falls. +1 Bonus Score",
 *   within {@link #CRYPT_WINDOW} ticks of a weapon use of ours. Both lines are the score calculator's own readers.</li>
 * </ul>
 * Counted since the previous node FINISHED (the route's start, for the first): {@link #window} is called each time a
 * node finishes. A bat that already existed when the window opened does not count.
 * <p>
 * Everything here is client-side observation of our own packets and our own copy of the world, so it is the same on
 * Hypixel and in the sim. It cannot be perfect when a teammate acts on the same thing at the same moment: a crypt a
 * teammate kills within three seconds of our own weapon use counts as ours, and a bat a teammate makes appear next to
 * us counts.
 */
public final class AwaitEvents {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");

    /** A new secret bat closer than this (blocks, player to bat when it first appears) is one secret. */
    public static final double BAT_SPAWN_RANGE = 10.0; // QUOI AwaitArgument uses 10 (killer560, 2026-10-06)
    /** A crypt / prince kill counts as ours when one of our weapon uses was at most this many ticks before it. The
     *  tab list's crypt line refreshes about once a second (the sim's every 20 ticks), so this is three of those. */
    public static final int CRYPT_WINDOW = 60;
    /** How long after we click a trapped chest its mimic's death still counts, in ticks. */
    private static final int MIMIC_WATCH = 1200;
    /** How close to its trapped chest a dying baby zombie must be to be that chest's mimic. */
    private static final double MIMIC_RANGE = 8.0;

    /** Client ticks seen - the clock every window here is measured on. */
    private static int ticks;
    /** True while a route runs: only then are secrets and crypts counted. */
    private static boolean active;
    private static int secrets;
    private static int crypts;
    private static final Set<String> counted = new HashSet<>();
    private static final Set<Integer> seenBats = new HashSet<>();
    private static final List<String> pending = new ArrayList<>();

    private static int lastWeaponUse = Integer.MIN_VALUE / 2;
    private static int lastTabCrypts = -1;

    private static BlockPos mimicChest;
    private static int mimicArmedAt;
    /** The tick the watched mimic was seen dying, or -1. */
    private static int mimicKilledAt = -1;
    /** Baby zombies that already existed when the trapped chest was clicked - not its mimic (one still dying from the
     *  last mimic, a chest or two away, read as this one's death in the first test run). */
    private static final Set<Integer> zombiesBefore = new HashSet<>();

    private static boolean registered;

    private AwaitEvents() {
    }

    /** Once, from the feature: the prince's chat line. */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ChatObserver.subscribe(message -> {
            String plain = ChatObserver.strip(message).trim();
            if (com.killer560.hub.scorecalc.ScoreCalculatorFeature.PRINCE_KILLED.matcher(plain).matches()) {
                onCryptKill("prince killed");
            }
        });
    }

    // ------------------------------------------------------------------------------------------- route lifecycle

    /** A route starts: the first window opens. */
    static void begin(Minecraft client) {
        active = true;
        Minecraft mc = client;
        lastTabCrypts = com.killer560.hub.scorecalc.ScoreCalculatorFeature.tabCrypts(mc);
        window(client);
    }

    /** A node finished: what is counted from here on belongs to the next node's await. */
    static void window(Minecraft client) {
        secrets = 0;
        crypts = 0;
        counted.clear();
        pending.clear();
        seenBats.clear();
        if (client.level != null) {
            for (Entity e : client.level.entitiesForRendering()) {
                if (e instanceof Bat) {
                    seenBats.add(e.getId());
                }
            }
        }
    }

    static void end() {
        active = false;
        counted.clear();
        seenBats.clear();
        pending.clear();
        secrets = 0;
        crypts = 0;
    }

    static int secrets() {
        return secrets;
    }

    static int crypts() {
        return crypts;
    }

    /** The events since the last call, as log lines ("await secret 1/1: lever at x,y,z (clicked by you)"). */
    static List<String> drain() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    // ------------------------------------------------------------------------------------------- per tick

    /** Every client tick while Auto Routes is on: the mimic watch always, bats and the crypt line during a route. */
    static void tick(Minecraft client) {
        ticks++;
        LocalPlayer player = client.player;
        if (client.level == null || player == null) {
            return;
        }
        tickMimic(client);
        if (!active) {
            return;
        }
        for (Entity e : client.level.entitiesForRendering()) {
            if (!(e instanceof Bat bat) || !seenBats.add(bat.getId())) {
                continue;
            }
            if (!isSecretBat(bat)) {
                continue; // a Spirit Sceptre's bats, or a vanilla one
            }
            double d = bat.distanceTo(player);
            BlockPos p = bat.blockPosition();
            if (d > BAT_SPAWN_RANGE) {
                LOGGER.debug("[AutoRoutes] await ignored: bat appeared at {} {} blocks away (over {})", p.toShortString(),
                        String.format(Locale.US, "%.1f", d), BAT_SPAWN_RANGE);
                continue;
            }
            addSecret("bat " + bat.getId(), "bat spawned at " + p.toShortString()
                    + String.format(Locale.US, " (%.1f blocks from you)", d));
        }
        int now = com.killer560.hub.scorecalc.ScoreCalculatorFeature.tabCrypts(client);
        if (now >= 0) {
            if (lastTabCrypts >= 0 && now > lastTabCrypts) {
                for (int i = lastTabCrypts; i < now; i++) {
                    onCryptKill("crypt killed (Crypts " + i + " -> " + (i + 1) + ")");
                }
            }
            lastTabCrypts = now;
        }
    }

    private static void tickMimic(Minecraft client) {
        if (mimicChest == null) {
            return;
        }
        if (ticks - mimicArmedAt > MIMIC_WATCH) {
            mimicChest = null;
            return;
        }
        Vec3 c = Vec3.atCenterOf(mimicChest);
        for (Entity e : client.level.entitiesForRendering()) {
            // The score calculator's mimic: a baby zombie dying (Odin Mimic.kt) - here, next to the chest we opened.
            if (e instanceof Zombie z && z.isBaby() && z.isDeadOrDying() && !zombiesBefore.contains(z.getId())
                    && z.position().distanceTo(c) <= MIMIC_RANGE) {
                mimicKilledAt = ticks;
                BlockPos at = mimicChest;
                mimicChest = null;
                LOGGER.info("[AutoRoutes] Mimic of the trapped chest at {} killed", at.toShortString());
                if (active) {
                    addSecret("mimic " + at.asLong(), "mimic killed (your trapped chest at " + at.toShortString() + ")");
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------- events

    /** From {@code AwaitEventsGameModeMixin}: we sent a right click on {@code pos} (not refused client-side). */
    public static void onLocalBlockClick(BlockPos pos) {
        if (pos == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        Block b = client.level.getBlockState(pos).getBlock();
        if (b == Blocks.TRAPPED_CHEST) {
            // A trapped chest is the mimic: its click is not the secret, the mimic's death is.
            mimicChest = pos.immutable();
            mimicArmedAt = ticks;
            mimicKilledAt = -1;
            zombiesBefore.clear();
            for (Entity e : client.level.entitiesForRendering()) {
                if (e instanceof Zombie z && z.isBaby()) {
                    zombiesBefore.add(z.getId());
                }
            }
            LOGGER.info("[AutoRoutes] Trapped chest at {} clicked - watching for its mimic", pos.toShortString());
            MimicKiller.onTrappedChestClicked(client, pos.immutable());
            return;
        }
        if (!active) {
            return;
        }
        String kind = b == Blocks.CHEST ? "chest"
                : b == Blocks.LEVER ? "lever" : b == Blocks.PLAYER_HEAD || b == Blocks.PLAYER_WALL_HEAD ? "skull" : null;
        if (kind == null) {
            LOGGER.debug("[AutoRoutes] await ignored: click on {} at {} is not a secret block", b, pos.toShortString());
            return;
        }
        if (b == Blocks.CHEST) {
            lastChestClickAt = ticks;
        }
        addSecret("block " + pos.asLong(), kind + " at " + pos.toShortString() + " (clicked by you)");
    }

    /** From {@code AwaitEventsPacketMixin}: an item entity was picked up by {@code collectorId}. */
    public static void onTakeItem(int itemId, int collectorId) {
        if (!active) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || collectorId != player.getId()) {
            LOGGER.debug("[AutoRoutes] await ignored: an item picked up by someone else");
            return;
        }
        Entity item = client.level == null ? null : client.level.getEntity(itemId);
        addSecret("item " + itemId, "item picked up by you" + (item != null ? " at " + item.blockPosition().toShortString() : ""));
    }

    /** Any use / attack of ours ({@code AwaitEventsGameModeMixin}, and the route's own use packets): what a crypt
     *  or prince kill is attributed to. */
    public static void onLocalWeaponUse() {
        lastWeaponUse = ticks;
    }

    private static void onCryptKill(String what) {
        if (!active) {
            return;
        }
        int ago = ticks - lastWeaponUse;
        if (ago > CRYPT_WINDOW) {
            LOGGER.debug("[AutoRoutes] await ignored: {} - not yours (no weapon use of yours in {} ticks)", what, CRYPT_WINDOW);
            return;
        }
        crypts++;
        pending.add("await crypt " + crypts + ": " + what + " (your weapon use " + ago + " tick(s) earlier)");
    }

    private static void addSecret(String key, String what) {
        if (!counted.add(key)) {
            return;
        }
        secrets++;
        pending.add("await secret " + secrets + ": " + what);
    }

    /** The tick of our last chest click during a route - the window it opens can arrive after the route moved on. */
    private static int lastChestClickAt = Integer.MIN_VALUE / 2;

    /** Whether we clicked a chest during a route at most {@code window} ticks ago. */
    static boolean chestClickedWithin(int window) {
        return ticks - lastChestClickAt <= window;
    }

    /** Whether the mimic of the trapped chest we last clicked has died at or after {@code sinceTick}. */
    static boolean mimicKilledSince(int sinceTick) {
        return mimicKilledAt >= 0 && mimicKilledAt >= sinceTick;
    }

    static int now() {
        return ticks;
    }

    /** Hypixel's secret bats carry Skyblock health (QUOI {@code AwaitArgument}: 100/200/400/800); a vanilla bat - and a
     *  Spirit Sceptre's - has 6. */
    public static boolean isSecretBat(Bat bat) {
        float max = bat.getMaxHealth();
        return max == 100f || max == 200f || max == 400f || max == 800f;
    }
}
