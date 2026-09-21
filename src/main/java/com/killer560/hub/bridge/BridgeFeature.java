package com.killer560.hub.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.interop.InteropConfig;
import com.killer560.hub.interop.InteropSource;
import com.killer560.hub.interop.PartyInteropState;
import com.killer560.hub.leapmenu.PartyTracker;
import com.killer560.hub.livemap.DungeonLayout;
import com.killer560.hub.partydata.PartyDataFeature;
import com.killer560.hub.partydata.PartyRoomIntel;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.scores.PlayerTeam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Cross-Mod Bridge: killer560s-mod speaks Devonian's, NoammAddons' and Odin's own party sockets, so party data
 * flows between our users and theirs in both directions. killer560 (2026-09-21): "I will never have access to
 * joy's bridge so I need you to build it", "Also make it work for Odin", "I really want everything to tie into
 * my mod."
 * <p>
 * The protocols are documented, source-cited, in the staging research ({@code BRIDGE-PROTOCOLS.md}); every
 * identifier translation lives in {@link BridgeTables}; the wire formats are {@link DevonianCodec},
 * {@link NoammCodec} and {@link OdinCodec}; the connection rules shared by all three are in
 * {@link SocketAdapter}. This class is the client-thread glue:
 * <ul>
 * <li><b>Out:</b> every 5 ticks it reads a {@link BridgeContext} - including
 * {@link PartyDataFeature#selfFactSnapshot()}, the same SELF facts our own relay publishes, so nothing is
 * re-derived - and hands it to each adapter, which translates and queues what its mod understands.</li>
 * <li><b>In:</b> adapters decode and validate on their own threads, then call back here on the client thread,
 * where facts go into {@link PartyInteropState} as {@link InteropSource#SOCKET} (below our own relay and the
 * local-mod bridge, above party chat) or into {@link PartyRoomIntel}/{@link MelodyIntel}.</li>
 * </ul>
 * <b>Hard rules</b> (all enforced in the adapters' {@code decide}): never connect to a mod's socket when that
 * mod is installed here - it already holds the connection and a duplicate might kick it; Hypixel only; only while
 * the grouping applies; only while the per-mod toggle is on; never any identity but the signed-in account's own.
 */
public final class BridgeFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-bridge");
    private static final int TICK_DIVISOR = 5;
    private static final int SERVER_CODE_DIVISOR = 20;

    private static final DevonianAdapter DEVONIAN = new DevonianAdapter();
    private static final NoammAdapter NOAMM = new NoammAdapter();
    private static final OdinAdapter ODIN = new OdinAdapter();
    private static final List<SocketAdapter> ADAPTERS = List.of(DEVONIAN, NOAMM, ODIN);

    private static int tickCounter;
    private static int serverCodeCounter;
    private static String serverCode;
    private static Object serverCodeLevel;
    private static boolean wasInDungeon;
    private static int runEpoch;
    private static volatile boolean p3Active;

    private BridgeFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(BridgeFeature::tick);
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(message)));
        LOGGER.info("[Bridge] Registered");
    }

    /** For the settings tab. */
    public static List<SocketAdapter> adapters() {
        return ADAPTERS;
    }

    /** A toggle changed: let a "gave up for this session" adapter try again on demand. */
    public static void onSettingsChanged() {
        for (SocketAdapter adapter : ADAPTERS) {
            adapter.resetSession();
        }
    }

    // ------------------------------------------------------------------ client tick

    private static void tick(Minecraft client) {
        boolean inDungeon = DungeonState.isInDungeon() && !DungeonState.isSimOverrideActive();
        if (inDungeon != wasInDungeon) {
            wasInDungeon = inDungeon;
            if (inDungeon) {
                runEpoch++;
            }
            p3Active = false;
            MelodyIntel.clear();
        }
        if (++tickCounter < TICK_DIVISOR) {
            return;
        }
        tickCounter = 0;
        BridgeContext ctx;
        try {
            ctx = context(client, inDungeon);
        } catch (Throwable t) {
            LOGGER.debug("[Bridge] context failed: {}", t.toString());
            return;
        }
        for (SocketAdapter adapter : ADAPTERS) {
            adapter.clientTick(ctx);
        }
    }

    private static void onChat(String plain) {
        if (plain == null || plain.isEmpty()) {
            return;
        }
        if (OdinCodec.P3_START.matcher(plain).matches()) {
            p3Active = true;
        } else if (OdinCodec.CORE_OPEN.matcher(plain).matches()) {
            // spec 3.3: Odin shuts its socket and clears every player's melody here
            p3Active = false;
            MelodyIntel.clear();
        }
    }

    private static BridgeContext context(Minecraft client, boolean inDungeon) {
        BridgeConfig cfg = BridgeConfig.getInstance();
        boolean anyOn = cfg.isEnabled() && (cfg.isDevonian() || cfg.isNoamm() || cfg.isOdin());
        if (!anyOn || client.player == null || client.level == null) {
            return new BridgeContext(false, false, false, null, null, null, List.of(), null, null, -1, -1,
                    List.of(), List.of(), false, runEpoch);
        }
        boolean active = InteropConfig.getInstance().isEnabled();
        boolean onHypixel = onHypixel(client);

        String selfName = null;
        UUID selfUuid = null;
        if (client.getUser() != null && client.getUser().getName() != null
                && client.getUser().getName().equals(client.player.getGameProfile().name())) {
            selfName = client.getUser().getName();
            selfUuid = client.getUser().getProfileId();
        }
        List<String> teammates = List.copyOf(PartyTracker.teammates());

        if (++serverCodeCounter >= SERVER_CODE_DIVISOR / TICK_DIVISOR || client.level != serverCodeLevel) {
            serverCodeCounter = 0;
            if (client.level != serverCodeLevel) {
                serverCodeLevel = client.level;
                serverCode = null;
            }
            String code = readServerCode(client);
            if (code != null) {
                serverCode = code;
            }
        }

        Integer partyHash = inDungeon && onHypixel ? devonianPartyHash(client, selfUuid, teammates) : null;

        List<PartyDataFeature.SelfFact> selfFacts = List.of();
        List<BridgeContext.RoomCell> cells = List.of();
        int entranceCol = -1;
        int entranceRow = -1;
        if (inDungeon && active && onHypixel && (DEVONIAN.isReady() || NOAMM.isReady())) {
            selfFacts = PartyDataFeature.selfFactSnapshot();
            DungeonLayout layout = DungeonLayout.current();
            List<BridgeContext.RoomCell> out = new ArrayList<>();
            for (int idx = 0; idx < DungeonLayout.GRID * DungeonLayout.GRID; idx++) {
                int room = layout.roomOfCell(idx);
                if (room < 0) {
                    continue;
                }
                String name = layout.name(room);
                if (name == null || name.isBlank() || "Unknown".equals(name)) {
                    continue;
                }
                int col = idx % DungeonLayout.GRID;
                int row = idx / DungeonLayout.GRID;
                BlockPos centre = DungeonLayout.cellCenter(idx);
                // Room tiles sit on even col/row; a room cell with an odd coordinate is a connector inside a
                // multi-tile room - NoammAddons' "separator".
                out.add(new BridgeContext.RoomCell(name, centre.getX(), centre.getZ(), col, row,
                        col % 2 == 1 || row % 2 == 1));
                RoomEntry entry = layout.entry(room);
                if (entranceCol < 0 && entry != null && "entrance".equalsIgnoreCase(entry.type)) {
                    // First cell in row-major order, like NoammAddons' dungeonList.find (FEAT_WebSocket.kt:79).
                    entranceCol = col;
                    entranceRow = row;
                }
            }
            cells = out;
        }
        return new BridgeContext(active, onHypixel, inDungeon, DungeonState.getFloor(), selfName, selfUuid,
                teammates, partyHash, serverCode, entranceCol, entranceRow, selfFacts, cells, p3Active, runEpoch);
    }

    /** hypixel.net only. p3sim has none of the identifiers these sockets group by (no Hypixel party for
     *  Devonian's Mod-API hash, no Hypixel server code for NoammAddons/Odin), so it is deliberately excluded. */
    private static boolean onHypixel(Minecraft client) {
        ServerData server = client.getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
    }

    /**
     * Devonian's party hash (spec 1.3), or null unless it can be computed EXACTLY: our UUID plus every party
     * mate's, each resolved from the player list Hypixel sends this client. Devonian itself reads the member set
     * from Hypixel's Mod API party packet, which this mod does not depend on; inside a dungeon the whole party is
     * in this instance, so their real UUIDs are known. Anything unresolvable -> null -> do not connect (a wrong
     * set would just be a room nobody else is in). Hypixel's fake tab-list entries use version-2 UUIDs; a real
     * account is always version 4, so only those count.
     */
    static Integer devonianPartyHash(Minecraft client, UUID self, List<String> teammates) {
        ClientPacketListener connection = client.getConnection();
        if (self == null || connection == null || teammates.isEmpty() || teammates.size() > 4) {
            return null;
        }
        List<UUID> members = new ArrayList<>(teammates.size() + 1);
        members.add(self);
        for (String name : teammates) {
            PlayerInfo info = connection.getPlayerInfo(name);
            if (info == null || info.getProfile() == null) {
                return null;
            }
            UUID id = info.getProfile().id();
            if (id == null || id.version() != 4 || members.contains(id)) {
                return null;
            }
            members.add(id);
        }
        return DevonianCodec.partyHash(members);
    }

    /** The sidebar date line's server code, exactly the way NoammAddons ({@code LocationUtils.kt:54-56}) and
     *  Odin read it: each scoreboard team's prefix + suffix, formatting stripped, through the shared regex. */
    private static String readServerCode(Minecraft client) {
        try {
            for (PlayerTeam team : client.level.getScoreboard().getPlayerTeams()) {
                String text = ChatFormatting.stripFormatting(team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString());
                String code = OdinCodec.serverCode(text);
                if (code != null) {
                    return code;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ inbound (client thread)

    static void applyOnClientThread(Runnable action) {
        Minecraft.getInstance().execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                LOGGER.debug("[Bridge] inbound failed: {}", t.toString());
            }
        });
    }

    /** Inbound facts are per-run facts: only while this client is itself in a real run with Interop on. */
    private static boolean acceptInbound() {
        return BridgeConfig.getInstance().isEnabled() && InteropConfig.getInstance().isEnabled()
                && DungeonState.isInDungeon() && !DungeonState.isSimOverrideActive();
    }

    static void applyDevonian(DevonianCodec.Message msg) {
        if (!acceptInbound()) {
            return;
        }
        if (msg instanceof DevonianCodec.RoomSecrets rs) {
            String name = resolveDevonianRoom(rs.devonianRoomId());
            BridgeTables.Room known = BridgeTables.room(name);
            if (known == null || rs.total() != known.secrets()) {
                return; // unmapped id, ambiguous Blaze, or a total that disagrees with the room database
            }
            PartyInteropState.offerRoomSecrets(name, rs.found(), rs.total(), InteropSource.SOCKET, "Devonian");
        } else if (msg instanceof DevonianCodec.SecretTracker st) {
            String teammate = teammate(st.ign());
            if (teammate != null) {
                PartyRoomIntel.offerPlayerSecrets(teammate, st.secrets());
            }
        }
    }

    /** Devonian id -> our room name; for the one id that covers two of ours (Blaze), whichever of the two our
     *  own map scan found this run - or null if neither/both (never a guess). */
    private static String resolveDevonianRoom(int devonianId) {
        List<BridgeTables.Room> rooms = BridgeTables.roomsForDevonianId(devonianId);
        if (rooms.size() == 1) {
            return rooms.get(0).name();
        }
        if (rooms.isEmpty()) {
            return null;
        }
        Set<String> onMap = new HashSet<>();
        DungeonLayout layout = DungeonLayout.current();
        for (int i = 0; i < layout.roomCount(); i++) {
            onMap.add(layout.name(i));
        }
        String match = null;
        for (BridgeTables.Room room : rooms) {
            if (onMap.contains(room.name())) {
                if (match != null) {
                    return null;
                }
                match = room.name();
            }
        }
        return match;
    }

    static void applyNoamm(NoammCodec.Message msg) {
        if (!acceptInbound()) {
            return;
        }
        String who = "NoammAddons";
        if (msg instanceof NoammCodec.Door d) {
            PartyRoomIntel.offerDoor(d.x(), d.z(), d.col(), d.row(), d.ourType(), who);
        } else if (msg instanceof NoammCodec.Room r) {
            PartyRoomIntel.offerRoom(r.name(), r.x(), r.z(), r.col(), r.row(), who);
        } else if (msg instanceof NoammCodec.Flag f) {
            PartyInteropState.Flag flag = switch (f.which()) {
                case "mimic" -> PartyInteropState.Flag.MIMIC_KILLED;
                case "prince" -> PartyInteropState.Flag.PRINCE_KILLED;
                case "bat" -> PartyInteropState.Flag.BAT_KILLED;
                default -> null;
            };
            if (flag != null) {
                PartyInteropState.offerFlag(flag, InteropSource.SOCKET, who);
            }
        } else if (msg instanceof NoammCodec.Dragon dr) {
            // M7 P5 only; DEATH has no slot in PartyInteropState, so only spawns are recorded.
            if (dr.spawn() && "M7".equals(DungeonState.getFloor())) {
                PartyInteropState.offerDragonSpawn(dr.ourColour(), InteropSource.SOCKET, who);
            }
        } else if (msg instanceof NoammCodec.RoomSecrets rs) {
            BridgeTables.Room known = BridgeTables.room(rs.room());
            if (known != null) {
                PartyInteropState.offerRoomSecrets(rs.room(), rs.secrets(), known.secrets(), InteropSource.SOCKET, who);
            }
        }
    }

    static void applyOdin(OdinCodec.Update update) {
        if (!acceptInbound() || !DungeonState.isF7OrM7()) {
            return;
        }
        // Odin has no authentication (spec 3.2): only names our own party list vouches for, never ourselves.
        String teammate = teammate(update.ign());
        if (teammate != null) {
            MelodyIntel.offer(teammate, update.type(), update.slot());
        }
    }

    /** @return the party list's spelling of this name if it is a current teammate (not us), else null. */
    private static String teammate(String ign) {
        if (ign == null) {
            return null;
        }
        for (String name : PartyTracker.teammates()) {
            if (name.equalsIgnoreCase(ign)) {
                return name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ JSON helpers for SelfFact payloads

    /** Case-preserving (unlike {@code PartyDataProtocol}'s reader, which lower-cases). */
    static String str(JsonObject o, String field) {
        JsonElement el = o == null ? null : o.get(field);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? el.getAsString() : null;
    }

    static Integer integer(JsonObject o, String field) {
        JsonElement el = o == null ? null : o.get(field);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            double d = el.getAsDouble();
            return d == Math.rint(d) && Math.abs(d) <= 1_000_000 ? (int) d : null;
        } catch (Exception e) {
            return null;
        }
    }
}
