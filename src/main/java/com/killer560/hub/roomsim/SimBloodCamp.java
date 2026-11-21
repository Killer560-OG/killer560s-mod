package com.killer560.hub.roomsim;

import com.killer560.hub.bloodcamp.BloodCampFeature;
import com.killer560.hub.bloodcamp.BloodCampMoveTimer;
import com.killer560.hub.bloodcamp.BloodMobNames;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.util.ModLog;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Watcher's blood camp in the sim's Blood room (simblood-1110, killer560 2026-11-10: "fully recreate the blood camp ...
 * as an option"). Sim Settings "Watcher Blood Camp", default OFF; OFF keeps the five-second stand-in ({@code BossPortal}).
 * <p>
 * Server thread, every server tick. The first player into Blood starts it: the Watcher greets the room, skulls sit in the
 * wall display cases, each trip flies a skull in a straight line 16.1 blocks (first wave) or 11.9 (after "Let's see how
 * you can handle this.") - the same path {@link BloodCampFeature} predicts - and it lands as a one-HP blood mob with a
 * "Name 1❤" tag, unhittable until the floor's move time ({@link BloodCampMoveTimer#fastWatcherMoveTicks}). After the last
 * kill he says "You have proven yourself. You may pass." and BossPortal opens the portal on that line. Everyone on the
 * server sees the same entities and lines (co-op). Numbers marked "estimated" are in docs/SIM.md; real camps log a
 * "[BloodCamp] camp census" line to refine them.
 */
public final class SimBloodCamp {

    private static final Logger LOGGER = ModLog.get("killer560smod-sim");

    /*
     * Measured (sim-watcher-real-1121) over 136 real camps in his logger recordings 11-16..11-21 (134 F7, 1 F4, ticks after
     * the greeting line): the first wave's trips leave at a median 121 / 161 / 201 / 250, "Let's see how you can handle
     * this." at 307, the first later trip 156 after it and later trips 50 apart (median, players killing at once); a summon
     * line goes out on its trip's own tick (median 0), a kill line 14 after the kill, the pass line 10 after the last.
     */
    private static final int[] FIRST_TRIPS = {121, 161, 201, 250};
    /** Which first-wave trips carry a summon line (real camps: about two of the four, the later ones). */
    private static final boolean[] FIRST_LINE = {false, false, true, true};
    private static final int FIRST_FLIGHT = 77;
    private static final int MOVE_LINE = 307;
    private static final int LATER_START = 156;
    private static final int LATER_GAP = 50;
    private static final int LATER_FLIGHT = 39;
    private static final int PASS_DELAY = 10;
    private static final int KILL_LINE_DELAY = 14;
    /** Estimated: kill lines at most this often (not every kill gets one). */
    private static final int KILL_LINE_GAP = 40;
    /*
     * The Watcher's own flight (same camps, his position once a second): he greets from the room's center 4.5 up, flies
     * to the case of each mob he picks and is there when its skull leaves (median 2 blocks from it, 1 block in front, 2.25
     * above its skull), waits at the center 5.5 up from the move line until the first later trip, and flies in straight
     * lines at about 0.6 blocks a tick. HOVER (how long he stays at a case after its skull left) is estimated.
     */
    private static final double SPEED = 0.6;
    private static final int HOVER = 20;

    /**
     * Greeting per floor (index 0 = Entrance). F4 and F7 are measured ("Ah, we meet again. As I foresaw..." in every real
     * camp on both); the other floors keep the wiki's older lines (estimated).
     */
    private static final String[] GREETINGS = {
            "Congratulations, you made it through the Entrance.", "Ah, you've finally arrived.", "Ah, we meet again...",
            "So you made it this far... interesting.", "Ah, we meet again. As I foresaw...",
            "I'm starting to get tired of seeing you around here...", "Oh.. hello?", "Ah, we meet again. As I foresaw..."};
    private static final String[] SUMMONS = {"This guy looks like a fighter.", "Go and live again!", "Not bad.", "You'll do.",
            "Go, fight!", "Hmmm... this one!"};
    private static final String[] KILLS = {"I'm impressed.", "That one was weak anyway.", "Aw, I liked that one.",
            "Very nice.", "Not bad."};

    private static final class Trip {
        final ArmorStand skull;
        final String skin;
        final String name;
        int start;
        final int flight;
        final double distance;
        Vec3 from;
        Vec3 to;
        Entity mob;
        Entity tag;
        boolean killed;

        Trip(ArmorStand skull, String skin, String name, int start, int flight, double distance) {
            this.skull = skull;
            this.skin = skin;
            this.name = name;
            this.start = start;
            this.flight = flight;
            this.distance = distance;
        }
    }

    /** One straight flight of the Watcher: from, to, the tick he leaves and the tick he arrives, his yaw on the way. */
    private record Leg(Vec3 from, Vec3 to, int depart, int arrive, float yaw) {
    }

    private static final List<Leg> LEGS = new ArrayList<>();
    private static int legIndex;
    private static int killLineAt = -1;
    private static String mapCode = "";
    /** Ticks since the greeting, or -1 before the camp started. */
    private static int tick = -1;
    private static int moveTicks;
    private static int killable;
    private static int lastKillLine;
    private static int passAt = -1;
    private static boolean done;
    private static Vec3 center;
    private static Entity watcher;
    private static Random rng;
    private static final List<Trip> TRIPS = new ArrayList<>();
    /** The room's chunks, kept loaded while the camp runs: an entity chunk that unloads comes back as new entities with
     *  new ids, so the trips' skulls read removed and the camp stalled (ship3 gate, 1 or 4 of 16 sent). */
    private static int forcedX = Integer.MIN_VALUE, forcedZ;

    private SimBloodCamp() {
    }

    static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                serverTick(server);
            } catch (RuntimeException e) {
                LOGGER.error("[Sim] blood camp tick threw", e);
            }
        });
    }

    /** Testkit: {tick, trips sent, mobs landed, mobs killed, total, done ? 1 : 0}; tick -1 before it started. */
    public static int[] state() {
        int sent = 0, landed = 0, killed = 0;
        for (Trip t : TRIPS) {
            if (t.to != null) sent++;
            if (t.mob != null) landed++;
            if (t.killed) killed++;
        }
        return new int[]{tick, sent, landed, killed, TRIPS.size(), done ? 1 : 0};
    }

    /** Testkit: each trip's {fromX, fromY, fromZ, toX, toY, toZ} once it has left its case. */
    public static List<double[]> paths() {
        List<double[]> out = new ArrayList<>();
        for (Trip t : TRIPS) {
            if (t.to != null) out.add(new double[]{t.from.x, t.from.y, t.from.z, t.to.x, t.to.y, t.to.z});
        }
        return out;
    }

    /** Testkit: the landed mobs still alive (to hit them), as entity ids on the server. */
    public static List<Integer> liveMobIds() {
        List<Integer> out = new ArrayList<>();
        for (Trip t : TRIPS) {
            if (t.mob != null && !t.killed) out.add(t.mob.getId());
        }
        return out;
    }

    /**
     * The center block of the room cell holding (x, z), at the sim's room-center height (y 70 on Hypixel's scale), or
     * null when the grid is not known. BossPortal puts its portal there too.
     */
    public static BlockPos roomCenterAt(int x, int z) {
        BlockPos origin = DungeonLayout.cellCenter(0);
        int step = DungeonLayout.cellCenter(1).getX() - origin.getX();
        if (step == 0) return null;
        int gx = 2 * Math.floorDiv(x - origin.getX() + step, 2 * step);
        int gz = 2 * Math.floorDiv(z - origin.getZ() + step, 2 * step);
        return DungeonLayout.cellCenter(gx, gz);
    }

    private static void serverTick(MinecraftServer server) {
        Minecraft client = Minecraft.getInstance();
        if (client.getSingleplayerServer() != server || !SimState.isActive()) return;
        String code = SimState.mapCode();
        if (code == null) code = "";
        if (!code.equals(mapCode)) {
            reset(server.overworld());
            mapCode = code;
        }
        boolean on = SimMenuPrefs.cached().bloodCamp && SimState.canAct(client) && SimState.isGeneratedFloor();
        if (!on) {
            if (tick >= 0) {
                LOGGER.info("[Sim] blood camp switched off mid-camp: cleared");
                reset(server.overworld());
            }
            return;
        }
        ServerLevel level = server.overworld();
        if (tick < 0) {
            if (done) return;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!SimMiniboss.isPlaced(p) && "Blood".equals(SimState.roomNameAt(p.getBlockX(), p.getBlockZ()))) {
                    start(server, level, p);
                    break;
                }
            }
            return;
        }
        tick++;
        if (tick == MOVE_LINE) say(server, "Let's see how you can handle this.");
        if (tick == killable) {
            for (Trip t : TRIPS) {
                if (t.mob != null) com.killer560.hub.compat.McEntities.setInvulnerable(t.mob, false);
            }
        }
        moveWatcher();
        int summon = 0;
        boolean allDown = !TRIPS.isEmpty();
        for (int i = 0; i < TRIPS.size(); i++) {
            Trip t = TRIPS.get(i);
            boolean speaks = i >= FIRST_TRIPS.length || FIRST_LINE[i];
            if (speaks) summon++;
            if (tick == t.start && speaks) {
                say(server, i == TRIPS.size() - 1 ? "That will be enough for now." : SUMMONS[(summon - 1) % SUMMONS.length]);
            }
            fly(level, t);
            if (t.mob != null && !t.killed && (t.mob.isRemoved() || !t.mob.isAlive())) {
                t.killed = true;
                if (t.tag != null) t.tag.discard();
                if (killLineAt < 0 && tick - lastKillLine >= KILL_LINE_GAP) killLineAt = tick + KILL_LINE_DELAY;
            }
            allDown &= t.killed;
        }
        if (allDown && passAt < 0) {
            passAt = tick + PASS_DELAY;
            killLineAt = -1; // the last kill gets the pass line instead (real camps: pass 10 ticks after it, no kill line)
        }
        if (tick == killLineAt) {
            killLineAt = -1;
            lastKillLine = tick;
            say(server, KILLS[rng.nextInt(KILLS.length)]);
        }
        if (tick == passAt) {
            say(server, "You have proven yourself. You may pass.");
            LOGGER.info("[Sim] blood camp cleared {} ticks after the greeting ({} mobs)", tick, TRIPS.size());
            done = true;
            tick = -1;
            forceChunks(server.overworld(), 0, 0, false);
        }
    }

    private static void start(MinecraftServer server, ServerLevel level, ServerPlayer p) {
        BlockPos c = roomCenterAt(p.getBlockX(), p.getBlockZ());
        if (c == null) return;
        rng = new Random(mapCode.hashCode());
        center = new Vec3(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
        String floor = SimState.floorLabel();
        char last = floor.isEmpty() ? 'E' : floor.charAt(floor.length() - 1);
        int n = last >= '1' && last <= '7' ? last - '0' : 0;
        moveTicks = BloodCampMoveTimer.fastWatcherMoveTicks(floor);
        killable = moveTicks;
        List<double[]> cases = cases(level, c);
        java.util.Collections.shuffle(cases, rng);
        List<String> skins = BloodCampFeature.bloodMobSkins();
        List<String> names = BloodMobNames.names().subList(0, BloodMobNames.names().indexOf("Fels"));
        int total = Math.min(cases.size(), 9 + n);
        for (int i = 0; i < total; i++) {
            double[] at = cases.get(i);
            String skin = skins.get(rng.nextInt(skins.size()));
            boolean first = i < FIRST_TRIPS.length;
            int startAt = first ? FIRST_TRIPS[i] : MOVE_LINE + LATER_START + (i - FIRST_TRIPS.length) * LATER_GAP;
            TRIPS.add(new Trip(SimMobs.spawnBloodSkull(level, at[0], at[1], at[2], skin), skin,
                    names.get(rng.nextInt(names.size())), startAt, first ? FIRST_FLIGHT : LATER_FLIGHT, first ? 16.1 : 11.9));
        }
        plan();
        watcher = SimMobs.spawnWatcher(level, center.x, center.y + 4.5, center.z, BloodCampFeature.watcherSkin());
        forceChunks(level, c.getX() >> 4, c.getZ() >> 4, true);
        tick = 0;
        lastKillLine = -KILL_LINE_GAP;
        killLineAt = -1;
        say(server, GREETINGS[n]);
        LOGGER.info("[Sim] blood camp started on {} by {}: {} mobs, killable {} ticks after the greeting, center {}", floor,
                p.getGameProfile().name(), total, killable, c.toShortString());
    }

    /**
     * The Watcher's flights: to each trip's case (1 block in front of it, 2.25 above the skull) so he is there on its start
     * tick, back to the center after the first wave, waiting HOVER ticks at a case before leaving. A trip whose case he
     * cannot reach in time at SPEED leaves when he arrives.
     */
    private static void plan() {
        LEGS.clear();
        legIndex = 0;
        Vec3 at = new Vec3(center.x, center.y + 4.5, center.z);
        int free = 0;
        for (int i = 0; i < TRIPS.size(); i++) {
            Trip t = TRIPS.get(i);
            if (i == FIRST_TRIPS.length) {
                Vec3 mid = new Vec3(center.x, center.y + 5.5, center.z);
                int depart = Math.max(free, MOVE_LINE - travel(at, mid));
                free = addLeg(at, mid, depart);
                at = mid;
            }
            Vec3 c = t.skull.position();
            boolean xWall = Math.abs(c.x - center.x) > Math.abs(c.z - center.z);
            Vec3 hover = new Vec3(c.x + (xWall ? Math.signum(center.x - c.x) : 0), c.y + 2.25,
                    c.z + (xWall ? 0 : Math.signum(center.z - c.z)));
            int arrive = addLeg(at, hover, Math.max(free, t.start - travel(at, hover)));
            t.start = Math.max(t.start, arrive);
            if (i + 1 < TRIPS.size() && TRIPS.get(i + 1).start < t.start) TRIPS.get(i + 1).start = t.start + 1;
            at = hover;
            free = t.start + HOVER;
        }
    }

    private static int travel(Vec3 a, Vec3 b) {
        return (int) Math.ceil(a.distanceTo(b) / SPEED);
    }

    /** Adds a flight leaving on {@code depart}; returns its arrival tick. */
    private static int addLeg(Vec3 from, Vec3 to, int depart) {
        int arrive = depart + travel(from, to);
        LEGS.add(new Leg(from, to, depart, arrive, (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z))));
        return arrive;
    }

    /** Moves the Watcher along his current flight (nothing while he waits). */
    private static void moveWatcher() {
        if (watcher == null || watcher.isRemoved()) return;
        while (legIndex < LEGS.size()) {
            Leg l = LEGS.get(legIndex);
            if (tick < l.depart()) return;
            double f = l.arrive() <= l.depart() ? 1 : Math.min(1, (tick - l.depart()) / (double) (l.arrive() - l.depart()));
            watcher.setPos(l.from().x + (l.to().x - l.from().x) * f, l.from().y + (l.to().y - l.from().y) * f,
                    l.from().z + (l.to().z - l.from().z) * f);
            watcher.setYRot(l.yaw());
            watcher.setYHeadRot(l.yaw());
            if (f < 1) return;
            legIndex++;
        }
    }

    /**
     * The display cases (his camp logs: x and z on a block's center 12 out from the room's center, y 71.75 / 75.75 /
     * 79.75): one pair of walls holds three columns (-4, 0, 4 along it), the other two (+-9). Which pair is which turns
     * with the room, so the layout whose spots stand on a cauldron (the capture's cases) wins.
     */
    private static List<double[]> cases(ServerLevel level, BlockPos c) {
        List<double[]> best = null;
        int bestScore = -1;
        for (boolean swap : new boolean[]{false, true}) {
            List<double[]> out = new ArrayList<>();
            int score = 0;
            for (int s = -1; s <= 1; s += 2) {
                for (int along : new int[]{-4, 0, 4}) addColumn(out, c, swap ? along : s * 12, swap ? s * 12 : along);
                for (int along : new int[]{-9, 9}) addColumn(out, c, swap ? s * 12 : along, swap ? along : s * 12);
            }
            for (double[] p : out) {
                if (level.getBlockState(BlockPos.containing(p[0], p[1], p[2])).is(Blocks.CAULDRON)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = out;
            }
        }
        return best;
    }

    private static void addColumn(List<double[]> out, BlockPos c, int dx, int dz) {
        for (double dy : new double[]{1.75, 5.75, 9.75}) {
            out.add(new double[]{c.getX() + dx + 0.5, c.getY() + dy, c.getZ() + dz + 0.5});
        }
    }

    /** A trip's flight: leaves the case on its start tick in a straight line, lands as its mob on the last. */
    private static void fly(ServerLevel level, Trip t) {
        if (t.mob != null || tick < t.start || t.skull.isRemoved()) return;
        if (t.to == null) {
            t.from = t.skull.position();
            t.to = landing(level, t.from, t.distance);
        }
        int step = tick - t.start;
        if (step < t.flight) {
            Vec3 at = t.from.lerp(t.to, step / (double) t.flight);
            t.skull.setPos(at.x, at.y, at.z);
            return;
        }
        t.skull.discard();
        float yaw = (float) Math.toDegrees(Math.atan2(-(center.x - t.to.x), center.z - t.to.z));
        Entity[] made = SimMobs.spawnBloodMob(level, t.to.x, t.to.y, t.to.z, t.skin, t.name, yaw);
        t.mob = made[0];
        t.tag = made[1];
        com.killer560.hub.compat.McEntities.setInvulnerable(t.mob, tick < killable);
    }

    /**
     * Where a trip ends: {@code distance} from its case, toward a spot within 4 blocks of the room's center, on the
     * lowest open height from the floor up (so the whole line is the one the helper draws: start + direction x distance).
     */
    private static Vec3 landing(ServerLevel level, Vec3 from, double distance) {
        double tx = center.x + rng.nextDouble() * 8 - 4, tz = center.z + rng.nextDouble() * 8 - 4;
        double hx = tx - from.x, hz = tz - from.z, hl = Math.sqrt(hx * hx + hz * hz);
        hx /= hl;
        hz /= hl;
        Vec3 to = null;
        for (double y = center.y - 1; y <= center.y + 4; y += 1) {
            double v = Math.min(distance, Math.max(0, from.y - y));
            double h = Math.sqrt(distance * distance - v * v);
            to = new Vec3(from.x + hx * h, from.y - v, from.z + hz * h);
            BlockPos feet = BlockPos.containing(to);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return to;
            }
        }
        return to;
    }

    private static void say(MinecraftServer server, String line) {
        SimCoop.broadcast(server, Component.literal("[BOSS] The Watcher").withStyle(ChatFormatting.RED)
                .append(Component.literal(": " + line).withStyle(ChatFormatting.WHITE)));
    }

    /** Forces the 3 x 3 chunks around (cx, cz) on, or releases the ones it forced. */
    private static void forceChunks(ServerLevel level, int cx, int cz, boolean on) {
        if (!on && forcedX == Integer.MIN_VALUE) return;
        if (!on) {
            cx = forcedX;
            cz = forcedZ;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, on);
        }
        forcedX = on ? cx : Integer.MIN_VALUE;
        forcedZ = cz;
    }

    private static void reset(ServerLevel level) {
        for (Trip t : TRIPS) {
            if (!t.skull.isRemoved()) t.skull.discard();
            if (t.mob != null && !t.mob.isRemoved()) t.mob.discard();
            if (t.tag != null && !t.tag.isRemoved()) t.tag.discard();
        }
        if (watcher != null && !watcher.isRemoved()) watcher.discard();
        TRIPS.clear();
        LEGS.clear();
        forceChunks(level, 0, 0, false);
        watcher = null;
        tick = -1;
        passAt = -1;
        done = false;
    }
}
