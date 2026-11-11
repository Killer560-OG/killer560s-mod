package com.killer560.hub.bloodrush;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.RouteCoords;
import com.killer560.hub.compat.McRender;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.HeldMap;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Blood Rush Helper (killer560, 2026-11-03: "Theirs shows where you should go to kill blood rush mobs. That is what I
 * want." - the reference mod adm's BR Roles). On the blood rush a room's Wither Key drops only once its starred mobs are
 * dead; this boxes them, shares them out between the killers, numbers yours in kill order, fills in the next one, and
 * drops each box as its mobs die. Information only: it never moves, aims, clicks or chats.
 *
 * <p>Behaviors mirrored from adm's own code (facts, our code): a starred mob is claimed by the box its body overlapped
 * most where first seen, else the nearest box of its room within {@link #CLAIM_REACH} blocks; a box shows until every mob
 * it claimed is dead (it shows before any is in view too) or the map shows the room cleared; Hypixel sends no death, so a
 * mob removed within {@link #DIES_ALONE} blocks, or within {@link #DIES_WITH_TAG} on the same tick as its name tag, is
 * dead, and further off only out of view; the rooms shown are the one you are in and the next room on the rush once a
 * wither door into it opens; the rush path is the rooms on the way (the room tree) from the Entrance to each wither or
 * blood door; a room's plan keys on the door you came in by (the doorway you last walked through, else its door on the
 * Entrance side of the path); roles, the door runner, Door Kills, the drop at fairy and master mode class roles are in
 * {@link BloodRushRoles}; a room with one box is a miniboss room and every role kills it.
 *
 * <p><b>The split</b> is where adm has hand-made plans per room x entry door x team size on its site. Ours is a rule
 * fitted to those plans ({@link BloodRushSplit}; killer560s-mod-logs\adm-study\measure_final.py): the room's boxes are grouped into one region a role
 * with k-means seeded from the box farthest from the door, regions kept to equal box counts on normal floors; roles are
 * numbered farthest region first on normal floors, most boxes first in master mode; inside a role the kill order walks
 * from the door, nearest first, then each next nearest the last. Once your own boxes are done, the others' that are left
 * show as help. Agreement with adm's plans is measured, not exact: an adm player and one of ours can still share a box,
 * so with adm's role lines in party chat the HUD says so.
 *
 * <p><b>Boxes</b> come from the room's known spawn spots ({@link BloodRushSpots}: earlier sightings, his own and the
 * jar's shipped table, grouped
 * within {@link #LINK} blocks) and, for mobs no learned box claims, from the mobs themselves where first seen.
 *
 * <p><b>Legit and cheat.</b> Legit: depth-tested, the rush and its doors read off the hotbar map. Cheat build only: Through
 * Walls, and Whole Rush Path (the scanned dungeon's path, every room on it at once).
 */
public final class BloodRushHelperFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bloodrush");

    /** Learned spots, and mobs no learned box claims, this close (each of x/z, 3 in y) are one box. */
    static final double LINK = 3.5;
    /** adm: a box further than this from a mob does not claim it. */
    static final double CLAIM_REACH = 5.0;
    /** adm's death rule: removed this close is dead... */
    static final double DIES_ALONE = 32.0;
    /** ...or this close on the same tick as its name tag. */
    static final double DIES_WITH_TAG = 48.0;
    /** A spawn is learned only when no player stands this close to the mob (an untouched mob has not moved). */
    static final double LEARN_ALONE = 10.0;
    /**
     * ...and only once it has stood on that spot this many client ticks (a mob first seen falling in, or already walking
     * after someone, is not learned where it was first seen). Since bloodrush2-1104 a spot is learned all run long, not
     * only on the rush, so the clear's rooms fill the table too.
     */
    static final int LEARN_STILL_TICKS = 10;
    /** How far (blocks, each axis) a learning mob may drift and still be standing. */
    static final double LEARN_STILL_DRIFT = 0.4;
    /** A learning mob that moved restarts from where it stands at most this many times. */
    static final int LEARN_RESTARTS = 3;

    private static final Pattern MORT_START = Pattern.compile("^\\[NPC\\] Mort: Here, I found this map when I first entered the dungeon\\.$");
    private static final Pattern BLOOD_DOOR_OPENED = Pattern.compile("^The BLOOD DOOR has been opened!?$");
    private static final Pattern WITHER_DOOR_OPENED = Pattern.compile("^[A-Za-z0-9_]{1,16} opened a WITHER door!?$");

    /** A starred mob of this world: where it was first seen, its body there, and its state. */
    private static final class Mob {
        final int id;
        final Vec3 first;
        final AABB body;
        int room;
        Entity entity;
        Entity tag;
        int tagGoneAt = -1;
        boolean dead;

        Mob(int id, Vec3 first, AABB body, int room, Entity entity, Entity tag) {
            this.id = id;
            this.first = first;
            this.body = body;
            this.room = room;
            this.entity = entity;
            this.tag = tag;
        }
    }

    /** A box: its space, the mobs it claimed, whether it came from learned spots. */
    static final class Box {
        AABB box;
        final List<Integer> mobs = new ArrayList<>();
        int alive;
        boolean learned;
        double cx;
        double cz;

        boolean done() {
            return !mobs.isEmpty() && alive == 0;
        }
    }

    /** What a box is to him: his (in kill order), help (someone else's, his own being done), or someone else's. */
    enum Look { MINE, HELP, THEIRS }

    /** One box as drawn, and the facts tests read. */
    record Spot(String room, AABB box, int argb, String label, boolean next, Look look, int order, int role, int mobs,
                int alive, boolean learnedOnly) {
    }

    private static final Map<Integer, Mob> MOBS = new HashMap<>();
    /** Mobs first seen alone, waiting to be seen standing still before their spot is learned: id -> pending. */
    private static final Map<Integer, Pending> LEARNING = new HashMap<>();

    /** A spot being learned: where the mob stands, since when, in which room, and how often it moved off. */
    private static final class Pending {
        Vec3 at;
        int since;
        int room;
        int restarts;

        Pending(Vec3 at, int since, int room) {
            this.at = at;
            this.since = since;
            this.room = room;
        }
    }
    /** The doorway (world x, z) he last walked into each room through. */
    private static final Map<Integer, double[]> WALKED_IN = new HashMap<>();
    /** Rooms the rush has been through, in order, and those it had when it reached fairy (null before). */
    private static final LinkedHashSet<Integer> RUSHED = new LinkedHashSet<>();
    private static Set<Integer> beforeFairy;
    /** Bumped on every change to {@link #RUSHED}, so the rush path cache can key on it without walking the set. */
    private static int rushedVersion;
    private static int nextRoom = -1;
    private static volatile List<Spot> CACHED = List.of();
    private static volatile Map<Integer, double[]> LAST_DOORS = Map.of();
    private static Object level;
    private static int generation = Integer.MIN_VALUE;
    private static boolean bloodOpened;
    /** Set by build(): the player stands in a room on the rush path (read each frame by Secret Waypoints). */
    private static volatile boolean inRushRoom;
    private static boolean mortSeen;
    private static boolean wasInDungeon;
    private static boolean droppedSaid;
    private static int myTile = -1;
    private static int lastRoom = -1;
    /** The snapshot every room index kept here (nextRoom, lastRoom, RUSHED, beforeFairy, WALKED_IN, each Mob and Pending)
     *  is numbered by; a new numbering maps them all over at once ({@link #renumber}, roomidx-1104). */
    private static DungeonLayout numberedBy;
    private static int ticks;
    private static int clock;
    /** An armor stand (a starred mob's name tag rides one) loaded since the last starred-mob scan; the scan runs on it, else
     *  every {@link #SCAN_EVERY} ticks (a tag renamed in place): it reads every stand's name, ~22 KB a time on a sim floor. */
    private static boolean standLoaded = true;
    private static int lastScan = Integer.MIN_VALUE / 2;
    private static final int SCAN_EVERY = 20;
    /** The learned-spot boxes of each room, by room name, while its frame and the spot table stay the same. */
    private static final Map<String, Learned> LEARNED = new HashMap<>();

    private record Learned(int x, int z, int rot, int version, List<AABB> boxes) {
    }
    private static String status = "idle";
    private static int statusPath = -1;
    private static int statusShown = -1;
    /** Test hook: learn spawn spots in the Dungeon Sim too (never on in play). */
    public static volatile boolean learnInSimForTest;

    private BloodRushHelperFeature() {
    }

    public static void register() {
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            WorldRenderUtils.initThroughWalls();
        }
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BloodRushHelper.tick", BloodRushHelperFeature::tick));
        ClientEntityEvents.ENTITY_LOAD.register((e, w) -> standLoaded |= e instanceof ArmorStand);
        com.killer560.hub.util.ChatObserver.subscribe(m -> onChat(com.killer560.hub.util.ChatObserver.strip(m)));
        LevelRenderEvents.COLLECT_SUBMITS.register(BloodRushHelperFeature::render);
        com.killer560.hub.hud.HudElementRegistry.register(HUD);
        net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.addLast(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("killer560smod", "blood_rush_roles"),
                (graphics, deltaTracker) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null || com.killer560.hub.hud.HudVisibility.menuOpen()
                            || com.killer560.hub.compat.McCompat.hudHidden(client) || !HUD.isEnabledInSettings()) {
                        return;
                    }
                    com.killer560.hub.hud.HudElementRegistry.drawAt(graphics, HUD);
                });
    }

    /** The notice a party running adm's roles gets (killer560, 2026-11-03: "I just don't want someone on their mod to be
     *  player 1 and ours 2 and they go for the same mobs"). */
    public static final String ADM_NOTICE = "ADM players in party: our split is close to ADM's, not identical - call your rooms";

    /** The two HUD lines while role claims from party chat are known (the notice, then who claimed what), else none. */
    public static List<String> hudLines() {
        if (!BloodRushConfig.getInstance().isEnabled() || !BloodRushRoles.admInParty()) {
            return List.of();
        }
        return List.of(ADM_NOTICE, "BR roles: " + BloodRushRoles.summary());
    }

    /** The roles line: shown only while someone in the party has claimed a role in adm's form. Movable in the HUD editor. */
    public static final com.killer560.hub.hud.HudElement HUD = new com.killer560.hub.hud.BasicHud("blood_rush_roles", "Blood Rush Roles", 4, 120) {

        @Override
        public int width() {
            return 2 + Minecraft.getInstance().font.width(ADM_NOTICE);
        }

        @Override
        public int height() {
            return 2 + 2 * com.killer560.hub.hud.HudText.ROW;
        }

        @Override
        public boolean isEnabledInSettings() {
            return BloodRushConfig.getInstance().isEnabledRaw();
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int x, int y) {
            boolean example = com.killer560.hub.compat.McCompat.screen(Minecraft.getInstance())
                    instanceof com.killer560.hub.hud.HudEditorScreen;
            List<String> lines = example ? List.of(ADM_NOTICE, "BR roles: 1 Bob, 2 you, door Jim (2 killing)") : hudLines();
            if (lines.isEmpty()) {
                return;
            }
            if (!example) {
                com.killer560.hub.hud.HudSeen.markDrawn(id());
            }
            var font = Minecraft.getInstance().font;
            graphics.text(font, "§6" + lines.get(0), x + 1, y + 1, 0xFFFFAA00, true);
            graphics.text(font, "§7" + lines.get(1), x + 1, y + 1 + com.killer560.hub.hud.HudText.ROW, 0xFFAAAAAA, true);
        }
    };

    /** Secret Waypoints asks each frame: hide them (Hide Secret Waypoints on, door still closed, standing in a rush room). */
    public static boolean hidesSecretWaypoints() {
        BloodRushConfig c = BloodRushConfig.getInstance();
        return inRushRoom && !bloodOpened && c.isEnabled() && c.isHideSecrets();
    }

    /** A setting changed: rebuild on the next tick. */
    public static void invalidate() {
        ticks = 0;
    }

    private static void onChat(String raw) {
        if (raw == null) {
            return;
        }
        String msg = com.killer560.hub.util.ChatObserver.stripCodes(raw).trim();
        if (BloodRushRoles.onChat(msg, Minecraft.getInstance())) {
            ticks = 0;
            return;
        }
        if (BLOOD_DOOR_OPENED.matcher(msg).matches()) {
            if (!bloodOpened) {
                LOGGER.info("[BloodRush] the blood door is open: the rush is over, boxes cleared");
            }
            bloodOpened = true;
            CACHED = List.of();
        } else if (MORT_START.matcher(msg).matches()) {
            mortSeen = true;
            bloodOpened = false;
            RUSHED.clear();
            rushedVersion++;
            beforeFairy = null;
            nextRoom = -1;
        } else if (WITHER_DOOR_OPENED.matcher(msg).matches()) {
            Minecraft.getInstance().execute(BloodRushHelperFeature::witherDoorOpened);
        }
    }

    /** "x opened a WITHER door!": the next rush room is the one beyond the shown wither door beside the rush so far. */
    private static void witherDoorOpened() {
        DungeonLayout layout = renumber(DungeonLayout.current());
        int cur = layout.currentRoom();
        int best = -1;
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (!HeldMap.isDoorCell(idx) || HeldMap.doorType(idx) != DungeonLayout.DOOR_WITHER) {
                continue;
            }
            int[] s = HeldMap.sides(idx);
            int a = layout.roomOfCell(s[0]);
            int b = layout.roomOfCell(s[1]);
            if (a < 0 || b < 0 || a == b) {
                continue;
            }
            boolean aIn = a == cur || RUSHED.contains(a);
            boolean bIn = b == cur || RUSHED.contains(b);
            if (aIn != bIn) {
                best = aIn ? b : a;
                if ((aIn ? a : b) == cur) {
                    break; // the door out of the room he is in wins
                }
            }
        }
        if (best >= 0) {
            nextRoom = best;
            if (beforeFairy == null && "FAIRY".equalsIgnoreCase(type(layout, best))) {
                reachFairy();
            }
            LOGGER.info("[BloodRush] wither door opened: next rush room {}", layout.name(best));
        }
        ticks = 0;
    }

    private static void reachFairy() {
        beforeFairy = new HashSet<>(RUSHED);
        BloodRushConfig cfg = BloodRushConfig.getInstance();
        if (BloodRushRoles.youDropped(cfg, true) && !droppedSaid) {
            droppedSaid = true;
            com.killer560.hub.util.ModChat.send("Blood Rush", com.killer560.hub.util.ModChat.text(
                    "Past fairy: you leave the rush - clear!"));
        }
    }

    private static boolean late(int room) {
        return beforeFairy != null && !beforeFairy.contains(room);
    }

    private static void resetWorld() {
        MOBS.clear();
        LEARNING.clear();
        WALKED_IN.clear();
        RUSHED.clear();
        rushedVersion++;
        beforeFairy = null;
        nextRoom = -1;
        pathCache = null;
        pathLayout = null;
        CACHED = List.of();
        inRushRoom = false;
        bloodOpened = false;
        mortSeen = false;
        droppedSaid = false;
        myTile = -1;
        lastRoom = -1;
        numberedBy = null;
        LEARNED.clear();
        standLoaded = true;
    }

    /** A new layout snapshot numbers its rooms afresh (DungeonLayout.numbersLike): every kept room index is mapped into
     *  it by its cell, or a mob first seen in his room at run start (rooms join the map every tick) counted for another
     *  room and its box never showed. Runs only when the numbering changed. */
    private static DungeonLayout renumber(DungeonLayout layout) {
        DungeonLayout old = numberedBy;
        numberedBy = layout;
        if (layout.numbersLike(old)) {
            return layout;
        }
        nextRoom = layout.sameRoom(old, nextRoom);
        lastRoom = layout.sameRoom(old, lastRoom);
        layout.sameRooms(old, RUSHED);
        if (beforeFairy != null) {
            layout.sameRooms(old, beforeFairy);
        }
        Map<Integer, double[]> walked = new HashMap<>(WALKED_IN);
        WALKED_IN.clear();
        walked.forEach((r, door) -> {
            int now = layout.sameRoom(old, r);
            if (now >= 0) {
                WALKED_IN.put(now, door);
            }
        });
        for (Mob m : MOBS.values()) {
            m.room = layout.sameRoom(old, m.room);
        }
        for (Pending p : LEARNING.values()) {
            p.room = layout.sameRoom(old, p.room);
        }
        return layout;
    }

    private static void tick(Minecraft client) {
        long a = meterForTest ? meterNow() : 0;
        tickBody(client);
        if (meterForTest) {
            meteredForTest += meterNow() - a;
        }
    }

    private static void tickBody(Minecraft client) {
        clock++;
        int gen = LiveMapFeature.resetGeneration();
        if (client.level != level || gen != generation) {
            BloodRushSpots.saveIfDirty(true);
            com.killer560.hub.roomsim.SimMobLooks.saveIfDirty(true);
            if (client.level != level) {
                // adm's rule: party-chat roles last until the dungeon they were for is over.
                BloodRushRoles.newWorld(wasInDungeon);
                wasInDungeon = false;
            }
            level = client.level;
            generation = gen;
            resetWorld();
        }
        BloodRushConfig cfg = BloodRushConfig.getInstance();
        if (DungeonState.isInDungeon()) {
            wasInDungeon = true;
        }
        if (client.level == null || client.player == null || !DungeonState.isInDungeon() || DungeonState.isBossPhaseActive()) {
            if (!CACHED.isEmpty()) {
                CACHED = List.of();
            }
            status = !cfg.isEnabled() ? "off" : "not in a clear";
            return;
        }
        DungeonLayout layout = renumber(DungeonLayout.current());
        if (!cfg.isEnabled()) {
            // Helper off: Learn Spawn Spots still learns, passively (bloodrush2-1104, so his normal runs fill the table). The
            // same starred-mob scan Mob ESP runs every frame, here every other tick, in a clear only; nothing drawn.
            if (!CACHED.isEmpty()) {
                CACHED = List.of();
            }
            boolean learn = cfg.isLearnSpots() && (!com.killer560.hub.roomsim.SimState.isActive() || learnInSimForTest);
            status = learn ? "off (learning spawn spots)" : "off";
            if (learn && ++ticks % 2 == 0) {
                trackMobs(client, layout, true);
                if (ticks % 200 == 0) {
                    BloodRushSpots.saveIfDirty(false);
                }
            }
            return;
        }
        if (bloodOpened) {
            // The rush is over and nothing shows, but the clear still teaches spawn spots for later runs.
            if (!CACHED.isEmpty()) {
                CACHED = List.of();
            }
            status = "blood door open";
            if (++ticks % 2 == 0 && cfg.isLearnSpots()
                    && (!com.killer560.hub.roomsim.SimState.isActive() || learnInSimForTest)) {
                trackMobs(client, layout, true);
                if (ticks % 200 == 0) {
                    BloodRushSpots.saveIfDirty(false);
                }
            }
            return;
        }
        trackSteps(client, layout);
        watchTags();
        if (++ticks % 2 != 0) {
            return;
        }
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        trackMobs(client, layout, cfg.isLearnSpots() && (!sim || learnInSimForTest));
        CACHED = build(client, layout, cfg);
        if (ticks % 200 == 0) {
            BloodRushSpots.saveIfDirty(false);
        }
    }

    private static int tileAt(double x, double z) {
        int tx = Math.floorDiv((int) Math.floor(x) - (LiveMapFeature.START_X - 16), 32);
        int tz = Math.floorDiv((int) Math.floor(z) - (LiveMapFeature.START_Z - 16), 32);
        return tx < 0 || tz < 0 || tx > 5 || tz > 5 ? -1 : tx + tz * 6;
    }

    /**
     * adm's step rule: from one room's tile into the next tile over, belonging to another room, is through the doorway
     * between them, halfway between the two tiles' centres; a jump of more than one tile (a leap, a pearl) is no door.
     */
    private static void trackSteps(Minecraft client, DungeonLayout layout) {
        Vec3 p = client.player.position();
        int t = tileAt(p.x, p.z);
        int room = layout.roomAtWorld(p.x, p.z);
        if (room >= 0 && room != lastRoom) {
            if (RUSHED.add(room)) {
                rushedVersion++;
            }
            if (beforeFairy == null && "FAIRY".equalsIgnoreCase(type(layout, room))) {
                reachFairy();
            }
            if (room == nextRoom) {
                nextRoom = -1;
            }
            lastRoom = room;
        }
        if (t < 0 || t == myTile) {
            return;
        }
        int was = myTile;
        myTile = t;
        if (was < 0) {
            return;
        }
        int a = layout.roomOfCell((was / 6) * 2 * DungeonLayout.GRID + (was % 6) * 2);
        int b = layout.roomOfCell((t / 6) * 2 * DungeonLayout.GRID + (t % 6) * 2);
        int dx = Math.abs(was % 6 - t % 6);
        int dz = Math.abs(was / 6 - t / 6);
        if (a < 0 || b < 0 || a == b || dx + dz != 1) {
            return;
        }
        double x = LiveMapFeature.START_X + 16.0 * ((was % 6) + (t % 6)) + 0.5;
        double z = LiveMapFeature.START_Z + 16.0 * ((was / 6) + (t / 6)) + 0.5;
        WALKED_IN.put(b, new double[]{x, z});
    }

    private static void watchTags() {
        for (Mob m : MOBS.values()) {
            if (m.tag != null && m.tag.isRemoved() && m.tagGoneAt < 0) {
                m.tagGoneAt = clock;
            }
        }
    }

    private static void trackMobs(Minecraft client, DungeonLayout layout, boolean learn) {
        boolean scan = standLoaded || clock - lastScan >= SCAN_EVERY;
        if (scan) {
            standLoaded = false;
            lastScan = clock;
        }
        for (Entity e : scan ? com.killer560.hub.mobesp.MobEspFeature.starredMobs(client) : List.<Entity>of()) {
            Mob m = MOBS.get(e.getId());
            if (m != null) {
                if (m.entity == null && !m.dead) {
                    m.entity = e; // back in view: Hypixel keeps a mob's id
                    m.tag = tagOf(client, e);
                    m.tagGoneAt = -1;
                }
                continue;
            }
            Vec3 at = e.position();
            int room = layout.roomAtWorld(at.x, at.z);
            MOBS.put(e.getId(), new Mob(e.getId(), at, e.getBoundingBox(), room, e, tagOf(client, e)));
            if (learn && room >= 0 && isMobRoom(layout, room) && alone(client, at)) {
                LEARNING.put(e.getId(), new Pending(at, clock, room));
            }
        }
        if (learn) {
            confirmLearning(client, layout);
        } else {
            LEARNING.clear();
        }
        Vec3 me = client.player.position();
        for (Mob m : MOBS.values()) {
            if (m.entity == null) {
                continue; // a mob the scan finds is alive and not removed, so the test below passes it over as before
            }
            Entity e = m.entity;
            if (e.isRemoved() || !e.isAlive()) {
                double d = Math.hypot(me.x - e.getX(), me.z - e.getZ());
                boolean withTag = m.tagGoneAt >= 0 && clock - m.tagGoneAt <= 2;
                if (!e.isAlive() || d < DIES_ALONE || (d < DIES_WITH_TAG && withTag)) {
                    m.dead = true;
                }
                m.entity = null;
                m.tag = null;
            }
        }
    }

    /** Learns each waiting mob's spot once it has stood still long enough; a mob that moved or went is dropped. */
    private static void confirmLearning(Minecraft client, DungeonLayout layout) {
        java.util.Iterator<Map.Entry<Integer, Pending>> it = LEARNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Pending> en = it.next();
            Pending p = en.getValue();
            Mob m = MOBS.get(en.getKey());
            Entity e = m == null ? null : m.entity;
            if (e == null || m.dead || e.isRemoved() || !e.isAlive()) {
                it.remove();
                continue;
            }
            Vec3 now = e.position();
            if (Math.abs(now.x - p.at.x) > LEARN_STILL_DRIFT || Math.abs(now.y - p.at.y) > LEARN_STILL_DRIFT
                    || Math.abs(now.z - p.at.z) > LEARN_STILL_DRIFT) {
                if (++p.restarts > LEARN_RESTARTS || layout.roomAtWorld(now.x, now.z) != p.room || !alone(client, now)) {
                    it.remove();
                } else {
                    p.at = now;
                    p.since = clock;
                }
                continue;
            }
            if (clock - p.since < LEARN_STILL_TICKS) {
                continue;
            }
            it.remove();
            int[] cr = layout.clayRotation(p.room);
            String name = layout.name(p.room);
            if (cr != null && name != null && !"Unknown".equals(name)) {
                RouteCoords.Frame frame = new RouteCoords.Frame(name, cr[0], cr[1], cr[2]);
                BloodRushSpots.learn(name, frame, BlockPos.containing(p.at));
                // Its look too (type, gear, skin, name), for the sim's Real Mob Looks (simblood-1110).
                com.killer560.hub.roomsim.SimMobLooks.record(name, frame, e, tagOf(client, e));
            }
        }
    }

    /** The star name tag over a mob (the stand Mob ESP resolves it from), or null. */
    private static Entity tagOf(Minecraft client, Entity mob) {
        for (ArmorStand s : client.level.getEntitiesOfClass(ArmorStand.class, mob.getBoundingBox().inflate(0.6, 3.5, 0.6))) {
            if (s.hasCustomName() && s.getName().getString().contains("✯")) {
                return s;
            }
        }
        return null;
    }

    private static boolean alone(Minecraft client, Vec3 at) {
        for (Player p : client.level.players()) {
            if (p.position().distanceToSqr(at) < LEARN_ALONE * LEARN_ALONE) {
                return false;
            }
        }
        return true;
    }

    private static String type(DungeonLayout layout, int room) {
        RoomEntry e = layout.entry(room);
        return e == null || e.type == null ? "" : e.type;
    }

    static boolean isMobRoom(DungeonLayout layout, int room) {
        String t = type(layout, room).toUpperCase(Locale.ROOT);
        return t.equals("NORMAL") || t.equals("RARE") || t.equals("CHAMPION");
    }

    /**
     * The rush path, adm's way: the rooms join as a tree, and every wither door and the blood door is on the way from the
     * Entrance to Blood, so the rush's rooms are those on the way from the Entrance to each of those doors (and to every
     * room the rush has been in), each entered by its door on the Entrance side. Legit reads the doors the hotbar map shows;
     * {@code scanned} (Whole Rush Path, cheat build) the scanned dungeon's. Returns room -> its entrance-side door (x, z).
     */
    static Map<Integer, double[]> rushPath(DungeonLayout layout, boolean scanned) {
        long answers = HeldMap.answersVersion();
        if (pathCache == null || layout != pathLayout || scanned != pathScanned || answers != pathAnswers
                || rushedVersion != pathRushed) {
            pathCache = computeRushPath(layout, scanned);
            pathLayout = layout;
            pathScanned = scanned;
            pathAnswers = answers;
            pathRushed = rushedVersion;
            pathComputes++;
        }
        return pathCache;
    }

    /*
     * Rush path cache (rushcache-1104): the path was rebuilt (lists, BFS, sets) every other tick, ~28 MB of garbage a minute.
     * The path is a pure function of: the layout snapshot (rooms, which room owns each cell, types, names, door locks: a new
     * DungeonLayout object whenever any of them or the player's room changes, DungeonLayout.current), the doors and rooms
     * the hotbar map shows (HeldMap.answersVersion: the map item's version, or the sim stand-in's visited rooms), the rooms
     * the rush has been in (rushedVersion), and the scanned flag. The cache keys on exactly those, compared field by field
     * (no allocation, no scan of the 121 cells), so a door opening, a new room appearing, a room identified or the player
     * entering a room recomputes and a stale path is never returned. nextRoom, the blood door chat and settings other than
     * Whole Rush Path are NOT inputs: they only choose which rooms of the path show (shownRooms). The returned map is
     * shared: read only.
     */
    private static Map<Integer, double[]> pathCache;
    private static DungeonLayout pathLayout;
    private static boolean pathScanned;
    private static long pathAnswers;
    private static int pathRushed;
    /** Test counter: how many times the path was actually rebuilt. */
    static int pathComputes;

    static Map<Integer, double[]> computeRushPath(DungeonLayout layout, boolean scanned) {
        int n = layout.roomCount();
        List<List<int[]>> links = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            links.add(new ArrayList<>());
        }
        Set<Integer> ends = new HashSet<>(RUSHED);
        int entrance = -1;
        for (int r = 0; r < n; r++) {
            String t = type(layout, r);
            if ("ENTRANCE".equalsIgnoreCase(t) || "Entrance".equalsIgnoreCase(layout.name(r))) {
                entrance = r;
            }
            if ("BLOOD".equalsIgnoreCase(t) && (scanned || shownOnMap(layout, r))) {
                ends.add(r);
            }
        }
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (!HeldMap.isDoorCell(idx)) {
                continue;
            }
            int type = scanned ? layout.doorType(idx) : (HeldMap.available() ? HeldMap.doorType(idx) : DungeonLayout.DOOR_NONE);
            if (type == DungeonLayout.DOOR_NONE) {
                continue;
            }
            int[] s = HeldMap.sides(idx);
            if (s[0] < 0 || s[1] < 0 || s[0] >= DungeonLayout.GRID * DungeonLayout.GRID
                    || s[1] >= DungeonLayout.GRID * DungeonLayout.GRID) {
                continue;
            }
            int a = layout.roomOfCell(s[0]);
            int b = layout.roomOfCell(s[1]);
            if (a < 0 || b < 0 || a == b) {
                continue;
            }
            links.get(a).add(new int[]{b, idx});
            links.get(b).add(new int[]{a, idx});
            if (type == DungeonLayout.DOOR_WITHER || type == DungeonLayout.DOOR_BLOOD) {
                ends.add(a);
                ends.add(b);
            }
        }
        Map<Integer, double[]> out = new HashMap<>();
        if (entrance < 0) {
            return out;
        }
        int[] parent = new int[n];
        int[] via = new int[n];
        java.util.Arrays.fill(parent, -2);
        parent[entrance] = -1;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(entrance);
        while (!q.isEmpty()) {
            int r = q.poll();
            for (int[] l : links.get(r)) {
                if (parent[l[0]] == -2) {
                    parent[l[0]] = r;
                    via[l[0]] = l[1];
                    q.add(l[0]);
                }
            }
        }
        out.put(entrance, null);
        for (int end : ends) {
            int r = end;
            while (r >= 0 && r < n && parent[r] >= 0 && !out.containsKey(r)) {
                Vec3 d = DungeonLayout.doorCentre(via[r]);
                out.put(r, new double[]{d.x, d.z});
                r = parent[r];
            }
        }
        return out;
    }

    private static boolean shownOnMap(DungeonLayout layout, int room) {
        int[] tiles = layout.tiles(room);
        if (tiles == null) {
            return false;
        }
        for (int t : tiles) {
            if (HeldMap.roomShown(t)) {
                return true;
            }
        }
        return false;
    }

    /** The rooms whose boxes show now. */
    private static List<Integer> shownRooms(DungeonLayout layout, BloodRushConfig cfg, Map<Integer, double[]> path) {
        List<Integer> out = new ArrayList<>();
        int cur = layout.currentRoom();
        List<Integer> want = new ArrayList<>();
        if (cfg.isWholePath()) {
            want.addAll(path.keySet());
        }
        want.add(cur);
        if (nextRoom >= 0) {
            want.add(nextRoom);
        }
        for (int r : want) {
            if (r < 0 || out.contains(r) || !isMobRoom(layout, r)
                    || com.killer560.hub.autoclear.AutoClearTargets.isClearedOnMap(layout, r)) {
                continue;
            }
            if (cfg.isAllRooms() || path.containsKey(r)) {
                out.add(r);
            }
        }
        if (path.size() != statusPath || out.size() != statusShown || !status.startsWith("rush ")) {
            statusPath = path.size();
            statusShown = out.size();
            status = "rush " + statusPath + " room(s), showing " + statusShown;
        }
        return out;
    }

    private static List<Spot> build(Minecraft client, DungeonLayout layout, BloodRushConfig cfg) {
        List<Spot> out = new ArrayList<>();
        Vec3 me = client.player.position();
        Map<Integer, double[]> path = rushPath(layout, cfg.isWholePath());
        inRushRoom = path.containsKey(layout.currentRoom());
        Map<Integer, double[]> doors = new HashMap<>();
        for (int room : shownRooms(layout, cfg, path)) {
            String name = layout.name(room);
            List<Box> boxes = boxes(layout, room, name, cfg);
            if (boxes.isEmpty()) {
                continue;
            }
            double[] door = WALKED_IN.get(room);
            if (door == null) {
                door = path.get(room);
            }
            if (door == null) {
                door = new double[]{me.x, me.z};
            }
            doors.put(room, door);
            boolean late = late(room);
            int n = BloodRushRoles.planCount(cfg, late);
            Integer playing = BloodRushRoles.count(cfg) == 0 ? Integer.valueOf(1) : BloodRushRoles.playing(cfg, late);
            if (BloodRushRoles.youDropped(cfg, late)) {
                n = 0;
                playing = 1; // he left the rush to clear: every box is his
            }
            List<List<Box>> roles = BloodRushSplit.split(boxes, door[0], door[1], Math.max(1, n), BloodRushRoles.master());
            boolean ownDone = true;
            List<Box> mineOrder = playing != null && playing >= 1 && playing <= roles.size() ? roles.get(playing - 1)
                    : List.of();
            for (Box b : mineOrder) {
                ownDone &= b.done();
            }
            boolean nextGiven = false;
            int order = 0;
            for (Box b : mineOrder) {
                order++;
                if (b.done()) {
                    continue;
                }
                boolean next = !nextGiven;
                nextGiven = true;
                int argb = b.mobs.isEmpty() ? cfg.getLearnedColor() : cfg.getMineColor();
                out.add(spot(name, b, argb, label(Integer.toString(order), b), next, Look.MINE, order, playing));
            }
            for (int r = 1; r <= roles.size(); r++) {
                if (playing != null && r == playing) {
                    continue;
                }
                boolean help = ownDone && playing != null && !mineOrder.isEmpty();
                if (!help && !cfg.isShowOthers()) {
                    continue;
                }
                String who = BloodRushRoles.holder(r);
                for (Box b : roles.get(r - 1)) {
                    if (b.done() || mineOrder.contains(b)) {
                        continue;
                    }
                    String text = (help ? "help " : "") + "R" + r + (who != null ? " " + who : "");
                    out.add(spot(name, b, help ? 0xFFFFAA00 : cfg.getOthersColor(), label(text, b), false,
                            help ? Look.HELP : Look.THEIRS, 0, r));
                }
            }
        }
        LAST_DOORS = doors;
        return out.equals(CACHED) ? CACHED : List.copyOf(out);
    }

    private static String label(String text, Box b) {
        return b.alive > 1 ? text + " x" + b.alive : text;
    }

    private static Spot spot(String room, Box b, int argb, String label, boolean next, Look look, int order, int role) {
        return new Spot(room, b.box, argb, label, next, look, order, role, b.mobs.size(), b.alive, b.mobs.isEmpty());
    }

    /**
     * The room's boxes: learned spots grouped within {@link #LINK}, each claiming the mobs whose body overlapped it most
     * where first seen, else the nearest within {@link #CLAIM_REACH}; mobs no learned box claims make boxes of their own.
     */
    private static List<Box> boxes(DungeonLayout layout, int room, String name, BloodRushConfig cfg) {
        List<Box> learned = new ArrayList<>();
        if (cfg.isShowLearned()) {
            int[] cr = layout.clayRotation(room);
            if (cr != null && name != null) {
                for (AABB a : learnedBoxes(name, cr)) {
                    Box bx = new Box();
                    bx.box = a;
                    bx.learned = true;
                    learned.add(bx);
                }
            }
        }
        List<Mob> loose = new ArrayList<>();
        for (Mob m : MOBS.values()) {
            if (m.room != room) {
                continue;
            }
            Box claim = null;
            double most = 0;
            for (Box b : learned) {
                double o = overlap(m.body, b.box);
                if (o > most) {
                    most = o;
                    claim = b;
                }
            }
            if (claim == null) {
                double best = CLAIM_REACH * CLAIM_REACH;
                for (Box b : learned) {
                    double g = gap(m.body, b.box);
                    if (g <= best) {
                        best = g;
                        claim = b;
                    }
                }
            }
            if (claim != null) {
                claim.mobs.add(m.id);
                if (!m.dead) {
                    claim.alive++;
                }
            } else {
                loose.add(m);
            }
        }
        List<Box> out = new ArrayList<>(learned);
        List<Vec3> pts = new ArrayList<>();
        for (Mob m : loose) {
            pts.add(m.first);
        }
        for (List<Integer> g : clusterIdx(pts)) {
            Box bx = new Box();
            List<Vec3> ps = new ArrayList<>();
            for (int i : g) {
                Mob m = loose.get(i);
                ps.add(m.first);
                bx.mobs.add(m.id);
                if (!m.dead) {
                    bx.alive++;
                }
            }
            bx.box = around(ps);
            out.add(bx);
        }
        for (Box b : out) {
            b.cx = (b.box.minX + b.box.maxX) / 2;
            b.cz = (b.box.minZ + b.box.maxZ) / 2;
        }
        // A stable order (the split breaks ties by it): west to east, then north to south.
        out.sort((a, b) -> a.cx != b.cx ? Double.compare(a.cx, b.cx) : Double.compare(a.cz, b.cz));
        return out;
    }

    /** The room's learned spots grouped within {@link #LINK}, one box each; rebuilt when its frame or the table changes. */
    private static List<AABB> learnedBoxes(String name, int[] cr) {
        int version = BloodRushSpots.version();
        Learned c = LEARNED.get(name);
        if (c != null && c.x == cr[0] && c.z == cr[1] && c.rot == cr[2] && c.version == version) {
            return c.boxes;
        }
        List<Vec3> pts = new ArrayList<>();
        for (BlockPos b : BloodRushSpots.worldSpots(name, new RouteCoords.Frame(name, cr[0], cr[1], cr[2]))) {
            pts.add(new Vec3(b.getX() + 0.5, b.getY(), b.getZ() + 0.5));
        }
        List<AABB> boxes = new ArrayList<>();
        for (List<Vec3> g : cluster(pts)) {
            boxes.add(around(g));
        }
        LEARNED.put(name, new Learned(cr[0], cr[1], cr[2], version, List.copyOf(boxes)));
        return boxes;
    }

    private static List<List<Vec3>> cluster(List<Vec3> pts) {
        List<List<Vec3>> out = new ArrayList<>();
        for (List<Integer> g : clusterIdx(pts)) {
            List<Vec3> l = new ArrayList<>();
            for (int i : g) {
                l.add(pts.get(i));
            }
            out.add(l);
        }
        return out;
    }

    private static List<List<Integer>> clusterIdx(List<Vec3> pts) {
        int n = pts.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Vec3 a = pts.get(i);
                Vec3 b = pts.get(j);
                if (Math.abs(a.x - b.x) <= LINK && Math.abs(a.z - b.z) <= LINK && Math.abs(a.y - b.y) <= 3.0) {
                    parent[find(parent, i)] = find(parent, j);
                }
            }
        }
        Map<Integer, List<Integer>> by = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            by.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        return new ArrayList<>(by.values());
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static AABB around(List<Vec3> pts) {
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double z0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        double z1 = -Double.MAX_VALUE;
        for (Vec3 p : pts) {
            x0 = Math.min(x0, p.x);
            y0 = Math.min(y0, p.y);
            z0 = Math.min(z0, p.z);
            x1 = Math.max(x1, p.x);
            y1 = Math.max(y1, p.y);
            z1 = Math.max(z1, p.z);
        }
        return new AABB(x0 - 0.8, y0, z0 - 0.8, x1 + 0.8, y1 + 2.0, z1 + 0.8);
    }

    private static double overlap(AABB a, AABB b) {
        double x = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
        double y = Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY);
        double z = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
        return x > 0 && y > 0 && z > 0 ? x * y * z : 0;
    }

    private static double gap(AABB a, AABB b) {
        double x = Math.max(0, Math.max(a.minX, b.minX) - Math.min(a.maxX, b.maxX));
        double y = Math.max(0, Math.max(a.minY, b.minY) - Math.min(a.maxY, b.maxY));
        double z = Math.max(0, Math.max(a.minZ, b.minZ) - Math.min(a.maxZ, b.maxZ));
        return x * x + y * y + z * z;
    }

    private static void render(LevelRenderContext context) {
        long a = meterForTest ? meterNow() : 0;
        renderBody(context);
        if (meterForTest) {
            meteredForTest += meterNow() - a;
        }
    }

    private static void renderBody(LevelRenderContext context) {
        List<Spot> spots = CACHED;
        if (spots.isEmpty()) {
            return;
        }
        BloodRushConfig cfg = BloodRushConfig.getInstance();
        boolean walls = cfg.isThroughWalls();
        int n = spots.size();
        float base = cfg.isFilled() ? cfg.getFillOpacity() / 100f : 0f;
        if (spots != drawnFor || base != drawnBase) {
            // New arrays, never refilled ones: the frame graph runs the submitted geometry later in the frame.
            AABB[] boxes = new AABB[n];
            float[] line = new float[n * 4];
            float[] fill = new float[n * 4];
            net.minecraft.util.FormattedCharSequence[] text = new net.minecraft.util.FormattedCharSequence[n];
            for (int i = 0; i < n; i++) {
                Spot s = spots.get(i);
                boxes[i] = s.box();
                float[] c = WorldRenderUtils.argbToFloats(s.argb());
                System.arraycopy(c, 0, line, i * 4, 3);
                line[i * 4 + 3] = 1f;
                System.arraycopy(c, 0, fill, i * 4, 3);
                fill[i * 4 + 3] = base <= 0f ? 0f : s.next() ? Math.min(1f, Math.max(base * 2f, base + 0.15f)) : base;
                text[i] = net.minecraft.util.FormattedCharSequence.forward(s.label(), net.minecraft.network.chat.Style.EMPTY);
            }
            drawBoxes = boxes;
            drawLine = line;
            drawFill = fill;
            drawSeqs = text;
            drawnFor = spots;
            drawnBase = base;
        }
        if (base > 0f) {
            WorldRenderUtils.renderFilledBoxes(context, drawBoxes, drawFill, n, 1f, walls);
        }
        WorldRenderUtils.renderOutlineBoxes(context, drawBoxes, drawLine, n, cfg.getOutlineWidth(), walls);
        drawLabels(context, spots, walls);
    }

    /** What {@link #drawnFor} draws with; until rushalloc-1105 they were rebuilt every frame. */
    private static List<Spot> drawnFor = List.of();
    private static float drawnBase = -1f;
    private static AABB[] drawBoxes;
    private static float[] drawLine;
    private static float[] drawFill;
    private static net.minecraft.util.FormattedCharSequence[] drawSeqs;

    private static void drawLabels(LevelRenderContext context, List<Spot> spots, boolean walls) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        Vec3 cam = McRender.cameraPos(context);
        for (int i = 0; i < spots.size(); i++) {
            Spot s = spots.get(i);
            if (s.label().isEmpty()) {
                continue;
            }
            AABB b = s.box();
            double x = (b.minX + b.maxX) / 2 - cam.x;
            double y = b.maxY + 0.6 - cam.y;
            double z = (b.minZ + b.maxZ) / 2 - cam.z;
            double dist = Math.sqrt(x * x + y * y + z * z);
            float sc = 0.025f * (float) Math.min(6.0, Math.max(1.0, dist / 10.0));
            poseStack.pushPose();
            try {
                poseStack.translate(x, y, z);
                McRender.rotate(poseStack, McRender.cameraRotation(context));
                poseStack.scale(sc, -sc, sc);
                McRender.drawText(context, font, drawSeqs[i], -McRender.textWidth(font, s.label()) / 2f,
                        -font.lineHeight / 2f, 0xFF000000 | s.argb(), false, poseStack,
                        walls ? net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH
                                : net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                        0x40000000, 0xF000F0);
            } finally {
                poseStack.popPose();
            }
        }
    }

    /** Each box drawn now: "room|order|look|x0,y0,z0,x1,y1,z1|mobs|alive|learnedOnly|next|label|role". */
    public static List<String> spotsForTest() {
        List<String> out = new ArrayList<>();
        for (Spot s : CACHED) {
            AABB b = s.box();
            out.add(String.format(Locale.ROOT, "%s|%d|%s|%.2f,%.2f,%.2f,%.2f,%.2f,%.2f|%d|%d|%b|%b|%s|%d", s.room(), s.order(),
                    s.look(), b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, s.mobs(), s.alive(), s.learnedOnly(),
                    s.next(), s.label(), s.role()));
        }
        return out;
    }

    /** The door each shown room's split went by, "room|x,z". */
    public static List<String> doorsForTest() {
        DungeonLayout layout = DungeonLayout.current();
        List<String> out = new ArrayList<>();
        for (Map.Entry<Integer, double[]> e : LAST_DOORS.entrySet()) {
            out.add(String.format(Locale.ROOT, "%s|%.2f,%.2f", layout.name(e.getKey()), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    /** Mobs waiting to be seen standing before their spot is learned. */
    public static int learningForTest() {
        return LEARNING.size();
    }

    /** "status; blood door open: x; Mort: y; mobs tracked: n". */
    public static String statusForTest() {
        return status + "; inRush=" + inRushRoom + "; bloodOpened=" + bloodOpened + "; mort=" + mortSeen + "; mobs=" + MOBS.size() + "; next="
                + nextRoom + "; roles " + BloodRushRoles.stateForTest();
    }

    /** The rush path rooms the hotbar map gives now, by name. */
    public static List<String> mapRushRoomsForTest() {
        DungeonLayout layout = DungeonLayout.current();
        List<String> out = new ArrayList<>();
        for (int r : rushPath(layout, false).keySet()) {
            out.add(layout.name(r));
        }
        return out;
    }

    /** Rush path (room name -> entrance-side door "x,z") as the cache gives it, or recomputed from scratch when {@code fresh}. */
    public static java.util.TreeMap<String, String> pathForTest(boolean fresh) {
        DungeonLayout layout = DungeonLayout.current();
        java.util.TreeMap<String, String> out = new java.util.TreeMap<>();
        for (Map.Entry<Integer, double[]> e : (fresh ? computeRushPath(layout, false) : rushPath(layout, false)).entrySet()) {
            double[] d = e.getValue();
            out.put(layout.name(e.getKey()), d == null ? "-" : d[0] + "," + d[1]);
        }
        return out;
    }

    /** Bytes this thread allocates over {@code calls} path lookups: rebuilt every time ({@code fresh}, the pre-cache cost) or cached. */
    public static long pathAllocForTest(boolean fresh, int calls) {
        DungeonLayout layout = DungeonLayout.current();
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        rushPath(layout, false);
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < calls; i++) {
            if (fresh) {
                computeRushPath(layout, false);
            } else {
                rushPath(layout, false);
            }
        }
        return mx.getThreadAllocatedBytes(id) - before;
    }

    /** Test hook: count the bytes the helper's own tick and render allocate on the client thread (never on in play). */
    private static boolean meterForTest;
    private static long meteredForTest;

    private static com.sun.management.ThreadMXBean meterBean;

    private static long meterNow() {
        return meterBean.getCurrentThreadAllocatedBytes(); // getThreadMXBean() itself allocates, so it is asked once
    }

    /** Turns the allocation meter on or off (client thread) and returns the bytes counted since the last call, then zeroes it. */
    public static long allocMeterForTest(boolean on) {
        long b = meteredForTest;
        meteredForTest = 0;
        if (meterBean == null) {
            meterBean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        }
        meterForTest = on;
        return b;
    }

    /** How many times the rush path was rebuilt since the jar loaded. */
    public static int pathComputesForTest() {
        return pathComputes;
    }

    /** The split on bare box middles {x, z} (index order kept): per role, the box indices in kill order. */
    public static List<List<Integer>> splitForTest(double[][] centres, double doorX, double doorZ, int n, boolean master) {
        List<Box> boxes = new ArrayList<>();
        for (double[] c : centres) {
            Box b = new Box();
            b.cx = c[0];
            b.cz = c[1];
            b.box = new AABB(c[0] - 0.8, 69, c[1] - 0.8, c[0] + 0.8, 71, c[1] + 0.8);
            boxes.add(b);
        }
        List<List<Integer>> out = new ArrayList<>();
        for (List<Box> r : BloodRushSplit.split(boxes, doorX, doorZ, n, master)) {
            List<Integer> l = new ArrayList<>();
            for (Box b : r) {
                l.add(boxes.indexOf(b));
            }
            out.add(l);
        }
        return out;
    }

    /** Forgets this world's mobs, doors and the blood door, as a new world would. */
    public static void resetForTest() {
        resetWorld();
    }
}
