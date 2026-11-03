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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

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
 * want." - the reference mod adm's BR Roles boxes). On the blood rush, the starred mobs of the room you are in have to die
 * before its Wither Key drops; this marks where they are, grouped into spots, numbered in the order to kill them from the
 * door you came in by, the next one filled in, and drops each spot as its mobs die. Information only: it never moves,
 * aims or clicks.
 *
 * <p><b>When.</b> In a dungeon clear (not the boss), from the start of the run until "The BLOOD DOOR has been opened!"
 * (then every spot goes, for the rest of that world). A room's spots also go once the map shows it cleared.
 *
 * <p><b>Which rooms.</b> The room you stand in, when it is a mob room (the room database's NORMAL, RARE, CHAMPION) on the
 * rush: one the dungeon map in your hotbar shows beside a wither or blood door (every room on the way to Blood has one,
 * the room before the door giving its key). All Rooms shows them in every mob room. Cheat build, Whole Rush Path: every
 * room on the rush path from the scanned dungeon (Entrance to Blood, the Auto Clear Blood Rush Split path) at once,
 * including ones the hotbar map does not show yet.
 *
 * <p><b>Spots.</b> Every starred mob the client has (Mob ESP's detection, minibosses included) is placed where it was
 * first seen; mobs within {@link #LINK} blocks of each other are one spot. With Show Learned Spots, the room's spawn spots
 * learned in earlier runs ({@link BloodRushSpots}) are spots too, so a spot shows before its mob is in view; a learned
 * spot with no live mob on it while you are within {@link #LEARNED_SEEN} blocks is taken as done. Mobs die as adm
 * describes Hypixel's (no death packet: the mob is removed): removed within {@link #DIES_WITHIN} blocks is dead, further
 * off it is only out of view and keeps its state.
 *
 * <p><b>Order and roles.</b> Nearest first from where you came into the room, each next spot the nearest to the last. With
 * Killers above 1, that order is cut into as many runs of spots as there are killers, each holding about the same number of
 * mobs, and My Role picks yours: yours are numbered and colored, the others' gray (or hidden without Show Others' Spots).
 *
 * <p><b>Legit and cheat.</b> Legit: boxes and numbers are depth-tested (they hide behind walls, as Mob ESP's legit
 * highlight does), only in the room you are in, and the rush is read off the hotbar map. Mob positions are what the
 * client is sent; learned spots are his own earlier sightings. Cheat build only: Through Walls, and Whole Rush Path
 * (scanned-dungeon knowledge beyond the hotbar map).
 */
public final class BloodRushHelperFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-bloodrush");

    /** Mobs this close (blocks, each of x/z, and 3 in y) to another of a spot are in it. */
    static final double LINK = 3.5;
    /** A mob removed this close is dead; further off, out of view. adm's measure of Hypixel. */
    static final double DIES_WITHIN = 32.0;
    /** A learned spot with no mob on it, seen from this close, is done. */
    static final double LEARNED_SEEN = 16.0;
    /** A spawn is learned only when no player stands this close to the mob (an untouched mob has not moved). */
    static final double LEARN_ALONE = 10.0;

    private static final Pattern MORT_START = Pattern.compile("^\\[NPC\\] Mort: Here, I found this map when I first entered the dungeon\\.$");
    private static final Pattern BLOOD_DOOR_OPENED = Pattern.compile("^The BLOOD DOOR has been opened!?$");

    /** A starred mob of this world: where it was first seen and its state. */
    private static final class Mob {
        final int id;
        final Vec3 first;
        final int room;
        Entity entity;
        boolean dead;

        Mob(int id, Vec3 first, int room, Entity entity) {
            this.id = id;
            this.first = first;
            this.room = room;
            this.entity = entity;
        }
    }

    /** One spot as drawn: box, color, label, filled (the next of yours), and the facts tests read. */
    record Spot(String room, AABB box, int argb, String label, boolean next, boolean mine, int order, int mobs,
                int alive, boolean learnedOnly) {
    }

    private static final Map<Integer, Mob> MOBS = new HashMap<>();
    private static final Map<Integer, Vec3> ENTRY = new HashMap<>();
    private static volatile List<Spot> CACHED = List.of();
    private static Object level;
    private static int generation = Integer.MIN_VALUE;
    private static boolean bloodOpened;
    private static boolean mortSeen;
    private static int lastRoom = -1;
    private static Vec3 lastPos;
    private static int ticks;
    private static String status = "idle";

    private BloodRushHelperFeature() {
    }

    public static void register() {
        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            WorldRenderUtils.initThroughWalls();
        }
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BloodRushHelper.tick", BloodRushHelperFeature::tick));
        com.killer560.hub.util.ChatObserver.subscribe(m -> onChat(com.killer560.hub.util.ChatObserver.strip(m)));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(BloodRushHelperFeature::render);
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
        if (BLOOD_DOOR_OPENED.matcher(msg).matches()) {
            if (!bloodOpened) {
                LOGGER.info("[BloodRush] the blood door is open: the rush is over, spots cleared");
            }
            bloodOpened = true;
            CACHED = List.of();
        } else if (MORT_START.matcher(msg).matches()) {
            mortSeen = true;
            bloodOpened = false;
        }
    }

    private static void resetWorld() {
        MOBS.clear();
        ENTRY.clear();
        CACHED = List.of();
        bloodOpened = false;
        mortSeen = false;
        lastRoom = -1;
        lastPos = null;
    }

    private static void tick(Minecraft client) {
        int gen = LiveMapFeature.resetGeneration();
        if (client.level != level || gen != generation) {
            BloodRushSpots.saveIfDirty(true);
            level = client.level;
            generation = gen;
            resetWorld();
        }
        BloodRushConfig cfg = BloodRushConfig.getInstance();
        if (!cfg.isEnabled() || client.level == null || client.player == null || !DungeonState.isInDungeon()
                || DungeonState.isBossPhaseActive() || bloodOpened) {
            if (!CACHED.isEmpty()) {
                CACHED = List.of();
            }
            status = !cfg.isEnabled() ? "off" : bloodOpened ? "blood door open" : "not in a clear";
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        trackEntry(client, layout);
        if (++ticks % 2 != 0) {
            return;
        }
        boolean sim = com.killer560.hub.roomsim.SimState.isActive();
        trackMobs(client, layout, cfg.isLearnSpots() && !sim);
        CACHED = build(client, layout, cfg);
        if (ticks % 200 == 0) {
            BloodRushSpots.saveIfDirty(false);
        }
    }

    /** The point he came into each room at: where he stood the tick before he crossed into it. */
    private static void trackEntry(Minecraft client, DungeonLayout layout) {
        Vec3 pos = client.player.position();
        int room = layout.roomAtWorld(pos.x, pos.z);
        if (room != lastRoom) {
            if (room >= 0) {
                ENTRY.put(room, lastPos != null && lastRoom >= 0 && lastPos.distanceTo(pos) < 4.0 ? lastPos : pos);
            }
            lastRoom = room;
        }
        lastPos = pos;
    }

    private static void trackMobs(Minecraft client, DungeonLayout layout, boolean learn) {
        Set<Integer> live = new HashSet<>();
        for (Entity e : com.killer560.hub.mobesp.MobEspFeature.starredMobs(client)) {
            live.add(e.getId());
            Mob m = MOBS.get(e.getId());
            if (m != null) {
                if (m.entity == null && !m.dead) {
                    m.entity = e; // back in view: Hypixel keeps a mob's id
                }
                continue;
            }
            Vec3 at = e.position();
            int room = layout.roomAtWorld(at.x, at.z);
            MOBS.put(e.getId(), new Mob(e.getId(), at, room, e));
            if (learn && room >= 0 && alone(client, at)) {
                int[] cr = layout.clayRotation(room);
                String name = layout.name(room);
                if (cr != null && name != null && !"Unknown".equals(name)) {
                    BloodRushSpots.learn(name, new RouteCoords.Frame(name, cr[0], cr[1], cr[2]), BlockPos.containing(at));
                }
            }
        }
        Vec3 me = client.player.position();
        for (Mob m : MOBS.values()) {
            if (m.entity == null || live.contains(m.id)) {
                continue;
            }
            Entity e = m.entity;
            if (e.isRemoved() || !e.isAlive()) {
                double d = Math.hypot(me.x - e.getX(), me.z - e.getZ());
                if (d < DIES_WITHIN || !e.isAlive()) {
                    m.dead = true;
                }
                m.entity = null;
            }
        }
    }

    private static boolean alone(Minecraft client, Vec3 at) {
        for (Player p : client.level.players()) {
            if (p.position().distanceToSqr(at) < LEARN_ALONE * LEARN_ALONE) {
                return false;
            }
        }
        return true;
    }

    static boolean isMobRoom(DungeonLayout layout, int room) {
        RoomEntry entry = layout.entry(room);
        if (entry == null || entry.type == null) {
            return false;
        }
        String t = entry.type.toUpperCase(Locale.ROOT);
        return t.equals("NORMAL") || t.equals("RARE") || t.equals("CHAMPION");
    }

    /** The rooms on the rush as the hotbar map shows it: each opened room beside a wither or blood door it shows. */
    static Set<Integer> mapRushRooms(DungeonLayout layout) {
        Set<Integer> out = new LinkedHashSet<>();
        if (!HeldMap.available()) {
            return out;
        }
        for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
            if (!HeldMap.isDoorCell(idx)) {
                continue;
            }
            int type = HeldMap.doorType(idx);
            if (type != DungeonLayout.DOOR_WITHER && type != DungeonLayout.DOOR_BLOOD) {
                continue;
            }
            for (int tile : HeldMap.sides(idx)) {
                if (tile >= 0 && tile < DungeonLayout.GRID * DungeonLayout.GRID && HeldMap.roomOpened(tile)) {
                    int r = layout.roomOfCell(tile);
                    if (r >= 0) {
                        out.add(r);
                    }
                }
            }
        }
        return out;
    }

    /** The rooms whose spots show now. */
    private static List<Integer> shownRooms(DungeonLayout layout, BloodRushConfig cfg) {
        List<Integer> out = new ArrayList<>();
        int cur = layout.currentRoom();
        Set<Integer> rush = mapRushRooms(layout);
        if (cfg.isWholePath()) {
            List<Integer> path = com.killer560.hub.autoclear.AutoClearFeature.bloodRushRooms(layout);
            if (path != null) {
                rush.addAll(path);
                for (int r : path) {
                    if (isMobRoom(layout, r) && !com.killer560.hub.autoclear.AutoClearTargets.isClearedOnMap(layout, r)) {
                        out.add(r);
                    }
                }
            }
        }
        if (cur >= 0 && !out.contains(cur) && isMobRoom(layout, cur)
                && !com.killer560.hub.autoclear.AutoClearTargets.isClearedOnMap(layout, cur)
                && (cfg.isAllRooms() || rush.contains(cur))) {
            out.add(cur);
        }
        status = "rush " + rush.size() + " room(s), showing " + out.size();
        return out;
    }

    /** A cluster of mob sightings and learned spots. */
    private static final class Group {
        final List<Vec3> points = new ArrayList<>();
        int mobs;
        int alive;
        int learned;
        boolean learnedSeenEmpty;
        Vec3 centre;
    }

    private static List<Spot> build(Minecraft client, DungeonLayout layout, BloodRushConfig cfg) {
        List<Spot> out = new ArrayList<>();
        Vec3 me = client.player.position();
        for (int room : shownRooms(layout, cfg)) {
            String name = layout.name(room);
            List<Vec3> pts = new ArrayList<>();
            List<Integer> kind = new ArrayList<>(); // mob id, or -1 for a learned spot
            for (Mob m : MOBS.values()) {
                if (m.room == room) {
                    pts.add(m.first);
                    kind.add(m.id);
                }
            }
            if (cfg.isShowLearned()) {
                int[] cr = layout.clayRotation(room);
                if (cr != null && name != null) {
                    for (BlockPos b : BloodRushSpots.worldSpots(name, new RouteCoords.Frame(name, cr[0], cr[1], cr[2]))) {
                        pts.add(new Vec3(b.getX() + 0.5, b.getY(), b.getZ() + 0.5));
                        kind.add(-1);
                    }
                }
            }
            List<Group> groups = cluster(pts, kind, me);
            // Drop the done ones from the order? No: the order is made over every spot, so numbers stay put as spots die.
            Vec3 from = ENTRY.getOrDefault(room, me);
            List<Group> tour = tour(groups, from);
            int killers = cfg.getKillers();
            int[] owner = split(tour, killers);
            int myRole = cfg.getRole();
            int n = 0;
            boolean nextGiven = false;
            for (int i = 0; i < tour.size(); i++) {
                Group g = tour.get(i);
                boolean mine = owner[i] == myRole;
                if (mine) {
                    n++;
                }
                boolean done = g.mobs > 0 ? g.alive == 0 : g.learnedSeenEmpty;
                if (done || (!mine && !cfg.isShowOthers())) {
                    continue;
                }
                boolean learnedOnly = g.mobs == 0;
                boolean next = mine && !nextGiven;
                if (next) {
                    nextGiven = true;
                }
                int argb = !mine ? cfg.getOthersColor() : learnedOnly ? cfg.getLearnedColor() : cfg.getMineColor();
                String label = mine ? Integer.toString(n) : (killers > 1 ? "R" + owner[i] : "");
                if (g.alive > 1) {
                    label += (label.isEmpty() ? "" : " ") + "x" + g.alive;
                }
                out.add(new Spot(name, box(g.points), argb, label, next, mine, mine ? n : 0, g.mobs, g.alive, learnedOnly));
            }
        }
        return List.copyOf(out);
    }

    private static List<Group> cluster(List<Vec3> pts, List<Integer> kind, Vec3 me) {
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
        Map<Integer, Group> by = new HashMap<>();
        List<Group> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Group g = by.computeIfAbsent(find(parent, i), k -> {
                Group ng = new Group();
                out.add(ng);
                return ng;
            });
            g.points.add(pts.get(i));
            int k = kind.get(i);
            if (k >= 0) {
                g.mobs++;
                if (!MOBS.get(k).dead) {
                    g.alive++;
                }
            } else {
                g.learned++;
            }
        }
        for (Group g : out) {
            double x = 0;
            double y = 0;
            double z = 0;
            for (Vec3 p : g.points) {
                x += p.x;
                y += p.y;
                z += p.z;
            }
            g.centre = new Vec3(x / g.points.size(), y / g.points.size(), z / g.points.size());
            g.learnedSeenEmpty = g.mobs == 0 && g.centre.distanceTo(me) < LEARNED_SEEN;
        }
        return out;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    /** Nearest first from {@code from}, then each next the nearest to the last (ties by position, so it never flickers). */
    static List<Group> tour(List<Group> groups, Vec3 from) {
        List<Group> left = new ArrayList<>(groups);
        left.sort((a, b) -> a.centre.x != b.centre.x ? Double.compare(a.centre.x, b.centre.x)
                : Double.compare(a.centre.z, b.centre.z));
        List<Group> out = new ArrayList<>();
        Vec3 at = from;
        while (!left.isEmpty()) {
            Group best = null;
            double bd = Double.MAX_VALUE;
            for (Group g : left) {
                double d = g.centre.distanceToSqr(at);
                if (d < bd - 1e-6) {
                    bd = d;
                    best = g;
                }
            }
            left.remove(best);
            out.add(best);
            at = best.centre;
        }
        return out;
    }

    /** Role of each spot in the tour: runs of spots, one a killer, each about the same number of mobs. */
    static int[] split(List<Group> tour, int killers) {
        int[] owner = new int[tour.size()];
        int total = 0;
        for (Group g : tour) {
            total += Math.max(1, g.mobs + (g.mobs == 0 ? g.learned : 0));
        }
        int acc = 0;
        for (int i = 0; i < tour.size(); i++) {
            Group g = tour.get(i);
            int w = Math.max(1, g.mobs + (g.mobs == 0 ? g.learned : 0));
            // The spot goes to the killer whose share its middle falls in.
            double mid = acc + w / 2.0;
            owner[i] = Math.min(killers, 1 + (int) Math.floor(mid * killers / Math.max(1, total)));
            acc += w;
        }
        return owner;
    }

    private static AABB box(List<Vec3> pts) {
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

    // ---- drawing -------------------------------------------------------------------------------------------------------

    private static void render(LevelRenderContext context) {
        List<Spot> spots = CACHED;
        if (spots.isEmpty()) {
            return;
        }
        BloodRushConfig cfg = BloodRushConfig.getInstance();
        boolean walls = cfg.isThroughWalls();
        int n = spots.size();
        AABB[] boxes = new AABB[n];
        float[] line = new float[n * 4];
        float[] fill = new float[n * 4];
        float base = cfg.getFillOpacity() / 100f;
        for (int i = 0; i < n; i++) {
            Spot s = spots.get(i);
            boxes[i] = s.box();
            float[] c = WorldRenderUtils.argbToFloats(s.argb());
            System.arraycopy(c, 0, line, i * 4, 3);
            line[i * 4 + 3] = 1f;
            System.arraycopy(c, 0, fill, i * 4, 3);
            fill[i * 4 + 3] = s.next() ? Math.min(1f, Math.max(base * 3f, base + 0.15f)) : base;
        }
        WorldRenderUtils.renderFilledBoxes(context, boxes, fill, n, 1f, walls);
        WorldRenderUtils.renderOutlineBoxes(context, boxes.clone(), line, n, 2f, walls);
        drawLabels(context, spots, walls);
    }

    private static void drawLabels(LevelRenderContext context, List<Spot> spots, boolean walls) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        Vec3 cam = McRender.cameraPos(context);
        for (Spot s : spots) {
            if (s.label().isEmpty()) {
                continue;
            }
            AABB b = s.box();
            Vec3 at = new Vec3((b.minX + b.maxX) / 2, b.maxY + 0.6, (b.minZ + b.maxZ) / 2);
            double dist = Math.sqrt(cam.distanceToSqr(at));
            float sc = 0.025f * (float) Math.min(6.0, Math.max(1.0, dist / 10.0));
            poseStack.pushPose();
            try {
                poseStack.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
                McRender.rotate(poseStack, McRender.cameraRotation(context));
                poseStack.scale(sc, -sc, sc);
                McRender.drawText(context, font, s.label(), -McRender.textWidth(font, s.label()) / 2f,
                        -font.lineHeight / 2f, 0xFF000000 | s.argb(), false, poseStack,
                        walls ? net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH
                                : net.minecraft.client.gui.Font.DisplayMode.NORMAL,
                        0x40000000, 0xF000F0);
            } finally {
                poseStack.popPose();
            }
        }
    }

    // ---- test hooks ----------------------------------------------------------------------------------------------------

    /** Each spot drawn now: "room|order|mine|x0,y0,z0,x1,y1,z1|mobs|alive|learnedOnly|next|label" (the drawn box). */
    public static List<String> spotsForTest() {
        List<String> out = new ArrayList<>();
        for (Spot s : CACHED) {
            AABB b = s.box();
            out.add(String.format(Locale.ROOT, "%s|%d|%b|%.2f,%.2f,%.2f,%.2f,%.2f,%.2f|%d|%d|%b|%b|%s", s.room(), s.order(),
                    s.mine(), b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, s.mobs(), s.alive(), s.learnedOnly(), s.next(),
                    s.label()));
        }
        return out;
    }

    /** "status; blood door open: x; Mort: y; mobs tracked: n". */
    public static String statusForTest() {
        return status + "; bloodOpened=" + bloodOpened + "; mort=" + mortSeen + "; mobs=" + MOBS.size();
    }

    /** The rush rooms the hotbar map shows now, by name. */
    public static List<String> mapRushRoomsForTest() {
        DungeonLayout layout = DungeonLayout.current();
        List<String> out = new ArrayList<>();
        for (int r : mapRushRooms(layout)) {
            out.add(layout.name(r));
        }
        return out;
    }

    /** Forgets this world's mobs and the blood door, as a new world would. */
    public static void resetForTest() {
        resetWorld();
    }
}
