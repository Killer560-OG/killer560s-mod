package com.killer560.hub.autosecret;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.autoroutes.RouteCoords;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ModPaths;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measures which room entries make Hypixel count a room as cleared without its starred mobs being killed (an
 * "insta clear"), so Auto Secret only tries one it has evidence for.
 *
 * <p>Passive observation only: it reads the player's own position, the entities the client already has, and the
 * dungeon map item the Live Map already samples. It sends nothing to the server. Cheat build only - its one consumer
 * (Auto Secret) is a cheat feature, so a legit jar has nothing to feed and this whole class folds out of it.
 *
 * <p><b>Recording.</b> Every time the player's room changes (by etherwarp, any other teleport, or on foot) an
 * observation opens for the room he entered: room, {@link #entryKeyFor entry key}, how he got there and who was
 * driving, the map state of both rooms, and then - for up to the window (15 s by default) - the room's starred
 * name-tag stands and the room's map state. It closes as one of {@link InstaClearStore.Outcome}: INSTA when the map
 * flips to cleared with starred mobs still standing, KILLED when it flips only after every starred stand seen in it is
 * gone, NO_CLEAR when it never flips; the rest are recorded but not counted.
 *
 * <p><b>Known rule</b> ({@link #knownToInstaClear}): {@code >= N} INSTA observations and no KILLED/NO_CLEAR at all
 * for that room + entry key, N = 2 by default ({@code /autosecret instaclear threshold <n>}).
 *
 * <p>Persisted to {@code config/killer560/dungeons/autosecret/insta-clear.json} after every closed observation.
 */
public final class InstaClearTracker {

    private static final Logger LOGGER = ModLog.get("killer560smod-autosecret");
    private static final String STAR = "✯";
    private static final String HEART = "❤";
    /** A stand whose health reads 0 is a mob that just died; Hypixel removes the stand right after. */
    private static final Pattern ZERO_HEALTH = Pattern.compile("(?<![\\d.,])0" + HEART);
    private static final Pattern KEY = Pattern.compile(
            "^from=(\\?|-?\\d{1,3},-?\\d{1,3});land=(walk|-?\\d{1,4},-?\\d{1,4},-?\\d{1,4})$");
    /** A stand that disappears within this many blocks of him is a kill; farther, it may only have left his view. */
    private static final double KILL_SEEN_RANGE = 40.0;
    private static final int SCAN_EVERY_TICKS = 2;
    private static final String FEATURE = "Insta Clear";

    private static final InstaClearStore STORE = new InstaClearStore();
    private static Path file;
    private static boolean loaded;
    private static boolean registered;

    // ---- per-run state, client thread only ----
    private static Object lastLevel;
    private static int lastGeneration = Integer.MIN_VALUE;
    private static String prevRoom;
    private static Vec3 prevPos;
    private static int tick;
    /** Set by the position-packet hook; the room change seen on the next tick came by teleport from {@link #teleportFrom}. */
    private static boolean teleportPending;
    private static Vec3 teleportFrom;
    private static String teleportMethod;
    private static long automatedUntilMs;
    private static String automatedBy;
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
    /** Test hook: room name -> forced map state, used instead of the dungeon map when present. */
    private static final Map<String, Integer> FORCED_STATE = new HashMap<>();

    private InstaClearTracker() {
    }

    /** One open observation: the room entered and what has been seen in it since. */
    private static final class Pending {
        final String room;
        final String key;
        final InstaClearStore.Obs obs = new InstaClearStore.Obs();
        final long startMs;
        final Map<Integer, Vec3> standsAlive = new HashMap<>();
        final Set<Integer> standsSeen = new HashSet<>();
        final Set<Integer> killed = new HashSet<>();

        Pending(String room, String key, long startMs) {
            this.room = room;
            this.key = key;
            this.startMs = startMs;
        }
    }

    // ============================================================================================== public API

    /** Hooks the recorder up: client tick, the position-packet hook (via {@code LiveMapPacketListenerMixin}) and the
     *  {@code /autosecret instaclear} readout. Safe to call more than once. A no-op in the legit build. */
    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || registered) {
            return;
        }
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("InstaClearTracker", client -> tick(client)));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("autosecret")
                        .then(ClientCommands.literal("instaclear")
                                .executes(c -> {
                                    summary();
                                    return 1;
                                })
                                .then(ClientCommands.literal("room")
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .executes(c -> {
                                                    roomDetail(StringArgumentType.getString(c, "name"));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("last").executes(c -> {
                                    last();
                                    return 1;
                                }))
                                .then(ClientCommands.literal("mark")
                                        .then(ClientCommands.argument("verdict", StringArgumentType.word())
                                                .suggests((c, b) -> {
                                                    for (String s : new String[]{"insta", "notinsta", "ignore", "measured"}) {
                                                        b.suggest(s);
                                                    }
                                                    return b.buildFuture();
                                                })
                                                .executes(c -> {
                                                    mark(StringArgumentType.getString(c, "verdict"));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("threshold")
                                        .then(ClientCommands.argument("n", IntegerArgumentType.integer(1, 50))
                                                .executes(c -> {
                                                    ensureLoaded();
                                                    STORE.setMinSuccesses(IntegerArgumentType.getInteger(c, "n"));
                                                    save();
                                                    ModChat.send(FEATURE, ModChat.text("Known now needs "),
                                                            ModChat.value(STORE.minSuccesses() + ""),
                                                            ModChat.text(" insta clears and no failure."));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("window")
                                        .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(2, 120))
                                                .executes(c -> {
                                                    ensureLoaded();
                                                    STORE.setWindowSeconds(IntegerArgumentType.getInteger(c, "seconds"));
                                                    save();
                                                    ModChat.send(FEATURE, ModChat.text("A room now has "),
                                                            ModChat.value(STORE.windowSeconds() + " s"),
                                                            ModChat.text(" after entry to flip."));
                                                    return 1;
                                                }))))));
        LOGGER.info("[InstaClear] recorder registered, evidence at {}", path());
    }

    /**
     * @return true only when the recorded evidence says entering {@code roomName} by {@code entryKey} clears it:
     *         at least {@link #minSuccesses()} observed insta clears and not one failure, for that exact room and key.
     */
    public static boolean knownToInstaClear(String roomName, String entryKey) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        ensureLoaded();
        return STORE.known(roomName, entryKey);
    }

    /** @return the entry keys known to insta clear {@code roomName}, most successes first; empty when none. */
    public static List<String> knownEntries(String roomName) {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return new ArrayList<>();
        }
        ensureLoaded();
        return STORE.knownKeys(roomName);
    }

    /**
     * How an entry is identified, so that the same entry compares equal across runs and room rotations.
     *
     * <p>Format: {@code from=<fx>,<fz>;land=<x>,<y>,<z>}, everything in the ENTERED room's own relative frame
     * ({@link RouteCoords}, the frame Auto Routes and secret waypoints store in):
     * <ul>
     *   <li>{@code from} is the room tile he came from - the tile of {@code fromRoomName} nearest to the entered room -
     *       as a relative 32-block tile index. {@code -1,0} is the tile next to relative tile {@code 0,0} on its -x
     *       side; {@code -2,0} means one room was skipped. {@code ?} when the from room is unknown.</li>
     *   <li>{@code land} is {@code landing} as a room-relative block (y without the sim's altitude shift), or
     *       {@code walk} when {@code landing} is null (he walked in through a door).</li>
     * </ul>
     *
     * @param landing the REAL world block he lands ON - the solid block under his feet after the etherwarp, the same
     *                block {@code ClearExecutor.etherPath} and Auto Routes target - or null for a walk-in
     * @return the key, or null while {@code roomName} is not on the current map with a known rotation
     */
    public static String entryKeyFor(String roomName, BlockPos landing, String fromRoomName) {
        DungeonLayout layout = DungeonLayout.current();
        int room = roomIdByName(layout, roomName);
        if (room < 0) {
            return null;
        }
        RouteCoords.Frame frame = frameOf(layout, room);
        if (frame == null) {
            return null;
        }
        String land;
        if (landing == null) {
            land = "walk";
        } else {
            BlockPos rel = RouteCoords.toRelativeBlock(frame, landing);
            land = rel.getX() + "," + rel.getY() + "," + rel.getZ();
        }
        int from = fromRoomName == null || fromRoomName.equals(roomName) ? -1 : roomIdByName(layout, fromRoomName);
        String fromPart = "?";
        if (from >= 0) {
            int[] pair = nearestTiles(layout, from, room);
            if (pair != null) {
                BlockPos centre = DungeonLayout.cellCenter(pair[0]);
                Vec3 rel = RouteCoords.toRelative(frame, new Vec3(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5));
                fromPart = Math.floorDiv((int) Math.floor(rel.x), 32) + "," + Math.floorDiv((int) Math.floor(rel.z), 32);
            }
        }
        return "from=" + fromPart + ";land=" + land;
    }

    // ============================================================================================== extras

    /** The real world block an entry key lands on in this run's copy of {@code roomName}, or null (walk-in key, room
     *  not on the map, or not a key this class made). The inverse of the {@code land} half of {@link #entryKeyFor}. */
    public static BlockPos realLandingFor(String roomName, String entryKey) {
        Matcher m = entryKey == null ? null : KEY.matcher(entryKey);
        if (m == null || !m.matches() || m.group(2).equals("walk")) {
            return null;
        }
        DungeonLayout layout = DungeonLayout.current();
        int room = roomIdByName(layout, roomName);
        RouteCoords.Frame frame = room < 0 ? null : frameOf(layout, room);
        if (frame == null) {
            return null;
        }
        String[] p = m.group(2).split(",");
        return RouteCoords.toRealBlock(frame, new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                Integer.parseInt(p[2])));
    }

    /** Optional: Auto Secret (or anything automated) calls this just before it warps, so the next entry is recorded
     *  with {@code driver=<who>} rather than guessed. Entries without it are "manual" unless Auto Routes or the
     *  Interactive Map is running. */
    public static void noteAutomatedEntry(String who) {
        automatedBy = who == null ? "automated" : who;
        automatedUntilMs = System.currentTimeMillis() + 3000;
    }

    public static int minSuccesses() {
        ensureLoaded();
        return STORE.minSuccesses();
    }

    /** {@code LiveMapFeature.scanConsumers}: keep the dungeon map sampled while the recorder needs its states. */
    public static boolean wantsMapScan() {
        return BuildVariant.CHEAT_FEATURES_ENABLED && registered;
    }

    /** {@code LiveMapPacketListenerMixin#handleMovePlayer}, client thread, BEFORE the position is applied - so the
     *  player is still where the teleport started. */
    public static void onServerPositionPacket() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED || !registered) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        teleportPending = true;
        teleportFrom = player.position();
        boolean etherItem = ItemIdentity.isEtherwarpItem(player.getMainHandItem());
        teleportMethod = etherItem && player.isShiftKeyDown() ? "etherwarp" : "teleport";
    }

    // ============================================================================================== recording

    private static void tick(Minecraft client) {
        tick++;
        if (client.level != lastLevel || LiveMapFeature.resetGeneration() != lastGeneration) {
            lastLevel = client.level;
            lastGeneration = LiveMapFeature.resetGeneration();
            if (!PENDING.isEmpty()) {
                LOGGER.info("[InstaClear] dropped {} open observation(s): world or map reset", PENDING.size());
            }
            PENDING.clear();
            prevRoom = null;
            prevPos = null;
            teleportPending = false;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || !DungeonState.isInDungeon() || LiveMapFeature.isInBoss()) {
            prevRoom = null;
            prevPos = player == null ? null : player.position();
            teleportPending = false;
            return;
        }
        DungeonLayout layout = DungeonLayout.current();
        Vec3 pos = player.position();
        int roomId = layout.roomAtWorld(pos.x, pos.z);
        String room = identified(layout, roomId) ? layout.name(roomId) : null;

        if (room != null && !room.equals(prevRoom)) {
            boolean teleported = teleportPending;
            Vec3 from = teleported && teleportFrom != null ? teleportFrom : prevPos;
            String fromRoom = prevRoom;
            if (teleported && teleportFrom != null) {
                int fromId = layout.roomAtWorld(teleportFrom.x, teleportFrom.z);
                fromRoom = identified(layout, fromId) ? layout.name(fromId) : null;
            }
            String method = teleported ? teleportMethod : "walk";
            BlockPos landing = teleported ? player.blockPosition().below() : null;
            try {
                onEntry(client, layout, roomId, room, fromRoom, from, pos, landing, method);
            } catch (RuntimeException e) {
                LOGGER.warn("[InstaClear] could not open an observation for {}", room, e);
            }
        }
        if (room != null) {
            prevRoom = room;
        }
        prevPos = pos;
        teleportPending = false;

        if (!PENDING.isEmpty() && tick % SCAN_EVERY_TICKS == 0) {
            scan(client, layout);
        }
    }

    private static void onEntry(Minecraft client, DungeonLayout layout, int roomId, String room, String fromRoom,
                                Vec3 from, Vec3 to, BlockPos landing, String method) {
        ensureLoaded();
        if (PENDING.containsKey(room)) {
            return; // back in a room already being watched - the first entry is the one that counts
        }
        String key = entryKeyFor(room, landing, fromRoom);
        if (key == null) {
            return;
        }
        int before = roomState(layout, roomId, room);
        Pending p = new Pending(room, key, System.currentTimeMillis());
        InstaClearStore.Obs o = p.obs;
        o.t = p.startMs;
        o.floor = DungeonState.getFloor();
        o.from = fromRoom;
        int fromId = roomIdByName(layout, fromRoom);
        o.fromState = fromId >= 0 ? stateName(roomState(layout, fromId, fromRoom)) : null;
        o.before = stateName(before);
        o.method = method;
        o.driver = driver();
        o.travel = from == null ? 0.0 : from.distanceTo(to);
        o.skip = fromId >= 0 ? skipBetween(layout, fromId, roomId) : -1;
        if (before == LiveMapFeature.MAP_CLEARED || before == LiveMapFeature.MAP_GREEN) {
            // Already cleared: there is nothing to learn, and every walk back through would flood the file.
            return;
        }
        PENDING.put(room, p);
        scanRoom(client, layout, p, roomId);
    }

    private static void scan(Minecraft client, DungeonLayout layout) {
        long now = System.currentTimeMillis();
        List<String> done = new ArrayList<>();
        for (Pending p : PENDING.values()) {
            int roomId = roomIdByName(layout, p.room);
            if (roomId < 0) {
                done.add(p.room);
                continue;
            }
            int alive = scanRoom(client, layout, p, roomId);
            int state = roomState(layout, roomId, p.room);
            boolean flipped = state == LiveMapFeature.MAP_CLEARED || state == LiveMapFeature.MAP_GREEN;
            InstaClearStore.Obs o = p.obs;
            o.stars = p.standsSeen.size();
            o.kills = p.killed.size();
            if (flipped) {
                o.flipMs = now - p.startMs;
                o.aliveAtFlip = alive;
                if (!nearRoom(client, layout, roomId, 16)) {
                    o.outcome = InstaClearStore.Outcome.UNOBSERVED;
                } else if (p.standsSeen.isEmpty()) {
                    o.outcome = InstaClearStore.Outcome.NO_STARS;
                } else if (alive > 0) {
                    o.outcome = InstaClearStore.Outcome.INSTA;
                } else {
                    o.outcome = InstaClearStore.Outcome.KILLED;
                }
            } else if (now - p.startMs >= STORE.windowSeconds() * 1000L) {
                o.aliveAtFlip = -1;
                if (state < 0) {
                    o.outcome = InstaClearStore.Outcome.NO_MAP;
                } else if (p.standsSeen.isEmpty()) {
                    o.outcome = InstaClearStore.Outcome.NO_STARS;
                } else {
                    o.outcome = InstaClearStore.Outcome.NO_CLEAR;
                }
            } else {
                continue;
            }
            done.add(p.room);
            close(p);
        }
        for (String r : done) {
            PENDING.remove(r);
        }
    }

    private static void close(Pending p) {
        InstaClearStore.Obs o = p.obs;
        STORE.add(p.room, p.key, o);
        int[] c = STORE.counts(p.room, p.key);
        LOGGER.info("[InstaClear] {} via {} ({} by {}, from {}): {} - stars {}, kills {}, alive at flip {}, flip {} ms;"
                        + " entry now {} ok / {} fail", p.room, p.key, o.method, o.driver, o.from, o.outcome, o.stars,
                o.kills, o.aliveAtFlip, o.flipMs, c[0], c[1]);
        save();
    }

    /** Updates the room's starred stands and @return how many are standing in it right now. */
    private static int scanRoom(Minecraft client, DungeonLayout layout, Pending p, int roomId) {
        int[] tiles = layout.tiles(roomId);
        Set<Integer> present = new HashSet<>();
        int alive = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) {
                continue;
            }
            if (!inTiles(tiles, stand.getX(), stand.getZ(), 0)) {
                continue;
            }
            String name = stand.getName().getString();
            if (!name.contains(STAR) || !name.contains(HEART)) {
                continue;
            }
            int id = stand.getId();
            if (ZERO_HEALTH.matcher(name).find()) {
                if (p.standsSeen.contains(id)) {
                    p.killed.add(id);
                }
                p.standsAlive.remove(id);
                continue;
            }
            present.add(id);
            p.standsSeen.add(id);
            p.standsAlive.put(id, new Vec3(stand.getX(), stand.getY(), stand.getZ()));
            alive++;
        }
        LocalPlayer player = client.player;
        var it = p.standsAlive.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (present.contains(e.getKey())) {
                continue;
            }
            Vec3 at = e.getValue();
            if (player != null && sq(player.getX() - at.x) + sq(player.getY() - at.y) + sq(player.getZ() - at.z)
                    <= KILL_SEEN_RANGE * KILL_SEEN_RANGE) {
                p.killed.add(e.getKey());
            }
            it.remove();
        }
        return alive;
    }

    // ============================================================================================== helpers

    private static double sq(double v) {
        return v * v;
    }

    private static boolean identified(DungeonLayout layout, int roomId) {
        if (roomId < 0) {
            return false;
        }
        String name = layout.name(roomId);
        return name != null && !name.equals("Unknown") && layout.clayRotation(roomId) != null;
    }

    private static int roomIdByName(DungeonLayout layout, String name) {
        if (name == null) {
            return -1;
        }
        for (int r = 0; r < layout.roomCount(); r++) {
            if (name.equals(layout.name(r)) && identified(layout, r)) {
                return r;
            }
        }
        return -1;
    }

    private static RouteCoords.Frame frameOf(DungeonLayout layout, int room) {
        int[] cr = layout.clayRotation(room);
        return cr == null ? null : new RouteCoords.Frame(layout.name(room), cr[0], cr[1], cr[2]);
    }

    /** @return {@code {fromTileIdx, toTileIdx}}: the pair of tiles, one per room, closest to each other. */
    private static int[] nearestTiles(DungeonLayout layout, int fromRoom, int toRoom) {
        int[] best = null;
        int bestD = Integer.MAX_VALUE;
        for (int a : layout.tiles(fromRoom)) {
            for (int b : layout.tiles(toRoom)) {
                int dx = a % DungeonLayout.GRID - b % DungeonLayout.GRID;
                int dz = a / DungeonLayout.GRID - b / DungeonLayout.GRID;
                int d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = new int[]{a, b};
                }
            }
        }
        return best;
    }

    /** Room tiles strictly between the two rooms' nearest tiles (grid steps of 2 per tile), 0 when adjacent. */
    private static int skipBetween(DungeonLayout layout, int fromRoom, int toRoom) {
        int[] pair = nearestTiles(layout, fromRoom, toRoom);
        if (pair == null) {
            return -1;
        }
        int dx = Math.abs(pair[0] % DungeonLayout.GRID - pair[1] % DungeonLayout.GRID) / 2;
        int dz = Math.abs(pair[0] / DungeonLayout.GRID - pair[1] / DungeonLayout.GRID) / 2;
        return Math.max(0, dx + dz - 1);
    }

    private static boolean inTiles(int[] tiles, double x, double z, double margin) {
        for (int t : tiles) {
            BlockPos c = DungeonLayout.cellCenter(t);
            if (x >= c.getX() - 16 - margin && x < c.getX() + 16 + margin
                    && z >= c.getZ() - 16 - margin && z < c.getZ() + 16 + margin) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearRoom(Minecraft client, DungeonLayout layout, int roomId, double margin) {
        return client.player != null && inTiles(layout.tiles(roomId), client.player.getX(), client.player.getZ(), margin);
    }

    /** Most progressed map state over the room's tiles, or -1 with no calibrated map. A test may force one. */
    private static int roomState(DungeonLayout layout, int roomId, String name) {
        Integer forced = name == null ? null : FORCED_STATE.get(name);
        if (forced != null) {
            return forced;
        }
        int best = Integer.MAX_VALUE;
        for (int t : layout.tiles(roomId)) {
            int s = LiveMapFeature.mapStateAt(t);
            if (s < 0) {
                return -1;
            }
            best = Math.min(best, s);
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static String stateName(int state) {
        if (state == LiveMapFeature.MAP_GREEN) return "green";
        if (state == LiveMapFeature.MAP_CLEARED) return "cleared";
        if (state == LiveMapFeature.MAP_DISCOVERED) return "discovered";
        if (state == LiveMapFeature.MAP_FAILED) return "failed";
        if (state == LiveMapFeature.MAP_UNOPENED) return "unopened";
        if (state == LiveMapFeature.MAP_UNDISCOVERED) return "undiscovered";
        return "nomap";
    }

    private static String driver() {
        if (System.currentTimeMillis() < automatedUntilMs && automatedBy != null) {
            return automatedBy;
        }
        if (com.killer560.hub.autoroutes.RouteExecutor.isRunning()) {
            return "autoroutes";
        }
        if (com.killer560.hub.livemap.autoclear.ClearExecutor.isActive()) {
            return "interactivemap";
        }
        return "manual";
    }

    // ============================================================================================== persistence

    private static Path path() {
        if (file == null) {
            file = ModPaths.config("killer560smod-autosecret/insta-clear.json");
        }
        return file;
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            if (STORE.load(path())) {
                LOGGER.info("[InstaClear] loaded {} observation(s) from {}", STORE.size(), path());
            }
        } catch (Exception e) {
            // Keep the unreadable file: never overwrite evidence because one read failed.
            LOGGER.warn("[InstaClear] could not read {} - recording into a fresh file beside it", path(), e);
            file = path().resolveSibling("insta-clear.unreadable-" + System.currentTimeMillis() + ".json");
            STORE.fromJson(null);
        }
    }

    private static void save() {
        try {
            STORE.save(path());
        } catch (Exception e) {
            LOGGER.warn("[InstaClear] could not save {}", path(), e);
        }
    }

    // ============================================================================================== readout

    private static List<String> summaryLines() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        List<String> rooms = STORE.roomNames();
        out.add(rooms.size() + " room(s), " + STORE.size() + " entr(ies) recorded; known = >= "
                + STORE.minSuccesses() + " insta clears and 0 failures; window " + STORE.windowSeconds() + " s");
        for (String room : rooms) {
            int ok = 0;
            int fail = 0;
            int other = 0;
            int known = 0;
            List<String> keys = STORE.keys(room);
            for (String key : keys) {
                int[] c = STORE.counts(room, key);
                ok += c[0];
                fail += c[1];
                other += c[2];
                if (STORE.known(room, key)) {
                    known++;
                }
            }
            long median = STORE.medianFlipMs(room, null);
            out.add(room + ": " + keys.size() + " entr(ies), " + ok + " insta / " + fail + " fail (" + other
                    + " not counted), median " + (median < 0 ? "-" : median + " ms") + ", known " + known);
        }
        return out;
    }

    private static void summary() {
        List<String> lines = summaryLines();
        ModChat.send(FEATURE, ModChat.text(lines.get(0)));
        for (int i = 1; i < lines.size(); i++) {
            ModChat.send(FEATURE, ModChat.dim(lines.get(i)));
        }
        if (lines.size() == 1) {
            ModChat.send(FEATURE, ModChat.dim("Nothing yet - enter rooms in a dungeon and it records each entry."));
        }
    }

    private static void roomDetail(String room) {
        ensureLoaded();
        String match = null;
        for (String r : STORE.roomNames()) {
            if (r.equalsIgnoreCase(room.trim())) {
                match = r;
            }
        }
        if (match == null) {
            ModChat.send(FEATURE, ModChat.bad("No entries recorded for " + room));
            return;
        }
        ModChat.send(FEATURE, ModChat.value(match));
        for (String key : STORE.keys(match)) {
            int[] c = STORE.counts(match, key);
            long median = STORE.medianFlipMs(match, key);
            Component verdict = STORE.known(match, key) ? ModChat.good(" KNOWN") : ModChat.dim("");
            ModChat.send(FEATURE, ModChat.text(key + "  "), ModChat.dim(c[0] + " insta / " + c[1] + " fail (+" + c[2]
                    + "), median " + (median < 0 ? "-" : median + " ms")), verdict);
        }
    }

    private static void last() {
        ensureLoaded();
        InstaClearStore.Obs o = STORE.last();
        if (o == null) {
            ModChat.send(FEATURE, ModChat.dim("No entry recorded this session yet."));
            return;
        }
        ModChat.send(FEATURE, ModChat.value(STORE.lastRoom()), ModChat.text(" " + STORE.lastKey()));
        ModChat.send(FEATURE, ModChat.dim(o.method + " by " + o.driver + " from " + o.from + " (" + o.fromState
                + "), room was " + o.before + "; stars " + o.stars + ", kills " + o.kills + ", alive at flip "
                + o.aliveAtFlip + ", flip " + o.flipMs + " ms -> " + o.outcome
                + (o.verdict != null ? " (marked " + o.verdict + ")" : "")));
    }

    private static void mark(String verdict) {
        ensureLoaded();
        InstaClearStore.Obs o = STORE.last();
        if (o == null) {
            ModChat.send(FEATURE, ModChat.bad("No entry recorded this session to mark."));
            return;
        }
        InstaClearStore.Outcome v = switch (verdict.toLowerCase(Locale.ROOT)) {
            case "insta" -> InstaClearStore.Outcome.INSTA;
            case "notinsta" -> InstaClearStore.Outcome.NOT_INSTA;
            case "ignore" -> InstaClearStore.Outcome.IGNORE;
            case "measured" -> null;
            default -> InstaClearStore.Outcome.IGNORE;
        };
        if (!List.of("insta", "notinsta", "ignore", "measured").contains(verdict.toLowerCase(Locale.ROOT))) {
            ModChat.send(FEATURE, ModChat.bad("Use insta, notinsta, ignore or measured."));
            return;
        }
        o.verdict = v;
        save();
        ModChat.send(FEATURE, ModChat.text("Marked the last entry ("), ModChat.value(STORE.lastRoom()),
                ModChat.text(") " + (v == null ? "back to its measured outcome " + o.outcome : v.name())));
    }

    // ============================================================================================== test hooks
    // For the testkit's logic and sim cases (reflection). None of these is used by the mod itself.

    /** Points the store at {@code path} (any file) and loads it; null goes back to the real file. */
    public static void testUseFile(String path) {
        file = path == null ? null : Path.of(path);
        loaded = false;
        PENDING.clear();
        ensureLoaded();
    }

    /** Overrides the dungeon map's state for a room by name ({@code LiveMapFeature.MAP_*}); null removes it. */
    public static void testForceMapState(String room, Integer state) {
        if (state == null) {
            FORCED_STATE.remove(room);
        } else {
            FORCED_STATE.put(room, state);
        }
    }

    public static void testClearForcedStates() {
        FORCED_STATE.clear();
    }

    /** Adds one closed observation, as if measured, and saves. {@code outcome} is an {@code Outcome} name. */
    public static void testAdd(String room, String key, String outcome, long flipMs) {
        ensureLoaded();
        InstaClearStore.Obs o = new InstaClearStore.Obs();
        o.t = System.currentTimeMillis();
        o.method = "test";
        o.driver = "test";
        o.outcome = InstaClearStore.Outcome.valueOf(outcome);
        o.flipMs = flipMs;
        STORE.add(room, key, o);
        save();
    }

    /** Sets the threshold N and saves (the same as {@code /autosecret instaclear threshold N}). */
    public static void testSetMinSuccesses(int n) {
        ensureLoaded();
        STORE.setMinSuccesses(n);
        save();
    }

    public static void testSetWindowSeconds(int s) {
        ensureLoaded();
        STORE.setWindowSeconds(s);
        save();
    }

    /** Drops everything recorded (in memory and in the current file). */
    public static void testClearAll() {
        ensureLoaded();
        STORE.clear();
        PENDING.clear();
        save();
    }

    /** Reloads the current file from disk, as a restart would. */
    public static void testReload() {
        loaded = false;
        ensureLoaded();
    }

    public static List<String> testSummaryLines() {
        return summaryLines();
    }

    /** Open observations as {@code room|key|method|stars|kills}. */
    public static List<String> testPending() {
        List<String> out = new ArrayList<>();
        for (Pending p : PENDING.values()) {
            out.add(p.room + "|" + p.key + "|" + p.obs.method + "|" + p.standsSeen.size() + "|" + p.killed.size());
        }
        return out;
    }

    /** {@code {successes, failures, notCounted}} for one room + key. */
    public static int[] testCounts(String room, String key) {
        ensureLoaded();
        return STORE.counts(room, key);
    }

    public static String testLast() {
        InstaClearStore.Obs o = STORE.last();
        return o == null ? null : STORE.lastRoom() + "|" + STORE.lastKey() + "|" + o.toJson();
    }

    /** The newest observation for one room as {@code room|key|json}, or null. */
    public static String testLastFor(String room) {
        String s = STORE.newestIn(room);
        return s == null ? null : room + "|" + s;
    }

    public static String testFilePath() {
        return path().toString();
    }

    public static int testSize() {
        ensureLoaded();
        return STORE.size();
    }
}
