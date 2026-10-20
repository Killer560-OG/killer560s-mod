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
 *   the Redstone Key: our click putting it on the room's redstone block (by hand, a route node or Secret Aura alike;
 *   {@link #onNodeClick}), and our pickup of it once the skull goes ({@link #countPickup}) - the pickup is an await
 *   event only, not one of the room's secrets;
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
    /** A dying secret bat further than this from him is not his bat node's kill. */
    public static final double BAT_KILL_RANGE = 10.0;
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
    /** Secret bats of his killed since the window opened - only a BAT node reads it (killer560, 2026-10-13). */
    private static int bats;
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
            if (plain.startsWith("A Prince falls")
                    && com.killer560.hub.scorecalc.ScoreCalculatorFeature.PRINCE_KILLED.matcher(plain).matches()) {
                onCryptKill("prince killed");
            }
            // Diagnostic only (no count: the skull click already counted it): when Hypixel confirms his essence, and
            // whether a route was running and waiting. startsWith is safe - a player's chat line starts with his name.
            if (plain.startsWith("You found a Wither Essence!")) {
                LOGGER.info("[AutoRoutes] secret confirmed by chat: Wither Essence collected at tick {} ({} - window count "
                        + "{})", ticks, active ? "route running" : "no route running", secrets);
            }
        });
    }

    // ------------------------------------------------------------------------------------------- route lifecycle

    /** A route starts: the first window opens - reaching back {@link #START_LOOKBACK} ticks for his own secrets. */
    static void begin(Minecraft client) {
        active = true;
        beganAt = ticks;
        Minecraft mc = client;
        lastTabCrypts = com.killer560.hub.scorecalc.ScoreCalculatorFeature.tabCrypts(mc);
        LOGGER.info("[AwaitEvents] counting started at tick {}: tab crypts {}, {} remembered secret(s) from before the start"
                + " (lookback {} ticks)", ticks, lastTabCrypts, recent.size(), START_LOOKBACK);
        window(client);
        Vec3 at = client.player == null ? null : client.player.position();
        for (Recent r : recent) {
            // Only one taken by the start node: Secret Aura's click on the LAST room's secret as the map warp left it
            // was within the lookback too, and met Museum #1's await for the chest beside the node, so the route moved
            // on before the aura had it (fullclear-1016 run 010, the skull 50 blocks away "counted for the first node").
            if (r.pos() != null && at != null && r.pos().distanceTo(at) > RouteRunMemory.NEAR_NODE) {
                LOGGER.info("[AutoRoutes] await: {} {} tick(s) before the route started is {} blocks from the start "
                        + "node - not its", r.what(), ticks - r.tick(), String.format(Locale.US, "%.1f",
                        r.pos().distanceTo(at)));
                continue;
            }
            if (ticks - r.tick() <= START_LOOKBACK) {
                LOGGER.info("[AutoRoutes] await: {} {} tick(s) before the route started - counted for the first node",
                        r.what(), ticks - r.tick());
                addSecret(r.key(), r.what() + " (" + (ticks - r.tick()) + " tick(s) before the start)");
            } else {
                LOGGER.info("[AwaitEvents] await: {} {} tick(s) before the route started - older than the {}-tick lookback,"
                        + " not counted", r.what(), ticks - r.tick(), START_LOOKBACK);
            }
        }
        recent.clear();
    }

    /**
     * How far before a route's start (ticks) a secret of his still counts for its first node's await.
     * <p>
     * killer560's 2026-10-07 Museum runs, Map Logger: after the Interactive Map took him to the start node, its await
     * for the chest beside it sat at 0/1 until he skipped it, three runs of four. Secret Aura stands down while a map
     * warp is in flight and Auto Routes stays inert for the same span, so both are let go on the same tick - and
     * Secret Aura ticks first (CheatUtils is registered before Auto Routes), so it opened the chest one tick before the
     * route's first window opened, and the click was never counted. The one run that worked was the one he warped onto
     * the node himself: the route armed on the landing tick, before the aura's 100 ms first-seen wait was up. Ten ticks
     * covers that race with room to spare, and a secret he took half a second before stepping on is the start node's.
     */
    static final int START_LOOKBACK = 10;

    /** The tick the running route started: a remembered secret from before it can meet a node's first await. */
    private static int beganAt;

    /**
     * The tick the open window began (the last {@link #window} call, or the route's start). A node that finishes hands
     * everything got since this tick that no met await has used on to the next node's await (see
     * {@code RouteExecutor.finishAction}): his secrets taken while a node was ACTING, not only in its last few ticks.
     * <p>
     * killer560, 2026-10-14, on Hypixel: "it never detects secrets. I think it picks them up faster than it knows it
     * grabs them". The first form of this carry looked back only {@code CARRY_TICKS} (10) from the node's finish. A
     * node that acts longer than that (a PATH that waits out a 67-tick landing, a breaker waiting for charges) let a
     * secret taken early in its action fall out of the carry, and the next await then sat at 0/1 for a secret he had
     * already got (2026-10-18). Claimed secrets are never carried twice, so the window start is the only bound needed.
     */
    private static int windowStart;

    /** The tick the open window began - read before {@link #window} resets it, as the finishing node's carry bound. */
    static int windowStart() {
        return windowStart;
    }

    static int beganAt() {
        return beganAt;
    }

    /** The secrets counted in the open window (keys as {@link RouteRunMemory} has them), for the node they meet. */
    static Set<String> windowSecrets() {
        Set<String> out = new HashSet<>();
        for (String k : counted) {
            if (!k.startsWith("batkill ")) {
                out.add(k);
            }
        }
        return out;
    }

    /** A secret of his seen while no route ran, for {@link #begin}: the last few only. */
    private record Recent(int tick, String key, String what, Vec3 pos) {
    }

    private static final java.util.ArrayDeque<Recent> recent = new java.util.ArrayDeque<>();

    private static void noteBeforeStart(String key, String what, Vec3 pos) {
        recent.addLast(new Recent(ticks, key, what, pos));
        while (recent.size() > 8 || !recent.isEmpty() && ticks - recent.peekFirst().tick() > START_LOOKBACK) {
            recent.pollFirst();
        }
    }

    /** A node finished: what is counted from here on belongs to the next node's await. */
    static void window(Minecraft client) {
        if (!counted.isEmpty()) {
            LOGGER.info("[AutoRoutes] secret window reset at tick {} (opened tick {}): {} secret(s) handed to the carry {}",
                    ticks, windowStart, secrets, counted);
        } else {
            LOGGER.info("[AwaitEvents] window reset at tick {} (opened tick {}): nothing counted, crypts={} bats={}", ticks,
                    windowStart, crypts, bats);
        }
        windowStart = ticks;
        secrets = 0;
        crypts = 0;
        bats = 0;
        refused = 0;
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
        if (active) {
            LOGGER.info("[AutoRoutes] secret counting stopped at tick {} (route ended) - {} secret(s) in the open window {}",
                    ticks, secrets, counted);
        }
        active = false;
        counted.clear();
        seenBats.clear();
        pending.clear();
        secrets = 0;
        crypts = 0;
        bats = 0;
        refused = 0;
    }

    static int secrets() {
        return secrets;
    }

    static int crypts() {
        return crypts;
    }

    static int bats() {
        return bats;
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
        tickKey(client);
        KeySpots.tick(client);
        if (!active) {
            return;
        }
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof Bat dying && dying.isDeadOrDying() && isSecretBat(dying)) {
                onBatKill(client, player, dying);
            }
            if (!(e instanceof Bat bat) || !seenBats.add(bat.getId())) {
                continue;
            }
            if (!isSecretBat(bat)) {
                LOGGER.debug("[AwaitEvents] bat {} at {} ignored: max health {} is not a secret bat's", bat.getId(),
                        bat.blockPosition().toShortString(), bat.getMaxHealth());
                continue; // a Spirit Sceptre's bats, or a vanilla one
            }
            double d = bat.distanceTo(player);
            BlockPos p = bat.blockPosition();
            if (d > BAT_SPAWN_RANGE) {
                LOGGER.info("[AutoRoutes] secret ignored: bat appeared at {} {} blocks away (over {})", p.toShortString(),
                        String.format(Locale.US, "%.1f", d), BAT_SPAWN_RANGE);
                continue;
            }
            RouteRunMemory.gotSecret("bat " + bat.getId(), bat.position(), "bat spawned at " + p.toShortString());
            addSecret("bat " + bat.getId(), "bat spawned at " + p.toShortString()
                    + String.format(Locale.US, " (%.1f blocks from you)", d));
        }
        int now = com.killer560.hub.scorecalc.ScoreCalculatorFeature.tabCrypts(client);
        if (now >= 0) {
            if (lastTabCrypts >= 0 && now > lastTabCrypts) {
                LOGGER.info("[AwaitEvents] tab crypts rose {} -> {} at tick {} (last weapon use {} tick(s) ago)",
                        lastTabCrypts, now, ticks, ticks - lastWeaponUse);
                for (int i = lastTabCrypts; i < now; i++) {
                    onCryptKill("crypt killed (Crypts " + i + " -> " + (i + 1) + ")");
                }
            }
            lastTabCrypts = now;
        }
    }

    /**
     * The watched trapped chest's mimic while it is alive - the same test {@link #tickMimic} uses for its death (a baby
     * zombie within {@link #MIMIC_RANGE} of the chest that was not there when it was clicked), nearest the chest first -
     * or null. What Kill Mimic aims its Spirit Sceptre at after the first two uses went straight down.
     */
    static Zombie liveMimic(Minecraft client) {
        if (mimicChest == null || client.level == null) {
            return null;
        }
        Vec3 c = Vec3.atCenterOf(mimicChest);
        Zombie best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : client.level.entitiesForRendering()) {
            if (e instanceof Zombie z && z.isBaby() && !z.isRemoved() && !z.isDeadOrDying()
                    && !zombiesBefore.contains(z.getId())) {
                double d = z.position().distanceTo(c);
                if (d <= MIMIC_RANGE && d < bestD) {
                    bestD = d;
                    best = z;
                }
            }
        }
        return best;
    }

    private static void tickMimic(Minecraft client) {
        if (mimicChest == null) {
            return;
        }
        if (ticks - mimicArmedAt > MIMIC_WATCH) {
            LOGGER.info("[AwaitEvents] mimic watch on the trapped chest at {} expired after {} ticks without a death",
                    mimicChest.toShortString(), MIMIC_WATCH);
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
                LOGGER.info("[AutoRoutes] Mimic of the trapped chest at {} killed (zombie {} at {}, {} tick(s) after the click,"
                        + " route running={})", at.toShortString(), z.getId(), z.blockPosition().toShortString(),
                        ticks - mimicArmedAt, active);
                RouteRunMemory.gotSecret("mimic " + at.asLong(), Vec3.atCenterOf(at), "mimic killed");
                if (active) {
                    addSecret("mimic " + at.asLong(), "mimic killed (your trapped chest at " + at.toShortString() + ")");
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------- events

    /** We sent a right click on {@code pos} on an unknown face (not refused client-side). */
    public static void onLocalBlockClick(BlockPos pos) {
        onLocalBlockClick(pos, null);
    }

    /** From {@code AwaitEventsGameModeMixin}: we sent a right click on {@code hit} (not refused client-side). */
    public static void onLocalBlockClick(net.minecraft.world.phys.BlockHitResult hit) {
        if (hit != null) {
            onLocalBlockClick(hit.getBlockPos(), hit.getDirection());
        }
    }

    private static void onLocalBlockClick(BlockPos pos, net.minecraft.core.Direction face) {
        if (pos == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        Block b = client.level.getBlockState(pos).getBlock();
        if ((b == Blocks.PLAYER_HEAD || b == Blocks.PLAYER_WALL_HEAD)
                && com.killer560.hub.secrets.SecretsFeature.isRedstoneKey(client.level, pos)) {
            onKeyClick(client, pos);
            return;
        }
        if (b == Blocks.REDSTONE_BLOCK) {
            onNodeClick(client, pos, face);
            return;
        }
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
        String kind = b == Blocks.CHEST ? "chest"
                : b == Blocks.LEVER ? "lever" : b == Blocks.PLAYER_HEAD || b == Blocks.PLAYER_WALL_HEAD ? "skull" : null;
        if (kind == null) {
            if (active) {
                LOGGER.info("[AutoRoutes] secret ignored: click on {} at {} is not a secret block (tick {})", b,
                        pos.toShortString(), ticks);
            }
            return;
        }
        // A dummy lever (Red Blue's cage nook, killer560 2026-10-17) is no secret: not for an await, not for the run
        // memory Auto Secret counts a room from.
        if (b == Blocks.LEVER && com.killer560.hub.secrets.DummyLevers.isDummy(pos)) {
            LOGGER.info("[AutoRoutes] await ignored: the lever at {} is a dummy lever", pos.toShortString());
            return;
        }
        LOGGER.info("[AutoRoutes] secret click sent: {} at {} face {} holding {} at tick {} ({})", kind, pos.toShortString(),
                face, client.player == null ? "?" : client.player.getMainHandItem().getHoverName().getString(), ticks,
                active ? "route running - counts for the waiting node" : "no route running - remembered for the start");
        RouteRunMemory.gotSecret("block " + pos.asLong(), Vec3.atCenterOf(pos), kind + " at " + pos.toShortString());
        if (!active) {
            // Its window can arrive after a route that counts it has started: waited under like any other (below).
            if (b == Blocks.CHEST) {
                lastChestClickAt = ticks;
            }
            noteBeforeStart("block " + pos.asLong(), kind + " at " + pos.toShortString() + " (clicked by you)",
                    Vec3.atCenterOf(pos));
            return;
        }
        if (b == Blocks.CHEST) {
            lastChestClickAt = ticks;
        }
        addSecret("block " + pos.asLong(), kind + " at " + pos.toShortString() + " (clicked by you)");
    }

    /** Our right clicks on a secret block that vanilla refused client-side (sent nothing) in the open window. */
    private static int refused;

    /** Refused secret clicks in the open window - never secrets, only what Auto Routes' await reads as "a click failed". */
    static int refused() {
        return refused;
    }

    /**
     * From {@code AwaitEventsGameModeMixin}: our right click on {@code hit} returned FAIL - vanilla refused it before
     * sending anything, which is what a Shadow Assassin's leap does to every block click (util/ShadowAssassinLeap: the
     * block is outside the client's world border). Not a secret; noted so a route's await knows a click was lost.
     */
    public static void onLocalBlockClickRefused(net.minecraft.world.phys.BlockHitResult hit) {
        Minecraft client = Minecraft.getInstance();
        if (!active || hit == null || client.level == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        Block b = client.level.getBlockState(pos).getBlock();
        if (b != Blocks.CHEST && b != Blocks.TRAPPED_CHEST && b != Blocks.LEVER && b != Blocks.PLAYER_HEAD
                && b != Blocks.PLAYER_WALL_HEAD && b != Blocks.REDSTONE_BLOCK) {
            return;
        }
        refused++;
        pending.add("await: click on " + b.getName().getString().toLowerCase(Locale.ROOT) + " at " + pos.toShortString()
                + " refused client-side - nothing sent" + (com.killer560.hub.util.ShadowAssassinLeap.refuses(client, pos)
                ? " (outside the world border: " + com.killer560.hub.util.ShadowAssassinLeap.describe(client) + ")" : ""));
    }

    // ------------------------------------------------------------------------------------------- the Redstone Key

    /**
     * Ticks after a click on the redstone node that Hypixel's answer (the key put in) still counts it, plus any server
     * stall since the click. Only for a click made with no key known found (a teammate's pickup, a key out of sight).
     */
    static final int NODE_CONFIRM_TICKS = 40;
    /** Ticks after his click on a key skull that the skull going still means he took it. */
    static final int KEY_TAKE_TICKS = 40;

    /** His key clicks waiting for the skull to go: position to {tick, lag mark}. */
    private static final java.util.Map<Long, long[]> keyClicks = new java.util.HashMap<>();
    /** His node clicks with no key known found, waiting for the server to put a key in: position to {tick, lag mark}. */
    private static final java.util.Map<Long, long[]> nodeClicks = new java.util.HashMap<>();
    /** He took a Redstone Key and it has not been put in yet (this run). */
    private static boolean ownKeyFound;
    private static Object keyLevel;
    private static int keyGeneration = Integer.MIN_VALUE;

    /** Forgets the key state on a new run (world change, dungeon entered, sim floor published). */
    private static void checkKeyRun(Minecraft client) {
        int gen = com.killer560.hub.livemap.LiveMapFeature.resetGeneration();
        if (client.level != keyLevel || gen != keyGeneration) {
            keyLevel = client.level;
            keyGeneration = gen;
            keyClicks.clear();
            nodeClicks.clear();
            ownKeyFound = false;
        }
    }

    /**
     * A click on the Redstone Key's skull. Not one of the room's secrets: Hypixel gives the room's key secret for PUTTING
     * the key on its redstone node ("rewards 1 secret", hypixelskyblock.minecraft.wiki, Redstone Key). It is one await
     * event once the skull goes ({@link #countPickup}), as his routes count it. A key already put in (a key head
     * standing on its redstone block) is not a pickup at all.
     */
    private static void onKeyClick(Minecraft client, BlockPos pos) {
        checkKeyRun(client);
        if (client.level.getBlockState(pos.below()).getBlock() == Blocks.REDSTONE_BLOCK) {
            LOGGER.info("[AwaitEvents] key skull click at {} ignored: the key is already on its redstone block",
                    pos.toShortString());
            return;
        }
        KeySpots.noteKeyAt(client, pos);
        keyClicks.put(pos.asLong(), new long[]{ticks, com.killer560.hub.util.ServerLag.mark()});
        LOGGER.info("[AutoRoutes] await: redstone key at {} clicked by you - counted for the await once the skull goes "
                + "(the room's secret is putting it on its redstone block)", pos.toShortString());
    }

    /**
     * A click on a redstone block. The room's key secret when it puts a key in: at once when a key is known found (his
     * own pickup, or one Secret Aura saw go) and the face clicked is open to air (Hypixel takes the key only on such a
     * face, killer560 2026-10-12) - Hypixel credits it on this click, as it does a chest; otherwise when the server
     * answers within {@link #NODE_CONFIRM_TICKS} by putting the key in (a teammate found it). A click with no key does
     * nothing on Hypixel and counts nothing here.
     */
    private static void onNodeClick(Minecraft client, BlockPos pos, net.minecraft.core.Direction face) {
        checkKeyRun(client);
        boolean open = face == null || client.level.getBlockState(pos.relative(face)).isAir();
        boolean known = ownKeyFound || com.killer560.hub.cheatutils.SecretAuraFeature.keyFoundNotPlaced();
        if (known && open) {
            ownKeyFound = false;
            com.killer560.hub.cheatutils.SecretAuraFeature.noteKeyPlaced();
            countNode(pos, "clicked by you, key found");
            return;
        }
        nodeClicks.put(pos.asLong(), new long[]{ticks, com.killer560.hub.util.ServerLag.mark()});
        LOGGER.info("[AutoRoutes] await: redstone block at {} clicked by you with {} - counted if the server puts a key in",
                pos.toShortString(), known ? "a covered face" : "no key known found");
    }

    private static void countNode(BlockPos pos, String how) {
        String key = "block " + pos.asLong();
        String what = "redstone key put on its redstone block at " + pos.toShortString() + " (" + how + ")";
        RouteRunMemory.gotSecret(key, Vec3.atCenterOf(pos), what);
        if (!active) {
            LOGGER.info("[AwaitEvents] {} at tick {} - no route running, remembered for the next start", what, ticks);
            noteBeforeStart(key, what, Vec3.atCenterOf(pos));
            return;
        }
        addSecret(key, what);
    }

    /** What {@link RouteRunMemory} files a key pickup under: an await event, never one of the room's secrets. */
    static final String KEY_PICKUP_WHAT = "redstone key taken";

    /**
     * His pickup of the key, confirmed by the skull going: one for the await, as his routes were recorded with it (Redstone
     * Crypt #1 waits for 2 by its chest and the key; the sim stalled there 20 s on every floor of fullclear acplan-1020
     * while the pickup counted nothing), and as {@link KeySpots} prefills one for a spot the key never spawned at. Not one
     * of the room's secrets: Hypixel gives that for the placing ({@link #onNodeClick}), so the run memory files it like a
     * lever, which {@code RouteRunMemory.secretsSince} leaves out of a room's count.
     */
    private static void countPickup(BlockPos p) {
        String key = "keypickup " + p.asLong();
        String what = KEY_PICKUP_WHAT + " at " + p.toShortString();
        RouteRunMemory.gotSecret(key, Vec3.atCenterOf(p), what);
        if (!active) {
            noteBeforeStart(key, what + " (by you)", Vec3.atCenterOf(p));
            return;
        }
        addSecret(key, what + " (by you)");
    }

    /** Every tick: his key clicks answered (the skull gone) and node clicks answered (a key put in). */
    private static void tickKey(Minecraft client) {
        if (keyClicks.isEmpty() && nodeClicks.isEmpty()) {
            return;
        }
        checkKeyRun(client);
        var it = keyClicks.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BlockPos p = BlockPos.of(e.getKey());
            if (client.level.isLoaded(p) && !com.killer560.hub.secrets.SecretsFeature.isRedstoneKey(client.level, p)) {
                it.remove();
                ownKeyFound = true;
                LOGGER.info("[AutoRoutes] await: you took the redstone key at {}", p.toShortString());
                countPickup(p);
            } else if (ticks - e.getValue()[0] > KEY_TAKE_TICKS + com.killer560.hub.util.ServerLag.ticksSince(e.getValue()[1])) {
                it.remove();
                LOGGER.info("[AwaitEvents] key click at {} expired: skull still there {} tick(s) after the click - not taken",
                        p.toShortString(), ticks - e.getValue()[0]);
            }
        }
        var nt = nodeClicks.entrySet().iterator();
        while (nt.hasNext()) {
            var e = nt.next();
            BlockPos p = BlockPos.of(e.getKey());
            if (client.level.isLoaded(p) && keyPutIn(client, p)) {
                nt.remove();
                ownKeyFound = false;
                com.killer560.hub.cheatutils.SecretAuraFeature.noteKeyPlaced();
                countNode(p, "clicked by you, the server put the key in");
            } else if (ticks - e.getValue()[0] > NODE_CONFIRM_TICKS + com.killer560.hub.util.ServerLag.ticksSince(e.getValue()[1])) {
                nt.remove();
                LOGGER.info("[AwaitEvents] redstone block click at {} expired: no key put in {} tick(s) after the click -"
                        + " not counted", p.toShortString(), ticks - e.getValue()[0]);
            }
        }
    }

    /**
     * Whether a key has gone in at this node: the redstone block is gone (the sim, RSA's "block changed"), a key head
     * stands on it (the testkit's server, Skyblocker's pos.above()), or an armor stand wearing the key is at it (RSA).
     */
    private static boolean keyPutIn(Minecraft client, BlockPos node) {
        if (client.level.getBlockState(node).getBlock() != Blocks.REDSTONE_BLOCK
                || com.killer560.hub.secrets.SecretsFeature.isRedstoneKey(client.level, node.above())) {
            return true;
        }
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(node).inflate(1.0, 3.0, 1.0);
        for (net.minecraft.world.entity.decoration.ArmorStand s : client.level.getEntitiesOfClass(
                net.minecraft.world.entity.decoration.ArmorStand.class, box)) {
            var prof = s.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .get(net.minecraft.core.component.DataComponents.PROFILE);
            if (prof != null && prof.partialProfile() != null
                    && REDSTONE_KEY_ID.equals(prof.partialProfile().id())) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.UUID REDSTONE_KEY_ID = java.util.UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e");

    /** For the testkit: whether he has a key found and not put in, by his own pickup. */
    static boolean ownKeyFound() {
        return ownKeyFound;
    }

    /** From {@code AwaitEventsPacketMixin}: an item entity was picked up by {@code collectorId}. */
    public static void onTakeItem(int itemId, int collectorId) {
        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;
        if (player == null || collectorId != player.getId()) {
            if (active) {
                LOGGER.info("[AutoRoutes] secret ignored: item {} picked up by entity {}, not you", itemId, collectorId);
            }
            return;
        }
        Entity item = client.level == null ? null : client.level.getEntity(itemId);
        String what = "item picked up by you" + (item != null ? " at " + item.blockPosition().toShortString() : "");
        RouteRunMemory.gotSecret("item " + itemId, item != null ? item.position() : player.position(), what);
        if (!active) {
            LOGGER.info("[AwaitEvents] {} (item {}) at tick {} - no route running, remembered for the next start", what,
                    itemId, ticks);
            noteBeforeStart("item " + itemId, what, item != null ? item.position() : null);
            return;
        }
        addSecret("item " + itemId, what);
    }

    /** Any use / attack of ours ({@code AwaitEventsGameModeMixin}, and the route's own use packets): what a crypt
     *  or prince kill is attributed to. */
    public static void onLocalWeaponUse() {
        lastWeaponUse = ticks;
    }

    /** Right clicks of his (a held key's burst is one) since the game started - a BAT node's Spirit Sceptre counts its own. */
    private static int weaponUses;
    private static int lastUseCountedAt = Integer.MIN_VALUE / 2;

    /** One use packet (item or block) went out this client tick; a burst of several in one tick is one use. */
    public static void onUseSent() {
        if (lastUseCountedAt != ticks) {
            lastUseCountedAt = ticks;
            weaponUses++;
        }
    }

    /** {@link #onUseSent} so far; take the difference, never the value. */
    static int weaponUses() {
        return weaponUses;
    }

    private static void onCryptKill(String what) {
        if (!active) {
            LOGGER.info("[AwaitEvents] crypt ignored: {} at tick {} - no route running", what, ticks);
            return;
        }
        int ago = ticks - lastWeaponUse;
        if (ago > CRYPT_WINDOW) {
            LOGGER.info("[AutoRoutes] await ignored: {} - not yours (no weapon use of yours in {} ticks, last {} ago)", what,
                    CRYPT_WINDOW, ago);
            return;
        }
        crypts++;
        pending.add("await crypt " + crypts + ": " + what + " (your weapon use " + ago + " tick(s) earlier)");
        LOGGER.info("[AwaitEvents] crypt counted: {} at tick {} - crypt count {} -> {} (weapon use {} tick(s) earlier)", what,
                ticks, crypts - 1, crypts, ago);
    }

    /**
     * A secret bat dying near him (its death event reaches the client as health 0): his BAT node's kill when one of his
     * weapon uses was at most {@link #CRYPT_WINDOW} ticks before it, as a crypt kill is attributed. Each bat once.
     */
    private static void onBatKill(Minecraft client, LocalPlayer player, Bat bat) {
        if (!counted.add("batkill " + bat.getId())) {
            return;
        }
        double d = bat.distanceTo(player);
        int ago = ticks - lastWeaponUse;
        if (d > BAT_KILL_RANGE || ago > CRYPT_WINDOW) {
            LOGGER.info("[AutoRoutes] secret ignored: bat died {} blocks away, your last weapon use {} tick(s) earlier",
                    String.format(Locale.US, "%.1f", d), ago);
            return;
        }
        bats++;
        LOGGER.info("[AwaitEvents] bat kill counted at {} tick {}: bat count {} -> {} ({} blocks, weapon use {} tick(s) ago)",
                bat.blockPosition().toShortString(), ticks, bats - 1, bats, String.format(Locale.ROOT, "%.1f", d), ago);
        pending.add("await bat " + bats + ": secret bat killed at " + bat.blockPosition().toShortString()
                + String.format(Locale.US, " (%.1f blocks from you, your weapon use %d tick(s) earlier)", d, ago));
        // The kill is also a SECRET for an await (QUOI: a bat's hurt/death sound calls onSecret on the waiting node),
        // unless this bat's appearance was already counted this run - one bat, one secret. killer560's Spider, Mod Only
        // Test 2026-10-19 15:33 and 15:36: the bat was alive before the route began (so its appearance was never
        // counted), #1 BAT killed it, and #2 PATH "await 2" (the chest and that bat) sat at 1/2 until he left-clicked.
        String key = "bat " + bat.getId();
        boolean appearanceCounted = RouteRunMemory.has(key);
        RouteRunMemory.gotSecret(key, bat.position(), "secret bat killed at " + bat.blockPosition().toShortString());
        if (appearanceCounted) {
            LOGGER.info("[AutoRoutes] secret: bat kill at {} not counted again - its appearance already counted this run",
                    bat.blockPosition().toShortString());
        } else {
            addSecret(key, "secret bat killed by you at " + bat.blockPosition().toShortString()
                    + String.format(Locale.US, " (%.1f blocks from you)", d));
        }
    }

    private static void addSecret(String key, String what) {
        if (!counted.add(key)) {
            LOGGER.info("[AutoRoutes] secret: {} already counted in this window ({}) - not counted twice", what, key);
            return;
        }
        secrets++;
        pending.add("await secret " + secrets + ": " + what);
        LOGGER.info("[AutoRoutes] secret signal: {} [key {}] at tick {} - window count {} -> {}", what, key, ticks,
                secrets - 1, secrets);
    }

    /** The tick of our last chest click (during a route, or just before one) - its window can arrive after the route moved on. */
    private static int lastChestClickAt = Integer.MIN_VALUE / 2;

    /** Whether we clicked a chest at most {@code window} ticks ago. */
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
